#!/usr/bin/env python3
"""Static merge guards: Step 5 UI and Phase 2 must ship together.

These check integration wiring, not Android runtime behaviour. Real inset,
rotation, gesture and recording smoke tests still require an Android device.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/com/rehman/ahmedreactionstudio"
editor = (SRC / "editor/EditorActivity.kt").read_text()
errors = []
passed = []


def check(name, condition):
    (passed if condition else errors).append(name)


def method(name, prefix="private fun"):
    start = editor.find(f"    {prefix} {name}(")
    if start < 0:
        errors.append(f"missing {name}")
        return ""
    end = editor.find("\n    }\n", start)
    return editor[start:end]


def contains(text, needle, name):
    check(name, needle in text)


check("no unresolved editor conflict markers", not re.search(r"^(?:<{7}|={7}|>{7})(?: |$)", editor, re.M))
check("one toolbar, not the disabled Phase 2 legacy pill", "USE_QUICK_BAR" not in editor)
check("four-side viewport API only", "setChromeInsets" not in editor)

for tab in ("sources", "add", "mixer", "text", "export"):
    contains(method("buildTabBar"), f'addTab("{tab}"', f"Step 5 {tab} tab retained")
contains(method("refreshTabBar"), "tab.isSelected = sel", "selected tab is exposed to accessibility")
contains(method("buildTabBar"), 'setSheet("mixer")', "Audio tab uses the same mixer id as the panel")

for view in ("panelScroll", "panelDivider", "sourceStripWrap", "tabBar", "quickWrap", "transportBar", "launchRow"):
    contains(method("relayoutChrome"), view, f"rotation preserves existing {view}")
contains(method("relayoutChrome"), "rootFrame.addView(quickWrap, qlp)", "portrait floating controls retained")
contains(method("relayoutChrome"), "railContent.addView(quickWrap", "landscape contextual controls retained")
contains(method("relayoutChrome"), "railContent.addView(launchRow", "landscape Record/Studio remain reachable")
contains(method("buildSideRail"), "sideRail = ScrollView(this)", "combined landscape rail scrolls instead of clipping controls")
for dangerous in ("startLiveCamera(", "engine.attach(", "engine.release(", "engine.pauseAll("):
    check(f"rotation does not call {dangerous}", dangerous not in method("relayoutChrome"))

strip = method("updateSourceStrip")
for needle, name in (
    ("p.layers.indices.reversed()", "Step 5 layer chips in Z order"),
    ("l.locked", "locked source badge"),
    ("!l.visible", "hidden source badge"),
    ("select(l.id)", "source chip selects a layer"),
    ("setOnLongClickListener", "source chip opens advanced settings"),
    ("srcDockExpanded", "Phase 2 collapsible add shortcuts"),
    ("startScreenCapture()", "screen source shortcut"),
    ("pickMedia(video = false)", "image source shortcut"),
):
    contains(strip, needle, name)
contains(method("select", "override fun"), "rebuildSourceDock()", "selection refreshes source chips")

quick = method("refreshQuickBar")
for needle, name in (
    ("if (fullCanvas)", "contextual controls stay hidden in Full Canvas"),
    # Source action buttons must be the Step 5 48dp touch target. They are
    # added to quickBar (a LinearLayout), so the bar() helper builds them with
    # an explicit 48dp LinearLayout.LayoutParams — NOT IconBtn.sized(), which
    # returns FrameLayout.LayoutParams and crashed on a pre-addView cast.
    ("LinearLayout.LayoutParams(UI.dp(this, 48), UI.dp(this, 48))",
     "source actions meet the Step 5 minimum touch size"),
    ("ctrl.toggleVisible", "hide/show action retained"),
    ("ctrl.toggleMuted", "mute action retained"),
    ("engine.toggleLayerPlay", "per-source playback retained"),
    ("toggleLiveCameraRecord", "camera take action retained"),
    ("switchCameraFacing", "switch camera action retained"),
    ("toggleCameraMirror", "mirror action retained"),
    ("openFlashRing", "hardware light action retained"),
    ("ctrl.toggleLocked", "lock action retained"),
    ("ctrl.toggleFit", "fit/fill action retained"),
    ("ctrl.center", "Phase 2 move shortcut retained"),
    ("size reset", "Phase 2 resize shortcut retained"),
    ("rotation reset", "Phase 2 rotate shortcut retained"),
    ('ctrl.moveZ(l.id, "up")', "Phase 2 bring forward retained"),
    ('ctrl.moveZ(l.id, "down")', "Phase 2 send backward retained"),
    ("openAdvancedSheet", "Step 5 More settings retained"),
):
    contains(quick, needle, name)

contains(method("buildUi"), "isScrollbarFadingEnabled = false", "overflow actions have a visible scroll affordance")
contains(method("capPanelHeight"), "ViewportFit.panelHeight", "measured panel uses the JVM-tested budget")
insets = method("applyViewportInsets")
for needle, name in (
    ("stage.setViewportInsets(sysL, padTop, padRight, padBottom)", "Phase 2 four-side safe canvas"),
    ("maxOf(padTop, topBar.bottom)", "top inset measured without counting system padding twice"),
    ("rootFrame.height - sheet.top", "bottom sheet measured, not guessed"),
    ("padBottom + gap", "floating controls follow the actual sheet height"),
    ("quickWrap.height", "floating controls reserve canvas space"),
    ("padRight += width", "right rail reserves canvas space"),
    ("emptyLp.setMargins(sysL, padTop, padRight, padBottom)", "Step 5 empty state follows the safe canvas"),
):
    contains(insets, needle, name)
contains(method("setSheet"), "val sv = panelScroll", "panel reference survives landscape reparenting")
check("no sheet-only panel lookup", 'sheet.findViewWithTag<ScrollView>("panelScroll")' not in editor)
contains(method("openAdvancedSheet"), "rebuildSourceDock()", "advanced panel refreshes strip visibility")
contains(method("onTick"), "!fullCanvas &&", "HUD ticks do not escape Full Canvas")
contains(method("updateHiddenPill"), "fullCanvas ||", "hidden-source badge respects Full Canvas")
contains(method("applyOrientationFor"), "SCREEN_ORIENTATION_UNSPECIFIED", "main's aspect-independent phone orientation retained")
contains(method("onDestroy", "override fun"), "removeOnGlobalLayoutListener", "main's layout listener cleanup retained")

for rel, needle in (
    ("editor/StageView.kt", "Compositor.chromeRect"),
    ("editor/PreviewEngine.kt", "LayerType.IMAGE"),
    ("core/Model.kt", "fun placeNewPip"),
    ("export/CompositionRecorder.kt", "ClipCursor"),
    ("editor/SourceDock.kt", "private val ROW_DP = 52"),
):
    text = (SRC / rel).read_text()
    contains(text, needle, f"preserved integration dependency {rel}: {needle}")

for name in passed:
    print("  OK ", name)
for name in errors:
    print("  FAIL", name)
print(f"{len(passed)} checks passed, {len(errors)} failed")
if errors:
    raise SystemExit(1)
print("BRANCH INTEGRATION STATIC VALIDATION OK")
