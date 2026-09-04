# Ahmed Reaction Studio (Kotlin)

A real native Android reaction-video studio written in Kotlin against the
framework APIs (`android.app`, `android.view`, `Camera2`, `MediaCodec`,
`MediaMuxer`, OpenGL-free CPU compositor), packaged as
`com.rehman.ahmedreactionstudio`.

- Animated splash screen (`SplashActivity`) then project home.
- **Full-screen DSLR-style studio**: the composition canvas fills the whole
  screen; every control floats OVER it (top bar + smart bottom dock that
  slides up like a camera / VN / KineMaster quick-panel).
- **Main canvas first**: an empty project asks what the background is —
  local video, **recorded camera**, **screen recording**, or image; anything
  added afterwards is a PiP. Any layer can later be promoted to main canvas
  (Adjust tab → “Set selected as main canvas”).
- 16:9 / 9:16 / 1:1 canvases (16:9 default) with normalized geometry and
  auto screen orientation.
- Import **video in any decodable container (MP4, AVI, WebM, MKV, 3GP, MOV)**
  and images; text overlays. Un-decodable files are reported, not crashed on.
- Camera2 fullscreen capture: front/back switch, **hardware flash on any lens
  that has one (front OR back)** + selfie screen-light fallback, zoom slider,
  MP4 recording straight into the project.
- **Screen recording** via MediaProjection (foreground service) as a main
  canvas or a PiP source.
- Layer editor: PiP drag/resize/rotate, Fill / Contain / PiP presets,
  visibility/lock, per-layer play/pause, mute & volume, z-order,
  undo/redo, autosave + snapshot recovery.
- **Export codec picker: H.264 MP4, H.265/HEVC MP4, VP8 WebM, VP9 WebM**
  (only codecs the device actually encodes are offered), plus resolution /
  quality / frame-rate choices. AVI has no Android muxer, so it is import-only.

## Layout

- `app/src/.../ui` — `SplashActivity`, `HomeActivity`, `DiagnosticsActivity`
- `app/src/.../editor` — `EditorActivity` (fullscreen overlay studio),
  `StageView`, `PreviewEngine`
- `app/src/.../camera` — `CameraActivity` (Camera2 + MediaRecorder, crash-safe)
- `app/src/.../capture` — `ScreenCaptureService` (MediaProjection screen record)
- `app/src/.../export` — `Exporter` (H.264/H.265/VP8/VP9 MediaCodec pipeline)
- `app/src/.../core` — project model, store, media probes, undo stack
- `build-apk.sh` — dependency-free offline builder (kotlinc → d8 → aapt2 → apksigner)
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
