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
import com.rehman.ahmedreactionstudio.core.ViewportFit
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
 * The view fills the whole screen and the CANVAS is contain-fitted inside the
 * part of it that no chrome covers (see [setViewportInsets]): the top bar,
 * the bottom dock / sheet / contextual controls and the system bars + display
 * cutout are subtracted first, then `scale = min(availW / canvasW,
 * availH / canvasH)` and the canvas is centred in what is left. Opening a
 * panel therefore shrinks the canvas a little instead of hiding a strip of
 * it — the whole composition is always visible, in portrait and landscape,
 * for 16:9, 9:16 and 1:1 alike. The letterbox surround is stage furniture:
 * drawn dark with a 1px frame around the exact area that will be exported.
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
        fun onDoubleTap(l: Layer)  // text layers: edit; anything else: nothing
        /** Tapping a locked layer: explain + offer unlock (never silent). */
        fun onLockedTap(l: Layer)
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

    /** the canvas rectangle in this view's pixels (read-only copy) */
    fun canvasBounds(): RectF = RectF(canvasRect)

    /**
     * Space at each edge of this view that chrome covers (system bars, cutout,
     * top bar, bottom controls) — the canvas is fitted inside the remainder.
     * A small breathing margin is added on top so the selection handles and
     * the label pill of an edge-hugging layer stay visible.
     */
    private var insetL = 0
    private var insetT = 0
    private var insetR = 0
    private var insetB = 0
    private var lastFitW = 0f
    private var lastFitH = 0f
    /** the host is told whenever the fitted canvas size changes (decode target, HUD) */
    var onCanvasLayout: ((Int, Int) -> Unit)? = null

    fun setViewportInsets(left: Int, top: Int, right: Int, bottom: Int) {
        val l = left.coerceAtLeast(0); val t = top.coerceAtLeast(0)
        val r = right.coerceAtLeast(0); val b = bottom.coerceAtLeast(0)
        if (l == insetL && t == insetT && r == insetR && b == insetB) return
        insetL = l; insetT = t; insetR = r; insetB = b
        layoutCanvas()
        invalidate()
    }

    /** current avoid-insets (l, t, r, b) in view pixels */
    fun viewportInsets(): IntArray = intArrayOf(insetL, insetT, insetR, insetB)

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
    /**
     * True pre-gesture state for the undo snapshot. The gesture math reference
     * ([startCx]…) can move during the gesture — a letterboxed FIT box is
     * collapsed onto its picture on the first resize step — but undo must
     * restore what was on screen when the finger went DOWN (box AND fit mode).
     */
    private var undoCx = 0f; private var undoCy = 0f
    private var undoWN = 0f; private var undoHN = 0f; private var undoRot = 0f
    private var undoFit = Layer.FIT_FILL
    /** resize box→picture normalization done for the current gesture */
    private var resizePrimed = false
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

    /**
     * Contain-fit the project aspect inside the unobstructed part of this
     * view, centred there. Pure function of (view size, insets, aspect):
     *
     *   avail = view − insets − breathing margin
     *   scale = min(avail.w / canvasW, avail.h / canvasH)
     *   canvas = (canvasW·scale, canvasH·scale) centred in avail
     *
     * If the insets ever leave less than a postage stamp (a tiny landscape
     * phone with every panel open) the fit falls back to the whole view rather
     * than collapsing to zero — the chrome then overlaps, but the picture
     * never disappears.
     */
    private fun layoutCanvas() {
        val p = host?.project ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 1f || vh <= 1f) return
        val box = ViewportFit.contain(
            vw, vh, insetL, insetT, insetR, insetB,
            pad = UI.dpf(context, 6f),
            srcW = p.aspect.canvasW, srcH = p.aspect.canvasH,
            minPx = UI.dpf(context, 96f))
        canvasRect.set(box.left, box.top, box.right, box.bottom)
        cw = box.width.roundToInt().coerceAtLeast(1)
        ch = box.height.roundToInt().coerceAtLeast(1)
        if (box.width != lastFitW || box.height != lastFitH) {
            lastFitW = box.width; lastFitH = box.height
            onCanvasLayout?.invoke(cw, ch)
        }
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
        val selId = hp.selectedId()
        canvas.save()
        canvas.translate(canvasRect.left, canvasRect.top)
        // unselected, visible, non-background sources: a subtle neutral
        // hairline around the visible picture — enough to see WHERE a source
        // is (and that it is a source) without competing with the selection
        for (o in p.layers) {
            if (o.id == selId || !o.visible || o.opacity <= 0.01f || LayerFit.isFullBleed(o)) continue
            val r = chromeRectOf(o)
            canvas.save()
            canvas.rotate(o.rotDeg, r.centerX(), r.centerY())
            framePaint.strokeWidth = UI.dpf(context, 1f)
            framePaint.color = if (o.locked) Color.argb(70, 255, 255, 255) else Color.argb(48, 255, 255, 255)
            canvas.drawRect(r, framePaint)
            canvas.restore()
        }
        val l = selId?.let { p.layerById(it) }
        // a hidden source is not on the canvas, so it has no frame either
        if (l != null && l.visible) drawChrome(canvas, l)
        canvas.restore()
    }

    /** the layer's BOX (what gestures resize) */
    private fun rectOf(l: Layer): RectF {
        val cxp = l.cx * cw
        val cyp = l.cy * ch
        tmpRect.set(cxp - l.wN * cw / 2f, cyp - l.hN * ch / 2f,
            cxp + l.wN * cw / 2f, cyp + l.hN * ch / 2f)
        return tmpRect
    }

    /**
     * The layer's VISIBLE picture (box ∩ drawn frame, via Compositor.chromeRect
     * — the same formula the renderer uses). A FIT layer that is pillarboxed
     * inside its box gets its frame around the picture, not the dead space,
     * so the border always sits on what the user actually sees. Falls back to
     * the box while no frame has been decoded yet (stays grabbable).
     */
    private val chromeTmp = RectF()
    private fun chromeRectOf(l: Layer): RectF {
        val hp = host
        Compositor.chromeRect(l, hp?.bitmapOf(l), cw, ch, chromeTmp)
        return chromeTmp
    }

    /** longest side (px) of the visible frame — the decode target the engine needs */
    fun visibleFrameMaxPx(l: Layer): Int {
        val r = chromeRectOf(l)
        return maxOf(r.width(), r.height()).toInt().coerceAtLeast(1)
    }

    /**
     * Polished selection frame — aligned to studio tokens.
     *
     * - Accent: UI.ACCENT for live, UI.FG2 dashed for locked.
     * - Contrast underlay 4.5dp black 150 alpha so frame reads on white canvas.
     * - Handles: 5.5dp, corner squares, edge pills, white 1.2dp rim + shadow.
     * - Rotation knob 18dp above top, line 1.5dp accent, dot white inner.
     * - Label pill: 10dp radius, bg 215 alpha 18,20,26, edge accent 1dp,
     *   text white 10sp Bold, LIVE prefix + name + LOCKED state, kept inside canvas.
     */
    private fun drawChrome(canvas: Canvas, l: Layer) {
        val r = RectF(chromeRectOf(l))
        val locked = l.locked
        val accent = if (locked) UI.FG2 else UI.ACCENT
        canvas.save()
        canvas.rotate(l.rotDeg, r.centerX(), r.centerY())

        // 1. contrast underlay
        chrome.style = Paint.Style.STROKE
        chrome.pathEffect = null
        chrome.color = Color.argb(150, 0, 0, 0)
        chrome.strokeWidth = UI.dpf(context, if (locked) 3f else 4.5f)
        canvas.drawRect(r, chrome)
        // 2. accent frame
        chrome.color = accent
        chrome.strokeWidth = UI.dpf(context, if (locked) 1.5f else 2.5f)
        if (locked) chrome.pathEffect = android.graphics.DashPathEffect(
            floatArrayOf(UI.dpf(context, 6f), UI.dpf(context, 4f)), 0f)
        canvas.drawRect(r, chrome)
        chrome.pathEffect = null

        val h = UI.dpf(context, 5.5f)
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = UI.dpf(context, 1.2f)
        }
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 0, 0, 0) }
        if (!locked) {
            chromeFill.color = accent
            val rr = UI.dpf(context, 1.5f)
            for (i in 0..2) {
                for (j in 0..2) {
                    if (i == 1 && j == 1) continue
                    val ex = if (j == 0) r.left else if (j == 2) r.right else r.centerX()
                    val ey = if (i == 0) r.top else if (i == 2) r.bottom else r.centerY()
                    val corner = (i != 1) && (j != 1)
                    val hw = if (corner || i == 1) h else h * 1.6f
                    val hh = if (corner || j == 1) h else h * 1.6f
                    val ew = if (corner) hw else if (i == 1) h * 0.6f else hw
                    val eh = if (corner) hh else if (j == 1) h * 0.6f else hh
                    tmpRect.set(ex - ew, ey - eh, ex + ew, ey + eh)
                    tmpRect.offset(0f, UI.dpf(context, 1f))
                    canvas.drawRoundRect(tmpRect, rr, rr, shadowPaint)
                    tmpRect.offset(0f, -UI.dpf(context, 1f))
                    canvas.drawRoundRect(tmpRect, rr, rr, chromeFill)
                    canvas.drawRoundRect(tmpRect, rr, rr, borderPaint)
                }
            }
            val topY = r.top - UI.dpf(context, 18f)
            val cx = r.centerX()
            chrome.strokeWidth = UI.dpf(context, 1.5f)
            chrome.color = accent
            canvas.drawLine(cx, r.top, cx, topY, chrome)
            chromeFill.color = accent
            canvas.drawCircle(cx, topY + UI.dpf(context, 1f), h * 1.35f, shadowPaint)
            canvas.drawCircle(cx, topY, h * 1.35f, chromeFill)
            canvas.drawCircle(cx, topY, h * 1.35f, borderPaint)
            chromeFill.color = Color.WHITE
            canvas.drawCircle(cx, topY, h * 0.55f, chromeFill)
        }

        // label pill — text + LIVE + LOCKED, never color-only
        val state = if (locked) "  LOCKED" else ""
        val label = (if (l.isLive()) "LIVE  " else "") + l.name.ifBlank { l.type.label } + state
        val padH = UI.dpf(context, 10f)
        val padV = UI.dpf(context, 4f)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = UI.dpf(context, 10.5f)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            letterSpacing = 0.02f
        }
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 18, 20, 26) }
        val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent; style = Paint.Style.STROKE; strokeWidth = UI.dpf(context, 1f)
        }
        val tw = textPaint.measureText(label)
        val th = textPaint.textSize
        val bw = tw + padH * 2
        val bh = th + padV * 2 + UI.dpf(context, 2f)
        val cx = r.centerX()
        val bx = cx - bw / 2
        val knobTop = if (locked) r.top - UI.dpf(context, 6f) else r.top - UI.dpf(context, 18f) - h * 1.8f
        var by = knobTop - bh - UI.dpf(context, 4f)
        if (by < UI.dpf(context, 4f)) by = r.top + UI.dpf(context, 8f)
        val rr2 = UI.dpf(context, 12f)
        // shadow under pill
        val shadowPill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(80, 0, 0, 0) }
        canvas.drawRoundRect(bx, by + UI.dpf(context, 1f), bx + bw, by + bh + UI.dpf(context, 1f), rr2, rr2, shadowPill)
        canvas.drawRoundRect(bx, by, bx + bw, by + bh, rr2, rr2, bgPaint)
        canvas.drawRoundRect(bx, by, bx + bw, by + bh, rr2, rr2, edgePaint)
        canvas.drawText(label, bx + padH, by + bh - padV - UI.dpf(context, 1.5f), textPaint)
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
        val r = chromeRectOf(l)   // handles sit on the visible frame
        val halfW = r.width() / 2f
        val halfH = r.height() / 2f
        val (px, py) = toLayerLocal(x, y, l, r)
        // Finger target: 24dp on normal layers, shrinking for small PiPs so a
        // tiny layer is not nothing-but-handles (the "cannot drag the PiP"
        // bug), with a 10dp floor so handles stay grabbable.
        val touch = min(UI.dpf(context, 24f), min(halfW, halfH) * 0.9f)
            .coerceAtLeast(UI.dpf(context, 10f))
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
    /** topmost-first, rotation-aware, hidden skipped — see LayerFit.hitTest */
    private fun layerAt(x: Float, y: Float): Layer? {
        val hp = host ?: return null
        return LayerFit.hitTest(hp.project.layers, x, y, cw, ch)
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
                    if (hit.locked) {
                        mode = Mode.NONE; startLayerId = null
                        hp.onLockedTap(hit)
                        return true
                    }
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
                    // the undo base stays the finger-DOWN state from
                    // startGesture: move-then-pinch is a single undo step.
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
                            if (hypot(x - downX, y - downY) > UI.dpf(context, 3f)) touchMoved(l)
                        }
                    }
                    Mode.PINCH -> if (e.pointerCount >= 2) { pinchMove(l, e); touchMoved(l) }
                    Mode.CORNER, Mode.EDGE -> { resizeTo(l, x, y); touchMoved(l) }
                    Mode.ROTATE -> { rotateTo(l, x, y); touchMoved(l) }
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
                    // clean second tap on a layer: text edits itself, media ignores
                    // (double-tap-hide was removed: too easy to trigger by accident)
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

    /** remember the finger-DOWN state so one undo step restores the gesture */
    private fun captureUndoBase(l: Layer) {
        undoCx = l.cx; undoCy = l.cy
        undoWN = l.wN; undoHN = l.hN
        undoRot = l.rotDeg; undoFit = l.fit
    }

    private fun startGesture(l: Layer, m: String) {
        captureUndoBase(l)
        resizePrimed = false
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

    /**
     * One undo snapshot per gesture, taken on the first real movement — of the
     * finger-DOWN state, not the already-moved one. The mutation for this move
     * event is already applied when this runs, so the pre-gesture values are
     * briefly restored, snapshotted through [Host.onChanged], then re-applied.
     * Without the swap, undo would restore "one move event in" — invisible for
     * a 2 px drag, but a stuck box + fit mode after a stretch resize (whose
     * first step collapses a letterboxed box onto its picture AND flips the
     * fit mode). Only this layer can have changed mid-gesture, so restoring
     * its six fields is a complete pre-gesture state.
     */
    private fun touchMoved(l: Layer) {
        if (!undoPushed) {
            undoPushed = true
            val cx = l.cx; val cy = l.cy
            val wn = l.wN; val hn = l.hN
            val rot = l.rotDeg; val fit = l.fit
            l.cx = undoCx; l.cy = undoCy
            l.wN = undoWN; l.hN = undoHN
            l.rotDeg = undoRot; l.fit = undoFit
            host?.onChanged()
            l.cx = cx; l.cy = cy
            l.wN = wn; l.hN = hn
            l.rotDeg = rot; l.fit = fit
        }
        moved = true
        host?.onTransform()
    }

    /**
     * True handle dragging (UI Plan2 Rule 9): the grabbed corner/edge follows
     * the finger while the OPPOSITE side stays anchored, in the layer's own
     * rotated frame — and the PICTURE follows the box, not just the box the
     * finger. No aspect lock — every source type stretches freely.
     *
     * EDGE (all 4 dirs): stretch ONLY that side. Left/right change width;
     * top/bottom change height. The other three sides do not move.
     * CORNER: stretch the whole frame (width AND height independently).
     * The opposite corner stays put.
     *
     * Two things make the picture (not just the box) obey the finger:
     *
     * 1. Letterbox collapse. Handles sit on the VISIBLE picture
     *    ([Compositor.chromeRect]) but the math below is box math. On a
     *    letterboxed FIT layer the box is bigger than the picture, so the
     *    first step shrinks the (invisible) dead space away: box := picture.
     *    The chrome is concentric with the box, so nothing on screen moves —
     *    the border, the handles and the picture are pixel-identical — and
     *    from then on picture edge == box edge == finger.
     * 2. Auto-stretch. Fit and Fill both keep the source aspect, so a wider
     *    box alone would only widen dead space (Fit) or re-crop (Fill) —
     *    dragging a side would look like nothing happened. When the new box
     *    aspect leaves the source aspect (>2 %), the layer flips to STRETCH
     *    (picture == box, squashed on mismatch). A uniform corner drag keeps
     *    the aspect and keeps the mode. Text has no picture-in-box mode and
     *    never flips. The Fit control cycles back to Fit/Fill at any time.
     */
    private fun resizeTo(l: Layer, x: Float, y: Float) {
        if (!resizePrimed) {
            resizePrimed = true
            collapseLetterbox(l)
        }
        val startWpx = (startWN * cw).coerceAtLeast(1f)
        val startHpx = (startHN * ch).coerceAtLeast(1f)
        val cx0 = startCx * cw
        val cy0 = startCy * ch
        val ang = Math.toRadians(l.rotDeg.toDouble())
        val ca = cos(ang).toFloat(); val sa = sin(ang).toFloat()

        // anchor = the side / corner opposite the grabbed handle
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
        // hsx==0 → vertical edge: keep start width. hsy==0 → horizontal edge:
        // keep start height. Both set → corner: both axes follow the finger.
        var newW = if (hsx != 0f) abs(px).coerceAtLeast(minPx) else startWpx
        var newH = if (hsy != 0f) abs(py).coerceAtLeast(minPx) else startHpx
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

        // the picture follows the box: leave Fit/Fill for Stretch once the
        // dragged box no longer matches the source aspect (a side always
        // does; a uniform corner never does). Tap jitter on a handle must
        // not flip the mode, so either axis has to move >0.5 % first.
        if (!l.isText() && l.fit != Layer.FIT_STRETCH) {
            val (effW, effH) = LayerFit.effective(l.srcW, l.srcH, l.srcRotation)
            if (effW > 0 && effH > 0 && newW > 0f && newH > 0f) {
                val dragged = abs(newW / startWpx - 1f) > 0.005f ||
                        abs(newH / startHpx - 1f) > 0.005f
                if (dragged) {
                    val boxAspect = newW / newH
                    val srcAspect = effW.toFloat() / effH
                    if (abs(boxAspect / srcAspect - 1f) > 0.02f) l.fit = Layer.FIT_STRETCH
                }
            }
        }
    }

    /**
     * First resize step on a letterboxed FIT layer: shrink the box onto the
     * visible picture (same centre — the chrome is concentric with the box)
     * and re-base the gesture math onto it. COVER/STRETCH/text layers already
     * have chrome == box, and a layer with no decoded frame yet falls back to
     * the box too, so all of those are a no-op here.
     */
    private fun collapseLetterbox(l: Layer) {
        if (l.isText()) return
        val bmp = host?.bitmapOf(l) ?: return
        val cr = RectF()
        Compositor.chromeRect(l, bmp, cw, ch, cr)
        val boxW = l.wN * cw
        val boxH = l.hN * ch
        if (abs(cr.width() - boxW) <= 0.5f && abs(cr.height() - boxH) <= 0.5f) return
        l.wN = cr.width() / cw
        l.hN = cr.height() / ch
        l.cx = cr.centerX() / cw
        l.cy = cr.centerY() / ch
        startWN = l.wN; startHN = l.hN
        startCx = l.cx; startCy = l.cy
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
