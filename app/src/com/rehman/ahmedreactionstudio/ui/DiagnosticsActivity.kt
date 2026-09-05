package com.rehman.ahmedreactionstudio.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.opengl.GLES20
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.rehman.ahmedreactionstudio.editor.EditorActivity
import com.rehman.ahmedreactionstudio.util.UI
import java.io.File

/** Device + codec + camera capability screen (spec 75). */
class DiagnosticsActivity : Activity() {

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        UI.styleWindow(this)
        val col = UI.col(this, true)
        col.setPadding(UI.dp(this, 16), UI.dp(this, 18), UI.dp(this, 16), 0)

        val title = UI.title(this, "Diagnostics")
        col.addView(title)
        val hint = UI.label(this, "Hardware-aware: capabilities are detected, never assumed.", dim = true, size = 11f)
        UI.margin(hint, 0, 2, 0, 0, this)
        col.addView(hint)

        val sb = StringBuilder()
        fun row(label: String, value: String) {
            sb.append(label).append(": ").append(value).append("\n")
            val rl = LinearLayout(this)
            rl.orientation = LinearLayout.HORIZONTAL
            val l = TextView(this); l.text = label; l.setTextColor(UI.FG2); l.textSize = 12.5f
            l.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val v = TextView(this); v.text = value; v.setTextColor(UI.FG); v.textSize = 12.5f
            v.gravity = android.view.Gravity.END
            rl.addView(l); rl.addView(v)
            col.addView(rl)
        }

        row("Android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")")
        row("Device", Build.MANUFACTURER + " " + Build.MODEL)
        row("ABI", Build.SUPPORTED_ABIS.firstOrNull() ?: "?")
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            row("RAM", UI.niceBytes(mi.totalMem))
        } catch (_: Exception) { }
        try {
            val sf = StatFs(Environment.getDataDirectory().absolutePath)
            row("Free storage", UI.niceBytes(sf.availableBytes))
        } catch (_: Exception) { }

        // OpenGL ES version
        try {
            val egl = android.opengl.EGL14.eglGetCurrentContext()
            val version = GLES20.glGetString(GLES20.GL_VERSION)
            row("OpenGL ES", version ?: "?")
        } catch (_: Exception) { }

        // cameras
        val cm = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        try {
            row("Cameras", cm.cameraIdList.size.toString())
            for (id in cm.cameraIdList) {
                val ch = cm.getCameraCharacteristics(id)
                val face = when (ch.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)) {
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK -> "Back"
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                    else -> "Ext"
                }
                val flash = ch.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                row("  $face camera $id", if (flash) "hardware flash" else "no flash")
            }
        } catch (_: Exception) { }

        // codecs
        var hwAvc = 0; var hwHevc = 0; var swAvc = 0; var swHevc = 0
        try {
            for (ci in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (!ci.isEncoder) continue
                val sw = ci.name.contains("google", true) || ci.name.contains("c2.android", true)
                for (t in ci.supportedTypes) {
                    if (t == MediaFormat.MIMETYPE_VIDEO_AVC) { if (sw) swAvc++ else hwAvc++ }
                    if (t == MediaFormat.MIMETYPE_VIDEO_HEVC) { if (sw) swHevc++ else hwHevc++ }
                }
            }
        } catch (_: Exception) { }
        row("H.264 encoders", "hw $hwAvc / sw $swAvc")
        row("HEVC encoders", "hw $hwHevc / sw $swHevc")
        row("Default export", "H.264 + AAC in MP4 (plays on Android and Windows)")
        try {
            var flex = 0; var nv12 = 0; var i420 = 0; var surfaceOnly = 0
            for (ci in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (!ci.isEncoder) continue
                if (!ci.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_AVC)) continue
                val caps = ci.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val fmts = caps.colorFormats
                if (fmts.contains(android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)) flex++
                if (fmts.contains(android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)) nv12++
                if (fmts.contains(android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)) i420++
                if (fmts.size == 1 && fmts[0] == 2130708361) surfaceOnly++
            }
            row("H.264 color", "Flexible×$flex  NV12×$nv12  I420×$i420  surface-only×$surfaceOnly")
        } catch (_: Exception) { }

        // storage for exports
        val ex = getExternalFilesDir(null)
        row("App data dir", ex?.absolutePath ?: "internal")

        // Preview path is the number that matters when playback looks bad:
        // HW = hardware MediaCodec, SW = software retriever fallback.
        val prefs = getSharedPreferences(EditorActivity.PREFS_EDITOR, MODE_PRIVATE)
        val hudBtn = UI.btn(this,
            if (prefs.getBoolean(EditorActivity.PREF_STATS_HUD, true))
                "Preview stats overlay: ON" else "Preview stats overlay: OFF",
            accent = false)
        hudBtn.setOnClickListener {
            val on = !prefs.getBoolean(EditorActivity.PREF_STATS_HUD, true)
            prefs.edit().putBoolean(EditorActivity.PREF_STATS_HUD, on).apply()
            hudBtn.text = if (on) "Preview stats overlay: ON" else "Preview stats overlay: OFF"
        }
        UI.margin(hudBtn, 0, 12, 0, 0, this)
        col.addView(hudBtn)
        val hudHint = UI.label(this,
            "While a clip plays the editor shows “HW · fps · ms/f”. " +
                "SW means that clip fell back to the software retriever and will stutter.",
            dim = true, size = 11f)
        UI.margin(hudHint, 0, 2, 0, 0, this)
        col.addView(hudHint)

        UI.margin(col, 0, 0, 0, 12, this)
        val copy = UI.btn(this, "Copy diagnostics", accent = false)
        copy.setOnClickListener {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("diagnostics", sb.toString()))
            UI.toast(this, "Copied")
        }
        col.addView(copy)
        // The row list can be taller than small screens: scroll, with padding.
        val scroll = android.widget.ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        col.layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        scroll.addView(col)
        setContentView(scroll)
    }
}
