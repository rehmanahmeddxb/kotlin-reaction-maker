package com.rehman.ahmedreactionstudio.core

import kotlin.math.abs

/**
 * JVM test for the canvas contain-fit rule used by StageView.
 *
 * Acceptance criterion under test: the ENTIRE composition (16:9, 9:16, 1:1)
 * fits inside the free viewport in portrait and landscape, centred, with
 * status/navigation bars, cutout, top bar, dock, contextual bar and an open
 * panel all subtracted — and never gets pushed or cropped when chrome grows.
 */
object ViewportFitTest {
    private var passes = 0
    private var failures = 0

    private fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) passes++ else failures++
        println((if (ok) "  ok   " else "  FAIL ") + name + (if (detail.isNotEmpty()) "  [$detail]" else ""))
    }

    private fun near(a: Float, b: Float, eps: Float = 0.6f) = abs(a - b) <= eps

    private data class Device(val name: String, val w: Float, val h: Float, val density: Float,
                              val statusPx: Int, val navPx: Int, val cutoutPx: Int)

    private val aspects = listOf(Triple("16:9", 1920, 1080), Triple("9:16", 1080, 1920), Triple("1:1", 1080, 1080))

    @JvmStatic
    fun main(args: Array<String>) {
        val pixel = Device("Pixel-class 1080x2400 @2.625", 1080f, 2400f, 2.625f, 63, 126, 84)
        val small = Device("small 720x1280 @2.0", 720f, 1280f, 2f, 48, 96, 0)
        val tablet = Device("tablet 1600x2560 @2.0", 1600f, 2560f, 2f, 48, 0, 0)

        for (d in listOf(pixel, small, tablet)) {
            for (portrait in listOf(true, false)) {
                val vw = if (portrait) d.w else d.h
                val vh = if (portrait) d.h else d.w
                val dp = { v: Float -> v * d.density }
                val pad = dp(6f); val minPx = dp(96f)
                val topBar = dp(56f).toInt()
                val dockCollapsed = dp(54f).toInt()
                val ctxBar = dp(52f).toInt()
                val transport = dp(56f).toInt()
                val launch = dp(62f).toInt()
                // Portrait: dock + ctx + transport + launcher stacked in the bottom
                // sheet. Landscape (EditorActivity.relayoutChrome): dock + ctx +
                // panel move into a RIGHT RAIL (40 % width, 220–340dp) and the
                // sheet is one row (transport | launcher).
                val sheetBase = if (portrait) dockCollapsed + ctxBar + transport + launch else launch
                val rail = if (portrait) 0 else (vw * 0.40f).toInt().coerceIn(dp(220f).toInt(), dp(340f).toInt())
                val panel = if (portrait) (vh * 0.40f).toInt().coerceAtLeast(dp(170f).toInt()) else 0
                val orient = if (portrait) "portrait" else "landscape"
                // in landscape the cutout sits on a side edge, in portrait on top
                val insetL = if (portrait) 0 else d.cutoutPx
                val insetT = if (portrait) maxOf(d.statusPx, d.cutoutPx) else d.statusPx
                val insetR = rail
                val insetB = d.navPx

                for ((label, sw, sh) in aspects) {
                    val tag = "${d.name} $orient $label"
                    // ---- 1. chrome closed: dock + ctx + transport + launch ----
                    val closed = ViewportFit.contain(vw, vh, insetL, insetT + topBar, insetR, insetB + sheetBase,
                        pad, sw, sh, minPx)
                    val availL = insetL + pad; val availT = insetT + topBar + pad
                    val availR = vw - insetR - pad; val availB = vh - insetB - sheetBase - pad
                    check("$tag: inside free viewport (closed)",
                        closed.left >= availL - 0.01f && closed.top >= availT - 0.01f &&
                            closed.right <= availR + 0.01f && closed.bottom <= availB + 0.01f,
                        "$closed avail=[$availL,$availT,$availR,$availB]")
                    check("$tag: aspect preserved (closed)", near(closed.width / closed.height, sw.toFloat() / sh, 0.002f),
                        "%.4f vs %.4f".format(closed.width / closed.height, sw.toFloat() / sh))
                    check("$tag: centred (closed)",
                        near(closed.left - availL, availR - closed.right) && near(closed.top - availT, availB - closed.bottom))
                    // it touches one of the two axes (max scale — nothing left on the table)
                    check("$tag: uses max scale", near(closed.width, availR - availL, 1f) || near(closed.height, availB - availT, 1f))

                    // ---- 2. panel opens (40 % of height): canvas SHRINKS, never cropped/pushed ----
                    val open = ViewportFit.contain(vw, vh, insetL, insetT + topBar, insetR, insetB + sheetBase + panel,
                        pad, sw, sh, minPx)
                    val openAvailB = vh - insetB - sheetBase - panel - pad
                    val degenerate = (openAvailB - availT) < minPx || (availR - availL) < minPx
                    if (!degenerate) {
                        check("$tag: panel open → still fully visible", open.bottom <= openAvailB + 0.01f && open.top >= availT - 0.01f,
                            "bottom=${open.bottom} limit=$openAvailB")
                        check("$tag: panel open → not larger than closed", open.width <= closed.width + 0.01f)
                    } else {
                        check("$tag: degenerate free area falls back to whole view (never zero)", open.width > minPx && open.height > minPx)
                    }
                    check("$tag: aspect preserved (panel open)", near(open.width / open.height, sw.toFloat() / sh, 0.002f))

                    // ---- 3. dock expands by one row: shrink is monotonic ----
                    val expanded = ViewportFit.contain(vw, vh, insetL, insetT + topBar, insetR, insetB + sheetBase + dp(48f).toInt(),
                        pad, sw, sh, minPx)
                    check("$tag: dock expanded → monotonic shrink", expanded.width <= closed.width + 0.01f && expanded.height <= closed.height + 0.01f)

                    // ---- 4. Full Canvas: only system insets (rail hidden), bigger than with chrome ----
                    val sysR = 0
                    val full = ViewportFit.contain(vw, vh, insetL, insetT, sysR, insetB, pad, sw, sh, minPx)
                    check("$tag: full canvas ≥ chrome layout", full.width >= closed.width - 0.01f && full.height >= closed.height - 0.01f)
                    check("$tag: full canvas clears system bars/cutout",
                        full.left >= insetL + pad - 0.01f && full.top >= insetT + pad - 0.01f &&
                            full.right <= vw - sysR - pad + 0.01f && full.bottom <= vh - insetB - pad + 0.01f)
                    // ---- 5. the canvas is never a postage stamp on a phone with chrome closed ----
                    // (9:16 projects are orientation-locked to portrait by
                    // EditorActivity.applyOrientationFor, so "9:16 in landscape"
                    // cannot occur in the app; it is still fully visible above.)
                    if (portrait || label != "9:16") {
                        check("$tag: usable size with chrome (≥ 120dp on the short side)",
                            minOf(closed.width, closed.height) >= dp(120f), "%.0f x %.0f px".format(closed.width, closed.height))
                    }
                }
            }
        }

        // ---- 5. the explicit formula: scale = min(availW/canvasW, availH/canvasH) ----
        run {
            val b = ViewportFit.contain(1000f, 2000f, 0, 100, 0, 300, 0f, 1920, 1080, 10f)
            val availW = 1000f; val availH = 1600f
            val scale = minOf(availW / 1920f, availH / 1080f)
            check("formula: width = canvasW·min(...)", near(b.width, 1920f * scale, 0.01f), "${b.width}")
            check("formula: height = canvasH·min(...)", near(b.height, 1080f * scale, 0.01f), "${b.height}")
            check("formula: centred vertically in avail", near(b.top, 100f + (availH - b.height) / 2f, 0.01f), "${b.top}")
            val p = ViewportFit.contain(1000f, 2000f, 0, 100, 0, 300, 0f, 1080, 1920, 10f)
            val ps = minOf(availW / 1080f, availH / 1920f)
            check("formula 9:16: height-bound", near(p.height, 1920f * ps, 0.01f) && near(p.height, availH, 0.01f))
            check("formula 9:16: centred horizontally", near(p.left, (availW - p.width) / 2f, 0.01f))
        }
        // ---- 6. negative / garbage insets never break the fit ----
        run {
            val b = ViewportFit.contain(800f, 600f, -50, -50, -50, -50, 4f, 1080, 1080, 10f)
            check("negative insets clamp to 0", near(b.width, 592f, 0.01f) && near(b.left, 104f, 0.01f), "$b")
            val z = ViewportFit.contain(800f, 600f, 0, 0, 0, 0, 4f, 0, 0, 10f)
            check("zero-size source does not divide by zero", z.width > 0f && z.height > 0f)
        }

        println("$passes passed, $failures failed")
        if (failures > 0) System.exit(1)
    }
}
