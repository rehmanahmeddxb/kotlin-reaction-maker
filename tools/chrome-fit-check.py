#!/usr/bin/env python3
"""
Static fit check for the studio's fixed-height bars.

ChromeBudget (tested by tools/chrome-budget-test) proves how the flexible
axis is divided. This check covers the *other* axis of every fixed bar: does
what StudioLayoutInjector puts INTO the top bar, transport, tool rail/row,
tab strip, camera row and a SourceDock row actually fit at the smallest
width/height each tier can have — with 44dp targets and no scrolling where
scrolling would hide a control?

It is arithmetic over the dp constants in the code (read from
ChromeBudget.kt where they are constants) plus the item lists of the
builders, which are transcribed here next to the builder's name. If you add
an item to a bar, add it here too, or the CI fails when it stops fitting.

Text widths are estimates: 0.62·sp per glyph for sans-serif-medium, 0.72·sp
for upper-case, plus the pill's horizontal padding. They are deliberately a
little generous.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/com/rehman/ahmedreactionstudio"
budget = (SRC / "core/ChromeBudget.kt").read_text(encoding="utf-8")
injector = (SRC / "editor/StudioLayoutInjector.kt").read_text(encoding="utf-8")


def const(name, text=budget):
    m = re.search(rf"const val {name} = (\d+)", text)
    if not m:
        sys.exit(f"constant {name} not found")
    return int(m.group(1))


TOP = const("TOP_DP")
TRANSPORT = const("TRANSPORT_DP")
COMPACT = const("COMPACT_BAR_DP")
RAIL = const("RAIL_DP")
TABS = const("TABS_DP")
TOOLROW = const("TOOLROW_DP")
CAMERA_ROW = const("CAMERA_ROW_DP")
TAP = const("TAP_DP", injector)
PANEL_MIN_PHONE = int(re.search(r"fun panelMinDp\(tablet: Boolean\) = if \(tablet\) (\d+) else (\d+)", budget).group(2))
PANEL_MIN_TABLET = int(re.search(r"fun panelMinDp\(tablet: Boolean\) = if \(tablet\) (\d+) else (\d+)", budget).group(1))

fails = 0


def check(name, ok, detail=""):
    global fails
    print(("  ok   " if ok else "  FAIL ") + name + (f"  [{detail}]" if detail else ""))
    if not ok:
        fails += 1


def text(s, sp, pad):
    upper = sum(1 for c in s if c.isupper() or c in "■●▾")
    return int(len(s) * sp * 0.62 + upper * sp * 0.10) + 2 * pad


# smallest usable width per tier (dp): 360dp phones (portrait), a 592dp-tall
# phone in landscape minus the 24dp status bar, 600dp tablets, tablet landscape
WIDTH = {
    "phone portrait": 360,
    "phone landscape": 592 - 24,
    "tablet portrait": 600,
    "tablet landscape": 800,
}
# smallest body height available to a vertical rail: phone landscape on a
# 360dp-short phone: 360 - 24 status - 44 - 44 bars
RAIL_BODY_MIN = 360 - 24 - COMPACT - COMPACT

# ---------------------------------------------------------------- top bar --
# buildTopBar: Back · title(flex) · [REC chip while capturing] · aspect · Save
# · Export · [Settings, tablets] · ⋯ ; padding 4 + 8. Save is an icon on
# phones, Export is an icon on phone portrait.
for tier, w in WIDTH.items():
    tablet = tier.startswith("tablet")
    items = [TAP,                                  # back
             text("16:9 ▾", 12, 8) + 6,            # aspect chip + marginEnd
             (text("Save", 12, 12) + 6) if tablet else TAP,
             TAP if tier == "phone portrait" else text("Export", 12, 12) + 6,
             TAP]                                  # ⋯
    if tablet:
        items.append(TAP)                          # settings
    fixed = sum(items) + 12
    title_idle = w - fixed
    title_rec = title_idle - (text("■ SCREEN", 11, 8) + 6)
    check(f"top bar · {tier}: items fit with a readable title while idle",
          title_idle >= 60, f"title={title_idle}dp of {w}")
    check(f"top bar · {tier}: items still fit (title may collapse) while capturing",
          title_rec >= 0, f"title={title_rec}dp")

# -------------------------------------------------------------- transport --
# buildTransport: Play · Stop · time · SeekBar(flex) · duration · [timeline
# toggle, all but phone portrait] · REC pill ; padding 4 + 8. Phone portrait's
# REC pill is a 44dp glyph that widens to "■ 0:12" while recording (checked
# in that state, the wider one).
for tier, w in WIDTH.items():
    compact = tier.startswith("phone")
    items = [TAP, TAP, text("00:00", 12, 3), text("00:00", 12, 4)]
    if tier == "phone portrait":
        items.append(max(TAP, text("■ 00:00", 12, 8)))
    else:
        items += [text("●  REC" if compact else "●  START RECORDING", 12, 14), TAP]
    seek = w - sum(items) - 12 - 4
    check(f"transport · {tier}: seek bar keeps >= 96dp", seek >= 96, f"seek={seek}dp of {w}")

# -------------------------------------------------------------- tool rail --
# buildRail: phones = Add | Undo Redo | [panel toggle, portrait] [timeline
# toggle, phone portrait] [Hide tools, phone landscape]; tablets add Camera
# Video Image Text. 44dp items at a 48dp pitch, 24dp gaps, 8dp padding.
def rail_len(n_items, n_gaps):
    return n_items * (TAP + 4) + n_gaps * 13 + 8


check("tool row · phone portrait: Add | Undo Redo | panel timeline fit in 360dp without scrolling",
      rail_len(5, 2) <= WIDTH["phone portrait"], f"{rail_len(5, 2)}dp")
check("tool rail · phone landscape: Add | Undo Redo | hide fit the shortest body without scrolling",
      rail_len(4, 2) <= RAIL_BODY_MIN, f"{rail_len(4, 2)}dp of {RAIL_BODY_MIN}")
check("tool row · tablet portrait: all seven tools + panel toggle fit in 600dp",
      rail_len(8, 2) <= WIDTH["tablet portrait"], f"{rail_len(8, 2)}dp")
check("tool rail · tablet landscape: all seven tools fit a 600dp-short tablet body",
      rail_len(7, 1) <= 600 - 24 - TOP - TRANSPORT, f"{rail_len(7, 1)}dp")
check("tool rail width holds a 44dp target with breathing room", RAIL >= TAP + 8, f"{RAIL}dp")

# -------------------------------------------------------------- tab strip --
# buildContextPanel: 4 text tabs (weight 1) + 44dp close; padding 4 + 2
for label, pmin in (("phone", PANEL_MIN_PHONE), ("tablet", PANEL_MIN_TABLET)):
    per_tab = (pmin - 6 - TAP) / 4 - 4
    longest = text("Sources", 9, 2)   # auto-size floor is 9sp
    check(f"tab strip · {label} panel at its {pmin}dp minimum: each tab >= 40dp wide and the longest label fits at the 9sp floor",
          per_tab >= 40 and longest <= per_tab, f"tab={per_tab:.0f}dp label>={longest}dp")
check("tab strip: tab items span the full strip height (>= 40dp targets)", TABS >= 40)

# ---------------------------------------------------------- SourceDock row --
# buildRow: [eye 44] [type 18 + 4 + 8] [name/status flex] [mute 44, clips] [drag 44]; row padding 4+4, margins 2+2
for label, pmin in (("phone", PANEL_MIN_PHONE), ("tablet", PANEL_MIN_TABLET)):
    fixed = 4 + TAP + (18 + 4 + 8) + TAP + TAP + 8
    name = pmin - fixed
    check(f"source row · {label} panel at {pmin}dp: name column >= 60dp beside eye · mute · drag",
          name >= 60, f"name={name}dp")

# ------------------------------------------------------------- camera row --
# refreshCameraStrip: name(flex) · Flash · Switch · Mirror · Light · Take (5 × 44); padding 10 + 6
for tier, w in WIDTH.items():
    name = w - 5 * TAP - 16
    check(f"camera row · {tier}: five 44dp controls leave >= 80dp for the camera name", name >= 80, f"name={name}dp")
check("camera row is a 44dp touch-target strip", CAMERA_ROW >= 44)

# --------------------------------------------------------- bars vs targets --
check("top bar (compact) holds a 44dp target", COMPACT >= TAP)
check("transport holds a 44dp target with room for a 36dp REC pill", TRANSPORT >= TAP and COMPACT >= 36)
check("portrait tool row holds a 44dp target", TOOLROW >= TAP)

print(f"\n{'CHROME FIT CHECK: all green' if fails == 0 else f'CHROME FIT CHECK: {fails} failed'}")
sys.exit(1 if fails else 0)
