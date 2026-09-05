package com.rehman.ahmedreactionstudio.core

import kotlin.math.abs

/**
 * JVM test for the pure layer rules behind selection & placement:
 *  - new-source placement never stacks on an existing PiP (stagger),
 *  - tap hit-testing is topmost-first, rotation-aware and skips hidden,
 *  - z-order verbs (front/back/up/down) are deterministic,
 *  - clampInside keeps a source recoverable.
 */
object LayerModelTest {
    private var passes = 0
    private var failures = 0
    private fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) passes++ else failures++
        println((if (ok) "  ok   " else "  FAIL ") + name + (if (detail.isNotEmpty()) "  [$detail]" else ""))
    }
    private fun layer(name: String, w: Int = 1280, h: Int = 720): Layer =
        Layer(type = LayerType.VIDEO, name = name, relPath = null, srcW = w, srcH = h)

    private fun overlaps(a: Layer, b: Layer): Boolean =
        abs(a.cx - b.cx) < (a.wN + b.wN) / 2f && abs(a.cy - b.cy) < (a.hN + b.hN) / 2f

    @JvmStatic
    fun main(args: Array<String>) {
        val cw = 1920; val ch = 1080

        // ---------- placement ----------
        run {
            val layers = ArrayList<Layer>()
            val bg = layer("main"); LayerFit.fill(bg); layers.add(bg)
            val a = layer("cam", 720, 1280); layers.add(a); LayerFit.placeNewPip(a, layers, cw, ch)
            val b = layer("clip"); layers.add(b); LayerFit.placeNewPip(b, layers, cw, ch)
            val c = layer("img", 1000, 1000); layers.add(c); LayerFit.placeNewPip(c, layers, cw, ch)
            val d = layer("txt", 1280, 720); layers.add(d); LayerFit.placeNewPip(d, layers, cw, ch)
            val e = layer("fifth", 1280, 720); layers.add(e); LayerFit.placeNewPip(e, layers, cw, ch)
            check("1st PiP → bottom-right", a.cx > 0.5f && a.cy > 0.5f, "cx=${a.cx} cy=${a.cy}")
            check("2nd PiP → bottom-left (not on top of the 1st)", b.cx < 0.5f && b.cy > 0.5f && !overlaps(a, b), "cx=${b.cx} cy=${b.cy}")
            check("3rd PiP → top-right", c.cx > 0.5f && c.cy < 0.5f && !overlaps(a, c) && !overlaps(b, c))
            check("4th PiP → top-left", d.cx < 0.5f && d.cy < 0.5f && listOf(a, b, c).none { overlaps(it, d) })
            check("5th PiP cascades off the last one (offset, not identical)",
                (abs(e.cx - d.cx) > 0.02f || abs(e.cy - d.cy) > 0.02f), "e=(${e.cx},${e.cy}) d=(${d.cx},${d.cy})")
            check("every PiP keeps its source aspect",
                listOf(a, b, c, d, e).all { l -> abs((l.wN * cw) / (l.hN * ch) - l.srcW.toFloat() / l.srcH) < 0.01f })
            check("every PiP fully inside the canvas",
                listOf(a, b, c, d).all { l -> l.cx - l.wN / 2 >= -0.001f && l.cx + l.wN / 2 <= 1.001f && l.cy - l.hN / 2 >= -0.001f && l.cy + l.hN / 2 <= 1.001f })
            // determinism: same input twice → same output
            val x1 = layer("x"); LayerFit.placeNewPip(x1, layers, cw, ch)
            val x2 = layer("x"); LayerFit.placeNewPip(x2, layers, cw, ch)
            check("placement is deterministic", x1.cx == x2.cx && x1.cy == x2.cy && x1.wN == x2.wN)
            // the background does not count as "occupying" a corner
            val only = ArrayList<Layer>(); val bg2 = layer("bg"); LayerFit.fill(bg2); only.add(bg2)
            val f = layer("first"); only.add(f); LayerFit.placeNewPip(f, only, cw, ch)
            check("full-bleed background never blocks the first corner", f.cx > 0.5f && f.cy > 0.5f)
            // hidden PiPs do not block either
            val hid = ArrayList<Layer>(); val hl = layer("hidden"); hid.add(hl); LayerFit.pip(hl, cw, ch, "br"); hl.visible = false
            val g = layer("g"); hid.add(g); LayerFit.placeNewPip(g, hid, cw, ch)
            check("hidden layers do not reserve a corner", g.cx > 0.5f && g.cy > 0.5f)
        }

        // ---------- hit testing ----------
        run {
            val bg = layer("bg"); LayerFit.fill(bg)
            val pip = layer("pip"); LayerFit.pip(pip, cw, ch, "br")
            val top = layer("top"); LayerFit.pip(top, cw, ch, "br")   // same place, added later → above
            val layers = listOf(bg, pip, top)
            val px = pip.cx * cw; val py = pip.cy * ch
            check("tap on stacked PiPs selects the TOPMOST (last in list)", LayerFit.hitTest(layers, px, py, cw, ch) === top)
            check("tap on empty area selects the background", LayerFit.hitTest(layers, 10f, 10f, cw, ch) === bg)
            top.visible = false
            check("hidden topmost is skipped → next one down", LayerFit.hitTest(layers, px, py, cw, ch) === pip)
            top.visible = true; top.opacity = 0f
            check("fully transparent layer is skipped", LayerFit.hitTest(layers, px, py, cw, ch) === pip)
            top.opacity = 1f
            check("outside the canvas → null", LayerFit.hitTest(layers, -5f, 10f, cw, ch) == null && LayerFit.hitTest(layers, cw + 1f, 10f, cw, ch) == null)
            // rotation: a 90° rotated 16:9 PiP is hit where it is DRAWN
            val rot = layer("rot"); rot.cx = 0.5f; rot.cy = 0.5f; rot.wN = 0.4f; rot.hN = 0.1f; rot.rotDeg = 90f
            val rl = listOf(rot)
            val halfWpx = rot.wN * cw / 2f  // 384 px along the (now vertical) long axis
            check("rotated layer: point along its rotated long axis hits",
                LayerFit.hitTest(rl, 0.5f * cw, 0.5f * ch + halfWpx * 0.9f, cw, ch) === rot)
            check("rotated layer: point along the un-rotated long axis misses",
                LayerFit.hitTest(rl, 0.5f * cw + halfWpx * 0.9f, 0.5f * ch, cw, ch) == null)
            check("locked layers are still hit (so the lock explanation can show)", run {
                val lk = layer("locked"); LayerFit.pip(lk, cw, ch, "tl"); lk.locked = true
                LayerFit.hitTest(listOf(lk), lk.cx * cw, lk.cy * ch, cw, ch) === lk
            })
        }

        // ---------- z-order ----------
        run {
            val p = Project(id = "t", name = "t", aspect = Aspect.R169)
            val a = layer("a"); val b = layer("b"); val c = layer("c")
            p.layers.addAll(listOf(a, b, c))
            var mutations = 0; var changes = 0
            val ctrl = SourceController({ p }, { mutations++ }, { changes++ })
            ctrl.moveZ(a.id, "up");    check("up: a,b,c → b,a,c", p.layers.map { it.name } == listOf("b", "a", "c"), p.layers.map { it.name }.toString())
            ctrl.moveZ(a.id, "up");    check("up again → b,c,a", p.layers.map { it.name } == listOf("b", "c", "a"))
            ctrl.moveZ(a.id, "up");    check("up at top is a no-op (stays topmost)", p.layers.map { it.name } == listOf("b", "c", "a"))
            ctrl.moveZ(a.id, "down");  check("down → b,a,c", p.layers.map { it.name } == listOf("b", "a", "c"))
            ctrl.moveZ(a.id, "back");  check("back → a,b,c", p.layers.map { it.name } == listOf("a", "b", "c"))
            ctrl.moveZ(a.id, "front"); check("front → b,c,a", p.layers.map { it.name } == listOf("b", "c", "a"))
            ctrl.moveZ(b.id, "down");  check("down at bottom is a no-op", p.layers.map { it.name } == listOf("b", "c", "a"))
            check("every z move snapshots undo + notifies", mutations == 7 && changes == 7, "mut=$mutations chg=$changes")
            // topmost after 'front' is the one a tap selects
            LayerFit.fill(b); LayerFit.fill(c); LayerFit.fill(a)
            check("hit test agrees with z-order after front", LayerFit.hitTest(p.layers, 100f, 100f, cw, ch) === a)
        }

        // ---------- clamp ----------
        run {
            val l = layer("far"); LayerFit.pip(l, cw, ch, "br"); l.cx = 5f; l.cy = -5f
            LayerFit.clampInside(l)
            check("a layer dragged off-canvas keeps ≥40 % visible", l.cx < 1f + l.wN * 0.6f && l.cy > -l.hN * 0.6f, "cx=${l.cx} cy=${l.cy}")
        }

        println("$passes passed, $failures failed")
        if (failures > 0) System.exit(1)
    }
}
