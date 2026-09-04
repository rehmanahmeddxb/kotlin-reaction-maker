package com.rehman.ahmedreactionstudio.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.rehman.ahmedreactionstudio.core.MediaKit
import com.rehman.ahmedreactionstudio.core.ProjectStore
import com.rehman.ahmedreactionstudio.util.UI
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Real camera hardware (Camera2): front/back preview, rear hardware torch,
 * switch, pinch-less zoom slider and MP4 recording straight into the project
 * media folder so the take is immediately usable as a PiP reaction layer.
 */
class CameraActivity : Activity() {

    companion object {
        const val EXTRA_PROJECT_ID = "pid"
        const val EXTRA_RESULT_REL = "rel"
        private const val REQ_PERMS = 100
    }

    private var projectId: String = ""
    private var facing = CameraCharacteristics.LENS_FACING_FRONT
    private var cameraId: String? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var repeatRequest: CaptureRequest? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var previewSize = android.util.Size(1280, 720)
    private var sensorOrientation = 90
    private var torchOn = false
    private var torchSupported = false

    private lateinit var store: ProjectStore
    private lateinit var texture: TextureView
    private lateinit var switchBtn: TextView
    private lateinit var torchBtn: TextView
    private lateinit var recordBtn: TextView
    private lateinit var timerLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var zoomSlider: SeekBar
    private var recorder: MediaRecorder? = null
    private var recording = false
    private var recordFile: File? = null
    private var recordStart = 0L
    private val timerHandler = Handler(android.os.Looper.getMainLooper())
    private val timerTick = object : Runnable {
        override fun run() {
            if (recording) {
                val s = (SystemClock.elapsedRealtime() - recordStart) / 1000
                timerLabel.text = String.format("%02d:%02d", s / 60, s % 60)
                timerHandler.postDelayed(this, 250)
            }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        UI.styleWindow(this)
        projectId = intent.getStringExtra(EXTRA_PROJECT_ID) ?: run { finish(); return }
        store = ProjectStore(this)
        buildUi()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), REQ_PERMS)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (code == REQ_PERMS) {
            if (res.isEmpty() || res.any { it != PackageManager.PERMISSION_GRANTED }) {
                UI.toast(this, "Camera & microphone permission needed to record reaction takes.")
                finish()
            }
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        texture = TextureView(this)
        root.addView(texture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT))
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { openCamera() }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                updatePreviewTransform()
            }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) { }
        }

        // controls
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.VERTICAL
        bottom.gravity = Gravity.CENTER_HORIZONTAL
        bottom.setPadding(0, UI.dp(this, 18), 0, UI.dp(this, 22))
        bottom.setBackgroundColor(Color.argb(120, 0, 0, 0))
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.setPadding(UI.dp(this, 10), UI.dp(this, 10), UI.dp(this, 10), 0)
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val back = UI.chip(this, "\u2039 Back")
        back.setOnClickListener { finish() }
        top.addView(back)

        statusLabel = UI.label(this, "", dim = false, size = 12f)
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.gravity = Gravity.CENTER_VERTICAL
        statusLabel.layoutParams = lp
        statusLabel.gravity = Gravity.CENTER
        top.addView(statusLabel)
        statusLabel.setTextColor(Color.WHITE)

        timerLabel = TextView(this)
        timerLabel.text = "00:00"
        timerLabel.setTextColor(Color.WHITE)
        timerLabel.textSize = 22f
        timerLabel.typeface = Typeface.create("monospace", Typeface.BOLD)
        bottom.addView(timerLabel)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        bottom.addView(row)

        switchBtn = UI.chip(this, "\u21C4 Switch")
        row.addView(switchBtn)
        switchBtn.setOnClickListener { toggleCamera() }
        UI.margin(switchBtn, 0, 0, 8, 0, this)

        torchBtn = UI.chip(this, "\uD83D\uDD26 Torch")
        torchBtn.setOnClickListener { toggleTorch() }
        row.addView(torchBtn)
        UI.margin(torchBtn, 0, 0, 8, 0, this)

        recordBtn = TextView(this)
        recordBtn.text = "\u25CF"
        recordBtn.gravity = Gravity.CENTER
        recordBtn.setTextColor(Color.WHITE)
        recordBtn.textSize = 26f
        val rg = android.graphics.drawable.GradientDrawable()
        rg.cornerRadius = UI.dpf(this, 34f)
        rg.setColor(0xFFE53935.toInt())
        recordBtn.background = rg
        recordBtn.layoutParams = LinearLayout.LayoutParams(UI.dp(this, 68), UI.dp(this, 68))
        recordBtn.setOnClickListener { toggleRecord() }
        row.addView(recordBtn)
        UI.margin(recordBtn, 8, 0, 8, 0, this)

        val zoomRow = LinearLayout(this)
        zoomRow.orientation = LinearLayout.HORIZONTAL
        zoomRow.gravity = Gravity.CENTER_VERTICAL
        bottom.addView(zoomRow)
        val zl = UI.label(this, "1\u00d7", dim = true, size = 12f)
        zoomRow.addView(zl)
        zoomSlider = SeekBar(this)
        zoomSlider.max = 100
        zoomSlider.progress = 0
        zoomSlider.progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        zoomSlider.thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        zoomSlider.layoutParams = LinearLayout.LayoutParams(UI.dp(this, 220), ViewGroup.LayoutParams.WRAP_CONTENT)
        zoomSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { if (u) setZoom(v / 100f) }
            override fun onStartTrackingTouch(s: SeekBar?) { }
            override fun onStopTrackingTouch(s: SeekBar?) { }
        })
        zoomRow.addView(zoomSlider)
        val zh = UI.label(this, "\uD83D\uDD0D", dim = true, size = 13f)
        zoomRow.addView(zh)
        bottom.addView(row)
        setContentView(root)
    }

    // ---------- camera plumbing ----------

    private fun startBg() {
        bgThread = HandlerThread("cam").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)
    }

    private fun stopBg() {
        bgThread?.quitSafely()
        try { bgThread?.join() } catch (_: Exception) { }
        bgThread = null; bgHandler = null
    }

    override fun onResume() {
        super.onResume()
        startBg()
        if (texture.isAvailable) openCamera()
    }

    override fun onPause() {
        closeCamera()
        stopBg()
        super.onPause()
    }

    private fun cm(): CameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private fun cameraIdFor(lens: Int): String {
        for (id in cm().cameraIdList) {
            val ch = cm().getCameraCharacteristics(id)
            if (ch.get(CameraCharacteristics.LENS_FACING) == lens) return id
        }
        return cm().cameraIdList.firstOrNull() ?: ""
    }

    private fun openCamera() {
        val chosen = if (facing == CameraCharacteristics.LENS_FACING_BACK)
            cameraIdFor(CameraCharacteristics.LENS_FACING_BACK)
        else cameraIdFor(CameraCharacteristics.LENS_FACING_FRONT)
        if (chosen.isEmpty()) { statusLabel.text = "No camera"; return }
        cameraId = chosen
        try {
            val ch = cm().getCameraCharacteristics(chosen)
            sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            // torch support only on the rear camera w/ actual flash unit (spec 22)
            torchSupported = facing == CameraCharacteristics.LENS_FACING_BACK &&
                (ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false)
            runOnUiThread {
                torchBtn.text = if (torchSupported) (if (torchOn) "\uD83D\uDD26 Torch ON" else "\uD83D\uDD26 Torch")
                else (if (facing == CameraCharacteristics.LENS_FACING_FRONT) "No flash (front)" else "No flash unit")
            }
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
            previewSize = sizes.firstOrNull { it.width == 1280 && it.height == 720 }
                ?: sizes.firstOrNull { it.width >= 640 }
                ?: android.util.Size(640, 480)
            updatePreviewTransform()
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
            cm().openCamera(chosen, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { device = camera; createSession() }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); device = null }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); device = null
                    runOnUiThread { statusLabel.text = "Camera error $error" }
                }
            }, bgHandler)
        } catch (e: Exception) {
            statusLabel.text = "Camera unavailable"
        }
    }

    /** rotate/mirror the preview so the phone UI looks right in portrait */
    private fun updatePreviewTransform() {
        val st = texture.surfaceTexture ?: return
        val vw = texture.width; val vh = texture.height
        if (vw == 0 || vh == 0) return
        val viewRatio = vw.toFloat() / vh
        val bufRatio = previewSize.width.toFloat() / previewSize.height
        val matrix = android.graphics.Matrix()
        // total rotation that aligns camera buffer with display
        val displayRot = (windowManager.defaultDisplay.rotation) // 0,1,2,3
        val extra = if (facing == CameraCharacteristics.LENS_FACING_FRONT) 180 else 0
        val degrees = (sensorOrientation + extra - displayRot * 90 + 360) % 360
        if (degrees == 90 || degrees == 270) {
            matrix.postScale(1f / viewRatio * bufRatio, viewRatio / bufRatio * 1f)
        } else {
            matrix.postScale(viewRatio / bufRatio * 1f, 1f / viewRatio * bufRatio)
        }
        matrix.postRotate(degrees.toFloat(), vw / 2f, vh / 2f)
        if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            matrix.postScale(-1f, 1f, vw / 2f, vh / 2f)
        }
        texture.setTransform(matrix)
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
    }

    private fun createSession() {
        val d = device ?: return
        val st = texture.surfaceTexture ?: return
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        val surface = Surface(st)
        try {
            d.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    val builder = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    builder.addTarget(surface)
                    if (torchOn) builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                    builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    val req = builder.build()
                    repeatRequest = req
                    try { s.setRepeatingRequest(req, null, bgHandler) } catch (_: Exception) { }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { }
            }, bgHandler)
        } catch (e: Exception) { }
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) { }
        session = null
        try { device?.close() } catch (_: Exception) { }
        device = null
    }

    private fun toggleCamera() {
        if (recording) return
        facing = if (facing == CameraCharacteristics.LENS_FACING_BACK)
            CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        torchOn = false
        closeCamera()
        openCamera()
    }

    private fun setTorch(v: Boolean) {
        torchOn = v
        val d = device ?: return
        try {
            val builder = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(Surface(texture.surfaceTexture))
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.FLASH_MODE, if (torchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            val req = builder.build()
            session?.setRepeatingRequest(req, null, bgHandler)
            repeatRequest = req
        } catch (_: Exception) { }
    }

    private fun toggleTorch() {
        if (!torchSupported) return
        setTorch(!torchOn)
        torchBtn.text = if (torchOn) "\uD83D\uDD26 Torch ON" else "\uD83D\uDD26 Torch"
    }

    private fun setZoom(z: Float) {
        val d = device ?: return
        try {
            val ch = cm().getCameraCharacteristics(cameraId ?: return)
            val max = (ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)
            val zoom = 1f + z * (max - 1f)
            val rect = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val cropW = (rect.width() / zoom).toInt()
            val cropH = (rect.height() / zoom).toInt()
            val crop = android.graphics.Rect(
                rect.centerX() - cropW / 2, rect.centerY() - cropH / 2,
                rect.centerX() + cropW / 2, rect.centerY() + cropH / 2)
            val builder = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(Surface(texture.surfaceTexture))
            builder.set(CaptureRequest.SCALER_CROP_REGION, crop)
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            if (torchOn) builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            session?.setRepeatingRequest(builder.build(), null, bgHandler)
        } catch (_: Exception) { }
    }

    // ---------- recording ----------

    private fun toggleRecord() {
        if (recording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        val d = device ?: return
        val st = texture.surfaceTexture ?: return
        val ch = try { cm().getCameraCharacteristics(cameraId!!) } catch (e: Exception) { return }
        // prefer 720p output sizes
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        var size = android.util.Size(1280, 720)
        var found = false
        for (s in map.getOutputSizes(MediaRecorder::class.java)) {
            if (s.width == 1280 && s.height == 720) { size = s; found = true; break }
        }
        if (!found) {
            val candidates = map.getOutputSizes(MediaRecorder::class.java).sortedByDescending { it.width }
            size = candidates.firstOrNull { it.width <= 1920 } ?: candidates.firstOrNull() ?: size
        }

        val dir = store.mediaDir(projectId)
        dir.mkdirs()
        val f = File(dir, "cam_${System.currentTimeMillis()}.mp4")
        recorder = MediaRecorder()
        val r = recorder!!
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setOutputFile(f.absolutePath)
        r.setVideoEncodingBitRate(8_000_000)
        r.setVideoFrameRate(30)
        r.setVideoSize(size.width, size.height)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(128_000)
        r.setAudioSamplingRate(44100)
        val rot = if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation + 180) % 360
        } else sensorOrientation
        r.setOrientationHint(rot)
        try {
            r.prepare()
        } catch (e: Exception) {
            try { r.reset() } catch (_: Exception) { }
            recorder = null
            UI.toast(this, "Recorder init failed: ${e.message}")
            return
        }
        val previewSurface = Surface(st)
        st.setDefaultBufferSize(size.width, size.height)

        // reopen with both recorder surface and preview surface
        closeSessionOnly()
        try {
            d.createCaptureSession(listOf(previewSurface, r.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        val builder = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        builder.addTarget(previewSurface)
                        builder.addTarget(r.surface)
                        if (torchOn) builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                        try { s.setRepeatingRequest(builder.build(), null, bgHandler) } catch (_: Exception) { }
                        r.start()
                        recording = true
                        recordFile = f
                        recordStart = SystemClock.elapsedRealtime()
                        timerHandler.post(timerTick)
                        runOnUiThread {
                            recordBtn.text = "\u25A0"
                            recordBtn.setTextColor(Color.WHITE)
                            recordBtn.setBackgroundColor(0xFFD32F2F.toInt())
                            statusLabel.text = "Recording \u2026"
                        }
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        try { r.reset() } catch (_: Exception) { }
                        recorder = null
                        UI.toast(this@CameraActivity, "Could not start recording")
                    }
                }, bgHandler)
        } catch (e: Exception) {
            try { r.reset() } catch (_: Exception) { }
            recorder = null
        }
    }

    private fun closeSessionOnly() {
        try { session?.close() } catch (_: Exception) { }
        session = null
    }

    private fun stopRecording() {
        val r = recorder ?: return
        recording = false
        timerHandler.removeCallbacks(timerTick)
        try { r.stop() } catch (_: Exception) { }
        try { r.reset() } catch (_: Exception) { }
        recorder = null
        val f = recordFile
        recordFile = null
        closeSessionOnly()
        try { device?.let { createSession() } } catch (_: Exception) { }
        recordBtn.text = "\u25CF"
        recordBtn.setTextColor(Color.WHITE)
        recordBtn.setBackgroundColor(0xFFE53935.toInt())
        timerLabel.text = "00:00"

        if (f == null || !f.exists() || f.length() < 1000) {
            UI.toast(this, "Recording failed or was too short")
            try { f?.delete() } catch (_: Exception) { }
            return
        }
        val info = MediaKit.probe(f.absolutePath)
        if (info.durMs < 300) { try { f.delete() } catch (_: Exception) { }; UI.toast(this, "Take too short"); return }
        statusLabel.text = "Take saved \u2014 adding to project"
        val rel = "media/${f.name}"
        // return take to the editor
        val i = Intent()
        i.putExtra(EXTRA_RESULT_REL, rel)
        setResult(Activity.RESULT_OK, i)
        finish()
    }
}
