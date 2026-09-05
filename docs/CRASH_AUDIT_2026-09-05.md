# Crash & Behavior Audit — 2026-09-05

Device-reported symptoms audited and fixed:

1. "The app APK just crashes" (random crashes)
2. "When I click 16:9 selection it didn't rotate to landscape automatically"
3. "When I added camera it crashed"

## Findings & fixes

### 1. 16:9 selection never rotated the studio (CONFIRMED BUG)

`EditorActivity.applyOrientationFor()` hard-set
`SCREEN_ORIENTATION_UNSPECIFIED` for every aspect, with a comment saying the
canvas contain-fit made rotation unnecessary. But the landscape chrome (right
rail, wider stage) only exists in landscape, and user expectation — and every
video editor — rotates the studio for 16:9. Result: tapping 16:9 did nothing
but refit.

**Fix:** `16:9 → SENSOR_LANDSCAPE`, `9:16 → SENSOR_PORTRAIT`, `1:1 →
UNSPECIFIED` (free). The editor already declares
`android:configChanges="orientation|screenSize|..."`, so the forced rotation
goes through `onConfigurationChanged()` — chrome re-lays out, canvas
re-fits, and the camera / decoders / master clock are NOT restarted.

The fullscreen camera recorder had the same problem and was additionally
manifest-locked to portrait; it is now sensor-landscape (16:9 framing) with
the same config-changes treatment.

### 2. MediaRecorder state-machine crash when the mic was enabled (CONFIRMED CRASH)

In **both** `CameraActivity` and `ScreenCaptureService` the MediaRecorder was
configured as:

```
setVideoSource(SURFACE)
setOutputFormat(MPEG_4)
setOutputFile(...)
setVideoEncoder(...)
if (mic) { setAudioSource(MIC); setAudioEncoder(AAC) ... }   // ← ILLEGAL ORDER
prepare()
```

`setAudioSource()` is only legal in the `Initialized` state — i.e. BEFORE
`setOutputFormat()`. Called after it, `prepare()` throws
`IllegalStateException`, which propagated straight out of the background
camera path. With RECORD_AUDIO granted (which the app requests alongside
CAMERA at add-camera time) **every camera-take and screen-recording start
crashed/failed** — exactly the "when I added camera it crashed" report.

**Fix:** both files now set ALL sources (video + audio) before
`setOutputFormat()`, then format/file/encoders. `LiveCamera` already had the
correct order and was left as-is.

### 3. Live-camera recorder given an unsupported size (CONFIRMED CRASH)

`LiveCamera.startRecordingLocked()` passed the **ImageReader YUV size** to
`MediaRecorder.setVideoSize()`. Camera2 requires every session surface's size
to come from that target class's size table
(`getOutputSizes(MediaRecorder.class)`), which differs from the YUV table on
many devices. With an unsupported size `createCaptureSession` fails →
`onConfigureFailed` → `"error"` state.

**Fix:** a recorder-specific size is now picked once at camera open
(closest supported MediaRecorder size ≤ 1080p) and used for the MP4 surface.

### 4. Camera error → fallback bounce loop (CONFIRMED BUG)

The live-camera state callback handled `"nocamera"`/`"error"` by deleting the
camera layer and immediately launching the fullscreen CameraActivity — from
the state callback, without any guard. A busy camera (or a fullscreen
recorder that then failed) bounced the user between two camera surfaces
repeatedly, each reopen restarting camera hardware.

**Fix:** fallback to the fullscreen recorder happens at most ONCE per attempt
(`cameraFallbackShown`, reset on a clean `"live"` state); after that the user
gets a plain "camera is busy" message. `"disconnected"` keeps the layer and
revives on resume instead of tearing the project apart. The layer is no
longer deleted on hardware failure (undo still works).

### 5. Recorded video orientation ignored device rotation (CONFIRMED BUG)

The live-camera and fullscreen `setOrientationHint()` used
`sensorOrientation` (front: +180) without subtracting the device's display
rotation. Takes recorded after the studio auto-rotated to landscape (fix #1)
came out sideways.

**Fix:** standard camera2 formula:
- back:  `(sensorOrientation - deviceRotation + 360) % 360`
- front: `(sensorOrientation + deviceRotation + 180) % 360`

applied in `LiveCamera` and `CameraActivity`. The feed's per-frame rotation
already used the correct formula.

### 6. No crash evidence anywhere (process-level)

Any uncaught exception killed the process with only logcat left behind — a
non-technical user could only report "it crashed".

**Fix:** new `App : Application` installs an `UncaughtExceptionHandler` that
writes the full stack trace (device, Android version, thread, time) to
`filesDir/crashes/crash-<time>.txt`, keeps the last 6, then lets the default
handler terminate the process deterministically. The Diagnostics screen now
shows the latest crash log with copy/clear, and "Copy diagnostics" includes
it — future crash reports come with the actual stack trace.

## Verified

- Full offline toolchain build passes (`build-apk.sh`, BUILD OK, v1+v2 signed).
- Manifest registers `android:name=".App"`; CameraActivity configChanges
  added; portrait lock removed.
- MediaRecorder call order now legal in all three record paths.
- No source order changes for the already-correct `LiveCamera` recorder
  config other than the size + orientation hint.

## Not reproduced but now guarded

- The `App` handler captures ANY uncaught throwable (GPU/GL, codec ROM bugs),
  so a crash on a specific device will leave a readable trace under
  Diagnostics instead of being a white-box failure.
