package com.rehman.ahmedreactionstudio.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.rehman.ahmedreactionstudio.core.Compositor
import com.rehman.ahmedreactionstudio.core.Layer
import com.rehman.ahmedreactionstudio.core.LayerFit
import com.rehman.ahmedreactionstudio.core.Project
import com.rehman.ahmedreactionstudio.util.UI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The compositor viewport: renders the composition with the same
 * Compositor used by the exporter and handles the PiP gestures
 * (select / move / 8-handle resize / rotate / pinch / snap).
 *
 * The view fills the whole screen and the CANVAS is contain-fitted inside it,
 * so the letterbox area is stage furniture rather than a mysterious strip of a
 * second colour: the surround is drawn dark with a 1px frame around the exact
 * area that will be exported.
 *
 * All gesture math happens in canvas-local pixels and normalized units
 * (0..1 of the canvas), never in density pixels — mixing the two is what made
 * snapping fire on every single drag. (Beware: inside a View subclass the
 * simple name `LayerType` resolves to `android.view.View.LayerType`, so layer
 * kinds are tested through `Layer.isText()` / `Layer.isVideoLike()`.)
 */
class StageView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : View(ctx, attrs) {

    interface Host {
        val project: Project
        fun selectedId(): String?
        fun select(id: String?)
        fun bitmapOf(l: Layer): android.graphics.Bitmap?
        fun textOf(l: Layer): String
        fun onTransform()          // debounced autosave + UI refresh
        fun onTapEmpty()
        fun onChanged()            // immediate (gesture start) snapshot
        fun onDoubleTap(l: Layer)  // quick action: hide / show (OBS plan §4.5)
        /**
         * Long press on the canvas: open the radial menu right under the
         * finger — the source's own ring when a source was pressed, the root
         * ring on empty canvas. [x]/[y] are in THIS view's pixels.
         */
        fun onLongPressCanvas(l: Layer?, x: Float, y: Float)
    }

    companion object {
        private const val SURROUND = 0xFF06070A.toInt()
        private const val MIN_BOX_N = 0.03f
        private const val MAX_BOX_N = 3f
        private const val SNAP_N = 0.016f      // ~1.6% of the canvas
    }

    var host: Host? = null

    /** the exported area, in this view's pixels */
    private val canvasRect = RectF(0f, 0f, 1f, 1f)
    private var cw = 1
    private var ch = 1

    /** canvas size in view pixels (used to size preview decoding) */
    val canvasW: Int get() { layoutCanvas(); return cw }
    val canvasH: Int get() { layoutCanvas(); return ch }

    private val ctxC = Compositor.Ctx()
    private val chrome = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chromeFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tmpRect = RectF()

    private enum class Mode { NONE, MOVE, CORNER, EDGE, ROTATE, PINCH }
    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var startLayerId: String? = null
    private var startCx = 0f; private var startCy = 0f
    private var startWN = 0f; private var startHN = 0f; private var startRot = 0f
    private var hsx = 0f; private var hsy = 0f          // grabbed handle (-1..1 per axis)
    private var startDist = 0f
    private var startAngle = 0f
    private var startMidX = 0f; private var startMidY = 0f
    private var moved = false
    private var undoPushed = false
    private var lastTapUp = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    /** long-press → radial menu at the finger */
    private var longPressFired = false
    private var pendingLongPress: Runnable? = null

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(specSize(w, 360), specSize(h, 360))
    }

    private fun specSize(spec: Int, fallbackDp: Int): Int {
        val size = MeasureSpec.getSize(spec)
        return if (MeasureSpec.getMode(spec) == MeasureSpec.UNSPECIFIED || size <= 0)
            UI.dp(context, fallbackDp) else size
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        layoutCanvas()
    }

    /** contain-fit the project aspect inside this view, centred */
    private fun layoutCanvas() {
        val p = host?.project ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 1f || vh <= 1f) return
        val ar = p.aspect.canvasW.toFloat() / p.aspect.canvasH
        var w = vw
        var h = w / ar
        if (h > vh) { h = vh; w = h * ar }
        val l = (vw - w) / 2f
        val t = (vh - h) / 2f
        canvasRect.set(l, t, l + w, t + h)
        cw = w.roundToInt().coerceAtLeast(1)
        ch = h.roundToInt().coerceAtLeast(1)
    }

    fun refresh() { invalidate() }

    // ----- unit mapping (canvas-local px <-> normalized) -----
    private fun lx(e: MotionEvent, i: Int = 0): Float = e.getX(i) - canvasRect.left
    private fun ly(e: MotionEvent, i: Int = 0): Float = e.getY(i) - canvasRect.top
    private fun nx(px: Float): Float = px / cw
    private fun ny(py: Float): Float = py / ch

    override fun onDraw(canvas: Canvas) {
        val hp = host ?: return
        layoutCanvas()
        val p = hp.project
        canvas.drawColor(SURROUND)
        canvas.save()
        canvas.translate(canvasRect.left, canvasRect.top)
        canvas.clipRect(0f, 0f, cw.toFloat(), ch.toFloat())
        Compositor.draw(ctxC, canvas, cw, ch, p, { hp.bitmapOf(it) }, 0L,
            hp.selectedId(), emptyMap())
        canvas.restore()
        // the exported area, marked exactly
        framePaint.style = Paint.Style.STROKE
        framePaint.strokeWidth = UI.dpf(context, 1f)
        framePaint.color = Color.argb(90, 255, 255, 255)
        canvas.drawRect(canvasRect, framePaint)
        val selId = hp.selectedId() ?: return
        val l = p.layerById(selId) ?: return
        canvas.save()
        canvas.translate(canvasRect.left, canvasRect.top)
        drawChrome(canvas, l)
        canvas.restore()
    }

    private fun rectOf(l: Layer): RectF {
        val cxp = l.cx * cw
        val cyp = l.cy * ch
        tmpRect.set(cxp - l.wN * cw / 2f, cyp - l.hN * ch / 2f,
            cxp + l.wN * cw / 2f, cyp + l.hN * ch / 2f)
        return tmpRect
    }

    private fun drawChrome(canvas: Canvas, l: Layer) {
        val r = RectF(rectOf(l))
        chrome.color = if (l.locked) UI.FG2 else UI.ACCENT
        chrome.style = Paint.Style.STROKE
        chrome.strokeWidth = UI.dpf(context, if (l.locked) 1f else 2f)
        canvas.save()
        canvas.rotate(l.rotDeg, r.centerX(), r.centerY())
        canvas.drawRect(r, chrome)
        val h = UI.dpf(context, 9f)
        chromeFill.color = if (l.locked) UI.BG3 else UI.ACCENT
        // 4 corners + 4 edges = 8 handles (spec 7.2)
        for (i in 0..2) {
            for (j in 0..2) {
                if (i == 1 && j == 1) continue
                val ex = if (j == 0) r.left else if (j == 2) r.right else r.centerX()
                val ey = if (i == 0) r.top else if (i == 2) r.bottom else r.centerY()
                canvas.drawRect(ex - h, ey - h, ex + h, ey + h, chromeFill)
            }
        }
        // rotation handle above top-center
        val topY = r.top - UI.dpf(context, 18f)
        val cx = r.centerX()
        chrome.strokeWidth = UI.dpf(context, 1.5f)
        chrome.color = UI.ACCENT2
        canvas.drawLine(cx, r.top, cx, topY, chrome)
        chromeFill.color = UI.ACCENT2
        canvas.drawCircle(cx, topY, h * 0.9f, chromeFill)
        chromeFill.color = Color.WHITE
        canvas.drawCircle(cx, topY, h * 0.45f, chromeFill)
        canvas.restore()
    }

    // ---------- hit testing ----------

    /** point in the layer's own (unrotated) frame, relative to its center */
    private fun toLayerLocal(x: Float, y: Float, l: Layer, r: RectF): Pair<Float, Float> {
        val ang = Math.toRadians((-l.rotDeg).toDouble())
        val dx = x - r.centerX(); val dy = y - r.centerY()
        return Pair((dx * cos(ang) - dy * sin(ang)).toFloat(),
            (dx * sin(ang) + dy * cos(ang)).toFloat())
    }

    private fun hitHandle(x: Float, y: Float, l: Layer): String? {
        val r = rectOf(l)
        val halfW = r.width() / 2f
        val halfH = r.height() / 2f
        val (px, py) = toLayerLocal(x, y, l, r)
        // generous finger target, but never so big that a small PiP is nothing
        // but handles (that was the "cannot drag the PiP" bug)
        val touch = min(UI.dpf(context, 20f), min(halfW, halfH) * 0.9f)
        if (touch <= 0f) return null
        // rotate knob above the top edge
        val knobY = -halfH - UI.dpf(context, 18f)
        if (abs(px) <= touch && abs(py - knobY) <= touch) return "ROT"
        val onV = when {
            abs(px + halfW) <= touch -> -1f
            abs(px - halfW) <= touch -> 1f
            else -> 0f
        }
        val onH = when {
            abs(py + halfH) <= touch -> -1f
            abs(py - halfH) <= touch -> 1f
            else -> 0f
        }
        val midX = abs(px) <= touch
        val midY = abs(py) <= touch
        return when {
            onV == -1f && onH == -1f -> "TL"
            onV == 1f && onH == -1f -> "TR"
            onV == -1f && onH == 1f -> "BL"
            onV == 1f && onH == 1f -> "BR"
            midX && onH == -1f -> "TC"
            midX && onH == 1f -> "BC"
            onV == -1f && midY -> "ML"
            onV == 1f && midY -> "MR"
            else -> null
        }
    }

    /** returns top-most layer whose box contains the point (canvas-local) */
    private fun layerAt(x: Float, y: Float): Layer? {
        val hp = host ?: return null
        val p = hp.project
        if (x < 0f || y < 0f || x > cw || y > ch) return null
        for (i in p.layers.indices.reversed()) {
            val l = p.layers[i]
            if (!l.visible || l.opacity <= 0.01f) continue
            val r = rectOf(l)
            val (px, py) = toLayerLocal(x, y, l, r)
            if (abs(px) <= r.width() / 2f && abs(py) <= r.height() / 2f) return l
        }
        return null
    }

    // ---------- gestures ----------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val hp = host ?: return false
        val p = hp.project
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                moved = false
                undoPushed = false
                downX = lx(e); downY = ly(e)
                scheduleLongPress(e.x, e.y, lx(e), ly(e))
                val selId = hp.selectedId()
                val sel = selId?.let { p.layerById(it) }
                // rotation / resize handles on the current selection first
                if (sel != null && !sel.locked && sel.visible) {
                    val handle = hitHandle(downX, downY, sel)
                    if (handle != null) {
                        startGesture(sel, handle)
                        return true
                    }
                }
                val hit = layerAt(downX, downY)
                if (hit != null) {
                    if (hit.id != selId) hp.select(hit.id)
                    if (hit.locked) { mode = Mode.NONE; startLayerId = null; return true }
                    startGesture(hit, "MOVE")
                } else {
                    hp.onTapEmpty()
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelRadialLongPress()
                val l = startLayerId?.let { p.layerById(it) }
                if (l != null && !l.locked && e.pointerCount == 2 && mode != Mode.ROTATE) {
                    mode = Mode.PINCH
                    val x0 = lx(e, 0); val y0 = ly(e, 0)
                    val x1 = lx(e, 1); val y1 = ly(e, 1)
                    startDist = hypot(x1 - x0, y1 - y0).coerceAtLeast(1f)
                    startAngle = atan2(y1 - y0, x1 - x0)
                    startMidX = (x0 + x1) / 2f; startMidY = (y0 + y1) / 2f
                    startCx = l.cx; startCy = l.cy
                    startWN = l.wN; startHN = l.hN
                    startRot = l.rotDeg
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(lx(e) - downX, ly(e) - downY) > UI.dpf(context, 12f)) cancelRadialLongPress()
                if (longPressFired) return true
                if (mode == Mode.NONE) return true
                val l = startLayerId?.let { p.layerById(it) } ?: return true
                val x = lx(e); val y = ly(e)
                when (mode) {
                    Mode.MOVE -> {
                        if (!LayerFit.isFullBleed(l)) {
                            l.cx = startCx + nx(x - downX)
                            l.cy = startCy + ny(y - downY)
                            snapMove(l)
                            LayerFit.clampInside(l)
                            if (hypot(x - downX, y - downY) > UI.dpf(context, 3f)) touchMoved()
                        }
                    }
                    Mode.PINCH -> if (e.pointerCount >= 2) { pinchMove(l, e); touchMoved() }
                    Mode.CORNER, Mode.EDGE -> { resizeTo(l, x, y); touchMoved() }
                    Mode.ROTATE -> { rotateTo(l, x, y); touchMoved() }
                    else -> { }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == Mode.PINCH) {
                    mode = Mode.MOVE
                    val l = startLayerId?.let { p.layerById(it) }
                    val keep = if (e.actionIndex == 0) 1 else 0
                    if (l != null && e.pointerCount > keep) {
                        downX = lx(e, keep); downY = ly(e, keep)
                        startCx = l.cx; startCy = l.cy
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                cancelRadialLongPress()
                if (longPressFired) {
                    longPressFired = false
                    mode = Mode.NONE; startLayerId = null
                    return true
                }
                if (moved) host?.onTransform()
                else {
                    // clean tap on a layer: double-tap = hide / show quick action
                    val x = lx(e); val y = ly(e)
                    val now = android.os.SystemClock.uptimeMillis()
                    val hit = layerAt(x, y)
                    if (hit != null && now - lastTapUp < 320L &&
                        hypot(x - lastTapX, y - lastTapY) < UI.dpf(context, 36f)) {
                        lastTapUp = 0L
                        hp.onDoubleTap(hit)
                    } else {
                        lastTapUp = now
                        lastTapX = x; lastTapY = y
                    }
                }
                mode = Mode.NONE
                startLayerId = null
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelRadialLongPress()
                longPressFired = false
                if (moved) host?.onTransform()
                mode = Mode.NONE
                startLayerId = null
                invalidate()
                return true
            }
        }
        return true
    }

    // ---------- long press → radial menu ----------

    private fun scheduleLongPress(rawX: Float, rawY: Float, cxp: Float, cyp: Float) {
        cancelRadialLongPress()
        longPressFired = false
        val r = Runnable {
            // a drag/resize already in progress must not be hijacked
            if (moved) return@Runnable
            longPressFired = true
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            mode = Mode.NONE
            startLayerId = null
            host?.onLongPressCanvas(layerAt(cxp, cyp), rawX, rawY)
        }
        pendingLongPress = r
        postDelayed(r, 460L)
    }

    private fun cancelRadialLongPress() {
        pendingLongPress?.let { removeCallbacks(it) }
        pendingLongPress = null
    }

    private fun startGesture(l: Layer, m: String) {
        mode = when (m) {
            "MOVE" -> Mode.MOVE
            "ROT" -> Mode.ROTATE
            "TL", "TR", "BL", "BR" -> Mode.CORNER
            else -> Mode.EDGE
        }
        val r = rectOf(l)
        hsx = when (m) {
            "TL", "BL", "ML" -> -1f
            "TR", "BR", "MR" -> 1f
            else -> 0f
        }
        hsy = when (m) {
            "TL", "TR", "TC" -> -1f
            "BL", "BR", "BC" -> 1f
            else -> 0f
        }
        if (mode == Mode.CORNER || mode == Mode.EDGE) {
            // re-anchor the down point to the handle so the first move is not a jump
            val ang = Math.toRadians(l.rotDeg.toDouble())
            val ca = cos(ang).toFloat(); val sa = sin(ang).toFloat()
            val ox = hsx * r.width() / 2f
            val oy = hsy * r.height() / 2f
            downX = r.centerX() + ox * ca - oy * sa
            downY = r.centerY() + ox * sa + oy * ca
        }
        startLayerId = l.id
        startCx = l.cx; startCy = l.cy
        startWN = l.wN; startHN = l.hN
        startRot = l.rotDeg
    }

    /** one undo snapshot per gesture, taken on the first real movement */
    private fun touchMoved() {
        if (!undoPushed) { undoPushed = true; host?.onChanged() }
        moved = true
        host?.onTransform()
    }

    /**
     * True handle dragging: the grabbed corner/edge follows the finger while the
     * OPPOSITE side stays anchored, in the layer's own rotated frame. Media
     * layers keep their aspect ratio, so a camera PiP can never be squashed.
     */
    private fun resizeTo(l: Layer, x: Float, y: Float) {
        val startWpx = (startWN * cw).coerceAtLeast(1f)
        val startHpx = (startHN * ch).coerceAtLeast(1f)
        val cx0 = startCx * cw
        val cy0 = startCy * ch
        val ang = Math.toRadians(l.rotDeg.toDouble())
        val ca = cos(ang).toFloat(); val sa = sin(ang).toFloat()

        // anchor = the side opposite the grabbed handle
        val axLocal = -hsx * startWpx / 2f
        val ayLocal = -hsy * startHpx / 2f
        val ax = cx0 + axLocal * ca - ayLocal * sa
        val ay = cy0 + axLocal * sa + ayLocal * ca

        // pointer in the layer's unrotated frame, relative to the anchor
        val cn = cos(-ang).toFloat(); val sn = sin(-ang).toFloat()
        val dx = x - ax; val dy = y - ay
        val px = dx * cn - dy * sn
        val py = dx * sn + dy * cn

        val minPx = UI.dpf(context, 24f)
        var newW = if (hsx != 0f) abs(px).coerceAtLeast(minPx) else startWpx
        var newH = if (hsy != 0f) abs(py).coerceAtLeast(minPx) else startHpx
        if (!l.isText()) {
            val k = when {
                hsx != 0f && hsy != 0f -> (newW / startWpx + newH / startHpx) / 2f
                hsx != 0f -> newW / startWpx
                else -> newH / startHpx
            }
            newW = startWpx * k
            newH = startHpx * k
        }
        newW = newW.coerceIn(minPx, cw * MAX_BOX_N)
        newH = newH.coerceIn(minPx, ch * MAX_BOX_N)

        val sgx = if (px >= 0f) 1f else -1f
        val sgy = if (py >= 0f) 1f else -1f
        val mxLocal = if (hsx != 0f) sgx * newW / 2f else 0f
        val myLocal = if (hsy != 0f) sgy * newH / 2f else 0f
        l.wN = newW / cw
        l.hN = newH / ch
        l.cx = (ax + mxLocal * ca - myLocal * sa) / cw
        l.cy = (ay + mxLocal * sa + myLocal * ca) / ch
    }

    private fun rotateTo(l: Layer, x: Float, y: Float) {
        val cx = l.cx * cw; val cy = l.cy * ch
        val a0 = atan2(downY - cy, downX - cx)
        val a1 = atan2(y - cy, x - cx)
        var deg = startRot + Math.toDegrees((a1 - a0).toDouble()).toFloat()
        deg = ((deg % 360f) + 360f) % 360f
        for (t in floatArrayOf(0f, 90f, 180f, 270f)) if (abs(deg - t) < 5f) deg = t
        l.rotDeg = deg
    }

    /** two fingers: scale + rotate around the pinch midpoint, layer follows it */
    private fun pinchMove(l: Layer, e: MotionEvent) {
        val x0 = lx(e, 0); val y0 = ly(e, 0)
        val x1 = lx(e, 1); val y1 = ly(e, 1)
        val dist = hypot(x1 - x0, y1 - y0).coerceAtLeast(1f)
        val angle = atan2(y1 - y0, x1 - x0)
        val sc = (dist / startDist).coerceIn(0.15f, 6f)
        val rotDelta = Math.toDegrees((angle - startAngle).toDouble()).toFloat()
        val vx = startCx * cw - startMidX
        val vy = startCy * ch - startMidY
        val a = Math.toRadians(rotDelta.toDouble())
        val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
        l.cx = (startMidX + (vx * ca - vy * sa) * sc) / cw
        l.cy = (startMidY + (vx * sa + vy * ca) * sc) / ch
        l.wN = (startWN * sc).coerceIn(MIN_BOX_N, MAX_BOX_N)
        l.hN = (startHN * sc).coerceIn(MIN_BOX_N, MAX_BOX_N)
        l.rotDeg = (((startRot + rotDelta) % 360f) + 360f) % 360f
        LayerFit.clampInside(l, 0.25f)
    }

    /**
     * Snap the layer to the canvas centre/edges and to sibling edges/centres.
     * Thresholds are NORMALIZED — a dp value here would be ~14x the whole
     * canvas and would glue every layer to the centre on every drag.
     */
    private fun snapMove(l: Layer) {
        val halfW = l.wN / 2f
        val halfH = l.hN / 2f
        if (l.wN < 0.9f) {
            val xs = ArrayList<Float>(10)
            xs.add(0.5f); xs.add(halfW); xs.add(1f - halfW)
            for (o in host?.project?.layers ?: emptyList()) {
                if (o.id == l.id) continue
                xs.add(o.cx + o.wN / 2f + halfW)
                xs.add(o.cx - o.wN / 2f - halfW)
                xs.add(o.cx)
            }
            l.cx = nearest(l.cx, xs)
        }
        if (l.hN < 0.9f) {
            val ys = ArrayList<Float>(10)
            ys.add(0.5f); ys.add(halfH); ys.add(1f - halfH)
            for (o in host?.project?.layers ?: emptyList()) {
                if (o.id == l.id) continue
                ys.add(o.cy + o.hN / 2f + halfH)
                ys.add(o.cy - o.hN / 2f - halfH)
                ys.add(o.cy)
            }
            l.cy = nearest(l.cy, ys)
        }
    }

    private fun nearest(v: Float, targets: List<Float>): Float {
        var best = v
        var bestD = SNAP_N
        for (t in targets) {
            val d = abs(v - t)
            if (d < bestD) { bestD = d; best = t }
        }
        return best
    }
}
