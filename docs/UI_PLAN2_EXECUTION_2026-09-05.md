# UI Plan2 — audit (T-01) and safest implementation plan

**Date:** 2026-09-05
**Source of truth:** `UI Plan2.md` (rewritten 2026-09-05, commit `0f56765`)
**Baseline build:** `bash build-apk.sh` → **BUILD OK** (48 s, 1.17 MB APK) before any change.

---

## 1. What changed in `UI Plan2.md`

`UI Plan2.md` has exactly one commit in history: `0f56765 Rewrite UI Plan2.md as a
current, grounded master plan (#25)`. It **replaced** the old 48-section
checklist with:

* §1 an inventory of what is already shipped (do not rebuild),
* §2 eight non-negotiable rules (one verb one home, don't touch media pipelines,
  reuse never fork, preview == export, no dead buttons, one problem at a time,
  device-test before ticking, the canvas is the product),
* §4 a **verb → canonical home ruling** (V01–V32) plus a naming dictionary,
* §6 a backlog: **P0** T-01…T-11, **P1** T-12…T-27, **P2** T-28…T-36,
* §7–§9 definition of done, regression guard, 6-cell test matrix.

Nothing in the backlog was ticked; §11 progress log is empty.

---

## 2. T-01 — verb inventory re-verified against HEAD

Walked `EditorActivity.kt`, `RadialMenus.kt`, `SourceDock.kt`, `StageView.kt`,
`Icons.kt`, `Sources.kt`.

### 2.1 P0 items already satisfied at HEAD

| Task | State | Evidence |
|---|---|---|
| T-02 double-tap-to-hide | **already fixed** | `StageView.kt:531` comment "double-tap-hide was removed"; `onDoubleTap` only edits TEXT layers |
| T-03 RECORD always visible | **already fixed** | `updateRecordButton()` always sets `View.VISIBLE`, dims to 0.65 α and states the missing piece ("ADD CAMERA TO RECORD"); `recordButtonTap()` opens the fixing dialog |
| T-04 export settings persisted + quick export reuse | **already fixed** | both `buildExportPanel()` and `quickExport()` read `editorPrefs()` `PREF_EXP_*`; a "Export again with last settings" row exists |
| T-07 dock drag + auto-scroll | **already fixed** | `SourceDock.autoScroll()` + `requestDisallowInterceptTouchEvent` |
| T-08 aspect picker, not a blind cycle | **already fixed** | `showAspectPicker()` dialog + `changeAspect()` calls `pushUndo()` |
| T-09 handles ≥24 dp, snap, haptics, reset | **already fixed** | `hitHandle()` 24 dp target with 10 dp floor; `snapMove()`; reset size/rotation buttons on the quick bar |
| T-10 async thumbnails | **already fixed** | `HomeActivity` decodes on a `Thread` with downsampling + tag guard |
| T-11 Diagnostics scrollable | **already fixed** | `DiagnosticsActivity` wraps in a `ScrollView` |

### 2.2 P0 items still open

| Task | Finding |
|---|---|
| **T-05** a11y | Top-bar `IconBtn`s and quick-bar buttons are labelled, but `HomeActivity` new-project/menu chips, `SplashActivity` and several `UI.chip`/`UI.btn` sites are not. Low-risk, additive. |
| **T-06** live duplicate | The rule is enforced **in the UI twice**: `RadialMenus.source()` hides the petal for live layers, `EditorActivity.duplicateLayer()` toasts a refusal. `SourceController.duplicate()` itself will happily clone a live camera, so any future caller re-opens the bug. Rule belongs in the controller. |

### 2.3 P1 duplicates confirmed still present

| Verb | Duplicate surfaces found at HEAD |
|---|---|
| V01 Mute | quick bar, mixer sheet, **source ring**, **advanced sheet** |
| V02 Solo | mixer sheet, **source ring**, **advanced sheet** |
| V32 Loop | mixer sheet, **source ring**, **advanced sheet** |
| V07 Volume | mixer sheet slider, **advanced sheet slider** |
| V03 Hide | quick bar, dock eye, **source ring**, **advanced sheet** |
| V04 Pause source | quick bar, dock status line, **source ring**, **advanced sheet** |
| V09 Fit/Fill | quick bar, **source ring**, **advanced sheet**, **Canvas ring "Fit all sources"** |
| V10 Z-order | quick bar ↑↓, dock drag, **Arrange ring** ×4, **advanced sheet** ×4 |
| V11 Anchors | **Arrange ring** 4 corners, **advanced sheet** 3×3 |
| V12 Set as background | **Arrange ring "Set as background"**, **Canvas ring "Selection as background"**, **advanced sheet** |
| V14 Duplicate | **source ring**, **advanced sheet** |

**Gap that blocks naive deletion:** the plan's canonical home for V10/V11/V12/V14/V29
is the **Layers sheet**, and the Layers sheet (`buildSourcesPanel()`) currently
contains *only* the ‹ › steppers and the dock list. Deleting the duplicates
first would make those verbs unreachable. The Layers sheet must gain a selected-source
inspector **before** anything is removed.

---

## 3. Safest implementation plan

Ordering principle: **add the canonical home first, delete the duplicate second,
build between every step.** Never delete a verb in a commit that does not already
contain its replacement.

| Step | Change | Risk | Why it is safe |
|---|---|---|---|
| **S1** | T-06: move the "no live-camera clone" rule into `SourceController.duplicate()` (return `null`); UI surfaces read the refusal instead of each re-implementing it | very low | one added guard, no call-site behaviour change; the ring petal and the sheet already refused |
| **S2** | T-05: `contentDescription` on the remaining unlabelled icon/chip controls | very low | additive, no logic |
| **S3** | **Add** a "Selected source" inspector to the Layers sheet: Front/Back, 3×3 anchor grid, Centre, Reset position, Set as background, Duplicate, Opacity, and the TEXT controls (T-25, T-29's sibling placement) | low | pure UI addition; every action already exists on `SourceController`, nothing new touches the pipeline |
| **S4** | T-12/13/15/16/32: delete Mute, Solo, Loop, Hide, Pause, Fit/Fill and the Volume slider from the **source ring** and the **advanced sheet** | medium | canonical homes (quick bar + mixer sheet) verified present in S0 audit |
| **S5** | T-17/18/19: delete the **Arrange ring** entirely (its 10 petals are all now in the Layers sheet), delete z-order/anchor/background rows from the advanced sheet, delete "Selection as background" and "Fit all sources" from the **Canvas ring** | medium | S3 added every one of these to the Layers sheet |
| **S6** | T-14: Duplicate lives in the Layers sheet only — remove from the source ring and advanced sheet | low | S3 added it |
| **Deferred** | T-20/T-21 (Light + camera-toolbar consolidation), T-23, T-26, T-27, all of P2 | — | T-21 requires a camera toolbar that does not exist yet; removing the ring/quick-bar camera verbs first would strand *switch camera* and *record take*. Marked `[!]` rather than half-done. |

**Not touched (Rule 2):** `MediaCodec`, `MediaExtractor`, `MediaMuxer`, Camera2,
`AudioMixer`, `Compositor`, `PreviewEngine` clock, `Exporter`.

**Verification per step:** `python3 tools/validate-pipeline.py`,
`validate-torch.py`, `validate-integration.py`, the three JVM regression suites,
then `bash build-apk.sh`. Device testing (§9 matrix) remains the user's step —
boxes stay unticked in `UI Plan2.md` until then, per Rule 7.

---

## 4. Result of this pass

See §11 of `UI Plan2.md` for the per-task reports appended by this work.
