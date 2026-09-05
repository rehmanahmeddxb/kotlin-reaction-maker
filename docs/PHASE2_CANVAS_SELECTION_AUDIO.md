# Phase 2 — Canvas fit, selection frames, audio pipeline

Implementation report for the three Phase 2 areas. Everything below was
implemented, compiled into `artifacts/AhmedReactionStudio-1.0.0.apk` with the
offline toolchain (`TC_ROOT=/tmp/ahmed-tc ./build-apk.sh` → `BUILD OK`) and
exercised by the tests listed in §5. **No Android device or emulator exists in
the build sandbox**, so §6 states exactly what could not be verified on
hardware. Nothing from `docs/P0_PIPELINE_FIX.md` was undone (MonotonicPts,
strided YuvWriter, ExportValidator, fail-loud muxer, live-frame triple
buffering, ticker restore are all still in place and still asserted by
`tools/validate-pipeline.py` and the CI dex-symbol check).

---

## 1. Root causes

### 1.1 Canvas cropping / push-down

| Symptom | Root cause (as found in the code) |
|---|---|
| Composition cropped on the left/right or top/bottom | `StageView` was `MATCH_PARENT` under **floating overlays** (56 dp top bar, bottom sheet, floating quick bar at 118 dp, snack at 208 dp). It fitted the aspect into the *whole* view, so whatever the chrome covered was cut off. There was no `WindowInsets` handling at all → status bar / navigation bar / camera cutout also covered the canvas. |
| Canvas pushed down / cropped when a source is added or a panel opens | Opening Layers / Mixer / Export made the bottom sheet up to 40 % of the screen tall. The stage did not know, so the sheet simply covered the lower part of the composition. Adding a source auto-opens feedback UI → same effect. |
| Landscape unusable | A 4-row bottom sheet (dock + controls + transport + launcher) under a 56 dp top bar leaves ~47 dp of height for a 16:9 canvas on a 1080×2400 phone — confirmed by the JVM fit test before the landscape rail was added. |
| Old scaling approach | `layoutCanvas()` used `min(view.w/canvasW, view.h/canvasH)` but against the *view*, not against the *free viewport*. Fixing this by changing source FIT/FILL would have been wrong — the sources were fine; the viewport was. |

### 1.2 Selection borders

* One accent already existed (`UI.ACCENT = rgb(255,90,44)`) but the frame was drawn thin and without contrast, so it disappeared on bright content; hidden layers still drew their frame; locked was not visually distinct.
* All new PiPs were anchored to the same bottom-right corner (`placePip` always `"br"`, and the live camera re-pipped itself to `"br"` on first frame, throwing away any user placement) → new sources stacked exactly on top of each other.
* `SourceController.moveZ("up"/"down")` existed but no UI exposed it — only To-front/To-back.
* Image layers were never decoded for the preview (`bitmapOf()` returned `engine.frameOf()` which only holds clip frames) → an added image was an invisible, selectable box until export.

### 1.3 Audio — stutter, "sped up", stops after a few seconds

Traced through `CompositionRecorder` (live recording), `Exporter` (offline
export), `PreviewEngine` (monitoring) and `LiveCamera`:

| Symptom | Root cause |
|---|---|
| **Stutter / unclear** | One `runLoop()` thread interleaved a full video frame (720p YUV conversion + encoder submit with 20 ms `dequeueInputBuffer` waits) with a *single* 1024-sample audio chunk per iteration. The mic was consumed slower than real time → `AudioRecord` ring-buffer overruns → dropped PCM. `Thread.sleep` on `read <= 0` made it worse. |
| **Sped up** | Audio PTS was derived from a sample counter that advanced **only when an AAC input buffer was obtained**; when the codec had no free buffer the chunk was *discarded* but the wall clock kept running → the AAC track contained fewer samples than real time. Players then play the shorter track over the video's duration: pitch/pace up, then silence. Meanwhile video PTS came from the wall clock — two clocks, one timeline. |
| **Camera audio stops after several seconds** | `LiveCamera`'s own `MediaRecorder` (camera take) opened `MIC` while the composite recorder also owned `AudioRecord` → the second client is silently starved on most OEMs. Also: while `finishing`, audio was no longer encoded but video still was; EOS PTS was `last + 23000` instead of the real end. |
| **Local-video audio stops** | Clip mixing used `durSamples` from `durMs` (container duration) against the decoded PCM length, ignored pause/speed, and clip EOF ended the mixer for *all* sources (incl. the mic). Speaker monitoring (MediaPlayer) kept playing during recording → mic re-captured it (echo). |
| Errors invisible | `ExportValidator.validate(expectAudio = false)` accepted a silent file; `AudioRecord`/AAC init failures fell back to video-only with no message. |
| Offline export gaps | `Exporter.encodeAudioChunk` returned nothing; on `dequeueInputBuffer == -1` the chunk was dropped **but the sample counter still advanced** → periodic gaps. Hard clipping on sum. |

---

## 2. Architecture changes

### 2.1 Viewport fit (canvas always visible)

* **`core/ViewportFit.kt` (new, Android-free)** — the single fit rule:
  `avail = view − insets − 2·pad; scale = min(avail.w/canvasW, avail.h/canvasH)`,
  centred, with a degenerate-area fallback so the picture shrinks but never
  collapses to zero.
* **`StageView`** — `setViewportInsets(l,t,r,b)` / `viewportInsets()` /
  `canvasBounds()` / `onCanvasLayout`. `layoutCanvas()` now calls
  `ViewportFit.contain(...)`; the canvas is re-fitted whenever insets change.
* **`EditorActivity`** — the *only* place that knows the chrome:
  * `root.setOnApplyWindowInsetsListener` reads system bars **+ display cutout**
    (`WindowInsets.Type.systemBars() | displayCutout()` on API 30+, legacy
    fields + `displayCutout` on 26–29).
  * `applyViewportInsets()` runs after every layout pass (global-layout
    listener) and measures the *actual* height of the top bar and bottom sheet
    (dock + contextual bar + transport + launcher + **open panel**) → stage
    insets. So opening a panel, expanding the dock, selecting a source or
    rotating **shrinks the canvas instead of covering it** (acceptance: never
    pushed or cropped).
  * **Orientation-aware chrome** (`relayoutChrome`): portrait keeps everything
    in the bottom sheet; landscape re-parents dock + controls + panel into a
    right rail (40 % width, 220–340 dp) and leaves a single bottom row
    (transport | launcher). Pure view re-parenting — nothing is rebuilt, the
    camera/decoders/clock are untouched (activity already handles
    `configChanges`).
  * Top overlays (rec chip, HUD, hidden pill) and the top bar/sheet padding
    are pushed clear of the cutout/system bars.
  * `stage.onCanvasLayout → syncPreviewTarget()` so decode size follows the
    fitted canvas.
* **Collapsible source dock** (`rebuildSourceDock`) — horizontal, collapsed
  `[Camera][Video][+ Add]`; expanded adds `Image · Text · Screen | Layers ·
  Audio · Export`. Collapsing frees the row and the canvas reclaims it.
* **Contextual controls** (`refreshContextBar`) —
  nothing selected → `Add source · Camera · Record/Stop · Mic · Torch · Full canvas`;
  source selected → name pill (what is selected, in words) + `Move · Resize ·
  Rotate · Fit · Fill · Mute · Pause/Play · Hide · Lock · Forward · Backward ·
  Wheel · More`; live camera additionally `Take · Switch · Mirror · Light`.
  Every button is a 48 dp target with a `contentDescription`.
* **Full Canvas mode** (`setFullCanvas`) — top bar, sheet, rail and quick bar
  hidden, immersive system bars (API 30 `WindowInsetsController`, legacy flags
  below), the composition fitted into the whole safe area, one clearly
  labelled **✕ EXIT FULL CANVAS** button (48 dp, cutout-aware). Entry points:
  top-bar button, contextual bar, Canvas ring; exit: the button or Back.
* The old floating quick bar is disabled (`USE_QUICK_BAR = false`) — it
  overlapped the canvas by design and duplicated every verb.
* All panels now have a title + ✕ header (dismissible overlays), and the
  advanced sheet triggers a re-fit when it opens.

### 2.2 Selection frames & layer behaviour

* **`StageView.drawChrome`** — one accent for every type: 1 dp dark contrast
  underlay + crisp 2.5 dp accent stroke drawn in the layer's rotated frame
  (follows position/size/scale/rotation exactly), 4 corner + 4 edge handles,
  rotation knob above the top edge, name/type pill. Unselected: subtle neutral
  hairline. Locked: dashed neutral frame, no handles (distinct state). Hidden:
  no frame at all. Selection is never colour-only (handles + pill + contextual
  bar name pill).
* **`LayerFit.hitTest`** (new, pure) — topmost-first (list order = draw
  order), rotation-aware, skips hidden/transparent; `StageView.layerAt` now
  delegates to it.
* **`LayerFit.placeNewPip`** (new, pure) — deterministic placement for new
  sources: `br → bl → tr → tl`, first free corner; all taken → 6 % cascade
  from the last PiP. Full-bleed backgrounds and hidden layers do not reserve
  corners. `placePip` uses it; the live camera's first-frame aspect adoption
  now **keeps the user's position/rotation** instead of snapping to a corner.
* **Bring forward / Send backward** exposed in the contextual bar, the Arrange
  ring (`RadialMenus.arrange`) and the advanced sheet (`moveZ("up"/"down")`).
* **Image layers in the preview** — `PreviewEngine.frameOf()` lazily decodes
  stills (`requestImage`) on the pool, publishes on main, sized to the layer's
  on-screen box; evict/recycle bookkeeping added. An added image is now
  visible immediately, like every other layer.

### 2.3 Audio pipeline

* **`export/AudioMath.kt` (new, Android-free)** — `AudioMath` (44.1 kHz mono
  16-bit, 1024-sample AAC frames, bytes⇄samples⇄µs), `Resampler` (48 k → 44.1 k
  linear, phase-continuous across chunks), `ClipCursor` (composition-time →
  media-time with loop/pause/speed, `seekComposition`, `resyncIfDrifted`,
  never reads past `durMs`), `Limiter` (soft look-ahead-free peak limiter,
  transparent below the ceiling).
* **`CompositionRecorder` (rewritten)** — one master audio clock:
  * `MicThread` (URGENT_AUDIO priority): blocking 20 ms `AudioRecord` reads,
    44.1 k mono or 48 k + `Resampler`, hands buffers to a bounded queue
    (overflow counted and substituted with silence — never a time jump),
    `AudioRecord.getTimestamp` anchors `audioZeroNs`.
  * `AudioThread`: fixed 1024-sample frames; mixes mic + every playing clip
    (`ClipCursor`, per-source volume, solo-aware mute, pause/speed honoured,
    120 ms drift guard against the preview clock) through the `Limiter`;
    **PTS = samplesEncoded × 1e6 / 44100** via `MonotonicPts`. No
    `Thread.sleep` for sync; blocking queue + codec waits only. Clip EOF only
    silences that clip — the mic continues. If the mic dies, paced silence
    keeps the timeline continuous.
  * Video PTS = `frame.stampNs − audioZero` → **both tracks on one timeline**.
  * Muxer: `muxLock`, pending-sample buffer until both tracks are configured
    (drops are counted, never silent).
  * Shutdown: stop mic → drain audio queue → audio EOS (with the continuing
    clock) → drain AAC → video EOS → drain AVC → `muxer.stop` →
    `ExportValidator.validate(expectAudio = hadAudioTrack)` + ">500 ms
    shorter" warning.
  * Failures are **surfaced**: `Result.message`, `onError` on the main thread,
    `stats()` in the HUD (queue depth, overruns, dropped, resamples).
  * The camera take (`LiveCamera` MediaRecorder) and composite recording are
    now mutually exclusive — no second MIC client.
  * Speaker monitoring is muted while recording (`PreviewEngine.monitorMuted`)
    → no echo re-capture; preview and recorder never share a decoder.
* **`Exporter`** — same `ClipCursor`/`Limiter`; `encodeAudioChunk` returns
  `Boolean` and the sample counter advances **only on success** (no gaps);
  pause/speed honoured; `validate(expectAudio = audioEnabled)`.

---

## 3. Files changed and why

| File | Why |
|---|---|
| `core/ViewportFit.kt` **(new)** | Pure fit rule (testable on the JVM). |
| `core/Model.kt` | `LayerFit.placeNewPip` (stagger), `LayerFit.hitTest` (topmost-first, rotation-aware). |
| `editor/StageView.kt` | Inset API, contain-fit via `ViewportFit`, professional selection chrome (accent + handles, locked/hidden states), hit-test delegation. |
| `editor/EditorActivity.kt` | WindowInsets + measured-chrome → stage insets; orientation-aware chrome (rail); collapsible source dock; contextual bottom controls; Full Canvas mode; panel headers; up/down in advanced sheet; staggered placement; camera keeps user placement; recorder wiring (start/stop, mutual exclusion with camera take, error surfacing, HUD stats). |
| `editor/RadialMenus.kt` | `enterFullCanvas` host verb + Canvas-ring petal; Bring forward / Send backward in the Arrange ring. |
| `editor/PreviewEngine.kt` | Lazy still-image decode for IMAGE layers; `monitorMuted` (no echo during recording). |
| `export/AudioMath.kt` **(new)** | Shared, testable audio primitives (`Resampler`, `ClipCursor`, `Limiter`, conversions). |
| `export/CompositionRecorder.kt` | Full rewrite of the live recorder around one master audio clock (see §2.3). |
| `export/Exporter.kt` | Shared `ClipCursor`/`Limiter`; no counter advance on dropped chunk; pause/speed; expect-audio validation. |
| `res/drawable/ic_fullscreen.xml` **(new)** | Full Canvas icon. |
| `tools/audio-math-test/` **(new)** | JVM harness for AudioMath (32 checks). |
| `tools/viewport-fit-test/` **(new)** | JVM harness for the fit rule (199 checks). |
| `tools/layer-model-test/` **(new)** | JVM harness for placement / hit-test / z-order (28 checks). |
| `artifacts/AhmedReactionStudio-1.0.0.apk` | Rebuilt. |

Performance: `consumeMediaFile` (every add-source path: picker, camera take,
screen record, snapshot) now runs the `MediaMetadataRetriever` probe, the copy
into the project folder and the image-bounds read on a worker thread; only the
layer-list mutation and the UI refresh run on the main thread. Previously the
add path blocked the UI for the copy + probe + a **full** image decode. Errors
on that path are shown in the snackbar instead of being swallowed.

Unchanged on purpose: `LiveCamera`, `GpuVideoDecoder`, `YuvWriter`,
`ExportValidator`, `Compositor` FIT/FILL semantics, `SourceDock` (vertical
layer list inside the Layers panel).

---

## 4. Behavioural contract (what a tester should see)

1. Open any project in portrait: the whole 16:9 / 9:16 / 1:1 canvas is
   visible between the top bar and the dock, centred, with a 6 dp margin.
   Rotate: same, with the controls in a right rail. Notch/status/nav bars never
   cover it.
2. Tap `+ Add` in the dock: the dock grows by one row → the canvas shrinks
   slightly, nothing is cropped. Tap `Less`: it grows back.
3. Open Layers / Audio / Export / a source's More sheet: the canvas shrinks
   above the panel; ✕ or Back closes it and the canvas grows back.
4. Full Canvas button (top bar / contextual bar / Canvas ring): only the
   composition and one ✕ EXIT FULL CANVAS button remain; Back also exits.
5. Add camera, then video, then image: they land in three different corners,
   each selected on creation with the orange frame + handles + name pill, and
   the contextual bar switches to source verbs. Tap overlapping sources: the
   topmost one is selected. Forward/Backward change the draw order and the tap
   result deterministically.
6. Lock a source: dashed neutral frame, gestures explain + offer unlock. Hide:
   no frame, not tappable, still in the Layers panel.
7. Record with camera + video for 60 s: audio stays in sync, no pitch change,
   mic continues after the clip ends; the HUD (Diagnostics) shows queue/overrun
   counters; any audio component failure shows a message instead of a silent
   video-only file.

---

## 5. Tests run (sandbox: Linux x86-64, JDK 17.0.9, kotlinc 1.9.24, no device)

| Test | Result |
|---|---|
| `TC_ROOT=/tmp/ahmed-tc ./build-apk.sh` | **BUILD OK** (9 incremental builds during the work, all green at the end; compile API 34, minSdk 26, targetSdk 30). |
| `python3 tools/validate-pipeline.py` | **PIPELINE STATIC VALIDATION OK** (P0 guards intact: MonotonicPts, strided YUV, ExportValidator, no literal-0 EOS PTS, etc.). |
| `bash tools/audio-math-test/run.sh` | **32 passed, 0 failed** — PTS strictly increasing over 25 839 frames; 10 min of frames = 600 000 ms ± 19.6 ms; the *old* drop-but-advance rule shortens 10 min by 30 019 ms (the "sped-up" bug) vs **0 ms** with the new rule; 48 k→44.1 k resampler preserves pitch (3 999 vs 4 000 zero crossings) with no chunk-boundary clicks; `ClipCursor` wraps at `durMs`, non-loop clip ends silent, 2× speed halves duration, muted still advances, loop-aware seek; `Limiter` transparent below ceiling, 60 000-peak → 32 000 with waveform correlation 1.0000; drift guard ignores 50 ms, re-anchors at 500 ms. |
| `bash tools/viewport-fit-test/run.sh` | **199 passed, 0 failed** — 3 devices (1080×2400 @2.625 with 84 px cutout, 720×1280 @2, 1600×2560 tablet) × portrait/landscape × 16:9, 9:16, 1:1: canvas inside the free viewport, aspect preserved, centred, max scale; panel open → still fully visible and not larger; dock expanded → monotonic shrink; Full Canvas ≥ chrome layout and clears bars/cutout; ≥ 120 dp short side on phones with chrome closed; the explicit `min(availW/canvasW, availH/canvasH)` formula; negative insets clamp; zero-size source safe. |
| `bash tools/layer-model-test/run.sh` | **28 passed, 0 failed** — 1st…4th PiP take four different corners, 5th cascades, aspect kept, inside canvas, deterministic, background/hidden do not reserve corners; hit-test: topmost wins, hidden/transparent skipped, outside → null, rotated layer hit where drawn, locked still hit; z-order up/down/front/back incl. no-ops at the ends, undo snapshot per move; clamp keeps ≥ 40 % visible. |
| CI dex-symbol assertions (`.github/workflows/android.yml`) | All required symbols present in `classes.dex` (checked locally with the same script logic), plus the new ones (`ViewportFit`, `AudioMath`, `ClipCursor`, `Limiter`, `Resampler`, `MicThread`, `AudioThread`, `setFullCanvas`, `refreshContextBar`, `rebuildSourceDock`, `placeNewPip`, `hitTest`, `relayoutChrome`). |

---

## 6. Remaining limitations — stated honestly

* **Not device-verified.** The sandbox has no Android device, emulator,
  `MediaCodec`, `AudioRecord` or `WindowInsets` runtime. Everything that
  depends on them (real mic capture, AAC/AVC encoders, muxer output, actual
  insets/cutout values, immersive-mode behaviour, touch gestures) is verified
  by construction, static checks and JVM tests of the pure logic only. The
  §4 contract is what needs a run on a phone (suggested: one API 26–29 device
  for the legacy inset/immersive path and one API 30+ device).
* **Landscape 9:16** is orientation-locked out by `applyOrientationFor`
  (existing behaviour, kept). The fit test still proves it would be fully
  visible, just small.
* The **degenerate fallback** (free area < 96 dp) lets chrome overlap rather
  than shrinking the canvas below usability; it should be unreachable with the
  rail layout but is there so the picture can never vanish.
* **Echo cancellation** is not applied (AudioRecord source is `MIC`, not
  `VOICE_COMMUNICATION`, to avoid OEM AGC artefacts); instead monitoring is
  muted during recording.
* The vertical `SourceDock` (inside the Layers panel) still uses its previous
  row design; the new horizontal dock is the primary source entry point.
* Kotlin compiler warnings that pre-date this work (unused variables,
  redundant `!!` in `Exporter`) were left as-is to keep the diff focused.
