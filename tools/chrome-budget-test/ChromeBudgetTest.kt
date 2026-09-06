package com.rehman.ahmedreactionstudio.core

/**
 * JVM test for the studio chrome budget (ChromeBudget.kt) — the rule the
 * StudioLayoutInjector uses to divide the usable window between the tool
 * rail, the canvas cell and the context panel.
 *
 * Acceptance criteria under test, for phone / small phone / foldable / tablet
 * in both orientations, for 16:9, 9:16 and 1:1, with every combination of
 * user overrides (panel open/closed, rail open/closed):
 *
 *  1. nothing ever exceeds the window: rail + canvas + panel == usable width
 *     (landscape) and canvas + panel body == flexible body (portrait);
 *  2. the canvas never collapses: it keeps >= 45 % of its axis and >= 96dp;
 *  3. an open panel is never narrower than a usable minimum (>= 160dp, and
 *     >= its tier minimum whenever the canvas floor allows it);
 *  4. canvas first: when the default layout has spare room, opening the
 *     panel by default never squeezes the canvas below the size its
 *     contain-fit wants for the body height (landscape);
 *  5. tablets get rail + canvas + panel simultaneously by default;
 *  6. phone landscape defaults to canvas first: the panel opens by default
 *     only when it fits beside a full-height 16:9 canvas;
 *  7. collapsing gives the space back to the canvas, re-opening restores it
 *     (state is a pure function of the inputs, so a rotation cannot drift).
 */
object ChromeBudgetTest {
    private var passes = 0
    private var failures = 0

    private fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) passes++ else failures++
        println((if (ok) "  ok   " else "  FAIL ") + name + (if (detail.isNotEmpty()) "  [$detail]" else ""))
    }

    /** usable dp sizes AFTER system bars, portrait orientation (w < h) */
    private data class Device(val name: String, val wDp: Int, val hDp: Int, val tablet: Boolean)

    private val devices = listOf(
        Device("small phone 360x640", 360, 640 - 24 - 48, false),
        Device("phone 393x851 (Pixel-class)", 393, 851 - 24 - 48, false),
        Device("tall phone 412x915", 412, 915 - 24 - 48, false),
        Device("short phone 360x592", 360, 592 - 24 - 48, false),
        Device("foldable inner 673x841", 673, 841 - 24 - 48, true),
        Device("tablet 800x1280", 800, 1280 - 24, true),
        Device("large tablet 1024x1366", 1024, 1366 - 24, true),
    )
    private val aspects = listOf(Triple("16:9", 1920, 1080), Triple("9:16", 1080, 1920), Triple("1:1", 1080, 1080))
    private val tri = listOf(null, true, false)

    @JvmStatic
    fun main(args: Array<String>) {
        println("default budgets (dp):")
        for (d in devices) for ((code, aw, ah) in aspects) {
            val l = ChromeBudget.landscape(d.hDp + 24, d.wDp - 24, aw, ah, d.tablet, null, null)
            val p = ChromeBudget.portrait(d.wDp, d.hDp, aw, ah, d.tablet, null)
            println("  %-30s %-5s land: rail=%-5s canvas=%-4d panel=%-3d | port: canvas=%-4d panelBody=%-3d (open=%d)".format(
                d.name, code, l.railShown, l.canvasDp, l.panelDp, p.canvasDp, p.panelBodyDp, p.openBodyDp))
        }
        for (d in devices) {
            for ((code, aw, ah) in aspects) {
                landscape(d, code, aw, ah)
                portrait(d, code, aw, ah)
            }
        }
        // extra rows (open timeline up to 90dp, live-camera row 44dp) are taken
        // from the flexible body, never from the canvas floor
        for (d in devices) for ((code, aw, ah) in aspects) for (tl in listOf(26, 42, 90, 90 + ChromeBudget.CAMERA_ROW_DP)) {
            // the activity only shows the rows when extrasFit() says so; the
            // budget must hold for whatever it then passes in
            val landFit = ChromeBudget.extrasFit(d.wDp - 24, true, d.tablet, tl)
            val lTl = if (landFit) tl else 0
            val l = ChromeBudget.landscape(d.hDp + 24, d.wDp - 24, aw, ah, d.tablet, true, true, lTl)
            val usableW = d.hDp + 24
            check("${d.name} · $code · extras=$tl: landscape widths still sum to the window",
                (if (l.railShown) ChromeBudget.RAIL_DP else 0) + l.canvasDp + l.panelDp == usableW)
            val portFit = ChromeBudget.extrasFit(d.hDp, false, d.tablet, tl)
            val pTl = if (portFit) tl else 0
            val p = ChromeBudget.portrait(d.wDp, d.hDp, aw, ah, d.tablet, true, pTl)
            val bodyH = d.hDp - ChromeBudget.TOP_DP - ChromeBudget.TABS_DP - ChromeBudget.TOOLROW_DP - ChromeBudget.TRANSPORT_DP - pTl
            check("${d.name} · $code · extras=$tl (shown=$portFit): portrait canvas + panel body == body minus extras",
                p.canvasDp + p.panelBodyDp == bodyH && p.canvasDp >= (bodyH * ChromeBudget.MIN_CANVAS_SHARE).toInt(),
                "${p.canvasDp} + ${p.panelBodyDp} vs $bodyH")
            check("${d.name} · $code · extras=$tl: rows shown only when >= 260dp of body remains",
                portFit == (bodyH + pTl - tl >= ChromeBudget.MIN_BODY_WITH_EXTRAS_DP))
            val landBody = (d.wDp - 24) - ChromeBudget.topDp(!d.tablet) - ChromeBudget.transportDp(!d.tablet)
            check("${d.name} · $code · extras=$tl: landscape rows shown only when >= 200dp of body remains",
                landFit == (landBody - tl >= ChromeBudget.MIN_BODY_WITH_EXTRAS_LAND_DP))
            // even with the rows dropped, an open panel on the shortest phone stays usable
            if (!portFit) check("${d.name} · $code · extras=$tl: without the rows the open panel is >= 120dp", p.panelBodyDp >= 120, "panel=${p.panelBodyDp}")
        }
        println("$passes passed, $failures failed")
        if (failures > 0) System.exit(1)
    }

    private fun landscape(d: Device, code: String, aw: Int, ah: Int) {
        // the portrait numbers already exclude a 24dp status bar and a 48dp nav
        // bar; rotated, the window is (hDp+24+48) x wDp with the status bar on
        // the short axis and the nav bar at the side
        val usableW = d.hDp + 24
        val usableH = d.wDp - 24
        val bodyH = usableH - ChromeBudget.topDp(!d.tablet) - ChromeBudget.transportDp(!d.tablet)
        val canvasWant = bodyH * aw / ah
        check("${d.name} · landscape: phone bars are compact (44dp), tablet bars full",
            (ChromeBudget.topDp(!d.tablet) == if (d.tablet) 48 else 44) &&
            ChromeBudget.topDp(!d.tablet) >= 44 && ChromeBudget.transportDp(!d.tablet) >= 44)
        for (panel in tri) for (rail in tri) {
            val b = ChromeBudget.landscape(usableW, usableH, aw, ah, d.tablet, panel, rail)
            val tag = "${d.name} · landscape · $code · panel=$panel rail=$rail"
            val railDp = if (b.railShown) ChromeBudget.RAIL_DP else 0
            check("$tag: rail + canvas + panel fills the width exactly",
                railDp + b.canvasDp + b.panelDp == usableW, "$railDp + ${b.canvasDp} + ${b.panelDp} vs $usableW")
            check("$tag: canvas keeps >= 45% of the width and >= 96dp",
                b.canvasDp >= (usableW * ChromeBudget.MIN_CANVAS_SHARE).toInt() && b.canvasDp >= 96, "canvas=${b.canvasDp}")
            if (b.panelDp > 0) {
                check("$tag: open panel is usable (>= 160dp)", b.panelDp >= 160, "panel=${b.panelDp}")
                check("$tag: open panel never exceeds its tier max", b.panelDp <= ChromeBudget.panelMaxDp(d.tablet), "panel=${b.panelDp}")
            }
            if (panel == true) check("$tag: explicit open → panel shown", b.panelDp > 0)
            if (panel == false) check("$tag: explicit close → panel collapsed, canvas reclaims", b.panelDp == 0 && b.canvasDp == usableW - railDp)
            if (rail == true) check("$tag: explicit rail open → rail shown", b.railShown)
            if (rail == false) check("$tag: explicit rail close → rail hidden", !b.railShown)
            if (panel == null && rail == null) {
                if (d.tablet) {
                    check("$tag: tablet default = rail + canvas + panel", b.railShown && b.panelDp > 0)
                } else {
                    val fits = usableW - canvasWant - (if (b.railDefault) ChromeBudget.RAIL_DP else 0) >= ChromeBudget.panelMinDp(false)
                    check("$tag: phone default panel only when it fits beside a full-height canvas",
                        (b.panelDp > 0) == fits, "want=$canvasWant spare=${usableW - canvasWant}")
                    if (fits || b.panelDp == 0) check("$tag: default never squeezes the canvas under its want",
                        b.canvasDp >= minOf(canvasWant, usableW - railDp), "canvas=${b.canvasDp} want=$canvasWant")
                }
            }
            // determinism: the same inputs always give the same answer (rotation cannot drift)
            val again = ChromeBudget.landscape(usableW, usableH, aw, ah, d.tablet, panel, rail)
            check("$tag: pure function of its inputs", again == b)
        }
    }

    private fun portrait(d: Device, code: String, aw: Int, ah: Int) {
        val usableW = d.wDp
        val usableH = d.hDp
        val bodyH = usableH - ChromeBudget.TOP_DP - ChromeBudget.TABS_DP - ChromeBudget.TOOLROW_DP - ChromeBudget.TRANSPORT_DP
        val canvasWant = usableW * ah / aw
        for (panel in tri) {
            val b = ChromeBudget.portrait(usableW, usableH, aw, ah, d.tablet, panel)
            val tag = "${d.name} · portrait · $code · panel=$panel"
            check("$tag: canvas + panel body fills the flexible body exactly",
                b.canvasDp + b.panelBodyDp == bodyH, "${b.canvasDp} + ${b.panelBodyDp} vs $bodyH")
            check("$tag: canvas keeps >= 45% of the body and >= 96dp",
                b.canvasDp >= (bodyH * ChromeBudget.MIN_CANVAS_SHARE).toInt() && b.canvasDp >= 96, "canvas=${b.canvasDp}")
            check("$tag: an open panel body is usable (>= 120dp)", b.openBodyDp >= 120, "open=${b.openBodyDp}")
            check("$tag: an open panel body never exceeds its tier max", b.openBodyDp <= ChromeBudget.portraitPanelMaxDp(d.tablet))
            if (panel == true) check("$tag: explicit open → body shown", b.panelBodyDp == b.openBodyDp && b.panelBodyDp > 0)
            if (panel == false) check("$tag: explicit close → tabs only, canvas takes the body", b.panelBodyDp == 0 && b.canvasDp == bodyH)
            if (panel == null) {
                if (d.tablet) check("$tag: tablet default = canvas + panel", b.panelBodyDp > 0)
                else {
                    val free = bodyH - canvasWant
                    val fits = free >= ChromeBudget.PORTRAIT_SPARE_MIN_DP
                    check("$tag: phone default panel only when a usable panel fits under a full-width canvas",
                        (b.panelBodyDp > 0) == fits, "want=$canvasWant free=$free")
                    // no black bars: while the spare is usable the picture keeps its full width
                    if (fits && free <= ChromeBudget.portraitPanelMaxDp(false))
                        check("$tag: default panel takes exactly the spare, canvas keeps its want",
                            b.canvasDp == canvasWant || b.canvasDp == maxOf((bodyH * ChromeBudget.MIN_CANVAS_SHARE).toInt(), 96),
                            "canvas=${b.canvasDp} want=$canvasWant panel=${b.panelBodyDp}")
                }
            }
            val again = ChromeBudget.portrait(usableW, usableH, aw, ah, d.tablet, panel)
            check("$tag: pure function of its inputs", again == b)
        }
    }
}
