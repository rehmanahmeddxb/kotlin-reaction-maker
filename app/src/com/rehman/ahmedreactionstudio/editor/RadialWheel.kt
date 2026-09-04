package com.rehman.ahmedreactionstudio.editor

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.rehman.ahmedreactionstudio.R
import com.rehman.ahmedreactionstudio.util.UI
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * NESTED radial menu — the app's primary interface.
 *
 * The old wheel could show exactly one ring of per-source verbs and every tap
 * closed it, so "Sources / Add / Controls / Dock / Mixing / Canvas / Export"
 * were unreachable and a petal could never open anything. This is a full menu
 * system instead:
 *
 *   ◉ hub  ── petals fly out ──  tap a FOLDER petal  →  it becomes the new hub
 *                                                        and its sub-petals bloom
 *             tap a LEAF petal   →  the action runs
 *             tap a TOGGLE petal →  the action runs and the ring re-renders
 *                                   with the new state (stays open)
 *             tap the hub / Back →  pop one level
 *             tap the scrim      →  close everything
 *
 * Levels are built from lambdas, so every ring reads live project state at the
 * moment it is drawn — no stale petals. Rings page at 8 petals so a project
 * with 30 sources still fits on screen.
 */
class RadialMenuView(context: Context) : FrameLayout(context) {

    /**
     * One petal.
     *  - [submenu] non-null  → folder: opens a nested ring
     *  - [action] non-null   → leaf: performs the verb
     *  - [keepOpen]          → toggle: performs the verb, then redraws this ring
     */
    class Item(
        val icon: Int,
        val label: String,
        val active: Boolean = false,
        val danger: Boolean = false,
        val badge: String? = null,
        val submenu: (() -> Level)? = null,
        val keepOpen: Boolean = false,
        val action: (() -> Unit)? = null
    )

    /** One ring: a hub identity plus a live list of petals. */
    class Level(
        val icon: Int,
        val title: String,
        val subtitle: String = "",
        val items: () -> List<Item>
    )

    var onDismiss: (() -> Unit)? = null

    private val stack = ArrayList<Level>()
    private val pages = ArrayList<Int>()          // current page per stack level
    private var open = false
    private var dismissing = false
    private var cx = 0f
    private var cy = 0f
    private var anchorX = -1f
    private var anchorY = -1f

    private val ring = FrameLayout(context)
    private val scrim = View(context)

    companion object {
        /** petals per page before a "More…" petal appears */
        private const val PAGE = 8
    }

    init {
        visibility = View.GONE
        isClickable = true
    }

    fun isOpen(): Boolean = open

    fun depth(): Int = stack.size

    /** Open [root] as the first ring, blooming around ([ax], [ay]) (-1 = centre). */
    fun show(root: Level, ax: Float = -1f, ay: Float = -1f) {
        if (open) { dismiss(false) }
        stack.clear(); pages.clear()
        stack.add(root); pages.add(0)
        anchorX = ax; anchorY = ay
        open = true
        dismissing = false
        visibility = View.VISIBLE
        removeAllViews()

        scrim.setBackgroundColor(Color.argb(165, 4, 5, 8))
        scrim.alpha = 0f
        scrim.setOnClickListener { dismiss(true) }
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(ring, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        scrim.animate().alpha(1f).setDuration(200).start()

        val go = Runnable { render(fresh = true) }
        if (width > 0 && height > 0) post(go)
        else addOnLayoutChangeListener(object : OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int,
                                        ol: Int, ot: Int, or: Int, ob: Int) {
                removeOnLayoutChangeListener(this)
                post(go)
            }
        })
    }

    /** Push a nested ring (called when a folder petal is tapped). */
    private fun push(level: Level) {
        stack.add(level); pages.add(0)
        render(fresh = true)
    }

    /** Pop one ring; closes the menu when the root is popped. */
    fun pop(): Boolean {
        if (stack.size <= 1) { dismiss(true); return false }
        stack.removeAt(stack.size - 1)
        pages.removeAt(pages.size - 1)
        render(fresh = true)
        return true
    }

    /** Redraw the current ring in place (used by toggles so state stays live). */
    fun refresh() { if (open && stack.isNotEmpty()) render(fresh = false) }

    // ---------------- layout / animation ----------------

    private fun render(fresh: Boolean) {
        if (!open || stack.isEmpty()) return
        ring.removeAllViews()

        val dp = UI.dpf(context, 1f)
        val level = stack.last()
        val all = try { level.items() } catch (_: Exception) { emptyList() }

        // ---- paging: PAGE petals, plus a More… petal when there are extras ----
        val pageIdx = pages.last().coerceAtLeast(0)
        val pageCount = if (all.size <= PAGE) 1 else ceil(all.size / (PAGE - 1f)).toInt()
        val safePage = if (pageCount <= 1) 0 else pageIdx % pageCount
        val items: List<Item> = if (pageCount <= 1) all else {
            val per = PAGE - 1
            val from = safePage * per
            val slice = all.subList(from, minOf(from + per, all.size))
            slice + Item(R.drawable.ic_more,
                "More  ${safePage + 1}/$pageCount", keepOpen = true) {
                pages[pages.size - 1] = (safePage + 1) % pageCount
            }
        }

        val n = items.size.coerceAtLeast(1)
        val petalSize = (if (n > 8) 48f else 54f) * dp
        val hubSize = 78 * dp
        val minR = ((petalSize + 14 * dp) * n) / (2f * PI).toFloat()
        val radius = max(122f * dp, minR)

        // centre the ring, fully on screen
        val ext = radius + petalSize / 2f + 26 * dp
        var x = if (anchorX >= 0) anchorX else width / 2f
        var y = if (anchorY >= 0) anchorY else height / 2f
        x = x.coerceIn(ext, (width - ext).coerceAtLeast(ext))
        y = y.coerceIn(ext, (height - ext).coerceAtLeast(ext))
        cx = x; cy = y

        buildHub(level, hubSize, fresh)

        val startAngle = -PI / 2.0
        for ((i, it) in items.withIndex()) {
            val ang = startAngle + i * 2.0 * PI / n
            val px = cx + (radius * cos(ang)).toFloat()
            val py = cy + (radius * sin(ang)).toFloat()
            addPetal(it, px, py, petalSize, i, fresh)
        }
    }

    private fun buildHub(level: Level, hubSize: Float, fresh: Boolean) {
        val dp = UI.dpf(context, 1f)
        val size = hubSize.toInt()

        val hub = FrameLayout(context)
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(Color.argb(242, 16, 19, 26))
        g.setStroke(UI.dp(context, 2), if (stack.size > 1) UI.ACCENT2 else UI.ACCENT)
        hub.background = g
        ring.addView(hub, LayoutParams(size, size, Gravity.START or Gravity.TOP))
        hub.x = cx - size / 2f
        hub.y = cy - size / 2f

        val icon = ImageView(context)
        // deeper than the root: the hub is a BACK button
        icon.setImageDrawable(Ic.get(context,
            if (stack.size > 1) R.drawable.ic_back else level.icon,
            if (stack.size > 1) UI.ACCENT2 else UI.ACCENT))
        val pad = (hubSize * 0.28f).toInt()
        icon.setPadding(pad, pad, pad, pad)
        hub.addView(icon, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        hub.setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (stack.size > 1) pop() else dismiss(true)
        }

        // title under the hub
        val title = TextView(context)
        title.text = level.title
        title.textSize = 12.5f
        title.gravity = Gravity.CENTER
        title.maxLines = 1
        title.setTextColor(Color.WHITE)
        title.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        title.setShadowLayer(6f, 0f, 2f, Color.BLACK)
        ring.addView(title, LayoutParams((220 * dp).toInt(), LayoutParams.WRAP_CONTENT,
            Gravity.START or Gravity.TOP))
        title.x = cx - 110 * dp
        title.y = cy + hubSize / 2f + 7 * dp

        val sub = TextView(context)
        sub.text = if (stack.size > 1) "‹ Back to ${stack[stack.size - 2].title}" else level.subtitle
        sub.textSize = 9.5f
        sub.gravity = Gravity.CENTER
        sub.maxLines = 1
        sub.setTextColor(Color.argb(200, 235, 238, 245))
        sub.setShadowLayer(5f, 0f, 1f, Color.BLACK)
        ring.addView(sub, LayoutParams((240 * dp).toInt(), LayoutParams.WRAP_CONTENT,
            Gravity.START or Gravity.TOP))
        sub.x = cx - 120 * dp
        sub.y = cy + hubSize / 2f + 24 * dp

        if (fresh) {
            for (v in listOf<View>(hub, title, sub)) {
                v.alpha = 0f
                v.scaleX = 0.4f; v.scaleY = 0.4f
                v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(280)
                    .setInterpolator(OvershootInterpolator(1.7f)).start()
            }
        }
    }

    private fun addPetal(pt: Item, px: Float, py: Float, size: Float, index: Int, fresh: Boolean) {
        val dp = UI.dpf(context, 1f)
        val s = size.toInt()
        val isFolder = pt.submenu != null

        val petal = FrameLayout(context)
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(Color.argb(240, 18, 21, 29))
        val tint = when {
            pt.danger -> UI.DANGER
            pt.active -> UI.ACCENT2
            isFolder -> UI.FG
            else -> UI.FG
        }
        bg.setStroke(UI.dp(context, if (isFolder) 2 else 1), when {
            pt.danger -> UI.DANGER
            pt.active -> UI.ACCENT2
            isFolder -> Color.argb(150, 255, 90, 44)
            else -> Color.argb(70, 255, 255, 255)
        })
        petal.background = bg
        ring.addView(petal, LayoutParams(s, s, Gravity.START or Gravity.TOP))

        val im = ImageView(context)
        im.setImageDrawable(Ic.get(context, pt.icon, tint))
        val pad = (size * 0.27f).toInt()
        im.setPadding(pad, pad, pad, pad)
        petal.addView(im, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // folder petals get a small chevron so hierarchy is visible at a glance
        if (isFolder) {
            val chev = ImageView(context)
            chev.setImageDrawable(Ic.get(context, R.drawable.ic_more, Color.argb(210, 255, 140, 90)))
            val cs = (size * 0.26f).toInt()
            val clp = LayoutParams(cs, cs, Gravity.END or Gravity.BOTTOM)
            clp.setMargins(0, 0, (size * 0.06f).toInt(), (size * 0.06f).toInt())
            petal.addView(chev, clp)
        }

        val label = TextView(context)
        label.text = pt.label
        label.textSize = 9.5f
        label.gravity = Gravity.CENTER
        label.maxLines = 2
        label.setTextColor(if (pt.danger) UI.DANGER else Color.argb(240, 255, 255, 255))
        label.setShadowLayer(4f, 0f, 1f, Color.BLACK)
        ring.addView(label, LayoutParams((100 * dp).toInt(), LayoutParams.WRAP_CONTENT,
            Gravity.START or Gravity.TOP))
        label.x = px - 50 * dp
        label.y = py + size / 2f + 2 * dp

        var badgeView: TextView? = null
        if (!pt.badge.isNullOrBlank()) {
            val b = TextView(context)
            b.text = pt.badge
            b.textSize = 7f
            b.gravity = Gravity.CENTER
            b.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            val c = if (pt.danger) UI.DANGER else UI.ACCENT2
            b.setTextColor(c)
            b.setPadding(UI.dp(context, 4), UI.dp(context, 1), UI.dp(context, 4), UI.dp(context, 1))
            val bg2 = GradientDrawable()
            bg2.cornerRadius = UI.dpf(context, 6f)
            bg2.setColor(Color.argb(230, 14, 16, 22))
            bg2.setStroke(1, c)
            b.background = bg2
            ring.addView(b, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.TOP))
            b.x = px + size / 2f - 14 * dp
            b.y = py - size / 2f - 4 * dp
            badgeView = b
        }

        petal.setOnClickListener {
            if (dismissing) return@setOnClickListener
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            petal.animate().scaleX(1.16f).scaleY(1.16f).setDuration(85)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: Animator) {
                        petal.animate().setListener(null)
                        handleTap(pt)
                    }
                }).start()
        }

        if (fresh) {
            petal.x = cx - size / 2f
            petal.y = cy - size / 2f
            petal.scaleX = 0.2f; petal.scaleY = 0.2f; petal.alpha = 0f
            label.alpha = 0f
            badgeView?.alpha = 0f
            val delay = 30L + index * 24L
            petal.animate().x(px - size / 2f).y(py - size / 2f)
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setStartDelay(delay).setDuration(310)
                .setInterpolator(OvershootInterpolator(1.55f)).start()
            label.animate().alpha(1f).setStartDelay(delay + 130).setDuration(170).start()
            badgeView?.animate()?.alpha(1f)?.setStartDelay(delay + 130)?.setDuration(170)?.start()
        } else {
            petal.x = px - size / 2f
            petal.y = py - size / 2f
        }
    }

    private fun handleTap(pt: Item) {
        val sub = pt.submenu
        if (sub != null) {
            val lvl = try { sub() } catch (_: Exception) { null }
            if (lvl != null) push(lvl)
            return
        }
        val act = pt.action
        if (act == null) { dismiss(true); return }
        if (pt.keepOpen) {
            try { act() } catch (_: Exception) { }
            refresh()          // toggles stay open and re-render with new state
        } else {
            try { act() } catch (_: Exception) { }
            dismiss(true)
        }
    }

    fun dismiss(animated: Boolean) {
        if (!open || dismissing) return
        dismissing = true
        if (!animated) { finishDismiss(); return }
        scrim.animate().alpha(0f).setDuration(170).start()
        val n = ring.childCount
        for (i in 0 until n) {
            val v = ring.getChildAt(i)
            v.animate().cancel()
            v.animate().scaleX(0f).scaleY(0f).alpha(0f)
                .setStartDelay(((n - i) * 7L).coerceAtMost(80L))
                .setDuration(145)
                .setInterpolator(AccelerateInterpolator())
                .start()
        }
        postDelayed({ finishDismiss() }, 300)
    }

    private fun finishDismiss() {
        open = false
        dismissing = false
        stack.clear(); pages.clear()
        ring.removeAllViews()
        removeAllViews()
        visibility = View.GONE
        onDismiss?.invoke()
    }
}
