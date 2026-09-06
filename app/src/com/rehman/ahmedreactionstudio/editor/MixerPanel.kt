package com.rehman.ahmedreactionstudio.editor

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.rehman.ahmedreactionstudio.R
import com.rehman.ahmedreactionstudio.core.Layer
import com.rehman.ahmedreactionstudio.util.UI

/**
 * MIXER tab: one strip per clip-backed source (video / screen record / camera
 * take) plus a read-only microphone strip when a live camera is on the canvas.
 *
 * Every control writes the same [Layer] fields the preview, the live recorder
 * and the exporter read (`volume`, `muted`, `solo`) — no fake channels, no
 * control that affects only one path. The mic strip is informational on
 * purpose: mic gain is fixed at unity in the recorder and there is no mic in
 * an offline export, so a fader here would lie about one of the two.
 */
class MixerPanel(context: Context) : LinearLayout(context) {

    interface Listener {
        fun onSelect(id: String)
        fun onMute(id: String)
        fun onSolo(id: String)
        fun onVolume(id: String, v: Float)
        /** preview monitor on/off — does not touch the project */
        fun onMonitorToggle()
    }

    var listener: Listener? = null

    private val list: LinearLayout
    private val count: TextView
    private val monitorBtn: IconBtn
    private var monitorMuted = false

    init {
        orientation = VERTICAL

        val head = LinearLayout(context)
        head.orientation = HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(UI.dp(context, 12), 0, UI.dp(context, 4), 0)
        head.addView(TextView(context).apply {
            text = "Mixer"
            setTextColor(UI.FG)
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            includeFontPadding = false
        })
        count = TextView(context).apply {
            setTextColor(UI.FG2)
            textSize = 11.5f
            includeFontPadding = false
            setPadding(UI.dp(context, 6), 0, 0, 0)
        }
        head.addView(count, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        monitorBtn = IconBtn(context)
        monitorBtn.layoutParams = IconBtn.sized(context, 40)
        monitorBtn.setIcon(R.drawable.ic_volume, UI.FG, "Mute preview monitor")
        monitorBtn.setOnClickListener { listener?.onMonitorToggle() }
        head.addView(monitorBtn)
        addView(head, LayoutParams(LayoutParams.MATCH_PARENT, UI.dp(context, 40)))

        list = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(UI.dp(context, 8), UI.dp(context, 2), UI.dp(context, 8), UI.dp(context, 8))
        }
        val scroller = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            addView(list, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        bind(emptyList(), null, monitorMuted = false)
    }

    fun bind(layers: List<Layer>, selectedId: String?, monitorMuted: Boolean) {
        this.monitorMuted = monitorMuted
        monitorBtn.setIcon(if (monitorMuted) R.drawable.ic_volume_off else R.drawable.ic_volume,
            if (monitorMuted) UI.DANGER else UI.FG,
            if (monitorMuted) "Unmute preview monitor" else "Mute preview monitor")
        list.removeAllViews()
        val audio = layers.filter { it.isClip() }
        val live = layers.firstOrNull { it.isLive() }
        count.text = when {
            audio.isEmpty() && live == null -> ""
            audio.size == 1 -> "· 1 channel"
            else -> "· ${audio.size} channels"
        }
        if (audio.isEmpty() && live == null) {
            list.addView(TextView(context).apply {
                text = "No audio yet.\nAdd a video to mix its sound; the microphone is captured while you record."
                setTextColor(UI.FG2)
                textSize = 12f
                setPadding(UI.dp(context, 6), UI.dp(context, 10), UI.dp(context, 6), UI.dp(context, 6))
            })
            return
        }
        if (live != null) addMicStrip(live)
        val anySolo = layers.any { it.solo }
        for (l in audio.asReversed()) addStrip(l, l.id == selectedId, anySolo)
    }

    /** Microphone: captured by the composite recorder only. Read-only by design. */
    private fun addMicStrip(live: Layer) {
        val wrap = strip(false)
        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = UI.dp(context, 36)
        }
        head.addView(ImageView(context).apply {
            setImageDrawable(Ic.get(context, R.drawable.ic_camera, UI.OK))
        }, LayoutParams(UI.dp(context, 18), UI.dp(context, 18)))
        head.addView(TextView(context).apply {
            text = "Microphone"
            setTextColor(UI.FG)
            textSize = 12.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            setPadding(UI.dp(context, 8), 0, UI.dp(context, 4), 0)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        head.addView(badge("LIVE", UI.OK))
        wrap.addView(head)
        wrap.addView(TextView(context).apply {
            text = "Recorded with \"${live.name.ifBlank { "Camera" }}\" while REC is on · unity gain"
            setTextColor(UI.FG2)
            textSize = 10.5f
            setPadding(UI.dp(context, 26), 0, 0, UI.dp(context, 4))
        })
        list.addView(wrap, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = UI.dp(context, 6)
        })
    }

    private fun addStrip(l: Layer, selected: Boolean, anySolo: Boolean) {
        val wrap = strip(selected)
        val effMuted = l.muted || (anySolo && !l.solo)

        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            minimumHeight = UI.dp(context, 36)
            contentDescription = "Select ${l.name.ifBlank { l.type.label }}"
            setOnClickListener { listener?.onSelect(l.id) }
        }
        head.addView(ImageView(context).apply {
            setImageDrawable(Ic.get(context, Ic.typeIcon(l.type),
                if (effMuted) Color.argb(120, 255, 255, 255) else UI.FG))
        }, LayoutParams(UI.dp(context, 18), UI.dp(context, 18)))
        head.addView(TextView(context).apply {
            text = l.name.ifBlank { l.type.label }
            setTextColor(if (effMuted) Color.argb(150, 235, 238, 245) else UI.FG)
            textSize = 12.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(UI.dp(context, 8), 0, UI.dp(context, 4), 0)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        if (!l.playing) head.addView(badge("PAUSED", UI.ACCENT2))

        // M / S toggles: fill AND glyph change with state (M ↔ 🔇)
        head.addView(toggle(
            if (l.muted) R.drawable.ic_volume_off else R.drawable.ic_volume,
            l.muted, UI.DANGER,
            if (l.muted) "Unmute ${l.name}" else "Mute ${l.name}") { listener?.onMute(l.id) })
        head.addView(toggle(R.drawable.ic_star, l.solo, UI.ACCENT2,
            if (l.solo) "Unsolo ${l.name}" else "Solo ${l.name}") { listener?.onSolo(l.id) })
        wrap.addView(head)

        if (effMuted && !l.muted) {
            wrap.addView(TextView(context).apply {
                text = "Silent — another source is soloed"
                setTextColor(UI.ACCENT2)
                textSize = 10.5f
                setPadding(UI.dp(context, 26), 0, 0, 0)
            })
        }

        val levelRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(UI.dp(context, 2), 0, 0, 0)
        }
        val pct = (l.volume * 100).toInt().coerceIn(0, 100)
        val lvl = TextView(context).apply {
            text = "$pct%"
            setTextColor(UI.FG2)
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            minWidth = UI.dp(context, 38)
            gravity = Gravity.END
            includeFontPadding = false
        }
        val sb = SeekBar(context).apply {
            max = 100
            progress = pct
            contentDescription = "Volume of ${l.name}"
            progressTintList = ColorStateList.valueOf(if (effMuted) UI.FG2 else UI.ACCENT)
            thumbTintList = ColorStateList.valueOf(if (effMuted) UI.FG2 else UI.ACCENT2)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    lvl.text = "$v%"
                    listener?.onVolume(l.id, v / 100f)
                }
                // strips live in a ScrollView: own the gesture while the fader is held
                override fun onStartTrackingTouch(s: SeekBar?) { s?.parent?.requestDisallowInterceptTouchEvent(true) }
                override fun onStopTrackingTouch(s: SeekBar?) { s?.parent?.requestDisallowInterceptTouchEvent(false) }
            })
        }
        levelRow.addView(sb, LayoutParams(0, UI.dp(context, 36), 1f))
        levelRow.addView(lvl)
        wrap.addView(levelRow)

        list.addView(wrap, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = UI.dp(context, 6)
        })
    }

    private fun strip(selected: Boolean): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(UI.dp(context, 8), UI.dp(context, 4), UI.dp(context, 8), UI.dp(context, 4))
        background = Ic.pill(context,
            if (selected) Color.argb(70, 255, 90, 44) else Color.argb(120, 20, 23, 31), 12f,
            if (selected) Color.argb(255, 255, 130, 80) else Color.argb(40, 255, 255, 255))
    }

    private fun toggle(icon: Int, on: Boolean, onColor: Int, desc: String, fn: () -> Unit): View {
        val b = IconBtn(context)
        b.layoutParams = LayoutParams(UI.dp(context, 40), UI.dp(context, 40)).apply { marginStart = UI.dp(context, 2) }
        b.setIcon(icon, if (on) onColor else UI.FG2, desc)
        b.background = Ic.pill(context,
            if (on) Color.argb(60, Color.red(onColor), Color.green(onColor), Color.blue(onColor)) else Color.TRANSPARENT,
            10f, if (on) Color.argb(140, Color.red(onColor), Color.green(onColor), Color.blue(onColor)) else Color.TRANSPARENT)
        b.setOnClickListener { fn() }
        return b
    }

    private fun badge(text: String, color: Int): TextView = TextView(context).apply {
        this.text = text
        textSize = 8f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(color)
        gravity = Gravity.CENTER
        includeFontPadding = false
        setPadding(UI.dp(context, 5), UI.dp(context, 2), UI.dp(context, 5), UI.dp(context, 2))
        background = Ic.pill(context, Color.argb(60, Color.red(color), Color.green(color), Color.blue(color)), 6f,
            Color.argb(140, Color.red(color), Color.green(color), Color.blue(color)))
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(UI.dp(context, 3), 0, UI.dp(context, 3), 0)
        }
    }
}
