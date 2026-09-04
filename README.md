# Ahmed Reaction Studio (Kotlin)

A real native Android reaction-video studio written in Kotlin against the
framework APIs (`android.app`, `android.view`, `Camera2`, `MediaCodec`,
`MediaMuxer`, OpenGL-free CPU compositor), packaged as
`com.rehman.ahmedreactionstudio`.

- Animated splash screen (`SplashActivity`) then project home.
- 16:9 / 9:16 / 1:1 projects with normalized canvas + auto orientation.
- Import videos/images, Camera2 front/rear capture with torch + zoom,
  text layers.
- Layer-based editor: PiP drag/resize, visibility/lock, per-layer play/pause,
  mute & volume, z-order, undo/redo, autosave + snapshot recovery.
- H.264 MP4 export via MediaCodec (NV12 encoder-format preference).

## Layout

- `app/src/.../ui` — `SplashActivity`, `HomeActivity`, `DiagnosticsActivity`
- `app/src/.../editor` — `EditorActivity`, `StageView`, `PreviewEngine`
- `app/src/.../camera` — `CameraActivity` (Camera2 + MediaRecorder)
- `app/src/.../export` — `Exporter` (H.264 MediaCodec pipeline)
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
