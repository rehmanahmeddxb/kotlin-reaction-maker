package com.rehman.ahmedreactionstudio.export

import kotlin.math.abs
import kotlin.math.floor

/**
 * Pure-Kotlin audio arithmetic shared by the real-time recorder
 * ([CompositionRecorder]) and the offline [Exporter].
 *
 * Deliberately free of any Android import so it can be compiled and run on a
 * plain JVM (`tools/audio-math-test/`) — the sample clock, the resampler, the
 * clip cursor and the limiter are the parts of the audio pipeline whose bugs
 * were "sped-up", "stuttering" and "stops after a few seconds", and they are
 * the parts a sandbox without a device CAN verify.
 *
 * Conventions used everywhere in the pipeline:
 *  - PCM is mono, 16-bit signed, [RATE] Hz;
 *  - one AAC-LC frame is [FRAME] samples (≈ 23.22 ms);
 *  - presentation time is derived ONLY from the count of samples encoded so
 *    far ([samplesToUs]); never from wall-clock or loop counters.
 */
object AudioMath {
    const val RATE = 44100
    const val FRAME = 1024
    const val BYTES_PER_SAMPLE = 2

    /** sample count → presentation microseconds (exact integer math, monotonic by construction) */
    fun samplesToUs(samples: Long, rate: Int = RATE): Long = samples * 1_000_000L / rate

    /** microseconds → sample count (floor) */
    fun usToSamples(us: Long, rate: Int = RATE): Long = us * rate / 1_000_000L

    /** byte count of interleaved 16-bit PCM → sample frames */
    fun bytesToFrames(bytes: Int, channels: Int = 1): Int = bytes / (BYTES_PER_SAMPLE * channels)

    /** sample frames of 16-bit PCM → bytes */
    fun framesToBytes(frames: Int, channels: Int = 1): Int = frames * BYTES_PER_SAMPLE * channels

    /** milliseconds → samples at [rate] */
    fun msToSamples(ms: Long, rate: Int = RATE): Long = ms * rate / 1000L
}

/**
 * Linear-interpolation resampler with a persistent fractional phase, used when
 * a microphone will only open at 48 kHz. Block boundaries are seamless: the
 * last input sample of the previous block is kept so the interpolation never
 * restarts (restarting is what produces a click every 20 ms).
 */
class Resampler(from: Int, to: Int) {
    private var last: Short = 0
    /** position of the next output sample inside the current input block, in source samples */
    private var phase = 0.0
    private val ratio = from.toDouble() / to
    private var primed = false

    fun process(input: ShortArray, n: Int): ShortArray {
        if (n <= 0) return ShortArray(0)
        val out = ShortArray((n / ratio).toInt() + 2)
        var k = 0
        // before the first block there is no previous sample: start at 0
        if (!primed) { phase = 0.0; primed = true }
        var pos = phase
        // output positions in [-1, n-1) can be interpolated from (last, input)
        while (pos < n - 1 && k < out.size) {
            val i = floor(pos).toInt()
            val frac = pos - i
            val a = if (i < 0) last.toInt() else input[i].toInt()
            val b = input[(i + 1).coerceAtMost(n - 1)].toInt()
            out[k++] = (a + (b - a) * frac).toInt().coerceIn(-32768, 32767).toShort()
            pos += ratio
        }
        // carry the phase into the next block, measured from ITS first sample
        phase = pos - n
        last = input[n - 1]
        return if (k == out.size) out else out.copyOf(k)
    }
}

/**
 * Read cursor over one decoded clip on the COMPOSITION timeline.
 *
 * - `speed` scales the read step (2× speed reads two source samples per output sample);
 * - the clip wraps at its media duration when looping (matching the preview,
 *   which wraps at `durMs`, not at the decoded PCM length), otherwise it holds
 *   silent after the end and reports [ended];
 * - a muted clip still advances (pass vol = 0) so un-muting resumes in sync.
 */
class ClipCursor(val pcm: ShortArray, durMs: Long, val loop: Boolean, speed: Float = 1f) {
    val step: Double = if (speed > 0.05f) speed.toDouble() else 1.0
    val durSamples: Long =
        (if (durMs > 0) AudioMath.msToSamples(durMs) else pcm.size.toLong()).coerceAtLeast(1L)
    var pos = 0.0
    var ended = false

    /** offline exporter: jump to composition sample [compSamples] (loop wrap / end hold applied) */
    fun seekComposition(compSamples: Long) {
        var p = compSamples * step
        if (loop) p %= durSamples.toDouble()
        pos = p
        ended = !loop && p >= durSamples
    }

    /** jump to a media position in milliseconds (what the preview clock reports) */
    fun seekMediaMs(ms: Long) {
        var p = AudioMath.msToSamples(ms).toDouble()
        if (loop) p %= durSamples.toDouble()
        pos = p.coerceAtLeast(0.0)
        ended = !loop && p >= durSamples
    }

    /**
     * Drift guard for the live recorder: if the cursor is further than
     * [toleranceSamples] from [expectedPos] (loop-aware), re-anchor it.
     * @return true when a jump happened
     */
    fun resyncIfDrifted(expectedPos: Double, toleranceSamples: Double): Boolean {
        var e = expectedPos
        if (loop) e %= durSamples.toDouble()
        var d = pos - e
        if (loop) {
            if (d > durSamples / 2.0) d -= durSamples
            if (d < -durSamples / 2.0) d += durSamples
        }
        if (abs(d) > toleranceSamples) {
            pos = e.coerceAtLeast(0.0)
            ended = !loop && pos >= durSamples
            return true
        }
        return false
    }

    /** add samples [from, to) of the current frame into [mix] at [vol]; advances the cursor */
    fun mixInto(mix: FloatArray, from: Int, to: Int, vol: Float) {
        if (ended) return
        val len = pcm.size
        var p = pos
        val audible = vol > 0.0005f
        for (i in from until to) {
            if (p >= durSamples) {
                if (loop) p -= durSamples else { ended = true; break }
            }
            val idx = p.toInt()
            if (audible && idx + 1 < len) {
                val frac = (p - idx).toFloat()
                val a = pcm[idx].toFloat()
                val b = pcm[idx + 1].toFloat()
                mix[i] += (a + (b - a) * frac) * vol
            }
            p += step
        }
        pos = p
    }
}

/**
 * Peak limiter for the mix bus: instant attack (a frame can never clip), slow
 * release (no pumping on speech). Sums of the microphone and several clips at
 * unity gain would otherwise hard-clip into distortion — the "unclear" audio.
 */
class Limiter(private val ceiling: Float = 32000f, private val release: Float = 0.08f) {
    var gain = 1f
        private set
    var engaged = 0
        private set

    /** write the limited [mix] (first [n] samples) into [out] as 16-bit PCM */
    fun apply(mix: FloatArray, n: Int, out: ShortArray) {
        var peak = 0f
        for (i in 0 until n) { val a = abs(mix[i]); if (a > peak) peak = a }
        val need = if (peak > ceiling) ceiling / peak else 1f
        if (need < gain) { gain = need; engaged++ }
        else gain += (1f - gain) * release
        val g = gain
        for (i in 0 until n) out[i] = (mix[i] * g).toInt().coerceIn(-32768, 32767).toShort()
    }
}
