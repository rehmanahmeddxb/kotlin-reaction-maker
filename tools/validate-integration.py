#!/usr/bin/env python3
"""Static merge guards for the redesigned Studio workspace.

Since PR #31 ("Redesign Studio Workspace") the editor chrome lives in
StudioLayoutInjector (top bar / left tool rail / right panel with
Sources-Mixer-Props-Effects tabs / timeline / transport, in both
orientations), while EditorActivity keeps the engine, camera, recording and
sheet behaviour. These guards check that both halves of the new architecture
ship together, that rotation re-layout stays lifecycle-safe, and that the
pre-existing integrations (stage, preview engine, model, recorder, dock)
remain wired.

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
# The redesigned workspace must build BOTH orientations; each one creates its
# own StageView (bound to the activity host) and its own panel set.
check("injector builds landscape and portrait layouts",
      "if (isLandscape)" in injector and "} else {" in injector)
check("StageView created in both orientations",
      count(injector, "activity.stage = StageView(activity)") == 2)
check("StageView host bound in both orientations",
      count(injector, "activity.stage.host = activity") == 2)
for panel in ("SourcesPanel(activity)", "MixerPanel(activity)", "EffectsPanel(activity)"):
    check(f"{panel.split('(')[0]} created in both orientations",
          count(injector, panel) == 2)
check("panel tab bar offers Sources/Mixer/Props/Effects + close in both orientations",
      all(count(injector, f'createTab("{t}"') == 2
          for t in ("Sources", "Mixer", "Props", "Effects", "X")))
check("both orientations bind their panels", count(injector, "bindPanels(activity,") == 2)
check("both orientations build the transport bar", count(injector, "buildTransport(activity,") == 2)
check("both orientations build the timeline seek", count(injector, "activity.seek = seek") == 2)

# Top bar actions must reach the real verbs, not dead buttons. Save is
# automatic (autosave), so the header no longer needs a Save button; the
# explicit "Save now" verb remains in the project radial menu.
for needle, name in (
    ("activity.quickExport()", "top bar Export is wired"),
    ("activity.showAspectPicker()", "aspect chip opens the aspect picker"),
    ("activity.openDiagnostics()", "settings opens diagnostics"),
):
    contains(injector, needle, name)

radial = (SRC / "editor/RadialMenus.kt").read_text()
check("project menu keeps explicit Save now", "fun saveNow()" in editor and 'h.saveNow()' in radial)

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
    ("activity.recordBtn = this", "transport record button exposed to the activity"),
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
contains(reconf, "StudioLayoutInjector.inject(this, rootFrame)",
         "configuration change re-injects the workspace")
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
contains(method("setSheet", "fun"), "setFullCanvas(false)", "opening a sheet exits Full Canvas")
contains(method("setFullCanvas"), "fullCanvas = on", "Full Canvas state toggles")

# recChip (recording indicator) must respect Full Canvas.
check("recording chip respects Full Canvas",
      "recChip.visibility = if (fullCanvas) View.GONE else View.VISIBLE" in editor)

# Retained main-activity behaviours from the pre-redesign code.
contains(method("applyOrientationFor"), "SCREEN_ORIENTATION_UNSPECIFIED",
         "main's aspect-independent phone orientation retained")
contains(method("onDestroy", "override fun"), "removeOnGlobalLayoutListener",
         "main's layout listener cleanup retained")

# ------------------------------------------------- cross-file dependencies ---
for rel, needle in (
    ("editor/StageView.kt", "Compositor.chromeRect"),
    ("editor/PreviewEngine.kt", "LayerType.IMAGE"),
    ("core/Model.kt", "fun placeNewPip"),
    ("export/CompositionRecorder.kt", "ClipCursor"),
    ("editor/SourceDock.kt", "private val ROW_DP = 52"),
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
