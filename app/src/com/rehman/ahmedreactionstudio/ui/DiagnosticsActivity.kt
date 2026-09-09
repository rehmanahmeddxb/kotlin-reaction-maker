package com.rehman.ahmedreactionstudio.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaCodecList
import android.media.MediaFormat
import android.opengl.GLES20
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.rehman.ahmedreactionstudio.R
import com.rehman.ahmedreactionstudio.camera.TorchController
import com.rehman.ahmedreactionstudio.editor.EditorActivity
import com.rehman.ahmedreactionstudio.editor.Ic
import com.rehman.ahmedreactionstudio.util.UI

/**
 * Diagnostics — device + codec + camera capability, polished into cards.
 *
 * - Top bar: back chip + title + copy action.
 * - Sections: Device, Cameras & Torch, Encoders, Storage, Stats toggle, Crash logs.
 * - Each section: card BG2 radius 16dp, stroke white 8%, elevation low, padding 14.
 * - Rows: label FG2 12sp + value FG 12.5sp Bold, 44dp min height, divider hairline.
 * - Crash log: monospace 10.5sp, red tint card.
 */
class DiagnosticsActivity : Activity() {

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        UI.styleWindow(this)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(UI.BG)
        root.setPadding(0, UI.dp(this, 12), 0, 0)

        // ---- top bar ----
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(UI.dp(this, 16), UI.dp(this, 6), UI.dp(this, 16), UI.dp(this, 10))
        val back = TextView(this)
        back.text = "Back"
        back.gravity = Gravity.CENTER
        back.setTextColor(UI.FG)
        back.textSize = 12f
        back.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        back.setPadding(UI.dp(this, 12), 0, UI.dp(this, 12), 0)
        back.background = UI.bg(this, UI.BG3, 18f, Color.argb(70, 255, 255, 255))
        back.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UI.dp(this, 36))
        back.setCompoundDrawablesRelativeWithIntrinsicBounds(Ic.get(this, R.drawable.ic_back, UI.FG), null, null, null)
        back.compoundDrawablePadding = UI.dp(this, 6)
        back.setOnClickListener { finish() }
        top.addView(back)

        val title = UI.title(this, "Diagnostics")
        title.textSize = 18f
        val tl = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        tl.marginStart = UI.dp(this, 12)
        title.layoutParams = tl
        top.addView(title)

        root.addView(top)

        // ---- content ----
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(UI.BG)
        col.setPadding(UI.dp(this, 16), 0, UI.dp(this, 16), UI.dp(this, 16))

        val hint = UI.label(this, "Hardware-aware: capabilities are detected, never assumed.", dim = true, size = 11f)
        hint.setTextColor(UI.TEXT_MUTED)
        UI.margin(hint, 0, 0, 0, 12, this)
        col.addView(hint)

        val sb = StringBuilder()

        // helper to create a card section
        fun sectionCard(titleText: String): LinearLayout {
            val card = LinearLayout(this)
            card.orientation = LinearLayout.VERTICAL
            card.background = UI.cardBg(this, UI.BG2, UI.R_L, Color.argb(50, 255, 255, 255))
            card.elevation = UI.dpf(this, 2f)
            card.setPadding(UI.dp(this, 14), UI.dp(this, 14), UI.dp(this, 14), UI.dp(this, 12))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = UI.dp(this, 12)
            card.layoutParams = lp

            val t = TextView(this)
            t.text = titleText.uppercase()
            t.setTextColor(UI.ACCENT2)
            t.textSize = 10.5f
            t.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            t.letterSpacing = 0.08f
            card.addView(t)

            val div = View(this)
            div.setBackgroundColor(Color.argb(40, 255, 255, 255))
            val dlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            dlp.topMargin = UI.dp(this, 10)
            dlp.bottomMargin = UI.dp(this, 6)
            div.layoutParams = dlp
            card.addView(div)
            return card
        }

        fun rowInto(parent: LinearLayout, label: String, value: String, accentValue: Boolean = false) {
            sb.append(label).append(": ").append(value).append("\n")
            val rl = LinearLayout(this)
            rl.orientation = LinearLayout.HORIZONTAL
            rl.gravity = Gravity.CENTER_VERTICAL
            rl.setPadding(0, UI.dp(this, 8), 0, UI.dp(this, 8))
            val l = TextView(this)
            l.text = label
            l.setTextColor(UI.FG2)
            l.textSize = 12f
            l.maxLines = 2
            l.ellipsize = android.text.TextUtils.TruncateAt.END
            l.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val v = TextView(this)
            v.text = value
            v.setTextColor(if (accentValue) UI.ACCENT2 else UI.FG)
            v.textSize = 12.5f
            v.typeface = Typeface.create("sans-serif-medium", if (accentValue) Typeface.BOLD else Typeface.NORMAL)
            v.gravity = Gravity.END
            v.maxLines = 3
            v.ellipsize = android.text.TextUtils.TruncateAt.END
            val vlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            vlp.marginStart = UI.dp(this, 12)
            v.layoutParams = vlp
            rl.addView(l)
            rl.addView(v)
            parent.addView(rl)

            // hairline divider
            val hair = View(this)
            hair.setBackgroundColor(Color.argb(30, 255, 255, 255))
            hair.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            parent.addView(hair)
        }

        // ---- Device card ----
        val devCard = sectionCard("Device")
        rowInto(devCard, "Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        rowInto(devCard, "Device", "${Build.MANUFACTURER} ${Build.MODEL}")
        rowInto(devCard, "ABI", Build.SUPPORTED_ABIS.firstOrNull() ?: "?")
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            rowInto(devCard, "RAM", UI.niceBytes(mi.totalMem))
        } catch (_: Exception) { }
        try {
            val sf = StatFs(Environment.getDataDirectory().absolutePath)
            rowInto(devCard, "Free storage", UI.niceBytes(sf.availableBytes))
        } catch (_: Exception) { }
        try {
            val version = GLES20.glGetString(GLES20.GL_VERSION)
            rowInto(devCard, "OpenGL ES", version ?: "?")
        } catch (_: Exception) { }
        col.addView(devCard)

        // ---- Cameras card ----
        val camCard = sectionCard("Cameras & Torch")
        val cm = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        try {
            rowInto(camCard, "Cameras", cm.cameraIdList.size.toString(), accentValue = true)
            for (id in cm.cameraIdList) {
                val ch = cm.getCameraCharacteristics(id)
                val face = when (ch.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)) {
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK -> "Back"
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                    else -> "External"
                }
                val flash = ch.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                rowInto(camCard, "$face camera $id", if (flash) "hardware flash" else "no flash")
            }
        } catch (_: Exception) { }
        try {
            val t = TorchController(this)
            t.start()
            rowInto(camCard, "Torch rear", t.describe(false))
            rowInto(camCard, "Torch front", t.describe(true))
            t.shutdown()
        } catch (_: Exception) { }
        col.addView(camCard)

        // ---- Codecs card ----
        val codecCard = sectionCard("Encoders")
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
        rowInto(codecCard, "H.264 encoders", "hw $hwAvc / sw $swAvc", accentValue = hwAvc > 0)
        rowInto(codecCard, "HEVC encoders", "hw $hwHevc / sw $swHevc")
        rowInto(codecCard, "Default export", "H.264 + AAC in MP4")
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
            rowInto(codecCard, "H.264 color", "Flex×$flex NV12×$nv12 I420×$i420 surf×$surfaceOnly")
        } catch (_: Exception) { }
        col.addView(codecCard)

        // ---- Storage card ----
        val storeCard = sectionCard("Storage")
        val ex = getExternalFilesDir(null)
        rowInto(storeCard, "App data", ex?.absolutePath ?: "internal")
        col.addView(storeCard)

        // ---- Preview stats toggle ----
        val statsCard = sectionCard("Preview")
        val prefs = getSharedPreferences(EditorActivity.PREFS_EDITOR, MODE_PRIVATE)
        val hudBtn = TextView(this)
        fun refreshHudLabel() {
            val on = prefs.getBoolean(EditorActivity.PREF_STATS_HUD, true)
            hudBtn.text = if (on) "Preview stats overlay: ON" else "Preview stats overlay: OFF"
            hudBtn.setTextColor(if (on) UI.OK else UI.FG2)
        }
        hudBtn.gravity = Gravity.CENTER
        hudBtn.textSize = 12.5f
        hudBtn.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        hudBtn.setPadding(UI.dp(this, 14), 0, UI.dp(this, 14), 0)
        hudBtn.background = UI.bg(this, UI.BG3, 12f, Color.argb(70, 255, 255, 255))
        hudBtn.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 44))
        hudBtn.setOnClickListener {
            val on = !prefs.getBoolean(EditorActivity.PREF_STATS_HUD, true)
            prefs.edit().putBoolean(EditorActivity.PREF_STATS_HUD, on).apply()
            refreshHudLabel()
        }
        refreshHudLabel()
        statsCard.addView(hudBtn)
        val hudHint = UI.label(this,
            "While a clip plays the editor shows \"HW · fps · ms/f\". SW means that clip fell back to the software retriever and will stutter.",
            dim = true, size = 11f)
        hudHint.setTextColor(UI.FG2)
        UI.margin(hudHint, 0, 8, 0, 0, this)
        statsCard.addView(hudHint)
        col.addView(statsCard)

        // ---- crash logs ----
        val crashes = com.rehman.ahmedreactionstudio.App.crashLogs(this)
        sb.append("crashLogs: ").append(crashes.size).append("\n")
        val crashCard = sectionCard(if (crashes.isEmpty()) "No crashes ✓" else "⚠ ${crashes.size} crash log(s)")
        if (crashes.isNotEmpty()) {
            val latest = crashes.first()
            val text = try { latest.readText() } catch (_: Exception) { "(unreadable)" }
            sb.append("---- latest crash ----\n").append(text).append("\n")
            val tv = TextView(this)
            tv.text = text.take(4000)
            tv.setTextColor(Color.argb(235, 255, 200, 200))
            tv.textSize = 10.5f
            tv.typeface = Typeface.MONOSPACE
            tv.setPadding(UI.dp(this, 12), UI.dp(this, 10), UI.dp(this, 12), UI.dp(this, 10))
            tv.background = GradientDrawable().apply {
                cornerRadius = UI.dpf(this@DiagnosticsActivity, 10f)
                setColor(Color.argb(60, 120, 30, 30))
                setStroke(UI.dp(this@DiagnosticsActivity, 1), Color.argb(70, 200, 80, 80))
            }
            val tlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            tlp.topMargin = UI.dp(this, 8)
            tv.layoutParams = tlp
            crashCard.addView(tv)

            val clearCrash = TextView(this)
            clearCrash.text = "Clear crash logs"
            clearCrash.gravity = Gravity.CENTER
            clearCrash.setTextColor(UI.DANGER)
            clearCrash.textSize = 12f
            clearCrash.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            clearCrash.setPadding(UI.dp(this, 14), 0, UI.dp(this, 14), 0)
            clearCrash.background = UI.bg(this, Color.argb(30, 235, 90, 90), 12f, Color.argb(70, 235, 90, 90))
            clearCrash.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 40)).apply {
                topMargin = UI.dp(this@DiagnosticsActivity, 10)
            }
            clearCrash.setOnClickListener {
                crashes.forEach { runCatching { it.delete() } }
                UI.toast(this, "Crash logs cleared")
                recreate()
            }
            crashCard.addView(clearCrash)
        } else {
            val ok = UI.label(this, "Your studio is stable — no crash logs found.", dim = true, size = 12f)
            ok.setTextColor(UI.OK)
            crashCard.addView(ok)
        }
        col.addView(crashCard)

        // ---- copy button ----
        val copy = TextView(this)
        copy.text = "Copy diagnostics"
        copy.gravity = Gravity.CENTER
        copy.setTextColor(UI.FG)
        copy.textSize = 13f
        copy.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        copy.setPadding(UI.dp(this, 16), 0, UI.dp(this, 16), 0)
        copy.background = UI.bg(this, UI.BG3, UI.R_M, Color.argb(80, 255, 255, 255))
        copy.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 44)).apply {
            topMargin = UI.dp(this@DiagnosticsActivity, 8)
        }
        copy.setCompoundDrawablesRelativeWithIntrinsicBounds(
            Ic.get(this, R.drawable.ic_copy, UI.FG2), null, null, null)
        copy.compoundDrawablePadding = UI.dp(this, 8)
        copy.setOnClickListener {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("diagnostics", sb.toString()))
            UI.toast(this, "Diagnostics copied")
        }
        col.addView(copy)

        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }
}
