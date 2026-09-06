package com.rehman.ahmedreactionstudio.editor

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.rehman.ahmedreactionstudio.R
import com.rehman.ahmedreactionstudio.core.Layer
import com.rehman.ahmedreactionstudio.util.UI

/**
 * EFFECTS tab. The compositor has no filter / colour / key stage yet, and the
 * rule of this studio is that every visible control does something real, so
 * this tab is honest about that: it lists what exists today (the per-source
 * look controls that ARE effects in the OBS sense — fit, opacity, mirror,
 * canvas colour) and states what is not there, instead of an empty page that
 * looks broken.
 */
class EffectsPanel(context: Context) : LinearLayout(context) {

    var onOpenProps: (() -> Unit)? = null
    var onCanvasColor: (() -> Unit)? = null

    private val body = LinearLayout(context)

    init {
        orientation = VERTICAL
        val head = LinearLayout(context)
        head.orientation = HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(UI.dp(context, 12), 0, UI.dp(context, 8), 0)
        head.addView(TextView(context).apply {
            text = "Effects"
            setTextColor(UI.FG)
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            includeFontPadding = false
        })
        addView(head, LayoutParams(LayoutParams.MATCH_PARENT, UI.dp(context, 40)))

        body.orientation = VERTICAL
        body.setPadding(UI.dp(context, 8), 0, UI.dp(context, 8), UI.dp(context, 8))
        addView(ScrollView(context).apply {
            isFillViewport = true
            addView(body, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        bind(null)
    }

    fun bind(l: Layer?) {
        body.removeAllViews()
        if (l == null) {
            note("Select a source to see its look controls.")
        } else {
            row(R.drawable.ic_fit, "Fit / Fill, opacity" + (if (l.isLive()) ", mirror" else ""),
                "in Properties") { onOpenProps?.invoke() }
        }
        row(R.drawable.ic_palette, "Canvas colour", "behind letterboxed sources") { onCanvasColor?.invoke() }
        note("Filters, colour grading, chroma key and transitions are not part of this version. " +
            "Nothing you see here is a placeholder — only controls that change the export are shown.")
    }

    private fun row(icon: Int, title: String, sub: String, fn: () -> Unit) {
        val r = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true
            contentDescription = title
            setPadding(UI.dp(context, 10), 0, UI.dp(context, 10), 0)
            background = Ic.pill(context, Color.argb(120, 20, 23, 31), 12f, Color.argb(40, 255, 255, 255))
            setOnClickListener { fn() }
        }
        r.addView(ImageView(context).apply { setImageDrawable(Ic.get(context, icon, UI.ACCENT2)) },
            LayoutParams(UI.dp(context, 20), UI.dp(context, 20)))
        val col = LinearLayout(context).apply { orientation = VERTICAL; setPadding(UI.dp(context, 10), 0, 0, 0) }
        col.addView(TextView(context).apply { text = title; setTextColor(UI.FG); textSize = 12.5f; maxLines = 1 })
        col.addView(TextView(context).apply { text = sub; setTextColor(UI.FG2); textSize = 10.5f; maxLines = 1 })
        r.addView(col, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        body.addView(r, LayoutParams(LayoutParams.MATCH_PARENT, UI.dp(context, 48)).apply {
            setMargins(UI.dp(context, 2), UI.dp(context, 3), UI.dp(context, 2), UI.dp(context, 3))
        })
    }

    private fun note(s: String) {
        body.addView(TextView(context).apply {
            text = s
            setTextColor(UI.FG2)
            textSize = 11.5f
            setPadding(UI.dp(context, 6), UI.dp(context, 8), UI.dp(context, 6), UI.dp(context, 6))
        })
    }
}
