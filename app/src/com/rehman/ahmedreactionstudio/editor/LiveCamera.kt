package com.rehman.ahmedreactionstudio.editor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import java.io.File

/**
 * LIVE CAMERA AS A CANVAS SOURCE.
 *
 * The old flow launched a fullscreen CameraActivity — the canvas vanished, you
 * framed your reaction blind, and only a finished clip came back. Here the
 * camera is just another source: Camera2 feeds an [ImageReader], every frame is
 * converted to an ARGB [Bitmap] and pushed into the PreviewEngine, so the
 * shared Compositor draws it with the same z-order / fit / opacity / transform
 * as a video layer. Drag, resize, rotate and snap all work on it.
 *
 * Implementation notes that matter on real devices:
 *  - YUV_420_888 → ARGB is done inline with integer math and the rotation +
 *    front-camera mirror folded into the destination index, so there is no
 *    per-frame Matrix/createBitmap allocation (that path GC-thrashed at 30 fps);
 *  - two reusable bitmaps are swapped so the compositor never reads the buffer
 *    being written;
 *  - frames are throttled to ~24 fps — the canvas cannot show more and the
 *    conversion is the expensive part;
 *  - everything camera-related runs on one background handler, and the
 *    open/close state machine mirrors CameraActivity's (which fixed the
 *    double-session crashes).
 */
class LiveCamera(
    private val ctx: Context,
    private val onFrame: (Bitmap) -> Unit,
    private val onState: (String) -> Unit
) {

    companion object {
        private const val TARGET_FPS_MS = 42L      // ~24 fps
        /** preview feed size — small enough to convert cheaply, big enough for a PiP */
        private val WANT = Size(960, 540)
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null

    @Volatile private var facing = CameraCharacteristics.LENS_FACING_FRONT
    @Volatile private var mirror = true
    @Volatile var recording = false
        private set
    @Volatile private var opening = false
    @Volatile var running = false
        private set

    private var sensorOrientation = 90
    private var feedSize = WANT
    private var lastFrameAt = 0L

    /** double-buffered output bitmaps (dimensions after rotation) */
    private var bufA: Bitmap? = null
    private var bufB: Bitmap? = null
    private var useA = true
    private var argb: IntArray = IntArray(0)
    private var rowY: ByteArray = ByteArray(0)
    private var rowU: ByteArray = ByteArray(0)
    private var rowV: ByteArray = ByteArray(0)

    /** decoded feed size as it appears on the canvas (rotation applied) */
    @Volatile var outW = 0
        private set
    @Volatile var outH = 0
        private set

    fun hasPermission(): Boolean =
        ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun isFront(): Boolean = facing == CameraCharacteristics.LENS_FACING_FRONT

    fun setMirror(m: Boolean) { mirror = m }

    private fun cm(): CameraManager =
        ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    // ---------------- lifecycle ----------------

    fun start(front: Boolean) {
        if (!hasPermission()) { onState("permission"); return }
        facing = if (front) CameraCharacteristics.LENS_FACING_FRONT
        else CameraCharacteristics.LENS_FACING_BACK
        if (thread == null) {
            val t = HandlerThread("live-cam")
            t.start()
            thread = t
            handler = Handler(t.looper)
        }
        running = true
        handler?.post { openLocked() }
    }

    fun switchFacing() {
        if (recording) { onState("busy"); return }
        val front = !isFront()
        facing = if (front) CameraCharacteristics.LENS_FACING_FRONT
        else CameraCharacteristics.LENS_FACING_BACK
        mirror = front
        handler?.post { closeCameraOnly(); openLocked() }
    }

    fun stop() {
        running = false
        val h = handler
        if (h == null) { releaseAll(); return }
        h.post { releaseAll() }
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun releaseAll() {
        stopRecordingLocked(discard = true)
        closeCameraOnly()
        bufA = null; bufB = null
        argb = IntArray(0)
    }

    private fun closeCameraOnly() {
        try { session?.close() } catch (_: Exception) { }
        session = null
        try { device?.close() } catch (_: Exception) { }
        device = null
        try { reader?.close() } catch (_: Exception) { }
        reader = null
        opening = false
    }

    private fun pickCameraId(): String? {
        val m = cm()
        return try {
            val ids = m.cameraIdList
            ids.firstOrNull {
                m.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing
            } ?: ids.firstOrNull()
        } catch (_: Exception) { null }
    }

    private fun openLocked() {
        if (opening || device != null || !running) return
        val id = pickCameraId()
        if (id == null) { onState("nocamera"); return }
        opening = true
        try {
            val ch = cm().getCameraCharacteristics(id)
            sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)
            feedSize = sizes?.filter { it.width <= 1280 && it.height <= 1280 }
                ?.minByOrNull {
                    Math.abs(it.width * it.height - WANT.width * WANT.height)
                } ?: WANT

            val r = ImageReader.newInstance(feedSize.width, feedSize.height,
                ImageFormat.YUV_420_888, 3)
            r.setOnImageAvailableListener({ rd ->
                val img = try { rd.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
                try {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastFrameAt >= TARGET_FPS_MS) {
                        lastFrameAt = now
                        convert(img)?.let { onFrame(it) }
                    }
                } catch (_: Exception) {
                } finally {
                    try { img.close() } catch (_: Exception) { }
                }
            }, handler)
            reader = r

            @Suppress("MissingPermission")
            cm().openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    device = d
                    opening = false
                    createSession()
                }
                override fun onDisconnected(d: CameraDevice) {
                    opening = false
                    try { d.close() } catch (_: Exception) { }
                    device = null
                    onState("disconnected")
                }
                override fun onError(d: CameraDevice, err: Int) {
                    opening = false
                    try { d.close() } catch (_: Exception) { }
                    device = null
                    onState("error")
                }
            }, handler)
        } catch (e: Exception) {
            opening = false
            onState("error")
        }
    }

    private fun createSession() {
        val d = device ?: return
        val r = reader ?: return
        val targets = ArrayList<Surface>()
        targets.add(r.surface)
        recorder?.surface?.let { targets.add(it) }
        try {
            @Suppress("DEPRECATION")
            d.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    repeatRequest()
                    onState(if (recording) "recording" else "live")
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    onState("error")
                }
            }, handler)
        } catch (e: Exception) {
            onState("error")
        }
    }

    private fun repeatRequest() {
        val d = device ?: return
        val s = session ?: return
        val r = reader ?: return
        try {
            val b = d.createCaptureRequest(
                if (recording) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(r.surface)
            if (recording) recorder?.surface?.let { b.addTarget(it) }
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            s.setRepeatingRequest(b.build(), null, handler)
        } catch (_: Exception) { }
    }

    // ---------------- recording the live feed to a clip ----------------

    /** Start recording; [onDone] gets the finished file (or null) on the cam thread. */
    fun startRecording(dir: File, onStarted: (Boolean) -> Unit) {
        val h = handler ?: return onStarted(false)
        h.post { onStarted(startRecordingLocked(dir)) }
    }

    private fun startRecordingLocked(dir: File): Boolean {
        if (recording) return false
        val d = device ?: return false
        dir.mkdirs()
        val f = File(dir, "cam_${System.currentTimeMillis()}.mp4")
        val withMic = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val r = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx)
        else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            if (withMic) r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setOutputFile(f.absolutePath)
            r.setVideoEncodingBitRate(6_000_000)
            r.setVideoFrameRate(30)
            r.setVideoSize(feedSize.width, feedSize.height)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (withMic) {
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(128_000)
                r.setAudioSamplingRate(44_100)
            }
            val rot = if (isFront()) (sensorOrientation + 180) % 360 else sensorOrientation
            r.setOrientationHint(rot)
            r.prepare()
        } catch (e: Exception) {
            try { r.release() } catch (_: Exception) { }
            onState("recfail")
            return false
        }
        recorder = r
        recordFile = f
        // rebuild the session with the recorder surface attached
        try { session?.close() } catch (_: Exception) { }
        session = null
        recording = true
        createSession()
        return try {
            r.start()
            onState("recording")
            true
        } catch (e: Exception) {
            recording = false
            try { r.release() } catch (_: Exception) { }
            recorder = null
            recordFile = null
            try { session?.close() } catch (_: Exception) { }
            session = null
            createSession()
            onState("recfail")
            false
        }
    }

    fun stopRecording(onDone: (File?) -> Unit) {
        val h = handler ?: return onDone(null)
        h.post {
            val f = stopRecordingLocked(discard = false)
            onDone(f)
        }
    }

    private fun stopRecordingLocked(discard: Boolean): File? {
        val r = recorder ?: return null
        recording = false
        var ok = true
        try { r.stop() } catch (_: Exception) { ok = false }
        try { r.release() } catch (_: Exception) { }
        recorder = null
        val f = recordFile
        recordFile = null
        // back to a preview-only session
        try { session?.close() } catch (_: Exception) { }
        session = null
        if (device != null && running) createSession()
        if (discard || !ok || f == null || !f.exists() || f.length() < 40_000) {
            try { f?.delete() } catch (_: Exception) { }
            return null
        }
        return f
    }

    // ---------------- YUV_420_888 → ARGB with rotation + mirror ----------------

    /**
     * Converts [img] straight into a reusable bitmap. The display rotation
     * (sensor orientation, plus 180° flip logic for the front camera) and the
     * mirror are applied by computing the DESTINATION index, so there is no
     * second pass and no Matrix allocation.
     */
    private fun convert(img: Image): Bitmap? {
        if (img.format != ImageFormat.YUV_420_888) return null
        val w = img.width
        val h = img.height
        val planes = img.planes
        if (planes.size < 3) return null

        val rot = displayRotation()
        val swap = rot == 90 || rot == 270
        val ow = if (swap) h else w
        val oh = if (swap) w else h

        if (argb.size != w * h) argb = IntArray(w * h)
        val out = argb

        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixStride = uPlane.pixelStride

        if (rowY.size != yRowStride) rowY = ByteArray(yRowStride)
        if (rowU.size != uvRowStride) rowU = ByteArray(uvRowStride)
        if (rowV.size != uvRowStride) rowV = ByteArray(uvRowStride)

        val doMirror = mirror && isFront()

        for (j in 0 until h) {
            yBuf.position(j * yRowStride)
            val yLen = minOf(yRowStride, yBuf.remaining())
            yBuf.get(rowY, 0, yLen)
            val uvRow = j / 2
            if (j % 2 == 0) {
                val uPos = uvRow * uvRowStride
                if (uPos < uBuf.limit()) {
                    uBuf.position(uPos)
                    uBuf.get(rowU, 0, minOf(uvRowStride, uBuf.remaining()))
                }
                if (uPos < vBuf.limit()) {
                    vBuf.position(uPos)
                    vBuf.get(rowV, 0, minOf(uvRowStride, vBuf.remaining()))
                }
            }
            for (i in 0 until w) {
                val y = (rowY[i].toInt() and 0xFF) - 16
                val uvIdx = (i / 2) * uvPixStride
                val u = (rowU[uvIdx].toInt() and 0xFF) - 128
                val v = (rowV[uvIdx].toInt() and 0xFF) - 128
                val y1192 = 1192 * y
                var r = (y1192 + 1634 * v) shr 10
                var g = (y1192 - 833 * v - 400 * u) shr 10
                var b = (y1192 + 2066 * u) shr 10
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255
                val color = -0x1000000 or (r shl 16) or (g shl 8) or b

                // destination coordinates after rotation + optional mirror
                var dx: Int
                var dy: Int
                when (rot) {
                    90 -> { dx = h - 1 - j; dy = i }
                    180 -> { dx = w - 1 - i; dy = h - 1 - j }
                    270 -> { dx = j; dy = w - 1 - i }
                    else -> { dx = i; dy = j }
                }
                if (doMirror) dx = ow - 1 - dx
                out[dy * ow + dx] = color
            }
        }

        val cur = if (useA) bufA else bufB
        val bmp: Bitmap = if (cur == null || cur.width != ow || cur.height != oh || cur.isRecycled) {
            val fresh = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            if (useA) bufA = fresh else bufB = fresh
            fresh
        } else cur
        bmp.setPixels(out, 0, ow, 0, 0, ow, oh)
        useA = !useA
        outW = ow; outH = oh
        return bmp
    }

    /**
     * How far the sensor image must rotate to look upright on the canvas.
     * The editor canvas follows the project aspect (landscape for 16:9,
     * portrait for 9:16), and the activity is locked to that orientation, so
     * the device rotation term is constant per project and the sensor
     * orientation carries the correction.
     */
    private fun displayRotation(): Int {
        val deviceRot = try {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            when (wm.defaultDisplay.rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
        } catch (_: Exception) { 0 }
        return if (isFront()) (sensorOrientation + deviceRot + 360) % 360
        else (sensorOrientation - deviceRot + 360) % 360
    }
}
