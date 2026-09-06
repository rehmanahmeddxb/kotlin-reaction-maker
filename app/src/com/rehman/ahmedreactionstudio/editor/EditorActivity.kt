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
import android.widget.ImageView
import android.widget.TextView
import com.rehman.ahmedreactionstudio.R
import com.rehman.ahmedreactionstudio.camera.CameraActivity
import com.rehman.ahmedreactionstudio.capture.ScreenCaptureService
import com.rehman.ahmedreactionstudio.core.Aspect
import com.rehman.ahmedreactionstudio.core.ChromeBudget
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
    var proj: Project? = null
    private var projectId: String = ""
    var selectedId: String? = null

    // ---- chrome built by StudioLayoutInjector (rebuilt on every rotation) ----
    lateinit var rootFrame: FrameLayout
    lateinit var chromeColumn: LinearLayout
    lateinit var stage: StageView
    lateinit var canvasCell: FrameLayout
    lateinit var topBar: LinearLayout
    lateinit var transportBar: LinearLayout
    lateinit var toolRail: View
    lateinit var contextPanel: LinearLayout
    lateinit var tabBar: LinearLayout
    lateinit var panelBody: FrameLayout
    lateinit var emptyOverlay: LinearLayout
    lateinit var cameraStrip: LinearLayout
    lateinit var recChip: TextView
    lateinit var statsHud: TextView
    lateinit var fullExitBtn: IconBtn
    lateinit var studioBtn: IconBtn
    lateinit var playBtn: IconBtn
    lateinit var timeLabel: TextView
    lateinit var durationLabel: TextView
    lateinit var seek: SeekBar
    lateinit var aspectChip: TextView
    lateinit var recordBtn: TextView
    lateinit var wheel: RadialMenuView
    lateinit var dockContainer: LinearLayout
    lateinit var dock: SourceDock
    var undoBtn: IconBtn? = null
    var redoBtn: IconBtn? = null
    var panelEdgeHandle: View? = null
    var railEdgeHandle: View? = null
    var timeline: TimelineView? = null
    var timelineBtn: IconBtn? = null
    var panelCloseBtn: IconBtn? = null
    var sourcesPanel: SourcesPanel? = null
    var mixerPanel: MixerPanel? = null
    var propertiesPanel: PropertiesPanel? = null
    var effectsPanel: EffectsPanel? = null
    val tabViews = HashMap<String, View>()
    override lateinit var ctrl: SourceController

    // ---- chrome state that must survive a rotation rebuild ----
    var chromeTier: StudioLayoutInjector.Tier = StudioLayoutInjector.Tier.PHONE_PORTRAIT
    /** null = "not decided yet" → ChromeBudget picks the default for this window */
    var panelOpen: Boolean? = null
    /** landscape tool rail; null = budget default (tablet: always shown) */
    var railOpen: Boolean? = null
    /** timeline strip; null = tier default (collapsed on phone landscape) */
    var timelineOpen: Boolean? = null
    var activeTab = "sources"
    /** full height (tab strip + body) of an OPEN portrait panel, px */
    var portraitPanelHeightPx = 0
    private var fullCanvas = false
    private var monitorMuted = false

    // ---- transient overlays (root-level) ----
    private var snackBar: LinearLayout? = null
    private var snackMsg: TextView? = null
    private var snackAction: TextView? = null
    private val snackHandler = Handler(Looper.getMainLooper())
    private val snackHide = Runnable { snackBar?.visibility = View.GONE }
    private var progOverlay: FrameLayout? = null
    private var progTitle: TextView? = null
    private var progMsg: TextView? = null
    private var progBar: android.widget.ProgressBar? = null
    private var progCancel: TextView? = null
    private var progOnCancel: (() -> Unit)? = null

    // ---- save / dirty ----
    private var saveDirty = false

    // ---- live camera on the canvas ----
    private var liveCam: LiveCamera? = null
    private var liveCamLayerId: String? = null
    /** fullscreen-recorder fallback fires at most once per editor session */
    private var cameraFallbackShown = false

    // ---- screen light (selfie fill) ----
    private var screenLight = false

    lateinit var engine: PreviewEngine
    private val undo = UndoStack()
    private val saveHandler = Handler(Looper.getMainLooper())
    private val autosave = Runnable { flushSave() }
    var scrubbing = false
    private var lastPlayingSig = ""

    private val exportCancel = AtomicBoolean(false)
    private var exportRunning = false

    // ---- composite recording ----
    private var recorder: CompositionRecorder? = null
    private var recording = false
    private var recordStartMs = 0L
    private var lastUiTickMs = 0L
    private var lastHudMs = 0L
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
        b?.getString("tab")?.let { activeTab = it }
        if (b?.containsKey("panel") == true) panelOpen = b.getBoolean("panel")
        if (b?.containsKey("rail") == true) railOpen = b.getBoolean("rail")
        if (b?.containsKey("timeline") == true) timelineOpen = b.getBoolean("timeline")
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

        buildUi()
        afterChromeBuilt()
    }

    private fun applyOrientationFor(a: Aspect) {
        // Follow the canvas: picking a 16:9 canvas rotates the studio into
        // landscape (where the landscape chrome/rail lives and the canvas
        // fills the width), picking 9:16 goes portrait. 1:1 is neutral — it
        // contain-fits either way, so we don't fight the user. The editor
        // declares configChanges for orientation, so this rotation re-lays
        // the chrome via onConfigurationChanged instead of restarting the
        // activity — the camera, decoders and master clock keep running.
        val want = when (a) {
            Aspect.R169 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            Aspect.R916 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            Aspect.R11 -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (requestedOrientation != want) requestedOrientation = want
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putString("pid", projectId)
        out.putString("sel", selectedId)
        out.putString("tab", activeTab)
        panelOpen?.let { out.putBoolean("panel", it) }
        railOpen?.let { out.putBoolean("rail", it) }
        timelineOpen?.let { out.putBoolean("timeline", it) }
        super.onSaveInstanceState(out)
    }

    fun engineReady(): Boolean = this::engine.isInitialized

    private fun transportReady(): Boolean =
        this::seek.isInitialized && this::durationLabel.isInitialized

    fun transportReadyForUi(): Boolean = transportReady()

    private fun chromeReady(): Boolean =
        this::stage.isInitialized && this::contextPanel.isInitialized && this::canvasCell.isInitialized

    private fun wheelReady(): Boolean = this::wheel.isInitialized

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
        if (engineReady()) {
            engine.refreshFrames()
            if (engine.anyPlaying()) engine.startSnapshots()
        }
        reconcileLiveCamera()
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
        if (this::rootFrame.isInitialized) rootFrame.removeCallbacks(recTimerTick)
        recordHandler.removeCallbacks(recTimerTick)
        recorder?.abort()
        recorder = null
        recordHandler.removeCallbacksAndMessages(null)
        snackHandler.removeCallbacksAndMessages(null)
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
        if (wheelReady() && wheel.isOpen()) { wheel.pop(); return }
        if (fullCanvas) { setFullCanvas(false); return }
        flushSave()
        store.clearOpen(projectId)
        super.onBackPressed()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        relayoutChrome()
        stage.post { syncPreviewTarget() }
        stage.refresh()
    }

    // ================= UI: one column, dp-sized bars, flexible canvas cell =================

    private fun buildUi() {
        val root = FrameLayout(this)
        rootFrame = root
        root.setBackgroundColor(UI.BLACK)
        // The column paints edge-to-edge under the system bars; the bars'
        // insets are applied as padding on the column so nothing sits under
        // the status bar, the gesture strip or a display cutout, in either
        // orientation. Full Canvas hides the bars and drops the padding.
        root.setOnApplyWindowInsetsListener { _, insets ->
            readSystemInsets(insets)
            applySystemInsets()
            // the usable size changed → re-divide it (cheap, no rebuild)
            applyChromeBudget()
            insets
        }
        StudioLayoutInjector.inject(this, root)
        setContentView(root)
    }

    /**
     * Tear the chrome down and rebuild it for the current configuration. The
     * stage / engine / camera are NOT touched: the new StageView simply binds
     * to the same host and the engine keeps decoding. Called on rotation and
     * whenever a structural chrome state (panel open / rail expanded) changes.
     */
    private fun relayoutChrome() {
        val wheelWasOpen = wheelReady() && wheel.isOpen()
        if (wheelWasOpen) wheel.dismiss(animated = false)
        rootFrame.removeAllViews()
        StudioLayoutInjector.inject(this, rootFrame)
        afterChromeBuilt()
        if (screenLight) applyScreenLight()
    }

    /** Everything that must run after inject(): rebinding, first paint, insets. */
    private fun afterChromeBuilt() {
        // every time the fitted canvas changes size (panel toggled, rotation,
        // aspect change) the engine re-targets its decode resolution
        stage.onCanvasLayout = { _, _ -> syncPreviewTarget() }
        rebindDock()
        rebuildDock()
        applySystemInsets()
        // first build / rebuild: the budget was computed from display metrics;
        // once the root has real bounds + insets, correct it in place
        rootFrame.requestApplyInsets()
        rootFrame.post { applyChromeBudget() }
        updateName()
        updateAspectChip()
        if (engineReady()) engine.refreshFrames()
        refreshAll()
        stage.post { syncPreviewTarget() }
        if (fullCanvas) applyFullCanvasChrome(true)
        // an export / recording finish in flight keeps its progress card
        applyProgressState()
    }

    // ---------------- system insets (status / nav / cutout / gesture) ----------------

    private var sysL = 0; private var sysT = 0; private var sysR = 0; private var sysB = 0

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
            if (Build.VERSION.SDK_INT >= 28) {
                val c = insets.displayCutout
                if (c != null) {
                    sysL = maxOf(sysL, c.safeInsetLeft); sysT = maxOf(sysT, c.safeInsetTop)
                    sysR = maxOf(sysR, c.safeInsetRight); sysB = maxOf(sysB, c.safeInsetBottom)
                }
            }
        }
    }

    private fun applySystemInsets() {
        if (!this::chromeColumn.isInitialized) return
        if (fullCanvas) chromeColumn.setPadding(0, 0, 0, 0)
        else chromeColumn.setPadding(sysL, sysT, sysR, sysB)
    }

    /**
     * Window width/height in dp minus the system bars: what ChromeBudget
     * divides up. The configuration's dp size is the primary source — it is
     * already correct inside onConfigurationChanged, before the root has been
     * re-measured — and the root's real bounds minus the insets refine it once
     * they agree with the current orientation.
     */
    fun usableWidthDp(): Int {
        val cfg = resources.configuration
        val dm = resources.displayMetrics
        var dp = cfg.screenWidthDp
        if (rootBoundsCurrent()) dp = ((rootFrame.width - sysL - sysR) / dm.density).toInt()
        return dp.coerceAtLeast(320)
    }

    fun usableHeightDp(): Int {
        val cfg = resources.configuration
        val dm = resources.displayMetrics
        var dp = cfg.screenHeightDp
        if (rootBoundsCurrent()) dp = ((rootFrame.height - sysT - sysB) / dm.density).toInt()
        return dp.coerceAtLeast(320)
    }

    /** true when the root has been laid out for the orientation we are building for */
    private fun rootBoundsCurrent(): Boolean {
        if (!this::rootFrame.isInitialized || rootFrame.width <= 0 || rootFrame.height <= 0) return false
        val landscapeCfg = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        return (rootFrame.width > rootFrame.height) == landscapeCfg
    }

    // ---------------- context panel: open / close / tabs ----------------

    /**
     * Open or collapse the contextual panel. The sizes are re-read from
     * [ChromeBudget] every time, so opening the panel on a narrow phone shrinks
     * it to its minimum and collapses the rail *before* the canvas would drop
     * under 45 % of the width; closing it hands the width back to the canvas
     * cell (weight 1). Portrait swaps the panel between "tabs + body" and
     * "tabs only" — the tabs stay reachable, the canvas grows.
     */
    fun setPanelOpen(open: Boolean) {
        panelOpen = open
        if (!chromeReady()) return
        applyChromeBudget()
        stage.post { syncPreviewTarget() }
    }

    /** Effective state (the budget default until the user chooses). */
    fun isPanelShown(): Boolean = chromeReady() && contextPanel.visibility == View.VISIBLE &&
        (StudioLayoutInjector.isLandscape(chromeTier) || panelBody.visibility == View.VISIBLE)

    /** Landscape rail: shown, or tucked into its left edge handle. */
    fun setRailOpen(open: Boolean) {
        railOpen = open
        if (!chromeReady()) return
        applyChromeBudget()
        stage.post { syncPreviewTarget() }
    }

    // ---- optional rows between body and transport: timeline · camera row ----

    private fun timelineWanted(): Boolean =
        (timelineOpen ?: (chromeTier != StudioLayoutInjector.Tier.PHONE_LANDSCAPE)) && !fullCanvas

    /** dp the timeline needs if shown (0 = closed by the user / no clips / Full Canvas) */
    private fun timelineWantDp(): Int {
        if (timeline == null || !timelineWanted()) return 0
        val clips = proj?.layers?.count { it.isClip() } ?: 0
        if (clips == 0) return 0
        val rows = minOf(clips, TimelineView.MAX_LANES) + (if (clips > TimelineView.MAX_LANES) 1 else 0)
        return 10 + rows * 16
    }

    private fun cameraRowWantDp(): Int =
        if (!fullCanvas && proj?.layers?.any { it.isLive() } == true) ChromeBudget.CAMERA_ROW_DP else 0

    fun timelineShown(): Boolean = timeline?.visibility == View.VISIBLE && timeline?.hasLanes() == true

    /**
     * dp granted to the optional rows. They are the lowest priority: when they
     * would leave too little flexible body the timeline gives way first, then
     * the camera row — the canvas and the panel never pay for them. (Every
     * camera-row control is also in the Props tab of the live camera, so
     * dropping the row loses nothing.)
     */
    fun extraRowsDp(): Int {
        val cam = cameraRowWantDp()
        val tl = timelineWantDp()
        val landscape = StudioLayoutInjector.isLandscape(chromeTier)
        val tablet = StudioLayoutInjector.isTablet(chromeTier)
        val h = usableHeightDp()
        return when {
            ChromeBudget.extrasFit(h, landscape, tablet, cam + tl) -> cam + tl
            ChromeBudget.extrasFit(h, landscape, tablet, cam) -> cam
            else -> 0
        }
    }

    /** Push the extraRowsDp() decision onto the views (stateless: recomputed from wants). */
    private fun applyExtraRowsFit() {
        val got = extraRowsDp()
        val cam = cameraRowWantDp()
        val tl = timelineWantDp()
        // an empty timeline measures 0dp, so it may stay VISIBLE (it appears with the first clip)
        timeline?.visibility = if (timelineWanted() && (tl == 0 || got >= cam + tl)) View.VISIBLE else View.GONE
        if (this::cameraStrip.isInitialized) cameraStrip.visibility = if (cam > 0 && got >= cam) View.VISIBLE else View.GONE
    }

    fun setTimelineOpen(open: Boolean) {
        timelineOpen = open
        applyChromeBudget()
        bindTimeline()
        stage.post { syncPreviewTarget() }
        if (open && !timelineShown() && timeline?.hasLanes() == true)
            UI.toast(this, "Not enough height for the timeline here")
    }

    private fun bindTimeline() {
        val tl = timeline ?: return
        val p = proj ?: return
        val grew = tl.bind(p.layers, p.durationMs())
        tl.setPlayhead(if (engineReady()) engine.master() else 0L)
        val on = timelineOpen ?: (chromeTier != StudioLayoutInjector.Tier.PHONE_LANDSCAPE)
        timelineBtn?.setIcon(R.drawable.ic_timeline, if (on && tl.hasLanes()) UI.ACCENT2 else UI.FG2,
            if (on) "Hide timeline" else "Show timeline")
        timelineBtn?.alpha = if (tl.hasLanes()) 1f else 0.35f
        if (grew) applyChromeBudget()
    }

    /** Re-apply the dp budget to the existing views (no rebuild, no state loss). */
    private fun applyChromeBudget() {
        if (!chromeReady() || fullCanvas) return
        applyExtraRowsFit()
        if (StudioLayoutInjector.isLandscape(chromeTier)) {
            val b = StudioLayoutInjector.landscapeBudget(this)
            toolRail.visibility = if (b.railShown) View.VISIBLE else View.GONE
            railEdgeHandle?.visibility = if (b.railShown) View.GONE else View.VISIBLE
            if (b.panelDp > 0) {
                val lp = contextPanel.layoutParams as LinearLayout.LayoutParams
                lp.width = UI.dp(this, b.panelDp)
                contextPanel.layoutParams = lp
            }
            contextPanel.visibility = if (b.panelDp > 0) View.VISIBLE else View.GONE
            panelEdgeHandle?.visibility = if (b.panelDp > 0) View.GONE else View.VISIBLE
            // the picture is fitted beside a visible edge handle, never under it
            stage.setViewportInsets(
                if (b.railShown) 0 else UI.dp(this, StudioLayoutInjector.EDGE_DP), 0,
                if (b.panelDp > 0) 0 else UI.dp(this, StudioLayoutInjector.EDGE_DP), 0)
        } else {
            val b = StudioLayoutInjector.portraitBudget(this)
            portraitPanelHeightPx = UI.dp(this, ChromeBudget.TABS_DP + 1 + b.openBodyDp)
            val lp = contextPanel.layoutParams as LinearLayout.LayoutParams
            lp.height = if (b.panelBodyDp > 0) portraitPanelHeightPx else ViewGroup.LayoutParams.WRAP_CONTENT
            contextPanel.layoutParams = lp
            panelBody.visibility = if (b.panelBodyDp > 0) View.VISIBLE else View.GONE
            sourcesPanel?.setCompact(b.panelBodyDp in 1 until ChromeBudget.PORTRAIT_COMPACT_BELOW_DP)
            contextPanel.visibility = View.VISIBLE
            toolRail.visibility = View.VISIBLE
            // the strip's last button is "collapse" while open and "expand" while collapsed
            panelCloseBtn?.setIcon(if (b.panelBodyDp > 0) R.drawable.ic_down else R.drawable.ic_up, UI.FG2,
                if (b.panelBodyDp > 0) "Collapse panel" else "Expand panel")
        }
    }

    fun showTab(id: String, user: Boolean = true) {
        activeTab = id
        if (!chromeReady()) return
        if (user && fullCanvas) setFullCanvas(false)
        val views = listOf(
            "sources" to sourcesPanel, "mixer" to mixerPanel,
            "props" to propertiesPanel, "effects" to effectsPanel)
        for ((k, v) in views) v?.visibility = if (k == id) View.VISIBLE else View.GONE
        for ((k, v) in tabViews) StudioLayoutInjector.styleTab(this, v, k == id)
        if (user) {
            if (!isPanelShown()) setPanelOpen(true)
            val shown = views.firstOrNull { it.first == id }?.second ?: return
            shown.alpha = 0f
            shown.animate().alpha(1f).setDuration(140).start()
        }
    }

    // ---------------- Full Canvas (immersive) ----------------

    private fun setFullCanvas(on: Boolean) {
        if (fullCanvas == on) return
        fullCanvas = on
        if (on && wheelReady()) wheel.dismiss(animated = false)
        applyFullCanvasChrome(on)
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
        applySystemInsets()
        stage.post { syncPreviewTarget() }
        UI.toast(this, if (on) "Full canvas — tap ✕ to return" else "Controls restored")
    }

    /** Hide / restore every bar around the canvas cell; the cell fills the column. */
    private fun applyFullCanvasChrome(on: Boolean) {
        if (!chromeReady()) return
        val vis = if (on) View.GONE else View.VISIBLE
        topBar.visibility = vis
        transportBar.visibility = vis
        // (timeline / camera row visibility on restore is decided by applyExtraRowsFit via applyChromeBudget)
        if (on) {
            timeline?.visibility = View.GONE
            toolRail.visibility = View.GONE
            contextPanel.visibility = View.GONE
            panelEdgeHandle?.visibility = View.GONE
            railEdgeHandle?.visibility = View.GONE
            emptyOverlay.visibility = View.GONE
            statsHud.visibility = View.GONE
            cameraStrip.visibility = View.GONE
        } else {
            applyChromeBudget()
            refreshAll()
        }
        fullExitBtn.visibility = if (on) View.VISIBLE else View.GONE
        // the exit button must clear a cutout while the bars are hidden
        (fullExitBtn.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.topMargin = UI.dp(this, 8) + (if (on) sysT else 0)
            it.rightMargin = UI.dp(this, 8) + (if (on) sysR else 0)
            fullExitBtn.layoutParams = it
        }
    }

    fun exitFullCanvas() = setFullCanvas(false)

    fun buildSnackBarInto(root: FrameLayout) {
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
        lp.setMargins(UI.dp(this, 14), 0, UI.dp(this, 14), UI.dp(this, 60))
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

    fun buildProgOverlayInto(root: FrameLayout) {
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

    /** the progress card's model, kept so a rotation mid-export rebuilds it unchanged */
    private var progState: ProgState? = null
    private class ProgState(val title: String, var msg: String, val determinate: Boolean,
                            var pct: Int, val onCancel: (() -> Unit)?)

    private fun showProgress(title: String, msg: String, determinate: Boolean,
                             onCancel: (() -> Unit)? = null) {
        progState = ProgState(title, msg, determinate, 0, onCancel)
        applyProgressState()
    }

    private fun updateProgress(pct: Int, msg: String) {
        progState?.let { it.pct = pct.coerceIn(0, 100); it.msg = msg }
        progBar?.progress = pct.coerceIn(0, 100)
        progMsg?.text = msg
    }

    private fun dismissProgress() {
        progState = null
        progOverlay?.visibility = View.GONE
        progOnCancel = null
    }

    /** Paint [progState] onto whichever progress card currently exists. */
    private fun applyProgressState() {
        val st = progState
        if (st == null) { progOverlay?.visibility = View.GONE; progOnCancel = null; return }
        progTitle?.text = st.title
        progMsg?.text = st.msg
        progBar?.isIndeterminate = !st.determinate
        progBar?.progress = st.pct
        progBar?.visibility = View.VISIBLE
        progOnCancel = st.onCancel
        progCancel?.visibility = if (st.onCancel != null) View.VISIBLE else View.GONE
        progOverlay?.visibility = View.VISIBLE
    }

    // ================= radial menu entry points =================

    /** Open the root ring, blooming from a chrome button (the ⋯ in the top bar). */
    fun openRootWheelFrom(anchor: View) {
        if (!wheelReady()) return
        val loc = IntArray(2); val rootLoc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        rootFrame.getLocationOnScreen(rootLoc)
        val ax = (loc[0] + anchor.width / 2f) - rootLoc[0]
        val ay = (loc[1] + anchor.height / 2f) - rootLoc[1]
        wheel.show(RadialMenus.root(this), ax, ay)
    }

    /** Open a specific ring at a point (canvas long-press, camera strip, dialogs). */
    fun openWheelLevel(level: RadialMenuView.Level, ax: Float, ay: Float) {
        if (!wheelReady()) return
        wheel.show(level, ax, ay)
    }

    fun onWheelDismissed() { refreshAll() }

    /** "+" in the rail / Sources header: the Add ring, centred on the canvas. */
    fun openAddChooser() {
        if (!wheelReady() || !chromeReady()) { pickMedia(true); return }
        val loc = IntArray(2); val rootLoc = IntArray(2)
        canvasCell.getLocationOnScreen(loc)
        rootFrame.getLocationOnScreen(rootLoc)
        openWheelLevel(RadialMenus.add(this),
            loc[0] - rootLoc[0] + canvasCell.width / 2f,
            loc[1] - rootLoc[1] + canvasCell.height / 2f)
    }

    /** Screen record from a plain button (the ring calls addScreen()). */
    fun startScreenCaptureFromUi() = startScreenCapture()

    /** Effects tab → the existing canvas-background ring, centred on the canvas. */
    fun openCanvasColourRing() {
        if (!wheelReady() || !chromeReady()) return
        val loc = IntArray(2); val rootLoc = IntArray(2)
        canvasCell.getLocationOnScreen(loc)
        rootFrame.getLocationOnScreen(rootLoc)
        openWheelLevel(RadialMenus.background(this),
            loc[0] - rootLoc[0] + canvasCell.width / 2f,
            loc[1] - rootLoc[1] + canvasCell.height / 2f)
    }

    /** REC chip on the canvas: stops whichever capture is running. */
    fun recChipTap() {
        if (ScreenCaptureService.running) { stopScreenCapture(); return }
        val live = proj?.layers?.firstOrNull { it.isLive() }
        if (live != null && liveCam?.recording == true) toggleLiveCameraRecord(live)
    }

    /** Preview-monitor mute: hear or silence playback without touching the project. */
    fun toggleMonitorMute() {
        monitorMuted = !monitorMuted
        if (engineReady()) engine.monitorMuted = monitorMuted
        bindSidePanels()
    }

    // ================= export settings (codec · resolution · quality · fps) =================

    fun openExportSettings() {
        val prefs = editorPrefs()
        val avail = Exporter.Codec.available().ifEmpty { listOf(Exporter.Codec.H264) }
        var codec = avail.firstOrNull { it.name == prefs.getString(PREF_EXP_CODEC, "H264") }
            ?: avail.firstOrNull { it == Exporter.Codec.H264 } ?: avail[0]
        var quality = prefs.getInt(PREF_EXP_QUALITY, EncoderConfig.Quality.BALANCED.ordinal)
        var maxDim = prefs.getInt(PREF_EXP_MAXDIM, 720).let { if (it <= 480) 480 else if (it >= 1080) 1080 else 720 }
        var fps = prefs.getInt(PREF_EXP_FPS, 30).let { if (it == 24) 24 else if (it == 60) 60 else 30 }

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(UI.dp(this, 20), UI.dp(this, 8), UI.dp(this, 20), 0)
        fun group(title: String, labels: List<String>, selected: Int, on: (Int) -> Unit) {
            val t = UI.label(this, title, dim = true, size = 11f)
            (t.layoutParams as LinearLayout.LayoutParams).setMargins(0, UI.dp(this, 10), 0, UI.dp(this, 4))
            col.addView(t)
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            val chips = ArrayList<TextView>()
            fun paint() {
                for ((i, c) in chips.withIndex()) {
                    val sel = i == chips.indexOfFirst { it.tag == "sel" }
                    c.background = Ic.pill(this, if (sel) UI.ACCENT else UI.BG3, 10f,
                        if (sel) Color.argb(120, 255, 200, 160) else Color.argb(70, 255, 255, 255))
                }
            }
            for ((i, lbl) in labels.withIndex()) {
                val c = UI.chip(this, lbl)
                c.textSize = 11.5f
                c.tag = if (i == selected) "sel" else null
                val clp = LinearLayout.LayoutParams(0, UI.dp(this, 36), 1f)
                clp.setMargins(UI.dp(this, 2), 0, UI.dp(this, 2), 0)
                c.layoutParams = clp
                c.setOnClickListener { for (o in chips) o.tag = null; c.tag = "sel"; on(i); paint() }
                chips.add(c); row.addView(c)
            }
            paint()
            col.addView(row)
        }
        group("Codec", avail.map { it.label }, avail.indexOf(codec)) { codec = avail[it] }
        val dims = listOf(480, 720, 1080)
        group("Resolution", dims.map { "${it}p" }, dims.indexOf(maxDim)) { maxDim = dims[it] }
        val qs = EncoderConfig.Quality.entries
        group("Quality", qs.map { it.label }, quality.coerceIn(0, qs.size - 1)) { quality = it }
        val fpss = listOf(24, 30, 60)
        group("Frame rate", fpss.map { "$it fps" }, fpss.indexOf(fps)) { fps = fpss[it] }
        val hint = UI.label(this, "AVI has no Android muxer, so it is import-only. Only codecs this device can encode are listed.", dim = true, size = 10.5f)
        (hint.layoutParams as LinearLayout.LayoutParams).setMargins(0, UI.dp(this, 12), 0, UI.dp(this, 4))
        col.addView(hint)
        val sv = ScrollView(this); sv.addView(col)
        AlertDialog.Builder(this)
            .setTitle("Export settings")
            .setView(sv)
            .setPositiveButton("Export") { _, _ ->
                saveExportPrefs(codec.name, quality, maxDim, fps)
                if (warnLiveBeforeExport()) return@setPositiveButton
                runExport(quality, maxDim, fps, codec)
            }
            .setNeutralButton("Save defaults") { _, _ -> saveExportPrefs(codec.name, quality, maxDim, fps) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ================= source dock (the Sources list rows) =================

    /**
     * (Re)create the source dock bound to the container the injector created
     * inside SourcesPanel. Must run after every chrome build.
     */
    private fun rebindDock() {
        dock = SourceDock(this, dockContainer, { this.proj!! }, { selectedId },
            { id -> select(id) },
            { l, what -> quickToggle(l, what) },
            { l -> engine.toggleLayerPlay(l); markDirty(); refreshAll() },
            { l -> select(l.id); showTab("props") },
            { pushUndo() },
            { from, to -> ctrl.reorderLive(from, to); stage.refresh() },
            { markDirty(); refreshAll() })
    }

    private fun rebuildDock() {
        if (this::dock.isInitialized) dock.rebuild()
    }

    /** Kept as the single "source surfaces changed" hook (dock + panels). */
    private fun rebuildSourceDock() {
        rebuildDock()
        bindSidePanels()
    }

    /** Camera strip + hidden pill + empty overlay follow the project state. */
    private fun refreshContextBar() {
        refreshCameraStrip()
        updateHiddenPill()
        updateEmptyState()
        updateUndoButtons()
    }

    // ================= panel: ADD =================

    /** The column the advanced sheet renders into: the Props tab's body. */
    private val panelContent: LinearLayout get() = propertiesPanel!!.content

    private fun section(title: String) {
        if (propertiesPanel == null) return
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
            // 40dp pills at a 44dp pitch: touch targets, not chips
            val b = StudioLayoutInjector.pillBtn(this, label, UI.FG, UI.BG3, 40) { fn() }
            b.textSize = 11.5f
            b.maxLines = 2
            b.setPadding(UI.dp(this, 6), 0, UI.dp(this, 6), 0)
            val lp = LinearLayout.LayoutParams(0, UI.dp(this, 40), 1f)
            lp.setMargins(UI.dp(this, 3), UI.dp(this, 2), UI.dp(this, 3), UI.dp(this, 2))
            b.layoutParams = lp
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
    fun showAspectPicker() {
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
                if (this::aspectChip.isInitialized) {
                    aspectChip.animate().cancel()
                    aspectChip.animate().scaleX(0.8f).scaleY(0.8f).setDuration(80).withEndAction {
                        changeAspect(next)
                        aspectChip.animate().scaleX(1f).scaleY(1f).setDuration(260)
                            .setInterpolator(OvershootInterpolator(2f)).start()
                    }.start()
                } else changeAspect(next)
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

    fun updateAspectChip() {
        if (!this::aspectChip.isInitialized) return
        aspectChip.text = proj!!.aspect.code + " ▾"
    }

    // ================= export prefs =================

    private fun saveExportPrefs(codecName: String, quality: Int, maxDim: Int, fps: Int) {
        editorPrefs().edit()
            .putString(PREF_EXP_CODEC, codecName)
            .putInt(PREF_EXP_QUALITY, quality)
            .putInt(PREF_EXP_MAXDIM, maxDim)
            .putInt(PREF_EXP_FPS, fps)
            .putBoolean(PREF_HAD_EXPORT, true)
            .apply()
    }

    // ================= source quick toggles =================

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

    /**
     * The advanced sheet = the PROPS tab. Selecting a source and opening its
     * properties are the same act: this selects [l], switches the panel to
     * Props and (re)fills it. PropertiesPanel.bind() calls back into
     * [fillAdvanced] so a re-selection always shows the right source.
     */
    fun openAdvancedSheet(l: Layer) {
        if (fullCanvas) setFullCanvas(false)
        val pp = propertiesPanel ?: return
        if (selectedId != l.id) {
            selectedId = l.id
            stage.refresh()
        }
        rebuildSourceDock()
        pp.bind(l, force = true)
        showTab("props")
        refreshContextBar()
    }

    /** Body of the Props tab for [l]; called by PropertiesPanel.bind(). */
    fun fillAdvanced(l: Layer) {
        if (propertiesPanel == null) return
        panelContent.removeAllViews()

        // header row: type · name · Rename
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(UI.dp(this, 14), UI.dp(this, 6), UI.dp(this, 10), UI.dp(this, 2))
        val hic = android.widget.ImageView(this)
        hic.setImageDrawable(Ic.get(this, Ic.typeIcon(l.type), UI.ACCENT2))
        val hlp = LinearLayout.LayoutParams(UI.dp(this, 18), UI.dp(this, 18))
        hlp.setMargins(0, 0, UI.dp(this, 8), 0)
        hic.layoutParams = hlp
        head.addView(hic)
        val hnm = TextView(this)
        hnm.text = when {
            l.isLive() -> "Live camera"
            l.isText() -> "Text overlay"
            else -> l.type.label
        }
        hnm.setTextColor(UI.FG2)
        hnm.textSize = 11.5f
        head.addView(hnm, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val ren = StudioLayoutInjector.pillBtn(this, "Rename", UI.FG, UI.BG3, 30) { renameLayer(l) }
        head.addView(ren)
        panelContent.addView(head)

        section("APPEARANCE")
        if (!l.isText()) {
            panelButtonRow(panelContent,
                (if (l.fit == Layer.FIT_FIT) "Fit: whole frame" else "Fill: crop to box") to {
                    ctrl.toggleFit(l.id); refillAdvanced(l)
                },
                (if (l.visible) "Hide" else "Show") to {
                    ctrl.toggleVisible(l.id); showHideFeedback(l); refillAdvanced(l)
                })
        } else {
            panelButtonRow(panelContent,
                (if (l.visible) "Hide" else "Show") to {
                    ctrl.toggleVisible(l.id); showHideFeedback(l); refillAdvanced(l)
                },
                (if (l.locked) "Unlock" else "Lock") to {
                    ctrl.toggleLocked(l.id); refillAdvanced(l)
                })
        }
        if (!l.isText()) {
            panelButtonRow(panelContent,
                (if (l.locked) "Unlock" else "Lock") to {
                    ctrl.toggleLocked(l.id); refillAdvanced(l)
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
                    engine.toggleLayerPlay(l); markDirty(); refillAdvanced(l)
                },
                (if (l.loop) "Loop: on" else "Loop: off") to {
                    ctrl.toggleLoop(l.id); refillAdvanced(l)
                })
            panelButtonRow(panelContent,
                (if (l.muted) "Unmute" else "Mute") to {
                    ctrl.toggleMuted(l.id); refillAdvanced(l)
                },
                (if (l.solo) "Solo: on" else "Solo: off") to {
                    ctrl.toggleSolo(l.id); refillAdvanced(l)
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

        if (l.isLive()) {
            section("CAMERA")
            val torchOn = isTorchOn(l) || isFrontTorchOn() || isBackTorchOn() || isBothTorchOn()
            val hasLed = hasFrontTorch() || hasBackTorch()
            panelButtonRow(panelContent,
                (if (torchOn) "Flash: on" else "Flash") to { if (hasLed) openFlashRing(l) else toggleScreenLight() },
                (if (l.camFacing == Layer.FACING_FRONT) "Use back camera" else "Use front camera") to { switchCameraFacing(l) })
            panelButtonRow(panelContent,
                (if (l.mirror) "Mirror: on" else "Mirror: off") to { toggleCameraMirror(l) },
                (if (screenLight) "Screen light: on" else "Screen light: off") to { toggleScreenLight() })
            val taking = isCameraRecording(l)
            panelButtonRow(panelContent,
                (if (taking) "Stop camera take" else "Record a camera take") to { toggleCameraRecord(l) })
        }

        if (l.isText()) {
            section("TEXT")
            panelButtonRow(panelContent,
                "Edit text" to { editTextLayer(l) },
                "Change color" to { cycleTextColor(l); refillAdvanced(l) })
            panelContent.addView(sliderRow("Text size",
                (l.fontSizeN * 1000).toInt().coerceIn(10, 300)) { v ->
                pushUndoLight(); l.fontSizeN = v / 1000f; markDirty(); stage.refresh()
            })
            panelButtonRow(panelContent,
                (if (l.shadow) "Shadow: on" else "Shadow: off") to {
                    pushUndo(); l.shadow = !l.shadow; markDirty(); stage.refresh()
                    refillAdvanced(l)
                })
        }

        section("ARRANGE — z-order (top of the list = front)")
        panelButtonRow(panelContent,
            "Bring forward" to { ctrl.moveZ(l.id, "up"); refillAdvanced(l) },
            "Send backward" to { ctrl.moveZ(l.id, "down"); refillAdvanced(l) })
        panelButtonRow(panelContent,
            "To front" to { ctrl.moveZ(l.id, "front"); refillAdvanced(l) },
            "To back" to { ctrl.moveZ(l.id, "back"); refillAdvanced(l) })
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
        val del = StudioLayoutInjector.pillBtn(this, "Delete source", UI.DANGER, UI.BG3, 40) {
            guardRecording {
                val nm = l.name
                if (l.isLive()) { removeLiveCameraLayer(); showSnack("$nm removed"); return@guardRecording }
                ctrl.delete(l.id); selectedId = null; engine.evict(l.id)
                refreshAll()
                showUndoSnack("Deleted $nm")
            }
        }
        del.contentDescription = "Delete ${l.name}"
        val dlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 40))
        dlp.setMargins(UI.dp(this, 12), UI.dp(this, 2), UI.dp(this, 12), UI.dp(this, 14))
        del.layoutParams = dlp
        panelContent.addView(del)

    }

    /** A toggle inside the Props tab changed state: redraw the same source. */
    private fun refillAdvanced(l: Layer) {
        propertiesPanel?.bind(l, force = true)
        rebuildSourceDock()
    }

    // ================= canvas overlays: empty state · camera strip · hidden pill =================

    private fun updateEmptyState() {
        if (!this::emptyOverlay.isInitialized) return
        val show = (proj?.layers?.isEmpty() == true) && !fullCanvas
        emptyOverlay.visibility = if (show) View.VISIBLE else View.GONE
    }

    /**
     * Live-camera controls, one tap from the camera: Flash · Switch · Mirror ·
     * Screen light · Take. Only exists while a live camera is on the canvas;
     * everything deeper (front/back/both LED) stays in the flash ring.
     */
    private fun refreshCameraStrip() {
        if (!this::cameraStrip.isInitialized) return
        val live = proj?.layers?.firstOrNull { it.isLive() }
        val row = cameraStrip
        val hadContent = row.childCount > 0
        row.removeAllViews()
        if (live == null || fullCanvas) {
            row.visibility = View.GONE
            if (hadContent) applyChromeBudget()
            return
        }
        val label = TextView(this)
        label.text = live.name.ifBlank { "Camera" }
        label.setTextColor(UI.FG2)
        label.textSize = 11.5f
        label.maxLines = 1
        label.ellipsize = android.text.TextUtils.TruncateAt.END
        label.includeFontPadding = false
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun b(icon: Int, desc: String, on: Boolean, onTint: Int = UI.ACCENT2, fn: () -> Unit) {
            val v = IconBtn(this)
            v.layoutParams = LinearLayout.LayoutParams(UI.dp(this, 44), UI.dp(this, 44))
            v.setIcon(icon, if (on) onTint else UI.FG, desc)
            v.setOnClickListener { fn() }
            row.addView(v)
        }
        val torchOn = isTorchOn(live) || isFrontTorchOn() || isBackTorchOn() || isBothTorchOn()
        val hasLed = hasFrontTorch() || hasBackTorch()
        b(R.drawable.ic_flash, if (torchOn) "Flash on — tap for options" else "Flash", torchOn) {
            if (hasLed) openFlashRing(live) else toggleScreenLight()
        }
        b(R.drawable.ic_switch, if (live.camFacing == Layer.FACING_FRONT) "Switch to back camera" else "Switch to front camera", false) {
            switchCameraFacing(live)
        }
        b(R.drawable.ic_loop, if (live.mirror) "Mirror on" else "Mirror off", live.mirror) { toggleCameraMirror(live) }
        b(R.drawable.ic_eye, if (screenLight) "Screen light on" else "Screen light", screenLight, Color.rgb(255, 236, 190)) {
            toggleScreenLight()
        }
        val taking = isCameraRecording(live)
        b(if (taking) R.drawable.ic_stop else R.drawable.ic_camera,
            if (taking) "Stop camera take" else "Record a camera take", taking, UI.DANGER) {
            toggleCameraRecord(live)
        }
        if (!hadContent) applyChromeBudget() else applyExtraRowsFit()
    }

    /** Hidden sources are surfaced by the Sources header (count + tap = select). */
    private fun updateHiddenPill() {
        val hidden = proj?.layers?.filter { !it.visible } ?: emptyList()
        sourcesPanel?.setHidden(hidden.size) { hidden.firstOrNull()?.let { select(it.id) } }
    }

    private fun updateUndoButtons() {
        undoBtn?.alpha = if (undo.canUndo()) 1f else 0.35f
        redoBtn?.alpha = if (undo.canRedo()) 1f else 0.35f
    }

    // ================= REC timer (composite recording) =================

    private val recTimerTick = object : Runnable {
        override fun run() {
            if (!recording || !this@EditorActivity::recordBtn.isInitialized) return
            val el = android.os.SystemClock.elapsedRealtime() - recordStartMs
            recordBtn.text = "■  " + UI.fmtTime(el)
            recordHandler.postDelayed(this, 500L)
        }
    }

    private fun onTick(ms: Long) {
        // engine clock → transport UI (throttled) → stage repaint
        val now = android.os.SystemClock.elapsedRealtime()
        if (!scrubbing && now - lastUiTickMs >= 50L) {
            lastUiTickMs = now
            if (this::timeLabel.isInitialized) timeLabel.text = UI.fmtTime(ms)
            if (this::seek.isInitialized) {
                val max = seek.max
                if (max > 0) seek.progress = ms.toInt().coerceAtMost(max)
            }
            timeline?.setPlayhead(ms)
        }
        // HUD refreshes at ~2 Hz — it reports the engine's own 500 ms window
        if (this::statsHud.isInitialized && now - lastHudMs >= 500L) {
            lastHudMs = now
            val show = !fullCanvas && editorPrefs().getBoolean(PREF_STATS_HUD, false) &&
                (engine.anyPlaying() || recording)
            if (show) {
                val r = recorder
                statsHud.text = if (recording && r != null) engine.stats() + "\n" + r.stats() else engine.stats()
                statsHud.visibility = View.VISIBLE
            } else if (statsHud.visibility != View.GONE) {
                statsHud.visibility = View.GONE
            }
        }
        // reflect play state on the transport button + panels when it changes
        // (also catches a non-looping source auto-pausing on its last frame)
        val sig = playingSignature()
        if (sig != lastPlayingSig) {
            lastPlayingSig = sig
            val playingNow = engine.anyPlaying()
            if (this::playBtn.isInitialized) {
                playBtn.setIcon(if (playingNow) R.drawable.ic_pause else R.drawable.ic_play,
                    Color.WHITE, if (playingNow) "Pause" else "Play")
            }
            // state change (e.g. a non-loop source auto-pausing at its end):
            // refresh all source surfaces so statuses never go stale
            refreshAll()
        }
        if (engineReady() && engine.consumeNewFrames() && this::stage.isInitialized) stage.refresh()
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

    fun togglePlay() {
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
        refreshContextBar(); rebuildSourceDock(); stage.refresh()
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
        updateName()
        updateRecordButton()
        bindSidePanels()
        bindTimeline()
        if (wheelReady() && wheel.isOpen()) wheel.refresh()
    }

    /** Push project state into the four tab bodies (cheap; called on every change). */
    private fun bindSidePanels() {
        val p = proj
        val layers = p?.layers ?: emptyList()
        val sel = selectedId?.let { id -> layers.firstOrNull { it.id == id } }
        sourcesPanel?.bind(layers, selectedId)
        mixerPanel?.bind(layers, selectedId, monitorMuted)
        propertiesPanel?.bind(sel)
        effectsPanel?.bind(sel)
    }

    fun removeSelectedSource() {
        val id = selectedId ?: run {
            UI.toast(this, "Select a source first")
            return
        }
        val l = proj?.layerById(id) ?: return
        if (recording) { UI.toast(this, "Stop the recording before removing a source"); return }
        guardRecording {
            val nm = l.name.ifBlank { l.type.label }
            if (l.isLive()) {
                removeLiveCameraLayer()
                showSnack("$nm removed")
                return@guardRecording
            }
            if (engineReady()) engine.evict(id)
            selectedId = null
            ctrl.delete(id)
            showUndoSnack("Deleted $nm")
        }
    }

    fun controlsStopTap() {
        if (recording) { stopCompositeRecording(); return }
        if (engineReady() && engine.anyPlaying()) {
            engine.pauseAll()
            engine.stopSnapshots()
            refreshAll()
        } else UI.toast(this, "Nothing is playing")
    }


    fun markDirty() {
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
    fun pushUndoLight() {
        if (System.currentTimeMillis() - lastUndoPush > 350) pushUndo()
        lastUndoPush = System.currentTimeMillis()
    }

    fun doUndo() {
        val snap = undo.popUndo { layersJsonOf(proj!!) } ?: return
        applyLayersJson(proj!!, snap)
        selectedId = null
        afterStructureChange()
    }

    fun doRedo() {
        val snap = undo.popRedo { layersJsonOf(proj!!) } ?: return
        applyLayersJson(proj!!, snap)
        selectedId = null
        afterStructureChange()
    }

    private fun afterStructureChange() {
        if (engineReady()) engine.attach(projectId)
        reconcileLiveCamera()
        // aspect can change via undo/redo, so re-sync orientation + chip here
        applyOrientationFor(proj!!.aspect)
        if (this::aspectChip.isInitialized) updateAspectChip()
        if (this::stage.isInitialized) stage.post { syncPreviewTarget() }
        refreshAll()
        val dur = proj!!.durationMs().toInt().coerceAtLeast(1)
        // the transport can be absent for one frame during a chrome rebuild
        if (transportReady()) {
            seek.max = dur
            durationLabel.text = UI.fmtTime(dur.toLong())
        }
        if (this::stage.isInitialized) stage.refresh()
        if (engineReady()) {
            engine.refreshFrames()
            if (engine.anyPlaying()) engine.startSnapshots()
        }
        markDirty()
    }

    fun mutateThen(f: () -> Unit) {
        pushUndo()
        f()
        afterStructureChange()
    }

    // ================= sources: pickers / camera / screen =================

    fun pickMedia(video: Boolean) {
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
        if (isFinishing || isDestroyed) return
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
            recChip.text = "■ SCREEN"
            recChip.contentDescription = "Stop the screen recording"
            recChip.visibility = if (fullCanvas) View.GONE else View.VISIBLE
            UI.toast(this, "Recording screen — tap the REC chip on the canvas to stop")
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

    fun addText() {
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
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ================= misc helpers =================

    fun sliderRow(label: String, value: Int, on: (Int) -> Unit): LinearLayout {
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
            // the Props body lives in a ScrollView: own the gesture while dragging
            override fun onStartTrackingTouch(s: SeekBar?) { s?.parent?.requestDisallowInterceptTouchEvent(true) }
            override fun onStopTrackingTouch(s: SeekBar?) {
                s?.parent?.requestDisallowInterceptTouchEvent(false)
                // one refresh at the end so the label ("Opacity 60%") and the dock catch up
                rebuildSourceDock()
            }
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
        if (!this::topBar.isInitialized) return
        val nameView = findTagged<TextView>(topBar, "name")
        val meta = findTagged<TextView>(topBar, "meta")
        nameView?.text = proj?.name
        val n = proj?.layers?.size ?: 0
        val saved = if (saveDirty) "● unsaved" else "✓ saved"
        meta?.text = "$n source" + (if (n == 1) "" else "s") + " · $saved"
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
    /**
     * The REC pill is the state: idle-ready (orange), recording (red + elapsed
     * time), or "what is missing" (dim). Never hidden — a tap always explains.
     */
    private fun updateRecordButton() {
        if (!this::recordBtn.isInitialized) return
        val p = proj ?: return
        val hasLive = p.layers.any { it.isLive() }
        val hasClip = p.layers.any { it.isClip() }
        val ready = hasLive && hasClip
        // tablets spell it out; phone landscape says "● REC"; phone portrait's
        // 360dp transport gets a 44dp record glyph that widens only for the timer
        val tier = chromeTier
        val glyphOnly = tier == StudioLayoutInjector.Tier.PHONE_PORTRAIT
        val compact = glyphOnly || tier == StudioLayoutInjector.Tier.PHONE_LANDSCAPE
        recordBtn.text = when {
            recording -> (if (glyphOnly) "■ " else "■  ") + UI.fmtTime(android.os.SystemClock.elapsedRealtime() - recordStartMs)
            glyphOnly -> "●"
            ready -> if (compact) "●  REC" else "●  START RECORDING"
            else -> if (compact) "●  REC" else "●  RECORD"
        }
        recordBtn.contentDescription = when {
            recording -> "Stop recording and save"
            ready -> "Start recording"
            !hasLive && !hasClip -> "Record — add a camera and a video first"
            !hasLive -> "Record — add a camera first"
            else -> "Record — add a video first"
        }
        recordBtn.alpha = if (recording || ready) 1f else 0.6f
        recordBtn.background = when {
            recording -> Ic.pill(this, Color.argb(240, 200, 34, 34), 18f, Color.argb(180, 255, 120, 120))
            ready -> Ic.pill(this, Color.argb(240, 255, 90, 44), 18f, Color.argb(140, 255, 200, 160))
            else -> Ic.pill(this, Color.argb(170, 38, 42, 52), 18f, Color.argb(70, 255, 255, 255))
        }
    }

    /** record taps when the setup is incomplete explain + open Add instead of hiding */
    fun recordButtonTap() {
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
            .setPositiveButton("Add now") { _, _ -> openAddChooser() }
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
        recordStartMs = android.os.SystemClock.elapsedRealtime()
        updateRecordButton()
        refreshContextBar()
        recordHandler.removeCallbacks(recordTick)
        recordHandler.post(recordTick)
        recordHandler.removeCallbacks(recTimerTick)
        recordHandler.postDelayed(recTimerTick, 500L)
        UI.toast(this, if (micEnabled || decoded.isNotEmpty())
            "Recording with audio — tap STOP to save" else "Recording — tap STOP to save")
    }

    private fun stopCompositeRecording(showUi: Boolean = true) {
        if (!recording) return
        recording = false
        recordHandler.removeCallbacks(recordTick)
        recordHandler.removeCallbacks(recTimerTick)
        engine.pauseAll()
        engine.stopSnapshots()
        val rec = recorder
        recorder = null
        updateRecordButton()
        refreshContextBar()
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
    fun addLiveCamera() {
        if (isFinishing || isDestroyed || proj == null) return
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
                        // No camera2 device at all. Leave the layer in place
                        // (so undo still works) but stop the feed; only the
                        // first failure auto-opens the fullscreen recorder,
                        // never repeatedly, or a busy camera would bounce the
                        // user between two screens in a loop.
                        stopLiveCamera(evict = true)
                        if (!cameraFallbackShown) {
                            cameraFallbackShown = true
                            UI.toast(this, "No live camera available — opening the recorder once")
                            openCamera()
                        } else UI.toast(this, "No camera available on this device")
                    }
                    "error" -> {
                        stopLiveCamera(evict = true)
                        if (!cameraFallbackShown) {
                            cameraFallbackShown = true
                            UI.toast(this, "Camera busy — opening the fullscreen recorder once")
                            openCamera()
                        } else UI.toast(this, "Camera is busy — close other camera apps and retry")
                    }
                    "disconnected" -> {
                        // Camera went away (another app grabbed it, USB cam
                        // unplugged). Keep the layer: it revives on resume.
                        UI.toast(this, "Camera disconnected — it reconnects when available")
                    }
                    "recfail" -> UI.toast(this, "Could not record this camera take")
                    "busy" -> UI.toast(this, "Camera is busy with the take — stop it first")
                    "torcherror" -> UI.toast(this,
                        liveCam?.torchLastError()?.takeIf { it.isNotBlank() }
                            ?: "Hardware torch unavailable")
                    "recording" -> { recChip.text = "■ TAKE"; recChip.contentDescription = "Stop the camera take"; recChip.visibility = if (fullCanvas) View.GONE else View.VISIBLE }
                    "live" -> { cameraFallbackShown = false; refreshAll() }
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
                        recChip.text = "■ TAKE"
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
        if (this::seek.isInitialized) seek.progress = 0
        onTick(0L)
    }
    override fun nudge(ms: Long) {
        val dur = proj!!.durationMs()
        val t = (engine.master() + ms).coerceIn(0L, dur)
        engine.seekTo(t)
        if (this::seek.isInitialized) seek.progress = t.toInt().coerceAtMost(seek.max)
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
    override fun openDockPanel() { showTab("sources") }
    override fun openMixerPanel() { showTab("mixer") }
    override fun openExportPanel() { openExportSettings() }
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

    /**
     * Screen light = max brightness + a warm-white letterbox surround. The
     * light comes from the stage's own surround (and the canvas cell behind
     * it), so nothing is added over the chrome and nothing covers the frame.
     */
    private fun applyScreenLight() {
        try {
            val lp = window.attributes
            lp.screenBrightness = if (screenLight) 1f else -1f
            window.attributes = lp
        } catch (_: Exception) { }
        if (!this::stage.isInitialized) return
        val light = Color.rgb(255, 246, 232)
        stage.surroundColor = if (screenLight) light else StudioLayoutInjector.CANVAS_BG
        if (this::canvasCell.isInitialized) canvasCell.setBackgroundColor(
            if (screenLight) light else StudioLayoutInjector.CANVAS_BG)
        refreshCameraStrip()
    }

    override fun openFlashRing(l: Layer) {
        openWheelLevel(RadialMenus.flash(this, l.id), -1f, -1f)
    }

    override fun isStatsHudOn(): Boolean =
        editorPrefs().getBoolean(PREF_STATS_HUD, false)

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
