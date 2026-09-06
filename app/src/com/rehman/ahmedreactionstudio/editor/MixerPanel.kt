package com.rehman.ahmedreactionstudio.editor

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import com.rehman.ahmedreactionstudio.R
import com.rehman.ahmedreactionstudio.util.UI

class MixerPanel(context: Context) : FrameLayout(context) {

    interface Listener {
        fun onMute(id: String)
        fun onSolo(id: String)
        fun onMasterVolume(v: Float)
    }
    var listener: Listener? = null

    init {
        setPadding(UI.dp(context, 10), UI.dp(context, 10), UI.dp(context, 10), UI.dp(context, 10))
        val bg = GradientDrawable().apply {
            cornerRadius = UI.dpf(context, 14f)
            setColor(Color.rgb(18, 20, 26))
            setStroke(UI.dp(context, 1), Color.argb(60, 255, 255, 255))
        }
        background = bg

        val inner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(inner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val title = TextView(context).apply {
            text = "Audio Mixer"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, UI.dp(context, 6))
        }
        inner.addView(title)

        // Channel strips row
        val strips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val names = listOf("Camera Mic", "Local Video", "Background", "External Mic")
        names.forEach { n ->
            val strip = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(UI.dp(context, 4), 0, UI.dp(context, 4), 0)
            }
            val lbl = TextView(context).apply {
                text = n
                textSize = 9f
                setTextColor(Color.rgb(200, 205, 220))
                gravity = Gravity.CENTER
            }
            strip.addView(lbl)

            // Meter simulation (vertical colored bars)
            val meter = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM
                setPadding(UI.dp(context, 2), UI.dp(context, 4), UI.dp(context, 2), UI.dp(context, 4))
                layoutParams = LinearLayout.LayoutParams(UI.dp(context, 22), UI.dp(context, 70))
            }
            // Green / yellow / red segments
            listOf(
                Color.rgb(60, 200, 60) to 30,
                Color.rgb(240, 200, 30) to 15,
                Color.rgb(220, 50, 50) to 10
            ).forEach { (c, h) ->
                val seg = View(context).apply {
                    setBackgroundColor(c)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, UI.dp(context, h)
                    )
                }
                meter.addView(seg)
            }
            strip.addView(meter)

            // Mute / Solo small buttons
            val btnRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val mute = Button(context).apply {
                text = "M"
                setOnClickListener { listener?.onMute("ch-" + n.replace(" ","-")) }
                textSize = 8f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(140, 30, 30))
                setOnClickListener { /* mute */ }
            }
            val solo = Button(context).apply {
                text = "S"
                setOnClickListener { listener?.onSolo("ch-" + n.replace(" ","-")) }
                textSize = 8f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(30, 100, 160))
                setOnClickListener { /* solo */ }
            }
            btnRow.addView(mute, LinearLayout.LayoutParams(UI.dp(context, 28), UI.dp(context, 22)))
            btnRow.addView(solo, LinearLayout.LayoutParams(UI.dp(context, 28), UI.dp(context, 22)))
            strip.addView(btnRow)

            strips.addView(strip)
        }
        inner.addView(strips)

        // Master row
        val masterRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, UI.dp(context, 4), 0, 0) }
        val masterLbl = TextView(context).apply { text = "Master"; textSize = 10f; setTextColor(Color.WHITE); setPadding(0, 0, UI.dp(context, 6), 0) }
        masterRow.addView(masterLbl)
        val masterMeter = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 35
            layoutParams = LinearLayout.LayoutParams(UI.dp(context, 90), UI.dp(context, 20))
            progressDrawable = GradientDrawable().apply { cornerRadius = UI.dpf(context, 6f); setColor(Color.rgb(220, 120, 30)) }
        }
        masterRow.addView(masterMeter)
        val db = TextView(context).apply { text = "-41.0 dB"; textSize = 10f; setTextColor(Color.rgb(220, 210, 190)); setPadding(UI.dp(context, 6), 0, 0, 0) }
        masterRow.addView(db)
        inner.addView(masterRow)

        // Bottom buttons
        val btnRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, UI.dp(context, 8), 0, 0) }
        listOf("+ Add", "- Remove", "Hide", "Properties").forEach { label ->
            val btn = Button(context).apply {
                text = label
                textSize = 11f
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = UI.dpf(context, 10f)
                    setColor(Color.rgb(30, 34, 48))
                    setStroke(UI.dp(context, 1), Color.rgb(80, 85, 100))
                }
                setOnClickListener { /* action */ }
            }
            btnRow.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(UI.dp(context, 4), 0, UI.dp(context, 4), 0)
            })
        }
        inner.addView(btnRow)
    }
}
