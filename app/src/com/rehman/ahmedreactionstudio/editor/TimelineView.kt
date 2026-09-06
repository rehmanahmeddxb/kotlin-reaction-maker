package com.rehman.ahmedreactionstudio.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.rehman.ahmedreactionstudio.core.Layer
import com.rehman.ahmedreactionstudio.util.UI

/**
 * A compact, truthful timeline: one lane per clip-backed source (video,
 * screen record, camera take), each bar spanning the time the clip plays
 * inside the composition (clips start at 0 and run for their own duration;
 * the composition lasts as long as the longest one), plus the master
 * playhead. Everything is drawn from the model on every [bind], so it can
 * never go stale, and dragging on it seeks the engine like the transport
 * slider does.
 *
 * It is deliberately small: 16dp per lane, at most [MAX_LANES] lanes
 * ("+N more" beyond that), GONE when the project has no clips. This version
 * of the app has no per-clip trim or offset, so the timeline does not
 * pretend to offer them.
 */
class TimelineView(context: Context) : View(context) {

    interface Listener {
        fun onScrubStart()
        fun onScrub(ms: Long)
        fun onScrubEnd()
    }

    var listener: Listener? = null

    private var lanes: List<Layer> = emptyList()
    private var durationMs = 1L
    private var playheadMs = 0L
    private var extra = 0

    private val labelW = UI.dp(context, 72)
    private val laneH = UI.dp(context, 16)
    private val padV = UI.dp(context, 5)
    private val padH = UI.dp(context, 10)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = UI.dpf(context, 10f)
        color = UI.FG2
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UI.ACCENT2
        strokeWidth = UI.dpf(context, 1.5f)
    }
    private val trackPaint = Paint().apply { color = Color.argb(28, 255, 255, 255) }
    private val tmp = RectF()

    init {
        setBackgroundColor(Color.rgb(11, 13, 17))
        contentDescription = "Timeline"
    }

    /** Re-read the model. Returns true when the measured height changed (caller re-lays the chrome). */
    fun bind(layers: List<Layer>, durationMs: Long): Boolean {
        val clips = layers.asReversed().filter { it.isClip() }   // top z first, like the dock
        val before = desiredHeight()
        lanes = clips.take(MAX_LANES)
        extra = (clips.size - MAX_LANES).coerceAtLeast(0)
        this.durationMs = durationMs.coerceAtLeast(1L)
        invalidate()
        val after = desiredHeight()
        if (before != after) requestLayout()
        return before != after
    }

    fun hasLanes(): Boolean = lanes.isNotEmpty()

    fun setPlayhead(ms: Long) {
        val w = trackWidth()
        if (w <= 0) { playheadMs = ms; return }
        val oldX = (playheadMs.toFloat() / durationMs * w).toInt()
        val newX = (ms.toFloat() / durationMs * w).toInt()
        playheadMs = ms
        if (oldX != newX) invalidate()
    }

    private fun desiredHeight(): Int {
        if (lanes.isEmpty()) return 0
        val rows = lanes.size + (if (extra > 0) 1 else 0)
        return padV * 2 + rows * laneH
    }

    private fun trackWidth(): Int = width - labelW - padH * 2

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, desiredHeight())
    }

    override fun onDraw(canvas: Canvas) {
        if (lanes.isEmpty()) return
        val tw = trackWidth().toFloat()
        val x0 = (labelW + padH).toFloat()
        var y = padV.toFloat()
        val ascent = -textPaint.ascent()
        for (l in lanes) {
            // label
            textPaint.color = if (l.visible) UI.FG else Color.argb(120, 235, 238, 245)
            val name = ellipsize(l.name.ifBlank { l.type.label }, labelW - padH - UI.dp(context, 4))
            canvas.drawText(name, padH.toFloat(), y + (laneH + ascent) / 2f - UI.dpf(context, 1.5f), textPaint)
            // full track
            tmp.set(x0, y + UI.dpf(context, 4f), x0 + tw, y + laneH - UI.dpf(context, 4f))
            canvas.drawRoundRect(tmp, UI.dpf(context, 2f), UI.dpf(context, 2f), trackPaint)
            // the clip's span
            val frac = (l.durMs.toFloat() / durationMs).coerceIn(0f, 1f)
            // (fully qualified: inside a View subclass `LayerType` is View.LayerType)
            val base = when (l.type) {
                com.rehman.ahmedreactionstudio.core.LayerType.SCREEN -> Color.rgb(64, 196, 255)
                com.rehman.ahmedreactionstudio.core.LayerType.CAMERA -> Color.rgb(105, 240, 174)
                else -> UI.ACCENT
            }
            val alpha = when {
                !l.visible -> 70
                l.muted -> 130
                !l.playing -> 150
                else -> 230
            }
            barPaint.color = Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base))
            tmp.set(x0, y + UI.dpf(context, 4f), x0 + tw * frac, y + laneH - UI.dpf(context, 4f))
            canvas.drawRoundRect(tmp, UI.dpf(context, 2f), UI.dpf(context, 2f), barPaint)
            y += laneH
        }
        if (extra > 0) {
            textPaint.color = UI.FG2
            canvas.drawText("+$extra more", padH.toFloat(), y + (laneH + ascent) / 2f - UI.dpf(context, 1.5f), textPaint)
        }
        // playhead over every lane
        val px = x0 + tw * (playheadMs.toFloat() / durationMs).coerceIn(0f, 1f)
        canvas.drawLine(px, padV.toFloat(), px, (height - padV).toFloat(), headPaint)
    }

    private fun ellipsize(s: String, maxPx: Int): String {
        if (textPaint.measureText(s) <= maxPx) return s
        var n = s.length
        while (n > 1 && textPaint.measureText(s.substring(0, n) + "…") > maxPx) n--
        return s.substring(0, n) + "…"
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (lanes.isEmpty()) return false
        val tw = trackWidth()
        if (tw <= 0) return false
        val ms = (((e.x - labelW - padH) / tw).coerceIn(0f, 1f) * durationMs).toLong()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                listener?.onScrubStart(); listener?.onScrub(ms); setPlayhead(ms)
            }
            MotionEvent.ACTION_MOVE -> { listener?.onScrub(ms); setPlayhead(ms) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                listener?.onScrubEnd()
                if (e.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        const val MAX_LANES = 4
    }
}
