package com.rehman.ahmedreactionstudio.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.rehman.ahmedreactionstudio.util.UI

/**
 * Polished launcher — gradient window bg (no white flash) + badge with
 * dual pulse rings + wordmark + tagline + version. Timings aligned to
 * 1.2s intro from frontend ref blueprint 00-launch-and-shell.
 *
 * - Badge 96dp, radius 26dp, gradient orange-red, stroke white 35%, elevation 8dp.
 * - Rings: 128dp, stroke 2dp, expanding 0.4→1.9×, alpha 0.55→0.
 * - Wordmark: "Ahmed" FG + " Reaction Studio" accent, 27sp Bold, 0.02 spacing.
 * - Tagline: 10.5sp muted, 0.26 spacing, 10dp top.
 * - Version: 10sp muted bottom 26dp.
 * - Motion: badge pop 900ms overshoot 2.4, brand rise 650ms decelerate 1.6
 *   delay 420ms, tagline fade 500ms delay 620ms, version fade 500ms delay 900ms.
 * - Exit: alpha 1→0 + scale 1→1.08 320ms, then Home with no transition.
 */
class SplashActivity : Activity() {

    companion object {
        const val VERSION = "1.0.0"
    }

    private val main = Handler(Looper.getMainLooper())
    private val advance = Runnable { fadeOutAndGo() }
    private val launchHome = Runnable { goHome() }
    private var leaving = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        UI.styleWindow(this)

        val root = FrameLayout(this)
        root.clipChildren = false
        root.clipToPadding = false
        root.setBackgroundColor(Color.TRANSPARENT)

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.clipChildren = false
        box.clipToPadding = false
        box.setBackgroundColor(Color.TRANSPARENT)

        // ---- brand block ----
        val ringHost = FrameLayout(this)
        ringHost.clipChildren = false
        ringHost.clipToPadding = false
        val ring1 = makeRing(UI.ACCENT)
        val ring2 = makeRing(UI.ACCENT2)
        ringHost.addView(ring1)
        ringHost.addView(ring2)

        val badge = makeBadge()
        ringHost.addView(badge, FrameLayout.LayoutParams(
            UI.dp(this, 96), UI.dp(this, 96), Gravity.CENTER))
        box.addView(ringHost, LinearLayout.LayoutParams(
            UI.dp(this, 150), UI.dp(this, 150)))

        val wordmark = LinearLayout(this)
        wordmark.orientation = LinearLayout.HORIZONTAL
        wordmark.gravity = Gravity.CENTER_HORIZONTAL
        wordmark.setPadding(0, UI.dp(this, 8), 0, 0)

        fun word(text: String, color: Int, space: Float): TextView {
            val t = TextView(this)
            t.text = text
            t.setTextColor(color)
            t.textSize = 27f
            t.letterSpacing = space
            t.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            t.includeFontPadding = false
            wordmark.addView(t)
            return t
        }
        word("Ahmed", UI.FG, 0.02f)
        word(" Reaction Studio", Color.rgb(255, 122, 60), 0.02f)
        box.addView(wordmark)

        val tagline = TextView(this)
        tagline.text = "RECORD  ·  LAYER  ·  REACT  ·  EXPORT"
        tagline.setTextColor(UI.FG2)
        tagline.textSize = 10.5f
        tagline.letterSpacing = 0.26f
        tagline.gravity = Gravity.CENTER_HORIZONTAL
        tagline.setPadding(0, UI.dp(this, 10), 0, 0)
        tagline.includeFontPadding = false
        tagline.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        box.addView(tagline)

        root.addView(box, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        val version = TextView(this)
        version.text = "v$VERSION  ·  Local-first · No cloud"
        version.setTextColor(UI.TEXT_MUTED)
        version.textSize = 10f
        version.letterSpacing = 0.04f
        version.gravity = Gravity.CENTER_HORIZONTAL
        version.includeFontPadding = false
        root.addView(version, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = UI.dp(this@SplashActivity, 28)
            })

        setContentView(root)

        // initial hidden
        badge.alpha = 0f
        badge.scaleX = 0.32f
        badge.scaleY = 0.32f
        wordmark.alpha = 0f
        wordmark.translationY = UI.dp(this, 28).toFloat()
        tagline.alpha = 0f
        version.alpha = 0f

        // badge pop
        ObjectAnimator.ofPropertyValuesHolder(badge,
            PropertyValuesHolder.ofFloat("scaleX", 0.32f, 1f),
            PropertyValuesHolder.ofFloat("scaleY", 0.32f, 1f),
            PropertyValuesHolder.ofFloat("alpha", 0f, 1f)
        ).apply {
            duration = 900L
            interpolator = OvershootInterpolator(2.4f)
            start()
        }
        // wordmark rise
        ObjectAnimator.ofPropertyValuesHolder(wordmark,
            PropertyValuesHolder.ofFloat("alpha", 0f, 1f),
            PropertyValuesHolder.ofFloat("translationY", wordmark.translationY, 0f)
        ).apply {
            duration = 650L
            startDelay = 420L
            interpolator = DecelerateInterpolator(1.6f)
            start()
        }
        ObjectAnimator.ofPropertyValuesHolder(tagline,
            PropertyValuesHolder.ofFloat("alpha", 0f, 1f)
        ).apply {
            duration = 500L
            startDelay = 620L
            start()
        }
        ObjectAnimator.ofPropertyValuesHolder(version,
            PropertyValuesHolder.ofFloat("alpha", 0f, 0.85f)
        ).apply {
            duration = 500L
            startDelay = 900L
            start()
        }
        pulse(ring1, 150L, 1900L)
        pulse(ring2, 1000L, 1900L)

        main.postDelayed(advance, 2400L)
    }

    private fun makeRing(color: Int): TextView {
        val t = TextView(this)
        val size = UI.dp(this, 128)
        t.layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(Color.TRANSPARENT)
        g.setStroke(UI.dp(this, 2), color)
        t.background = g
        t.alpha = 0f
        return t
    }

    private fun makeBadge(): TextView {
        val t = TextView(this)
        t.text = "\u25B6"
        t.gravity = Gravity.CENTER
        t.setTextColor(Color.WHITE)
        t.textSize = 34f
        t.setPadding(UI.dp(this, 4), 0, 0, 0)
        t.elevation = UI.dpf(this, UI.ELEV_MED)
        val g = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(255, 145, 60), Color.rgb(238, 60, 28)))
        g.cornerRadius = UI.dpf(this, 26f)
        g.setStroke(UI.dp(this, 1), Color.argb(90, 255, 255, 255))
        t.background = g
        return t
    }

    private fun pulse(v: View, delayMs: Long, durMs: Long) {
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = durMs
        anim.startDelay = delayMs
        anim.repeatCount = ValueAnimator.INFINITE
        anim.addUpdateListener {
            val p = it.animatedValue as Float
            val s = 0.4f + 1.5f * p
            v.scaleX = s
            v.scaleY = s
            v.alpha = (0.55f * (1f - p)).coerceIn(0f, 0.55f)
        }
        anim.start()
    }

    private fun fadeOutAndGo() {
        if (leaving) return
        leaving = true
        val block = findViewById<View>(android.R.id.content)
        ObjectAnimator.ofPropertyValuesHolder(block,
            PropertyValuesHolder.ofFloat("alpha", 1f, 0f),
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.06f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.06f)
        ).apply {
            duration = 320L
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    main.postDelayed(launchHome, 40L)
                }
            })
            start()
        }
    }

    private fun goHome() {
        if (isFinishing) return
        startActivity(Intent(this, HomeActivity::class.java))
        overridePendingTransition(0, 0)
        main.postDelayed({ finish() }, 60L)
    }

    override fun onDestroy() {
        leaving = true
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
