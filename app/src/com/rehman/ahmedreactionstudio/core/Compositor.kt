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
        val staticCache = HashMap<String, StaticLayout>()
        var measuredFontPx = 0f
    }

    fun effectiveSize(sw: Int, sh: Int, rotation: Int): Pair<Int, Int> =
        if (rotation == 90 || rotation == 270) Pair(sh, sw) else Pair(sw, sh)

    /**
     * Draws the full composition into [canvas] (size W x H).
     * @param bitmapFor returns the decoded bitmap for a media layer, or null
     *                  (caller owns caching; the compositor never decodes).
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

        canvas.save()
        canvas.rotate(l.rotDeg, cx, cy)
        ctx.rect.set(cx - boxW / 2f, cy - boxH / 2f, cx + boxW / 2f, cy + boxH / 2f)

        if (l.type == LayerType.TEXT) {
            drawText(ctx, canvas, ctx.rect, l, text)
        } else {
            val b = bmp
            if (b != null && !b.isRecycled) {
                val (effW, effH) = if (l.srcW > 0) effectiveSize(l.srcW, l.srcH, l.srcRotation)
                else Pair(b.width, b.height)
                if (effW <= 0 || effH <= 0) { canvas.restore(); return }
                val alpha = (l.opacity * 255f).toInt().coerceIn(0, 255)
                ctx.p.alpha = alpha
                canvas.save()
                canvas.clipRect(ctx.rect)
                // cover (no distortion) by default
                val sc = kotlin.math.max(boxW / effW, boxH / effH)
                val dw = effW * sc
                val dh = effH * sc
                val dx = cx - dw / 2f
                val dy = cy - dh / 2f
                canvas.drawBitmap(b, null, RectF(dx, dy, dx + dw, dy + dh), ctx.p)
                canvas.restore()
                ctx.p.alpha = 255
            }
        }
        canvas.restore()
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
        for (para in lines) {
            layouts.add(StaticLayout.Builder
                .obtain(para, 0, para.length, ctx.textPaint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .build())
        }
        val totalH = layouts.sumOf { it.height }
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
        ctx.staticCache.clear() // keep memory flat between frames
    }
}
