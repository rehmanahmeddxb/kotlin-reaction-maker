# Ahmed Reaction Studio — P0 pipeline fix

Golden rule: **what the user sees in the composition is what export contains.**

This document is the root-cause report, architecture report, modified-files
list, test report, and remaining limitations.

No container/codec swap was used as a fake fix. Default remains **H.264 + AAC
in MP4**. RECORD always encodes that baseline. Offline export still offers
HEVC/VP8/VP9 when the device can encode them, labelled as less compatible.

---

## Architecture

Preview, RECORD, and offline export share one source model (`Layer` /
`Project`) and one compositor (`Compositor.draw`). Each path supplies a
`(Layer) -> Bitmap?`:

| Path | Frame source | Clock | Encoder |
|---|---|---|---|
| Preview | `PreviewEngine.frameOf` (GPU decode + `LiveCamera.setFrame`) | PreviewEngine master (~16 ms tick) | none |
| RECORD (`CompositionRecorder`) | same `engine.frameOf` (live camera + playing clips) | `elapsedRealtime` at 30 fps | H.264 + AAC → MP4 |
| Offline `Exporter` | GPU/software decode of files on disk + **copied** live frames | `frameIdx * 1000 / fps` | user codec, default H.264 + AAC → MP4 |

The user workflow “live camera + local video → START RECORDING → save” is
RECORD. Offline export of a live layer cannot play the camera forward; it now
freezes the last camera frame instead of encoding an empty box.

---

## Root causes (read from the implementations, not guessed)

### 1. RECORD EOS timestamps reset to 0 (unplayable file)

`CompositionRecorder` queued video/audio EOS with `pts = 0` after a stream of
increasing frame timestamps. MediaMuxer finalized a non-zero file. Android
Gallery, Windows players, and anything that actually decodes the samples
rejected it.

**Fix:** `MonotonicPts` per track. EOS is `lastPts + Δ`, never 0. Output PTS
is re-anchored the same way before `writeSampleData`.

### 2. `muxer.start()` failures were swallowed

If `addTrack` / `start()` threw, samples were dropped and success was
`file.length() > 0`. That is not a playable video.

**Fix:** fail-loud (`muxerFailed` / `failed`). Success requires
`muxer.stop()` plus `ExportValidator` (MediaExtractor: video track, sample
count, non-decreasing PTS, duration > 0). RECORD also requires
`videoSamplesWritten > 0`.

### 3. Packed NV12 written into strided Flexible buffers (corrupt bitstream)

Hardware encoders advertise `COLOR_FormatYUV420Flexible` and expect **strided**
planes (`rowStride ≥ width`, UV `pixelStride` 1 or 2). The old path treated
Flexible as tightly packed NV12/I420 and wrote absolute indices.

**Fix:** `YuvWriter.fillInput` uses `MediaCodec.getInputImage` and writes into
the Image’s planes (including the plane buffer’s current position). Packed
NV12/I420 is only the fallback when `getInputImage` is null. `pickEncoder`
prefers Flexible.

`writeSampleData` now sets `ByteBuffer` position/limit from `BufferInfo`.

### 4. Live camera bitmap vanished when a clip was added (black camera)

`PreviewEngine.attach()` → `recycleFrames()` did `frames.clear()` even for
`externalIds` (live camera). Adding a local video after the camera dropped the
live bitmap until the next ImageReader callback — which, under record load,
could be after the first encoded frames (or never in time).

**Fix:** `recycleFrames` keeps external bitmaps. `attach` restores the ticker
(`wasTicking` / `wantSnapshots` / `anyPlaying()`).

Offline export now receives a **copy** of each live frame (`Options.liveFrames`).
The live buffers are triple-buffered and would otherwise be overwritten in
~120 ms.

### 5. First GPU PBO read returned an uninitialized bitmap (black local video)

`GlUtil.readViaPbo` returns false on the first call (PBO not primed). `draw`
used to return that unused bitmap as success, so software fallback never ran.
Combined with the ticker stopping on `attach`, a newly added clip stayed black
until Play + a second frame.

**Fix:** first unprimed PBO frame does a synchronous `glReadPixels`. Ticker
restarts after structure changes; `consumeMediaFile` calls `startSnapshots()`.

### 6. LiveCamera double-buffer race (torn / black camera under record load)

Two bitmaps: compositor still reading the published frame while `convert()`
wrapped around and `setPixels`’d it.

**Fix:** triple buffer. Never write the bitmap currently published to
`PreviewEngine`.

### 7. Three clocks (sync, not “wrong container”)

PreviewEngine master, RECORD `elapsedRealtime`, Exporter `frameIdx/fps`.
RECORD mixes pre-decoded clip PCM + mic against the wall-clock video PTS.
Audio and video EOS now share the same monotonic rule so a late EOS cannot
rewind the track.

---

## What was already correct (not re-done)

- P1 flashlight: hardware torch only if `FLASH_INFO_AVAILABLE`; otherwise
  “Front: no LED — use screen light” / “Back: no LED”. Screen light = brightness
  1.0 + warm-white panel behind the stage. CameraActivity matches.
- HomeActivity thumbs already decode off-UI with `inSampleSize = 4`.
- Preview and export already shared `Compositor.draw` + Layer geometry.

---

## Modified files

| File | Change |
|---|---|
| `export/YuvWriter.kt` | **new** — ARGB → `getInputImage` / packed fallback; `MonotonicPts` |
| `export/ExportValidator.kt` | **new** — MediaExtractor playability probe |
| `export/CompositionRecorder.kt` | RECORD rewrite: Flexible + YuvWriter, latch, EOS last+Δ, BufferInfo, audio give-up, validator |
| `export/Exporter.kt` | `liveFrames`, Flexible-first `pickEncoder`, YuvWriter, monotonic drain/mux, validator, fail-loud muxer |
| `editor/PreviewEngine.kt` | keep live frames across recycle; restore ticker after attach |
| `editor/LiveCamera.kt` | triple-buffer; never write the published bitmap |
| `core/gpu/GlUtil.kt` | sync `glReadPixels` on the first unprimed PBO frame |
| `editor/EditorActivity.kt` | copy live frames into export; pause preview during encode; wait for a camera frame before RECORD; hidden-sources pill; 60 fps option; honest live-export dialog |
| `camera/CameraActivity.kt` | “Hardware torch” vs “Screen light” labels (never call the overlay “Flash”) |
| `ui/DiagnosticsActivity.kt` | default export = H.264+AAC MP4; H.264 color-format inventory |
| `.github/workflows/android.yml` | dex-symbol guards for `YuvWriter`, `ExportValidator`, `MonotonicPts`, `CompositionRecorder`, `liveFrames` |
| `tools/validate-pipeline.py` | **new** — static P0 guards (no device required) |
| `docs/P0_PIPELINE_FIX.md` | this report |

---

## Test report

### Automated (this environment)

- `python3 tools/validate-pipeline.py` — **44 checks passed** (source-pattern
  guards + MonotonicPts algorithm + YUV size + BT.709 limited-range black/white).
- `./build-apk.sh` — **BUILD OK** (kotlinc 1.9.24, d8, v1+v2 signed APK
  `artifacts/AhmedReactionStudio-1.0.0.apk`, ~1.1 MB). Warnings only
  (deprecated Camera2 session APIs, unused params). No errors.
- Dex-symbol asserts on the built APK: all activities + `YuvWriter` +
  `ExportValidator` + `MonotonicPts` + `CompositionRecorder` + `liveFrames`.

### Not run here (no Android hardware / no MediaCodec)

This sandbox has **no Android device, no emulator, and no hardware encoder**.
The following cannot be claimed as passed:

- RECORD a live camera + local video and play the MP4 in Android Gallery.
- Play the same file in Windows (Movies & TV / VLC).
- Camera switch / torch LED vs screen light on a real phone.
- 60 fps encode on a chipset that actually advertises 60 fps.

A non-zero APK or a non-zero MP4 is **not** success. Success is:
`ExportValidator` ok **and** the file plays outside the app.

### What to run on a phone

1. New project → Camera (live) → Local video → frame them → **START RECORDING**
   ≥ 3 s → STOP & SAVE.
2. Open the file from Gallery **and** copy it to a PC. Both must play, with
   camera **and** the clip visible, audio present.
3. Hide the camera, confirm the hidden-sources pill, unhide, record again.
4. Offline Export with a live camera on canvas: dialog offers recording or a
   **frozen** camera frame — never a black hole when a frame exists.
5. Diagnostics: “Default export: H.264 + AAC in MP4”; Front/Back flash rows
   say “hardware flash” or “no flash” from `FLASH_INFO_AVAILABLE`.

---

## Remaining limitations

- Offline export of a **live** camera still cannot play the camera forward.
  RECORD is the motion-capture path. Export freezes the last frame.
- RECORD still composites on the main thread at ~30 fps. Under heavy GPU
  decode the recorder may drop queued frames (queue depth 4). That is a P3
  performance issue, not a playability bug.
- WebM export is video-only (AAC in WebM is not offered). H.264/H.265 MP4
  mux AAC.
- `ExportValidator` uses this device’s `MediaExtractor`. A file that probes
  here almost always plays on Android; a Windows player that lacks the codec
  (HEVC) can still fail — which is why the default is H.264.
- First GPU frame after a decoder rebuild pays one synchronous `glReadPixels`
  (~15–25 ms). Subsequent frames stay on the async PBO path.
- No on-device instrumentation test suite exists in this repo (offline
  kotlinc build, no Gradle/Robolectric).
