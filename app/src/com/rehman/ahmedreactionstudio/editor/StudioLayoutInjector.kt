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
import com.rehman.ahmedreactionstudio.util.UI

/**
 * Polished full-bleed canvas studio — every control floats over the canvas.
 *
 * Design system alignment:
 * - Top strip: 48dp, radius 16dp, BG2@94% + white 12% stroke, elevation 8dp.
 *   Title is the ONLY flex element, ellipsises. Fit ladder: labelled → icon-only
 *   Save/Export → no aspect chip (overflow).
 * - Sidebar: 244dp L / 256dp P, BG + right radius 16dp, elevation 12dp, stroke 22% white.
 *   Sections: header 44dp, radius 10dp, icon 18dp ACCENT2, title 11.5sp Bold caps 0.08 spacing,
 *   badge 10.5sp FG2, chevron 12sp. Body padding 2/6/2/10.
 *   Rows: 44dp (52dp with sub), radius 10dp, active = orange wash 70 + stroke 120 orange,
 *   icon 18dp, label 13sp, sub 10.5sp 160 alpha, badge pill radius 8dp.
 * - Timeline: single pill 56dp, radius 27dp, 242,27,30,38 + 60 white stroke, elevation 8dp.
 *   Play 44dp white, time mono 12sp FG, seek flex 36dp floor, divider 1dp 70 white,
 *   stop 40dp FG2, record pill ACCENT radius 20dp min 104dp height 40dp.
 * - Quick bar: scrollable, background 235,27,30,38 radius 22dp stroke 60 white elevation 8dp.
 * - Chip row: hidden pill BG2 radius 14dp 11sp Bold FG2 32dp, recChip 235,52,22,18 radius 14dp,
 *   stats HUD mono 10.5sp OK on 190,12,14,18 radius 8dp.
 * - Empty overlay: 320dp, 235,18,20,27 radius 20dp stroke 90 white elevation 10dp,
 *   icon tile 72dp BG3 radius 24dp, title 16sp Bold, sub 12.5sp FG2, CTA accent pill 42dp.
 * - Snack: 242,18,20,27 radius 14dp stroke 110 white elevation 8dp, margin bottom 80dp to avoid timeline.
 * - Progress: dim 160 black, card 250,20,23,31 radius 16dp stroke 100 white elevation 12dp.
 */
object StudioLayoutInjector {

    class StudioSection(
        val id: String,
        val icon: ImageView,
        val badge: TextView,
        val chev: TextView,
        val body: LinearLayout
    ) {
        var open: Boolean = true
    }

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
        bar.translationY = UI.dpf(a, 14f)
        bar.animate().alpha(1f).translationY(0f).setDuration(220).start()
        bar.contentDescription = msg
        snackHandler.postDelayed(snackHide, 3800L)
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
        progOverlay?.alpha = 0f
        progOverlay?.animate()?.alpha(1f)?.setDuration(180)?.start()
    }

    fun updateProgress(a: EditorActivity, pct: Int, msg: String) {
        progBar?.progress = pct.coerceIn(0, 100)
        progMsg?.text = msg
    }

    fun dismissProgress(a: EditorActivity) {
        progOverlay?.animate()?.alpha(0f)?.setDuration(150)?.withEndAction {
            progOverlay?.visibility = View.GONE
        }?.start()
        progOnCancel = null
    }

    fun inject(activity: EditorActivity, root: FrameLayout) {
        root.removeAllViews()
        val landscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        val stage = StageView(activity)
        stage.host = activity
        activity.stage = stage
        root.addView(stage, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        activity.applyViewportInsets()

        activity.emptyOverlay = buildEmptyOverlay(activity)
        root.addView(activity.emptyOverlay, FrameLayout.LayoutParams(
            UI.dp(activity, 320), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        activity.topBar = buildTopStrip(activity)
        val stripLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            UI.dp(activity, 48), Gravity.TOP or Gravity.START)
        stripLp.setMargins(UI.dp(activity, 10), UI.dp(activity, 8), UI.dp(activity, 10), 0)
        root.addView(activity.topBar, stripLp)

        activity.sidebar = buildSidebar(activity)
        val sideLp = FrameLayout.LayoutParams(UI.dp(activity, if (landscape) 244 else 256),
            ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.START)
        sideLp.topMargin = UI.dp(activity, 56)
        root.addView(activity.sidebar, sideLp)

        activity.hiddenPill = chip(activity, UI.BG2)
        activity.hiddenPill.text = "1 hidden"
        activity.hiddenPill.setOnClickListener { activity.showAllHidden() }
        val hpLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
        hpLp.setMargins(UI.dp(activity, 12), UI.dp(activity, 60), 0, 0)
        root.addView(activity.hiddenPill, hpLp)
        activity.hiddenPill.visibility = View.GONE

        activity.recChip = chip(activity, Color.argb(235, 52, 22, 18),
            Color.argb(160, 255, 90, 44))
        activity.recChip.setTextColor(UI.FG)
        activity.recChip.setOnClickListener { activity.recChipTap() }
        val rcLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        rcLp.topMargin = UI.dp(activity, 60)
        root.addView(activity.recChip, rcLp)
        activity.recChip.visibility = View.GONE

        activity.statsHud = TextView(activity)
        activity.statsHud.setTextColor(UI.OK)
        activity.statsHud.textSize = 10.5f
        activity.statsHud.typeface = Typeface.create("monospace", Typeface.NORMAL)
        activity.statsHud.gravity = Gravity.END
        activity.statsHud.background = box(activity, Color.argb(190, 12, 14, 18), 10f,
            Color.argb(70, 255, 255, 255))
        activity.statsHud.setPadding(UI.dp(activity, 12), UI.dp(activity, 7),
            UI.dp(activity, 12), UI.dp(activity, 7))
        activity.statsHud.elevation = UI.dpf(activity, 2f)
        val hudLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
        hudLp.setMargins(0, UI.dp(activity, 60), UI.dp(activity, 12), 0)
        root.addView(activity.statsHud, hudLp)
        activity.statsHud.visibility = View.GONE

        val quickWrap = HorizontalScrollView(activity)
        quickWrap.isHorizontalScrollBarEnabled = false
        quickWrap.overScrollMode = View.OVER_SCROLL_NEVER
        val quickBar = LinearLayout(activity)
        quickBar.orientation = LinearLayout.HORIZONTAL
        quickBar.gravity = Gravity.CENTER_VERTICAL
        quickBar.background = box(activity, Color.argb(235, 27, 30, 38), 22f,
            Color.argb(60, 255, 255, 255))
        quickBar.elevation = UI.dpf(activity, UI.ELEV_MED)
        quickBar.setPadding(UI.dp(activity, 6), UI.dp(activity, 4), UI.dp(activity, 6), UI.dp(activity, 4))
        activity.quickWrap = quickWrap
        activity.quickBar = quickBar
        quickWrap.addView(quickBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val qwLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        qwLp.setMargins(UI.dp(activity, 8), UI.dp(activity, 96), UI.dp(activity, 8), 0)
        root.addView(quickWrap, qwLp)
        quickWrap.visibility = View.GONE

        activity.timelinePill = buildTimeline(activity)
        val tlLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            UI.dp(activity, 56), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        tlLp.setMargins(UI.dp(activity, 12), 0, UI.dp(activity, 12), UI.dp(activity, 14))
        root.addView(activity.timelinePill, tlLp)

        activity.fullExitBtn = TextView(activity)
        activity.fullExitBtn.text = "✕  Exit full canvas"
        activity.fullExitBtn.setTextColor(UI.FG)
        activity.fullExitBtn.textSize = 12.5f
        activity.fullExitBtn.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        activity.fullExitBtn.gravity = Gravity.CENTER
        activity.fullExitBtn.includeFontPadding = false
        activity.fullExitBtn.setPadding(UI.dp(activity, 16), 0, UI.dp(activity, 16), 0)
        activity.fullExitBtn.background = box(activity, Color.argb(240, 38, 42, 52), 20f,
            Color.argb(110, 255, 255, 255))
        activity.fullExitBtn.elevation = UI.dpf(activity, UI.ELEV_MED)
        activity.fullExitBtn.setOnClickListener { activity.setFullCanvas(false) }
        val feLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            UI.dp(activity, 38), Gravity.TOP or Gravity.END)
        feLp.setMargins(0, UI.dp(activity, 12), UI.dp(activity, 12), 0)
        root.addView(activity.fullExitBtn, feLp)
        activity.fullExitBtn.visibility = View.GONE

        buildSnackBar(activity, root)
        buildProgOverlay(activity, root)

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

        val firstLayout = !activity.chromeLaidOut
        activity.chromeLaidOut = true
        setSidebarOpen(activity, activity.sidebarOpen || (firstLayout && landscape),
            animate = false)
    }

    private fun buildTopStrip(a: EditorActivity): LinearLayout {
        val strip = LinearLayout(a)
        strip.orientation = LinearLayout.HORIZONTAL
        strip.gravity = Gravity.CENTER_VERTICAL
        strip.background = box(a, Color.argb(240, 27, 30, 38), 16f,
            Color.argb(60, 255, 255, 255))
        strip.elevation = UI.dpf(a, UI.ELEV_MED)
        strip.setPadding(UI.dp(a, 10), 0, UI.dp(a, 10), 0)

        val sidebarBtn = IconBtn(a)
        sidebarBtn.setIcon(R.drawable.ic_menu, UI.FG, "Toggle controls panel")
        sidebarBtn.setOnClickListener { a.toggleSidebar() }
        a.sidebarBtn = sidebarBtn
        strip.addView(sidebarBtn, LinearLayout.LayoutParams(UI.dp(a, 40), UI.dp(a, 40)))

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
        title.textSize = 13f
        title.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        title.letterSpacing = -0.01f
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        a.titleView = title
        titleBox.addView(title)
        val meta = TextView(a)
        meta.tag = "meta"
        meta.setTextColor(UI.FG2)
        meta.textSize = 10f
        meta.letterSpacing = 0.02f
        meta.maxLines = 1
        meta.ellipsize = TextUtils.TruncateAt.END
        a.metaView = meta
        titleBox.addView(meta)
        val tlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        tlp.marginStart = UI.dp(a, 10)
        strip.addView(titleBox, tlp)

        val chip = TextView(a)
        chip.text = (a.proj?.aspect ?: Aspect.R169).code
        chip.setTextColor(UI.FG2)
        chip.textSize = 11.5f
        chip.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        chip.gravity = Gravity.CENTER
        chip.includeFontPadding = false
        chip.setPadding(UI.dp(a, 12), 0, UI.dp(a, 12), 0)
        chip.background = box(a, UI.BG3, 15f, Color.argb(50, 255, 255, 255))
        chip.contentDescription = "Canvas aspect ratio"
        chip.minHeight = UI.dp(a, 30)
        chip.setOnClickListener { a.showAspectPicker() }
        a.aspectChip = chip
        val clp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UI.dp(a, 30))
        clp.setMargins(UI.dp(a, 6), 0, UI.dp(a, 2), 0)
        strip.addView(chip, clp)

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

        val save = TextView(a)
        save.text = "Saved"
        save.setTextColor(UI.OK)
        save.textSize = 11.5f
        save.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        save.letterSpacing = 0.02f
        save.gravity = Gravity.CENTER
        save.includeFontPadding = false
        save.setPadding(UI.dp(a, 12), 0, UI.dp(a, 12), 0)
        save.background = box(a, UI.BG3, 15f, Color.argb(50, 255, 255, 255))
        save.contentDescription = "Save project"
        save.minHeight = UI.dp(a, 30)
        save.setOnClickListener { a.saveNow() }
        a.savePill = save
        val slp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UI.dp(a, 30))
        slp.setMargins(UI.dp(a, 8), 0, 0, 0)
        strip.addView(save, slp)

        val export = TextView(a)
        export.text = "Export"
        export.setTextColor(UI.ACCENT_FG)
        export.textSize = 11.5f
        export.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        export.letterSpacing = 0.02f
        export.gravity = Gravity.CENTER
        export.includeFontPadding = false
        export.setPadding(UI.dp(a, 14), 0, UI.dp(a, 14), 0)
        export.background = box(a, UI.ACCENT, 15f, Color.argb(120, 255, 200, 160))
        export.elevation = UI.dpf(a, 2f)
        export.contentDescription = "Quick export with saved settings"
        export.minHeight = UI.dp(a, 30)
        export.setOnClickListener { a.quickExport() }
        a.exportPill = export
        val elp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UI.dp(a, 30))
        elp.setMargins(UI.dp(a, 8), 0, 0, 0)
        strip.addView(export, elp)

        val fc = IconBtn(a)
        fc.setIcon(R.drawable.ic_fullscreen, UI.FG, "Full screen canvas")
        fc.setOnClickListener { a.enterFullCanvas() }
        a.fullCanvasBtn = fc
        val fclp = LinearLayout.LayoutParams(UI.dp(a, 36), UI.dp(a, 36))
        fclp.marginStart = UI.dp(a, 6)
        strip.addView(fc, fclp)
        val more = IconBtn(a)
        more.setIcon(R.drawable.ic_more, UI.FG2, "More options")
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
            .setTitle("Studio")
            .setItems(arrayOf(
                "Canvas aspect…",
                if (hud) "Hide stats overlay" else "Show stats overlay",
                "Full screen canvas",
                "Diagnostics"
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
            a.savePill.text = if (a.savePill.text.toString() == "●" || a.savePill.text.toString() == "✓") "Saved" else a.savePill.text
        } else {
            a.exportPill.text = " "
            a.exportPill.setCompoundDrawablesRelativeWithIntrinsicBounds(
                Ic.get(a, R.drawable.ic_export, Color.rgb(14, 14, 16)), null, null, null)
            // save pill icon-only when tier 1
            if (tier == 1) {
                val isDirty = a.savePill.text.toString().contains("Save") || a.savePill.text.toString() == "●"
                a.savePill.text = if (isDirty) "●" else "✓"
            }
        }
        a.aspectChip.visibility = if (tier == 2) View.GONE else View.VISIBLE
    }

    private fun buildTimeline(a: EditorActivity): LinearLayout {
        val tl = LinearLayout(a)
        tl.orientation = LinearLayout.HORIZONTAL
        tl.gravity = Gravity.CENTER_VERTICAL
        tl.background = box(a, Color.argb(242, 27, 30, 38), 28f,
            Color.argb(60, 255, 255, 255))
        tl.elevation = UI.dpf(a, UI.ELEV_MED)
        tl.setPadding(UI.dp(a, 12), 0, UI.dp(a, 12), 0)

        val play = IconBtn(a)
        play.setIcon(R.drawable.ic_play, Color.WHITE, "Play timeline")
        play.setOnClickListener { a.togglePlay() }
        a.playBtn = play
        tl.addView(play, LinearLayout.LayoutParams(UI.dp(a, 44), UI.dp(a, 44)))

        val time = TextView(a)
        time.text = "0:00"
        time.setTextColor(UI.FG)
        time.textSize = 12.5f
        time.typeface = Typeface.create("monospace", Typeface.BOLD)
        time.letterSpacing = 0.02f
        time.gravity = Gravity.CENTER_VERTICAL
        time.setPadding(UI.dp(a, 8), 0, 0, 0)
        time.minWidth = UI.dp(a, 48)
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
        seek.minimumWidth = UI.dp(a, 40)
        val sep = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        sep.setMargins(UI.dp(a, 4), 0, UI.dp(a, 8), 0)
        tl.addView(seek, sep)

        val dur = TextView(a)
        dur.text = "/ 0:00"
        dur.setTextColor(UI.FG2)
        dur.textSize = 12f
        dur.typeface = Typeface.create("monospace", Typeface.NORMAL)
        dur.letterSpacing = 0.02f
        a.durationLabel = dur
        tl.addView(dur, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))

        val vdiv = View(a)
        vdiv.setBackgroundColor(Color.argb(70, 255, 255, 255))
        tl.addView(vdiv, LinearLayout.LayoutParams(UI.dp(a, 1), UI.dp(a, 28)).apply {
            setMargins(UI.dp(a, 10), 0, UI.dp(a, 10), 0)
        })

        val stop = IconBtn(a)
        stop.setIcon(R.drawable.ic_stop, UI.FG2, "Stop playback")
        stop.setOnClickListener { a.controlsStopTap() }
        a.recordStopBtn = stop
        tl.addView(stop, LinearLayout.LayoutParams(UI.dp(a, 40), UI.dp(a, 40)))

        val rec = TextView(a)
        rec.text = "●  RECORD"
        rec.setTextColor(UI.ACCENT_FG)
        rec.textSize = 11.5f
        rec.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        rec.letterSpacing = 0.04f
        rec.gravity = Gravity.CENTER
        rec.includeFontPadding = false
        rec.setPadding(UI.dp(a, 16), 0, UI.dp(a, 16), 0)
        rec.background = box(a, UI.ACCENT, 20f, Color.argb(140, 255, 200, 160))
        rec.elevation = UI.dpf(a, 2f)
        rec.contentDescription = "Record reaction"
        rec.minHeight = UI.dp(a, 40)
        rec.setOnClickListener { a.recordButtonTap() }
        a.recordBtn = rec
        val rlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UI.dp(a, 40))
        rlp.marginStart = UI.dp(a, 10)
        rec.minimumWidth = UI.dp(a, 108)
        tl.addView(rec, rlp)
        return tl
    }

    fun layoutTimeline(a: EditorActivity) {
        if (!a.chromeInitialized()) return
        val rootW = a.rootFrame.width
        if (rootW <= 0) return
        val landscape = a.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val w = if (landscape) minOf((rootW * 0.86f).toInt(), UI.dp(a, 760))
        else (rootW * 0.94f).toInt()
        val lp = a.timelinePill.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.width != w) {
            lp.width = w
            a.timelinePill.layoutParams = lp
        }
    }

    private fun buildSidebar(a: EditorActivity): LinearLayout {
        val bar = LinearLayout(a)
        bar.orientation = LinearLayout.VERTICAL
        bar.background = roundedRight(a, UI.BG, 16f)
        bar.elevation = UI.dpf(a, UI.ELEV_HIGH)

        val scroll = ScrollView(a)
        scroll.isVerticalScrollBarEnabled = false
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER)
        scroll.clipToPadding = false
        scroll.setPadding(0, 0, 0, UI.dp(a, 12))
        a.panelScroll = scroll
        bar.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val content = LinearLayout(a)
        content.orientation = LinearLayout.VERTICAL
        content.setBackgroundColor(Color.TRANSPARENT)
        content.setPadding(UI.dp(a, 8), UI.dp(a, 8), UI.dp(a, 8), UI.dp(a, 16))
        a.panelContent = content
        scroll.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fun openOf(id: String, def: Boolean) = a.sectionOpen.getOrDefault(id, def)

        val layers = section(a, content, "layers", R.drawable.ic_layers, "Layers",
            openOf("layers", true))
        a.layersHost = LinearLayout(a)
        a.layersHost.orientation = LinearLayout.VERTICAL
        layers.body.addView(a.layersHost)
        divider(a, layers.body)
        subLabel(a, layers.body, "Add source")
        a.addHost = LinearLayout(a)
        a.addHost.orientation = LinearLayout.VERTICAL
        actRow(a, a.addHost, R.drawable.ic_camera, "Camera (live)",
            "on the canvas — frame it, then record") { a.addCameraLive() }
        actRow(a, a.addHost, R.drawable.ic_camera, "Camera take",
            "fullscreen recorder → PiP clip") { a.openCamera() }
        actRow(a, a.addHost, R.drawable.ic_video, "Video file",
            "from your phone") { a.addVideo() }
        actRow(a, a.addHost, R.drawable.ic_image, "Image",
            "from your phone") { a.addImage() }
        actRow(a, a.addHost, R.drawable.ic_screen, "Screen record",
            "mirrors this phone") { a.addScreen() }
        actRow(a, a.addHost, R.drawable.ic_text, "Text",
            "overlay caption") { a.addTextSource() }
        layers.body.addView(a.addHost)
        divider(a, layers.body)
        a.layersActionsHost = LinearLayout(a)
        a.layersActionsHost.orientation = LinearLayout.VERTICAL
        layers.body.addView(a.layersActionsHost)

        val source = section(a, content, "source", R.drawable.ic_layers, "Source",
            openOf("source", false))
        a.sourceSectionBody = LinearLayout(a)
        a.sourceSectionBody.orientation = LinearLayout.VERTICAL
        source.body.addView(a.sourceSectionBody)

        val audio = section(a, content, "audio", R.drawable.ic_volume, "Audio",
            openOf("audio", true))
        a.audioHost = LinearLayout(a)
        a.audioHost.orientation = LinearLayout.VERTICAL
        audio.body.addView(a.audioHost)

        val record = section(a, content, "record", R.drawable.ic_video, "Record",
            openOf("record", true))
        a.recordSectionBody = LinearLayout(a)
        a.recordSectionBody.orientation = LinearLayout.VERTICAL
        record.body.addView(a.recordSectionBody)

        val canvas = section(a, content, "canvas", R.drawable.ic_aspect, "Canvas",
            openOf("canvas", true))
        a.canvasHost = LinearLayout(a)
        a.canvasHost.orientation = LinearLayout.VERTICAL
        canvas.body.addView(a.canvasHost)

        val export = section(a, content, "export", R.drawable.ic_export, "Export",
            openOf("export", true))
        a.exportHost = LinearLayout(a)
        a.exportHost.orientation = LinearLayout.VERTICAL
        export.body.addView(a.exportHost)

        val project = section(a, content, "project", R.drawable.ic_settings, "Project",
            openOf("project", true))
        a.projectHost = LinearLayout(a)
        a.projectHost.orientation = LinearLayout.VERTICAL
        actRow(a, a.projectHost, R.drawable.ic_edit, "Rename project") { a.renameProject() }
        actRow(a, a.projectHost, R.drawable.ic_check, "Save now") { a.saveNow() }
        actRow(a, a.projectHost, R.drawable.ic_info, "Diagnostics") { a.openDiagnostics() }
        actRow(a, a.projectHost, R.drawable.ic_back, "Close project", danger = true) { a.closeProject() }
        project.body.addView(a.projectHost)

        a.sec.clear()
        a.sec.putAll(mapOf(
            "layers" to layers, "source" to source, "audio" to audio,
            "record" to record, "canvas" to canvas, "export" to export,
            "project" to project
        ))
        return bar
    }

    private fun section(a: EditorActivity, parent: LinearLayout, id: String, iconRes: Int,
                        title: String, open: Boolean): StudioSection {
        val header = LinearLayout(a)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(UI.dp(a, 12), UI.dp(a, 10), UI.dp(a, 10), UI.dp(a, 10))
        header.isClickable = true
        header.isFocusable = true
        header.background = box(a, Color.argb(45, 255, 255, 255), 12f, Color.TRANSPARENT)
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
        tlp.marginStart = UI.dp(a, 10)
        header.addView(t, tlp)
        val badge = TextView(a)
        badge.setTextColor(UI.FG2)
        badge.textSize = 10.5f
        badge.maxLines = 1
        badge.ellipsize = TextUtils.TruncateAt.END
        badge.gravity = Gravity.CENTER_VERTICAL
        badge.letterSpacing = 0.02f
        val blp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        blp.marginStart = UI.dp(a, 10)
        header.addView(badge, blp)
        val chev = TextView(a)
        chev.text = if (open) "▾" else "▸"
        chev.setTextColor(UI.FG2)
        chev.textSize = 13f
        chev.gravity = Gravity.CENTER
        chev.setPadding(UI.dp(a, 6), 0, 0, 0)
        header.addView(chev, LinearLayout.LayoutParams(UI.dp(a, 24), UI.dp(a, 24)))

        val body = LinearLayout(a)
        body.orientation = LinearLayout.VERTICAL
        body.setBackgroundColor(Color.TRANSPARENT)
        body.setPadding(UI.dp(a, 2), UI.dp(a, 8), UI.dp(a, 2), UI.dp(a, 12))
        body.visibility = if (open) View.VISIBLE else View.GONE

        val sec = StudioSection(id, icon, badge, chev, body).apply { this.open = open }
        header.setOnClickListener {
            sec.open = !sec.open
            a.sectionOpen[id] = sec.open
            body.visibility = if (sec.open) View.VISIBLE else View.GONE
            chev.text = if (sec.open) "▾" else "▸"
            // subtle spring
            if (sec.open) {
                body.alpha = 0f
                body.translationY = UI.dpf(a, -6f)
                body.animate().alpha(1f).translationY(0f).setDuration(180).start()
                a.onSectionOpened(id)
            }
        }
        parent.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            UI.dp(a, 44)).apply { topMargin = UI.dp(a, 4) })
        parent.addView(body)
        return sec
    }

    fun actRow(a: EditorActivity, parent: LinearLayout, icon: Int, label: String,
               sub: String? = null, active: Boolean = false, danger: Boolean = false,
               enabled: Boolean = true, badge: String? = null, badgeColor: Int? = null,
               onTap: () -> Unit): LinearLayout {
        val row = LinearLayout(a)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(UI.dp(a, 12), 0, UI.dp(a, 12), 0)
        val h = if (sub != null) 52 else 44
        row.background = if (active) box(a, Color.argb(70, 255, 90, 44), 12f,
            Color.argb(120, 255, 90, 44))
        else box(a, Color.TRANSPARENT, 12f, Color.TRANSPARENT)
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
        col.setBackgroundColor(Color.TRANSPARENT)
        val lbl = TextView(a)
        lbl.text = label
        lbl.setTextColor(when {
            !enabled -> Color.argb(110, 255, 255, 255)
            danger -> UI.DANGER
            else -> UI.FG
        })
        lbl.textSize = 13f
        lbl.letterSpacing = -0.01f
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
            s.letterSpacing = 0.01f
            col.addView(s)
        }
        val clp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        clp.marginStart = UI.dp(a, 12)
        col.layoutParams = clp
        row.addView(col)
        if (badge != null) {
            val b = TextView(a)
            b.text = badge
            b.setTextColor(badgeColor ?: (if (active) UI.ACCENT2 else UI.FG2))
            b.textSize = 10f
            b.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            b.letterSpacing = 0.04f
            b.setPadding(UI.dp(a, 7), UI.dp(a, 2), UI.dp(a, 7), UI.dp(a, 2))
            b.background = box(a,
                if (active) Color.argb(90, 255, 90, 44) else Color.argb(50, 255, 255, 255),
                8f, Color.TRANSPARENT)
            val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            blp.marginStart = UI.dp(a, 8)
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
            row.contentDescription = if (sub != null) "$label — $sub" else label
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
        t.textSize = 10f
        t.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        t.letterSpacing = 0.08f
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(UI.dp(a, 4), UI.dp(a, 12), UI.dp(a, 4), UI.dp(a, 6))
        t.layoutParams = lp
        parent.addView(t)
        return t
    }

    fun noteRow(a: EditorActivity, parent: LinearLayout, text: String): TextView {
        val t = TextView(a)
        t.text = text
        t.setTextColor(UI.FG2)
        t.textSize = 11f
        t.letterSpacing = 0.01f
        t.lineSpacing = UI.dpf(a, 2f), 1f
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(UI.dp(a, 8), UI.dp(a, 6), UI.dp(a, 8), UI.dp(a, 8))
        t.layoutParams = lp
        parent.addView(t)
        return t
    }

    fun divider(a: EditorActivity, parent: LinearLayout) {
        val line = View(a)
        line.setBackgroundColor(Color.argb(40, 255, 255, 255))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        lp.setMargins(0, UI.dp(a, 10), 0, UI.dp(a, 10))
        parent.addView(line, lp)
    }

    fun setSidebarOpen(a: EditorActivity, open: Boolean, animate: Boolean = true) {
        if (!a.chromeInitialized()) return
        a.sidebarOpen = open
        val bar = a.sidebar
        val w = if (bar.width > 0) bar.width else UI.dp(a,
            if (a.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 244 else 256)
        if (open) {
            bar.visibility = View.VISIBLE
            if (animate) {
                bar.translationX = -w.toFloat()
                bar.alpha = 0f
                bar.animate().translationX(0f).alpha(1f).setDuration(220)
                    .setInterpolator(OvershootInterpolator(0.9f)).start()
            } else {
                bar.translationX = 0f
                bar.alpha = 1f
            }
        } else if (animate) {
            bar.animate().translationX(-w.toFloat()).alpha(0f).setDuration(180)
                .withEndAction { bar.visibility = View.INVISIBLE }.start()
        } else {
            bar.translationX = -w.toFloat()
            bar.alpha = 0f
            bar.visibility = View.INVISIBLE
        }
    }

    fun toggleSidebar(a: EditorActivity) { setSidebarOpen(a, !a.sidebarOpen) }

    fun setSection(a: EditorActivity, id: String, open: Boolean) {
        val s = a.sec[id] ?: return
        if (s.open == open) return
        s.open = open
        a.sectionOpen[id] = open
        s.body.visibility = if (open) View.VISIBLE else View.GONE
        s.chev.text = if (open) "▾" else "▸"
        if (open) a.onSectionOpened(id)
    }

    private fun buildEmptyOverlay(a: EditorActivity): LinearLayout {
        val panel = LinearLayout(a)
        panel.orientation = LinearLayout.VERTICAL
        panel.gravity = Gravity.CENTER_HORIZONTAL
        panel.setPadding(UI.dp(a, 28), UI.dp(a, 28), UI.dp(a, 28), UI.dp(a, 26))
        panel.background = box(a, Color.argb(235, 18, 20, 27), 20f,
            Color.argb(90, 255, 255, 255))
        panel.elevation = UI.dpf(a, 10f)

        val iconTile = FrameLayout(a)
        iconTile.background = box(a, UI.BG3, 24f, Color.argb(60, 255, 255, 255))
        iconTile.elevation = UI.dpf(a, 2f)
        val icon = ImageView(a)
        icon.setImageDrawable(Ic.get(a, R.drawable.ic_layers, UI.ACCENT2))
        icon.setPadding(UI.dp(a, 18), UI.dp(a, 18), UI.dp(a, 18), UI.dp(a, 18))
        iconTile.addView(icon, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        panel.addView(iconTile, LinearLayout.LayoutParams(UI.dp(a, 72), UI.dp(a, 72)))

        val title = TextView(a)
        title.text = "Your canvas is ready"
        title.setTextColor(Color.WHITE)
        title.textSize = 16f
        title.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        title.letterSpacing = -0.01f
        title.gravity = Gravity.CENTER
        val tlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        tlp.topMargin = UI.dp(a, 16)
        title.layoutParams = tlp
        panel.addView(title)

        val sub = TextView(a)
        sub.text = "Add a camera, video, image or text to begin.\nEverything overlays this full-screen canvas — what you frame is what you export."
        sub.setTextColor(UI.FG2)
        sub.textSize = 12.5f
        sub.gravity = Gravity.CENTER
        sub.lineSpacing = UI.dpf(a, 2f), 1f
        val slp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        slp.topMargin = UI.dp(a, 8)
        sub.layoutParams = slp
        panel.addView(sub)

        val add = TextView(a)
        add.text = "Add a source"
        add.setTextColor(UI.ACCENT_FG)
        add.textSize = 13f
        add.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        add.letterSpacing = 0.02f
        add.gravity = Gravity.CENTER
        add.includeFontPadding = false
        add.background = box(a, UI.ACCENT, 20f, Color.argb(120, 255, 200, 160))
        add.elevation = UI.dpf(a, 2f)
        add.setOnClickListener { a.openSidebarAt("layers") }
        add.contentDescription = "Add a source"
        val alp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(a, 42))
        alp.topMargin = UI.dp(a, 20)
        add.layoutParams = alp
        panel.addView(add)
        return panel
    }

    private fun chip(a: EditorActivity, fill: Int, stroke: Int = Color.argb(60, 255, 255, 255)): TextView {
        val t = TextView(a)
        t.setTextColor(UI.FG2)
        t.textSize = 11f
        t.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        t.letterSpacing = 0.02f
        t.gravity = Gravity.CENTER
        t.includeFontPadding = false
        t.setPadding(UI.dp(a, 14), 0, UI.dp(a, 14), 0)
        t.background = box(a, fill, 16f, stroke)
        t.elevation = UI.dpf(a, 1f)
        t.minHeight = UI.dp(a, 32)
        t.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UI.dp(a, 32))
        return t
    }

    private fun box(a: Context, color: Int, rDp: Float, stroke: Int): GradientDrawable {
        val g = GradientDrawable()
        g.cornerRadius = UI.dpf(a, rDp)
        g.setColor(color)
        if (stroke != Color.TRANSPARENT) g.setStroke(UI.dp(a, 1), stroke)
        return g
    }

    private fun roundedRight(a: Context, color: Int, rDp: Float): GradientDrawable {
        val r = UI.dpf(a, rDp)
        val g = GradientDrawable()
        g.cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        g.setColor(color)
        g.setStroke(UI.dp(a, 1), Color.argb(55, 255, 255, 255))
        return g
    }

    private fun buildSnackBar(a: EditorActivity, root: FrameLayout) {
        val bar = LinearLayout(a)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(UI.dp(a, 16), UI.dp(a, 12), UI.dp(a, 10), UI.dp(a, 12))
        bar.background = box(a, Color.argb(242, 18, 20, 27), 16f,
            Color.argb(110, 255, 255, 255))
        bar.elevation = UI.dpf(a, UI.ELEV_MED)
        bar.visibility = View.GONE
        val msg = TextView(a)
        msg.setTextColor(Color.WHITE)
        msg.textSize = 13f
        msg.letterSpacing = -0.01f
        msg.maxLines = 2
        msg.ellipsize = TextUtils.TruncateAt.END
        msg.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        bar.addView(msg)
        val action = TextView(a)
        action.setTextColor(UI.ACCENT2)
        action.textSize = 13f
        action.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        action.letterSpacing = 0.02f
        action.setPadding(UI.dp(a, 14), UI.dp(a, 8), UI.dp(a, 14), UI.dp(a, 8))
        action.background = box(a, Color.argb(40, 255, 160, 44), 10f, Color.TRANSPARENT)
        bar.addView(action)
        snackBar = bar
        snackMsg = msg
        snackAction = action
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        lp.setMargins(UI.dp(a, 14), 0, UI.dp(a, 14), UI.dp(a, 80))
        root.addView(bar, lp)
    }

    private fun buildProgOverlay(a: EditorActivity, root: FrameLayout) {
        val over = FrameLayout(a)
        over.setBackgroundColor(Color.argb(160, 0, 0, 0))
        over.visibility = View.GONE
        over.isClickable = true
        over.isFocusable = true
        val card = LinearLayout(a)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(UI.dp(a, 22), UI.dp(a, 20), UI.dp(a, 22), UI.dp(a, 18))
        card.background = box(a, Color.argb(250, 20, 23, 31), 18f,
            Color.argb(100, 255, 255, 255))
        card.elevation = UI.dpf(a, UI.ELEV_HIGH)
        val title = TextView(a)
        title.setTextColor(Color.WHITE)
        title.textSize = 15f
        title.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        title.letterSpacing = -0.01f
        card.addView(title)
        val msgT = TextView(a)
        msgT.setTextColor(Color.argb(210, 235, 238, 245))
        msgT.textSize = 12.5f
        msgT.letterSpacing = 0.01f
        val mlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        mlp.topMargin = UI.dp(a, 6)
        msgT.layoutParams = mlp
        card.addView(msgT)
        val barP = android.widget.ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal)
        barP.max = 100
        barP.progressTintList = android.content.res.ColorStateList.valueOf(UI.ACCENT)
        barP.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.argb(60, 255, 255, 255))
        val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        blp.topMargin = UI.dp(a, 16)
        barP.layoutParams = blp
        card.addView(barP)
        val cancel = TextView(a)
        cancel.text = "Cancel"
        cancel.gravity = Gravity.CENTER
        cancel.setTextColor(UI.DANGER)
        cancel.textSize = 13f
        cancel.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        cancel.letterSpacing = 0.02f
        cancel.setPadding(0, UI.dp(a, 14), 0, UI.dp(a, 4))
        cancel.contentDescription = "Cancel operation"
        cancel.setOnClickListener { progOnCancel?.invoke() }
        card.addView(cancel)
        val clp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        clp.setMargins(UI.dp(a, 32), 0, UI.dp(a, 32), 0)
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
