#!/usr/bin/env python3
"""Static merge guards for the Studio workspace.

The editor chrome is ONE authoritative layout built by StudioLayoutInjector:
a dp-sized top bar, a flexible body (landscape: tool rail | canvas cell |
context panel; portrait: canvas cell / context panel / tool row) and a
dp-sized transport bar. The context panel hosts exactly one of
Sources / Mixer / Props / Effects. EditorActivity keeps the engine, camera,
recording and export behaviour and rebuilds the chrome through
relayoutChrome() on a configuration change.

These guards check that both orientations go through the same builders, that
rotation re-layout stays lifecycle-safe, that every chrome control reaches a
real editor verb, and that the pre-existing integrations (stage, preview
engine, model, recorder, dock) remain wired.

These check integration wiring, not Android runtime behaviour. Real inset,
rotation, gesture and recording smoke tests still require an Android device.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/com/rehman/ahmedreactionstudio"
editor = (SRC / "editor/EditorActivity.kt").read_text()
injector = (SRC / "editor/StudioLayoutInjector.kt").read_text()
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


def count(text, needle):
    return text.count(needle)


# ---------------------------------------------------------------- hygiene ---
check("no unresolved editor conflict markers",
      "<<<<<<< " not in editor and ">>>>>>> " not in editor
      and "<<<<<<< " not in injector and ">>>>>>> " not in injector)
check("one toolbar, not the disabled Phase 2 legacy pill", "USE_QUICK_BAR" not in editor)
check("four-side viewport API only", "setChromeInsets" not in editor)

# ------------------------------------------- StudioLayoutInjector: chrome ---
# One set of builders serves BOTH orientations: inject() branches on the
# orientation and each branch must go through the shared canvas / panel /
# bind / transport builders (no second chrome, no per-orientation copies).
check("injector builds landscape and portrait layouts",
      "if (isLandscape)" in injector and "} else {" in injector)
inject_body = injector[injector.find("fun inject("):injector.find("fun buildTopBar(")]
land = inject_body[inject_body.find("if (isLandscape)"):inject_body.find("} else {")]
port = inject_body[inject_body.find("} else {"):]
check("landscape branch builds its body", "buildLandscapeBody(activity," in land)
check("portrait branch builds its body", "buildPortraitBody(activity," in port)
check("both orientations bind their panels", count(inject_body, "bindPanels(activity,") == 2)
check("both orientations build the transport bar", count(inject_body, "buildTransport(activity,") == 2)
for body in ("buildLandscapeBody", "buildPortraitBody"):
    fn = injector[injector.find(f"private fun {body}("):]
    fn = fn[:fn.find("\n    }\n")]
    check(f"{body} places the canvas cell", "buildCanvasCell(activity)" in fn)
    check(f"{body} places the context panel", "buildContextPanel(activity)" in fn)
cell = injector[injector.find("private fun buildCanvasCell("):injector.find("private fun buildRail(")]
check("StageView created by the shared canvas cell builder",
      count(cell, "activity.stage = StageView(activity)") == 1)
check("StageView host bound by the shared canvas cell builder",
      count(cell, "activity.stage.host = activity") == 1)
check("StageView is told the cell already excludes every bar",
      "stage.setViewportInsets(0, 0, 0, 0)" in cell)
panel = injector[injector.find("private fun buildContextPanel("):injector.find("private fun bindPanels(")]
for p_ in ("SourcesPanel(activity)", "MixerPanel(activity)", "PropertiesPanel(activity)", "EffectsPanel(activity)"):
    check(f"{p_.split('(')[0]} created by the shared context panel builder", count(panel, p_) == 1)
check("panel tab bar offers Sources/Mixer/Props/Effects + close",
      all(count(panel, f'createTab("{t}"') == 1
          for t in ("Sources", "Mixer", "Props", "Effects", "X")))
check("the source dock lives inside the Sources tab",
      "activity.dockContainer = sourcesPanel.dockContainer" in panel)
transport = injector[injector.find("private fun buildTransport("):injector.find("private fun buildOverlays(")]
check("transport builds the timeline seek", count(transport, "activity.seek = seek") == 1)
check("no percent weights between chrome and canvas",
      "weight = 0." not in injector and "LayoutParams(0, 0, 0." not in injector)
check("no structural hacks (translation / negative margins) in the injector",
      "translationX" not in injector and "translationY" not in injector and "-UI.dp(" not in injector)
check("chrome sizes come from ChromeBudget (one source of truth)",
      "ChromeBudget.landscape(" in injector and "ChromeBudget.portrait(" in injector
      and "landscapeBudget(activity)" in injector and "portraitBudget(activity)" in injector)
check("optional rows (timeline, camera row) sit between the body and the transport in both branches",
      count(inject_body, "buildCameraRow(activity, column)") == 2
      and count(inject_body, "buildTimeline(activity, column)") == 2)
check("no raw android.widget.Button in the studio chrome",
      "Button(" not in injector.replace("IconBtn(", "").replace("pillBtn(", "")
      and "UI.btn(" not in editor)

# Top bar actions must reach the real verbs, not dead buttons.
for needle, name in (
    ("activity.saveNow()", "top bar Save is wired"),
    ("activity.quickExport()", "top bar Export is wired"),
    ("activity.showAspectPicker()", "aspect chip opens the aspect picker"),
    ("activity.openDiagnostics()", "settings opens diagnostics"),
):
    contains(injector, needle, name)

# Landscape left tool rail: every tool wired to a real editor verb.
for needle, name in (
    ("activity.pickMedia(true)", "tool rail add/video wired"),
    ("activity.addLiveCamera()", "tool rail camera wired"),
    ("activity.pickMedia(false)", "tool rail image wired"),
    ("activity.addText()", "tool rail text wired"),
    ("activity.doUndo()", "tool rail undo wired"),
    ("activity.doRedo()", "tool rail redo wired"),
):
    contains(injector, needle, name)

# Sources panel: full OBS-style verb set, including the redesign's new
# move-up/move-down Z-order controls.
sources = injector[injector.find("sourcesPanel.listener"):]
sources = sources[:sources.find("mixerPanel.listener")]
for needle, name in (
    ("activity.select(id)", "sources selection wired"),
    ("activity.ctrl.toggleVisible(id)", "sources hide/show wired"),
    ("activity.pickMedia(true)", "sources add wired"),
    ("activity.pickMedia(false)", "sources add-image wired"),
    ("activity.removeSelectedSource()", "sources remove wired"),
    ('activity.ctrl.moveZ(id, "up")', "sources move-up Z-order wired"),
    ('activity.ctrl.moveZ(id, "down")', "sources move-down Z-order wired"),
    ("activity.openAdvancedSheet(layer)", "sources properties opens advanced sheet"),
):
    contains(sources, needle, name)

# Mixer panel: mute / solo / volume reach the controller and the engine.
mixer = injector[injector.find("mixerPanel.listener"):]
for needle, name in (
    ("activity.ctrl.toggleMuted(id)", "mixer mute wired"),
    ("activity.ctrl.toggleSolo(id)", "mixer solo wired"),
    ("activity.engine.setVolume(l, v)", "mixer volume wired to engine"),
    ("activity.pushUndoLight()", "mixer volume pushes undo"),
    ("activity.markDirty()", "mixer volume marks project dirty"),
):
    contains(mixer, needle, name)

# Timeline + transport: scrubbing seeks the engine, transport drives playback.
for needle, name in (
    ("activity.engine.seekTo(v.toLong())", "timeline seek scrubs the engine"),
    ("activity.scrubbing = true", "timeline drag sets scrubbing"),
    ("activity.togglePlay()", "transport play/pause wired"),
    ("activity.recordButtonTap()", "transport record wired"),
    ("activity.controlsStopTap()", "transport stop wired"),
    ("activity.timeLabel.text", "transport time label wired"),
    ("activity.durationLabel", "transport duration label wired"),
    ("activity.recordBtn = recBtn", "transport record button exposed to the activity"),
):
    contains(injector, needle, name)

# ------------------------------------------------- EditorActivity wiring ---
# Rotation (and the first layout) must delegate to the injector, and the
# re-layout path must never bounce engine/camera lifecycle.
relayout = method("relayoutChrome")
contains(relayout, "StudioLayoutInjector.inject(this, rootFrame)",
         "rotation re-layout delegates to the Studio injector")
for dangerous in ("startLiveCamera(", "engine.attach(", "engine.release(", "engine.pauseAll("):
    check(f"rotation does not call {dangerous}", dangerous not in relayout)
reconf = method("onConfigurationChanged", "override fun")
contains(reconf, "relayoutChrome()",
         "configuration change re-injects the workspace through relayoutChrome")
contains(reconf, "syncPreviewTarget()", "configuration change re-targets the preview")
contains(reconf, "stage.refresh()", "configuration change refreshes the stage")

# First build must go through the injector too (not a second chrome).
build_ui = method("buildUi")
contains(build_ui, "StudioLayoutInjector.inject(this, root)", "buildUi injects the Studio workspace")
contains(build_ui, "setContentView(root)", "buildUi installs the root view")

# Ticks: transport UI ~20 Hz, HUD ~2 Hz, HUD never in Full Canvas.
tick = method("onTick")
contains(tick, "lastUiTickMs", "transport tick throttled")
contains(tick, "lastHudMs", "HUD tick throttled")
contains(tick, "!fullCanvas &&", "HUD ticks do not escape Full Canvas")

# Selection / sheets keep their refresh wiring.
contains(method("select", "override fun"), "rebuildSourceDock()",
         "selection refreshes the source dock")
adv = method("openAdvancedSheet", "fun")
contains(adv, "rebuildSourceDock()", "advanced sheet refreshes the source dock")
check("advanced sheet exists and is reachable from the injector",
      "fun openAdvancedSheet(l: Layer)" in editor and "openAdvancedSheet(layer)" in injector)
check("the Props tab body is filled by the advanced sheet sections",
      "propertiesPanel.onFill = { l -> activity.fillAdvanced(l) }" in injector
      and "fun fillAdvanced(l: Layer)" in editor)
contains(method("showTab", "fun"), "setFullCanvas(false)", "opening a panel tab exits Full Canvas")
contains(method("setPanelOpen", "fun"), "panelOpen = open", "panel open state survives a rotation rebuild")
contains(method("onSaveInstanceState", "override fun"), '"tab"', "active tab survives process death")
contains(method("setFullCanvas"), "fullCanvas = on", "Full Canvas state toggles")

# recChip (recording indicator) must respect Full Canvas.
check("recording chip respects Full Canvas",
      "recChip.visibility = if (fullCanvas) View.GONE else View.VISIBLE" in editor)

# Retained main-activity behaviours from the pre-redesign code.
contains(method("applyOrientationFor"), "SCREEN_ORIENTATION_UNSPECIFIED",
         "main's aspect-independent phone orientation retained")
destroy = method("onDestroy", "override fun")
contains(destroy, "removeCallbacksAndMessages(null)", "handler cleanup retained")
contains(destroy, "engine.release()", "engine released with the activity")
# system insets are applied as padding on the chrome column, never as a
# second layout or a hard-coded status-bar guess
contains(method("buildUi"), "setOnApplyWindowInsetsListener", "edge-to-edge insets listener installed")
contains(method("applySystemInsets"), "chromeColumn.setPadding", "insets become column padding")

# The re-layout path must be state-preserving: what the user opened/closed
# and which tab was active are plain fields read back by the injector.
for field in ("activity.panelOpen", "activity.railOpen", "activity.timelineOpen", "activity.activeTab"):
    contains(injector, field, f"injector rebuilds from persisted chrome state {field}")
check("dp budget re-applied in place (no rebuild) when a piece opens/closes",
      "private fun applyChromeBudget()" in editor
      and "applyChromeBudget()" in method("setPanelOpen", "fun")
      and "applyChromeBudget()" in method("setRailOpen", "fun"))

# ------------------------------------------------- cross-file dependencies ---
for rel, needle in (
    ("editor/StageView.kt", "Compositor.chromeRect"),
    ("editor/PreviewEngine.kt", "LayerType.IMAGE"),
    ("core/Model.kt", "fun placeNewPip"),
    ("export/CompositionRecorder.kt", "ClipCursor"),
    ("editor/SourceDock.kt", "private val ROW_DP = 52"),
    ("editor/SourceDock.kt", "ellipsize = android.text.TextUtils.TruncateAt.END"),
    ("editor/SourcesPanel.kt", "fun setCompact(compact: Boolean)"),
    ("core/ChromeBudget.kt", "const val PORTRAIT_SPARE_MIN_DP"),
):
    text = (SRC / rel).read_text()
    contains(text, needle, f"preserved integration dependency {rel}: {needle}")

# The StageView/PreviewEngine pair must still be created together by the
# injector in both orientations (preview draws through the engine).
check("preview engine referenced by the editor", "engine.attach(" in editor)

for name in passed:
    print("  OK ", name)
for name in errors:
    print("  FAIL", name)
print(f"{len(passed)} checks passed, {len(errors)} failed")
if errors:
    raise SystemExit(1)
print("BRANCH INTEGRATION STATIC VALIDATION OK")
