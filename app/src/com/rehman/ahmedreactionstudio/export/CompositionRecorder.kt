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
 * Threading: [renderAndSubmit] MUST be called on the main thread (it reads
 * PreviewEngine bitmaps). Encoder + mixer run on a private thread.
 */
class CompositionRecorder(
    private val projectRef: () -> Project,
    private val frameOf: (Layer) -> Bitmap?,
    private val timeMs: () -> Long
) {

    companion object {
        private const val TAG = "AhmedRecorder"
        private const val AUDIO_RATE = 44100
        private const val AUDIO_CHUNK = 1024
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
    private val clips = ArrayList<ClipState>()
    private var audioSamples = 0L

    private var muxer: MediaMuxer? = null
    @Volatile private var muxerStarted = false
    private var videoTrack = -1
    private var audioTrack = -1

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var startWall = 0L

    @Volatile private var finishing = false
    @Volatile private var discard = false
    @Volatile private var setupFailed = false
    private var onDone: ((File?) -> Unit)? = null

    @Volatile var recording = false
        private set
    var outFile: File? = null
        private set

    private val videoPts = MonotonicPts()
    private val audioPtsClock = MonotonicPts()
    @Volatile private var videoSamplesWritten = 0

    private class ClipState(val clip: ClipAudio, val pcm: ShortArray) {
        val durSamples: Long = clip.durMs * AUDIO_RATE / 1000L
    }

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
        clips.clear()
        for (d in audio) if (d.pcm.isNotEmpty()) clips.add(ClipState(d.clip, d.pcm))
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
        startWall = SystemClock.elapsedRealtime()
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

    private fun setupAudio() {
        if (micEnabledFlag) {
            try {
                val min = AudioRecord.getMinBufferSize(AUDIO_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val rec = AudioRecord(MediaRecorder.AudioSource.MIC, AUDIO_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(min, AUDIO_RATE * 2))
                rec.startRecording()
                micRec = rec
            } catch (_: Exception) { micRec = null }
        }
        if (micRec == null && clips.isEmpty()) { audioEnabled = false; return }
        try {
            val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_RATE, 1)
            af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            af.setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            af.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1)
            audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audioCodec!!.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioCodec!!.start()
        } catch (e: Exception) {
            Log.w(TAG, "AAC encoder unavailable; recording video-only", e)
            audioCodec = null
            audioEnabled = false
        }
    }

    private fun runLoop() {
        val vc = codec ?: run { finalize(true); return }
        val m = muxer ?: run { finalize(true); return }
        val ac = audioCodec
        val vInfo = MediaCodec.BufferInfo()
        val aInfo = MediaCodec.BufferInfo()
        var vEosQueued = false
        var vEosDone = false
        var aEosQueued = false
        var aEosDone = false
        var failed = false
        val audioPaced = audioEnabled && ac != null
        // If the AAC encoder never produces a format, do not hold the muxer
        // hostage — drop audio after 800 ms and mux video-only. An MP4 with
        // video and no audio plays; an MP4 that never started does not.
        val audioGiveUpAt = SystemClock.elapsedRealtime() + 800L
        var audioGaveUp = false

        fun tryStartMuxer() {
            if (muxerStarted) return
            if (videoTrack < 0) return
            if (audioPaced && audioTrack < 0 && !audioGaveUp) return
            try {
                m.start()
                muxerStarted = true
            } catch (e: Exception) {
                Log.e(TAG, "muxer.start failed", e)
                failed = true
            }
        }

        fun drainVideo(): Boolean {
            while (true) {
                val outIdx = try { vc.dequeueOutputBuffer(vInfo, 2_000) } catch (_: Exception) { return true }
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        try { videoTrack = m.addTrack(vc.outputFormat) } catch (e: Exception) {
                            Log.e(TAG, "add video track", e); failed = true; return true
                        }
                        tryStartMuxer()
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

        fun drainAudio(): Boolean {
            if (ac == null) return true
            while (true) {
                val outIdx = try { ac.dequeueOutputBuffer(aInfo, 2_000) } catch (_: Exception) { return true }
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        try { audioTrack = m.addTrack(ac.outputFormat) } catch (e: Exception) {
                            Log.e(TAG, "add audio track", e)
                            audioEnabled = false
                            tryStartMuxer()
                            return true
                        }
                        tryStartMuxer()
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                    outIdx >= 0 -> {
                        val eos = writeOut(m, audioTrack, ac, outIdx, aInfo, audioPtsClock)
                        if (eos) return true
                    }
                    else -> return false
                }
            }
        }

        try {
            val finishDeadline = SystemClock.elapsedRealtime() + 6000L
            while (!failed && !(vEosDone && (if (audioPaced && !audioGaveUp) aEosDone else true))) {
                if (!audioGaveUp && audioPaced && audioTrack < 0 &&
                    SystemClock.elapsedRealtime() > audioGiveUpAt) {
                    audioGaveUp = true
                    Log.w(TAG, "AAC format never arrived; muxing video-only")
                    tryStartMuxer()
                }

                val bmp = queue.poll()
                if (bmp != null) {
                    val inIdx = try { vc.dequeueInputBuffer(20_000) } catch (_: Exception) { -1 }
                    if (inIdx >= 0) {
                        if (!bmp.isRecycled) {
                            val bytes = YuvWriter.fillInput(vc, inIdx, bmp, w, h, nv12)
                            if (bytes > 0) {
                                val pts = videoPts.next(
                                    (SystemClock.elapsedRealtime() - startWall) * 1000L
                                )
                                try { vc.queueInputBuffer(inIdx, 0, bytes, pts, 0) } catch (_: Exception) { }
                            } else {
                                try { vc.queueInputBuffer(inIdx, 0, 0, videoPts.lastOr(0L), 0) } catch (_: Exception) { }
                            }
                        }
                    }
                    recycle(bmp)
                }
                if (drainVideo()) vEosDone = true

                if (audioPaced && !finishing && !audioGaveUp) {
                    encodeAudioChunk()
                    if (drainAudio()) aEosDone = true
                } else if (!audioPaced && !finishing) {
                    try { Thread.sleep(8) } catch (_: Exception) { }
                }

                if (finishing) {
                    if (!vEosQueued && queue.isEmpty()) {
                        val inIdx = try { vc.dequeueInputBuffer(20_000) } catch (_: Exception) { -1 }
                        if (inIdx >= 0) {
                            val pts = videoPts.next(videoPts.lastOr(0L) + 33_333L)
                            try {
                                vc.queueInputBuffer(inIdx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                vEosQueued = true
                            } catch (_: Exception) { }
                        }
                    }
                    if (audioPaced && !aEosQueued && !audioGaveUp) {
                        val inIdx = try { ac!!.dequeueInputBuffer(20_000) } catch (_: Exception) { -1 }
                        if (inIdx >= 0) {
                            val pts = audioPtsClock.next(audioPtsClock.lastOr(0L) + 23_000L)
                            try {
                                ac!!.queueInputBuffer(inIdx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                aEosQueued = true
                            } catch (_: Exception) { }
                        }
                    }
                    if (drainVideo()) vEosDone = true
                    if (audioPaced && !audioGaveUp && drainAudio()) aEosDone = true
                    if (SystemClock.elapsedRealtime() > finishDeadline) break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "encode loop", e)
            failed = true
        } finally {
            finalize(failed)
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
                        m.writeSampleData(track, buf, info)
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

    private fun encodeAudioChunk() {
        val ac = audioCodec ?: return
        val n = AUDIO_CHUNK
        val chunk = ShortArray(n)
        val read = try { micRec?.read(chunk, 0, n) ?: -1 } catch (_: Exception) { -1 }
        if (read <= 0) {
            java.util.Arrays.fill(chunk, 0)
            try { Thread.sleep(n * 1000L / AUDIO_RATE) } catch (_: Exception) { }
        } else if (read < n) {
            for (i in read until n) chunk[i] = 0
        }
        val base = audioSamples
        for (c in clips) {
            val data = c.pcm
            if (data.isEmpty() || c.durSamples <= 0L) continue
            val vol = c.clip.volume
            for (i in 0 until n) {
                val p = base + i
                val idx = if (p < c.durSamples) p.toInt()
                          else if (c.clip.loop) (p % c.durSamples).toInt()
                          else -1
                if (idx < 0 || idx >= data.size) continue
                val v = chunk[i].toInt() + (data[idx] * vol).toInt()
                chunk[i] = v.coerceIn(-32768, 32767).toShort()
            }
        }
        val inIdx = try { ac.dequeueInputBuffer(20_000) } catch (_: Exception) { -1 }
        if (inIdx >= 0) {
            val buf = ac.getInputBuffer(inIdx)
            if (buf != null) {
                buf.clear()
                buf.asShortBuffer().put(chunk)
                val ptsUs = audioPtsClock.next(audioSamples * 1_000_000L / AUDIO_RATE)
                try { ac.queueInputBuffer(inIdx, 0, chunk.size * 2, ptsUs, 0) } catch (_: Exception) { }
                audioSamples += n
            }
        }
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
        if (discard) try { outFile?.delete() } catch (_: Exception) { }
        var b = queue.poll()
        while (b != null) { recycle(b); b = queue.poll() }
        recyclePool()
        clips.clear()

        val file = outFile
        val playable = !failed && !discard && muxOk && file != null &&
            videoSamplesWritten > 0 &&
            ExportValidator.validate(file.absolutePath, expectAudio = false).ok
        if (!playable && file != null && !discard) {
            Log.e(TAG, "recording not playable (samples=$videoSamplesWritten muxOk=$muxOk failed=$failed)")
        }
        val result = if (playable) file else null
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
