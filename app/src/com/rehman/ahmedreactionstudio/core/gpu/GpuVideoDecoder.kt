package com.rehman.ahmedreactionstudio.core.gpu

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Continuous hardware video decode path (Option A).
 *
 * MX Player / ExoPlayer style:
 *   MediaExtractor → MediaCodec (HW) → SurfaceTexture (OES) → GL blit → Bitmap
 *
 * Not MediaMetadataRetriever seek-grabs. Frames stream forward in decode order
 * while playing; seeks only happen on scrub / pause-jump.
 *
 * One shared GL thread owns the EGL context and every decoder instance so the
 * compositor can keep using ARGB bitmaps without a full GLES compositor rewrite
 * yet — the expensive part (decode) is already GPU/zero-copy until the final
 * FBO read for Canvas compositing.
 *
 * Threading:
 *  - all MediaCodec / GL work runs on the internal GL HandlerThread
 *  - [frameOf] / [currentBitmap] are safe to call from the UI thread
 *  - [release] / [releaseAll] block until the GL thread tears down
 */
object GpuVideoPipeline {

    private val lock = Any()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var egl: EglCore? = null
    private var blit: GlUtil.OesToBitmap? = null
    private val decoders = ConcurrentHashMap<String, GpuVideoDecoder>()
    private val main = Handler(Looper.getMainLooper())
    private var running = false

    /** Ensure the GL thread + EGL context exist. Safe to call repeatedly. */
    fun ensure() {
        synchronized(lock) {
            if (running) return
            val t = HandlerThread("gpu-video")
            t.start()
            thread = t
            handler = Handler(t.looper)
            val ready = java.util.concurrent.CountDownLatch(1)
            var err: Exception? = null
            handler!!.post {
                try {
                    val core = EglCore()
                    core.init()
                    egl = core
                    blit = GlUtil.OesToBitmap()
                    blit!!.ensureProgram()
                } catch (e: Exception) {
                    err = e
                } finally {
                    ready.countDown()
                }
            }
            try { ready.await() } catch (_: Exception) { }
            if (err != null) {
                try { t.quitSafely() } catch (_: Exception) { }
                thread = null; handler = null
                throw err!!
            }
            running = true
        }
    }

    fun post(r: Runnable) {
        ensure()
        handler?.post(r)
    }

    fun runSync(timeoutMs: Long = 4000L, block: () -> Unit) {
        ensure()
        val h = handler ?: return
        if (Looper.myLooper() == h.looper) {
            block(); return
        }
        val done = java.util.concurrent.CountDownLatch(1)
        h.post {
            try { block() } finally { done.countDown() }
        }
        try { done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) { }
    }

    fun getOrCreate(id: String, path: String): GpuVideoDecoder {
        ensure()
        val existing = decoders[id]
        if (existing != null && existing.path == path) return existing
        existing?.let { releaseDecoder(id) }
        val d = GpuVideoDecoder(id, path)
        decoders[id] = d
        runSync { d.open() }
        return d
    }

    fun decoder(id: String): GpuVideoDecoder? = decoders[id]

    fun releaseDecoder(id: String) {
        val d = decoders.remove(id) ?: return
        runSync { d.close() }
    }

    fun releaseAll() {
        synchronized(lock) {
            if (!running) return
            val ids = ArrayList(decoders.keys)
            for (id in ids) {
                val d = decoders.remove(id)
                runSync { d?.close() }
            }
            runSync {
                try { blit?.release() } catch (_: Exception) { }
                blit = null
                try { egl?.release() } catch (_: Exception) { }
                egl = null
            }
            try { thread?.quitSafely() } catch (_: Exception) { }
            thread = null
            handler = null
            running = false
        }
    }

    internal fun blit(): GlUtil.OesToBitmap? = blit
    internal fun egl(): EglCore? = egl
    internal fun mainHandler(): Handler = main
}

/**
 * One continuous MediaCodec decoder for a single media file.
 *
 * Call [advanceTo] with the desired media time each preview tick while playing
 * (or once on seek). The decoder feeds packets forward until the target is
 * reached, presents on the OES surface, and blits to a reusable Bitmap.
 */
class GpuVideoDecoder(
    val id: String,
    val path: String
) {
    @Volatile private var open = false
    @Volatile private var broken = false
    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var target: OesSurfaceTarget? = null
    private var trackIdx = -1
    private var mime: String? = null
    private var srcW = 0
    private var srcH = 0
    private var rotation = 0
    private var durationUs = 0L

    private var inputDone = false
    private var outputDone = false
    private var sawOutput = false
    private var lastPtsUs = -1L
    private var pendingSeekUs = -1L
    private var maxPx = 720

    /** double-buffer so the UI can draw one while we blit into the other */
    private var bufA: Bitmap? = null
    private var bufB: Bitmap? = null
    private var writeA = true
    @Volatile private var published: Bitmap? = null
    @Volatile var lastDecodeMs = 0L
        private set
    @Volatile var produced = false
        private set

    private val info = MediaCodec.BufferInfo()
    private val busy = AtomicBoolean(false)

    fun open() {
        if (open || broken) return
        try {
            GpuVideoPipeline.egl()?.makeCurrent()
            val ex = MediaExtractor()
            ex.setDataSource(path)
            var tIdx = -1
            var m: String? = null
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mm = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mm.startsWith("video/")) {
                    tIdx = i; m = mm; fmt = f; break
                }
            }
            if (tIdx < 0 || m == null || fmt == null) {
                ex.release(); broken = true; return
            }
            ex.selectTrack(tIdx)
            trackIdx = tIdx
            mime = m
            srcW = if (fmt.containsKey(MediaFormat.KEY_WIDTH)) fmt.getInteger(MediaFormat.KEY_WIDTH) else 0
            srcH = if (fmt.containsKey(MediaFormat.KEY_HEIGHT)) fmt.getInteger(MediaFormat.KEY_HEIGHT) else 0
            rotation = when {
                fmt.containsKey(MediaFormat.KEY_ROTATION) -> fmt.getInteger(MediaFormat.KEY_ROTATION)
                fmt.containsKey("rotation-degrees") -> fmt.getInteger("rotation-degrees")
                else -> 0
            }
            durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L

            val oes = OesSurfaceTarget()
            if (srcW > 0 && srcH > 0) oes.setDefaultBufferSize(srcW, srcH)

            val dec = try {
                MediaCodec.createDecoderByType(m)
            } catch (_: Exception) {
                oes.release(); ex.release(); broken = true; return
            }
            try {
                // Prefer low-latency when available (API 30+)
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    try { fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1) } catch (_: Exception) { }
                }
                dec.configure(fmt, oes.surface, null, 0)
                dec.start()
            } catch (_: Exception) {
                try { dec.release() } catch (_: Exception) { }
                oes.release(); ex.release(); broken = true; return
            }

            extractor = ex
            codec = dec
            target = oes
            open = true
            inputDone = false
            outputDone = false
            sawOutput = false
            lastPtsUs = -1L
        } catch (_: Exception) {
            broken = true
            close()
        }
    }

    fun close() {
        open = false
        try { codec?.stop() } catch (_: Exception) { }
        try { codec?.release() } catch (_: Exception) { }
        codec = null
        try { extractor?.release() } catch (_: Exception) { }
        extractor = null
        try { target?.release() } catch (_: Exception) { }
        target = null
        try { bufA?.recycle() } catch (_: Exception) { }
        try { bufB?.recycle() } catch (_: Exception) { }
        bufA = null; bufB = null
        published = null
        broken = true
    }

    /** Latest decoded bitmap (may be null before first frame). UI-thread safe. */
    fun currentBitmap(): Bitmap? = published

    /**
     * Drive the decoder toward media time [mediaMs].
     *  - continuous play: call every tick with advancing times (no seek)
     *  - scrub / jump: large backward/forward deltas trigger seekTo
     * [maxSide] caps the bitmap longest side (preview adaptive quality).
     * Returns true if a new frame was published.
     *
     * MUST run on the GPU pipeline thread.
     */
    fun advanceTo(mediaMs: Long, maxSide: Int, forceSeek: Boolean = false): Boolean {
        if (broken) return false
        if (!open) open()
        if (!open || broken) return false
        if (!busy.compareAndSet(false, true)) return false
        val t0 = SystemClock.elapsedRealtime()
        try {
            GpuVideoPipeline.egl()?.makeCurrent()
            maxPx = maxSide.coerceIn(240, 1920)
            val targetUs = (mediaMs.coerceAtLeast(0L) * 1000L)
            val needSeek = forceSeek ||
                lastPtsUs < 0L ||
                targetUs + 80_000L < lastPtsUs ||          // jump backward
                (targetUs - lastPtsUs) > 1_200_000L        // big forward gap
            if (needSeek) seekInternal(targetUs)

            // Drain until we have a frame at/near target, or stall budget hits
            var frames = 0
            val budget = if (forceSeek) 48 else 12
            while (frames < budget) {
                feedInput()
                val got = drainOutput(targetUs)
                if (got) {
                    frames++
                    // For scrubbing, stop at first frame past/near target
                    if (forceSeek && lastPtsUs >= targetUs - 40_000L) break
                    // For playback, stop once we're at/past the requested time
                    if (!forceSeek && lastPtsUs >= targetUs - 15_000L) break
                } else if (outputDone) {
                    // EOS: loop handled by caller via media time wrap
                    break
                } else {
                    // no output this round — one more feed attempt then quit
                    feedInput()
                    if (!drainOutput(targetUs)) break
                    frames++
                }
            }

            val oes = target ?: return false
            // Wait briefly for SurfaceTexture frame-available if codec released a buffer
            var wait = 0
            while (!oes.frameAvailable && wait < 8) {
                try { Thread.sleep(2) } catch (_: Exception) { }
                wait++
            }
            if (!oes.frameAvailable && lastPtsUs < 0) return false
            val st = oes.updateTexImage()
            val (dw, dh) = displaySize()
            val (ow, oh) = GlUtil.fitSize(dw, dh, maxPx)
            val blit = GpuVideoPipeline.blit() ?: return false
            // Blit into the back buffer so the front buffer the UI is drawing
            // is never rewritten mid-frame.
            val back = if (writeA) bufA else bufB
            val bmp = blit.draw(oes.texId, st, ow, oh, back) ?: return false
            if (writeA) {
                if (bufA != null && bufA !== bmp) try { bufA?.recycle() } catch (_: Exception) { }
                bufA = bmp
            } else {
                if (bufB != null && bufB !== bmp) try { bufB?.recycle() } catch (_: Exception) { }
                bufB = bmp
            }
            writeA = !writeA
            // Publish a stable reference — compositor must not recycle this;
            // we own both buffers and swap the published pointer only.
            published = bmp
            produced = true
            lastDecodeMs = SystemClock.elapsedRealtime() - t0
            return true
        } catch (_: Exception) {
            return false
        } finally {
            busy.set(false)
        }
    }

    private fun displaySize(): Pair<Int, Int> {
        return if (rotation == 90 || rotation == 270) Pair(srcH.coerceAtLeast(2), srcW.coerceAtLeast(2))
        else Pair(srcW.coerceAtLeast(2), srcH.coerceAtLeast(2))
    }

    private fun seekInternal(targetUs: Long) {
        val ex = extractor ?: return
        val c = codec ?: return
        try {
            ex.seekTo(targetUs.coerceAtLeast(0L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            c.flush()
        } catch (_: Exception) {
            try { c.flush() } catch (_: Exception) { }
        }
        inputDone = false
        outputDone = false
        lastPtsUs = -1L
        pendingSeekUs = targetUs
    }

    private fun feedInput() {
        if (inputDone) return
        val c = codec ?: return
        val ex = extractor ?: return
        val inIdx = try { c.dequeueInputBuffer(0) } catch (_: Exception) { -1 }
        if (inIdx < 0) return
        val buf = try { c.getInputBuffer(inIdx) } catch (_: Exception) { null } ?: return
        val n = try { ex.readSampleData(buf, 0) } catch (_: Exception) { -1 }
        if (n < 0) {
            try {
                c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            } catch (_: Exception) { }
            inputDone = true
        } else {
            val pts = ex.sampleTime
            try {
                c.queueInputBuffer(inIdx, 0, n, pts, 0)
            } catch (_: Exception) { }
            try { ex.advance() } catch (_: Exception) { }
        }
    }

    /**
     * Drain one output buffer. Returns true if a frame was released to the surface.
     * Drops frames that are still well behind the target during catch-up.
     */
    private fun drainOutput(targetUs: Long): Boolean {
        val c = codec ?: return false
        val outIdx = try { c.dequeueOutputBuffer(info, 2_000) } catch (_: Exception) { return false }
        when {
            outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
            outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                try {
                    val of = c.outputFormat
                    if (of.containsKey(MediaFormat.KEY_WIDTH)) srcW = of.getInteger(MediaFormat.KEY_WIDTH)
                    if (of.containsKey(MediaFormat.KEY_HEIGHT)) srcH = of.getInteger(MediaFormat.KEY_HEIGHT)
                    if (of.containsKey("rotation-degrees")) rotation = of.getInteger("rotation-degrees")
                } catch (_: Exception) { }
                return false
            }
            outIdx >= 0 -> {
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (eos) outputDone = true
                val pts = info.presentationTimeUs
                // Drop frames far behind target during catch-up after seek / lag
                val behind = targetUs - pts
                val render = info.size > 0 && (behind < 120_000L || lastPtsUs < 0L || pendingSeekUs >= 0)
                try {
                    c.releaseOutputBuffer(outIdx, render)
                } catch (_: Exception) {
                    try { c.releaseOutputBuffer(outIdx, false) } catch (_: Exception) { }
                    return false
                }
                if (render) {
                    lastPtsUs = pts
                    sawOutput = true
                    if (pendingSeekUs >= 0 && pts + 30_000L >= pendingSeekUs) pendingSeekUs = -1L
                    return true
                }
                return false
            }
            else -> return false
        }
    }
}
