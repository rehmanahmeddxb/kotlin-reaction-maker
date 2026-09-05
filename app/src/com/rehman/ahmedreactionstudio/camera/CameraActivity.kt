package com.rehman.ahmedreactionstudio.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.rehman.ahmedreactionstudio.core.MediaKit
import com.rehman.ahmedreactionstudio.core.ProjectStore
import com.rehman.ahmedreactionstudio.util.UI
import java.io.File
import kotlin.math.abs

/**
 * Fullscreen camera (Camera2): front/back preview + recording straight into
 * the project media folder. All controls overlay the preview, DSLR-style.
 *
 * Robustness notes (device crashes fixed here):
 *  - every view is added to its parent exactly once (the old UI double-added
 *    the button row and died with "The specified child already has a parent");
 *  - camera open/close is serialized on a background handler with an
 *    `opening/started` state machine, so rapid switch/torch taps and resume
 *    can never create two sessions or touch a released device;
 *  - torch (flash) works on whichever camera reports FLASH_INFO_AVAILABLE
 *    (front OR back), and falls back to a screen-light for selfie flashes;
 *  - the permission result actually opens the camera once granted.
 */
class CameraActivity : Activity() {

    companion object {
        const val EXTRA_PROJECT_ID = "pid"
        const val EXTRA_RESULT_REL = "rel"
        const val EXTRA_ROLE = "role"   // "main" canvas layer vs "pip"
        private const val REQ_PERMS = 100
    }

    private var projectId: String = ""
    private var role: String = "main"
    private var facing = CameraCharacteristics.LENS_FACING_FRONT
    private var cameraId: String? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var previewSize = android.util.Size(1280, 720)
    private var sensorOrientation = 90
    private var torchOn = false
    private var torchSupported = false
    private var opening = false
    private var started = false
    private var maxZoom = 1f

    private lateinit var store: ProjectStore
    private lateinit var texture: TextureView
    private lateinit var switchBtn: TextView
    private lateinit var torchBtn: TextView
    private lateinit var recordBtn: TextView
    private lateinit var timerLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var zoomSlider: SeekBar
    private lateinit var flashView: View
    private var previewSurface: Surface? = null
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
        role = intent.getStringExtra(EXTRA_ROLE) ?: "main"
        store = ProjectStore(this)
        buildUi()
        if (!hasPermissions()) requestPermissions(
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), REQ_PERMS)
    }

    private fun hasPermissions(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (code == REQ_PERMS) {
            if (!hasPermissions()) {
                UI.toast(this, "Camera permission is needed to record reaction takes.")
                finish()
            }
            // permission granted -> open as soon as the surface exists
            if (texture.isAvailable) openCamera()
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
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { updatePreviewTransform() }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) { }
        }

        // white screen-flash overlay (selfie "flash" when no hardware flash)
        flashView = View(this)
        flashView.setBackgroundColor(Color.WHITE)
        flashView.alpha = 0f
        flashView.isClickable = false
        root.addView(flashView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT))

        // ---------- top bar ----------
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(UI.dp(this, 10), UI.dp(this, 12), UI.dp(this, 10), UI.dp(this, 8))
        top.setBackgroundColor(Color.argb(90, 0, 0, 0))
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val back = UI.chip(this, "Close")
        back.setCompoundDrawablesRelativeWithIntrinsicBounds(
            com.rehman.ahmedreactionstudio.editor.Ic.get(
                this, com.rehman.ahmedreactionstudio.R.drawable.ic_back, UI.FG), null, null, null)
        back.compoundDrawablePadding = UI.dp(this, 4)
        back.setOnClickListener { finish() }
        top.addView(back)

        val title = TextView(this)
        title.text = if (role == "main") "Record main canvas" else "Record PiP reaction"
        title.setTextColor(Color.WHITE)
        title.textSize = 13f
        title.gravity = Gravity.CENTER
        val tlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        tlp.gravity = Gravity.CENTER_VERTICAL
        title.layoutParams = tlp
        top.addView(title)

        statusLabel = UI.label(this, "", dim = false, size = 12f)
        statusLabel.setTextColor(Color.WHITE)
        statusLabel.gravity = Gravity.END
        val slp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        slp.gravity = Gravity.CENTER_VERTICAL
        statusLabel.layoutParams = slp
        top.addView(statusLabel)

        // ---------- bottom control dock ----------
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.VERTICAL
        bottom.gravity = Gravity.CENTER_HORIZONTAL
        bottom.setPadding(UI.dp(this, 12), UI.dp(this, 14), UI.dp(this, 12), UI.dp(this, 20))
        bottom.setBackgroundColor(Color.argb(120, 0, 0, 0))
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        timerLabel = TextView(this)
        timerLabel.text = "00:00"
        timerLabel.setTextColor(Color.WHITE)
        timerLabel.textSize = 20f
        timerLabel.typeface = Typeface.create("monospace", Typeface.BOLD)
        bottom.addView(timerLabel)

        // zoom row
        val zoomRow = LinearLayout(this)
        zoomRow.orientation = LinearLayout.HORIZONTAL
        zoomRow.gravity = Gravity.CENTER_VERTICAL
        bottom.addView(zoomRow)
        val zl = UI.label(this, "1×", dim = false, size = 12f)
        zl.setTextColor(Color.WHITE)
        zoomRow.addView(zl)
        zoomSlider = SeekBar(this)
        zoomSlider.max = 100
        zoomSlider.progress = 0
        zoomSlider.progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        zoomSlider.thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        zoomSlider.layoutParams = LinearLayout.LayoutParams(UI.dp(this, 200), ViewGroup.LayoutParams.WRAP_CONTENT)
        zoomRow.addView(zoomSlider)
        val zh = UI.label(this, "🔍", dim = false, size = 13f)
        zoomRow.addView(zh)
        zoomSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) {
                zl.text = String.format("%.1f×", 1f + v / 100f * (maxZoom - 1f).coerceAtLeast(0f))
                if (u) applyParams()
            }
            override fun onStartTrackingTouch(s: SeekBar?) { }
            override fun onStopTrackingTouch(s: SeekBar?) { }
        })

        // button row (added exactly once)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        bottom.addView(row)

        switchBtn = UI.chip(this, "Camera")
        switchBtn.setCompoundDrawablesRelativeWithIntrinsicBounds(
            com.rehman.ahmedreactionstudio.editor.Ic.get(
                this, com.rehman.ahmedreactionstudio.R.drawable.ic_switch, UI.FG), null, null, null)
        switchBtn.compoundDrawablePadding = UI.dp(this, 4)
        switchBtn.setOnClickListener { toggleCamera() }
        row.addView(switchBtn)
        UI.margin(switchBtn, 0, 0, 10, 0, this)

        torchBtn = UI.chip(this, "Flash")
        torchBtn.setCompoundDrawablesRelativeWithIntrinsicBounds(
            com.rehman.ahmedreactionstudio.editor.Ic.get(
                this, com.rehman.ahmedreactionstudio.R.drawable.ic_flash, UI.FG), null, null, null)
        torchBtn.compoundDrawablePadding = UI.dp(this, 4)
        torchBtn.setOnClickListener { toggleTorch() }
        row.addView(torchBtn)
        UI.margin(torchBtn, 0, 0, 10, 0, this)

        recordBtn = TextView(this)
        recordBtn.text = "●"
        recordBtn.gravity = Gravity.CENTER
        recordBtn.setTextColor(Color.WHITE)
        recordBtn.textSize = 26f
        recordBtn.contentDescription = "Start recording"
        val rg = android.graphics.drawable.GradientDrawable()
        rg.cornerRadius = UI.dpf(this, 34f)
        rg.setColor(0xFFE53935.toInt())
        recordBtn.background = rg
        recordBtn.tag = rg
        recordBtn.layoutParams = LinearLayout.LayoutParams(UI.dp(this, 68), UI.dp(this, 68))
        recordBtn.setOnClickListener { toggleRecord() }
        row.addView(recordBtn)

        val hint = UI.label(this, "Tap the red button to record this camera", dim = false, size = 11f)
        hint.setTextColor(Color.argb(200, 255, 255, 255))
        val hlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        hlp.topMargin = UI.dp(this, 6)
        hint.layoutParams = hlp
        bottom.addView(hint)

        setContentView(root)
    }

    // ---------- camera plumbing ----------

    private fun startBg() {
        if (bgThread != null) return
        bgThread = HandlerThread("cam").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)
    }

    private fun stopBg() {
        bgThread?.quitSafely()
        try { bgThread?.join(800) } catch (_: Exception) { }
        bgThread = null; bgHandler = null
    }

    override fun onResume() {
        super.onResume()
        startBg()
        if (hasPermissions() && texture.isAvailable) openCamera()
    }

    override fun onPause() {
        closeCamera()
        stopBg()
        super.onPause()
    }

    private fun cm(): CameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private fun cameraIdFor(lens: Int): String {
        try {
            for (id in cm().cameraIdList) {
                val ch = cm().getCameraCharacteristics(id)
                if (ch.get(CameraCharacteristics.LENS_FACING) == lens) return id
            }
            return cm().cameraIdList.firstOrNull() ?: ""
        } catch (_: Exception) { return "" }
    }

    /** Serialized entry point: safe to call from UI thread, resumes, switches. */
    private fun openCamera() {
        if (!hasPermissions()) return
        val h = bgHandler ?: return
        h.post {
            if (opening || started) return@post
            opening = true
            try {
                openCameraLocked()
            } catch (e: Exception) {
                opening = false
                runOnUiThread { statusLabel.text = "Camera unavailable" }
            }
        }
    }

    private fun openCameraLocked() {
        closeCameraLocked()
        val chosen = if (facing == CameraCharacteristics.LENS_FACING_BACK)
            cameraIdFor(CameraCharacteristics.LENS_FACING_BACK)
        else cameraIdFor(CameraCharacteristics.LENS_FACING_FRONT)
        if (chosen.isEmpty()) {
            opening = false
            runOnUiThread { statusLabel.text = "No camera found" }
            return
        }
        cameraId = chosen
        val ch = cm().getCameraCharacteristics(chosen)
        sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        // flash is enabled for ANY camera (front or back) that has a hardware flash unit
        val hwFlash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
        torchSupported = hwFlash
        runOnUiThread { updateTorchLabel() }

        maxZoom = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f

        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
        previewSize = sizes.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: sizes.firstOrNull { it.width in 640..1920 }
            ?: sizes.firstOrNull()
            ?: android.util.Size(1280, 720)

        runOnUiThread { updatePreviewTransform() }
        cm().openCamera(chosen, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                opening = false
                started = true
                device = camera
                createPreviewSession()
            }
            override fun onDisconnected(camera: CameraDevice) {
                opening = false; started = false
                try { camera.close() } catch (_: Exception) { }
                device = null
            }
            override fun onError(camera: CameraDevice, error: Int) {
                opening = false; started = false
                try { camera.close() } catch (_: Exception) { }
                device = null
                runOnUiThread { statusLabel.text = "Camera error $error" }
            }
        }, bgHandler)
    }

    /** rotate/mirror the preview so the phone UI looks right */
    private fun updatePreviewTransform() {
        val st = texture.surfaceTexture ?: return
        val vw = texture.width; val vh = texture.height
        if (vw == 0 || vh == 0) return
        val viewRatio = vw.toFloat() / vh
        val bufRatio = previewSize.width.toFloat() / previewSize.height
        val matrix = android.graphics.Matrix()
        @Suppress("DEPRECATION")
        val displayRot = windowManager.defaultDisplay.rotation
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

    private fun createPreviewSession() {
        val d = device ?: return
        val st = texture.surfaceTexture ?: return
        try {
            st.setDefaultBufferSize(previewSize.width, previewSize.height)
            try { previewSurface?.release() } catch (_: Exception) { }
            val surface = Surface(st)
            previewSurface = surface
            d.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    applyParams()
                    runOnUiThread { statusLabel.text = "" }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    runOnUiThread { statusLabel.text = "Preview failed" }
                }
            }, bgHandler)
        } catch (e: Exception) {
            runOnUiThread { statusLabel.text = "Preview failed" }
        }
    }

    /** One place that builds the repeating request: torch + zoom + AF. */
    private fun applyParams() {
        val d = device ?: return
        val s = session ?: return
        val surface = previewSurface ?: return
        try {
            val template = if (recording) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
            val builder = d.createCaptureRequest(template)
            builder.addTarget(surface)
            if (recording) {
                val rs = recorder?.surface
                if (rs != null) builder.addTarget(rs)
            }
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            // hardware torch only when the active camera supports it
            if (torchSupported) {
                builder.set(CaptureRequest.FLASH_MODE,
                    if (torchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            }
            // digital zoom
            val id = cameraId
            if (id != null) {
                val ch = cm().getCameraCharacteristics(id)
                val max = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
                if (max > 1f) {
                    val zoom = 1f + (zoomSlider.progress / 100f) * (max - 1f)
                    val rect = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    if (rect != null && zoom > 1.01f) {
                        val cropW = (rect.width() / zoom).toInt()
                        val cropH = (rect.height() / zoom).toInt()
                        val crop = android.graphics.Rect(
                            rect.centerX() - cropW / 2, rect.centerY() - cropH / 2,
                            rect.centerX() + cropW / 2, rect.centerY() + cropH / 2)
                        builder.set(CaptureRequest.SCALER_CROP_REGION, crop)
                    }
                }
            }
            s.setRepeatingRequest(builder.build(), null, bgHandler)
        } catch (_: Exception) { }
    }

    private fun closeCamera() {
        val h = bgHandler
        if (h == null) { closeCameraLocked(); return }
        h.post { closeCameraLocked() }
    }

    private fun closeCameraLocked() {
        try { session?.close() } catch (_: Exception) { }
        session = null
        try { device?.close() } catch (_: Exception) { }
        device = null
        try { previewSurface?.release() } catch (_: Exception) { }
        previewSurface = null
        opening = false; started = false
    }

    private fun toggleCamera() {
        if (recording || opening) return
        facing = if (facing == CameraCharacteristics.LENS_FACING_BACK)
            CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        torchOn = false
        zoomSlider.progress = 0
        openCamera()
    }

    private fun updateTorchLabel() {
        when {
            torchSupported ->
                torchBtn.text = if (torchOn) "Flash ON" else "Flash"
            facing == CameraCharacteristics.LENS_FACING_FRONT ->
                torchBtn.text = if (torchOn) "Screen ON" else "Screen light"
            else -> {
                torchBtn.text = "No flash"
                torchBtn.isEnabled = false
                return
            }
        }
        torchBtn.isEnabled = true
    }

    private fun toggleTorch() {
        torchOn = !torchOn
        // selfie cameras without hardware flash: use the white screen as a light
        flashView.animate().alpha(if (torchOn && !torchSupported &&
            facing == CameraCharacteristics.LENS_FACING_FRONT) 0.85f else 0f).setDuration(120).start()
        if (torchSupported) applyParams()
        updateTorchLabel()
    }

    // ---------- recording ----------

    private fun toggleRecord() {
        if (recording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        val h = bgHandler ?: return
        h.post { startRecordingLocked() }
    }

    private fun startRecordingLocked() {
        val d = device ?: return
        val ch = try { cm().getCameraCharacteristics(cameraId ?: return) } catch (e: Exception) { return }
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val recSizes = map.getOutputSizes(MediaRecorder::class.java)
        var size = recSizes?.firstOrNull { it.width == 1280 && it.height == 720 }
        if (size == null) {
            size = recSizes?.sortedByDescending { it.width }?.firstOrNull { it.width <= 1920 }
                ?: android.util.Size(1280, 720)
        }

        val dir = store.mediaDir(projectId)
        dir.mkdirs()
        val f = File(dir, "cam_${System.currentTimeMillis()}.mp4")
        val withMic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val r = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setOutputFile(f.absolutePath)
        r.setVideoEncodingBitRate(8_000_000)
        r.setVideoFrameRate(30)
        r.setVideoSize(size.width, size.height)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        if (withMic) {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128_000)
            r.setAudioSamplingRate(44_100)
        }
        val rot = if (facing == CameraCharacteristics.LENS_FACING_FRONT) (sensorOrientation + 180) % 360
        else sensorOrientation
        r.setOrientationHint(rot)
        try {
            r.prepare()
        } catch (e: Exception) {
            try { r.release() } catch (_: Exception) { }
            runOnUiThread { UI.toast(this, "Recorder init failed: ${e.message}") }
            return
        }
        recorder = r
        val st = texture.surfaceTexture
        if (st == null) { try { r.release() } catch (_: Exception) { }; recorder = null; return }
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        val preview = previewSurface ?: Surface(st)

        try { session?.close() } catch (_: Exception) { }
        session = null
        try {
            d.createCaptureSession(listOf(preview, r.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    recording = true
                    applyParams()
                    try { r.start() } catch (e: Exception) {
                        recording = false
                        try { r.release() } catch (_: Exception) { }
                        recorder = null
                        runOnUiThread { UI.toast(this@CameraActivity, "Could not start recording") }
                        createPreviewSession()
                        return
                    }
                    recordFile = f
                    recordStart = SystemClock.elapsedRealtime()
                    timerHandler.post(timerTick)
                    runOnUiThread {
                        recordBtn.text = "■"
                        (recordBtn.tag as? android.graphics.drawable.GradientDrawable)
                            ?.setColor(0xFFD32F2F.toInt())
                        recordBtn.contentDescription = "Stop recording"
                        statusLabel.text = "Recording …"
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    try { r.release() } catch (_: Exception) { }
                    recorder = null
                    runOnUiThread { UI.toast(this@CameraActivity, "Could not start recording") }
                    createPreviewSession()
                }
            }, bgHandler)
        } catch (e: Exception) {
            try { r.release() } catch (_: Exception) { }
            recorder = null
            createPreviewSession()
        }
    }

    private fun stopRecording() {
        val h = bgHandler ?: return
        h.post { stopRecordingLocked() }
    }

    private fun stopRecordingLocked() {
        val r = recorder ?: return
        recording = false
        timerHandler.removeCallbacks(timerTick)
        var ok = true
        try { r.stop() } catch (_: Exception) { ok = false }
        try { r.release() } catch (_: Exception) { }
        recorder = null
        val f = recordFile
        recordFile = null
        try { session?.close() } catch (_: Exception) { }
        session = null
        if (device != null) createPreviewSession()
        runOnUiThread {
            recordBtn.text = "●"
            (recordBtn.tag as? android.graphics.drawable.GradientDrawable)
                ?.setColor(0xFFE53935.toInt())
            recordBtn.contentDescription = "Start recording"
            timerLabel.text = "00:00"
            statusLabel.text = ""
        }

        if (!ok || f == null || !f.exists() || f.length() < 50_000) {
            runOnUiThread { UI.toast(this, "Recording failed or was too short") }
            try { f?.delete() } catch (_: Exception) { }
            return
        }
        val info = MediaKit.probe(f.absolutePath)
        if (info.durMs < 300 || info.width == 0) {
            try { f.delete() } catch (_: Exception) { }
            runOnUiThread { UI.toast(this, "Take too short or unreadable") }
            return
        }
        val rel = "media/${f.name}"
        val i = Intent()
        i.putExtra(EXTRA_RESULT_REL, rel)
        i.putExtra(EXTRA_ROLE, role)
        setResult(Activity.RESULT_OK, i)
        finish()
    }
}
