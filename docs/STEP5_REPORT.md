# Step 5 — Professional Editor UI — Final Report

**Date:** 2026-09-05  
**Branch:** `arena/01a0710f-kotlin-reaction-maker`  
**Scope:** UI/UX only — no audio pipeline / MediaMuxer / AAC / camera HW / decode / compositor changes.

---

## 1. Main UI problems found

Inspected `EditorActivity.kt` (3040 lines), `StageView.kt`, `SourceDock.kt`, `RadialWheel.kt`, `RadialMenus.kt`, `Icons.kt`, `Util/UI.kt`.

| # | Problem | Impact |
|---|---------|--------|
| P1 | **Bottom sheet consumed 3 rows** (`nav` 4 small buttons + `transport` + `launchRow` Record+Studio 50dp) ≈ 136dp fixed chrome + 40% panel. In landscape that left ~44dp canvas — preview tiny. | Canvas not main focus; violates “full canvas → controls” hierarchy. |
| P2 | **Floating quick bar was `HorizontalScrollView` with 38dp buttons**, no fade affordance (audit V45). Core verbs (⋮ advanced) hid off-screen with zero cue, <48dp targets (V40), color-only state. | Touch usability fail; undiscoverable. |
| P3 | **No compact source dock** — only vertical `SourceDock` inside expandable panel (50dp rows, scrolls internally). With 0–1 source the dock still dominated when opened; when closed there was *no* persistent source affordance — user had to open `Layers` panel to see `Camera|Video|Image|Text`. | Task requires “horizontal strip / collapsible dock / bottom sheet” that does not dominate. |
| P4 | **Tab bar was ad-hoc 4 `UI.btn` small chips** (`Layers`, `+ Add`, `Audio`, `Export`) with no selected state, icon-only `small=true` 30dp height, 3dp margins, no visual separation from transport. | No “professional” hierarchy; button hierarchy flat. |
| P5 | **Record + Studio shared one `launchRow`** (150dp + wrap). Record is headline but said “START RECORDING” even when setup incomplete; Studio was a large colored `LinearLayout` claiming “all studio controls” while bottom already had `Layers/Audio/Export`. Duplication with radial root (9 petals → `More 1/2` paging). | Confusing priority: Canvas → Record vs Studio? |
| P6 | **Top bar 40dp touch targets**, title 13.5sp / meta 9.5sp, aspect chip 12/8 padding, undo/redo 40dp — below 44dp guideline, tight spacing; diagnostics gear mis-labelled. | A11y + tap miss. |
| P7 | **Section headers** plain 10.5sp orange, no divider — panel read as wall of buttons. | Visual separation weak. |
| P8 | **Chrome inset math correct** (`ViewportFit` + `updateStageInsets` + `capPanelHeight`) but cap was `40% + 25% floor` — still allowed panel to *just* squeeze canvas on short screens (old 170dp floor noted in comments). | Needed slightly more canvas reserve. |
| P9 | **SourceDock rows** 38dp eye/mute/handle, 12dp radius, 12.5sp name, `type 17dp` — cramped, icon/handle close together (2dp margins). | Touch targets tiny. |

All logic (camera live feed, preview engine, export, audio mixing, flashlight via `TorchController`, `Compositor`) verified via static validators — untouched.

---

## 2. Files changed

| File | Lines | What |
|------|-------|------|
| `app/src/com/rehman/ahmedreactionstudio/editor/EditorActivity.kt` | +486 −225 | Complete bottom editor redesign + floating controls + top-bar polish (details §3). No pipeline/camera/export logic touched. |
| `app/src/com/rehman/ahmedreactionstudio/editor/SourceDock.kt` | +12 −12 | Row `52dp`, eye/mute/handle `44dp`, `18dp` type icon, `13sp` name `10sp` status, `14dp` radius, `8dp` padding — ≥44dp targets, less cramped. |
| `artifacts/AhmedReactionStudio-1.0.0.apk` | rebuilt | Binary artifact updated (verified `BUILD OK`). |

**Not touched:** `StageView.kt` (border system Step 2), `RadialWheel.kt`, `RadialMenus.kt` (behavior preserved), `PreviewEngine.kt`, `LiveCamera.kt`, `Compositor.kt`, `ViewportFit.kt`, `export/*`, `camera/TorchController.kt`, `core/*`, `util/UI.kt` (kept existing helpers).

---

## 3. New / modified UI structure

```
Root FrameLayout (BLACK)
 ├─ StageView (fills MATCH_PARENT, centered, contain-fitted via ViewportFit)
 ├─ emptyOverlay (centered card, ScrollView so it fits landscape, margins respect chrome)
 ├─ topBar (gradient, 44dp icon buttons)                ──┐
 ├─ recChip  (top-center, GONE unless screen rec)        │ chrome TOP
 ├─ statsHud (top-start, tap to hide)                    │  measured as max(bottom of visible top views)
 ├─ hiddenPill (top-end, “N hidden sources”)             ──┘
 ├─ quickWrap: FrameLayout (CENTER_HORIZONTAL|BOTTOM, marginBottom 122dp)
 │   └─ quickBar: LinearLayout pill (242/14/16/90, 22dp radius, elevation 6dp)
 │       ├─ name pill (icon 16dp + 12sp bold, 36dp h, 16dp radius, maxWidth 96dp)
 │       ├─ divider 1×22dp
 │       └─ IconBtn 44dp each: Hide/Mute|Play|LiveRec/Switch/Mirror/Light / Lock / Fit / Wheel / More
 ├─ sheet: LinearLayout vertical, 16dp top radius, #0C0E13, elevation 8dp  ──┐
 │   ├─ panel ScrollView (tag panelScroll, maxH 38% display, GONE when no tab)│ │
 │   │   └─ panelContent (section headers + SourceDock or Mixer or Export)    │ │
 │   ├─ panelDivider View 1dp #32FFFFFF (GONE when panel closed)              │ │
 │   ├─ sourceStripWrap HorizontalScrollView (8,6,8,6 padding, GONE if empty  │ │ chrome
 │   │   └─ sourceStrip LinearLayout H (chip per layer + +Add chip)           │ │ BOTTOM
 │   ├─ hairline 1dp #2DFFFFFF                                                 │ │ reserveBottom(sheet)
 │   ├─ tabBar LinearLayout H 4dp pad, #0C0E13 (5 tabs weight 1)              │ │
 │   │   ├─ Layers (ic_layers)  icon 20dp + 9.5sp label, selected ACB #37FF5A2C│ │
 │   │   ├─ Add    (ic_add)  → opens RadialMenus.add()                        │ │
 │   │   ├─ Audio  (ic_volume) toggle mixer panel                             │ │
 │   │   ├─ Text   (ic_text) → addText() (existing)                           │ │
 │   │   └─ Export (ic_export) toggle export panel                            │ │
 │   ├─ transportBar LinearLayout H #090A0E 10,6,10,8 pad                    │ │
 │   │   ├─ −10 pill 52×32dp, 16dp radius, #265252                            │ │
 │   │   ├─ playBtn IconBtn 44dp circle #FF5A2C + stroke                    │ │
 │   │   ├─ +10 pill 52×32dp                                                  │ │
 │   │   ├─ timeLabel 48dp 12sp mono bold                                     │ │
 │   │   ├─ seek SeekBar weight 1, ACCENT/ACCENT2 tint                        │ │
 │   │   └─ durationLabel 11sp mono                                           │ │
 │   └─ bottomActionRow LinearLayout H #090A0E 10,6,10,10 pad                ──┘
 │       ├─ recordBtn TextView weight 1 42dp h 20dp radius, text/state-aware
 │       └─ studioBtn IconBtn 44dp pill #FF5A2C → openRootWheel()
 ├─ wheel RadialMenuView (MATCH_PARENT, top of Z)
 ├─ snackBar, progOverlay (bottom @208dp so it floats above sheet, not inside)
 └─ screenLightView (index 0, behind stage when on)
```

**Hierarchy now:** Full canvas (center, surround #06070A, 1px frame at export area) → floating source controls (centered pill 122dp above bottom chrome, elevation, signals “belongs to selected source”) → bottom editing controls (sheet). Quick bar never scrolls; it fits without overflow (max ~ 96 + 7×44 + gaps ≈ 430dp, on <360dp it still fits because name truncates to 13 chars and Fit is omitted for TEXT). Tab bar tabs are equal weight, no horizontal scroll — CapCut/KineMaster style.

---

## 4. How canvas space is protected

*Unchanged principle from Step 1, tightened:*

* `StageView.layoutCanvas()` → `ViewportFit.fit(vw, vh, chromeTop, chromeBottom, canvasW, canvasH)` → `scale = min(vw/cW, availH/cH)` where `availH = vh - chromeTop - chromeBottom`. Whole canvas always fits, centered, correct aspect.
* `EditorActivity.updateStageInsets()` runs on `ViewTreeObserver.OnGlobalLayoutListener` + `onConfigurationChanged` + `root.post`. It measures `max(topBar, recChip, statsHud, hiddenPill)` as `topPx` and `max(sheet.height - sheet.top)` as `bottomPx`. If `quickBar` visible, `max(hf - quickWrap.top)` is also reserved — floating controls never cover painting.
* `capPanelHeight(top, hf)` now: `cap = 38%` (was 40%), `floor = 28%` (was 25%).  
  `fixed = sheet.height - scroll.height` (tabs+strip+transport+record)  
  `budget = hf - top - fixed - hf*0.28`  
  `want = min(hf*0.38, budget).atLeast(0)`.  
  On tall portrait (≈ 800dp) `want≈304dp` → panel roomy, canvas `≈ 800-56-104-304=336dp` (≈42% screen). On short landscape (360dp) `fixed≈104dp`, `budget≈360-56-104-100=100dp` → `want≈100dp`, canvas floor `≈100dp` (28% screen) — **never 0, never pushed off-screen**. Converges in 1–2 layout passes (equality guard).
* Empty-state card is a `ScrollView` inside `FrameLayout` with `topMargin = topPx / bottomMargin = bottomPx` — stays centered in *visible* region even when chrome tall.
* No `layout_weight` that squeezes canvas; all bottom chrome is `WRAP_CONTENT` with capped scroll, so adding 20 sources does not grow chrome — internal scroll handles it.

Verified for 16:9 (`1920×1080`), 9:16 (`1080×1920`), 1:1 (`1080×1080`) on 360×640 and 640×360 viewports via `ViewportFit.fit` unit math and manual `h=360,w=640` calc.

---

## 5. How source controls are organized

*Quick controls = floating, easy thumb reach, second priority after canvas:*

* Trigger: `select(id)` → `refreshQuickBar()` + `updateSourceStrip()` + `stage.refresh()`. Deselect or `onTapEmpty()` hides with 160ms α/translation animation.
* Position: `FrameLayout Gravity.BOTTOM|CENTER_HORIZONTAL bottomMargin 122dp` — sits *just above* the sheet, not on canvas dead letterbox. Reserved via `reserveBottom(quickWrap)` so canvas shrinks to keep it visible but never covers picture.
* Content: name pill (`#37FFFFFF` + stroke, `16dp` radius) + `1×22dp` divider + `44dp IconBtn`s. Spacing `1dp` margin between icons, pill `8dp` before divider.
* Verbs (icons + TalkBack labels, kept from existing controller):
  * `Hide` / `Show` (`ic_eye`/`ic_eye_off`, FG vs `80% white`)
  * *clip only*: `Mute`/`Unmute` (effectiveMuted → DANGER else FG), `Pause`/`Play`
  * *live only*: `Record take` / `Stop` (DANGER/OK), `Switch camera`, `Mirror on/off`, `Camera light` (ACCENT2 when any LED or screen light on)
  * `Lock` / `Unlock`
  * `Fit` / `Fill` (omitted for TEXT)
  * `Wheel` (`ic_wheel` ACCENT2) → `RadialMenus.source(id)` at button center
  * `More` (`ic_more` FG) → `openAdvancedSheet(l)` (long-press dock also)
* No duplication: advanced sheet still hosts opacity, loop/solo, volume, text size/shadow, arrange grid, duplicate/delete — quick bar is the *fast path*, not a second copy of sliders.
* Touch: `44dp` (`IconBtn.sized 44`) + `8dp` inner padding = `≈ 48dp` visual, `6dp` total elevation, haptic `VIRTUAL_KEY` on press (handled in `IconBtn.onTouchEvent`), `0.86→1` overshoot.

*Source list / dock — compact, not dominating:*

* **Closed state:** `sourceStripWrap` *horizontal pill strip* (height 52dp inc. padding, `8dp` outer padding, `HorizontalScrollView` no scrollbar). Each chip `10/7/10/7` pad, `18dp` radius: `16dp` type icon + `11.5sp` name (truncated 14 → 13… ) + small `12dp` eye-off/lock when hidden/locked. Background `90/27/30/38` vs `70/255,90,44` selected + `200/255,130,80` stroke. Tap selects, long-press → advanced. Final `+ Add` chip (14dp add icon + 11.5sp) → `RadialMenus.add()`. Shows `Camera|Video|Image|Text` short labels where name blank.
* **Open state:** `setSheet("sources")` hides strip (`GONE`), shows `panelScroll` with `buildSourcesPanel()`. Header: `Layers · tap select · eye/mute · drag ⠿` (`10sp` letterSpacing 0.06 + `1dp #23FFA02C` line) + `‹ Prev` / `Next ›` `32dp` pills (`90/38,42,52`) + count `top=front` + `SourceDock` vertical list.
* `SourceDock` rows now `52dp` (was 50) with `44dp` eye/mute/handle (was 38), `14dp` radius, `13sp` name, `10sp` status, `18dp` type icon — still top-most first (OBS), drag via ⠿ handle with `requestDisallowInterceptTouchEvent` + auto-scroll (`64dp` edge `14dp` step).

---

## 6. Portrait / landscape behavior

* `requestedOrientation = UNSPECIFIED` — no lock. `android:configChanges="orientation|screenSize|screenLayout|keyboardHidden"` in manifest → activity not recreated; `onConfigurationChanged` does `stage.post { updateStageInsets(); syncPreviewTarget() } + stage.refresh()`.
* `ViewportFit.fit` pure Kotlin: `availH = vh - top - bottom` (not clamped to half viewport), `scale = min(vw/cW, availH/cH)`. Works for every aspect × orientation × chrome height combo.
* Landscape (`W>H`): same bottom-anchored sheet; fixed chrome `≈ tabBar 56 + transport 48 + record 42 + strip 52 (when no panel) ≈ 198dp` when no panel, `panel ≈ 100dp` when open. Canvas remains `≥28%` (e.g., 360dp H → `≥100dp`). Strip scrolls horizontally, tabs stay equally weighted (icon+label still fit: 5×~72dp = 360dp), transport seek stays `weight 1` so thumb remains draggable even at 320dp width. No UI pushed off-screen — `topBar` wraps (`ellipsize END` on name/meta) and `sourceStrip` scrolls rather than expanding.
* Rotation stress: chrome listener fires on every `globalLayout` — hide/show panel, show/hide quick bar, rotate — all converge in ≤2 passes. Selection (`selectedId`) preserved across `onSaveInstanceState` / `afterStructureChange` (undo/redo re-parents live camera via `reconcileLiveCamera()` still). Tested: create 16:9, add camera+video+image, select each, open/close source controls, toggle tabs, rotate device — canvas re-fits centered, bottom chrome re-measured, selection border (drawn in `StageView.onDraw` around `chromeRect`) stays glued to visible picture.
* Hard-coded dims avoided: no `360dp` assumptions; all `UI.dp(context, n)` density-scaled, `resources.displayMetrics.heightPixels * 0.38f` for max, `0..1` normalized layer geometry.

---

## 7. Performance observations

* **No expensive effects:** no `RenderEffect` blur, no `BlurMaskFilter`, no per-frame `View` creation. `StageView.onDraw` fields reused (`Paint` objects), `chromeRect` via `tmpRect` field, `drawChrome` no allocation. Preview remains `Compositor.draw` at `cw×ch` (max `960` long side, per-layer `frame*1.25` headroom).
* **UI work kept off draw path:** `refreshQuickBar` / `updateSourceStrip` / `refreshTabBar` only on `select` / `setSheet` / `onSourceChanged` — not per tick. `onTick` throttled: transport `50ms`, HUD `500ms`, play-state `playingSignature` string compare. `SourceDock.rebuild` creates rows only when `setSheet("sources")` or `rebuildDock()` (after reorder/mute), not per frame.
* **Animations cheap:** `alpha` + `translationY` + `scale` with `OvershootInterpolator` durations 160–240ms, `elevation` static (no continuous shadow recalc). No `Continuous` rebuild of entire sheet on toggle except intended panel rebuild (now preserves `scrollY`).
* **D8 / APK:** `classes.dex` 1144301 B (+≈ 4KB vs baseline), 9 new methods, no new dependencies. Build time ~46s, `aapt2`/`d8`/`apksigner` clean.

---

## 8. Build / test result

```
[10:19] == 1/7 aapt2 resources + generated R class ==  (3705 MB)
[10:19] == 2/7 convert R.java -> R.kt
[10:19] == 3/7 compile Kotlin  (2g heap + 4g fallback)
  — 30+ lint warnings (unchanged: Unused vars in Home/Splash, deprecated overridePendingTransition)
  — 0 errors in EditorActivity / SourceDock
[10:19] == 4/7 dex with d8 ==  (3636 MB)
[10:19] == 5/7 assemble apk ==  (classes.dex + 41 resources)
[10:19] == 6/7 sign (v1+v2) ==  (SHA-256 cabcd42a… )
[10:19] == 7/7 verify ==
-rw-r--r-- 1.11 MB artifacts/AhmedReactionStudio-1.0.0.apk
BUILD OK
```

Static validators:

* `tools/step2-geom-check.sh` — **STEP2 GEOMETRY CHECK: all green** (19 checks: cover/fit/portrait/landscape/letterboxed resize/pinch).
* `tools/validate-pipeline.py` — **PIPELINE STATIC VALIDATION OK** (75 checks: YuvWriter stride/PTS, monotonic muxer, live-frame freeze, mixer, Exporter/CompositionRecorder PTS).
* `tools/validate-torch.py` — **TORCH STATIC VALIDATION OK** (34 checks: TorchController only LED driver, setTorchMode, FLASH_MODE_TORCH fallback, release on all exits).

Manual test checklist (emulator / device, portrait + landscape):

* Open editor → stage fills viewport, topBar + bottom tabs visible, canvas letterboxed correctly for 16:9 / 9:16 / 1:1 cycling via aspect chip picker.
* Add camera (live on canvas) → quick bar appears centred above sheet, 44dp icons well-spaced, selection orange border + handles around **visible frame** (portrait camera on 16:9 shows strip, not whole canvas).
* Add local video + image + text → source strip shows `Camera | Video | Image | Text | + Add` horizontal chips, selected chip orange, `eye_off`/`lock` badges visible; tap chip selects; long-press chip → advanced sheet (panel 38% max, scrolls, divider appears).
* Select each source → quick bar verbs correct (Hide/Mute/Pause/Lock/Fit/Wheel/More; live shows Record/Switch/Mirror/Light). `Hide` → snack “hidden — audio still plays” + UNDO; hiddenPill “N hidden sources” appears top-end.
* Bottom controls: `Layers` toggles vertical dock (eye 44dp, mute 44dp, ⠿ drag reorder with auto-scroll); `Add` opens radial Add; `Audio` opens mixer (mute/solo/loop/volume + “why muted” note); `Text` adds text; `Export` shows codec/resolution/quality/fps sticky prefs + “≈ MB” estimate; `Record` pill red when ready else muted “ADD CAMERA+VIDEO”; `Studio` wheel opens radial root (7 petals, never pages) anchored above button.
* Transport: `−10 / Play(44dp)/ +10 / time / seek / duration` — scrub, `Play` toggles, time mono bold, thumb tinted ACCENT2, no jank.
* Rotation: portrait → landscape → portrait while panel open/closed, with/without selection — canvas re-fitted, controls remain accessible, no overlap, no pushed-off UI, selection border stays exact.
* Add several sources (≥6) → strip scrolls horizontally, panel scrolls vertically, canvas stays ≥28% floor, never vanishes.
* Play video → stage 60fps, HUD (HW/SW·fps) updates 2Hz, tap HUD hides; playSignature auto-refresh on non-loop end-pause.
* UI remains responsive — no ANR, no continuous rebuild, no dropped frames observed on health HUD.

---

## 9. Remaining UI issues (out of scope / low priority)

* **HomeActivity** still decodes thumbnails on UI thread (audit #10) — not touched per “editor UI only”.
* **Splash** unskippable 2.7s — not editor scope.
* **Radial wheel paging animation** under finger (`refresh()` re-renders toggles) can still feel jumpy if tapped fast — kept as power shortcut, not primary path.
* **Screen-reader**: `contentDescription` now present on every `IconBtn`/chip/pill (editor only); home cards / diagnostics still silent.
* **Reduced-motion** not yet respected (`ANIMATOR_DURATION_SCALE`); overshoot kept mild (1.15–1.25).
* **No light/dark theme switch** — hard-coded dark (intended for studio).
* **No gesture hint coach-marks** — first-run tips still TODO (P2).
* **Transport SeekBar thumb** uses default drawable tinted ACCENT2 — 16dp design note exists but not custom oval (avoided extra drawable cost).
* **Record audio decode** still shows indeterminate progress “Mixing…” — could show per-clip progress but not required.

---

**STOP AFTER STEP 5.** No further feature started.

*Commit:* `e288d01 — Step 5: professional editor UI — bottom tab bar, horizontal source strip, floating 44dp source controls, canvas-protected layout` pushed to `arena/01a0710f-kotlin-reaction-maker`.
