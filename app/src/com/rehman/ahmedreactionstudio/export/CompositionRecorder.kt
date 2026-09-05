package com.rehman.ahmedreactionstudio.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.rehman.ahmedreactionstudio.core.Compositor
import com.rehman.ahmedreactionstudio.core.Layer
import com.rehman.ahmedreactionstudio.core.Project
import java.io.File
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One clip whose audio is mixed into the recording. */
class ClipAudio(
    val path: String,
    val volume: Float,
    val loop: Boolean,
    val durMs: Long
)

/** A clip's audio pre-decoded to mono PCM, ready to mix. */
class DecodedClip(val clip: ClipAudio, val pcm: ShortArray)

/**
 * Real-time composition recorder (the RECORD button).
 *
 * Records exactly what the canvas shows — every visible source composited by
 * the SAME [Compositor] the preview and the exporter use — while the master
 * clock runs, at a steady fps. The live camera feeds straight through, so a
 * "local video + camera" setup records as one take.
 *
 * Playability rules (the previous implementation failed these):
 *  - H.264 + AAC in MP4 only (the universally playable baseline)
 *  - encoder input via [YuvWriter]/getInputImage (strided YUV, not packed NV12 guesses)
 *  - one monotonic PTS clock per track; EOS carries lastPts+1, never 0
 *  - muxer.start() only after tracks are added; failures abort instead of
 *    silently writing an empty container
 *  - MediaExtractor validation before reporting success
 *
 * STEP 3 audio architecture:
 *  - ONE consistent format: [AudioConfig] (mono 44100 Hz 16-bit, 1024-frame
 *    chunks). Mic, decode, mixer, AAC and PTS all agree — verified, not
 *    assumed.
 *  - ONE consistent timeline per track: video PTS = wall-clock since take
 *    start; audio PTS = totalSamplesWritten * 1e6 / rate. Both start at 0
 *    (setup time is excluded) and both advance in real time, so they stay
 *    compatible without resampling.
 *  - DEDICATED audio thread: the old code encoded audio on the video thread,
 *    so every expensive YUV conversion delayed the next mic read. Audio then
 *    ran SLOWER than real time (gaps, stutter, track ending early) while the
 *    mic FIFO overran. Audio now paces itself: blocking mic reads when the
 *    mic is live, wall-clock pacing for clip-only takes.
 *  - PER-SOURCE states ([AudioSourceState]): a clip past its end (ENDED) or
 *    a single failed mic read (TEMPORARILY_EMPTY) mixes silence and NEVER
 *    stops the take. Global EOS happens only when the user stops.
 *  - MediaMuxer is NOT thread-safe: every addTrack/start/writeSampleData is
 *    serialized on [muxerLock].
 *
 * Threading: [renderAndSubmit] MUST be called on the main thread (it reads
 * PreviewEngine bitmaps). Video encodes on a private handler thread, audio
 * on its own thread.
 */
class CompositionRecorder(
    private val projectRef: () -> Project,
    private val frameOf: (Layer) -> Bitmap?,
    private val timeMs: () -> Long
) {

    companion object {
        private const val TAG = "AhmedRecorder"
        private val AUDIO_RATE = AudioConfig.SAMPLE_RATE
        private val AUDIO_CHUNK = AudioConfig.CHUNK_FRAMES
    }

    private var codec: MediaCodec? = null
    private var w = 0
    private var h = 0
    private var fps = 30
    private var nv12 = true
    private var videoMime = MediaFormat.MIMETYPE_VIDEO_AVC
    private val queue = ArrayBlockingQueue<Bitmap>(4)
    private val ctx = Compositor.Ctx()
    private val pool = ArrayDeque<Bitmap>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var audioCodec: MediaCodec? = null
    private var micRec: AudioRecord? = null
    private var audioEnabled = false
    private var micEnabledFlag = false
    /** clip mixer sources (audio thread owns mixing; muted flags are volatile for live mute) */
    private val clipSources = ArrayList<ClipAudioSource>()
    /** live mic mute (volatile so the editor can flip it mid-take without touching the mixer) */
    @Volatile var micMuted = false
        private set
    /** audio timeline: total PCM frames queued to the AAC encoder (audio thread only) */
    private var audioSamples = 0L

    private var muxer: MediaMuxer? = null
    private val muxerLock = Any()
    @Volatile private var muxerStarted = false
    @Volatile private var videoTrack = -1
    @Volatile private var audioTrack = -1
    /** true when this take wants an audio track (set in setup, before threads start) */
    @Volatile private var audioWanted = false
    /** true once we decide to mux video-only (AAC format never arrived / audio failed) */
    @Volatile private var audioGaveUp = false
    /** true when the VIDEO path failed fatally (audio aborts too; file is failed) */
    @Volatile private var videoFailed = false

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var audioThread: Thread? = null
    private var startWall = 0L

    @Volatile private var finishing = false
    @Volatile private var discard = false
    @Volatile private var setupFailed = false
    private var onDone: ((File?) -> Unit)? = null

    @Volatile var recording = false
        private set
    var outFile: File? = null
        private set

    /**
     * OUTPUT-ONLY PTS guards (muxer timeline). Encoder INPUT timestamps are
     * passed directly (video: wall-clock, audio: sample-count) because both
     * are already monotonic; feeding inputs through the same clock as outputs
     * was a bug — every input advanced `last`, so every encoder-delayed
     * output got bumped to last+1 and timestamps compressed.
     */
    private val videoPts = MonotonicPts()
    private val audioPtsClock = MonotonicPts()
    @Volatile private var videoSamplesWritten = 0
    @Volatile private var audioSamplesWritten = 0

    /** Start the encoder writing to [outFile]. Returns false only on no video encoder / setup failure. */
    fun start(
        outFile: File, w: Int, h: Int, fps: Int, codecKind: Exporter.Codec,
        audio: List<DecodedClip>, micEnabled: Boolean, onError: (String) -> Unit
    ): Boolean {
        this.w = w; this.h = h; this.fps = fps
        this.outFile = outFile
        this.micEnabledFlag = micEnabled
        // RECORD is the golden "what you see is what you get" path. Always
        // H.264/AAC/MP4 regardless of the export-sheet codec — WebM/HEVC from
        // this real-time path is what produced files that would not play.
        this.videoMime = MediaFormat.MIMETYPE_VIDEO_AVC
        clipSources.clear()
        for (d in audio) {
            if (d.pcm.isEmpty()) continue
            clipSources.add(ClipAudioSource(d.pcm, d.clip.volume, d.clip.loop, muted = false))
        }
        micMuted = false
        audioSamples = 0L
        val (encName, colorFmt) = Exporter.pickEncoder(MediaFormat.MIMETYPE_VIDEO_AVC)
        if (encName.isEmpty() || colorFmt < 0) {
            onError("No H.264 encoder on this device")
            return false
        }
        nv12 = colorFmt != MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        try {
            for (i in 0 until 3) pool.addLast(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888))
        } catch (e: Exception) {
            onError(e.message ?: "Out of memory")
            return false
        }
        val t = HandlerThread("compo-rec")
        t.start()
        thread = t
        handler = Handler(t.looper)
        recording = true
        finishing = false
        discard = false
        setupFailed = false
        videoFailed = false
        audioGaveUp = false
        videoTrack = -1
        audioTrack = -1
        muxerStarted = false
        videoSamplesWritten = 0
        audioSamplesWritten = 0
        val ready = CountDownLatch(1)
        handler!!.post {
            val ok = setup(encName, colorFmt)
            if (!ok) {
                setupFailed = true
                ready.countDown()
                finalize(failed = true)
                return@post
            }
            // STEP 3: the take clock starts AFTER setup. The old code stamped
            // startWall before encoder/muxer setup, so the first video PTS
            // included ~100-500 ms of setup time while audio started at 0 —
            // a permanent A/V offset on every take.
            startWall = SystemClock.elapsedRealtime()
            val wall = startWall
            ready.countDown()
            if (audioWanted) {
                val at = Thread({ audioLoop(wall) }, "compo-rec-audio")
                audioThread = at
                at.start()
            }
            videoLoop(wall)
            try { audioThread?.join(8000L) } catch (_: Exception) { }
            finalize(videoFailed)
        }
        try { ready.await(4, TimeUnit.SECONDS) } catch (_: Exception) { }
        if (setupFailed || codec == null) {
            recording = false
            onError("Could not start the H.264 encoder")
            return false
        }
        return true
    }

    /** Render the current composition and enqueue it. Main thread only. */
    fun renderAndSubmit() {
        if (!recording || setupFailed) return
        val bmp = obtain() ?: return
        val p = projectRef()
        val c = Canvas(bmp)
        Compositor.draw(ctx, c, w, h, p, frameOf, timeMs(), null)
        if (!queue.offer(bmp)) recycle(bmp)
    }

    fun finish(onDone: (File?) -> Unit) {
        if (!recording) { onDone(null); return }
        recording = false
        finishing = true
        this.onDone = onDone
    }

    fun abort() {
        if (!recording && handler == null) return
        recording = false
        discard = true
        finishing = true
    }

    /** Live mic mute for Tests D/E (thread-safe, takes effect on the next chunk). */
    fun setMicMuted(muted: Boolean) {
        micMuted = muted
    }

    /** Live clip mute by start-list index (thread-safe, takes effect on the next chunk). */
    fun setClipMutedAt(index: Int, muted: Boolean) {
        try {
            if (index in clipSources.indices) clipSources[index].muted = muted
        } catch (_: Exception) { }
    }

    private fun setup(encName: String, colorFmt: Int): Boolean {
        return try {
            outFile?.parentFile?.mkdirs()
            val vf = EncoderConfig.videoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, w, h, fps, colorFmt,
                EncoderConfig.Quality.BALANCED, liveRecorder = true, compat = true
            )
            codec = MediaCodec.createByCodecName(encName)
            codec!!.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec!!.start()
            muxer = MediaMuxer(outFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            audioEnabled = clipSources.isNotEmpty() || micEnabledFlag
            if (audioEnabled) setupAudio()
            audioWanted = audioEnabled && audioCodec != null
            true
        } catch (e: Exception) {
            Log.e(TAG, "setup failed", e)
            false
        }
    }

    private fun setupAudio() {
        if (micEnabledFlag) {
            try {
                val min = AudioRecord.getMinBufferSize(
                    AUDIO_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                if (min <= 0) {
                    // Device reports this format unsupported — record the
                    // clips only instead of crashing the take.
                    Log.w(TAG, "mic 44.1k mono unsupported (min=$min); clips-only")
                    micRec = null
                } else {
                    val rec = AudioRecord(
                        MediaRecorder.AudioSource.MIC, AUDIO_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(min, AUDIO_RATE * 2)
                    )
                    if (rec.state != AudioRecord.STATE_INITIALIZED) {
                        try { rec.release() } catch (_: Exception) { }
                        micRec = null
                        Log.w(TAG, "mic not initialized; clips-only")
                    } else {
                        try {
                            rec.startRecording()
                        } catch (e: Exception) {
                            Log.w(TAG, "mic startRecording failed", e)
                        }
                        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                            try { rec.stop() } catch (_: Exception) { }
                            try { rec.release() } catch (_: Exception) { }
                            micRec = null
                            Log.w(TAG, "mic not recording; clips-only")
                        } else {
                            micRec = rec
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "mic setup failed; clips-only", e)
                micRec = null
            }
        }
        if (micRec == null && clipSources.isEmpty()) { audioEnabled = false; return }
        try {
            val af = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_RATE, AudioConfig.CHANNEL_COUNT
            )
            af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            af.setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            af.setInteger(MediaFormat.KEY_CHANNEL_COUNT, AudioConfig.CHANNEL_COUNT)
            audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audioCodec!!.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioCodec!!.start()
        } catch (e: Exception) {
            Log.w(TAG, "AAC encoder unavailable; recording video-only", e)
            audioCodec = null
            audioEnabled = false
        }
    }

    // ------------------------------------------------------------------ video

    private fun videoLoop(startWallLocal: Long) {
        val vc = codec ?: run { videoFailed = true; return }
        val m = muxer ?: run { videoFailed = true; return }
        val vInfo = MediaCodec.BufferInfo()
        var vEosQueued = false
        var vEosDone = false
        var lastVideoInPts = -1L
        var finishDeadline = 0L

        fun tryStartMuxerLocal() = tryStartMuxer(m)

        fun drainVideo(): Boolean {
            while (true) {
                val outIdx = try { vc.dequeueOutputBuffer(vInfo, 2_000) } catch (_: Exception) { return true }
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        var fatal = false
                        synchronized(muxerLock) {
                            // A late second format-change after start() cannot
                            // add a track — skip it instead of crashing.
                            if (!muxerStarted) {
                                try {
                                    videoTrack = m.addTrack(vc.outputFormat)
                                } catch (e: Exception) {
                                    Log.e(TAG, "add video track", e)
                                    videoFailed = true
                                    fatal = true
                                }
                            }
                        }
                        if (fatal) return true
                        tryStartMuxerLocal()
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                    outIdx >= 0 -> {
                        val eos = writeOut(m, videoTrack, vc, outIdx, vInfo, videoPts) { videoSamplesWritten++ }
                        if (eos) return true
                    }
                    else -> return false
                }
            }
        }

        try {
            while (!videoFailed && !vEosDone) {
                val bmp = queue.poll()
                if (bmp != null) {
                    val inIdx = try { vc.dequeueInputBuffer(20_000) } catch (_: Exception) { -1 }
                    if (inIdx >= 0) {
                        if (!bmp.isRecycled) {
                            val bytes = YuvWriter.fillInput(vc, inIdx, bmp, w, h, nv12)
                            if (bytes > 0) {
                                // INPUT PTS passes wall-clock directly (already
                                // monotonic). The output guard (videoPts) only
                                // sees encoder outputs — see field docs.
                                val pts = (SystemClock.elapsedRealtime() - startWallLocal) * 1000L
                                try { vc.queueInputBuffer(inIdx, 0, bytes, pts, 0) } catch (_: Exception) { }
                                lastVideoInPts = pts
                            } else {
                                // YUV conversion failed for this frame: queue an
                                // empty input with an advancing wall-clock PTS
                                // (the dequeued buffer must be queued; a 0-byte
                                // input emits nothing and the player holds the
                                // previous frame). Never a duplicate PTS.
                                val pts = (SystemClock.elapsedRealtime() - startWallLocal) * 1000L
                                try { vc.queueInputBuffer(inIdx, 0, 0, pts, 0) } catch (_: Exception) { }
                                lastVideoInPts = pts
                            }
                        }
                    }
                    recycle(bmp)
                } else if (!finishing) {
                    try { Thread.sleep(4) } catch (_: Exception) { }
                }
                if (drainVideo()) vEosDone = true

                if (finishing) {
                    if (finishDeadline == 0L) finishDeadline = SystemClock.elapsedRealtime() + 6000L
                    if (!vEosQueued && queue.isEmpty()) {
                        val inIdx = try { vc.dequeueInputBuffer(20_000) } catch (_: Exception) { -1 }
                        if (inIdx >= 0) {
                            val base = lastVideoInPts.coerceAtLeast(0L)
                            val pts = base + 33_333L
                            try {
                                vc.queueInputBuffer(inIdx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                vEosQueued = true
                                lastVideoInPts = pts
                            } catch (_: Exception) { }
                        }
                    }
                    if (drainVideo()) vEosDone = true
                    if (SystemClock.elapsedRealtime() > finishDeadline) break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "video loop", e)
            videoFailed = true
        }
    }

    // ------------------------------------------------------------------ audio

    /**
     * Dedicated audio loop: capture mic (blocking reads = hardware pacing),
     * mix clips at the sample-count timeline, encode AAC, drain to muxer.
     *
     * Runs on its own thread so video conversion can never starve it. The
     * loop ends ONLY on finishing (user stop), video failure, or discard —
     * never because one source went empty or ended.
     */
    private fun audioLoop(startWallLocal: Long) {
        val ac = audioCodec ?: return
        val aInfo = MediaCodec.BufferInfo()
        var aEosQueued = false
        var aEosDone = false
        var finishDeadline = 0L
        // Chunk waiting for an encoder input buffer (dequeue can fail under
        // load; retaining it keeps mic + clips sample-aligned instead of
        // dropping mic audio while clip positions stall).
        var pending: ShortArray? = null
        var pendingPts = 0L
        val micDrain = ShortArray(AUDIO_CHUNK)
        // If the AAC encoder never produces a format, do not hold the muxer
        // hostage — drop audio after 800 ms and mux video-only. An MP4 with
        // video and no audio plays; an MP4 that never started does not.
        val audioGiveUpAt = SystemClock.elapsedRealtime() + 800L

        fun drainAudio(): Boolean {
            while (true) {
                val outIdx = try { ac.dequeueOutputBuffer(aInfo, 2_000) } catch (_: Exception) { return true }
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        synchronized(muxerLock) {
                            if (muxerStarted) {
                                // Video already started without us (gave up):
                                // stay video-only instead of crashing addTrack.
                                audioGaveUp = true
                            } else {
                                try {
                                    audioTrack = muxer!!.addTrack(ac.outputFormat)
                                } catch (e: Exception) {
                                    Log.w(TAG, "add audio track; muxing video-only", e)
                                    audioGaveUp = true
                                }
                            }
                        }
                        muxer?.let { tryStartMuxer(it) }
                        if (audioGaveUp) return true
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                    outIdx >= 0 -> {
                        val eos = writeOut(muxer!!, audioTrack, ac, outIdx, aInfo, audioPtsClock) {
                            audioSamplesWritten++
                        }
                        if (eos) return true
                    }
                    else -> return false
                }
            }
        }

        try {
            while (!aEosDone) {
                if (videoFailed) break
                if (!audioGaveUp && audioTrack < 0 &&
                    SystemClock.elapsedRealtime() > audioGiveUpAt) {
                    audioGaveUp = true
                    Log.w(TAG, "AAC format never arrived; muxing video-only")
                    muxer?.let { tryStartMuxer(it) }
                    break
                }
                if (audioGaveUp) break

                if (finishing || discard) {
                    if (finishDeadline == 0L) finishDeadline = SystemClock.elapsedRealtime() + 6000L
                    // Flush any pending chunk as a regular frame first so no
                    // captured audio is lost at the tail.
                    val p = pending
                    if (p != null) {
                        if (queueAudioInput(ac, p, pendingPts)) {
                            pending = null
                            audioSamples += AUDIO_CHUNK
                        } else {
                            if (drainAudio()) aEosDone = true
                            if (SystemClock.elapsedRealtime() > finishDeadline) break
                            continue
                        }
                    }
                    if (!aEosQueued) {
                        val inIdx = try { ac.dequeueInputBuffer(20_000) } catch (_: Exception) { -1 }
                        if (inIdx >= 0) {
                            // EOS carries the NEXT frame time on the sample
                            // timeline (last input + 1024 frames), never 0 and
                            // never via the output clock.
                            val pts = AudioConfig.ptsUs(audioSamples)
                            try {
                                ac.queueInputBuffer(inIdx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                aEosQueued = true
                            } catch (_: Exception) { }
                        }
                    }
                    if (drainAudio()) aEosDone = true
                    if (SystemClock.elapsedRealtime() > finishDeadline) break
                    continue
                }

                // ---- regular chunk ----
                if (pending == null) {
                    val hasMic = micRec != null
                    if (!hasMic) {
                        // Clips-only: pace generation to the wall clock so the
                        // audio track matches the video duration. Fixed sleeps
                        // drift (encode overhead accumulates); waiting until
                        // the timeline is due self-corrects.
                        paceToWallClock(startWallLocal)
                    }
                    val chunk = ShortArray(AUDIO_CHUNK)
                    if (hasMic) {
                        val paced = readMicInto(chunk, micDrain)
                        if (!paced) {
                            // Mic read failed instantly (no hardware pacing for
                            // this chunk): fall back to wall-clock pacing so a
                            // dead mic degrades to paced silence instead of a
                            // busy loop that timestamps thousands of chunks
                            // ahead of the video.
                            paceToWallClock(startWallLocal)
                        }
                    }
                    // Mix clips at the sample timeline. ENDED/MUTED clips
                    // contribute silence; TEMPORARILY_EMPTY never appears for
                    // pre-decoded clips. One source's state never ends the mix.
                    try {
                        for (c in clipSources) c.mixInto(audioSamples, chunk)
                    } catch (_: Exception) { }
                    pending = chunk
                    pendingPts = AudioConfig.ptsUs(audioSamples)
                }
                if (queueAudioInput(ac, pending!!, pendingPts)) {
                    pending = null
                    audioSamples += AUDIO_CHUNK
                }
                if (drainAudio()) aEosDone = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "audio loop", e)
            // Audio must never fail the take: video-only is playable,
            // a failed take is not.
            audioGaveUp = true
            muxer?.let { try { tryStartMuxer(it) } catch (_: Exception) { } }
        }
    }

    /**
     * Fill [out] with mic audio (or silence when muted/failed).
     *
     * @return true when the read itself paced this chunk (blocking hardware
     * read delivered samples, or drained while muted). False means the read
     * failed instantly and the caller must wall-clock pace instead.
     */
    private fun readMicInto(out: ShortArray, drain: ShortArray): Boolean {
        val rec = micRec ?: return false
        if (micMuted) {
            // Muted ≠ stopped: keep draining the hardware FIFO so unmuting
            // resumes with fresh audio instead of a burst of stale buffered
            // samples, but mix silence.
            val r = try { rec.read(drain, 0, drain.size) } catch (_: Exception) { -1 }
            java.util.Arrays.fill(out, 0)
            return r > 0
        }
        val r = try { rec.read(out, 0, out.size) } catch (_: Exception) { -1 }
        if (r > 0) {
            // TEMPORARILY_EMPTY would only apply to the missing tail; a
            // partial read still contributes real samples + silence.
            if (r < out.size) java.util.Arrays.fill(out, r, out.size, 0.toShort())
            return true
        }
        // Single failed read = TEMPORARILY_EMPTY (silence this chunk, retry
        // next chunk). Recording continues; clips are unaffected.
        java.util.Arrays.fill(out, 0)
        return false
    }

    /** Sleep until the wall clock reaches the audio timeline (clips-only pacing). */
    private fun paceToWallClock(startWallLocal: Long) {
        val targetMs = audioSamples * 1000L / AUDIO_RATE
        val elapsed = SystemClock.elapsedRealtime() - startWallLocal
        val wait = targetMs - elapsed
        if (wait > 0) {
            try { Thread.sleep(wait.coerceAtMost(250L)) } catch (_: Exception) { }
        }
    }

    /**
     * Queue exactly one 1024-frame mono 16-bit chunk. Returns false when no
     * encoder input buffer was available (caller retains the chunk and
     * retries — the timeline does NOT advance on failure).
     */
    private fun queueAudioInput(ac: MediaCodec, chunk: ShortArray, ptsUs: Long): Boolean {
        val inIdx = try { ac.dequeueInputBuffer(10_000) } catch (_: Exception) { -1 }
        if (inIdx < 0) return false
        val buf = try { ac.getInputBuffer(inIdx) } catch (_: Exception) { null } ?: return false
        if (buf.capacity() < AudioConfig.CHUNK_BYTES) return false
        return try {
            buf.clear()
            // STEP 3: encoder PCM is NATIVE (little-endian) order.
            buf.order(ByteOrder.nativeOrder())
            buf.asShortBuffer().put(chunk, 0, AUDIO_CHUNK.coerceAtMost(chunk.size))
            // INPUT PTS passes the sample-count timeline directly (already
            // monotonic). Never through the output guard.
            ac.queueInputBuffer(inIdx, 0, AudioConfig.CHUNK_BYTES, ptsUs, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun tryStartMuxer(m: MediaMuxer) {
        synchronized(muxerLock) {
            if (muxerStarted) return
            if (videoTrack < 0) return
            if (audioWanted && audioTrack < 0 && !audioGaveUp) return
            try {
                m.start()
                muxerStarted = true
            } catch (e: Exception) {
                Log.e(TAG, "muxer.start failed", e)
                videoFailed = true
            }
        }
    }

    /**
     * Drain one encoder output buffer into the muxer. Returns true on EOS.
     * Codec-config buffers are never muxed (they live in outputFormat).
     * ByteBuffer position/limit is set from BufferInfo — some devices return
     * a buffer whose position is 0 with data at info.offset; others already
     * slice it. Setting both is the portable pattern.
     */
    private fun writeOut(
        m: MediaMuxer,
        track: Int,
        c: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo,
        clock: MonotonicPts,
        onSample: (() -> Unit)? = null
    ): Boolean {
        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
        try {
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                c.releaseOutputBuffer(index, false)
                return false
            }
            if (track >= 0 && muxerStarted && info.size > 0) {
                val buf = c.getOutputBuffer(index)
                if (buf != null) {
                    info.presentationTimeUs = clock.next(info.presentationTimeUs)
                    val end = info.offset + info.size
                    if (end <= buf.capacity()) {
                        buf.position(info.offset)
                        buf.limit(end)
                        synchronized(muxerLock) {
                            if (muxerStarted) m.writeSampleData(track, buf, info)
                        }
                        onSample?.invoke()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "writeSampleData", e)
        } finally {
            try { c.releaseOutputBuffer(index, false) } catch (_: Exception) { }
        }
        return eos
    }

    private fun finalize(failed: Boolean) {
        try { codec?.stop() } catch (_: Exception) { }
        try { codec?.release() } catch (_: Exception) { }
        codec = null
        try { audioCodec?.stop() } catch (_: Exception) { }
        try { audioCodec?.release() } catch (_: Exception) { }
        audioCodec = null
        try { micRec?.stop() } catch (_: Exception) { }
        try { micRec?.release() } catch (_: Exception) { }
        micRec = null
        var muxOk = false
        try {
            synchronized(muxerLock) {
                if (muxerStarted) {
                    muxer?.stop()
                    muxOk = true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "muxer.stop", e)
            muxOk = false
        }
        try { muxer?.release() } catch (_: Exception) { }
        muxer = null
        muxerStarted = false
        if (discard) try { outFile?.delete() } catch (_: Exception) { }
        var b = queue.poll()
        while (b != null) { recycle(b); b = queue.poll() }
        recyclePool()
        clipSources.clear()

        val file = outFile
        val playable = !failed && !discard && muxOk && file != null &&
            videoSamplesWritten > 0 &&
            ExportValidator.validate(file.absolutePath, expectAudio = false).ok
        if (!playable && file != null && !discard) {
            Log.e(TAG, "recording not playable (vSamples=$videoSamplesWritten " +
                "aSamples=$audioSamplesWritten muxOk=$muxOk failed=$failed)")
        }
        val result = if (playable) file else null
        val cb = onDone
        onDone = null
        recording = false
        try { thread?.quitSafely() } catch (_: Exception) { }
        thread = null
        handler = null
        audioThread = null
        mainHandler.post { cb?.invoke(result) }
    }

    private fun obtain(): Bitmap? = synchronized(pool) {
        if (pool.isEmpty()) null else pool.removeFirst()
    }

    private fun recycle(b: Bitmap) {
        synchronized(pool) {
            if (pool.size < 3) pool.addLast(b) else b.recycle()
        }
    }

    private fun recyclePool() {
        synchronized(pool) {
            while (pool.isNotEmpty()) try { pool.removeFirst().recycle() } catch (_: Exception) { }
        }
    }
}
