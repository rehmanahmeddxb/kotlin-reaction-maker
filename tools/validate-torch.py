#!/usr/bin/env python3
"""
Static guards for the hardware flashlight / torch (STEP 4).

No Android hardware or emulator is needed. These checks catch the classes of
bugs that made the old "flash" fake a rear torch with a white screen, or left a
rear LED burning:

  * the LED is driven ONLY through TorchController (CameraManager.setTorchMode),
    never by ad-hoc setTorchMode calls scattered around the camera code;
  * FLASH_MODE_TORCH is a pure fallback, used only when torch mode is refused;
  * a side without an LED is never faked (screen light stays the fallback);
  * every exit path (pause / stop / destroy / switch / close / record stop /
    camera error) switches the LED off.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app" / "src" / "com" / "rehman" / "ahmedreactionstudio"

errors: list[str] = []
oks: list[str] = []


def read(rel: str) -> str:
    p = SRC / rel
    if not p.exists():
        errors.append(f"missing file {rel}")
        return ""
    return p.read_text(encoding="utf-8")


def has(rel: str, needle: str, why: str) -> None:
    text = read(rel)
    if needle in text:
        oks.append(f"{rel}: {why}")
    else:
        errors.append(f"{rel}: missing `{needle}` ({why})")


def hasnt(rel: str, needle: str, why: str) -> None:
    text = read(rel)
    if needle not in text:
        oks.append(f"{rel}: {why}")
    else:
        errors.append(f"{rel}: must NOT contain `{needle}` ({why})")


def hasnt_call(rel: str, call: str, why: str) -> None:
    """Forbid a real call site (comments naming CameraManager.setTorchMode are fine)."""
    text = read(rel)
    hits = [m.start() for m in re.finditer(re.escape(call), text)
            if not text[max(0, m.start() - 13):m.start()].endswith("CameraManager")]
    if not hits:
        oks.append(f"{rel}: {why}")
    else:
        errors.append(f"{rel}: {len(hits)} ad-hoc `{call}` call site(s) ({why})")


def count_at_least(rel: str, needle: str, n: int, why: str) -> None:
    text = read(rel)
    c = text.count(needle)
    if c >= n:
        oks.append(f"{rel}: {why} ({c}x)")
    else:
        errors.append(f"{rel}: expected {n}x `{needle}`, found {c} ({why})")


TORCH = "camera/TorchController.kt"
LIVE = "editor/LiveCamera.kt"
CAMACT = "camera/CameraActivity.kt"
EDITOR = "editor/EditorActivity.kt"

# ---------------- the controller is the only LED driver ----------------
has(TORCH, "setTorchMode(", "hardware torch uses CameraManager.setTorchMode()")
has(TORCH, "registerTorchCallback", "true LED state tracked with TorchCallback")
has(TORCH, "FLASH_INFO_AVAILABLE", "LED presence read from FLASH_INFO_AVAILABLE")
has(TORCH, "LENS_FACING_BACK", "rear camera identified by LENS_FACING_BACK")
has(TORCH, "LENS_FACING_FRONT", "front camera identified by LENS_FACING_FRONT")
has(TORCH, "CameraAccessException", "CameraAccessException handled")
has(TORCH, "SecurityException", "permission errors handled")
has(TORCH, "IllegalArgumentException", "stale/unknown camera id handled")
has(TORCH, "CAMERA_IN_USE", "camera-in-use handled")
has(TORCH, "fun releaseAll()", "releaseAll() turns every LED off")
has(TORCH, "fun shutdown()", "shutdown() releases the LED and the callback")

for f in (LIVE, CAMACT, EDITOR):
    hasnt_call(f, ".setTorchMode(",
               f"no ad-hoc torch control — TorchController owns the LED")

# ---------------- FLASH_MODE_TORCH is only a fallback ----------------
has(LIVE, "torchViaRequest", "capture-request torch gated behind torch mode failure")
has(LIVE, "useRequestTorch", "FLASH_MODE_TORCH only when torch mode was refused")
has(CAMACT, "torchViaRequest", "capture-request torch gated behind torch mode failure")
has(LIVE, "hasFlashUnit", "LED presence still queried for the request fallback")

# ---------------- no faking: screen light stays a fallback ----------------
has(CAMACT, "no front LED", "screen light only offered where there is no LED")
has(CAMACT, "This device has no rear flash", "rear camera without a flash says so")
has(EDITOR, "Screen light", "editor keeps the screen-light fallback")
has(LIVE, "torchCtl.hasFlash(", "front LED existence is queried, never assumed")

# ---------------- lifecycle: the LED is off on every exit path ----------------
# LiveCamera
count_at_least(LIVE, "torchCtl.releaseAll()", 2, "LED off on close/release")
count_at_least(LIVE, "torchOff()", 4, "torch off on switch / stop / error / record stop")
has(LIVE, "shutdownTorch()", "torch callback unhooked on stop")
has(LIVE, "torch.setTorch(wasFront, false)".replace("torch.setTorch", "torchCtl.setTorch"),
    "switching cameras turns the outgoing LED off")
# CameraActivity
count_at_least(CAMACT, "torchOff()", 4, "torch off on close / switch / record stop / error")
has(CAMACT, "torch.shutdown()", "activity destroy releases the torch")
has(CAMACT, "override fun onPause()", "pause closes camera + torch")
has(CAMACT, "override fun onDestroy()", "destroy path exists")

# ---------------- UI reports the real state ----------------
has(CAMACT, "torchBtn.isEnabled = false", "unavailable torch disables the button")
has(CAMACT, "torchBtn.isEnabled = true", "available torch enables the button")
has(LIVE, "isTorchLitForBack()", "editor reads the real LED state")

# ---------------- the forbidden: faking the rear torch on screen ----------------
cam_text = read(CAMACT)
if "no front LED" in cam_text and cam_text.index("no front LED") < cam_text.index("setScreenLight(want)"):
    oks.append(f"{CAMACT}: screen light guarded by the front-camera branch")
else:
    errors.append(f"{CAMACT}: screen-light fallback must sit in the front-camera branch")

print("TORCH STATIC VALIDATION")
for o in oks:
    print("  OK  " + o)
if errors:
    print()
    for e in errors:
        print("  FAIL " + e)
    print()
    print(f"TORCH STATIC VALIDATION FAILED ({len(errors)} problem(s))")
    sys.exit(1)
print()
print(f"TORCH STATIC VALIDATION OK ({len(oks)} checks)")
