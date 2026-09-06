package com.rehman.ahmedreactionstudio.editor

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.rehman.ahmedreactionstudio.core.Layer
import com.rehman.ahmedreactionstudio.util.UI

/**
 * PROPS tab: the advanced sheet of the *currently selected* source, rebuilt
 * on every selection change (EditorActivity.bindSidePanels → [bind]).
 *
 * The panel owns a pinned header and a scrolling [content] column; the
 * activity's `fillAdvanced(l)` fills [content] with the same sections the
 * advanced sheet always had (appearance · playback & audio · text · arrange ·
 * danger); `openAdvancedSheet(l)` selects + switches to this tab. Two
 * consequences of hosting it here instead of a floating sheet:
 *  - it never covers the canvas (the panel is a sibling of the canvas cell),
 *  - it can never show a stale source: a new selection replaces it at once.
 */
class PropertiesPanel(context: Context) : LinearLayout(context) {

    /** Fill [content] for this layer (bound to EditorActivity.fillAdvanced). */
    var onFill: ((Layer) -> Unit)? = null

    val content = LinearLayout(context)
    private val title: TextView
    private val empty: TextView
    private val scroller: ScrollView
    private var boundId: String? = null

    init {
        orientation = VERTICAL

        val head = LinearLayout(context)
        head.orientation = HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(UI.dp(context, 12), 0, UI.dp(context, 8), 0)
        title = TextView(context).apply {
            text = "Properties"
            setTextColor(UI.FG)
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        head.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(head, LayoutParams(LayoutParams.MATCH_PARENT, UI.dp(context, 40)))

        content.orientation = VERTICAL
        content.setPadding(0, 0, 0, UI.dp(context, 8))
        scroller = ScrollView(context).apply {
            isFillViewport = true
            addView(content, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        empty = TextView(context).apply {
            text = "Select a source on the canvas or in Sources to edit its fit, opacity, audio, order and text."
            setTextColor(UI.FG2)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(UI.dp(context, 20), UI.dp(context, 16), UI.dp(context, 20), UI.dp(context, 16))
        }
        content.addView(empty)
    }

    /** Rebuild for [l] (null = nothing selected). Cheap when the same source is re-bound. */
    fun bind(l: Layer?, force: Boolean = false) {
        if (l == null) {
            boundId = null
            title.text = "Properties"
            content.removeAllViews()
            content.addView(empty)
            return
        }
        title.text = l.name.ifBlank { l.type.label }
        if (!force && boundId == l.id) return
        boundId = l.id
        val keepY = scroller.scrollY
        content.removeAllViews()
        onFill?.invoke(l)
        if (keepY > 0 && !force) scroller.post { scroller.scrollTo(0, 0) }
    }

    fun boundId(): String? = boundId
}
