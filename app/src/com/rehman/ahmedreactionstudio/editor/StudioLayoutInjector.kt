package com.rehman.ahmedreactionstudio.editor

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import com.rehman.ahmedreactionstudio.R
import com.rehman.ahmedreactionstudio.core.Aspect
import com.rehman.ahmedreactionstudio.core.ChromeBudget
import com.rehman.ahmedreactionstudio.util.UI

/**
 * THE Studio layout. One authoritative chrome, four size tiers, no percent
 * weights, no overlapping frames.
 *
 * Structure (every bar is dp-sized; the canvas cell takes what is left):
 *
 *   ┌──────────── top bar (48dp · 44 on phone landscape) ───────────┐
 *   │ rail │            canvas cell (flexible)          │  context  │   ← landscape
 *   │ 56dp │  StageView contain-fits the composition    │  panel    │
 *   ├──────────── transport (52dp · 44 on phone landscape) ─────────┤
 *
 *   ┌──── top bar (48) ────┐
 *   │     canvas cell      │  (flexible)
 *   ├─ tabs (40) ──────────┤                                          ← portrait
 *   │ context panel body   │  (dp budget, collapsible to tabs only)
 *   ├─ tool row (48) ──────┤
 *   ├─ transport (52) ─────┤
 *
 * How much the rail / panel get is decided by [ChromeBudget] from the usable
 * dp size, the canvas aspect and the user's open/close choices — canvas
 * first, then sources, then contextual controls. Collapsed pieces leave a
 * 28dp edge handle over the canvas cell so they can always be re-opened.
 *
 * The context panel is ONE FrameLayout body that shows exactly one of
 * Sources / Mixer / Props / Effects; the ✕ in its tab strip collapses it and
 * the canvas cell grows. Tablets show rail + canvas + panel together.
 *
 * StageView is placed INSIDE the canvas cell and told the cell's insets are
 * zero — the cell already excludes every bar — so the existing contain-fit
 * puts the whole composition, centred, in whatever remains. Overlays that
 * must float (radial wheel, snackbar, progress) are added to the root
 * FrameLayout on top of the column; canvas-local overlays (REC chip, stats
 * HUD, camera strip, hidden pill, empty prompt, Full-Canvas exit) live inside
 * the canvas cell. None of them takes layout space from the column.
 */
object StudioLayoutInjector {

    // ---- design tokens ------------------------------------------------------
    private val BAR_BG = Color.rgb(13, 15, 20)
    private val PANEL_BG = Color.rgb(20, 22, 29)
    val CANVAS_BG = Color.rgb(6, 7, 10)
    private val HAIRLINE = Color.argb(46, 255, 255, 255)
    private val TAB_ACTIVE_BG = Color.argb(44, 255, 90, 44)
    private const val RAIL_DP = ChromeBudget.RAIL_DP
    private const val TABS_DP = ChromeBudget.TABS_DP
    private const val TOOLROW_DP = ChromeBudget.TOOLROW_DP
    private const val TAP_DP = 44
    /** width of the slim re-open handles on the canvas cell's edges */
    const val EDGE_DP = 28

    /** Which chrome tier is built. Decided from the configuration, never from pixels. */
    enum class Tier { PHONE_PORTRAIT, PHONE_LANDSCAPE, TABLET_LANDSCAPE, TABLET_PORTRAIT }

    fun tierFor(cfg: Configuration): Tier {
        val landscape = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
        val tablet = cfg.smallestScreenWidthDp >= 600
        return when {
            tablet && landscape -> Tier.TABLET_LANDSCAPE
            tablet -> Tier.TABLET_PORTRAIT
            landscape -> Tier.PHONE_LANDSCAPE
            else -> Tier.PHONE_PORTRAIT
        }
    }

    fun isTablet(tier: Tier) = tier == Tier.TABLET_LANDSCAPE || tier == Tier.TABLET_PORTRAIT
    fun isLandscape(tier: Tier) = tier == Tier.PHONE_LANDSCAPE || tier == Tier.TABLET_LANDSCAPE

    /** The dp budget for the current window (usable = window minus system bars). */
    fun landscapeBudget(activity: EditorActivity): ChromeBudget.Landscape {
        val cfg = activity.resources.configuration
        val a = activity.proj?.aspect ?: Aspect.R169
        return ChromeBudget.landscape(
            activity.usableWidthDp(), activity.usableHeightDp(), a.canvasW, a.canvasH,
            tablet = cfg.smallestScreenWidthDp >= 600,
            panelOpen = activity.panelOpen, railOpen = activity.railOpen,
            extraRowsDp = activity.extraRowsDp())
    }

    fun portraitBudget(activity: EditorActivity): ChromeBudget.Portrait {
        val cfg = activity.resources.configuration
        val a = activity.proj?.aspect ?: Aspect.R916
        return ChromeBudget.portrait(
            activity.usableWidthDp(), activity.usableHeightDp(), a.canvasW, a.canvasH,
            tablet = cfg.smallestScreenWidthDp >= 600, panelOpen = activity.panelOpen,
            extraRowsDp = activity.extraRowsDp())
    }

    // =====================================================================
    // entry point
    // =====================================================================

    fun inject(activity: EditorActivity, root: FrameLayout) {
        val tier = tierFor(activity.resources.configuration)
        activity.chromeTier = tier

        // --- the column that owns all layout space -------------------------
        val column = LinearLayout(activity)
        column.orientation = LinearLayout.VERTICAL
        column.setBackgroundColor(CANVAS_BG)
        root.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        activity.chromeColumn = column

        val isLandscape = isLandscape(tier)
        // phone landscape has the least height: both bars drop to 44dp
        val compact = tier == Tier.PHONE_LANDSCAPE
        buildTopBar(activity, column, ChromeBudget.topDp(compact))

        if (isLandscape) {
            // rail | canvas | context panel — panels bound once, per build
            buildLandscapeBody(activity, column)
            bindPanels(activity, activity.sourcesPanel!!, activity.mixerPanel!!,
                activity.propertiesPanel!!, activity.effectsPanel!!)
            buildCameraRow(activity, column)
            buildTimeline(activity, column)
            buildTransport(activity, column, ChromeBudget.transportDp(compact))
        } else {
            // canvas / context panel / tool row
            buildPortraitBody(activity, column)
            bindPanels(activity, activity.sourcesPanel!!, activity.mixerPanel!!,
                activity.propertiesPanel!!, activity.effectsPanel!!)
            buildCameraRow(activity, column)
            buildTimeline(activity, column)
            buildTransport(activity, column, ChromeBudget.transportDp(compact))
        }
        activity.showTab(activity.activeTab, user = false)

        // --- floating overlays (root-level, zero layout cost) --------------
        buildOverlays(activity, root)
    }

    // =====================================================================
    // top bar
    // =====================================================================

    private fun buildTopBar(activity: EditorActivity, column: LinearLayout, heightDp: Int) {
        val bar = LinearLayout(activity)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(BAR_BG)
        bar.setPadding(UI.dp(activity, 4), 0, UI.dp(activity, 8), 0)
        column.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(activity, heightDp)))
        activity.topBar = bar

        val back = IconBtn(activity)
        back.layoutParams = IconBtn.sized(activity, TAP_DP)
        back.setIcon(R.drawable.ic_back, UI.FG, "Back to projects")
        back.setOnClickListener { activity.onBackPressed() }
        bar.addView(back)

        // project name + live meta ("16:9 · 3 sources · ✓ Saved") — tagged so
        // EditorActivity.updateName() finds them
        val titleCol = LinearLayout(activity)
        titleCol.orientation = LinearLayout.VERTICAL
        titleCol.gravity = Gravity.CENTER_VERTICAL
        titleCol.setPadding(UI.dp(activity, 4), 0, UI.dp(activity, 8), 0)
        val name = TextView(activity)
        name.tag = "name"
        name.text = activity.proj?.name ?: ""
        name.setTextColor(UI.FG)
        name.textSize = 14f
        name.maxLines = 1
        name.ellipsize = android.text.TextUtils.TruncateAt.END
        name.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        name.includeFontPadding = false
        titleCol.addView(name)
        val meta = TextView(activity)
        meta.tag = "meta"
        meta.setTextColor(UI.FG2)
        meta.textSize = 10.5f
        meta.maxLines = 1
        meta.ellipsize = android.text.TextUtils.TruncateAt.END
        meta.includeFontPadding = false
        titleCol.addView(meta)
        bar.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // REC chip (screen record / camera take in progress): a real bar item,
        // never an overlay on the picture. GONE unless a capture is running.
        val rec = TextView(activity)
        rec.text = "● REC"
        rec.textSize = 11f
        rec.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        rec.setTextColor(Color.WHITE)
        rec.gravity = Gravity.CENTER
        rec.includeFontPadding = false
        rec.maxLines = 1
        rec.setPadding(UI.dp(activity, 8), 0, UI.dp(activity, 8), 0)
        rec.background = Ic.pill(activity, Color.argb(235, 200, 34, 34), 16f, Color.argb(160, 255, 120, 120))
        rec.visibility = View.GONE
        rec.setOnClickListener { activity.recChipTap() }
        rec.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UI.dp(activity, 32))
            .apply { marginEnd = UI.dp(activity, 6) }
        activity.recChip = rec
        bar.addView(rec)

        val aspect = TextView(activity)
        aspect.text = (activity.proj?.aspect?.code ?: "16:9") + " ▾"
        aspect.setTextColor(UI.FG)
        aspect.textSize = 12f
        aspect.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        aspect.gravity = Gravity.CENTER
        aspect.includeFontPadding = false
        aspect.setPadding(UI.dp(activity, 8), 0, UI.dp(activity, 8), 0)
        aspect.background = Ic.pill(activity, Color.argb(30, 255, 255, 255), 8f, HAIRLINE)
        aspect.contentDescription = "Canvas aspect ratio"
        aspect.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UI.dp(activity, 32))
            .apply { marginEnd = UI.dp(activity, 6) }
        aspect.setOnClickListener { activity.showAspectPicker() }
        activity.aspectChip = aspect
        bar.addView(aspect)

        if (isTablet(activity.chromeTier)) {
            val save = pillBtn(activity, "Save", UI.FG, Color.argb(30, 255, 255, 255), 32) { activity.saveNow() }
            save.contentDescription = "Save project"
            bar.addView(save)
        } else {
            val save = IconBtn(activity)
            save.layoutParams = IconBtn.sized(activity, TAP_DP)
            save.setIcon(R.drawable.ic_check, UI.FG, "Save project")
            save.setOnClickListener { activity.saveNow() }
            bar.addView(save)
        }

        if (activity.chromeTier == Tier.PHONE_PORTRAIT) {
            // 360dp: an "Export" pill next to the REC chip pushes ⋯ off the bar,
            // so the primary action is an accent-filled icon here (still 44dp)
            val export = IconBtn(activity)
            export.layoutParams = IconBtn.sized(activity, TAP_DP)
            export.setIcon(R.drawable.ic_export, Color.WHITE, "Export video")
            export.background = android.graphics.drawable.InsetDrawable(
                Ic.pill(activity, UI.ACCENT, 16f, HAIRLINE), UI.dp(activity, 4))
            export.setOnClickListener { activity.quickExport() }
            export.setOnLongClickListener { activity.openExportSettings(); true }
            bar.addView(export)
        } else {
            val export = pillBtn(activity, "Export", Color.WHITE, UI.ACCENT, 32) { activity.quickExport() }
            export.contentDescription = "Export video"
            export.setOnLongClickListener { activity.openExportSettings(); true }
            bar.addView(export)
        }

        // Settings / diagnostics. Tablets: its own button. Phones: a 360dp bar
        // holding Back · aspect · Save · Export · ⋯ leaves the title 88dp and,
        // with the REC chip up, 18dp — one more 44dp item would push ⋯ off the
        // edge — so there it is the ⋯ long-press and the wheel's Project ring.
        if (isTablet(activity.chromeTier)) {
            val settings = IconBtn(activity)
            settings.layoutParams = IconBtn.sized(activity, TAP_DP)
            settings.setIcon(R.drawable.ic_settings, UI.FG, "Settings and diagnostics")
            settings.setOnClickListener { activity.openDiagnostics() }
            settings.setOnLongClickListener { activity.openExportSettings(); true }
            bar.addView(settings)
        }

        val more = IconBtn(activity)
        more.layoutParams = IconBtn.sized(activity, TAP_DP)
        more.setIcon(R.drawable.ic_more, UI.FG, "Studio menu")
        more.setOnClickListener { activity.openRootWheelFrom(more) }
        more.setOnLongClickListener { activity.openDiagnostics(); true }
        activity.studioBtn = more
        bar.addView(more)
    }

    // =====================================================================
    // bodies
    // =====================================================================

    /**
     * rail | canvas | panel. Widths come from [ChromeBudget]: the canvas gets
     * the width its contain-fit needs for the body height; the rail and the
     * panel are shown by default only when they fit beside it, and a user
     * override shrinks the panel to its minimum / collapses the rail before
     * the canvas drops under 45 % of the width. Both side pieces collapse
     * into slim edge handles over the canvas cell so they can be re-opened.
     */
    private fun buildLandscapeBody(activity: EditorActivity, column: LinearLayout) {
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        column.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val b = landscapeBudget(activity)

        val rail = buildRail(activity, vertical = true)
        row.addView(rail, LinearLayout.LayoutParams(UI.dp(activity, RAIL_DP), ViewGroup.LayoutParams.MATCH_PARENT))
        activity.toolRail = rail
        rail.visibility = if (b.railShown) View.VISIBLE else View.GONE

        val cell = buildCanvasCell(activity)
        row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        val panel = buildContextPanel(activity)
        activity.contextPanel = panel
        row.addView(panel, LinearLayout.LayoutParams(
            UI.dp(activity, if (b.panelDp > 0) b.panelDp else ChromeBudget.panelMinDp(isTablet(activity.chromeTier))),
            ViewGroup.LayoutParams.MATCH_PARENT))
        panel.visibility = if (b.panelDp > 0) View.VISIBLE else View.GONE

        // edge handles: cost no layout space, only visible while their piece is collapsed
        cell.addView(edgeHandle(activity, left = true), FrameLayout.LayoutParams(
            UI.dp(activity, EDGE_DP), UI.dp(activity, 72), Gravity.START or Gravity.CENTER_VERTICAL))
        cell.addView(edgeHandle(activity, left = false), FrameLayout.LayoutParams(
            UI.dp(activity, EDGE_DP), UI.dp(activity, 72), Gravity.END or Gravity.CENTER_VERTICAL))
        activity.railEdgeHandle?.visibility = if (b.railShown) View.GONE else View.VISIBLE
        activity.panelEdgeHandle?.visibility = if (b.panelDp > 0) View.GONE else View.VISIBLE
        // the stage fits the picture beside a visible handle, never under it
        activity.stage.setViewportInsets(
            if (b.railShown) 0 else UI.dp(activity, EDGE_DP), 0,
            if (b.panelDp > 0) 0 else UI.dp(activity, EDGE_DP), 0)
    }

    /**
     * canvas / panel / tool row. The panel BODY height comes from
     * [ChromeBudget]; the 40dp tab strip is always present (it is the re-open
     * affordance), so a collapsed panel is exactly the tab strip and the
     * canvas takes everything else.
     */
    private fun buildPortraitBody(activity: EditorActivity, column: LinearLayout) {
        val cell = buildCanvasCell(activity)
        column.addView(cell, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val b = portraitBudget(activity)
        val panel = buildContextPanel(activity)
        activity.contextPanel = panel
        activity.portraitPanelHeightPx = UI.dp(activity, TABS_DP + 1 + b.openBodyDp)
        column.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            if (b.panelBodyDp > 0) activity.portraitPanelHeightPx else ViewGroup.LayoutParams.WRAP_CONTENT))
        activity.panelBody.visibility = if (b.panelBodyDp > 0) View.VISIBLE else View.GONE
        activity.sourcesPanel?.setCompact(b.panelBodyDp in 1 until ChromeBudget.PORTRAIT_COMPACT_BELOW_DP)

        val toolRow = buildRail(activity, vertical = false)
        column.addView(toolRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(activity, TOOLROW_DP)))
        activity.toolRail = toolRow
    }

    // =====================================================================
    // canvas cell
    // =====================================================================

    private fun buildCanvasCell(activity: EditorActivity): FrameLayout {
        val cell = FrameLayout(activity)
        cell.setBackgroundColor(CANVAS_BG)
        cell.clipChildren = true
        activity.stage = StageView(activity)
        activity.stage.host = activity
        val stage = activity.stage
        cell.addView(stage, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // The cell is the only thing that bounds the stage now — nothing
        // overlaps it — so the stage's own insets are zero.
        stage.setViewportInsets(0, 0, 0, 0)
        activity.canvasCell = cell

        // empty-project prompt, centred over the canvas, tap-through disabled
        val empty = LinearLayout(activity)
        empty.orientation = LinearLayout.VERTICAL
        empty.gravity = Gravity.CENTER
        empty.isClickable = true
        empty.setPadding(UI.dp(activity, 20), UI.dp(activity, 16), UI.dp(activity, 20), UI.dp(activity, 16))
        empty.background = Ic.pill(activity, Color.argb(215, 18, 20, 27), 16f, HAIRLINE)
        val t = TextView(activity)
        t.text = "What is the background?"
        t.setTextColor(UI.FG)
        t.textSize = 15f
        t.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        t.gravity = Gravity.CENTER
        empty.addView(t)
        val s = TextView(activity)
        s.text = "The first source fills the canvas. Everything after it is a picture-in-picture."
        s.setTextColor(UI.FG2)
        s.textSize = 11.5f
        s.gravity = Gravity.CENTER
        s.maxWidth = UI.dp(activity, 400)
        s.setPadding(0, UI.dp(activity, 4), 0, UI.dp(activity, 12))
        empty.addView(s)
        val choices = LinearLayout(activity)
        choices.orientation = LinearLayout.HORIZONTAL
        choices.gravity = Gravity.CENTER
        fun choice(icon: Int, label: String, fn: () -> Unit) {
            val b = LinearLayout(activity)
            b.orientation = LinearLayout.VERTICAL
            b.gravity = Gravity.CENTER
            b.isClickable = true; b.isFocusable = true
            b.contentDescription = label
            b.setPadding(UI.dp(activity, 6), UI.dp(activity, 6), UI.dp(activity, 6), UI.dp(activity, 6))
            b.background = Ic.pill(activity, Color.argb(36, 255, 255, 255), 12f, HAIRLINE)
            val iv = ImageView(activity)
            iv.setImageDrawable(Ic.get(activity, icon, UI.ACCENT2))
            b.addView(iv, LinearLayout.LayoutParams(UI.dp(activity, 24), UI.dp(activity, 24)))
            val tv = TextView(activity)
            tv.text = label; tv.textSize = 10.5f; tv.setTextColor(UI.FG); tv.maxLines = 1
            b.addView(tv)
            b.setOnClickListener { fn() }
            // equal shares of the card's width: 4 × ~70dp on a 360dp phone, wider on tablets
            choices.addView(b, LinearLayout.LayoutParams(0, UI.dp(activity, 60), 1f)
                .apply { setMargins(UI.dp(activity, 3), 0, UI.dp(activity, 3), 0) })
        }
        choice(R.drawable.ic_camera, "Camera") { activity.addLiveCamera() }
        choice(R.drawable.ic_video, "Video") { activity.pickMedia(true) }
        choice(R.drawable.ic_screen, "Screen") { activity.startScreenCaptureFromUi() }
        choice(R.drawable.ic_image, "Image") { activity.pickMedia(false) }
        empty.addView(choices, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        activity.emptyOverlay = empty
        // WRAP_CONTENT inside the cell: the card is as wide as its (wrapping)
        // subtitle allows, never wider than the cell minus 16dp margins
        cell.addView(empty, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            .apply { setMargins(UI.dp(activity, 16), UI.dp(activity, 16), UI.dp(activity, 16), UI.dp(activity, 16)) })
        empty.visibility = View.GONE

        // stats HUD — opt-in diagnostics (Project ring → Stats overlay), tiny,
        // never interactive, bottom-start corner
        val hud = TextView(activity)
        hud.textSize = 9.5f
        hud.typeface = Typeface.MONOSPACE
        hud.setTextColor(Color.argb(200, 235, 238, 245))
        hud.setPadding(UI.dp(activity, 8), UI.dp(activity, 4), UI.dp(activity, 8), UI.dp(activity, 4))
        hud.background = Ic.pill(activity, Color.argb(150, 0, 0, 0), 6f, Color.TRANSPARENT)
        hud.visibility = View.GONE
        activity.statsHud = hud
        cell.addView(hud, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
            .apply { setMargins(UI.dp(activity, 8), 0, 0, UI.dp(activity, 8)) })

        // full-canvas exit — top-right, only visible in Full Canvas mode
        val exit = IconBtn(activity)
        exit.setIcon(R.drawable.ic_close, UI.FG, "Exit full canvas")
        exit.background = Ic.pill(activity, Color.argb(200, 18, 20, 27), 22f, HAIRLINE)
        exit.visibility = View.GONE
        exit.setOnClickListener { activity.exitFullCanvas() }
        activity.fullExitBtn = exit
        cell.addView(exit, FrameLayout.LayoutParams(UI.dp(activity, TAP_DP), UI.dp(activity, TAP_DP),
            Gravity.TOP or Gravity.END).apply { setMargins(0, UI.dp(activity, 8), UI.dp(activity, 8), 0) })

        return cell
    }

    /** Slim tab on a canvas edge that re-opens the collapsed rail (left) or panel (right). */
    private fun edgeHandle(activity: EditorActivity, left: Boolean): View {
        val h = TextView(activity)
        h.text = if (left) "›" else "‹"
        h.textSize = 18f
        h.gravity = Gravity.CENTER
        h.setTextColor(UI.FG)
        h.contentDescription = if (left) "Show tools" else "Open panel"
        h.background = Ic.pill(activity, Color.argb(190, 26, 29, 38), 10f, HAIRLINE)
        h.setOnClickListener { if (left) activity.setRailOpen(true) else activity.setPanelOpen(true) }
        if (left) activity.railEdgeHandle = h else activity.panelEdgeHandle = h
        return h
    }

    // =====================================================================
    // tool rail (vertical in landscape, a row in portrait)
    // =====================================================================

    /**
     * Tool rail (landscape, vertical) / tool row (portrait, horizontal).
     *
     * Phones do not get the full seven-tool rail: a 360dp-wide row cannot hold
     * seven 48dp targets plus the view toggles, and a phone-landscape rail has
     * ~250dp of height. Instead of a scrolling strip that hides Undo/Redo off
     * the end, the phone tier shows Add (the Add ring: camera · video · image ·
     * screen · text — the same five verbs, one tap deeper) + Undo · Redo + the
     * view toggles: 274dp in a portrait row, 226dp in a landscape rail, so
     * nothing scrolls on a 360dp phone. Tablets get every tool.
     */
    private fun buildRail(activity: EditorActivity, vertical: Boolean): View {
        val items = LinearLayout(activity)
        items.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        items.gravity = Gravity.CENTER
        items.setBackgroundColor(BAR_BG)
        items.setPadding(UI.dp(activity, 4), UI.dp(activity, 4), UI.dp(activity, 4), UI.dp(activity, 4))
        val tablet = isTablet(activity.chromeTier)

        fun tool(icon: Int, label: String, fn: () -> Unit): IconBtn {
            val b = IconBtn(activity)
            b.setIcon(icon, UI.FG, label)
            b.layoutParams = LinearLayout.LayoutParams(UI.dp(activity, TAP_DP), UI.dp(activity, TAP_DP))
                .apply { setMargins(UI.dp(activity, 2), UI.dp(activity, 2), UI.dp(activity, 2), UI.dp(activity, 2)) }
            b.setOnClickListener { fn() }
            items.addView(b)
            return b
        }
        fun gap() {
            // a hairline separates creation · history · view toggles
            val g = View(activity)
            g.setBackgroundColor(HAIRLINE)
            items.addView(g, if (vertical) LinearLayout.LayoutParams(UI.dp(activity, 24), 1).apply { setMargins(0, UI.dp(activity, 6), 0, UI.dp(activity, 6)) }
                             else LinearLayout.LayoutParams(1, UI.dp(activity, 24)).apply { setMargins(UI.dp(activity, 6), 0, UI.dp(activity, 6), 0) })
        }
        tool(R.drawable.ic_add, "Add source") { activity.openAddChooser() }
        if (tablet) {
            tool(R.drawable.ic_camera, "Live camera") { activity.addLiveCamera() }
            tool(R.drawable.ic_video, "Video file") { activity.pickMedia(true) }
            tool(R.drawable.ic_image, "Image") { activity.pickMedia(false) }
            tool(R.drawable.ic_text, "Text") { activity.addText() }
        }
        gap()
        activity.undoBtn = tool(R.drawable.ic_undo, "Undo") { activity.doUndo() }
        activity.redoBtn = tool(R.drawable.ic_redo, "Redo") { activity.doRedo() }
        if (!vertical || !tablet) gap()
        if (!vertical) {
            // portrait has no top-bar room for the panel toggle; put it here
            tool(R.drawable.ic_panel, "Show or hide panel") { activity.setPanelOpen(!activity.isPanelShown()) }
        }
        if (activity.chromeTier == Tier.PHONE_PORTRAIT) {
            // the one tier whose transport has no room for the timeline toggle
            val tl = timelineToggle(activity)
            // IconBtn.sized() yields FrameLayout params — give the row its own
            items.addView(tl, LinearLayout.LayoutParams(UI.dp(activity, TAP_DP), UI.dp(activity, TAP_DP))
                .apply { setMargins(UI.dp(activity, 2), UI.dp(activity, 2), UI.dp(activity, 2), UI.dp(activity, 2)) })
        }
        if (vertical && !tablet) {
            // phone landscape: the rail can be tucked away to widen the canvas
            tool(R.drawable.ic_back, "Hide tools") { activity.setRailOpen(false) }
        }

        // scroll when the screen is shorter/narrower than the tools
        return if (vertical) {
            ScrollView(activity).apply {
                isVerticalScrollBarEnabled = false
                isFillViewport = true
                setBackgroundColor(BAR_BG)
                addView(items, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        } else {
            HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                isFillViewport = true
                setBackgroundColor(BAR_BG)
                addView(items, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        }
    }

    // =====================================================================
    // context panel: tabs + exactly one visible body
    // =====================================================================

    private fun buildContextPanel(activity: EditorActivity): LinearLayout {
        val panel = LinearLayout(activity)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(PANEL_BG)

        // hairline against the canvas
        val edge = View(activity)
        edge.setBackgroundColor(HAIRLINE)
        panel.addView(edge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))

        val tabs = LinearLayout(activity)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabs.gravity = Gravity.CENTER_VERTICAL
        tabs.setBackgroundColor(BAR_BG)
        tabs.setPadding(UI.dp(activity, 4), 0, UI.dp(activity, 2), 0)
        panel.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(activity, TABS_DP)))
        activity.tabBar = tabs

        val body = FrameLayout(activity)
        panel.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        activity.panelBody = body

        val sourcesPanel = SourcesPanel(activity)
        val mixerPanel = MixerPanel(activity)
        val propertiesPanel = PropertiesPanel(activity)
        val effectsPanel = EffectsPanel(activity)
        activity.sourcesPanel = sourcesPanel
        activity.mixerPanel = mixerPanel
        activity.propertiesPanel = propertiesPanel
        activity.effectsPanel = effectsPanel
        val full = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        body.addView(sourcesPanel, full)
        body.addView(mixerPanel, FrameLayout.LayoutParams(full))
        body.addView(propertiesPanel, FrameLayout.LayoutParams(full))
        body.addView(effectsPanel, FrameLayout.LayoutParams(full))

        // the SourceDock (OBS mini-mixer rows: eye · mute · name/status · badges ·
        // drag handle) IS the sources list — SourcesPanel hosts its container
        activity.dockContainer = sourcesPanel.dockContainer

        activity.tabViews.clear()
        /**
         * One tab-strip item. Tabs switch the visible body; the "X" item
         * (an icon, not a letter) collapses the whole panel so the canvas
         * reclaims the space. Every item spans the full 40dp strip height
         * (the close button is 44dp wide), so the targets stay touch-friendly.
         */
        fun createTab(label: String, id: String) {
            if (label == "X") {
                // portrait: the strip stays when the body collapses, so the same
                // button re-opens it (down/up chevron); landscape: the whole panel
                // goes and the edge handle brings it back, so this is a plain ✕
                val close = IconBtn(activity)
                close.layoutParams = LinearLayout.LayoutParams(UI.dp(activity, TAP_DP), UI.dp(activity, TABS_DP))
                close.setIcon(R.drawable.ic_close, UI.FG2, "Collapse panel")
                close.setOnClickListener { activity.setPanelOpen(!activity.isPanelShown()) }
                activity.panelCloseBtn = close
                tabs.addView(close)
                return
            }
            val t = TextView(activity)
            t.text = label
            t.textSize = 11.5f
            t.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            t.gravity = Gravity.CENTER
            t.includeFontPadding = false
            t.maxLines = 1
            // four labels share a 240dp panel (≈47dp each): shrink before clipping
            t.setAutoSizeTextTypeUniformWithConfiguration(9, 12, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            t.setPadding(UI.dp(activity, 2), 0, UI.dp(activity, 2), 0)
            t.setTextColor(UI.FG2)
            t.contentDescription = "$label tab"
            // the pill is drawn 32dp tall (4dp inset) but the touch target is the full 40dp strip
            t.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                .apply { setMargins(UI.dp(activity, 2), 0, UI.dp(activity, 2), 0) }
            styleTab(activity, t, false)
            t.setOnClickListener { activity.showTab(id) }
            tabs.addView(t)
            activity.tabViews[id] = t
        }
        createTab("Sources", "sources")
        createTab("Mixer", "mixer")
        createTab("Props", "props")
        createTab("Effects", "effects")
        createTab("X", "close")
        return panel
    }

    /**
     * Wire the four panel bodies to the editor's verbs. Nothing here is a
     * stub: every callback reaches SourceController, the engine or an
     * editor action that already existed for the wheel.
     */
    private fun bindPanels(activity: EditorActivity, sourcesPanel: SourcesPanel,
                           mixerPanel: MixerPanel, propertiesPanel: PropertiesPanel,
                           effectsPanel: EffectsPanel) {
        sourcesPanel.listener = object : SourcesPanel.Listener {
            override fun onSelect(id: String) { activity.select(id) }
            override fun onToggleVisible(id: String) { activity.ctrl.toggleVisible(id) }
            override fun onAdd() { activity.openAddChooser() }
            override fun onAddCamera() { activity.addLiveCamera() }
            override fun onAddVideo() { activity.pickMedia(true) }
            override fun onAddImage() { activity.pickMedia(false) }
            override fun onAddScreen() { activity.startScreenCaptureFromUi() }
            override fun onAddText() { activity.addText() }
            override fun onRemove() { activity.removeSelectedSource() }
            override fun onHide() { activity.selectedId?.let { activity.ctrl.toggleVisible(it) } }
            override fun onMoveUp(id: String) { activity.ctrl.moveZ(id, "up") }
            override fun onMoveDown(id: String) { activity.ctrl.moveZ(id, "down") }
            override fun onProperties() {
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
            override fun onMonitorToggle() { activity.toggleMonitorMute() }
        }
        // the Props body IS the advanced sheet; the activity fills it per source
        propertiesPanel.onFill = { l -> activity.fillAdvanced(l) }
        effectsPanel.onOpenProps = { activity.showTab("props") }
        effectsPanel.onCanvasColor = { activity.openCanvasColourRing() }
    }

    // =====================================================================
    // live-camera row (a real row; GONE unless a live camera is on the canvas)
    // =====================================================================

    private fun buildCameraRow(activity: EditorActivity, column: LinearLayout) {
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setBackgroundColor(BAR_BG)
        row.setPadding(UI.dp(activity, 10), 0, UI.dp(activity, 6), 0)
        row.visibility = View.GONE
        activity.cameraStrip = row
        column.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(activity, ChromeBudget.CAMERA_ROW_DP)))
    }

    // =====================================================================
    // timeline (collapsible; WRAP_CONTENT → 0dp when empty or collapsed)
    // =====================================================================

    private fun buildTimeline(activity: EditorActivity, column: LinearLayout) {
        val tl = TimelineView(activity)
        tl.listener = object : TimelineView.Listener {
            override fun onScrubStart() { activity.scrubbing = true }
            override fun onScrub(ms: Long) {
                activity.timeLabel.text = UI.fmtTime(ms)
                if (activity.engineReady()) activity.engine.seekTo(ms)
                if (activity.transportReadyForUi()) activity.seek.progress = ms.toInt().coerceAtMost(activity.seek.max)
            }
            override fun onScrubEnd() {
                activity.scrubbing = false
                if (activity.engineReady()) activity.engine.refreshFrames()
            }
        }
        activity.timeline = tl
        column.addView(tl, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // phone landscape: collapsed by default (height is the scarce axis)
        tl.visibility = if (activity.timelineOpen ?: (activity.chromeTier != Tier.PHONE_LANDSCAPE)) View.VISIBLE else View.GONE
    }

    // =====================================================================
    // transport
    // =====================================================================

    private fun buildTransport(activity: EditorActivity, column: LinearLayout, heightDp: Int) {
        val bar = LinearLayout(activity)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(BAR_BG)
        bar.setPadding(UI.dp(activity, 4), 0, UI.dp(activity, 8), 0)
        column.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(activity, heightDp)))
        activity.transportBar = bar

        val playBtn = IconBtn(activity)
        playBtn.layoutParams = IconBtn.sized(activity, TAP_DP)
        playBtn.setIcon(R.drawable.ic_play, Color.WHITE, "Play")
        playBtn.setOnClickListener { activity.togglePlay() }
        activity.playBtn = playBtn
        bar.addView(playBtn)

        val stopBtn = IconBtn(activity)
        stopBtn.layoutParams = IconBtn.sized(activity, TAP_DP)
        stopBtn.setIcon(R.drawable.ic_stop, UI.FG, "Stop")
        stopBtn.setOnClickListener { activity.controlsStopTap() }
        bar.addView(stopBtn)

        val timeLabel = TextView(activity)
        timeLabel.text = "0:00"
        timeLabel.setTextColor(UI.FG)
        timeLabel.textSize = 12f
        timeLabel.typeface = Typeface.MONOSPACE
        timeLabel.includeFontPadding = false
        timeLabel.setPadding(UI.dp(activity, 6), 0, 0, 0)
        activity.timeLabel = timeLabel
        bar.addView(timeLabel)

        val seek = SeekBar(activity)
        seek.progressTintList = ColorStateList.valueOf(UI.ACCENT)
        seek.thumbTintList = ColorStateList.valueOf(UI.ACCENT2)
        seek.max = activity.proj?.durationMs()?.toInt()?.coerceAtLeast(1) ?: 1
        seek.contentDescription = "Timeline"
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
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
        activity.seek = seek
        bar.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { setMargins(UI.dp(activity, 2), 0, UI.dp(activity, 2), 0) })

        val durationLabel = TextView(activity)
        durationLabel.text = "0:00"
        durationLabel.setTextColor(UI.FG2)
        durationLabel.textSize = 12f
        durationLabel.typeface = Typeface.MONOSPACE
        durationLabel.includeFontPadding = false
        durationLabel.setPadding(0, 0, UI.dp(activity, 8), 0)
        activity.durationLabel = durationLabel
        bar.addView(durationLabel)

        // timeline show/hide lives here unless the bar is a 360dp phone-portrait
        // one (play · stop · time · seek · duration · REC already leave the seek
        // ~110dp there); phone portrait puts it in the tool row instead
        if (activity.chromeTier != Tier.PHONE_PORTRAIT) bar.addView(timelineToggle(activity))

        // record: one pill whose label is the state (updateRecordButton owns it)
        val recBtn = TextView(activity)
        activity.recordBtn = recBtn
        recBtn.text = "●  REC"
        recBtn.textSize = 12f
        recBtn.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        recBtn.gravity = Gravity.CENTER
        recBtn.includeFontPadding = false
        recBtn.maxLines = 1
        recBtn.setTextColor(Color.WHITE)
        // phone portrait: a 44dp glyph pill ("●"; "■ 0:12" while recording) —
        // see EditorActivity.updateRecordButton; elsewhere a text pill
        val glyphOnly = activity.chromeTier == Tier.PHONE_PORTRAIT
        val hp = UI.dp(activity, if (glyphOnly) 8 else 14)
        recBtn.setPadding(hp, 0, hp, 0)
        recBtn.minWidth = UI.dp(activity, TAP_DP)
        recBtn.background = Ic.pill(activity, Color.argb(240, 200, 34, 34), 18f, Color.argb(160, 255, 120, 120))
        recBtn.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UI.dp(activity, 36))
        recBtn.setOnClickListener { activity.recordButtonTap() }
        bar.addView(recBtn)
    }

    private fun timelineToggle(activity: EditorActivity): IconBtn {
        val tlBtn = IconBtn(activity)
        tlBtn.layoutParams = IconBtn.sized(activity, TAP_DP)
        tlBtn.setIcon(R.drawable.ic_timeline, UI.FG2, "Show or hide timeline")
        tlBtn.setOnClickListener { activity.setTimelineOpen(!(activity.timelineOpen ?: (activity.chromeTier != Tier.PHONE_LANDSCAPE))) }
        activity.timelineBtn = tlBtn
        return tlBtn
    }

    // =====================================================================
    // overlays on the root frame
    // =====================================================================

    private fun buildOverlays(activity: EditorActivity, root: FrameLayout) {
        // radial wheel: full-screen, GONE until shown; it dims everything and
        // blooms around the finger / the ⋯ button
        val wheel = RadialMenuView(activity)
        wheel.onDismiss = { activity.onWheelDismissed() }
        activity.wheel = wheel
        root.addView(wheel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        activity.buildSnackBarInto(root)
        activity.buildProgOverlayInto(root)
    }

    // =====================================================================
    // shared widgets
    // =====================================================================

    /**
     * Compact text pill. Replaces every raw [android.widget.Button] the studio
     * used to have: raw Buttons carry a 48dp minimum height, all-caps and
     * heavy internal padding, so in a 44–52dp bar they clipped and sat on a
     * different baseline than the icon buttons next to them.
     */
    fun pillBtn(activity: EditorActivity, text: String, textColor: Int, fill: Int,
                heightDp: Int = 32, onClick: () -> Unit): TextView {
        val t = TextView(activity)
        t.text = text
        t.textSize = 12f
        t.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        t.isAllCaps = false
        t.gravity = Gravity.CENTER
        t.setTextColor(textColor)
        t.includeFontPadding = false
        t.maxLines = 1
        t.setPadding(UI.dp(activity, 12), 0, UI.dp(activity, 12), 0)
        val g = GradientDrawable()
        g.cornerRadius = UI.dpf(activity, heightDp / 2f)
        g.setColor(fill)
        g.setStroke(UI.dp(activity, 1), HAIRLINE)
        t.background = g
        t.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UI.dp(activity, heightDp))
            .apply { setMargins(UI.dp(activity, 3), 0, UI.dp(activity, 3), 0) }
        t.setOnClickListener { onClick() }
        return t
    }

    /** Tab strip highlight; called by EditorActivity.showTab. */
    fun styleTab(activity: EditorActivity, v: View, active: Boolean) {
        val t = v as? TextView ?: return
        t.setTextColor(if (active) UI.ACCENT2 else UI.FG2)
        val pill = Ic.pill(activity, if (active) TAB_ACTIVE_BG else Color.TRANSPARENT, 8f,
            if (active) Color.argb(110, 255, 90, 44) else Color.TRANSPARENT)
        val inset = UI.dp(activity, 4)
        t.background = android.graphics.drawable.InsetDrawable(pill, 0, inset, 0, inset)
    }
}
