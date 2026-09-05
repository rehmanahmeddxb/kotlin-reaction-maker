package com.rehman.ahmedreactionstudio.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import java.io.File

/**
 * Screen recording via MediaProjection. Writes an MP4 (H.264 + AAC) clip
 * straight into the project media folder so it can be imported like any
 * other layer (main canvas OR PiP).
 *
 * Started with the MediaProjection result code + intent. Records until
 * [stopWithResult] is called; the produced file path is delivered back to
 * the editor through [onStopped].
 */
class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START = "ars.screen.start"
        const val ACTION_STOP = "ars.screen.stop"
        /**
         * T-31 — the single stop wording. The notification and the editor's
         * on-canvas chip must say the same thing, or the user cannot tell
         * whether they are two recordings or one.
         */
        const val STOP_LABEL = "Stop screen recording"
        const val EXTRA_RESULT_CODE = "rc"
        const val EXTRA_RESULT_DATA = "data"
        const val EXTRA_PROJECT_DIR = "pdir"
        const val CHANNEL_ID = "ars_screen_rec"
        const val NOTIF_ID = 7123

        /** last completed take; the editor polls this on resume */
        @Volatile
        var pendingFile: File? = null

        @Volatile
        var running = false
            private set

        var onStopped: ((File?) -> Unit)? = null
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var outFile: File? = null
    private var width = 1280
    private var height = 720
    private var density = 1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
                val pdir = intent.getStringExtra(EXTRA_PROJECT_DIR)
                if (code == 0 || data == null || pdir == null) {
                    stopSelf(); return START_NOT_STICKY
                }
                startForegroundCompat()
                startRecording(code, data, File(pdir))
            }
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "Screen recording", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        val stop = Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP)
        val pi = android.app.PendingIntent.getService(
            this, 0, stop,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Recording screen")
                .setContentText("$STOP_LABEL — it is added to your project")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true)
                .setContentIntent(pi)
                .build()
        else @Suppress("DEPRECATION") Notification.Builder(this)
            .setContentTitle("Recording screen")
            .setContentText("$STOP_LABEL — it is added to your project")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun startRecording(code: Int, data: Intent, projectDir: File) {
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(code, data) ?: run {
                failShutdown("Screen capture permission was denied"); return
            }
            projection = mp

            // MediaProjection requires a callback on API 34+
            if (Build.VERSION.SDK_INT >= 34) {
                thread = HandlerThread("screen-cap").also { it.start() }
                handler = Handler(thread!!.looper)
                mp.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        // projection revoked by the system/user
                        stopRecording()
                    }
                }, handler)
            }

            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            density = metrics.densityDpi.coerceAtLeast(1)
            // even dimensions, cap longest side at 1080 for light encodes
            var w = metrics.widthPixels
            var h = metrics.heightPixels
            val longest = maxOf(w, h)
            if (longest > 1080) {
                val s = 1080f / longest
                w = (w * s).toInt()
                h = (h * s).toInt()
            }
            width = (w / 2) * 2
            height = (h / 2) * 2

            val dir = File(projectDir, "media")
            dir.mkdirs()
            val f = File(dir, "screen_${System.currentTimeMillis()}.mp4")
            outFile = f

            val withMic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            // Audio source must be configured before setOutputFormat, or the
            // MediaRecorder state machine throws during prepare().
            if (withMic) r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setOutputFile(f.absolutePath)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoSize(width, height)
            r.setVideoFrameRate(30)
            r.setVideoEncodingBitRate(10_000_000)
            if (withMic) {
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(128_000)
                r.setAudioSamplingRate(44_100)
            }
            r.prepare()
            recorder = r

            virtualDisplay = mp.createVirtualDisplay(
                "ars-screen", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, handler)
            r.start()
            running = true
        } catch (e: Exception) {
            failShutdown("Could not start screen recording: ${e.message}")
        }
    }

    private fun failShutdown(msg: String) {
        running = false
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
        }
        cleanup()
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopRecording() {
        if (!running) { stopSelf(); return }
        running = false
        var produced: File? = null
        try {
            recorder?.apply {
                try { stop() } catch (_: Exception) { }
                try { reset() } catch (_: Exception) { }
                release()
            }
            val f = outFile
            if (f != null && f.exists() && f.length() > 50_000) {
                produced = f
                pendingFile = f
            } else {
                try { f?.delete() } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
        recorder = null
        val cb = onStopped
        onStopped = null
        cleanup()
        stopForegroundCompat()
        stopSelf()
        cb?.invoke(produced)
    }

    private fun cleanup() {
        try { virtualDisplay?.release() } catch (_: Exception) { }
        virtualDisplay = null
        try { projection?.stop() } catch (_: Exception) { }
        projection = null
        try { thread?.quitSafely() } catch (_: Exception) { }
        thread = null; handler = null
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (_: Exception) { }
    }

    override fun onDestroy() {
        cleanup()
        running = false
        super.onDestroy()
    }
}
