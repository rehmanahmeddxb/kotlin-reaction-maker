#!/usr/bin/env python3
"""Assertions over a `uiautomator dump` of the Studio editor.

Used by tools/emulator-smoke/smoke.sh on the CI emulator; stdlib only.

    check_window.py --tier T --step S --density D [--expect-bars]
                    [--expect LABEL]... [--expect-orientation portrait|landscape] window.xml
    check_window.py --bounds-of LABEL window.xml
        → prints "x y" (centre of the first node whose content-desc / text
          equals LABEL, else starts with it, else equals it ignoring case);
          exit 1 when absent

The checks are the on-device half of the §31 audit — the questions the static
budget cannot answer:

  * every clickable node is entirely inside the window                    (no off-screen / clipped controls)
  * every clickable node is ≥ 44dp on both sides — except the 40dp strip
    items (tabs, close, Sources action row, mixer toggles, "+ Add") and
    the 24dp in-row play/pause status shortcut, which are 40 / 24 by
    design and documented as such                                          (touch targets)
  * no two clickable nodes overlap (a clickable row containing clickable
    buttons is fine)                                                       (no overlaps)
  * the canvas ("Canvas") exists, is ≥ 96dp on both sides, and no chrome
    control intersects it — the edge handles and the full-canvas ✕ sit on
    the canvas CELL by design and the stage insets the picture past them   (canvas never covered)
  * with --expect-bars: Back · aspect · Save · Export · ⋯ · Play/Pause ·
    Stop · REC · Canvas, plus a tab strip, the Sources header, or the
    "Open panel" handle                                                    (nothing missing)
"""
import argparse
import sys
import xml.etree.ElementTree as ET

PKG = "com.rehman.ahmedreactionstudio"
MIN_TAP = 44

# 40dp by design (docs/STUDIO_UI_RECONSTRUCTION.md §31 row 18): the tab strip
# (40dp; the ✕ is 44×40), the Sources header "+ Add" and "N hidden" (40dp
# header), the Sources action row and the mixer's monitor / M / S toggles.
FORTY_EXACT = {"Mixer tab", "Props tab", "Effects tab", "Sources tab", "Collapse panel", "Expand panel",
               "Add a source", "Mute preview monitor", "Unmute preview monitor",
               "Bring selected source forward", "Send selected source backward",
               "Hide selected source", "Properties of selected source", "Remove selected source"}
FORTY_PREFIX = ("Mute ", "Unmute ", "Solo ", "Unsolo ", "Hide ", "Show ")
# the clip row's status line ("Pause X" / "Play X") is a 24dp in-row shortcut;
# the same verb is on the wheel and in Props with full-size targets
STATUS_SHORTCUT_PREFIX = ("Pause ", "Play ")
STATUS_SHORTCUT_MIN = 24
SLIDER_CLASSES = {"android.widget.SeekBar"}
ON_CANVAS_CELL_BY_DESIGN = {"Show tools", "Open panel", "Exit full canvas"}

EXPECT_ANY_TIER = [("Back to projects",), ("Canvas aspect ratio",), ("Save project",), ("Export video",),
                   ("Studio menu",), ("Play", "Pause"), ("Stop",), ("Canvas",)]
EXPECT_REC_PREFIX = ("Record", "Start recording", "Stop recording")
EXPECT_PANEL_AFFORDANCE = ("Sources tab", "Mixer tab", "Add a source", "Open panel", "Expand panel")


def bounds(node):
    b = node.get("bounds")  # "[l,t][r,b]"
    lt, rb = b[1:-1].split("][")
    l, t = (int(v) for v in lt.split(","))
    r, bt = (int(v) for v in rb.split(","))
    return l, t, r, bt


def label(node):
    return node.get("content-desc") or node.get("text") or node.get("class")


def intersects(a, b):
    return not (a[2] <= b[0] or b[2] <= a[0] or a[3] <= b[1] or b[3] <= a[1])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("xml")
    ap.add_argument("--tier", default="?")
    ap.add_argument("--step", default="?")
    ap.add_argument("--density", type=int, default=480)
    ap.add_argument("--package", default=PKG)
    ap.add_argument("--expect-bars", action="store_true")
    ap.add_argument("--expect", action="append", default=[], help="label that must be present (prefix match)")
    ap.add_argument("--expect-orientation", choices=["portrait", "landscape"])
    ap.add_argument("--bounds-of")
    ap.add_argument("--wheel-open", action="store_true", help="exit 0 when a radial menu is open")
    a = ap.parse_args()

    hierarchy = ET.parse(a.xml).getroot()
    all_nodes = list(hierarchy.iter("node"))

    def labelled(text, pool=None):
        pool = all_nodes if pool is None else pool
        for n in pool:
            if n.get("content-desc") == text or n.get("text") == text:
                return n
        for n in pool:
            if (n.get("content-desc") or "").startswith(text) or (n.get("text") or "").startswith(text):
                return n
        low = text.lower()
        for n in pool:
            if (n.get("text") or "").lower() == low or (n.get("content-desc") or "").lower() == low:
                return n
        return None

    if a.bounds_of:
        n = labelled(a.bounds_of)
        if n is None:
            return 1
        l, t, r, b = bounds(n)
        print((l + r) // 2, (t + b) // 2)
        return 0
    if a.wheel_open:
        return 0 if any(n.get("content-desc") == "Radial menu" or (n.get("class") or "").endswith(".RadialMenuView")
                        for n in all_nodes) else 1

    # the app's windows only (the dump may also carry system UI windows)
    app_roots = [n for n in hierarchy if n.tag == "node" and n.get("package") == a.package]
    if not app_roots:
        app_roots = [n for n in hierarchy if n.tag == "node"]
    nodes = [n for r in app_roots for n in r.iter("node")]
    W = max((bounds(r)[2] for r in app_roots), default=0)
    H = max((bounds(r)[3] for r in app_roots), default=0)
    dp = lambda px: px * 160 / a.density
    errs, notes = [], []

    clickable = [n for n in nodes if n.get("clickable") == "true"]
    canvas = labelled("Canvas", nodes)
    # the wheel is a GONE view until shown, so its class only appears in a dump while open
    wheel_open = any(n.get("content-desc") == "Radial menu" for n in nodes) \
        or any((n.get("class") or "").endswith(".RadialMenuView") for n in nodes)
    dialog_open = any((n.get("resource-id") or "").endswith("android:id/button1") for n in nodes)
    # the snackbar (message + UNDO / UNLOCK action) is a 3.5 s overlay on the
    # root frame; while it is up the geometry rules are suspended, like the wheel
    snack_open = any(n.get("clickable") == "true" and n.get("text") in ("UNDO", "UNLOCK") for n in nodes)
    # the empty-canvas card (Camera · Video · Screen · Image) sits on the canvas
    # cell by design until the first source arrives
    empty_state = labelled("What is the background?", nodes) is not None

    # 1. on screen + size
    for n in clickable:
        l, t, r, b = bounds(n)
        lab = label(n)
        if l < 0 or t < 0 or r > W or b > H:
            errs.append(f"off-screen: {lab} {bounds(n)} window {W}x{H}")
        w, h = dp(r - l), dp(b - t)
        if n.get("class") in SLIDER_CLASSES:
            if h < 34:
                errs.append(f"slider band too thin: {lab} {h:.0f}dp")
            continue
        if lab.startswith(STATUS_SHORTCUT_PREFIX):
            if h < STATUS_SHORTCUT_MIN - 0.5:
                errs.append(f"status shortcut under {STATUS_SHORTCUT_MIN}dp: {lab} {w:.0f}x{h:.0f}dp")
            continue
        if n.get("class") == "android.widget.LinearLayout" and (w >= 120 or h >= 44):
            # full-width rows (source rows, mixer strips, shortcuts, empty-state choices)
            if h < 40:
                errs.append(f"row too short: {lab} {h:.0f}dp")
            continue
        floor = 40 if (lab in FORTY_EXACT or lab.startswith(FORTY_PREFIX) or " hidden source" in lab) else MIN_TAP
        if min(w, h) < floor - 0.5:
            errs.append(f"target under {floor}dp: {lab} {w:.0f}x{h:.0f}dp")

    if wheel_open or dialog_open or snack_open:
        notes.append("radial menu open" if wheel_open else "dialog open" if dialog_open else "snackbar up")
    else:
        # 2. overlaps (ancestor/descendant pairs are by design)
        def is_ancestor(x, y):
            return any(c is y for c in x.iter("node"))
        for i, x in enumerate(clickable):
            for y in clickable[i + 1:]:
                if is_ancestor(x, y) or is_ancestor(y, x):
                    continue
                bx, by = bounds(x), bounds(y)
                if intersects(bx, by):
                    errs.append(f"overlap: '{label(x)}' {bx} × '{label(y)}' {by}")

        # 3. canvas
        if canvas is None:
            errs.append("no Canvas node")
        else:
            cb = bounds(canvas)
            cw, ch = dp(cb[2] - cb[0]), dp(cb[3] - cb[1])
            if cw < 96 or ch < 96:
                errs.append(f"canvas collapsed: {cw:.0f}x{ch:.0f}dp")
            notes.append(f"canvas cell {cw:.0f}x{ch:.0f}dp")
            for n in clickable:
                if label(n) in ON_CANVAS_CELL_BY_DESIGN or any(c is canvas for c in n.iter("node")):
                    continue
                if empty_state and label(n) in ("Camera", "Video", "Screen", "Image"):
                    continue
                nb = bounds(n)
                if intersects(nb, cb):
                    errs.append(f"chrome over canvas: {label(n)} {nb}")

    # 4. expected controls
    if a.expect_bars:
        for alts in EXPECT_ANY_TIER:
            if not any(labelled(x, nodes) is not None and
                       ((labelled(x, nodes).get("content-desc") == x) or (labelled(x, nodes).get("text") == x))
                       for x in alts):
                errs.append("missing control: " + " / ".join(alts))
        if not any((n.get("content-desc") or "").startswith(EXPECT_REC_PREFIX) for n in nodes):
            errs.append("missing control: record button")
        if not any(any(n.get("content-desc") == x for n in nodes) for x in EXPECT_PANEL_AFFORDANCE):
            errs.append("missing: no tab strip, Sources header or panel handle")

    # 5. explicit expectations, orientation
    for e in a.expect:
        if labelled(e, nodes) is None:
            errs.append(f"missing: {e}")
    got = "landscape" if W > H else "portrait"
    if a.expect_orientation and got != a.expect_orientation:
        errs.append(f"orientation: window is {got} ({W}x{H}px), expected {a.expect_orientation}")
    notes.append(f"{got} {dp(W):.0f}x{dp(H):.0f}dp")
    notes.append(f"{len(clickable)} clickable")

    print(f"{a.tier} · {a.step}: {'ok' if not errs else 'FAIL'} — " + "; ".join(notes))
    for e in errs:
        print("   " + e)
    return 0 if not errs else 1


if __name__ == "__main__":
    sys.exit(main())
