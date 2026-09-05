package com.rehman.ahmedreactionstudio.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
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
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs
import kotlin.math.min

/** One clip whose audio is mixed into the recording. */
class ClipAudio(
    val path: String,
    val volume: Float,
    val loop: Boolean,
    val durMs: Long,
    /** layer id, so the mixer can follow live play / pause / mute / volume changes */
    val layerId: String = "",
    val speed: Float = 1f
)

/** A clip's audio pre-decoded to mono PCM (44.1 kHz, 16-bit), ready to mix. */
class DecodedClip(val clip: ClipAudio, val pcm: ShortArray)

/**
 * Real-time composition recorder (the RECORD button).
 *
 * Records exactly what the canvas shows — every visible source composited by
 * the SAME [Compositor] the preview and the exporter use — while the master
 * clock runs, at a steady fps. The live camera feeds straight through, so a
 * "local video + camera" setup records as one take.
 *
 * Playability rules (kept from the P0 pass):
 *  - H.264 + AAC in MP4 only (the universally playable baseline)
 *  - encoder input via [YuvWriter]/getInputImage (strided YUV, not packed NV12 guesses)
 *  - one monotonic PTS clock per track; EOS carries lastPts+1, never 0
 *  - muxer.start() only after tracks are added; failures abort instead of
 *    silently writing an empty container
 *  - MediaExtractor validation before reporting success
 *
 * ## Audio architecture (Phase 2)
 *
 * The previous recorder read the microphone from the SAME thread that
 * converted and encoded video, one 23 ms chunk per loop iteration, slept when
 * a read came back empty and silently dropped a chunk whenever the AAC
 * encoder had no free input buffer — while still stamping the next chunk as
 * if nothing was missing. Under load that produced audio slower than real
 * time (AudioRecord overruns → stutter), a track shorter than the video
 * ("sped-up" playback) and, when the clip PCM ran out, silence.
 *
 * Now there are three independent stages joined by queues:
 *
 *  1. **Capture** ([MicThread]): a dedicated thread does nothing but
 *     `AudioRecord.read` (blocking, paced by the audio HAL) and hands raw
 *     PCM to a bounded queue. Nothing else can starve it.
 *  2. **Mix + encode** ([AudioThread]): pulls PCM off the queue, cuts it
 *     into exact AAC frames (1024 samples), mixes every clip on the
 *     composition timeline (following live play / pause / mute / volume, with
 *     a drift guard against the preview's own media clock) through a peak
 *     limiter, and feeds the AAC encoder. It **never drops** a frame: when
 *     the encoder has no free input buffer it drains the output side and
 *     waits. The presentation time of every frame is
 *     `samplesEncoded * 1e6 / 44100` — ONE master clock derived from the
 *     sample count, never from wall time or a loop counter.
 *  3. **Video** ([runLoop] on the recorder HandlerThread): composited frames
 *     arrive with a `System.nanoTime()` stamp taken at render time; their PTS
 *     is that stamp measured from the audio clock's zero (mic sample #0,
 *     refined with `AudioRecord.getTimestamp` when the device supports it),
 *     so both tracks share the same timeline.
 *
 * Shutdown is ordered: stop capture → drain the queue → audio EOS → drain AAC
 * → video EOS → drain H.264 → codecs stopped → muxer.stop → MediaExtractor
 * validation (audio expected whenever an audio track was muxed).
 *
 * Every audio failure is counted and reported ([stats], [Result.message]) and
 * component failures are surfaced through `onError` — never swallowed.
 *
 * Threading: [renderAndSubmit] MUST be called on the main thread (it reads
 * PreviewEngine bitmaps). Everything else runs on private threads.
 */
class CompositionRecorder(
    private val projectRef: () -> Project,
    private val frameOf: (Layer) -> Bitmap?,
    private val timeMs: () -> Long,
    /** the preview's media position of a clip layer, in ms (drift guard for the clip mix) */
    private val clipMediaMs: ((Layer) -> Long)? = null
) {

    companion object {
        private const val TAG = "AhmedRecorder"
        /** every stage runs mono 16-bit PCM at this rate; clips are decoded to it */
        const val AUDIO_RATE = 44100
        /** one AAC-LC frame = 1024 PCM samples per channel (≈ 23.2 ms) */
        const val AUDIO_FRAME = 1024
        private const val AUDIO_BITRATE = 128_000
        /** raw mic buffers parked between capture and encode (≈ 5 s) before drops */
        private const val MIC_QUEUE_CAP = 256
        /** encoded samples parked per track until the muxer has both formats */
        private const val PENDING_CAP = 128
        private const val NS_PER_S = 1_000_000_000L
        /** clip audio is re-anchored to the preview clock when it drifts further than this */
        private const val RESYNC_SAMPLES = AUDIO_RATE * 12 / 100   // 120 ms
    }

    /** Final outcome: the playable file (or null) plus an honest message and the stats line. */
    class Result(val file: File?, val message: String?, val stats: String)

    /** Live control state of one mixed clip, published by the main thread, read by the audio thread. */
    private class ClipControl {
        @Volatile var playing = true
        @Volatile var muted = false
        @Volatile var volume = 1f
        /** preview media position + the nanoTime it was read at (-1 = unknown) */
        @Volatile var mediaMs = -1L
        @Volatile var stampNs = 0L
    }

    private class ClipState(val clip: ClipAudio, pcm: ShortArray) {
        val control = ClipControl().also { it.volume = clip.volume }
        /** read cursor on the composition timeline (speed, loop wrap at durMs, end hold) */
        val cursor = ClipCursor(pcm, clip.durMs, clip.loop, clip.speed)
    }

    /** raw PCM handed from the capture thread to the audio thread; `eos` closes the stream */
    private class MicChunk(val pcm: ShortArray?, val eos: Boolean)

    /** a composited frame + the moment it was rendered (System.nanoTime) */
    private class Frame(val bmp: Bitmap, val stampNs: Long)

    /** encoded sample parked until the muxer starts */
    private class Pending(val track: Int, val data: ByteBuffer, val info: MediaCodec.BufferInfo)

    // ---- video ----
    private var codec: MediaCodec? = null
    private var w = 0
    private var h = 0
    private var fps = 30
    private var nv12 = true
    private var videoMime = MediaFormat.MIMETYPE_VIDEO_AVC
    private val queue = ArrayBlockingQueue<Frame>(4)
    private val ctx = Compositor.Ctx()
    private val pool = ArrayDeque<Bitmap>()
    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- audio ----
    private var audioCodec: MediaCodec? = null
    private var micRec: AudioRecord? = null
    private var micRate = AUDIO_RATE
    private var micThread: MicThread? = null
    private var audioThread: AudioThread? = null
    private val micQueue = LinkedBlockingQueue<MicChunk>(MIC_QUEUE_CAP)
    @Volatile private var audioEnabled = false
    private var micEnabledFlag = false
    @Volatile private var micActive = false
    private val clips = ArrayList<ClipState>()
    private val audioDone = CountDownLatch(1)
    @Volatile private var audioFailed = false
    @Volatile private var audioGaveUp = false
    /** microphone gain (1 = unity) and master gain applied to clip audio */
    @Volatile var micGain = 1f
    @Volatile var masterGain = 1f

    // ---- clock ----
    /** System.nanoTime() of audio sample #0 (mic start), the zero of BOTH tracks */
    @Volatile private var startNs = 0L
    /** refined zero from AudioRecord.getTimestamp (0 = not available) */
    @Volatile private var audioZeroNs = 0L
    /** System.nanoTime() when the composition (clips) started playing; clips are silent before it */
    @Volatile private var compStartNs = 0L

    // ---- mux ----
    private var muxer: MediaMuxer? = null
    private val muxLock = Any()
    @Volatile private var muxerStarted = false
    @Volatile private var muxerFailed = false
    @Volatile private var videoTrack = -1
    @Volatile private var audioTrack = -1
    private val pending = ArrayList<Pending>()

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile private var finishing = false
    @Volatile private var discard = false
    @Volatile private var setupFailed = false
    private var onDone: ((Result) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    @Volatile var recording = false
        private set
    var outFile: File? = null
        private set

    private val videoPts = MonotonicPts()
    private val audioPtsClock = MonotonicPts()

    // ---- diagnostics (all volatile: written on worker threads, read from the UI) ----
    @Volatile private var videoSamplesWritten = 0
    @Volatile private var videoFramesDropped = 0
    @Volatile private var audioSamplesMuxed = 0
    @Volatile private var samplesEncoded = 0L
    @Volatile private var micChunksCaptured = 0L
    @Volatile private var micDroppedSamples = 0L
    @Volatile private var micReadErrors = 0
    @Volatile private var micStarvedPolls = 0
    @Volatile private var encoderInputWaits = 0
    @Volatile private var limiterEngaged = 0
    @Volatile private var clipResyncs = 0
    @Volatile private var lastVideoPtsUs = 0L
    private val warnings = ArrayList<String>()

    // =====================================================================
    // public API
    // =====================================================================

    /** Start the encoder writing to [outFile]. Returns false only on no video encoder / setup failure. */
    fun start(
        outFile: File, w: Int, h: Int, fps: Int, codecKind: Exporter.Codec,
        audio: List<DecodedClip>, micEnabled: Boolean, onError: (String) -> Unit
    ): Boolean {
        this.w = w; this.h = h; this.fps = fps
        this.outFile = outFile
        this.micEnabledFlag = micEnabled
        this.onError = onError
        // RECORD is the golden "what you see is what you get" path. Always
        // H.264/AAC/MP4 regardless of the export-sheet codec — WebM/HEVC from
        // this real-time path is what produced files that would not play.
        this.videoMime = MediaFormat.MIMETYPE_VIDEO_AVC
        clips.clear()
        for (d in audio) if (d.pcm.size > 1) clips.add(ClipState(d.clip, d.pcm))
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
        startNs = System.nanoTime()
        compStartNs = 0L
        recording = true
        finishing = false
        discard = false
        setupFailed = false
        val ready = CountDownLatch(1)
        handler!!.post {
            val ok = setup(encName, colorFmt)
            if (!ok) setupFailed = true
            ready.countDown()
            if (ok) runLoop() else finalize(failed = true)
        }
        try { ready.await(6, TimeUnit.SECONDS) } catch (_: Exception) { }
        if (setupFailed || codec == null) {
            recording = false
            onError("Could not start the H.264 encoder")
            return false
        }
        return true
    }

    /**
     * Call the instant the preview transport starts playing the clips (master
     * clock = 0). Clip audio is mixed from this moment; the microphone has
     * been rolling since [start], so mic audio begins at T=0 of the file and
     * the clips join exactly when their first frame is drawn.
     */
    fun markCompositionStart() {
        compStartNs = System.nanoTime()
    }

    /** Render the current composition and enqueue it. Main thread only. */
    fun renderAndSubmit() {
        if (!recording || setupFailed) return
        val p = projectRef()
        publishClipControls(p)
        val bmp = obtain() ?: run { videoFramesDropped++; return }
        val c = Canvas(bmp)
        Compositor.draw(ctx, c, w, h, p, frameOf, timeMs(), null)
        val stamp = System.nanoTime()
        if (!queue.offer(Frame(bmp, stamp))) { recycle(bmp); videoFramesDropped++ }
    }

    /** Stop capturing, drain everything in order and report the file. */
    fun finish(onDone: (Result) -> Unit) {
        if (!recording) { onDone(Result(null, "Not recording", stats())); return }
        recording = false
        this.onDone = onDone
        finishing = true
        micThread?.requestStop()
    }

    fun abort() {
        if (!recording && handler == null) return
        recording = false
        discard = true
        finishing = true
        micThread?.requestStop()
    }

    /** One-line health summary for the stats HUD / diagnostics. */
    fun stats(): String {
        val aSec = samplesEncoded / AUDIO_RATE.toFloat()
        val vSec = lastVideoPtsUs / 1_000_000f
        val sb = StringBuilder()
        sb.append("REC v ").append(videoSamplesWritten).append("f ")
            .append(String.format(java.util.Locale.US, "%.1fs", vSec))
        if (videoFramesDropped > 0) sb.append(" drop ").append(videoFramesDropped)
        if (audioEnabled) {
            sb.append(" · a ").append(String.format(java.util.Locale.US, "%.1fs", aSec))
            sb.append(if (micActive) " mic" else " nomic")
            if (micDroppedSamples > 0) sb.append(" drop ").append(micDroppedSamples / AUDIO_FRAME).append("f")
            if (micStarvedPolls > 0) sb.append(" starve ").append(micStarvedPolls)
            if (encoderInputWaits > 0) sb.append(" wait ").append(encoderInputWaits)
            if (limiterEngaged > 0) sb.append(" lim ").append(limiterEngaged)
            if (clipResyncs > 0) sb.append(" resync ").append(clipResyncs)
            sb.append(" q ").append(micQueue.size)
        } else sb.append(" · no audio")
        return sb.toString()
    }

    // =====================================================================
    // setup
    // =====================================================================

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
            audioEnabled = clips.isNotEmpty() || micEnabledFlag
            if (audioEnabled) setupAudio()
            true
        } catch (e: Exception) {
            Log.e(TAG, "setup failed", e)
            false
        }
    }

    /**
     * Mic + AAC encoder + the two audio threads. Any failure here is reported
     * to the UI (never just logged); the recording carries on with whatever
     * audio is still possible and the final message says what was lost.
     */
    private fun setupAudio() {
        if (micEnabledFlag) openMic()
        if (micRec == null && clips.isEmpty()) {
            audioEnabled = false
            return
        }
        try {
            val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_RATE, 1)
            af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            af.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_FRAME * 2 * 4)
            af.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1)
            audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audioCodec!!.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioCodec!!.start()
        } catch (e: Exception) {
            Log.e(TAG, "AAC encoder unavailable; recording video-only", e)
            try { audioCodec?.release() } catch (_: Exception) { }
            audioCodec = null
            audioEnabled = false
            closeMic()
            report("AAC audio encoder failed to start — recording video only")
            return
        }
        // capture first so the mic is rolling from T=0, then the consumer
        micRec?.let { rec ->
            val mt = MicThread(rec, micRate)
            micThread = mt
            mt.start()
        }
        val at = AudioThread(audioCodec!!)
        audioThread = at
        at.start()
    }

    /** Open the microphone at 44.1 kHz mono; fall back to 48 kHz + resampling. */
    private fun openMic() {
        for (rate in intArrayOf(AUDIO_RATE, 48000)) {
            var rec: AudioRecord? = null
            try {
                val min = AudioRecord.getMinBufferSize(rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (min <= 0) continue
                // a generous HAL buffer (1 s) is the safety net UNDER our own
                // queue; the dedicated capture thread is what prevents overruns
                rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(min * 2, rate * 2))
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    rec.release(); continue
                }
                rec.startRecording()
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    rec.stop(); rec.release(); continue
                }
                // sample #0 is being captured about now: this is the zero of both tracks
                startNs = System.nanoTime()
                micRec = rec
                micRate = rate
                micActive = true
                return
            } catch (e: Exception) {
                Log.w(TAG, "mic open at $rate failed", e)
                try { rec?.release() } catch (_: Exception) { }
            }
        }
        micRec = null
        micActive = false
        report(if (clips.isEmpty()) "Microphone unavailable — recording has no audio"
               else "Microphone unavailable — recording clip audio only")
    }

    private fun closeMic() {
        try { micRec?.stop() } catch (_: Exception) { }
        try { micRec?.release() } catch (_: Exception) { }
        micRec = null
        micActive = false
    }

    private fun report(msg: String) {
        synchronized(warnings) { if (!warnings.contains(msg)) warnings.add(msg) }
        Log.w(TAG, msg)
        val cb = onError ?: return
        mainHandler.post { cb(msg) }
    }

    /** main thread: mirror the layers' live play / mute / volume / position into the mixer's controls */
    private fun publishClipControls(p: Project) {
        if (clips.isEmpty()) return
        val anySolo = p.layers.any { it.solo }
        val now = System.nanoTime()
        for (c in clips) {
            val l = if (c.clip.layerId.isNotEmpty()) p.layerById(c.clip.layerId) else null
            val ctl = c.control
            if (l == null) continue
            ctl.playing = l.playing
            ctl.muted = l.muted || (anySolo && !l.solo)
            ctl.volume = l.volume
            val fn = clipMediaMs
            if (fn != null) {
                try {
                    val ms = fn(l)
                    ctl.mediaMs = ms
                    ctl.stampNs = now
                } catch (_: Exception) { }
            }
        }
    }

    // =====================================================================
    // capture thread
    // =====================================================================

    private inner class MicThread(private val rec: AudioRecord, private val rate: Int) : Thread("compo-mic") {
        @Volatile private var stopRequested = false
        fun requestStop() { stopRequested = true }

        override fun run() {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Exception) { }
            // 20 ms of source samples per read: small enough that stop() is
            // prompt, big enough that the read loop is not the bottleneck
            val n = rate / 50
            var tsTries = 0
            val rs = if (rate != AUDIO_RATE) Resampler(rate, AUDIO_RATE) else null
            try {
                while (!stopRequested) {
                    val raw = ShortArray(n)
                    val got = try { rec.read(raw, 0, n, AudioRecord.READ_BLOCKING) } catch (e: Exception) { -1 }
                    if (got <= 0) {
                        micReadErrors++
                        if (got == AudioRecord.ERROR_DEAD_OBJECT || got == AudioRecord.ERROR_INVALID_OPERATION ||
                            micReadErrors > 50) {
                            report("Microphone stopped delivering audio (error $got)")
                            break
                        }
                        continue
                    }
                    micChunksCaptured++
                    val out = if (rs == null) (if (got == n) raw else raw.copyOf(got)) else rs.process(raw, got)
                    if (out.isNotEmpty() && !micQueue.offer(MicChunk(out, false))) {
                        // the encoder is > MIC_QUEUE_CAP chunks behind: count it and let
                        // the consumer substitute silence, so the clock stays honest
                        micDroppedSamples += out.size
                        if (micDroppedSamples == out.size.toLong())
                            report("Audio encoder fell behind the microphone — some mic audio was replaced by silence")
                    }
                    if (audioZeroNs == 0L && tsTries < 40) { tsTries++; anchorClock() }
                }
            } finally {
                micActive = false
                // close the stream for the consumer even if the queue is full
                var tries = 0
                while (!micQueue.offer(MicChunk(null, true)) && tries++ < MIC_QUEUE_CAP) micQueue.poll()
            }
        }

        /**
         * Map mic sample #0 onto System.nanoTime() using the HAL timestamp, so
         * video frames (stamped with nanoTime at render) can be expressed on
         * the audio clock. Falls back to the mic start time when the device
         * has no usable timestamp.
         */
        private fun anchorClock() {
            try {
                val ts = AudioTimestamp()
                if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS &&
                    ts.framePosition > 0 && ts.nanoTime > 0) {
                    val zero = ts.nanoTime - ts.framePosition * NS_PER_S / rate
                    // sanity: the HAL cannot have started more than 0.5 s away from startRecording()
                    if (abs(zero - startNs) < 500_000_000L) audioZeroNs = zero
                }
            } catch (_: Throwable) { }
        }
    }

    // =====================================================================
    // mix + encode thread
    // =====================================================================

    private inner class AudioThread(private val ac: MediaCodec) : Thread("compo-audio") {
        private val info = MediaCodec.BufferInfo()
        private val frame = ShortArray(AUDIO_FRAME)
        private val mix = FloatArray(AUDIO_FRAME)
        private var frameLen = 0
        private var carry: ShortArray? = null
        private var carryOff = 0
        private var streamClosed = false
        private var useMic = micThread != null
        private val limiter = Limiter()
        private var silenceOwed = 0L
        private val frameNs = AUDIO_FRAME.toLong() * NS_PER_S / AUDIO_RATE

        override fun run() {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            } catch (_: Exception) { }
            try {
                while (!audioFailed && !muxerFailed) {
                    val ready = if (useMic) fillFromMic() else fillSilencePaced()
                    if (!ready) {
                        if (streamClosed || (!useMic && finishing)) break
                        continue
                    }
                    mixClips()
                    if (!submit(frame, AUDIO_FRAME, eos = false)) break
                    frameLen = 0
                }
                // ---- EOS: PTS continues the sample clock (never 0, never a repeat) ----
                if (!audioFailed && !muxerFailed) {
                    submit(null, 0, eos = true)
                    val deadline = SystemClock.elapsedRealtime() + 3000L
                    while (SystemClock.elapsedRealtime() < deadline && !audioFailed && !muxerFailed) {
                        if (drain(blockUs = 10_000)) break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "audio thread", e)
                audioFailed = true
                report("Audio encoding failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                audioDone.countDown()
            }
        }

        /** Fill [frame] with the next 1024 mic samples. False = nothing yet (or stream closed). */
        private fun fillFromMic(): Boolean {
            // mic chunks the capture thread had to drop become silence of the same length
            if (frameLen == 0) {
                val owed = micDroppedSamples - silenceOwed
                if (owed >= AUDIO_FRAME) {
                    java.util.Arrays.fill(frame, 0)
                    frameLen = AUDIO_FRAME
                    silenceOwed += AUDIO_FRAME
                    return true
                }
            }
            while (frameLen < AUDIO_FRAME) {
                var src = carry
                if (src == null) {
                    val chunk = micQueue.poll(200, TimeUnit.MILLISECONDS)
                    if (chunk == null) {
                        // capture is late: keep the encoder's output side moving, count it
                        micStarvedPolls++
                        drain(blockUs = 0)
                        if (audioFailed || muxerFailed) return false
                        if (finishing && !micActive && micQueue.isEmpty()) streamClosed = true
                        if (streamClosed) break
                        continue
                    }
                    val pcm = chunk.pcm
                    if (chunk.eos || pcm == null) {
                        if (finishing) { streamClosed = true; break }
                        // the microphone died mid-take: keep the clip mix + the clock alive
                        useMic = false
                        return false
                    }
                    src = pcm
                    carry = pcm
                    carryOff = 0
                }
                val n = min(AUDIO_FRAME - frameLen, src.size - carryOff)
                System.arraycopy(src, carryOff, frame, frameLen, n)
                frameLen += n
                carryOff += n
                if (carryOff >= src.size) carry = null
            }
            if (frameLen >= AUDIO_FRAME) return true
            if (streamClosed && frameLen > 0) {
                // final partial frame: pad with silence so the last real samples are kept
                java.util.Arrays.fill(frame, frameLen, AUDIO_FRAME, 0)
                frameLen = AUDIO_FRAME
                return true
            }
            return false
        }

        /**
         * No microphone: the mixer still needs a clock. Silent frames are
         * generated so that `samplesEncoded` tracks the monotonic clock, and the
         * PTS is STILL the sample count. The park below is pacing (do not run
         * ahead of real time), not synchronisation — the timeline never depends
         * on how long it slept.
         */
        private fun fillSilencePaced(): Boolean {
            val elapsed = System.nanoTime() - startNs
            val target = elapsed * AUDIO_RATE / NS_PER_S
            if (samplesEncoded + AUDIO_FRAME > target) {
                if (finishing) return false
                drain(blockUs = 0)
                LockSupport.parkNanos(frameNs / 2)
                return false
            }
            java.util.Arrays.fill(frame, 0)
            frameLen = AUDIO_FRAME
            return true
        }

        /** Add every clip at its composition-timeline position, then limit and write back to [frame]. */
        private fun mixClips() {
            val mg = micGain
            for (i in 0 until AUDIO_FRAME) mix[i] = frame[i] * mg
            val cs = compStartNs
            if (cs != 0L && clips.isNotEmpty()) {
                // wall time of this frame's first sample, on the audio clock
                val frameStartNs = audioZero() + samplesEncoded * NS_PER_S / AUDIO_RATE
                // sample index inside this frame at which the composition clock is >= 0
                val fromIdx = if (frameStartNs >= cs) 0
                              else ((cs - frameStartNs) * AUDIO_RATE / NS_PER_S).toInt().coerceIn(0, AUDIO_FRAME)
                val master = masterGain
                for (c in clips) {
                    val ctl = c.control
                    if (!ctl.playing) continue          // paused source: hold position, no sound
                    val cur = c.cursor
                    // drift guard: the picture is the reference. If the preview's media
                    // clock and our sample position disagree by > 120 ms (main-thread
                    // stalls, a seek, a restarted clip) jump to the preview's position.
                    val mMs = ctl.mediaMs
                    if (mMs >= 0L) {
                        val exp = mMs / 1000.0 * AUDIO_RATE +
                            (frameStartNs - ctl.stampNs) / 1e9 * AUDIO_RATE * cur.step
                        if (cur.resyncIfDrifted(exp, RESYNC_SAMPLES.toDouble())) clipResyncs++
                    }
                    val vol = if (ctl.muted) 0f else ctl.volume * master
                    cur.mixInto(mix, fromIdx, AUDIO_FRAME, vol)
                }
            }
            // peak limiter: instant attack, slow release — prevents hard clipping
            // when the mic and a loud clip stack up, without pumping on speech
            val before = limiter.engaged
            limiter.apply(mix, AUDIO_FRAME, frame)
            if (limiter.engaged != before) limiterEngaged++
        }

        /**
         * Queue one frame (or EOS) to the AAC encoder. Waits for an input
         * buffer while draining the output side — a frame is never dropped, so
         * the PTS (samples encoded) can never run ahead of the audio that was
         * actually written.
         */
        private fun submit(pcm: ShortArray?, n: Int, eos: Boolean): Boolean {
            val waitStart = SystemClock.elapsedRealtime()
            while (!audioFailed && !muxerFailed) {
                val idx = try { ac.dequeueInputBuffer(10_000) } catch (e: Exception) {
                    audioFailed = true; report("AAC encoder error: ${e.message}"); return false
                }
                if (idx >= 0) {
                    val buf = ac.getInputBuffer(idx)
                    if (buf == null) {
                        audioFailed = true; report("AAC encoder returned no input buffer"); return false
                    }
                    buf.clear()
                    val bytes = if (pcm != null && n > 0) { buf.asShortBuffer().put(pcm, 0, n); n * 2 } else 0
                    val ptsUs = audioPtsClock.next(samplesEncoded * 1_000_000L / AUDIO_RATE)
                    val flags = if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                    try {
                        ac.queueInputBuffer(idx, 0, bytes, ptsUs, flags)
                    } catch (e: Exception) {
                        audioFailed = true; report("AAC encoder rejected input: ${e.message}"); return false
                    }
                    if (!eos) samplesEncoded += n
                    drain(blockUs = 0)
                    return true
                }
                encoderInputWaits++
                drain(blockUs = 5_000)
                if (SystemClock.elapsedRealtime() - waitStart > 4000L) {
                    audioFailed = true
                    report("AAC encoder stalled (no input buffer for 4 s)")
                    return false
                }
            }
            return false
        }

        /** Move encoded AAC to the muxer. Returns true when the EOS output was seen. */
        private fun drain(blockUs: Long): Boolean {
            var first = true
            while (true) {
                val outIdx = try {
                    ac.dequeueOutputBuffer(info, if (first) blockUs else 0L)
                } catch (e: Exception) { audioFailed = true; return true }
                first = false
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = ac.outputFormat
                        if (!addTrack(fmt, video = false)) {
                            audioEnabled = false
                            audioGaveUp = true
                            tryStartMuxer()
                            return true
                        }
                        tryStartMuxer()
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                    outIdx >= 0 -> {
                        val eos = writeOut(audioTrack, ac, outIdx, info, audioPtsClock) { audioSamplesMuxed++ }
                        if (eos) return true
                    }
                    else -> return false
                }
            }
        }
    }

    private fun audioZero(): Long { val z = audioZeroNs; return if (z != 0L) z else startNs }

    // =====================================================================
    // muxer (shared by both encoder threads)
    // =====================================================================

    private fun addTrack(fmt: MediaFormat, video: Boolean): Boolean = synchronized(muxLock) {
        val m = muxer ?: return false
        if (muxerStarted) return false
        try {
            val t = m.addTrack(fmt)
            if (video) videoTrack = t else audioTrack = t
            true
        } catch (e: Exception) {
            Log.e(TAG, "add ${if (video) "video" else "audio"} track", e)
            if (video) muxerFailed = true else report("Could not add the audio track — recording video only")
            false
        }
    }

    private fun tryStartMuxer() {
        synchronized(muxLock) {
            if (muxerStarted || muxerFailed) return
            if (videoTrack < 0) return
            if (audioEnabled && audioTrack < 0 && !audioGaveUp) return
            val m = muxer ?: return
            try {
                m.start()
                muxerStarted = true
            } catch (e: Exception) {
                Log.e(TAG, "muxer.start failed", e)
                muxerFailed = true
                report("MP4 muxer failed to start: ${e.message}")
                return
            }
            // flush the samples parked while we waited for the second track —
            // that is how the first key frame and the first ~100 ms of audio survive
            for (pe in pending) {
                if (pe.track < 0) continue
                if (pe.track == audioTrack && (!audioEnabled || audioGaveUp)) continue
                try { m.writeSampleData(pe.track, pe.data, pe.info) } catch (e: Exception) { Log.w(TAG, "flush pending", e) }
            }
            pending.clear()
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
            if (track >= 0 && info.size > 0 && !muxerFailed) {
                val buf = c.getOutputBuffer(index)
                if (buf != null) {
                    info.presentationTimeUs = clock.next(info.presentationTimeUs)
                    val end = info.offset + info.size
                    if (end <= buf.capacity()) {
                        buf.position(info.offset)
                        buf.limit(end)
                        synchronized(muxLock) {
                            val m = muxer
                            if (muxerStarted && m != null) {
                                m.writeSampleData(track, buf, info)
                                onSample?.invoke()
                            } else if (pending.size < PENDING_CAP) {
                                val copy = ByteBuffer.allocate(info.size)
                                copy.put(buf); copy.flip()
                                val ci = MediaCodec.BufferInfo()
                                ci.set(0, info.size, info.presentationTimeUs, info.flags)
                                pending.add(Pending(track, copy, ci))
                                onSample?.invoke()
                            } else {
                                Log.w(TAG, "pending queue full before muxer start; sample dropped")
                            }
                            Unit
                        }
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

    // =====================================================================
    // video loop (recorder HandlerThread)
    // =====================================================================

    private fun runLoop() {
        val vc = codec ?: run { finalize(true); return }
        if (muxer == null) { finalize(true); return }
        val vInfo = MediaCodec.BufferInfo()
        var vEosQueued = false
        var vEosDone = false
        var failed = false
        // If the AAC encoder never produces a format, do not hold the muxer
        // hostage — drop audio after 1.5 s and mux video-only. An MP4 with
        // video and no audio plays; an MP4 that never started does not.
        val audioGiveUpAt = SystemClock.elapsedRealtime() + 1500L

        fun drainVideo(): Boolean {
            while (true) {
                val outIdx = try { vc.dequeueOutputBuffer(vInfo, 2_000) } catch (_: Exception) { return true }
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!addTrack(vc.outputFormat, video = true)) { failed = true; return true }
                        tryStartMuxer()
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                    outIdx >= 0 -> {
                        val eos = writeOut(videoTrack, vc, outIdx, vInfo, videoPts) { videoSamplesWritten++ }
                        if (vInfo.presentationTimeUs > lastVideoPtsUs) lastVideoPtsUs = vInfo.presentationTimeUs
                        if (eos) return true
                    }
                    else -> return false
                }
            }
        }

        try {
            var finishDeadline = Long.MAX_VALUE
            while (!failed && !muxerFailed && !vEosDone) {
                if (audioEnabled && !audioGaveUp && audioTrack < 0 && !muxerStarted &&
                    SystemClock.elapsedRealtime() > audioGiveUpAt) {
                    audioGaveUp = true
                    report("AAC encoder produced no output — recording video only")
                    tryStartMuxer()
                }

                val fr = queue.poll(4, TimeUnit.MILLISECONDS)
                if (fr != null) {
                    val inIdx = try { vc.dequeueInputBuffer(20_000) } catch (_: Exception) { -1 }
                    if (inIdx >= 0) {
                        val bmp = fr.bmp
                        if (!bmp.isRecycled) {
                            val bytes = YuvWriter.fillInput(vc, inIdx, bmp, w, h, nv12)
                            if (bytes > 0) {
                                // the frame's render time on the AUDIO clock
                                val pts = videoPts.next(((fr.stampNs - audioZero()) / 1000L).coerceAtLeast(0L))
                                try { vc.queueInputBuffer(inIdx, 0, bytes, pts, 0) } catch (_: Exception) { }
                            } else {
                                try { vc.queueInputBuffer(inIdx, 0, 0, videoPts.lastOr(0L), 0) } catch (_: Exception) { }
                            }
                        }
                    } else videoFramesDropped++
                    recycle(fr.bmp)
                }
                if (drainVideo()) vEosDone = true

                if (finishing) {
                    if (finishDeadline == Long.MAX_VALUE) finishDeadline = SystemClock.elapsedRealtime() + 8000L
                    if (!vEosQueued && queue.isEmpty()) {
                        val inIdx = try { vc.dequeueInputBuffer(20_000) } catch (_: Exception) { -1 }
                        if (inIdx >= 0) {
                            val eosPts = videoPts.next(videoPts.lastOr(0L) + 1_000_000L / fps)
                            try {
                                vc.queueInputBuffer(inIdx, 0, 0, eosPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                vEosQueued = true
                            } catch (_: Exception) { }
                        }
                    }
                    if (drainVideo()) vEosDone = true
                    if (SystemClock.elapsedRealtime() > finishDeadline) {
                        Log.w(TAG, "video EOS deadline hit")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "encode loop", e)
            failed = true
        } finally {
            finalize(failed || muxerFailed)
        }
    }

    // =====================================================================
    // shutdown
    // =====================================================================

    /**
     * Ordered shutdown: capture stopped → queue drained → AAC EOS → H.264 EOS
     * (already done by the caller) → codecs stopped → muxer.stop → validate.
     */
    private fun finalize(failed: Boolean) {
        // 1. the microphone is stopped first; the audio thread drains what was
        //    captured and closes its stream with EOS
        finishing = true
        micThread?.requestStop()
        if (audioThread != null) {
            try { audioDone.await(6, TimeUnit.SECONDS) } catch (_: Exception) { }
        }
        try { micThread?.join(1500) } catch (_: Exception) { }
        closeMic()
        // 2. encoders
        try { codec?.stop() } catch (_: Exception) { }
        try { codec?.release() } catch (_: Exception) { }
        codec = null
        try { audioCodec?.stop() } catch (_: Exception) { }
        try { audioCodec?.release() } catch (_: Exception) { }
        audioCodec = null
        // 3. container
        var muxOk = false
        synchronized(muxLock) {
            try {
                if (muxerStarted) {
                    muxer?.stop()
                    muxOk = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "muxer.stop", e)
                muxOk = false
            }
            try { muxer?.release() } catch (_: Exception) { }
            muxer = null
            muxerStarted = false
            pending.clear()
        }
        if (discard) try { outFile?.delete() } catch (_: Exception) { }
        var f = queue.poll()
        while (f != null) { recycle(f.bmp); f = queue.poll() }
        recyclePool()
        micQueue.clear()

        val file = outFile
        val hadAudioTrack = audioEnabled && !audioGaveUp && audioTrack >= 0 && !audioFailed
        var message: String? = null
        var playable = false
        if (!failed && !discard && muxOk && file != null && videoSamplesWritten > 0) {
            val rep = ExportValidator.validate(file.absolutePath, expectAudio = hadAudioTrack)
            playable = rep.ok
            if (!rep.ok) message = rep.message
            else if (hadAudioTrack && rep.audioSamples <= 0) message = "Recording saved, but its audio track is empty"
            else if (hadAudioTrack && rep.durationUs > 0) {
                // audio shorter than video by more than half a second = something was lost
                val aUs = samplesEncoded * 1_000_000L / AUDIO_RATE
                val gap = rep.durationUs - aUs
                if (gap > 500_000L) message = "Audio is ${gap / 1000} ms shorter than the video"
            }
        }
        if (!playable && file != null && !discard) {
            Log.e(TAG, "recording not playable (samples=$videoSamplesWritten muxOk=$muxOk failed=$failed) $message")
        }
        val warn = synchronized(warnings) { warnings.joinToString(" · ") }
        val msg = listOfNotNull(message, warn.ifBlank { null }).joinToString(" · ").ifBlank { null }
        val result = Result(if (playable) file else null, msg, stats())
        Log.i(TAG, "finished: ${result.stats} · ${msg ?: "clean"}")
        clips.clear()
        val cb = onDone
        onDone = null
        recording = false
        try { thread?.quitSafely() } catch (_: Exception) { }
        thread = null
        handler = null
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
