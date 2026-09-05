package com.rehman.ahmedreactionstudio.editor

import android.app.Activity
import android.app.AlertDialog
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
import com.rehman.ahmedreactionstudio.core.ViewportFit
import com.rehman.ahmedreactionstudio.core.applyLayersJson
import com.rehman.ahmedreactionstudio.core.layersJsonOf
import com.rehman.ahmedreactionstudio.export.AudioDecode
import com.rehman.ahmedreactionstudio.export.ClipAudio
import com.rehman.ahmedreactionstudio.export.CompositionRecorder
import com.rehman.ahmedreactionstudio.export.DecodedClip
import com.rehman.ahmedreactionstudio.export.EncoderConfig
import com.rehman.ahmedreactionstudio.export.Exporter
import com.rehman.ahmedreactionstudio.export.MediaSave
import com.rehman.ahmedreactionstudio.ui.DiagnosticsActivity
import com.rehman.ahmedreactionstudio.util.UI
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reaction studio: the shared compositor sits inside measured safe chrome.
 * Step 5 tabs, layer chips and a floating contextual pill coexist with the
 * Phase 2 landscape rail, Full Canvas mode and nested Studio/source menus.
 * SourceController owns mutations/undo; view re-layout never restarts the
 * preview, live camera or audio master clock.
 */
class EditorActivity : Activity(), StageView.Host, RadialMenus.Host {

    companion object {
        const val EXTRA_PROJECT_ID = "pid"
        const val EXTRA_PROJECT_NAME = "pname"
        const val EXTRA_PROJECT_ASPECT = "paspect"
        const val REQ_PICK_VIDEO = 41
        const val REQ_PICK_IMAGE = 42
        const val REQ_CAMERA = 43
        const val REQ_SCREEN_CAPTURE = 44
        const val REQ_APP_PERMS = 45
        const val REQ_CAMERA_PERM = 46
        const val REQ_RECORD_PERM = 47
        /** editor prefs file + key for the preview health overlay */
        const val PREFS_EDITOR = "editor"
        const val PREF_STATS_HUD = "stats_hud"
        /** sticky export settings (codec name, quality idx, maxDim, fps) */
        const val PREF_EXP_CODEC = "exp_codec"
        const val PREF_EXP_QUALITY = "exp_quality"
        const val PREF_EXP_MAXDIM = "exp_maxdim"
        const val PREF_EXP_FPS = "exp_fps"
        const val PREF_HAD_EXPORT = "had_export"
    }

    private fun editorPrefs() = getSharedPreferences(PREFS_EDITOR, MODE_PRIVATE)

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
    private var chromeLayoutListener: android.view.ViewTreeObserver.OnGlobalLayoutListener? = null
    private lateinit var panelDivider: View
    private lateinit var panelContent: LinearLayout
    private lateinit var sheet: LinearLayout
    private lateinit var dockContainer: LinearLayout
    private lateinit var recChip: TextView
    private lateinit var statsHud: TextView
    private lateinit var hiddenPill: TextView
    private lateinit var wheel: RadialMenuView
    private lateinit var dock: SourceDock
    override lateinit var ctrl: SourceController
    private lateinit var rootFrame: FrameLayout
    private lateinit var studioBtn: IconBtn
    private var wheelBtn: IconBtn? = null
    private var sheetTab: String? = null
    // STEP 5 — professional bottom editor: tab bar + source strip
    private lateinit var tabBar: LinearLayout
    private lateinit var transportBar: LinearLayout
    private lateinit var sourceStripWrap: HorizontalScrollView
    private lateinit var sourceStrip: LinearLayout
    private val tabViews = HashMap<String, View>()

    // ===== viewport chrome: the canvas is fitted into what these leave free =====
    private lateinit var topBar: LinearLayout
    private lateinit var quickWrap: HorizontalScrollView
    /** Shared source strip: existing layers plus collapsible add-source shortcuts. */
    private var srcDockExpanded = false
    /** Full Canvas mode: every overlay hidden except one exit button */
    private var fullCanvas = false
    private lateinit var fullExitBtn: TextView
    /** system bar + cutout insets (px), applied by the WindowInsets listener */
    private var sysL = 0; private var sysT = 0; private var sysR = 0; private var sysB = 0
    private val insetsSync = Runnable { applyViewportInsets() }
    /**
     * Orientation-aware chrome. Portrait: everything in the bottom sheet.
     * Landscape: tabs, sources, contextual controls, panel and Record live in
     * a RIGHT RAIL; the bottom sheet is transport only — a 4-row sheet under
     * a 56dp top bar left a 16:9 canvas ~47dp tall on a phone.
     */
    private lateinit var panelScroll: ScrollView
    private lateinit var launchRow: LinearLayout
    private lateinit var sideRail: ScrollView
    private lateinit var railContent: LinearLayout
    private var chromeLandscape: Boolean? = null

    /** undo snackbar: custom bar with an action (replaces bare toasts for undoable ops) */
    private var snackBar: LinearLayout? = null
    private var snackMsg: TextView? = null
    private var snackAction: TextView? = null
    private val snackHandler = Handler(Looper.getMainLooper())
    private val snackHide = Runnable { snackBar?.visibility = View.GONE }

    /** modern progress overlay (replaces the deprecated ProgressDialog) */
    private var progOverlay: FrameLayout? = null
    private var progTitle: TextView? = null
    private var progMsg: TextView? = null
    private var progBar: android.widget.ProgressBar? = null
    private var progCancel: TextView? = null
    private var progOnCancel: (() -> Unit)? = null

    /** save indicator for the top-bar meta line (● unsaved / ✓ saved) */
    private var saveDirty = false

    /** live camera feed → canvas (one at a time; see LiveCamera) */
    private var liveCam: LiveCamera? = null
    private var liveCamLayerId: String? = null

    /** screen flash (front-camera lighting): overlay panel + max brightness */
    private var screenLight = false
    private var screenLightView: View? = null

    private lateinit var engine: PreviewEngine
    private val undo = UndoStack()
    private val saveHandler = Handler(Looper.getMainLooper())
    private val autosave = Runnable { flushSave() }
    private var scrubbing = false
    private var lastPlayingSig = ""

    private val exportCancel = AtomicBoolean(false)
    private var exportRunning = false

    // ===== composite (multi-source) recording — the RECORD button =====
    private lateinit var recordBtn: TextView
    private var recorder: CompositionRecorder? = null
    private var recording = false
    private var camWaitTries = 0
    private val recordHandler = Handler(Looper.getMainLooper())
    private val recordTick = object : Runnable {
        override fun run() {
            if (!recording) return
            recorder?.renderAndSubmit()
            recordHandler.postDelayed(this, 33L)
        }
    }
    /** on-the-fly decode cache so IMAGE sources render during recording */
    private val recordImageCache = HashMap<String, Bitmap>()

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
            { l -> engine.toggleLayerPlay(l); markDirty(); refreshAll() },
            { l -> openAdvancedSheet(l) },
            { pushUndo() },
            { from, to -> ctrl.reorderLive(from, to); stage.refresh() },
            { markDirty(); refreshAll() })
        rebuildDock()
        rebuildSourceDock()
        refreshContextBar()
        updateName()
        engine.refreshFrames()
        updateEmptyState()
        updateRecordButton()
    }

    private fun applyOrientationFor(@Suppress("UNUSED_PARAMETER") a: Aspect) {
        // STEP 1 viewport fix: never force-rotate the phone for a canvas
        // aspect. Every canvas (16:9, 9:16, 1:1) contain-fits every device
        // orientation via ViewportFit, so locking fought the user, hid the
        // composition mid-rotate, and made 16:9-in-portrait untestable.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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
        if (recChip.visibility == View.VISIBLE && !ScreenCaptureService.running &&
            liveCam?.recording != true) recChip.visibility = View.GONE
        // a live camera layer that survived a pause / rotate / relaunch gets
        // its feed back (a project saved with a live layer reopens live)
        reconcileLiveCamera()
        if (engineReady()) engine.refreshFrames()
    }

    override fun onPause() {
        flushSave()
        super.onPause()
    }

    override fun onStop() {
        // don't lose a composite recording if the user leaves mid-take
        if (recording) stopCompositeRecording(showUi = false)
        if (engineReady()) engine.stopSnapshots()
        // release the camera whenever we leave the foreground; it is restarted
        // in onResume so another app can use the camera meanwhile
        if (liveCam?.recording != true) stopLiveCamera(evict = false)
        // never leave the panel glowing / brightness pinned in the background
        if (screenLight) { screenLight = false; applyScreenLight() }
        super.onStop()
    }

    override fun onDestroy() {
        if (this::rootFrame.isInitialized) {
            chromeLayoutListener?.let {
                try { rootFrame.viewTreeObserver.removeOnGlobalLayoutListener(it) }
                catch (_: Exception) { }
            }
        }
        chromeLayoutListener = null
        if (this::rootFrame.isInitialized) rootFrame.removeCallbacks(insetsSync)
        recorder?.abort()
        recorder = null
        recordHandler.removeCallbacksAndMessages(null)
        for (b in recordImageCache.values) try { b.recycle() } catch (_: Exception) { }
        recordImageCache.clear()
        stopLiveCamera(evict = true)
        saveHandler.removeCallbacksAndMessages(null)
        flushSave()
        if (engineReady()) engine.release()
        if (ScreenCaptureService.onStopped != null) ScreenCaptureService.onStopped = null
        store.clearOpen(projectId)
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (wheel.isOpen()) { wheel.pop(); return }
        if (fullCanvas) { setFullCanvas(false); return }
        if (sheetTab != null) { setSheet(null); return }
        flushSave()
        store.clearOpen(projectId)
        super.onBackPressed()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // rotation: the chrome re-lays out, then the canvas is re-fitted; the
        // camera / decoders / master clock are NOT restarted (configChanges)
        relayoutChrome(newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
        stage.post { applyViewportInsets(); syncPreviewTarget() }
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
        recChip.contentDescription = "Stop the recording"
        recChip.gravity = Gravity.CENTER
        recChip.setTextColor(UI.DANGER)
        recChip.textSize = 11f
        recChip.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        recChip.setPadding(UI.dp(this, 14), UI.dp(this, 8), UI.dp(this, 14), UI.dp(this, 8))
        recChip.background = Ic.pill(this, Color.argb(220, 20, 8, 10), 18f,
            Color.argb(180, 255, 90, 90))
        recChip.visibility = View.GONE
        recChip.setOnClickListener {
            val camL = liveCamLayerId?.let { id -> proj?.layerById(id) }
            if (liveCam?.recording == true && camL != null) toggleLiveCameraRecord(camL)
            else stopScreenCapture()
        }
        val rlp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        rlp.topMargin = UI.dp(this, 58)
        root.addView(recChip, rlp)

        // ===== preview health HUD =====
        // "The preview stutters" is impossible to diagnose blind. This shows the
        // two numbers that matter: whether clips are on the hardware MediaCodec
        // path (HW) or have been pushed onto the MediaMetadataRetriever
        // fallback (SW), and the preview frame rate. Tap to hide; the
        // Diagnostics screen turns it back on.
        statsHud = TextView(this)
        statsHud.textSize = 10.5f
        statsHud.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        statsHud.setTextColor(Color.WHITE)
        statsHud.setPadding(UI.dp(this, 10), UI.dp(this, 4), UI.dp(this, 10), UI.dp(this, 4))
        statsHud.background = Ic.pill(this, Color.argb(165, 8, 10, 14), 12f,
            Color.argb(55, 255, 255, 255))
        statsHud.contentDescription = "Preview statistics. Tap to hide."
        statsHud.visibility = View.GONE
        statsHud.setOnClickListener {
            editorPrefs().edit().putBoolean(PREF_STATS_HUD, false).apply()
            statsHud.visibility = View.GONE
        }
        val shlp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
        shlp.topMargin = UI.dp(this, 58)
        shlp.marginStart = UI.dp(this, 10)
        shlp.leftMargin = UI.dp(this, 10)
        root.addView(statsHud, shlp)

        hiddenPill = TextView(this)
        hiddenPill.textSize = 11f
        hiddenPill.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        hiddenPill.setTextColor(Color.WHITE)
        hiddenPill.setPadding(UI.dp(this, 12), UI.dp(this, 6), UI.dp(this, 12), UI.dp(this, 6))
        hiddenPill.background = Ic.pill(this, Color.argb(200, 18, 20, 28), 14f,
            Color.argb(90, 255, 255, 255))
        hiddenPill.visibility = View.GONE
        hiddenPill.contentDescription = "Show hidden sources"
        hiddenPill.setOnClickListener { setSheet("sources") }
        val hplp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
        hplp.topMargin = UI.dp(this, 58)
        hplp.marginEnd = UI.dp(this, 10)
        hplp.rightMargin = UI.dp(this, 10)
        root.addView(hiddenPill, hplp)

        // ===== floating source controls — centered pill above bottom bar =====
        // Step 5 pill styling + Phase 2 contextual verbs. Constrain the row
        // to the safe width; an explicit scrollbar/fade keeps every action
        // reachable on narrow phones and in the landscape rail.
        val qWrap = HorizontalScrollView(this)
        qWrap.isHorizontalScrollBarEnabled = true
        qWrap.isScrollbarFadingEnabled = false
        qWrap.isHorizontalFadingEdgeEnabled = true
        qWrap.setFadingEdgeLength(UI.dp(this, 16))
        val qlp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        qlp.bottomMargin = UI.dp(this, 8) // updated from the measured sheet, never a fixed offset
        qlp.setMargins(UI.dp(this, 12), 0, UI.dp(this, 12), UI.dp(this, 8))
        root.addView(qWrap, qlp)
        quickWrap = qWrap
        quickBar = LinearLayout(this)
        quickBar.orientation = LinearLayout.HORIZONTAL
        quickBar.gravity = Gravity.CENTER_VERTICAL
        quickBar.setPadding(UI.dp(this, 10), UI.dp(this, 6), UI.dp(this, 10), UI.dp(this, 6))
        quickBar.background = Ic.pill(this, Color.argb(232, 14, 16, 22), 22f,
            Color.argb(110, 255, 255, 255))
        // subtle elevation via shadow is free (no blur) — use outline for performance
        quickBar.elevation = UI.dpf(this, 6f)
        quickBar.visibility = View.GONE
        qWrap.addView(quickBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        quickWrap = qWrap

        buildSheet(root)
        buildSideRail(root)
        relayoutChrome(isLandscape())
        buildFullCanvasExit(root)

        // ===== the canvas is fitted into whatever the chrome leaves free =====
        // System bars + display cutout come from WindowInsets; the top bar,
        // the sheet (dock / contextual controls / open panel) and the quick
        // bar are measured after every layout pass. StageView then re-runs
        // its contain-fit, so opening a panel shrinks the canvas instead of
        // covering it.
        root.setOnApplyWindowInsetsListener { v, insets ->
            readSystemInsets(insets)
            v.post(insetsSync)
            insets
        }
        chromeLayoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener { applyViewportInsets() }
        root.viewTreeObserver.addOnGlobalLayoutListener(chromeLayoutListener)
        stage.onCanvasLayout = { _, _ -> stage.post { syncPreviewTarget() } }

        // ===== nested radial menu overlay (top of everything) =====
        wheel = RadialMenuView(this)
        root.addView(wheel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        stage.contentDescription = "Composition canvas. Tap a source to select it."
        buildSnackBar(root)
        buildProgOverlay(root)

        setContentView(root)
        stage.post { syncPreviewTarget() }
    }

    /** Both legacy Step 5 refreshes and Phase 2 use one measured layout. */
    private fun updateStageInsets() = applyViewportInsets()

    /** Step 5's 38% panel cap / 28% canvas reserve, including floating controls. */
    private fun capPanelHeight(topPx: Int, viewHeight: Int) {
        if (chromeLandscape == true || panelScroll.visibility != View.VISIBLE || sheet.height <= 0) return
        val fixedSheet = (sheet.height - panelScroll.height).coerceAtLeast(0)
        val controls = if (quickWrap.visibility == View.VISIBLE) quickWrap.height + UI.dp(this, 8) else 0
        val want = ViewportFit.panelHeight(viewHeight, topPx, fixedSheet, controls)
        val lp = panelScroll.layoutParams as? LinearLayout.LayoutParams ?: return
        if (lp.height != want) {
            lp.height = want
            panelScroll.layoutParams = lp
        }
    }

    private fun buildTopBar(root: FrameLayout) {
        val top = LinearLayout(this)
        topBar = top
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(UI.dp(this, 12), UI.dp(this, 10), UI.dp(this, 12), UI.dp(this, 10))
        val tg = GradientDrawable()
        tg.orientation = GradientDrawable.Orientation.TOP_BOTTOM
        tg.colors = intArrayOf(Color.argb(210, 0, 0, 0), Color.argb(55, 0, 0, 0), Color.argb(0, 0, 0, 0))
        top.background = tg
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val back = IconBtn(this)
        back.layoutParams = IconBtn.sized(this, 44)
        back.setIcon(R.drawable.ic_back, UI.FG, "Back")
        back.setOnClickListener { onBackPressed() }
        top.addView(back)

        val nameCol = LinearLayout(this)
        nameCol.orientation = LinearLayout.VERTICAL
        val nlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        nlp.setMargins(UI.dp(this, 10), 0, UI.dp(this, 8), 0)
        nameCol.layoutParams = nlp
        val nameView = TextView(this)
        nameView.id = View.generateViewId()
        nameView.tag = "name"
        nameView.setTextColor(UI.FG)
        nameView.textSize = 14f
        nameView.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        nameView.maxLines = 1
        nameView.ellipsize = android.text.TextUtils.TruncateAt.END
        nameCol.addView(nameView)
        val meta = TextView(this)
        meta.tag = "meta"
        meta.setTextColor(Color.argb(190, 255, 255, 255))
        meta.textSize = 10f
        meta.maxLines = 1
        meta.ellipsize = android.text.TextUtils.TruncateAt.END
        nameCol.addView(meta)
        nameCol.isClickable = true
        nameCol.isFocusable = true
        nameCol.contentDescription = "Rename project"
        nameCol.setOnClickListener { renameProject() }
        top.addView(nameCol)

        aspectChip = TextView(this)
        aspectChip.gravity = Gravity.CENTER
        aspectChip.setTextColor(Color.WHITE)
        aspectChip.textSize = 11.5f
        aspectChip.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        aspectChip.setPadding(UI.dp(this, 14), UI.dp(this, 9), UI.dp(this, 14), UI.dp(this, 9))
        aspectChip.background = Ic.pill(this, Color.argb(210, 30, 34, 44), 18f,
            Color.argb(100, 255, 255, 255))
        aspectChip.contentDescription = "Change canvas aspect ratio"
        aspectChip.setOnClickListener { showAspectPicker() }
        val alp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        alp.setMargins(0, 0, UI.dp(this, 10), 0)
        aspectChip.layoutParams = alp
        top.addView(aspectChip)
        updateAspectChip()

        val undoB = IconBtn(this)
        undoB.layoutParams = IconBtn.sized(this, 44)
        undoB.setIcon(R.drawable.ic_undo, UI.FG, "Undo")
        undoB.setOnClickListener { doUndo() }
        top.addView(undoB)

        val redoB = IconBtn(this)
        redoB.layoutParams = IconBtn.sized(this, 44)
        redoB.setIcon(R.drawable.ic_redo, UI.FG, "Redo")
        redoB.setOnClickListener { doRedo() }
        top.addView(redoB)

        val full = IconBtn(this)
        full.layoutParams = IconBtn.sized(this, 44)
        full.setIcon(R.drawable.ic_fullscreen, UI.FG, "Full canvas: hide all controls")
        full.setOnClickListener { setFullCanvas(true) }
        top.addView(full)

        val diag = IconBtn(this)
        diag.layoutParams = IconBtn.sized(this, 44)
        diag.setIcon(R.drawable.ic_settings, UI.FG, "Project settings")
        diag.setOnClickListener { startActivity(Intent(this, DiagnosticsActivity::class.java)) }
        top.addView(diag)
    }

    private fun buildSheet(root: FrameLayout) {
        sheet = LinearLayout(this)
        sheet.orientation = LinearLayout.VERTICAL
        val sg = GradientDrawable()
        sg.cornerRadius = UI.dpf(this, 16f)
        sg.setColor(Color.rgb(12, 14, 19))
        sg.setStroke(UI.dp(this, 1), Color.argb(45, 255, 255, 255))
        sheet.background = sg
        // subtle top elevation
        sheet.elevation = UI.dpf(this, 8f)
        val slp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        root.addView(sheet, slp)

        // ---------- expandable panel (Sources / Mixer / Export / Advanced) ----------
        panelContent = LinearLayout(this)
        panelContent.orientation = LinearLayout.VERTICAL
        panelContent.setPadding(0, UI.dp(this, 2), 0, 0)
        val scroll = ScrollView(this)
        scroll.tag = "panelScroll"
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        val maxH = (resources.displayMetrics.heightPixels * 0.38f).toInt().coerceAtLeast(0)
        scroll.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxH)
        scroll.visibility = View.GONE
        scroll.addView(panelContent, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sheet.addView(scroll)
        panelScroll = scroll

        // divider between panel and controls — visible only when panel open
        panelDivider = View(this)
        panelDivider.tag = "panelDivider"
        panelDivider.setBackgroundColor(Color.argb(50, 255, 255, 255))
        panelDivider.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 1))
        panelDivider.visibility = View.GONE
        sheet.addView(panelDivider)

        // ---------- shared horizontal source strip ----------
        // Step 5 layer chips plus Phase 2 collapsible add-source shortcuts.
        // Tapping a chip selects; long press opens that source's settings.
        sourceStripWrap = HorizontalScrollView(this)
        sourceStripWrap.isHorizontalScrollBarEnabled = false
        sourceStripWrap.setPadding(UI.dp(this, 8), UI.dp(this, 6), UI.dp(this, 8), UI.dp(this, 6))
        sourceStripWrap.visibility = View.GONE
        sourceStrip = LinearLayout(this)
        sourceStrip.orientation = LinearLayout.HORIZONTAL
        sourceStrip.gravity = Gravity.CENTER_VERTICAL
        sourceStripWrap.addView(sourceStrip, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
        sheet.addView(sourceStripWrap)

        // ---------- professional bottom tab bar (Sources / Add / Audio / Text / Export) ----------
        tabBar = LinearLayout(this)
        tabBar.orientation = LinearLayout.HORIZONTAL
        tabBar.gravity = Gravity.CENTER_VERTICAL
        tabBar.setPadding(UI.dp(this, 4), UI.dp(this, 4), UI.dp(this, 4), UI.dp(this, 4))
        tabBar.setBackgroundColor(Color.rgb(12, 14, 19))
        // top hairline
        val tabTopLine = View(this)
        tabTopLine.setBackgroundColor(Color.argb(45, 255, 255, 255))
        tabTopLine.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 1))
        sheet.addView(tabTopLine)
        sheet.addView(tabBar)
        buildTabBar()

        // ---------- transport row (play / seek / time) ----------
        transportBar = LinearLayout(this)
        transportBar.orientation = LinearLayout.HORIZONTAL
        transportBar.gravity = Gravity.CENTER_VERTICAL
        transportBar.setPadding(UI.dp(this, 10), UI.dp(this, 6), UI.dp(this, 10), UI.dp(this, 8))
        transportBar.setBackgroundColor(Color.rgb(9, 10, 14))
        sheet.addView(transportBar)
        buildTransportBar()

        // ---------- composite RECORD + Studio row — compact, not dominating ----------
        val bottomActionRow = LinearLayout(this)
        launchRow = bottomActionRow
        bottomActionRow.orientation = LinearLayout.HORIZONTAL
        bottomActionRow.gravity = Gravity.CENTER_VERTICAL
        bottomActionRow.setPadding(UI.dp(this, 10), UI.dp(this, 6), UI.dp(this, 10), UI.dp(this, 10))
        bottomActionRow.setBackgroundColor(Color.rgb(9, 10, 14))
        sheet.addView(bottomActionRow)

        recordBtn = TextView(this)
        recordBtn.gravity = Gravity.CENTER
        recordBtn.setTextColor(Color.WHITE)
        recordBtn.textSize = 12.5f
        recordBtn.maxLines = 1
        recordBtn.ellipsize = android.text.TextUtils.TruncateAt.END
        recordBtn.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        recordBtn.setPadding(UI.dp(this, 16), 0, UI.dp(this, 16), 0)
        recordBtn.text = "●  START RECORDING"
        recordBtn.background = Ic.pill(this, Color.argb(240, 200, 34, 34), 20f,
            Color.argb(160, 255, 130, 130))
        recordBtn.visibility = View.VISIBLE
        recordBtn.setOnClickListener { recordButtonTap() }
        val rblp = LinearLayout.LayoutParams(0, UI.dp(this, 44), 1f)
        rblp.setMargins(0, 0, UI.dp(this, 10), 0)
        recordBtn.layoutParams = rblp
        bottomActionRow.addView(recordBtn)

        studioBtn = IconBtn(this)
        studioBtn.layoutParams = IconBtn.sized(this, 44)
        studioBtn.background = Ic.pill(this, Color.argb(240, 255, 90, 44), 22f,
            Color.argb(140, 255, 200, 160))
        studioBtn.setIcon(R.drawable.ic_wheel, Color.WHITE, "Open Studio menu — all controls")
        studioBtn.setOnClickListener {
            studioBtn.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            studioBtn.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).withEndAction {
                studioBtn.animate().scaleX(1f).scaleY(1f).setDuration(220)
                    .setInterpolator(OvershootInterpolator(2f)).start()
                openRootWheel()
            }.start()
        }
        bottomActionRow.addView(studioBtn)
    }

    private fun buildTabBar() {
        tabBar.removeAllViews()
        tabViews.clear()
        fun addTab(id: String, icon: Int, label: String, desc: String, onTap: () -> Unit) {
            val tab = LinearLayout(this)
            tab.orientation = LinearLayout.VERTICAL
            tab.gravity = Gravity.CENTER
            tab.setPadding(UI.dp(this, 4), UI.dp(this, 6), UI.dp(this, 4), UI.dp(this, 6))
            tab.isClickable = true
            tab.isFocusable = true
            tab.contentDescription = desc
            val sel = sheetTab == id
            val bg = GradientDrawable()
            bg.cornerRadius = UI.dpf(this, 12f)
            bg.setColor(if (sel) Color.argb(55, 255, 90, 44) else Color.TRANSPARENT)
            if (sel) bg.setStroke(UI.dp(this, 1), Color.argb(90, 255, 90, 44))
            tab.background = bg
            tab.isSelected = sel
            val iv = android.widget.ImageView(this)
            iv.setImageDrawable(Ic.get(this, icon, if (sel) UI.ACCENT else UI.FG2))
            val ivlp = LinearLayout.LayoutParams(UI.dp(this, 20), UI.dp(this, 20))
            iv.layoutParams = ivlp
            tab.addView(iv)
            val tv = TextView(this)
            tv.text = label
            tv.setTextColor(if (sel) UI.ACCENT else UI.FG2)
            tv.textSize = 9.5f
            tv.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            tv.gravity = Gravity.CENTER
            tv.maxLines = 1
            val tvlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            tvlp.topMargin = UI.dp(this, 3)
            tv.layoutParams = tvlp
            tab.addView(tv)
            tab.tag = id
            tab.setOnClickListener { onTap() }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(UI.dp(this, 2), 0, UI.dp(this, 2), 0)
            tab.layoutParams = lp
            tabBar.addView(tab)
            tabViews[id] = tab
        }
        addTab("sources", R.drawable.ic_layers, "Layers", "Layers — sources on canvas") {
            if (sheetTab == "sources") setSheet(null) else setSheet("sources")
        }
        addTab("add", R.drawable.ic_add, "Add", "Add source — camera, video, image, text") {
            openWheelLevel(RadialMenus.add(this), -1f, -1f)
        }
        addTab("mixer", R.drawable.ic_volume, "Audio", "Audio mixer") {
            if (sheetTab == "mixer") setSheet(null) else setSheet("mixer")
        }
        addTab("text", R.drawable.ic_text, "Text", "Add text overlay") {
            addText()
        }
        addTab("export", R.drawable.ic_export, "Export", "Export video") {
            if (sheetTab == "export") setSheet(null) else setSheet("export")
        }
    }

    private fun refreshTabBar() {
        // update active state without rebuilding to avoid flicker
        for ((id, v) in tabViews) {
            val sel = sheetTab == id
            val tab = v as LinearLayout
            tab.isSelected = sel
            val iv = tab.getChildAt(0) as android.widget.ImageView
            val tv = tab.getChildAt(1) as TextView
            val wantIcon = when (id) {
                "sources" -> R.drawable.ic_layers
                "add" -> R.drawable.ic_add
                "mixer" -> R.drawable.ic_volume
                "text" -> R.drawable.ic_text
                "export" -> R.drawable.ic_export
                else -> R.drawable.ic_layers
            }
            iv.setImageDrawable(Ic.get(this, wantIcon, if (sel) UI.ACCENT else UI.FG2))
            tv.setTextColor(if (sel) UI.ACCENT else UI.FG2)
            val bg = tab.background as? GradientDrawable
            bg?.setColor(if (sel) Color.argb(55, 255, 90, 44) else Color.TRANSPARENT)
            if (sel) bg?.setStroke(UI.dp(this, 1), Color.argb(90, 255, 90, 44))
            else bg?.setStroke(0, Color.TRANSPARENT)
        }
    }

    private fun buildTransportBar() {
        transportBar.removeAllViews()
        val back10 = TextView(this)
        back10.text = "−10"
        back10.gravity = Gravity.CENTER
        back10.setTextColor(UI.FG)
        back10.textSize = 11f
        back10.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        back10.setPadding(UI.dp(this, 10), 0, UI.dp(this, 10), 0)
        val b10bg = GradientDrawable()
        b10bg.cornerRadius = UI.dpf(this, 16f)
        b10bg.setColor(Color.argb(90, 38, 42, 52))
        b10bg.setStroke(UI.dp(this, 1), Color.argb(50, 255, 255, 255))
        back10.background = b10bg
        back10.layoutParams = LinearLayout.LayoutParams(UI.dp(this, 52), UI.dp(this, 32))
        back10.contentDescription = "Back 10 seconds"
        back10.isClickable = true
        back10.setOnClickListener { nudge(-10_000L) }
        transportBar.addView(back10)

        playBtn = IconBtn(this)
        val plp = LinearLayout.LayoutParams(UI.dp(this, 44), UI.dp(this, 44))
        plp.setMargins(UI.dp(this, 8), 0, UI.dp(this, 8), 0)
        playBtn.layoutParams = plp
        val pg = GradientDrawable()
        pg.shape = GradientDrawable.OVAL
        pg.setColor(UI.ACCENT)
        pg.setStroke(UI.dp(this, 1), Color.argb(120, 255, 200, 160))
        playBtn.background = pg
        playBtn.setIcon(R.drawable.ic_play, Color.WHITE, "Play")
        playBtn.setOnClickListener { togglePlay() }
        transportBar.addView(playBtn)

        val fwd10 = TextView(this)
        fwd10.text = "+10"
        fwd10.gravity = Gravity.CENTER
        fwd10.setTextColor(UI.FG)
        fwd10.textSize = 11f
        fwd10.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        fwd10.setPadding(UI.dp(this, 10), 0, UI.dp(this, 10), 0)
        val f10bg = GradientDrawable()
        f10bg.cornerRadius = UI.dpf(this, 16f)
        f10bg.setColor(Color.argb(90, 38, 42, 52))
        f10bg.setStroke(UI.dp(this, 1), Color.argb(50, 255, 255, 255))
        fwd10.background = f10bg
        fwd10.layoutParams = LinearLayout.LayoutParams(UI.dp(this, 52), UI.dp(this, 32))
        fwd10.contentDescription = "Forward 10 seconds"
        fwd10.isClickable = true
        fwd10.setOnClickListener { nudge(10_000L) }
        transportBar.addView(fwd10)

        timeLabel = TextView(this)
        timeLabel.text = "0:00"
        timeLabel.setTextColor(Color.WHITE)
        timeLabel.textSize = 12f
        timeLabel.typeface = Typeface.create("monospace", Typeface.BOLD)
        timeLabel.gravity = Gravity.CENTER
        val tlp = LinearLayout.LayoutParams(UI.dp(this, 48), ViewGroup.LayoutParams.WRAP_CONTENT)
        tlp.setMargins(UI.dp(this, 10), 0, UI.dp(this, 4), 0)
        timeLabel.layoutParams = tlp
        transportBar.addView(timeLabel)

        seek = SeekBar(this)
        seek.progressTintList = android.content.res.ColorStateList.valueOf(UI.ACCENT)
        seek.thumbTintList = android.content.res.ColorStateList.valueOf(UI.ACCENT2)
        // thumb size: keep default but ensure it's large enough to grab (no custom drawable needed)
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
        transportBar.addView(seek)

        durationLabel = TextView(this)
        durationLabel.text = "/ " + UI.fmtTime(proj!!.durationMs())
        durationLabel.setTextColor(Color.argb(170, 255, 255, 255))
        durationLabel.textSize = 11f
        durationLabel.typeface = Typeface.create("monospace", Typeface.NORMAL)
        val dlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dlp.setMargins(UI.dp(this, 6), 0, 0, 0)
        durationLabel.layoutParams = dlp
        transportBar.addView(durationLabel)
    }

    private fun updateSourceStrip() {
        if (!this::sourceStrip.isInitialized || !this::sourceStripWrap.isInitialized) return
        sourceStrip.removeAllViews()
        val p = proj ?: return
        val hasPanel = sheetTab != null
        if (fullCanvas || hasPanel) {
            sourceStripWrap.visibility = View.GONE
            return
        }
        sourceStripWrap.visibility = View.VISIBLE
        val live = p.layers.firstOrNull { it.isLive() }
        dockBtn(sourceStrip, R.drawable.ic_camera, "Camera",
            if (live != null) "Select the live camera" else "Add the live camera", active = live != null) {
            if (live != null) select(live.id) else addLiveCamera()
        }
        dockBtn(sourceStrip, R.drawable.ic_video, "Video", "Add a local video") { pickMedia(video = true) }
        // chips for each source — horizontal strip, most-recent-first visual? keep Z order top-first as dock
        for (i in p.layers.indices.reversed()) {
            val l = p.layers[i]
            val sel = l.id == selectedId
            val chip = LinearLayout(this)
            chip.orientation = LinearLayout.HORIZONTAL
            chip.gravity = Gravity.CENTER_VERTICAL
            chip.setPadding(UI.dp(this, 10), UI.dp(this, 7), UI.dp(this, 10), UI.dp(this, 7))
            val bg = GradientDrawable()
            bg.cornerRadius = UI.dpf(this, 18f)
            bg.setColor(if (sel) Color.argb(70, 255, 90, 44) else Color.argb(90, 27, 30, 38))
            bg.setStroke(UI.dp(this, 1), if (sel) Color.argb(200, 255, 130, 80) else Color.argb(45, 255, 255, 255))
            chip.background = bg
            val iv = android.widget.ImageView(this)
            iv.setImageDrawable(Ic.get(this, Ic.typeIcon(l.type), if (sel) UI.ACCENT else if (l.visible) UI.FG else Color.argb(120, 255, 255, 255)))
            val ivlp = LinearLayout.LayoutParams(UI.dp(this, 16), UI.dp(this, 16))
            ivlp.setMargins(0, 0, UI.dp(this, 6), 0)
            iv.layoutParams = ivlp
            chip.addView(iv)
            val tv = TextView(this)
            val short = when (l.type) {
                LayerType.CAMERA -> if (l.isLive()) "Camera" else "Take"
                LayerType.VIDEO -> "Video"
                LayerType.IMAGE -> "Image"
                LayerType.TEXT -> "Text"
                LayerType.SCREEN -> "Screen"
            }
            val nm = l.name.ifBlank { short }
            tv.text = if (nm.length > 14) nm.take(13) + "…" else nm
            tv.setTextColor(if (sel) Color.WHITE else if (l.visible) UI.FG else Color.argb(130, 255, 255, 255))
            tv.textSize = 11.5f
            tv.typeface = Typeface.create("sans-serif-medium", if (sel) Typeface.BOLD else Typeface.NORMAL)
            tv.maxLines = 1
            chip.addView(tv)
            if (!l.visible) {
                val eye = android.widget.ImageView(this)
                eye.setImageDrawable(Ic.get(this, R.drawable.ic_eye_off, Color.argb(110, 255, 255, 255)))
                val elp = LinearLayout.LayoutParams(UI.dp(this, 12), UI.dp(this, 12))
                elp.setMargins(UI.dp(this, 6), 0, 0, 0)
                eye.layoutParams = elp
                chip.addView(eye)
            } else if (l.locked) {
                val lk = android.widget.ImageView(this)
                lk.setImageDrawable(Ic.get(this, R.drawable.ic_lock, Color.argb(170, 255, 200, 120)))
                val llp = LinearLayout.LayoutParams(UI.dp(this, 12), UI.dp(this, 12))
                llp.setMargins(UI.dp(this, 6), 0, 0, 0)
                lk.layoutParams = llp
                chip.addView(lk)
            }
            chip.isClickable = true
            chip.isFocusable = true
            chip.isSelected = sel
            chip.minimumHeight = UI.dp(this, 48)
            chip.contentDescription = "Select ${l.name.ifBlank { short }}"
            chip.setOnClickListener { select(l.id) }
            chip.setOnLongClickListener { openAdvancedSheet(l); true }
            val clp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            clp.setMargins(UI.dp(this, 4), 0, UI.dp(this, 4), 0)
            chip.layoutParams = clp
            sourceStrip.addView(chip)
        }
        // + Add chip at the end
        val addChip = LinearLayout(this)
        addChip.orientation = LinearLayout.HORIZONTAL
        addChip.gravity = Gravity.CENTER_VERTICAL
        addChip.setPadding(UI.dp(this, 12), UI.dp(this, 7), UI.dp(this, 14), UI.dp(this, 7))
        val abg = GradientDrawable()
        abg.cornerRadius = UI.dpf(this, 18f)
        abg.setColor(Color.argb(90, 27, 30, 38))
        abg.setStroke(UI.dp(this, 1), Color.argb(70, 255, 255, 255))
        addChip.background = abg
        val aiv = android.widget.ImageView(this)
        aiv.setImageDrawable(Ic.get(this, R.drawable.ic_add, UI.ACCENT))
        val ailp = LinearLayout.LayoutParams(UI.dp(this, 14), UI.dp(this, 14))
        ailp.setMargins(0, 0, UI.dp(this, 5), 0)
        aiv.layoutParams = ailp
        addChip.addView(aiv)
        val atv = TextView(this)
        atv.text = if (srcDockExpanded) "Less" else "Add"
        atv.setTextColor(UI.FG)
        atv.textSize = 11.5f
        atv.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        addChip.addView(atv)
        addChip.isClickable = true
        addChip.isFocusable = true
        addChip.minimumHeight = UI.dp(this, 48)
        addChip.contentDescription = if (srcDockExpanded) "Collapse source shortcuts" else "More source types"
        addChip.setOnClickListener {
            srcDockExpanded = !srcDockExpanded
            rebuildSourceDock()
        }
        val aclp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        aclp.setMargins(UI.dp(this, 4), 0, UI.dp(this, 4), 0)
        addChip.layoutParams = aclp
        sourceStrip.addView(addChip)
        if (srcDockExpanded) {
            dockBtn(sourceStrip, R.drawable.ic_image, "Image", "Add an image") { pickMedia(video = false) }
            dockBtn(sourceStrip, R.drawable.ic_text, "Text", "Add a text overlay") { addText() }
            dockBtn(sourceStrip, R.drawable.ic_screen, "Screen", "Record the screen as a source") { startScreenCapture() }
        }
        sourceStripWrap.post(insetsSync)
    }


    // ================= orientation-aware chrome =================

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    private fun railWidthPx(): Int =
        (resources.displayMetrics.widthPixels * 0.40f).toInt()
            .coerceIn(UI.dp(this, 220), UI.dp(this, 340))

    private fun buildSideRail(root: FrameLayout) {
        // The combined tabs/source/actions can exceed a short landscape
        // screen. Scroll the rail instead of clipping Record or panel controls.
        sideRail = ScrollView(this)
        sideRail.isFillViewport = true
        sideRail.isVerticalScrollBarEnabled = true
        sideRail.isScrollbarFadingEnabled = false
        railContent = LinearLayout(this)
        railContent.orientation = LinearLayout.VERTICAL
        sideRail.addView(railContent, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val g = GradientDrawable()
        g.orientation = GradientDrawable.Orientation.LEFT_RIGHT
        g.colors = intArrayOf(Color.argb(225, 10, 12, 17), Color.argb(250, 8, 9, 13))
        sideRail.background = g
        sideRail.visibility = View.GONE
        sideRail.isClickable = true
        root.addView(sideRail, FrameLayout.LayoutParams(railWidthPx(),
            ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END or Gravity.TOP))
    }

    /**
     * Re-parent the already-built rows for the orientation. Pure view
     * re-parenting: no panel is rebuilt, the camera / decoders / clock are
     * untouched (the activity handles configChanges itself).
     */
    private fun relayoutChrome(landscape: Boolean) {
        if (chromeLandscape == landscape) return
        if (!this::sideRail.isInitialized || !this::launchRow.isInitialized) return
        chromeLandscape = landscape
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        for (v in listOf<View>(panelScroll, panelDivider, sourceStripWrap, tabBar, quickWrap, transportBar, launchRow)) {
            (v.parent as? ViewGroup)?.removeView(v)
        }
        sheet.removeAllViews()
        railContent.removeAllViews()
        if (!landscape) {
            sheet.addView(panelScroll, LinearLayout.LayoutParams(match,
                (resources.displayMetrics.heightPixels * 0.38f).toInt()))
            sheet.addView(panelDivider, LinearLayout.LayoutParams(match, UI.dp(this, 1)))
            sheet.addView(sourceStripWrap, LinearLayout.LayoutParams(match, wrap))
            sheet.addView(tabBar, LinearLayout.LayoutParams(match, wrap))
            sheet.addView(transportBar, LinearLayout.LayoutParams(match, wrap))
            sheet.addView(launchRow, LinearLayout.LayoutParams(match, wrap))
            val qlp = FrameLayout.LayoutParams(wrap, wrap, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            qlp.setMargins(UI.dp(this, 12), 0, UI.dp(this, 12), UI.dp(this, 8))
            rootFrame.addView(quickWrap, qlp)
            // Re-parenting must not place the toolbar above modal overlays.
            if (this::wheel.isInitialized) wheel.bringToFront()
            snackBar?.bringToFront()
            progOverlay?.bringToFront()
            if (fullCanvas && this::fullExitBtn.isInitialized) fullExitBtn.bringToFront()
            sideRail.visibility = View.GONE
        } else {
            railContent.addView(tabBar, LinearLayout.LayoutParams(match, wrap))
            railContent.addView(sourceStripWrap, LinearLayout.LayoutParams(match, wrap))
            railContent.addView(quickWrap, LinearLayout.LayoutParams(match, wrap))
            railContent.addView(panelDivider, LinearLayout.LayoutParams(match, UI.dp(this, 1)))
            railContent.addView(panelScroll, LinearLayout.LayoutParams(match,
                (resources.displayMetrics.heightPixels * 0.38f).toInt()))
            railContent.addView(launchRow, LinearLayout.LayoutParams(match, wrap))
            sideRail.visibility = if (fullCanvas) View.GONE else View.VISIBLE
            sheet.addView(transportBar, LinearLayout.LayoutParams(match, wrap))
        }
        sheet.post(insetsSync)
    }

    // ================= source dock + contextual controls + Full Canvas =================

    private fun dockBtn(parent: LinearLayout, icon: Int, label: String, desc: String,
                        active: Boolean = false, fn: () -> Unit): LinearLayout {
        val b = LinearLayout(this)
        b.orientation = LinearLayout.VERTICAL
        b.gravity = Gravity.CENTER
        b.isClickable = true
        b.isFocusable = true
        b.contentDescription = desc
        b.setPadding(UI.dp(this, 6), UI.dp(this, 4), UI.dp(this, 6), UI.dp(this, 3))
        b.background = Ic.pill(this,
            if (active) Color.argb(70, 255, 90, 44) else Color.argb(40, 255, 255, 255), 14f,
            if (active) Color.argb(200, 255, 90, 44) else Color.argb(50, 255, 255, 255))
        val iv = android.widget.ImageView(this)
        iv.setImageDrawable(Ic.get(this, icon, if (active) UI.ACCENT2 else UI.FG))
        iv.layoutParams = LinearLayout.LayoutParams(UI.dp(this, 22), UI.dp(this, 22))
        b.addView(iv)
        val tv = TextView(this)
        tv.text = label
        tv.textSize = 9.5f
        tv.maxLines = 1
        tv.setTextColor(if (active) UI.ACCENT2 else UI.FG2)
        tv.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        b.addView(tv)
        // 48dp minimum touch target (accessibility) with 4dp gaps
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UI.dp(this, 48))
        lp.setMargins(UI.dp(this, 2), 0, UI.dp(this, 2), 0)
        b.minimumWidth = UI.dp(this, 56)
        b.layoutParams = lp
        b.setOnClickListener { b.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); fn() }
        parent.addView(b)
        return b
    }

    /** Layer chips plus [Camera][Video][+ Add]; expanded adds Image / Text / Screen. */
    private fun rebuildSourceDock() {
        updateSourceStrip()
    }

    /**
     * Contextual bottom controls.
     *  - nothing selected: Add source · Camera · Record · Mic · Torch · Full canvas
     *  - source selected: Move/Resize/Rotate hint · Fit/Fill · Mute · Pause ·
     *    Hide · Lock · Forward/Backward · More (advanced sheet)
     */
    private fun refreshContextBar() {
        refreshQuickBar()
    }

    private fun buildFullCanvasExit(root: FrameLayout) {
        fullExitBtn = TextView(this)
        fullExitBtn.text = "✕  EXIT FULL CANVAS"
        fullExitBtn.contentDescription = "Exit full canvas mode"
        fullExitBtn.gravity = Gravity.CENTER
        fullExitBtn.setTextColor(Color.WHITE)
        fullExitBtn.textSize = 12f
        fullExitBtn.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        fullExitBtn.setPadding(UI.dp(this, 16), UI.dp(this, 12), UI.dp(this, 16), UI.dp(this, 12))
        fullExitBtn.minHeight = UI.dp(this, 48)
        fullExitBtn.background = Ic.pill(this, Color.argb(215, 18, 20, 27), 24f,
            Color.argb(160, 255, 255, 255))
        fullExitBtn.visibility = View.GONE
        fullExitBtn.setOnClickListener { setFullCanvas(false) }
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
        lp.topMargin = UI.dp(this, 12)
        lp.rightMargin = UI.dp(this, 12)
        lp.marginEnd = UI.dp(this, 12)
        root.addView(fullExitBtn, lp)
    }

    /**
     * Full Canvas mode: every overlay hidden, the composition fitted into the
     * whole safe area, one clearly labelled exit button. Selection gestures
     * keep working; the preview / camera / decoders are untouched (pure view
     * visibility — nothing is restarted).
     */
    private fun setFullCanvas(on: Boolean) {
        if (fullCanvas == on) return
        fullCanvas = on
        if (on) {
            setSheet(null)
            if (this::wheel.isInitialized) wheel.dismiss(animated = false)
        }
        val vis = if (on) View.GONE else View.VISIBLE
        topBar.visibility = vis
        sheet.visibility = vis
        if (this::sideRail.isInitialized) sideRail.visibility = if (on || chromeLandscape != true) View.GONE else View.VISIBLE
        quickWrap.visibility = if (on) View.GONE else View.VISIBLE
        if (on) {
            recChip.visibility = View.GONE
            statsHud.visibility = View.GONE
            hiddenPill.visibility = View.GONE
            emptyOverlay.visibility = View.GONE
        } else {
            refreshAll()
            recChip.visibility = if (ScreenCaptureService.running || liveCam?.recording == true) View.VISIBLE else View.GONE
        }
        fullExitBtn.visibility = if (on) View.VISIBLE else View.GONE
        fullExitBtn.bringToFront()
        // immersive system bars: API 30+ controller, legacy flags below (minSdk 26)
        if (Build.VERSION.SDK_INT >= 30) {
            val ctl = window.insetsController
            if (ctl != null) {
                if (on) {
                    ctl.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    ctl.hide(android.view.WindowInsets.Type.systemBars())
                } else ctl.show(android.view.WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (on)
                (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
            else View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
        // top margin of the exit button must clear the cutout
        (fullExitBtn.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.topMargin = UI.dp(this, 12) + (if (on) sysT else 0)
            fullExitBtn.layoutParams = it
        }
        applyViewportInsets()
        UI.toast(this, if (on) "Full canvas — tap ✕ to return" else "Controls restored")
    }

    private fun readSystemInsets(insets: android.view.WindowInsets) {
        if (Build.VERSION.SDK_INT >= 30) {
            val sys = insets.getInsets(android.view.WindowInsets.Type.systemBars() or
                android.view.WindowInsets.Type.displayCutout())
            sysL = sys.left; sysT = sys.top; sysR = sys.right; sysB = sys.bottom
        } else {
            @Suppress("DEPRECATION")
            run {
                sysL = insets.systemWindowInsetLeft; sysT = insets.systemWindowInsetTop
                sysR = insets.systemWindowInsetRight; sysB = insets.systemWindowInsetBottom
            }
            // API 28/29: the cutout is not part of the system-window insets when
            // the window draws into it — add it explicitly
            if (Build.VERSION.SDK_INT >= 28) {
                val c = insets.displayCutout
                if (c != null) {
                    sysL = maxOf(sysL, c.safeInsetLeft); sysT = maxOf(sysT, c.safeInsetTop)
                    sysR = maxOf(sysR, c.safeInsetRight); sysB = maxOf(sysB, c.safeInsetBottom)
                }
            }
        }
    }

    /**
     * Compute the avoid-rect for the stage from the chrome that is actually
     * visible right now and hand it to StageView, which re-fits the canvas.
     * Called after every layout pass (cheap: StageView ignores unchanged
     * values), so opening a panel, expanding the dock, selecting a source or
     * rotating the phone all keep the whole composition on screen.
     */
    private fun applyViewportInsets() {
        if (!this::stage.isInitialized || !this::sheet.isInitialized || rootFrame.height <= 0) return
        val gap = UI.dp(this, 8)
        val land = chromeLandscape == true
        // Insets are included in chrome padding exactly once, then we measure
        // the bottom edge (adding sysT to topBar.height would double-count it).
        val topPadding = UI.dp(this, 10) + sysT
        if (topBar.paddingTop != topPadding || topBar.paddingLeft != UI.dp(this, 12) + sysL ||
            topBar.paddingRight != UI.dp(this, 12) + sysR) {
            topBar.setPadding(UI.dp(this, 12) + sysL, topPadding, UI.dp(this, 12) + sysR, UI.dp(this, 10))
        }
        if (sheet.paddingBottom != sysB || sheet.paddingLeft != sysL || sheet.paddingRight != sysR) {
            sheet.setPadding(sysL, 0, sysR, sysB)
        }
        var padTop = sysT
        var padBottom = sysB
        var padRight = sysR
        if (fullCanvas) {
            // The only remaining control is also kept off the picture.
            val lp = fullExitBtn.layoutParams as FrameLayout.LayoutParams
            val wantTop = sysT + UI.dp(this, 12)
            val wantRight = sysR + UI.dp(this, 12)
            if (lp.topMargin != wantTop || lp.rightMargin != wantRight) {
                lp.topMargin = wantTop; lp.rightMargin = wantRight; lp.marginEnd = wantRight
                fullExitBtn.layoutParams = lp
            }
            padTop = maxOf(padTop, fullExitBtn.bottom)
        } else {
            if (topBar.visibility == View.VISIBLE) padTop = maxOf(padTop, topBar.bottom)
            for (v in listOf(recChip, statsHud, hiddenPill)) {
                val lp = v.layoutParams as FrameLayout.LayoutParams
                val wantTop = topBar.bottom + UI.dp(this, 2)
                if (lp.topMargin != wantTop) { lp.topMargin = wantTop; v.layoutParams = lp }
                if (v.visibility == View.VISIBLE) padTop = maxOf(padTop, wantTop + v.height)
            }
            capPanelHeight(padTop, rootFrame.height)
            if (sheet.visibility == View.VISIBLE) padBottom = maxOf(padBottom, rootFrame.height - sheet.top)
            if (land && sideRail.visibility == View.VISIBLE) {
                val lp = sideRail.layoutParams as FrameLayout.LayoutParams
                val width = railWidthPx()
                if (lp.topMargin != padTop || lp.bottomMargin != padBottom || lp.rightMargin != sysR || lp.width != width) {
                    lp.topMargin = padTop; lp.bottomMargin = padBottom
                    lp.rightMargin = sysR; lp.marginEnd = sysR; lp.width = width
                    sideRail.layoutParams = lp
                }
                padRight += width
            } else if (quickWrap.parent === rootFrame) {
                val lp = quickWrap.layoutParams as FrameLayout.LayoutParams
                val wantBottom = padBottom + gap
                val wantLeft = sysL + UI.dp(this, 12)
                val wantRight = sysR + UI.dp(this, 12)
                if (lp.bottomMargin != wantBottom || lp.leftMargin != wantLeft || lp.rightMargin != wantRight) {
                    lp.bottomMargin = wantBottom; lp.leftMargin = wantLeft; lp.rightMargin = wantRight
                    quickWrap.layoutParams = lp
                }
                if (quickWrap.visibility == View.VISIBLE) padBottom = wantBottom + quickWrap.height
            }
        }
        stage.setViewportInsets(sysL, padTop, padRight, padBottom)
        // Step 5's empty state stays inside the same safe rectangle as the
        // canvas, including the Phase 2 landscape rail and system cutout.
        val emptyLp = emptyOverlay.layoutParams as FrameLayout.LayoutParams
        if (emptyLp.leftMargin != sysL || emptyLp.topMargin != padTop ||
            emptyLp.rightMargin != padRight || emptyLp.bottomMargin != padBottom) {
            emptyLp.setMargins(sysL, padTop, padRight, padBottom)
            emptyOverlay.layoutParams = emptyLp
        }
    }

    // ================= snackbar (message + action, replaces bare toasts) =================

    private fun buildSnackBar(root: FrameLayout) {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(UI.dp(this, 16), UI.dp(this, 10), UI.dp(this, 8), UI.dp(this, 10))
        bar.background = Ic.pill(this, Color.argb(242, 18, 20, 27), 14f,
            Color.argb(110, 255, 255, 255))
        bar.visibility = View.GONE
        snackMsg = TextView(this)
        snackMsg!!.setTextColor(Color.WHITE)
        snackMsg!!.textSize = 12.5f
        snackMsg!!.maxLines = 2
        snackMsg!!.layoutParams = LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        bar.addView(snackMsg)
        snackAction = TextView(this)
        snackAction!!.setTextColor(UI.ACCENT2)
        snackAction!!.textSize = 12.5f
        snackAction!!.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        snackAction!!.setPadding(UI.dp(this, 12), UI.dp(this, 6), UI.dp(this, 12), UI.dp(this, 6))
        bar.addView(snackAction)
        snackBar = bar
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        lp.setMargins(UI.dp(this, 14), 0, UI.dp(this, 14), UI.dp(this, 208))
        root.addView(bar, lp)
    }

    /** Show a message with an optional action (e.g. "Source hidden" + UNDO). */
    private fun showSnack(msg: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        val bar = snackBar ?: return
        snackHandler.removeCallbacks(snackHide)
        snackMsg?.text = msg
        if (actionLabel != null && action != null) {
            snackAction?.visibility = View.VISIBLE
            snackAction?.text = actionLabel
            snackAction?.contentDescription = actionLabel
            snackAction?.setOnClickListener { bar.visibility = View.GONE; action() }
        } else {
            snackAction?.visibility = View.GONE
        }
        bar.visibility = View.VISIBLE
        bar.alpha = 0f
        bar.translationY = UI.dpf(this, 12f)
        bar.animate().alpha(1f).translationY(0f).setDuration(200).start()
        bar.contentDescription = msg
        snackHandler.postDelayed(snackHide, 3500L)
    }

    private fun showUndoSnack(msg: String) = showSnack(msg, "UNDO") { doUndo() }

    // ================= progress overlay (themed, cancellable) =================

    private fun buildProgOverlay(root: FrameLayout) {
        val over = FrameLayout(this)
        over.setBackgroundColor(Color.argb(150, 0, 0, 0))
        over.visibility = View.GONE
        over.isClickable = true
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(UI.dp(this, 20), UI.dp(this, 18), UI.dp(this, 20), UI.dp(this, 16))
        card.background = Ic.pill(this, Color.argb(250, 20, 23, 31), 16f,
            Color.argb(100, 255, 255, 255))
        progTitle = TextView(this)
        progTitle!!.setTextColor(Color.WHITE)
        progTitle!!.textSize = 14f
        progTitle!!.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        card.addView(progTitle)
        progMsg = TextView(this)
        progMsg!!.setTextColor(Color.argb(210, 235, 238, 245))
        progMsg!!.textSize = 12f
        val mlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        mlp.topMargin = UI.dp(this, 4)
        progMsg!!.layoutParams = mlp
        card.addView(progMsg)
        progBar = android.widget.ProgressBar(this, null,
            android.R.attr.progressBarStyleHorizontal)
        progBar!!.max = 100
        progBar!!.progressTintList = android.content.res.ColorStateList.valueOf(UI.ACCENT)
        val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        blp.topMargin = UI.dp(this, 12)
        progBar!!.layoutParams = blp
        card.addView(progBar)
        progCancel = TextView(this)
        progCancel!!.text = "Cancel"
        progCancel!!.gravity = Gravity.CENTER
        progCancel!!.setTextColor(UI.DANGER)
        progCancel!!.textSize = 13f
        progCancel!!.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        progCancel!!.setPadding(0, UI.dp(this, 10), 0, UI.dp(this, 2))
        progCancel!!.contentDescription = "Cancel"
        progCancel!!.setOnClickListener { progOnCancel?.invoke() }
        card.addView(progCancel)
        val clp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        clp.setMargins(UI.dp(this, 36), 0, UI.dp(this, 36), 0)
        over.addView(card, clp)
        progOverlay = over
        root.addView(over, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun showProgress(title: String, msg: String, determinate: Boolean,
                             onCancel: (() -> Unit)? = null) {
        progTitle?.text = title
        progMsg?.text = msg
        progBar?.isIndeterminate = !determinate
        progBar?.progress = 0
        progBar?.visibility = View.VISIBLE
        progOnCancel = onCancel
        progCancel?.visibility = if (onCancel != null) View.VISIBLE else View.GONE
        progOverlay?.visibility = View.VISIBLE
    }

    private fun updateProgress(pct: Int, msg: String) {
        progBar?.progress = pct.coerceIn(0, 100)
        progMsg?.text = msg
    }

    private fun dismissProgress() {
        progOverlay?.visibility = View.GONE
        progOnCancel = null
    }

    // ================= radial menu entry points =================

    /** Open the root ring, blooming from the Studio button. */
    private fun openRootWheel() {
        setSheet(null)
        val loc = IntArray(2); val rootLoc = IntArray(2)
        if (this::studioBtn.isInitialized) {
            studioBtn.getLocationOnScreen(loc)
            rootFrame.getLocationOnScreen(rootLoc)
            val ax = (loc[0] + studioBtn.width / 2f) - rootLoc[0]
            val ay = (loc[1] + studioBtn.height / 2f) - rootLoc[1]
            wheel.show(RadialMenus.root(this), ax, ay - UI.dpf(this, 28f))
        } else {
            wheel.show(RadialMenus.root(this), -1f, -1f)
        }
    }

    /** Open a specific ring at a point (used by canvas long-press and ◉). */
    private fun openWheelLevel(level: RadialMenuView.Level, ax: Float, ay: Float) {
        setSheet(null)
        wheel.show(level, ax, ay)
    }

    // ================= sheet (only where a ring is the wrong tool) =================

    private fun setSheet(tab: String?) {
        if (tab != null && fullCanvas) setFullCanvas(false)
        sheetTab = tab
        val sv = panelScroll
        val divider = panelDivider
        if (tab == null) {
            sv.visibility = View.GONE
            divider.visibility = View.GONE
            refreshTabBar()
            updateSourceStrip()
            sheet.post { updateStageInsets() }
            return
        }
        val keepY = sv.scrollY
        panelContent.removeAllViews()
        // every panel is a dismissible overlay: title + ✕ (back also closes it)
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(UI.dp(this, 14), UI.dp(this, 6), UI.dp(this, 6), 0)
        val ht = TextView(this)
        ht.text = when (tab) { "sources" -> "Layers"; "mixer" -> "Audio mixer"; "export" -> "Export"; else -> tab }
        ht.setTextColor(UI.FG)
        ht.textSize = 13f
        ht.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        ht.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        head.addView(ht)
        val hx = IconBtn(this)
        hx.layoutParams = IconBtn.sized(this, 48)
        hx.setIcon(R.drawable.ic_close, UI.FG, "Close panel")
        hx.setOnClickListener { setSheet(null) }
        head.addView(hx)
        panelContent.addView(head)
        when (tab) {
            "sources" -> buildSourcesPanel()
            "mixer" -> buildMixerPanel()
            "export" -> buildExportPanel()
        }
        sv.visibility = View.VISIBLE
        divider.visibility = View.VISIBLE
        refreshTabBar()
        updateSourceStrip()
        if (keepY > 0) sv.post { sv.scrollTo(0, keepY) }
        panelContent.alpha = 0f
        panelContent.translationY = UI.dpf(this, 22f)
        panelContent.animate().alpha(1f).translationY(0f)
            .setDuration(220).setInterpolator(OvershootInterpolator(1.15f)).start()
        sheet.post { updateStageInsets() }
    }

    // ================= panel: SOURCES dock =================

    private fun buildSourcesPanel() {
        section("LAYERS  ·  tap to select  ·  eye / mute  ·  drag ⠿ to reorder")
        if (proj!!.layers.isNotEmpty()) {
            val head = LinearLayout(this)
            head.orientation = LinearLayout.HORIZONTAL
            head.gravity = Gravity.CENTER_VERTICAL
            head.setPadding(UI.dp(this, 12), 0, UI.dp(this, 12), UI.dp(this, 6))
            val prev = TextView(this)
            prev.text = "‹ Prev"
            prev.gravity = Gravity.CENTER
            prev.setTextColor(UI.FG)
            prev.textSize = 11f
            prev.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            prev.setPadding(UI.dp(this, 14), 0, UI.dp(this, 14), 0)
            val pbg = GradientDrawable()
            pbg.cornerRadius = UI.dpf(this, 16f)
            pbg.setColor(Color.argb(90, 38, 42, 52))
            pbg.setStroke(UI.dp(this, 1), Color.argb(50, 255, 255, 255))
            prev.background = pbg
            prev.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UI.dp(this, 32))
            prev.contentDescription = "Select previous layer"
            prev.isClickable = true
            prev.setOnClickListener { stepSelection(-1) }
            head.addView(prev)
            val count = TextView(this)
            val n = proj!!.layers.size
            count.text = "$n layer" + (if (n == 1) "" else "s") + "  ·  top = front"
            count.setTextColor(UI.FG2)
            count.textSize = 11f
            count.gravity = Gravity.CENTER
            count.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            head.addView(count)
            val next = TextView(this)
            next.text = "Next ›"
            next.gravity = Gravity.CENTER
            next.setTextColor(UI.FG)
            next.textSize = 11f
            next.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            next.setPadding(UI.dp(this, 14), 0, UI.dp(this, 14), 0)
            val nbg = GradientDrawable()
            nbg.cornerRadius = UI.dpf(this, 16f)
            nbg.setColor(Color.argb(90, 38, 42, 52))
            nbg.setStroke(UI.dp(this, 1), Color.argb(50, 255, 255, 255))
            next.background = nbg
            next.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UI.dp(this, 32))
            next.contentDescription = "Select next layer"
            next.isClickable = true
            next.setOnClickListener { stepSelection(1) }
            head.addView(next)
            panelContent.addView(head)
        }
        (dockContainer.parent as? ViewGroup)?.removeView(dockContainer)
        dockContainer.setPadding(UI.dp(this, 10), UI.dp(this, 2), UI.dp(this, 10), UI.dp(this, 10))
        panelContent.addView(dockContainer)
        dock.rebuild()
        if (proj!!.layers.isEmpty()) {
            val b = UI.btn(this, "＋  Add your first source", accent = true)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 44))
            lp.setMargins(UI.dp(this, 14), UI.dp(this, 8), UI.dp(this, 14), UI.dp(this, 12))
            b.layoutParams = lp
            b.setOnClickListener { openWheelLevel(RadialMenus.add(this), -1f, -1f) }
            panelContent.addView(b)
        }
    }

    /** cycle the selection through the layer stack (‹ › steppers) */
    private fun stepSelection(dir: Int) {
        val p = proj ?: return
        if (p.layers.isEmpty()) return
        val i = p.layers.indexOfFirst { it.id == selectedId }
        val next = ((if (i < 0) 0 else i + dir) % p.layers.size + p.layers.size) % p.layers.size
        select(p.layers[next].id)
    }

    // ================= panel: MIXER (sliders need a sheet) =================

    private fun buildMixerPanel() {
        val audio = proj!!.layers.filter { it.isClip() }
        section("MIXER — per-source level · mute · solo")
        if (audio.isEmpty()) {
            val t = UI.label(this, "No audio sources yet — add a video, screen recording " +
                "or record a camera take.", dim = true, size = 12f)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(UI.dp(this, 14), UI.dp(this, 6), UI.dp(this, 14), UI.dp(this, 14))
            t.layoutParams = lp
            panelContent.addView(t)
            return
        }
        for (l in audio.reversed()) {
            val head = LinearLayout(this)
            head.orientation = LinearLayout.HORIZONTAL
            head.gravity = Gravity.CENTER_VERTICAL
            head.setPadding(UI.dp(this, 14), UI.dp(this, 6), UI.dp(this, 12), 0)
            val ic = android.widget.ImageView(this)
            ic.setImageDrawable(Ic.get(this, Ic.typeIcon(l.type), UI.ACCENT2))
            val ilp = LinearLayout.LayoutParams(UI.dp(this, 16), UI.dp(this, 16))
            ilp.setMargins(0, 0, UI.dp(this, 8), 0)
            ic.layoutParams = ilp
            head.addView(ic)
            val nm = TextView(this)
            nm.text = l.name.ifBlank { l.type.label }
            nm.setTextColor(Color.WHITE)
            nm.textSize = 12.5f
            nm.maxLines = 1
            head.addView(nm, LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val effMuted = ctrl.effectiveMuted(l)
            val mb = IconBtn(this)
            mb.layoutParams = IconBtn.sized(this, 36)
            mb.setIcon(if (effMuted) R.drawable.ic_volume_off else R.drawable.ic_volume,
                if (effMuted) UI.DANGER else UI.FG,
                if (l.muted) "Unmute ${l.name}" else "Mute ${l.name}")
            mb.setOnClickListener {
                ctrl.toggleMuted(l.id)
                setSheet("mixer")
                showUndoSnack(if (l.muted) "${l.name} muted" else "${l.name} unmuted")
            }
            head.addView(mb)
            val sb2 = IconBtn(this)
            sb2.layoutParams = IconBtn.sized(this, 36)
            sb2.setIcon(R.drawable.ic_star, if (l.solo) UI.ACCENT2 else UI.FG,
                if (l.solo) "Unsolo ${l.name}" else "Solo ${l.name}")
            sb2.setOnClickListener {
                ctrl.toggleSolo(l.id)
                setSheet("mixer")
                showUndoSnack(if (l.solo) "${l.name} soloed — others silent" else "${l.name} unsoloed")
            }
            head.addView(sb2)
            val lb = IconBtn(this)
            lb.layoutParams = IconBtn.sized(this, 36)
            lb.setIcon(R.drawable.ic_loop, if (l.loop) UI.ACCENT2 else UI.FG,
                if (l.loop) "Loop off for ${l.name}" else "Loop on for ${l.name}")
            lb.setOnClickListener { ctrl.toggleLoop(l.id); setSheet("mixer") }
            head.addView(lb)
            panelContent.addView(head)
            // explain WHY a channel is silent — the #1 mixer confusion
            if (effMuted && !l.muted) {
                val why = UI.label(this, "Silent because another source is soloed.",
                    dim = true, size = 10f)
                val wlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                wlp.setMargins(UI.dp(this, 38), 0, UI.dp(this, 14), 0)
                why.layoutParams = wlp
                panelContent.addView(why)
            }
            panelContent.addView(sliderRow("Level  ${(l.volume * 100).toInt()}%",
                (l.volume * 100).toInt()) { v ->
                pushUndoLight(); engine.setVolume(l, v / 100f); markDirty()
            })
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
        t.textSize = 10f
        t.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        t.letterSpacing = 0.06f
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(UI.dp(this, 14), UI.dp(this, 12), UI.dp(this, 14), UI.dp(this, 6))
        t.layoutParams = lp
        panelContent.addView(t)
        val line = View(this)
        line.setBackgroundColor(Color.argb(35, 255, 160, 44))
        val llp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 1))
        llp.setMargins(UI.dp(this, 14), 0, UI.dp(this, 14), UI.dp(this, 4))
        line.layoutParams = llp
        panelContent.addView(line)
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

    private fun setBgColor(c: Int) {
        pushUndo()
        proj!!.bgColor = c
        markDirty(); stage.refresh()
    }

    /**
     * Aspect picker (replaces the blind one-tap cycle that also rotated the
     * phone without warning). Each option explains itself; the change itself
     * is undoable.
     */
    private fun showAspectPicker() {
        if (exportRunning) { UI.toast(this, "Stop the export first"); return }
        val cur = proj!!.aspect
        val labels = Aspect.entries.map {
            val hint = when (it) {
                Aspect.R169 -> "YouTube · landscape"
                Aspect.R916 -> "Reels · Shorts · TikTok"
                Aspect.R11 -> "Square posts"
            }
            "${it.code}  —  $hint" + if (it == cur) "  ✓" else ""
        }
        AlertDialog.Builder(this)
            .setTitle("Canvas aspect ratio")
            .setItems(labels.toTypedArray()) { _, which ->
                val next = Aspect.entries[which]
                if (next == cur) return@setItems
                aspectChip.animate().cancel()
                aspectChip.animate().scaleX(0.8f).scaleY(0.8f).setDuration(80).withEndAction {
                    changeAspect(next)
                    aspectChip.animate().scaleX(1f).scaleY(1f).setDuration(260)
                        .setInterpolator(OvershootInterpolator(2f)).start()
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun changeAspect(a: Aspect) {
        val p = proj!!
        if (p.aspect == a) return
        if (exportRunning) { UI.toast(this, "Stop the export first"); return }
        pushUndo()
        p.aspect = a
        applyOrientationFor(a)
        updateAspectChip()
        markDirty()
        stage.post { syncPreviewTarget() }
        stage.refresh()
        showUndoSnack("Canvas ${a.code} — every source keeps its own frame ratio")
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
        // Playability-first labels: H.264 plays everywhere; HEVC/WebM are
        // smaller but a real share of players and apps cannot decode them.
        val codecNames = avail.map {
            when (it) {
                Exporter.Codec.H264 -> "${it.label}  (plays everywhere)"
                Exporter.Codec.H265 -> "${it.label}  (smaller · some apps can't play it)"
                else -> "${it.label}  (some apps can't play it)"
            }
        }
        // H.264 is the default for one reason: it plays EVERYWHERE. HEVC used
        // to be the default and produced valid files that simply would not play
        // in several galleries, chat apps and old players.
        val prefs = editorPrefs()
        var codecIdx = avail.indexOfFirst { it.name == prefs.getString(PREF_EXP_CODEC, "H264") }
            .let { if (it >= 0) it else avail.indexOfFirst { c -> c == Exporter.Codec.H264 } }
            .coerceAtLeast(0)
        val qualityNames = EncoderConfig.Quality.entries.map { "${it.label} — ${it.hint}" }
        val resNames = arrayOf("Small (~480p)", "Medium (~720p)", "Large (~1080p)")
        val fpsNames = arrayOf("24 fps", "30 fps", "60 fps")
        var quality = prefs.getInt(PREF_EXP_QUALITY, EncoderConfig.Quality.BALANCED.ordinal)
            .coerceIn(0, qualityNames.size - 1)
        var maxDim = prefs.getInt(PREF_EXP_MAXDIM, 720).let {
            if (it <= 480) 480 else if (it >= 1080) 1080 else 720
        }
        var fps = prefs.getInt(PREF_EXP_FPS, 30).let { if (it == 24) 24 else if (it == 60) 60 else 30 }

        // one-tap repeat of the last export (same settings, no re-picking)
        if (prefs.getBoolean(PREF_HAD_EXPORT, false)) {
            val lastCodec = prefs.getString(PREF_EXP_CODEC, "H264") ?: "H264"
            val repeat = UI.btn(this, "↻  Export again — $lastCodec · ${maxDim}p · ${fps}fps",
                accent = false, small = true)
            val rlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            rlp.setMargins(UI.dp(this, 12), UI.dp(this, 2), UI.dp(this, 12), UI.dp(this, 2))
            repeat.layoutParams = rlp
            repeat.contentDescription = "Export again with last settings"
            repeat.setOnClickListener {
                saveExportPrefs(avail[codecIdx].name, quality, maxDim, fps)
                setSheet(null)
                if (!warnLiveBeforeExport()) runExport(quality, maxDim, fps, avail[codecIdx])
            }
            panelContent.addView(repeat)
        }

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

        // forward declaration so every picker can refresh the size estimate
        var estimate: TextView? = null
        fun refreshEstimate() {
            val (w, h) = Exporter.chooseSize(p.aspect.canvasW, p.aspect.canvasH, maxDim)
            val mime = avail[codecIdx].mime
            val q = EncoderConfig.Quality.of(quality)
            val perMin = EncoderConfig.megabytesPerMinute(q, w, h, fps, mime)
            val total = EncoderConfig.predictedBytes(q, w, h, fps, mime, p.durationMs())
            estimate?.text =
                "≈ ${UI.niceBytes(total)} for this project  ·  about " +
                "${String.format(java.util.Locale.US, "%.1f", perMin)} MB per minute\n" +
                "${w}×${h} @ ${fps}fps · ${avail[codecIdx].label} · long-GOP VBR (OBS-style)"
        }

        valueRow("Format / codec", codecNames[codecIdx], codecNames) { codecIdx = it; refreshEstimate() }
        val resInit = when (maxDim) { 480 -> resNames[0]; 1080 -> resNames[2]; else -> resNames[1] }
        valueRow("Resolution", resInit, resNames.toList()) {
            maxDim = intArrayOf(480, 720, 1080)[it]; refreshEstimate()
        }
        valueRow("Quality", qualityNames[quality], qualityNames) { quality = it; refreshEstimate() }
        valueRow("Frame rate", when (fps) { 24 -> fpsNames[0]; 60 -> fpsNames[2]; else -> fpsNames[1] }, fpsNames.toList()) {
            fps = intArrayOf(24, 30, 60)[it]; refreshEstimate()
        }

        val est = UI.label(this, "", dim = false, size = 12f)
        val elp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        elp.setMargins(UI.dp(this, 14), UI.dp(this, 10), UI.dp(this, 14), UI.dp(this, 2))
        est.layoutParams = elp
        panelContent.addView(est)
        estimate = est
        refreshEstimate()

        val info = UI.label(this,
            "H.264/H.265 → MP4 · VP8/VP9 → WebM. What you see is exactly what exports.\n" +
            "Saved to Movies/AhmedReactionStudio (Gallery).\n" +
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
            saveExportPrefs(avail[codecIdx].name, quality, maxDim, fps)
            setSheet(null)
            if (!warnLiveBeforeExport()) runExport(quality, maxDim, fps, avail[codecIdx])
        }
        panelContent.addView(go)
    }

    /** sticky export settings: the next visit (and "export again") reuses them */
    private fun saveExportPrefs(codecName: String, quality: Int, maxDim: Int, fps: Int) {
        editorPrefs().edit()
            .putString(PREF_EXP_CODEC, codecName)
            .putInt(PREF_EXP_QUALITY, quality)
            .putInt(PREF_EXP_MAXDIM, maxDim)
            .putInt(PREF_EXP_FPS, fps)
            .putBoolean(PREF_HAD_EXPORT, true)
            .apply()
    }

    // ================= Quick Control Bar =================

    private fun refreshQuickBar() {
        if (!this::quickBar.isInitialized) return
        quickBar.animate().cancel()
        if (fullCanvas) {
            quickBar.visibility = View.GONE
            quickWrap.visibility = View.GONE
            return
        }
        quickBar.removeAllViews()
        val l = selectedId?.let { proj!!.layerById(it) }
        if (l == null) {
            dockBtn(quickBar, R.drawable.ic_add, "Add source", "Add a source") {
                openWheelLevel(RadialMenus.add(this), -1f, -1f)
            }
            val live = proj!!.layers.firstOrNull { it.isLive() }
            dockBtn(quickBar, R.drawable.ic_camera, "Camera",
                if (live != null) "Select the live camera" else "Add the live camera", active = live != null) {
                if (live != null) select(live.id) else addLiveCamera()
            }
            dockBtn(quickBar, if (recording) R.drawable.ic_stop else R.drawable.ic_video,
                if (recording) "Stop" else "Record",
                if (recording) "Stop and save the recording" else "Record the composition",
                active = recording) { recordButtonTap() }
            val micOk = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            dockBtn(quickBar, if (micOk) R.drawable.ic_volume else R.drawable.ic_volume_off, "Mic",
                if (micOk) "Microphone ready for recording" else "Grant microphone permission",
                active = micOk && recording) {
                if (!micOk) requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_RECORD_PERM)
                else UI.toast(this, "Microphone is mixed into every recording")
            }
            val lit = liveCam?.isTorchLitForFront() == true || liveCam?.isTorchLitForBack() == true || screenLight
            dockBtn(quickBar, R.drawable.ic_flash, "Torch", "Camera light", active = lit) {
                if (live != null) openFlashRing(live) else toggleScreenLight()
            }
            dockBtn(quickBar, R.drawable.ic_fullscreen, "Full canvas", "Show only the canvas") { setFullCanvas(true) }
            quickBar.alpha = 1f
            quickBar.translationY = 0f
            quickBar.visibility = View.VISIBLE
            quickWrap.visibility = View.VISIBLE
            quickWrap.post(insetsSync)
            return
        }
        quickBar.animate().cancel()
        quickWrap.visibility = View.VISIBLE
        val wasGone = quickBar.visibility != View.VISIBLE
        if (!wasGone) { quickBar.alpha = 1f; quickBar.translationY = 0f }

        // selected source name pill — compact, icon + short label
        val pill = LinearLayout(this)
        pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.setPadding(UI.dp(this, 11), 0, UI.dp(this, 11), 0)
        val g = GradientDrawable()
        g.cornerRadius = UI.dpf(this, 16f)
        g.setColor(Color.argb(55, 255, 255, 255))
        g.setStroke(UI.dp(this, 1), Color.argb(70, 255, 255, 255))
        pill.background = g
        pill.contentDescription = "Selected: ${l.name}. Tap for all settings"
        pill.isFocusable = true
        pill.setOnClickListener { openAdvancedSheet(l) }
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
        nm.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        nm.maxLines = 1
        nm.ellipsize = android.text.TextUtils.TruncateAt.END
        nm.maxWidth = UI.dp(this, 96)
        pill.addView(nm)
        val plp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UI.dp(this, 36))
        plp.setMargins(0, 0, UI.dp(this, 8), 0)
        pill.layoutParams = plp
        quickBar.addView(pill)

        // divider
        val div = View(this)
        div.setBackgroundColor(Color.argb(70, 255, 255, 255))
        val dlp = LinearLayout.LayoutParams(UI.dp(this, 1), UI.dp(this, 22))
        dlp.setMargins(0, 0, UI.dp(this, 8), 0)
        div.layoutParams = dlp
        quickBar.addView(div)

        fun bar(resId: Int, tint: Int, desc: String, fn: () -> Unit): IconBtn {
            val b = IconBtn(this)
            b.layoutParams = IconBtn.sized(this, 48)
            // At least the Step 5 target size, now 48dp for the shared toolbar.
            b.setIcon(resId, tint, desc)
            val lp = b.layoutParams as LinearLayout.LayoutParams
            lp.setMargins(UI.dp(this, 1), 0, UI.dp(this, 1), 0)
            b.layoutParams = lp
            b.setOnClickListener { fn() }
            quickBar.addView(b)
            return b
        }

        bar(if (l.visible) R.drawable.ic_eye else R.drawable.ic_eye_off,
            if (l.visible) UI.FG else Color.argb(120, 255, 255, 255),
            if (l.visible) "Hide ${l.name}" else "Show ${l.name}") {
            ctrl.toggleVisible(l.id); showHideFeedback(l)
        }
        if (l.isClip()) {
            val effMuted = ctrl.effectiveMuted(l)
            bar(if (effMuted) R.drawable.ic_volume_off else R.drawable.ic_volume,
                if (effMuted) UI.DANGER else UI.FG,
                if (effMuted) "Unmute ${l.name}" else "Mute ${l.name}") {
                ctrl.toggleMuted(l.id)
                showUndoSnack(if (l.muted) "${l.name} muted" else "${l.name} unmuted")
            }
            bar(if (l.playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (l.playing) UI.FG else UI.ACCENT2,
                if (l.playing) "Pause ${l.name}" else "Play ${l.name}") {
                engine.toggleLayerPlay(l); markDirty(); refreshAll()
            }
        } else if (l.isLive()) {
            val rec = liveCam?.recording == true
            bar(if (rec) R.drawable.ic_stop else R.drawable.ic_camera,
                if (rec) UI.DANGER else UI.OK,
                if (rec) "Stop camera take" else "Record camera take") { toggleLiveCameraRecord(l) }
            bar(R.drawable.ic_switch, UI.FG, "Switch camera") { switchCameraFacing(l) }
            bar(R.drawable.ic_loop, if (l.mirror) UI.ACCENT2 else UI.FG,
                if (l.mirror) "Mirror off" else "Mirror on") { toggleCameraMirror(l) }
            val lit = liveCam?.isTorchLitForFront() == true ||
                liveCam?.isTorchLitForBack() == true || screenLight
            bar(R.drawable.ic_flash, if (lit) UI.ACCENT2 else UI.FG, "Camera light") { openFlashRing(l) }
        }
        bar(if (l.locked) R.drawable.ic_lock else R.drawable.ic_lock_open,
            if (l.locked) UI.ACCENT2 else UI.FG,
            if (l.locked) "Unlock ${l.name}" else "Lock ${l.name}") {
            ctrl.toggleLocked(l.id)
            showUndoSnack(if (l.locked) "${l.name} locked" else "${l.name} unlocked")
        }
        if (!l.isText()) {
            bar(if (l.fit == Layer.FIT_FIT) R.drawable.ic_fit else R.drawable.ic_fill,
                if (l.fit == Layer.FIT_FIT) UI.ACCENT2 else UI.FG,
                if (l.fit == Layer.FIT_FIT) "Fill: crop to box" else "Fit: whole frame") {
                ctrl.toggleFit(l.id)
            }
        }
        wheelBtn = bar(R.drawable.ic_wheel, UI.ACCENT2, "More actions for ${l.name}") {
            val wb = wheelBtn ?: return@bar
            val cur = selectedId?.let { proj!!.layerById(it) } ?: return@bar
            openWheel(wb, cur)
        }
        bar(R.drawable.ic_more, UI.FG, "All settings for ${l.name}") {
            val cur = selectedId?.let { proj!!.layerById(it) } ?: return@bar
            openAdvancedSheet(cur)
        }

        bar(R.drawable.ic_drag, UI.FG, "Centre ${l.name}; drag on canvas to move freely") {
            if (l.locked) { onLockedTap(l); return@bar }
            ctrl.center(l.id); showUndoSnack("${l.name} centred — drag on the canvas to move")
        }
        bar(R.drawable.ic_corner_br, UI.FG, "Reset size of ${l.name}; drag corner handles to resize") {
            if (l.locked) { onLockedTap(l); return@bar }
            pushUndo()
            val cx = l.cx; val cy = l.cy
            if (l.isText()) { l.wN = 0.86f; l.hN = 0.28f }
            else LayerFit.pip(l, proj!!.aspect.canvasW, proj!!.aspect.canvasH, anchor = "br")
            l.cx = cx; l.cy = cy; LayerFit.clampInside(l)
            markDirty(); stage.refresh()
            showUndoSnack("${l.name} size reset — corner handles resize")
        }
        bar(R.drawable.ic_reset, UI.FG, "Reset rotation of ${l.name}; drag the round knob to rotate") {
            if (l.locked) { onLockedTap(l); return@bar }
            pushUndo(); l.rotDeg = 0f; markDirty(); stage.refresh()
            showUndoSnack("${l.name} rotation reset — the knob above the frame rotates")
        }
        bar(R.drawable.ic_up, UI.FG, "Bring ${l.name} forward") { ctrl.moveZ(l.id, "up") }
        bar(R.drawable.ic_down, UI.FG, "Send ${l.name} backward") { ctrl.moveZ(l.id, "down") }
        bar(R.drawable.ic_fullscreen, UI.FG, "Full canvas: hide all controls") { setFullCanvas(true) }

        if (wasGone) {
            quickBar.alpha = 0f
            quickBar.translationY = UI.dpf(this, 16f)
            quickBar.visibility = View.VISIBLE
            quickBar.animate().alpha(1f).translationY(0f).setDuration(220)
                .setInterpolator(OvershootInterpolator(1.25f)).start()
            quickWrap.post { updateStageInsets() }
        } else {
            if (quickBar.visibility != View.VISIBLE) quickBar.visibility = View.VISIBLE
            quickWrap.post { updateStageInsets() }
        }
    }

    private fun showHideFeedback(l: Layer) {
        // hiding is visual only (audio keeps playing) — say so, with an Undo
        if (!l.visible) showUndoSnack("${l.name} hidden — audio still plays")
        else showUndoSnack("${l.name} visible")
    }

    private fun quickToggle(l: Layer, what: String) {
        when (what) {
            "vis" -> { ctrl.toggleVisible(l.id); showHideFeedback(l) }
            "mute" -> {
                ctrl.toggleMuted(l.id)
                showUndoSnack(if (l.muted) "${l.name} muted" else "${l.name} unmuted")
            }
        }
    }

    // ================= Radial wheel =================

    /** ◉ on the quick bar: jump straight into this source's ring (depth 1). */
    private fun openWheel(anchor: View, l: Layer) {
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val rootLoc = IntArray(2)
        rootFrame.getLocationOnScreen(rootLoc)
        val ax = (loc[0] + anchor.width / 2f) - rootLoc[0]
        val ay = (loc[1] + anchor.height / 2f) - rootLoc[1]
        openWheelLevel(RadialMenus.source(this, l.id), ax, ay)
    }

    /** destructive operations are locked while an export runs (plan §7) */
    private fun guardRecording(f: () -> Unit) {
        if (exportRunning) { UI.toast(this, "Locked while exporting"); return }
        f()
    }

    // ================= Advanced sheet (long press / ⋮) =================

    /** duplicate with the single rule every surface enforces: no live-camera clones */
    private fun duplicateLayer(l: Layer) {
        if (l.isLive()) {
            UI.toast(this, "The live camera can't be duplicated — record a take first")
            return
        }
        val nid = ctrl.duplicate(l.id)
        selectedId = nid
        refreshAll()
        showUndoSnack("Duplicated ${l.name}")
    }

    private fun openAdvancedSheet(l: Layer) {
        if (fullCanvas) setFullCanvas(false)
        setSheet(null)
        selectedId = l.id
        refreshContextBar(); rebuildDock(); rebuildSourceDock(); stage.refresh()
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
        if (!l.isText()) {
            panelButtonRow(panelContent,
                (if (l.fit == Layer.FIT_FIT) "Fit: whole frame" else "Fill: crop to box") to {
                    ctrl.toggleFit(l.id); openAdvancedSheet(l)
                },
                (if (l.visible) "Hide" else "Show") to {
                    ctrl.toggleVisible(l.id); showHideFeedback(l); openAdvancedSheet(l)
                })
        } else {
            panelButtonRow(panelContent,
                (if (l.visible) "Hide" else "Show") to {
                    ctrl.toggleVisible(l.id); showHideFeedback(l); openAdvancedSheet(l)
                },
                (if (l.locked) "Unlock" else "Lock") to {
                    ctrl.toggleLocked(l.id); openAdvancedSheet(l)
                })
        }
        if (!l.isText()) {
            panelButtonRow(panelContent,
                (if (l.locked) "Unlock" else "Lock") to {
                    ctrl.toggleLocked(l.id); openAdvancedSheet(l)
                })
        }
        panelContent.addView(sliderRow("Opacity  ${(l.opacity * 100).toInt()}%",
            (l.opacity * 100).toInt()) { v ->
            pushUndoLight(); l.opacity = v / 100f; markDirty(); stage.refresh()
        })

        if (l.isClip()) {
            section("PLAYBACK & AUDIO")
            panelButtonRow(panelContent,
                (if (l.playing) "Pause source" else "Play source") to {
                    engine.toggleLayerPlay(l); markDirty(); openAdvancedSheet(l)
                },
                (if (l.loop) "Loop: on" else "Loop: off") to {
                    ctrl.toggleLoop(l.id); openAdvancedSheet(l)
                })
            panelButtonRow(panelContent,
                (if (l.muted) "Unmute" else "Mute") to {
                    ctrl.toggleMuted(l.id); openAdvancedSheet(l)
                },
                (if (l.solo) "Solo: on" else "Solo: off") to {
                    ctrl.toggleSolo(l.id); openAdvancedSheet(l)
                })
            panelContent.addView(sliderRow("Volume  ${(l.volume * 100).toInt()}%",
                (l.volume * 100).toInt()) { v ->
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

        if (l.isText()) {
            section("TEXT")
            panelButtonRow(panelContent,
                "Edit text" to { editTextLayer(l) },
                "Change color" to { cycleTextColor(l); openAdvancedSheet(l) })
            panelContent.addView(sliderRow("Text size",
                (l.fontSizeN * 1000).toInt().coerceIn(10, 300)) { v ->
                pushUndoLight(); l.fontSizeN = v / 1000f; markDirty(); stage.refresh()
            })
            panelButtonRow(panelContent,
                (if (l.shadow) "Shadow: on" else "Shadow: off") to {
                    pushUndo(); l.shadow = !l.shadow; markDirty(); stage.refresh()
                    openAdvancedSheet(l)
                })
        }

        section("ARRANGE — z-order (top of the list = front)")
        panelButtonRow(panelContent,
            "Bring forward" to { ctrl.moveZ(l.id, "up"); openAdvancedSheet(l) },
            "Send backward" to { ctrl.moveZ(l.id, "down"); openAdvancedSheet(l) })
        panelButtonRow(panelContent,
            "To front" to { ctrl.moveZ(l.id, "front"); openAdvancedSheet(l) },
            "To back" to { ctrl.moveZ(l.id, "back"); openAdvancedSheet(l) })
        panelButtonRow(panelContent,
            "Top-left" to { ctrl.anchor(l.id, "tl") },
            "Top" to { ctrl.anchor(l.id, "tc") },
            "Top-right" to { ctrl.anchor(l.id, "tr") })
        panelButtonRow(panelContent,
            "Bottom-left" to { ctrl.anchor(l.id, "bl") },
            "Bottom" to { ctrl.anchor(l.id, "bc") },
            "Bottom-right" to { ctrl.anchor(l.id, "br") })
        panelButtonRow(panelContent,
            "Center" to { ctrl.center(l.id) },
            "Reset position" to { ctrl.resetGeometry(l.id) })
        if (!l.isText()) {
            panelButtonRow(panelContent,
                "Set as background" to { ctrl.setAsCanvasBackground(l.id) },
                "Duplicate" to { duplicateLayer(l) })
        } else {
            panelButtonRow(panelContent,
                "Duplicate" to { duplicateLayer(l) })
        }

        section("DANGER")
        val del = UI.btn(this, "Delete source", accent = false, small = false)
        del.setTextColor(UI.DANGER)
        del.contentDescription = "Delete ${l.name}"
        val dlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 44))
        dlp.setMargins(UI.dp(this, 12), UI.dp(this, 2), UI.dp(this, 12), UI.dp(this, 14))
        del.layoutParams = dlp
        del.setOnClickListener {
            guardRecording {
                val nm = l.name
                ctrl.delete(l.id); selectedId = null; engine.evict(l.id)
                setSheet(null); refreshAll()
                showUndoSnack("Deleted $nm")
            }
        }
        panelContent.addView(del)

        val sv = panelScroll   // lives in the sheet (portrait) or the side rail (landscape)
        sv.visibility = View.VISIBLE
        sheet.post(insetsSync)   // the canvas shrinks around the sheet, it is never covered
        panelContent.alpha = 0f
        panelContent.translationY = UI.dpf(this, 26f)
        panelContent.animate().alpha(1f).translationY(0f)
            .setDuration(230).setInterpolator(OvershootInterpolator(1.2f)).start()
        sheetTab = "adv"
        panelDivider.visibility = View.VISIBLE
        refreshTabBar()
        rebuildSourceDock()
    }

    // ================= empty state =================

    private fun updateEmptyState() {
        emptyOverlay.removeAllViews()
        if (fullCanvas || proj!!.layers.isNotEmpty()) { emptyOverlay.visibility = View.GONE; return }
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
            "This becomes your main canvas.\nAnything you add later is a layer on top.",
            dim = true, size = 12f)
        sub.gravity = Gravity.CENTER
        sub.setTextColor(Color.argb(220, 235, 238, 245))
        val slp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        slp.setMargins(0, UI.dp(this, 6), 0, UI.dp(this, 12))
        sub.layoutParams = slp
        box.addView(sub)

        fun big(label: String, icon: Int, accent: Boolean = true, fn: () -> Unit) {
            val b = UI.btn(this, label, accent = accent)
            b.setCompoundDrawablesRelativeWithIntrinsicBounds(
                Ic.get(this, icon, if (accent) Color.WHITE else UI.FG), null, null, null)
            b.compoundDrawablePadding = UI.dp(this, 8)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 46))
            lp.setMargins(0, UI.dp(this, 4), 0, UI.dp(this, 4))
            b.layoutParams = lp
            b.setOnClickListener { fn() }
            box.addView(b)
        }
        big("Camera — live on canvas", R.drawable.ic_camera) { addLiveCamera() }
        big("Local video", R.drawable.ic_video) { pickMedia(video = true) }
        big("Record screen", R.drawable.ic_screen) { startScreenCapture() }
        big("Image", R.drawable.ic_image) { pickMedia(video = false) }
        big("Open all controls", R.drawable.ic_wheel, accent = false) { openRootWheel() }

        // scrollable: 5 buttons + header must fit short landscape screens too
        val scroller = ScrollView(this)
        scroller.isVerticalScrollBarEnabled = false
        scroller.addView(box)
        emptyOverlay.addView(scroller, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER))
    }

    // ================= engine ticks =================

    private var lastUiTickMs = 0L
    private var lastHudMs = 0L

    private fun onTick(ms: Long) {
        if (!this::playBtn.isInitialized || !this::statsHud.isInitialized) return
        // The master clock ticks at ~60 Hz; the transport only needs ~20 Hz.
        // Throttling keeps TextView.setText / SeekBar progress off the main
        // thread's critical path so it cannot steal time from the stage draw.
        val now = android.os.SystemClock.elapsedRealtime()
        if (!scrubbing && now - lastUiTickMs >= 50L) {
            lastUiTickMs = now
            timeLabel.text = UI.fmtTime(ms)
            val max = seek.max
            if (max > 0) seek.progress = ms.toInt().coerceAtMost(max)
        }
        // HUD refreshes at ~2 Hz — it reports the engine's own 500 ms window
        if (now - lastHudMs >= 500L) {
            lastHudMs = now
            val show = !fullCanvas && editorPrefs().getBoolean(PREF_STATS_HUD, true) &&
                (engine.anyPlaying() || recording)
            if (show) {
                val r = recorder
                statsHud.text = if (recording && r != null) engine.stats() + "\n" + r.stats() else engine.stats()
                statsHud.visibility = View.VISIBLE
            } else if (statsHud.visibility != View.GONE) {
                statsHud.visibility = View.GONE
            }
        }
        // reflect play state on the transport button + quick bar when it changes
        // (also catches a non-looping source auto-pausing on its last frame)
        val sig = playingSignature()
        if (sig != lastPlayingSig) {
            lastPlayingSig = sig
            val playingNow = engine.anyPlaying()
            playBtn.setIcon(if (playingNow) R.drawable.ic_pause else R.drawable.ic_play,
                Color.WHITE, if (playingNow) "Pause" else "Play")
            // state change (e.g. a non-loop source auto-pausing at its end):
            // refresh all source surfaces so statuses never go stale
            refreshAll()
        }
        if (engine.consumeNewFrames()) stage.refresh()
    }

    /** cheap signature of per-source play states (detects auto-pause at end) */
    private fun playingSignature(): String {
        val sb = StringBuilder()
        for (l in proj!!.layers) if (l.isClip()) sb.append(if (l.playing) '1' else '0')
        return sb.toString()
    }

    private fun syncPreviewTarget() {
        if (!this::stage.isInitialized || !engineReady()) return
        val long = maxOf(stage.canvasW, stage.canvasH)
        engine.targetMaxPx = long.coerceIn(480, 960)
        // Decode each clip at the size it is actually drawn at. The closure
        // reads the stage on every call, so it stays correct after a rotate,
        // an aspect change or a layer resize without any extra plumbing.
        engine.layerTargetPx = { l ->
            // decode at the size of the VISIBLE frame (a pillarboxed FIT main
            // on a 16:9 canvas is a narrow strip — it must not pay for a
            // full-canvas decode)
            val frameLong = stage.visibleFrameMaxPx(l).toFloat()
            // headroom: a layer dragged larger keeps looking sharp for the
            // one frame it takes the decoder to notice
            (frameLong * 1.25f).toInt().coerceIn(240, 1440)
        }
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
        refreshContextBar(); rebuildDock(); rebuildSourceDock(); stage.refresh()
    }
    override fun bitmapOf(l: Layer): Bitmap? = engine.frameOf(l)
    override fun textOf(l: Layer): String = l.text
    override fun onTransform() { markDirty() }
    override fun onTapEmpty() { select(null) }
    override fun onChanged() { pushUndo() }
    override fun onDoubleTap(l: Layer) {
        // text layers edit on double tap; media ignores it (hide-on-double-tap
        // was removed: far too easy to trigger by accident)
        if (l.isText()) editTextLayer(l)
    }

    override fun onLockedTap(l: Layer) {
        showSnack("${l.name} is locked — gestures are off", "UNLOCK") { ctrl.toggleLocked(l.id) }
    }

    /** Long press anywhere on the canvas opens the rings under the finger. */
    override fun onLongPressCanvas(l: Layer?, x: Float, y: Float) {
        val stageLoc = IntArray(2); val rootLoc = IntArray(2)
        stage.getLocationOnScreen(stageLoc)
        rootFrame.getLocationOnScreen(rootLoc)
        val ax = x + stageLoc[0] - rootLoc[0]
        val ay = y + stageLoc[1] - rootLoc[1]
        if (l != null) {
            select(l.id)
            openWheelLevel(RadialMenus.source(this, l.id), ax, ay)
        } else {
            openWheelLevel(RadialMenus.root(this), ax, ay)
        }
    }

    private fun onSourceChanged() {
        // called by SourceController after every command
        reconcileLiveCamera()
        stage.refresh()
        refreshAll()
        markDirty()
        engine.refreshFrames()
    }

    /**
     * Keep the camera HARDWARE in sync with the layer list.
     *
     * Any path can change the layers behind our back — the Delete petal, the
     * advanced sheet, a dock drag, and above all undo/redo (which rebuilds the
     * list from JSON). Two things must never happen:
     *   1. the live layer is gone but LiveCamera still holds the camera open
     *      (the camera stays locked for every other app), and
     *   2. a live layer exists with no feed behind it (a dead frozen box,
     *      which undo used to resurrect).
     * This reconciles both, so no individual call site has to remember.
     */
    private fun reconcileLiveCamera() {
        val p = proj ?: return
        val liveLayer = p.layers.firstOrNull { it.isLive() }
        if (liveLayer == null) {
            if (liveCam != null || liveCamLayerId != null) {
                stopLiveCamera(evict = true)
                liveCamLayerId = null
            }
            return
        }
        if (liveCamLayerId != liveLayer.id) {
            // undo/redo restored a different (or a brand new) live layer
            stopLiveCamera(evict = true)
            liveCamLayerId = liveLayer.id
        }
        if (liveCam == null && !isFinishing &&
            checkSelfPermission(android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) {
            startLiveCamera()
        }
    }

    private fun refreshAll() {
        refreshContextBar()
        rebuildDock()
        rebuildSourceDock()
        updateEmptyState()
        updateName()
        updateRecordButton()
        updateHiddenPill()
        refreshTabBar()
    }

    private fun updateHiddenPill() {
        if (!this::hiddenPill.isInitialized) return
        val n = proj?.layers?.count { !it.visible } ?: 0
        if (fullCanvas || n <= 0) {
            hiddenPill.visibility = View.GONE
            return
        }
        hiddenPill.text = if (n == 1) "1 hidden source" else "$n hidden sources"
        hiddenPill.visibility = View.VISIBLE
    }

    private fun markDirty() {
        saveDirty = true
        updateName()
        saveHandler.removeCallbacks(autosave)
        saveHandler.postDelayed(autosave, 600)
    }

    private fun flushSave() {
        val p = proj ?: return
        p.updatedAt = System.currentTimeMillis()
        store.save(p, alsoSnapshot = true)
        saveDirty = false
        try { updateName() } catch (_: Exception) { }
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
        reconcileLiveCamera()
        // aspect can change via undo/redo, so re-sync orientation + chip here
        if (this::aspectChip.isInitialized) {
            applyOrientationFor(proj!!.aspect)
            updateAspectChip()
            stage.post { syncPreviewTarget() }
        }
        refreshAll()
        val dur = proj!!.durationMs().toInt().coerceAtLeast(1)
        seek.max = dur
        durationLabel.text = "/ " + UI.fmtTime(dur.toLong())
        stage.refresh()
        engine.refreshFrames()
        if (engine.anyPlaying()) engine.startSnapshots()
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
        if (code == REQ_CAMERA_PERM) {
            if (checkSelfPermission(android.Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED) addLiveCamera()
            else UI.toast(this, "Camera permission is needed to put the camera on the canvas")
        }
        if (code == REQ_RECORD_PERM) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED) startCompositeRecording()
            else UI.toast(this, "Mic permission denied — recording will mix the clip audio only")
        }
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
            recChip.visibility = if (fullCanvas) View.GONE else View.VISIBLE
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
        showProgress("Importing media", "Copying $displayName…", determinate = false)
        Thread {
            val ok = MediaKit.copyContentToFile(this, uri, tmp)
            // probe on the worker too (MediaMetadataRetriever can take 100s of ms)
            val info = if (ok) MediaKit.probe(tmp.absolutePath) else null
            runOnUiThread {
                dismissProgress()
                if (!ok || info == null) { UI.toast(this, "Import failed or file unreadable"); return@runOnUiThread }
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
     * Add a media file as a layer. The slow parts — MediaMetadataRetriever
     * probe, the copy into the project folder and (for stills) the image
     * bounds read — run on a worker thread; only the layer-list mutation and
     * the UI refresh happen on the main thread. Adding a source used to block
     * the UI for the whole copy + probe + a FULL image decode.
     */
    private fun consumeMediaFile(src: File, role: String, name: String, type: LayerType) {
        val pid = projectId
        val mediaDir = store.mediaDir(pid).absolutePath
        val inProject = src.parentFile?.absolutePath == mediaDir
        Thread({
            var rel: String? = null
            var info = com.rehman.ahmedreactionstudio.core.MediaInfo(0L, 0, 0, 0, null)
            var err: String? = null
            try {
                info = MediaKit.probe(src.absolutePath)
                if (type == LayerType.IMAGE && (info.width <= 0 || info.height <= 0)) {
                    // bounds only — no pixel decode on the add path
                    val o = android.graphics.BitmapFactory.Options()
                    o.inJustDecodeBounds = true
                    android.graphics.BitmapFactory.decodeFile(src.absolutePath, o)
                    if (o.outWidth > 0 && o.outHeight > 0)
                        info = com.rehman.ahmedreactionstudio.core.MediaInfo(0L, o.outWidth, o.outHeight, 0, null)
                }
                rel = if (inProject) "media/${src.name}" else store.copyIntoMedia(pid, src)
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
            }
            val relPath = rel
            runOnUiThread {
                if (isFinishing || isDestroyed || projectId != pid) return@runOnUiThread
                if (relPath == null) {
                    showSnack("Could not add \"$name\": ${err ?: "unreadable file"}")
                    return@runOnUiThread
                }
                mutateThen {
                    val p = proj!!
                    val l = if (type == LayerType.IMAGE)
                        Layer(type = type, name = name, relPath = relPath,
                            srcW = info.width, srcH = info.height)
                    else
                        Layer(type = type, name = name, relPath = relPath, durMs = info.durMs,
                            srcW = info.width, srcH = info.height, srcRotation = info.rotation)
                    p.layers.add(l)
                    if (role == "main" || p.layers.size == 1) placeMain(l, p) else placePip(l, p)
                    selectedId = l.id
                }
                try { if (!inProject) src.delete() } catch (_: Exception) { }
                finishAddSource(src, role, name, type)
            }
        }, "add-source").start()
    }

    private fun finishAddSource(src: File, role: String, name: String, type: LayerType) {
        setSheet(null)
        val asMain = role == "main" || (proj?.layers?.size == 1)
        showSnack(
            if (asMain) "\"$name\" fills the canvas — later additions become layers."
            else "\"$name\" added as a layer. Drag, resize, or tap Layers to reorder."
        )
        if (type != LayerType.IMAGE && engineReady()) {
            engine.startSnapshots()
            engine.refreshFrames()
        }
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

    /**
     * PiP placement that never stacks a new source exactly on top of the
     * previous one: try the corners bottom-right → bottom-left → top-right →
     * top-left and take the first one whose box does not overlap an existing
     * PiP; if all four are taken, cascade from the last-added PiP by 6 %.
     */
    private fun placePip(l: Layer, p: Project) {
        if (l.type == LayerType.TEXT) {
            l.wN = 0.86f; l.hN = 0.28f
            l.cx = 0.5f; l.cy = 0.5f
            return
        }
        l.fit = Layer.FIT_FIT
        LayerFit.placeNewPip(l, p.layers, p.aspect.canvasW, p.aspect.canvasH)
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
        val saved = if (saveDirty) "● Saving…" else "✓ Saved"
        meta?.text = "${proj!!.aspect.code} canvas · $n source" + (if (n == 1) "" else "s") + " · $saved"
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

        // Encode into the private cache first: always writable, and a failed or
        // cancelled export never leaves a half file in the user's Gallery.
        // MediaSave then moves it somewhere the phone can actually see.
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val fileName = "AhmedReaction_${p.name.replace(" ", "_")}_$stamp.${codec.ext}"
        val out = File(cacheDir, fileName)
        val mime = MediaSave.mimeFor(codec.ext)

        flushSave()
        exportRunning = true
        exportCancel.set(false)
        showProgress("Exporting → ${codec.label}", "Preparing…", determinate = true) {
            exportCancel.set(true)
        }

        // Freeze a COPY of each live camera frame. The live buffers are
        // triple-buffered and will be overwritten within ~120 ms; exporting
        // the live reference produced a black camera box.
        val live = HashMap<String, Bitmap>()
        for (l in p.layers) if (l.isLive()) {
            val src = if (engineReady()) engine.frameOf(l) else null
            if (src != null && !src.isRecycled) {
                try { live[l.id] = src.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Exception) { }
            }
        }
        if (engineReady()) { engine.pauseAll(); engine.stopSnapshots() }

        Exporter.export(p, store, Exporter.Options(fps = fps, maxDim = maxDim, quality = quality,
            codec = codec, outFile = out, liveFrames = live),
            exportCancel,
            { prog, msg -> runOnUiThread { updateProgress(prog, msg) } },
            { res ->
                runOnUiThread {
                    exportRunning = false
                    dismissProgress()
                    for (b in live.values) try { b.recycle() } catch (_: Exception) { }
                    if (res.ok && res.file != null) {
                        publishAndReport(res.file, fileName, mime, "Export complete", codec)
                    } else {
                        showSnack(res.message)
                    }
                }
            })
    }

    // ================= COMPOSITE RECORDING (local file + camera) =================

    /**
     * The RECORD button shows when a live camera AND a clip (local video /
     * screen record / camera take) are both on the canvas — the setup the user
     * asked for. Recording the composite makes no sense without at least one of
     * each, so the button stays hidden otherwise.
     */
    private fun updateRecordButton() {
        if (!this::recordBtn.isInitialized) return
        // the contextual bar mirrors the record state (Record / Stop verb)
        if (selectedId == null) refreshContextBar()
        val p = proj ?: return
        val hasLive = p.layers.any { it.isLive() }
        val hasClip = p.layers.any { it.isClip() }
        val ready = hasLive && hasClip
        recordBtn.visibility = View.VISIBLE
        recordBtn.text = when {
            recording -> "■  STOP & SAVE"
            ready -> "●  START RECORDING"
            !hasLive && !hasClip -> "●  ADD CAMERA + VIDEO TO RECORD"
            !hasLive -> "●  ADD CAMERA TO RECORD"
            else -> "●  ADD VIDEO TO RECORD"
        }
        recordBtn.alpha = if (recording || ready) 1f else 0.65f
        recordBtn.contentDescription = recordBtn.text.toString()
        recordBtn.background = if (recording)
            Ic.pill(this, Color.argb(240, 200, 34, 34), 20f, Color.argb(180, 255, 120, 120))
        else if (ready)
            Ic.pill(this, Color.argb(240, 255, 90, 44), 20f, Color.argb(140, 255, 200, 160))
        else
            Ic.pill(this, Color.argb(170, 38, 42, 52), 20f, Color.argb(70, 255, 255, 255))
        // ensure tabBar reflects recording state if needed
        try { refreshTabBar() } catch (_: Exception) {}
    }

    /** record taps when the setup is incomplete explain + open Add instead of hiding */
    private fun recordButtonTap() {
        if (recording) { stopCompositeRecording(); return }
        val p = proj ?: return
        val hasLive = p.layers.any { it.isLive() }
        val hasClip = p.layers.any { it.isClip() }
        if (hasLive && hasClip) { startCompositeRecording(); return }
        val missing = when {
            !hasLive && !hasClip -> "a live camera and a video"
            !hasLive -> "a live camera"
            else -> "a video"
        }
        AlertDialog.Builder(this)
            .setTitle("Set up the reaction first")
            .setMessage("Recording captures your live camera together with a playing " +
                "video. Add $missing to the canvas, frame them, then hit record.")
            .setPositiveButton("Add now") { _, _ ->
                openWheelLevel(RadialMenus.add(this), -1f, -1f)
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    /** Frame supplier for the recorder: engine frames + lazily decoded images. */
    private fun recordFrameOf(l: Layer): Bitmap? {
        if (l.type != LayerType.IMAGE) return engine.frameOf(l)
        recordImageCache[l.id]?.let { return it }
        val rel = l.relPath ?: return null
        val bmp = MediaKit.image(File(store.projectDir(projectId), rel).absolutePath) ?: return null
        recordImageCache[l.id] = bmp
        return bmp
    }

    private fun startCompositeRecording() {
        if (recording) return
        if (exportRunning) { UI.toast(this, "Locked while exporting"); return }
        val p = proj!!
        if (!p.layers.any { it.isLive() }) { UI.toast(this, "Add the live camera first"); return }
        if (!p.layers.any { it.isClip() }) { UI.toast(this, "Add a local video first"); return }
        val liveL = p.layers.firstOrNull { it.isLive() }
        if (liveL != null && engine.frameOf(liveL) == null) {
            if (camWaitTries++ >= 20) {
                camWaitTries = 0
                UI.toast(this, "Camera has not produced a frame yet — check permissions")
                return
            }
            showSnack("Waiting for the camera…")
            recordHandler.postDelayed({ if (!recording) startCompositeRecording() }, 250)
            return
        }
        camWaitTries = 0
        // the mic is the reaction audio — ask for it up front if not yet granted
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_RECORD_PERM)
            return
        }

        // the camera-take recorder (MediaRecorder) owns the mic while it runs —
        // two AudioRecord clients on one mic is how the audio "stops after a
        // few seconds" on many devices. One recorder at a time.
        if (liveCam?.recording == true) {
            UI.toast(this, "Stop the camera take first — it is using the microphone")
            return
        }

        // every clip's audio; mute/solo/volume/pause are followed LIVE by the
        // mixer (via layerId), so decode every clip that has a file
        val audio = ArrayList<ClipAudio>()
        for (l in p.layers) {
            if (!l.isClip() || l.relPath.isNullOrBlank()) continue
            val f = File(store.projectDir(projectId), l.relPath!!)
            if (f.exists()) audio.add(ClipAudio(f.absolutePath, l.volume, l.loop, l.durMs,
                layerId = l.id, speed = l.speed))
        }
        val micEnabled = true

        // decode clip audio off the UI thread BEFORE playback starts, so the
        // recorded audio and video stay aligned from the very first frame
        showProgress("Preparing audio", "Decoding the clip sound…", determinate = false)
        Thread {
            val decoded = ArrayList<DecodedClip>()
            val failed = ArrayList<String>()
            for (a in audio) {
                try {
                    val pcm = AudioDecode.toPcmMono(a.path)
                    if (pcm != null && pcm.data.isNotEmpty()) decoded.add(DecodedClip(a, pcm.data))
                    else failed.add(File(a.path).name)
                } catch (e: Throwable) {
                    failed.add(File(a.path).name)
                }
            }
            runOnUiThread {
                dismissProgress()
                if (failed.isNotEmpty())
                    showSnack("No audio track decoded for ${failed.joinToString()} — recording without it")
                beginCompositeRecording(decoded, micEnabled)
            }
        }.start()
    }

    private fun beginCompositeRecording(decoded: List<DecodedClip>, micEnabled: Boolean) {
        if (recording) return
        val p = proj!!
        val (w, h) = Exporter.chooseSize(p.aspect.canvasW, p.aspect.canvasH, 720)
        val tmp = File(cacheDir, "rec_${System.currentTimeMillis()}.mp4")
        val rec = CompositionRecorder({ this.proj!! }, { l -> recordFrameOf(l) }, { engine.master() },
            { l -> engine.mediaTimeOf(l) })
        // Preview monitor off BEFORE the mic opens: the recorder mixes the clip
        // PCM itself; the speaker copy would be re-captured by the microphone.
        engine.monitorMuted = true
        val ok = rec.start(tmp, w, h, 30, Exporter.Codec.H264, decoded, micEnabled) { err ->
            runOnUiThread { showSnack("Recording audio: $err") }
        }
        if (!ok) {
            engine.monitorMuted = false
            UI.toast(this, "Could not start recording")
            return
        }
        recorder = rec
        recording = true
        // start every source from the top, in sync — and tell the recorder the
        // exact instant the composition clock started so clip audio joins at 0:00
        engine.seekTo(0L)
        engine.playAll()
        engine.startSnapshots()
        rec.markCompositionStart()
        updateRecordButton()
        setSheet(null)
        recordHandler.removeCallbacks(recordTick)
        recordHandler.post(recordTick)
        UI.toast(this, if (micEnabled || decoded.isNotEmpty())
            "Recording with audio — tap STOP to save" else "Recording — tap STOP to save")
    }

    private fun stopCompositeRecording(showUi: Boolean = true) {
        if (!recording) return
        recording = false
        recordHandler.removeCallbacks(recordTick)
        engine.pauseAll()
        engine.stopSnapshots()
        val rec = recorder
        recorder = null
        updateRecordButton()
        if (showUi) showProgress("Finishing recording", "Draining audio and video…", determinate = false)
        rec?.finish { res ->
            runOnUiThread {
                dismissProgress()
                engine.monitorMuted = false
                recordImageCache.clear()
                val f = res.file
                if (f != null && f.exists() && f.length() > 0) {
                    lastRecordingNote = res.message
                    android.util.Log.i("AhmedRecorder", "take ok: ${res.stats}")
                    saveRecordingToPublic(f, showUi)
                } else if (showUi) {
                    AlertDialog.Builder(this)
                        .setTitle("Recording failed")
                        .setMessage((res.message ?: "The take was too short or could not be written.") +
                            "\n\n" + res.stats)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    /** audio warnings from the last take (shown in the saved dialog) */
    private var lastRecordingNote: String? = null

    /** Copy the finished take somewhere the phone can really see, then report it. */
    private fun saveRecordingToPublic(src: File, showUi: Boolean) {
        val p = proj
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val name = "AhmedReaction_${p?.name?.replace(" ", "_") ?: "project"}_$stamp.mp4"
        if (showUi) publishAndReport(src, name, "video/mp4", "Recording saved")
        else MediaSave.publishVideo(this, src, name, "video/mp4")
    }

    /**
     * Save [src] publicly and tell the user THE TRUTH about where it went.
     *
     * The previous code announced "Saved to Gallery" unconditionally, even when
     * the MediaStore insert had failed and the only copy sat in
     * /Android/data/<pkg>/… where no file manager could reach it. Now the
     * dialog reports the verified location and byte count, and a save that
     * genuinely failed says so instead of pretending.
     */
    private fun publishAndReport(src: File, name: String, mime: String, title: String,
                                 codec: Exporter.Codec? = null) {
        showProgress("Saving", "Saving to your phone…", determinate = false)
        Thread {
            val saved = try { MediaSave.publishVideo(this, src, name, mime) } catch (_: Throwable) { null }
            runOnUiThread {
                dismissProgress()
                if (saved == null) {
                    AlertDialog.Builder(this)
                        .setTitle("Could not save the video")
                        .setMessage("The video was encoded but no writable public folder " +
                            "accepted it. Free some storage and try again — nothing was lost " +
                            "until you close this dialog.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@runOnUiThread
                }
                val where = if (saved.publiclyVisible)
                    "Saved to ${saved.location}"
                else
                    "Public folders were unavailable, so it was saved inside the app folder:\n${saved.location}"
                // playability note for the less-compatible codecs (the classic
                // "it exported fine but won't play in my other app" report)
                val compatNote = (if (codec != null && codec != Exporter.Codec.H264)
                    "\n\nNote: ${codec.label} won't play in some apps — re-export as H.264 if needed."
                else "") + (lastRecordingNote?.let { "\n\nAudio: $it" } ?: "")
                lastRecordingNote = null
                AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage("$where\n\n${UI.niceBytes(saved.bytes)} · ${codec?.label ?: "H.264 / AVC"}$compatNote")
                    .setPositiveButton("View") { _, _ -> viewRecording(saved.uri, saved.path, mime) }
                    .setNeutralButton("Share") { _, _ ->
                        val u = saved.uri ?: saved.path?.let { Uri.fromFile(File(it)) }
                        if (u != null) UI.shareUri(this, u, mime)
                        else UI.toast(this, saved.location)
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }.start()
    }

    private fun viewRecording(uri: Uri?, path: String?, mime: String = "video/mp4") {
        try {
            val viewUri = uri ?: (path?.let { Uri.fromFile(File(it)) } ?: return)
            val i = Intent(Intent.ACTION_VIEW).apply {
                // the real MIME: WebM served as video/mp4 would not play
                setDataAndType(viewUri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(i)
        } catch (e: Exception) {
            UI.toast(this, "No video player found — the file is saved at ${path ?: "Movies/AhmedReactionStudio"}")
        }
    }

    // ================= LIVE CAMERA ON THE CANVAS =================

    /**
     * Add the camera as a LIVE source composited on the canvas.
     *
     * This is the fix for "selecting the camera shows a strange interface":
     * no fullscreen activity, no separate UI — a CAMERA layer is created,
     * placed like any other source, and [LiveCamera] pushes its frames into
     * the PreviewEngine so the shared Compositor draws it on the stage. You
     * frame the reaction inside the composition, with drag / resize / rotate /
     * fit / z-order all live.
     */
    private fun addLiveCamera() {
        if (liveCamLayerId != null) {
            val existing = proj!!.layerById(liveCamLayerId!!)
            if (existing != null) {
                UI.toast(this, "The live camera is already on the canvas")
                select(existing.id)
                return
            }
        }
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA,
                android.Manifest.permission.RECORD_AUDIO), REQ_CAMERA_PERM)
            return
        }
        val p = proj!!
        val asMain = p.layers.isEmpty()
        mutateThen {
            val l = Layer(type = LayerType.CAMERA, name = "Camera (live)")
            l.live = true
            l.camFacing = Layer.FACING_FRONT
            l.mirror = true
            // a sane 16:9 guess until the first frame reports the real size
            l.srcW = 1280; l.srcH = 720
            l.fit = Layer.FIT_FIT
            p.layers.add(l)
            if (asMain) placeMain(l, p) else placePip(l, p)
            selectedId = l.id
            liveCamLayerId = l.id
        }
        startLiveCamera()
    }

    private fun startLiveCamera() {
        val id = liveCamLayerId ?: return
        if (liveCam != null) return
        val cam = LiveCamera(this, { bmp ->
            // frames arrive on the camera thread → hop to the UI thread
            runOnUiThread {
                val l = proj?.layerById(id)
                if (l == null) { stopLiveCamera(evict = true); return@runOnUiThread }
                // adopt the real feed aspect once (keeps fit/PiP geometry honest)
                val cw = cam0W(); val chh = cam0H()
                if (cw > 0 && chh > 0 && (l.srcW != cw || l.srcH != chh)) {
                    l.srcW = cw; l.srcH = chh
                    if (!LayerFit.isFullBleed(l)) {
                        // re-derive the box aspect from the real feed but keep
                        // the user's position / rotation (no jump to a corner)
                        val cx = l.cx; val cy = l.cy; val rot = l.rotDeg
                        LayerFit.pip(l, proj!!.aspect.canvasW, proj!!.aspect.canvasH,
                            anchor = "br")
                        l.cx = cx; l.cy = cy; l.rotDeg = rot
                        LayerFit.clampInside(l)
                    }
                }
                engine.setFrame(l, bmp)
                stage.refresh()
            }
        }, { state ->
            runOnUiThread {
                when (state) {
                    "permission" -> UI.toast(this, "Camera permission is needed")
                    "nocamera" -> {
                        UI.toast(this, "No camera on this device — using the fullscreen recorder")
                        removeLiveCameraLayer(); openCamera()
                    }
                    "error" -> {
                        UI.toast(this, "Camera busy — falling back to the fullscreen recorder")
                        removeLiveCameraLayer(); openCamera()
                    }
                    "recfail" -> UI.toast(this, "Could not record this camera take")
                    "torcherror" -> UI.toast(this,
                        liveCam?.torchLastError()?.takeIf { it.isNotBlank() }
                            ?: "Hardware torch unavailable")
                    "recording" -> { recChip.text = "● STOP CAMERA TAKE"; recChip.contentDescription = "Stop the camera take"; recChip.visibility = if (fullCanvas) View.GONE else View.VISIBLE }
                    "live" -> refreshAll()
                }
            }
        })
        liveCam = cam
        cam.start(front = true)
        UI.toast(this, "Live camera on the canvas — drag, resize and record from ◉ Studio")
    }

    private fun cam0W(): Int = liveCam?.outW ?: 0
    private fun cam0H(): Int = liveCam?.outH ?: 0

    private fun stopLiveCamera(evict: Boolean) {
        val cam = liveCam ?: return
        liveCam = null
        cam.stop()
        val id = liveCamLayerId
        if (evict && id != null) engine.clearExternal(id)
    }

    private fun removeLiveCameraLayer() {
        val id = liveCamLayerId ?: return
        stopLiveCamera(evict = true)
        liveCamLayerId = null
        ctrl.delete(id)
        if (selectedId == id) selectedId = null
        refreshAll()
    }

    /** Record the live camera to a clip and swap the layer over IN PLACE. */
    private fun toggleLiveCameraRecord(l: Layer) {
        val cam = liveCam
        if (cam == null) { UI.toast(this, "The live camera is not running"); return }
        if (recording && !cam.recording) {
            // the composite recorder owns the microphone; a second MediaRecorder
            // on the same mic would silence one of them mid-take
            UI.toast(this, "Stop the composite recording first — it is using the microphone")
            return
        }
        if (cam.recording) {
            cam.stopRecording { f ->
                runOnUiThread {
                    recChip.visibility = View.GONE
                    if (f == null || !f.exists()) {
                        UI.toast(this, "Take was too short or failed")
                        refreshAll(); return@runOnUiThread
                    }
                    swapLiveCameraToClip(l, f)
                }
            }
        } else {
            cam.startRecording(store.mediaDir(projectId)) { ok ->
                runOnUiThread {
                    if (ok) {
                        recChip.text = "● STOP CAMERA TAKE"
                        recChip.contentDescription = "Stop the camera take"
                        recChip.visibility = if (fullCanvas) View.GONE else View.VISIBLE
                        UI.toast(this, "Recording the camera take")
                    } else UI.toast(this, "Could not start the take")
                    refreshAll()
                }
            }
        }
    }

    /**
     * The finished take replaces the live feed IN PLACE: same geometry, same
     * z-order, same name — so the composition you framed live is exactly the
     * one that exports.
     */
    private fun swapLiveCameraToClip(live: Layer, f: File) {
        val p = proj!!
        val info = MediaKit.probe(f.absolutePath)
        pushUndo()
        val idx = p.layers.indexOf(live).coerceAtLeast(0)
        stopLiveCamera(evict = true)
        liveCamLayerId = null
        val clip = Layer(type = LayerType.CAMERA, name = "Camera take",
            relPath = "media/${f.name}", durMs = info.durMs,
            srcW = info.width, srcH = info.height, srcRotation = info.rotation)
        clip.cx = live.cx; clip.cy = live.cy
        clip.wN = live.wN; clip.hN = live.hN; clip.rotDeg = live.rotDeg
        clip.fit = live.fit
        clip.opacity = live.opacity
        clip.visible = live.visible
        p.layers.remove(live)
        p.layers.add(idx.coerceAtMost(p.layers.size), clip)
        selectedId = clip.id
        afterStructureChange()
        UI.toast(this, "Take added as a clip — ${UI.fmtTime(info.durMs)}")
    }

    // ================= RadialMenus.Host =================

    override fun selected(): Layer? = selectedId?.let { proj!!.layerById(it) }
    override fun selectId(id: String?) { select(id) }

    override fun addVideo() { pickMedia(video = true) }
    override fun addImage() { pickMedia(video = false) }
    override fun addCameraLive() { addLiveCamera() }
    override fun addCameraTake() { openCamera() }
    override fun addScreen() { startScreenCapture() }
    override fun addTextSource() { addText() }

    override fun anyPlaying(): Boolean = engineReady() && engine.anyPlaying()
    override fun toggleMasterPlay() { togglePlay() }
    override fun restart() {
        engine.seekTo(0L)
        seek.progress = 0
        onTick(0L)
    }
    override fun nudge(ms: Long) {
        val dur = proj!!.durationMs()
        val t = (engine.master() + ms).coerceIn(0L, dur)
        engine.seekTo(t)
        seek.progress = t.toInt().coerceAtMost(seek.max)
        onTick(t)
    }
    override fun toggleSourcePlay(l: Layer) {
        engine.toggleLayerPlay(l); markDirty(); refreshAll()
    }
    override fun snapshotFrame() {
        // freeze the current composition as an IMAGE source
        val p = proj!!
        try {
            val bmp = Bitmap.createBitmap(p.aspect.canvasW / 2, p.aspect.canvasH / 2,
                Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            com.rehman.ahmedreactionstudio.core.Compositor.draw(
                com.rehman.ahmedreactionstudio.core.Compositor.Ctx(), c,
                bmp.width, bmp.height, p, { engine.frameOf(it) }, engine.master(), null)
            val dir = store.mediaDir(projectId); dir.mkdirs()
            val f = File(dir, "snap_${System.currentTimeMillis()}.png")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            consumeMediaFile(f, "pip", name = "Snapshot", type = LayerType.IMAGE)
        } catch (e: Exception) {
            UI.toast(this, "Snapshot failed: ${e.message}")
        }
    }
    override fun undo() { doUndo() }
    override fun redo() { doRedo() }

    override fun enterFullCanvas() { setFullCanvas(true) }
    override fun openDockPanel() { setSheet("sources") }
    override fun openMixerPanel() { setSheet("mixer") }
    override fun openExportPanel() { setSheet("export") }
    override fun openAdvanced(l: Layer) { openAdvancedSheet(l) }
    override fun quickExport() {
        val avail = Exporter.Codec.available().ifEmpty { listOf(Exporter.Codec.H264) }
        val codec = avail.firstOrNull { it == Exporter.Codec.H264 } ?: avail[0]
        if (warnLiveBeforeExport()) return
        runExport(1, 720, 30, codec)
    }

    /**
     * Live camera has no file to seek. Recording captures motion; offline
     * export freezes the last camera frame (never a black hole).
     */
    private fun warnLiveBeforeExport(): Boolean {
        val live = proj!!.layers.firstOrNull { it.isLive() && it.visible } ?: return false
        val hasFrame = engineReady() && engine.frameOf(live) != null
        AlertDialog.Builder(this)
            .setTitle("Live camera on the canvas")
            .setMessage("\"${live.name}\" is a live feed. Tap START RECORDING to capture " +
                "camera + video as they play.\n\nOffline export can only freeze the " +
                (if (hasFrame) "current camera frame" else "camera as an empty box until a frame arrives") +
                " — it cannot play the live camera forward.")
            .setPositiveButton("Start recording") { _, _ -> recordButtonTap() }
            .setNegativeButton(if (hasFrame) "Export frozen frame" else "Export anyway") { _, _ ->
                val prefs = editorPrefs()
                val avail = Exporter.Codec.available().ifEmpty { listOf(Exporter.Codec.H264) }
                val codec = avail.firstOrNull { it.name == prefs.getString(PREF_EXP_CODEC, "H264") }
                    ?: avail.firstOrNull { it == Exporter.Codec.H264 } ?: avail[0]
                val quality = prefs.getInt(PREF_EXP_QUALITY, EncoderConfig.Quality.BALANCED.ordinal)
                val maxDim = prefs.getInt(PREF_EXP_MAXDIM, 720).let {
                    if (it <= 480) 480 else if (it >= 1080) 1080 else 720
                }
                val fps = prefs.getInt(PREF_EXP_FPS, 30).let { if (it == 24) 24 else if (it == 60) 60 else 30 }
                runExport(quality, maxDim, fps, codec)
            }
            .setNeutralButton("Cancel", null)
            .show()
        return true
    }

    override fun setAspect(a: Aspect) { changeAspect(a) }
    override fun setBg(color: Int) { setBgColor(color) }
    override fun fitAllSources() {
        val p = proj!!
        pushUndo()
        for (l in p.layers) if (!l.isText()) l.fit = Layer.FIT_FIT
        markDirty(); stage.refresh(); refreshAll()
        UI.toast(this, "Every source shows its whole frame")
    }
    override fun renameProject() {
        val input = EditText(this)
        input.setText(proj!!.name)
        input.setTextColor(UI.FG)
        AlertDialog.Builder(this).setTitle("Rename project").setView(input)
            .setPositiveButton("OK") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) { proj!!.name = n; markDirty(); updateName() }
            }
            .setNegativeButton("Cancel", null).show()
    }
    override fun saveNow() { flushSave(); UI.toast(this, "Project saved") }
    override fun openDiagnostics() {
        startActivity(Intent(this, DiagnosticsActivity::class.java))
    }
    override fun closeProject() { onBackPressed() }
    override fun editText(l: Layer) { editTextLayer(l) }
    override fun cycleTextColor(l: Layer) {
        pushUndo(); l.textColor = nextColor(l.textColor); markDirty(); stage.refresh()
    }

    override fun isCameraRecording(l: Layer): Boolean =
        l.id == liveCamLayerId && liveCam?.recording == true
    override fun toggleCameraRecord(l: Layer) { toggleLiveCameraRecord(l) }
    override fun switchCameraFacing(l: Layer) {
        val cam = liveCam ?: return
        cam.switchFacing()
        l.camFacing = if (cam.isFront()) Layer.FACING_FRONT else Layer.FACING_BACK
        l.mirror = cam.isFront()
        markDirty(); refreshAll()
    }
    override fun toggleCameraMirror(l: Layer) {
        l.mirror = !l.mirror
        liveCam?.setMirror(l.mirror)
        markDirty(); refreshAll()
    }

    // ---------------- flashlight (LED torch + screen light) ----------------
    // Dual-torch: both front and back LEDs can be controlled independently.
    // The state is remembered per-facing so switching camera preserves the user's choice.
    // "Both on" uses CameraManager.setTorchMode for the idle camera.

    override fun isTorchOn(l: Layer): Boolean =
        l.id == liveCamLayerId && liveCam?.torch == true

    override fun hasTorch(l: Layer): Boolean =
        l.id == liveCamLayerId && liveCam?.hasFlashUnit == true

    override fun hasFrontTorch(): Boolean = liveCam?.frontHasFlash == true
    override fun hasBackTorch(): Boolean = liveCam?.backHasFlash == true
    override fun isFrontTorchOn(): Boolean = liveCam?.isTorchOnForFront() == true
    override fun isBackTorchOn(): Boolean = liveCam?.isTorchOnForBack() == true
    override fun isBothTorchOn(): Boolean = liveCam?.bothTorchesFullyOn() == true

    override fun toggleTorch(l: Layer) {
        val cam = liveCam
        if (cam == null || l.id != liveCamLayerId) {
            UI.toast(this, "The live camera is not running")
            return
        }
        if (!cam.hasFlashUnit) {
            // no LED on this side — say so instead of faking a torch
            UI.toast(this, if (cam.isFront())
                "Front camera has no LED — use the screen light"
            else "This device has no rear flash")
            return
        }
        if (!cam.toggleTorch() && !cam.torch) {
            UI.toast(this, cam.torchLastError().takeIf { it.isNotBlank() }
                ?: "This camera has no flash — try the screen light")
            return
        }
        UI.toast(this, if (cam.torch) "Flashlight on" else "Flashlight off")
        refreshAll()
    }
    override fun toggleFrontTorch() {
        val cam = liveCam
        if (cam == null) { UI.toast(this, "Live camera not running"); return }
        if (!cam.hasFlashForFront()) {
            UI.toast(this, "Front camera has no LED — use the screen light")
            return
        }
        if (!cam.toggleFrontTorch() && !cam.isTorchOnForFront()) {
            UI.toast(this, cam.torchLastError().takeIf { it.isNotBlank() }
                ?: "Front flash unavailable")
            return
        }
        UI.toast(this, if (cam.isTorchOnForFront()) "Front flash on" else "Front flash off")
        refreshAll()
    }
    override fun toggleBackTorch() {
        val cam = liveCam
        if (cam == null) { UI.toast(this, "Live camera not running"); return }
        if (!cam.hasFlashForBack()) {
            UI.toast(this, "This device has no rear flash")
            return
        }
        if (!cam.toggleBackTorch() && !cam.isTorchOnForBack()) {
            UI.toast(this, cam.torchLastError().takeIf { it.isNotBlank() }
                ?: "Rear flash unavailable")
            return
        }
        UI.toast(this, if (cam.isTorchOnForBack()) "Rear flash on (LED)" else "Rear flash off")
        refreshAll()
    }
    override fun toggleBothTorch() {
        val cam = liveCam
        if (cam == null) { UI.toast(this, "Live camera not running"); return }
        if (!cam.hasFlashForFront() && !cam.hasFlashForBack()) {
            UI.toast(this, "This device has no camera flash — use the screen light")
            return
        }
        val turnOn = !cam.bothTorchesFullyOn()
        if (turnOn && !cam.setBothTorches(true)) {
            UI.toast(this, "No camera flash is available")
            return
        }
        if (!turnOn) cam.setBothTorches(false)
        UI.toast(this, if (turnOn) "Both flashes on" else "Both flashes off")
        refreshAll()
    }

    override fun isScreenLightOn(): Boolean = screenLight

    /**
     * SCREEN FLASH for the front camera.
     *
     * Front lenses almost never have an LED, so the phone itself becomes the
     * lamp. We push window brightness to 1.0 and draw a bright warm-white
     * panel BEHIND the stage so the canvas stays fully visible — the light
     * comes from the letterbox surround, not by covering the composition.
     */
    override fun toggleScreenLight() {
        screenLight = !screenLight
        applyScreenLight()
        UI.toast(this, if (screenLight) "Screen light on" else "Screen light off")
        refreshAll()
    }

    private fun applyScreenLight() {
        try {
            val lp = window.attributes
            lp.screenBrightness = if (screenLight) 1f else -1f
            window.attributes = lp
        } catch (_: Exception) { }
        if (screenLight) {
            if (screenLightView == null) {
                val v = View(this)
                v.setBackgroundColor(Color.argb(242, 255, 246, 232))
                v.isClickable = false
                v.isFocusable = false
                // behind stage, in front of root background so canvas remains visible
                rootFrame.addView(v, 0, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT))
                screenLightView = v
            }
            screenLightView?.visibility = View.VISIBLE
            // keep stage and overlays above the light
            if (this::stage.isInitialized) stage.bringToFront()
            emptyOverlay.bringToFront()
            if (this::wheel.isInitialized) wheel.bringToFront()
            if (this::sheet.isInitialized) sheet.bringToFront()
        } else {
            screenLightView?.visibility = View.GONE
        }
    }

    override fun openFlashRing(l: Layer) {
        openWheelLevel(RadialMenus.flash(this, l.id), -1f, -1f)
    }

    override fun isStatsHudOn(): Boolean =
        editorPrefs().getBoolean(PREF_STATS_HUD, true)

    override fun toggleStatsHud() {
        val on = !isStatsHudOn()
        editorPrefs().edit().putBoolean(PREF_STATS_HUD, on).apply()
        if (on && this::statsHud.isInitialized && engineReady() &&
            (engine.anyPlaying() || recording)) {
            statsHud.text = engine.stats()
            statsHud.visibility = View.VISIBLE
        } else if (this::statsHud.isInitialized) {
            statsHud.visibility = View.GONE
        }
    }

    override fun toast(msg: String) { UI.toast(this, msg) }
}
