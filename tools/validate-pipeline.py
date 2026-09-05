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
    "export/AudioDecode.kt",
    "export/AudioMixer.kt",
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
# STEP 3: MonotonicPts guards are OUTPUT-ONLY (muxer timeline). Encoder inputs
# pass their own monotonic timelines directly (video: frame/wall clock,
# audio: sample count). Sharing one clock for inputs+outputs compressed
# encoder-delayed timestamps, so the guard lives in writeOut/writeTrack as
# `clock.next(info.presentationTimeUs)` with per-track clock instances.
must_contain("export/Exporter.kt", "clock.next(info.presentationTimeUs)", "monotonic muxer PTS (output guard)")
must_contain("export/CompositionRecorder.kt", "clock.next(info.presentationTimeUs)", "recorder monotonic muxer PTS (output guard)")
must_contain("export/Exporter.kt", "val vPts = MonotonicPts()", "video output clock instance")
must_contain("export/Exporter.kt", "val aClock = MonotonicPts()", "audio output clock instance")
must_contain("export/CompositionRecorder.kt", "private val videoPts = MonotonicPts()", "recorder video output clock")
must_contain("export/CompositionRecorder.kt", "private val audioPtsClock = MonotonicPts()", "recorder audio output clock")
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

# ---- STEP 3 audio pipeline ----
# One format everywhere (mixer/decode/encode/PTS agree — no assumed rates).
must_contain("export/AudioMixer.kt", "object AudioConfig", "single audio format truth")
must_contain("export/AudioMixer.kt", "SAMPLE_RATE", "central sample rate")
must_contain("export/AudioMixer.kt", "enum class AudioSourceState", "per-source states")
must_contain("export/AudioMixer.kt", "TEMPORARILY_EMPTY", "empty != ended")
must_contain("export/AudioMixer.kt", "class AudioMixer", "shared mixer")
must_contain("export/AudioMixer.kt", "fun ptsUs", "sample-count PTS")
# Native byte order on every raw-audio ByteBuffer (BIG_ENDIAN default = distortion).
must_contain("export/AudioDecode.kt", "ByteOrder.nativeOrder()", "decode PCM byte order")
must_contain("export/CompositionRecorder.kt", "ByteOrder.nativeOrder()", "recorder PCM byte order")
must_contain("export/Exporter.kt", "ByteOrder.nativeOrder()", "exporter PCM byte order")
# Recorder: dedicated audio thread + serialized muxer (MediaMuxer is not thread-safe).
must_contain("export/CompositionRecorder.kt", "compo-rec-audio", "dedicated audio thread")
must_contain("export/CompositionRecorder.kt", "muxerLock", "muxer synchronization")
must_contain("export/CompositionRecorder.kt", "ClipAudioSource", "mixer clip sources")
must_contain("export/Exporter.kt", "ClipAudioSource", "exporter mixer clip sources")
# Clip duration truth is decoded frames, not retriever-ms estimates.
must_not_match("export/Exporter.kt", r"durMs\s*\*\s*44100", "no ms-derived clip duration in exporter")
must_not_match("export/CompositionRecorder.kt", r"durMs\s*\*\s*AUDIO_RATE", "no ms-derived clip duration in recorder")


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


def test_audio_pts() -> None:
    # Mirrors AudioConfig.ptsUs: samples * 1_000_000 / 44100 (integer math).
    def pts_us(samples: int) -> int:
        return samples * 1_000_000 // 44100

    if pts_us(0) != 0:
        errors.append("audio PTS of 0 samples must be 0")
    else:
        oks.append("audio PTS(0) = 0")
    if pts_us(44100) != 1_000_000:
        errors.append("audio PTS of 1 second must be 1_000_000 us")
    else:
        oks.append("audio PTS(44100) = 1s")
    # 1024-frame AAC chunks must be strictly increasing with no jumps.
    seq = [pts_us(i * 1024) for i in range(200)]
    if any(seq[i] <= seq[i - 1] for i in range(1, len(seq))):
        errors.append("audio chunk PTS not strictly increasing")
    else:
        oks.append("audio chunk PTS strictly increasing over 200 chunks")
    step = seq[1] - seq[0]
    if not (23_000 <= step <= 23_500):
        errors.append(f"audio chunk step {step} us, want ~23220 us")
    else:
        oks.append(f"audio chunk step = {step} us (~23.2 ms)")


def test_audio_mixer() -> None:
    # Mirrors ClipAudioSource.mixInto + AudioMixer: per-source states, and the
    # two invariants TEMPORARILY_EMPTY != ENDED and ONE-ENDED != GLOBAL-EOS.
    ACTIVE, TEMPTY, ENDED, MUTED = "ACTIVE", "TEMPORARILY_EMPTY", "ENDED", "MUTED"

    class Clip:
        def __init__(self, pcm, volume=1.0, loop=False, muted=False):
            self.pcm = pcm
            self.volume = volume
            self.loop = loop
            self.muted = muted

        def mix_into(self, base, out):
            if self.muted:
                return MUTED
            total = len(self.pcm)
            if total <= 0:
                return ENDED
            produced = False
            past_end = False
            for i in range(len(out)):
                p = base + i
                if p < total:
                    idx = p
                elif self.loop:
                    idx = p % total
                else:
                    idx = -1
                if idx < 0 or idx >= total:
                    past_end = True
                    continue
                produced = True
                if self.volume != 0.0:
                    v = out[i] + int(self.pcm[idx] * self.volume)
                    out[i] = max(-32768, min(32767, v))
            if produced:
                return ACTIVE
            return ENDED if past_end else ACTIVE

    # 1. basic mix: two constant clips sum (with clipping).
    a = Clip([1000] * 44100)
    b = Clip([2000] * 44100)
    out = [0] * 1024
    sa, sb = a.mix_into(0, out), b.mix_into(0, out)
    if sa != ACTIVE or sb != ACTIVE or any(v != 3000 for v in out):
        errors.append("mixer basic sum failed")
    else:
        oks.append("mixer sums two clips sample-by-sample")
    # 2. clipping, not overflow.
    c = Clip([30000] * 44100)
    d = Clip([30000] * 44100)
    out2 = [0] * 8
    c.mix_into(0, out2)
    d.mix_into(0, out2)
    if any(v != 32767 for v in out2):
        errors.append("mixer must clip at 32767")
    else:
        oks.append("mixer clips at int16 range")
    # 3. non-loop clip ends -> ENDED + silence, mixer continues other clips.
    short = Clip([500] * 100, loop=False)
    long = Clip([700] * 44100, loop=False)
    tail = [0] * 16
    st_short = short.mix_into(1000, tail)  # fully past its 100 frames
    st_long = long.mix_into(1000, tail)
    if st_short != ENDED or st_long != ACTIVE or any(v != 700 for v in tail):
        errors.append("one ENDED clip must not stop the mix")
    else:
        oks.append("ENDED clip -> silence, other clips continue (no global EOS)")
    # 4. looping wraps instead of ending.
    looper = Clip([i % 100 for i in range(200)], loop=True)
    wrap = [0] * 8
    st = looper.mix_into(198, wrap)  # frames 198,199,0,1,2,3,4,5
    if st != ACTIVE or wrap != [98, 99, 0, 1, 2, 3, 4, 5]:
        errors.append(f"loop wrap failed: {wrap}")
    else:
        oks.append("looping clip wraps at its end")
    # 5. muted mixes silence but stays addressable (unmuting resumes).
    m = Clip([900] * 44100, muted=True)
    mo = [0] * 8
    if m.mix_into(0, mo) != MUTED or any(v != 0 for v in mo):
        errors.append("muted clip must mix silence")
    else:
        m.muted = False
        if m.mix_into(0, mo) != ACTIVE or any(v != 900 for v in mo):
            errors.append("unmuted clip must resume")
        else:
            oks.append("MUTED <-> ACTIVE without losing the source")
    # 6. partial tail (base inside, end past): ACTIVE, real samples then silence.
    part = Clip([111] * 10, loop=False)
    po = [0] * 8
    if part.mix_into(8, po) != ACTIVE or po != [111, 111, 0, 0, 0, 0, 0, 0]:
        errors.append(f"partial tail failed: {po}")
    else:
        oks.append("partial tail mixes samples then silence, still ACTIVE")


test_pts()
test_yuv_packed_size()
test_bt709_range()
test_audio_pts()
test_audio_mixer()

print(f"{len(oks)} checks passed")
for line in oks:
    print("  OK ", line)
if errors:
    print(f"\n{len(errors)} FAILED")
    for line in errors:
        print("  FAIL", line)
    sys.exit(1)
print("\nPIPELINE STATIC VALIDATION OK")
