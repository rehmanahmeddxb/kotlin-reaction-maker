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
 * Animated launcher screen.
 *
 * A soft gradient window background (drawn by the theme before the first
 * frame, so there is never a white flash), then a springy brand badge with
 * expanding pulse rings, a letter-spaced wordmark and a tagline. Once the
 * intro plays (~2.3 s) the whole block cross-fades out and HomeActivity opens.
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

        // ---- animated brand block ----
        val ringHost = FrameLayout(this)
        ringHost.clipChildren = false
        ringHost.clipToPadding = false
        val ring1 = makeRing(Color.rgb(255, 90, 44))
        val ring2 = makeRing(Color.rgb(255, 160, 44))
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
        wordmark.setPadding(0, UI.dp(this, 6), 0, 0)

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

        val tagline = UI.label(this, "RECORD  \u00b7  LAYER  \u00b7  REACT  \u00b7  EXPORT", dim = true, size = 10.5f)
        tagline.letterSpacing = 0.26f
        tagline.gravity = Gravity.CENTER_HORIZONTAL
        tagline.setPadding(0, UI.dp(this, 10), 0, 0)
        box.addView(tagline)

        root.addView(box, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        val version = UI.label(this, "v$VERSION", dim = true, size = 10f)
        version.gravity = Gravity.CENTER_HORIZONTAL
        root.addView(version, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = UI.dp(this@SplashActivity, 26)
            })

        setContentView(root)

        val brand = wordmark
        val block = box
        // initial hidden states
        badge.alpha = 0f
        badge.scaleX = 0.3f
        badge.scaleY = 0.3f
        brand.alpha = 0f
        brand.translationY = UI.dp(this, 26).toFloat()
        tagline.alpha = 0f
        version.alpha = 0f

        // 1) springy badge pop-in
        ObjectAnimator.ofPropertyValuesHolder(badge,
            PropertyValuesHolder.ofFloat("scaleX", 0.3f, 1f),
            PropertyValuesHolder.ofFloat("scaleY", 0.3f, 1f),
            PropertyValuesHolder.ofFloat("alpha", 0f, 1f)
        ).apply {
            duration = 900L
            interpolator = OvershootInterpolator(2.4f)
            start()
        }
        // 2) wordmark + tagline rise in
        ObjectAnimator.ofPropertyValuesHolder(brand,
            PropertyValuesHolder.ofFloat("alpha", 0f, 1f),
            PropertyValuesHolder.ofFloat("translationY", brand.translationY, 0f)
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
        // 3) infinite expanding pulse rings behind the badge
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
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.08f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.08f)
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
