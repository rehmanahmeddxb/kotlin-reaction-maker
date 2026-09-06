package com.rehman.ahmedreactionstudio.editor

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Button
import com.rehman.ahmedreactionstudio.R
import com.rehman.ahmedreactionstudio.util.UI

object StudioLayoutInjector {
    fun inject(activity: EditorActivity, root: FrameLayout) {
        val dm = activity.resources.displayMetrics
        val isLandscape = dm.widthPixels > dm.heightPixels

        activity.emptyOverlay = LinearLayout(activity)
        activity.playBtn = IconBtn(activity)
        activity.timeLabel = TextView(activity)
        activity.durationLabel = TextView(activity)
        activity.seek = SeekBar(activity)
        activity.aspectChip = TextView(activity)
        activity.quickBar = LinearLayout(activity)
        activity.panelDivider = View(activity)
        activity.panelContent = LinearLayout(activity)
        activity.sheet = LinearLayout(activity)
        activity.dockContainer = LinearLayout(activity)
        activity.recChip = TextView(activity)
        activity.statsHud = TextView(activity)
        activity.hiddenPill = TextView(activity)
        activity.dock = SourceDock(activity, activity.dockContainer, { null!! }, { null }, { }, { _, _ -> }, { }, { }, { }, { _, _ -> }, { }) // dummy
        activity.studioBtn = IconBtn(activity)
        activity.tabBar = LinearLayout(activity)
        activity.transportBar = LinearLayout(activity)
        activity.sourceStripWrap = HorizontalScrollView(activity)
        activity.sourceStrip = LinearLayout(activity)
        activity.topBar = LinearLayout(activity)
        activity.quickWrap = HorizontalScrollView(activity)
        activity.fullExitBtn = TextView(activity)
        activity.panelScroll = ScrollView(activity)
        activity.launchRow = LinearLayout(activity)
        activity.sideRail = ScrollView(activity)
        activity.railContent = LinearLayout(activity)
        activity.recordBtn = TextView(activity)

        val mainLayout = LinearLayout(activity)
        mainLayout.orientation = LinearLayout.VERTICAL
        mainLayout.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        mainLayout.setBackgroundColor(Color.rgb(18, 20, 26))
        root.addView(mainLayout)

        val topBar = LinearLayout(activity)
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.setPadding(UI.dp(activity, 12), UI.dp(activity, 8), UI.dp(activity, 12), UI.dp(activity, 8))
        topBar.setBackgroundColor(Color.rgb(12, 14, 19))
        val topBarLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.07f)
        mainLayout.addView(topBar, topBarLp)

        val backBtn = TextView(activity).apply {
            text = "← Studio"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setOnClickListener { activity.onBackPressed() }
        }
        topBar.addView(backBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val aspectRatioBtn = TextView(activity).apply {
            text = "16:9 ▾"
            setTextColor(Color.WHITE)
            setPadding(UI.dp(activity, 8), UI.dp(activity, 4), UI.dp(activity, 8), UI.dp(activity, 4))
            setOnClickListener { activity.showAspectPicker() }
        }
        activity.aspectChip = aspectRatioBtn
        topBar.addView(aspectRatioBtn)
        
        val settingsBtn = ImageView(activity).apply {
            setImageDrawable(Ic.get(activity, R.drawable.ic_settings, Color.WHITE))
            setPadding(UI.dp(activity, 8), UI.dp(activity, 4), UI.dp(activity, 8), UI.dp(activity, 4))
            setOnClickListener { activity.openDiagnostics() }
        }
        topBar.addView(settingsBtn)

        val saveBtnTop = Button(activity).apply {
            text = "Save"
            setPadding(UI.dp(activity, 4), 0, UI.dp(activity, 4), 0)
            setOnClickListener { activity.saveNow() }
        }
        topBar.addView(saveBtnTop)

        val exportBtnTop = Button(activity).apply {
            text = "Export"
            setPadding(UI.dp(activity, 4), 0, UI.dp(activity, 4), 0)
            setOnClickListener { activity.quickExport() }
        }
        topBar.addView(exportBtnTop)

        if (isLandscape) {
            val middleRow = LinearLayout(activity)
            middleRow.orientation = LinearLayout.HORIZONTAL
            mainLayout.addView(middleRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.61f))

            val leftToolbar = LinearLayout(activity)
            leftToolbar.orientation = LinearLayout.VERTICAL
            leftToolbar.gravity = Gravity.CENTER_HORIZONTAL
            leftToolbar.setBackgroundColor(Color.rgb(15, 17, 22))
            val leftLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.08f)
            middleRow.addView(leftToolbar, leftLp)

            val tools = listOf(
                Pair(R.drawable.ic_add, "Add") { activity.pickMedia(true) },
                Pair(R.drawable.ic_camera, "Camera") { activity.addLiveCamera() },
                Pair(R.drawable.ic_video, "Video") { activity.pickMedia(true) },
                Pair(R.drawable.ic_image, "Image") { activity.pickMedia(false) },
                Pair(R.drawable.ic_text, "Text") { activity.addText() },
                Pair(R.drawable.ic_undo, "Undo") { activity.doUndo() },
                Pair(R.drawable.ic_redo, "Redo") { activity.doRedo() }
            )
            val scroller = ScrollView(activity)
            scroller.isVerticalScrollBarEnabled = false
            val toolsContainer = LinearLayout(activity)
            toolsContainer.orientation = LinearLayout.VERTICAL
            toolsContainer.gravity = Gravity.CENTER_HORIZONTAL
            for ((icon, desc, action) in tools) {
                val btn = ImageView(activity).apply {
                    setImageDrawable(Ic.get(activity, icon, Color.WHITE))
                    setPadding(0, UI.dp(activity, 12), 0, UI.dp(activity, 12))
                    contentDescription = desc
                    setOnClickListener { action() }
                }
                toolsContainer.addView(btn, LinearLayout.LayoutParams(UI.dp(activity, 44), UI.dp(activity, 44)))
            }
            scroller.addView(toolsContainer)
            leftToolbar.addView(scroller)

            val canvasContainer = FrameLayout(activity)
            canvasContainer.setBackgroundColor(Color.rgb(4, 5, 7))
            val canvasLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.67f)
            middleRow.addView(canvasContainer, canvasLp)
            
            activity.stage = StageView(activity)
            activity.stage.host = activity
            canvasContainer.addView(activity.stage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))

            val rightPanel = LinearLayout(activity)
            rightPanel.orientation = LinearLayout.VERTICAL
            rightPanel.setBackgroundColor(Color.rgb(18, 20, 26))
            val rightLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.25f)
            middleRow.addView(rightPanel, rightLp)

            val tabs = LinearLayout(activity)
            tabs.orientation = LinearLayout.HORIZONTAL
            rightPanel.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            val contentArea = FrameLayout(activity)
            rightPanel.addView(contentArea, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

            val sourcesPanel = SourcesPanel(activity)
            val mixerPanel = MixerPanel(activity)
            val propertiesPanel = FrameLayout(activity)
            val scrollerProp = ScrollView(activity)
            scrollerProp.addView(activity.panelContent, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            propertiesPanel.addView(scrollerProp, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            
            // Initial empty state for properties
            val emptyProps = TextView(activity).apply {
                text = "Select an object to edit its properties"
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }
            activity.panelContent.addView(emptyProps, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

            val effectsPanel = EffectsPanel(activity)

            activity.sourcesPanel = sourcesPanel
            activity.mixerPanel = mixerPanel

            contentArea.addView(sourcesPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            contentArea.addView(mixerPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            contentArea.addView(propertiesPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            contentArea.addView(effectsPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

            fun showPanel(view: View) {
                sourcesPanel.visibility = View.GONE
                mixerPanel.visibility = View.GONE
                propertiesPanel.visibility = View.GONE
                effectsPanel.visibility = View.GONE
                view.visibility = View.VISIBLE
                rightPanel?.visibility = View.VISIBLE
            }

            fun createTab(label: String, view: View?) {
                val btn = Button(activity).apply {
                    text = label
                    textSize = 10f
                    isAllCaps = false
                    setPadding(UI.dp(activity, 4), 0, UI.dp(activity, 4), 0)
                    setOnClickListener { 
                        if (view == null) {
                            rightPanel.visibility = View.GONE
                        } else {
                            showPanel(view)
                        }
                    }
                }
                tabs.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            createTab("Sources", sourcesPanel)
            createTab("Mixer", mixerPanel)
            createTab("Props", propertiesPanel)
            createTab("Effects", effectsPanel)
            createTab("X", null)


            showPanel(sourcesPanel)
            
            val timeline = LinearLayout(activity)
            timeline.orientation = LinearLayout.VERTICAL
            timeline.setBackgroundColor(Color.rgb(15, 17, 22))
            val timelineLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.22f)
            mainLayout.addView(timeline, timelineLp)
            
            val timelineScroll = ScrollView(activity)
            val timelineContent = LinearLayout(activity)
            timelineContent.orientation = LinearLayout.VERTICAL
            timelineContent.setPadding(UI.dp(activity, 8), UI.dp(activity, 8), UI.dp(activity, 8), UI.dp(activity, 8))
            timelineScroll.addView(timelineContent)

            // Playhead
            val playheadRow = LinearLayout(activity)
            playheadRow.orientation = LinearLayout.HORIZONTAL
            playheadRow.gravity = Gravity.CENTER_VERTICAL

            val seek = SeekBar(activity).apply {
                progressTintList = android.content.res.ColorStateList.valueOf(UI.ACCENT)
                thumbTintList = android.content.res.ColorStateList.valueOf(UI.ACCENT2)
                max = activity.proj?.durationMs()?.toInt()?.coerceAtLeast(1) ?: 1
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                        if (fromUser) {
                            activity.timeLabel.text = UI.fmtTime(v.toLong())
                            if (activity.engineReady()) activity.engine.seekTo(v.toLong())
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) { activity.scrubbing = true }
                    override fun onStopTrackingTouch(sb: SeekBar?) {
                        activity.scrubbing = false
                        if (activity.engineReady()) activity.engine.refreshFrames()
                    }
                })
            }

            activity.seek = seek
            playheadRow.addView(seek)
            timelineContent.addView(playheadRow)

            // Tracks
            for (l in activity.proj?.layers ?: emptyList()) {
                val track = TextView(activity).apply {
                    text = l.name.ifBlank { l.type.label }
                    setTextColor(Color.WHITE)
                    textSize = 10f
                    setBackgroundColor(Color.rgb(30, 34, 48))
                    setPadding(UI.dp(activity, 8), UI.dp(activity, 4), UI.dp(activity, 8), UI.dp(activity, 4))
                }
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = UI.dp(activity, 4)
                timelineContent.addView(track, lp)
            }

            timeline.addView(timelineScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

            val transportBar = LinearLayout(activity)
            transportBar.orientation = LinearLayout.HORIZONTAL
            transportBar.gravity = Gravity.CENTER_VERTICAL
            transportBar.setBackgroundColor(Color.rgb(9, 10, 14))
            val transLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.10f)
            mainLayout.addView(transportBar, transLp)
            
            activity.transportBar = transportBar
            buildTransport(activity, transportBar)

            bindPanels(activity, sourcesPanel, mixerPanel, propertiesPanel)

        } else {
            val canvasContainer = FrameLayout(activity)
            canvasContainer.setBackgroundColor(Color.rgb(4, 5, 7))
            val canvasLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.50f)
            mainLayout.addView(canvasContainer, canvasLp)
            
            activity.stage = StageView(activity)
            activity.stage.host = activity
            canvasContainer.addView(activity.stage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))

            val contextPanel = LinearLayout(activity)
            contextPanel.orientation = LinearLayout.VERTICAL
            val contextLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.20f)
            mainLayout.addView(contextPanel, contextLp)
            
            val tabs = LinearLayout(activity)
            tabs.orientation = LinearLayout.HORIZONTAL
            contextPanel.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            val contentArea = FrameLayout(activity)
            contextPanel.addView(contentArea, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

            val sourcesPanel = SourcesPanel(activity)
            val mixerPanel = MixerPanel(activity)
            val propertiesPanel = FrameLayout(activity)
            val scrollerProp = ScrollView(activity)
            scrollerProp.addView(activity.panelContent, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            propertiesPanel.addView(scrollerProp, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            
            // Initial empty state for properties
            val emptyProps = TextView(activity).apply {
                text = "Select an object to edit its properties"
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }
            activity.panelContent.addView(emptyProps, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

            val effectsPanel = EffectsPanel(activity)

            activity.sourcesPanel = sourcesPanel
            activity.mixerPanel = mixerPanel

            contentArea.addView(sourcesPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            contentArea.addView(mixerPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            contentArea.addView(propertiesPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            contentArea.addView(effectsPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))


            fun showPanel(view: View) {
                sourcesPanel.visibility = View.GONE
                mixerPanel.visibility = View.GONE
                propertiesPanel.visibility = View.GONE
                effectsPanel.visibility = View.GONE
                view.visibility = View.VISIBLE
                rightPanel.visibility = View.VISIBLE
            }


            fun createTab(label: String, view: View?) {
                val btn = Button(activity).apply {
                    text = label
                    textSize = 10f
                    isAllCaps = false
                    setPadding(UI.dp(activity, 4), 0, UI.dp(activity, 4), 0)
                    setOnClickListener { 
                        if (view == null) {
                            rightPanel.visibility = View.GONE
                        } else {
                            showPanel(view)
                        }
                    }
                }
                tabs.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            createTab("Sources", sourcesPanel)
            createTab("Mixer", mixerPanel)
            createTab("Props", propertiesPanel)
            createTab("Effects", effectsPanel)
            createTab("X", null)


            showPanel(sourcesPanel)

            val timeline = LinearLayout(activity)
            timeline.orientation = LinearLayout.VERTICAL
            timeline.setBackgroundColor(Color.rgb(15, 17, 22))
            val timelineLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.15f)
            mainLayout.addView(timeline, timelineLp)

            val timelineScroll = ScrollView(activity)
            val timelineContent = LinearLayout(activity)
            timelineContent.orientation = LinearLayout.VERTICAL
            timelineContent.setPadding(UI.dp(activity, 8), UI.dp(activity, 8), UI.dp(activity, 8), UI.dp(activity, 8))
            timelineScroll.addView(timelineContent)

            // Playhead
            val playheadRow = LinearLayout(activity)
            playheadRow.orientation = LinearLayout.HORIZONTAL
            playheadRow.gravity = Gravity.CENTER_VERTICAL

            val seek = SeekBar(activity).apply {
                progressTintList = android.content.res.ColorStateList.valueOf(UI.ACCENT)
                thumbTintList = android.content.res.ColorStateList.valueOf(UI.ACCENT2)
                max = activity.proj?.durationMs()?.toInt()?.coerceAtLeast(1) ?: 1
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                        if (fromUser) {
                            activity.timeLabel.text = UI.fmtTime(v.toLong())
                            if (activity.engineReady()) activity.engine.seekTo(v.toLong())
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) { activity.scrubbing = true }
                    override fun onStopTrackingTouch(sb: SeekBar?) {
                        activity.scrubbing = false
                        if (activity.engineReady()) activity.engine.refreshFrames()
                    }
                })
            }

            activity.seek = seek
            playheadRow.addView(seek)
            timelineContent.addView(playheadRow)

            // Tracks
            for (l in activity.proj?.layers ?: emptyList()) {
                val track = TextView(activity).apply {
                    text = l.name.ifBlank { l.type.label }
                    setTextColor(Color.WHITE)
                    textSize = 10f
                    setBackgroundColor(Color.rgb(30, 34, 48))
                    setPadding(UI.dp(activity, 8), UI.dp(activity, 4), UI.dp(activity, 8), UI.dp(activity, 4))
                }
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = UI.dp(activity, 4)
                timelineContent.addView(track, lp)
            }

            timeline.addView(timelineScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            
            val transportBar = LinearLayout(activity)
            transportBar.orientation = LinearLayout.HORIZONTAL
            transportBar.gravity = Gravity.CENTER_VERTICAL
            transportBar.setBackgroundColor(Color.rgb(9, 10, 14))
            val transLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.08f)
            mainLayout.addView(transportBar, transLp)
            
            activity.transportBar = transportBar
            buildTransport(activity, transportBar)

            bindPanels(activity, sourcesPanel, mixerPanel, propertiesPanel)
        }
    }

    private fun bindPanels(activity: EditorActivity, sourcesPanel: SourcesPanel, mixerPanel: MixerPanel, propertiesPanel: View) {
        sourcesPanel.listener = object : SourcesPanel.Listener {
            override fun onSelect(id: String) { activity.select(id) }
            override fun onToggleVisible(id: String) { activity.ctrl.toggleVisible(id) }
            override fun onAdd() { activity.pickMedia(true) }
            override fun onAddVideo() { activity.pickMedia(true) }
            override fun onAddImage() { activity.pickMedia(false) }
            override fun onRemove() { activity.removeSelectedSource() }
            override fun onHide() { activity.selectedId?.let { activity.ctrl.toggleVisible(it) } }
            override fun onProperties() { 
                propertiesPanel.visibility = View.VISIBLE
                sourcesPanel.visibility = View.GONE
                activity.selectedId?.let { id -> activity.proj?.layerById(id)?.let { layer -> activity.openAdvancedSheet(layer) } }
            }
        }
        mixerPanel.listener = object : MixerPanel.Listener {
            override fun onSelect(id: String) { activity.select(id) }
            override fun onMute(id: String) { activity.ctrl.toggleMuted(id) }
            override fun onSolo(id: String) { activity.ctrl.toggleSolo(id) }
            override fun onVolume(id: String, v: Float) {
                val l = activity.proj?.layerById(id) ?: return
                activity.pushUndoLight()
                if (activity.engineReady()) activity.engine.setVolume(l, v) else l.volume = v
                activity.markDirty()
            }
        }
    }

    private fun buildTransport(activity: EditorActivity, bar: LinearLayout) {

        val playBtn = IconBtn(activity).apply {
            setIcon(R.drawable.ic_play, Color.WHITE, "Play")
            setOnClickListener { activity.togglePlay() }
        }
        activity.playBtn = playBtn
        bar.addView(playBtn, LinearLayout.LayoutParams(UI.dp(activity, 44), UI.dp(activity, 44)))

        
        val recBtn = Button(activity).apply {
            activity.recordBtn = this
            text = "● Record"
            setTextColor(Color.RED)
            setOnClickListener { activity.recordButtonTap() }
        }
        bar.addView(recBtn)
        
        val stopBtn = Button(activity).apply {
            text = "⏹ Stop"
            setOnClickListener { activity.controlsStopTap() }
        }
        bar.addView(stopBtn)
        
        val timeLabel = TextView(activity).apply {
            text = "00:00:00"
            setTextColor(Color.WHITE)
            setPadding(UI.dp(activity, 10), 0, 0, 0)
        }
        activity.timeLabel = timeLabel
        bar.addView(timeLabel)

        val durationLabel = TextView(activity).apply {
            text = "/ 00:00:00"
            setTextColor(Color.GRAY)
            setPadding(UI.dp(activity, 4), 0, 0, 0)
        }
        activity.durationLabel = durationLabel
        bar.addView(durationLabel)

    }
}
