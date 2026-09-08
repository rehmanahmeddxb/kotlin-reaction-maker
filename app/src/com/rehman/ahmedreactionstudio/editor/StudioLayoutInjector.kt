package com.rehman.ahmedreactionstudio.editor

import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.rehman.ahmedreactionstudio.R
import com.rehman.ahmedreactionstudio.core.Aspect
import com.rehman.ahmedreactionstudio.core.Project
import com.rehman.ahmedreactionstudio.util.UI

/**
 * Full-bleed canvas studio (docs/SIDEBAR_STUDIO_PLAN.md).
 *
 * The canvas owns 100 % of the screen: [EditorActivity.stage] is MATCH_PARENT
 * and contain-fits the whole safe area (the stage gets NO chrome insets —
 * [EditorActivity.applyViewportInsets] passes only the system-bar insets).
 * Every control floats ON TOP of the canvas as translucent chrome, so the
 * full composition stays visible while recording:
 *
 *   root
 *   ├── stage               100 % canvas — never shrunk by chrome
 *   ├── emptyOverlay        centred card, only while there are no sources
 *   ├── topBar              floating 48dp strip: panel · project · aspect ·
 *   │                       undo/redo · Save · Export · Full canvas · more
 *   ├── sidebar             floating left panel — seven labelled sections
 *   │                       (Layers · Source · Audio · Record · Canvas ·
 *   │                       Export · Project)
 *   ├── hiddenPill / recChip / statsHud   chip row under the strip
 *   ├── quickWrap → quickBar             floating selection controls
 *   ├── timelinePill        floating transport + RECORD:
 *   │                       play · time · seek · stop · record
 *   ├── fullExitBtn / snackBar / progOverlay
 *
 * Fit rules (plan §fit-ladder, no crops / no overlaps):
 *  - the top strip degrades in tiers as width shrinks (labelled → icon-only
 *    Save/Export → no aspect chip); the title box is the only flex element
 *    and ellipsises instead of clipping neighbours.
 *  - the timeline pill is a SINGLE row (transport and record together), so
 *    nothing can ever collide with it; the seek bar is its flex element.
 *  - the quick bar lives in a horizontal scroll view, so it never overflows.
 */
object StudioLayoutInjector {

    /** One sidebar section: a header row that toggles a body. */
    class StudioSection(
        val id: String,
        val icon: ImageView,
        val badge: TextView,
        val chev: TextView,
        val body: LinearLayout
    ) {
        var open: Boolean = true
    }

    // ================= snack bar / progress (kept here: chrome) =================

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

    fun showSnack(a: EditorActivity, msg: String, actionLabel: String? = null,
                  action: (() -> Unit)? = null) {
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
        bar.translationY = UI.dpf(a, 12f)
        bar.animate().alpha(1f).translationY(0f).setDuration(200).start()
        bar.contentDescription = msg
        snackHandler.postDelayed(snackHide, 3500L)
    }

    fun showProgress(a: EditorActivity, title: String, msg: String, determinate: Boolean,
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

    fun updateProgress(a: EditorActivity, pct: Int, msg: String) {
        progBar?.progress = pct.coerceIn(0, 100)
        progMsg?.text = msg
    }

    fun dismissProgress(a: EditorActivity) {
        progOverlay?.visibility = View.GONE
        progOnCancel = null
    }

    // ================= inject =================

    fun inject(activity: EditorActivity, root: FrameLayout) {
        root.removeAllViews()
        val landscape = activity.resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE

        // ---- 1) the canvas: 100% of the screen, adaptive, behind everything ----
        val stage = StageView(activity)
        stage.host = activity
        activity.stage = stage
        root.addView(stage, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        activity.applyViewportInsets()

        // ---- 2) empty state (only while there are no sources) ----
        activity.emptyOverlay = buildEmptyOverlay(activity)
        root.addView(activity.emptyOverlay, FrameLayout.LayoutParams(
            UI.dp(activity, 300), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // ---- 3) floating top strip ----
        activity.topBar = buildTopStrip(activity)
        val stripLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            UI.dp(activity, 48), Gravity.TOP or Gravity.START)
        stripLp.setMargins(UI.dp(activity, 10), UI.dp(activity, 8), UI.dp(activity, 10), 0)
        root.addView(activity.topBar, stripLp)

        // ---- 4) floating sidebar (seven labelled sections) ----
        activity.sidebar = buildSidebar(activity)
        val sideLp = FrameLayout.LayoutParams(UI.dp(activity, if (landscape) 244 else 256),
            ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.START)
        sideLp.topMargin = UI.dp(activity, 56)
        root.addView(activity.sidebar, sideLp)

        // ---- 5) chip row under the strip: hidden (L) · recChip (C) · HUD (R) ----
        activity.hiddenPill = chip(activity, UI.BG2)
        activity.hiddenPill.text = "1 hidden"
        activity.hiddenPill.setOnClickListener { activity.showAllHidden() }
        val hpLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
        hpLp.setMargins(UI.dp(activity, 10), UI.dp(activity, 58), 0, 0)
        root.addView(activity.hiddenPill, hpLp)
        activity.hiddenPill.visibility = View.GONE

        activity.recChip = chip(activity, Color.argb(235, 52, 22, 18),
            Color.argb(160, 255, 90, 44))
        activity.recChip.setTextColor(UI.FG)
        activity.recChip.setOnClickListener { activity.recChipTap() }
        val rcLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        rcLp.topMargin = UI.dp(activity, 58)
        root.addView(activity.recChip, rcLp)
        activity.recChip.visibility = View.GONE

        activity.statsHud = TextView(activity)
        activity.statsHud.setTextColor(UI.OK)
        activity.statsHud.textSize = 10.5f
        activity.statsHud.typeface = Typeface.create("monospace", Typeface.NORMAL)
        activity.statsHud.gravity = Gravity.END
        activity.statsHud.background = box(activity, Color.argb(190, 12, 14, 18), 8f,
            Color.argb(70, 255, 255, 255))
        activity.statsHud.setPadding(UI.dp(activity, 10), UI.dp(activity, 6),
            UI.dp(activity, 10), UI.dp(activity, 6))
        val hudLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
        hudLp.setMargins(0, UI.dp(activity, 58), UI.dp(activity, 10), 0)
        root.addView(activity.statsHud, hudLp)
        activity.statsHud.visibility = View.GONE

        // ---- 6) quick bar (floating, centred; scrollable so it never crops) ----
        val quickWrap = HorizontalScrollView(activity)
        quickWrap.isHorizontalScrollBarEnabled = false
        val quickBar = LinearLayout(activity)
        quickBar.orientation = LinearLayout.HORIZONTAL
        quickBar.gravity = Gravity.CENTER_VERTICAL
        quickBar.background = box(activity, Color.argb(235, 27, 30, 38), 22f,
            Color.argb(60, 255, 255, 255))
        quickBar.setPadding(UI.dp(activity, 6), UI.dp(activity, 4), UI.dp(activity, 6), UI.dp(activity, 4))
        activity.quickWrap = quickWrap
        activity.quickBar = quickBar
        quickWrap.addView(quickBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val qwLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        qwLp.setMargins(UI.dp(activity, 6), UI.dp(activity, 94), UI.dp(activity, 6), 0)
        root.addView(quickWrap, qwLp)
        quickWrap.visibility = View.GONE

        // ---- 7) timeline pill: play · time · seek · /dur · stop · RECORD ----
        activity.timelinePill = buildTimeline(activity)
        val tlLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            UI.dp(activity, 54), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        tlLp.setMargins(UI.dp(activity, 10), 0, UI.dp(activity, 10), UI.dp(activity, 12))
        root.addView(activity.timelinePill, tlLp)

        // ---- 8) full-canvas exit (hidden unless in Full Canvas mode) ----
        activity.fullExitBtn = TextView(activity)
        activity.fullExitBtn.text = "✕  Exit full canvas"
        activity.fullExitBtn.setTextColor(UI.FG)
        activity.fullExitBtn.textSize = 12f
        activity.fullExitBtn.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        activity.fullExitBtn.gravity = Gravity.CENTER
        activity.fullExitBtn.includeFontPadding = false
        activity.fullExitBtn.setPadding(UI.dp(activity, 14), 0, UI.dp(activity, 14), 0)
        activity.fullExitBtn.background = box(activity, Color.argb(240, 38, 42, 52), 18f,
            Color.argb(110, 255, 255, 255))
        activity.fullExitBtn.setOnClickListener { activity.setFullCanvas(false) }
        val feLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            UI.dp(activity, 36), Gravity.TOP or Gravity.END)
        feLp.setMargins(0, UI.dp(activity, 10), UI.dp(activity, 10), 0)
        root.addView(activity.fullExitBtn, feLp)
        activity.fullExitBtn.visibility = View.GONE

        // ---- 9) snack bar + progress overlay ----
        buildSnackBar(activity, root)
        buildProgOverlay(activity, root)

        // ---- 10) window insets + chrome fit on every layout pass ----
        root.setOnApplyWindowInsetsListener { _, insets ->
            activity.applyWindowInsets(insets)
            insets
        }
        val listener = object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                activity.fitChrome()
            }
        }
        activity.chromeLayoutListener = listener
        root.viewTreeObserver.addOnGlobalLayoutListener(listener)

        // ---- 11) sidebar default: open in landscape, closed in portrait ----
        val firstLayout = !activity.chromeLaidOut
        activity.chromeLaidOut = true
        setSidebarOpen(activity, activity.sidebarOpen || (firstLayout && landscape),
            animate = false)
    }

    // ================= top strip =================

    private fun buildTopStrip(a: EditorActivity): LinearLayout {
        val strip = LinearLayout(a)
        strip.orientation = LinearLayout.HORIZONTAL
        strip.gravity = Gravity.CENTER_VERTICAL
        strip.background = box(a, Color.argb(240, 27, 30, 38), 16f,
            Color.argb(60, 255, 255, 255))
        strip.setPadding(UI.dp(a, 10), 0, UI.dp(a, 10), 0)

        // panel toggle (hamburger)
        val sidebarBtn = IconBtn(a)
        sidebarBtn.setIcon(R.drawable.ic_menu, UI.FG, "Toggle controls panel")
        sidebarBtn.setOnClickListener { a.toggleSidebar() }
        a.sidebarBtn = sidebarBtn
        strip.addView(sidebarBtn, LinearLayout.LayoutParams(UI.dp(a, 40), UI.dp(a, 40)))

        // project title + meta (the ONLY flex element of the row)
        val titleBox = LinearLayout(a)
        titleBox.orientation = LinearLayout.VERTICAL
        titleBox.gravity = Gravity.CENTER_VERTICAL
        titleBox.isClickable = true
        titleBox.isFocusable = true
        titleBox.contentDescription = "Project name — tap to rename"
        titleBox.setOnClickListener { a.renameProject() }
        val title = TextView(a)
        title.tag = "name"
        title.text = a.proj?.name ?: ""
        title.setTextColor(UI.FG)
        title.textSize = 12.5f
        title.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        a.titleView = title
        titleBox.addView(title)
        val meta = TextView(a)
        meta.tag = "meta"
        meta.setTextColor(UI.FG2)
        meta.textSize = 9.5f
        meta.maxLines = 1
        meta.ellipsize = TextUtils.TruncateAt.END
        a.metaView = meta
        titleBox.addView(meta)
        val tlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        tlp.marginStart = UI.dp(a, 8)
        strip.addView(titleBox, tlp)

        // aspect chip
        val chip = TextView(a)
        chip.text = (a.proj?.aspect ?: Aspect.R169).code
        chip.setTextColor(UI.FG2)
        chip.textSize = 11.5f
        chip.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        chip.gravity = Gravity.CENTER
        chip.includeFontPadding = false
        chip.setPadding(UI.dp(a, 10), 0, UI.dp(a, 10), 0)
        chip.background = box(a, UI.BG3, 15f, Color.argb(50, 255, 255, 255))
        chip.contentDescription = "Canvas aspect ratio"
        chip.setOnClickListener { a.showAspectPicker() }
        a.aspectChip = chip
        val clp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UI.dp(a, 30))
        clp.setMargins(UI.dp(a, 4), 0, UI.dp(a, 2), 0)
        strip.addView(chip, clp)

        // undo / redo
        val undo = IconBtn(a)
        undo.setIcon(R.drawable.ic_undo, UI.FG2, "Undo")
        undo.setOnClickListener { a.doUndo() }
        a.undoBtn = undo
        strip.addView(undo, LinearLayout.LayoutParams(UI.dp(a, 36), UI.dp(a, 36)))
        val redo = IconBtn(a)
        redo.setIcon(R.drawable.ic_redo, UI.FG2, "Redo")
        redo.setOnClickListener { a.doRedo() }
        a.redoBtn = redo
        val rlp = LinearLayout.LayoutParams(UI.dp(a, 36), UI.dp(a, 36))
        rlp.marginStart = UI.dp(a, 2)
        strip.addView(redo, rlp)

        // save (state pill)
        val save = TextView(a)
        save.text = "Saved"
        save.setTextColor(UI.OK)
        save.textSize = 11.5f
        save.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        save.gravity = Gravity.CENTER
        save.includeFontPadding = false
        save.setPadding(UI.dp(a, 10), 0, UI.dp(a, 10), 0)
        save.background = box(a, UI.BG3, 15f, Color.argb(50, 255, 255, 255))
        save.contentDescription = "Save project"
        save.setOnClickListener { a.saveNow() }
        a.savePill = save
        val slp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UI.dp(a, 30))
        slp.setMargins(UI.dp(a, 6), 0, 0, 0)
        strip.addView(save, slp)

        // export (accent pill)
        val export = TextView(a)
        export.text = "Export"
        export.setTextColor(Color.rgb(14, 14, 16))
        export.textSize = 11.5f
        export.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        export.gravity = Gravity.CENTER
        export.includeFontPadding = false
        export.setPadding(UI.dp(a, 12), 0, UI.dp(a, 12), 0)
        export.background = box(a, UI.ACCENT, 15f, Color.argb(120, 255, 200, 160))
        export.contentDescription = "Quick export with saved settings"
        export.setOnClickListener { a.quickExport() }
        a.exportPill = export
        val elp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UI.dp(a, 30))
        elp.setMargins(UI.dp(a, 6), 0, 0, 0)
        strip.addView(export, elp)

        // full canvas + overflow
        val fc = IconBtn(a)
        fc.setIcon(R.drawable.ic_fullscreen, UI.FG, "Full screen canvas")
        fc.setOnClickListener { a.enterFullCanvas() }
        a.fullCanvasBtn = fc
        val fclp = LinearLayout.LayoutParams(UI.dp(a, 36), UI.dp(a, 36))
        fclp.marginStart = UI.dp(a, 4)
        strip.addView(fc, fclp)
        val more = IconBtn(a)
        more.setIcon(R.drawable.ic_more, UI.FG2, "More")
        more.setOnClickListener { showOverflowMenu(a) }
        a.overflowBtn = more
        val mlp = LinearLayout.LayoutParams(UI.dp(a, 36), UI.dp(a, 36))
        mlp.marginStart = UI.dp(a, 2)
        strip.addView(more, mlp)
        return strip
    }

    private fun showOverflowMenu(a: EditorActivity) {
        val hud = a.isStatsHudOn()
        AlertDialog.Builder(a)
            .setTitle("More")
            .setItems(arrayOf(
                "Canvas aspect…",
                if (hud) "Hide stats overlay" else "Show stats overlay",
                "Full screen canvas",
                "Studio diagnostics"
            )) { _, w ->
                when (w) {
                    0 -> a.showAspectPicker()
                    1 -> a.toggleStatsHud()
                    2 -> a.enterFullCanvas()
                    3 -> a.openDiagnostics()
                }
            }
            .show()
    }

    /**
     * Top-strip fit ladder (no crops): tier 0 = labelled Save/Export + aspect
     * chip; tier 1 = icon-only Save/Export; tier 2 = no aspect chip (it stays
     * one tap away in the overflow menu). The title box absorbs the rest and
     * ellipsises.
     */
    fun fitTopStrip(a: EditorActivity) {
        if (!a.chromeInitialized()) return
        val strip = a.topBar
        if (strip.width <= 0) { strip.post { fitTopStrip(a) }; return }
        val avail = strip.width - strip.paddingLeft - strip.paddingRight
        val d = UI.dp(a, 1)
        val fixed0 = (40 + 6 + 60 + 74 + 6 + 60 + 62 + 74) * d
        val fixed1 = (40 + 6 + 50 + 74 + 6 + 34 + 40 + 74) * d
        var tier = 0
        if (avail - fixed0 < 96 * d) tier = 1
        if (tier == 1 && avail - fixed1 < 48 * d) tier = 2
        if (a.topTier == tier) return
        a.topTier = tier
        if (tier == 0) {
            a.exportPill.text = "Export"
            a.exportPill.setCompoundDrawablesRelative(null, null, null, null)
        } else {
            a.exportPill.text = " "
            a.exportPill.setCompoundDrawablesRelativeWithIntrinsicBounds(
                Ic.get(a, R.drawable.ic_export, Color.rgb(14, 14, 16)), null, null, null)
        }
        a.aspectChip.visibility = if (tier == 2) View.GONE else View.VISIBLE
    }

    // ================= timeline pill =================

    private fun buildTimeline(a: EditorActivity): LinearLayout {
        val tl = LinearLayout(a)
        tl.orientation = LinearLayout.HORIZONTAL
        tl.gravity = Gravity.CENTER_VERTICAL
        tl.background = box(a, Color.argb(242, 27, 30, 38), 27f,
            Color.argb(60, 255, 255, 255))
        tl.setPadding(UI.dp(a, 10), 0, UI.dp(a, 10), 0)

        val play = IconBtn(a)
        play.setIcon(R.drawable.ic_play, Color.WHITE, "Play")
        play.setOnClickListener { a.togglePlay() }
        a.playBtn = play
        tl.addView(play, LinearLayout.LayoutParams(UI.dp(a, 44), UI.dp(a, 44)))

        val time = TextView(a)
        time.text = "0:00"
        time.setTextColor(UI.FG)
        time.textSize = 12f
        time.typeface = Typeface.create("monospace", Typeface.NORMAL)
        time.gravity = Gravity.CENTER_VERTICAL
        time.setPadding(UI.dp(a, 8), 0, 0, 0)
        a.timeLabel = time
        tl.addView(time, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))

        val seek = SeekBar(a)
        seek.progressTintList = android.content.res.ColorStateList.valueOf(UI.ACCENT)
        seek.thumbTintList = android.content.res.ColorStateList.valueOf(UI.ACCENT2)
        seek.max = a.proj?.durationMs()?.toInt()?.coerceAtLeast(1) ?: 1
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                if (fromUser) {
                    a.timeLabel.text = UI.fmtTime(v.toLong())
                    if (a.engineReady()) a.engine.seekTo(v.toLong())
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { a.scrubbing = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                a.scrubbing = false
                if (a.engineReady()) a.engine.refreshFrames()
            }
        })
        a.seek = seek
        seek.minimumWidth = UI.dp(a, 36)
        val sep = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        sep.setMargins(UI.dp(a, 2), 0, UI.dp(a, 6), 0)
        tl.addView(seek, sep)

        val dur = TextView(a)
        dur.text = "/ 0:00"
        dur.setTextColor(UI.FG2)
        dur.textSize = 12f
        dur.typeface = Typeface.create("monospace", Typeface.NORMAL)
        a.durationLabel = dur
        tl.addView(dur, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))

        val vdiv = View(a)
        vdiv.setBackgroundColor(Color.argb(70, 255, 255, 255))
        tl.addView(vdiv, LinearLayout.LayoutParams(UI.dp(a, 1), UI.dp(a, 26)).apply {
            setMargins(UI.dp(a, 8), 0, UI.dp(a, 8), 0)
        })

        val stop = IconBtn(a)
        stop.setIcon(R.drawable.ic_stop, UI.FG2, "Stop playback")
        stop.setOnClickListener { a.controlsStopTap() }
        a.recordStopBtn = stop
        tl.addView(stop, LinearLayout.LayoutParams(UI.dp(a, 40), UI.dp(a, 40)))

        val rec = TextView(a)
        rec.text = "●  RECORD"
        rec.setTextColor(Color.rgb(14, 14, 16))
        rec.textSize = 11.5f
        rec.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        rec.letterSpacing = 0.04f
        rec.gravity = Gravity.CENTER
        rec.includeFontPadding = false
        rec.setPadding(UI.dp(a, 14), 0, UI.dp(a, 14), 0)
        rec.background = box(a, UI.ACCENT, 20f, Color.argb(140, 255, 200, 160))
        rec.contentDescription = "Record"
        rec.setOnClickListener { a.recordButtonTap() }
        a.recordBtn = rec
        val rlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UI.dp(a, 40))
        rlp.marginStart = UI.dp(a, 8)
        rec.minimumWidth = UI.dp(a, 104)
        tl.addView(rec, rlp)
        return tl
    }

    /**
     * The timeline is one row — transport AND record — so it can never collide
     * with anything. Width: 86 % (max 760dp) in landscape, 94 % in portrait;
     * the seek bar is its only flex element and shrinks first.
     */
    fun layoutTimeline(a: EditorActivity) {
        if (!a.chromeInitialized()) return
        val rootW = a.rootFrame.width
        if (rootW <= 0) return
        val landscape = a.resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
        val w = if (landscape) minOf((rootW * 0.86f).toInt(), UI.dp(a, 760))
        else (rootW * 0.94f).toInt()
        val lp = a.timelinePill.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.width != w) {
            lp.width = w
            a.timelinePill.layoutParams = lp
        }
    }

    // ================= sidebar =================

    private fun buildSidebar(a: EditorActivity): LinearLayout {
        val bar = LinearLayout(a)
        bar.orientation = LinearLayout.VERTICAL
        bar.background = roundedRight(a, UI.BG, 14f)
        bar.elevation = UI.dpf(a, 12f)

        val scroll = ScrollView(a)
        scroll.isVerticalScrollBarEnabled = true
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER)
        a.panelScroll = scroll
        bar.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val content = LinearLayout(a)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(UI.dp(a, 8), UI.dp(a, 6), UI.dp(a, 8), UI.dp(a, 14))
        a.panelContent = content
        scroll.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fun openOf(id: String, def: Boolean) = a.sectionOpen.getOrDefault(id, def)

        // ---- Layers ----
        val layers = section(a, content, "layers", R.drawable.ic_layers, "Layers",
            openOf("layers", true))
        a.layersHost = LinearLayout(a)
        a.layersHost.orientation = LinearLayout.VERTICAL
        layers.body.addView(a.layersHost)
        divider(a, layers.body)
        subLabel(a, layers.body, "Add source")
        a.addHost = LinearLayout(a)
        a.addHost.orientation = LinearLayout.VERTICAL
        a.addHost.addView(actRow(a, a.addHost, R.drawable.ic_camera, "Camera (live)",
            "on the canvas — frame it, then record") { a.addCameraLive() })
        a.addHost.addView(actRow(a, a.addHost, R.drawable.ic_camera, "Camera take",
            "fullscreen recorder → PiP clip") { a.openCamera() })
        a.addHost.addView(actRow(a, a.addHost, R.drawable.ic_video, "Video file",
            "from your phone") { a.addVideo() })
        a.addHost.addView(actRow(a, a.addHost, R.drawable.ic_image, "Image",
            "from your phone") { a.addImage() })
        a.addHost.addView(actRow(a, a.addHost, R.drawable.ic_screen, "Screen record",
            "mirrors this phone") { a.addScreen() })
        a.addHost.addView(actRow(a, a.addHost, R.drawable.ic_text, "Text",
            "overlay caption") { a.addTextSource() })
        layers.body.addView(a.addHost)
        divider(a, layers.body)
        a.layersActionsHost = LinearLayout(a)
        a.layersActionsHost.orientation = LinearLayout.VERTICAL
        layers.body.addView(a.layersActionsHost)

        // ---- Source (selection) ----
        val source = section(a, content, "source", R.drawable.ic_layers, "Source",
            openOf("source", false))
        a.sourceSectionBody = LinearLayout(a)
        a.sourceSectionBody.orientation = LinearLayout.VERTICAL
        source.body.addView(a.sourceSectionBody)

        // ---- Audio ----
        val audio = section(a, content, "audio", R.drawable.ic_volume, "Audio",
            openOf("audio", true))
        a.audioHost = LinearLayout(a)
        a.audioHost.orientation = LinearLayout.VERTICAL
        audio.body.addView(a.audioHost)

        // ---- Record ----
        val record = section(a, content, "record", R.drawable.ic_video, "Record",
            openOf("record", true))
        a.recordSectionBody = LinearLayout(a)
        a.recordSectionBody.orientation = LinearLayout.VERTICAL
        record.body.addView(a.recordSectionBody)

        // ---- Canvas ----
        val canvas = section(a, content, "canvas", R.drawable.ic_aspect, "Canvas",
            openOf("canvas", true))
        a.canvasHost = LinearLayout(a)
        a.canvasHost.orientation = LinearLayout.VERTICAL
        canvas.body.addView(a.canvasHost)

        // ---- Export ----
        val export = section(a, content, "export", R.drawable.ic_export, "Export",
            openOf("export", true))
        a.exportHost = LinearLayout(a)
        a.exportHost.orientation = LinearLayout.VERTICAL
        export.body.addView(a.exportHost)

        // ---- Project ----
        val project = section(a, content, "project", R.drawable.ic_settings, "Project",
            openOf("project", true))
        a.projectHost = LinearLayout(a)
        a.projectHost.orientation = LinearLayout.VERTICAL
        a.projectHost.addView(actRow(a, a.projectHost, R.drawable.ic_edit, "Rename project") {
            a.renameProject()
        })
        a.projectHost.addView(actRow(a, a.projectHost, R.drawable.ic_check, "Save now") {
            a.saveNow()
        })
        a.projectHost.addView(actRow(a, a.projectHost, R.drawable.ic_info, "Studio diagnostics") {
            a.openDiagnostics()
        })
        a.projectHost.addView(actRow(a, a.projectHost, R.drawable.ic_back, "Close project",
            danger = true) { a.closeProject() })
        project.body.addView(a.projectHost)

        a.sec.clear()
        a.sec.putAll(mapOf(
            "layers" to layers, "source" to source, "audio" to audio,
            "record" to record, "canvas" to canvas, "export" to export,
            "project" to project
        ))
        return bar
    }

    /** Section header (icon · TITLE · badge · chevron) + collapsible body. */
    private fun section(a: EditorActivity, parent: LinearLayout, id: String, iconRes: Int,
                        title: String, open: Boolean): StudioSection {
        val header = LinearLayout(a)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(UI.dp(a, 10), UI.dp(a, 8), UI.dp(a, 8), UI.dp(a, 8))
        header.isClickable = true
        header.isFocusable = true
        header.background = box(a, Color.argb(45, 255, 255, 255), 10f, Color.TRANSPARENT)
        val icon = ImageView(a)
        icon.setImageDrawable(Ic.get(a, iconRes, UI.ACCENT2))
        header.addView(icon, LinearLayout.LayoutParams(UI.dp(a, 18), UI.dp(a, 18)))
        val t = TextView(a)
        t.text = title.uppercase()
        t.setTextColor(UI.FG)
        t.textSize = 11.5f
        t.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        t.letterSpacing = 0.08f
        val tlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        tlp.marginStart = UI.dp(a, 8)
        header.addView(t, tlp)
        val badge = TextView(a)
        badge.setTextColor(UI.FG2)
        badge.textSize = 10.5f
        badge.maxLines = 1
        badge.ellipsize = TextUtils.TruncateAt.END
        badge.gravity = Gravity.CENTER_VERTICAL
        val blp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        blp.marginStart = UI.dp(a, 8)
        header.addView(badge, blp)
        val chev = TextView(a)
        chev.text = if (open) "▾" else "▸"
        chev.setTextColor(UI.FG2)
        chev.textSize = 12f
        header.addView(chev)

        val body = LinearLayout(a)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(UI.dp(a, 2), UI.dp(a, 6), UI.dp(a, 2), UI.dp(a, 10))
        body.visibility = if (open) View.VISIBLE else View.GONE

        val sec = StudioSection(id, icon, badge, chev, body).apply { this.open = open }
        header.setOnClickListener {
            sec.open = !sec.open
            a.sectionOpen[id] = sec.open
            body.visibility = if (sec.open) View.VISIBLE else View.GONE
            chev.text = if (sec.open) "▾" else "▸"
            if (sec.open) a.onSectionOpened(id)
        }
        parent.addView(header)
        parent.addView(body)
        return sec
    }

    // ================= sidebar row kit (used by the activity too) =================

    /** A 44dp (52dp with sub-line) labelled action row — the sidebar's unit. */
    fun actRow(a: EditorActivity, parent: LinearLayout, icon: Int, label: String,
               sub: String? = null, active: Boolean = false, danger: Boolean = false,
               enabled: Boolean = true, badge: String? = null, badgeColor: Int? = null,
               onTap: () -> Unit): LinearLayout {
        val row = LinearLayout(a)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(UI.dp(a, 10), 0, UI.dp(a, 10), 0)
        val h = if (sub != null) 52 else 44
        row.background = if (active) box(a, Color.argb(70, 255, 90, 44), 10f,
            Color.argb(120, 255, 90, 44))
        else box(a, Color.TRANSPARENT, 10f, Color.TRANSPARENT)
        val tint = when {
            !enabled -> Color.argb(90, 255, 255, 255)
            danger -> UI.DANGER
            active -> UI.ACCENT2
            else -> UI.FG
        }
        val iconView = ImageView(a)
        iconView.setImageDrawable(Ic.get(a, icon, tint))
        row.addView(iconView, LinearLayout.LayoutParams(UI.dp(a, 18), UI.dp(a, 18)))
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        val lbl = TextView(a)
        lbl.text = label
        lbl.setTextColor(when {
            !enabled -> Color.argb(110, 255, 255, 255)
            danger -> UI.DANGER
            else -> UI.FG
        })
        lbl.textSize = 13f
        lbl.maxLines = 1
        lbl.ellipsize = TextUtils.TruncateAt.END
        lbl.typeface = Typeface.create("sans-serif",
            if (active || danger) Typeface.BOLD else Typeface.NORMAL)
        col.addView(lbl)
        if (sub != null) {
            val s = TextView(a)
            s.text = sub
            s.setTextColor(Color.argb(160, 160, 166, 180))
            s.textSize = 10.5f
            s.maxLines = 1
            s.ellipsize = TextUtils.TruncateAt.END
            col.addView(s)
        }
        val clp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        clp.marginStart = UI.dp(a, 10)
        col.layoutParams = clp
        row.addView(col)
        if (badge != null) {
            val b = TextView(a)
            b.text = badge
            b.setTextColor(badgeColor ?: (if (active) UI.ACCENT2 else UI.FG2))
            b.textSize = 10f
            b.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            b.setPadding(UI.dp(a, 6), UI.dp(a, 2), UI.dp(a, 6), UI.dp(a, 2))
            b.background = box(a,
                if (active) Color.argb(90, 255, 90, 44) else Color.argb(50, 255, 255, 255),
                8f, Color.TRANSPARENT)
            val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            blp.marginStart = UI.dp(a, 6)
            row.addView(b, blp)
        }
        val rlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(a, h))
        rlp.setMargins(0, UI.dp(a, 2), 0, UI.dp(a, 2))
        row.layoutParams = rlp
        row.isEnabled = enabled
        row.alpha = if (enabled) 1f else 0.55f
        if (enabled) {
            row.isClickable = true
            row.isFocusable = true
            row.contentDescription = label
            row.setOnClickListener {
                row.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onTap()
            }
        }
        parent.addView(row)
        return row
    }

    fun subLabel(a: EditorActivity, parent: LinearLayout, text: String): TextView {
        val t = TextView(a)
        t.text = text.uppercase()
        t.setTextColor(UI.ACCENT2)
        t.textSize = 9.5f
        t.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        t.letterSpacing = 0.08f
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(UI.dp(a, 4), UI.dp(a, 8), UI.dp(a, 4), UI.dp(a, 4))
        t.layoutParams = lp
        parent.addView(t)
        return t
    }

    fun noteRow(a: EditorActivity, parent: LinearLayout, text: String): TextView {
        val t = TextView(a)
        t.text = text
        t.setTextColor(UI.FG2)
        t.textSize = 11f
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(UI.dp(a, 6), UI.dp(a, 4), UI.dp(a, 6), UI.dp(a, 6))
        t.layoutParams = lp
        parent.addView(t)
        return t
    }

    fun divider(a: EditorActivity, parent: LinearLayout) {
        val line = View(a)
        line.setBackgroundColor(Color.argb(40, 255, 255, 255))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        lp.setMargins(0, UI.dp(a, 6), 0, UI.dp(a, 6))
        parent.addView(line, lp)
    }

    // ================= sidebar open / section state =================

    fun setSidebarOpen(a: EditorActivity, open: Boolean, animate: Boolean = true) {
        if (!a.chromeInitialized()) return
        a.sidebarOpen = open
        val bar = a.sidebar
        val w = if (bar.width > 0) bar.width else UI.dp(a,
            if (a.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                244 else 256)
        if (open) {
            bar.visibility = View.VISIBLE
            if (animate) {
                bar.translationX = -w.toFloat()
                bar.animate().translationX(0f).setDuration(210)
                    .setInterpolator(OvershootInterpolator(1.1f)).start()
            } else {
                bar.translationX = 0f
            }
        } else if (animate) {
            bar.animate().translationX(-w.toFloat()).setDuration(180)
                .withEndAction { bar.visibility = View.INVISIBLE }.start()
        } else {
            bar.translationX = -w.toFloat()
            bar.visibility = View.INVISIBLE
        }
    }

    fun toggleSidebar(a: EditorActivity) {
        setSidebarOpen(a, !a.sidebarOpen)
    }

    /** Open a section (and remember the state across chrome re-layouts). */
    fun setSection(a: EditorActivity, id: String, open: Boolean) {
        val s = a.sec[id] ?: return
        if (s.open == open) return
        s.open = open
        a.sectionOpen[id] = open
        s.body.visibility = if (open) View.VISIBLE else View.GONE
        s.chev.text = if (open) "▾" else "▸"
        if (open) a.onSectionOpened(id)
    }

    // ================= empty state =================

    private fun buildEmptyOverlay(a: EditorActivity): LinearLayout {
        val panel = LinearLayout(a)
        panel.orientation = LinearLayout.VERTICAL
        panel.gravity = Gravity.CENTER_HORIZONTAL
        panel.setPadding(UI.dp(a, 28), UI.dp(a, 26), UI.dp(a, 28), UI.dp(a, 24))
        panel.background = box(a, Color.argb(235, 18, 20, 27), 20f,
            Color.argb(90, 255, 255, 255))
        panel.elevation = UI.dpf(a, 10f)

        val iconTile = FrameLayout(a)
        iconTile.background = box(a, UI.BG3, 32f, Color.argb(60, 255, 255, 255))
        val icon = ImageView(a)
        icon.setImageDrawable(Ic.get(a, R.drawable.ic_layers, UI.ACCENT2))
        icon.setPadding(UI.dp(a, 14), UI.dp(a, 14), UI.dp(a, 14), UI.dp(a, 14))
        iconTile.addView(icon, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER))
        panel.addView(iconTile, LinearLayout.LayoutParams(UI.dp(a, 64), UI.dp(a, 64)))

        val title = TextView(a)
        title.text = "Your canvas is ready"
        title.setTextColor(UI.FG)
        title.textSize = 15f
        title.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        title.gravity = Gravity.CENTER
        val tlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        tlp.topMargin = UI.dp(a, 14)
        title.layoutParams = tlp
        panel.addView(title)

        val sub = TextView(a)
        sub.text = "Add a camera, video, image or text to begin.\nEverything overlays this full-screen canvas."
        sub.setTextColor(UI.FG2)
        sub.textSize = 12f
        sub.gravity = Gravity.CENTER
        val slp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        slp.topMargin = UI.dp(a, 6)
        sub.layoutParams = slp
        panel.addView(sub)

        val add = TextView(a)
        add.text = "Add a source"
        add.setTextColor(Color.rgb(14, 14, 16))
        add.textSize = 12.5f
        add.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        add.gravity = Gravity.CENTER
        add.includeFontPadding = false
        add.background = box(a, UI.ACCENT, 20f, Color.argb(120, 255, 200, 160))
        add.setOnClickListener { a.openSidebarAt("layers") }
        val alp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(a, 40))
        alp.topMargin = UI.dp(a, 16)
        add.layoutParams = alp
        panel.addView(add)
        return panel
    }

    // ================= small widgets =================

    private fun chip(a: EditorActivity, fill: Int, stroke: Int = Color.argb(60, 255, 255, 255)): TextView {
        val t = TextView(a)
        t.setTextColor(UI.FG2)
        t.textSize = 11f
        t.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        t.gravity = Gravity.CENTER
        t.includeFontPadding = false
        t.setPadding(UI.dp(a, 12), 0, UI.dp(a, 12), 0)
        t.background = box(a, fill, 14f, stroke)
        return t
    }

    private fun box(a: Context, color: Int, rDp: Float, stroke: Int): GradientDrawable {
        val g = GradientDrawable()
        g.cornerRadius = UI.dpf(a, rDp)
        g.setColor(color)
        if (stroke != Color.TRANSPARENT) g.setStroke(1, stroke)
        return g
    }

    private fun roundedRight(a: Context, color: Int, rDp: Float): GradientDrawable {
        val r = UI.dpf(a, rDp)
        val g = GradientDrawable()
        g.cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        g.setColor(color)
        g.setStroke(1, Color.argb(55, 255, 255, 255))
        return g
    }

    // ================= snack bar / progress construction =================

    private fun buildSnackBar(a: EditorActivity, root: FrameLayout) {
        val bar = LinearLayout(a)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(UI.dp(a, 16), UI.dp(a, 10), UI.dp(a, 8), UI.dp(a, 10))
        bar.background = box(a, Color.argb(242, 18, 20, 27), 14f,
            Color.argb(110, 255, 255, 255))
        bar.elevation = UI.dpf(a, 8f)
        bar.visibility = View.GONE
        val msg = TextView(a)
        msg.setTextColor(Color.WHITE)
        msg.textSize = 12.5f
        msg.maxLines = 2
        msg.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        bar.addView(msg)
        val action = TextView(a)
        action.setTextColor(UI.ACCENT2)
        action.textSize = 12.5f
        action.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        action.setPadding(UI.dp(a, 12), UI.dp(a, 6), UI.dp(a, 12), UI.dp(a, 6))
        bar.addView(action)
        snackBar = bar
        snackMsg = msg
        snackAction = action
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        lp.setMargins(UI.dp(a, 14), 0, UI.dp(a, 14), UI.dp(a, 76))
        root.addView(bar, lp)
    }

    private fun buildProgOverlay(a: EditorActivity, root: FrameLayout) {
        val over = FrameLayout(a)
        over.setBackgroundColor(Color.argb(150, 0, 0, 0))
        over.visibility = View.GONE
        over.isClickable = true
        val card = LinearLayout(a)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(UI.dp(a, 20), UI.dp(a, 18), UI.dp(a, 20), UI.dp(a, 16))
        card.background = box(a, Color.argb(250, 20, 23, 31), 16f,
            Color.argb(100, 255, 255, 255))
        val title = TextView(a)
        title.setTextColor(Color.WHITE)
        title.textSize = 14f
        title.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        card.addView(title)
        val msgT = TextView(a)
        msgT.setTextColor(Color.argb(210, 235, 238, 245))
        msgT.textSize = 12f
        val mlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        mlp.topMargin = UI.dp(a, 4)
        msgT.layoutParams = mlp
        card.addView(msgT)
        val barP = android.widget.ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal)
        barP.max = 100
        barP.progressTintList = android.content.res.ColorStateList.valueOf(UI.ACCENT)
        val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        blp.topMargin = UI.dp(a, 12)
        barP.layoutParams = blp
        card.addView(barP)
        val cancel = TextView(a)
        cancel.text = "Cancel"
        cancel.gravity = Gravity.CENTER
        cancel.setTextColor(UI.DANGER)
        cancel.textSize = 13f
        cancel.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        cancel.setPadding(0, UI.dp(a, 10), 0, UI.dp(a, 2))
        cancel.contentDescription = "Cancel"
        cancel.setOnClickListener { progOnCancel?.invoke() }
        card.addView(cancel)
        val clp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        clp.setMargins(UI.dp(a, 36), 0, UI.dp(a, 36), 0)
        over.addView(card, clp)
        progOverlay = over
        progTitle = title
        progMsg = msgT
        progBar = barP
        progCancel = cancel
        root.addView(over, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}
