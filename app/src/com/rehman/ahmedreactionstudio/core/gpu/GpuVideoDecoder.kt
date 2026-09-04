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
        if (existing != null && existing.path == path && !existing.isBroken) return existing
        existing?.let { releaseDecoder(id) }
        val d = GpuVideoDecoder(id, path)
        decoders[id] = d
        runSync { d.open() }
        if (d.isBroken) {
            // Do not cache a decoder that could not open: a cached failure is
            // permanent, and one transient hiccup would silently demote that
            // layer to the software retriever for the rest of the session.
            decoders.remove(id)
            throw IllegalStateException("gpu decoder could not open: $path")
        }
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
                try { egl?.release() } catch (_: Exception) { }
                egl = null
            }
            try { thread?.quitSafely() } catch (_: Exception) { }
            thread = null
            handler = null
            running = false
        }
    }

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

    /** presentation timestamp of the frame currently held in [published] */
    @Volatile var publishedPtsUs = -1L
        private set
    /** running estimate of the media's frame interval (us), for pacing */
    @Volatile var avgFrameUs = 0L
        private set

    /**
     * This decoder's own blitter. A single shared blitter had to resize its FBO
     * (destroy + recreate a texture and a multi-megabyte pixel buffer) every
     * time two layers with different aspect ratios alternated — which is every
     * frame of a multi-layer project.
     */
    private var blit: GlUtil.OesToBitmap? = null

    private val info = MediaCodec.BufferInfo()
    private val busy = AtomicBoolean(false)

    private fun blitter(): GlUtil.OesToBitmap {
        blit?.let { return it }
        val b = GlUtil.OesToBitmap()
        b.ensureProgram()
        blit = b
        return b
    }

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
        try { blit?.release() } catch (_: Exception) { }
        blit = null
        try { bufA?.recycle() } catch (_: Exception) { }
        try { bufB?.recycle() } catch (_: Exception) { }
        bufA = null; bufB = null
        published = null
        publishedPtsUs = -1L
        avgFrameUs = 0L
        broken = true
    }

    /** Latest decoded bitmap (may be null before first frame). UI-thread safe. */
    fun currentBitmap(): Bitmap? = published

    /** true when the decoder could not open / hit a fatal error */
    val isBroken: Boolean get() = broken

    /**
     * true when [bmp] is one of this decoder's own double buffers, i.e. the
     * caller must NOT recycle it. Lets the preview engine recycle software
     * fallback frames safely even for a layer that also has a GPU decoder.
     */
    fun owns(bmp: Bitmap?): Boolean =
        bmp != null && (bmp === bufA || bmp === bufB || bmp === published)

    /**
     * Drive the decoder toward media time [mediaMs].
     *  - continuous play: call every tick with advancing times (no seek)
     *  - scrub / jump: large backward/forward deltas trigger seekTo
     * [maxSide] caps the bitmap longest side (preview adaptive quality).
     * [paced] enables the "this frame is still current — hand it back again"
     * short-circuit. It is a PREVIEW-ONLY optimisation: the exporter steps in
     * exact frame increments and would otherwise be served the same frame
     * twice, duplicating frames in the exported file.
     * Returns true if a frame is available for [mediaMs].
     *
     * MUST run on the GPU pipeline thread.
     */
    fun advanceTo(
        mediaMs: Long,
        maxSide: Int,
        forceSeek: Boolean = false,
        paced: Boolean = false
    ): Boolean {
        if (broken) return false
        maxPx = maxSide.coerceIn(240, 1920)
        if (!open) open()
        if (!open || broken) return false
        if (!busy.compareAndSet(false, true)) {
            // A decode is already in flight on this decoder. Reporting failure
            // here used to push the layer onto the software fallback for a
            // frame; the frame we already published is the better answer.
            return published != null
        }
        val t0 = SystemClock.elapsedRealtime()
        try {
            GpuVideoPipeline.egl()?.makeCurrent()
            val targetUs = (mediaMs.coerceAtLeast(0L) * 1000L)
            val needSeek = forceSeek ||
                lastPtsUs < 0L ||
                targetUs + 80_000L < lastPtsUs ||          // jump backward
                (targetUs - lastPtsUs) > 1_200_000L        // big forward gap

            if (paced && !needSeek && published != null && publishedPtsUs >= 0L) {
                // Frame pacing. The published frame is still the one that
                // belongs on screen for this media time, so decoding again
                // would spend the full GPU + read-back cost to produce a
                // pixel-identical bitmap. Without this the decoder walked
                // forward on every 16 ms tick regardless of the clip's real
                // cadence, which both starved the shared GL thread and made
                // the decoder race ahead of the clock.
                val interval = if (avgFrameUs > 0L) avgFrameUs else 33_333L
                if (targetUs < publishedPtsUs + interval) {
                    lastDecodeMs = 0L
                    return true
                }
            }

            if (needSeek) seekInternal(targetUs)

            // Drain until we have a frame at/near target, or the budget runs out
            var didRender = false
            var frames = 0
            val budget = if (needSeek) 64 else 12
            val slackUs = if (needSeek) 40_000L else 15_000L
            val timeoutUs = if (needSeek) 8_000L else 2_000L
            while (frames < budget) {
                feedInput()
                val got = drainOutput(targetUs, timeoutUs)
                if (got) {
                    didRender = true
                    frames++
                    // scrubbing: stop at the first frame at/past target.
                    // playback: stop as soon as we have caught up with the clock.
                    if (lastPtsUs >= targetUs - slackUs) break
                } else if (outputDone) {
                    // EOS: loop is handled by the caller via media-time wrap
                    break
                } else if (inputDone) {
                    // nothing left to feed and nothing ready — one last short
                    // drain so we never spin on a drained codec
                    if (!drainOutput(targetUs, timeoutUs)) break
                    didRender = true
                    frames++
                    if (lastPtsUs >= targetUs - slackUs) break
                }
            }

            if (!didRender) {
                if (paced) {
                    // Nothing new came out of the codec. During playback that is
                    // normal — the next frame simply is not due yet — so keep
                    // showing what we have instead of paying for a software
                    // decode (or, worse, for a pointless re-blit).
                    lastDecodeMs = 0L
                    return published != null && !needSeek
                }
                // Non-paced callers get the old behaviour: re-blit whatever the
                // surface is holding so they always receive a bitmap.
                if (published == null) return false
            }

            val oes = target ?: return false
            // Acquire the newest queued frame directly. Sleeping on the
            // onFrameAvailable flag cannot work: that listener is posted to
            // this very looper, so it can never be dispatched while we are
            // running on it — it only ever burned up to 16 ms and then blitted
            // a frame that was one tick stale.
            val st = oes.updateTexImage()
            val (dw, dh) = displaySize()
            val (ow, oh) = GlUtil.fitSize(dw, dh, maxPx)
            // Blit into the back buffer so the front buffer the UI is drawing
            // is never rewritten mid-frame.
            val back = if (writeA) bufA else bufB
            val bmp = blitter().draw(oes.texId, st, ow, oh, back) ?: return false
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
            publishedPtsUs = lastPtsUs
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
        publishedPtsUs = -1L
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
    private fun drainOutput(targetUs: Long, timeoutUs: Long): Boolean {
        val c = codec ?: return false
        val outIdx = try { c.dequeueOutputBuffer(info, timeoutUs) } catch (_: Exception) { return false }
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
                    // learn the media's real cadence so the pacing check above
                    // can hold a frame for exactly as long as it is current
                    if (lastPtsUs >= 0L && pts > lastPtsUs) {
                        val d = pts - lastPtsUs
                        if (d in 1_000L..200_000L) {
                            avgFrameUs = if (avgFrameUs <= 0L) d else (avgFrameUs * 3 + d) / 4
                        }
                    }
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
