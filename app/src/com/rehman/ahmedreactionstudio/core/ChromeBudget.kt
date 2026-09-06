package com.rehman.ahmedreactionstudio.core

/**
 * Pure dp arithmetic that decides how much room the studio chrome may take
 * around the canvas. No Android types, so it runs under the JVM test in
 * tools/chrome-budget-test exactly as it runs on the device.
 *
 * Rule, in priority order:
 *  1. CANVAS FIRST — the canvas wants the size at which its contain-fit fills
 *     the flexible body (landscape: full body height → width = h·aspect;
 *     portrait: full width → height = w/aspect). The rail and the context
 *     panel are opened *by default* only when they fit in the space the
 *     canvas does not need.
 *  2. USER WINS — an explicit open/close (a tab tap, ✕, the edge handles)
 *     overrides the default. Then the panel takes its minimum, the canvas
 *     shrinks — but never below [MIN_CANVAS_SHARE] of the axis — and the
 *     rail is the first thing to give way (it collapses into its edge handle).
 *  3. TABLETS keep rail + canvas + panel together: the panel is open by
 *     default and the rail is always shown.
 *
 * Everything the injector sizes in dp comes from here, so the layout has one
 * source of truth and no percent weights between chrome and canvas.
 */
object ChromeBudget {
    const val TOP_DP = 48
    const val TRANSPORT_DP = 52
    /** phone landscape: both bars drop to the touch-target minimum */
    const val COMPACT_BAR_DP = 44
    const val RAIL_DP = 56
    const val TABS_DP = 40
    const val TOOLROW_DP = 48
    /** live-camera row (Flash · Switch · Mirror · Light · Take), present only with a live camera */
    const val CAMERA_ROW_DP = 44

    fun topDp(compact: Boolean) = if (compact) COMPACT_BAR_DP else TOP_DP
    fun transportDp(compact: Boolean) = if (compact) COMPACT_BAR_DP else TRANSPORT_DP

    /** The canvas keeps at least this share of the flexible axis, whatever is open. */
    const val MIN_CANVAS_SHARE = 0.45f

    data class Landscape(
        /** rail shown (56dp) — false = collapsed into the left edge handle */
        val railShown: Boolean,
        /** context panel width in dp, 0 = collapsed into the right edge handle */
        val panelDp: Int,
        /** what the canvas cell gets, dp */
        val canvasDp: Int,
        /** the defaults the budget would pick with no user override */
        val railDefault: Boolean,
        val panelDefault: Boolean,
    )

    data class Portrait(
        /** height of the panel BODY below the tab strip, 0 = collapsed (tabs only) */
        val panelBodyDp: Int,
        /** the canvas cell height, dp */
        val canvasDp: Int,
        val panelDefault: Boolean,
        /** height the body would get when the user opens the panel (for animations / toggles) */
        val openBodyDp: Int,
    )

    fun panelMinDp(tablet: Boolean) = if (tablet) 280 else 240
    fun panelMaxDp(tablet: Boolean) = if (tablet) 360 else 300
    fun portraitPanelMinDp(tablet: Boolean) = if (tablet) 260 else 200
    fun portraitPanelMaxDp(tablet: Boolean) = if (tablet) 420 else 360
    /**
     * Portrait: the panel opens by default whenever the canvas leaves at least
     * this much under itself. Between this and [portraitPanelMinDp] the panel
     * takes exactly the spare (the picture keeps its full-width size): black
     * bars around a letterboxed canvas plus an empty tab strip are worse than
     * a short panel (header + 1.5–2.5 source rows, and the list scrolls). The
     * panels drop their secondary rows under [PORTRAIT_COMPACT_BELOW_DP].
     */
    const val PORTRAIT_SPARE_MIN_DP = 120
    /** panel bodies shorter than this hide their secondary (duplicated-elsewhere) rows */
    const val PORTRAIT_COMPACT_BELOW_DP = 200

    /**
     * @param usableW usable width in dp (window minus system bars)
     * @param usableH usable height in dp
     * @param aspectW/aspectH canvas aspect (16:9, 9:16, 1:1)
     * @param panelOpen user override (null = default)
     * @param railOpen user override (null = default)
     */
    fun landscape(
        usableW: Int, usableH: Int, aspectW: Int, aspectH: Int,
        tablet: Boolean, panelOpen: Boolean?, railOpen: Boolean?,
        extraRowsDp: Int = 0,
    ): Landscape {
        val compact = !tablet
        val bodyH = (usableH - topDp(compact) - transportDp(compact) - extraRowsDp).coerceAtLeast(96)
        val canvasWant = (bodyH.toLong() * aspectW / aspectH).toInt().coerceAtLeast(96)
        val pMin = panelMinDp(tablet)
        val pMax = panelMaxDp(tablet)
        val pPref = (usableW * (if (tablet) 0.26f else 0.34f)).toInt().coerceIn(pMin, pMax)
        val spare = usableW - canvasWant

        val railDefault = tablet || spare >= RAIL_DP
        val panelDefault = tablet || spare - (if (railDefault) RAIL_DP else 0) >= pMin

        var rail = railOpen ?: railDefault
        val panel = panelOpen ?: panelDefault
        val floor = (usableW * MIN_CANVAS_SHARE).toInt()

        var panelDp = 0
        if (panel) {
            // as wide as it likes while the canvas still reaches its want …
            val freeForPanel = usableW - (if (rail) RAIL_DP else 0) - canvasWant
            panelDp = when {
                freeForPanel >= pPref -> pPref
                freeForPanel >= pMin -> freeForPanel
                else -> pMin                    // … otherwise its minimum and the canvas shrinks
            }
            // canvas floor: shrink the panel to its minimum first, then drop the rail
            if (usableW - (if (rail) RAIL_DP else 0) - panelDp < floor) panelDp = pMin
            if (rail && !tablet && railOpen != true && usableW - RAIL_DP - panelDp < floor) rail = false
            if (usableW - (if (rail) RAIL_DP else 0) - panelDp < floor) {
                // the screen is simply too narrow for a panel of pMin next to a canvas of
                // 45 %: give the panel what is left above 160dp so both remain usable
                panelDp = (usableW - (if (rail) RAIL_DP else 0) - floor).coerceAtLeast(160)
            }
        }
        val canvasDp = (usableW - (if (rail) RAIL_DP else 0) - panelDp).coerceAtLeast(96)
        return Landscape(rail, panelDp, canvasDp, railDefault, panelDefault)
    }

    fun portrait(
        usableW: Int, usableH: Int, aspectW: Int, aspectH: Int,
        tablet: Boolean, panelOpen: Boolean?,
        extraRowsDp: Int = 0,
    ): Portrait {
        // the tab strip is always there (it is the re-open affordance)
        val bodyH = (usableH - TOP_DP - TABS_DP - TOOLROW_DP - TRANSPORT_DP - extraRowsDp).coerceAtLeast(96)
        val canvasWant = (usableW.toLong() * aspectH / aspectW).toInt().coerceAtLeast(96)
        val pMin = portraitPanelMinDp(tablet)
        val pMax = portraitPanelMaxDp(tablet)
        val free = bodyH - canvasWant
        // tablets keep canvas + context together in portrait too
        val panelDefault = tablet || free >= PORTRAIT_SPARE_MIN_DP
        val canvasMin = maxOf((bodyH * MIN_CANVAS_SHARE).toInt(), 96)
        // what an open panel gets: the free height if generous; exactly the
        // spare when that is short but still usable (the picture keeps its
        // full-width size — no black bars); else its minimum, and never so much
        // that the canvas drops under its floor — on a screen too short for
        // both, the panel is what shrinks (canvas first)
        var openBody = when {
            free >= pMin -> free.coerceAtMost(pMax)
            free >= PORTRAIT_SPARE_MIN_DP -> free
            else -> pMin
        }
        if (bodyH - openBody < canvasMin) openBody = (bodyH - canvasMin).coerceAtLeast(0)
        val open = panelOpen ?: panelDefault
        val body = if (open) openBody else 0
        return Portrait(body, bodyH - body, panelDefault, openBody)
    }

    // ------------------------------------------------------------------
    // tablet landscape: Sources pinned above the context tabs
    // ------------------------------------------------------------------

    /**
     * Usable height from which a tablet in landscape shows the Sources list
     * AND the active context tab at the same time (OBS-style docks stacked
     * in the right panel): 400dp of body after the bars, even with every
     * optional row (timeline 90 + camera row 44) present, so the decision
     * never flips when a row appears. Below it the panel is tabbed.
     */
    const val PIN_SOURCES_MIN_USABLE_H_DP = 400 + TOP_DP + TRANSPORT_DP + 90 + CAMERA_ROW_DP
    const val SOURCES_PANE_MIN_DP = 200
    const val SOURCES_PANE_MAX_DP = 340
    /** the context body under the pinned Sources keeps at least this much */
    const val CONTEXT_UNDER_SOURCES_MIN_DP = 160

    fun sourcesPinned(usableH: Int, tablet: Boolean, landscape: Boolean): Boolean =
        tablet && landscape && usableH >= PIN_SOURCES_MIN_USABLE_H_DP

    /**
     * Height of the pinned Sources pane for a body of [bodyH] dp (the body
     * after the bars and the optional rows actually shown): 42 % of it,
     * clamped, and never so much that the context body below the 40dp tab
     * strip falls under [CONTEXT_UNDER_SOURCES_MIN_DP].
     */
    fun sourcesPaneDp(bodyH: Int): Int {
        val want = (bodyH * 0.42f).toInt().coerceIn(SOURCES_PANE_MIN_DP, SOURCES_PANE_MAX_DP)
        return want.coerceAtMost((bodyH - TABS_DP - CONTEXT_UNDER_SOURCES_MIN_DP).coerceAtLeast(SOURCES_PANE_MIN_DP))
    }

    /** Below this body height the optional rows (timeline, camera row) give way. */
    const val MIN_BODY_WITH_EXTRAS_DP = 260
    /** landscape: the body is height-bound already and the bars are compact */
    const val MIN_BODY_WITH_EXTRAS_LAND_DP = 200

    /**
     * Whether the optional rows may be shown at all: they are the lowest
     * priority, so they are dropped (not the canvas, not the panel) when they
     * would leave the flexible body under [MIN_BODY_WITH_EXTRAS_DP]. Decided
     * from the fixed bars only, so it cannot oscillate with its own result.
     */
    fun extrasFit(usableH: Int, landscape: Boolean, tablet: Boolean, extrasDp: Int): Boolean {
        if (extrasDp <= 0) return true
        val fixed = if (landscape) topDp(!tablet) + transportDp(!tablet)
                    else TOP_DP + TABS_DP + TOOLROW_DP + TRANSPORT_DP
        val floor = if (landscape) MIN_BODY_WITH_EXTRAS_LAND_DP else MIN_BODY_WITH_EXTRAS_DP
        return usableH - fixed - extrasDp >= floor
    }
}
