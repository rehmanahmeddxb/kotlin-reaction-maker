package com.rehman.ahmedreactionstudio.editor

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import com.rehman.ahmedreactionstudio.R
import com.rehman.ahmedreactionstudio.util.UI

class SourcesPanel(context: Context) : FrameLayout(context) {

    interface Listener {
        fun onSelect(id: String)
        fun onToggleVisible(id: String)
        fun onAdd()
        fun onRemove()
        fun onHide()
        fun onProperties()
    }
    var listener: Listener? = null
    init {
        setPadding(UI.dp(context, 12), UI.dp(context, 10), UI.dp(context, 12), UI.dp(context, 10))
        val bg = GradientDrawable().apply {
            cornerRadius = UI.dpf(context, 14f)
            setColor(Color.rgb(18, 20, 26))
            setStroke(UI.dp(context, 1), Color.argb(60, 255, 255, 255))
        }
        background = bg

        val inner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(inner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Title
        val title = TextView(context).apply {
            text = "Sources"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, UI.dp(context, 8))
        }
        inner.addView(title)

        // List rows
        val items = listOf(
            Pair("Camera", R.drawable.ic_camera),
            Pair("Local Video", R.drawable.ic_video),
            Pair("Background Music", R.drawable.ic_image),
            Pair("External Mic", R.drawable.ic_volume)
        )
        items.forEach { (name, icon) ->
            val row = LinearLayout(context).apply { setOnClickListener { listener?.onSelect("source-" + name) } 
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, UI.dp(context, 4), 0, UI.dp(context, 4))
            }
            val iconView = ImageView(context).apply {
                setImageResource(icon)
                setColorFilter(Color.rgb(200, 210, 230))
            }
            row.addView(iconView, LinearLayout.LayoutParams(UI.dp(context, 24), UI.dp(context, 24)))

            val lbl = TextView(context).apply {
                text = name
                setTextColor(Color.rgb(230, 232, 238))
                textSize = 13f
                setPadding(UI.dp(context, 8), 0, 0, 0)
            }
            row.addView(lbl, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val eye = ImageButton(context).apply {
                setImageResource(R.drawable.ic_eye)
                setBackgroundResource(android.R.color.transparent)
                contentDescription = "Hide $name"
                setOnClickListener { listener?.onToggleVisible("source-" + name) }
                setOnClickListener { /* toggle visibility */ }
            }
            row.addView(eye, LinearLayout.LayoutParams(UI.dp(context, 28), UI.dp(context, 28)))
            inner.addView(row)
        }

        // Bottom buttons
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, UI.dp(context, 8), 0, 0)
        }
        listOf("+ Add", "- Remove", "Hide", "Properties").forEach { label ->
            val btn = Button(context).apply {
                text = label
                textSize = 11f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(30, 34, 48))
                // simple pill shape
                background = GradientDrawable().apply {
                    cornerRadius = UI.dpf(context, 10f)
                    setColor(Color.rgb(30, 34, 48))
                    setStroke(UI.dp(context, 1), Color.rgb(80, 85, 100))
                }
                // Use a simple pill background via drawable if exists; else rely on default
                setOnClickListener { /* action */ }
            }
            btnRow.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(UI.dp(context, 4), 0, UI.dp(context, 4), 0)
            })
        }
        inner.addView(btnRow)
    }
}
