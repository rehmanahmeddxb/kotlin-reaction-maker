package com.rehman.ahmedreactionstudio.editor

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.rehman.ahmedreactionstudio.R
import com.rehman.ahmedreactionstudio.util.UI

/**
 * OBS-style Controls dock that sits under [SourcesPanel] on the right rail.
 * Start Recording / Pause / Stop / Save / Flashlight — same chrome as Sources
 * so the two boxes read as one column.
 */
class ControlsPanel(context: Context) : LinearLayout(context) {

    interface Listener {
        fun onStartRecording()
        fun onPause()
        fun onStop()
        fun onSave()
        fun onFlashlight()
    }

    var listener: Listener? = null

    private val recBtn: Button
    private val pauseBtn: Button
    private val stopBtn: Button
    private val saveBtn: Button
    private val flashBtn: Button

    init {
        orientation = VERTICAL
        setPadding(UI.dp(context, 12), UI.dp(context, 10), UI.dp(context, 12), UI.dp(context, 10))
        background = panelBg()

        val title = TextView(context).apply {
            text = "Controls"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, UI.dp(context, 8))
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                Ic.get(context, R.drawable.ic_video, Color.WHITE), null, null, null)
            compoundDrawablePadding = UI.dp(context, 8)
            gravity = Gravity.CENTER_VERTICAL
        }
        addView(title)

        recBtn = actionBtn("●  Start Recording", accent = true, danger = false)
        recBtn.contentDescription = "Start recording the composition"
        recBtn.minHeight = UI.dp(context, 48)
        recBtn.setOnClickListener { listener?.onStartRecording() }
        addView(recBtn, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, UI.dp(context, 48)).apply {
            bottomMargin = UI.dp(context, 8)
        })

        val row1 = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        pauseBtn = actionBtn("Pause", accent = false)
        pauseBtn.contentDescription = "Pause or play preview"
        pauseBtn.setOnClickListener { listener?.onPause() }
        stopBtn = actionBtn("Stop", accent = false)
        stopBtn.contentDescription = "Stop recording or playback"
        stopBtn.setOnClickListener { listener?.onStop() }
        row1.addView(pauseBtn, LinearLayout.LayoutParams(0, UI.dp(context, 48), 1f).apply {
            marginEnd = UI.dp(context, 4)
        })
        row1.addView(stopBtn, LinearLayout.LayoutParams(0, UI.dp(context, 48), 1f).apply {
            marginStart = UI.dp(context, 4)
        })
        addView(row1, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = UI.dp(context, 6)
        })

        val row2 = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        saveBtn = actionBtn("Save", accent = false)
        saveBtn.contentDescription = "Save the project"
        saveBtn.setOnClickListener { listener?.onSave() }
        flashBtn = actionBtn("Flash", accent = false)
        flashBtn.contentDescription = "Toggle flashlight or screen light"
        flashBtn.setOnClickListener { listener?.onFlashlight() }
        row2.addView(saveBtn, LinearLayout.LayoutParams(0, UI.dp(context, 48), 1f).apply {
            marginEnd = UI.dp(context, 4)
        })
        row2.addView(flashBtn, LinearLayout.LayoutParams(0, UI.dp(context, 48), 1f).apply {
            marginStart = UI.dp(context, 4)
        })
        addView(row2, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun bind(recording: Boolean, playing: Boolean, flashOn: Boolean, recReady: Boolean) {
        recBtn.text = if (recording) "■  Stop & Save" else "●  Start Recording"
        recBtn.contentDescription = if (recording) "Stop recording and save" else "Start recording the composition"
        recBtn.isEnabled = true
        recBtn.alpha = if (recording || recReady) 1f else 0.85f
        recBtn.background = pill(if (recording) Color.rgb(200, 34, 34)
            else if (recReady) UI.ACCENT
            else Color.rgb(30, 34, 48))

        pauseBtn.text = if (playing) "Pause" else "Play"
        pauseBtn.contentDescription = if (recording) "Recording can't be paused"
            else if (playing) "Pause preview" else "Play preview"
        pauseBtn.alpha = if (recording) 0.5f else 1f
        pauseBtn.background = pill(if (playing && !recording) Color.rgb(40, 90, 70) else Color.rgb(30, 34, 48))

        stopBtn.alpha = if (recording || playing) 1f else 0.7f
        stopBtn.isEnabled = true

        flashBtn.text = if (flashOn) "Flash on" else "Flash"
        flashBtn.background = pill(if (flashOn) Color.rgb(180, 120, 20) else Color.rgb(30, 34, 48))
        flashBtn.setTextColor(if (flashOn) Color.rgb(255, 230, 140) else Color.WHITE)
    }

    private fun actionBtn(label: String, accent: Boolean, danger: Boolean = false): Button {
        val b = Button(context)
        b.text = label
        b.isAllCaps = false
        b.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        b.setTextColor(Color.WHITE)
        b.textSize = 12f
        b.minHeight = 0
        b.minimumHeight = 0
        b.setPadding(UI.dp(context, 6), 0, UI.dp(context, 6), 0)
        b.background = pill(
            when {
                danger -> Color.rgb(200, 34, 34)
                accent -> UI.ACCENT
                else -> Color.rgb(30, 34, 48)
            }
        )
        return b
    }

    private fun panelBg(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = UI.dpf(context, 14f)
        setColor(Color.rgb(18, 20, 26))
        setStroke(UI.dp(context, 1), Color.argb(60, 255, 255, 255))
    }

    private fun pill(fill: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = UI.dpf(context, 10f)
        setColor(fill)
        setStroke(UI.dp(context, 1), Color.rgb(80, 85, 100))
    }
}
