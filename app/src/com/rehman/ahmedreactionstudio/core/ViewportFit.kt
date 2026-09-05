package com.rehman.ahmedreactionstudio.core

/**
 * STEP 1 — editor-viewport math.
 *
 * The ENTIRE composition canvas must always be visible inside the editor
 * viewport: never cropped, never pushed off-screen, correct aspect ratio,
 * centered, scaled down when necessary to fit the available area.
 *
 *   scale = min(availableWidth / canvasWidth, availableHeight / canvasHeight)
 *
 * Pure Kotlin (no Android types) so the responsive cases (16:9 / 9:16 / 1:1
 * × portrait / landscape × chrome open / closed) can be unit-tested on the
 * JVM. [StageView] is the only production caller.
 */
object ViewportFit {

    data class Rect(val l: Float, val t: Float, val r: Float, val b: Float) {
        val w: Float get() = r - l
        val h: Float get() = b - t
    }

    /**
     * Contain-fit a [canvasW]×[canvasH] canvas inside a [vw]×[vh] viewport
     * whose top [topInset]px and bottom [bottomInset]px are reserved for
     * editor chrome (toolbar, bottom sheet, quick bar, chips).
     */
    fun fit(
        vw: Float,
        vh: Float,
        topInset: Float,
        bottomInset: Float,
        canvasW: Int,
        canvasH: Int
    ): Rect {
        if (vw <= 1f || vh <= 1f || canvasW <= 0 || canvasH <= 0) {
            return Rect(0f, 0f, 1f, 1f)
        }
        // NOTE: insets are NOT clamped to half the viewport. On a short
        // landscape screen the legitimate chrome (toolbar + sheet rows) can
        // exceed 50% of the height, and clamping would slide the canvas
        // underneath the sheet. The host caps the expanding panel so the
        // remaining region always keeps a usable minimum height; the
        // coerceAtLeast(1f) below only guards transient/stale measurements
        // (e.g. mid-rotation) against div-by-zero/NaN.
        val top = topInset.coerceIn(0f, vh)
        val bottom = bottomInset.coerceIn(0f, vh)
        val availH = (vh - top - bottom).coerceAtLeast(1f)
        val ar = canvasW.toFloat() / canvasH.toFloat()
        var w = vw
        var h = w / ar
        if (h > availH) {
            h = availH
            w = h * ar
        }
        val l = (vw - w) / 2f
        val t = top + (availH - h) / 2f
        return Rect(l, t, l + w, t + h)
    }
}
