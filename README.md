# Ahmed Reaction Studio (Kotlin)

A real native Android reaction-video studio written in Kotlin against the
framework APIs (`android.app`, `android.view`, `Camera2`, `MediaCodec`,
`MediaMuxer`, OpenGL-free CPU compositor), packaged as
`com.rehman.ahmedreactionstudio`.

> **Architecture:** OBS-style source controls — see
> [`docs/OBS_SOURCE_PLAN.md`](docs/OBS_SOURCE_PLAN.md). Sources are
> first-class citizens: select one and its controls are one tap away,
> never buried in settings. What you see is exactly what gets exported.

- Animated splash screen (`SplashActivity`) then project home.
- **Full-screen studio**: the composition canvas fills the whole screen;
  every control floats OVER it (top bar, quick control bar, bottom dock).
- **Sources, OBS-style**: every source gets a floating **Quick Control Bar**
  (👁 hide · 🔇 mute · ⏯ source pause · 🔒 lock · fit · ◉ radial wheel · ⋮
  advanced sheet), a **Source Dock** mini-mixer (per-row eye/mute toggles,
  live status chips, drag-handle Z reordering) and a **contextual radial
  wheel** that blooms with spring animations around the selected source.
  Hide ≠ delete; pause = hold last frame; hidden sources keep their audio;
  solo mutes everything else without destroying state.
- **Fit mode per source**: Fill (cover) or Fit (whole frame, letterboxed) —
  camera takes default to Fit, so a camera is never "cut out" of the canvas.
- **Main canvas first**: an empty project asks what the background is —
  local video, **recorded camera**, **screen recording**, or image; anything
  added afterwards is a PiP. Any source can later be promoted to canvas
  background (advanced sheet / Canvas tab).
- 16:9 / 9:16 / 1:1 canvases (16:9 default) with normalized geometry,
  auto screen orientation and a one-tap cycling aspect chip.
- Canvas gestures: tap select, **double-tap = hide/show**, drag with snap,
  8-handle resize, rotate knob, pinch scale+rotate.
- Import **video in any decodable container (MP4, AVI, WebM, MKV, 3GP, MOV)**
  and images; text overlays. Un-decodable files are reported, not crashed on.
- Camera2 fullscreen capture: front/back switch, **hardware flash on any lens
  that has one (front OR back)** + selfie screen-light fallback, zoom slider,
  MP4 recording straight into the project.
- **Screen recording** via MediaProjection (foreground service) as a main
  canvas or a PiP source.
- Loop per source, per-source volume, opacity, z-order, undo/redo, autosave
  + snapshot recovery; destructive operations are locked while exporting.
- **Export codec picker: H.264 MP4, H.265/HEVC MP4, VP8 WebM, VP9 WebM**
  (only codecs the device actually encodes are offered), plus resolution /
  quality / frame-rate choices. AVI has no Android muxer, so it is import-only.

## Canvas & layer geometry

One rule set (`core/LayerFit.kt`) places every layer, and the same
`Compositor` draws the preview and the export, so what you place is what you
get:

- **Main canvas** — the layer box *is* the canvas (1 × 1). How the frame
  fills it is per-source: `fit = fill` cover-crops (full bleed), `fit = fit`
  letterboxes the **whole frame** inside the canvas (camera default — nothing
  gets cut). The selection frame and all eight handles stay on screen.
- **Overlays / PiP** — the box keeps the *source* aspect ratio, is fitted into
  the reaction-cam area and pinned to a corner, so a portrait camera take is
  never squashed into a landscape sliver. Dragging keeps at least 40 % of the
  layer on canvas.
- **Preview decoding** — one cached `MediaMetadataRetriever` per clip, walked
  forward at the stage's own resolution on a worker pool (adaptive: it drops
  the decode size before it drops your frame rate), which is what keeps an
  imported 4K clip playable.

## Layout

- `app/src/.../ui` — `SplashActivity`, `HomeActivity`, `DiagnosticsActivity`
- `app/src/.../editor` — `EditorActivity` (fullscreen OBS-style studio),
  `StageView` (canvas gestures), `PreviewEngine`, `SourceDock` (mini mixer),
  `RadialWheel` (animated contextual wheel), `Icons` (vector-icon toolkit)
- `app/src/.../camera` — `CameraActivity` (Camera2 + MediaRecorder, crash-safe)
- `app/src/.../capture` — `ScreenCaptureService` (MediaProjection screen record)
- `app/src/.../export` — `Exporter` (H.264/H.265/VP8/VP9 MediaCodec pipeline)
- `app/src/.../core` — project model, `SourceController` (command layer),
  store, media probes, undo stack
- `res/drawable/ic_*.xml` — Material-style vector icon set
- `build-apk.sh` — dependency-free offline builder (aapt2 → R class →
  kotlinc → d8 → apksigner)
- `.github/workflows/android.yml` — CI that builds, verifies and uploads the APK

## Building

Prerequisites on the build host (the script tolerates env overrides, see header):
a JDK 17+, a `kotlinc`, an API-30 `android.jar`, `aapt2`, `d8.jar` and
`apksigner.jar`. Point `TC_ROOT`/`KOTLINC`/… at them or use the layout the
CI workflow assembles under `/tmp/ahmed-tc`.

```sh
./build-apk.sh
# artifacts/AhmedReactionStudio-1.0.0.apk  (signed, installable on Android 8+)
```

On GitHub, push any branch (or run the workflow manually) and download the
`AhmedReactionStudio-1.0.0-apk` artifact from the Actions run.

## Install

Android 8.0+ (min SDK 26). The APK is signed with a generated key — allow
"install unknown apps" when prompted.
