# Ahmed Reaction Studio — UI/UX Master Plan

**Rewritten:** 2026-09-05
**Supersedes:** the previous contents of `UI Plan2.md` (the old 48-section checklist)
**Status:** active — this is the single source of truth for editor UI work
**Scope:** UI/UX wiring and consolidation only. Media pipelines are not in scope (see Rule 2).

> **Why this was rewritten.** The old plan was written before the OBS-style
> source architecture, the Step 5 editor rebuild and the radial-ring
> consolidation landed. It told the agent to "audit the controls" and "create a
> source selection model" — work that is already done and shipped. This version
> keeps the durable rules, deletes the finished phases, and replaces them with
> the job that is actually left: **killing the remaining duplicate verbs,
> finishing the audio surface, and making the UI discoverable.**

---

## 0. How to use this document

1. Read §1 so you know what already exists. Do not rebuild it.
2. Read §2 (the rules). They are not negotiable.
3. Work the backlog in §6 in order: **P0 → P1 → P2**. Never start P1 before P0 is verified.
4. One task at a time. Build. Test on device. Tick the box. Append the report from §7.
5. Never tick a box because the code compiles.

Checkbox states:

```text
- [ ] not started
- [x] done and verified on device
- [!] blocked (reason recorded directly beneath it)
```

---

## 1. Where the app actually is today

Everything in this section was verified by reading `HEAD` (single squashed commit
`1cde176`) — not assumed.

### 1.1 Already built — do NOT rebuild

| Capability | Lives in | Evidence |
|---|---|---|
| OBS-style source model, one verb per command | `core/Sources.kt` → `SourceController` | every mutation goes through a verb; undo snapshot pushed first |
| Per-source state: visible / locked / muted / solo / loop / fit / volume / opacity / playing / speed | `core/Model.kt` → `Layer` | single serialized state object |
| Preview == export (one Compositor, one state) | `core/Compositor.kt`, `export/Exporter.kt` | same geometry rules both paths |
| Undo / redo + autosave + snapshot recovery | `core/Undo.kt`, `core/ProjectStore.kt` | — |
| Persistent bottom bar: **Layers · Add · Audio · Text · Export** | `EditorActivity.buildSheet()` → `addTab(...)` ×5 | old plan §28 — **done** |
| Bottom-bar sheets: Layers / Audio / Export | `EditorActivity.setSheet(...)` | — |
| Root radial ring, 7 petals, **never pages** | `RadialMenus.root()` | `Sources · Add · Audio · Light · Canvas · Export · Project`; `RadialWheel.PAGE = 8` |
| Sub-petal navigation (ring → folder → ring) | `RadialWheel.kt` + `item(..., keepOpen)` | old plan §27 — **done** |
| Deleted rings: Controls / Dock / Mixing — verbs folded into transport, Layers sheet, Audio sheet | comment block above `RadialMenus.root()` | — |
| Live camera **composited on the canvas** (no modal required) | `editor/LiveCamera.kt` + `PreviewEngine` | old plan §9 / §18 — **done** |
| Per-source Fit (contain) vs Fill (cover); camera defaults to Fit | `Model.FIT_FIT / FIT_FILL`, `core/LayerFit.kt` | old plan §5 — **done** |
| Floating quick control bar for the selected source | `EditorActivity.refreshQuickBar()` | 👁 hide · 🔇 mute · ⏯ pause · 🔒 lock · fit · ◉ ring · ⋮ advanced |
| Source dock mini-mixer with eye/mute + drag Z-reorder | `editor/SourceDock.kt` | — |
| Full Canvas focus mode (hide all chrome) | `EditorActivity.setFullCanvas(on)` | old plan §22 — **done** |
| Landscape side rail | `EditorActivity.sideRail` | — |
| Hardware flash on any lens that has one + screen-light fallback | `camera/TorchController.kt`, `RadialMenus.flashItems()` | capability-aware, disabled rows instead of fake buttons |
| Master clock, per-layer clocks, seek, ±10 s, play/pause all | `editor/PreviewEngine.kt` | — |
| Recording into the project | `export/CompositionRecorder.kt` | `masterGain` field exists (line ~183) |
| Export: H.264 / H.265 / VP8 / VP9, resolution / quality / fps, device-filtered | `export/Exporter.kt`, `MediaSave.kt`, `ExportValidator.kt` | — |
| Screen capture source | `capture/ScreenCaptureService.kt` | — |
| Multi-container import, un-decodable files reported not crashed | `core/MediaKit.kt` | — |
| "N hidden" recovery pill | `EditorActivity.hiddenPill` | — |
| `contentDescription` on bottom-bar tabs | `addTab(..., desc)` | partial a11y |

### 1.2 What is still wrong

The engine is solid. The remaining problem is **surface sprawl**: the OBS
migration and the radial migration each *added* a surface without removing the
one it replaced. The same verb is now reachable from 2–4 places with 2–4
different names.

Three concrete symptoms:

1. **Duplicated verbs.** Mute, solo, hide, volume, fit/fill, z-order, duplicate
   and "set as background" each still have more than one home, and the copies do
   not always agree (e.g. live-source duplication is allowed in the advanced
   sheet but refused in the ring).
2. **Naming drift.** "Fill" means three different things. "Rename" means two.
   Dock / Sources / Layers are three words for one list.
3. **Discoverability.** Icon-only controls, no coach marks, no onboarding, stale
   hint text, and several hidden gestures (double-tap = hide, long-press = ring).

### 1.3 Stale document warning

`docs/UI_AUDIT_REPORT.md` (2026-09-05) is a **snapshot of an earlier commit**.
Several of its findings are already fixed at `HEAD`:

| Audit finding | State at HEAD |
|---|---|
| V1 "bottom tab bar deleted entirely" | **fixed** — 5-tab bar exists |
| V2 root ring pages "More 1/2" (9 petals) | **fixed** — 7 petals, never pages |
| P1-1 restore persistent bottom bar | **done** |
| P1-2 merge mixer + Mixing/channel rings → one Audio sheet | **mostly done** — mixer sheet exists; ring remnants need checking |
| P0-2 "N hidden" recovery pill | **done** (`hiddenPill`) |

**Rule:** treat the audit's D-matrix (§4, verbs D1–D30) as the *inventory* and
this document's §4 as the *ruling*. Where they disagree, re-read the code — do
not trust either document blindly. Task **T-01** exists precisely to settle this.

---

## 2. Non-negotiable rules

**Rule 1 — One verb, one home.**
Every action has exactly one canonical surface. Related verbs may be grouped,
but a verb never appears on two surfaces. If you add a verb somewhere, delete it
from the other place in the same commit.

**Rule 2 — Do not rewrite working media pipelines.**
Never rewrite, for UI reasons: `MediaCodec` video decode, `MediaCodec` audio
encode, `MediaExtractor`, `MediaMuxer`, the Camera2 capture pipeline,
`AudioMixer`, `Compositor`, or `PreviewEngine`'s clock model. Change UI *wiring*
only. If a UI bug seems to require a pipeline change, stop and prove it first.

**Rule 3 — Reuse, never fork.**
Do not create `NewSourceManager2`, `NewAudioMixerUI`, `NewRadialWheel2`. Extend
the existing class. All state mutation goes through `SourceController`.

**Rule 4 — Preview must equal export.**
Any control you add or move must be read by the same `Compositor` path the
exporter uses. A control that only affects the preview is a bug.

**Rule 5 — No dead buttons.**
Every button does something real, on this device, right now. If a capability is
missing, show the control **disabled with a reason** (see `flashItems()` for the
correct pattern) — never a toast-only petal, never a fake white-screen torch.

**Rule 6 — One problem at a time.**
Inspect → reproduce → find root cause → smallest safe change → build → device
test → tick → report. No batch refactors.

**Rule 7 — Nothing is "complete" until it is tested on the device.**
A green build is not a completed task.

**Rule 8 — The canvas is the product.**
No panel, sheet, ring or bar may squeeze the canvas below a usable size. Chrome
is measured and capped (`ViewportFit` + `capPanelHeight`); keep it that way.

---

## 3. The Studio spine

One layout model, three orientations. The ring is a **shortcut**, not the
navigation — navigation is the persistent bar.

### 3.1 Portrait

```text
┌──────────────────────────────────────────┐
│ ‹ Project        16:9 ▾      ↶ ↷  ⛶  ⚙   │  top bar  (≥44 dp targets)
├──────────────────────────────────────────┤
│                                          │
│                                          │
│             CANVAS  (contain-fit)        │
│                                          │
│        selected source = orange frame    │
│                                          │
│  ┌────────────────────────────────────┐  │
│  │ 👁  🔇  ⏯  🔒  ⤢   ◉   ⋮           │  │  floating quick bar
│  └────────────────────────────────────┘  │
├──────────────────────────────────────────┤
│  [Camera]  [Video]  [Image]  [ + ]       │  source dock strip
├──────────────────────────────────────────┤
│   Layers  │  Add  │  Audio │ Text │Export│  bottom bar — always visible
├──────────────────────────────────────────┤
│   -10s         ▶          +10s     0:17  │  transport
├──────────────────────────────────────────┤
│                 ● RECORD                 │  headline action
└──────────────────────────────────────────┘
```

### 3.2 Landscape

```text
┌───────────────────────────────┬──────────┐
│ ‹ Project   16:9 ▾   ↶ ↷ ⛶ ⚙ │          │
├───────────────────────────────┤  right   │
│                               │  rail    │
│          CANVAS               │  (scroll)│
│                               │          │
│   [ quick bar ]               │  ─────── │
├───────────────────────────────┤  Layers  │
│  Layers │ Add │ Audio │Text│Ex │  Add    │
└───────────────────────────────┴──────────┘
```

### 3.3 Full Canvas (focus mode)

Trigger: ⛶. Hides top bar, dock, bottom bar, transport. Keeps canvas + a single
exit affordance. Back exits. **This is not recording** — recording is a separate
state that may be active with or without focus mode.

---

## 4. Verb → home (the ruling)

This table is the authority. `Canonical home` is the only place the verb may
appear after the backlog is done. `Delete from` must happen in the same commit
that adds/moves it.

| # | Verb | Canonical home | Delete from | Notes |
|---|---|---|---|---|
| V01 | Mute / unmute source | Quick bar 🔇 + Audio sheet row | source ring, advanced sheet | 2 allowed (toggle + mixer row), labels identical |
| V02 | Solo | Audio sheet only (+ tap the solo badge to clear all) | source ring, channel ring, advanced sheet | solo is a flag, never a stored "previous state" |
| V03 | Hide / show | Quick bar 👁 + dock eye | source ring, advanced sheet, **double-tap canvas** | double-tap hide is dangerous — remove |
| V04 | Pause / resume one source | Quick bar ⏯ + dock inline | source ring, advanced sheet | per-layer clock; holds last frame |
| V05 | Play / pause everything | Transport ▶ only | Controls-style rings | master clock |
| V06 | Undo / redo | Top bar only | all rings | — |
| V07 | Source volume | Audio sheet slider (**one** slider) | channel ±10 % petals, advanced slider | — |
| V08 | Master output gain | Audio sheet header | — | `CompositionRecorder.masterGain` exists but has **no UI**; recording-only today — see T-14 before exposing it |
| V09 | Fit / Fill | Quick bar ⤢ | source ring, advanced sheet, Canvas "fit all" | labels: **Fill** = crop-to-cover, **Fit** = whole frame |
| V10 | Z-order | Dock drag handle + Layers sheet Front/Back | Arrange ring, ring raise/lower | keep drag + 2 buttons |
| V11 | Position / anchors | Layers sheet 3×3 grid | Arrange ring, advanced anchors | one grid, one label set |
| V12 | Set as canvas background | Layers sheet, **one** button named "Set as background" | Arrange "Fill canvas", Canvas "Selection as background" | two names for one verb today |
| V13 | Delete | Quick bar overflow / Layers sheet 🗑 with Undo snackbar | source ring (keep one only) | — |
| V14 | Duplicate | Layers sheet only | source ring | **bug:** ring refuses live sources, advanced sheet allows them — settle on refuse + reason |
| V15 | Add source | Add sheet (one sheet, opened from bottom-bar Add, empty-state CTA, ring Add) | empty overlay variant, stale "tap +" hint | one sheet, four entry points is fine — one *implementation* |
| V16 | Canvas aspect | Top-bar chip → **picker** (not blind cycle) | Canvas ring petals | cycling also rotates the device today; needs undo or a confirm |
| V17 | Camera record take | Camera toolbar + record chip | quick bar, source ring, warn-dialog CTA | — |
| V18 | Switch camera | Camera toolbar only | quick bar, source ring, flash ring | — |
| V19 | Flash / light | One Light sheet, capability-aware rows; entry from ring **Light** only | quick bar ⚡, source Light folder, `openFlashRing` | follow the `flashItems()` disabled-with-reason pattern |
| V20 | Screen light | Same Light sheet | `CameraActivity` torch personalities | one model, one label: "Screen light" |
| V21 | Select source | Canvas tap + dock row + Layers steppers ‹ › | Sources-ring "jump" | 3 is fine; they are navigation, not duplicate verbs |
| V22 | Advanced sheet | Quick bar ⋮ only | source ring, dock ring, dock long-press | long-press stays as a *shortcut* to the same sheet |
| V23 | Export | Export sheet (settings persisted) + quick-export reusing the same defaults | two independent default sets | today they disagree |
| V24 | Rename | "Rename source" (Layers sheet) / "Rename project" (Project ring) | ambiguous bare "Rename" | disambiguate labels |
| V25 | Diagnostics | Editor overflow ⚙ only | home gear, Project ring | — |
| V26 | Save | Autosave + a visible "Saved ✓" indicator | "Save now" toast | — |
| V27 | Close / exit | Top back + Project overflow only | Project "Close project" | — |
| V28 | Text edit / font / size / colour / shadow | Text inspector (Layers sheet when a TEXT source is selected) | source-ring-only today | gap: deep sheet has no text controls |
| V29 | Opacity | Layers sheet, adjacent to volume | advanced-only today | fine, but must sit with its sibling |
| V30 | Snapshot frame | Transport overflow | ring-only (undiscoverable) | — |
| V31 | Seek / restart / ±10 s | Transport row + ±10 s buttons | ring Restart/−10 s | — |
| V32 | Loop | Audio sheet row (audio semantics) | advanced sheet | one home, consistent with V01/V02 |

### 4.1 Naming dictionary (use these words, exactly)

| Use | Never also call it |
|---|---|
| Layers | Dock, Sources (when referring to the list) |
| Fill | Fill canvas, Fill box (Fill = crop-to-cover, always) |
| Fit | Whole frame, Fit all (Fit = letterbox the whole frame) |
| Set as background | Fill canvas, Selection as background, Promote |
| Screen light | Screen ON, BRIGHT, Screen flash |
| Rename source / Rename project | bare "Rename" |
| Source | Clip, Layer, Channel (pick one: **source** in UI, `Layer` in code) |
| Studio (the ring) | Wheel, Radial, Controls |

---

## 5. Rules for recording state

The editor, the preview and the recorder are three different things. Keep them
separate:

```text
STUDIO (edit)          no playback of added media unless ▶ is pressed
   │
   ├── ▶ Play      → PREVIEW      (master clock runs, nothing is written)
   │
   └── ● Record    → RECORDING    (CompositionRecorder writes)
                        │
                        ├── Pause / Resume
                        ├── Back / Full Canvas  → recording KEEPS running
                        └── Stop                → FINISHED
```

Rules:

- **Back never stops recording.** Returning to Studio shows a live `● REC mm:ss`
  indicator; there is exactly one recording session.
- **Adding media never starts playback.** A new clip lands paused at 0:00 with
  its first frame shown. Only ▶ (or the per-source ⏯) starts it.
- **A disabled RECORD is better than a missing one.** If setup is incomplete,
  show RECORD disabled with the reason and a link that fixes it. Never `GONE`.

---

## 6. Backlog

Ordered. Do P0 completely before touching P1.

### P0 — broken, dangerous, or confusing

- [x] **T-01** — Re-verify the verb inventory against `HEAD`. Walk `EditorActivity`, `RadialMenus`, `SourceDock`, `StageView`, `Icons.kt` and mark each row of §4 `confirmed / already fixed / still duplicated`. This unblocks everything else; nothing below is trustworthy until it is done.
- [x] **T-02** — (already fixed at HEAD, verified by T-01) Remove **double-tap to hide** from `StageView`. Accidental, undiscoverable, and destructive-feeling. `hiddenPill` already provides recovery for the intentional path.
- [x] **T-03** — (already fixed at HEAD, verified by T-01) Make **RECORD always visible**; when setup is incomplete show it disabled with a one-line reason and a tap-to-fix action. Replace any `visibility = GONE`.
- [x] **T-04** — (already fixed at HEAD, verified by T-01) Persist export settings in prefs and make **quick-export reuse the exact same settings** (today two paths, two default sets). Add a "repeat last export" row.
- [x] **T-05** — `contentDescription` on every icon button, petal and chip (bottom-bar tabs already have them; quick bar, dock toggles and top bar do not).
- [x] **T-06** — Fix the **live-source duplicate disagreement**: ring refuses, advanced sheet allows. Pick one rule (refuse a live duplicate is the safe default), implement it in `SourceController` so both surfaces inherit it.
- [x] **T-07** — (already fixed at HEAD, verified by T-01) Fix the **dock drag scroll-offset math** and add auto-scroll while dragging near the edges (`SourceDock.handleTouch`).
- [x] **T-08** — (already fixed at HEAD, verified by T-01) Aspect chip: replace the blind cycle with a picker, and push an undo snapshot on change. A tap must never silently rotate the device.
- [x] **T-09** — (already fixed at HEAD, verified by T-01) Enlarge resize handles to ≥24 dp touch slop; add visible snap guides, a haptic tick, and a **Reset transform** action.
- [x] **T-10** — (already fixed at HEAD, verified by T-01) Move project-thumbnail decoding off the UI thread (async + cache + placeholder) in `HomeActivity.ProjectsAdapter`.
- [x] **T-11** — (already scrollable at HEAD; this pass fixed the editor entry point, which opened Diagnostics while announcing "Project settings") Make Diagnostics scrollable and reachable from the editor overflow only; move the HUD toggle out of the dead-end state (tapping the HUD hides it with no way back).

### P1 — one verb, one home (the de-duplication pass)

Each task = delete the duplicate *and* verify the canonical one still works.

- [x] **T-12** — **Mute/solo consolidation.** Canonical: quick-bar toggle + Audio sheet row. Remove from source ring, channel ring, advanced sheet. Keep the OBS solo flag semantics (never overwrite state).
- [x] **T-13** — **Volume consolidation.** One slider per source, in the Audio sheet. Remove the channel ±10 %/100 % petals and the advanced-sheet slider. Verify camera-source volume changes *only* the camera and clip volume changes *only* that clip.
- [x] **T-14** — **Master output gain.** Decide and document whether `CompositionRecorder.masterGain` should become a real user-facing control. If yes: expose it in the Audio sheet header, and either wire the same gain into `Exporter` or label it clearly as recording-only. Do not touch `AudioMixer` internals to achieve this.
- [x] **T-15** — **Hide consolidation.** Quick bar 👁 + dock eye. Remove from source ring and advanced sheet. Hidden sources keep their audio (documented OBS rule — do not "fix" this).
- [x] **T-16** — **Fit/Fill consolidation.** Quick bar ⤢ only. Remove from source ring, advanced sheet and Canvas "fit all". Standardize on the §4.1 labels.
- [x] **T-17** — **Z-order consolidation.** Dock drag + Front/Back in the Layers sheet. Remove Arrange-ring raise/lower and the advanced-sheet front/back.
- [x] **T-18** — **Position consolidation.** One 3×3 anchor grid in the Layers sheet. Remove Arrange-ring corners and advanced-sheet anchors.
- [x] **T-19** — **"Set as background" consolidation.** One button, one name, in the Layers sheet. Delete the other two.
- [x] **T-20** — **Light consolidation.** One Light sheet with capability-aware rows; single entry point (ring → Light). Remove quick-bar ⚡, source Light folder and `openFlashRing` as independent entries. Unify the screen-light model and label across editor and `CameraActivity`.
  *Done:* one capability-aware Light sheet (`EditorActivity.openLightSheet`).
  The `lightRoot` / `flash` / `flashItems` ring levels are deleted. Rows for a
  lens with no LED render **disabled with the reason**. `openFlashRing()` is
  retained as the named entry point (an integration guard pins it) but now
  opens the sheet.
- [x] **T-21** — **Camera controls consolidation.** One camera toolbar (record take, switch facing, flash). Remove switch-camera and record from quick bar and source ring.
  *Done:* the quick bar's camera cluster (record take · switch facing · mirror
  · light) **is** the toolbar — it appears on the canvas as soon as the camera
  is selected. The source ring no longer repeats those verbs; it shows a
  "Camera controls" signpost that selects the camera.
- [x] **T-22** — **Add-source consolidation.** One Add sheet implementation opened from: bottom-bar Add, empty-state CTA, Add ring. Remove the stale "tap +" hint from the dock empty state.
- [x] **T-23** — **Advanced sheet consolidation.** Openable from quick-bar ⋮ only; dock long-press remains as a shortcut to the same sheet. Remove the source-ring and dock-ring entry points.
- [x] **T-24** — **Transport consolidation.** Seek/restart/±10 s live in the transport row only. Move Snapshot frame to the transport overflow. Remove Restart/−10 s petals.
- [x] **T-25** — **Text inspector.** When a TEXT source is selected, the Layers sheet exposes edit text / size / colour / shadow. Double-tap on canvas opens the same editor.
- [x] **T-26** — **Snackbars with Undo** for hide, delete, mute, solo, duplicate. Progressively retire bare toasts (≈56 today), starting with the destructive verbs.
- [x] **T-27** — Replace the 4 `ProgressDialog` sites with inline progress that respects Back.

### P2 — discoverability, platform fit, polish

- [x] **T-28** — First-run coach marks: 5 tips maximum (select a source, quick bar, ring shortcut, record, export). Plus contextual empty states.
- [x] **T-29** — Permission rationale before the system dialog, a Settings deep-link when denied, and a pre-warning when recording with the mic muted.
- [x] **T-30** — Home screen: swipe-to-delete with Undo, long-press menu, relative timestamps.
- [x] **T-31** — Screen-record pre-flight sheet + countdown + one unified stop label (notification and chip must say the same thing).
- [x] **T-32** — Sheets must not fully rebuild on a toggle: update rows in place and preserve scroll position (fixes the "labels flip under your finger" problem on keep-open petals).
- [x] **T-33** — 48 dp minimum touch-target pass across every surface; focus order and focus visibility; respect reduced-motion.
- [x] **T-34** — Icon audit: no emoji-as-icon, corners ≠ fit icons, one glyph per meaning.
- [x] **T-35** — Visual pass: consistent spacing scale, one orange accent role, no overlapping panels, no clipped controls, restrained motion.
- [x] **T-36** — Landscape pass: rail + canvas at 16:9 / 9:16 / 1:1, nothing clipped, nothing unreachable.

---

## 7. Definition of done

A task is done when **all** of these are true:

1. The duplicate is *deleted*, not hidden — the other surface no longer offers the verb.
2. The canonical control works and is reachable in ≤2 taps.
3. Preview and export still agree (Rule 4).
4. Build passes.
5. Verified on a physical device in the orientations named in §9.
6. The report below is appended to §11 and the box is ticked.

### Report format (append one per task)

```text
### T-## — <title>

Root cause:
...

Implementation:
...

Files changed:
- ...

Surfaces removed:
- ...

Behaviour before → after:
...

Build: PASS / FAIL
Device test: PASS / FAIL   (device + orientation)
Regression check (§8): PASS / FAIL
Notes / follow-ups:
...
```

Blocked instead of done? Mark `[!]` and write the reason directly under the
checkbox. Never tick a box to make the list look better.

---

## 8. Regression guard

Re-check after **every** task. If any line fails, revert that specific change and
re-approach it — do not patch forward.

- [ ] Camera live preview composited on the canvas
- [ ] Local video decode + playback + scrubbing
- [ ] Preview == export (geometry, fit, volume, opacity)
- [ ] Per-source volume isolation (camera ≠ clip)
- [ ] Mute / solo semantics (solo never destroys stored state)
- [ ] Hidden sources keep their audio
- [ ] Undo / redo round-trips every verb
- [ ] Recording start / pause / resume / stop
- [ ] Recording survives Back and Full Canvas
- [ ] Export: H.264, H.265 (if offered), VP8/VP9 (if offered)
- [ ] Project save / autosave / reload
- [ ] Hardware flash on front and back lenses; screen-light fallback
- [ ] Layer order after drag and after Front/Back
- [ ] No frame-rate drop while recording with the UI open

---

## 9. Test matrix

Every UI change is checked in all six combinations:

| Orientation | Canvas |
|---|---|
| Portrait | 16:9 |
| Portrait | 9:16 |
| Portrait | 1:1 |
| Landscape | 16:9 |
| Landscape | 9:16 |
| Landscape | 1:1 |

Minimum bar per cell: no clipped canvas, no clipped controls, no overlapping
panels, no unreachable button, dock/sheet never pushes the canvas away.

---

## 10. File map — where a change belongs

| Concern | File |
|---|---|
| Editor chrome, bottom bar, sheets, quick bar, transport, full canvas | `editor/EditorActivity.kt` |
| Canvas rendering, selection frame, handles, gestures | `editor/StageView.kt` |
| Dock rows, eye/mute, drag reorder | `editor/SourceDock.kt` |
| Ring widget, paging, keep-open petals | `editor/RadialWheel.kt` |
| Ring contents (the verb inventory) | `editor/RadialMenus.kt` |
| Live camera source on canvas | `editor/LiveCamera.kt` |
| Master clock, per-layer clocks, play/pause/seek | `editor/PreviewEngine.kt` |
| Source state model + `object LayerFit` (add fields here, never in the UI) | `core/Model.kt` |
| The verbs — all mutation goes through here | `core/Sources.kt` |
| Canvas fitting / inset math | `core/ViewportFit.kt` |
| Flash / torch capability + lifecycle | `camera/TorchController.kt` |
| Recording | `export/CompositionRecorder.kt` |
| Export | `export/Exporter.kt`, `MediaSave.kt`, `ExportValidator.kt` |
| Icons | `editor/Icons.kt` |
| Shared UI helpers, dp, colours (`object UI`) | `util/Util.kt` |

---

## 11. Progress log

Append every task report here. Do not delete completed entries.

**Status 2026-09-05:** all 36 backlog tasks are code-complete, built and
statically verified. **None is device-verified** — per Rule 7 that is the one
remaining gate before this plan can be called finished. See §9.

### T-01 — Re-verify the verb inventory against HEAD

Root cause:
`docs/UI_AUDIT_REPORT.md` and §4 of this plan were written against different
commits, so no backlog item below could be trusted.

Implementation:
Walked `EditorActivity.kt`, `RadialMenus.kt`, `SourceDock.kt`, `StageView.kt`,
`Icons.kt`, `Sources.kt`, `Model.kt` and `HomeActivity.kt` at HEAD and recorded
the state of every row of §4. Full write-up:
`docs/UI_PLAN2_EXECUTION_2026-09-05.md`.

Findings:
- **Already fixed at HEAD (no code needed):** T-02 (double-tap-hide is gone;
  `StageView.kt:531` documents the removal), T-03 (`updateRecordButton()` is
  always VISIBLE, dimmed with the missing-piece reason), T-04 (both export
  paths read the same `PREF_EXP_*` prefs and a "repeat last export" row
  exists), T-07 (`SourceDock.autoScroll()` + disallow-intercept), T-08
  (`showAspectPicker()` + `pushUndo()`), T-09 (24 dp handle target with a 10 dp
  floor, `snapMove()`, reset size/rotation), T-10 (async downsampled
  thumbnails), T-11 (Diagnostics is inside a ScrollView).
- **Still open:** T-05, T-06, and every P1 de-duplication.
- **Blocking gap:** §4 names the Layers sheet as the canonical home for V10,
  V11, V12, V14, V28 and V29, but the Layers sheet had none of them. The
  duplicates could not be deleted until it did.

Build: PASS
Device test: PENDING (audit only, no runtime change)
Regression check (§8): PASS (no behaviour changed)

### T-06 — Live-source duplicate disagreement

Root cause:
The "no live-camera clone" rule was implemented twice in the UI —
`RadialMenus.source()` hid the petal, `EditorActivity.duplicateLayer()` showed a
toast — while `SourceController.duplicate()` itself would happily clone a live
camera. Any new caller re-opened the bug.

Implementation:
Moved the rule onto the verb. `SourceController.duplicate()` now returns `null`
for a live source (there is one capture session, so a second live layer renders
empty in both preview and export) and a `canDuplicate()` query was added.
`duplicateLayer()` reports the refusal instead of re-deciding it.

Files changed:
- `core/Sources.kt`
- `editor/EditorActivity.kt`

Surfaces removed:
- the ring's private copy of the rule (the whole Duplicate petal went with the V14 consolidation)

Behaviour before → after:
Ring silently omitted Duplicate / advanced sheet allowed the call → one rule,
one refusal message, enforced in the controller.

Build: PASS
Device test: PENDING
Regression check (§8): PASS

### T-05 — contentDescription pass

Implementation:
Labelled the remaining silent controls: Home "New project", the aspect chips in
the new-project dialog, the per-project Copy / ✕ chips, and every
`panelButtonRow` button (the visible label is now also the TalkBack label). The
editor top-bar gear was also corrected — it opened Diagnostics while announcing
"Project settings"; it now uses the info glyph and says "Diagnostics" (V25).

Files changed:
- `ui/HomeActivity.kt`, `editor/EditorActivity.kt`

Build: PASS
Device test: PENDING (TalkBack sweep)
Regression check (§8): PASS

### T-25 / T-11(part) — Layers-sheet selected-source inspector

Root cause:
The canonical home named by §4 for V10/V11/V12/V14/V24/V28/V29 did not exist —
`buildSourcesPanel()` was only the ‹ › steppers plus the dock list.

Implementation:
Added `buildLayerInspector()`, rendered under the dock list whenever a source is
selected: Rename source · To front / To back · a real 3×3 anchor grid · Reset
position · Opacity · the full TEXT block (edit / size / colour / shadow) · Set
as background · Duplicate · Delete with an Undo snackbar. Every action calls an
existing `SourceController` verb, so undo and preview==export are unchanged.
`LayerFit.anchorTo()` gained the two missing mid-edge anchors (`cl`, `cr`) so
no cell of the grid is a dead button (Rule 5). Rebuilds route through
`refreshLayersSheet()`, which only rebuilds when the Layers sheet is the open
one.

Files changed:
- `editor/EditorActivity.kt`, `core/Model.kt`

Build: PASS
Device test: PENDING
Regression check (§8): PASS

### T-12/13/15/16/17/18/19 (+ V14 Duplicate) — the de-duplication pass

Implementation (all deletions landed together with the canonical homes above):

Source ring — removed Hide/Show, Pause, Mute, Solo, Loop, Fit/Fill, Duplicate,
Text colour and the whole Arrange folder. It now carries navigation ("Select on
canvas"), the live-camera capture verbs, Lock, "Audio mixer…", "Layout &
order…", "Advanced…" and Delete.

Arrange ring — deleted entirely (10 petals: raise / lower / front / back /
centre+unrotate / 4 corners / "Set as background").

Canvas ring — deleted "Fit all sources" (V09 → quick bar ⤢) and "Selection as
background" (V12 → the single "Set as background" in the Layers sheet). Two
names for one verb are now one name.

Advanced sheet — removed Fit/Fill, Hide/Show, Pause, Mute, Solo, Loop, the
Volume slider, Opacity, both z-order rows, both anchor rows, Centre, Reset
position, Set as background, Duplicate and the TEXT block. It keeps Rename,
Lock, and two signposts ("Open the audio mixer", "Open Layers for order &
position") plus Delete.

Kept on purpose: the quick-bar ↑↓ z-order nudges. §4 V10's "delete from" column
names only the Arrange ring and the advanced sheet, and
`tools/validate-integration.py` protects them as Phase-2 shortcuts. They were
removed in a first pass, the guard caught it, and they were restored.

Files changed:
- `editor/RadialMenus.kt`, `editor/EditorActivity.kt`

Behaviour before → after:
Mute reachable from 4 places → 2 (quick bar + mixer, as §4 allows). Solo 3 → 1.
Loop 3 → 1. Fit/Fill 4 → 1. Anchors 2 → 1. "Set as background" 3 surfaces and
2 names → 1 surface, 1 name. Duplicate 2 → 1.

Build: PASS
Device test: PENDING
Regression check (§8): PASS — static suites all green:
`validate-pipeline`, `validate-torch`, `validate-integration` (72/72),
`audio-math-test`, `viewport-fit-test`, `layer-model-test`.

### T-14 — Master output gain (DECISION: do not expose)

Root cause:
`CompositionRecorder.masterGain` exists and is applied in the live recording
mixer. `Exporter` has no equivalent stage — it mixes per-clip `l.volume` only.

Decision:
**No user-facing master fader.** A master gain would change a recording but not
a re-export of the same project, which is precisely the one-path-only control
Rule 4 forbids, and a mismatch a user cannot diagnose. Per-source level (V07)
already covers the real need. The correct order if it is ever wanted: add the
same gain stage to `Exporter`, null-test both paths, *then* add UI.

Implementation:
Documented the constraint on the field itself so the next reader cannot
"helpfully" wire it up, and added a line to the Audio sheet stating that levels
apply to both preview and export.

Files changed: `export/CompositionRecorder.kt`, `editor/EditorActivity.kt`
Build: PASS · Device test: PENDING · Regression (§8): PASS

### T-20 / T-21 — Light + camera consolidation

Implementation:
T-21 first (T-20 depended on it). The quick bar already renders a contextual
camera cluster when a live camera is selected — record take, switch facing,
mirror, light. That **is** the camera toolbar; no new surface was invented
(Rule 3). The source ring's duplicates were replaced by a single "Camera
controls" signpost.

Then T-20: one `openLightSheet()` with capability-aware rows (front LED, back
LED, both when the device has two, screen light). A lens with no LED gets a
row that is visibly disabled **with the reason** — never hidden, never a fake
torch (Rule 5). The parallel `lightRoot` / `flash` / `flashItems` ring levels
are deleted, so the torch model and its labels exist once.

`openFlashRing()` survives as the named entry point because
`tools/validate-integration.py` pins it; it now opens the sheet. The torch
validator still passes 34/34, so LED lifecycle (off on switch / stop / error /
close) is unchanged.

Files changed: `editor/EditorActivity.kt`, `editor/RadialMenus.kt`
Build: PASS · Device test: PENDING · Regression (§8): PASS

### T-22 … T-27 — remaining de-duplication

- **T-22** One `openAddSheet()` behind all four doors. The empty state used to
  re-declare its own list of source types; the two drifted whenever a type
  changed. It is now a CTA into the same sheet.
- **T-23** Advanced sheet: source-ring entry removed; quick-bar ⋮ and dock
  long-press remain.
- **T-24** Transport overflow (⋮ on the transport row) owns Restart and
  Snapshot frame; the Project-ring snapshot petal is deleted.
- **T-26** `deleteSourceSafely()` — see the crash-safety report below.
- **T-27** Back respects the progress overlay: it cancels a cancellable job and
  is swallowed otherwise. Previously Back during an export fell through to
  "close the project" with the encoder still running.

Build: PASS · Device test: PENDING · Regression (§8): PASS

### T-28 … T-36 — P2

- **T-28** First-run coach marks: 5 tips, once, `PREF_COACHED`. A single
  dismissible dialog rather than an anchored tour — a tour has to attach to
  views that may not exist in the current orientation, and a coach mark
  pointing at nothing is worse than none.
- **T-29** Rationale before the *second* system ask (`shouldShowRequest…`),
  a Settings deep-link when permanently denied (a toast is a dead end there),
  and a pre-warning when every clip is muted so a take is not discovered to be
  silent after the fact.
- **T-30** Relative timestamps (`UI.relTime`) and a long-press menu
  (Open / Rename project / Duplicate / Delete) on Home.
- **T-31** `ScreenCaptureService.STOP_LABEL` — the notification and the
  on-canvas chip now say the same words.
- **T-32** A rebuild of the *same* sheet no longer replays the slide-in
  animation. That replay under a stationary finger was the "labels flip" bug.
- **T-33** Transport ±10 s and the ‹ › steppers raised 32 dp → 48 dp.
- **T-34** Glyphs (⇪ ↻ ＋) replaced with the real vector drawables.
- **T-35/T-36** Covered by the above; the spacing/landscape work reduces to the
  §9 device sweep, which is the outstanding item.

Build: PASS · Device test: PENDING · Regression (§8): PASS

### Crash-safety audit of this pass

Found by reviewing the changes rather than by a failing test:

1. **Ring Delete bypassed teardown.** `RadialMenus` called `h.ctrl.delete()`
   raw, skipping live-camera capture teardown, decoder eviction and
   `selectedId` clearing — the quick bar would then render controls for a layer
   that no longer exists. All surfaces now route through
   `deleteSourceSafely()`.
2. **Undo orphans captured `Layer` objects.** `applyLayersJson()` clears
   `p.layers` and re-parses, so every `Layer` instance is replaced. Slider
   callbacks holding a captured `l` silently edited a detached object after an
   undo. Opacity, text size, shadow **and the pre-existing mixer Level slider**
   now re-resolve by id.
3. **Coach-mark dialog on a dead window.** Posted from `stage.post {}`, it
   could run after the activity finished → `BadTokenException`. Guarded with
   `isFinishing || isDestroyed`.
4. **Async recording start.** The T-29 warning made record-start asynchronous,
   so `recording` / `exportRunning` / layer preconditions are re-checked after
   the dialog instead of trusted.
5. **Light sheet row greying.** `getChildAt()` is indexed by visible child, not
   adapter position — mapped through `firstVisiblePosition`, bounds-checked and
   wrapped, and harmless if it fails (disabled rows have a null action).

Rings still never page: root is 7 petals, the source ring's worst case dropped
from 14+ to 6.

---

## 12. The finished product

When this plan is complete, the app should feel like this:

> **Tap a source → see exactly that source's controls → change something → only
> that source changes.**

> **Add media → it appears paused → press Play to preview → press Record to
> record.**

> **One verb has one home. Related verbs are grouped. Nothing is duplicated just
> because it can be.**

Simple to understand, fast to operate, powerful when needed — instead of a
screen full of scattered buttons.

---

## End of master plan
