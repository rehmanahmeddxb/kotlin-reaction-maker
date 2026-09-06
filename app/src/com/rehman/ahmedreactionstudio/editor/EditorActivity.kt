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

    lateinit var stage: StageView
    lateinit var emptyOverlay: LinearLayout
    lateinit var playBtn: IconBtn
    lateinit var timeLabel: TextView
    lateinit var durationLabel: TextView
    lateinit var seek: SeekBar
    lateinit var aspectChip: TextView
    lateinit var quickBar: LinearLayout
    private var chromeLayoutListener: android.view.ViewTreeObserver.OnGlobalLayoutListener? = null
    lateinit var panelDivider: View
    lateinit var panelContent: LinearLayout
    lateinit var sheet: LinearLayout
    lateinit var dockContainer: LinearLayout
    lateinit var recChip: TextView
    lateinit var statsHud: TextView
    lateinit var hiddenPill: TextView
    lateinit var wheel: RadialMenuView
    lateinit var dock: SourceDock
    var sourcesPanel: SourcesPanel? = null
    var controlsPanel: ControlsPanel? = null
    var mixerPanel: MixerPanel? = null
    override lateinit var ctrl: SourceController
    lateinit var rootFrame: FrameLayout
    lateinit var studioBtn: IconBtn
    private var wheelBtn: IconBtn? = null
    private var sheetTab: String? = null
    // STEP 5 — professional bottom editor: tab bar + source strip
    lateinit var tabBar: LinearLayout
    lateinit var transportBar: LinearLayout
    lateinit var sourceStripWrap: HorizontalScrollView
    lateinit var sourceStrip: LinearLayout
    private val tabViews = HashMap<String, View>()

    // ===== viewport chrome: the canvas is fitted into what these leave free =====
    lateinit var topBar: LinearLayout
    lateinit var quickWrap: HorizontalScrollView
    /** Shared source strip: existing layers plus collapsible add-source shortcuts. */
    private var srcDockExpanded = false
    /** Full Canvas mode: every overlay hidden except one exit button */
    private var fullCanvas = false
    lateinit var fullExitBtn: TextView
    /** system bar + cutout insets (px), applied by the WindowInsets listener */
    private var sysL = 0; private var sysT = 0; private var sysR = 0; private var sysB = 0
    private val insetsSync = Runnable { applyViewportInsets() }
    /**
     * Orientation-aware chrome. Portrait: everything in the bottom sheet.
     * Landscape: tabs, sources, contextual controls, panel and Record live in
     * a RIGHT RAIL; the bottom sheet is transport only — a 4-row sheet under
     * a 56dp top bar left a 16:9 canvas ~47dp tall on a phone.
     */
    lateinit var panelScroll: ScrollView
    lateinit var launchRow: LinearLayout
    lateinit var sideRail: ScrollView
    lateinit var railContent: LinearLayout
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
    /** true after we've auto-opened the fullscreen recorder once for a
     *  live-camera failure, so a busy camera can never bounce between
     *  screens in a loop; reset whenever a live camera starts cleanly. */
    private var cameraFallbackShown = false

    /** screen flash (front-camera lighting): overlay panel + max brightness */
    private var screenLight = false
    private var screenLightView: View? = null

    lateinit var engine: PreviewEngine
    private val undo = UndoStack()
    private val saveHandler = Handler(Looper.getMainLooper())
    private val autosave = Runnable { flushSave() }
    var scrubbing = false
    private var lastPlayingSig = ""

    private val exportCancel = AtomicBoolean(false)
    private var exportRunning = false

    // ===== composite (multi-source) recording — the RECORD button =====
    lateinit var recordBtn: TextView
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
        bindSidePanels()
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
        super.onSaveInstanceState(out)
    }

    fun engineReady(): Boolean = this::engine.isInitialized

    /** Bottom-sheet transport (seek / duration). The experimental side-panel
     *  layout omits [buildSheet], so these stay uninitialized — callers must
     *  not touch them. */
    private fun transportReady(): Boolean =
        this::seek.isInitialized && this::durationLabel.isInitialized

    private fun sheetReady(): Boolean =
        this::sheet.isInitialized && this::panelScroll.isInitialized &&
            this::panelContent.isInitialized && this::panelDivider.isInitialized

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
        if (wheelReady() && wheel.isOpen()) { wheel.pop(); return }
        if (fullCanvas) { setFullCanvas(false); return }
        if (sheetTab != null) { setSheet(null); return }
        flushSave()
        store.clearOpen(projectId)
        super.onBackPressed()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        rootFrame.removeAllViews()
        com.rehman.ahmedreactionstudio.editor.StudioLayoutInjector.inject(this, rootFrame)
        stage.post { syncPreviewTarget() }
        stage.refresh()
    }
    // ================= UI: fullscreen canvas + floating overlays =================

    private fun buildUi() {
        val root = FrameLayout(this)
        rootFrame = root
        root.setBackgroundColor(UI.BLACK)
        
        com.rehman.ahmedreactionstudio.editor.StudioLayoutInjector.inject(this, root)
        
        setContentView(root)
    }
    private fun updateStageInsets() = applyViewportInsets()

    /** Step 5's 38% panel cap / 28% canvas reserve, including floating controls. */
    private fun capPanelHeight(vararg args: Any?) { }

    private fun buildTopBar(vararg args: Any?) { }

    private fun buildSheet(vararg args: Any?) { }

    private fun buildTabBar(vararg args: Any?) { }

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

    private fun buildTransportBar(vararg args: Any?) { }

    private fun updateSourceStrip(vararg args: Any?) { }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    private fun railWidthPx(): Int =
        (resources.displayMetrics.widthPixels * 0.40f).toInt()
            .coerceIn(UI.dp(this, 220), UI.dp(this, 340))

    private fun buildSideRail(vararg args: Any?) { }

    private fun relayoutChrome(vararg args: Any?) {
        rootFrame.removeAllViews()
        com.rehman.ahmedreactionstudio.editor.StudioLayoutInjector.inject(this, rootFrame)
    }

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

    private fun buildFullCanvasExit(vararg args: Any?) { }

    private fun setFullCanvas(on: Boolean) {
        if (fullCanvas == on) return
        if (!this::topBar.isInitialized || !this::sheet.isInitialized ||
            !this::fullExitBtn.isInitialized) return
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
    private fun applyViewportInsets(vararg args: Any?) { }

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
        if (!wheelReady()) return
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
    fun openWheelLevel(level: RadialMenuView.Level, ax: Float, ay: Float) {
        if (!wheelReady()) return
        setSheet(null)
        wheel.show(level, ax, ay)
    }

    // ================= sheet (only where a ring is the wrong tool) =================

    fun setSheet(tab: String?) {
        if (!sheetReady()) { sheetTab = tab; return }
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

    private fun buildSourcesPanel(vararg args: Any?) { }

    private fun stepSelection(dir: Int) {
        val p = proj ?: return
        if (p.layers.isEmpty()) return
        val i = p.layers.indexOfFirst { it.id == selectedId }
        val next = ((if (i < 0) 0 else i + dir) % p.layers.size + p.layers.size) % p.layers.size
        select(p.layers[next].id)
    }

    // ================= panel: MIXER (sliders need a sheet) =================

    private fun buildMixerPanel(vararg args: Any?) { }

    private fun rebuildDock() {
        if (this::dock.isInitialized) dock.rebuild()
    }

    // ================= panel: ADD =================

    private fun section(title: String) {
        if (!this::panelContent.isInitialized) return
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
        aspectChip.text = proj!!.aspect.code
    }

    // ================= panel: EXPORT =================

    private fun buildExportPanel(vararg args: Any?) { }

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

    private fun refreshQuickBar(vararg args: Any?) { }

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

    fun openAdvancedSheet(l: Layer) {
        if (!sheetReady()) {
            selectedId = l.id
            refreshContextBar(); rebuildDock(); rebuildSourceDock()
            if (this::stage.isInitialized) stage.refresh()
            return
        }
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

    private fun updateEmptyState(vararg args: Any?) { }

    private fun onTick(ms: Long) {
        // Always keep the stage painting even when the experimental layout
        // omitted the transport bar (playBtn / seek never built).
        val now = android.os.SystemClock.elapsedRealtime()
        if (!scrubbing && now - lastUiTickMs >= 50L) {
            lastUiTickMs = now
            if (this::timeLabel.isInitialized) timeLabel.text = UI.fmtTime(ms)
            if (this::seek.isInitialized) {
                val max = seek.max
                if (max > 0) seek.progress = ms.toInt().coerceAtMost(max)
            }
        }
        // HUD refreshes at ~2 Hz — it reports the engine's own 500 ms window
        if (this::statsHud.isInitialized && now - lastHudMs >= 500L) {
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
        refreshContextBar(); rebuildDock(); rebuildSourceDock(); stage.refresh()
        bindSidePanels()
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
        bindSidePanels()
    }

    private fun bindSidePanels() {
        val p = proj
        sourcesPanel?.bind(p?.layers ?: emptyList(), selectedId)
        val hasLive = p?.layers?.any { it.isLive() } == true
        val hasClip = p?.layers?.any { it.isClip() } == true
        val flashOn = liveCam?.isTorchLitForFront() == true ||
            liveCam?.isTorchLitForBack() == true || screenLight
        val playing = engineReady() && engine.anyPlaying()
        controlsPanel?.bind(recording, playing, flashOn, hasLive && hasClip)
        mixerPanel?.bind(p?.layers ?: emptyList(), selectedId)
    }

    fun removeSelectedSource() {
        val id = selectedId ?: run {
            UI.toast(this, "Select a source first")
            return
        }
        val l = proj?.layerById(id) ?: return
        if (l.isLive()) {
            removeLiveCameraLayer()
            return
        }
        if (engineReady()) engine.evict(id)
        selectedId = null
        ctrl.delete(id)
    }

    fun controlsStopTap() {
        if (recording) { stopCompositeRecording(); return }
        if (engineReady() && engine.anyPlaying()) {
            engine.pauseAll()
            engine.stopSnapshots()
            refreshAll()
        } else UI.toast(this, "Nothing is playing")
    }

    fun controlsFlashTap() {
        val live = proj?.layers?.firstOrNull { it.isLive() }
        if (live != null && liveCam != null && liveCam!!.hasFlashUnit) toggleTorch(live)
        else toggleScreenLight()
    }

    private fun updateHiddenPill(vararg args: Any?) { }

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
        // The experimental side-panel layout omits the transport bar, so seek /
        // durationLabel may never have been built. Adding a source (camera
        // permission result, import, undo) must still succeed.
        if (transportReady()) {
            seek.max = dur
            durationLabel.text = "/ " + UI.fmtTime(dur.toLong())
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
                setSheet(null)
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
        if (this::recordBtn.isInitialized) {
            // the contextual bar mirrors the record state (Record / Stop verb)
            if (selectedId == null) refreshContextBar()
            val p = proj
            if (p != null) {
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
                try { refreshTabBar() } catch (_: Exception) {}
            }
        }
        bindSidePanels()
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
                    "recording" -> { recChip.text = "● STOP CAMERA TAKE"; recChip.contentDescription = "Stop the camera take"; recChip.visibility = if (fullCanvas) View.GONE else View.VISIBLE }
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
