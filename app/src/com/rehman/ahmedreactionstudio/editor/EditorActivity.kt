package com.rehman.ahmedreactionstudio.editor

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.rehman.ahmedreactionstudio.camera.CameraActivity
import com.rehman.ahmedreactionstudio.core.Aspect
import com.rehman.ahmedreactionstudio.core.Layer
import com.rehman.ahmedreactionstudio.core.LayerPresets
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

class EditorActivity : Activity(), StageView.Host {

    companion object {
        const val EXTRA_PROJECT_ID = "pid"
        const val EXTRA_PROJECT_NAME = "pname"
        const val EXTRA_PROJECT_ASPECT = "paspect"
        const val REQ_PICK_VIDEO = 41
        const val REQ_PICK_IMAGE = 42
        const val REQ_CAMERA = 43
    }

    private lateinit var store: ProjectStore
    private var proj: Project? = null
    private var projectId: String = ""
    private var selectedId: String? = null

    private lateinit var stage: StageView
    private lateinit var stageFrame: FrameLayout
    private lateinit var playBtn: TextView
    private lateinit var timeLabel: TextView
    private lateinit var durationLabel: TextView
    private lateinit var seek: SeekBar
    private lateinit var layersRow: LinearLayout
    private lateinit var propsCol: LinearLayout
    private lateinit var nameView: TextView

    private lateinit var engine: PreviewEngine
    private val undo = UndoStack()
    private val saveHandler = Handler(Looper.getMainLooper())
    private val autosave = Runnable { flushSave() }
    private var dirty = false
    private var scrubbing = false
    private var restoring = false

    private var exportDialog: ProgressDialog? = null
    private val exportCancel = AtomicBoolean(false)
    private var exportRunning = false

    private val importKind = arrayOf("", "video", "image")

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

        when (p.aspect) {
            Aspect.R169 -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            Aspect.R916 -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            Aspect.R11 -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        // engine is created before any view can draw so the very first frame is safe
        engine = PreviewEngine(this, { this.proj!! }, store) { ms -> onTick(ms) }
        engine.attach(projectId)

        buildUi()
        rebuildLayersRow()
        refreshProps()
        updateName()
        engine.refreshFrames()
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putString("pid", projectId)
        out.putString("sel", selectedId)
        super.onSaveInstanceState(out)
    }

    private fun engineReady(): Boolean = this::engine.isInitialized

    override fun onResume() {
        super.onResume()
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
        store.clearOpen(projectId)
        super.onDestroy()
    }

    override fun onBackPressed() {
        flushSave()
        store.clearOpen(projectId)
        super.onBackPressed()
    }

    // ---------------- UI building ----------------

    private fun buildUi() {
        val p = proj!!
        val root = FrameLayout(this)
        root.setBackgroundColor(UI.BG)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT))

        // header
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.setPadding(UI.dp(this, 10), UI.dp(this, 8), UI.dp(this, 10), UI.dp(this, 8))
        header.setBackgroundColor(UI.BG2)
        col.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))

        val back = UI.chip(this, "\u2039")
        back.setOnClickListener { finish() }
        header.addView(back)

        val nameCol = LinearLayout(this)
        nameCol.orientation = LinearLayout.VERTICAL
        nameCol.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        nameView = TextView(this)
        nameView.setTextColor(UI.FG)
        nameView.textSize = 15f
        nameView.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        nameView.maxLines = 1
        nameCol.addView(nameView)
        val meta = UI.label(this, "${p.aspect.code} \u00b7 normalized canvas ${p.aspect.canvasW}\u00d7${p.aspect.canvasH}", dim = true, size = 10f)
        nameCol.addView(meta)
        header.addView(nameCol)

        val undoB = UI.chip(this, "\u21B6")
        undoB.setOnClickListener { doUndo() }
        header.addView(undoB)
        val redoB = UI.chip(this, "\u21B7")
        redoB.setOnClickListener { doRedo() }
        header.addView(redoB)
        val diag = UI.chip(this, "\u2699")
        diag.setOnClickListener { startActivity(Intent(this, DiagnosticsActivity::class.java)) }
        header.addView(diag)

        // stage
        stageFrame = FrameLayout(this)
        stageFrame.setBackgroundColor(Color.rgb(6, 7, 9))
        col.addView(stageFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        stage = StageView(this)
        stage.host = this
        stageFrame.addView(stage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))

        // bottom panel (transport / tools / layers / props) - bounded, scrollable
        val scroll = android.widget.ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        val maxH = (resources.displayMetrics.heightPixels * 0.52f).toInt().coerceAtLeast(UI.dp(this, 210))
        scroll.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxH)
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.VERTICAL
        bottom.setBackgroundColor(UI.BG)
        scroll.addView(bottom, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(scroll)

        // transport
        val transport = LinearLayout(this)
        transport.orientation = LinearLayout.HORIZONTAL
        transport.gravity = Gravity.CENTER_VERTICAL
        transport.setPadding(UI.dp(this, 8), 0, UI.dp(this, 8), 0)
        bottom.addView(transport)

        playBtn = TextView(this)
        playBtn.text = "\u25B6"
        playBtn.gravity = Gravity.CENTER
        playBtn.setTextColor(Color.WHITE)
        playBtn.textSize = 17f
        val pg = GradientDrawable()
        pg.cornerRadius = UI.dpf(this, 20f)
        pg.setColor(UI.ACCENT)
        playBtn.background = pg
        playBtn.layoutParams = LinearLayout.LayoutParams(UI.dp(this, 40), UI.dp(this, 40))
        playBtn.setOnClickListener { togglePlay() }
        transport.addView(playBtn)

        timeLabel = UI.label(this, "0:00", dim = false, size = 13f)
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

        durationLabel = UI.label(this, "/ " + UI.fmtTime(proj!!.durationMs()), dim = true, size = 13f)
        UI.margin(durationLabel, 8, 0, 4, 0, this)
        transport.addView(durationLabel)

        // tool chips row (scrollable)
        val tools = HorizontalScrollView(this)
        tools.isHorizontalScrollBarEnabled = false
        val toolsRow = LinearLayout(this)
        toolsRow.orientation = LinearLayout.HORIZONTAL
        toolsRow.setPadding(UI.dp(this, 8), UI.dp(this, 4), UI.dp(this, 8), 0)
        tools.addView(toolsRow)
        bottom.addView(tools)

        fun tool(label: String, onClick: (View) -> Unit): TextView {
            val c = UI.chip(this, label)
            c.setOnClickListener(onClick)
            toolsRow.addView(c)
            UI.margin(c, 0, 0, 6, 0, this)
            return c
        }
        tool("\uD83C\uDFA5 Video") { pick(REQ_PICK_VIDEO) }
        tool("\uD83D\uDDBC Image") { pick(REQ_PICK_IMAGE) }
        tool("\uD83C\uDFAC Camera") { openCamera() }
        tool("\uD83D\uDD8B Text") { addText() }
        tool("Fill") { withSel { presetFit(it) } }
        tool("Center") { withSel { presetCenter(it) } }
        tool("PIP") { withSel { presetPip(it) } }
        tool("Undo") { doUndo() }
        tool("Redo") { doRedo() }
        tool("BG") { chooseBg() }
        tool("Export") { showExportDialog() }

        // layers row
        val layersWrap = HorizontalScrollView(this)
        layersWrap.isHorizontalScrollBarEnabled = false
        layersRow = LinearLayout(this)
        layersRow.orientation = LinearLayout.HORIZONTAL
        layersRow.setPadding(UI.dp(this, 8), UI.dp(this, 6), UI.dp(this, 8), UI.dp(this, 2))
        layersWrap.addView(layersRow)
        bottom.addView(layersWrap)

        // properties
        propsCol = LinearLayout(this)
        propsCol.orientation = LinearLayout.VERTICAL
        propsCol.setPadding(UI.dp(this, 10), UI.dp(this, 2), UI.dp(this, 10), UI.dp(this, 10))
        bottom.addView(propsCol)

        setContentView(root)
    }

    private fun updateName() {
        nameView.text = proj?.name
    }

    // ---------------- engine ticks ----------------

    private fun onTick(ms: Long) {
        if (!this::playBtn.isInitialized) return
        if (!scrubbing) {
            timeLabel.text = UI.fmtTime(ms)
            val max = seek.max
            if (max > 0) seek.progress = (ms.toInt()).coerceAtMost(max)
        }
        playBtn.text = if (engine.anyPlaying()) "\u275A\u275A" else "\u25B6"
        stage.refresh()
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
        val id = selectedId ?: return
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

    private fun pushUndo() {
        undo.pushSnapshot(layersJsonOf(proj!!))
    }

    private fun snapshotAll() {
        proj?.let { store.snapshot(it) }
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
        engine.attach(projectId)   // rebuild clocks
        rebuildLayersRow()
        refreshProps()
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

    // ---------------- layer ops ----------------

    private fun pick(req: Int) {
        val kind = if (req == REQ_PICK_VIDEO) "video/*" else "image/*"
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = kind
        try { startActivityForResult(i, req) } catch (e: Exception) { UI.toast(this, "No file picker available") }
    }

    private fun openCamera() {
        val i = Intent(this, CameraActivity::class.java)
        i.putExtra(CameraActivity.EXTRA_PROJECT_ID, projectId)
        startActivityForResult(i, REQ_CAMERA)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != Activity.RESULT_OK) return
        when (req) {
            REQ_PICK_VIDEO, REQ_PICK_IMAGE -> {
                val uri = data?.data ?: return
                importFromUri(uri, req == REQ_PICK_VIDEO)
            }
            REQ_CAMERA -> {
                val rel = data?.getStringExtra(CameraActivity.EXTRA_RESULT_REL) ?: return
                val clip = File(store.projectDir(projectId), rel)
                if (!clip.exists()) return
                importClip(clip, name = "Camera take", type = LayerType.CAMERA)
            }
        }
    }

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
        val tmp = File(cacheDir, "import_" + System.currentTimeMillis() + "_" + displayName.replace("/", "_"))
        if (!MediaKit.copyContentToFile(this, uri, tmp)) { UI.toast(this, "Import failed"); return }
        importClip(tmp, name = displayName, type = if (isVideo) LayerType.VIDEO else LayerType.IMAGE)
    }

    private fun importClip(src: File, name: String, type: LayerType) {
        mutateThen {
            val p = proj!!
            val info = MediaKit.probe(src.absolutePath)
            // camera takes are already written into the project media dir - reuse
            val inProject = src.parentFile?.absolutePath == store.mediaDir(projectId).absolutePath
            val rel = if (inProject) "media/${src.name}" else store.copyIntoMedia(projectId, src)
            val l: Layer
            if (type == LayerType.IMAGE) {
                val bmp = MediaKit.image(src.absolutePath)
                l = Layer(type = type, name = name, relPath = rel,
                    srcW = bmp?.width ?: info.width, srcH = bmp?.height ?: info.height)
            } else {
                l = LayerPresets.fullscreen(type, name, rel, info.durMs, info.width, info.height, info.rotation)
            }
            fitNormalized(l, p)
            // first media layer covers the canvas; anything else becomes a PiP
            if (p.layers.isNotEmpty()) {
                l.wN *= 0.46f
                l.hN *= 0.46f
                l.cx = 0.5f
                l.cy = 0.76f
            }
            p.layers.add(l)
            selectedId = l.id
        }
        try { if (src.parentFile?.absolutePath != store.mediaDir(projectId).absolutePath) src.delete() } catch (_: Exception) { }
    }

    /** contain-fit the layer box onto the canvas without distortion */
    private fun fitNormalized(l: Layer, p: Project) {
        if (l.srcW <= 0 || l.srcH <= 0) { l.wN = 1f; l.hN = 1f; return }
        val (sw, sh) = if (l.srcRotation == 90 || l.srcRotation == 270) Pair(l.srcH, l.srcW) else Pair(l.srcW, l.srcH)
        val ca = p.aspect.canvasW.toFloat() / p.aspect.canvasH
        val sa = sw.toFloat() / sh
        if (sa >= ca) { l.wN = 1f; l.hN = (l.wN / sa) * ca }
        else { l.hN = 1f; l.wN = (l.hN * sa) / ca }
        l.cx = 0.5f; l.cy = 0.5f
        l.wN *= 0.96f; l.hN *= 0.96f
    }

    private fun presetFit(l: Layer) {
        pushUndo()
        fitNormalized(l, proj!!)
        markDirty(); stage.refresh()
    }

    private fun presetCenter(l: Layer) {
        pushUndo()
        l.cx = 0.5f; l.cy = 0.5f
        markDirty(); stage.refresh()
    }

    private fun presetPip(l: Layer) {
        pushUndo()
        l.wN = 0.44f
        l.hN = (l.wN * proj!!.aspect.canvasW * 0.75f) / proj!!.aspect.canvasH
        l.cx = 0.5f
        l.cy = 0.76f
        markDirty(); stage.refresh()
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
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseBg() {
        val colors = listOf(
            Color.rgb(16, 20, 24), Color.rgb(255, 255, 255), Color.rgb(255, 90, 44),
            Color.rgb(30, 60, 120), Color.rgb(20, 120, 90), Color.rgb(120, 30, 90),
            Color.rgb(240, 200, 60), Color.rgb(10, 180, 220)
        )
        val names = listOf("Dark", "White", "Studio orange", "Navy", "Green", "Purple", "Yellow", "Cyan")
        val holder = LinearLayout(this)
        holder.orientation = LinearLayout.VERTICAL
        holder.setPadding(UI.dp(this, 20), UI.dp(this, 6), UI.dp(this, 20), 0)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for ((i, c) in colors.withIndex()) {
            val v = TextView(this)
            v.text = names[i].substring(0, 1)
            v.gravity = Gravity.CENTER
            v.setTextColor(Color.WHITE)
            val g = GradientDrawable()
            g.cornerRadius = UI.dpf(this, 16f)
            g.setColor(c)
            v.background = g
            val lp = LinearLayout.LayoutParams(UI.dp(this, 32), UI.dp(this, 32))
            lp.rightMargin = UI.dp(this, 8)
            v.layoutParams = lp
            v.setOnClickListener {
                pushUndo()
                proj!!.bgColor = c
                markDirty(); stage.refresh()
                (it.tag as? android.app.Dialog)?.dismiss()
            }
            row.addView(v)
        }
        holder.addView(row)
        val dlg = AlertDialog.Builder(this).setTitle("Canvas background").setView(holder)
            .setNegativeButton("Close", null).show()
        for (i in 0 until row.childCount) row.getChildAt(i).tag = dlg
    }

    // ---------------- layers row & properties ----------------

    private fun rebuildLayersRow() {
        layersRow.removeAllViews()
        val p = proj!!
        if (p.layers.isEmpty()) {
            val t = UI.label(this, "No layers yet \u2014 add Video / Image / Text / Camera below", dim = true, size = 12f)
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
                LayerType.VIDEO -> "\uD83C\uDFA5"
                LayerType.CAMERA -> "\uD83C\uDFAC"
                LayerType.IMAGE -> "\uD83D\uDDBC"
                LayerType.TEXT -> "\uD83D\uDD8B"
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
            if (l.locked) {
                val lk = TextView(this)
                lk.text = "\uD83D\uDD12"
                lk.textSize = 8f
                card.addView(lk)
            }
            val li = i
            card.setOnClickListener {
                select(if (selectedId == l.id) null else l.id)
                rebuildLayersRow()
            }
            card.setOnLongClickListener {
                renameLayer(l)
                true
            }
            layersRow.addView(card)
        }
    }

    private fun refreshProps() {
        propsCol.removeAllViews()
        val p = proj!!
        val l = selectedId?.let { p.layerById(it) }
        if (l == null) {
            val t = UI.label(this,
                if (p.layers.isEmpty())
                    "Tap a layer or add media. Layers are independent: pause one \u2014 others keep playing."
                else "Tap a layer chip or on the canvas to select it.",
                dim = true, size = 12f)
            propsCol.addView(t)
            return
        }
        // name
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

        // ops row 1
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
        op(if (l.visible) "\uD83D\uDC41" else "\uD83D\uDE48") { mutateThen { l.visible = !l.visible } }
        op(if (l.locked) "\uD83D\uDD13 Unlock" else "\uD83D\uDD12 Lock") { mutateThen { l.locked = !l.locked } }
        if (l.isVideoLike()) {
            op(if (l.playing) "\u275A\u275A Pause layer" else "\u25B6 Play layer") {
                engine.toggleLayerPlay(l)
                refreshProps(); markDirty()
            }
            op(if (l.muted) "\uD83D\uDD07 Muted" else "\uD83D\uDD0A Sound") { mutateThen { l.muted = !l.muted } }
        }
        if (l.type == LayerType.TEXT) {
            op("\u270E Edit text") { editTextLayer(l) }
            op("Color") { mutateThen { l.textColor = nextColor(l.textColor) } }
        }
        op("\u2795") { duplicateLayer(l) }
        op("\uD83D\uDDD1") { deleteLayer(l) }
        propsCol.addView(ops)

        // sliders
        val opSlider = sliderRow("Opacity", (l.opacity * 100).toInt()) { v ->
            pushUndoLight()
            l.opacity = v / 100f
            markDirty(); stage.refresh()
        }
        propsCol.addView(opSlider)

        if (l.isVideoLike() && !l.muted) {
            val volSlider = sliderRow("Volume", (l.volume * 100).toInt()) { v ->
                pushUndoLight()
                engine.setVolume(l, v / 100f)
                markDirty()
            }
            propsCol.addView(volSlider)
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

        // z-order
        val zRow = LinearLayout(this)
        zRow.orientation = LinearLayout.HORIZONTAL
        zRow.gravity = Gravity.CENTER_VERTICAL
        val zt = UI.label(this, "Order", dim = true, size = 12f)
        zRow.addView(zt)
        UI.margin(zt, 0, 0, 8, 0, this)
        val front = UI.chip(this, "\u2B06 To front")
        front.setOnClickListener { mutateThen { moveTo(l, p.layers.size - 1) } }
        zRow.addView(front)
        val back = UI.chip(this, "\u2B07 To back")
        UI.margin(back, 5, 0, 0, 0, this)
        back.setOnClickListener { mutateThen { moveTo(l, 0) } }
        zRow.addView(back)
        propsCol.addView(zRow)
        UI.margin(propsCol, 0, 0, 0, 0, this)
    }

    private fun pushUndoLight() {
        // coalesce sliders: snapshot only first movement of a burst
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
            .setPositiveButton("OK") { _, _ -> pushUndo(); l.name = input.text.toString(); rebuildLayersRow(); markDirty() }
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

    // ---------------- export ----------------

    private fun showExportDialog() {
        val p = proj!!
        if (p.layers.isEmpty()) { UI.toast(this, "Nothing to export yet"); return }
        if (exportRunning) { UI.toast(this, "An export is already running"); return }

        val qualityNames = arrayOf("Fast", "Balanced", "High quality")
        val resNames = arrayOf("Small (~480p)", "Medium (~720p)", "Large (~1080p)")
        val fpsNames = arrayOf("24 fps", "30 fps")
        val holder = LinearLayout(this)
        holder.orientation = LinearLayout.VERTICAL
        holder.setPadding(UI.dp(this, 22), UI.dp(this, 4), UI.dp(this, 22), 0)
        var quality = 1
        var maxDim = 720
        var fps = 30

        fun pickRow(label: String, options: Array<String>, onPick: (Int) -> Unit): TextView {
            val t = UI.label(this, "$label: ${options[0]}", dim = false, size = 13f)
            UI.margin(t, 0, 6, 0, 6, this)
            t.setOnClickListener {
                AlertDialog.Builder(this@EditorActivity)
                    .setTitle(label)
                    .setItems(options) { _, which -> onPick(which); t.text = "$label: ${options[which]}" }
                    .show()
            }
            holder.addView(t)
            return t
        }
        pickRow("Quality", qualityNames) { quality = it }
        pickRow("Resolution", resNames) { maxDim = intArrayOf(480, 720, 1080)[it] }
        pickRow("Frame rate", fpsNames) { fps = if (it == 0) 24 else 30 }
        val info = UI.label(this, "Output: H.264 MP4 (no audio track in this MVP)\n" +
            "Duration: " + UI.fmtTime(p.durationMs()) + "  \u00b7  " + p.layers.size + " layers", dim = true, size = 11f)
        UI.margin(info, 0, 10, 0, 0, this)
        holder.addView(info)

        AlertDialog.Builder(this)
            .setTitle("Export video")
            .setView(holder)
            .setPositiveButton("Export") { _, _ -> runExport(quality, maxDim, fps) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runExport(quality: Int, maxDim: Int, fps: Int) {
        val p = proj!!
        val dir = File(getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), "AhmedStudio")
        dir.mkdirs()
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val out = File(dir, "AhmedReaction_${p.name.replace(" ", "_")}_$stamp.mp4")

        flushSave()
        exportRunning = true
        exportCancel.set(false)
        exportDialog = ProgressDialog(this).apply {
            setTitle("Exporting H.264 MP4")
            setMessage("Preparing\u2026")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setProgress(0)
            max = 100
            setCancelable(false)
            setButton(ProgressDialog.BUTTON_NEGATIVE, "Cancel") { _, _ -> exportCancel.set(true) }
            show()
        }

        Exporter.export(p, store, Exporter.Options(fps = fps, maxDim = maxDim, quality = quality, outFile = out),
            exportCancel,
            { prog, msg ->
                runOnUiThread { exportDialog?.let { it.progress = prog; it.setMessage(msg) } }
            },
            { res ->
                runOnUiThread {
                    exportRunning = false
                    exportDialog?.dismiss(); exportDialog = null
                    if (res.ok && res.file != null) {
                        UI.publishToGallery(this, res.file) { uri ->
                            AlertDialog.Builder(this)
                                .setTitle("Export complete")
                                .setMessage("Saved to Gallery / Movies/AhmedReactionStudio\n\n${res.file!!.absolutePath}\n${UI.niceBytes(res.file!!.length())}")
                                .setPositiveButton("Share") { _, _ -> if (uri != null) UI.shareUri(this, uri) else UI.toast(this, "Saved (Android 8/9: find it in Movies/AhmedStudio)") }
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
