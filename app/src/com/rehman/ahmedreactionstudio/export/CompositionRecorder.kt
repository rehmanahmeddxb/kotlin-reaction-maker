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
import com.rehman.ahmedreactionstudio.core.Compositor
import com.rehman.ahmedreactionstudio.core.Layer
import com.rehman.ahmedreactionstudio.core.Project
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue

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
 * AUDIO: each clip's audio track is decoded to mono 44.1 kHz PCM once at
 * start, then summed with the microphone (if permitted) sample-by-sample and
 * encoded to AAC in the same muxer. Video and audio are timestamped off the
 * same wall clock, so they stay in sync.
 *
 * Threading: rendering reads [frameOf], whose frames are owned by the UI
 * thread, so [renderAndSubmit] MUST be called on the main thread. The encoder
 * + mixer run on one private background thread and drain a small bounded queue
 * of double-buffered bitmaps; if the encoder cannot keep up, a video frame is
 * skipped (real-time capture drops rather than stalls).
 */
class CompositionRecorder(
    private val projectRef: () -> Project,
    private val frameOf: (Layer) -> Bitmap?,
    private val timeMs: () -> Long
) {

    companion object {
        private const val AUDIO_RATE = 44100
        private const val AUDIO_CHUNK = 1024          // ~23 ms per audio block
    }

    // ---------- video ----------
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

    // ---------- audio ----------
    private var audioCodec: MediaCodec? = null
    private var micRec: AudioRecord? = null
    private var audioEnabled = false
    private var micEnabledFlag = false
    private val clips = ArrayList<ClipState>()
    private var audioSamples = 0L

    // ---------- muxer ----------
    private var muxer: MediaMuxer? = null
    private var muxerStarted = false
    private var videoTrack = -1
    private var audioTrack = -1

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var startWall = 0L

    @Volatile private var finishing = false
    @Volatile private var discard = false
    private var onDone: ((File?) -> Unit)? = null

    @Volatile var recording = false
        private set
    var outFile: File? = null
        private set

    private class ClipState(val clip: ClipAudio, val pcm: ShortArray) {
        val durSamples: Long = clip.durMs * AUDIO_RATE / 1000L
    }

    /** Start the encoder writing to [outFile]. Returns false only on no video encoder. */
    fun start(
        outFile: File, w: Int, h: Int, fps: Int, codecKind: Exporter.Codec,
        audio: List<DecodedClip>, micEnabled: Boolean, onError: (String) -> Unit
    ): Boolean {
        this.w = w; this.h = h; this.fps = fps
        this.outFile = outFile
        this.micEnabledFlag = micEnabled
        this.videoMime = codecKind.mime
        clips.clear()
        for (d in audio) if (d.pcm.isNotEmpty()) clips.add(ClipState(d.clip, d.pcm))
        val (encName, colorFmt) = Exporter.pickEncoder(codecKind.mime)
        if (encName.isEmpty() || colorFmt < 0) {
            onError("No ${codecKind.label} encoder on this device")
            return false
        }
        nv12 = colorFmt != MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        // pre-allocate the double-buffer pool on the main thread
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
        handler!!.post { setupAndRun(encName, colorFmt) }
        return true
    }

    /** Render the current composition and enqueue it. Main thread only. */
    fun renderAndSubmit() {
        if (!recording) return
        val bmp = obtain() ?: return
        val p = projectRef()
        val c = Canvas(bmp)
        Compositor.draw(ctx, c, w, h, p, frameOf, timeMs(), null)
        if (!queue.offer(bmp)) recycle(bmp)
    }

    /** Stop capture, finalize the muxer, then invoke [onDone] on the main thread. */
    fun finish(onDone: (File?) -> Unit) {
        if (!recording) { onDone(null); return }
        recording = false
        finishing = true
        this.onDone = onDone
    }

    /** Abort without a callback and discard any partial file (activity teardown). */
    fun abort() {
        if (!recording) return
        recording = false
        discard = true
        finishing = true
    }

    // ================= setup (background thread) =================

    private fun setupAndRun(encName: String, colorFmt: Int) {
        try {
            outFile?.parentFile?.mkdirs()
            // video encoder
            val vf = MediaFormat.createVideoFormat(videoMime, w, h)
            vf.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFmt)
            vf.setInteger(MediaFormat.KEY_BIT_RATE, (w * h * fps * 0.12).toInt().coerceIn(400_000, 24_000_000))
            vf.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            codec = MediaCodec.createByCodecName(encName)
            codec!!.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec!!.start()

            muxer = MediaMuxer(outFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // audio (pre-decoded clips + mic + AAC encoder); degrades to video-only on failure
            audioEnabled = clips.isNotEmpty() || micEnabledFlag
            if (audioEnabled) setupAudio()
        } catch (e: Exception) {
            finalize(failed = true)
            return
        }
        runLoop()
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
        // no mic and no decodable clip audio → fall back to video-only
        if (micRec == null && clips.isEmpty()) { audioEnabled = false; return }
        try {
            val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_RATE, 1)
            af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            af.setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audioCodec!!.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioCodec!!.start()
        } catch (_: Exception) {
            audioCodec = null
            audioEnabled = false
        }
    }

    // ================= encode + mix loop (background thread) =================

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

        fun drainVideo(): Boolean {
            while (true) {
                val outIdx = try { vc.dequeueOutputBuffer(vInfo, 10_000) } catch (_: Exception) { -100 }
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        try { videoTrack = m.addTrack(vc.outputFormat) } catch (_: Exception) { failed = true; return true }
                        tryStartMuxer()
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                    outIdx >= 0 -> {
                        if (videoTrack >= 0 && muxerStarted) {
                            try { vc.getOutputBuffer(outIdx)?.let { m.writeSampleData(videoTrack, it, vInfo) } }
                            catch (_: Exception) { }
                        }
                        val eos = vInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        try { vc.releaseOutputBuffer(outIdx, false) } catch (_: Exception) { }
                        if (eos) return true
                    }
                    else -> return false
                }
            }
        }

        fun drainAudio(): Boolean {
            if (ac == null) return true
            while (true) {
                val outIdx = try { ac.dequeueOutputBuffer(aInfo, 10_000) } catch (_: Exception) { -100 }
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        try { audioTrack = m.addTrack(ac.outputFormat) } catch (_: Exception) { failed = true; return true }
                        tryStartMuxer()
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                    outIdx >= 0 -> {
                        if (audioTrack >= 0 && muxerStarted) {
                            try { ac.getOutputBuffer(outIdx)?.let { m.writeSampleData(audioTrack, it, aInfo) } }
                            catch (_: Exception) { }
                        }
                        val eos = aInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        try { ac.releaseOutputBuffer(outIdx, false) } catch (_: Exception) { }
                        if (eos) return true
                    }
                    else -> return false
                }
            }
        }

        try {
            val audioPaced = audioEnabled && ac != null
            val finishDeadline = SystemClock.elapsedRealtime() + 5000L
            while (!failed && !(vEosDone && (if (audioPaced) aEosDone else true))) {
                // ----- video (non-blocking) -----
                val bmp = queue.poll()
                if (bmp != null) {
                    val inIdx = vc.dequeueInputBuffer(20_000)
                    if (inIdx >= 0) {
                        val buf = vc.getInputBuffer(inIdx)
                        if (buf != null && !bmp.isRecycled) {
                            val bytes = Exporter.writeYuv(buf, bmp, w, h, nv12)
                            val pts = (SystemClock.elapsedRealtime() - startWall) * 1000L
                            vc.queueInputBuffer(inIdx, 0, bytes, pts, 0)
                        }
                    }
                    recycle(bmp)
                }
                if (drainVideo()) vEosDone = true

                // ----- audio (paced by the microphone read) -----
                if (audioPaced && !finishing) {
                    encodeAudioChunk()
                    if (drainAudio()) aEosDone = true
                } else if (!audioPaced && !finishing) {
                    // no audio → keep the loop cadence from spinning too fast
                    try { Thread.sleep(12) } catch (_: Exception) { }
                }

                // ----- finishing → flush + EOS -----
                if (finishing) {
                    if (!vEosQueued && queue.isEmpty()) {
                        val inIdx = vc.dequeueInputBuffer(20_000)
                        if (inIdx >= 0) {
                            vc.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            vEosQueued = true
                        }
                    }
                    if (audioPaced && !aEosQueued) {
                        val inIdx = ac!!.dequeueInputBuffer(20_000)
                        if (inIdx >= 0) {
                            ac.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            aEosQueued = true
                        }
                    }
                    if (drainVideo()) vEosDone = true
                    if (audioPaced && drainAudio()) aEosDone = true
                    // safety: never spin forever waiting for an EOS that will not come
                    if (SystemClock.elapsedRealtime() > finishDeadline) break
                }
            }
        } catch (e: Exception) {
            failed = true
        } finally {
            finalize(failed)
        }
    }

    private fun tryStartMuxer() {
        val m = muxer ?: return
        if (!muxerStarted && videoTrack >= 0 && (!audioEnabled || audioTrack >= 0)) {
            try { m.start(); muxerStarted = true } catch (_: Exception) { }
        }
    }

    /** Read one audio block (mic + clips), mix, and feed the AAC encoder. */
    private fun encodeAudioChunk() {
        val ac = audioCodec ?: return
        val n = AUDIO_CHUNK
        val chunk = ShortArray(n)

        // microphone (this read paces the loop at real time)
        val read = try { micRec?.read(chunk, 0, n) ?: -1 } catch (_: Exception) { -1 }
        if (read <= 0) {
            java.util.Arrays.fill(chunk, 0)
            try { Thread.sleep(n * 1000L / AUDIO_RATE) } catch (_: Exception) { }
        } else if (read < n) {
            for (i in read until n) chunk[i] = 0
        }

        // add each clip's audio at the current position
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

        val inIdx = ac.dequeueInputBuffer(20_000)
        if (inIdx >= 0) {
            val buf = ac.getInputBuffer(inIdx)
            if (buf != null) {
                buf.clear()
                buf.asShortBuffer().put(chunk)
                val ptsUs = audioSamples * 1_000_000L / AUDIO_RATE
                ac.queueInputBuffer(inIdx, 0, chunk.size * 2, ptsUs, 0)
                audioSamples += n
            }
        }
    }

    // ================= teardown =================

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
        try { if (muxerStarted) muxer?.stop() } catch (_: Exception) { }
        try { muxer?.release() } catch (_: Exception) { }
        muxer = null
        muxerStarted = false
        if (discard) try { outFile?.delete() } catch (_: Exception) { }
        // drain any frames still queued so their bitmaps are returned to the pool
        var b = queue.poll()
        while (b != null) { recycle(b); b = queue.poll() }
        val ok = !failed && !discard && outFile != null && outFile!!.exists() && outFile!!.length() > 0
        val result = if (ok) outFile else null
        recyclePool()
        clips.clear()
        val cb = onDone
        onDone = null
        recording = false
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
