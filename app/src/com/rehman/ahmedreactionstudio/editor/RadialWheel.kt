package com.rehman.ahmedreactionstudio.editor

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.rehman.ahmedreactionstudio.util.UI
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Contextual radial control wheel (OBS plan §4.2).
 *
 * Tap ◉ and a hub (the source itself) springs in with 6–8 petals flying out
 * around it — the petal set depends on the selected source type. Petals are
 * the most-used controls; everything deeper lives in the long-press sheet.
 *
 * Interaction:
 *   petal tap      → haptic + flash + action + wheel closes
 *   hub / scrim tap → dismiss
 */
class RadialWheelView(context: Context) : FrameLayout(context) {

    class Petal(
        val icon: Int,
        val label: String,
        val active: Boolean = false,      // engaged state (accent tint)
        val danger: Boolean = false,      // destructive (red tint)
        val action: () -> Unit
    )

    var onDismiss: (() -> Unit)? = null

    private var open = false
    private var dismissing = false
    private val animViews = ArrayList<View>()

    private val scrim = View(context)
    private val hubBg = ImageView(context)
    private val hubIcon = ImageView(context)
    private val hubName = TextView(context)

    init {
        visibility = View.GONE
        val sg = GradientDrawable()
        sg.shape = GradientDrawable.OVAL
        sg.setColor(Color.argb(235, 16, 19, 26))
        sg.setStroke(UI.dp(context, 2), UI.ACCENT)
        hubBg.background = sg
        hubIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
        hubName.setTextColor(Color.WHITE)
        hubName.textSize = 12f
        hubName.gravity = Gravity.CENTER
        hubName.setShadowLayer(6f, 0f, 2f, Color.BLACK)
    }

    fun isOpen(): Boolean = open

    /**
     * Pop the wheel. [anchorX]/[anchorY] are the screen position of the ◉
     * button (the wheel blooms around it); pass -1 to bloom at screen centre.
     */
    fun show(hubIconRes: Int, name: String, petals: List<Petal>,
             anchorX: Float = -1f, anchorY: Float = -1f) {
        if (open) return
        open = true
        dismissing = false
        visibility = View.VISIBLE
        removeAllViews()
        animViews.clear()

        scrim.setBackgroundColor(Color.argb(150, 4, 5, 8))
        scrim.alpha = 0f
        scrim.setOnClickListener { dismiss(true) }
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val go = Runnable { layoutAndAnimate(hubIconRes, name, petals, anchorX, anchorY) }
        if (width > 0 && height > 0) post(go)
        else addOnLayoutChangeListener(object : OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int,
                                        ol: Int, ot: Int, or: Int, ob: Int) {
                removeOnLayoutChangeListener(this)
                post(go)
            }
        })
    }

    private fun layoutAndAnimate(hubIconRes: Int, name: String, petals: List<Petal>,
                                 anchorX: Float, anchorY: Float) {
        val dp = UI.dp(context, 1)
        val petalSize = 54 * dp
        val hubSize = 74 * dp
        val labelGap = 16 * dp
        val n = petals.size

        // radius big enough that petals never overlap
        val minR = ((petalSize + 12 * dp) * n) / (2f * PI).toFloat()
        val radius = max(118f * dp, minR)

        // wheel centre: around the ◉ button, clamped fully on screen
        var cx = if (anchorX >= 0) anchorX else width / 2f
        var cy = if (anchorY >= 0) anchorY else height / 2f
        val ext = radius + petalSize / 2f + labelGap + 12 * dp
        cx = cx.coerceIn(ext, (width - ext).coerceAtLeast(ext))
        cy = cy.coerceIn(ext, (height - ext).coerceAtLeast(ext))

        // ---- hub ----
        hubBg.setOnClickListener { dismiss(true) }
        addView(hubBg, LayoutParams(hubSize, hubSize, Gravity.START or Gravity.TOP))
        hubBg.x = cx - hubSize / 2f
        hubBg.y = cy - hubSize / 2f
        hubBg.scaleX = 0.3f; hubBg.scaleY = 0.3f; hubBg.alpha = 0f; hubBg.rotation = -50f
        val iconPad = 16 * dp
        addView(hubIcon, LayoutParams(hubSize - iconPad * 2, hubSize - iconPad * 2,
            Gravity.START or Gravity.TOP))
        hubIcon.x = cx - hubSize / 2f + iconPad
        hubIcon.y = cy - hubSize / 2f + iconPad
        hubIcon.setImageDrawable(Ic.get(context, hubIconRes, UI.ACCENT2))
        hubIcon.scaleX = 0.3f; hubIcon.scaleY = 0.3f; hubIcon.alpha = 0f
        hubIcon.setOnClickListener { dismiss(true) }

        addView(hubName, LayoutParams(200 * dp, LayoutParams.WRAP_CONTENT,
            Gravity.START or Gravity.TOP))
        hubName.text = name
        hubName.x = cx - 100 * dp
        hubName.y = cy + hubSize / 2f + 8 * dp
        hubName.alpha = 0f

        hubBg.animate().scaleX(1f).scaleY(1f).alpha(1f).rotation(0f)
            .setDuration(300).setInterpolator(OvershootInterpolator(1.8f)).start()
        hubIcon.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(300)
            .setInterpolator(OvershootInterpolator(1.8f)).start()
        hubName.animate().alpha(1f).setStartDelay(120).setDuration(200).start()
        animViews.add(hubBg); animViews.add(hubIcon); animViews.add(hubName)

        // ---- petals, staggered spring ----
        val startAngle = -PI / 2.0
        for ((i, pt) in petals.withIndex()) {
            val ang = startAngle + i * 2.0 * PI / n
            val px = cx + (radius * cos(ang)).toFloat()
            val py = cy + (radius * sin(ang)).toFloat()

            val petal = makePetal(pt, petalSize)
            addView(petal)
            petal.x = cx - petalSize / 2f
            petal.y = cy - petalSize / 2f
            petal.scaleX = 0f; petal.scaleY = 0f; petal.alpha = 0f

            val label = TextView(context)
            label.text = pt.label
            label.textSize = 9.5f
            label.gravity = Gravity.CENTER
            label.setTextColor(if (pt.danger) UI.DANGER else Color.argb(235, 255, 255, 255))
            label.setShadowLayer(4f, 0f, 1f, Color.BLACK)
            addView(label, LayoutParams(96 * dp, LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.TOP))
            label.x = px - 48 * dp
            label.y = py + petalSize / 2f + 1 * dp
            label.alpha = 0f

            val delay = 40L + i * 26L
            petal.animate()
                .x(px - petalSize / 2f).y(py - petalSize / 2f)
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setStartDelay(delay).setDuration(320)
                .setInterpolator(OvershootInterpolator(1.6f)).start()
            label.animate().alpha(1f).setStartDelay(delay + 140).setDuration(180).start()
            animViews.add(petal); animViews.add(label)
        }

        scrim.animate().alpha(1f).setDuration(220).start()
    }

    private fun makePetal(pt: Petal, size: Int): FrameLayout {
        val wrap = FrameLayout(context)
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(Color.argb(238, 18, 21, 29))
        val tint = when {
            pt.danger -> UI.DANGER
            pt.active -> UI.ACCENT2
            else -> UI.FG
        }
        bg.setStroke(UI.dp(context, 1),
            if (pt.active || pt.danger) tint else Color.argb(70, 255, 255, 255))
        wrap.background = bg
        wrap.layoutParams = LayoutParams(size, size, Gravity.START or Gravity.TOP)

        val im = ImageView(context)
        im.setImageDrawable(Ic.get(context, pt.icon, tint))
        val pad = UI.dp(context, 14)
        im.setPadding(pad, pad, pad, pad)
        wrap.addView(im, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        wrap.setOnClickListener {
            if (dismissing) return@setOnClickListener
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            // flash feedback, then action + close
            wrap.animate().scaleX(1.18f).scaleY(1.18f).setDuration(90)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: Animator) {
                        pt.action()
                        dismiss(true)
                    }
                }).start()
        }
        return wrap
    }

    fun dismiss(animated: Boolean) {
        if (!open || dismissing) return
        dismissing = true
        if (!animated) { finishDismiss(); return }
        scrim.animate().alpha(0f).setDuration(180).start()
        val n = animViews.size
        for ((i, v) in animViews.withIndex()) {
            v.animate().cancel()
            v.animate().scaleX(0f).scaleY(0f).alpha(0f)
                .setStartDelay(((n - i) * 8L).coerceAtMost(90L))
                .setDuration(150)
                .setInterpolator(AccelerateInterpolator())
                .start()
        }
        postDelayed({ finishDismiss() }, 320)
    }

    private fun finishDismiss() {
        open = false
        dismissing = false
        removeAllViews()
        animViews.clear()
        visibility = View.GONE
        onDismiss?.invoke()
    }
}
