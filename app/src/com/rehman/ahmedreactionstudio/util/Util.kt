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

/**
 * Central design system — single source of truth for colors, radii, spacing,
 * typography and elevation. All screens read from here so the whole app
 * feels like one product, not a collection of panels.
 *
 * Palette: charcoal studio (dark) + orange accent, aligned with
 * `frontend ref/blueprints/05-tokens-states-flows.md` but re-themed to this
 * app's orange brand. No new hues outside this object.
 */
object UI {

    // ===== Core palette (existing names kept for compatibility) =====
    val BG = Color.rgb(16, 18, 24)          // #101218 — app bg, sidebar
    val BG2 = Color.rgb(27, 30, 38)         // #1B1E26 — top strip, cards
    val BG3 = Color.rgb(38, 42, 52)         // #262A34 — chips, dialogs
    val FG = Color.rgb(235, 238, 245)       // #EBEEF5 — primary text
    val FG2 = Color.rgb(160, 166, 180)      // #A0A6B4 — secondary
    val ACCENT = Color.rgb(255, 90, 44)     // #FF5A2C — primary CTA, selection
    val ACCENT2 = Color.rgb(255, 160, 44)   // #FFA02C — secondary accent
    val OK = Color.rgb(70, 210, 130)        // #46D282 — saved, success
    val DANGER = Color.rgb(235, 90, 90)     // #EB5A5A — record, delete
    val BLACK = Color.rgb(10, 10, 12)

    // ===== Extended tokens (semantic) =====
    val SURFACE = BG2
    val SURFACE_ELEVATED = BG3
    val SURFACE_BORDER = Color.argb(70, 255, 255, 255)
    val SURFACE_BORDER_STRONG = Color.argb(110, 255, 255, 255)
    val STAGE = Color.rgb(6, 7, 10)         // #06070A — canvas backdrop
    val TEXT_PRIMARY = FG
    val TEXT_SECONDARY = FG2
    val TEXT_MUTED = Color.rgb(100, 116, 139) // #64748B
    val ACCENT_FG = Color.rgb(14, 14, 16)   // text on accent
    val ACCENT_SOFT = Color.argb(55, 255, 90, 44) // selection wash
    val ACCENT_BORDER = Color.argb(120, 255, 200, 160)
    val DANGER_SOFT = Color.argb(40, 235, 90, 90)
    val OK_SOFT = Color.argb(40, 70, 210, 130)
    val OVERLAY_DIM = Color.argb(150, 0, 0, 0)
    val OVERLAY_LIGHT = Color.argb(235, 18, 20, 27)

    // Layer type accents (sidebar rows + canvas badges)
    val LAYER_VIDEO = ACCENT
    val LAYER_CAMERA_FRONT = ACCENT2
    val LAYER_CAMERA_BACK = DANGER
    val LAYER_IMAGE = OK
    val LAYER_SCREEN = Color.rgb(124, 147, 196) // #7C93C4 slate
    val LAYER_TEXT = Color.rgb(255, 209, 102)   // #FFD166

    // ===== Radii (dp, converted via dpf) =====
    const val R_S = 8f
    const val R_M = 12f
    const val R_L = 16f
    const val R_XL = 20f
    const val R_PILL = 28f
    const val R_CIRCLE = 100f

    // ===== Spacing (dp) =====
    const val SP_2 = 2
    const val SP_4 = 4
    const val SP_6 = 6
    const val SP_8 = 8
    const val SP_10 = 10
    const val SP_12 = 12
    const val SP_14 = 14
    const val SP_16 = 16
    const val SP_20 = 20
    const val SP_24 = 24

    // ===== Elevation (dp) =====
    const val ELEV_LOW = 4f
    const val ELEV_MED = 8f
    const val ELEV_HIGH = 12f
    const val ELEV_MAX = 16f

    // ===== Motion =====
    const val DUR_SHORT = 150L
    const val DUR_MED = 220L
    const val DUR_LONG = 320L

    // ===== Utils =====
    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).roundToInt()
    fun dpf(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

    fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

    fun fmtTime(ms: Long): String {
        val s = (ms / 1000L).coerceAtLeast(0L)
        return String.format(Locale.US, "%d:%02d", s / 60L, s % 60L)
    }

    fun relTime(ms: Long): String {
        if (ms <= 0L) return "just now"
        val d = System.currentTimeMillis() - ms
        if (d < 0L) return "just now"
        val min = d / 60_000L
        if (min < 1L) return "just now"
        if (min < 60L) return "$min minute" + (if (min == 1L) "" else "s") + " ago"
        val hr = min / 60L
        if (hr < 24L) return "$hr hour" + (if (hr == 1L) "" else "s") + " ago"
        val day = hr / 24L
        if (day < 7L) return "$day day" + (if (day == 1L) "" else "s") + " ago"
        val wk = day / 7L
        if (wk < 5L) return "$wk week" + (if (wk == 1L) "" else "s") + " ago"
        val mo = day / 30L
        if (mo < 12L) return "$mo month" + (if (mo == 1L) "" else "s") + " ago"
        val yr = day / 365L
        return "$yr year" + (if (yr == 1L) "" else "s") + " ago"
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

    // ===== Background builders (single place for radii + stroke) =====
    fun bg(ctx: Context, color: Int, radiusDp: Float, stroke: Int = Color.TRANSPARENT,
           strokeWdp: Int = 1): GradientDrawable {
        val g = GradientDrawable()
        g.cornerRadius = dpf(ctx, radiusDp)
        g.setColor(color)
        if (stroke != Color.TRANSPARENT) g.setStroke(dp(ctx, strokeWdp), stroke)
        return g
    }

    fun pillBg(ctx: Context, color: Int, stroke: Int = SURFACE_BORDER): GradientDrawable =
        bg(ctx, color, R_PILL, stroke)

    fun cardBg(ctx: Context, color: Int = BG2, radius: Float = R_L,
               stroke: Int = Color.argb(50, 255, 255, 255)): GradientDrawable =
        bg(ctx, color, radius, stroke)

    // ===== Layout helpers =====
    fun col(ctx: Context, vertical: Boolean): LinearLayout {
        val ll = LinearLayout(ctx)
        ll.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        ll.setBackgroundColor(BG)
        return ll
    }

    /** pill-shaped button used across screens — now with consistent radii + elevation */
    fun btn(ctx: Context, label: String, accent: Boolean = true, small: Boolean = false): Button {
        val b = Button(ctx)
        b.text = label
        b.isAllCaps = false
        b.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        b.setTextColor(if (accent) ACCENT_FG else FG)
        b.textSize = if (small) 11f else 13.5f
        b.letterSpacing = 0.02f
        b.setPadding(dp(ctx, if (small) 12 else 16), 0, dp(ctx, if (small) 12 else 16), 0)
        b.minHeight = 0
        b.minimumHeight = 0
        b.elevation = dpf(ctx, if (accent) ELEV_LOW else 0f)
        if (small) b.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 36))
        else b.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 44))
        val g = GradientDrawable()
        g.cornerRadius = dpf(ctx, if (small) 12f else R_M)
        if (accent) {
            g.setColor(ACCENT)
            g.setStroke(dp(ctx, 1), ACCENT_BORDER)
        } else {
            g.setColor(BG3)
            g.setStroke(dp(ctx, 1), Color.argb(90, 255, 255, 255))
        }
        b.background = g
        return b
    }

    /** Small chip button — 36dp min touch, consistent pill shape */
    fun chip(ctx: Context, label: String): TextView {
        val t = TextView(ctx)
        t.text = label
        t.gravity = Gravity.CENTER
        t.setTextColor(FG)
        t.textSize = 12f
        t.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        t.letterSpacing = 0.02f
        t.setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0)
        t.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 36))
        t.minHeight = dp(ctx, 36)
        val g = GradientDrawable()
        g.cornerRadius = dpf(ctx, 18f)
        g.setColor(BG3)
        g.setStroke(dp(ctx, 1), Color.argb(70, 255, 255, 255))
        t.background = g
        return t
    }

    fun chipAccent(ctx: Context, label: String): TextView {
        val t = chip(ctx, label)
        t.setTextColor(ACCENT_FG)
        t.background = pillBg(ctx, ACCENT, ACCENT_BORDER)
        return t
    }

    fun chipDanger(ctx: Context, label: String): TextView {
        val t = chip(ctx, label)
        t.setTextColor(DANGER)
        t.background = pillBg(ctx, Color.argb(30, 235, 90, 90), Color.argb(80, 235, 90, 90))
        return t
    }

    fun title(ctx: Context, s: String): TextView {
        val t = TextView(ctx)
        t.text = s
        t.setTextColor(FG)
        t.textSize = 18f
        t.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        t.letterSpacing = -0.01f
        t.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        return t
    }

    fun titleSmall(ctx: Context, s: String): TextView {
        val t = title(ctx, s)
        t.textSize = 15f
        return t
    }

    fun label(ctx: Context, s: String, dim: Boolean = false, size: Float = 12f): TextView {
        val t = TextView(ctx)
        t.text = s
        t.setTextColor(if (dim) FG2 else FG)
        t.textSize = size
        t.letterSpacing = 0.01f
        t.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        return t
    }

    fun labelMuted(ctx: Context, s: String, size: Float = 11f): TextView {
        return label(ctx, s, dim = true, size = size).apply {
            setTextColor(TEXT_MUTED)
        }
    }

    fun margin(v: View, l: Int, t: Int, r: Int, b: Int, ctx: Context) {
        val lp = v.layoutParams as? LinearLayout.LayoutParams ?: return
        lp.setMargins(dp(ctx, l), dp(ctx, t), dp(ctx, r), dp(ctx, b))
    }

    fun styleWindow(a: Activity, lightNav: Boolean = true) {
        val w = a.window
        w.statusBarColor = BG
        w.navigationBarColor = BG
    }

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
