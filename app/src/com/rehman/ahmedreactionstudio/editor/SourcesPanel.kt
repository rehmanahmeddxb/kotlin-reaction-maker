package com.rehman.ahmedreactionstudio.editor

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.rehman.ahmedreactionstudio.R
import com.rehman.ahmedreactionstudio.core.Layer
import com.rehman.ahmedreactionstudio.util.UI

/**
 * SOURCES tab of the context panel.
 *
 *   ┌ Sources · 3                      [+ Add] ┐   ← header, always visible
 *   │  [👁][🔇] 🎬 My Video   Visible · Playing ⠿ │   ← SourceDock rows (scroll)
 *   │  [👁][  ] 📷 Camera     LIVE              ⠿ │
 *   ├──────────────────────────────────────────┤
 *   │  [▲] [▼]           [👁] [✎] [🗑]           │   ← acts on the selection
 *   └──────────────────────────────────────────┘
 *
 * There is exactly ONE source list: the OBS-style [SourceDock] renders its
 * rows into [dockContainer] (eye · mute · type · name/status · badges · drag
 * handle, tap = select, long-press = properties, ⠿ = live Z reorder). This
 * panel only adds the pinned header, the empty-project shortcuts and the
 * selection action row. Every verb goes through [Listener] → the activity's
 * SourceController, so undo/redo and preview == export hold by construction.
 */
class SourcesPanel(context: Context) : LinearLayout(context) {

    interface Listener {
        fun onSelect(id: String)
        fun onToggleVisible(id: String)
        fun onAdd()
        fun onAddCamera()
        fun onAddVideo()
        fun onAddImage()
        fun onAddScreen()
        fun onAddText()
        fun onRemove()
        fun onHide()
        fun onProperties()
        fun onMoveUp(id: String)
        fun onMoveDown(id: String)
    }

    var listener: Listener? = null

    /** The SourceDock renders its rows here. */
    val dockContainer = LinearLayout(context)

    private val count: TextView
    private val hiddenBadge: TextView
    private val shortcuts: LinearLayout
    private val upBtn: IconBtn
    private val downBtn: IconBtn
    private val hideBtn: IconBtn
    private val propsBtn: IconBtn
    private val removeBtn: IconBtn
    private val actionHint: TextView

    init {
        orientation = VERTICAL

        // ---- header --------------------------------------------------------
        val head = LinearLayout(context)
        head.orientation = HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(UI.dp(context, 12), 0, UI.dp(context, 8), 0)
        val title = TextView(context).apply {
            text = "Sources"
            setTextColor(UI.FG)
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            includeFontPadding = false
        }
        head.addView(title)
        count = TextView(context).apply {
            setTextColor(UI.FG2)
            textSize = 11.5f
            includeFontPadding = false
            setPadding(UI.dp(context, 6), 0, 0, 0)
        }
        head.addView(count, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        hiddenBadge = TextView(context).apply {
            textSize = 10.5f
            setTextColor(UI.FG)
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            setPadding(UI.dp(context, 8), 0, UI.dp(context, 8), 0)
            background = Ic.pill(context, Color.argb(60, 255, 255, 255), 13f, Color.argb(40, 255, 255, 255))
            visibility = View.GONE
        }
        head.addView(hiddenBadge, LayoutParams(LayoutParams.WRAP_CONTENT, UI.dp(context, 26)).apply { marginEnd = UI.dp(context, 6) })
        val add = TextView(context).apply {
            text = "+ Add"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(Color.WHITE)
            setPadding(UI.dp(context, 12), 0, UI.dp(context, 12), 0)
            // drawn as a 30dp pill, but the touch target is the full 40dp header
            background = android.graphics.drawable.InsetDrawable(
                Ic.pill(context, UI.ACCENT, 15f, Color.argb(120, 255, 200, 160)), 0, UI.dp(context, 5), 0, UI.dp(context, 5))
            contentDescription = "Add a source"
            setOnClickListener { listener?.onAdd() }
        }
        head.addView(add, LayoutParams(LayoutParams.WRAP_CONTENT, UI.dp(context, 40)))
        addView(head, LayoutParams(LayoutParams.MATCH_PARENT, UI.dp(context, 40)))

        // ---- list ---------------------------------------------------------
        val listCol = LinearLayout(context)
        listCol.orientation = VERTICAL
        listCol.setPadding(UI.dp(context, 6), UI.dp(context, 2), UI.dp(context, 6), UI.dp(context, 6))
        shortcuts = LinearLayout(context).apply { orientation = VERTICAL }
        listCol.addView(shortcuts, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        dockContainer.orientation = VERTICAL
        listCol.addView(dockContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val scroller = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            addView(listCol, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        buildShortcuts()

        // ---- selection action row -------------------------------------------
        val divider = View(context)
        divider.setBackgroundColor(Color.argb(40, 255, 255, 255))
        addView(divider, LayoutParams(LayoutParams.MATCH_PARENT, 1))
        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(UI.dp(context, 6), 0, UI.dp(context, 6), 0)
        fun action(icon: Int, desc: String, tint: Int = UI.FG, fn: () -> Unit): IconBtn {
            val b = IconBtn(context)
            b.layoutParams = IconBtn.sized(context, 40)
            b.setIcon(icon, tint, desc)
            b.setOnClickListener { fn() }
            row.addView(b)
            return b
        }
        upBtn = action(R.drawable.ic_up, "Bring selected source forward") { sel?.let { listener?.onMoveUp(it.id) } }
        downBtn = action(R.drawable.ic_down, "Send selected source backward") { sel?.let { listener?.onMoveDown(it.id) } }
        actionHint = TextView(context).apply {
            text = "Select a source"
            textSize = 10.5f
            setTextColor(UI.FG2)
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = false
        }
        row.addView(actionHint, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        hideBtn = action(R.drawable.ic_eye, "Hide selected source") { listener?.onHide() }
        propsBtn = action(R.drawable.ic_edit, "Properties of selected source") { listener?.onProperties() }
        removeBtn = action(R.drawable.ic_delete, "Remove selected source", UI.DANGER) { listener?.onRemove() }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, UI.dp(context, 44)))

        bind(emptyList(), null)
    }

    private var sel: Layer? = null

    /** "N hidden" in the header; tap selects the first hidden source. */
    fun setHidden(n: Int, onTap: () -> Unit) {
        if (n <= 0) { hiddenBadge.visibility = View.GONE; return }
        hiddenBadge.text = if (n == 1) "1 hidden" else "$n hidden"
        hiddenBadge.contentDescription = "$n hidden source" + (if (n == 1) "" else "s") + " — tap to select"
        hiddenBadge.setOnClickListener { onTap() }
        hiddenBadge.visibility = View.VISIBLE
    }

    private fun buildShortcuts() {
        shortcuts.removeAllViews()
        val hint = TextView(context).apply {
            text = "No sources yet. The first one becomes the canvas background:"
            setTextColor(UI.FG2)
            textSize = 11.5f
            setPadding(UI.dp(context, 6), UI.dp(context, 6), UI.dp(context, 6), UI.dp(context, 6))
        }
        shortcuts.addView(hint)
        shortcut("Live camera", R.drawable.ic_camera, "Add the live camera") { listener?.onAddCamera() }
        shortcut("Video file", R.drawable.ic_video, "Add a local video") { listener?.onAddVideo() }
        shortcut("Screen recording", R.drawable.ic_screen, "Record the screen as a source") { listener?.onAddScreen() }
        shortcut("Image", R.drawable.ic_image, "Add an image") { listener?.onAddImage() }
        shortcut("Text overlay", R.drawable.ic_text, "Add a text overlay") { listener?.onAddText() }
    }

    private fun shortcut(name: String, icon: Int, desc: String, onTap: () -> Unit) {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(UI.dp(context, 10), 0, UI.dp(context, 10), 0)
            isClickable = true
            isFocusable = true
            contentDescription = desc
            background = Ic.pill(context, Color.argb(120, 20, 23, 31), 12f, Color.argb(40, 255, 255, 255))
            setOnClickListener { onTap() }
        }
        val iconView = ImageView(context).apply {
            setImageDrawable(Ic.get(context, icon, UI.ACCENT2))
        }
        row.addView(iconView, LayoutParams(UI.dp(context, 20), UI.dp(context, 20)))
        val lbl = TextView(context).apply {
            text = name
            setTextColor(UI.FG)
            textSize = 13f
            setPadding(UI.dp(context, 10), 0, 0, 0)
        }
        row.addView(lbl, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        shortcuts.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, UI.dp(context, 44)).apply {
            setMargins(UI.dp(context, 2), UI.dp(context, 2), UI.dp(context, 2), UI.dp(context, 2))
        })
    }

    /**
     * Refresh header / empty state / action row. The rows themselves are drawn
     * by the SourceDock (EditorActivity.rebuildDock) into [dockContainer].
     */
    fun bind(layers: List<Layer>, selectedId: String?) {
        count.text = when (layers.size) {
            0 -> ""
            1 -> "· 1 source"
            else -> "· ${layers.size} sources"
        }
        shortcuts.visibility = if (layers.isEmpty()) View.VISIBLE else View.GONE
        dockContainer.visibility = if (layers.isEmpty()) View.GONE else View.VISIBLE

        sel = layers.firstOrNull { it.id == selectedId }
        val l = sel
        val has = l != null
        for (b in listOf(upBtn, downBtn, hideBtn, propsBtn, removeBtn)) {
            b.isEnabled = has
            b.alpha = if (has) 1f else 0.35f
        }
        if (l != null) {
            actionHint.text = l.name.ifBlank { l.type.label }
            actionHint.setTextColor(UI.FG)
            hideBtn.setIcon(if (l.visible) R.drawable.ic_eye_off else R.drawable.ic_eye, UI.FG,
                if (l.visible) "Hide ${l.name}" else "Show ${l.name}")
            val idx = layers.indexOf(l)
            upBtn.alpha = if (idx < layers.size - 1) 1f else 0.35f
            downBtn.alpha = if (idx > 0) 1f else 0.35f
        } else {
            actionHint.text = if (layers.isEmpty()) "" else "Select a source"
            actionHint.setTextColor(UI.FG2)
            hideBtn.setIcon(R.drawable.ic_eye, UI.FG, "Hide selected source")
        }
    }
}
