package com.rehman.ahmedreactionstudio.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.rehman.ahmedreactionstudio.core.Compositor
import com.rehman.ahmedreactionstudio.core.Layer
import com.rehman.ahmedreactionstudio.core.Project
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Real-time composition recorder (the RECORD button).
 *
 * Records exactly what the canvas shows — every visible source composited by
 * the SAME [Compositor] the preview and the exporter use — while the master
 * clock runs, at a steady fps. The live camera feeds straight through, so a
 * "local video + camera" setup records as one take.
 *
 * Threading: rendering reads [frameOf], whose frames are owned by the UI
 * thread, so [renderAndSubmit] MUST be called on the main thread. The encoder
 * runs on a private background thread and drains a small bounded queue of
 * double-buffered bitmaps; if the encoder cannot keep up, a frame is skipped
 * (real-time capture drops rather than stalls).
 */
class CompositionRecorder(
    private val projectRef: () -> Project,
    private val frameOf: (Layer) -> Bitmap?,
    private val timeMs: () -> Long
) {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIdx = -1
    private var muxerStarted = false
    private var w = 0
    private var h = 0
    private var fps = 30
    private var nv12 = true

    private val queue = ArrayBlockingQueue<Bitmap>(4)
    @Volatile private var finishing = false
    @Volatile private var discard = false
    private var onDone: ((File?) -> Unit)? = null

    private val ctx = Compositor.Ctx()
    private val pool = ArrayDeque<Bitmap>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile var recording = false
        private set
    var outFile: File? = null
        private set

    /** Start the encoder writing to [outFile]. Returns false on immediate failure. */
    fun start(outFile: File, w: Int, h: Int, fps: Int, codecKind: Exporter.Codec, onError: (String) -> Unit): Boolean {
        this.w = w; this.h = h; this.fps = fps
        this.outFile = outFile
        try { outFile.parentFile?.mkdirs() } catch (_: Exception) { }
        val (encName, colorFmt) = Exporter.pickEncoder(codecKind.mime)
        if (encName.isEmpty() || colorFmt < 0) {
            onError("No ${codecKind.label} encoder on this device")
            return false
        }
        nv12 = colorFmt != MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        try {
            val bitrate = (w * h * fps * 0.12).toInt().coerceIn(400_000, 24_000_000)
            val format = MediaFormat.createVideoFormat(codecKind.mime, w, h)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFmt)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            codec = MediaCodec.createByCodecName(encName)
            codec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec!!.start()
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            // pre-allocate the double-buffer pool (main thread; safe to draw into)
            for (i in 0 until 3) pool.addLast(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888))
        } catch (e: Exception) {
            release()
            onError(e.message ?: "Encoder setup failed")
            return false
        }
        val t = HandlerThread("compo-rec")
        t.start()
        thread = t
        handler = Handler(t.looper)
        recording = true
        finishing = false
        discard = false
        handler!!.post { encodeLoop() }
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

    private fun release() {
        try { codec?.stop() } catch (_: Exception) { }
        try { codec?.release() } catch (_: Exception) { }
        codec = null
        try { if (muxerStarted) muxer?.stop() } catch (_: Exception) { }
        try { muxer?.release() } catch (_: Exception) { }
        muxer = null
        muxerStarted = false
    }

    private fun encodeLoop() {
        val c = codec
        val m = muxer
        if (c == null || m == null) {
            val cb = onDone; onDone = null; recording = false
            mainHandler.post { cb?.invoke(null) }
            return
        }
        val info = MediaCodec.BufferInfo()
        var ptsUs = 0L
        val frameUs = 1_000_000L / fps
        var eosQueued = false
        var eosDone = false
        var failed = false

        fun drain(): Boolean {
            while (true) {
                val outIdx = try { c.dequeueOutputBuffer(info, 2000) } catch (_: Exception) { -100 }
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        try {
                            trackIdx = m.addTrack(c.outputFormat)
                            if (!muxerStarted) { m.start(); muxerStarted = true }
                        } catch (_: Exception) { failed = true; return true }
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                    outIdx >= 0 -> {
                        if (trackIdx >= 0 && muxerStarted) {
                            try { c.getOutputBuffer(outIdx)?.let { m.writeSampleData(trackIdx, it, info) } }
                            catch (_: Exception) { }
                        }
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        try { c.releaseOutputBuffer(outIdx, false) } catch (_: Exception) { }
                        if (eos) return true
                    }
                    else -> return false
                }
            }
        }

        try {
            while (!eosDone && !failed) {
                val bmp = queue.poll(40, TimeUnit.MILLISECONDS)
                if (bmp != null) {
                    val inIdx = c.dequeueInputBuffer(20_000)
                    if (inIdx >= 0) {
                        val buf = c.getInputBuffer(inIdx)
                        if (buf != null && !bmp.isRecycled) {
                            val bytes = Exporter.writeYuv(buf, bmp, w, h, nv12)
                            c.queueInputBuffer(inIdx, 0, bytes, ptsUs, 0)
                        } else {
                            c.queueInputBuffer(inIdx, 0, 0, ptsUs, 0)
                        }
                        ptsUs += frameUs
                    }
                    recycle(bmp)
                    drain()
                } else if (finishing) {
                    if (!eosQueued) {
                        val inIdx = c.dequeueInputBuffer(20_000)
                        if (inIdx >= 0) {
                            c.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosQueued = true
                        }
                    }
                    if (drain()) eosDone = true
                }
            }
        } catch (e: Exception) {
            failed = true
        } finally {
            release()
            if (discard) try { outFile?.delete() } catch (_: Exception) { }
            val ok = !failed && !discard && outFile != null && outFile!!.exists() && outFile!!.length() > 0
            val result = if (ok) outFile else null
            recyclePool()
            val cb = onDone
            onDone = null
            recording = false
            mainHandler.post { cb?.invoke(result) }
        }
    }
}
