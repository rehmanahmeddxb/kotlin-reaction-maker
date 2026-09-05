#!/usr/bin/env python3
"""
Static guards for the P0 media pipeline.

No Android hardware is required. These checks catch the classes of bugs that
produced unplayable exports / black camera / frozen local video:

  * EOS queued at pts=0 after increasing timestamps
  * live camera frames dropped on attach()
  * COLOR_FormatYUV420Flexible treated as packed NV12 without getInputImage
  * writeSampleData without BufferInfo position/limit
  * muxer.start() failures swallowed
  * first GPU PBO frame returned uninitialized
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


def must_contain(rel: str, needle: str, why: str) -> None:
    text = read(rel)
    if needle not in text:
        errors.append(f"{rel}: missing `{needle}` ({why})")
    else:
        oks.append(f"{rel}: {why}")


def must_not_match(rel: str, pattern: str, why: str) -> None:
    text = read(rel)
    if re.search(pattern, text):
        errors.append(f"{rel}: forbidden pattern {pattern!r} ({why})")
    else:
        oks.append(f"{rel}: no {why}")


# ---- files that must exist ----
for rel in (
    "export/YuvWriter.kt",
    "export/ExportValidator.kt",
    "export/CompositionRecorder.kt",
    "export/Exporter.kt",
    "editor/PreviewEngine.kt",
    "editor/LiveCamera.kt",
    "core/gpu/GlUtil.kt",
    "editor/EditorActivity.kt",
):
    if (SRC / rel).exists():
        oks.append(f"present {rel}")
    else:
        errors.append(f"missing {rel}")

# ---- encoder input must go through getInputImage / YuvWriter ----
must_contain("export/YuvWriter.kt", "getInputImage", "strided Image path")
must_contain("export/YuvWriter.kt", "rowStride", "honour encoder stride")
must_contain("export/YuvWriter.kt", "pixelStride", "honour UV pixel stride")
must_contain("export/YuvWriter.kt", "class MonotonicPts", "PTS clock")
must_contain("export/Exporter.kt", "YuvWriter.fillInput", "exporter uses YuvWriter")
must_contain("export/CompositionRecorder.kt", "YuvWriter.fillInput", "recorder uses YuvWriter")
must_contain("export/Exporter.kt", "COLOR_FormatYUV420Flexible", "Flexible preferred")

# ---- PTS / muxer ----
must_contain("export/Exporter.kt", "vPts.next", "monotonic video PTS")
must_contain("export/CompositionRecorder.kt", "videoPts.next", "recorder monotonic video PTS")
must_contain("export/CompositionRecorder.kt", "BUFFER_FLAG_END_OF_STREAM", "recorder sends EOS")
must_contain("export/Exporter.kt", "muxerFailed", "muxer.start fail-loud")
must_contain("export/CompositionRecorder.kt", "muxer.start failed", "recorder muxer.start fail-loud")
must_contain("export/Exporter.kt", "buf.position(info.offset)", "BufferInfo position")
must_contain("export/CompositionRecorder.kt", "buf.position(info.offset)", "recorder BufferInfo position")
must_contain("export/Exporter.kt", "ExportValidator.validate", "probe after finalize")
must_contain("export/CompositionRecorder.kt", "ExportValidator.validate", "recorder probe")

# EOS at literal pts 0 on the MUX path is the classic unplayable-file bug.
# Decoder EOS (GpuVideoDecoder / AudioDecode) is allowed — those are not muxed.
for rel in ("export/Exporter.kt", "export/CompositionRecorder.kt"):
    text = read(rel)
    if re.search(
        r"queueInputBuffer\([^;]*0,\s*0,\s*0,\s*MediaCodec\.BUFFER_FLAG_END_OF_STREAM",
        text,
    ) or re.search(
        r"queueInputBuffer\([^;]*0,\s*0,\s*0,\s*BUFFER_FLAG_END_OF_STREAM",
        text,
    ):
        errors.append(f"{rel}: EOS queued at pts=0")
    else:
        oks.append(f"{rel}: EOS not at pts=0")

# ---- live camera kept across attach / triple buffer / copy on export ----
must_contain("editor/PreviewEngine.kt", "externalIds.contains(id)", "keep live frames on recycle")
must_contain("editor/PreviewEngine.kt", "wasTicking", "restore ticker after attach")
must_contain("editor/LiveCamera.kt", "published", "triple-buffer published slot")
must_contain("editor/LiveCamera.kt", "bufC", "third camera buffer")
must_contain("editor/EditorActivity.kt", "liveFrames", "pass frozen camera frames to exporter")
must_contain("editor/EditorActivity.kt", "src.copy(Bitmap.Config.ARGB_8888, false)", "copy live frame for export")
must_contain("export/Exporter.kt", "l.isLive() -> opts.liveFrames[l.id]", "live layer uses frozen frame")

# ---- first PBO frame must sync-read ----
must_contain("core/gpu/GlUtil.kt", "glReadPixels", "sync read of first PBO frame")
must_contain("core/gpu/GlUtil.kt", "uninitialized bitmap", "documents the black-first-frame bug")

# ---- validator contract ----
must_contain("export/ExportValidator.kt", "MediaExtractor", "re-open with the platform parser")
must_contain("export/ExportValidator.kt", "pts < last", "reject backwards timestamps")

# ---- default playable baseline ----
must_contain("export/CompositionRecorder.kt", "MIMETYPE_VIDEO_AVC", "RECORD is H.264")
must_contain("editor/EditorActivity.kt", "Exporter.Codec.H264", "H.264 default")


# ---- MonotonicPts algorithm (mirrors YuvWriter.kt) ----
class MonotonicPts:
    def __init__(self, start=0):
        self.last = -1
        self.start = start

    def next(self, candidate):
        v = candidate
        if v < self.start:
            v = self.start
        if v <= self.last:
            v = self.last + 1
        self.last = v
        return v


def test_pts() -> None:
    c = MonotonicPts()
    got = [c.next(x) for x in (0, 33_333, 66_666, 0, 66_666, 100_000)]
    exp = [0, 33_333, 66_666, 66_667, 66_668, 100_000]
    if got != exp:
        errors.append(f"MonotonicPts mismatch {got} != {exp}")
    else:
        oks.append("MonotonicPts: EOS-at-0 and duplicates become last+1")
    c2 = MonotonicPts()
    seq = [c2.next(i * 33_333) for i in range(30)]
    if any(seq[i] <= seq[i - 1] for i in range(1, len(seq))):
        errors.append("MonotonicPts not strictly increasing over 30 frames")
    else:
        oks.append("MonotonicPts: 30-frame wall-clock sequence is strict")


def test_yuv_packed_size() -> None:
    # NV12/I420 = Y + UV/2 + UV/2 = 1.5 bytes/pixel
    for w, h in ((16, 16), (1280, 720), (720, 1280), (1920, 1088)):
        need = w * h + (w * h) // 2
        if need != w * h * 3 // 2:
            errors.append(f"YUV size formula {w}x{h}")
        if need % 2:
            errors.append(f"YUV size odd {w}x{h}")
    oks.append("YUV packed size is 1.5 bytes/pixel for even dims")


def test_bt709_range() -> None:
    def y_of(c):
        r, g, b = (c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF
        y = ((47 * r + 157 * g + 16 * b + 128) >> 8) + 16
        return 16 if y < 16 else 235 if y > 235 else y

    black = y_of(0xFF000000)
    white = y_of(0xFFFFFFFF)
    if black != 16:
        errors.append(f"BT.709 black Y={black} want 16")
    else:
        oks.append("BT.709 limited black = 16")
    if not (230 <= white <= 235):
        errors.append(f"BT.709 white Y={white} want ~235")
    else:
        oks.append(f"BT.709 limited white = {white}")


test_pts()
test_yuv_packed_size()
test_bt709_range()

print(f"{len(oks)} checks passed")
for line in oks:
    print("  OK ", line)
if errors:
    print(f"\n{len(errors)} FAILED")
    for line in errors:
        print("  FAIL", line)
    sys.exit(1)
print("\nPIPELINE STATIC VALIDATION OK")
