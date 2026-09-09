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
        const val PREF_COACHED = "coached"
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
    var chromeLayoutListener: android.view.ViewTreeObserver.OnGlobalLayoutListener? = null
    lateinit var panelContent: LinearLayout
    lateinit var recChip: TextView
    lateinit var statsHud: TextView
    lateinit var hiddenPill: TextView
    lateinit var dock: SourceDock
    lateinit var ctrl: SourceController
    lateinit var rootFrame: FrameLayout
    // ===== floating chrome (full-bleed canvas studio, SIDEBAR_STUDIO_PLAN) =====
    lateinit var topBar: LinearLayout
    lateinit var quickWrap: HorizontalScrollView
    lateinit var sidebar: LinearLayout
    lateinit var sidebarBtn: IconBtn
    lateinit var titleView: TextView
    lateinit var metaView: TextView
    lateinit var undoBtn: IconBtn
    lateinit var redoBtn: IconBtn
    lateinit var savePill: TextView
    lateinit var exportPill: TextView
    lateinit var fullCanvasBtn: IconBtn
    lateinit var overflowBtn: IconBtn
    lateinit var timelinePill: LinearLayout
    lateinit var recordStopBtn: IconBtn
    lateinit var layersHost: LinearLayout
    lateinit var addHost: LinearLayout
    lateinit var layersActionsHost: LinearLayout
    lateinit var sourceSectionBody: LinearLayout
    lateinit var audioHost: LinearLayout
    lateinit var recordSectionBody: LinearLayout
    lateinit var canvasHost: LinearLayout
    lateinit var exportHost: LinearLayout
    lateinit var projectHost: LinearLayout
    /** sidebar section registry, filled by [StudioLayoutInjector.inject] */
    val sec = HashMap<String, StudioLayoutInjector.StudioSection>()
    /** per-section collapsed state, preserved across chrome re-layouts */
    val sectionOpen = HashMap<String, Boolean>()
    /** floating panel open state (hamburger / back button) */
    var sidebarOpen = false
    /** top-strip fit tier (0 labelled / 1 icon-only save+export / 2 no chip) */
    var topTier = -1
    /** true after the first chrome layout (drives the landscape default) */
    var chromeLaidOut = false

    // ===== viewport: chrome floats OVER the canvas; only system insets apply =====
    /** Full Canvas mode: every overlay hidden except one exit button */
    private var fullCanvas = false
    lateinit var fullExitBtn: TextView
    /** system bar + cutout insets (px), applied by the WindowInsets listener */
    private var sysL = 0; private var sysT = 0; private var sysR = 0; private var sysB = 0
    private val insetsSync = Runnable { applyViewportInsets() }
    /** the sidebar's single scroll view (recreated with the chrome) */
    lateinit var panelScroll: ScrollView

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

    // ===== section render bookkeeping (sliders survive refreshes) =====
    /** bumped on every structural / selection change → SOURCE re-renders */
    private var sourceVersion = 0
    private var sourceRenderedKey: String? = null
    private var sourceRenderedVersion = -1
    private var audioRenderedKey: String? = null

    // Throttle clocks for onTick: transport UI at ~20 Hz, stats HUD at ~2 Hz.
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

        buildUi()
        rebindDock()
        rebuildDock()
        refreshAll()
        updateName()
        engine.refreshFrames()
        stage.post { showCoachIfNeeded() }
    }

    private fun showCoachIfNeeded() {
        val prefs = editorPrefs()
        if (prefs.getBoolean(PREF_COACHED, false)) return
        if (proj?.layers?.isNotEmpty() == true) return
        // first launch coach — 3-step onboarding
        val steps = listOf(
            "Welcome to Ahmed Reaction Studio — your canvas is 100% of the screen. All controls float over it." to "Tap ☰ to open the sidebar with 7 sections.",
            "Layers shows every source. Add Camera (live) to put yourself on canvas, then frame it with drag & pinch." to "The quick bar above the canvas is your one-tap verbs.",
            "Record needs a live camera + a video. Export saves to your phone. Long-press a source for more." to "You're set — add a source to begin!"
        )
        var idx = 0
        fun showStep() {
            if (idx >= steps.size) {
                prefs.edit().putBoolean(PREF_COACHED, true).apply()
                return
            }
            val (title, sub) = steps[idx]
            AlertDialog.Builder(this)
                .setTitle("Step ${idx + 1} of ${steps.size}")
                .setMessage("$title\n\n$sub")
                .setPositiveButton(if (idx == steps.size - 1) "Got it" else "Next") { _, _ ->
                    idx++
                    showStep()
                }
                .setNegativeButton("Skip") { _, _ ->
                    prefs.edit().putBoolean(PREF_COACHED, true).apply()
                }
                .setCancelable(false)
                .show()
        }
        showStep()
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
        if (fullCanvas) { setFullCanvas(false); return }
        if (sidebarOpen) { StudioLayoutInjector.setSidebarOpen(this, false); return }
        flushSave()
        store.clearOpen(projectId)
        super.onBackPressed()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        rootFrame.removeAllViews()
        StudioLayoutInjector.inject(this, rootFrame)
        rebindDock()
        rebuildDock()
        refreshAll()
        updateRecordButton()
        stage.post { syncPreviewTarget() }
        stage.refresh()
    }
    // ================= UI: full-bleed canvas + floating overlay chrome =================
    //
    // The canvas owns 100% of the screen (docs/SIDEBAR_STUDIO_PLAN.md). All
    // controls float over it as translucent chrome, so the full composition
    // stays visible while recording. Chrome is built by StudioLayoutInjector.

    private fun buildUi() {
        val root = FrameLayout(this)
        rootFrame = root
        root.setBackgroundColor(UI.BLACK)
        StudioLayoutInjector.inject(this, root)
        setContentView(root)
        applyViewportInsets()
    }

    /**
     * Single gate for the layout injector (a different file cannot use
     * `this::field.isInitialized`): true once the floating chrome is built.
     */
    fun chromeInitialized(): Boolean =
        this::topBar.isInitialized && this::timelinePill.isInitialized &&
            this::sidebar.isInitialized

    /** Re-fit the floating chrome after any layout pass (strip tiers + timeline). */
    fun fitChrome() {
        StudioLayoutInjector.fitTopStrip(this)
        StudioLayoutInjector.layoutTimeline(this)
        refreshTopStrip()
    }

    /**
     * Only system-bar / cutout insets reach the stage — chrome floats over the
     * canvas and never shrinks it. Called after every layout pass (cheap:
     * StageView ignores unchanged values), so rotating the phone, toggling
     * Full Canvas or showing a cutout all keep the whole composition on screen.
     */
    fun applyViewportInsets() {
        if (!this::stage.isInitialized) return
        stage.setViewportInsets(sysL, sysT, sysR, sysB)
    }

    /** WindowInsets listener entry (wired by the layout injector). */
    fun applyWindowInsets(insets: android.view.WindowInsets) {
        readSystemInsets(insets)
        applyViewportInsets()
        StudioLayoutInjector.fitTopStrip(this)
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    /**
     * Full Canvas: every floating chrome view hides and the system bars go
     * away, so the canvas is literally 100% of the physical screen.
     * The exit pill is the only thing left.
     */
    fun setFullCanvas(on: Boolean) {
        if (fullCanvas == on) return
        if (!this::topBar.isInitialized || !this::fullExitBtn.isInitialized) return
        fullCanvas = on
        if (on) StudioLayoutInjector.setSidebarOpen(this, false, animate = false)
        val vis = if (on) View.GONE else View.VISIBLE
        topBar.visibility = vis
        quickWrap.visibility = vis
        recChip.visibility = vis
        statsHud.visibility = vis
        emptyOverlay.visibility = vis
        timelinePill.visibility = vis
        hiddenPill.visibility = vis
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
        if (on) UI.toast(this, "Full canvas — tap ✕ to return")
        else {
            recChip.visibility = if (ScreenCaptureService.running || liveCam?.recording == true)
                View.VISIBLE else View.GONE
            refreshAll()
            UI.toast(this, "Controls restored")
        }
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

    /** Show a message with an optional action (e.g. "Source hidden" + UNDO).
     *  The bar itself is chrome, so it lives in the layout injector. */
    private fun showSnack(msg: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        StudioLayoutInjector.showSnack(this, msg, actionLabel, action)
    }

    private fun showUndoSnack(msg: String) = showSnack(msg, "UNDO") { doUndo() }

    // ================= progress overlay (themed, cancellable) =================
    // The overlay view is chrome — built by the layout injector.

    private fun showProgress(title: String, msg: String, determinate: Boolean,
                             onCancel: (() -> Unit)? = null) {
        StudioLayoutInjector.showProgress(this, title, msg, determinate, onCancel)
    }

    private fun updateProgress(pct: Int, msg: String) {
        StudioLayoutInjector.updateProgress(this, pct, msg)
    }

    private fun dismissProgress() {
        StudioLayoutInjector.dismissProgress(this)
    }

    // ================= sidebar entry points =================

    /** Open the floating sidebar and expand the named section. */
    fun openSidebarAt(id: String) {
        if (fullCanvas) return
        StudioLayoutInjector.setSidebarOpen(this, true)
        StudioLayoutInjector.setSection(this, id, true)
    }

    fun toggleSidebar() {
        StudioLayoutInjector.toggleSidebar(this)
    }

    /** Injector hook: called when a section header is expanded by the user. */
    fun onSectionOpened(id: String) {
        if (id == "source") refreshSourceSection()
    }

    // ================= Layers section: the dock =================

    /**
     * (Re)create the live-camera / source dock inside the sidebar's LAYERS
     * section and its callbacks. The layout chrome ([StudioLayoutInjector])
     * installs a lightweight stand-in dock on every build (including each
     * [onConfigurationChanged] re-layout), so it must be rebound to a real
     * [SourceDock] whenever the chrome is rebuilt — otherwise the stand-in
     * throws on the next [rebuildDock]. Call right after any chrome (re)build.
     */
    private fun rebindDock() {
        if (!this::layersHost.isInitialized) return
        layersHost.removeAllViews()
        dock = SourceDock(this, layersHost, { this.proj!! }, { selectedId },
            { id -> select(id) },
            { l, what -> quickToggle(l, what) },
            { l -> engine.toggleLayerPlay(l); bumpSource(); markDirty(); refreshAll() },
            { l -> openSourceSection(l) },
            { pushUndo() },
            { from, to -> ctrl.reorderLive(from, to); stage.refresh() },
            { markDirty(); refreshAll() })
    }

    private fun rebuildDock() {
        if (this::dock.isInitialized) dock.rebuild()
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

    // ================= export settings =================

    private fun saveExportPrefs(codecName: String, quality: Int, maxDim: Int, fps: Int) {
        editorPrefs().edit()
            .putString(PREF_EXP_CODEC, codecName)
            .putInt(PREF_EXP_QUALITY, quality)
            .putInt(PREF_EXP_MAXDIM, maxDim)
            .putInt(PREF_EXP_FPS, fps)
            .putBoolean(PREF_HAD_EXPORT, true)
            .apply()
    }

    // ================= Quick Control Bar (floating, over the canvas) =================

    /**
     * One icon per verb, for the selected source only:
     * hide · mute · pause · lock · fit/fill/stretch · (camera: take · switch) · more · delete.
     * Everything else lives in the sidebar's SOURCE section — one tap away.
     */
    private fun refreshQuickBar() {
        if (!this::quickBar.isInitialized) return
        quickBar.removeAllViews()
        val l = selected()
        if (l == null || fullCanvas) {
            quickWrap.visibility = View.GONE
            return
        }
        quickWrap.visibility = View.VISIBLE
        fun add(icon: Int, desc: String, tint: Int, onTap: () -> Unit) {
            val b = IconBtn(this)
            b.setIcon(icon, tint, desc)
            b.setOnClickListener { onTap() }
            val lp = LinearLayout.LayoutParams(UI.dp(this, 40), UI.dp(this, 40))
            lp.setMargins(UI.dp(this, 3), 0, UI.dp(this, 3), 0)
            quickBar.addView(b, lp)
        }
        add(if (l.visible) R.drawable.ic_eye_off else R.drawable.ic_eye,
            if (l.visible) "Hide ${l.name}" else "Show ${l.name}", UI.FG) {
            ctrl.toggleVisible(l.id); showHideFeedback(l)
        }
        if (l.isVideoLike()) {
            val m = ctrl.effectiveMuted(l)
            add(if (m) R.drawable.ic_volume_off else R.drawable.ic_volume,
                if (m) "Unmute ${l.name}" else "Mute ${l.name}", if (m) UI.DANGER else UI.FG) {
                ctrl.toggleMuted(l.id)
            }
        }
        if (l.isClip()) {
            add(if (l.playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (l.playing) "Pause ${l.name}" else "Play ${l.name}", UI.ACCENT2) {
                toggleSourcePlay(l)
            }
        }
        add(if (l.locked) R.drawable.ic_lock else R.drawable.ic_lock_open,
            if (l.locked) "Unlock ${l.name}" else "Lock ${l.name}",
            if (l.locked) UI.ACCENT2 else UI.FG) {
            ctrl.toggleLocked(l.id)
        }
        if (!l.isText()) {
            // the Fit control cycles Fit → Fill → Stretch → Fit; the quick
            // bar shows the ACTION (the next mode), like hide/mute/lock do
            val (fitIcon, fitDesc) = when (l.fit) {
                Layer.FIT_FIT -> Pair(R.drawable.ic_fill, "Fill: crop to box")
                Layer.FIT_FILL -> Pair(R.drawable.ic_aspect, "Stretch: fill the box exactly")
                else -> Pair(R.drawable.ic_fit, "Fit: whole frame")
            }
            add(fitIcon, fitDesc, UI.FG) {
                ctrl.toggleFit(l.id)
            }
        }
        if (l.isLive()) {
            val rec = isCameraRecording(l)
            add(if (rec) R.drawable.ic_stop else R.drawable.ic_camera,
                if (rec) "Stop camera take" else "Record camera take",
                if (rec) UI.DANGER else UI.FG) {
                toggleCameraRecord(l)
            }
            add(R.drawable.ic_switch, "Switch camera", UI.FG) {
                switchCameraFacing(l)
            }
        }
        add(R.drawable.ic_more, "More controls", UI.FG2) {
            openSourceSection(l)
        }
        add(R.drawable.ic_delete, "Delete ${l.name}", UI.DANGER) {
            guardRecording {
                val nm = l.name
                deleteSourceSafely(l)
                showUndoSnack("Deleted $nm")
            }
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
     * The selected source's full control set, rendered into the sidebar's
     * SOURCE section (replaces the old advanced sheet). One tap away from
     * long-pressing a source, the quick bar's ⋮ or the dock row.
     */
    fun openSourceSection(l: Layer) {
        if (fullCanvas) setFullCanvas(false)
        select(l.id)
        StudioLayoutInjector.setSidebarOpen(this, true)
        StudioLayoutInjector.setSection(this, "source", true)
    }

    /** bump so SOURCE / AUDIO sections re-render with fresh state */
    private fun bumpSource() {
        sourceVersion++
    }

    private fun refreshSourceSection() {
        if (!this::sourceSectionBody.isInitialized) return
        val l = selected()
        val key = l?.id ?: "none"
        if (key == sourceRenderedKey && sourceRenderedVersion == sourceVersion) return
        sourceRenderedKey = key
        sourceRenderedVersion = sourceVersion
        val body = sourceSectionBody
        body.removeAllViews()
        if (l == null) {
            StudioLayoutInjector.noteRow(this, body,
                "Select a source — tap a layer above, or a source on the canvas.")
            return
        }

        // header: type icon + name + rename chip
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(UI.dp(this, 4), UI.dp(this, 2), UI.dp(this, 4), UI.dp(this, 4))
        val hic = android.widget.ImageView(this)
        hic.setImageDrawable(Ic.get(this, Ic.typeIcon(l.type), UI.ACCENT2))
        val hlp = LinearLayout.LayoutParams(UI.dp(this, 20), UI.dp(this, 20))
        hlp.setMargins(0, 0, UI.dp(this, 10), 0)
        hic.layoutParams = hlp
        head.addView(hic)
        val hnm = TextView(this)
        hnm.text = l.name.ifBlank { l.type.name }
        hnm.setTextColor(Color.WHITE)
        hnm.textSize = 13.5f
        hnm.maxLines = 1
        hnm.ellipsize = android.text.TextUtils.TruncateAt.END
        hnm.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        head.addView(hnm, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val ren = UI.chip(this, "Rename")
        ren.setOnClickListener { renameLayer(l) }
        head.addView(ren)
        body.addView(head)

        StudioLayoutInjector.subLabel(this, body, "Appearance")
        StudioLayoutInjector.actRow(this, body,
            if (l.visible) R.drawable.ic_eye_off else R.drawable.ic_eye,
            if (l.visible) "Hide" else "Show", active = !l.visible) {
            ctrl.toggleVisible(l.id); showHideFeedback(l)
        }
        StudioLayoutInjector.actRow(this, body,
            if (l.locked) R.drawable.ic_lock else R.drawable.ic_lock_open,
            if (l.locked) "Unlock" else "Lock", active = l.locked) {
            ctrl.toggleLocked(l.id)
        }
        if (!l.isText()) {
            // Naming standard (used in every surface): Fit = whole frame,
            // Fill = crop to box, Stretch = picture fills the box exactly.
            // Never "Fill" for background promotion.
            val (fitIcon, fitLabel, fitNext) = when (l.fit) {
                Layer.FIT_FIT -> Triple(R.drawable.ic_fit, "Fit: whole frame",
                    "tap for Fill: crop to box")
                Layer.FIT_FILL -> Triple(R.drawable.ic_fill, "Fill: crop to box",
                    "tap for Stretch: fill box")
                else -> Triple(R.drawable.ic_aspect, "Stretch: fill box",
                    "tap for Fit: whole frame")
            }
            StudioLayoutInjector.actRow(this, body, fitIcon, fitLabel, fitNext,
                active = l.fit != Layer.FIT_FILL) {
                ctrl.toggleFit(l.id)
            }
            if (l.fit == Layer.FIT_STRETCH) {
                StudioLayoutInjector.noteRow(this, body,
                    "Stretched — the picture fills the box exactly, so it can " +
                    "look squashed. A handle drag stretched it; tap the row " +
                    "above for Fit or Fill to restore the aspect.")
            }
        }
        body.addView(sliderRow("Opacity  ${(l.opacity * 100).toInt()}%",
            (l.opacity * 100).toInt()) { v ->
            pushUndoLight(); l.opacity = v / 100f; markDirty(); stage.refresh()
        })

        if (l.isClip()) {
            StudioLayoutInjector.subLabel(this, body, "Playback & audio")
            StudioLayoutInjector.actRow(this, body,
                if (l.playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (l.playing) "Pause source" else "Play source", active = !l.playing) {
                engine.toggleLayerPlay(l); bumpSource(); markDirty(); refreshAll()
            }
            StudioLayoutInjector.actRow(this, body, R.drawable.ic_loop,
                if (l.loop) "Loop: on" else "Loop: off", active = l.loop) {
                ctrl.toggleLoop(l.id)
            }
        }
        if (l.isVideoLike()) {
            if (!l.isClip()) StudioLayoutInjector.subLabel(this, body, "Audio")
            StudioLayoutInjector.actRow(this, body,
                if (ctrl.effectiveMuted(l)) R.drawable.ic_volume_off else R.drawable.ic_volume,
                if (ctrl.effectiveMuted(l)) "Unmute" else "Mute",
                active = ctrl.effectiveMuted(l),
                badge = if (ctrl.effectiveMuted(l) && !l.muted) "via solo" else null) {
                ctrl.toggleMuted(l.id)
            }
            StudioLayoutInjector.actRow(this, body, R.drawable.ic_star,
                if (l.solo) "Solo: on" else "Solo: off", active = l.solo) {
                ctrl.toggleSolo(l.id)
            }
            body.addView(sliderRow("Volume  ${(l.volume * 100).toInt()}%",
                (l.volume * 100).toInt()) { v ->
                pushUndoLight()
                if (engineReady()) engine.setVolume(l, v / 100f) else l.volume = v / 100f
                markDirty()
            })
            StudioLayoutInjector.noteRow(this, body,
                "Solo = only soloed sources are heard (nothing else is changed or lost).")
        }

        if (l.isLive()) {
            StudioLayoutInjector.subLabel(this, body, "Camera")
            StudioLayoutInjector.actRow(this, body,
                if (isCameraRecording(l)) R.drawable.ic_stop else R.drawable.ic_camera,
                if (isCameraRecording(l)) "Stop take" else "Record take",
                active = isCameraRecording(l), danger = isCameraRecording(l)) {
                toggleCameraRecord(l)
            }
            StudioLayoutInjector.actRow(this, body, R.drawable.ic_switch,
                "Switch camera") {
                switchCameraFacing(l)
            }
            StudioLayoutInjector.actRow(this, body, R.drawable.ic_loop,
                if (l.mirror) "Mirror: on" else "Mirror: off", active = l.mirror) {
                toggleCameraMirror(l)
            }
            StudioLayoutInjector.subLabel(this, body, "Light")
            rebuildLightRows(body, l)
        }

        if (l.isText()) {
            StudioLayoutInjector.subLabel(this, body, "Text")
            StudioLayoutInjector.actRow(this, body, R.drawable.ic_edit, "Edit text") {
                editTextLayer(l)
            }
            StudioLayoutInjector.actRow(this, body, R.drawable.ic_palette, "Change colour") {
                cycleTextColor(l); bumpSource()
            }
            body.addView(sliderRow("Text size",
                (l.fontSizeN * 1000).toInt().coerceIn(10, 300)) { v ->
                pushUndoLight(); l.fontSizeN = v / 1000f; markDirty(); stage.refresh()
            })
            StudioLayoutInjector.actRow(this, body, R.drawable.ic_text,
                if (l.shadow) "Shadow: on" else "Shadow: off", active = l.shadow) {
                pushUndo(); l.shadow = !l.shadow; markDirty(); stage.refresh()
                bumpSource()
            }
        }

        StudioLayoutInjector.subLabel(this, body, "Arrange — z-order (top = front)")
        StudioLayoutInjector.actRow(this, body, R.drawable.ic_up, "Bring forward") {
            ctrl.moveZ(l.id, "up")
        }
        StudioLayoutInjector.actRow(this, body, R.drawable.ic_down, "Send backward") {
            ctrl.moveZ(l.id, "down")
        }
        StudioLayoutInjector.actRow(this, body, R.drawable.ic_up, "To front",
            badge = "front") {
            ctrl.moveZ(l.id, "front")
        }
        StudioLayoutInjector.actRow(this, body, R.drawable.ic_down, "To back",
            badge = "back") {
            ctrl.moveZ(l.id, "back")
        }
        StudioLayoutInjector.actRow(this, body, R.drawable.ic_corner_tl, "Corner: top-left") {
            ctrl.anchor(l.id, "tl")
        }
        StudioLayoutInjector.actRow(this, body, R.drawable.ic_corner_tr, "Corner: top-right") {
            ctrl.anchor(l.id, "tr")
        }
        StudioLayoutInjector.actRow(this, body, R.drawable.ic_corner_bl, "Corner: bottom-left") {
            ctrl.anchor(l.id, "bl")
        }
        StudioLayoutInjector.actRow(this, body, R.drawable.ic_corner_br, "Corner: bottom-right") {
            ctrl.anchor(l.id, "br")
        }
        StudioLayoutInjector.actRow(this, body, R.drawable.ic_center,
            "Centre + unrotate") {
            ctrl.center(l.id); ctrl.resetGeometry(l.id)
        }
        if (!l.isText()) {
            StudioLayoutInjector.actRow(this, body, R.drawable.ic_fill, "Set as background") {
                ctrl.setAsCanvasBackground(l.id)
            }
        }
        StudioLayoutInjector.subLabel(this, body, "Actions")
        StudioLayoutInjector.actRow(this, body, R.drawable.ic_copy, "Duplicate",
            enabled = !l.isLive(),
            badge = if (l.isLive()) "LIVE" else null) {
            duplicateLayer(l)
        }
        StudioLayoutInjector.subLabel(this, body, "Danger")
        StudioLayoutInjector.actRow(this, body, R.drawable.ic_delete, "Delete",
            danger = true) {
            guardRecording {
                val nm = l.name
                deleteSourceSafely(l)
                showUndoSnack("Deleted $nm")
            }
        }
    }

    // ================= AUDIO section (sliders → versioned rebuild) =================

    private fun refreshAudioSection() {
        if (!this::audioHost.isInitialized) return
        val p = proj ?: return
        val mixable = p.layers.filter { it.isVideoLike() }
        val key = mixable.joinToString(",") { it.id } + "v" + sourceVersion
        if (key == audioRenderedKey) return
        audioRenderedKey = key
        val host = audioHost
        host.removeAllViews()
        if (mixable.isEmpty()) {
            StudioLayoutInjector.noteRow(this, host,
                "No video or camera sources yet. Add one to control its audio here.")
            return
        }
        for (l in mixable.asReversed()) addAudioStrip(host, l)
    }

    private fun addAudioStrip(host: LinearLayout, l: Layer) {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        val act = this
        wrap.setPadding(UI.dp(act, 10), UI.dp(act, 8), UI.dp(act, 10), UI.dp(act, 8))
        wrap.background = GradientDrawable().apply {
            cornerRadius = UI.dpf(act, 12f)
            setColor(Color.argb(45, 255, 255, 255))
            setStroke(UI.dp(act, 1), Color.argb(30, 255, 255, 255))
        }
        wrap.elevation = UI.dpf(act, 1f)
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val icon = android.widget.ImageView(this)
        icon.setImageDrawable(Ic.get(this, Ic.typeIcon(l.type), UI.FG2))
        head.addView(icon, LinearLayout.LayoutParams(UI.dp(this, 16), UI.dp(this, 16)))
        val nm = TextView(this)
        nm.text = l.name.ifBlank { l.type.label }
        nm.setTextColor(UI.FG)
        nm.textSize = 12f
        nm.maxLines = 1
        nm.ellipsize = android.text.TextUtils.TruncateAt.END
        val nlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        nlp.marginStart = UI.dp(this, 6)
        nm.layoutParams = nlp
        head.addView(nm)

        val anySolo = proj?.layers?.any { it.solo } == true
        val effMuted = l.muted || (anySolo && !l.solo)
        fun chip(label: String, on: Boolean, onColor: Int, desc: String, onTap: () -> Unit): TextView {
            val c = TextView(act)
            c.text = label
            c.gravity = Gravity.CENTER
            c.setTextColor(if (on) Color.rgb(16, 16, 18) else UI.FG2)
            c.textSize = 11f
            c.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            c.setPadding(UI.dp(act, 8), 0, UI.dp(act, 8), 0)
            c.background = GradientDrawable().apply {
                cornerRadius = UI.dpf(act, 8f)
                setColor(if (on) onColor else UI.BG3)
                setStroke(1, Color.argb(60, 255, 255, 255))
            }
            c.contentDescription = desc
            c.isClickable = true
            c.isFocusable = true
            c.setOnClickListener { onTap() }
            return c
        }
        val m = chip("M", effMuted, UI.DANGER,
            if (effMuted) "Unmute ${l.name}" else "Mute ${l.name}") { ctrl.toggleMuted(l.id) }
        head.addView(m, LinearLayout.LayoutParams(UI.dp(this, 32), UI.dp(this, 26)))
        val s = chip("S", l.solo, UI.ACCENT2,
            if (l.solo) "Unsolo ${l.name}" else "Solo ${l.name}") { ctrl.toggleSolo(l.id) }
        val slp = LinearLayout.LayoutParams(UI.dp(this, 32), UI.dp(this, 26))
        slp.marginStart = UI.dp(this, 6)
        head.addView(s, slp)
        wrap.addView(head)
        if (effMuted && !l.muted) {
            val why = TextView(this)
            why.text = "Silent — another source is soloed"
            why.setTextColor(UI.ACCENT2)
            why.textSize = 10f
            why.setPadding(UI.dp(this, 2), UI.dp(this, 2), 0, 0)
            wrap.addView(why)
        }
        val pct = (l.volume * 100).toInt().coerceIn(0, 100)
        val sb = SeekBar(this)
        sb.max = 100
        sb.progress = pct
        sb.contentDescription = "Volume of ${l.name}"
        sb.progressTintList = android.content.res.ColorStateList.valueOf(UI.ACCENT)
        sb.thumbTintList = android.content.res.ColorStateList.valueOf(UI.ACCENT2)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sk: SeekBar?, v: Int, fromUser: Boolean) {
                if (!fromUser) return
                pushUndoLight()
                if (engineReady()) engine.setVolume(l, v / 100f) else l.volume = v / 100f
                markDirty()
            }
            override fun onStartTrackingTouch(sk: SeekBar?) {}
            override fun onStopTrackingTouch(sk: SeekBar?) {}
        })
        val bslp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        bslp.topMargin = UI.dp(this, 2)
        wrap.addView(sb, bslp)
        host.addView(wrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, UI.dp(act, 2), 0, UI.dp(act, 2))
        })
    }

    // ================= RECORD section (state → cheap full rebuild) =================

    private fun rebuildRecordSection() {
        if (!this::recordSectionBody.isInitialized) return
        val body = recordSectionBody
        body.removeAllViews()
        val p = proj ?: return
        val hasLive = p.layers.any { it.isLive() }
        val hasClip = p.layers.any { it.isClip() }
        val ready = hasLive && hasClip
        val reason = when {
            recording || ready -> null
            !hasLive && !hasClip -> "Recording needs a live camera and a video on the canvas."
            !hasLive -> "Add a live camera to record."
            else -> "Add a video to record with the camera."
        }
        if (reason != null) StudioLayoutInjector.noteRow(this, body, reason)
        val playing = engineReady() && engine.anyPlaying()
        StudioLayoutInjector.actRow(this, body,
            if (playing) R.drawable.ic_pause else R.drawable.ic_play,
            if (playing) "Pause master" else "Play master", active = !playing) {
            togglePlay()
        }
        StudioLayoutInjector.actRow(this, body, R.drawable.ic_stop, "Stop playback") {
            controlsStopTap()
        }
        StudioLayoutInjector.actRow(this, body, R.drawable.ic_camera, "Snapshot frame",
            "freeze the canvas as an image layer") {
            snapshotFrame()
        }
        StudioLayoutInjector.actRow(this, body, R.drawable.ic_reset, "Restart timeline") {
            restart()
        }
        StudioLayoutInjector.subLabel(this, body, "Light")
        rebuildLightRows(body, null)
    }

    /** Front / Back / Both / Screen rows — capability-aware, never fake. */
    private fun rebuildLightRows(body: LinearLayout, cam: Layer?) {
        val live = cam ?: proj?.layers?.firstOrNull { it.isLive() }
        if (live == null || liveCam == null) {
            StudioLayoutInjector.noteRow(this, body,
                "Add a live camera to control its light.")
            return
        }
        val frontPending = liveCam?.isTorchPendingForFront() == true
        val backPending = liveCam?.isTorchPendingForBack() == true
        StudioLayoutInjector.actRow(this, body, R.drawable.ic_flash,
            if (isFrontTorchOn()) "Front flash: on" else "Front flash: off",
            active = isFrontTorchOn(),
            badge = if (isFrontTorchOn()) (if (hasFrontTorch()) "LED" else "SCREEN") else null,
            enabled = hasFrontLight(),
            sub = when {
                frontPending -> "Waiting for camera LED — will light when free"
                hasFrontTorch() -> null
                else -> "No front LED — uses screen light"
            }) {
            toggleFrontTorch()
        }
        StudioLayoutInjector.actRow(this, body, R.drawable.ic_flash,
            if (isBackTorchOn()) "Back flash: on" else "Back flash: off",
            active = isBackTorchOn(), badge = if (isBackTorchOn()) "LED" else null,
            enabled = hasBackTorch(),
            sub = when {
                backPending -> "Waiting for camera LED — will light when free"
                hasBackTorch() -> null
                else -> "No rear LED on this device"
            }) {
            toggleBackTorch()
        }
        if (hasBackTorch() && hasFrontLight()) {
            StudioLayoutInjector.actRow(this, body, R.drawable.ic_flash,
                if (isBothTorchOn()) "Both flashes: on" else "Both flashes: off",
                active = isBothTorchOn(),
                sub = if (hasFrontTorch()) null else "Front uses screen light + rear LED") {
                toggleBothTorch()
            }
        }
        StudioLayoutInjector.actRow(this, body, R.drawable.ic_eye,
            if (isScreenLightOn()) "Screen light: on" else "Screen light: off",
            active = isScreenLightOn(), badge = if (isScreenLightOn()) "BRIGHT" else null) {
            toggleScreenLight()
        }
    }

    // ================= CANVAS section =================

    private fun rebuildCanvasSection() {
        if (!this::canvasHost.isInitialized) return
        val body = canvasHost
        body.removeAllViews()
        val p = proj ?: return
        StudioLayoutInjector.subLabel(this, body, "Aspect")
        val hints = mapOf(
            Aspect.R169 to "YouTube · landscape",
            Aspect.R916 to "Reels · Shorts · TikTok",
            Aspect.R11 to "Square posts"
        )
        for (a in Aspect.entries) {
            StudioLayoutInjector.actRow(this, body, R.drawable.ic_aspect, a.code,
                hints[a], active = p.aspect == a,
                badge = if (p.aspect == a) "ON" else null) {
                changeAspect(a)
            }
        }
        StudioLayoutInjector.actRow(this, body, R.drawable.ic_fullscreen, "Full screen canvas",
            active = fullCanvas, badge = if (fullCanvas) "ON" else null) {
            enterFullCanvas()
        }
        StudioLayoutInjector.actRow(this, body, R.drawable.ic_fit,
            "Fit all sources", "every source shows its whole frame") {
            fitAllSources()
        }
        StudioLayoutInjector.subLabel(this, body, "Background")
        for ((name, c) in canvasBgColors()) {
            bgRow(body, name, c, p.bgColor == c)
        }
    }

    private fun canvasBgColors(): List<Pair<String, Int>> = listOf(
        "Dark" to 0xFF101418.toInt(),
        "Black" to 0xFF000000.toInt(),
        "White" to 0xFFFFFFFF.toInt(),
        "Orange" to 0xFFFF5A2C.toInt(),
        "Navy" to 0xFF1E3C78.toInt(),
        "Green" to 0xFF14785A.toInt(),
        "Purple" to 0xFF781E5A.toInt()
    )

    private fun bgRow(parent: LinearLayout, name: String, color: Int, active: Boolean) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(UI.dp(this, 12), 0, UI.dp(this, 12), 0)
        row.isClickable = true
        row.isFocusable = true
        row.contentDescription = "Canvas background: $name"
        row.background = if (active) GradientDrawable().apply {
            cornerRadius = UI.dpf(this@EditorActivity, 12f)
            setColor(Color.argb(50, 255, 90, 44))
            setStroke(UI.dp(this@EditorActivity, 1), Color.argb(90, 255, 130, 80))
        } else GradientDrawable().apply {
            cornerRadius = UI.dpf(this@EditorActivity, 12f)
            setColor(Color.TRANSPARENT)
        }
        val dot = View(this)
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(UI.dp(this@EditorActivity, 1), Color.argb(160, 255, 255, 255))
        }
        dot.elevation = UI.dpf(this, 1f)
        row.addView(dot, LinearLayout.LayoutParams(UI.dp(this, 18), UI.dp(this, 18)))
        val lbl = TextView(this)
        lbl.text = name
        lbl.setTextColor(UI.FG)
        lbl.textSize = 13f
        lbl.letterSpacing = -0.01f
        lbl.typeface = Typeface.create("sans-serif", if (active) Typeface.BOLD else Typeface.NORMAL)
        val clp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        clp.marginStart = UI.dp(this, 12)
        lbl.layoutParams = clp
        row.addView(lbl)
        if (active) {
            val b = TextView(this)
            b.text = "ON"
            b.setTextColor(UI.ACCENT2)
            b.textSize = 10f
            b.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            b.letterSpacing = 0.06f
            b.setPadding(UI.dp(this, 8), UI.dp(this, 2), UI.dp(this, 8), UI.dp(this, 2))
            b.background = GradientDrawable().apply {
                cornerRadius = UI.dpf(this@EditorActivity, 8f)
                setColor(Color.argb(70, 255, 90, 44))
            }
            row.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val rlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 44))
        rlp.setMargins(0, UI.dp(this, 2), 0, UI.dp(this, 2))
        row.layoutParams = rlp
        row.setOnClickListener { setBgColor(color) }
        parent.addView(row)
    }

    // ================= EXPORT section =================

    private fun rebuildExportSection() {
        if (!this::exportHost.isInitialized) return
        val body = exportHost
        body.removeAllViews()
        val prefs = editorPrefs()
        val maxDim = prefs.getInt(PREF_EXP_MAXDIM, 720)
        val fps = prefs.getInt(PREF_EXP_FPS, 30)
        val codecName = prefs.getString(PREF_EXP_CODEC, "H264") ?: "H264"
        StudioLayoutInjector.actRow(this, body, R.drawable.ic_export, "Export video",
            "saves with your saved settings",
            badge = "${maxDim}p · ${fps} · $codecName") {
            quickExport()
        }
        StudioLayoutInjector.actRow(this, body, R.drawable.ic_settings, "Export settings…") {
            openExportSettings()
        }
        StudioLayoutInjector.noteRow(this, body,
            "Saved to your phone — view or share it when done.")
    }

    // ================= top strip state =================

    private fun refreshTopStrip() {
        if (!this::topBar.isInitialized) return
        updateAspectChip()
        val compact = topTier >= 1
        savePill.text = if (compact) (if (saveDirty) "●" else "✓") else (if (saveDirty) "Save" else "Saved")
        savePill.setTextColor(if (saveDirty) UI.ACCENT2 else UI.OK)
    }

    // ================= sidebar refresh (one entry, every state change) =================

    private fun refreshSidebar() {
        if (!this::panelContent.isInitialized) return
        val p = proj ?: return
        val sel = selected()
        sec["layers"]?.badge?.text =
            if (p.layers.isEmpty()) "empty" else "${p.layers.size} source${if (p.layers.size == 1) "" else "s"}"
        if (this::layersActionsHost.isInitialized) {
            layersActionsHost.removeAllViews()
            StudioLayoutInjector.actRow(this, layersActionsHost, R.drawable.ic_copy,
                "Duplicate", "copy the selected source", enabled = sel != null) {
                sel?.let { duplicateLayer(it) }
            }
            StudioLayoutInjector.actRow(this, layersActionsHost, R.drawable.ic_delete,
                "Delete", "remove the selected source", danger = true, enabled = sel != null) {
                removeSelectedSource()
            }
        }
        sec["source"]?.icon?.setImageDrawable(Ic.get(this,
            if (sel != null) Ic.typeIcon(sel.type) else R.drawable.ic_layers, UI.ACCENT2))
        sec["source"]?.badge?.text = sel?.name?.ifBlank { null } ?: "—"
        refreshSourceSection()
        val mixable = p.layers.count { it.isVideoLike() }
        sec["audio"]?.badge?.text =
            if (mixable == 0) "no sources" else "$mixable channel${if (mixable == 1) "" else "s"}"
        refreshAudioSection()
        rebuildRecordSection()
        rebuildCanvasSection()
        rebuildExportSection()
        sec["project"]?.badge?.text = if (saveDirty) "unsaved" else "saved"
    }

    // ================= export settings (sticky, reused by quick export) =================

    fun openExportSettings() {
        if (exportRunning) { UI.toast(this, "Stop the export first"); return }
        val prefs = editorPrefs()
        val curMaxDim = prefs.getInt(PREF_EXP_MAXDIM, 720)
        val curFps = prefs.getInt(PREF_EXP_FPS, 30)
        val curCodec = prefs.getString(PREF_EXP_CODEC, "H264") ?: "H264"
        AlertDialog.Builder(this)
            .setTitle("Resolution")
            .setSingleChoiceItems(
                arrayOf("480p — small file", "720p — balanced", "1080p — best quality"),
                when (curMaxDim) { 480 -> 0; 1080 -> 2; else -> 1 }) { d, w ->
                d.dismiss()
                showExportFpsPicker(curFps, curCodec, when (w) { 0 -> 480; 2 -> 1080; else -> 720 })
            }
            .show()
    }

    private fun showExportFpsPicker(curFps: Int, curCodec: String, maxDim: Int) {
        AlertDialog.Builder(this)
            .setTitle("Framerate")
            .setSingleChoiceItems(arrayOf("24 fps — filmic", "30 fps — standard", "60 fps — smooth"),
                when (curFps) { 24 -> 0; 60 -> 2; else -> 1 }) { d, w ->
                d.dismiss()
                showExportCodecPicker(curCodec, maxDim, when (w) { 0 -> 24; 2 -> 60; else -> 30 })
            }
            .show()
    }

    private fun showExportCodecPicker(curCodec: String, maxDim: Int, fps: Int) {
        val avail = Exporter.Codec.available().ifEmpty { listOf(Exporter.Codec.H264) }
        AlertDialog.Builder(this)
            .setTitle("Codec")
            .setItems(avail.map { it.label }.toTypedArray()) { _, w ->
                saveExportPrefs(avail[w].name, EncoderConfig.Quality.BALANCED.ordinal, maxDim, fps)
                UI.toast(this, "Export settings saved")
                refreshSidebar()
            }
            .show()
    }

    // ================= empty state / hidden pill =================

    private fun updateEmptyState() {
        if (!this::emptyOverlay.isInitialized) return
        val show = proj?.layers?.isEmpty() == true && !fullCanvas
        emptyOverlay.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun updateHiddenPill() {
        if (!this::hiddenPill.isInitialized) return
        val n = proj?.layers?.count { !it.visible } ?: 0
        if (n <= 0 || fullCanvas) {
            hiddenPill.visibility = View.GONE
            return
        }
        hiddenPill.visibility = View.VISIBLE
        hiddenPill.text = "$n hidden"
    }

    /** "N hidden" pill: one tap restores every hidden source (single undo step). */
    fun showAllHidden() {
        val hidden = proj?.layers?.filter { !it.visible } ?: return
        if (hidden.isEmpty()) return
        pushUndo()
        for (l in hidden) l.visible = true
        markDirty(); stage.refresh(); refreshAll()
        UI.toast(this, "Every source is visible again")
    }

    /** rec chip: stop the camera take or the screen record, whichever is running. */
    fun recChipTap() {
        when {
            ScreenCaptureService.running -> stopScreenCapture()
            liveCam?.recording == true ->
                proj?.layers?.firstOrNull { it.isLive() }?.let { toggleCameraRecord(it) }
        }
    }

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
        bumpSource()
        refreshAll()
        onTick(engine.master())
    }

    // ================= StageView.Host =================

    override val project: Project get() = proj!!
    override fun selectedId(): String? = selectedId
    override fun select(id: String?) {
        selectedId = id
        bumpSource()
        refreshAll()
        stage.refresh()
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

    /**
     * Long press a source: select it and open its SOURCE section in the
     * sidebar. Long press empty canvas: open the sidebar on LAYERS (the add
     * list is the most wanted verb from the canvas).
     */
    override fun onLongPressCanvas(l: Layer?, x: Float, y: Float) {
        if (fullCanvas) return
        if (l != null) {
            select(l.id)
            StudioLayoutInjector.setSidebarOpen(this, true)
            StudioLayoutInjector.setSection(this, "source", true)
        } else {
            openSidebarAt("layers")
        }
    }

    private fun onSourceChanged() {
        // called by SourceController after every command
        reconcileLiveCamera()
        bumpSource()
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

    /**
     * Every state change funnels through here: quick bar · dock rows ·
     * sidebar sections · top strip · record button · empty state · pills.
     * Slider sections (SOURCE / AUDIO) only re-render when [sourceVersion]
     * moves, so dragging a volume slider mid-refresh never resets the thumb.
     */
    private fun refreshAll() {
        if (!this::stage.isInitialized) return
        refreshQuickBar()
        rebuildDock()
        refreshTopStrip()
        refreshSidebar()
        updateEmptyState()
        updateName()
        updateRecordButton()
        updateHiddenPill()
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
        val nm = l.name
        deleteSourceSafely(l)
        showUndoSnack("Deleted $nm")
    }

    /**
     * The ONE delete path every surface uses (quick bar, SOURCE section,
     * LAYERS row): tears down a live camera session, evicts the decoder,
     * clears the selection — then deletes. Never call ctrl.delete raw for a
     * source that may own a camera or a decoder.
     */
    private fun deleteSourceSafely(l: Layer) {
        if (l.isLive()) {
            stopLiveCamera(evict = true)
            liveCamLayerId = null
        } else if (engineReady()) {
            engine.evict(l.id)
        }
        if (selectedId == l.id) selectedId = null
        ctrl.delete(l.id)
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
        bumpSource()
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

    fun openCamera() {
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
        row.setPadding(UI.dp(this, 12), UI.dp(this, 6), UI.dp(this, 12), UI.dp(this, 6))
        row.background = GradientDrawable().apply {
            cornerRadius = UI.dpf(this@EditorActivity, 10f)
            setColor(Color.argb(30, 255, 255, 255))
        }
        val lb = TextView(this)
        lb.text = label
        lb.setTextColor(UI.FG2)
        lb.textSize = 11.5f
        lb.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        lb.letterSpacing = 0.02f
        lb.maxLines = 1
        lb.ellipsize = android.text.TextUtils.TruncateAt.END
        lb.layoutParams = LinearLayout.LayoutParams(UI.dp(this, 110), ViewGroup.LayoutParams.WRAP_CONTENT)
        row.addView(lb)
        val sb = SeekBar(this)
        sb.max = 100
        sb.progress = value
        sb.progressTintList = android.content.res.ColorStateList.valueOf(UI.ACCENT)
        sb.thumbTintList = android.content.res.ColorStateList.valueOf(UI.ACCENT2)
        sb.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = UI.dp(this@EditorActivity, 10)
        }
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { if (u) on(v) }
            override fun onStartTrackingTouch(s: SeekBar?) { }
            override fun onStopTrackingTouch(s: SeekBar?) { }
        })
        row.addView(sb)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, UI.dp(this, 4), 0, UI.dp(this, 4))
        row.layoutParams = lp
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
    /**
     * The RECORD button lives in the floating timeline pill. It shows the
     * state verb (RECORD / STOP & SAVE); the setup reason (which source is
     * missing) lives in the sidebar's RECORD section, and tapping the button
     * with an incomplete setup explains + offers Add — so the pill stays a
     * fixed width and never crowds the seek bar.
     */
    private fun updateRecordButton() {
        if (!this::recordBtn.isInitialized) return
        val p = proj ?: return
        val hasLive = p.layers.any { it.isLive() }
        val hasClip = p.layers.any { it.isClip() }
        val ready = hasLive && hasClip
        recordBtn.text = when {
            recording -> "■  STOP & SAVE"
            ready -> "●  RECORD"
            else -> "●  RECORD"
        }
        recordBtn.alpha = if (recording || ready) 1f else 0.65f
        val reason = when {
            recording -> ""
            ready -> " camera + video ready"
            !hasLive && !hasClip -> " — add a camera and a video"
            !hasLive -> " — add a live camera"
            else -> " — add a video"
        }
        recordBtn.contentDescription = "Record" + reason
        recordBtn.setTextColor(if (recording || ready) Color.rgb(14, 14, 16) else UI.FG2)
        recordBtn.background = if (recording)
            Ic.pill(this, Color.argb(240, 200, 34, 34), 20f, Color.argb(180, 255, 120, 120))
        else if (ready)
            Ic.pill(this, Color.argb(240, 255, 90, 44), 20f, Color.argb(140, 255, 200, 160))
        else
            Ic.pill(this, Color.argb(170, 38, 42, 52), 20f, Color.argb(70, 255, 255, 255))
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
                openSidebarAt("layers")
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
                    "torchpending" -> refreshAll()
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

    // ================= studio verbs (sidebar / quick bar / canvas call these) =================

    fun selected(): Layer? = selectedId?.let { proj!!.layerById(it) }
    fun selectId(id: String?) { select(id) }

    fun addVideo() { pickMedia(video = true) }
    fun addImage() { pickMedia(video = false) }
    fun addCameraLive() { addLiveCamera() }
    fun addCameraTake() { openCamera() }
    fun addScreen() { startScreenCapture() }
    fun addTextSource() { addText() }

    fun anyPlaying(): Boolean = engineReady() && engine.anyPlaying()
    fun toggleMasterPlay() { togglePlay() }
    fun restart() {
        engine.seekTo(0L)
        if (this::seek.isInitialized) seek.progress = 0
        onTick(0L)
    }
    fun nudge(ms: Long) {
        val dur = proj!!.durationMs()
        val t = (engine.master() + ms).coerceIn(0L, dur)
        engine.seekTo(t)
        if (this::seek.isInitialized) seek.progress = t.toInt().coerceAtMost(seek.max)
        onTick(t)
    }
    fun toggleSourcePlay(l: Layer) {
        engine.toggleLayerPlay(l); bumpSource(); markDirty(); refreshAll()
    }
    fun snapshotFrame() {
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
    fun undo() { doUndo() }
    fun redo() { doRedo() }

    fun enterFullCanvas() { setFullCanvas(true) }
    fun openDockPanel() { openSidebarAt("layers") }
    fun openMixerPanel() { openSidebarAt("audio") }
    fun openExportPanel() { openExportSettings() }
    fun openAdvanced(l: Layer) { openSourceSection(l) }
    /**
     * Quick export = export with the user's SAVED settings (UI Plan2 T-04):
     * the Export pill and the sidebar's "Export video" row are one verb.
     */
    fun quickExport() {
        if (exportRunning) { UI.toast(this, "An export is already running"); return }
        if (recording) { UI.toast(this, "Stop the recording first"); return }
        if (proj?.layers?.isEmpty() == true) {
            UI.toast(this, "Nothing to export yet — add a source first"); return
        }
        if (warnLiveBeforeExport()) return
        runExportFromPrefs()
    }

    private fun runExportFromPrefs() {
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
                runExportFromPrefs()
            }
            .setNeutralButton("Cancel", null)
            .show()
        return true
    }

    fun setAspect(a: Aspect) { changeAspect(a) }
    fun setBg(color: Int) { setBgColor(color) }
    fun fitAllSources() {
        val p = proj!!
        pushUndo()
        for (l in p.layers) if (!l.isText()) l.fit = Layer.FIT_FIT
        markDirty(); stage.refresh(); refreshAll()
        UI.toast(this, "Every source shows its whole frame")
    }
    fun renameProject() {
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
    fun saveNow() { flushSave(); UI.toast(this, "Project saved") }
    fun openDiagnostics() {
        startActivity(Intent(this, DiagnosticsActivity::class.java))
    }
    fun closeProject() { onBackPressed() }
    fun editText(l: Layer) { editTextLayer(l) }
    fun cycleTextColor(l: Layer) {
        pushUndo(); l.textColor = nextColor(l.textColor); markDirty(); stage.refresh()
    }

    fun isCameraRecording(l: Layer): Boolean =
        l.id == liveCamLayerId && liveCam?.recording == true
    fun toggleCameraRecord(l: Layer) { toggleLiveCameraRecord(l) }
    fun switchCameraFacing(l: Layer) {
        val cam = liveCam ?: return
        cam.switchFacing()
        l.camFacing = if (cam.isFront()) Layer.FACING_FRONT else Layer.FACING_BACK
        l.mirror = cam.isFront()
        markDirty(); refreshAll()
    }
    fun toggleCameraMirror(l: Layer) {
        l.mirror = !l.mirror
        liveCam?.setMirror(l.mirror)
        markDirty(); refreshAll()
    }

    // ---------------- flashlight (LED torch + screen light) ----------------
    // Dual-torch: both front and back LEDs can be controlled independently.
    // The state is remembered per-facing so switching camera preserves the user's choice.
    // "Both on" uses CameraManager.setTorchMode for the idle camera.

    fun isTorchOn(l: Layer): Boolean =
        l.id == liveCamLayerId && liveCam?.torch == true

    fun hasTorch(l: Layer): Boolean =
        l.id == liveCamLayerId && liveCam?.hasFlashUnit == true

    fun hasFrontTorch(): Boolean = liveCam?.frontHasFlash == true
    fun hasBackTorch(): Boolean = liveCam?.backHasFlash == true
    /** Front side always has a light source: physical LED or the screen panel. */
    private fun hasFrontLight(): Boolean = liveCam != null
    private fun frontLightOn(): Boolean =
        if (hasFrontTorch()) liveCam?.isTorchOnForFront() == true else screenLight
    private fun backLightOn(): Boolean = liveCam?.isTorchOnForBack() == true
    fun isFrontTorchOn(): Boolean = frontLightOn()
    fun isBackTorchOn(): Boolean = backLightOn()
    fun isBothTorchOn(): Boolean = frontLightOn() && backLightOn()

    fun toggleTorch(l: Layer) {
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
    fun toggleFrontTorch() {
        val cam = liveCam
        if (cam == null) { UI.toast(this, "Live camera not running"); return }
        if (!cam.hasFlashForFront()) {
            // No front LED on this phone: the honest front flash IS the screen
            // light. Turn it on and let the user see the result immediately
            // instead of a disabled row / "use screen light" toast.
            toggleScreenLight()
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
    fun toggleBackTorch() {
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
        // A rear request that cannot be lit right now (camera busy) is shown as
        // pending on the row; it is not an error, so don't show the old toast.
        UI.toast(this,
            if (cam.isTorchPendingForBack()) "Rear flash will light when the rear camera is available"
            else if (cam.isTorchOnForBack()) "Rear flash on (LED)"
            else "Rear flash off")
        refreshAll()
    }
    fun toggleBothTorch() {
        val cam = liveCam
        if (cam == null) { UI.toast(this, "Live camera not running"); return }
        if (!cam.hasFlashForBack()) {
            UI.toast(this, "This device has no rear LED — use the screen light")
            return
        }
        val turnOn = !isBothTorchOn()
        if (turnOn) {
            // Front: hardware LED when it exists, otherwise the screen light.
            if (cam.hasFlashForFront()) {
                if (!cam.setTorchFor(true, true) && !cam.isTorchOnForFront()) {
                    UI.toast(this, cam.torchLastError().takeIf { it.isNotBlank() }
                        ?: "Front flash unavailable")
                    return
                }
            } else if (!screenLight) {
                toggleScreenLight()
            }
            // Rear: the hardware LED.
            if (!cam.setTorchFor(false, true) && !cam.isTorchOnForBack()) {
                UI.toast(this, cam.torchLastError().takeIf { it.isNotBlank() }
                    ?: "Rear flash unavailable")
                return
            }
        } else {
            if (cam.hasFlashForFront() && cam.isTorchOnForFront()) {
                cam.setTorchFor(true, false)
            } else if (screenLight) {
                toggleScreenLight()
            }
            if (cam.isTorchOnForBack()) cam.setTorchFor(false, false)
        }
        UI.toast(this, if (turnOn) "Both flashes on" else "Both flashes off")
        refreshAll()
    }

    fun isScreenLightOn(): Boolean = screenLight

    /**
     * SCREEN FLASH for the front camera.
     *
     * Front lenses almost never have an LED, so the phone itself becomes the
     * lamp. We push window brightness to 1.0 and draw a bright warm-white
     * panel BEHIND the stage so the canvas stays fully visible — the light
     * comes from the letterbox surround, not by covering the composition.
     */
    fun toggleScreenLight() {
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
            // added at index 0 it sits behind the stage — the canvas and all
            // floating chrome stay above the light without any re-ordering
            screenLightView?.visibility = View.VISIBLE
        } else {
            screenLightView?.visibility = View.GONE
        }
    }

    fun isStatsHudOn(): Boolean =
        editorPrefs().getBoolean(PREF_STATS_HUD, true)

    fun toggleStatsHud() {
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

    fun toast(msg: String) { UI.toast(this, msg) }
}
