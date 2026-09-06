# Studio UI reconstruction

Companion to `STUDIO_INTERFACE_AUDIT.md` (the "before"). This document is the
"after": what the studio chrome is now, the single rule it is built from, how
it was verified, and — honestly — what was *not* verified.

Scope of the change: **UI chrome only.** `Compositor`, `PreviewEngine`,
`Exporter`, `SourceController`, the `Project`/`Layer` model, `ViewportFit`,
`StageView`'s geometry, `LiveCamera`, the recorders and the media codecs are
untouched. `RadialWheel`, `RadialMenus`, the advanced sheet and `SourceDock`
are kept and are still the way most per-source actions are reached.

---

## 1. The one layout

There is exactly one chrome builder: `StudioLayoutInjector.inject()`. It
builds a single vertical `LinearLayout` ("the column") into the root frame.
No percent weights, no negative margins, no `translationX/Y`, no overlapping
frames used for layout, no fallback layouts. `ControlsPanel.kt` (the old
duplicate quick-bar) is deleted.

```
column (edge-to-edge; system-bar + cutout insets applied as padding)
├─ top bar          48dp  (44dp phone-landscape)
│    Back · project name / meta · [● REC chip, only while capturing] · aspect ▾ · Save · Export · ⋯
├─ body             flexible (weight 1)  ← the only flexible row
│    landscape:  [tool rail 56dp] | [canvas cell, weight 1] | [context panel, budget dp]
│    portrait:   [canvas cell, weight 1] / [context panel: tabs 40dp + body] / [tool row 48dp]
├─ camera row       44dp  (only when a live camera exists AND it fits)
├─ timeline         10 + 16·lanes dp, max 4 lanes  (only when clips exist AND it is open AND it fits)
└─ transport        52dp  (44dp phone-landscape)
     Play · Stop · time · SeekBar · duration · [timeline toggle — tablets] · ● REC
```

The canvas cell is the **only** thing that flexes. Everything else is a fixed
dp height, so the canvas can never be squeezed by an empty panel: an empty
Sources tab is exactly as tall as a full one.

Inside the canvas cell, `StageView` contain-fits the project aspect (16:9,
9:16, 1:1) and centres it. Overlays in the cell are the empty-project prompt
(4 equal-width choices: Camera / Video / Screen / Image), the opt-in stats
HUD (bottom-start, default **off**), the Full-Canvas exit button and — in
landscape only — the 44×72dp edge handles (a 36dp pill drawn flush with the
edge inside a 44dp touch target) that re-open a collapsed rail or panel. The handles inset the stage viewport (`stage.setViewportInsets`) so
the picture is fitted *beside* them, never under them.

## 2. The budget rule (`core/ChromeBudget.kt`)

A pure Kotlin object, no Android imports, so it runs in a JVM test. It
divides the *usable* window (window minus status/nav bars and cutout) and
returns dp sizes; the injector only applies them.

Constants: `TOP_DP 48`, `TRANSPORT_DP 52`, `COMPACT_BAR_DP 44`, `RAIL_DP 56`,
`TABS_DP 40`, `TOOLROW_DP 48`, `CAMERA_ROW_DP 44`,
`MIN_CANVAS_SHARE 0.45`, `MIN_BODY_WITH_EXTRAS_DP 260` (portrait),
`MIN_BODY_WITH_EXTRAS_LAND_DP 200` (landscape). Panel widths (landscape):
phone 240–300dp, tablet 280–360dp. Panel body heights (portrait): phone
200–360dp, tablet 260–420dp.

**Priority order, highest first:** canvas ≥ 45 % of its axis (and ≥ 96dp) →
context panel → tool rail → camera row → timeline.

* `landscape(usableW, usableH, aspect, tablet, panelOpen?, railOpen?, extraRowsDp)`
  – the canvas *wants* the width its contain-fit needs for the body height.
  Rail and panel are shown by default only when they fit beside that want.
  A user override (`panelOpen = true`) first shrinks the panel to its
  minimum, then collapses the rail, before the canvas may drop — and it never
  drops below 45 % of the width. Explicit close → canvas reclaims the width.
  Invariant: `rail + canvas + panel == usableW`.
* `portrait(...)` – same idea on the height axis: the canvas wants
  `width / aspect`; the panel opens by default when ≥ 120dp is left under
  that (`PORTRAIT_SPARE_MIN_DP`) and takes exactly the spare up to its
  maximum, so the picture keeps its full-width size — a 16:9 canvas on a
  360×640 phone gets 202dp of canvas and a 178dp panel instead of a
  letterboxed canvas over an empty tab strip. Canvas ≥ 45 % of the body
  always; below 120dp of spare the panel collapses to its tab strip and
  opens on demand at its minimum.
* `extrasFit(usableH, landscape, tablet, extrasDp)` – the optional rows come
  out of the flexible body; they are shown only when the body would still be
  ≥ 260dp (portrait) / ≥ 200dp (landscape). The timeline is dropped first,
  then the camera row (`EditorActivity.applyExtraRowsFit`). Nothing is lost
  when the camera row is dropped: every camera control (flash, facing,
  mirror, screen light, take) is also in **Props → CAMERA**.

Default results (dp) from the test's device table — `rail=true` everywhere:

| device (usable, dp) | 16:9 land canvas / panel | 9:16 land canvas / panel | 16:9 port canvas / panel body | 9:16 port canvas / panel body |
|---|---|---|---|---|
| small phone 360×640 | 536 / collapsed | 296 / 240 | 202 / 178 | 380 / collapsed (open = 200) |
| Pixel-class 393×851 | 499 / 248 | 474 / 273 | 265 / 326 | 591 / collapsed |
| tall phone 412×915 | 533 / 278 | 517 / 294 | 295 / 360 | 655 / collapsed |
| short phone 360×592 | 488 / collapsed | 248 / 240 | 202 / 130 | 332 / collapsed (open = 183) |
| foldable inner 673×841 | 457 / 280 | 457 / 280 | 378 / 203 | 321 / 260 |
| tablet 800×1280 | 944 / 280 | 892 / 332 | 648 / 420 | 808 / 260 |
| large tablet 1024×1366 | 1030 / 280 | 955 / 355 | 734 / 420 | 894 / 260 |

"collapsed" means the budget chose to give the width/height to the canvas;
the panel is one tap away (edge handle in landscape, ⌃ in the portrait tab
strip, or any tab) and opens at its minimum without pushing the canvas
under 45 %.

## 3. Tiers

`StudioLayoutInjector.tierFor(Configuration)`: `smallestScreenWidthDp ≥ 600`
→ tablet; orientation → landscape/portrait. Four tiers.

| | phone portrait | phone landscape | tablet portrait | tablet landscape |
|---|---|---|---|---|
| bars | 48 / 52 | **44 / 44** | 48 / 52 | 48 / 52 |
| tool rail | bottom row 48dp: Add · Undo · Redo · panel · timeline | left 56dp: Add · Undo · Redo · hide; **"Hide tools" collapses it** into an edge handle | bottom row: all seven tools + panel toggle | left 56dp: all seven tools |
| context panel | below canvas, tabs + body; ⌄/⌃ collapses body | right, budget dp; ✕ collapses whole panel → edge handle | below canvas | right |
| timeline default | open | **collapsed** | open | open |
| top bar | Back · title · aspect · Save (icon) · Export (icon) · ⋯ | + Export pill | + Save pill, Settings | + Save pill, Settings |
| REC pill | 44dp glyph, "■ 0:12" while recording | "● REC" | "● START RECORDING" | same |
| timeline toggle | in tool row | in transport | in transport | in transport |

Phones do not carry the five separate add-tools: a 360dp row cannot hold
seven 48dp targets plus the view toggles without scrolling Undo/Redo off the
end. **Add** opens the existing Add ring (camera · video · image · screen ·
text — the same five verbs, one tap deeper), and the Sources tab's empty
state lists them as full-width shortcuts. Every width claim in this table is
checked by `tools/chrome-fit-check.py` (see §7).

Tablets get rail + canvas + panel simultaneously in both orientations
(asserted by the test). Phones in landscape get the compact tier the brief
asked for: compact bars, collapsible rail, collapsible panel, timeline
collapsed by default.

**Tablet landscape: Sources + canvas + context at the same time.** When the
usable height is ≥ 634dp (`ChromeBudget.sourcesPinned` — i.e. 400dp of body
would remain even with the timeline and camera row both shown, so the
decision never flips when a row appears) the right panel is
`[Sources pane] / [Mixer · Props · Effects tabs] / [active body]`. The
Sources pane is 42 % of the body clamped to 200–340dp
(`sourcesPaneDp`), and the tabbed body under it keeps ≥ 160dp; both are
asserted for every optional-row combination. It is the same
`SourcesPanel` object with the same bindings — only where the builder
*adds* it differs, and the Sources tab is simply not created. A 10"
tablet (800×1280) gets a 280dp pane; the 673×841 foldable a 230dp one;
a 600dp-short tablet (576dp usable) stays tabbed. Rotating to portrait
restores the Sources tab and the user's last `activeTab`.

## 4. Panels (one active body, `showTab`)

* **Sources** – fixed header (title · "N hidden" badge · "+ Add") over a
  scrolling list. The list *is* the existing `SourceDock`, hosted in
  `SourcesPanel.dockContainer`; reorder is the dock's own drag →
  `SourceController.reorderLive`. A row is 52dp: `[eye] type name/status …
  [mute, clips] [⠿]` — the fixed pieces are 44dp targets and the name/status
  column is the only flexible one, so at the 240dp phone-landscape minimum
  the name still has 66dp and ellipsizes (the old row also carried a run of
  LIVE/SOLO/LOOP/LOCK badges, which at 240dp left the name ~18dp: state is
  now the status line — `SOLO · PAUSED · LOCKED`). Under the list: a
  selection action row (forward / backward / hide / props / remove); on a
  portrait phone panel shorter than 200dp that row is dropped
  (`setCompact`) because each of its verbs is also on the row, in Props or
  on the wheel. Empty project: the five add shortcuts.
* **Mixer** – per audible source: type · name · PAUSED badge · M · S ·
  volume fader (`engine.setVolume`), monitor-mute in the header, mic strip
  read-only, "Silent — another source is soloed" explained inline. Faders
  call `requestDisallowInterceptTouchEvent` so the panel's scroller never
  steals the drag. Compact empty state.
* **Props** – the advanced sheet (`fillAdvanced`) rendered in place:
  APPEARANCE (fit/fill, opacity, mirror), PLAYBACK & AUDIO, **CAMERA** (live
  sources), TEXT, ARRANGE (z-order), DANGER. Same code the wheel's
  "Advanced" opens — no second implementation.
* **Effects** – honest: lists the look controls that exist (fit/fill,
  opacity, mirror, canvas colour) and states that filters / grading / chroma
  key / transitions are not in this version. No placeholder controls.

The tab strip is 40dp; each tab spans the full strip height (pill drawn with
a 4dp inset), the close item is 44×40dp.

## 5. Rotation and state

`EditorActivity` declares `configChanges=orientation|screenSize|screenLayout|keyboardHidden`.
`onConfigurationChanged → relayoutChrome()`: `rootFrame.removeAllViews()`,
`inject()`, `afterChromeBuilt()` (rebind dock, insets, budget, refreshAll,
`applyProgressState`). The engine, camera and master clock are not touched;
the new `StageView` binds to the same host.

State that survives relayout *and* process death (`onSaveInstanceState`):
`panelOpen?`, `railOpen?`, `timelineOpen?` (tri-state: `null` = budget
default), `activeTab`, selection, project id. Also carried across relayout:
screen-light, Full-Canvas, and the export/recording progress card
(`progState`).

Insets: the root's `OnApplyWindowInsetsListener` reads system bars + cutout
(API 30 `WindowInsets.Type`, legacy fields + `DisplayCutout` below) and
applies them as **padding on the column**, then re-runs `applyChromeBudget()`
(no rebuild). Full Canvas hides the bars, drops the padding and keeps the
exit button clear of the cutout.

## 6. What was removed

`ControlsPanel.kt`; the quick bar / source strip / sheet-tab / side-rail
code paths and their flags (`USE_QUICK_BAR`, `setChromeInsets`,
`refreshQuickBar`, `updateSourceStrip`, `capPanelHeight`,
`applyViewportInsets`, `updateStageInsets`, `hiddenPill` overlay,
`timelineHeightDp`, …). `grep` for each returns nothing. The REC state left
the picture and became a top-bar chip; the "N hidden" pill left the picture
and became a Sources-header badge; the stats HUD is opt-in.

## 7. Verification — what was actually run

| check | command | result |
|---|---|---|
| APK build (kotlinc + d8, offline toolchain) | `./build-apk.sh` | green (build #26) |
| static integration guard, 109 needles (incl. the touch-target geometry below) | `python3 tools/validate-integration.py` | 109 / 0 |
| chrome budget, 7 devices × 3 aspects × panel/rail overrides × extras + pinned-Sources split | `bash tools/chrome-budget-test/run.sh` | 1884 / 0 |
| fixed-bar fit: what each bar *contains* vs. the narrowest width of each tier (top bar idle + capturing, transport seek ≥ 96dp, rail/row without scrolling, tab strip at panel minimum, source row name ≥ 60dp, camera row) | `python3 tools/chrome-fit-check.py` | all green |
| viewport fit | `bash tools/viewport-fit-test/run.sh` | 420 / 0 |
| stage geometry | `bash tools/step2-geom-check.sh` | all green |
| dex symbols the CI asserts (incl. `ChromeBudget`, `TimelineView`, `PropertiesPanel`, `EffectsPanel`) | `unzip -p artifacts/*.apk classes.dex \| strings` | all present; `ControlsPanel` 0 refs |
| structural-hack grep (`translationX/Y`, `-UI.dp(`, `weight = 0.`, overlapping frames) in chrome code | grep | none (the only `translationY` is the snackbar's entrance animation) |
| **on-device smoke, 4 tiers** (API 30 x86_64 emulator, GitHub Actions job `emulator-smoke`): live view-hierarchy dumps checked for off-screen / < 44dp / overlapping controls, a covered or collapsed canvas, missing bar controls; regression walk add text · select · hide · show · Mixer / Props / Effects · collapse · re-open · undo · redo · play · stop · aspect switch (= `onConfigurationChanged` re-layout) · live camera | `tools/emulator-smoke/smoke.sh` → `check_window.py` | runs in CI on every push; report in the job summary, screenshots in the `emulator-smoke` artifact — **not run in this sandbox** (no KVM), see below |

Invariants the budget test asserts for every combination: rail + canvas +
panel == usable width; canvas ≥ 45 % and ≥ 96dp; an open landscape panel is
≥ 160dp and ≤ tier max, an open portrait body is ≥ 120dp and ≤ tier max;
explicit close → canvas reclaims; tablets get rail + canvas + panel; extras
rows shown only above the body floors; phone-landscape bars are 44dp; the
function is pure.

### What was **not** verified — read this before calling it done

* **No device or emulator run *from this sandbox*.** It has no adb, no
  emulator and no KVM, and nothing in this document is a screenshot taken
  here. The claims "no clipping / no overlap / no off-screen control" rest on
  (a) the dp budget being asserted for the device table above, (b) the
  per-bar fit arithmetic in `chrome-fit-check.py` — which, when first run,
  found two real overflows on a 360dp phone (an "Export" pill pushing ⋯ off
  the top bar while the REC chip was up; a 77–91dp seek bar) that are fixed
  by the phone-portrait icon Export and glyph REC pill — and (c) the layout
  being a single column of fixed rows plus one flexible cell, i.e. there is
  no mechanism left that *could* overlap. That is strong evidence, not
  proof; the text-width estimates in (b) are ±10 %.
* **The device run now exists as a CI job** (`emulator-smoke` in
  `.github/workflows/android.yml`, script in `tools/emulator-smoke/`). It
  installs the signed APK on an API 30 x86_64 emulator, resizes the window
  to a 360×800dp phone and an 800×1280dp tablet, creates a 9:16 and a 16:9
  project through the real Home UI (the editor pins its orientation to the
  canvas, so that yields all four tiers), and after every step asserts from
  the `uiautomator` dump exactly the §31 questions a static check cannot
  answer: every clickable node on screen and ≥ 44dp (the 40dp strip items
  and the 24dp in-row play/pause shortcut are listed as such), no two
  clickable nodes overlapping, the canvas cell ≥ 96dp with no chrome over
  it, the tier's bar controls present, the process alive. Its report is
  written to the job summary; screenshots and window dumps are uploaded.
  **Its first result was not visible when this was written** — the sandbox
  can push but cannot read job logs or artifacts — so read the latest
  `emulator-smoke` job before treating the eight "YES (static)" rows as
  device-confirmed; if it is red, the report names the tier, the step and
  the node.
* **Regression list not executed on hardware.** Add video / image / camera /
  text, select / hide / mute / pause / lock / solo / loop, fit / fill, move /
  resize / rotate, wheel, advanced sheet, undo / redo, play / record / stop /
  save / export, aspect, rotation, reorder: every one of these is wired to
  the same `EditorActivity` / `SourceController` / engine verb it used before
  (the validator checks each call site by name), and none of the engine code
  changed, but they were not tapped through on a phone.
* Font scaling above 1.0 and RTL were not exercised. Bars are dp-fixed;
  text inside them ellipsizes or wraps within its own row, so the layout
  cannot break, but large-font legibility of the 10.5–12sp labels is not
  confirmed.
* Real cutout/gesture-nav geometry was reasoned from the inset code, not
  observed.

The first thing to do on a device: install `artifacts/AhmedReactionStudio-1.0.0.apk`,
open a project in each of the four tiers, toggle panel / rail / timeline,
rotate with the wheel open and with an export running, and walk the
regression list above.

---

## 8. §31 audit — 18 questions

Answered against the code as it is now. "YES (static)" means verified by
build + tests + reading, not on hardware (see §7).

| # | question | answer | evidence |
|---|---|---|---|
| 1 | Is the canvas centred, aspect-preserving, never covered, clipped or squeezed by empty panels? | **YES (static)** | only flexible row is the canvas cell; `StageView` contain-fits; panels are fixed dp regardless of content; budget floor 45 %/96dp asserted |
| 2 | Does landscape compute rail / canvas / panel from usable width with minimums, collapsing rail & panel before the canvas? | **YES** | `ChromeBudget.landscape`, invariants in test |
| 3 | Do the tabs SOURCES / MIXER / PROPS / EFFECTS show one active body that fills the area? | **YES** | `buildContextPanel` + `showTab`; bodies are `MATCH_PARENT` in a weight-1 frame |
| 4 | Does ✕ collapse the whole panel, does the canvas reclaim the space, and can it be re-opened? | **YES** | landscape: `setPanelOpen(false)` → `panelDp = 0`, edge handle re-opens; portrait: ⌄/⌃ toggle on the strip; tabs also re-open |
| 5 | Sources: type, name, selected, visibility, mute, pause, lock, status, reorder, scrolling list with fixed header? | **YES** | `SourcesPanel` header outside the `ScrollView`; rows are `SourceDock` (52dp; name ≥ 66dp at the narrowest panel, ellipsized; pause = tap the status line; lock/solo/loop in the status line) |
| 6 | Mixer: compact empty state; source / volume / mute / solo / status through the existing listener? | **YES** | `MixerPanel.bind`; `engine.setVolume`, `ctrl.toggleMuted/toggleSolo` |
| 7 | RadialWheel kept, and not exploded into permanent buttons? | **YES** | `RadialMenuView` in dex; only 7 rail tools + 5 top-bar items + 8 transport items are permanent |
| 8 | SourceDock kept at an adequate size with handles / eye / mute reachable? | **YES** | `ROW_DP = 52`, eye/mute 44dp, drag handle unchanged |
| 9 | Tool rail: Add / Camera / Video / Image / Text / Undo / Redo, compact, scrollable, collapsible when narrow? | **YES** | tablets: all seven, no scrolling needed (fit-checked); phones: Add (→ Add ring with the same five verbs) · Undo · Redo · view toggles, fits 360dp without scrolling; scroll containers remain for font-scale overflow; phone landscape "Hide tools" → edge handle |
| 10 | Timeline collapsible and compact when empty? | **YES** | `TimelineView` measures 0dp with no lanes; toggle; dropped first by `extrasFit` |
| 11 | Transport independent, consistent height: Play / Record / Stop / time / duration? | **YES** | fixed 52dp (44dp compact) row; never part of the panel |
| 12 | Top bar: Back / Aspect / Settings-diagnostics / Save / Export with a stable, touch-friendly height? | **YES** | 48dp (44dp compact); 44dp targets; tablets have a Settings button (tap diagnostics, long-press export settings); phones (no width for a sixth item at 360dp) reach diagnostics via ⋯ long-press and the wheel's Project ring; Export long-press = export settings |
| 13 | Portrait: top / dominant canvas / tabs / active panel / timeline + transport? | **YES** | `buildPortraitBody`; canvas ≥ 45 % of body, panel body 200–360dp (tablet 260–420) |
| 14 | Phone landscape differs from tablet landscape (compact bar, collapsible rail & panel, timeline collapsed by default)? | **YES** | `Tier.PHONE_LANDSCAPE`: 44dp bars, "Hide tools", timeline default closed; tablets always rail + canvas + panel, and with ≥ 634dp usable height the Sources list is pinned above the Mixer / Props / Effects tabs — §20's Sources + canvas + context together (`sourcesPinned`; pane 200–340dp, ≥ 160dp of tabbed body under it, asserted for every optional-row combination) |
| 15 | Status / nav bars, gesture area, cutout, edge-to-edge handled? | **YES (static)** | inset listener → column padding; Full Canvas immersive; exit button offset by cutout |
| 16 | Structural hacks removed (percent weights, negative margins, translations, overlapping frames, invisible touch-intercepting views, duplicated UI)? | **YES** | grep clean; `ControlsPanel` deleted; one injector |
| 17 | `onConfigurationChanged` rebuild path preserved with no state loss? | **YES** | `relayoutChrome()`; tri-state chrome flags + tab + selection + progress card + screen-light survive; engine untouched; a pinned-Sources tablet rotating to portrait gets its Sources tab back and keeps the user's last tab |
| 18 | Touch targets ~44–48dp without overlaps? | **YES** | `TAP_DP = 44` is the hit box of every bar control; the compact-looking pieces draw a smaller pill *inside* a 44dp view (`insetPill`): top-bar aspect / REC / Save / Export chips 32dp-on-44, transport REC pill 36-on-44, transport seek band 44, edge handles 44 wide (36 pill), Props button rows 40-on-44, snackbar action and progress Cancel ≥ 44; tab strip items 40dp, Sources action row / mixer M·S·monitor 40dp and the clip row's status-line play/pause 24dp band are the documented exceptions (each verb also has a full-size target on the wheel or in Props); dock eye / mute / drag 44dp; the emulator job measures all of this from the live hierarchy |

**Verdict:** no answer is NO. Eight of the eighteen are "YES (static)" or
depend on the static evidence in §7; the `emulator-smoke` CI job now
re-asks those eight from a real window on every push — its latest run, not
this document, is the device answer. Nothing here is a guess about code
that was not read: every row points at the function that implements it.
