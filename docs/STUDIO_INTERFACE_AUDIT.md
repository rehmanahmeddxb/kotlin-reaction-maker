# Studio Interface Audit — what is real, what is fake, what is broken

**Date:** 2026-09-06 · **Scope:** the studio screen (`EditorActivity` + the
`editor/` package) as it exists at commit `9e6e5b1` on branch
`arena/01a075b7-kotlin-reaction-maker`.
**Method:** read every line of the layout code and traced every button to
the function it calls, then traced that function to the engine / recorder /
exporter. Nothing below is a guess from a screenshot — each finding cites
the file and line. No device build was possible in this sandbox, so
finding 1.1 (which of two layouts actually appears) should be confirmed
with one phone screenshot; everything else is deterministic from the source.

The question was: *"Simple recording works (camera + local video → record →
stop → save). But the buttons are misplaced. Audit the interface: what is
wrong and why, what controls / sources / mixer settings are real and what
are not, and can this be called a professional app?"*

Short answer: **the recording pipeline is real and reasonably serious; the
interface on top of it is not.** Roughly half of the studio UI code is
either never attached to the screen or is a placeholder, and the visible
half is a rough first-pass layout with no design system. Details follow.

---

## 0. Verdict up front

| Area | State | Professional? |
|---|---|---|
| Recording pipeline (camera + clip → encoder → MP4 → Gallery) | Real; works; volume / mute / solo honoured live | Close to it |
| Export pipeline (H.264 / HEVC / VP8 / VP9, mute / solo / volume) | Real | Yes |
| Sources panel | Real list; wrong actions on two of its buttons; a dead "Camera" row | No |
| Audio Mixer | Real per-clip volume / mute / solo — but drawn as flat % sliders with a broken mute indicator, no meters, no mic strip, no master | No |
| Properties ("Props") tab | Works only via the Sources → Properties button; goes stale on re-select; empty otherwise | No |
| Effects tab | **Placeholder text. No effects exist anywhere in the app.** | No |
| Timeline strip | Static labels built once; never updates when sources change | Fake |
| Camera controls (flash, switch lens, mirror, zoom) | Implemented in code, **unreachable from any button** | Hidden |
| Screen recording, Full-canvas mode, Export settings | Implemented, **unreachable from any button** | Hidden |
| Radial wheel, Source Dock, Quick Control Bar, Stats HUD, REC chip, Snackbar, Progress overlay | **Never created / never attached** | Ghost code |
| Portrait layout | No Add / Camera / Text / Undo buttons at all | No |
| Layout system | Percent-weighted rows with raw Material `Button`s; nothing is dp-sized | No |

**Can we call it a professional app with this interface? No.** It is a
professional *engine* wearing a prototype UI. The good news is that the
gap is UI wiring, not architecture — nearly every missing control already
has a working function behind it.

---

## 1. Why the buttons look misplaced (root causes)

### 1.1 Two layouts fight for the same screen; the wrong one can win

`applyOrientationFor()` (`EditorActivity.kt:259`) forces a **16:9 project
into landscape** and a **9:16 project into portrait**. `StudioLayoutInjector.
inject()` (`StudioLayoutInjector.kt:59-61`) then decides which chrome to
build by checking `displayMetrics.widthPixels > heightPixels` **at the
moment it is called** — i.e. in `onCreate`, *before the rotation the
activity itself just requested has happened*. On a phone held upright and
opening a 16:9 project, it builds the **portrait** chrome, the activity
rotates to landscape, and `onConfigurationChanged` (`EditorActivity.kt:360`)
rebuilds. That rebuild is a full `removeAllViews()` + re-inject, which
throws away the timeline "tracks", the tab selection and the scroll
position. Any timing glitch here leaves you looking at the portrait
layout in a landscape window — a 50 %-height canvas strip, a 20 % panel
and a 15 % "timeline" all stretched sideways. That alone produces the
"everything is in the wrong place" impression.

### 1.2 The whole screen is percent-weighted, not sized

Every band of the UI is a `LinearLayout` weight
(`StudioLayoutInjector.kt`, lines 110, 149, 155, 189, 199, 271, 326, 338,
347, 476, 480):

```
landscape: top 7 % | [rail 8 % · canvas 67 % · panel 25 %] 61 % | timeline 22 % | transport 10 %
portrait : top 7 % | canvas 50 % | panel 20 % | timeline 15 % | transport 8 %
```

Percentages do not know how tall a button is. On a 360 dp-tall landscape
phone the top bar is ~25 dp and the transport bar ~36 dp, while the
controls inside them are 30–44 dp, so they clip or overflow. On a tall
tablet the same rows become huge empty strips. Professional editors size
chrome in dp and give the *canvas* whatever is left — the reverse of what
is done here.

### 1.3 Raw `android.widget.Button` inside short rows

The five panel tabs (`createTab`, `StudioLayoutInjector.kt:243-256` and
`395-408`), the four Sources buttons (`SourcesPanel.kt:81-99`) and the
Mixer M / S chips (`MixerPanel.kt:186-206`) are stock Material `Button`s.
With the app theme (`Theme.Material.NoActionBar`) a `Button` carries a
48 dp min-height, elevation, an all-caps default and 16 dp side padding.
The code fights this piecemeal (`minHeight = 0`, `isAllCaps = false`,
10–11 sp text) and loses in different ways in each place, which is why
the tabs, the Sources buttons and the transport pills all have different
heights, corner radii and baselines. `StudioLayoutInjector.kt:24-33` even
has a comment acknowledging this exact problem for the top bar — the fix
(`pillBtn`) was applied to two buttons and nowhere else.

### 1.4 Fifteen layout functions are stubs

`EditorActivity.kt` still contains the full Step-5 / Phase-2 chrome
logic, but these fifteen functions were hollowed out to `{ }`:

```
capPanelHeight buildTopBar buildSheet buildTabBar buildTransportBar
updateSourceStrip buildSideRail buildFullCanvasExit applyViewportInsets
buildSourcesPanel buildMixerPanel buildExportPanel refreshQuickBar
updateEmptyState updateHiddenPill
```

Everything that used to position the canvas around the panels
(`applyViewportInsets`), cap the sheet height (`capPanelHeight`), show an
empty-project prompt (`updateEmptyState`) or rebuild the source strip is a
no-op. The callers still run — they just do nothing.

### 1.5 Twenty-six views are created and never attached

`StudioLayoutInjector.inject()` lines 62-95 instantiate placeholder views
so `lateinit` fields don't crash: `emptyOverlay, quickBar, panelDivider,
sheet, dockContainer, recChip, statsHud, hiddenPill, studioBtn, tabBar,
sourceStripWrap, sourceStrip, topBar, quickWrap, fullExitBtn, launchRow,
sideRail, railContent` (and initially `playBtn, seek, recordBtn …` which
are later replaced by the real ones). **None of the first group is ever
`addView`-ed.** Code all over `EditorActivity` sets their text and
visibility (`recChip.visibility = VISIBLE` at 2236/2291, `statsHud` at
2614) — into a view that lives only in memory. That is why there is no
REC indicator, no stats HUD, no "hidden sources" pill.

### 1.6 The radial wheel is never constructed

`wheel` (`EditorActivity.kt:113`) is a `lateinit` that is **never
assigned**. `wheelReady()` is therefore always `false` and every
`openWheelLevel / openRootWheel` call returns silently (703, 719). The
consequences are concrete:

* The **"Set up the reaction first → Add now"** dialog button
  (`EditorActivity.kt:1903`) does nothing when tapped.
* Long-press on the canvas (`onLongPressCanvas`, 1253) does nothing.
* Everything only the wheel exposed (§3) is unreachable.

`RadialWheel.kt` (526 lines) and `RadialMenus.kt` (393 lines) are shipped,
compiled, dead code. So are `SourceDock.kt` (305 lines — rebuilt on every
selection change into a container that is not in the tree) and
`ControlsPanel.kt` (168 lines — never instantiated). That is **1 438
lines of UI that the user cannot see**, against **988 lines that they
can**.

---

## 2. Control-by-control: what each visible thing actually does

### 2.1 Top bar (`StudioLayoutInjector.kt:105-148`)

| Control | Real? | Notes |
|---|---|---|
| `← Studio` | Yes | `onBackPressed()` — but see 4.3: the first press after using *Properties* is swallowed. |
| `16:9 ▾` aspect chip | Yes, but wrong text | Hard-coded `"16:9 ▾"`; `updateAspectChip()` is never called at startup, so a 9:16 or 1:1 project shows "16:9" until you change it. |
| ⚙ settings icon | Yes | Opens **Diagnostics**, not settings. Label lies. |
| `Save` | Yes | `flushSave()` + toast. |
| `Export` | Yes | **Silent quick export** at 720p30 H.264. There is no way to reach the codec / resolution / quality picker the README advertises — `buildExportPanel` is a stub and `openExportPanel()` is wheel-only. |
| Project name / "✓ Saved" status | **Missing** | `updateName()` (1772) looks for views tagged `"name"` / `"meta"`; nothing in the layout carries those tags. |

### 2.2 Left tool rail — landscape only (`StudioLayoutInjector.kt:161-188`)

| Icon | Action | Real? |
|---|---|---|
| ➕ Add | `pickMedia(true)` | Yes (video picker) |
| 📷 Camera | `addLiveCamera()` | Yes |
| 🎞 Video | `pickMedia(true)` | Yes — **identical to Add**, redundant |
| 🖼 Image | `pickMedia(false)` | Yes |
| T Text | `addText()` | Yes |
| ↶ Undo / ↷ Redo | Yes | |

Missing from the rail although the code exists: **Screen record**
(`startScreenCapture`, no callers outside the wheel), **Camera take**
(`openCamera` → `CameraActivity`, only reached as a *fallback* when the
live camera fails, lines 2215/2223).

**In portrait there is no rail at all** (lines 336-490 build no tool
buttons). A 9:16 project can only add sources through the Sources panel
shortcuts — and the first of those is broken (2.3).

### 2.3 Sources panel (`SourcesPanel.kt`, bound at `StudioLayoutInjector.kt:494-507`)

The list itself is honest: one row per layer, top of list = front,
eye toggles `visible`, ▲▼ call `ctrl.moveZ`, tap selects. Good.

The buttons are not:

| Button | What it says | What it does | Verdict |
|---|---|---|---|
| Empty-state row **"Camera — Add the live camera"** | Adds the camera | `onAdd()` → `pickMedia(true)` → **opens the video file picker** | **Wrong action.** In portrait this is the *only* camera entry point, so a portrait user cannot add a camera at all. |
| `+ Add` | Add a source | Opens the video picker only — no menu for camera / image / text / screen | Under-delivers |
| `− Remove` | Remove selected | Deletes immediately, **no confirmation, no undo toast** (snackbar never built, 1.5), **not blocked during recording** (`removeSelectedSource` 1334 has no `guardRecording`, unlike the advanced-sheet delete at 1128) | Dangerous |
| `Hide` | Hide selected | `toggleVisible` — label never flips to "Show" | Half |
| `Properties` | Open properties | Hides the Sources view, calls `openAdvancedSheet(l)` which fills `panelContent` (the Props tab body) | Works once; see 4.2 |

There is **no rename, no duplicate, no lock, no fit/fill** in the panel —
these exist in the (unreachable) wheel and the advanced sheet only.

### 2.4 Audio Mixer (`MixerPanel.kt`, bound at `StudioLayoutInjector.kt:509-519`)

What is real — and this matters, because it is genuinely wired end to
end:

* **Volume slider** → `engine.setVolume()` (`PreviewEngine.kt:502`) which
  sets `l.volume` and the live `MediaPlayer` gain; the recorder re-reads
  `l.volume` every frame (`CompositionRecorder.kt:494`) and the exporter
  uses it at `Exporter.kt:283/612`. **Preview = recording = export.** ✔
* **M (mute)** and **S (solo)** → `ctrl.toggleMuted / toggleSolo`; effective
  mute = `muted || (anySolo && !solo)` in preview (`PreviewEngine.kt:195`),
  recorder (`CompositionRecorder.kt:493`) and exporter (`Exporter.kt:272`).
  ✔
* An honest "Silent — another source is soloed" note.

What is not:

| Issue | Where | Effect |
|---|---|---|
| Mute chip label is `"M"` whether muted or not (`if (effMuted) "M" else "M"`) | `MixerPanel.kt:124` | Only the fill colour changes; a copy-paste bug. |
| Strips are filtered by `isClip()` (`MixerPanel.kt:72`) | | **The live camera / microphone has no strip.** Mic gain exists (`CompositionRecorder.micGain`) but is internal by decision (`CompositionRecorder.kt:184`). The mock-up's "Camera Mic" and "External Mic" channels do not exist. |
| No master fader | | `masterGain` is applied only in the recorder, not the exporter, so surfacing it would make record ≠ export — the comment at `CompositionRecorder.kt:186-190` explains this correctly. Honest, but a "mixer" without a master reads as unfinished. |
| No level meters of any kind | grep for peak/rms/meter → nothing | The mock-up's green/yellow/red bars were never implemented. A mixer with no metering cannot tell you whether the mic is clipping. |
| Percent scale, not dB | | Volume is `0–100 %` linear on a horizontal `SeekBar`. Every audio tool the user has seen uses vertical faders in dB. Not wrong, but not what the picture promised. |
| No "monitor mute" (hear preview yes/no) | `monitorMuted` is set only internally (1996/2001/2034) | You cannot silence playback while framing a shot. |
| No per-strip pan, no audio-only sources (`LayerType` has no AUDIO; picker filters `video/*`) | `Model.kt:18`, `EditorActivity.kt:1441` | "Background Music" from the mock-up is impossible to add. |

### 2.5 Props tab

The tab shows `activity.panelContent`, which starts as the text *"Select an
object to edit its properties"*. **Selecting a source does not populate it**
— `select()` (1232) only rebinds Sources and Mixer. The only thing that
fills it is Sources → *Properties*. Once filled it has a real, useful set
of controls (Fit/Fill, Hide, Lock, Opacity, Play/Pause, Loop, Mute, Solo,
Volume, text edit / colour / size / shadow, z-order, corner anchors,
Set-as-background, Duplicate, Delete-with-guard). But:

* It is **not refreshed when you select another source** — the panel keeps
  the previous source's name and its buttons keep mutating the previous
  source (closures capture `l`). This is a correctness bug, not a
  cosmetic one.
* Each button calls `openAdvancedSheet(l)` again to refresh, which does a
  `removeAllViews()` + overshoot animation on every tap (1148).

### 2.6 Effects tab (`EffectsPanel.kt`, 23 lines)

A `TextView` reading *"Select a source to apply effects"*. There is no
effects system — no filters, no colour, no LUT, no chroma key, no
transitions — anywhere in the codebase. **This tab should not exist until
something is behind it.** Showing it is the single most damaging thing to
the "is this professional" question: it promises a feature category the
app does not have.

### 2.7 "X" tab

Hides the whole panel container **including the tab row that holds the X**
(`rightPanel.visibility = GONE` at 251 / `contextPanel` at 403). There is
no button to bring it back; the only way is to rotate the phone or reopen
the project. One-way door.

### 2.8 "Timeline" band (`StudioLayoutInjector.kt:271-324`, `476-...`)

A `SeekBar` (real — it scrubs the master clock) plus one `TextView` per
layer, **built once at inject time**. Adding, removing or renaming a
source does not touch it (no other file references `timelineContent`).
There are no clips, no in/out points, no trimming, no time ruler, no
playhead over tracks. Calling it a timeline is generous; it is a list of
names that goes stale. It also steals 15–22 % of the screen height from
the canvas.

### 2.9 Transport bar (`buildTransport`, 522-573)

| Control | Real? | Notes |
|---|---|---|
| ▶/⏸ | Yes | Icon syncs from `onTick` ✔ |
| `● Record` pill | Yes | `recordButtonTap()`; label becomes "● ADD CAMERA + VIDEO TO RECORD" etc. when not ready — informative, but the "Add now" button in its dialog is dead (1.6). |
| `⏹ Stop` | Yes | Stops recording, else pauses playback. |
| `00:00:00 / 00:00:00` | Yes | Duration updates on structure change (1416). |

Missing entirely: a **recording timer / REC indicator** (recChip never
attached), **elapsed-time while recording**, **flash**, **switch camera**.

### 2.10 What happens during Export / Record finish / Delete

`buildSnackBar()` and `buildProgOverlay()` are never called (no callers of
either). Therefore:

* `showProgress("Exporting → …")` at export start and `"Finishing
  recording"` at record stop write into `null` views — **the user gets no
  progress bar and no Cancel button** during an export that may take
  minutes. The app just looks frozen until the final `AlertDialog`.
* All fifteen `showSnack / showUndoSnack` call sites are silent —
  **"Deleted X — UNDO"**, export error messages, hide feedback: none
  appears.

---

## 3. Implemented but unreachable (the hidden app)

Every one of these has working code in `EditorActivity` and is exposed
**only** through the wheel that is never built:

| Feature | Function | Lines |
|---|---|---|
| Screen recording as a source | `startScreenCapture()` | 1463 |
| Record a camera take in the fullscreen recorder | `openCamera()` (only as failure fallback) | 1455 |
| Switch front / back camera | `switchCameraFacing(l)` | 2456 |
| Front / back / both **flash** | `toggleFrontTorch / toggleBackTorch / toggleBothTorch` | 2507-2560 |
| **Screen light** (selfie fill) | `toggleScreenLight()` | 2564 |
| Mirror the camera | `toggleMirror` (`setMirror` exists in LiveCamera) | — |
| Full-canvas / immersive mode | `setFullCanvas(true)` | 490 |
| Export settings (codec / res / quality / fps) | `openExportPanel` → stub | 918 |
| Canvas background colour (7 presets) | `setBg()` | 2424 |
| Fit all sources | `fitAllSources()` | 2425 |
| Snapshot frame to image | `snapshotFrame()` | 2358 |
| Stats overlay toggle | pref `PREF_STATS_HUD` | — |
| Rename project | `renameProject()` | 2432 |
| Corner anchors / centre + unrotate / duplicate / lock (wheel copies) | via `ctrl` | — |

The README describes most of these as shipped features. From the user's
seat they do not exist.

---

## 4. Behavioural bugs found while tracing (not cosmetic)

1. **Sources → "Camera" shortcut opens the file picker** (`SourcesPanel.kt:112`
   → `onAdd` → `pickMedia(true)`). Should call `addLiveCamera()`.
2. **Props panel targets a stale source** after re-selection (2.5).
3. **Back button eaten once** after opening Properties: `openAdvancedSheet`
   sets `sheetTab = "adv"` (1144); `onBackPressed` sees `sheetTab != null`,
   calls `setSheet(null)` (which only hides never-attached views) and
   returns (354). Second press works. Feels like a hang.
4. **Remove while recording is allowed** from the Sources panel (2.3) —
   the recorder holds `ClipControl`s keyed by layer id and will find
   `layerById == null` mid-take (`CompositionRecorder.kt:489-491` skips it,
   so no crash, but the take silently loses a source).
5. **Aspect chip shows the wrong ratio** at startup (2.1).
6. **"Add now" in the record-readiness dialog does nothing** (1.6).
7. **Long-press on canvas does nothing** (1.6) although `StageView` still
   detects it and calls the host.
8. **Layout built for the wrong orientation on open** (1.1) then rebuilt,
   losing panel state.
9. **Silent export** (2.10): no progress, no cancel, no error surface.
10. **Portrait users cannot add a camera** (2.2 + bug 1) and cannot undo.

---

## 5. What is genuinely good (keep it)

* The **source model** (`Model.kt`, `Sources.kt`): one command layer
  (`SourceController`) with hide ≠ delete, pause = hold frame, solo as a
  computed state, fit/fill per source. This is the right foundation.
* **Preview = record = export** for volume / mute / solo / geometry. Most
  hobby editors get this wrong; this one does not.
* `StageView` gestures: select, drag with snap, 8-handle resize, rotate,
  pinch; selection frame follows the *visible* bounds.
* `CompositionRecorder` / `Exporter` / `MediaSave`: real MediaCodec
  pipelines with verified saves and clear failure dialogs.
* Undo / redo + autosave + snapshot recovery.

None of that needs to change to make the studio professional.

---

## 6. What "professional" would actually require (the gap, honestly)

A viewer will judge the app on the first screen of the studio. Today that
screen fails five tests every commercial mobile editor passes:

1. **Every visible control does what its label says** — currently 3 do not
   (Camera row, ⚙ settings, "16:9" chip) and 1 tab is empty (Effects).
2. **Nothing visible is stale** — timeline tracks, Props panel, mute label.
3. **Feedback for long operations** — progress + cancel for export and
   recording finish; a REC timer while recording.
4. **A single sizing system** — dp-sized chrome, canvas takes the
   remainder, one button component used everywhere, one 48 dp tap-target
   rule (the code already has the note at `StudioLayoutInjector.kt:24-33`,
   it just was not applied).
5. **Camera controls one tap from the camera** — flash, switch lens,
   mirror, screen light. For a *reaction* app these are not advanced
   features; they are the features.

Everything in 1–3 and 5 is wiring to functions that already exist. Item 4
is a rewrite of `StudioLayoutInjector.kt` (574 lines), not of the app.

---

## 7. Recommended order of work

Ordered by user-visible damage per hour of work; each item is
independently shippable.

| # | Change | Fixes | Effort |
|---|---|---|---|
| 1 | Decide the orientation **before** building chrome (use the requested orientation, not `displayMetrics`), or build once after rotation settles | 1.1, bug 8 | S |
| 2 | Remove the **Effects** tab and the fake **timeline** band; give the height to the canvas | 2.6, 2.8 | S |
| 3 | Sources: Camera row → `addLiveCamera()`; `+ Add` → a 5-item chooser (Camera · Video · Image · Text · Screen); Remove → confirm + `guardRecording`; Hide label flips | bugs 1, 4, 10 | S |
| 4 | Props: rebuild on every `select()`; drop the `"adv"` sheetTab so Back is not swallowed | 2.5, bug 3 | S |
| 5 | Attach a real **progress overlay** and **snackbar** to `rootFrame` (the builders already exist, 573/626 — call them) | 2.10, bug 9 | S |
| 6 | Camera strip under the canvas when a live camera exists: Flash · Switch · Mirror · Screen light · Take | §3 | M |
| 7 | Transport: REC timer + red dot while recording; Export button → the existing codec/quality picker (rebuild `buildExportPanel` as a dialog) | 2.9, 2.1 | M |
| 8 | Mixer: fix the M label; vertical dB fader per clip; **a camera-mic strip** (even if gain stays fixed, show the state); a simple peak meter fed from the recorder's mixed PCM | 2.4 | M–L |
| 9 | Replace all raw `Button`s with `pillBtn`; dp-size every bar; delete the 15 stubs and the 26 phantom views; delete or wire `RadialWheel / RadialMenus / SourceDock / ControlsPanel` | 1.2–1.6 | L |
| 10 | Portrait: add a bottom tool row mirroring the landscape rail | 2.2 | S |

After 1–5 the app stops lying to the user. After 6–8 it becomes usable
as a reaction studio. After 9–10 it can reasonably be called
professional.
