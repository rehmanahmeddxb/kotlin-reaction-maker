package com.rehman.ahmedreactionstudio.export

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * JVM tests for the device-independent half of the audio pipeline.
 * Run with tools/audio-math-test/run.sh (compiles AudioMath.kt + this file
 * with the same kotlinc the APK build uses; no Android needed).
 */
object AudioMathTest {
    private var failures = 0
    private var passes = 0

    private fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) { passes++; println("  OK   $name") }
        else { failures++; println("  FAIL $name $detail") }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        clockTests()
        conversionTests()
        resamplerTests()
        cursorTests()
        limiterTests()
        driftGuardTests()
        println()
        println("$passes passed, $failures failed")
        if (failures > 0) System.exit(1)
    }

    // ---- 1. the master clock: PTS from samples, strictly monotonic, no drift ----
    private fun clockTests() {
        println("sample clock")
        var samples = 0L
        var last = -1L
        var strictly = true
        // 10 minutes of 1024-sample frames
        val frames = 10 * 60 * AudioMath.RATE / AudioMath.FRAME
        for (i in 0 until frames) {
            val pts = AudioMath.samplesToUs(samples)
            if (pts <= last) strictly = false
            last = pts
            samples += AudioMath.FRAME
        }
        check("PTS strictly increasing over ${frames} frames", strictly)
        // 10 minutes of samples must map to 10 minutes ± one frame
        val tenMinUs = 600_000_000L
        val err = abs(AudioMath.samplesToUs(samples) - tenMinUs)
        check("10 min of frames = 10 min ± 1 frame (err ${err} µs)", err <= 23_220L)
        // integer math: no cumulative rounding drift vs the exact value
        val exact = samples * 1_000_000.0 / AudioMath.RATE
        check("no rounding drift vs exact (${abs(exact - AudioMath.samplesToUs(samples))} µs)",
            abs(exact - AudioMath.samplesToUs(samples)) < 1.0)
        // the old bug: PTS advanced only when a chunk was accepted. Simulate 5 % dropped
        // chunks with the OLD rule (skip sample advance) vs the NEW rule (never drop).
        var oldSamples = 0L; var newSamples = 0L
        for (i in 0 until frames) { if (i % 20 != 0) oldSamples += AudioMath.FRAME; newSamples += AudioMath.FRAME }
        val oldDur = AudioMath.samplesToUs(oldSamples); val newDur = AudioMath.samplesToUs(newSamples)
        check("old rule would shorten the track by ${(tenMinUs - oldDur) / 1000} ms (= sped-up playback); new rule 0 ms",
            oldDur < tenMinUs - 20_000_000L && newDur >= tenMinUs - 23_220L)
    }

    // ---- 2. bytes / samples / µs conversions ----
    private fun conversionTests() {
        println("unit conversions")
        check("1024 mono frames = 2048 bytes", AudioMath.framesToBytes(1024) == 2048)
        check("2048 bytes mono = 1024 frames", AudioMath.bytesToFrames(2048) == 1024)
        check("4096 bytes stereo = 1024 frames", AudioMath.bytesToFrames(4096, 2) == 1024)
        check("1 s = 44100 samples", AudioMath.msToSamples(1000) == 44100L)
        check("44100 samples = 1 000 000 µs", AudioMath.samplesToUs(44100) == 1_000_000L)
        check("1 000 000 µs = 44100 samples", AudioMath.usToSamples(1_000_000) == 44100L)
        check("1024 samples = 23219 µs", AudioMath.samplesToUs(1024) == 23219L)
        check("48k: 1024 samples = 21333 µs", AudioMath.samplesToUs(1024, 48000) == 21333L)
    }

    // ---- 3. resampler: 48 k → 44.1 k keeps pitch and length, no block-boundary clicks ----
    private fun resamplerTests() {
        println("resampler 48000 → 44100")
        val from = 48000; val to = 44100
        val rs = Resampler(from, to)
        val seconds = 2.0
        val n = (from * seconds).toInt()
        val f = 1000.0
        val src = ShortArray(n) { (sin(2 * PI * f * it / from) * 10000).toInt().toShort() }
        // feed in 20 ms blocks exactly like MicThread does
        val block = from / 50
        val out = ArrayList<Short>()
        var off = 0
        while (off < n) {
            val len = minOf(block, n - off)
            val chunk = src.copyOfRange(off, off + len)
            for (v in rs.process(chunk, len)) out.add(v)
            off += len
        }
        val expected = (to * seconds).toInt()
        check("output length ${out.size} ≈ $expected (±2)", abs(out.size - expected) <= 2)
        // pitch: count zero crossings ≈ 2 * f * seconds
        var zc = 0
        for (i in 1 until out.size) if ((out[i - 1] < 0) != (out[i] < 0)) zc++
        check("zero crossings $zc ≈ ${(2 * f * seconds).toInt()} (pitch preserved)", abs(zc - 2 * f * seconds) <= 4)
        // continuity: max sample-to-sample jump must be no larger than the sine's own slope + tolerance
        val maxSlope = 2 * PI * f / to * 10000 * 1.05 + 2
        var maxJump = 0
        for (i in 1 until out.size) maxJump = maxOf(maxJump, abs(out[i] - out[i - 1]))
        check("no clicks at block boundaries (max jump $maxJump ≤ ${maxSlope.toInt()})", maxJump <= maxSlope)
    }

    // ---- 4. clip cursor: loop wrap at durMs, end hold, speed, pause semantics ----
    private fun cursorTests() {
        println("clip cursor")
        val rate = AudioMath.RATE
        // 1 s clip whose PCM is 1.2 s long (decoder padding): must wrap at 1 s like the preview
        val pcm = ShortArray((rate * 1.2).toInt()) { if (it < rate) 1000 else 30000 }
        val loop = ClipCursor(pcm, 1000, loop = true)
        val mix = FloatArray(AudioMath.FRAME)
        var sawPadding = false
        var frames = 0
        while (frames < rate * 3 / AudioMath.FRAME) {
            java.util.Arrays.fill(mix, 0f)
            loop.mixInto(mix, 0, AudioMath.FRAME, 1f)
            for (v in mix) if (v > 20000f) sawPadding = true
            frames++
        }
        check("looping clip wraps at durMs, never reads decoder padding", !sawPadding && !loop.ended)

        val once = ClipCursor(pcm, 1000, loop = false)
        var nonZeroAfterEnd = false
        var total = 0
        frames = 0
        while (frames < rate * 3 / AudioMath.FRAME) {
            java.util.Arrays.fill(mix, 0f)
            once.mixInto(mix, 0, AudioMath.FRAME, 1f)
            if (frames * AudioMath.FRAME > rate + AudioMath.FRAME) for (v in mix) if (v != 0f) nonZeroAfterEnd = true
            for (v in mix) if (v != 0f) total++
            frames++
        }
        check("non-looping clip goes silent after durMs and reports ended", once.ended && !nonZeroAfterEnd)
        check("…and produced ≈ 1 s of audio ($total samples)", abs(total - rate) <= AudioMath.FRAME)

        // 2× speed consumes the source twice as fast
        val fast = ClipCursor(pcm, 1000, loop = false, speed = 2f)
        var n2 = 0
        frames = 0
        while (!fast.ended && frames < 1000) {
            java.util.Arrays.fill(mix, 0f)
            fast.mixInto(mix, 0, AudioMath.FRAME, 1f)
            for (v in mix) if (v != 0f) n2++
            frames++
        }
        check("2× speed: 1 s clip lasts ≈ 0.5 s ($n2 samples)", abs(n2 - rate / 2) <= AudioMath.FRAME)

        // pause: the recorder skips mixInto for a paused clip, so the cursor must not move
        val paused = ClipCursor(pcm, 1000, loop = false)
        paused.mixInto(mix, 0, AudioMath.FRAME, 1f)
        val p1 = paused.pos
        // (simulated pause = no calls) then resume
        paused.mixInto(mix, 0, AudioMath.FRAME, 1f)
        check("cursor advances exactly one frame per mixed frame", abs(paused.pos - p1 - AudioMath.FRAME) < 1e-9)

        // muted clip (vol 0) still advances so un-mute is in sync
        val muted = ClipCursor(pcm, 1000, loop = false)
        java.util.Arrays.fill(mix, 0f)
        muted.mixInto(mix, 0, AudioMath.FRAME, 0f)
        check("muted clip advances silently", muted.pos == AudioMath.FRAME.toDouble() && mix.all { it == 0f })

        // partial frame start (composition starts mid-frame): only [from, to) is written
        val part = ClipCursor(pcm, 1000, loop = false)
        java.util.Arrays.fill(mix, 0f)
        part.mixInto(mix, 512, AudioMath.FRAME, 1f)
        check("mid-frame start writes only the tail of the frame",
            mix.take(512).all { it == 0f } && mix.drop(512).all { it != 0f } && part.pos == 512.0)

        // offline exporter path: seekComposition is deterministic and loop-aware
        val ex = ClipCursor(pcm, 1000, loop = true)
        ex.seekComposition(rate * 2L + 100)
        check("seekComposition wraps loops (pos ${ex.pos})", ex.pos == 100.0)
        val exEnd = ClipCursor(pcm, 1000, loop = false)
        exEnd.seekComposition(rate * 2L)
        check("seekComposition past the end of a non-loop clip = ended", exEnd.ended)
    }

    // ---- 5. limiter: never clips, transparent below the ceiling ----
    private fun limiterTests() {
        println("limiter")
        val lim = Limiter()
        val n = AudioMath.FRAME
        val mix = FloatArray(n) { 12000f * sin(2 * PI * 440 * it / AudioMath.RATE).toFloat() }
        val out = ShortArray(n)
        lim.apply(mix, n, out)
        var maxErr = 0
        for (i in 0 until n) maxErr = maxOf(maxErr, abs(out[i] - mix[i].toInt()))
        check("quiet frame passes unchanged (max err $maxErr)", maxErr <= 1 && lim.engaged == 0)
        // mic + two loud clips at unity: 3 × 20000 = 60000 → must not wrap or clip
        val loud = FloatArray(n) { 60000f * sin(2 * PI * 440 * it / AudioMath.RATE).toFloat() }
        lim.apply(loud, n, out)
        val peak = out.maxOf { abs(it.toInt()) }
        check("60000-peak mix limited to ≤ 32000 (peak $peak), engaged", peak <= 32000 && lim.engaged == 1)
        // shape preserved (no hard clipping = still a scaled sine, correlation ≈ 1)
        var dot = 0.0; var a2 = 0.0; var b2 = 0.0
        for (i in 0 until n) { dot += out[i] * loud[i].toDouble(); a2 += out[i] * out[i].toDouble(); b2 += loud[i] * loud[i].toDouble() }
        val corr = dot / Math.sqrt(a2 * b2)
        check("waveform shape preserved (corr ${"%.4f".format(corr)})", corr > 0.999)
        // release: after silence the gain recovers towards 1
        val silent = FloatArray(n)
        repeat(100) { lim.apply(silent, n, out) }
        check("gain releases back to ~1 (${"%.3f".format(lim.gain)})", lim.gain > 0.99f)
    }

    // ---- 6. drift guard: small drift ignored, big drift re-anchored, loop-aware ----
    private fun driftGuardTests() {
        println("drift guard")
        val rate = AudioMath.RATE
        val pcm = ShortArray(rate * 2) { 1 }
        val c = ClipCursor(pcm, 2000, loop = true)
        c.pos = 10000.0
        check("50 ms drift is left alone", !c.resyncIfDrifted(10000.0 + rate * 0.05, rate * 0.12) && c.pos == 10000.0)
        check("500 ms drift re-anchors", c.resyncIfDrifted(10000.0 + rate * 0.5, rate * 0.12) && abs(c.pos - (10000.0 + rate * 0.5)) < 1)
        // loop-aware: pos just before the wrap vs expected just after is a tiny drift, not a jump
        c.pos = rate * 2.0 - 100
        check("wrap-around distance is loop-aware", !c.resyncIfDrifted(50.0, rate * 0.12))
        // the picture is the reference: a seek in the preview moves the audio too
        val s = ClipCursor(pcm, 2000, loop = false)
        s.seekMediaMs(1500)
        check("seekMediaMs lands on the media position", s.pos == rate * 1.5)
    }
}
