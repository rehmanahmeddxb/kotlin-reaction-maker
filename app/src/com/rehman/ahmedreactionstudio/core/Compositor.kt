package com.rehman.ahmedreactionstudio.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * Deterministic software compositor shared by the live preview AND the exporter,
 * so "preview == export" visually (spec 18: preview quality must never change
 * what gets exported — here the geometry is literally the same code path).
 *
 * All math happens in normalized coordinates converted to the target pixel
 * space, so the same code serves preview at screen resolution and export at
 * 480p/540p/720p.
 */
object Compositor {

    class Ctx {
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val rect = RectF()
        val dst = RectF()
        /**
         * StaticLayout is expensive to build, so it is memoised on the shape of
         * the text (content + wrapped width + size). Rebuilding it 30x/second
         * for every text layer was a steady source of GC hitches during
         * playback; the cache is bounded so live typing can't grow it.
         */
        val staticCache = HashMap<String, StaticLayout>()
        var measuredFontPx = 0f
    }

    fun effectiveSize(sw: Int, sh: Int, rotation: Int): Pair<Int, Int> =
        if (rotation == 90 || rotation == 270) Pair(sh, sw) else Pair(sw, sh)

    /**
     * The EXACT on-canvas bounds of what this layer visually occupies, in the
     * pixel space of a W×H composition:
     *  - COVER (`fill`) and text: the layer box — the drawn frame is clipped
     *    to it, so what you SEE ends at the box edge, not at the frame edge
     *  - CONTAIN (`fit`): the letterboxed drawn frame — the picture, not the
     *    dead space around it
     * When no frame exists yet (no bitmap) it degrades to the box so the layer
     * stays selectable/grabbable.
     *
     * `StageView` draws the selection border, the 8 handles and the rotate knob
     * around precisely this rect, and hit-testing uses it too. It shares the
     * [LayerFit.drawnFrame] formula with [drawLayer], so the chrome and the
     * picture cannot drift apart — the border belongs to the SOURCE, not to
     * the canvas, and follows position/size/rotation automatically.
     */
    fun chromeRect(l: Layer, b: Bitmap?, W: Int, H: Int, out: RectF) {
        val cx = l.cx * W
        val cy = l.cy * H
        var w = l.wN * W
        var h = l.hN * H
        if (l.type != LayerType.TEXT && b != null && !b.isRecycled) {
            val (effW, effH) = if (l.srcW > 0) effectiveSize(l.srcW, l.srcH, l.srcRotation)
            else Pair(b.width, b.height)
            val (fw, fh) = LayerFit.drawnFrame(w, h, effW, effH, l.fit)
            if (fw >= 1f && fh >= 1f) {
                // visible = frame ∩ box (cover overflows the box and is clipped;
                // contain is fully inside it)
                w = minOf(w, fw)
                h = minOf(h, fh)
            }
        }
        w = w.coerceAtLeast(1f)
        h = h.coerceAtLeast(1f)
        out.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    /**
     * Draws the full composition into [canvas] (size W x H).
     * @param bitmapFor returns the decoded bitmap for a media layer, or null
     *                  (caller owns caching; the compositor never decodes).
     * @param selectionId reserved: the id the EDITOR currently edits. The
     *                    compositor never draws any chrome — selection UI is
     *                    the StageView's job (drawn around [chromeRect]) so
     *                    preview == export by construction and no border can
     *                    ever leak into a recording or an export.
     * @param textLayerOverrides optional id->text to preview live typing
     */
    fun draw(
        ctx: Ctx,
        canvas: Canvas,
        W: Int,
        H: Int,
        p: Project,
        bitmapFor: (Layer) -> Bitmap?,
        timeMs: Long,
        selectionId: String? = null,
        textOverrides: Map<String, String> = emptyMap()
    ) {
        canvas.drawColor(p.bgColor)
        for (l in p.layers) {
            if (!l.visible) continue
            if (l.opacity <= 0.005f) continue
            drawLayer(ctx, canvas, W, H, l, bitmapFor(l), timeMs, textOverrides[l.id] ?: l.text)
        }
    }

    private fun drawLayer(
        ctx: Ctx,
        canvas: Canvas,
        W: Int,
        H: Int,
        l: Layer,
        bmp: Bitmap?,
        timeMs: Long,
        text: String
    ) {
        val boxW = l.wN * W
        val boxH = l.hN * H
        if (boxW <= 1f || boxH <= 1f) return
        val cx = l.cx * W
        val cy = l.cy * H

        val rotated = kotlin.math.abs(l.rotDeg) > 0.01f
        if (rotated) { canvas.save(); canvas.rotate(l.rotDeg, cx, cy) }
        ctx.rect.set(cx - boxW / 2f, cy - boxH / 2f, cx + boxW / 2f, cy + boxH / 2f)

        if (l.type == LayerType.TEXT) {
            drawText(ctx, canvas, ctx.rect, l, text)
        } else {
            val b = bmp
            if (b != null && !b.isRecycled) {
                val (effW, effH) = if (l.srcW > 0) effectiveSize(l.srcW, l.srcH, l.srcRotation)
                else Pair(b.width, b.height)
                // Per-source fit mode (OBS plan §3):
                //  FILL = COVER  — the frame fills its box (full-bleed mains,
                //                  edges cropped when aspects differ)
                //  FIT  = CONTAIN — the WHOLE frame is visible inside the box,
                //                  letterboxed; this is what stops camera takes
                //                  being "cut out" on a different-aspect canvas.
                // The size math is LayerFit.drawnFrame — THE formula, shared
                // with Compositor.chromeRect, so the editor's selection border
                // surrounds exactly this rect.
                val (dw, dh) = LayerFit.drawnFrame(boxW, boxH, effW, effH, l.fit)
                if (dw >= 1f && dh >= 1f) {
                    val alpha = (l.opacity * 255f).toInt().coerceIn(0, 255)
                    ctx.p.alpha = alpha
                    canvas.save()
                    canvas.clipRect(ctx.rect)
                    val dx = cx - dw / 2f
                    val dy = cy - dh / 2f
                    ctx.dst.set(dx, dy, dx + dw, dy + dh)
                    canvas.drawBitmap(b, null, ctx.dst, ctx.p)
                    canvas.restore()
                    ctx.p.alpha = 255
                }
            }
        }
        if (rotated) canvas.restore()
    }

    private fun drawText(ctx: Ctx, canvas: Canvas, box: RectF, l: Layer, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val sizePx = (l.fontSizeN * box.height()).coerceAtLeast(6f)
        ctx.textPaint.textSize = sizePx
        ctx.textPaint.color = l.textColor
        ctx.textPaint.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        ctx.textPaint.isAntiAlias = true
        ctx.textPaint.textAlign = Paint.Align.LEFT

        val alpha = (l.opacity * 255f).toInt().coerceIn(0, 255)
        ctx.textPaint.alpha = alpha

        val width = (box.width() - box.width() * 0.08f).toInt().coerceAtLeast(40)
        val lines = t.split("\n").filter { it.isNotBlank() }
        if (lines.isEmpty()) return
        if (l.shadow) {
            ctx.textPaint.setShadowLayer(sizePx * 0.12f, 0f, sizePx * 0.06f, 0x99000000.toInt())
        }
        val layouts = ArrayList<StaticLayout>(lines.size)
        var totalH = 0
        for (para in lines) {
            val key = "$para|${width}|${sizePx.toInt()}|${l.shadow}"
            val sl: StaticLayout = ctx.staticCache[key] ?: run {
                val built = StaticLayout.Builder
                    .obtain(para, 0, para.length, ctx.textPaint, width)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setIncludePad(false)
                    .build()
                if (ctx.staticCache.size > 96) ctx.staticCache.clear()
                ctx.staticCache[key] = built
                built
            }
            layouts.add(sl)
            totalH += sl.height
        }
        var y = box.centerY() - totalH / 2f
        for (sl in layouts) {
            canvas.save()
            canvas.translate(box.left + (box.width() - width) / 2f, y)
            sl.draw(canvas)
            canvas.restore()
            y += sl.height
        }
        ctx.textPaint.clearShadowLayer()
        ctx.textPaint.alpha = 255
    }
}
