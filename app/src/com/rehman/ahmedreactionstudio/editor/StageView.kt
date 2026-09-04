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
import com.rehman.ahmedreactionstudio.core.Project
import com.rehman.ahmedreactionstudio.util.UI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * The compositor viewport: renders the composition with the same
 * Compositor used by the exporter and handles the PiP gestures
 * (select / move / 8-handle resize / rotate / pinch / snap).
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
    }

    var host: Host? = null

    var stageW = 1
        private set
    var stageH = 1
        private set

    private val ctxC = Compositor.Ctx()
    private val chrome = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chromeFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dim = Paint()

    private enum class Mode { NONE, MOVE, CORNER, EDGE, ROTATE, PINCH }
    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var startLayerId: String? = null
    private var startCx = 0f; private var startCy = 0f
    private var startWN = 0f; private var startHN = 0f; private var startRot = 0f
    private var startDist = 0f
    private var startAngle = 0f
    private var startCX = 0f; private var startCY = 0f
    private var moved = false

    override fun onMeasure(w: Int, h: Int) {
        val pw = MeasureSpec.getSize(w)
        val ph = MeasureSpec.getSize(h)
        val p = host?.project ?: run { super.onMeasure(w, h); return }
        val arW = p.aspect.canvasW.toFloat()
        val arH = p.aspect.canvasH.toFloat()
        var sw = pw.toFloat()
        var sh = sw * arH / arW
        if (sh > ph) { sh = ph.toFloat(); sw = sh * arW / arH }
        setMeasuredDimension(sw.toInt(), sh.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        stageW = w.coerceAtLeast(1)
        stageH = h.coerceAtLeast(1)
    }

    fun refresh() { invalidate() }

    // ----- unit mapping -----
    private fun nx(px: Float): Float = px / stageW
    private fun ny(py: Float): Float = py / stageH

    override fun onDraw(canvas: Canvas) {
        val hp = host ?: return
        val p = hp.project
        Compositor.draw(ctxC, canvas, stageW, stageH, p, { hp.bitmapOf(it) }, 0L,
            hp.selectedId(), emptyMap())
        hp.selectedId()?.let { selId ->
            val l = p.layerById(selId) ?: return
            drawChrome(canvas, l)
        }
    }

    private fun rectOf(l: Layer): RectF {
        val cxp = l.cx * stageW
        val cyp = l.cy * stageH
        val r = RectF()
        r.set(cxp - l.wN * stageW / 2f, cyp - l.hN * stageH / 2f,
            cxp + l.wN * stageW / 2f, cyp + l.hN * stageH / 2f)
        return r
    }

    private fun drawChrome(canvas: Canvas, l: Layer) {
        val r = rectOf(l)
        chrome.color = if (l.locked) UI.FG2 else UI.ACCENT
        chrome.style = Paint.Style.STROKE
        chrome.strokeWidth = UI.dpf(context, if (l.locked) 1f else 2f)
        canvas.save()
        canvas.rotate(l.rotDeg, r.centerX(), r.centerY())
        canvas.drawRect(r, chrome)
        val h = UI.dp(context, 9)
        chromeFill.color = if (l.locked) UI.BG3 else UI.ACCENT
        // 4 corners + 4 edges = 8 handles (spec 7.2)
        val hs = floatArrayOf(-1f, 0f, 1f)
        for (i in 0..2) {
            for (j in 0..2) {
                if (i == 1 && j == 1) continue
                val ex = if (j == 0) r.left else if (j == 2) r.right else r.centerX()
                val ey = if (i == 0) r.top else if (i == 2) r.bottom else r.centerY()
                canvas.drawRect(ex - h, ey - h, ex + h, ey + h, chromeFill)
            }
        }
        // rotation handle above top-center
        val topY = r.top - UI.dp(context, 18)
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

    // ---------- gestures ----------

    private fun hitHandle(x: Float, y: Float, l: Layer): String? {
        val r = rectOf(l)
        // inverse-rotate the point into local space
        val ang = Math.toRadians((-l.rotDeg).toDouble())
        val cxp = r.centerX(); val cyp = r.centerY()
        val dx = x - cxp; val dy = y - cyp
        val lx = (dx * cos(ang) - dy * sin(ang)).toFloat()
        val ly = (dx * sin(ang) + dy * cos(ang)).toFloat()
        val h = UI.dp(context, 22).toFloat() // generous hit area (spec 7.3)
        // rotate knob above top
        val knobY = r.top - cyp - UI.dp(context, 18)
        if (abs(lx) < h && abs(ly - knobY) < h) return "ROT"
        val lx2 = lx + cxp; val ly2 = ly + cyp // no: local coords already relative to center... see below
        return handleAt(r, lx + cxp, ly + cyp, h)
    }

    private fun handleAt(r: RectF, x: Float, y: Float, h: Float): String? {
        fun near(a: Float, b: Float) = abs(a - b) <= h
        // corners
        if (near(x, r.left) && near(y, r.top)) return "TL"
        if (near(x, r.left) && near(y, r.bottom)) return "BL"
        if (near(x, r.right) && near(y, r.top)) return "TR"
        if (near(x, r.right) && near(y, r.bottom)) return "BR"
        if (near(x, r.centerX()) && near(y, r.top)) return "TC"
        if (near(x, r.centerX()) && near(y, r.bottom)) return "BC"
        if (near(x, r.left) && near(y, r.centerY())) return "ML"
        if (near(x, r.right) && near(y, r.centerY())) return "MR"
        return null
    }

    /** returns top-most layer whose box contains the point (local) */
    private fun layerAt(x: Float, y: Float): Layer? {
        val hp = host ?: return null
        val p = hp.project
        for (i in p.layers.indices.reversed()) {
            val l = p.layers[i]
            if (!l.visible || l.opacity <= 0.01f) continue
            val r = rectOf(l)
            val ang = Math.toRadians((-l.rotDeg).toDouble())
            val dx = x - r.centerX(); val dy = y - r.centerY()
            val lx = (dx * cos(ang) - dy * sin(ang)).toFloat()
            val ly = (dx * sin(ang) + dy * cos(ang)).toFloat()
            if (abs(lx) <= r.width() / 2f && abs(ly) <= r.height() / 2f) return l
        }
        return null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val hp = host ?: return false
        val p = hp.project
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                moved = false
                downX = e.x; downY = e.y
                val selId = hp.selectedId()
                val sel = selId?.let { p.layerById(it) }
                // rotation / resize handles on the current selection first
                if (sel != null && !sel.locked) {
                    val handle = hitHandle(e.x, e.y, sel)
                    if (handle != null) {
                        startGesture(sel, handle)
                        return true
                    }
                }
                val hit = layerAt(e.x, e.y)
                if (hit != null) {
                    if (hit.id != selId) { hp.select(hit.id); }
                    if (hit.locked) return true
                    startGesture(hit, "MOVE")
                } else {
                    hp.onTapEmpty()
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (mode == Mode.MOVE && e.pointerCount == 2) {
                    // switch to pinch
                    mode = Mode.PINCH
                    val x0 = e.getX(0); val y0 = e.getY(0)
                    val x1 = e.getX(1); val y1 = e.getY(1)
                    startDist = hypot(x1 - x0, y1 - y0).coerceAtLeast(1f)
                    startAngle = atan2(y1 - y0, x1 - x0)
                    val midX = (x0 + x1) / 2f; val midY = (y0 + y1) / 2f
                    startCX = midX; startCY = midY
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.NONE) return true
                val l = startLayerId?.let { p.layerById(it) } ?: return true
                if (mode == Mode.MOVE) {
                    var dx = nx(e.x - downX)
                    var dy = ny(e.y - downY)
                    l.cx = (startCx + dx).coerceIn(0f, 1f)
                    l.cy = (startCy + dy).coerceIn(0f, 1f)
                    snapMove(l)
                    if (hypot(e.x - downX, e.y - downY) > UI.dpf(context, 4f)) moved = true
                } else if (mode == Mode.PINCH && e.pointerCount == 2) {
                    val x0 = e.getX(0); val y0 = e.getY(0)
                    val x1 = e.getX(1); val y1 = e.getY(1)
                    val dist = hypot(x1 - x0, y1 - y0).coerceAtLeast(1f)
                    val angle = atan2(y1 - y0, x1 - x0)
                    val sc = dist / startDist
                    val rotDelta = Math.toDegrees((angle - startAngle).toDouble()).toFloat()
                    applyScaleFromCenter(l, sc, 1f)
                    l.rotDeg = (startRot + rotDelta) % 360f
                } else if (mode == Mode.CORNER || mode == Mode.EDGE) {
                    resizeHandle(l, e.x, e.y)
                } else if (mode == Mode.ROTATE) {
                    val cx = l.cx * stageW; val cy = l.cy * stageH
                    val a0 = atan2(downY - cy, downX - cx)
                    val a1 = atan2(e.y - cy, e.x - cx)
                    l.rotDeg = (startRot + Math.toDegrees((a1 - a0).toDouble()).toFloat()) % 360f
                    if (hypot(e.x - downX, e.y - downY) > UI.dpf(context, 4f)) moved = true
                }
                hp.onTransform()
                invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == Mode.PINCH && e.pointerCount <= 2) {
                    // fall back to move when one finger lifts
                    mode = Mode.MOVE
                    downX = e.getX(if (e.actionIndex == 0) 1 else 0)
                    downY = e.getY(if (e.actionIndex == 0) 1 else 0)
                    val l = startLayerId?.let { p.layerById(it) } ?: return true
                    startCx = l.cx; startCy = l.cy
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.MOVE && !moved) {
                    hp.onTransform() // still register tap-move as save-worthy
                }
                mode = Mode.NONE
                startLayerId = null
                return true
            }
        }
        return true
    }

    private fun startGesture(l: Layer, m: String) {
        mode = when (m) {
            "MOVE" -> Mode.MOVE
            "ROT" -> Mode.ROTATE
            "TL", "TR", "BL", "BR" -> Mode.CORNER
            else -> Mode.EDGE
        }
        startLayerId = l.id
        startCx = l.cx; startCy = l.cy
        startWN = l.wN; startHN = l.hN
        startRot = l.rotDeg
        host?.onChanged()
    }

    private fun applyScaleFromCenter(l: Layer, scW: Float, scH: Float) {
        l.wN = (startWN * scW).coerceIn(0.02f, 1.6f)
        l.hN = (startHN * scH).coerceIn(0.02f, 1.6f)
    }

    private fun resizeHandle(l: Layer, x: Float, y: Float) {
        // resize from center using pointer distance ratio (predictable + robust)
        val cxP = l.cx * stageW
        val cyP = l.cy * stageH
        val d0 = hypot(downX - cxP, downY - cyP).coerceAtLeast(1f)
        val d1 = hypot(x - cxP, y - cyP).coerceAtLeast(1f)
        when (mode) {
            Mode.CORNER -> applyScaleFromCenter(l, d1 / d0, d1 / d0)
            Mode.EDGE -> {
                // decide axis by which handle
                val ddx = x - downX; val ddy = y - downY
                val l0 = startLayerId?.let { host?.project?.layerById(it) } ?: return
                // simple: if handle was top/bottom scale height, else width (approx by distance direction)
                val vertical = abs(ddy) > abs(ddx)
                if (vertical) applyScaleFromCenter(l, 1f, d1 / d0)
                else applyScaleFromCenter(l, d1 / d0, 1f)
            }
            else -> { }
        }
        if (hypot(x - downX, y - downY) > UI.dpf(context, 3f)) moved = true
    }

    /** Snap layer center / edges to canvas center, canvas edges and sibling centers. */
    private fun snapMove(l: Layer) {
        val th = UI.dpf(context, 5f)
        fun snapOne(v: Float, targets: List<Float>): Float? {
            for (t in targets) if (abs(v - t) <= th) return t
            return null
        }
        val halfW = l.wN / 2f
        val halfH = l.hN / 2f
        l.cx = snapOne(l.cx, listOf(0.5f)) ?: l.cx
        l.cy = snapOne(l.cy, listOf(0.5f)) ?: l.cy
        val lefts = ArrayList<Float>(); val rights = ArrayList<Float>()
        val tops = ArrayList<Float>(); val bottoms = ArrayList<Float>()
        for (o in host?.project?.layers ?: emptyList()) {
            if (o.id == l.id) continue
            lefts.add(o.cx - o.wN / 2f); rights.add(o.cx + o.wN / 2f)
            tops.add(o.cy - o.hN / 2f); bottoms.add(o.cy + o.hN / 2f)
        }
        l.cx = snapOne(l.cx - halfW, lefts)?.let { it + halfW } ?: l.cx
        l.cx = snapOne(l.cx + halfW, rights)?.let { it - halfW } ?: l.cx
        l.cy = snapOne(l.cy - halfH, tops)?.let { it + halfH } ?: l.cy
        l.cy = snapOne(l.cy + halfH, bottoms)?.let { it - halfH } ?: l.cy
    }
}
