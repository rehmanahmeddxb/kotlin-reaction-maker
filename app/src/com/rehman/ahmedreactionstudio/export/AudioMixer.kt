package com.rehman.ahmedreactionstudio.export

/**
 * STEP 3 — single source of truth for the entire audio pipeline.
 *
 * Every audio component (microphone capture, clip decode, mixer, AAC encoder,
 * PTS generator) MUST agree on these values. Previously the rate/chunk size
 * were hard-coded in three places (44100 in AudioDecode, CompositionRecorder
 * and Exporter); any drift between them produces exactly the reported
 * symptoms: accelerated, stuttering or truncated audio.
 *
 * Format (all stages):
 *  - sample rate : 44100 Hz (the one rate guaranteed on all devices)
 *  - channels    : 1 (mono). Stereo sources are downmixed at decode time.
 *  - PCM         : 16-bit signed little-endian (native order), interleaved.
 *  - frames      : 1024 PCM frames per AAC input buffer (2048 bytes).
 */
object AudioConfig {
    const val SAMPLE_RATE = 44100
    const val CHANNEL_COUNT = 1
    const val CHUNK_FRAMES = 1024
    const val BYTES_PER_SAMPLE = 2
    const val CHUNK_BYTES = CHUNK_FRAMES * BYTES_PER_SAMPLE

    /** Sample-count based PTS: monotonic, continuous, drift-free. */
    fun ptsUs(totalFramesWritten: Long): Long =
        totalFramesWritten * 1_000_000L / SAMPLE_RATE

    fun framesForMs(ms: Long): Long = ms * SAMPLE_RATE / 1000L
}

/**
 * Per-source lifecycle. The critical invariants:
 *  - TEMPORARILY_EMPTY ≠ ENDED (a single empty read must not end the source)
 *  - ONE SOURCE ENDED ≠ GLOBAL EOS (other sources and the muxer continue)
 *  - GLOBAL EOS happens only when the export/record timeline itself finishes
 *    (user stops the take, or the offline timeline reaches its end).
 */
enum class AudioSourceState {
    /** contributing samples this chunk */
    ACTIVE,
    /** no samples right now (e.g. one failed mic read); retry next chunk */
    TEMPORARILY_EMPTY,
    /** permanently finished (non-looping clip past its end); mixes silence */
    ENDED,
    /** user-muted; mixes silence */
    MUTED
}

/**
 * One pre-decoded clip, ready to mix.
 *
 * Duration truth is [pcm.size] (actual decoded frames), NOT durMs-derived
 * estimates: MediaMetadataRetriever durations routinely differ from the
 * decoded frame count by padding/rounding, and using the estimate truncates
 * tails or reads past the array.
 */
class ClipAudioSource(
    val pcm: ShortArray,
    @Volatile var volume: Float,
    @Volatile var loop: Boolean,
    @Volatile var muted: Boolean = false
) {
    val totalFrames: Long get() = pcm.size.toLong()

    @Volatile
    var state: AudioSourceState =
        if (pcm.isEmpty()) AudioSourceState.ENDED else AudioSourceState.ACTIVE
        private set

    /**
     * Add this clip's [len] frames starting at timeline position [baseFrames]
     * into [out] at [offset]. Missing frames (past end, muted, empty) are
     * silence — the mixer NEVER stops for one source.
     *
     * @return this source's state after the chunk.
     */
    fun mixInto(
        baseFrames: Long,
        out: ShortArray,
        offset: Int = 0,
        len: Int = out.size - offset
    ): AudioSourceState {
        if (muted) {
            state = AudioSourceState.MUTED
            return state
        }
        val data = pcm
        val total = data.size.toLong()
        if (total <= 0L) {
            state = AudioSourceState.ENDED
            return state
        }
        var produced = false
        var pastEnd = false
        val vol = volume
        val end = (offset + len).coerceAtMost(out.size)
        var i = offset
        while (i < end) {
            val p = baseFrames + (i - offset)
            val idx = if (p < total) {
                p.toInt()
            } else if (loop) {
                // total > 0 here, so the modulo is safe.
                (p % total).toInt()
            } else {
                -1
            }
            if (idx < 0 || idx >= data.size) {
                pastEnd = true
                i++
                continue
            }
            produced = true
            if (vol != 0f) {
                val v = out[i].toInt() + (data[idx] * vol).toInt()
                out[i] = v.coerceIn(-32768, 32767).toShort()
            }
            i++
        }
        state = when {
            produced -> AudioSourceState.ACTIVE
            pastEnd -> AudioSourceState.ENDED
            else -> AudioSourceState.ACTIVE
        }
        return state
    }
}

/**
 * The mixer: sums every clip into one continuous mono PCM stream driven by
 * the export/record timeline ([baseFrames] = total frames written so far).
 *
 * There is deliberately NO global-EOS concept here. A clip returning ENDED
 * only means "I contribute silence from now on". The caller (recorder /
 * exporter) decides global EOS from the timeline alone:
 *  - recorder: user pressed stop (finishing flag)
 *  - exporter: baseFrames reached totalAudioSamples
 */
class AudioMixer(val clips: List<ClipAudioSource>) {

    /** Mix all clips at [baseFrames] into [out]. Returns per-source states. */
    fun mixClips(baseFrames: Long, out: ShortArray): List<AudioSourceState> {
        if (clips.isEmpty()) return emptyList()
        val states = ArrayList<AudioSourceState>(clips.size)
        for (c in clips) {
            try {
                states.add(c.mixInto(baseFrames, out))
            } catch (_: Exception) {
                // One bad source must never kill the mix.
                states.add(AudioSourceState.TEMPORARILY_EMPTY)
            }
        }
        return states
    }
}
