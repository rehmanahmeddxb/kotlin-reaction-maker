# Ahmed Reaction Studio — UI Audit Report

**Date:** 2026-09-05 · **Scope:** full app, start → end, all screens & features
**Method:** static audit of every UI file (`ui/`, `editor/`, `camera/`, `capture/`, `util/UI.kt`),
gesture map, tap-count walkthroughs per task, duplication matrix, platform/a11y checks.
**Note:** report only — no code changed.

Files referenced below are under `app/src/com/rehman/ahmedreactionstudio/`.

---

## Table of contents

1. [Executive summary — the 10 biggest wins](#1-executive-summary--the-10-biggest-wins)
2. [Screen inventory (start → end)](#2-screen-inventory-start--end)
3. [How every task is performed today + how to make it easiest](#3-how-every-task-is-performed-today--how-to-make-it-easiest)
4. [Duplicated functions in the UI](#4-duplicated-functions-in-the-ui)
5. [Wrong practices & violations catalog](#5-wrong-practices--violations-catalog)
6. [Proposed simplified IA](#6-proposed-simplified-ia)
7. [Prioritized action plan](#7-prioritized-action-plan)
8. [Appendix A — full gesture inventory](#appendix-a--full-gesture-inventory)
9. [Appendix B — entry-point map (every opener → every surface)](#appendix-b--entry-point-map-every-opener--every-surface)

---

## 1. Executive summary — the 10 biggest wins

The app's engine is solid (single state model, undo, preview==export). The UI's core
problem is **one idea taken too far: everything was forced into the nested radial menu**,
so frequent one-tap jobs now take 3–5 taps through a paging ring, while the same verb is
also duplicated in 3–7 other places. Discoverability is near zero for first-time users
(no labels, no onboarding, hidden gestures).

| # | Fix (highest leverage first) | Why it matters |
|---|---|---|
| 1 | **Restore a persistent one-tap bar** for the 5 frequent jobs (Sources · Add · Play · Audio · Export). Keep the radial as a power shortcut, not the only path. | Mute/hide/play/add/export go from 3–4 taps → 1 tap. Fixes the single biggest complaint class. |
| 2 | **Fix the root ring paging itself**: 9 root petals but `PAGE = 8`, so the *home menu* shows a “More 1/2” petal. Merge Dock→Sources, Controls→transport, or Light→camera ring. | The main menu is currently broken on first open. |
| 3 | **Unify the 7 mute / 5 solo / 5 hide paths** into one honest surface (dock row + one audio sheet). Delete the per-channel ring. | Users can't form a mental model when one verb lives in 7 places with 3 labels. |
| 4 | **One camera entry** (“Camera”) with Live / Record segmented control. Auto-fallback to fullscreen; remove the “Fullscreen take (fallback)” petal from the normal path. | Two camera UXs with different controls is the #1 confusion source after the ring. |
| 5 | **Make RECORD discoverable**: always-visible (disabled with reason) instead of `GONE` until live+clip coexist; add one guided “Add both” setup. | The headline feature is invisible with zero explanation of how to unlock it. |
| 6 | **Kill dangerous/invisible gestures**: double-tap=hide → require visible control; long-press ring → add visible hint; enlarge 9dp handles to 48dp targets. | Data-loss-feeling accidents + undiscoverable features + a11y failures. |
| 7 | **Persist export settings** (codec/quality/res/fps are locals, reset every open) + one-tap “Export again with last settings”. | Repeat exporters re-pick 4 settings every single time. |
| 8 | **Replace toasts-with-no-undo by Snackbars with Undo** for hide/delete/mute/solo; replace deprecated `ProgressDialog` with modern progress UI. | 56 toasts in `EditorActivity` alone; destructive feedback evaporates. |
| 9 | **Accessibility pass**: zero `contentDescription` in the whole app; icon-only buttons everywhere; <48dp targets; color-only state. | Currently unusable with TalkBack; fails Play-store a11y expectations. |
| 10 | **Home list hygiene**: decode thumbnails off UI thread, add rename/open affordances, swipe-to-delete instead of stacked ✕/Copy chips. | Jank on scroll + destructive button next to open target. |

Estimated effect of #1–#7 alone: **median task taps 3.5 → 1.2**, and removal of
~40% of the duplicate menu items without losing a single capability.

---

## 2. Screen inventory (start → end)

### S0. Splash — `ui/SplashActivity.kt`

Animated brand screen (~2.4 s + 320 ms fade): pulsing rings, springy badge,
wordmark, tagline, version. Skips to Home. No interaction, no skip button.
*Verdict: fine, but unskippable 2.7 s on every cold start; add tap-to-skip.*

### S1. Home (project list) — `ui/HomeActivity.kt`

| Element | Behavior |
|---|---|
| Header: ▶ logo, title, subtitle, ⚙ chip | ⚙ opens Diagnostics (gear icon actually opens a *diagnostics* screen — mislabeled) |
| Hint “stored on device only” | Static text |
| Project cards (ListView) | Thumb (decoded **on UI thread**), name, `aspect · N layers · duration`; tap = open; per-card **Copy** chip + red ✕ chip (delete with confirm) |
| “+ New project” button | Dialog: name field (default “My Reaction”), 3 aspect chips, Create/Cancel |

Gaps: no rename, no search/sort, no empty-state illustration (blank list + button only),
no last-opened indicator, delete ✕ sits where a thumb-action would be (mis-tap risk).

### S2. New-project dialog — `HomeActivity.showNewDialog()`

Name + aspect chips. Issues: chip click handler is convoluted
(`chips.values.firstOrNull { it === c }` — works but fragile); default 16:9 applied
*after* `show()`; empty/blank names accepted; dialog is a raw `AlertDialog` with an
`EditText` (no rounded sheet styling like the rest of the app).

### S3. Editor shell — `editor/EditorActivity.kt` (`buildUi`, `buildTopBar`, `buildSheet`)

Fullscreen canvas; everything floats over it:

| Zone | Contents |
|---|---|
| Top bar (gradient) | Back · project name + `aspect · N sources` meta · **aspect chip (tap = cycle 16:9→9:16→1:1)** · undo · redo · gear→Diagnostics |
| Canvas (`StageView`) | Contain-fitted composition, letterboxed; selection chrome (8 handles + rotate knob + label pill) |
| Empty overlay | “Set your main canvas” card with 5 stacked buttons: Camera live · Local video · Record screen · Image · ◉ Open radial menu |
| Quick Control Bar (floating pill, **horizontally scrollable**) | Name pill · eye · mute* · play/pause* · lock · fit/fill · ◉ (source ring) · ⋮ (advanced sheet). For live camera instead: record · switch-cam · flash. (* clip sources only) |
| Transport row | Play/pause (accent circle) · current time · seekbar · duration |
| Bottom row | **● START RECORDING** (only visible when live+clip coexist) + **◉ Studio** launcher (subtitle “sources · add · controls · mixing” — lists 4 of 9) |
| Bottom sheet (40% max-height scroll) | Hosts 4 mutually-exclusive panels: Sources dock · Mixer · Export · Advanced (per-source). Opened from petals/buttons; Back closes |
| Overlays | `recChip` (● STOP …) top-center · stats HUD (HW/SW·fps) top-left, tap-to-hide · radial `RadialMenuView` fullscreen scrim on top of everything · screen-light white view + max brightness |

### S4. Radial system — `RadialWheel.kt` + `RadialMenus.kt`

Nested rings: hub = back/close, folder petals push a level, leaf petals act,
toggle petals (`keepOpen`) act + re-render. 8 petals/page with a “More n/m” pager.
Full tree (9 root petals — **overflows the 8-per-page limit**):

```
◉ STUDIO (root — itself pages! 9 items → 7 + More)
├── Sources → per-source folder (badge HIDDEN/MUTED/LOCK/PAUSED/LIVE/REC/SOLO)
│   └── source ring: Select · Hide/Show · [live: Record · Switch · Mirror · Light▸
│       | clip: Play · Mute · Loop · Solo | text: Edit · Colour] · Fit/Fill ·
│       Lock · Arrange▸ · Advanced… · Duplicate* · Delete   (* hidden for live)
│       └── Arrange▸: To front · To back · Centre · 4 corners · Fill canvas
│       └── Light▸ (= flash ring): Front LED · Back LED · Both · Screen light · Switch cam
├── Add → Camera live · Video file · Image · Screen record · Text · Fullscreen take (fallback)
├── Controls → Play/Pause all · Restart · −10s · +10s · Snapshot frame · Undo · Redo
├── Dock → Open dock · Select next/prev · Raise/Lower selection · Advanced… · Deselect · Jump to source▸
├── Mixing → Mute/Unmute all · Clear solos · per-clip channel▸ · Open mixer panel
│   └── channel▸: Mute · Solo · Vol+10% · Vol−10% · Vol 100% · Advanced…
├── Light (= lightRoot) → same flash items, or “Add live camera first” + screen light
├── Canvas → 16:9 · 9:16 · 1:1 · Background▸ (7 colours) · Fit all · Selection as background
├── Export → Quick export 720p30 · Export settings…
└── Project → Rename project · Save now · Undo · Redo · Diagnostics · Close project
```

### S5. Bottom-sheet panels — `EditorActivity`

* **Sources panel** (`buildSourcesPanel`): section header + `SourceDock` list + (if empty) CTA button opening the Add ring.
* **Mixer panel** (`buildMixerPanel`): per-clip row (icon, name, mute btn, solo btn) + Level slider. Any mute/solo tap **rebuilds the whole sheet** (scroll jumps to top).
* **Export panel** (`buildExportPanel`): 4 picker rows (codec / resolution / quality / fps) each opening an `AlertDialog` list + size estimate + info + “⇪ Export video”. All choices are **method locals — forgotten on close**.
* **Advanced sheet** (`openAdvancedSheet`): header (icon, name, Rename chip) · APPEARANCE (fit toggle, hide/show, opacity slider) · PLAYBACK & AUDIO (clip only: play, loop, mute, solo, volume slider, solo note) · ARRANGE (front/back, 6 anchors, center, duplicate) · DANGER (delete). **Every toggle rebuilds the sheet** (scroll lost). **No lock toggle, no text-content editor, duplicate allowed for live** (ring forbids it — inconsistent).

### S6. Source dock rows — `SourceDock.kt`

Per-source row (top-most first): eye · mute-or-spacer · type icon · name + status line
(`HIDDEN · MUTED · PAUSED · LOCKED · FIT…`) · badges (LIVE/SOLO/LOOP/LOCK) · ⠿ drag handle.
Tap = select, long-press = advanced sheet, handle-drag = live Z reorder (one undo step).
Hint text when empty says “tap + to add one” — **there is no + button anymore** (stale).

### S7. Canvas gestures — `StageView.kt` (see Appendix A for full list)

Tap select · tap-empty deselect · **double-tap hide/show** · drag-move (snap+clamp) ·
8-handle resize (corners keep aspect for media, edges distort; text free) ·
rotate knob · pinch scale+rotate · **long-press opens radial at finger** (460 ms).

### S8. Fullscreen camera — `camera/CameraActivity.kt` (fallback path)

Portrait-locked Camera2 screen: preview + white flash overlay · top bar (Close chip,
“Record main canvas / Record PiP reaction” title, status) · bottom dock (timer,
zoom slider 1×–max, Camera-switch chip, Flash chip w/ 3 personalities:
“Flash ON” / “Screen ON” / “No flash”, red ●/■ record button, hint). Records an MP4
into project media, returns to editor. Robust plumbing; UI is a second, divergent
camera UX.

### S9. Screen recording — `capture/ScreenCaptureService.kt` + editor glue

System MediaProjection permission → foreground service + notification
(“Recording screen / Tap to stop & add to studio”, tap = stop) + editor `recChip`
also stops. On stop, the MP4 auto-imports as main (if canvas empty) or PiP.
Issues: leaving the app to record other apps is unexplained; stop affordance is
split between notification and chip; `pendingFile` + `onStopped` dual delivery.

### S10. Composite RECORD — `EditorActivity` record path + `export/CompositionRecorder`

`● START RECORDING` (visible only with live+clip) → mic permission → “Preparing audio”
spinner → records composite (clip audio + mic) at 720p30 → STOP → save dialog
(View/Share/Close). Hidden-gating is the core UX bug (§5.3).

### S11. Export flow — `Exporter` + `MediaSave` + dialogs

Export panel → `warnLiveBeforeExport` (if visible live layer: Record a take /
Export anyway / Cancel) → deprecated horizontal `ProgressDialog` (Cancel button) →
“saving” spinner → result dialog (verified path + bytes; View/Share/Close).
Honest save reporting is good; settings amnesia + `ProgressDialog` + modal stacking
are the gaps.

### S12. Diagnostics — `ui/DiagnosticsActivity.kt`

Device/Android/RAM/storage/OpenGL/cameras/flash/encoders/app-dir rows + preview-HUD
on/off toggle + “Copy diagnostics”. **Not scrollable** (overflows on small phones);
OpenGL version read with no EGL context (always “?”); HUD toggle exiled here even
though the HUD lives in the editor.

### S13. Small dialogs/toasts

Rename source, rename project, add-text, edit-text (all bare `AlertDialog`+`EditText`,
inconsistent padding/tint with the dark theme); 56 toast call sites in the editor;
share via `UI.shareUri`; view via `ACTION_VIEW`.

---

## 3. How every task is performed today + how to make it easiest

Legend: **Today** = exact current path with tap count. **Easiest** = concrete proposal.
“Ring” = ◉ Studio radial menu.

### T1. Create a project

* **Today (3 taps + typing):** Home → “+ New project” → type name → pick aspect chip → Create.
  Friction: default name “My Reaction” invites duplicates; aspect chips unexplained
  (no preview of what 9:16 means); blank names allowed.
* **Easiest:** keep 3 taps, but: smart default name (“Reaction 12” / date-based),
  aspect cards with mini canvas previews + use-case hints (“YouTube” / “Reels·Shorts” / “Post”),
  Create disabled until non-blank name. Remember last aspect.

### T2. Open / manage projects

* **Today:** tap card = open; Copy / ✕ per card. No rename without opening; no indicators.
* **Easiest:** card tap opens; long-press (or ⋮) → Rename · Duplicate · Delete · Info.
  Add relative “edited 2h ago” + source-count dots. Swipe-to-delete with Undo snackbar
  instead of the always-visible red ✕ next to the open target.

### T3. Set the main canvas background (first source)

* **Today (1–2 taps — good):** empty overlay → Camera/Video/Screen/Image. Role is implicit
  (first = main). 5 stacked buttons can overflow short landscape screens (no scroll).
* **Easiest:** keep, but: make the card scrollable; add one-line role caption
  (“This fills the screen — later additions become floating windows”); replace the
  5th “Open radial menu” button with a secondary link (it duplicates the Studio button
  40dp below it).

### T4. Add a second source (PiP)

* **Today (3 taps):** Studio → Add → type… then a system picker / permission / dialog.
  Friction: the most common job (add reaction cam over video) needs the ring + paging
  knowledge; the Add ring's 6th item “Fullscreen take (fallback)” leaks implementation.
* **Easiest (1 tap):** persistent bottom-bar **＋ Add** button opening one bottom sheet
  with 5 visual tiles (Camera · Video · Image · Screen · Text) + recent-file shortcuts.
  Keep ring path as-is for power users. Hide “fallback” — fall back automatically with a
  one-line notice.

### T5. Select a source

* **Today (5 ways):** canvas tap · dock row · Sources ring → source · Dock ring next/prev ·
  “Jump to source”. Hidden/locked sources break canvas tap (hidden = untappable).
* **Easiest:** canvas tap + dock list are enough; keep next/prev as dock-header ‹ ›
  steppers; delete the Dock ring's navigation petals. Add “select hidden source” via
  dock (already works) + a canvas “N hidden” pill that opens the dock.

### T6. Move / resize / rotate on canvas

* **Today:** drag (snap+clamp); 8 handles (9dp ≈ 18px — far below the 48dp guideline);
  rotate knob 18dp above box; pinch. Full-bleed mains can't be dragged (correct but
  unexplained — drag silently does nothing). Locked = dead gestures, no hint.
* **Easiest:** bigger handles (≥24dp touch slop, keep 9dp visuals); when dragging a
  full-bleed main, show “Background fills the canvas — resize via Fit” tooltip once;
  locked layer tap → snackbar “Locked — Unlock?” with action. Add optional snap-guide
  lines (currently snap is invisible magic).

### T7. Hide / show a source

* **Today (5 ways):** quick-bar eye · source ring · dock eye · advanced sheet · **double-tap canvas**.
  Double-tap is dangerous (accidental, no undo affordance) and hides the thing you
  were looking at; hidden sources keep playing audio (OBS-correct, user-surprising).
* **Easiest:** eye toggles (quick bar + dock) stay; **remove double-tap hide**
  (reserve double-tap for text-edit / zoom); hiding shows Snackbar “Hidden — audio still
  plays · Undo”. Canvas gets an “👁 N hidden” pill to recover.

### T8. Mute / unmute

* **Today (7 ways!):** quick bar · dock row · source ring · mixer panel · Mixing ring
  (per-channel folder) · channel ring · advanced sheet. Solo interactions confuse
  (“MUTED BY SOLO” needs a manual to parse).
* **Easiest (1 tap):** dock row mute + quick-bar mute stay; **one** Audio sheet
  (sliders + mute + solo) replaces mixer panel + Mixing ring + channel ring + advanced
  audio rows. Effective-mute indicator: `🔇 Muted` vs `🔇 Solo-muted` with a “why?” tooltip.

### T9. Solo / unsolo

* **Today (5 ways):** source ring · channel ring · mixer panel · advanced sheet ·
  Mixing “Clear solos”. Computed-state model is right; UI explains it once in 9.5sp grey.
* **Easiest:** solo lives only in the Audio sheet + dock badge; tapping a SOLO badge
  clears it; first-solo shows a coach mark (“Only soloed sources are heard — nothing is lost”).

### T10. Play / pause one source

* **Today (4 ways):** quick bar · source ring · advanced sheet · (implicit) master transport.
  Non-loop end-hold auto-pauses silently (only a status line changes).
* **Easiest:** quick-bar play + dock inline play affordance (currently dock shows
  “PAUSED” text but no play button — add one). Ended source shows “↺ Replay” state.

### T11. Play / pause all, restart, ±10 s, scrub

* **Today:** transport play + seekbar (good, 1 tap) duplicated by Controls ring
  (Play all · Restart · ±10 s). Seekbar max can go stale; scrubbing seeks live (heavy);
  no frame-step; ±10 s buried 3 taps deep.
* **Easiest:** transport keeps play + seekbar; add −10/+10 arrows *flanking* the
  transport (visible, 1 tap); delete the Controls ring (its Undo/Redo also duplicated —
  keep top-bar only). Snapshot moves to an explicit “📷” transport overflow, not a ring.

### T12. Loop toggle

* **Today:** source ring + advanced sheet (+ LOOP badge in dock). Fine but scattered.
* **Easiest:** keep in Audio sheet per-channel row (loop affects A/V sync) + source
  overflow; one place per surface max.

### T13. Volume / opacity sliders

* **Today:** volume in 3 places (mixer slider, channel ±10% steppers, advanced slider);
  opacity only in advanced. Mixer/advanced rebuild on every tap (scroll jump); undo
  granularity throttled oddly (350 ms light snapshots).
* **Easiest:** volume + opacity both in the per-source inspector (see §6) with live
  value labels; channel-ring steppers deleted; slider drags = one undo step on release
  (standard), not time-throttled snapshots.

### T14. Fit (whole frame) vs Fill (crop)

* **Today:** quick bar · source ring · advanced sheet · Canvas “Fit all”.
  Labels vary (“Whole frame” / “Fill box” / “▦ Fit: whole frame” / “⤢ Fill”).
  Icons `ic_fit`/`ic_fill` also reused for corner anchors (meaning collapse).
* **Easiest:** one segmented control `[Fill|Fit]` in the inspector + quick-bar icon
  stays; standardize wording to **Fill (crop)** / **Fit (whole)** everywhere;
  give corners their own icons. First-time camera PiP shows “Nothing is cropped ✓”.

### T15. Lock / unlock

* **Today:** quick bar + source ring only (advanced sheet lacks it). Locked tap gives
  no feedback; chrome turns grey (color-only signal).
* **Easiest:** add lock row to inspector; locked-tap → “Unlock?” snackbar; lock icon
  in dock row (currently badge-only, no toggle).

### T16. Z-order (front/back/up/down/drag)

* **Today (4 mechanisms):** Arrange ring front/back · Dock ring raise/lower ·
  advanced front/back · dock drag-reorder (broken math when scrolled — uses rawY
  minus container origin, ignoring ScrollView offset; no auto-scroll).
* **Easiest:** dock drag-reorder is the hero (fix scroll math + auto-scroll +
  drop highlight); inspector keeps Front/Back; delete Dock-ring raise/lower and
  Arrange-ring duplicates. Dock rows already communicate order visually — lean on that.

### T17. Corners / center / set-as-background

* **Today (3 places):** Arrange ring (centre + 4 corners + “Fill canvas”) ·
  advanced (6 anchors + center) · Canvas ring (“Selection as background”).
  “Fill canvas” = `setAsCanvasBackground` but labeled like the Fit/Fill control —
  users will confuse “Fill box” (crop) with “Fill canvas” (promote to background).
* **Easiest:** one “Position” grid (3×3 incl. center) in the inspector; “Set as
  background” as a separate explicit button with confirm when a background exists.
  **Rename**: Fill/Fit stays about cropping; background promotion never uses “Fill”.

### T18. Duplicate / delete / rename

* **Today:** duplicate in ring + advanced (**ring forbids live duplicate, advanced
  allows it — bug/inconsistency**); delete in ring + advanced danger zone (no confirm,
  undo via top bar only); rename in advanced header + project rename in Project ring
  (two “Rename” meanings).
* **Easiest:** inspector overflow: Rename · Duplicate · Delete (delete → Snackbar Undo,
  no modal); fix live-duplicate rule in one place (allow with “live copy shares the
  camera” note, or forbid consistently). Rename project moves to Home long-press +
  editor title tap (tap project name to rename — standard).

### T19. Text: add / edit / color / size

* **Today:** add via Add ring (bare dialog); **edit + color only in the source ring**
  (advanced sheet shows no text controls!); size/shadow uneditable (model has
  `fontSizeN`/`shadow`, zero UI). Color cycles 6 fixed colors blind (no preview).
* **Easiest:** text inspector section: Edit field (inline) · size slider · color swatches
  with preview · shadow toggle. Double-tap a text layer = edit (replacing double-tap-hide).

### T20. Live camera: add / frame / switch / mirror / record take

* **Today:** Add ring → “Camera (live on canvas)” → permission → PiP appears + toast.
  Record via quick bar / source ring / recChip / warn-dialog. Switch via quick bar /
  ring / flash ring. Mirror only in source ring. One-live limit explained by toast.
* **Easiest:** single Camera tile → live PiP with inline camera toolbar
  (● Record · ⇄ Switch · 🪞 Mirror · 💡 Light); record state shown on the PiP itself
  (REC dot + timer), not only the top chip; second-camera attempt → “Only one live
  camera — record this take first?” dialog with action.

### T21. Flash / screen light

* **Today (4 entries to one ring):** quick-bar ⚡ · source-ring Light folder ·
  root Light petal · `openFlashRing`. Items: Front LED · Back LED · Both · Screen ·
  Switch cam; no-LED devices get “Front: no LED (use screen)” dead petals + toasts.
* **Easiest:** one 💡 button (camera toolbar + root overflow) opening one sheet:
  per-facing rows with capability-aware states (No LED rows disabled with reason, not
  fake buttons), Both toggle only when both exist, Screen light with brightness
  warning. Remember per-facing state (already modeled — surface it).

### T22. Screen recording

* **Today:** Add ring / empty overlay → audio+notification permission → system
  projection dialog → user leaves app → records → stops via notification tap or
  editor chip → auto-imports. No in-app guidance (“now switch to the app to record”);
  no countdown; no pause.
* **Easiest:** pre-flight sheet: what will happen (1-2-3 steps) + mic on/off toggle +
  “Start” → 3-2-1 countdown → persistent stop affordance (chip + notification, same
  label “■ Stop screen recording”). Post-stop: “Added as PiP — move to background?”
  snackbar.

### T23. Fullscreen camera takes

* **Today:** Add ring → “Fullscreen take (fallback)” → separate portrait activity →
  record → returns clip. Different controls (zoom slider here only), divergent flash
  labels, role baked into title.
* **Easiest:** remove from menus; trigger automatically when live camera fails, with
  “Live preview isn't available on this device — recording full-screen instead”.
  Keep zoom (consider adding pinch-zoom to live path later).

### T24. Composite RECORD (reaction capture)

* **Today:** button `GONE` until live+clip coexist (no hint); then START → audio-prep
  spinner → 720p30 record → STOP & SAVE → save dialog (View/Share/Close).
* **Easiest:** button always visible: disabled state reads “● Record — add camera + video”
  and tapping explains/links setup; one “⚡ Reaction setup” wizard (pick video → add
  camera → frame → record). Persist last record config; show live REC timer on canvas.

### T25. Export

* **Today (5+ taps):** Studio → Export → “Export settings…” → 4 sequential AlertDialog
  pickers → Export → live-warning maybe → ProgressDialog → save dialog.
  Quick export (720p30 H.264) exists but buried at ring depth 2.
* **Easiest (2 taps):** persistent bottom-bar Export button → one sheet with inline
  segmented pickers (Codec · Size · Quality · FPS) + live estimate + big Export;
  first row = “↻ Last time: H.265 · 720p · Balanced” one-tap repeat. Persist settings
  in prefs. Replace `ProgressDialog` with inline determinate progress + cancel.

### T26. Aspect ratio & background color

* **Today:** aspect chip cycles (1 tap, no confirm, **no undo**, rotates the device!) +
  Canvas ring explicit petals; background via Canvas → Background → 7 colors.
* **Easiest:** chip tap opens a 3-option picker (preview thumbnails), not blind cycle;
  aspect change pushes undo + confirm if sources will letterbox; background becomes a
  swatch row in the Canvas section of the inspector (1 tap, no ring dive).

### T27. Undo / redo / save / project ops

* **Today:** undo/redo in top bar + Controls ring + Project ring (3×); autosave + “Save
  now” toast; rename project in ring; close project in ring + back + system back
  (3 behaviors); Diagnostics via home gear + editor gear + ring (3×).
* **Easiest:** undo/redo top-bar only; title tap = rename; overflow ⋮ = Save now ·
  Diagnostics · Close (keep system back = Back). Home gear becomes labeled “Diagnostics”
  or moves into an About row (gear ≠ diagnostics).

### T28. Diagnostics & preview HUD

* **Today:** diagnostics list (unscrollable!) + HUD on/off + copy. HUD auto-appears in
  playback, tap hides, re-enable requires leaving the editor.
* **Easiest:** make diagnostics scrollable; move HUD toggle into editor overflow
  (“Stats overlay”); HUD gets a small ✕ + “don't show again” instead of whole-HUD tap;
  humanize stats (“Smooth · hardware” vs “Slow · software fallback” with a fix hint).

---

## 4. Duplicated functions in the UI

“Same verb, N surfaces” — the complete map. Severity = confusion × frequency.

| # | Verb | Surfaces (count) | Labels used | Verdict |
|---|---|---|---|---|
| D1 | Mute clip | quick bar · dock row · source ring · mixer panel · Mixing ring channel folder · channel ring · advanced sheet (**7**) | Mute/Unmute, icon only, “MUTED” | 🔴 Keep 2 (quick bar + audio sheet) |
| D2 | Solo | source ring · channel ring · mixer panel · advanced sheet · Mixing “clear solos” (**5**) | Solo/Solo:on/Clear solos/⭐ | 🔴 Keep 1 + badge-tap clear |
| D3 | Hide/show | quick bar · dock eye · source ring · advanced sheet · double-tap canvas (**5**) | Hide/Show, 👁/🚫, icons | 🔴 Keep 2, kill double-tap |
| D4 | Play/pause source | quick bar · source ring · advanced sheet · (master transport side-effects) (**4**) | Play/Pause/❚❚/▶ | 🟠 Keep 2 (+ dock inline) |
| D5 | Play/pause all | transport button · Controls ring (**2**) + per-source confusion | Play all/Pause all | 🟠 Keep transport only |
| D6 | Undo / Redo | top bar · Controls ring · Project ring (**3**) | Undo/Redo, icons | 🟠 Keep top bar only |
| D7 | Volume | mixer slider · channel ±10%/100% · advanced slider (**3** + mechanisms) | Level/Volume/Volume ±10% | 🔴 One slider, one place |
| D8 | Fit/Fill | quick bar · source ring · advanced sheet · Canvas “fit all” (**4**) | Whole frame/Fill box/▦/⤢ | 🟠 Standardize + keep 2 |
| D9 | Z-order | Arrange ring · Dock ring raise/lower · advanced front/back · dock drag (**4**) | To front/Raise/⬆/drag | 🔴 Dock drag + Front/Back |
| D10 | Corners/center | Arrange ring · advanced anchors · (Canvas bg promo nearby) (**3**) | 3 label sets + reused icons | 🟠 One 3×3 grid |
| D11 | Set as background | Arrange “Fill canvas” · Canvas “Selection as background” (**2**, different names!) | Fill canvas / Selection as background | 🔴 One name, one button |
| D12 | Delete | source ring · advanced danger (**2**) | Delete/🗑 Delete source | 🟢 OK (keep both, add Undo bar) |
| D13 | Duplicate | source ring (no-live) · advanced (allows live — **bug**) (**2**) | Duplicate/⧉ Duplicate | 🔴 Fix rule, one place |
| D14 | Add source | empty overlay · Add ring · Sources-empty CTA · dock stale “tap +” hint (**4**) | 4 variants | 🟠 One Add sheet everywhere |
| D15 | Aspect | top chip (cycle) · Canvas ring (3 petals) (**2**, different interaction!) | 16:9 chip vs petals | 🟠 One picker |
| D16 | Camera record take | quick bar · source ring · recChip · warn-dialog CTA (**4**) | Record/Stop/● STOP CAMERA TAKE | 🟠 Camera toolbar + chip |
| D17 | Switch camera | quick bar · source ring · flash ring item (**3**) | Switch cam/Switch to front/back | 🟠 Camera toolbar only |
| D18 | Flash/light entry | quick bar ⚡ · source Light folder · root Light petal · `openFlashRing` (**4 entries → 1 ring**) | Light/Flash/💡 | 🔴 One entry, capability-aware |
| D19 | Screen light | flash ring · lightRoot-no-live · CameraActivity torch personalities (**3**) | Screen light/Screen ON/BRIGHT | 🟠 Unify model + labels |
| D20 | Select source | canvas tap · dock row · Sources ring select · Dock next/prev · Jump-to-source (**5+**) | Select/Jump/Deselect | 🟠 Canvas + dock + steppers |
| D21 | Advanced sheet open | quick ⋮ · source ring · Dock ring · dock long-press (**4**) | Advanced…/⋮/long-press | 🟢 OK if inspector replaces it |
| D22 | Export settings | Export ring → panel · (quick export separate defaults!) (**2 paths, 2 default sets**) | Export settings…/Quick export | 🟠 One sheet, sticky settings |
| D23 | Rename (layer vs project) | advanced header · Project ring (**2 meanings, 1 word**) | Rename/Rename project | 🟠 Disambiguate labels |
| D24 | Diagnostics open | home gear · editor gear · Project ring (**3**) | ⚙/Diagnostics/info icon | 🟠 One (editor overflow) |
| D25 | Save | autosave (silent) · “Save now” toast (**2**) + close-flush | Save now | 🟢 Keep, add “Saved ✓” indicator |
| D26 | Close/exit | top back · Project “Close project” · system back (**3**, subtly different) | Close/‹ | 🟠 Back + overflow only |
| D27 | Text edit/color | source ring only (**1**) — but advanced sheet (the “deep UI”) has none | Edit text/Colour | 🔴 Gap: add to inspector |
| D28 | Opacity | advanced only (**1**) — fine, but volume's sibling should sit beside it | Opacity | 🟢 Keep in inspector |
| D29 | Snapshot frame | Controls ring only (**1**) — undiscoverable | Snapshot frame | 🟠 Move to transport overflow |
| D30 | Seek/restart/±10s | transport seekbar · Controls Restart/±10s (**2**) | Restart/−10s | 🟠 Transport ±10s buttons |

**Root causes:** (a) the radial migration copied every verb into the rings without
removing the original; (b) no naming dictionary (Fill×3 meanings, Rename×2);
(c) no “one verb, one home” rule. The fix is deletion + a naming table, not new features.

---

## 5. Wrong practices & violations catalog

### 5.1 Navigation & information architecture

* **V1. Radial as the only chrome.** The bottom tab bar was deleted entirely, so there
  is no persistent navigation — every job starts with “open the mystery wheel”.
  Violates Android bottom-navigation patterns and removes all scent. (EditorActivity `buildSheet`)
* **V2. Root ring doesn't fit its own ring.** 9 petals > `PAGE = 8` ⇒ the home menu
  opens with a “More 1/2” pager. Verified: `RadialMenus.root` lists 9 folders,
  `RadialWheel.PAGE = 8`. The first impression is a broken menu.
* **V3. Depth without breadcrumbs.** Sources → source → Arrange → corner = 4 levels;
  only signal is “‹ Back to X” 9sp subtitle. Users get lost mid-task.
* **V4. “Dock” ring vs “Sources” ring vs source dock panel.** Three names for the
  source list; the Dock ring's real content (open dock, next/prev, raise/lower) is a
  grab-bag. “Dock” is developer jargon — users think “layers”.
* **V5. Modal stacking.** Ring → sheet → AlertDialog pickers → ProgressDialog →
  result dialog. Export can stack 3 modals; Back behavior differs per layer.
* **V6. Two camera apps in one.** Live-on-canvas vs fullscreen activity have different
  controls, labels, orientation, and flash models. Users must learn both.
* **V7. Dead-end states.** Hidden sources (untappable), `GONE` record button,
  HUD with no in-editor re-enable, “No LED” petals that only toast — all strand users.

### 5.2 Discoverability & learnability

* **V8. Zero onboarding.** No coach marks for: Studio button, long-press, double-tap,
  pinch, handles, hidden-audio rule, record gating. The empty overlay teaches step 1
  and then silence.
* **V9. Icon-only everything.** Top bar, quick bar, dock toggles: 15+ icon buttons with
  no labels and no long-press tooltips. Meaning must be memorized.
* **V10. Stale/misleading hints.** Dock empty text references a “+” button that no
  longer exists; Studio subtitle lists 4 of 9 petals; “Fullscreen take (fallback)”
  exposes internals; CameraActivity hint states the obvious while hiding the role logic.
* **V11. Inconsistent naming dictionary.** Fill (crop) vs Fill canvas (promote) vs
  Fit (whole) vs Fit all; Rename ×2; Dock/Sources/Layers ×3; Controls/Mixing split.
  See §4 D11/D23.
* **V12. Keep-open ring re-render.** Toggle petals rebuild the ring in place — labels
  flip under the finger (Mute→Unmute), badges pop layout. No transition explaining
  what changed beyond the label swap.

### 5.3 Visibility of system status & feedback

* **V13. 56 toasts, 0 snackbars.** Every confirmation evaporates with no action.
  Hide/delete/mute/solo/duplicate all deserve Undo — currently undo requires knowing
  the top-bar icons exist.
* **V14. Silent no-ops.** Dragging a full-bleed main, tapping a locked layer, tapping
  “No LED” petals, scrubbing with stale seek max — all do nothing without telling why.
* **V15. Deprecated `ProgressDialog`** in import, record-prep, export, and save
  (4 call sites). Blocked UI, un-themed Holo visuals, no progress semantics for
  indeterminate phases.
* **V16. recChip overload.** One red chip means “stop screen-rec” OR “stop camera take”
  depending on hidden state; label swaps between “STOP SCREEN-REC” / “STOP CAMERA TAKE”.
  During composite RECORD it shows nothing (recording state only on the bottom button).
* **V17. Stats HUD cryptics.** “HW · fps · ms/f” needs the Diagnostics screen's
  paragraph to decode; auto-shows over the canvas; tap-anywhere-to-hide with no
  re-entry in the editor.
* **V18. Save invisibility.** Autosave is silent; “Save now” toasts and vanishes.
  No “● Unsaved / ✓ Saved” indicator anywhere despite `markDirty`/`flushSave` knowing.

### 5.4 Gestures & direct manipulation

* **V19. Double-tap = hide.** A destructive-feeling action on an accidental-prone
  gesture; conflicts with platform double-tap-zoom expectations; no confirm/undo cue.
  (`StageView` 320 ms / 36dp detector + `onDoubleTap → toggleVisible`.)
* **V20. Long-press hijack.** 460 ms press opens the ring and *cancels* the pending
  drag (`mode = NONE`), so slow-finger drags on small PiPs open a menu instead of
  moving. No visual press-and-hold affordance.
* **V21. 9dp handles.** Selection handles are ~18px visual with no expanded touch slop
  (`hitHandle` touch = min(20dp, 90% of half-size) — on small PiPs the whole layer
  becomes handles; the code comments admit the “cannot drag the PiP” bug class).
  Rotate knob floats 18dp off-box — fat-finger magnet.
* **V22. Invisible snap.** `snapMove` pulls to centers/edges silently; no guide lines,
  no haptic, no way to disable. Users fight “magnetic” drags without knowing why.
* **V23. Pinch complexity.** Pinch does scale+rotate+translate around the midpoint with
  `clampInside(0.25)` — powerful but undiscoverable and easy to lose layers with;
  no reset-transform action anywhere (no “Reset size/rotation”!).

### 5.5 Consistency & standards

* **V24. Emoji + vector + text style soup.** Buttons mix emoji (👁🔇⭐◤⬆🗑), vectors
  (`ic_*`), and text (“Copy”, “Rename”) with no system. Emoji render differently per
  OEM/skin — the UI looks different on every phone.
* **V25. Dialog inconsistency.** Feature UI is custom dark rounded; all inputs
  (rename/add-text/export pickers/project dialog) are stock `AlertDialog` + bare
  `EditText` — light-on-some-OEMs, unstyled, mismatched padding.
* **V26. Accent overuse.** The same orange means: brand, play, selected, record-idle,
  active toggle, badge, focused ring. Red/orange/green/amber state colors collide
  (record-idle orange vs recording red vs danger red).
* **V27. Orientation whiplash.** Cycling the aspect chip force-rotates the device
  (landscape/portrait/unspecified per aspect). A content setting physically rotates
  the phone — startling, and `R11 → UNSPECIFIED` behaves differently from the others.
* **V28. Undo inconsistency.** Aspect change pushes **no undo** (verified:
  `changeAspect` mutates directly) while background color does; slider drags push
  time-throttled snapshots instead of one-per-gesture.

### 5.6 Android platform & correctness-adjacent UI bugs

* **V29. Diagnostics not scrollable.** Rows are appended to a plain `LinearLayout`
  (verified: no `ScrollView`) — content clips on small phones with no way to reach
  Copy/HUD toggle.
* **V30. Thumbnail decode on UI thread.** `BitmapFactory.decodeFile` inside
  `ProjectsAdapter.getView` (verified) — scroll jank proportional to thumbnail size;
  no placeholder, no async, no cache.
* **V31. Export amnesia.** Codec/quality/resolution/fps are function locals in
  `buildExportPanel` — every visit resets to HEVC-or-H264/720p/Balanced/30fps.
* **V32. Dock drag math ignores scroll.** `handleTouch` computes `rawY − container
  origin` without `ScrollView.scrollY` — dragging while scrolled targets wrong rows;
  no auto-scroll near edges.
* **V33. Sheet rebuild churn.** Mixer and advanced sheets call `setSheet`/`openAdvancedSheet`
  (full `removeAllViews` + rebuild) on *every toggle* — scroll position lost, sliders
  re-created mid-drag risk, 230 ms re-animation on each tap.
* **V34. Home dialog chip logic.** `showNewDialog` chip clicks run a convoluted
  identity lookup; selection state lives in view `isSelected` (fragile, untested).
* **V35. Screen-light z-hack.** `applyScreenLight` inserts a white view at index 0 and
  `bringToFront()`s stage/sheet/wheel to compensate — fragile ordering, fights window
  brightness restore, no thermal/battery note.
* **V36. Permission dead-ends.** Denials only toast (“…permission is needed”) with no
  rationale, no retry path, no Settings deep-link; screen/camera recording can proceed
  **silently muted** without mic permission (discovered at playback).
* **V37. OpenGL “?” row.** Diagnostics reads `GLES20.glGetString` with no EGL context —
  always null. A diagnostics screen showing “?” for the headline GPU row undermines trust.
* **V38. Seekbar staleness.** `seek.max` set at build; updated only in
  `afterStructureChange` — paths that change duration without it (live swap edge cases)
  desync time/duration labels.

### 5.7 Accessibility (critical — currently ~0/10)

* **V39. Zero `contentDescription`.** Verified by grep: not a single one. Every
  `IconBtn`, petal, handle, chip is silent to TalkBack. Icon-only UI + no descriptions
  = unusable with a screen reader.
* **V40. Touch targets.** Quick bar 38dp, dock toggles 38dp, petals 46–52dp (ok),
  canvas handles ~18px, seekbar thin — majority below the 48dp minimum.
* **V41. Color-only state.** Selected (orange ring), muted (red), locked (grey),
  badges (color chips) — no shape/text redundancy for color-blind users.
* **V42. Tiny low-contrast text.** 9–10.5sp grey-on-dark for status/subtitles/badges
  (`MUTED BY SOLO`, ring subtitles) — below readability guidance, no scaling safety
  (fixed-dp layouts clip at large font scales).
* **V43. No focus/keyboard semantics.** Custom views (`StageView`, rings, IconBtn)
  expose no accessibility actions; scrim dismiss and hub-back are touch-only concepts.
* **V44. No reduced-motion path.** Staggered overshoot blooms, pulses, scale pops on
  every interaction with no `ANIMATOR_DURATION_SCALE`/reduced-motion respect.

### 5.8 Visual & layout

* **V45. Quick-bar overflow.** The contextual bar is a `HorizontalScrollView` with no
  fade/affordance — on narrow phones core verbs (⋮ advanced!) hide off-screen with no cue.
* **V46. Empty-overlay overflow.** 5 × 46dp buttons + header in an unscrolled card —
  clips in landscape/short screens where the Studio button + transport also compete.
* **V47. Sheet height clamp.** `maxH = 40% of screen (min 170dp)` — mixer with 6 sources
  or advanced sheet scrolls internally while the canvas shrinks; no drag-to-expand.
* **V48. Hardcoded dark-only theme.** No light mode, no dynamic/Material-You color,
  status/nav bars hardcoded — fine for v1 but a Play-listing gap.

---

## 6. Proposed simplified IA

Keep every capability; cut surfaces from ~14 to 6. Principle: **one verb, one home;
ring becomes accelerator, not gatekeeper.**

```
Editor
├── Top bar:  ‹ Back · [Project name ▾ rename] · Saved ✓/● · Undo · Redo · ⋮ overflow
│       overflow: Stats overlay · Diagnostics · Fit all sources · Snapshot · Close
├── Canvas (tap select · drag · pinch · handles ≥24dp slop)
│       + floating pills: “👁 N hidden” · “● REC 0:12” (when recording)
├── Selection toolbar (replaces quick bar; fixed, no scroll, labeled icons):
│       👁 Hide · 🔊 Mute* · ▶ Play* · 🔒 Lock · [Fill|Fit] · 💡 Light (camera) · ⋮ More
├── Bottom bar (persistent, 5):
│       Layers · ＋ Add · ▶/❚❚ + time + seek + ±10s · Audio · Export
├── Sheets (one host, sticky):
│       · Layers  = dock list (drag reorder fixed) + select + ‹ › steppers
│       · Add     = 5 visual tiles + recents
│       · Audio   = per-channel mute/solo/loop/volume (+ “why muted” hints)
│       · Inspector (⋮ More) = per-source: Rename · Opacity · Fit/Fill · Position 3×3 ·
│                 Set background · Front/Back · Text controls · Duplicate · Delete
│       · Export  = sticky Codec/Size/Quality/FPS + estimate + Export + “repeat last”
├── Camera toolbar (when live selected): ● Record · ⇄ Switch · 🪞 Mirror · 💡 Light
└── ◉ Ring (kept!): long-press Studio/floating ◉ = command palette for power users
```

What gets **deleted**: Controls ring, Dock ring, Mixing ring, channel ring, Light root
petal, Arrange ring (→ inspector grid), double-tap-hide, “More” on root (root shrinks
to ≤7), CameraActivity menu entry (auto-fallback), 4 export-picker dialogs (→ inline).

Tap-count deltas (median across §3 tasks): **3.5 → 1.2 taps**; zero task exceeds 2 taps
except export-with-changed-settings (3) and guided wizards.

---

## 7. Prioritized action plan

### P0 — fix broken / dangerous (do first, each ≤1 day)

| ID | Action | Touches |
|---|---|---|
| P0-1 | Shrink root ring to ≤8 (merge Dock→Sources, drop Controls) so home never pages | RadialMenus.root |
| P0-2 | Remove double-tap-hide; add “N hidden” recovery pill | StageView, EditorActivity |
| P0-3 | Make RECORD always visible w/ disabled reason + setup link | updateRecordButton |
| P0-4 | Persist export settings in prefs + “repeat last” row | buildExportPanel |
| P0-5 | `contentDescription` on all icon buttons/petals/chips (string table) | Icons.kt, RadialWheel, UI.kt |
| P0-6 | Scrollable Diagnostics + move HUD toggle to editor overflow | DiagnosticsActivity, EditorActivity |
| P0-7 | Thumbnails off UI thread (async + cache + placeholder) | HomeActivity.ProjectsAdapter |
| P0-8 | Undo for aspect change; confirm on cycle or switch to picker | changeAspect |
| P0-9 | Fix dock drag scroll math + auto-scroll | SourceDock.handleTouch |
| P0-10 | Unify live-duplicate rule (ring vs advanced disagree) | RadialMenus.source, openAdvancedSheet |

### P1 — simplify (one verb, one home)

| ID | Action |
|---|---|
| P1-1 | Restore persistent bottom bar (Layers · Add · Transport±10s · Audio · Export); ring = long-press/shortcut |
| P1-2 | Merge mixer + Mixing/channel rings + advanced audio → one Audio sheet |
| P1-3 | Merge Arrange + advanced position + Canvas promo → inspector Position grid; rename Fill-canvas → “Set as background” |
| P1-4 | One camera toolbar; auto-fallback fullscreen; delete fallback petal |
| P1-5 | One light sheet (capability-aware rows); single 💡 entry |
| P1-6 | Text inspector (edit/size/swatches/shadow); double-tap text = edit |
| P1-7 | Snackbars with Undo for hide/delete/mute/solo; kill 56 bare toasts progressively |
| P1-8 | Replace `ProgressDialog` (4 sites) with modern inline progress |
| P1-9 | Enlarge handles (24dp slop), add snap guides + haptic, add Reset transform |
| P1-10 | Naming dictionary + icon audit (corners ≠ fit icons; no emoji-as-icon) |

### P2 — polish & platform

| ID | Action |
|---|---|
| P2-1 | First-run coach marks (5 tips max) + contextual empty states |
| P2-2 | Permission rationale + Settings deep-link + “recording muted” pre-warning |
| P2-3 | Home: swipe delete w/ Undo, long-press menu, relative timestamps |
| P2-4 | Screen-record pre-flight sheet + countdown + unified stop label |
| P2-5 | Sheet: no full rebuild on toggle (update rows in place, keep scroll) |
| P2-6 | 48dp targets pass, focus semantics, reduced-motion respect |
| P2-7 | Light mode / dynamic color investigation; dialog theming unification |
| P2-8 | Fix real EGL version read or drop the row; humanize HUD stats |

**Suggested order:** P0-1 → P0-5 → P0-3 → P0-4 → P1-1 (spine) → P1-2/P1-3 (sheets) →
P0-2/P1-9 (gestures) → rest. P1-1 unlocks deleting 4 rings; do it before polishing rings.

---

## Appendix A — full gesture inventory

| Surface | Gesture | Action | Issues |
|---|---|---|---|
| Canvas | tap source | select | hidden/transparent sources untappable |
| Canvas | tap empty | deselect | ok |
| Canvas | **double-tap** | **hide/show** | dangerous, accidental, remove (V19) |
| Canvas | drag | move + invisible snap + clamp | snap invisible; full-bleed no-op silent |
| Canvas | 8 handles | resize (corner=aspect, edge=distort) | 18px targets; text free-form ok |
| Canvas | knob above box | rotate w/ 5° cardinal snap | tiny, floats off-box |
| Canvas | pinch | scale+rotate+translate | undiscoverable; no reset |
| Canvas | **long-press 460ms** | open ring at finger | hijacks slow drags; no affordance |
| Dock row | tap | select | ok |
| Dock row | long-press | advanced sheet | undiscoverable |
| Dock handle | vertical drag | Z reorder | scroll-offset bug (V32) |
| Quick bar | horizontal swipe | reveal overflow buttons | no affordance (V45) |
| Transport | scrub | seek live | heavy; max can stale |
| Aspect chip | tap | **blind cycle + rotate device** | no confirm/undo (V27/V28) |
| HUD | tap | hide (exiled re-enable) | trap (V17) |
| Splash | any | nothing (unskippable 2.7s) | add skip |
| Ring | hub tap / Back | pop level / close | ok |
| Ring | scrim tap | close all | ok |
| Ring | petal tap | push/act/toggle+refresh | refresh-under-finger (V12) |

## Appendix B — entry-point map (every opener → every surface)

| Surface | Opened from |
|---|---|
| Root ring | Studio button · empty-overlay btn · canvas long-press (empty) |
| Source ring | quick ◉ · long-press (source) · Sources ring folder · Dock “jump” |
| Add ring | root · Sources-empty · Sources-panel CTA |
| Flash ring | quick ⚡ · source Light folder · root Light · openFlashRing |
| Dock panel | Dock ring · (replaces content of shared sheet host) |
| Mixer panel | Mixing ring “open panel” |
| Export panel | Export ring “settings” |
| Advanced sheet | quick ⋮ · source ring · Dock ring · dock long-press |
| CameraActivity | Add “Fullscreen take” · live-camera error fallback |
| Screen rec | Add ring · empty overlay · (stops: notification OR chip) |
| Export run | Export panel CTA · quick-export petal · warn-dialog “export anyway” |
| Diagnostics | home ⚙ · editor ⚙ · Project ring |
| Composite record | bottom RECORD (gated) |

*End of report. No code was modified.*
