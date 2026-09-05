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
import com.rehman.ahmedreactionstudio.camera.TorchController
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
 * Flashlight: the real LED is driven by [TorchController]
 * (`CameraManager.setTorchMode`), Android's proper torch mechanism, so the
 * torch keeps burning across session rebuilds (recording start/stop, facing
 * switch, preview restart) and is switched off on every exit path.
 * The torch state is remembered PER FACING (frontTorch / backTorch) while the
 * camera runs, and each side is only lit when the user explicitly switched it
 * on — switching cameras clears the side we leave, so a rear LED can never be
 * left burning by accident. The capture-request `FLASH_MODE_TORCH` path is only
 * used as a fallback on devices whose framework refuses torch mode for the open
 * camera. A side with no LED is never faked: the editor's screen light is the
 * documented fallback and the UI says so.
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

    /** the ONLY object allowed to touch a real LED: CameraManager.setTorchMode */
    private val torchCtl = TorchController(ctx)

    @Volatile private var facing = CameraCharacteristics.LENS_FACING_FRONT
    @Volatile private var mirror = true
    /** torch wanted for front camera (persists across switches) */
    @Volatile private var frontTorch = false
    /** torch wanted for back camera (persists across switches) */
    @Volatile private var backTorch = false
    /**
     * "Both flashes" is an explicit user choice: only then do we keep the LED
     * of the idle camera burning. Default off, so switching cameras can never
     * leave the rear LED on behind a front preview.
     */
    @Volatile private var bothTorches = false
    /**
     * True when setTorchMode was refused by the framework for an open camera
     * that does report a flash unit — we then drive the LED through the
     * capture request (FLASH_MODE_TORCH) while a session exists.
     */
    @Volatile private var torchViaRequest = false
    /** last torch failure reported to the UI ("" = none) */
    @Volatile private var lastTorchError = ""
    /** hardware torch (LED) state of currently open camera; re-applied after every session rebuild */
    var torch: Boolean
        get() = if (isFront()) frontTorch else backTorch
        private set(v) { if (isFront()) frontTorch = v else backTorch = v }
    /** whether the CURRENTLY open camera has an LED at all */
    @Volatile var hasFlashUnit = false
        private set
    /** whether front camera has flash (cached without opening) */
    @Volatile var frontHasFlash = false
        private set
    /** whether back camera has flash (cached without opening) */
    @Volatile var backHasFlash = false
        private set
    @Volatile var recording = false
        private set
    @Volatile private var opening = false
    @Volatile var running = false
        private set

    private var sensorOrientation = 90
    private var feedSize = WANT
    /**
     * Size for the MediaRecorder surface. Camera2 only accepts sizes from
     * `getOutputSizes(MediaRecorder.class)` — feeding it the ImageReader
     * (YUV) size made createCaptureSession fail on devices whose two size
     * tables differ, which surfaced as "camera busy" and a fallback loop.
     */
    private var recordSize: Size? = null
    private var lastFrameAt = 0L
    private var activeCameraId: String? = null

    /**
     * Triple-buffered output bitmaps (dimensions after rotation).
     *
     * Two buffers is not enough: the compositor (and the recorder) may still
     * be reading the published frame while convert() wraps around and writes
     * it again. That race produced torn / black camera frames in the export
     * even though the live preview looked fine. Three slots: never write the
     * bitmap currently published to PreviewEngine.
     *
     * Size change is the edge case: when the display rotates (feed goes from
     * portrait to landscape — picking 16:9 auto-rotates the studio) the output
     * dimensions swap. Reusing a buffer of the OLD size and calling setPixels
     * with a different count throws IllegalStateException ("w x h does not
     * match pixels") straight off the camera callback — an uncaught crash the
     * moment rotation changed the feed. Old-size buffers are dropped.
     */
    private var bufA: Bitmap? = null
    private var bufB: Bitmap? = null
    private var bufC: Bitmap? = null
    private var writeSlot = 0
    @Volatile private var published: Bitmap? = null
    private var argb: IntArray = IntArray(0)
    private var lastOutW = 0
    private var lastOutH = 0
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

    /** true when the torch is wanted for the given facing (persisted) */
    fun isTorchOnForFront(): Boolean = frontTorch
    fun isTorchOnForBack(): Boolean = backTorch
    fun hasFlashForFront(): Boolean = frontHasFlash
    fun hasFlashForBack(): Boolean = backHasFlash
    /** true only when the LED of that facing is actually burning right now */
    fun isTorchLitForFront(): Boolean = torchCtl.isTorchOn(true)
    fun isTorchLitForBack(): Boolean = torchCtl.isTorchOn(false)

    /** "Both flashes" — the only mode that keeps the idle camera's LED on. */
    fun bothTorchesMode(): Boolean = bothTorches

    /**
     * Turn both LEDs on/off together. Both are driven with
     * `CameraManager.setTorchMode`, so this works even though only one camera
     * can be open for preview at a time.
     */
    fun setBothTorches(on: Boolean): Boolean {
        refreshFlashCache()
        var changed = false
        if (frontHasFlash) { frontTorch = on; changed = true }
        if (backHasFlash) { backTorch = on; changed = true }
        if (!changed) return false
        bothTorches = on
        handler?.post { applyTorch() }
        return true
    }

    fun bothTorchesOn(): Boolean = (frontHasFlash && frontTorch) || (backHasFlash && backTorch)
    fun bothTorchesFullyOn(): Boolean = bothTorches && (frontHasFlash || backHasFlash) &&
        (!frontHasFlash || frontTorch) && (!backHasFlash || backTorch)

    /**
     * Turn the LED torch on/off for the camera that is open right now.
     * Also remembers per-facing so switching preserves the choice.
     * Returns false when this camera has no flash unit or the OS refused.
     */
    fun setTorch(on: Boolean): Boolean {
        if (on && !hasFlashUnit) return false
        if (isFront()) frontTorch = on else backTorch = on
        if (!on) bothTorches = false
        handler?.post { applyTorch() }
        return true
    }

    /** Set torch for a specific facing (even when that camera is not open). */
    fun setTorchFor(front: Boolean, on: Boolean): Boolean {
        refreshFlashCache()
        val has = if (front) frontHasFlash else backHasFlash
        if (on && !has) return false
        if (front) frontTorch = on else backTorch = on
        if (!on) bothTorches = false
        handler?.post { applyTorch() }
        return true
    }

    fun toggleTorch(): Boolean = setTorch(!torch)
    fun toggleFrontTorch(): Boolean = setTorchFor(true, !frontTorch)
    fun toggleBackTorch(): Boolean = setTorchFor(false, !backTorch)

    /** Does the camera facing [front] have an LED? Answers without opening it. */
    fun flashAvailable(front: Boolean): Boolean = torchCtl.hasFlash(front)

    /**
     * Why the last torch toggle failed ("" when it worked). Lets the UI say
     * "camera in use by another app" instead of a generic no-flash message.
     */
    fun torchLastError(): String = if (torchCtl.lastFail == TorchController.Fail.NONE) "" else torchCtl.failureText()

    fun setMirror(m: Boolean) { mirror = m }

    private fun cm(): CameraManager =
        ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /**
     * Re-read which sides own a real LED. TorchController filters on
     * LENS_FACING + FLASH_INFO_AVAILABLE, so a front camera without a physical
     * LED reports false and the UI keeps offering the screen light instead of
     * pretending a hardware torch exists.
     */
    private fun refreshFlashCache() {
        torchCtl.start()
        torchCtl.refresh()
        frontHasFlash = torchCtl.hasFlash(true)
        backHasFlash = torchCtl.hasFlash(false)
        hasFlashUnit = if (isFront()) frontHasFlash else backHasFlash
    }

    /**
     * Apply the wanted torch state to the hardware. Runs on the camera thread.
     *
     *  - every side is driven with CameraManager.setTorchMode() (no camera
     *    device needs to be open, so the LED survives session rebuilds);
     *  - the LED of a side is only ON when the user switched it on for that
     *    side; switching cameras clears the side we leave, so a rear LED is
     *    never left burning by accident;
     *  - if the framework refuses torch mode for the OPEN camera and that
     *    camera does report a flash unit, the LED is driven through
     *    FLASH_MODE_TORCH on the live capture request instead.
     */
    private fun applyTorch() {
        // Both sides are driven from the user's intent: setTorchMode() works
        // without opening a camera, so an explicitly enabled rear LED keeps
        // burning behind a front preview (and vice versa) — that is the whole
        // point of the Front / Back / Both ring. It is never a silent
        // carry-over: switchFacing() clears the side we are leaving.
        applySide(isFront())
        applySide(!isFront())
        repeatRequest()
    }

    /** Drive one side's LED; the active side may fall back to the request. */
    private fun applySide(front: Boolean) {
        val want = if (front) frontTorch else backTorch
        val active = front == isFront()
        if (!want) {
            torchCtl.setTorch(front, false)
            if (active) torchViaRequest = false
            noteTorchError("")
            return
        }
        val ok = torchCtl.setTorch(front, true)
        val has = if (front) frontHasFlash else backHasFlash
        // fall back to the capture-request torch only for the side we have a
        // session for, and only when it really reports a flash unit
        if (active) torchViaRequest = !ok && has
        when {
            ok -> noteTorchError("")
            !ok && has && active -> noteTorchError("")
            else -> {
                noteTorchError(torchCtl.failureText())
                // never claim a torch we could not actually switch on
                if (front) frontTorch = false else backTorch = false
            }
        }
    }

    /**
     * Report a torch failure once (not on every session rebuild) so the UI can
     * say "camera in use by another app" instead of silently doing nothing.
     */
    private fun noteTorchError(msg: String) {
        if (msg == lastTorchError) return
        lastTorchError = msg
        if (msg.isNotEmpty()) onState("torcherror")
    }

    /** Turn every LED off and forget the wanted state (stop/error/switch away). */
    private fun torchOff() {
        torchCtl.releaseAll()
        frontTorch = false
        backTorch = false
        bothTorches = false
        torchViaRequest = false
    }

    // ---------------- lifecycle ----------------

    fun start(front: Boolean) {
        if (!hasPermission()) { onState("permission"); return }
        facing = if (front) CameraCharacteristics.LENS_FACING_FRONT
        else CameraCharacteristics.LENS_FACING_BACK
        refreshFlashCache()
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
        val wasFront = isFront()
        // The LED we are leaving must not stay on: unless the user explicitly
        // asked for "both flashes", switching cameras turns the outgoing torch
        // off, so a rear LED can never burn behind a front preview.
        if (!bothTorches) {
            torchCtl.setTorch(wasFront, false)
            if (wasFront) frontTorch = false else backTorch = false
        }
        val front = !wasFront
        facing = if (front) CameraCharacteristics.LENS_FACING_FRONT
        else CameraCharacteristics.LENS_FACING_BACK
        mirror = front
        handler?.post { closeCameraOnly(); openLocked() }
    }

    fun stop() {
        running = false
        val h = handler
        if (h == null) { releaseAll(); shutdownTorch(); return }
        h.post { releaseAll(); shutdownTorch() }
        thread?.quitSafely()
        thread = null
        handler = null
    }

    /** Last thing we ever do with the LEDs: everything off, callback unhooked. */
    private fun shutdownTorch() {
        try { torchCtl.shutdown() } catch (_: Exception) { }
    }

    private fun releaseAll() {
        // never walk away leaving the LED burning — every id, both facings
        torchOff()
        try { repeatRequest() } catch (_: Exception) { }
        stopRecordingLocked(discard = true)
        closeCameraOnly()
        bufA = null; bufB = null; bufC = null
        published = null
        argb = IntArray(0)
    }

    private fun closeCameraOnly() {
        try { session?.close() } catch (_: Exception) { }
        session = null
        try { device?.close() } catch (_: Exception) { }
        device = null
        activeCameraId = null
        try { reader?.close() } catch (_: Exception) { }
        reader = null
        opening = false
        // The torch is independent of the camera device, so closing the device
        // has to switch the LED off explicitly; re-opening re-applies the state
        // the user asked for (see applyTorch).
        try { torchCtl.releaseAll() } catch (_: Exception) { }
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
        activeCameraId = id
        opening = true
        try {
            val ch = cm().getCameraCharacteristics(id)
            sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            refreshFlashCache()
            // FLASH_INFO_AVAILABLE of the camera we actually opened
            hasFlashUnit = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)
            feedSize = sizes?.filter { it.width <= 1280 && it.height <= 1280 }
                ?.minByOrNull {
                    Math.abs(it.width * it.height - WANT.width * WANT.height)
                } ?: WANT
            // Recorder sizes are a SEPARATE camera2 table: pick the closest
            // ≤1080p MP4 size the device actually supports for MediaRecorder.
            recordSize = map?.getOutputSizes(MediaRecorder::class.java)
                ?.filter { it.width <= 1920 && it.height <= 1920 }
                ?.minByOrNull {
                    Math.abs(it.width * it.height - 1280 * 720)
                }

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
                    // light the LED through CameraManager even before/without a
                    // session — that is the whole point of setTorchMode
                    try { applyTorch() } catch (_: Exception) { }
                }
                override fun onDisconnected(d: CameraDevice) {
                    opening = false
                    // camera gone: never leave its LED burning
                    torchOff()
                    try { d.close() } catch (_: Exception) { }
                    device = null
                    activeCameraId = null
                    onState("disconnected")
                }
                override fun onError(d: CameraDevice, err: Int) {
                    opening = false
                    torchOff()
                    try { d.close() } catch (_: Exception) { }
                    device = null
                    activeCameraId = null
                    onState("error")
                }
            }, handler)
        } catch (e: Exception) {
            opening = false
            activeCameraId = null
            torchOff()
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
                    // re-apply the LED state after every session rebuild
                    // (recording start/stop, facing switch, error recovery)
                    applyTorch()
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
            val wantTorch = if (isFront()) frontTorch else backTorch
            // The LED is driven by CameraManager.setTorchMode(). FLASH_MODE_TORCH
            // is only the fallback for devices that refuse torch mode while a
            // session is open — mixing both is what made the "torch" flicker or
            // die on session rebuilds.
            val useRequestTorch = wantTorch && hasFlashUnit && torchViaRequest
            b.set(
                CaptureRequest.FLASH_MODE,
                if (useRequestTorch) CaptureRequest.FLASH_MODE_TORCH
                else CaptureRequest.FLASH_MODE_OFF
            )
            if (useRequestTorch) {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
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
        val rs = recordSize ?: feedSize
        val r = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx)
        else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            if (withMic) r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setOutputFile(f.absolutePath)
            r.setVideoEncodingBitRate(6_000_000)
            r.setVideoFrameRate(30)
            r.setVideoSize(rs.width, rs.height)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (withMic) {
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(128_000)
                r.setAudioSamplingRate(44_100)
            }
            // Standard camera2 orientation hint: (sensorOrientation -
            // deviceRotation + 360) % 360 for the back camera, plus 180° for
            // the front (mirrored). Using the raw sensor value ignored the
            // device rotation, so takes shot with the studio in landscape
            // (16:9 canvas auto-rotates) came out sideways.
            val deviceRot = try {
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                when (wm.defaultDisplay.rotation) {
                    Surface.ROTATION_90 -> 90
                    Surface.ROTATION_180 -> 180
                    Surface.ROTATION_270 -> 270
                    else -> 0
                }
            } catch (_: Exception) { 0 }
            val rot = if (isFront()) (sensorOrientation + deviceRot + 180) % 360
            else (sensorOrientation - deviceRot + 360) % 360
            r.setOrientationHint(rot)
            r.prepare()
        } catch (e: Exception) {
            try { r.release() } catch (_: Exception) { }
            onState("recfail")
            return false
        }
        recorder = r
        recordFile = f
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
        // a finished take must not leave the rear LED burning
        torchOff()
        var ok = true
        try { r.stop() } catch (_: Exception) { ok = false }
        try { r.release() } catch (_: Exception) { }
        recorder = null
        val f = recordFile
        recordFile = null
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

        val avoid = published
        val slots = arrayOf(bufA, bufB, bufC)
        var bmp: Bitmap? = null
        for (k in 0..2) {
            val i = (writeSlot + k) % 3
            val cur = slots[i]
            if (cur !== avoid && cur != null && !cur.isRecycled &&
                cur.width == ow && cur.height == oh) {
                bmp = cur
                writeSlot = (i + 1) % 3
                break
            }
        }
        if (bmp == null) {
            val fresh = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            when {
                bufA == null || bufA === avoid || bufA?.isRecycled == true -> bufA = fresh
                bufB == null || bufB === avoid || bufB?.isRecycled == true -> bufB = fresh
                else -> bufC = fresh
            }
            bmp = fresh
        }
        val outBmp = bmp ?: return null
        outBmp.setPixels(out, 0, ow, 0, 0, ow, oh)
        published = outBmp
        outW = ow; outH = oh
        return outBmp
    }

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
