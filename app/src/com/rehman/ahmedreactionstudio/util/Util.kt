package com.rehman.ahmedreactionstudio.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.roundToInt

object UI {

    val BG = Color.rgb(16, 18, 24)
    val BG2 = Color.rgb(27, 30, 38)
    val BG3 = Color.rgb(38, 42, 52)
    val FG = Color.rgb(235, 238, 245)
    val FG2 = Color.rgb(160, 166, 180)
    val ACCENT = Color.rgb(255, 90, 44)      // studio orange-red
    val ACCENT2 = Color.rgb(255, 160, 44)
    val OK = Color.rgb(70, 210, 130)
    val DANGER = Color.rgb(235, 90, 90)
    val BLACK = Color.rgb(10, 10, 12)

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).roundToInt()

    fun dpf(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

    fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

    fun fmtTime(ms: Long): String {
        val s = (ms / 1000L).coerceAtLeast(0L)
        return String.format(Locale.US, "%d:%02d", s / 60L, s % 60L)
    }

    fun niceBytes(n: Long): String {
        if (n < 1024) return "$n B"
        val kb = n / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    fun keepScreenOn(win: Window, on: Boolean) {
        if (on) win.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else win.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun darken(c: Int, f: Float): Int {
        val a = Color.alpha(c)
        return Color.argb(a,
            (Color.red(c) * f).toInt().coerceIn(0, 255),
            (Color.green(c) * f).toInt().coerceIn(0, 255),
            (Color.blue(c) * f).toInt().coerceIn(0, 255))
    }

    /** Convenience: a dark LinearLayout container */
    fun col(ctx: Context, vertical: Boolean): LinearLayout {
        val ll = LinearLayout(ctx)
        ll.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        ll.setBackgroundColor(BG)
        return ll
    }

    /** pill-shaped button used across screens */
    fun btn(ctx: Context, label: String, accent: Boolean = true, small: Boolean = false): Button {
        val b = Button(ctx)
        b.text = label
        b.isAllCaps = false
        b.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        b.setTextColor(if (accent) Color.WHITE else FG)
        b.textSize = if (small) 11f else 14f
        b.setPadding(dp(ctx, if (small) 8 else 14), 0, dp(ctx, if (small) 8 else 14), 0)
        b.minHeight = 0
        b.minimumHeight = 0
        if (small) b.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 30))
        else b.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 42))
        val g = GradientDrawable()
        g.cornerRadius = dpf(ctx, 12f)
        if (accent) {
            g.setColor(ACCENT)
            g.setStroke(dp(ctx, 1), Color.argb(120, 255, 200, 160))
        } else {
            g.setColor(BG3)
            g.setStroke(dp(ctx, 1), Color.argb(90, 255, 255, 255))
        }
        b.background = g
        return b
    }

    /** Small chip button that keeps pressed state */
    fun chip(ctx: Context, label: String): TextView {
        val t = TextView(ctx)
        t.text = label
        t.gravity = Gravity.CENTER
        t.setTextColor(FG)
        t.textSize = 12f
        t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        t.setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0)
        t.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 34))
        val g = GradientDrawable()
        g.cornerRadius = dpf(ctx, 17f)
        g.setColor(BG3)
        g.setStroke(dp(ctx, 1), Color.argb(70, 255, 255, 255))
        t.background = g
        return t
    }

    fun title(ctx: Context, s: String): TextView {
        val t = TextView(ctx)
        t.text = s
        t.setTextColor(FG)
        t.textSize = 18f
        t.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        t.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        return t
    }

    fun label(ctx: Context, s: String, dim: Boolean = false, size: Float = 12f): TextView {
        val t = TextView(ctx)
        t.text = s
        t.setTextColor(if (dim) FG2 else FG)
        t.textSize = size
        t.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        return t
    }

    fun margin(v: View, l: Int, t: Int, r: Int, b: Int, ctx: Context) {
        val lp = v.layoutParams as? LinearLayout.LayoutParams ?: return
        lp.setMargins(dp(ctx, l), dp(ctx, t), dp(ctx, r), dp(ctx, b))
    }

    /** Dark status bar + dark navigation for every screen. */
    fun styleWindow(a: Activity, lightNav: Boolean = true) {
        val w = a.window
        w.statusBarColor = BG
        w.navigationBarColor = BG
    }

    /**
     * Saving a finished video lives in [com.rehman.ahmedreactionstudio.export.MediaSave].
     *
     * The old `publishToGallery` helper that used to sit here reported success
     * even when its MediaStore insert had thrown or written zero bytes, which is
     * why exports appeared to vanish. Use MediaSave.publishVideo instead — it
     * verifies the bytes and tells you where the file really landed.
     */

    fun shareUri(act: Activity, uri: android.net.Uri, mime: String = "video/mp4") {
        try {
            val i = Intent(Intent.ACTION_SEND).setType(mime)
            i.putExtra(Intent.EXTRA_STREAM, uri)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            act.startActivity(Intent.createChooser(i, "Share video"))
        } catch (e: Exception) {
            toast(act, "Share failed: ${e.message}")
        }
    }
}
