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
import kotlin.math.max
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
 * STEP 2 — selection chrome belongs to the SOURCE, not to the canvas.
 * Exactly ONE source is selected (host.selectedId()); it gets the strong
 * orange border + 8 handles + rotate knob + label, drawn around
 * `Compositor.chromeRect` — the layer's EXACT visible bounds under its own
 * position / size / fit / rotation — never around the box's dead letterbox
 * space and never around the whole canvas. Unselected sources keep only a
 * very subtle outline. The chrome lives in this view's onDraw (the same pass
 * that composites the frame), so it stays glued to the picture while video
 * plays, while the canvas resizes for 16:9 / 9:16 / 1:1, and while another
 * source is added — with zero extra Android Views created per frame.
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

    /**
     * STEP 1 — chrome reserved at the top/bottom of this view (toolbar,
     * bottom sheet, quick bar, status chips), in this view's pixels.
     * The canvas is contain-fitted in the REMAINING region, so chrome can
     * never cover or crop it. Set by the host on every layout pass.
     */
    private var chromeTopPx = 0f
    private var chromeBottomPx = 0f

    fun setChromeInsets(topPx: Float, bottomPx: Float) {
        val ct = topPx.coerceAtLeast(0f)
        val cb = bottomPx.coerceAtLeast(0f)
        if (ct == chromeTopPx && cb == chromeBottomPx) return
        chromeTopPx = ct
        chromeBottomPx = cb
        layoutCanvas()
        invalidate()
    }

    /** canvas size in view pixels (used to size preview decoding) */
    val canvasW: Int get() { layoutCanvas(); return cw }
    val canvasH: Int get() { layoutCanvas(); return ch }

    private val ctxC = Compositor.Ctx()
    private val chrome = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chromeFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val subtle = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handleBorder = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tmpRect = RectF()

    /**
     * The EXACT visible bounds of a layer, in canvas-local px — the same rect
     * the compositor scales/clips the picture into (`Compositor.chromeRect`).
     * Selection border, handles and hit-testing all use this one rect, so
     * the frame belongs to the SOURCE: it follows position / size / scale /
     * rotation, it never surrounds dead letterbox space, and it never
     * accidentally becomes a "frame around the whole canvas" for a
     * letterboxed main-canvas source (the classic portrait-camera-on-16:9
     * case). For COVER/text sources chrome == box, so ordinary resizing and
     * tapping behave exactly as they always did.
     */
    private fun chromeRectOf(l: Layer): RectF {
        layoutCanvas()
        val hp = host
        Compositor.chromeRect(l, if (hp != null) hp.bitmapOf(l) else null, cw, ch, tmpRect)
        return tmpRect
    }

    /**
     * Longest side, in canvas px, of the layer's VISIBLE frame. The host uses
     * this to size preview decoding to what is actually DRAWN — a
     * pillarboxed main-camera strip no longer pays for full-canvas decode.
     */
    fun visibleFrameMaxPx(l: Layer): Int {
        val r = chromeRectOf(l)
        return max(r.width(), r.height()).roundToInt().coerceAtLeast(1)
    }

    private enum class Mode { NONE, MOVE, CORNER, EDGE, ROTATE, PINCH }
    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var startLayerId: String? = null
    private var startCx = 0f; private var startCy = 0f
    private var startWN = 0f; private var startHN = 0f; private var startRot = 0f
    /** resize runs in the VISIBLE-frame space; these map it back to the box */
    private var startBoxWN = 1f; private var startBoxHN = 1f
    private var startRX = 1f; private var startRY = 1f
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
     * STEP 1 — contain-fit the project aspect inside the VISIBLE region of
     * this view (viewport minus chrome insets), centred:
     * scale = min(availW / canvasW, availH / canvasH).
     * The whole canvas always fits; chrome can never crop or cover it.
     */
    private fun layoutCanvas() {
        val p = host?.project ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 1f || vh <= 1f) return
        val r = ViewportFit.fit(vw, vh, chromeTopPx, chromeBottomPx,
            p.aspect.canvasW, p.aspect.canvasH)
        canvasRect.set(r.l, r.t, r.r, r.b)
        cw = r.w.roundToInt().coerceAtLeast(1)
        ch = r.h.roundToInt().coerceAtLeast(1)
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
        // Unselected sources: at most a very subtle outline around their own
        // visible bounds (a tap affordance) — never the strong editing frame.
        // Full-bleed backgrounds are skipped: the exported-area line IS their
        // outline, and drawing another one at the canvas edge only adds noise.
        subtle.strokeWidth = UI.dpf(context, 1f)
        for (o in p.layers) {
            if (o.id == selId || !o.visible || o.opacity <= 0.01f) continue
            val cr = chromeRectOf(o)
            if (LayerFit.isFullBleed(o) && cr.width() >= cw - 1f &&
                cr.height() >= ch - 1f) continue
            subtle.color = if (o.locked) Color.argb(72, 160, 166, 180)
                           else Color.argb(56, 255, 255, 255)
            canvas.save()
            if (abs(o.rotDeg) > 0.01f)
                canvas.rotate(o.rotDeg, cr.centerX(), cr.centerY())
            canvas.drawRect(cr, subtle)
            canvas.restore()
        }
        // Exactly ONE source owns the strong orange editing frame
        val l = selId?.let { p.layerById(it) }
        if (l != null) drawChrome(canvas, l)
        canvas.restore()
    }

    /**
     * The editing frame of ONE source: drawn around `chromeRectOf` — the
     * exact visible bounds under the layer's own position/size/fit/rotation —
     * and everything else about it (8 transform handles, rotate knob, label)
     * hangs off the same rect, so grabbing a drawn handle always grabs what
     * this frame is made of. Paints are fields: onDraw runs per video frame
     * while playing, so it must not allocate.
     */
    private fun drawChrome(canvas: Canvas, l: Layer) {
        val r = RectF(chromeRectOf(l))
        val selAccent = if (l.locked) UI.FG2 else UI.ACCENT
        // outer glow for visibility on both dark and light canvas backgrounds
        chrome.color = Color.argb(60, Color.red(selAccent), Color.green(selAccent), Color.blue(selAccent))
        chrome.style = Paint.Style.STROKE
        chrome.strokeWidth = UI.dpf(context, if (l.locked) 6f else 7f)
        canvas.save()
        canvas.rotate(l.rotDeg, r.centerX(), r.centerY())
        canvas.drawRect(r, chrome)
        chrome.color = selAccent
        chrome.strokeWidth = UI.dpf(context, if (l.locked) 1f else 2f)
        canvas.drawRect(r, chrome)
        val h = UI.dpf(context, 9f)
        // handles with white border so they pop on any background
        handleBorder.style = Paint.Style.STROKE
        handleBorder.color = Color.WHITE
        handleBorder.strokeWidth = UI.dpf(context, 1.2f)
        chromeFill.color = if (l.locked) UI.BG3 else selAccent
        for (i in 0..2) {
            for (j in 0..2) {
                if (i == 1 && j == 1) continue
                val ex = if (j == 0) r.left else if (j == 2) r.right else r.centerX()
                val ey = if (i == 0) r.top else if (i == 2) r.bottom else r.centerY()
                canvas.drawRect(ex - h, ey - h, ex + h, ey + h, chromeFill)
                canvas.drawRect(ex - h, ey - h, ex + h, ey + h, handleBorder)
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
        canvas.drawCircle(cx, topY, h * 0.9f, handleBorder)
        chromeFill.color = Color.WHITE
        canvas.drawCircle(cx, topY, h * 0.45f, chromeFill)
        // selection label pill (type + name) above the chrome so you always know what is selected
        val label = (if (l.isLive()) "LIVE  " else "") + l.name.ifBlank { l.type.label }
        val padH = UI.dpf(context, 8f)
        val padV = UI.dpf(context, 3f)
        labelText.color = Color.WHITE
        labelText.textSize = UI.dpf(context, 10f)
        val tw = labelText.measureText(label)
        val th = labelText.textSize
        val bw = tw + padH * 2
        val bh = th + padV * 2 + UI.dpf(context, 2f)
        val bx = cx - bw / 2
        val by = topY - h * 1.8f - bh
        val rr = UI.dpf(context, 10f)
        labelBg.color = Color.argb(210, 18, 20, 26)
        canvas.drawRoundRect(bx, by, bx + bw, by + bh, rr, rr, labelBg)
        canvas.drawText(label, bx + padH, by + bh - padV - UI.dpf(context, 1f), labelText)
        if (l.locked) {
            labelText.color = UI.ACCENT2
            labelText.textSize = UI.dpf(context, 9f)
            canvas.drawText("🔒", bx + bw + UI.dpf(context, 4f), by + bh - padV, labelText)
        }
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

    /**
     * Handle hit-testing runs on the SAME rect the chrome is drawn on — the
     * layer's visible frame — so a drawn handle is always a grabbable handle.
     */
    private fun hitHandle(x: Float, y: Float, l: Layer): String? {
        val r = RectF(chromeRectOf(l))
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

    /**
     * Returns top-most layer whose VISIBLE frame contains the point
     * (canvas-local). Tapping selects the picture you see: the letterboxed
     * sides of a `fit` source belong to the layer behind it, and a
     * full-bleed source still owns the whole canvas exactly as before.
     */
    private fun layerAt(x: Float, y: Float): Layer? {
        val hp = host ?: return null
        val p = hp.project
        if (x < 0f || y < 0f || x > cw || y > ch) return null
        for (i in p.layers.indices.reversed()) {
            val l = p.layers[i]
            if (!l.visible || l.opacity <= 0.01f) continue
            val r = chromeRectOf(l)
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

    private fun startGesture(l: Layer, m: String) {
        mode = when (m) {
            "MOVE" -> Mode.MOVE
            "ROT" -> Mode.ROTATE
            "TL", "TR", "BL", "BR" -> Mode.CORNER
            else -> Mode.EDGE
        }
        // the gesture re-anchors to the handle EXACTLY where it was drawn
        val r = RectF(chromeRectOf(l))
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
        startRot = l.rotDeg
        // Resize runs in the VISIBLE-frame space (where the border and its
        // handles are) and maps back to the box by the box/frame ratio
        // captured at gesture start. For COVER/text/aspect-matched sources
        // frame == box, so this reproduces the classic math exactly.
        startBoxWN = l.wN; startBoxHN = l.hN
        startWN = r.width() / cw; startHN = r.height() / ch
        startRX = if (startWN > 0.001f) (startBoxWN / startWN).coerceAtLeast(1f) else 1f
        startRY = if (startHN > 0.001f) (startBoxHN / startHN).coerceAtLeast(1f) else 1f
    }

    /** one undo snapshot per gesture, taken on the first real movement */
    private fun touchMoved() {
        if (!undoPushed) { undoPushed = true; host?.onChanged() }
        moved = true
        host?.onTransform()
    }

    /**
     * True handle dragging: the grabbed corner/edge of the layer's VISIBLE
     * frame follows the finger while the OPPOSITE side stays anchored, in the
     * layer's own rotated frame — the same rect the border is drawn on, so
     * the border always tracks the drag.
     *
     * CORNER handles scale a media layer proportionally, so a camera PiP can
     * never be squashed. EDGE handles stretch ONLY that side — width or
     * height change independently and the box can be freely distorted — for
     * layers whose picture fills their box. For a letterboxed CONTAIN source
     * a pure side-stretch would change the box but NOT one drawn pixel, so
     * the border would appear frozen; there edge handles scale uniformly,
     * which is the only stretch a fit-mode frame can physically perform.
     */
    private fun resizeTo(l: Layer, x: Float, y: Float) {
        val startWpx = (startWN * cw).coerceAtLeast(1f)
        val startHpx = (startHN * ch).coerceAtLeast(1f)
        val cx0 = startCx * cw
        val cy0 = startCy * ch
        val ang = Math.toRadians(l.rotDeg.toDouble())
        val ca = cos(ang).toFloat(); val sa = sin(ang).toFloat()

        // anchor = the side opposite the grabbed handle (of the visible frame)
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
        val letterboxed = startRX > 1.02f || startRY > 1.02f
        if (!l.isText()) {
            if (hsx != 0f && hsy != 0f) {
                // Corner (both axes): keep the media aspect ratio
                val k = (newW / startWpx + newH / startHpx) / 2f
                newW = startWpx * k
                newH = startHpx * k
            } else if (letterboxed) {
                // Edge on a fit-mode source: uniform scale of the frame
                val k = if (hsx != 0f) newW / startWpx else newH / startHpx
                newW = startWpx * k
                newH = startHpx * k
            }
        }
        newW = newW.coerceIn(minPx, cw * MAX_BOX_N)
        newH = newH.coerceIn(minPx, ch * MAX_BOX_N)

        val sgx = if (px >= 0f) 1f else -1f
        val sgy = if (py >= 0f) 1f else -1f
        val mxLocal = if (hsx != 0f) sgx * newW / 2f else 0f
        val myLocal = if (hsy != 0f) sgy * newH / 2f else 0f
        // visible frame -> box: ratio 1 unless the source letterboxes inside
        // its box; the box absorbs the letterbox slack, the chrome the user
        // grabbed maps 1:1 to the finger.
        l.wN = (newW * startRX / cw).coerceAtMost(MAX_BOX_N)
        l.hN = (newH * startRY / ch).coerceAtMost(MAX_BOX_N)
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
