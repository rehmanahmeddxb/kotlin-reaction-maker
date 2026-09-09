package com.rehman.ahmedreactionstudio.editor

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.rehman.ahmedreactionstudio.R
import com.rehman.ahmedreactionstudio.util.UI

object Ic {
    fun typeIcon(t: com.rehman.ahmedreactionstudio.core.LayerType): Int = when (t) {
        com.rehman.ahmedreactionstudio.core.LayerType.VIDEO -> R.drawable.ic_video
        com.rehman.ahmedreactionstudio.core.LayerType.CAMERA -> R.drawable.ic_camera
        com.rehman.ahmedreactionstudio.core.LayerType.SCREEN -> R.drawable.ic_screen
        com.rehman.ahmedreactionstudio.core.LayerType.IMAGE -> R.drawable.ic_image
        com.rehman.ahmedreactionstudio.core.LayerType.TEXT -> R.drawable.ic_text
    }

    fun get(ctx: Context, resId: Int, tint: Int = UI.FG): Drawable {
        val d = ctx.resources.getDrawable(resId, ctx.theme).mutate()
        d.setTint(tint)
        return d
    }

    fun pill(ctx: Context, color: Int, radiusDp: Float = 20f,
             stroke: Int = Color.argb(60, 255, 255, 255)): GradientDrawable {
        val g = GradientDrawable()
        g.cornerRadius = UI.dpf(ctx, radiusDp)
        g.setColor(color)
        g.setStroke(UI.dp(ctx, 1), stroke)
        return g
    }

    fun chipBg(ctx: Context, color: Int, radiusDp: Float = 12f, stroke: Int = Color.TRANSPARENT): GradientDrawable {
        val g = GradientDrawable()
        g.cornerRadius = UI.dpf(ctx, radiusDp)
        g.setColor(color)
        if (stroke != Color.TRANSPARENT) g.setStroke(UI.dp(ctx, 1), stroke)
        return g
    }
}

/**
 * Polished icon button — 44dp minimum touch target, premium micro-interactions:
 * press pulse 86% dip + overshoot 2.6f, haptic tick, 10dp padding = 24dp icon in 44dp frame.
 */
class IconBtn(context: Context) : FrameLayout(context) {

    val image = ImageView(context)
    private var downScale = false

    init {
        val pad = UI.dp(context, 10)
        image.setPadding(pad, pad, pad, pad)
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        isClickable = true
        isFocusable = true
        minimumWidth = UI.dp(context, 44)
        minimumHeight = UI.dp(context, 44)
    }

    fun setIcon(resId: Int, tint: Int = UI.FG, desc: String? = null) {
        image.setImageDrawable(Ic.get(context, resId, tint))
        if (desc != null) contentDescription = desc
    }

    fun setSelectedState(selected: Boolean) {
        background = if (selected) Ic.chipBg(context, Color.argb(60, 255, 90, 44), 12f,
            Color.argb(90, 255, 130, 80)) else null
    }

    fun setIconAnimated(resId: Int, tint: Int = UI.FG, desc: String? = null) {
        animate().cancel()
        animate().scaleX(0.72f).scaleY(0.72f).setDuration(70).withEndAction {
            setIcon(resId, tint, desc)
            animate().scaleX(1f).scaleY(1f).setDuration(240)
                .setInterpolator(OvershootInterpolator(2.2f)).start()
        }.start()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downScale = true
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                animate().cancel()
                animate().scaleX(0.86f).scaleY(0.86f).setDuration(80).start()
            }
            MotionEvent.ACTION_UP -> {
                if (downScale) {
                    animate().cancel()
                    animate().scaleX(1f).scaleY(1f).setDuration(300)
                        .setInterpolator(OvershootInterpolator(2.6f)).start()
                }
                downScale = false
            }
            MotionEvent.ACTION_CANCEL -> {
                downScale = false
                animate().cancel()
                animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }
        }
        return super.onTouchEvent(event)
    }

    companion object {
        fun sized(ctx: Context, dpSize: Int = 44): LayoutParams =
            LayoutParams(UI.dp(ctx, dpSize), UI.dp(ctx, dpSize))
    }
}
