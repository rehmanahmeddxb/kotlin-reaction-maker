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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.rehman.ahmedreactionstudio.camera.CameraActivity
import com.rehman.ahmedreactionstudio.capture.ScreenCaptureService
import com.rehman.ahmedreactionstudio.core.Aspect
import com.rehman.ahmedreactionstudio.core.Layer
import com.rehman.ahmedreactionstudio.core.LayerFit
import com.rehman.ahmedreactionstudio.core.LayerType
import com.rehman.ahmedreactionstudio.core.MediaKit
import com.rehman.ahmedreactionstudio.core.Project
import com.rehman.ahmedreactionstudio.core.ProjectStore
import com.rehman.ahmedreactionstudio.core.UndoStack
import com.rehman.ahmedreactionstudio.core.applyLayersJson
import com.rehman.ahmedreactionstudio.core.layersJsonOf
import com.rehman.ahmedreactionstudio.export.Exporter
import com.rehman.ahmedreactionstudio.ui.DiagnosticsActivity
import com.rehman.ahmedreactionstudio.util.UI
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DSLR-style studio:
 *  - the composition canvas fills the entire screen, edge to edge;
 *  - all controls float OVER the canvas (top bar + a smart bottom dock);
 *  - the dock has four "quick mode" tabs (Add · Layers · Adjust · Export)
 *    that slide up into a smart panel, like a modern camera/editor app;
 *  - an empty project first asks what the MAIN CANVAS is (video, camera,
 *    screen record or image); everything added later becomes a PiP.
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
    private lateinit var playBtn: TextView
    private lateinit var timeLabel: TextView
    private lateinit var durationLabel: TextView
    private lateinit var seek: SeekBar
    private lateinit var layersRow: LinearLayout
    private lateinit var propsCol: LinearLayout
    private lateinit var nameView: TextView
    private lateinit var panelContent: LinearLayout
    private lateinit var sheet: LinearLayout
    private lateinit var tabBtns: HashMap<String, TextView>
    private lateinit var recChip: TextView
    private var sheetOpen = false

    private lateinit var engine: PreviewEngine
    private val undo = UndoStack()
    private val saveHandler = Handler(Looper.getMainLooper())
    private val autosave = Runnable { flushSave() }
    private var dirty = false
    private var scrubbing = false

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

        buildUi()
        rebuildLayersRow()
        refreshProps()
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
        // a take finished while we were backgrounded (service callback missed)
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
        if (sheetOpen) { setSheetOpen(false); return }
        flushSave()
        store.clearOpen(projectId)
        super.onBackPressed()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        stage.post { syncPreviewTarget() }
        stage.refresh()
    }

    // ---------------- UI: fullscreen canvas + floating overlay ----------------

    private fun buildUi() {
        val root = FrameLayout(this)
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

        // empty-state prompt over the canvas ("what is the main canvas?")
        emptyOverlay = LinearLayout(this)
        emptyOverlay.orientation = LinearLayout.VERTICAL
        emptyOverlay.gravity = Gravity.CENTER
        emptyOverlay.setPadding(UI.dp(this, 24), 0, UI.dp(this, 24), 0)
        root.addView(emptyOverlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ===== top overlay bar =====
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(UI.dp(this, 8), UI.dp(this, 10), UI.dp(this, 8), UI.dp(this, 8))
        top.setBackgroundColor(Color.argb(110, 0, 0, 0))
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val back = UI.chip(this, "‹")
        back.setOnClickListener { onBackPressed() }
        top.addView(back)

        val nameCol = LinearLayout(this)
        nameCol.orientation = LinearLayout.VERTICAL
        val nlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        nlp.setMargins(UI.dp(this, 10), 0, UI.dp(this, 6), 0)
        nameCol.layoutParams = nlp
        nameView = TextView(this)
        nameView.setTextColor(UI.FG)
        nameView.textSize = 14f
        nameView.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        nameView.maxLines = 1
        nameCol.addView(nameView)
        val meta = UI.label(this, "", dim = true, size = 10f)
        meta.setTextColor(Color.argb(200, 255, 255, 255))
        nameCol.addView(meta)
        meta.text = "${proj!!.aspect.code} canvas"
        top.addView(nameCol)

        val undoB = UI.chip(this, "↶")
        undoB.setOnClickListener { doUndo() }
        top.addView(undoB)
        val redoB = UI.chip(this, "↷")
        redoB.setOnClickListener { doRedo() }
        top.addView(redoB)
        val diag = UI.chip(this, "⚙")
        diag.setOnClickListener { startActivity(Intent(this, DiagnosticsActivity::class.java)) }
        top.addView(diag)

        // screen-recording in progress chip (tap to stop)
        recChip = UI.chip(this, "● Stop screen-rec")
        recChip.setTextColor(UI.DANGER)
        recChip.visibility = View.GONE
        recChip.setOnClickListener { stopScreenCapture() }
        val rlp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        rlp.topMargin = UI.dp(this, 54)
        root.addView(recChip, rlp)

        // ===== bottom dock =====
        sheet = LinearLayout(this)
        sheet.orientation = LinearLayout.VERTICAL
        sheet.setBackgroundColor(Color.argb(205, 12, 14, 20))
        val slp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        root.addView(sheet, slp)

        // transport row (always visible)
        val transport = LinearLayout(this)
        transport.orientation = LinearLayout.HORIZONTAL
        transport.gravity = Gravity.CENTER_VERTICAL
        transport.setPadding(UI.dp(this, 8), UI.dp(this, 4), UI.dp(this, 8), UI.dp(this, 2))
        sheet.addView(transport)

        playBtn = TextView(this)
        playBtn.text = "▶"
        playBtn.gravity = Gravity.CENTER
        playBtn.setTextColor(Color.WHITE)
        playBtn.textSize = 16f
        val pg = GradientDrawable()
        pg.cornerRadius = UI.dpf(this, 20f)
        pg.setColor(UI.ACCENT)
        playBtn.background = pg
        playBtn.layoutParams = LinearLayout.LayoutParams(UI.dp(this, 40), UI.dp(this, 40))
        playBtn.setOnClickListener { togglePlay() }
        transport.addView(playBtn)

        timeLabel = UI.label(this, "0:00", dim = false, size = 12f)
        timeLabel.setTextColor(Color.WHITE)
        UI.margin(timeLabel, 8, 0, 8, 0, this)
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
            override fun onStopTrackingTouch(sb: SeekBar?) { scrubbing = false }
        })
        transport.addView(seek)

        durationLabel = UI.label(this, "/ " + UI.fmtTime(proj!!.durationMs()), dim = true, size = 12f)
        durationLabel.setTextColor(Color.argb(200, 255, 255, 255))
        UI.margin(durationLabel, 8, 0, 4, 0, this)
        transport.addView(durationLabel)

        // expandable smart panel
        panelContent = LinearLayout(this)
        panelContent.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        val maxH = (resources.displayMetrics.heightPixels * 0.62f).toInt().coerceAtLeast(UI.dp(this, 230))
        scroll.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxH)
        scroll.visibility = View.GONE
        scroll.addView(panelContent, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sheet.addView(scroll)

        // tab bar
        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabs.setPadding(UI.dp(this, 4), UI.dp(this, 6), UI.dp(this, 4), UI.dp(this, 10))
        sheet.addView(tabs)
        tabBtns = HashMap()
        for ((key, label) in listOf("add" to "＋ Add", "layers" to "▤ Layers",
            "adjust" to "◳ Adjust", "export" to "⇪ Export")) {
            val t = TextView(this)
            t.text = label
            t.gravity = Gravity.CENTER
            t.setTextColor(UI.FG)
            t.textSize = 12.5f
            t.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(0, UI.dp(this, 42), 1f)
            lp.setMargins(UI.dp(this, 4), 0, UI.dp(this, 4), 0)
            t.layoutParams = lp
            val g = GradientDrawable()
            g.cornerRadius = UI.dpf(this, 12f)
            g.setColor(UI.BG3)
            t.background = g
            t.setOnClickListener { onTab(key) }
            tabs.addView(t)
            tabBtns[key] = t
        }

        setContentView(root)
        stage.post { syncPreviewTarget() }
    }

    private fun onTab(key: String) {
        if (key == "add") {
            // tapping Add while open = collapse (quick toggle); otherwise open panel
            if (sheetOpen) setSheetOpen(false)
            else { setSheetOpen(true); buildAddPanel(); highlightTab("add") }
        } else {
            setSheetOpen(true)
            when (key) {
                "layers" -> { buildLayersPanel(); highlightTab("layers") }
                "adjust" -> { buildAdjustPanel(); highlightTab("adjust") }
                "export" -> { buildExportPanel(); highlightTab("export") }
            }
        }
    }

    private fun highlightTab(active: String?) {
        for ((k, v) in tabBtns) {
            val g = v.background as GradientDrawable
            if (k == active) {
                g.setColor(UI.ACCENT)
                g.setStroke(UI.dp(this, 1), Color.argb(160, 255, 220, 180))
                v.setTextColor(Color.WHITE)
            } else {
                g.setColor(UI.BG3)
                g.setStroke(UI.dp(this, 1), Color.argb(60, 255, 255, 255))
                v.setTextColor(UI.FG)
            }
        }
    }

    private fun setSheetOpen(open: Boolean) {
        sheetOpen = open
        val sv = (panelContent.parent as ScrollView)
        sv.visibility = if (open) View.VISIBLE else View.GONE
        if (!open) highlightTab(null)
    }

    // ---------------- smart panel: ADD ----------------

    private fun addPanelSection(container: LinearLayout, title: String) {
        val t = UI.label(this, title, dim = true, size = 11f)
        t.setTextColor(UI.ACCENT2)
        t.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(UI.dp(this, 12), UI.dp(this, 10), UI.dp(this, 12), UI.dp(this, 4))
        t.layoutParams = lp
        container.addView(t)
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
        panelContent.removeAllViews()
        val hasMain = proj!!.layers.isNotEmpty()

        if (!hasMain) {
            val t = UI.label(this,
                "First pick what fills the MAIN CANVAS (the background).",
                dim = false, size = 13f)
            t.gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(UI.dp(this, 16), UI.dp(this, 12), UI.dp(this, 16), UI.dp(this, 2))
            t.layoutParams = lp
            panelContent.addView(t)
            addPanelSection(panelContent, "MAIN CANVAS SOURCE")
        } else {
            addPanelSection(panelContent, "ADD PIP / OVERLAY LAYER")
        }

        panelButtonRow(panelContent,
            "🎬 Video file" to { pickMedia(video = true) },
            "🖼 Image file" to { pickMedia(video = false) })
        panelButtonRow(panelContent,
            "🎥 Record camera" to { openCamera() },
            "⛺ Record screen" to { startScreenCapture() })

        addPanelSection(panelContent, "OVERLAYS")
        panelButtonRow(panelContent,
            "💬 Text" to { addText() })

        val note = UI.label(this,
            "Accepted video files: MP4, AVI, WebM, MKV, 3GP… (anything this device can decode).",
            dim = true, size = 10.5f)
        val nlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        nlp.setMargins(UI.dp(this, 14), UI.dp(this, 8), UI.dp(this, 14), UI.dp(this, 10))
        note.layoutParams = nlp
        panelContent.addView(note)
    }

    // ---------------- smart panel: LAYERS ----------------

    private fun buildLayersPanel() {
        panelContent.removeAllViews()
        addPanelSection(panelContent, "LAYERS (tap to select · long-press to rename)")
        val wrap = android.widget.HorizontalScrollView(this)
        wrap.isHorizontalScrollBarEnabled = false
        layersRow = LinearLayout(this)
        layersRow.orientation = LinearLayout.HORIZONTAL
        layersRow.setPadding(UI.dp(this, 10), UI.dp(this, 4), UI.dp(this, 10), UI.dp(this, 6))
        wrap.addView(layersRow)
        panelContent.addView(wrap)
        rebuildLayersRow()

        addPanelSection(panelContent, "SELECTED LAYER")
        propsCol = LinearLayout(this)
        propsCol.orientation = LinearLayout.VERTICAL
        propsCol.setPadding(UI.dp(this, 10), 0, UI.dp(this, 10), UI.dp(this, 10))
        panelContent.addView(propsCol)
        refreshProps()
    }

    // ---------------- smart panel: ADJUST ----------------

    private fun buildAdjustPanel() {
        panelContent.removeAllViews()
        val p = proj!!

        addPanelSection(panelContent, "CANVAS RATIO (changes screen orientation)")
        panelButtonRow(panelContent,
            "16:9" to { changeAspect(Aspect.R169) },
            "9:16" to { changeAspect(Aspect.R916) },
            "1:1" to { changeAspect(Aspect.R11) })

        addPanelSection(panelContent, "CANVAS BACKGROUND")
        panelButtonRow(panelContent,
            "Dark" to { setBg(Color.rgb(16, 20, 24)) },
            "White" to { setBg(Color.rgb(255, 255, 255)) },
            "Orange" to { setBg(Color.rgb(255, 90, 44)) })
        panelButtonRow(panelContent,
            "Navy" to { setBg(Color.rgb(30, 60, 120)) },
            "Green" to { setBg(Color.rgb(20, 120, 90)) },
            "Purple" to { setBg(Color.rgb(120, 30, 90)) })

        addPanelSection(panelContent, "SELECTED LAYER FIT")
        panelButtonRow(panelContent,
            "⤢ Fill canvas" to { withSel { presetFill(it) } },
            "▦ Contain" to { withSel { presetContain(it) } },
            "▢ PiP" to { withSel { presetPip(it) } })
        addPanelSection(panelContent, "OVERLAY POSITION (always kept on canvas)")
        panelButtonRow(panelContent,
            "◤ Top-left" to { withSel { presetAnchor(it, "tl") } },
            "◉ Center" to { withSel { presetAnchor(it, "c") } },
            "◥ Top-right" to { withSel { presetAnchor(it, "tr") } })
        panelButtonRow(panelContent,
            "◣ Bottom-left" to { withSel { presetAnchor(it, "bl") } },
            "⬇ Bottom-center" to { withSel { presetAnchor(it, "bc") } },
            "◢ Bottom-right" to { withSel { presetAnchor(it, "br") } })

        if (p.layers.isNotEmpty()) {
            addPanelSection(panelContent, "MAIN CANVAS LAYER")
            panelButtonRow(panelContent,
                "Set selected as main canvas" to { makeSelectedMain() })
        }
    }

    private fun setBg(c: Int) {
        pushUndo()
        proj!!.bgColor = c
        markDirty(); stage.refresh()
    }

    private fun changeAspect(a: Aspect) {
        val p = proj!!
        if (p.aspect == a) return
        p.aspect = a
        applyOrientationFor(a)
        markDirty()
        stage.post { syncPreviewTarget() }
        stage.refresh()
        UI.toast(this, "Canvas set to ${a.code} — every layer keeps its own frame ratio")
    }

    // ---------------- smart panel: EXPORT ----------------

    private fun buildExportPanel() {
        panelContent.removeAllViews()
        val p = proj!!
        addPanelSection(panelContent, "EXPORT VIDEO")
        if (p.layers.isEmpty()) {
            val t = UI.label(this, "Nothing to export yet — add a main canvas first.",
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
            "Containers: H.264/H.265 → MP4 · VP8/VP9 → WebM.\n" +
            "AVI can be imported but Android offers no AVI encoder for export.\n" +
            "Duration ${UI.fmtTime(p.durationMs())} · ${p.layers.size} layers",
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
            setSheetOpen(false)
            runExport(quality, maxDim, fps, avail[codecIdx])
        }
        panelContent.addView(go)
    }

    // ---------------- empty state ----------------

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
        g.setColor(Color.argb(170, 8, 10, 15))
        g.setStroke(UI.dp(this, 1), Color.argb(90, 255, 255, 255))
        box.background = g

        val head = UI.label(this, "Set your main canvas", dim = false, size = 17f)
        head.setTextColor(Color.WHITE)
        head.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        head.gravity = Gravity.CENTER
        box.addView(head)
        val sub = UI.label(this,
            "What plays full-screen behind your reaction?\nPick one — it fills the canvas; extras become PiP.",
            dim = true, size = 12f)
        sub.gravity = Gravity.CENTER
        sub.setTextColor(Color.argb(220, 235, 238, 245))
        val slp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        slp.setMargins(0, UI.dp(this, 6), 0, UI.dp(this, 12))
        sub.layoutParams = slp
        box.addView(sub)

        fun big(label: String, fn: () -> Unit) {
            val b = UI.btn(this, label, accent = true)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 44))
            lp.setMargins(0, UI.dp(this, 4), 0, UI.dp(this, 4))
            b.layoutParams = lp
            b.setOnClickListener { fn() }
            box.addView(b)
        }
        big("🎬  Local video") { pickMedia(video = true) }
        big("🎥  Record camera") { openCamera() }
        big("⛺  Record screen") { startScreenCapture() }
        big("🖼  Image") { pickMedia(video = false) }

        emptyOverlay.addView(box, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // ---------------- engine ticks ----------------

    private fun onTick(ms: Long) {
        if (!this::playBtn.isInitialized) return
        if (!scrubbing) {
            timeLabel.text = UI.fmtTime(ms)
            val max = seek.max
            if (max > 0) seek.progress = (ms.toInt()).coerceAtMost(max)
        }
        playBtn.text = if (engine.anyPlaying()) "❚❚" else "▶"
        // Repaint the composition only when a decoded frame actually landed.
        // Redrawing the full canvas 30x/s while the decoder is behind burns the
        // UI thread that the transport and the drag gestures need.
        if (engine.consumeNewFrames()) stage.refresh()
    }

    /** decode preview frames at (roughly) the size they are drawn at */
    private fun syncPreviewTarget() {
        if (!this::stage.isInitialized || !engineReady()) return
        val long = maxOf(stage.canvasW, stage.canvasH)
        engine.targetMaxPx = long.coerceIn(480, 960)
    }

    private fun togglePlay() {
        if (engine.anyPlaying()) { engine.pauseAll(); engine.stopSnapshots() }
        else { engine.playAll(); engine.startSnapshots() }
        refreshProps()
        onTick(engine.master())
    }

    // ---------------- StageView.Host ----------------

    override val project: Project get() = proj!!
    override fun selectedId(): String? = selectedId
    override fun select(id: String?) { selectedId = id; refreshProps(); stage.refresh() }
    override fun bitmapOf(l: Layer): Bitmap? = engine.frameOf(l)
    override fun textOf(l: Layer): String = l.text
    override fun onTransform() { markDirty() }
    override fun onTapEmpty() { select(null) }
    override fun onChanged() { pushUndo() }

    private fun withSel(f: (Layer) -> Unit) {
        val id = selectedId ?: run { UI.toast(this, "Select a layer first"); return }
        val l = proj!!.layerById(id) ?: return
        f(l)
        stage.refresh()
    }

    private fun markDirty() {
        dirty = true
        saveHandler.removeCallbacks(autosave)
        saveHandler.postDelayed(autosave, 600)
    }

    private fun flushSave() {
        val p = proj ?: return
        p.updatedAt = System.currentTimeMillis()
        store.save(p, alsoSnapshot = true)
        dirty = false
    }

    private fun pushUndo() { undo.pushSnapshot(layersJsonOf(proj!!)) }

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
        rebuildLayersRow()
        refreshProps()
        val dur = proj!!.durationMs().toInt().coerceAtLeast(1)
        seek.max = dur
        durationLabel.text = "/ " + UI.fmtTime(dur.toLong())
        stage.refresh()
        engine.refreshFrames()
        updateEmptyState()
        markDirty()
    }

    private fun mutateThen(f: () -> Unit) {
        pushUndo()
        f()
        afterStructureChange()
    }

    // ---------------- sources: pickers / camera / screen ----------------

    /** open a system picker that accepts every video format (mp4/avi/webm/mkv…) */
    private fun pickMedia(video: Boolean) {
        pendingRole = if (proj!!.layers.isEmpty()) "main" else "pip"
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        if (video) {
            i.type = "video/*"
            // broaden beyond the device's advertised types
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

    // ----- screen recording -----

    private fun startScreenCapture() {
        pendingRole = if (proj!!.layers.isEmpty()) "main" else "pip"
        // ask for mic + notifications first (screen projection itself prompts next)
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
        if (code == REQ_APP_PERMS) launchProjection() // mic is optional; continue regardless
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
            setSheetOpen(false)
            UI.toast(this, "Recording screen — tap the top bar chip to stop")
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

    // ---------------- import ----------------

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

    /**
     * Central import: a file (already in the project media dir, or a temp copy)
     * becomes either the full-screen MAIN CANVAS (role=main) or a PiP (role=pip).
     */
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
        setSheetOpen(false)
    }

    /**
     * MAIN CANVAS: the layer box becomes the canvas itself and the compositor
     * cover-crops the frame into it. (The old code made the box *bigger* than
     * the canvas to fake a cover — same picture, but the selection frame and
     * every resize handle ended up off screen.)
     */
    private fun fillCanvas(l: Layer, p: Project) { LayerFit.fill(l) }

    /** contain-fit the layer box onto the canvas without distortion */
    private fun fitNormalized(l: Layer, p: Project) {
        LayerFit.contain(l, p.aspect.canvasW, p.aspect.canvasH)
    }

    /** place a fresh layer as the background: full bleed, bottom of the z-order */
    private fun placeMain(l: Layer, p: Project) {
        LayerFit.fill(l)
        p.layers.remove(l)
        p.layers.add(0, l)
    }

    /**
     * Place a fresh overlay: its OWN aspect ratio (never squashed), sized into
     * the reaction-cam area and pinned inside the canvas bottom-right.
     */
    private fun placePip(l: Layer, p: Project) {
        if (l.type == LayerType.TEXT) {
            l.wN = 0.86f; l.hN = 0.28f
            l.cx = 0.5f; l.cy = 0.5f
        } else {
            LayerFit.pip(l, p.aspect.canvasW, p.aspect.canvasH, anchor = "br")
        }
    }

    private fun presetFill(l: Layer) {
        pushUndo(); fillCanvas(l, proj!!); markDirty(); stage.refresh()
    }

    private fun presetContain(l: Layer) {
        pushUndo(); fitNormalized(l, proj!!); markDirty(); stage.refresh()
    }

    /** snap the selected overlay to a canvas anchor, always fully on canvas */
    private fun presetAnchor(l: Layer, anchor: String) {
        pushUndo(); LayerFit.anchorTo(l, anchor); markDirty(); stage.refresh()
    }

    private fun presetPip(l: Layer) {
        pushUndo()
        LayerFit.pip(l, proj!!.aspect.canvasW, proj!!.aspect.canvasH, anchor = "br")
        markDirty(); stage.refresh()
    }

    /** promote a layer to be the background canvas (full screen, sent to back) */
    private fun makeSelectedMain() {
        val id = selectedId ?: return
        mutateThen {
            val p = proj!!
            val l = p.layerById(id) ?: return@mutateThen
            fillCanvas(l, p)
            p.layers.remove(l)
            p.layers.add(0, l)   // bottom of z-order = background
        }
    }

    private fun addText() {
        val input = EditText(this)
        input.hint = "Text"
        input.setTextColor(UI.FG)
        input.setHintTextColor(Color.argb(150, 255, 255, 255))
        AlertDialog.Builder(this)
            .setTitle("Add text layer")
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
                setSheetOpen(false)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------- layers row & properties ----------------

    private fun rebuildLayersRow() {
        if (!this::layersRow.isInitialized) return
        layersRow.removeAllViews()
        val p = proj!!
        if (p.layers.isEmpty()) {
            val t = UI.label(this, "No layers yet — use the Add tab.", dim = true, size = 12f)
            layersRow.addView(t)
            return
        }
        // z order topmost first (right side of list = on top)
        for (i in p.layers.indices.reversed()) {
            val l = p.layers[i]
            val card = LinearLayout(this)
            card.orientation = LinearLayout.VERTICAL
            card.gravity = Gravity.CENTER
            card.setPadding(UI.dp(this, 10), UI.dp(this, 6), UI.dp(this, 10), UI.dp(this, 6))
            val g = GradientDrawable()
            g.cornerRadius = UI.dpf(this, 10f)
            val selected = l.id == selectedId
            g.setColor(if (selected) Color.argb(90, 255, 90, 44) else UI.BG3)
            g.setStroke(UI.dp(this, 1),
                if (selected) Color.argb(255, 255, 140, 90) else Color.argb(60, 255, 255, 255))
            card.background = g
            val lp = LinearLayout.LayoutParams(UI.dp(this, 74), UI.dp(this, 56))
            lp.rightMargin = UI.dp(this, 6)
            card.layoutParams = lp

            val ic = TextView(this)
            ic.text = when (l.type) {
                LayerType.VIDEO -> "🎬"
                LayerType.CAMERA -> "🎥"
                LayerType.SCREEN -> "⛺"
                LayerType.IMAGE -> "🖼"
                LayerType.TEXT -> "💬"
            }
            ic.textSize = 15f
            card.addView(ic)
            val nm = TextView(this)
            nm.text = l.name.take(10)
            nm.setTextColor(if (l.visible) UI.FG else UI.FG2)
            nm.textSize = 9.5f
            nm.maxLines = 1
            nm.alpha = if (l.visible) 1f else 0.5f
            card.addView(nm)
            if (i == 0) {
                val main = UI.label(this, "MAIN", dim = false, size = 7.5f)
                main.setTextColor(UI.ACCENT2)
                card.addView(main)
            }
            if (l.locked) {
                val lk = TextView(this); lk.text = "🔒"; lk.textSize = 8f; card.addView(lk)
            }
            card.setOnClickListener {
                select(if (selectedId == l.id) null else l.id)
                rebuildLayersRow()
            }
            card.setOnLongClickListener { renameLayer(l); true }
            layersRow.addView(card)
        }
    }

    private fun refreshProps() {
        if (!this::propsCol.isInitialized) return
        propsCol.removeAllViews()
        val p = proj!!
        val l = selectedId?.let { p.layerById(it) }
        if (l == null) {
            val t = UI.label(this,
                if (p.layers.isEmpty()) "No layer selected."
                else "Tap a layer chip or on the canvas to select it.",
                dim = true, size = 12f)
            propsCol.addView(t)
            return
        }
        val row0 = LinearLayout(this)
        row0.orientation = LinearLayout.HORIZONTAL
        row0.gravity = Gravity.CENTER_VERTICAL
        val nm = TextView(this)
        nm.text = l.name
        nm.setTextColor(UI.FG)
        nm.textSize = 14f
        nm.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        row0.addView(nm, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val rename = UI.chip(this, "Rename")
        rename.setOnClickListener { renameLayer(l) }
        row0.addView(rename)
        propsCol.addView(row0)

        val ops = LinearLayout(this)
        ops.orientation = LinearLayout.HORIZONTAL
        ops.gravity = Gravity.CENTER_VERTICAL
        fun op(label: String, fn: () -> Unit): TextView {
            val c = UI.chip(this, label)
            c.setOnClickListener { fn() }
            ops.addView(c)
            UI.margin(c, 0, 0, 5, 0, this)
            return c
        }
        op(if (l.visible) "👁" else "🙈") { mutateThen { l.visible = !l.visible } }
        op(if (l.locked) "🔓 Unlock" else "🔒 Lock") { mutateThen { l.locked = !l.locked } }
        if (l.isVideoLike()) {
            op(if (l.playing) "❚❚ Layer" else "▶ Layer") {
                engine.toggleLayerPlay(l); refreshProps(); markDirty()
            }
            op(if (l.muted) "🔇 Muted" else "🔊 Sound") { mutateThen { l.muted = !l.muted } }
        }
        if (l.type == LayerType.TEXT) {
            op("✎ Text") { editTextLayer(l) }
            op("Color") { mutateThen { l.textColor = nextColor(l.textColor) } }
        }
        op("⧉") { duplicateLayer(l) }
        op("🗑") { deleteLayer(l) }
        propsCol.addView(ops)

        propsCol.addView(sliderRow("Opacity", (l.opacity * 100).toInt()) { v ->
            pushUndoLight(); l.opacity = v / 100f; markDirty(); stage.refresh()
        })

        if (l.isVideoLike() && !l.muted) {
            propsCol.addView(sliderRow("Volume", (l.volume * 100).toInt()) { v ->
                pushUndoLight(); engine.setVolume(l, v / 100f); markDirty()
            })
        }

        if (l.type == LayerType.TEXT) {
            val sizeRow = LinearLayout(this)
            sizeRow.orientation = LinearLayout.HORIZONTAL
            sizeRow.gravity = Gravity.CENTER_VERTICAL
            val minus = UI.chip(this, "A-")
            minus.setOnClickListener { pushUndoLight(); l.fontSizeN = (l.fontSizeN * 0.85f).coerceAtLeast(0.02f); markDirty(); stage.refresh() }
            sizeRow.addView(minus)
            val sizeT = UI.label(this, "Text size", dim = true, size = 12f)
            sizeT.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            sizeT.gravity = Gravity.CENTER
            sizeRow.addView(sizeT)
            val plus = UI.chip(this, "A+")
            plus.setOnClickListener { pushUndoLight(); l.fontSizeN = (l.fontSizeN * 1.18f).coerceAtMost(0.4f); markDirty(); stage.refresh() }
            sizeRow.addView(plus)
            propsCol.addView(sizeRow)
        }

        val zRow = LinearLayout(this)
        zRow.orientation = LinearLayout.HORIZONTAL
        zRow.gravity = Gravity.CENTER_VERTICAL
        val zt = UI.label(this, "Order", dim = true, size = 12f)
        zRow.addView(zt)
        UI.margin(zt, 0, 0, 8, 0, this)
        val front = UI.chip(this, "⬆ To front")
        front.setOnClickListener { mutateThen { moveTo(l, proj!!.layers.size - 1) } }
        zRow.addView(front)
        val back = UI.chip(this, "⬇ To back")
        UI.margin(back, 5, 0, 0, 0, this)
        back.setOnClickListener { mutateThen { moveTo(l, 0) } }
        zRow.addView(back)
        propsCol.addView(zRow)
    }

    private fun pushUndoLight() {
        if (System.currentTimeMillis() - lastUndoPush > 350) pushUndo()
        lastUndoPush = System.currentTimeMillis()
    }

    private var lastUndoPush = 0L

    private fun sliderRow(label: String, value: Int, on: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
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

    private fun moveTo(l: Layer, target: Int) {
        val list = proj!!.layers
        val idx = list.indexOf(l)
        if (idx < 0) return
        list.removeAt(idx)
        list.add(target.coerceIn(0, list.size), l)
    }

    private fun duplicateLayer(l: Layer) {
        mutateThen {
            val copy = l.clone()
            copy.id = java.util.UUID.randomUUID().toString()
            copy.name = l.name + " copy"
            copy.cx = ((l.cx + 0.06f) % 1f).coerceIn(0f, 1f)
            copy.cy = ((l.cy + 0.06f) % 1f).coerceIn(0f, 1f)
            val p = proj!!
            val idx = p.layers.indexOf(l)
            p.layers.add(idx + 1, copy)
            if (copy.isVideoLike()) {
                copy.pausedMediaMs = l.pausedMediaMs
                copy.playing = l.playing
            }
            selectedId = copy.id
        }
    }

    private fun deleteLayer(l: Layer) {
        mutateThen {
            proj!!.layers.remove(l)
            selectedId = null
        }
        engine.evict(l.id)
    }

    private fun renameLayer(l: Layer) {
        val input = EditText(this)
        input.setText(l.name)
        input.setTextColor(UI.FG)
        input.setHintTextColor(Color.argb(150, 255, 255, 255))
        AlertDialog.Builder(this)
            .setTitle("Rename layer")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                pushUndo(); l.name = input.text.toString(); rebuildLayersRow(); markDirty()
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

    private fun updateName() { nameView.text = proj?.name }

    // ---------------- export ----------------

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
            setTitle("Exporting ${codec.label}")
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
