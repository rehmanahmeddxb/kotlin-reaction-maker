package com.rehman.ahmedreactionstudio.editor

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.rehman.ahmedreactionstudio.R
import com.rehman.ahmedreactionstudio.core.Layer
import com.rehman.ahmedreactionstudio.core.LayerType
import com.rehman.ahmedreactionstudio.util.UI

/**
 * Per-source audio mixer on the left rail. Mute / solo / level write the same
 * [Layer] fields preview and export already read — no fake channels.
 */
class MixerPanel(context: Context) : LinearLayout(context) {

    interface Listener {
        fun onSelect(id: String)
        fun onMute(id: String)
        fun onSolo(id: String)
        fun onVolume(id: String, v: Float)
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
            text = "Audio Mixer"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, UI.dp(context, 8))
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                Ic.get(context, R.drawable.ic_volume, Color.WHITE), null, null, null)
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

        bind(emptyList(), null)
    }

    fun bind(layers: List<Layer>, selectedId: String?) {
        list.removeAllViews()
        val audio = layers.filter { it.isClip() }
        if (audio.isEmpty()) {
            val hint = TextView(context).apply {
                text = "Add a video to mix its audio. Camera mic is captured when you record."
                setTextColor(Color.rgb(140, 148, 162))
                textSize = 12f
            }
            list.addView(hint)
            return
        }
        val anySolo = layers.any { it.solo }
        for (l in audio.asReversed()) addStrip(l, l.id == selectedId, anySolo)
    }

    private fun addStrip(l: Layer, selected: Boolean, anySolo: Boolean) {
        val wrap = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(UI.dp(context, 4), UI.dp(context, 6), UI.dp(context, 4), UI.dp(context, 6))
            background = GradientDrawable().apply {
                cornerRadius = UI.dpf(context, 8f)
                setColor(if (selected) Color.rgb(36, 72, 120) else Color.TRANSPARENT)
            }
        }

        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            minimumHeight = UI.dp(context, 36)
            contentDescription = "Select ${l.name.ifBlank { l.type.label }}"
            setOnClickListener { listener?.onSelect(l.id) }
        }
        val icon = when (l.type) {
            LayerType.CAMERA -> R.drawable.ic_camera
            LayerType.VIDEO -> R.drawable.ic_video
            LayerType.SCREEN -> R.drawable.ic_screen
            else -> R.drawable.ic_volume
        }
        val iconView = ImageView(context).apply {
            setImageDrawable(Ic.get(context, icon, Color.rgb(200, 210, 230)))
        }
        head.addView(iconView, LinearLayout.LayoutParams(UI.dp(context, 18), UI.dp(context, 18)))
        val nm = TextView(context).apply {
            text = l.name.ifBlank { l.type.label }
            setTextColor(Color.rgb(230, 232, 238))
            textSize = 12f
            maxLines = 1
            setPadding(UI.dp(context, 6), 0, UI.dp(context, 4), 0)
        }
        head.addView(nm, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        val effMuted = l.muted || (anySolo && !l.solo)
        head.addView(chip(
            if (effMuted) "M" else "M",
            if (effMuted) Color.rgb(140, 30, 30) else Color.rgb(30, 34, 48),
            if (l.muted) "Unmute ${l.name}" else "Mute ${l.name}"
        ) { listener?.onMute(l.id) })
        head.addView(chip(
            "S",
            if (l.solo) Color.rgb(180, 110, 20) else Color.rgb(30, 34, 48),
            if (l.solo) "Unsolo ${l.name}" else "Solo ${l.name}"
        ) { listener?.onSolo(l.id) })
        wrap.addView(head)

        if (effMuted && !l.muted) {
            val why = TextView(context).apply {
                text = "Silent — another source is soloed"
                setTextColor(Color.rgb(200, 160, 80))
                textSize = 10f
                setPadding(UI.dp(context, 24), 0, 0, 0)
            }
            wrap.addView(why)
        }

        val levelRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, UI.dp(context, 2), 0, 0)
        }
        val pct = (l.volume * 100).toInt().coerceIn(0, 100)
        val lvl = TextView(context).apply {
            text = "$pct%"
            setTextColor(Color.rgb(180, 186, 198))
            textSize = 10f
            minWidth = UI.dp(context, 32)
        }
        levelRow.addView(lvl)
        val sb = SeekBar(context).apply {
            max = 100
            progress = pct
            contentDescription = "Volume of ${l.name}"
            progressTintList = android.content.res.ColorStateList.valueOf(UI.ACCENT)
            thumbTintList = android.content.res.ColorStateList.valueOf(UI.ACCENT2)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    lvl.text = "$v%"
                    listener?.onVolume(l.id, v / 100f)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        levelRow.addView(sb, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        wrap.addView(levelRow)

        list.addView(wrap, LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = UI.dp(context, 4)
        })
    }

    private fun chip(label: String, fill: Int, desc: String, onTap: () -> Unit): Button {
        val b = Button(context)
        b.text = label
        b.isAllCaps = false
        b.textSize = 11f
        b.setTextColor(Color.WHITE)
        b.contentDescription = desc
        b.minHeight = 0
        b.minimumHeight = 0
        b.minWidth = 0
        b.minimumWidth = 0
        b.setPadding(0, 0, 0, 0)
        b.background = GradientDrawable().apply {
            cornerRadius = UI.dpf(context, 8f)
            setColor(fill)
            setStroke(UI.dp(context, 1), Color.rgb(80, 85, 100))
        }
        b.setOnClickListener { onTap() }
        val lp = LinearLayout.LayoutParams(UI.dp(context, 36), UI.dp(context, 32))
        lp.marginStart = UI.dp(context, 4)
        b.layoutParams = lp
        return b
    }
}
