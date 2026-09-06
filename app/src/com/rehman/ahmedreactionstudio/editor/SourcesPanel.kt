package com.rehman.ahmedreactionstudio.editor

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.rehman.ahmedreactionstudio.R
import com.rehman.ahmedreactionstudio.core.Layer
import com.rehman.ahmedreactionstudio.util.UI

/**
 * OBS-style Sources dock. Stretches to fill the space above [ControlsPanel]
 * on the right rail — list scrolls, Add/Remove/Hide/Properties stay pinned
 * to the bottom of this box.
 */
class SourcesPanel(context: Context) : LinearLayout(context) {

    interface Listener {
        fun onSelect(id: String)
        fun onToggleVisible(id: String)
        fun onAdd()
        fun onAddVideo()
        fun onAddImage()
        fun onRemove()
        fun onHide()
        fun onProperties()
        fun onMoveUp(id: String)
        fun onMoveDown(id: String)
    }

    var listener: Listener? = null

    private val list: LinearLayout

    init {
        orientation = VERTICAL
        setPadding(UI.dp(context, 12), UI.dp(context, 10), UI.dp(context, 12), UI.dp(context, 10))
        background = GradientDrawable().apply {
            cornerRadius = UI.dpf(context, 14f)
            setColor(Color.rgb(18, 20, 26))
            setStroke(UI.dp(context, 1), Color.argb(60, 255, 255, 255))
        }

        val title = TextView(context).apply {
            text = "Sources"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, UI.dp(context, 8))
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                Ic.get(context, R.drawable.ic_layers, Color.WHITE), null, null, null)
            compoundDrawablePadding = UI.dp(context, 8)
            gravity = Gravity.CENTER_VERTICAL
        }
        addView(title)

        list = LinearLayout(context).apply { orientation = VERTICAL }
        val scroller = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            addView(list, FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(scroller, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val btnRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, UI.dp(context, 8), 0, 0)
        }
        fun bottom(label: String, desc: String, fn: () -> Unit) {
            val btn = Button(context).apply {
                text = label
                textSize = 11f
                isAllCaps = false
                setTextColor(Color.WHITE)
                contentDescription = desc
                minHeight = 0
                minimumHeight = 0
                setPadding(UI.dp(context, 4), 0, UI.dp(context, 4), 0)
                background = GradientDrawable().apply {
                    cornerRadius = UI.dpf(context, 10f)
                    setColor(Color.rgb(30, 34, 48))
                    setStroke(UI.dp(context, 1), Color.rgb(80, 85, 100))
                }
                setOnClickListener { fn() }
            }
            btnRow.addView(btn, LinearLayout.LayoutParams(0, UI.dp(context, 40), 1f).apply {
                setMargins(UI.dp(context, 3), 0, UI.dp(context, 3), 0)
            })
        }
        bottom("+ Add", "Add a source") { listener?.onAdd() }
        bottom("− Remove", "Remove the selected source") { listener?.onRemove() }
        bottom("Hide", "Hide the selected source") { listener?.onHide() }
        bottom("Properties", "Source properties") { listener?.onProperties() }
        addView(btnRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        bind(emptyList(), null)
    }

    fun bind(layers: List<Layer>, selectedId: String?) {
        list.removeAllViews()
        if (layers.isEmpty()) {
            addShortcut("Camera", R.drawable.ic_camera, "Add the live camera") { listener?.onAdd() }
            addShortcut("Local Video", R.drawable.ic_video, "Add a local video") { listener?.onAddVideo() }
            addShortcut("Image", R.drawable.ic_image, "Add an image") { listener?.onAddImage() }
            val hint = TextView(context).apply {
                text = "Tap a row or + Add"
                setTextColor(Color.rgb(140, 148, 162))
                textSize = 11f
                setPadding(0, UI.dp(context, 8), 0, 0)
            }
            list.addView(hint)
            return
        }
        for (l in layers.asReversed()) addLayerRow(l, l.id == selectedId)
    }

    private fun addShortcut(name: String, icon: Int, desc: String, onTap: () -> Unit) {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(UI.dp(context, 6), UI.dp(context, 8), UI.dp(context, 6), UI.dp(context, 8))
            isClickable = true
            isFocusable = true
            contentDescription = desc
            minimumHeight = UI.dp(context, 44)
            setOnClickListener { onTap() }
        }
        val iconView = ImageView(context).apply {
            setImageDrawable(Ic.get(context, icon, Color.rgb(200, 210, 230)))
        }
        row.addView(iconView, LinearLayout.LayoutParams(UI.dp(context, 22), UI.dp(context, 22)))
        val lbl = TextView(context).apply {
            text = name
            setTextColor(Color.rgb(230, 232, 238))
            textSize = 13f
            setPadding(UI.dp(context, 8), 0, 0, 0)
        }
        row.addView(lbl, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        list.addView(row)
    }

    private fun addLayerRow(l: Layer, selected: Boolean) {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(UI.dp(context, 6), UI.dp(context, 6), UI.dp(context, 4), UI.dp(context, 6))
            isClickable = true
            isFocusable = true
            isSelected = selected
            minimumHeight = UI.dp(context, 44)
            contentDescription = "Select ${l.name.ifBlank { l.type.label }}"
            background = GradientDrawable().apply {
                cornerRadius = UI.dpf(context, 8f)
                setColor(if (selected) Color.rgb(36, 72, 120) else Color.TRANSPARENT)
            }
            setOnClickListener { listener?.onSelect(l.id) }
        }
        val iconView = ImageView(context).apply {
            setImageDrawable(Ic.get(context, Ic.typeIcon(l.type),
                if (selected) Color.WHITE else Color.rgb(200, 210, 230)))
        }
        row.addView(iconView, LinearLayout.LayoutParams(UI.dp(context, 22), UI.dp(context, 22)))
        val lbl = TextView(context).apply {
            text = l.name.ifBlank { l.type.label }
            setTextColor(if (l.visible) Color.rgb(230, 232, 238) else Color.argb(140, 230, 232, 238))
            textSize = 13f
            maxLines = 1
            setPadding(UI.dp(context, 8), 0, 0, 0)
        }
        row.addView(lbl, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val up = ImageButton(context).apply {
            setImageDrawable(Ic.get(context, R.drawable.ic_up, Color.WHITE))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { listener?.onMoveUp(l.id) }
        }
        row.addView(up, LinearLayout.LayoutParams(UI.dp(context, 36), UI.dp(context, 36)))

        val down = ImageButton(context).apply {
            setImageDrawable(Ic.get(context, R.drawable.ic_down, Color.WHITE))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { listener?.onMoveDown(l.id) }
        }
        row.addView(down, LinearLayout.LayoutParams(UI.dp(context, 36), UI.dp(context, 36)))

        val eye = ImageButton(context).apply {
            setImageDrawable(Ic.get(context,
                if (l.visible) R.drawable.ic_eye else R.drawable.ic_eye_off,
                if (l.visible) Color.WHITE else Color.argb(140, 255, 255, 255)))
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = if (l.visible) "Hide ${l.name}" else "Show ${l.name}"
            setOnClickListener { listener?.onToggleVisible(l.id) }
        }
        row.addView(eye, LinearLayout.LayoutParams(UI.dp(context, 36), UI.dp(context, 36)))
        list.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = UI.dp(context, 2)
        })
    }
}
