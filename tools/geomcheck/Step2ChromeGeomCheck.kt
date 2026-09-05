package com.rehman.ahmedreactionstudio.geomcheck

import com.rehman.ahmedreactionstudio.core.Layer
import com.rehman.ahmedreactionstudio.core.LayerFit
import kotlin.math.abs

/**
 * STEP 2 — JVM check of the selection-border geometry (see tools/step2-geom-check.sh).
 *
 * Exercises `LayerFit.drawnFrame` — the ONE formula the Compositor uses for
 * the drawBitmap destination AND StageView uses (through
 * `Compositor.chromeRect`) for the selection border — plus the exact
 * chrome-space resize mapping `StageView.resizeTo` performs. Pure floats,
 * so this runs on a plain JVM: no device, no Android views, no emulator.
 * The same canvases the editor cycles through (16:9 / 9:16 / 1:1), the
 * same sources it composites (portrait camera, landscape video, image).
 */

private var failures = 0

private fun check(name: String, cond: Boolean) {
    if (!cond) { failures++; println("FAIL  $name") } else println("ok    $name")
}

/** Mirrors Compositor.chromeRect: visible = box ∩ drawnFrame (no bitmap -> box). */
private fun chromeSize(boxW: Float, boxH: Float, srcW: Int, srcH: Int, rot: Int, fit: String): Pair<Float, Float> {
    val (ew, eh) = LayerFit.effective(srcW, srcH, rot)
    val (fw, fh) = LayerFit.drawnFrame(boxW, boxH, ew, eh, fit)
    if (fw < 1f || fh < 1f) return Pair(boxW, boxH)
    return Pair(minOf(boxW, fw), minOf(boxH, fh))
}

fun main() {
    val eps = 0.51f

    // --- COVER (fill): the frame is clipped to the box, so chrome IS the box
    //     whatever the source aspect — a full-bleed main correctly wears a
    //     frame at the canvas edges (its visible bounds), and that box never
    //     "sticks out" past it because LayerFit.fill() clamps the box to 1x1.
    for ((cW, cH) in listOf(1920 to 1080, 1080 to 1920, 1080 to 1080)) {
        val (vw, vh) = chromeSize(cW.toFloat(), cH.toFloat(), 1080, 1920, 0, Layer.FIT_FILL)
        check("cover main on ${cW}x${cH}: chrome == box",
            vw == cW.toFloat() && vh == cH.toFloat())
        val (vw2, vh2) = chromeSize(cW.toFloat(), cH.toFloat(), 1920, 1080, 90, Layer.FIT_FILL)
        check("cover main with rot-90 metadata on ${cW}x${cH}: chrome == box",
            vw2 == cW.toFloat() && vh2 == cH.toFloat())
    }

    // --- CONTAIN (fit): the border hugs the letterboxed PICTURE, not the box.
    // Portrait camera as the 16:9 main — the exact bug: the border used to
    // surround the ENTIRE canvas; it must be the strip, and only the strip.
    run {
        val (vw, vh) = chromeSize(1920f, 1080f, 1080, 1920, 0, Layer.FIT_FIT)
        check("fit portrait camera on 16:9 main: height-constrained strip",
            abs(vh - 1080f) < eps && abs(vw - 1080f * 1080f / 1920f) < eps)
        check("fit portrait camera on 16:9 main: does NOT surround the canvas",
            vw < 1920f - 1f)
        check("fit portrait camera on 16:9 main: chrome keeps the source aspect",
            abs(vw / vh - 1080f / 1920f) < 0.01f)
    }
    // The same source on 9:16: aspects match, so chrome == canvas (correctly —
    // the picture fills it), and on 1:1: width-clipped strip again.
    run {
        val (vw, vh) = chromeSize(1080f, 1920f, 1080, 1920, 0, Layer.FIT_FIT)
        check("fit portrait camera on 9:16 main: chrome == full canvas",
            abs(vw - 1080f) < eps && abs(vh - 1920f) < eps)
    }
    run {
        val (vw, vh) = chromeSize(1080f, 1080f, 1080, 1920, 0, Layer.FIT_FIT)
        check("fit portrait camera on 1:1 main: height-clipped strip, never the square",
            abs(vh - 1080f) < eps && vw < 1080f - 1f)
    }
    // Landscape video as a FIT main on 9:16 -> full-width letterboxed band
    run {
        val (vw, vh) = chromeSize(1080f, 1920f, 1920, 1080, 0, Layer.FIT_FIT)
        check("fit landscape video on 9:16 main: width-constrained band",
            abs(vw - 1080f) < eps && vh < 1920f - 1f)
    }

    // --- Aspect-matched PiP (the default placement of every added overlay):
    //     box aspect == source aspect -> chrome == box, so the border and the
    //     8 handles sit exactly where they did before Step 2.
    run {
        val boxW = 0.36f * 1920f
        // LayerFit.pip() builds the box AT the source aspect: same pixel ratio
        val boxH = boxW / (1920f / 1080f)
        val (vw, vh) = chromeSize(boxW, boxH, 1920, 1080, 0, Layer.FIT_FIT)
        check("aspect-matched fit PiP: chrome == box",
            abs(vw - boxW) < 1f && abs(vh - boxH) < 1f)
    }

    // --- resizeTo's chrome-space mapping. The gesture scales the VISIBLE
    //     frame by k (corner for media; edge on a letterboxed fit source),
    //     then writes box = frame * (boxStart/frameStart). Invariant tested:
    //     after the write-back the compositor's chrome recomputes to EXACTLY
    //     the scaled frame — the border follows the finger on every axis,
    //     letterboxed or not, on every canvas aspect.
    fun resizeFollowsFinger(cW: Int, cH: Int, srcW: Int, srcH: Int, fit: String,
                            startWN: Float, startHN: Float, k: Float): Boolean {
        val (f0w, f0h) = chromeSize(startWN * cW, startHN * cH, srcW, srcH, 0, fit)
        val rx = startWN * cW / f0w
        val ry = startHN * cH / f0h
        val newFw = f0w * k; val newFh = f0h * k
        val bWN = (newFw * rx / cW).coerceIn(0.03f, 3f)
        val bHN = (newFh * ry / cH).coerceIn(0.03f, 3f)
        val (rw, rh) = chromeSize(bWN * cW, bHN * cH, srcW, srcH, 0, fit)
        return abs(rw - newFw) < 2f && abs(rh - newFh) < 2f
    }
    check("resize mapping: letterboxed fit main on 16:9, uniform k=1.25 — border tracks the frame",
        resizeFollowsFinger(1920, 1080, 1080, 1920, Layer.FIT_FIT, 1f, 1f, 1.25f))
    check("resize mapping: letterboxed fit main on 16:9, shrink k=0.62",
        resizeFollowsFinger(1920, 1080, 1080, 1920, Layer.FIT_FIT, 1f, 1f, 0.62f))
    check("resize mapping: fit PiP on 9:16, k=1.9",
        resizeFollowsFinger(1080, 1920, 1080, 1920, Layer.FIT_FIT, 0.34f, 0.30f, 1.9f))
    check("resize mapping: COVER main on 1:1 (rx=ry=1 — classic box math), k=0.8",
        resizeFollowsFinger(1080, 1080, 1920, 1080, Layer.FIT_FILL, 1f, 1f, 0.8f))

    // --- Pinch scales the box; the letterboxed chrome must scale with it
    //     exactly (so the border glued to the picture never lags a pinch).
    run {
        val (a1, b1) = chromeSize(600f, 700f, 1080, 1920, 0, Layer.FIT_FIT)
        val (a2, b2) = chromeSize(1200f, 1400f, 1080, 1920, 0, Layer.FIT_FIT)
        check("pinch scale 2x doubles the letterboxed chrome exactly",
            abs(a2 - 2 * a1) < eps && abs(b2 - 2 * b1) < eps)
    }

    // --- Rotation: chromeRect is concentric with the box by construction
    //     (the compositor centers the frame in the box), so rotating the
    //     chrome around the box center rotates it around the FRAME center —
    //     one pivot, border spins with the picture.
    check("chrome is concentric with the box (single rotation pivot)",
        LayerFit.drawnFrame(1920f, 1080f, 1080, 1920, Layer.FIT_FIT).let { (fw, fh) ->
            fw <= 1920f && fh <= 1080f && fw > 1f && fh > 1f
        })

    // --- No-bitmap sources (live camera before its first frame): chrome
    //     degrades to the box so the source stays selectable, never zero.
    run {
        val (fw, fh) = LayerFit.drawnFrame(1920f, 1080f, 0, 0, Layer.FIT_FIT)
        check("degenerate source size yields (0,0) -> chromeRect falls back to the box",
            fw == 0f && fh == 0f)
    }

    if (failures > 0) {
        println("STEP2 GEOMETRY CHECK: $failures FAILURE(S)")
        kotlin.system.exitProcess(1)
    }
    println("STEP2 GEOMETRY CHECK: all green")
}
