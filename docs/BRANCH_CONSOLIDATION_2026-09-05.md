# Branch consolidation — 2026-09-05

## Preservation rule

**Do not lose either branch's progress.** Preserve commit ancestry, keep the
Phase 2 pipeline **and** Step 5 UI, and rebuild the APK from the combined source.
A merge that simply chooses one complete `EditorActivity.kt` is not sufficient.
Future changes should start from the consolidated `main`, not restore a stale
pre-integration editor or APK.

## Branch inventory

The initial checkout was clean. The remote had three branches and one open PR:

| Branch / PR | Original tip | Treatment |
|---|---|---|
| `main` (including merged PR #21, Step 5) | `f8938de699f7461bc99267bb15900d3905056366` | Starting baseline; retain all existing history and UI work. |
| `arena/01a06d7e-kotlin-reaction-maker` | `86266034b7ab2050fedf3646a1bce1758a71ec4c` | Already an ancestor of `main`; no unique commits to merge. |
| `arena/01a07057-kotlin-reaction-maker` / PR #20 | `318e8f4fab69bf5a8cb3977fc8ef7353c464180f` | Initially conflicted with Step 5 in the editor and APK; integrated on the session branch. |
| Same PR #20, updated while this consolidation was running | `d5f34f08e29d983f468236fc921ec5e8aaa37c0c` | Fetched and merged as well. Its five 44dp top-bar targets, SourceDock sizing and report update are retained. |

Integration branch: `arena/01a0711c-kotlin-reaction-maker`. Both integrations
use merge commits, not squash/rebase. The PR into `main` must also use a merge
commit so the original PR's commits remain in the final ancestry. No force
pushes or manual deletion of the original branches are needed. GitHub's
repository-wide automatic deletion of merged PR branches is a separate setting;
deleting a merged branch label does not remove commits from `main`.

## Conflict decisions: keep features from both sides

### Phase 2 preserved

- `AudioMath.kt`, the master-clock `CompositionRecorder`, `Exporter`,
  `PreviewEngine`, `StageView`, `Model.kt`, and radial menu additions are retained
  from PR #20. The non-UI pipeline/selection changes were not rewritten during
  the conflict resolution.
- Independent microphone capture and sample-count audio encoding, shared clip
  cursor/resampler/limiter, playback monitoring exclusion during recording,
  camera-take/composite mutual exclusion, and surfaced recording failures.
- Four-side canvas fit with system/cutout insets, Full Canvas mode and an
  explicit exit, landscape rail, deterministic PiP placement, rotated hit tests,
  source-owned visible-picture selection frames, and image preview rendering.
- Off-main-thread media copy/probe/image-bounds import and surfaced add errors.
- Collapsible Camera/Video/Image/Text/Screen shortcuts, global contextual
  controls, and selected-source move/resize/rotate/stacking actions.

### Main / Step 5 preserved

- Five editor tabs: Layers, Add, Audio, Text, Export, including selected-tab
  feedback. The Audio tab now consistently uses the panel's `mixer` id.
- Per-layer horizontal chips, Z-order display, selection, hidden/locked badges,
  and long-press advanced settings.
- Floating selected-source pill, name/icon, hide/mute/play/camera/lock/fit/
  Studio/More actions, top-bar typography and targets, section styling,
  transport controls, state-aware Record button and compact Studio launcher.
- `SourceDock.kt` is unchanged from Step 5: 52dp rows and 44dp controls.
- Main's aspect-independent phone orientation and layout-listener cleanup.
- Earlier torch, audio decoder format/native-byte-order fixes, export
  validation, strided YUV, monotonic output PTS and live-camera frame retention.

### Shared layout, not duplicate toolbars

The Phase 2 source actions and Step 5 pill are one contextual toolbar. Selected
source targets are 48dp (at least Step 5's 44dp). The toolbar is constrained to
the available width and has an explicit horizontal scroll indicator, so adding
Phase 2 actions does not put controls permanently off-screen.

Portrait retains the Step 5 sheet and floating pill. The pill follows the
**measured** sheet height rather than a fixed 122dp offset. The pure
`ViewportFit.panelHeight` helper retains the 38% panel cap and 28% canvas-space
reserve, including the floating controls in the budget. Top chrome is measured
without adding the system inset twice; the empty state shares the safe region.

Landscape re-parents the same tabs, source chips, toolbar, panel and Record/
Studio controls into a **scrollable** right rail; transport stays full-width
at the bottom. The rail scrolls on short displays instead of clipping controls.
Opening a panel uses the existing `panelScroll` reference, not a lookup that
fails after the panel moves out of the sheet. Re-parenting does not restart the
camera, preview decoders or recorder.

Full Canvas hides chrome, HUD, recording/hidden-source badges and the empty
state; UI ticks and camera callbacks respect that visibility. The exit button
also reserves space outside the composition. Back or the exit restores controls.

The earlier reports in `PHASE2_CANVAS_SELECTION_AUDIO.md` and `STEP5_REPORT.md`
remain as historical records. Where they describe incompatible UI structures,
this consolidation report describes the current combination. In particular,
Step 5 tabs/pill are **not** discarded, and canvas aspect does **not** force the
phone orientation.

## Validation

Local environment: Linux, JDK 17.0.9, Kotlin compiler 1.9.24, compile API 34,
min SDK 26 / target SDK 30. No Android device or emulator is available.

| Check | Result |
|---|---|
| `./build-apk.sh` | BUILD OK; APK rebuilt from combined source, not chosen from either branch. |
| APK signature, manifest and CI dex assertions | Pass; original, Phase 2, Step 5 and torch feature symbols present. |
| `python3 tools/validate-pipeline.py` | 81 checks passed. |
| `python3 tools/validate-torch.py` | 34 checks passed. |
| `python3 tools/validate-integration.py` | 72 static integration checks passed. |
| `bash tools/audio-math-test/run.sh` | 32 passed, 0 failed. |
| `bash tools/viewport-fit-test/run.sh` | 420 passed, 0 failed (199 original + 221 panel-budget regressions). |
| `bash tools/layer-model-test/run.sh` | 28 passed, 0 failed. |
| `bash tools/step2-geom-check.sh` | All green. |
| Conflict-marker scan and `git diff --check` | Pass. |

CI now runs the integration guards, all four JVM/geometry suites and expanded
APK feature-symbol assertions. The three imported JVM runners no longer mask
compiler failures with `|| true`.

The latest PR #20 update changed no production source beyond the five top-bar
sizes already in the validated integration. Its history and documentation
change were incorporated without replacing the validated combined APK.

### Still requires a phone

Static guards and JVM math tests cannot verify actual Android views or media
hardware. Before treating this as device-verified, test:

1. All three canvas aspects in portrait/landscape, including a short screen,
   system bars/cutout, an open panel, and the scrollable rail/toolbar.
2. Layer-chip selection, hide/lock, z-order, transforms, undo/redo, and image
   preview; confirm the selected frame follows the visible source.
3. Full Canvas and Back/exit during playback/recording; ensure the HUD and
   asynchronous recording badges do not cover it.
4. A 60-second camera + video recording: mic continues after clip EOF, saved
   audio/video stay synchronized, and errors are visible.
5. Front/back torch, camera-take/composite exclusion, import, and exported-file
   playback on API 26–29 and API 30+ where possible.

## Recovery and final ancestry verification

Verified complete Git bundles were saved outside the repository so they do not
inflate Git history:

- `/home/user/merge-backups/kotlin-reaction-maker-20260905/before-consolidation.bundle`
- `/home/user/merge-backups/kotlin-reaction-maker-20260905/updated-remote-work.bundle`

The same directory contains the original refs, PR metadata, integration source
snapshot and validation logs. The bundles are workspace recovery copies;
original commits are also preserved through the integration's merge parents.
No project/user-data migration or deletion is part of this change.

After the integration PR lands, fetch `main` and verify every captured original
tip (not merely branch names, which GitHub can automatically delete):

```sh
git fetch origin main
for tip in \
  f8938de699f7461bc99267bb15900d3905056366 \
  86266034b7ab2050fedf3646a1bce1758a71ec4c \
  318e8f4fab69bf5a8cb3977fc8ef7353c464180f \
  d5f34f08e29d983f468236fc921ec5e8aaa37c0c
do
  git merge-base --is-ancestor "$tip" origin/main || exit 1
done
```

A successful check proves no captured branch commits were omitted. Functional
preservation is additionally guarded by the tests above; it is not a substitute
for the Android smoke tests.
