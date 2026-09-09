package com.rehman.ahmedreactionstudio.editor

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.rehman.ahmedreactionstudio.R
import com.rehman.ahmedreactionstudio.core.Layer
import com.rehman.ahmedreactionstudio.core.Project
import com.rehman.ahmedreactionstudio.util.UI

/**
 * Polished SOURCE DOCK — OBS-style mixer rows, aligned to the studio design system.
 *
 * - Row: 52dp height, radius 14dp, BG2 + white 8% stroke, selected = orange wash 70% + orange stroke.
 * - Eye/mute: 44dp IconBtn, FG / DANGER tint, contentDescription per source.
 * - Type icon: 18dp ACCENT2 or muted 120 white when hidden.
 * - Name: 13.5sp Bold white, 1-line ellipsis, -0.01 spacing.
 * - Status: 10sp secondary, clickable for play/pause on clips.
 * - Badges: 7.5sp Bold pill, 60% bg + 140% stroke, margin 3dp.
 * - Drag handle: 44dp, 170 white, haptic long-press, auto-scroll at edges.
 */
class SourceDock(
    private val act: Activity,
    private val container: LinearLayout,
    private val projectRef: () -> Project,
    private val selectedId: () -> String?,
    private val onSelect: (String?) -> Unit,
    private val onQuickToggle: (Layer, String) -> Unit,
    private val onPlayToggle: (Layer) -> Unit,
    private val onLongPress: (Layer) -> Unit,
    private val onReorderStart: () -> Unit,
    private val onReorder: (fromLayerIdx: Int, toFinalLayerIdx: Int) -> Unit,
    private val onReorderEnd: () -> Unit
) {

    private val ROW_DP = 52
    private var dragRow: LinearLayout? = null
    private var dragLayer: Layer? = null

    fun rebuild() {
        container.removeAllViews()
        val p = projectRef()
        if (p.layers.isEmpty()) {
            val empty = LinearLayout(act)
            empty.orientation = LinearLayout.VERTICAL
            empty.setPadding(UI.dp(act, 14), UI.dp(act, 10), UI.dp(act, 14), UI.dp(act, 10))
            val t = TextView(act)
            t.text = "No sources yet"
            t.setTextColor(UI.FG)
            t.textSize = 12.5f
            t.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            empty.addView(t)
            val sub = TextView(act)
            sub.text = "Tap Add to create one — camera, video, image, screen or text."
            sub.setTextColor(UI.FG2)
            sub.textSize = 11f
            sub.setPadding(0, UI.dp(act, 2), 0, 0)
            empty.addView(sub)
            container.addView(empty)
            return
        }
        for (i in p.layers.indices.reversed()) {
            container.addView(buildRow(p.layers[i]))
        }
    }

    private fun buildRow(l: Layer): LinearLayout {
        val selected = l.id == selectedId()
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(UI.dp(act, 8), 0, UI.dp(act, 8), 0)
        row.background = if (selected) {
            UI.bg(act, Color.argb(70, 255, 90, 44), UI.R_L, Color.argb(255, 255, 130, 80))
        } else {
            UI.bg(act, Color.argb(120, 20, 23, 31), UI.R_L, Color.argb(40, 255, 255, 255))
        }
        row.elevation = if (selected) UI.dpf(act, 2f) else 0f
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(act, ROW_DP))
        lp.setMargins(UI.dp(act, 2), UI.dp(act, 3), UI.dp(act, 2), UI.dp(act, 3))
        row.layoutParams = lp

        // eye
        val eye = IconBtn(act)
        eye.layoutParams = IconBtn.sized(act, 44)
        eye.setIcon(
            if (l.visible) R.drawable.ic_eye else R.drawable.ic_eye_off,
            if (l.visible) UI.FG else Color.argb(120, 255, 255, 255),
            if (l.visible) "Hide ${l.name}" else "Show ${l.name}")
        eye.setOnClickListener { onQuickToggle(l, "vis") }
        row.addView(eye)

        // mute
        if (l.isVideoLike()) {
            val mute = IconBtn(act)
            mute.layoutParams = IconBtn.sized(act, 44)
            val effMuted = l.muted || mutedBySolo(l)
            mute.setIcon(
                if (effMuted) R.drawable.ic_volume_off else R.drawable.ic_volume,
                if (effMuted) UI.DANGER else UI.FG,
                if (effMuted) "Unmute ${l.name}" else "Mute ${l.name}")
            mute.setOnClickListener { onQuickToggle(l, "mute") }
            row.addView(mute)
        } else {
            val spacer = View(act)
            row.addView(spacer, LinearLayout.LayoutParams(UI.dp(act, 44), UI.dp(act, 44)))
        }

        // type icon
        val typeIc = ImageView(act)
        typeIc.setImageDrawable(Ic.get(act, Ic.typeIcon(l.type),
            if (l.visible) layerAccent(l) else Color.argb(120, 255, 255, 255)))
        val tlp = LinearLayout.LayoutParams(UI.dp(act, 18), UI.dp(act, 18))
        tlp.setMargins(UI.dp(act, 8), 0, UI.dp(act, 10), 0)
        typeIc.layoutParams = tlp
        row.addView(typeIc)

        // name + status
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(Color.TRANSPARENT)
        col.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

        val nm = TextView(act)
        nm.text = l.name.ifBlank { l.type.label }
        nm.setTextColor(if (l.visible) Color.WHITE else Color.argb(150, 255, 255, 255))
        nm.textSize = 13.5f
        nm.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        nm.letterSpacing = -0.01f
        nm.maxLines = 1
        nm.ellipsize = android.text.TextUtils.TruncateAt.END
        col.addView(nm)

        val st = TextView(act)
        st.text = statusOf(l)
        st.textSize = 10f
        st.letterSpacing = 0.02f
        st.setTextColor(statusColor(l))
        st.maxLines = 1
        st.ellipsize = android.text.TextUtils.TruncateAt.END
        if (l.isClip()) {
            st.isClickable = true
            st.isFocusable = true
            st.contentDescription = if (l.playing) "Pause ${l.name}" else "Play ${l.name}"
            st.setOnClickListener { onPlayToggle(l) }
        }
        col.addView(st)
        row.addView(col)

        // badges
        if (l.isLive()) row.addView(badge("LIVE", UI.OK))
        if (l.solo) row.addView(badge("SOLO", UI.ACCENT2))
        if (l.loop && l.isClip()) row.addView(badge("LOOP", UI.OK))
        if (l.locked) row.addView(badge("LOCK", Color.rgb(255, 200, 120)))

        // drag handle — 44dp touch, 170 white
        val handle = ImageView(act)
        handle.setImageDrawable(Ic.get(act, R.drawable.ic_drag, Color.argb(170, 255, 255, 255)))
        handle.setPadding(UI.dp(act, 10), UI.dp(act, 10), UI.dp(act, 10), UI.dp(act, 10))
        handle.contentDescription = "Drag to reorder ${l.name}"
        handle.layoutParams = LinearLayout.LayoutParams(UI.dp(act, 44), UI.dp(act, 44))
        handle.setOnTouchListener { _, ev -> handleTouch(ev, row, l) }
        row.addView(handle)

        row.contentDescription = "Select ${l.name}. ${statusOf(l)}"
        row.setOnClickListener { onSelect(if (selectedId() == l.id) null else l.id) }
        row.setOnLongClickListener { onLongPress(l); true }
        return row
    }

    private fun layerAccent(l: Layer): Int = when (l.type) {
        com.rehman.ahmedreactionstudio.core.LayerType.VIDEO -> UI.LAYER_VIDEO
        com.rehman.ahmedreactionstudio.core.LayerType.CAMERA -> if (l.camFacing == com.rehman.ahmedreactionstudio.core.Layer.FACING_FRONT) UI.LAYER_CAMERA_FRONT else UI.LAYER_CAMERA_BACK
        com.rehman.ahmedreactionstudio.core.LayerType.SCREEN -> UI.LAYER_SCREEN
        com.rehman.ahmedreactionstudio.core.LayerType.IMAGE -> UI.LAYER_IMAGE
        com.rehman.ahmedreactionstudio.core.LayerType.TEXT -> UI.LAYER_TEXT
    }

    private fun mutedBySolo(l: Layer): Boolean {
        val p = projectRef()
        return p.layers.any { it.solo } && !l.solo
    }

    private fun statusOf(l: Layer): String {
        val bits = ArrayList<String>()
        if (!l.visible) bits.add("HIDDEN")
        if (l.isLive()) bits.add("LIVE ON CANVAS")
        if (l.isVideoLike()) {
            if (mutedBySolo(l) && !l.muted) bits.add("MUTED BY SOLO")
            else if (l.muted) bits.add("MUTED")
        }
        if (l.isClip() && !l.playing) bits.add("PAUSED")
        if (l.locked) bits.add("LOCKED")
        if (!l.isText()) {
            if (l.fit == Layer.FIT_FIT) bits.add("FIT")
            else if (l.fit == Layer.FIT_STRETCH) bits.add("STRETCH")
        }
        if (bits.isEmpty()) {
            return if (l.isLive()) "Live camera · framing on canvas"
            else if (l.isClip()) "Visible · Sound on · Playing"
            else if (l.isText()) "Text overlay" else "Visible"
        }
        return bits.joinToString(" · ")
    }

    private fun statusColor(l: Layer): Int = when {
        !l.visible -> Color.argb(170, 255, 255, 255)
        mutedBySolo(l) || l.muted -> UI.DANGER
        l.isClip() && !l.playing -> UI.ACCENT2
        else -> Color.argb(140, 255, 255, 255)
    }

    private fun badge(text: String, color: Int): TextView {
        val b = TextView(act)
        b.text = text
        b.textSize = 7.5f
        b.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        b.setTextColor(color)
        b.gravity = Gravity.CENTER
        b.letterSpacing = 0.06f
        b.setPadding(UI.dp(act, 6), UI.dp(act, 2), UI.dp(act, 6), UI.dp(act, 2))
        val g = GradientDrawable()
        g.cornerRadius = UI.dpf(act, 6f)
        g.setColor(Color.argb(60, Color.red(color), Color.green(color), Color.blue(color)))
        g.setStroke(1, Color.argb(140, Color.red(color), Color.green(color), Color.blue(color)))
        b.background = g
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(UI.dp(act, 3), 0, UI.dp(act, 3), 0)
        b.layoutParams = lp
        return b
    }

    private fun handleTouch(ev: MotionEvent, row: LinearLayout, l: Layer): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragRow = row
                dragLayer = l
                onReorderStart()
                row.parent?.requestDisallowInterceptTouchEvent(true)
                row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                (row.background as? GradientDrawable)?.setColor(Color.argb(150, 40, 45, 60))
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val r = dragRow ?: return true
                autoScroll(ev)
                val p = projectRef()
                val n = p.layers.size
                if (n < 2) return true
                val rowH = UI.dp(act, ROW_DP) + UI.dp(act, 6)
                val yInList = ev.rawY - locationOf(container)[1]
                val visTarget = ((yInList - rowH / 2f) / rowH).toInt().coerceIn(0, n - 1)
                val visCur = container.indexOfChild(r)
                if (visTarget != visCur && visTarget in 0 until container.childCount) {
                    val dl = dragLayer ?: return true
                    val fromLayerIdx = p.layers.indexOf(dl)
                    val toFinalLayerIdx = n - 1 - visTarget
                    if (fromLayerIdx >= 0 && toFinalLayerIdx != fromLayerIdx) {
                        onReorder(fromLayerIdx, toFinalLayerIdx)
                        container.removeView(r)
                        container.addView(r, visTarget)
                        r.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                row.parent?.requestDisallowInterceptTouchEvent(false)
                dragRow?.let {
                    (it.background as? GradientDrawable)?.setColor(
                        if (it === row && l.id == selectedId()) Color.argb(70, 255, 90, 44)
                        else Color.argb(120, 20, 23, 31))
                }
                if (ev.actionMasked == MotionEvent.ACTION_UP) onReorderEnd()
                dragRow = null
                dragLayer = null
                return true
            }
        }
        return true
    }

    private fun locationOf(v: View): IntArray {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return loc
    }

    private fun hostScroller(): android.widget.ScrollView? {
        var p: android.view.ViewParent? = container.parent
        while (p != null) {
            if (p is android.widget.ScrollView) return p
            p = p.parent
        }
        return null
    }

    private fun autoScroll(ev: MotionEvent) {
        val sv = hostScroller() ?: return
        val loc = locationOf(sv)
        val edge = UI.dp(act, 64)
        val step = UI.dp(act, 14)
        val y = ev.rawY - loc[1]
        when {
            y < edge -> sv.smoothScrollBy(0, -step)
            y > sv.height - edge -> sv.smoothScrollBy(0, step)
        }
    }
}
