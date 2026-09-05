package com.rehman.ahmedreactionstudio.core

import kotlin.math.min

/**
 * Pure contain-fit of a composition into the unobstructed part of a view.
 *
 * No Android types so the rule can be unit-tested on the JVM
 * (tools/viewport-fit-test). StageView is the only production caller.
 *
 *   avail  = view − insets − 2·pad
 *   scale  = min(avail.w / srcW, avail.h / srcH)
 *   canvas = (srcW·scale, srcH·scale), centred inside avail
 *
 * Degenerate insets (chrome covering nearly everything — a tiny landscape
 * phone with every panel open) fall back to the whole view minus padding, so
 * the picture shrinks but never collapses to zero or vanishes.
 */
object ViewportFit {
    class Box(val left: Float, val top: Float, val width: Float, val height: Float) {
        val right: Float get() = left + width
        val bottom: Float get() = top + height
        override fun toString() = "Box(l=$left t=$top w=$width h=$height)"
    }

    /**
     * Budget the portrait panel around the measured fixed sheet and floating
     * controls. Keep Step 5's 38% cap and at least 28% of the view for the
     * canvas; the landscape panel lives in a separately scrollable right rail.
     * All arguments/results are pixels. Kept pure for merge regression tests.
     */
    fun panelHeight(viewHeight: Int, topInset: Int, fixedSheet: Int, floatingControls: Int): Int {
        val h = viewHeight.coerceAtLeast(0).toFloat()
        val budget = h - topInset.coerceAtLeast(0) - fixedSheet.coerceAtLeast(0) -
            floatingControls.coerceAtLeast(0) - h * 0.28f
        return min(h * 0.38f, budget).coerceAtLeast(0f).toInt()
    }

    fun contain(
        viewW: Float, viewH: Float,
        insetL: Int, insetT: Int, insetR: Int, insetB: Int,
        pad: Float,
        srcW: Int, srcH: Int,
        minPx: Float
    ): Box {
        val l = insetL.coerceAtLeast(0); val t = insetT.coerceAtLeast(0)
        val r = insetR.coerceAtLeast(0); val b = insetB.coerceAtLeast(0)
        var ax = l + pad
        var ay = t + pad
        var aw = viewW - l - r - pad * 2f
        var ah = viewH - t - b - pad * 2f
        if (aw < minPx || ah < minPx) {
            ax = pad; ay = pad; aw = viewW - pad * 2f; ah = viewH - pad * 2f
        }
        if (aw < 1f) aw = 1f
        if (ah < 1f) ah = 1f
        val sw = srcW.coerceAtLeast(1).toFloat()
        val sh = srcH.coerceAtLeast(1).toFloat()
        val scale = min(aw / sw, ah / sh)
        val w = sw * scale
        val h = sh * scale
        return Box(ax + (aw - w) / 2f, ay + (ah - h) / 2f, w, h)
    }
}
