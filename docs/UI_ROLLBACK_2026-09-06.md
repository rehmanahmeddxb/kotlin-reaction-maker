# Editor UI rollback to the PR #24 baseline — 2026-09-06

## What the user reported

> After PR #24 the app was ~90 % OK. The PRs that landed after it (#25, #26)
> spoiled everything: multiple duplicates, bad overlays, cropped views of
> controls/buttons, too much noise, lost control almost.

## What I found (examination, not guesswork)

The **canvas-fit / layout engine was NOT broken** by the later PRs. These core
functions are byte-for-byte identical between the PR #24 merge (`85fb017`) and
`main` at `fb20cf9`:

- `buildUi`, `buildSheet`, `relayoutChrome`, `applyViewportInsets`,
  `capPanelHeight`, `readSystemInsets` (`EditorActivity.kt`)
- `RadialWheel.kt`, `SourceDock.kt`, `StageView.kt`, `PreviewEngine.kt`

So the composition never stopped being contain-fitted into the safe chrome.

What PR #26 actually changed was the **distribution of source controls** across
surfaces. That is what produced each reported symptom:

| Symptom | Root cause in the code |
|---|---|
| "lost control almost" | The per-source **radial ring** was emptied. PR #24 exposed one-tap *Hide / Pause / Mute / Solo / Loop / Fit / Arrange / flash* petals. PR #26 deleted them all and left only *Select on canvas, Lock, Layout & order…, Delete*. |
| "buried / too much noise" | The same verbs were re-homed behind nested sheets and a brand-new **Layers-sheet inspector** (3×3 anchor grid, opacity, text block, …). A one-tap action became a 2–3 level drill. |
| "multiple duplicates" | Because verbs were *moved* rather than unified, several now render on **both** a floating quick bar **and** a sheet/dock at the same moment. |
| "cropped / overlays / buttons cut off" | The added inspector grids + empty advanced sheet pushed more rows/buttons into the shared bottom sheet than fit comfortably, and the gutted "⋮ All settings" sheet stopped being a real control surface. |

## What I did

Restored the two files that carried the regression — and **only** those two —
to their exact PR #24 content:

- `app/.../editor/EditorActivity.kt`
- `app/.../editor/RadialMenus.kt`

This brings back: the full per-source radial ring, the rich "All settings"
advanced sheet, and removes the dense new Layers inspector, without touching
any other file.

Kept (not reverted) because they do not create the reported editor issues:
- `HomeActivity.kt` — long-press project menu, accessibility labels, relative
  timestamps (home screen only).
- `core/Model.kt`, `core/Sources.kt`, `capture/ScreenCaptureService.kt`,
  `util/Util.kt`, `export/CompositionRecorder.kt` — additive robustness /
  small helpers that do not change the editor layout.

## Validation (no Android device here — this is what CAN be verified)

| Check | Result |
|---|---|
| `./build-apk.sh` (fresh signed APK from the restored source) | **BUILD OK** |
| `python3 tools/validate-pipeline.py` | pass |
| `python3 tools/validate-torch.py` | 34 checks pass |
| `python3 tools/validate-integration.py` | 72 checks pass |
| `tools/viewport-fit-test/run.sh` | 420 passed, 0 failed |
| `tools/layer-model-test/run.sh` | 28 passed, 0 failed |
| `tools/audio-math-test/run.sh` | 32 passed, 0 failed |
| `tools/step2-geom-check.sh` | all green |

The rebuilt `artifacts/AhmedReactionStudio-1.0.0.apk` is committed alongside.

## Still requires a phone

Static checks cannot see actual Android rendering. Please eyeball on a device
(portrait + landscape, a source selected and unselected, Layers / Audio /
Export panels open) to confirm the overlap / cropping is gone.

## Optional follow-up (not done, to avoid re-introducing churn)

PR #26 also contained a few genuinely-good, non-visual improvements that were
wired into the gutted architecture and therefore came back out with this
rollback. They can be re-layered cleanly on top of the restored PR #24 editor
if you want them:

1. `requestWithRationale` / `offerAppSettings` — explain-before-asking
   permission flow (microphone / camera), instead of a bare re-prompt.
2. `deleteSourceSafely` — route *every* delete (ring petal, sheets, dock)
   through one path that tears down a live camera session and evicts its
   decoder, rather than calling `SourceController.delete` raw.

If you'd like either re-added on the PR #24 UI, say so and I'll do it as a
second, smaller change.
