# STEP 4 — Real hardware flashlight / torch

Scope: flashlight + the minimum camera-lifecycle code it needs. Audio, mixing,
encoding, MediaMuxer, export, canvas layout, source borders, compositor and the
rest of the editor were **not** touched.

---

## 1. Root cause of the previous flashlight behaviour

The old code never used Android's torch API for the camera that was open. It
only set `CaptureRequest.FLASH_MODE_TORCH` on the repeating request of the
*currently open* camera session:

```kotlin
// CameraActivity.applyParams()  (before)
if (torchSupported) {
    builder.set(CaptureRequest.FLASH_MODE,
        if (torchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
}
```

```kotlin
// LiveCamera.repeatRequest()  (before)
b.set(CaptureRequest.FLASH_MODE,
     if (wantTorch && hasFlashUnit) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
```

Consequences on real devices:

1. **The "flash" was a screen light, not the LED.** `torchSupported` /
   `hasFlashUnit` were read from whatever camera happened to be open. Both
   entry points start on the **front** camera (`facing = LENS_FACING_FRONT`),
   a front camera has `FLASH_INFO_AVAILABLE = false` on essentially every
   phone, so the code took the "no hardware flash" branch and switched on the
   white overlay / screen-light panel. That is the "front screen-light being
   passed off as the flashlight" behaviour.
2. **The torch died on every session rebuild.** `FLASH_MODE_TORCH` only lives
   in the repeating request. Recording start/stop, facing switch, preview
   restart and camera close all rebuild the `CameraCaptureSession`
   (`session?.close(); session = null; createSession()`), so the rear LED was
   dropped on each of those — exactly the "torch does not survive preview state
   changes" symptom.
3. **The rear LED could be left ON.** `CameraManager.setTorchMode()` was used
   in exactly one place: LiveCamera's `applyIdleTorch()`, which switched the
   *idle* camera's LED on from the remembered per-facing flag. After
   `closeCameraOnly()` sets `activeCameraId = null`, the camera that was just
   closed counts as "idle", so a rear torch enabled before a Back → Front
   switch kept burning with no preview and no way to notice it.
4. **No error handling.** `CameraAccessException` / camera-in-use / no-torch
   devices were swallowed; the UI still reported the torch as ON.

## 2. What the fix does

New file `app/src/com/rehman/ahmedreactionstudio/camera/TorchController.kt` —
the only class allowed to touch a flash LED:

* inventories `CameraManager.cameraIdList` and stores, per id,
  `LENS_FACING_BACK` / `LENS_FACING_FRONT` / `FLASH_INFO_AVAILABLE` /
  hardware level;
* drives the LED with **`CameraManager.setTorchMode(cameraId, on)`** — no
  camera device needs to be open, so the torch survives session rebuilds and
  works during recording;
* registers a `CameraManager.TorchCallback` so `isTorchOn()` reports the **real**
  LED state (and `onTorchModeUnavailable` marks a camera whose framework cannot
  do torch mode);
* maps every failure to a reason (`NO_FLASH`, `TORCH_UNSUPPORTED`,
  `CAMERA_IN_USE`, `CAMERA_DISCONNECTED`, `CAMERA_DISABLED`, `CAMERA_ERROR`,
  `PERMISSION_DENIED`) and never throws;
* `releaseAll()` / `shutdown()` switch every LED off and unhook the callback.

`FLASH_MODE_TORCH` on the capture request is kept **only** as a fallback
(`torchViaRequest`) for the rare device that reports a flash unit but refuses
torch mode while a session is open.

## 3. Behaviour matrix

| Side | `FLASH_INFO_AVAILABLE` | Flashlight control |
| --- | --- | --- |
| Rear | true | rear LED via `setTorchMode()` (button: `Torch` / `Torch ON`) |
| Rear | false | button **disabled**, label `No flash`, toast "This device has no rear flash" — never faked |
| Front | true | that camera's LED via `setTorchMode()` (`Front torch` / `Front torch ON`) |
| Front | false | **screen-light fallback** (existing white overlay in `CameraActivity`, existing brightness panel in `EditorActivity`), label `Screen light` / `Screen light ON` |

Camera switching: switching cameras clears the side you leave, so a rear LED is
never left burning behind a front preview. In the editor's Light ring the
Front / Back / Both toggles still work independently — that is an explicit user
action, and `setTorchMode()` keeps the rear LED lit while the front preview
runs.

## 4. Lifecycle cleanup (LED is switched off on every exit path)

| Path | CameraActivity | LiveCamera |
| --- | --- | --- |
| leaving the screen / pause | `onPause → closeCamera → closeCameraLocked → torchOff()` | `EditorActivity.onStop → stopLiveCamera → stop → releaseAll → torchOff()` |
| activity destroyed | `onDestroy → torch.shutdown()` | `stop() → shutdownTorch()` |
| switching cameras | `toggleCamera → torchOff()` | `switchFacing → setTorch(outgoing, false)` |
| closing the camera | `closeCameraLocked → torchOff()` | `closeCameraOnly → releaseAll()` |
| stopping a recording | `stopRecordingLocked → torchOff()` | `stopRecordingLocked → torchOff()` |
| camera disconnected / error / camera in use | `onDisconnected` / `onError → torchOff()` | `onDisconnected` / `onError` / open failure → `torchOff()` |

`torchOff()` = every LED off (`TorchController.releaseAll()`) + state reset +
screen-light overlay hidden.

## 5. Files changed

| File | Change |
| --- | --- |
| `app/src/.../camera/TorchController.kt` | **new** — hardware torch: id inventory, `setTorchMode`, `TorchCallback`, failure reasons, `releaseAll`/`shutdown`, diagnostics text |
| `app/src/.../camera/CameraActivity.kt` | real rear/front LED through the controller; `FLASH_MODE_TORCH` demoted to a fallback; button reflects ON/OFF/unavailable; screen light only where there is no LED; torch off on switch / close / pause / record stop / error / destroy |
| `app/src/.../editor/LiveCamera.kt` | torch driven by the controller; `applyTorch()` re-applies the state after every session rebuild; switching clears the outgoing side; `torchOff()` on stop, close, record stop, disconnect, error; request-torch fallback only when torch mode is refused |
| `app/src/.../editor/EditorActivity.kt` | honest toasts ("no LED — use the screen light", real failure text), light indicator reads the actual LED state, screen-light fallback kept |
| `app/src/.../ui/DiagnosticsActivity.kt` | two new rows: `Torch: rear` / `Torch: front` (camera id + LED state) |
| `tools/validate-torch.py` | **new** — 34 static guards (LED only via controller, request-torch only as fallback, no fake rear torch, LED off on every exit path) |
| `.github/workflows/android.yml` | runs the torch validator in CI |
| `docs/STEP4_TORCH_FIX.md` | this report |
| `artifacts/AhmedReactionStudio-1.0.0.apk` | rebuilt signed APK |

## 6. Build / test result

* `python3 tools/validate-pipeline.py` → `PIPELINE STATIC VALIDATION OK`
* `python3 tools/validate-torch.py` → `TORCH STATIC VALIDATION OK (34 checks)`
* `./build-apk.sh` → `BUILD OK` (kotlinc clean, d8 dex, v1+v2 signed APK)

On-device verification (the 11-step sequence) needs real hardware: this
sandbox has no emulator, no ADB and no device, so the LED itself could not be
observed here. What *was* verified is everything that can be verified without a
phone: compilation, dexing, signing and the static invariants above. The new
`Diagnostics → Torch: rear/front` rows print the camera id the app selected and
whether that side's LED is on, so the hardware check on a device is a single
screen.
