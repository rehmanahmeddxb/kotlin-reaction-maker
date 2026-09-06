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
 * The OBS-style SOURCE DOCK (plan §4.3): every source as a mixer row.
 *
 *   [👁]  🎥 Camera take                          ⠿
 *         LIVE
 *   [👁]  🎬 My Video.mp4                   [🔇]  ⠿
 *         SOLO · PAUSED
 *
 * Rows are in Z order (top row = front-most). Tap selects, eye/mute toggle
 * instantly, long-press opens the advanced sheet and the ⠿ handle drag-
 * reorders the composition live (one undo step per drag).
 */
class SourceDock(
    private val act: Activity,
    private val container: LinearLayout,
    private val projectRef: () -> Project,
    private val selectedId: () -> String?,
    private val onSelect: (String?) -> Unit,
    private val onQuickToggle: (Layer, String) -> Unit,   // "vis" | "mute"
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
            val t = UI.label(act, "No sources yet — tap Add to create one.", dim = true, size = 12.5f)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(UI.dp(act, 14), UI.dp(act, 10), UI.dp(act, 14), UI.dp(act, 10))
            t.layoutParams = lp
            container.addView(t)
            return
        }
        // top-most z first (OBS mixer order)
        for (i in p.layers.indices.reversed()) {
            container.addView(buildRow(p.layers[i]))
        }
    }

    /**
     * One row, 52dp:  [eye] · type · name / status … [mute] [⠿]
     *
     * Fixed pieces are 44dp targets (eye, mute for clips, drag handle) and a
     * 30dp type glyph; the name/status column is the only flexible piece, so
     * it gets everything that is left — 64dp at the 240dp phone-landscape
     * panel minimum, ~120dp at a 300dp panel — and ellipsizes, never clips.
     * State (LIVE · MUTED · PAUSED · SOLO · LOOP · LOCKED · FIT) is the status
     * line, not a row of badges that would squeeze the name to nothing.
     */
    private fun buildRow(l: Layer): LinearLayout {
        val selected = l.id == selectedId()
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(UI.dp(act, 4), 0, UI.dp(act, 4), 0)
        val bg = GradientDrawable()
        bg.cornerRadius = UI.dpf(act, 10f)
        bg.setColor(if (selected) Color.argb(70, 255, 90, 44) else Color.argb(120, 20, 23, 31))
        bg.setStroke(UI.dp(act, 1),
            if (selected) Color.argb(255, 255, 130, 80) else Color.argb(40, 255, 255, 255))
        row.background = bg
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(act, ROW_DP))
        lp.setMargins(UI.dp(act, 2), UI.dp(act, 2), UI.dp(act, 2), UI.dp(act, 2))
        row.layoutParams = lp

        // --- eye (visibility) ---
        val eye = IconBtn(act)
        eye.layoutParams = IconBtn.sized(act, 44)
        eye.setIcon(
            if (l.visible) R.drawable.ic_eye else R.drawable.ic_eye_off,
            if (l.visible) UI.FG else Color.argb(120, 255, 255, 255),
            if (l.visible) "Hide ${l.name}" else "Show ${l.name}")
        eye.setOnClickListener { onQuickToggle(l, "vis") }
        row.addView(eye)

        // --- type icon (18dp glyph in a 30dp slot; same x on every row) ---
        val typeIc = ImageView(act)
        typeIc.setImageDrawable(Ic.get(act, Ic.typeIcon(l.type),
            if (!l.visible) Color.argb(120, 255, 255, 255) else if (l.isLive()) UI.OK else UI.ACCENT2))
        val tlp = LinearLayout.LayoutParams(UI.dp(act, 18), UI.dp(act, 18))
        tlp.setMargins(UI.dp(act, 4), 0, UI.dp(act, 8), 0)
        typeIc.layoutParams = tlp
        row.addView(typeIc)

        // --- name + status: the flexible column ---
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

        val nm = TextView(act)
        nm.text = l.name.ifBlank { l.type.label }
        nm.setTextColor(if (l.visible) Color.WHITE else Color.argb(150, 255, 255, 255))
        nm.textSize = 13f
        nm.typeface = Typeface.create("sans-serif-medium", if (selected) Typeface.BOLD else Typeface.NORMAL)
        nm.maxLines = 1
        nm.ellipsize = android.text.TextUtils.TruncateAt.END
        nm.includeFontPadding = false
        col.addView(nm)

        val st = TextView(act)
        st.text = statusOf(l)
        st.textSize = 10f
        st.setTextColor(statusColor(l))
        st.maxLines = 1
        st.ellipsize = android.text.TextUtils.TruncateAt.END
        st.includeFontPadding = false
        st.setPadding(0, UI.dp(act, 2), 0, 0)
        if (l.isClip()) {
            // the PAUSED/playing line is itself the play switch: one tap,
            // no ring dive, and TalkBack announces the state + action
            st.isClickable = true
            st.isFocusable = true
            st.contentDescription = if (l.playing) "Pause ${l.name}" else "Play ${l.name}"
            st.setOnClickListener { onPlayToggle(l) }
        }
        col.addView(st)
        row.addView(col)

        // --- mute (clips only; non-clip rows simply have a wider name) ---
        if (l.isClip()) {
            val mute = IconBtn(act)
            mute.layoutParams = IconBtn.sized(act, 44)
            val effMuted = l.muted || mutedBySolo(l)
            mute.setIcon(
                if (effMuted) R.drawable.ic_volume_off else R.drawable.ic_volume,
                if (effMuted) UI.DANGER else UI.FG,
                if (effMuted) "Unmute ${l.name}" else "Mute ${l.name}")
            mute.setOnClickListener { onQuickToggle(l, "mute") }
            row.addView(mute)
        }

        // --- drag handle ---
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

    private fun mutedBySolo(l: Layer): Boolean {
        val p = projectRef()
        return p.layers.any { it.solo } && !l.solo
    }

    private fun statusOf(l: Layer): String {
        val bits = ArrayList<String>()
        if (!l.visible) bits.add("HIDDEN")
        if (l.isLive()) bits.add("LIVE")
        if (l.isClip()) {
            if (l.solo) bits.add("SOLO")
            if (mutedBySolo(l) && !l.muted) bits.add("MUTED BY SOLO")
            else if (l.muted) bits.add("MUTED")
            if (!l.playing) bits.add("PAUSED")
            if (l.loop) bits.add("LOOP")
        }
        if (l.locked) bits.add("LOCKED")
        if (l.fit == Layer.FIT_FIT && !l.isText()) bits.add("FIT")
        if (bits.isEmpty()) {
            return if (l.isLive()) "Live camera on the canvas"
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

    // ---------- drag-to-reorder Z ----------

    private fun handleTouch(ev: MotionEvent, row: LinearLayout, l: Layer): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragRow = row
                dragLayer = l
                onReorderStart()
                // the dock lives inside a ScrollView — stop it from stealing
                // the vertical drag once the handle owns the gesture
                row.parent?.requestDisallowInterceptTouchEvent(true)
                row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                val g = row.background as? GradientDrawable
                g?.setColor(Color.argb(150, 40, 45, 60))
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val r = dragRow ?: return true
                autoScroll(ev)
                val p = projectRef()
                val n = p.layers.size
                if (n < 2) return true
                val rowH = UI.dp(act, ROW_DP) + UI.dp(act, 4)
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
                    val g = it.background as? GradientDrawable
                    g?.setColor(Color.argb(120, 20, 23, 31))
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

    /** the ScrollView hosting the dock, if any (for edge auto-scroll) */
    private fun hostScroller(): android.widget.ScrollView? {
        var p: android.view.ViewParent? = container.parent
        while (p != null) {
            if (p is android.widget.ScrollView) return p
            p = p.parent
        }
        return null
    }

    /**
     * Edge auto-scroll while dragging: without it a row can never be dropped
     * onto an off-screen position, which made long dock lists un-reorderable.
     */
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
