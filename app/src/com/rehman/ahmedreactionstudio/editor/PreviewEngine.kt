package com.rehman.ahmedreactionstudio.editor

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.rehman.ahmedreactionstudio.core.Layer
import com.rehman.ahmedreactionstudio.core.MediaKit
import com.rehman.ahmedreactionstudio.core.Project
import com.rehman.ahmedreactionstudio.core.gpu.GpuVideoDecoder
import com.rehman.ahmedreactionstudio.core.gpu.GpuVideoPipeline
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min

/**
 * Runs the editor "clock" and preview media.
 *
 * Semantics (spec 13/14):
 *  - one master clock (monotonic elapsed time deltas)
 *  - every video layer has an INDEPENDENT play state:
 *      playing ? media = freeze + (master - resumeAt) * speed
 *      paused  ? media = freeze                 (layer frozen, others keep going)
 *  - audio is optional playback of the same source tied to the layer state
 *
 * Decode path (Option A — continuous HW decode, MX-Player style):
 *  - each clip uses [GpuVideoDecoder]: MediaExtractor → MediaCodec → OES Surface
 *    → GL blit → Bitmap, NOT MediaMetadataRetriever seek-grabs
 *  - frames stream forward while playing; seeks only on scrub / big jumps
 *  - decodes run on the shared GPU thread and are COALESCED per layer
 *  - MediaPlayer.prepare()/seekTo() never run on the UI thread
 *
 * Fallback: if GPU open fails for a file, that layer uses a cached
 * [MediaKit.FrameSource] so the app never goes blank.
 */
class PreviewEngine(
    private val ctx: Context,
    private val projectRef: () -> Project,
    private val store: com.rehman.ahmedreactionstudio.core.ProjectStore,
    private val onFrameReady: (Long) -> Unit   // master time callback -> UI updates
) {
    class Clock {
        var freeze: Long = 0L          // media position while paused (or resume anchor)
        var resumeAt: Long = 0L        // master time the layer last resumed
        var wasPlaying = false
        fun media(master: Long, speed: Float): Long {
            val m = if (wasPlaying) freeze + ((master - resumeAt).coerceAtLeast(0L) * speed).toLong()
            else freeze
            return m.coerceAtLeast(0L)
        }
        fun resume(master: Long) { resumeAt = master; wasPlaying = true }
        fun pause(master: Long) { freeze = media(master, 1f); wasPlaying = false }
        /** scrub to master time WITHOUT changing the play/pause state */
        fun seek(master: Long, speed: Float) { freeze = (master * speed).toLong(); resumeAt = master }
    }

    companion object {
        /**
         * ~60 Hz. The master clock and the transport stay smooth, and the
         * decoder's own pacing check makes a tick that has no new frame due
         * essentially free — it returns the frame it already published instead
         * of re-decoding it.
         */
        private const val TICK_MS = 16L
        private const val MIN_DECODE_PX = 240
        /** playback opens at this fraction of the layer's ideal size */
        private const val START_SCALE = 0.8f
    }

    private val clocks = HashMap<String, Clock>()
    private val players = HashMap<String, MediaPlayer>()
    private val frames = HashMap<String, Bitmap>()   // last decoded frame per media layer (main thread only)
    private val gpuIds = ConcurrentHashMap.newKeySet<String>()
    private val fallback = ConcurrentHashMap<String, MediaKit.FrameSource>()
    private val decoding = ConcurrentHashMap.newKeySet<String>()
    private val preparing = ConcurrentHashMap.newKeySet<String>()
    private val paths = HashMap<String, String>()
    private val lastSeekAt = HashMap<String, Long>()
    /**
     * Software fallback is a last resort, not a life sentence. One transient
     * GPU hiccup used to demote a layer to MediaMetadataRetriever forever —
     * which is most of what "the preview is rubbish" felt like. Keep retrying
     * the hardware path with a short back-off.
     */
    private val softStreak = ConcurrentHashMap<String, Int>()
    private val gpuRetryAt = ConcurrentHashMap<String, Long>()
    /** layer ids that need a force-seek on the next decode (after scrub) */
    private val forceSeek = ConcurrentHashMap.newKeySet<String>()

    /**
     * Layers whose frames are pushed from OUTSIDE (the live camera owns and
     * reuses its bitmaps). The engine must never recycle those — doing so
     * would tear down a buffer the producer is still drawing into.
     */
    private val externalIds = HashSet<String>()

    /**
     * Still-image layers. They are not clips (no clock, no decoder), so before
     * this the preview never had a bitmap for them: an added image was an
     * invisible box you could select but not see until export. Decoded once,
     * off the main thread, keyed by id; the request set stops duplicates.
     */
    private val imageLoading = ConcurrentHashMap.newKeySet<String>()
    private val imageIds = HashSet<String>()

    private val exec = Executors.newFixedThreadPool(2)
    private val handler = Handler(Looper.getMainLooper())
    private var masterMs = 0L
    private var lastTick = 0L
    private var lastAdapt = 0L
    private var ticking = false
    private var snapshotLoop = false
    private var projectId: String = ""

    /** longest side the preview asks the decoder for; the editor sets it from the stage size */
    @Volatile
    var targetMaxPx = 720

    /**
     * On-screen size (longest side, device pixels) of one layer, used to decode
     * each clip at the size the compositor will actually DRAW it. Optional:
     * when null every layer falls back to [targetMaxPx].
     */
    var layerTargetPx: ((Layer) -> Int)? = null

    /** adapts down while decoding cannot keep up, back up when it can */
    @Volatile
    private var adaptiveScale = START_SCALE
    private var avgDecodeMs = 0f
    private val newFrames = AtomicBoolean(false)

    // ---------- preview health (surface it in the editor, don't guess) ----------
    private var fpsCount = 0
    private var fpsSince = 0L
    /** decodes that actually reported a cost during the current window */
    private var costCount = 0
    @Volatile private var previewFps = 0f
    /** layers currently stuck on the software retriever instead of MediaCodec */
    private val softNow = ConcurrentHashMap.newKeySet<String>()

    private val tick = object : Runnable {
        override fun run() {
            if (!ticking) return
            val now = SystemClock.elapsedRealtime()
            val dt = min(now - lastTick, 120L)
            lastTick = now
            if (now - fpsSince >= 500L) {
                previewFps = fpsCount * 1000f / (now - fpsSince).coerceAtLeast(1L)
                fpsCount = 0
                fpsSince = now
            }
            if (anyPlaying()) {
                masterMs += dt
                val dur = project().durationMs()
                if (dur > 0 && masterMs >= dur) masterMs %= dur
                endOfMediaCheck()
                syncPlayers(now)
                // snapshotLoop alone was not enough: playing a SINGLE layer
                // from the source dock never called startSnapshots(), so the
                // clip drew one frame and then froze. Any playing source needs
                // continuous decode.
                if (snapshotLoop || anyPlaying()) requestFrames(closest = false)
                if (now - lastAdapt > 1000L) { lastAdapt = now; adaptQuality() }
            }
            onFrameReady(masterMs)
            handler.postDelayed(this, TICK_MS)
        }
    }

    /**
     * OBS plan §2: a non-looping video that reaches its end HOLDS its last
     * frame and auto-pauses (it never silently restarts). Pressing play on an
     * ended source restarts it from 0:00 — handled in [toggleLayerPlay].
     */
    private fun endOfMediaCheck() {
        val p = project()
        for (l in p.layers) {
            if (!l.isClip() || !l.playing || l.loop || l.durMs <= 0L) continue
            val t = clocks[l.id]?.media(masterMs, l.speed) ?: continue
            if (t >= l.durMs) {
                l.playing = false
                l.pausedMediaMs = l.durMs
                clocks[l.id]?.let { c -> c.wasPlaying = false; c.freeze = l.durMs }
                playerOf(l.id)?.let { try { it.pause() } catch (_: Exception) { } }
            }
        }
    }

    /** audio-solo: while any source is soloed, every other source is muted */
    private fun effectiveMuted(l: Layer): Boolean {
        if (l.muted) return true
        val p = project()
        val anySolo = p.layers.any { it.solo }
        return anySolo && !l.solo
    }

    /**
     * Preview-monitor mute. While the composite RECORD runs, the recorder
     * mixes the clips' decoded PCM itself; playing the same clips through the
     * speaker at the same time (a) competes for the audio output and (b) is
     * picked up by the microphone as a delayed, muffled copy of the clip
     * ("echo"/"double audio"). Muting the monitor keeps the master clock,
     * the decoders and the play state exactly as they are — only the
     * MediaPlayer output goes silent.
     */
    @Volatile var monitorMuted = false
        set(value) {
            if (field == value) return
            field = value
            handler.post { applyMonitorVolume() }
        }

    private fun applyMonitorVolume() {
        val p = project()
        for ((id, mp) in players) {
            val l = p.layerById(id) ?: continue
            val v = if (monitorMuted) 0f else l.volume
            try { mp.setVolume(v, v) } catch (_: Exception) { }
        }
    }

    fun attach(projectId: String) {
        val wasTicking = ticking
        val wantSnapshots = snapshotLoop
        detach()
        this.projectId = projectId
        val p = project()
        clocks.clear()
        paths.clear()
        val live = HashSet<String>()
        for (l in p.layers) {
            live.add(l.id)
            if (!l.isClip()) continue
            val c = Clock()
            c.freeze = l.pausedMediaMs
            c.wasPlaying = l.playing
            c.resumeAt = 0L
            clocks[l.id] = c
            pathOf(l)?.let { paths[l.id] = it }
        }
        // drop decoders of layers that are gone
        for (id in ArrayList(gpuIds)) if (!live.contains(id)) releaseSource(id)
        for (id in ArrayList(fallback.keys)) if (!live.contains(id)) releaseSource(id)
        // Adding a source used to stop the ticker (detach() does). A newly
        // added clip with playing=true then decoded one (often black PBO)
        // frame and froze. Restore continuous decode when anything is playing.
        if (wasTicking || wantSnapshots || anyPlaying()) {
            if (wantSnapshots) snapshotLoop = true
            startTicker()
        }
    }

    fun detach() {
        stopTicker()
        stopSnapshots()
        releaseAllPlayers()
        recycleFrames()
        for (id in ArrayList(gpuIds)) releaseSource(id)
        for (id in ArrayList(fallback.keys)) releaseSource(id)
        clocks.clear()
        paths.clear()
    }

    private fun project(): Project = projectRef()

    fun master(): Long = masterMs

    /** true once since the last call whenever a decoded frame landed */
    fun consumeNewFrames(): Boolean = newFrames.getAndSet(false)

    /**
     * Live preview health for the editor HUD. This exists because "the preview
     * is garbage" is untestable from the outside: the number that matters is
     * whether a clip is on the hardware MediaCodec path or has been pushed onto
     * the (10-20x slower) MediaMetadataRetriever fallback.
     */
    fun stats(): String {
        val path = if (softNow.isEmpty()) "HW" else "SW×${softNow.size}"
        return "$path · ${previewFps.toInt()} fps · ${avgDecodeMs.toInt()} ms/f"
    }

    fun seekTo(ms: Long) {
        masterMs = ms.coerceAtLeast(0L)
        val p = project()
        p.lastPlayheadMs = masterMs
        for (l in p.layers) if (l.isClip()) {
            clocks[l.id]?.seek(masterMs, l.speed)
            forceSeek.add(l.id)
        }
        syncPlayers(SystemClock.elapsedRealtime())
        refreshFrames()
    }

    fun playAll() {
        adaptiveScale = START_SCALE
        val p = project()
        for (l in p.layers) if (l.isClip()) {
            val c = clocks[l.id] ?: continue
            // an ended non-looping clip restarts from 0:00 on play-all
            if (!l.loop && l.durMs > 0 && c.freeze >= l.durMs - 80) { c.freeze = 0L; l.pausedMediaMs = 0L; forceSeek.add(l.id) }
            l.playing = true
            c.resume(masterMs)
        }
        startTicker()
        refreshFrames()
    }

    fun pauseAll() {
        val p = project()
        for (l in p.layers) if (l.isClip()) {
            l.playing = false
            clocks[l.id]?.pause(masterMs)
        }
        pausePlayers()
    }

    fun toggleLayerPlay(l: Layer): Boolean {
        // returns new state
        if (!l.isClip()) return l.playing
        if (l.playing) {
            l.playing = false
            clocks[l.id]?.pause(masterMs)
            l.pausedMediaMs = clocks[l.id]?.freeze ?: 0L
            playerOf(l.id)?.let { try { it.pause() } catch (_: Exception) { } }
        } else {
            val c = clocks[l.id]
            // ended (non-loop) clip restarts from 0:00; paused clips resume
            if (c != null && !l.loop && l.durMs > 0 && c.freeze >= l.durMs - 80) {
                c.freeze = 0L
                forceSeek.add(l.id)
            }
            l.playing = true
            l.pausedMediaMs = 0L
            c?.resume(masterMs)
            startTicker()
        }
        refreshFrames()
        return l.playing
    }

    /**
     * OBS rule: visibility is visual-only — a hidden-but-playing source still
     * drives the master clock (and keeps its audio, see syncPlayers).
     */
    fun anyPlaying(): Boolean {
        val p = project()
        for (l in p.layers) if (l.isClip() && l.playing) return true
        return false
    }

    private fun startTicker() {
        if (ticking) return
        ticking = true
        lastTick = SystemClock.elapsedRealtime()
        handler.post(tick)
    }

    private fun stopTicker() { ticking = false; handler.removeCallbacks(tick) }

    private fun pathOf(l: Layer): String? {
        val rel = l.relPath ?: return null
        if (rel.isBlank()) return null
        val f = File(store.projectDir(projectId), rel)
        return if (f.exists()) f.absolutePath else null
    }

    private fun ensureGpu(id: String, path: String): GpuVideoDecoder? {
        return try {
            val d = GpuVideoPipeline.getOrCreate(id, path)
            gpuIds.add(id)
            // drop any leftover software fallback for this id
            fallback.remove(id)?.release()
            d
        } catch (_: Exception) {
            null
        }
    }

    private fun ensureFallback(id: String, path: String): MediaKit.FrameSource {
        val cached = fallback[id]
        if (cached != null && cached.path == path) return cached
        cached?.release()
        val s = MediaKit.FrameSource(path)
        fallback[id] = s
        return s
    }

    private fun releaseSource(id: String) {
        gpuIds.remove(id)
        forceSeek.remove(id)
        decoding.remove(id)
        softStreak.remove(id)
        gpuRetryAt.remove(id)
        softNow.remove(id)
        try { GpuVideoPipeline.releaseDecoder(id) } catch (_: Exception) { }
        fallback.remove(id)?.let { try { it.release() } catch (_: Exception) { } }
    }

    // ---------- audio (never on the UI thread) ----------

    private fun ensureAudioPlayer(l: Layer) {
        if (effectiveMuted(l) || !l.isClip()) return
        if (players.containsKey(l.id) || preparing.contains(l.id)) return
        val path = paths[l.id] ?: pathOf(l) ?: return
        preparing.add(l.id)
        val id = l.id
        val vol = l.volume
        val loop = l.loop
        exec.execute {
            var mp: MediaPlayer? = null
            try {
                mp = MediaPlayer()
                mp.setDataSource(path)
                mp.isLooping = loop
                val mv = if (monitorMuted) 0f else vol
                mp.setVolume(mv, mv)
                mp.prepare()
            } catch (_: Exception) {
                try { mp?.release() } catch (_: Exception) { }
                mp = null
            }
            val ready = mp
            handler.post {
                preparing.remove(id)
                if (ready == null) return@post
                val old = players.put(id, ready)
                try { old?.release() } catch (_: Exception) { }
                val lay = project().layerById(id)
                if (lay == null || !lay.playing || effectiveMuted(lay)) {
                    try { ready.pause() } catch (_: Exception) { }
                } else {
                    try {
                        ready.seekTo(mediaTimeOf(lay).toInt())
                        ready.start()
                    } catch (_: Exception) { }
                }
            }
        }
    }

    private fun syncPlayers(now: Long) {
        val p = project()
        for (l in p.layers) {
            if (!l.isClip()) continue
            // audio follows play + mute/solo ONLY. Hiding a source does not
            // silence it (OBS rule, plan §2).
            val playingNow = l.playing && !effectiveMuted(l)
            val mp = players[l.id]
            if (playingNow) {
                if (mp == null) { ensureAudioPlayer(l); continue }
                try { mp.isLooping = l.loop } catch (_: Exception) { }
                try {
                    if (!mp.isPlaying) {
                        // don't restart a non-looping clip that already ended
                        val t = mediaTimeOf(l)
                        if (l.loop || l.durMs <= 0 || t < l.durMs - 80) {
                            mp.seekTo(t.toInt())
                            mp.start()
                        }
                    } else if (now - (lastSeekAt[l.id] ?: 0L) > 1200L) {
                        // drift guard: re-anchor only when clearly off, and never
                        // more than about once a second (repeated seeks stutter)
                        val target = mediaTimeOf(l)
                        val cur = mp.currentPosition.toLong()
                        if (abs(cur - target) > 400) {
                            mp.seekTo(target.toInt())
                            lastSeekAt[l.id] = now
                        }
                    }
                } catch (_: Exception) { }
            } else if (mp != null) {
                try { if (mp.isPlaying) mp.pause() } catch (_: Exception) { }
            }
        }
    }

    private fun pausePlayers() {
        for ((_, mp) in players) { try { if (mp.isPlaying) mp.pause() } catch (_: Exception) { } }
    }

    fun releaseAllPlayers() {
        for ((_, mp) in players) { try { mp.release() } catch (_: Exception) { } }
        players.clear()
        preparing.clear()
        lastSeekAt.clear()
    }

    fun playerOf(id: String): MediaPlayer? = players[id]

    /** media time (ms) of a layer right now; looping clips wrap at their end */
    fun mediaTimeOf(l: Layer): Long {
        var t = clocks[l.id]?.media(masterMs, l.speed) ?: 0L
        if (l.loop && l.durMs > 0) t %= l.durMs
        return t
    }

    fun setVolume(l: Layer, v: Float) {
        l.volume = v
        val mv = if (monitorMuted) 0f else v
        try { players[l.id]?.setVolume(mv, mv) } catch (_: Exception) { }
    }

    // ---------- frame snapshots ----------

    /** Re-decode every visible video layer right now (used on seek / play / pause). */
    fun refreshFrames() {
        val p = project()
        var any = false
        for (l in p.layers) if (l.isClip() && l.visible && !l.relPath.isNullOrBlank()) {
            any = true
            paths[l.id] = pathOf(l) ?: continue
            forceSeek.add(l.id)
        }
        if (!any) { onFrameReady(masterMs); return }
        requestFrames(closest = true)
    }

    /** Continuous decoding while the master clock runs. */
    fun startSnapshots() {
        snapshotLoop = true
        startTicker()
    }

    fun stopSnapshots() { snapshotLoop = false }

    /**
     * Queue one decode per layer for its current media time. In-flight layers
     * are skipped, so a slow decoder degrades the frame rate instead of
     * queueing up work and dragging the whole preview behind it.
     */
    private fun requestFrames(closest: Boolean) {
        val p = project()
        for (l in p.layers) {
            if (!l.isClip() || !l.visible || l.relPath.isNullOrBlank()) continue
            if (!closest && !l.playing) continue
            val id = l.id
            val path = paths[id] ?: pathOf(l) ?: continue
            val t = mediaTimeOf(l)
            if (!decoding.add(id)) continue
            val force = forceSeek.remove(id) || closest
            val px = maxPxFor(l)
            // GPU path prefers the dedicated GL thread; fallback uses the pool
            val useGpu = gpuIds.contains(id) ||
                !fallback.containsKey(id) ||
                SystemClock.elapsedRealtime() >= (gpuRetryAt[id] ?: 0L)
            if (useGpu) {
                GpuVideoPipeline.post {
                    var ok = false
                    var cost = 0L
                    var bmp: Bitmap? = null
                    var soft = false
                    try {
                        val d = ensureGpu(id, path)
                        if (d != null) {
                            ok = d.advanceTo(t, px, forceSeek = force, paced = true)
                            cost = d.lastDecodeMs
                            bmp = d.currentBitmap()
                        }
                    } catch (_: Exception) {
                        ok = false
                    }
                    if (!ok || bmp == null) {
                        // soft-fail into the retriever fallback for this frame
                        try {
                            val src = ensureFallback(id, path)
                            bmp = src.frameAt(t, px, closest = force)
                            cost = src.lastDecodeMs
                            soft = true
                        } catch (_: Exception) { }
                    }
                    val out = bmp
                    val c = cost
                    val isSoft = soft
                    if (isSoft) {
                        val n = (softStreak[id] ?: 0) + 1
                        softStreak[id] = n
                        gpuRetryAt[id] = SystemClock.elapsedRealtime() + n.coerceAtMost(6) * 1000L
                    } else {
                        softStreak.remove(id)
                        gpuRetryAt.remove(id)
                    }
                    handler.post {
                        if (c > 0) {
                            costCount++
                            if (avgDecodeMs <= 0f) avgDecodeMs = c.toFloat()
                            else avgDecodeMs = avgDecodeMs * 0.7f + c * 0.3f
                        }
                        if (out != null) {
                            if (isSoft) publishSoftware(id, out)
                            // GPU bitmaps are owned by the decoder; publishGpu
                            // must not recycle them
                            else publishGpu(id, out)
                        }
                        decoding.remove(id)
                    }
                }
            } else {
                exec.execute {
                    try {
                        val src = ensureFallback(id, path)
                        val bmp = src.frameAt(t, px, closest = force)
                        val cost = src.lastDecodeMs
                        handler.post {
                            if (avgDecodeMs <= 0f) avgDecodeMs = cost.toFloat()
                            else avgDecodeMs = avgDecodeMs * 0.7f + cost * 0.3f
                            if (bmp != null) publishSoftware(id, bmp)
                        }
                    } catch (_: Exception) {
                    } finally {
                        decoding.remove(id)
                    }
                }
            }
        }
    }

    /**
     * GPU frames: the decoder owns the bitmap, so the reference is swapped and
     * never recycled. If the reference is unchanged the tick produced the very
     * same frame (the decoder's pacing check held it), and the stage is NOT
     * invalidated — otherwise a 60 Hz clock would force 60 pointless redraws of
     * an identical composition every second.
     */
    private fun publishGpu(id: String, bmp: Bitmap) {
        if (project().layerById(id) == null) return
        val prev = frames.put(id, bmp)
        if (prev === bmp) return
        softNow.remove(id)
        fpsCount++
        newFrames.set(true)
        onFrameReady(masterMs)
    }

    /** Software frames: we own them, so the previous one is recycled. */
    private fun publishSoftware(id: String, bmp: Bitmap) {
        if (project().layerById(id) == null) { bmp.recycle(); return }
        val prev = frames.put(id, bmp)
        if (prev === bmp) return
        softNow.add(id)
        fpsCount++
        if (prev != null && !externalIds.contains(id) && !decoderOwns(id, prev)) {
            try { prev.recycle() } catch (_: Exception) { }
        }
        newFrames.set(true)
        onFrameReady(masterMs)
    }

    /** true when the layer's GPU decoder still owns [bmp] as a frame buffer */
    private fun decoderOwns(id: String, bmp: Bitmap): Boolean =
        try { GpuVideoPipeline.decoder(id)?.owns(bmp) == true } catch (_: Exception) { false }

    /**
     * Decode size for ONE layer.
     *
     * Every clip is decoded at the size the compositor will actually draw it,
     * not at a single global "preview resolution". A reaction PiP occupying
     * ~300 px of a 1080 px canvas used to be decoded at 960 px — about 10x more
     * pixels than could ever be shown, and every one of them paid for decode,
     * GL read-back and a bitmap copy. Per-layer sizing is the largest single
     * cut in per-frame work in the whole preview path.
     */
    private fun maxPxFor(l: Layer): Int {
        val hint = layerTargetPx?.invoke(l) ?: targetMaxPx
        val base = min(hint, targetMaxPx).coerceIn(MIN_DECODE_PX, 1920)
        // while paused / scrubbing there is time to be crisp; while playing,
        // staying fluid matters more than the last few pixels
        if (!anyPlaying()) return min(1440, (base * 1.35f).toInt()).coerceAtLeast(base)
        return (base * adaptiveScale).toInt().coerceIn(MIN_DECODE_PX, base)
    }

    /**
     * Keep the preview fluid on slow devices instead of dropping to 2 fps.
     * Playback opens slightly soft and only climbs to full size while the
     * decoder is comfortably ahead of the clock, so a weak phone never opens a
     * project straight into a stuttering preview.
     */
    private fun adaptQuality() {
        // A window with no real decode means every frame was served by the
        // decoder's pacing check — cheap by definition. Recover from an earlier
        // slow frame instead of leaving the preview stuck at reduced quality.
        val measured = costCount > 0
        costCount = 0
        adaptiveScale = when {
            !measured && adaptiveScale < 1f -> (adaptiveScale * 1.2f).coerceAtMost(1f)
            avgDecodeMs > 55f -> (adaptiveScale * 0.8f).coerceAtLeast(0.4f)
            avgDecodeMs in 0.5f..24f && adaptiveScale < 1f -> (adaptiveScale * 1.2f).coerceAtMost(1f)
            else -> adaptiveScale
        }
    }

    /** evict + recycle a layer's frame and decoder (main thread). */
    fun evict(id: String) {
        releaseSource(id)
        val external = externalIds.remove(id)
        imageIds.remove(id)
        imageLoading.remove(id)
        handler.post {
            val prev = frames.remove(id)
            // only recycle software / external-owned frames, never GPU-owned
            if (prev != null && !external && !gpuIds.contains(id)) {
                try { prev.recycle() } catch (_: Exception) { }
            }
            newFrames.set(true)
        }
    }

    fun frameOf(l: Layer): Bitmap? {
        val b = frames[l.id]
        if (b == null && l.type == com.rehman.ahmedreactionstudio.core.LayerType.IMAGE) requestImage(l)
        return b
    }

    /** Decode a still image for the preview on the pool; publish on main. */
    private fun requestImage(l: Layer) {
        val id = l.id
        val path = pathOf(l) ?: return
        if (!imageLoading.add(id)) return
        val px = (layerTargetPx?.invoke(l) ?: targetMaxPx).coerceIn(256, 2048)
        try {
            exec.execute {
                val bmp = try { MediaKit.image(path, px) } catch (_: Throwable) { null }
                handler.post {
                    imageLoading.remove(id)
                    if (bmp == null) return@post
                    val still = project().layerById(id)
                    if (still == null) { try { bmp.recycle() } catch (_: Exception) { }; return@post }
                    imageIds.add(id)
                    frames.put(id, bmp)?.let { old -> if (old !== bmp) try { old.recycle() } catch (_: Exception) { } }
                    newFrames.set(true)
                    onFrameReady(masterMs)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            imageLoading.remove(id)
        }
    }

    /**
     * Push a frame produced outside the engine (the live camera). The bitmap
     * stays owned by the producer: it is registered as external so no engine
     * path ever recycles it.
     */
    fun setFrame(l: Layer, bmp: Bitmap?) {
        if (bmp == null) return
        externalIds.add(l.id)
        frames.put(l.id, bmp)
        newFrames.set(true)
    }

    /** Stop treating [id] as externally fed (the producer released it). */
    fun clearExternal(id: String) {
        externalIds.remove(id)
        frames.remove(id)
        newFrames.set(true)
    }

    fun recycleFrames() {
        // Live-camera frames are owned by LiveCamera. Clearing them from the
        // map (the old code did `frames.clear()` even for external ids) is
        // why adding a local video after the camera made the camera go black
        // until the next ImageReader callback — which, under load, could be
        // never in time for the first recorded frames.
        val keep = HashMap<String, Bitmap>()
        for ((id, b) in frames) {
            if (externalIds.contains(id)) {
                keep[id] = b
                continue
            }
            if (gpuIds.contains(id)) continue  // decoder owns it
            try { b.recycle() } catch (_: Exception) { }
        }
        frames.clear()
        frames.putAll(keep)
        imageIds.clear()   // stills are re-decoded lazily on the next frameOf()
    }

    fun release() {
        detach()
        try { GpuVideoPipeline.releaseAll() } catch (_: Exception) { }
        exec.shutdown()
    }
}
