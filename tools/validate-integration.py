#!/usr/bin/env python3
"""Static merge guards for the full-bleed sidebar studio (SIDEBAR_STUDIO_PLAN).

Since 2026-09-08 the editor chrome is a full-bleed canvas with ALL controls
floating over it (docs/SIDEBAR_STUDIO_PLAN.md §2, implementation report §8):
StudioLayoutInjector builds the 100%-screen stage, the floating top strip,
the 7-section sidebar, the quick bar, the single-row timeline pill (with the
RECORD verb), the chip row, snack bar and progress overlay. EditorActivity
keeps the engine, camera, recording, export and the sidebar's dynamic section
renderers. The radial wheel, the tab panels and the sheet are retired.

These guards check that the new architecture ships as one coherent unit and
that rotation re-layout stays lifecycle-safe. They check integration wiring,
not Android runtime behaviour — real inset, rotation, gesture and recording
smoke tests still require an Android device.
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

# ---------------------------------------- full-bleed canvas (the core rule) ---
# The canvas owns 100% of the screen: ONE orientation-independent layout, the
# stage is MATCH_PARENT and created before any chrome, and the only insets it
# ever receives are the system bars / cutout.
check("injector builds one orientation-independent workspace",
      "if (isLandscape)" not in injector)
check("stage created and host bound exactly once",
      count(injector, "val stage = StageView(activity)") == 1
      and count(injector, "activity.stage = stage") == 1
      and count(injector, "stage.host = activity") == 1)
check("stage is added before the floating chrome",
      injector.find("root.addView(stage,") < injector.find("buildTopStrip(activity)"))
contains(method("applyViewportInsets", "fun"),
         "setViewportInsets(sysL, sysT, sysR, sysB)",
         "canvas receives only system-bar / cutout insets (chrome never shrinks it)")

# ------------------------------------------- StudioLayoutInjector: chrome ---
# The sidebar carries the seven labelled sections.
check("sidebar builds the seven sections",
      count(injector, "section(a, content, ") == 7)
for needle, name in (
    ("a.toggleSidebar()", "hamburger wired to toggleSidebar"),
    ("a.openSidebarAt(\"layers\")", "empty state opens the Layers section"),
    ("activity.showAllHidden()", "hidden pill restores all hidden sources"),
    ("activity.recChipTap()", "rec chip stops the running take / screen record"),
    ("activity.setFullCanvas(false)", "full-canvas exit button wired"),
    ("a.renameProject()", "top strip title renames the project"),
    ("a.doUndo()", "top strip undo wired"),
    ("a.doRedo()", "top strip redo wired"),
    ("a.saveNow()", "top strip Save wired"),
    ("a.quickExport()", "top strip Export wired"),
    ("a.showAspectPicker()", "aspect chip opens the aspect picker"),
    ("a.openDiagnostics()", "overflow opens diagnostics"),
    ("a.enterFullCanvas()", "top strip full-canvas wired"),
    ("a.toggleStatsHud()", "overflow stats overlay wired"),
    ("a.addCameraLive()", "sidebar add: live camera wired"),
    ("a.openCamera()", "sidebar add: camera take wired"),
    ("a.addVideo()", "sidebar add: video wired"),
    ("a.addImage()", "sidebar add: image wired"),
    ("a.addScreen()", "sidebar add: screen record wired"),
    ("a.addTextSource()", "sidebar add: text wired"),
    ("a.closeProject()", "sidebar close project wired"),
):
    contains(injector, needle, name)

# Top-strip fit ladder: no crops as the strip narrows.
check("top strip fit ladder present (tiered save/export/chip)",
      "fun fitTopStrip" in injector and "topTier" in injector and "topTier" in editor)
check("timeline width is laid out per orientation",
      "fun layoutTimeline" in injector and "fitChrome" in editor)

# The timeline is ONE row: transport and the RECORD verb together, so they can
# never collide; scrubbing drives the engine.
contains(injector, "fun buildTimeline", "timeline pill builder exists")
for needle, name in (
    ("a.engine.seekTo(v.toLong())", "timeline seek scrubs the engine"),
    ("a.scrubbing = true", "timeline drag sets scrubbing"),
    ("a.togglePlay()", "timeline play/pause wired"),
    ("a.recordButtonTap()", "timeline record wired"),
    ("a.controlsStopTap()", "timeline stop wired"),
    ("a.timeLabel.text", "timeline time label wired"),
    ("a.durationLabel", "timeline duration label wired"),
    ("a.recordBtn = rec", "record button exposed to the activity"),
):
    contains(injector, needle, name)

# Snack bar + progress overlay are chrome: the injector must build them (they
# used to be dead — built nowhere).
check("snack bar built by the injector", "buildSnackBar(activity, root)" in injector)
check("progress overlay built by the injector", "buildProgOverlay(activity, root)" in injector)

# ------------------------------------------------- EditorActivity wiring ---
# Rotation (and the first layout) must delegate to the injector, and the
# re-layout path must never bounce engine/camera lifecycle.
reconf = method("onConfigurationChanged", "override fun")
contains(reconf, "StudioLayoutInjector.inject(this, rootFrame)",
         "configuration change re-injects the workspace")
contains(reconf, "syncPreviewTarget()", "configuration change re-targets the preview")
contains(reconf, "stage.refresh()", "configuration change refreshes the stage")
for dangerous in ("startLiveCamera(", "engine.attach(", "engine.release(", "engine.pauseAll("):
    check(f"rotation does not call {dangerous}", dangerous not in reconf)

# First build must go through the injector too (not a second chrome).
build_ui = method("buildUi")
contains(build_ui, "StudioLayoutInjector.inject(this, root)", "buildUi injects the Studio workspace")
contains(build_ui, "setContentView(root)", "buildUi installs the root view")

# Ticks: transport UI ~20 Hz, HUD ~2 Hz, HUD never in Full Canvas.
tick = method("onTick")
contains(tick, "lastUiTickMs", "transport tick throttled")
contains(tick, "lastHudMs", "HUD tick throttled")
contains(tick, "!fullCanvas &&", "HUD ticks do not escape Full Canvas")

# Selection / long-press / back: the sidebar is the navigation model now.
contains(method("select", "override fun"), "refreshAll()", "selection refreshes the chrome")
lp = method("onLongPressCanvas", "override fun")
contains(lp, 'setSection(this, "source", true)', "long-press a source opens its SOURCE section")
contains(lp, 'openSidebarAt("layers")', "long-press empty canvas opens LAYERS")
back = method("onBackPressed", "override fun")
contains(back, "setFullCanvas(false)", "back exits Full Canvas first")
contains(back, "setSidebarOpen(this, false)", "back closes the sidebar second")

# Source section: the advanced sheet's full verb set, versioned so slider
# drags survive refreshes.
adv = method("refreshSourceSection")
for needle, name in (
    ("sourceVersion", "source section re-renders on version bump"),
    ("Opacity", "source opacity present"),
    ("Volume", "source volume present"),
    ("rebuildLightRows(body, l)", "source light rows present"),
    ("Set as background", "source set-as-background present"),
    ("Delete", "source delete present"),
    ("duplicateLayer(l)", "source duplicate present"),
):
    contains(adv, needle, name)
contains(method("openSourceSection", "fun"), "setFullCanvas(false)",
         "opening the source section exits Full Canvas")

# Audio section: per-clip mute/solo/volume reach the controller and the engine.
check("audio section builder exists", "fun refreshAudioSection()" in editor)
aud = editor[editor.find("fun refreshAudioSection("):]
aud = aud[:aud.find("fun rebuildRecordSection(")]
for needle, name in (
    ("audioRenderedKey", "audio section re-renders on version bump"),
    ("ctrl.toggleMuted(l.id)", "audio mute wired"),
    ("ctrl.toggleSolo(l.id)", "audio solo wired"),
    ("engine.setVolume(l, v / 100f)", "audio volume wired to engine"),
    ("pushUndoLight()", "audio volume pushes undo"),
):
    contains(aud, needle, name)

# Record / Canvas / Export sections reach the real verbs.
contains(method("rebuildRecordSection"), "togglePlay()", "record section: master play")
contains(method("rebuildRecordSection"), "rebuildLightRows(body, null)", "record section: light")
canv = editor[editor.find("fun rebuildCanvasSection("):]
canv = canv[:canv.find("fun rebuildExportSection(")]
for needle, name in (
    ("changeAspect(a)", "canvas section: aspect wired"),
    ("enterFullCanvas()", "canvas section: full canvas wired"),
    ("fitAllSources()", "canvas section: fit all wired"),
    ("setBgColor(color)", "canvas section: background wired"),
):
    contains(canv, needle, name)
contains(method("rebuildExportSection"), "quickExport()", "export section: export video wired")
exps = editor[editor.find("fun openExportSettings("):]
exps = exps[:exps.find("// ================= empty state / hidden pill =================")]
contains(exps, "saveExportPrefs", "export settings persist")
contains(method("quickExport", "fun"), "runExportFromPrefs()",
         "quick export reuses the saved settings (T-04)")

# Quick bar: one icon per verb for the selection, over the canvas.
qb = method("refreshQuickBar")
for needle, name in (
    ("ctrl.toggleVisible(l.id)", "quick bar hide/show wired"),
    ("openSourceSection(l)", "quick bar more opens the SOURCE section"),
    ("ic_delete", "quick bar delete present"),
    ("toggleCameraRecord(l)", "quick bar camera take wired"),
):
    contains(qb, needle, name)

# Every delete routes through one safe path (live camera + decoder teardown).
check("deleteSourceSafely exists", "fun deleteSourceSafely(l: Layer)" in editor)
check("all delete surfaces route through deleteSourceSafely",
      count(editor, "deleteSourceSafely(l)") >= 3)

# Full Canvas state + recChip respect it.
contains(method("setFullCanvas", "fun"), "fullCanvas = on", "Full Canvas state toggles")
check("recording chip respects Full Canvas",
      "recChip.visibility = if (fullCanvas) View.GONE else View.VISIBLE" in editor)

# The radial and the tab-panel era are retired (Rule 1).
check("no radial references left in the editor",
      "RadialMenus" not in editor and "RadialMenuView" not in editor)
check("no radial references left in the injector",
      "RadialMenus" not in injector and "RadialMenuView" not in injector)
check("no tab-panel fields left in the editor",
      all(s not in editor for s in ("sourcesPanel", "controlsPanel", "mixerPanel", "sheetTab")))
for gone in ("RadialMenus.kt", "RadialWheel.kt", "SourcesPanel.kt", "MixerPanel.kt",
             "ControlsPanel.kt", "PropertiesPanel.kt", "EffectsPanel.kt"):
    check(f"retired: {gone} deleted", not (SRC / "editor" / gone).exists())

# Retained main-activity behaviours from the pre-redesign code.
contains(method("applyOrientationFor"), "SCREEN_ORIENTATION_UNSPECIFIED",
         "main's aspect-independent phone orientation retained")
contains(method("onDestroy", "override fun"), "removeOnGlobalLayoutListener",
         "main's layout listener cleanup retained")

# ------------------------------------------------- cross-file dependencies ---
for rel, needle in (
    ("editor/StageView.kt", "Compositor.chromeRect"),
    ("editor/StageView.kt", "fun setViewportInsets"),
    ("editor/PreviewEngine.kt", "LayerType.IMAGE"),
    ("core/Model.kt", "fun placeNewPip"),
    ("export/CompositionRecorder.kt", "ClipCursor"),
    ("editor/SourceDock.kt", "private val ROW_DP = 52"),
):
    text = (SRC / rel).read_text()
    contains(text, needle, f"preserved integration dependency {rel}: {needle}")

# The StageView/PreviewEngine pair must still be created together by the
# injector (preview draws through the engine).
check("preview engine referenced by the editor", "engine.attach(" in editor)

for name in passed:
    print("  OK ", name)
for name in errors:
    print("  FAIL", name)
print(f"{len(passed)} checks passed, {len(errors)} failed")
if errors:
    raise SystemExit(1)
print("BRANCH INTEGRATION STATIC VALIDATION OK")
