package com.rehman.ahmedreactionstudio.editor

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.rehman.ahmedreactionstudio.R
import com.rehman.ahmedreactionstudio.camera.CameraActivity
import com.rehman.ahmedreactionstudio.capture.ScreenCaptureService
import com.rehman.ahmedreactionstudio.core.Aspect
import com.rehman.ahmedreactionstudio.core.Layer
import com.rehman.ahmedreactionstudio.core.LayerFit
import com.rehman.ahmedreactionstudio.core.LayerType
import com.rehman.ahmedreactionstudio.core.MediaKit
import com.rehman.ahmedreactionstudio.core.Project
import com.rehman.ahmedreactionstudio.core.ProjectStore
import com.rehman.ahmedreactionstudio.core.SourceController
import com.rehman.ahmedreactionstudio.core.UndoStack
import com.rehman.ahmedreactionstudio.core.applyLayersJson
import com.rehman.ahmedreactionstudio.core.layersJsonOf
import com.rehman.ahmedreactionstudio.export.Exporter
import com.rehman.ahmedreactionstudio.ui.DiagnosticsActivity
import com.rehman.ahmedreactionstudio.util.UI
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OBS-style studio (docs/OBS_SOURCE_PLAN.md):
 *
 *  - the composition canvas fills the screen; controls float over it;
 *  - SOURCES are first-class: selecting one shows a Quick Control Bar
 *    (hide / mute / pause / lock / fit / ◉ wheel / ⋮ sheet) — every control
 *    works in one tap, no settings maze;
 *  - the Source Dock is a mini mixer: per-row eye + mute toggles, live status,
 *    drag-handle Z reordering;
 *  - the radial wheel blooms contextually per source type with spring
 *    animations, haptics and vector icons;
 *  - all mutations go through SourceController (command layer) → undo/redo
 *    and preview == export are guaranteed by construction.
 */
class EditorActivity : Activity(), StageView.Host {

    companion object {
        const val EXTRA_PROJECT_ID = "pid"
        const val EXTRA_PROJECT_NAME = "pname"
        const val EXTRA_PROJECT_ASPECT = "paspect"
        const val REQ_PICK_VIDEO = 41
        const val REQ_PICK_IMAGE = 42
        const val REQ_CAMERA = 43
        const val REQ_SCREEN_CAPTURE = 44
        const val REQ_APP_PERMS = 45
    }

    private lateinit var store: ProjectStore
    private var proj: Project? = null
    private var projectId: String = ""
    private var selectedId: String? = null

    private lateinit var stage: StageView
    private lateinit var emptyOverlay: LinearLayout
    private lateinit var playBtn: IconBtn
    private lateinit var timeLabel: TextView
    private lateinit var durationLabel: TextView
    private lateinit var seek: SeekBar
    private lateinit var aspectChip: TextView
    private lateinit var quickBar: LinearLayout
    private lateinit var panelContent: LinearLayout
    private lateinit var sheet: LinearLayout
    private lateinit var dockContainer: LinearLayout
    private lateinit var tabBtns: HashMap<String, TextView>
    private lateinit var recChip: TextView
    private lateinit var wheel: RadialWheelView
    private lateinit var dock: SourceDock
    private lateinit var ctrl: SourceController
    private lateinit var rootFrame: FrameLayout
    private var wheelBtn: IconBtn? = null
    private var sheetTab: String? = null

    private lateinit var engine: PreviewEngine
    private val undo = UndoStack()
    private val saveHandler = Handler(Looper.getMainLooper())
    private val autosave = Runnable { flushSave() }
    private var scrubbing = false
    private var lastPlayingSig = ""

    private var exportDialog: ProgressDialog? = null
    private val exportCancel = AtomicBoolean(false)
    private var exportRunning = false

    // role assigned to the next imported media: "main" canvas or "pip"
    private var pendingRole = "main"
    private var pendingCameraRole = "main"

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        UI.styleWindow(this)
        store = ProjectStore(this)
        projectId = intent.getStringExtra(EXTRA_PROJECT_ID)
            ?: b?.getString("pid")
            ?: store.listIds().firstOrNull()
            ?: run { finish(); return }
        var p = store.load(projectId)
        if (p == null) p = store.loadSnapshot(projectId)
        if (p == null) { UI.toast(this, "Project file missing"); finish(); return }
        proj = p
        selectedId = b?.getString("sel")
        store.markOpen(projectId)

        ctrl = SourceController({ this.proj!! }, { pushUndo() }, { onSourceChanged() })

        applyOrientationFor(p.aspect)
        engine = PreviewEngine(this, { this.proj!! }, store) { ms -> onTick(ms) }
        engine.attach(projectId)

        ScreenCaptureService.onStopped = { f ->
            runOnUiThread {
                recChip.visibility = View.GONE
                if (f != null) consumeMediaFile(f, if (p.layers.isEmpty()) "main" else "pip",
                    name = "Screen record", type = LayerType.SCREEN)
                else UI.toast(this, "Screen recording was empty")
            }
        }

        dockContainer = LinearLayout(this)
        dockContainer.orientation = LinearLayout.VERTICAL
        buildUi()
        dock = SourceDock(this, dockContainer, { this.proj!! }, { selectedId },
            { id -> select(id) },
            { l, what -> quickToggle(l, what) },
            { l -> openAdvancedSheet(l) },
            { pushUndo() },
            { from, to -> ctrl.reorderLive(from, to); stage.refresh() },
            { markDirty(); refreshAll() })
        rebuildDock()
        refreshQuickBar()
        updateName()
        engine.refreshFrames()
        updateEmptyState()
    }

    private fun applyOrientationFor(a: Aspect) {
        requestedOrientation = when (a) {
            Aspect.R169 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            Aspect.R916 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            Aspect.R11 -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putString("pid", projectId)
        out.putString("sel", selectedId)
        super.onSaveInstanceState(out)
    }

    private fun engineReady(): Boolean = this::engine.isInitialized

    override fun onResume() {
        super.onResume()
        val pending = ScreenCaptureService.pendingFile
        if (pending != null && pending.exists()) {
            ScreenCaptureService.pendingFile = null
            val role = if (proj?.layers?.isEmpty() == true) "main" else "pip"
            consumeMediaFile(pending, role, name = "Screen record", type = LayerType.SCREEN)
        }
        if (recChip.visibility == View.VISIBLE && !ScreenCaptureService.running) recChip.visibility = View.GONE
        if (engineReady()) engine.refreshFrames()
    }

    override fun onPause() {
        flushSave()
        super.onPause()
    }

    override fun onStop() {
        if (engineReady()) engine.stopSnapshots()
        super.onStop()
    }

    override fun onDestroy() {
        saveHandler.removeCallbacksAndMessages(null)
        flushSave()
        if (engineReady()) engine.release()
        if (ScreenCaptureService.onStopped != null) ScreenCaptureService.onStopped = null
        store.clearOpen(projectId)
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (wheel.isOpen()) { wheel.dismiss(true); return }
        if (sheetTab != null) { setSheet(null); return }
        flushSave()
        store.clearOpen(projectId)
        super.onBackPressed()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        stage.post { syncPreviewTarget() }
        stage.refresh()
    }

    // ================= UI: fullscreen canvas + floating overlays =================

    private fun buildUi() {
        val root = FrameLayout(this)
        rootFrame = root
        root.setBackgroundColor(UI.BLACK)

        // ===== stage fills the whole screen =====
        val stageFrame = FrameLayout(this)
        stageFrame.setBackgroundColor(Color.rgb(4, 5, 7))
        root.addView(stageFrame, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        stage = StageView(this)
        stage.host = this
        stageFrame.addView(stage, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))

        // empty-state prompt
        emptyOverlay = LinearLayout(this)
        emptyOverlay.orientation = LinearLayout.VERTICAL
        emptyOverlay.gravity = Gravity.CENTER
        emptyOverlay.setPadding(UI.dp(this, 24), 0, UI.dp(this, 24), 0)
        root.addView(emptyOverlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        buildTopBar(root)

        // screen-recording chip
        recChip = TextView(this)
        recChip.text = "● STOP SCREEN-REC"
        recChip.gravity = Gravity.CENTER
        recChip.setTextColor(UI.DANGER)
        recChip.textSize = 11f
        recChip.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        recChip.setPadding(UI.dp(this, 14), UI.dp(this, 8), UI.dp(this, 14), UI.dp(this, 8))
        recChip.background = Ic.pill(this, Color.argb(220, 20, 8, 10), 18f,
            Color.argb(180, 255, 90, 90))
        recChip.visibility = View.GONE
        recChip.setOnClickListener { stopScreenCapture() }
        val rlp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        rlp.topMargin = UI.dp(this, 58)
        root.addView(recChip, rlp)

        // ===== floating quick control bar (above the dock) =====
        val qWrap = HorizontalScrollView(this)
        qWrap.isHorizontalScrollBarEnabled = false
        val qlp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        qlp.bottomMargin = UI.dp(this, 118)
        root.addView(qWrap, qlp)
        quickBar = LinearLayout(this)
        quickBar.orientation = LinearLayout.HORIZONTAL
        quickBar.gravity = Gravity.CENTER_VERTICAL
        quickBar.setPadding(UI.dp(this, 8), UI.dp(this, 6), UI.dp(this, 8), UI.dp(this, 6))
        quickBar.background = Ic.pill(this, Color.argb(225, 13, 15, 21), 24f,
            Color.argb(90, 255, 255, 255))
        quickBar.visibility = View.GONE
        qWrap.addView(quickBar)

        buildSheet(root)

        // ===== radial wheel overlay (top of everything) =====
        wheel = RadialWheelView(this)
        root.addView(wheel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(root)
        stage.post { syncPreviewTarget() }
    }

    private fun buildTopBar(root: FrameLayout) {
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(UI.dp(this, 10), UI.dp(this, 8), UI.dp(this, 10), UI.dp(this, 8))
        val tg = GradientDrawable()
        tg.orientation = GradientDrawable.Orientation.TOP_BOTTOM
        tg.colors = intArrayOf(Color.argb(190, 0, 0, 0), Color.argb(0, 0, 0, 0))
        top.background = tg
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val back = IconBtn(this)
        back.layoutParams = IconBtn.sized(this, 40)
        back.setIcon(R.drawable.ic_back)
        back.setOnClickListener { onBackPressed() }
        top.addView(back)

        val nameCol = LinearLayout(this)
        nameCol.orientation = LinearLayout.VERTICAL
        val nlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        nlp.setMargins(UI.dp(this, 8), 0, UI.dp(this, 6), 0)
        nameCol.layoutParams = nlp
        val nameView = TextView(this)
        nameView.id = View.generateViewId()
        nameView.tag = "name"
        nameView.setTextColor(UI.FG)
        nameView.textSize = 13.5f
        nameView.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        nameView.maxLines = 1
        nameCol.addView(nameView)
        val meta = TextView(this)
        meta.tag = "meta"
        meta.setTextColor(Color.argb(190, 255, 255, 255))
        meta.textSize = 9.5f
        nameCol.addView(meta)
        top.addView(nameCol)

        // aspect chip: one tap cycles 16:9 → 9:16 → 1:1
        aspectChip = TextView(this)
        aspectChip.gravity = Gravity.CENTER
        aspectChip.setTextColor(Color.WHITE)
        aspectChip.textSize = 11f
        aspectChip.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        aspectChip.setPadding(UI.dp(this, 12), UI.dp(this, 8), UI.dp(this, 12), UI.dp(this, 8))
        aspectChip.background = Ic.pill(this, Color.argb(200, 30, 34, 44), 16f,
            Color.argb(90, 255, 255, 255))
        aspectChip.setOnClickListener { cycleAspect() }
        val alp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        alp.setMargins(0, 0, UI.dp(this, 8), 0)
        aspectChip.layoutParams = alp
        top.addView(aspectChip)
        updateAspectChip()

        val undoB = IconBtn(this)
        undoB.layoutParams = IconBtn.sized(this, 40)
        undoB.setIcon(R.drawable.ic_undo)
        undoB.setOnClickListener { doUndo() }
        top.addView(undoB)

        val redoB = IconBtn(this)
        redoB.layoutParams = IconBtn.sized(this, 40)
        redoB.setIcon(R.drawable.ic_redo)
        redoB.setOnClickListener { doRedo() }
        top.addView(redoB)

        val diag = IconBtn(this)
        diag.layoutParams = IconBtn.sized(this, 40)
        diag.setIcon(R.drawable.ic_settings)
        diag.setOnClickListener { startActivity(Intent(this, DiagnosticsActivity::class.java)) }
        top.addView(diag)
    }

    private fun buildSheet(root: FrameLayout) {
        sheet = LinearLayout(this)
        sheet.orientation = LinearLayout.VERTICAL
        val sg = GradientDrawable()
        sg.orientation = GradientDrawable.Orientation.TOP_BOTTOM
        sg.colors = intArrayOf(Color.argb(235, 10, 12, 17), Color.argb(250, 8, 9, 13))
        sheet.background = sg
        val slp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        root.addView(sheet, slp)

        // expandable panel (Sources / Add / Canvas / Export)
        panelContent = LinearLayout(this)
        panelContent.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(this)
        scroll.tag = "panelScroll"
        scroll.isVerticalScrollBarEnabled = false
        val maxH = (resources.displayMetrics.heightPixels * 0.40f).toInt()
            .coerceAtLeast(UI.dp(this, 170))
        scroll.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxH)
        scroll.visibility = View.GONE
        scroll.addView(panelContent, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sheet.addView(scroll)

        // transport row (always visible)
        val transport = LinearLayout(this)
        transport.orientation = LinearLayout.HORIZONTAL
        transport.gravity = Gravity.CENTER_VERTICAL
        transport.setPadding(UI.dp(this, 10), UI.dp(this, 8), UI.dp(this, 10), UI.dp(this, 4))
        sheet.addView(transport)

        playBtn = IconBtn(this)
        playBtn.layoutParams = IconBtn.sized(this, 44)
        val pg = GradientDrawable()
        pg.shape = GradientDrawable.OVAL
        pg.setColor(UI.ACCENT)
        playBtn.background = pg
        playBtn.setIcon(R.drawable.ic_play, Color.WHITE)
        playBtn.setOnClickListener { togglePlay() }
        transport.addView(playBtn)

        timeLabel = TextView(this)
        timeLabel.text = "0:00"
        timeLabel.setTextColor(Color.WHITE)
        timeLabel.textSize = 12f
        timeLabel.typeface = Typeface.create("monospace", Typeface.NORMAL)
        val tlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        tlp.setMargins(UI.dp(this, 10), 0, UI.dp(this, 6), 0)
        timeLabel.layoutParams = tlp
        transport.addView(timeLabel)

        seek = SeekBar(this)
        seek.progressTintList = android.content.res.ColorStateList.valueOf(UI.ACCENT)
        seek.thumbTintList = android.content.res.ColorStateList.valueOf(UI.ACCENT2)
        seek.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        seek.max = proj!!.durationMs().toInt().coerceAtLeast(1)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                if (fromUser) {
                    timeLabel.text = UI.fmtTime(v.toLong())
                    if (engineReady()) engine.seekTo(v.toLong())
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { scrubbing = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                scrubbing = false
                if (engineReady()) engine.refreshFrames()
            }
        })
        transport.addView(seek)

        durationLabel = TextView(this)
        durationLabel.text = "/ " + UI.fmtTime(proj!!.durationMs())
        durationLabel.setTextColor(Color.argb(190, 255, 255, 255))
        durationLabel.textSize = 12f
        val dlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        dlp.setMargins(UI.dp(this, 6), 0, UI.dp(this, 4), 0)
        durationLabel.layoutParams = dlp
        transport.addView(durationLabel)

        // tab bar: Sources / Add / Canvas / Export
        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabs.setPadding(UI.dp(this, 6), UI.dp(this, 4), UI.dp(this, 6), UI.dp(this, 10))
        sheet.addView(tabs)
        tabBtns = HashMap()
        val defs = listOf(
            "sources" to ("Sources" to R.drawable.ic_layers),
            "add" to ("Add" to R.drawable.ic_add),
            "canvas" to ("Canvas" to R.drawable.ic_aspect),
            "export" to ("Export" to R.drawable.ic_export))
        for ((key, ln) in defs) {
            val t = TextView(this)
            t.text = ln.first
            t.gravity = Gravity.CENTER
            t.setTextColor(UI.FG)
            t.textSize = 12f
            t.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            t.setCompoundDrawablePadding(UI.dp(this, 6))
            t.setCompoundDrawablesRelativeWithIntrinsicBounds(
                Ic.get(this, ln.second, UI.FG2), null, null, null)
            val lp = LinearLayout.LayoutParams(0, UI.dp(this, 42), 1f)
            lp.setMargins(UI.dp(this, 4), 0, UI.dp(this, 4), 0)
            t.layoutParams = lp
            val g = GradientDrawable()
            g.cornerRadius = UI.dpf(this, 12f)
            g.setColor(Color.argb(180, 24, 27, 36))
            g.setStroke(1, Color.argb(60, 255, 255, 255))
            t.background = g
            t.setOnClickListener { onTab(key) }
            tabs.addView(t)
            tabBtns[key] = t
        }
    }

    // ================= tabs / sheet =================

    private fun onTab(key: String) {
        if (sheetTab == key) { setSheet(null); return }
        setSheet(key)
    }

    private fun setSheet(tab: String?) {
        sheetTab = tab
        val sv = sheet.findViewWithTag<ScrollView>("panelScroll")
        highlightTab(tab)
        if (tab == null) {
            sv.visibility = View.GONE
            return
        }
        panelContent.removeAllViews()
        when (tab) {
            "sources" -> buildSourcesPanel()
            "add" -> buildAddPanel()
            "canvas" -> buildCanvasPanel()
            "export" -> buildExportPanel()
        }
        sv.visibility = View.VISIBLE
        panelContent.alpha = 0f
        panelContent.translationY = UI.dpf(this, 26f)
        panelContent.animate().alpha(1f).translationY(0f)
            .setDuration(230).setInterpolator(OvershootInterpolator(1.2f)).start()
    }

    private fun highlightTab(active: String?) {
        for ((k, v) in tabBtns) {
            val g = v.background as GradientDrawable
            if (k == active) {
                g.setColor(Color.argb(235, 255, 90, 44))
                v.setTextColor(Color.WHITE)
                v.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    Ic.get(this, when (k) {
                        "sources" -> R.drawable.ic_layers
                        "add" -> R.drawable.ic_add
                        "canvas" -> R.drawable.ic_aspect
                        else -> R.drawable.ic_export
                    }, Color.WHITE), null, null, null)
            } else {
                g.setColor(Color.argb(180, 24, 27, 36))
                v.setTextColor(UI.FG)
                v.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    Ic.get(this, when (k) {
                        "sources" -> R.drawable.ic_layers
                        "add" -> R.drawable.ic_add
                        "canvas" -> R.drawable.ic_aspect
                        else -> R.drawable.ic_export
                    }, UI.FG2), null, null, null)
            }
        }
    }

    // ================= panel: SOURCES dock =================

    private fun buildSourcesPanel() {
        section("SOURCES — tap select · eye/mute toggle · ⠿ drag = Z order · long press = more")
        // the dock container is reused across panel rebuilds — re-parent it here
        (dockContainer.parent as? ViewGroup)?.removeView(dockContainer)
        dockContainer.setPadding(UI.dp(this, 8), UI.dp(this, 2), UI.dp(this, 8), UI.dp(this, 10))
        panelContent.addView(dockContainer)
        dock.rebuild()
        if (proj!!.layers.isEmpty()) {
            val b = UI.btn(this, "+  Add your first source", accent = true)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 44))
            lp.setMargins(UI.dp(this, 12), UI.dp(this, 4), UI.dp(this, 12), UI.dp(this, 12))
            b.layoutParams = lp
            b.setOnClickListener { setSheet("add") }
            panelContent.addView(b)
        }
    }

    private fun rebuildDock() {
        if (this::dock.isInitialized) dock.rebuild()
    }

    // ================= panel: ADD =================

    private fun section(title: String) {
        val t = TextView(this)
        t.text = title
        t.setTextColor(UI.ACCENT2)
        t.textSize = 10.5f
        t.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(UI.dp(this, 14), UI.dp(this, 10), UI.dp(this, 14), UI.dp(this, 5))
        t.layoutParams = lp
        panelContent.addView(t)
    }

    private fun panelButtonRow(container: LinearLayout, vararg items: Pair<String, () -> Unit>) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(UI.dp(this, 8), 0, UI.dp(this, 8), 0)
        for ((label, fn) in items) {
            val b = UI.btn(this, label, accent = false, small = true)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(UI.dp(this, 4), UI.dp(this, 3), UI.dp(this, 4), UI.dp(this, 3))
            b.layoutParams = lp
            b.setOnClickListener { fn() }
            row.addView(b)
        }
        container.addView(row)
    }

    private fun buildAddPanel() {
        val hasMain = proj!!.layers.isNotEmpty()
        if (!hasMain) {
            val t = UI.label(this,
                "First pick what fills the MAIN CANVAS (the background).", dim = false, size = 13f)
            t.gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(UI.dp(this, 16), UI.dp(this, 12), UI.dp(this, 16), UI.dp(this, 2))
            t.layoutParams = lp
            panelContent.addView(t)
            section("MAIN CANVAS SOURCE")
        } else {
            section("ADD SOURCE (becomes a PiP overlay)")
        }
        panelButtonRow(panelContent,
            "🎬 Video file" to { pickMedia(video = true) },
            "🖼 Image file" to { pickMedia(video = false) })
        panelButtonRow(panelContent,
            "🎥 Record camera" to { openCamera() },
            "⛺ Record screen" to { startScreenCapture() })
        section("OVERLAYS")
        panelButtonRow(panelContent, "💬 Text" to { addText() })
        val note = UI.label(this,
            "Videos: MP4, AVI, WebM, MKV, 3GP… (anything this device can decode).",
            dim = true, size = 10.5f)
        val nlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        nlp.setMargins(UI.dp(this, 14), UI.dp(this, 8), UI.dp(this, 14), UI.dp(this, 10))
        note.layoutParams = nlp
        panelContent.addView(note)
    }

    // ================= panel: CANVAS =================

    private fun buildCanvasPanel() {
        section("CANVAS RATIO (rotates the screen, layers keep their geometry)")
        panelButtonRow(panelContent,
            "16:9" to { changeAspect(Aspect.R169) },
            "9:16" to { changeAspect(Aspect.R916) },
            "1:1" to { changeAspect(Aspect.R11) })
        section("CANVAS BACKGROUND")
        panelButtonRow(panelContent,
            "Dark" to { setBg(Color.rgb(16, 20, 24)) },
            "White" to { setBg(Color.rgb(255, 255, 255)) },
            "Orange" to { setBg(Color.rgb(255, 90, 44)) })
        panelButtonRow(panelContent,
            "Navy" to { setBg(Color.rgb(30, 60, 120)) },
            "Green" to { setBg(Color.rgb(20, 120, 90)) },
            "Purple" to { setBg(Color.rgb(120, 30, 90)) })
        if (selectedId != null) {
            section("SELECTED SOURCE")
            panelButtonRow(panelContent,
                "Set selected as canvas background" to {
                    guardRecording { ctrl.setAsCanvasBackground(selectedId) }
                })
        }
    }

    private fun setBg(c: Int) {
        pushUndo()
        proj!!.bgColor = c
        markDirty(); stage.refresh()
    }

    private fun cycleAspect() {
        val next = when (proj!!.aspect) {
            Aspect.R169 -> Aspect.R916
            Aspect.R916 -> Aspect.R11
            Aspect.R11 -> Aspect.R169
        }
        aspectChip.animate().cancel()
        aspectChip.animate().scaleX(0.8f).scaleY(0.8f).setDuration(80).withEndAction {
            changeAspect(next)
            aspectChip.animate().scaleX(1f).scaleY(1f).setDuration(260)
                .setInterpolator(OvershootInterpolator(2f)).start()
        }.start()
    }

    private fun changeAspect(a: Aspect) {
        val p = proj!!
        if (p.aspect == a) return
        if (exportRunning) { UI.toast(this, "Stop the export first"); return }
        p.aspect = a
        applyOrientationFor(a)
        updateAspectChip()
        markDirty()
        stage.post { syncPreviewTarget() }
        stage.refresh()
        UI.toast(this, "Canvas ${a.code} — every source keeps its own frame ratio")
    }

    private fun updateAspectChip() { aspectChip.text = proj!!.aspect.code }

    // ================= panel: EXPORT =================

    private fun buildExportPanel() {
        val p = proj!!
        section("EXPORT VIDEO")
        if (p.layers.isEmpty()) {
            val t = UI.label(this, "Nothing to export yet — add a source first.",
                dim = true, size = 12.5f)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(UI.dp(this, 14), UI.dp(this, 6), UI.dp(this, 14), UI.dp(this, 14))
            t.layoutParams = lp
            panelContent.addView(t)
            return
        }

        val avail = Exporter.Codec.available().ifEmpty { listOf(Exporter.Codec.H264) }
        val codecNames = avail.map { it.label }
        var codecIdx = avail.indexOfFirst { it == Exporter.Codec.H264 }.coerceAtLeast(0)
        val qualityNames = arrayOf("Fast", "Balanced", "High quality")
        val resNames = arrayOf("Small (~480p)", "Medium (~720p)", "Large (~1080p)")
        val fpsNames = arrayOf("24 fps", "30 fps")
        var quality = 1
        var maxDim = 720
        var fps = 30

        fun valueRow(label: String, initial: String, options: List<String>, onPick: (Int) -> Unit): TextView {
            val t = UI.btn(this, "$label:  $initial", accent = false, small = true)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(UI.dp(this, 12), UI.dp(this, 4), UI.dp(this, 12), UI.dp(this, 4))
            t.layoutParams = lp
            t.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            t.setOnClickListener {
                AlertDialog.Builder(this@EditorActivity).setTitle(label)
                    .setItems(options.toTypedArray()) { _, which ->
                        onPick(which); t.text = "$label:  ${options[which]}"
                    }.show()
            }
            panelContent.addView(t)
            return t
        }

        valueRow("Format / codec", codecNames[codecIdx], codecNames) { codecIdx = it }
        valueRow("Resolution", resNames[1], resNames.toList()) { maxDim = intArrayOf(480, 720, 1080)[it] }
        valueRow("Quality", qualityNames[1], qualityNames.toList()) { quality = it }
        valueRow("Frame rate", fpsNames[1], fpsNames.toList()) { fps = if (it == 0) 24 else 30 }

        val info = UI.label(this,
            "H.264/H.265 → MP4 · VP8/VP9 → WebM. What you see is exactly what exports.\n" +
            "Duration ${UI.fmtTime(p.durationMs())} · ${p.layers.size} sources",
            dim = true, size = 10.5f)
        val ilp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        ilp.setMargins(UI.dp(this, 14), UI.dp(this, 8), UI.dp(this, 14), UI.dp(this, 6))
        info.layoutParams = ilp
        panelContent.addView(info)

        val go = UI.btn(this, "⇪  Export video", accent = true)
        val glp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 48))
        glp.setMargins(UI.dp(this, 12), UI.dp(this, 6), UI.dp(this, 12), UI.dp(this, 14))
        go.layoutParams = glp
        go.setOnClickListener {
            setSheet(null)
            runExport(quality, maxDim, fps, avail[codecIdx])
        }
        panelContent.addView(go)
    }

    // ================= Quick Control Bar =================

    private fun refreshQuickBar() {
        if (!this::quickBar.isInitialized) return
        quickBar.removeAllViews()
        val l = selectedId?.let { proj!!.layerById(it) }
        if (l == null) {
            if (quickBar.visibility == View.VISIBLE) {
                quickBar.animate().alpha(0f).translationY(UI.dpf(this, 20f)).setDuration(180)
                    .withEndAction { quickBar.visibility = View.GONE }.start()
            }
            return
        }
        // cancel any in-flight hide animation so its withEndAction(GONE)
        // cannot swallow the bar we are about to rebuild
        quickBar.animate().cancel()
        val wasGone = quickBar.visibility != View.VISIBLE
        if (!wasGone) { quickBar.alpha = 1f; quickBar.translationY = 0f }

        // name pill
        val pill = LinearLayout(this)
        pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.setPadding(UI.dp(this, 10), 0, UI.dp(this, 10), 0)
        val ic = android.widget.ImageView(this)
        ic.setImageDrawable(Ic.get(this, Ic.typeIcon(l.type), UI.ACCENT2))
        val iclp = LinearLayout.LayoutParams(UI.dp(this, 16), UI.dp(this, 16))
        iclp.setMargins(0, 0, UI.dp(this, 7), 0)
        ic.layoutParams = iclp
        pill.addView(ic)
        val nm = TextView(this)
        nm.text = l.name.ifBlank { l.type.name }
        nm.setTextColor(Color.WHITE)
        nm.textSize = 12f
        nm.maxLines = 1
        nm.maxWidth = UI.dp(this, 110)
        pill.addView(nm)
        val plp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UI.dp(this, 36))
        plp.setMargins(UI.dp(this, 2), 0, UI.dp(this, 6), 0)
        pill.layoutParams = plp
        quickBar.addView(pill)

        fun bar(resId: Int, tint: Int, fn: () -> Unit): IconBtn {
            val b = IconBtn(this)
            b.layoutParams = IconBtn.sized(this, 38)
            b.setIcon(resId, tint)
            b.setOnClickListener { fn() }
            quickBar.addView(b)
            return b
        }

        bar(if (l.visible) R.drawable.ic_eye else R.drawable.ic_eye_off,
            if (l.visible) UI.FG else Color.argb(110, 255, 255, 255)) {
            ctrl.toggleVisible(l.id); animateHideFeedback(l)
        }
        if (l.isVideoLike()) {
            val effMuted = ctrl.effectiveMuted(l)
            bar(if (effMuted) R.drawable.ic_volume_off else R.drawable.ic_volume,
                if (effMuted) UI.DANGER else UI.FG) { ctrl.toggleMuted(l.id) }
            bar(if (l.playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (l.playing) UI.FG else UI.ACCENT2) {
                engine.toggleLayerPlay(l); markDirty(); refreshAll()
            }
        }
        bar(if (l.locked) R.drawable.ic_lock else R.drawable.ic_lock_open,
            if (l.locked) UI.ACCENT2 else UI.FG) { ctrl.toggleLocked(l.id) }
        if (!l.isText()) {
            bar(if (l.fit == Layer.FIT_FIT) R.drawable.ic_fit else R.drawable.ic_fill,
                if (l.fit == Layer.FIT_FIT) UI.ACCENT2 else UI.FG) { ctrl.toggleFit(l.id) }
        }
        // radial wheel (re-resolves the selection on tap so state is never stale)
        wheelBtn = bar(R.drawable.ic_wheel, UI.ACCENT2) {
            val wb = wheelBtn ?: return@bar
            val cur = selectedId?.let { proj!!.layerById(it) } ?: return@bar
            openWheel(wb, cur)
        }
        // more → advanced sheet
        bar(R.drawable.ic_more, UI.FG) {
            val cur = selectedId?.let { proj!!.layerById(it) } ?: return@bar
            openAdvancedSheet(cur)
        }

        if (wasGone) {
            quickBar.alpha = 0f
            quickBar.translationY = UI.dpf(this, 20f)
            quickBar.visibility = View.VISIBLE
            quickBar.animate().alpha(1f).translationY(0f).setDuration(240)
                .setInterpolator(OvershootInterpolator(1.4f)).start()
        }
    }

    private fun animateHideFeedback(l: Layer) {
        if (!l.visible) UI.toast(this, "${l.name} hidden — it stays in the source list")
    }

    private fun quickToggle(l: Layer, what: String) {
        when (what) {
            "vis" -> ctrl.toggleVisible(l.id)
            "mute" -> ctrl.toggleMuted(l.id)
        }
    }

    // ================= Radial wheel =================

    private fun openWheel(anchor: View, l: Layer) {
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val rootLoc = IntArray(2)
        rootFrame.getLocationOnScreen(rootLoc)
        val ax = (loc[0] + anchor.width / 2f) - rootLoc[0]
        val ay = (loc[1] + anchor.height / 2f) - rootLoc[1]

        val petals = ArrayList<RadialWheelView.Petal>()
        if (l.isVideoLike()) {
            petals.add(RadialWheelView.Petal(
                if (l.playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (l.playing) "Pause" else "Play", !l.playing) {
                engine.toggleLayerPlay(l); markDirty(); refreshAll()
            })
            petals.add(RadialWheelView.Petal(
                if (ctrl.effectiveMuted(l)) R.drawable.ic_volume else R.drawable.ic_volume_off,
                if (ctrl.effectiveMuted(l)) "Unmute" else "Mute", ctrl.effectiveMuted(l)) {
                ctrl.toggleMuted(l.id)
            })
            petals.add(RadialWheelView.Petal(R.drawable.ic_loop, if (l.loop) "Loop on" else "Loop", l.loop) {
                ctrl.toggleLoop(l.id)
            })
            petals.add(RadialWheelView.Petal(
                if (l.visible) R.drawable.ic_eye_off else R.drawable.ic_eye,
                if (l.visible) "Hide" else "Show", !l.visible) { ctrl.toggleVisible(l.id) })
            petals.add(RadialWheelView.Petal(
                if (l.locked) R.drawable.ic_lock else R.drawable.ic_lock_open,
                if (l.locked) "Unlock" else "Lock", l.locked) { ctrl.toggleLocked(l.id) })
            petals.add(RadialWheelView.Petal(
                if (l.fit == Layer.FIT_FIT) R.drawable.ic_fit else R.drawable.ic_fill,
                if (l.fit == Layer.FIT_FIT) "Whole frame" else "Fill box", l.fit == Layer.FIT_FIT) {
                ctrl.toggleFit(l.id)
            })
            petals.add(RadialWheelView.Petal(R.drawable.ic_copy, "Duplicate") {
                val nid = ctrl.duplicate(l.id); selectedId = nid; refreshAll()
            })
            petals.add(RadialWheelView.Petal(R.drawable.ic_delete, "Delete", danger = true) {
                guardRecording {
                    ctrl.delete(l.id); selectedId = null; engine.evict(l.id); refreshAll()
                }
            })
        } else if (l.isText()) {
            petals.add(RadialWheelView.Petal(R.drawable.ic_edit, "Edit") { editTextLayer(l) })
            petals.add(RadialWheelView.Petal(R.drawable.ic_palette, "Color") {
                pushUndo(); l.textColor = nextColor(l.textColor); markDirty(); stage.refresh()
            })
            petals.add(RadialWheelView.Petal(
                if (l.visible) R.drawable.ic_eye_off else R.drawable.ic_eye,
                if (l.visible) "Hide" else "Show", !l.visible) { ctrl.toggleVisible(l.id) })
            petals.add(RadialWheelView.Petal(
                if (l.locked) R.drawable.ic_lock else R.drawable.ic_lock_open,
                if (l.locked) "Unlock" else "Lock", l.locked) { ctrl.toggleLocked(l.id) })
            petals.add(RadialWheelView.Petal(R.drawable.ic_center, "Center") { ctrl.center(l.id) })
            petals.add(RadialWheelView.Petal(R.drawable.ic_delete, "Delete", danger = true) {
                guardRecording {
                    ctrl.delete(l.id); selectedId = null; refreshAll()
                }
            })
        } else {
            petals.add(RadialWheelView.Petal(
                if (l.visible) R.drawable.ic_eye_off else R.drawable.ic_eye,
                if (l.visible) "Hide" else "Show", !l.visible) { ctrl.toggleVisible(l.id) })
            petals.add(RadialWheelView.Petal(
                if (l.locked) R.drawable.ic_lock else R.drawable.ic_lock_open,
                if (l.locked) "Unlock" else "Lock", l.locked) { ctrl.toggleLocked(l.id) })
            petals.add(RadialWheelView.Petal(
                if (l.fit == Layer.FIT_FIT) R.drawable.ic_fit else R.drawable.ic_fill,
                if (l.fit == Layer.FIT_FIT) "Whole frame" else "Fill box", l.fit == Layer.FIT_FIT) {
                ctrl.toggleFit(l.id)
            })
            petals.add(RadialWheelView.Petal(R.drawable.ic_center, "Center") { ctrl.center(l.id) })
            petals.add(RadialWheelView.Petal(R.drawable.ic_copy, "Duplicate") {
                val nid = ctrl.duplicate(l.id); selectedId = nid; refreshAll()
            })
            petals.add(RadialWheelView.Petal(R.drawable.ic_delete, "Delete", danger = true) {
                guardRecording {
                    ctrl.delete(l.id); selectedId = null; refreshAll()
                }
            })
        }
        wheel.show(Ic.typeIcon(l.type), l.name, petals, ax, ay)
    }

    /** destructive operations are locked while an export runs (plan §7) */
    private fun guardRecording(f: () -> Unit) {
        if (exportRunning) { UI.toast(this, "Locked while exporting"); return }
        f()
    }

    // ================= Advanced sheet (long press / ⋮) =================

    private fun openAdvancedSheet(l: Layer) {
        setSheet(null)
        selectedId = l.id
        refreshQuickBar(); rebuildDock(); stage.refresh()
        panelContent.removeAllViews()

        // header
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(UI.dp(this, 14), UI.dp(this, 10), UI.dp(this, 10), UI.dp(this, 4))
        val hic = android.widget.ImageView(this)
        hic.setImageDrawable(Ic.get(this, Ic.typeIcon(l.type), UI.ACCENT2))
        val hlp = LinearLayout.LayoutParams(UI.dp(this, 20), UI.dp(this, 20))
        hlp.setMargins(0, 0, UI.dp(this, 10), 0)
        hic.layoutParams = hlp
        head.addView(hic)
        val hnm = TextView(this)
        hnm.text = l.name.ifBlank { l.type.name }
        hnm.setTextColor(Color.WHITE)
        hnm.textSize = 14f
        hnm.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        head.addView(hnm, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val ren = UI.chip(this, "Rename")
        ren.setOnClickListener { renameLayer(l) }
        head.addView(ren)
        panelContent.addView(head)

        section("APPEARANCE")
        panelButtonRow(panelContent,
            (if (l.fit == Layer.FIT_FIT) "▦ Fit: whole frame" else "⤢ Fill: crop to box") to {
                ctrl.toggleFit(l.id); openAdvancedSheet(l)
            },
            (if (l.visible) "👁 Hide" else "🚫 Show") to { ctrl.toggleVisible(l.id); openAdvancedSheet(l) })
        panelContent.addView(sliderRow("Opacity", (l.opacity * 100).toInt()) { v ->
            pushUndoLight(); l.opacity = v / 100f; markDirty(); stage.refresh()
        })

        if (l.isVideoLike()) {
            section("PLAYBACK & AUDIO")
            panelButtonRow(panelContent,
                (if (l.playing) "❚❚ Pause source" else "▶ Play source") to {
                    engine.toggleLayerPlay(l); markDirty(); openAdvancedSheet(l)
                },
                (if (l.loop) "🔁 Loop: on" else "🔁 Loop: off") to {
                    ctrl.toggleLoop(l.id); openAdvancedSheet(l)
                })
            panelButtonRow(panelContent,
                (if (l.muted) "🔇 Unmute" else "🔊 Mute") to { ctrl.toggleMuted(l.id); openAdvancedSheet(l) },
                (if (l.solo) "⭐ Solo: on" else "⭐ Solo: off") to { ctrl.toggleSolo(l.id); openAdvancedSheet(l) })
            panelContent.addView(sliderRow("Volume", (l.volume * 100).toInt()) { v ->
                pushUndoLight(); engine.setVolume(l, v / 100f); markDirty()
            })
            val soloNote = UI.label(this,
                "Solo = only soloed sources are heard (nothing else is changed or lost).",
                dim = true, size = 9.5f)
            val snlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            snlp.setMargins(UI.dp(this, 16), 0, UI.dp(this, 14), UI.dp(this, 4))
            soloNote.layoutParams = snlp
            panelContent.addView(soloNote)
        }

        section("ARRANGE")
        panelButtonRow(panelContent,
            "⬆ To front" to { ctrl.moveZ(l.id, "front") },
            "⬇ To back" to { ctrl.moveZ(l.id, "back") })
        panelButtonRow(panelContent,
            "◤" to { ctrl.anchor(l.id, "tl") }, "⬆" to { ctrl.anchor(l.id, "tc") },
            "◥" to { ctrl.anchor(l.id, "tr") })
        panelButtonRow(panelContent,
            "◣" to { ctrl.anchor(l.id, "bl") }, "⬇" to { ctrl.anchor(l.id, "bc") },
            "◢" to { ctrl.anchor(l.id, "br") })
        panelButtonRow(panelContent,
            "◎ Center" to { ctrl.center(l.id) },
            "⧉ Duplicate" to { val nid = ctrl.duplicate(l.id); selectedId = nid; refreshAll() })

        section("DANGER")
        val del = UI.btn(this, "🗑  Delete source", accent = false, small = false)
        del.setTextColor(UI.DANGER)
        val dlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 44))
        dlp.setMargins(UI.dp(this, 12), UI.dp(this, 2), UI.dp(this, 12), UI.dp(this, 14))
        del.layoutParams = dlp
        del.setOnClickListener {
            guardRecording {
                ctrl.delete(l.id); selectedId = null; engine.evict(l.id)
                setSheet(null); refreshAll()
            }
        }
        panelContent.addView(del)

        val sv = sheet.findViewWithTag<ScrollView>("panelScroll")
        sv.visibility = View.VISIBLE
        panelContent.alpha = 0f
        panelContent.translationY = UI.dpf(this, 26f)
        panelContent.animate().alpha(1f).translationY(0f)
            .setDuration(230).setInterpolator(OvershootInterpolator(1.2f)).start()
        // mark no tab highlighted (this is a per-source sheet)
        highlightTab(null)
        sheetTab = "adv"
    }

    // ================= empty state =================

    private fun updateEmptyState() {
        emptyOverlay.removeAllViews()
        if (proj!!.layers.isNotEmpty()) { emptyOverlay.visibility = View.GONE; return }
        emptyOverlay.visibility = View.VISIBLE

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.setPadding(UI.dp(this, 20), UI.dp(this, 20), UI.dp(this, 20), UI.dp(this, 20))
        val g = GradientDrawable()
        g.cornerRadius = UI.dpf(this, 18f)
        g.setColor(Color.argb(180, 8, 10, 15))
        g.setStroke(UI.dp(this, 1), Color.argb(90, 255, 255, 255))
        box.background = g

        val head = UI.label(this, "Set your main canvas", dim = false, size = 17f)
        head.setTextColor(Color.WHITE)
        head.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        head.gravity = Gravity.CENTER
        box.addView(head)
        val sub = UI.label(this,
            "What plays full-screen behind your reaction?\nPick one — extras become PiP sources.",
            dim = true, size = 12f)
        sub.gravity = Gravity.CENTER
        sub.setTextColor(Color.argb(220, 235, 238, 245))
        val slp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        slp.setMargins(0, UI.dp(this, 6), 0, UI.dp(this, 12))
        sub.layoutParams = slp
        box.addView(sub)

        fun big(label: String, icon: Int, fn: () -> Unit) {
            val b = UI.btn(this, label, accent = true)
            b.setCompoundDrawablesRelativeWithIntrinsicBounds(
                Ic.get(this, icon, Color.WHITE), null, null, null)
            b.compoundDrawablePadding = UI.dp(this, 8)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 46))
            lp.setMargins(0, UI.dp(this, 4), 0, UI.dp(this, 4))
            b.layoutParams = lp
            b.setOnClickListener { fn() }
            box.addView(b)
        }
        big("Local video", R.drawable.ic_video) { pickMedia(video = true) }
        big("Record camera", R.drawable.ic_camera) { openCamera() }
        big("Record screen", R.drawable.ic_screen) { startScreenCapture() }
        big("Image", R.drawable.ic_image) { pickMedia(video = false) }

        emptyOverlay.addView(box, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // ================= engine ticks =================

    private fun onTick(ms: Long) {
        if (!this::playBtn.isInitialized) return
        if (!scrubbing) {
            timeLabel.text = UI.fmtTime(ms)
            val max = seek.max
            if (max > 0) seek.progress = (ms.toInt()).coerceAtMost(max)
        }
        // reflect play state on the transport button + quick bar when it changes
        // (also catches a non-looping source auto-pausing on its last frame)
        val sig = playingSignature()
        if (sig != lastPlayingSig) {
            lastPlayingSig = sig
            playBtn.setIcon(if (engine.anyPlaying()) R.drawable.ic_pause else R.drawable.ic_play,
                Color.WHITE)
            // state change (e.g. a non-loop source auto-pausing at its end):
            // refresh all source surfaces so statuses never go stale
            refreshAll()
        }
        if (engine.consumeNewFrames()) stage.refresh()
    }

    /** cheap signature of per-source play states (detects auto-pause at end) */
    private fun playingSignature(): String {
        val sb = StringBuilder()
        for (l in proj!!.layers) if (l.isVideoLike()) sb.append(if (l.playing) '1' else '0')
        return sb.toString()
    }

    private fun syncPreviewTarget() {
        if (!this::stage.isInitialized || !engineReady()) return
        val long = maxOf(stage.canvasW, stage.canvasH)
        engine.targetMaxPx = long.coerceIn(480, 960)
    }

    private fun togglePlay() {
        if (engine.anyPlaying()) { engine.pauseAll(); engine.stopSnapshots() }
        else { engine.playAll(); engine.startSnapshots() }
        refreshAll()
        onTick(engine.master())
    }

    // ================= StageView.Host =================

    override val project: Project get() = proj!!
    override fun selectedId(): String? = selectedId
    override fun select(id: String?) {
        selectedId = id
        refreshQuickBar(); rebuildDock(); stage.refresh()
    }
    override fun bitmapOf(l: Layer): Bitmap? = engine.frameOf(l)
    override fun textOf(l: Layer): String = l.text
    override fun onTransform() { markDirty() }
    override fun onTapEmpty() { select(null) }
    override fun onChanged() { pushUndo() }
    override fun onDoubleTap(l: Layer) {
        // quick action (plan §4.5): double tap = hide / show
        ctrl.toggleVisible(l.id)
    }

    private fun onSourceChanged() {
        // called by SourceController after every command
        stage.refresh()
        refreshAll()
        markDirty()
        engine.refreshFrames()
    }

    private fun refreshAll() {
        refreshQuickBar()
        rebuildDock()
        updateEmptyState()
        updateName()
    }

    private fun markDirty() {
        saveHandler.removeCallbacks(autosave)
        saveHandler.postDelayed(autosave, 600)
    }

    private fun flushSave() {
        val p = proj ?: return
        p.updatedAt = System.currentTimeMillis()
        store.save(p, alsoSnapshot = true)
    }

    private fun pushUndo() { undo.pushSnapshot(layersJsonOf(proj!!)) }

    private var lastUndoPush = 0L
    private fun pushUndoLight() {
        if (System.currentTimeMillis() - lastUndoPush > 350) pushUndo()
        lastUndoPush = System.currentTimeMillis()
    }

    private fun doUndo() {
        val snap = undo.popUndo { layersJsonOf(proj!!) } ?: return
        applyLayersJson(proj!!, snap)
        selectedId = null
        afterStructureChange()
    }

    private fun doRedo() {
        val snap = undo.popRedo { layersJsonOf(proj!!) } ?: return
        applyLayersJson(proj!!, snap)
        selectedId = null
        afterStructureChange()
    }

    private fun afterStructureChange() {
        engine.attach(projectId)
        refreshAll()
        val dur = proj!!.durationMs().toInt().coerceAtLeast(1)
        seek.max = dur
        durationLabel.text = "/ " + UI.fmtTime(dur.toLong())
        stage.refresh()
        engine.refreshFrames()
        markDirty()
    }

    private fun mutateThen(f: () -> Unit) {
        pushUndo()
        f()
        afterStructureChange()
    }

    // ================= sources: pickers / camera / screen =================

    private fun pickMedia(video: Boolean) {
        pendingRole = if (proj!!.layers.isEmpty()) "main" else "pip"
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        if (video) {
            i.type = "video/*"
            i.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "video/*", "video/mp4", "video/avi", "video/x-msvideo",
                "video/webm", "video/x-matroska", "video/3gpp", "video/quicktime",
                "application/x-matroska", "application/avi"))
        } else {
            i.type = "image/*"
        }
        try {
            startActivityForResult(Intent.createChooser(i,
                if (video) "Choose video (MP4 / AVI / WebM / MKV)" else "Choose image"),
                if (video) REQ_PICK_VIDEO else REQ_PICK_IMAGE)
        } catch (e: Exception) { UI.toast(this, "No file picker available") }
    }

    private fun openCamera() {
        pendingCameraRole = if (proj!!.layers.isEmpty()) "main" else "pip"
        val i = Intent(this, CameraActivity::class.java)
        i.putExtra(CameraActivity.EXTRA_PROJECT_ID, projectId)
        i.putExtra(CameraActivity.EXTRA_ROLE, pendingCameraRole)
        startActivityForResult(i, REQ_CAMERA)
    }

    private fun startScreenCapture() {
        pendingRole = if (proj!!.layers.isEmpty()) "main" else "pip"
        val need = ArrayList<String>()
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(android.Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(android.Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), REQ_APP_PERMS)
            return
        }
        launchProjection()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == REQ_APP_PERMS) launchProjection()
    }

    private fun launchProjection() {
        if (ScreenCaptureService.running) { UI.toast(this, "Screen recording already running"); return }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mpm == null) { UI.toast(this, "Screen capture not supported on this device"); return }
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_SCREEN_CAPTURE)
        } catch (e: Exception) {
            UI.toast(this, "Screen capture unavailable: ${e.message}")
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        when (req) {
            REQ_PICK_VIDEO, REQ_PICK_IMAGE -> {
                if (res != Activity.RESULT_OK) return
                val uri = data?.data ?: return
                importFromUri(uri, req == REQ_PICK_VIDEO)
            }
            REQ_CAMERA -> {
                if (res != Activity.RESULT_OK) return
                val rel = data?.getStringExtra(CameraActivity.EXTRA_RESULT_REL) ?: return
                val role = data.getStringExtra(CameraActivity.EXTRA_ROLE) ?: pendingCameraRole
                val clip = File(store.projectDir(projectId), rel)
                if (!clip.exists()) { UI.toast(this, "Camera take missing"); return }
                consumeMediaFile(clip, role, name = "Camera take", type = LayerType.CAMERA)
            }
            REQ_SCREEN_CAPTURE -> {
                if (res != Activity.RESULT_OK || data == null) {
                    UI.toast(this, "Screen recording permission denied")
                    return
                }
                beginScreenService(res, data)
            }
        }
    }

    private fun beginScreenService(resultCode: Int, data: Intent) {
        val svc = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenCaptureService.EXTRA_PROJECT_DIR, store.projectDir(projectId).absolutePath)
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
            recChip.visibility = View.VISIBLE
            setSheet(null)
            UI.toast(this, "Recording screen — tap the top chip to stop")
        } catch (e: Exception) {
            UI.toast(this, "Could not start screen recording: ${e.message}")
        }
    }

    private fun stopScreenCapture() {
        val svc = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        try { startService(svc) } catch (_: Exception) { }
    }

    // ================= import =================

    private fun importFromUri(uri: Uri, isVideo: Boolean) {
        var displayName = "imported"
        try {
            val c: Cursor? = contentResolver.query(uri, null, null, null, null)
            c?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) displayName = it.getString(idx) ?: displayName
                }
            }
        } catch (_: Exception) { }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val tmp = File(cacheDir, "import_" + System.currentTimeMillis() + "_" + safeName)
        val progress = ProgressDialog(this).apply {
            setTitle("Importing media")
            setMessage("Copying $displayName…")
            setCancelable(false)
            show()
        }
        Thread {
            val ok = MediaKit.copyContentToFile(this, uri, tmp)
            runOnUiThread {
                progress.dismiss()
                if (!ok) { UI.toast(this, "Import failed or file unreadable"); return@runOnUiThread }
                val info = MediaKit.probe(tmp.absolutePath)
                if (isVideo && info.width == 0 && info.durMs == 0L) {
                    UI.toast(this, "This video format can't be decoded on this device")
                    tmp.delete()
                    return@runOnUiThread
                }
                val type = if (isVideo) LayerType.VIDEO else LayerType.IMAGE
                consumeMediaFile(tmp, pendingRole, name = displayName, type = type)
            }
        }.start()
    }

    private fun consumeMediaFile(src: File, role: String, name: String, type: LayerType) {
        mutateThen {
            val p = proj!!
            val info = MediaKit.probe(src.absolutePath)
            val inProject = src.parentFile?.absolutePath == store.mediaDir(projectId).absolutePath
            val rel = if (inProject) "media/${src.name}" else store.copyIntoMedia(projectId, src)
            val l: Layer
            if (type == LayerType.IMAGE) {
                val bmp = MediaKit.image(src.absolutePath)
                l = Layer(type = type, name = name, relPath = rel,
                    srcW = bmp?.width ?: info.width, srcH = bmp?.height ?: info.height)
            } else {
                l = Layer(type = type, name = name, relPath = rel, durMs = info.durMs,
                    srcW = info.width, srcH = info.height, srcRotation = info.rotation)
            }
            p.layers.add(l)
            if (role == "main" || p.layers.size == 1) placeMain(l, p) else placePip(l, p)
            selectedId = l.id
        }
        try {
            if (src.parentFile?.absolutePath != store.mediaDir(projectId).absolutePath) src.delete()
        } catch (_: Exception) { }
        setSheet(null)
    }

    /**
     * MAIN CANVAS placement. Camera takes default to FIT (whole frame visible)
     * — that is the fix for "the camera gets cut out": a portrait take on a
     * landscape canvas is letterboxed, never cropped. Everything else keeps
     * full-bleed COVER. (plan §3)
     */
    private fun placeMain(l: Layer, p: Project) {
        LayerFit.fill(l)
        l.fit = if (l.type == LayerType.CAMERA) Layer.FIT_FIT else Layer.FIT_FILL
        p.layers.remove(l)
        p.layers.add(0, l)
    }

    private fun placePip(l: Layer, p: Project) {
        if (l.type == LayerType.TEXT) {
            l.wN = 0.86f; l.hN = 0.28f
            l.cx = 0.5f; l.cy = 0.5f
        } else {
            LayerFit.pip(l, p.aspect.canvasW, p.aspect.canvasH, anchor = "br")
            l.fit = Layer.FIT_FIT
        }
    }

    private fun addText() {
        val input = EditText(this)
        input.hint = "Text"
        input.setTextColor(UI.FG)
        input.setHintTextColor(Color.argb(150, 255, 255, 255))
        AlertDialog.Builder(this)
            .setTitle("Add text source")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val t = input.text.toString()
                mutateThen {
                    val l = Layer(type = LayerType.TEXT, name = "Text")
                    l.text = if (t.isBlank()) "Ahmed Studio" else t
                    l.wN = 0.86f
                    l.hN = 0.28f
                    l.cx = 0.5f; l.cy = 0.5f
                    proj!!.layers.add(l)
                    selectedId = l.id
                }
                setSheet(null)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ================= misc helpers =================

    private fun sliderRow(label: String, value: Int, on: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(UI.dp(this, 14), UI.dp(this, 2), UI.dp(this, 14), UI.dp(this, 2))
        val lb = UI.label(this, label, dim = true, size = 12f)
        row.addView(lb)
        UI.margin(lb, 0, 0, 8, 0, this)
        val sb = SeekBar(this)
        sb.max = 100
        sb.progress = value
        sb.progressTintList = android.content.res.ColorStateList.valueOf(UI.ACCENT)
        sb.thumbTintList = android.content.res.ColorStateList.valueOf(UI.ACCENT2)
        sb.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { if (u) on(v) }
            override fun onStartTrackingTouch(s: SeekBar?) { }
            override fun onStopTrackingTouch(s: SeekBar?) { }
        })
        row.addView(sb)
        return row
    }

    private fun renameLayer(l: Layer) {
        val input = EditText(this)
        input.setText(l.name)
        input.setTextColor(UI.FG)
        input.setHintTextColor(Color.argb(150, 255, 255, 255))
        AlertDialog.Builder(this)
            .setTitle("Rename source")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                ctrl.setName(l.id, input.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editTextLayer(l: Layer) {
        val input = EditText(this)
        input.setText(l.text)
        input.setTextColor(UI.FG)
        input.setHintTextColor(Color.argb(150, 255, 255, 255))
        AlertDialog.Builder(this)
            .setTitle("Text")
            .setView(input)
            .setPositiveButton("OK") { _, _ -> pushUndo(); l.text = input.text.toString(); markDirty(); stage.refresh() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun nextColor(c: Int): Int {
        val list = listOf(
            0xFFFFFFFF.toInt(), 0xFFFF5252.toInt(), 0xFFFFC107.toInt(),
            0xFF69F0AE.toInt(), 0xFF40C4FF.toInt(), 0xFF000000.toInt()
        )
        val i = list.indexOf(c)
        return list[(i + 1) % list.size]
    }

    private fun updateName() {
        val top = (window.decorView as ViewGroup)
        val nameView = findTagged<TextView>(top, "name")
        val meta = findTagged<TextView>(top, "meta")
        nameView?.text = proj?.name
        val n = proj?.layers?.size ?: 0
        meta?.text = "${proj!!.aspect.code} canvas · $n source" + (if (n == 1) "" else "s")
    }

    private fun <T : View> findTagged(root: View, tag: String): T? {
        if (root.tag == tag) {
            @Suppress("UNCHECKED_CAST")
            return root as? T
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val f = findTagged<T>(root.getChildAt(i), tag)
                if (f != null) return f
            }
        }
        return null
    }

    // ================= export =================

    private fun runExport(quality: Int, maxDim: Int, fps: Int, codec: Exporter.Codec) {
        val p = proj!!
        if (exportRunning) { UI.toast(this, "An export is already running"); return }

        val dir = File(getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), "AhmedStudio")
        dir.mkdirs()
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val out = File(dir, "AhmedReaction_${p.name.replace(" ", "_")}_$stamp.${codec.ext}")
        val mime = if (codec.webm) "video/webm" else "video/mp4"

        flushSave()
        exportRunning = true
        exportCancel.set(false)
        exportDialog = ProgressDialog(this).apply {
            setTitle("● REC → ${codec.label}")
            setMessage("Preparing…")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setProgress(0)
            max = 100
            setCancelable(false)
            setButton(ProgressDialog.BUTTON_NEGATIVE, "Cancel") { _, _ -> exportCancel.set(true) }
            show()
        }

        Exporter.export(p, store, Exporter.Options(fps = fps, maxDim = maxDim, quality = quality,
            codec = codec, outFile = out),
            exportCancel,
            { prog, msg -> runOnUiThread { exportDialog?.let { it.progress = prog; it.setMessage(msg) } } },
            { res ->
                runOnUiThread {
                    exportRunning = false
                    exportDialog?.dismiss(); exportDialog = null
                    if (res.ok && res.file != null) {
                        UI.publishToGallery(this, res.file, mime) { uri ->
                            AlertDialog.Builder(this)
                                .setTitle("Export complete")
                                .setMessage("Saved to Gallery / Movies/AhmedReactionStudio\n\n${res.file.absolutePath}\n${UI.niceBytes(res.file.length())}")
                                .setPositiveButton("Share") { _, _ ->
                                    if (uri != null) UI.shareUri(this, uri, mime)
                                    else UI.toast(this, "Saved (find it in Movies/AhmedStudio)")
                                }
                                .setNegativeButton("Close", null)
                                .show()
                        }
                    } else {
                        UI.toast(this, res.message)
                    }
                }
            })
    }
}
