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
 * Smoothness rules learned the hard way (imported clips used to stutter):
 *  - ONE cached [MediaKit.FrameSource] per layer instead of re-opening the
 *    container for every frame,
 *  - decodes run on a worker pool and are COALESCED: a layer never has two
 *    requests in flight and the clock never waits for a decoder,
 *  - frames are decoded at the preview size, and the size adapts down when
 *    the device cannot keep up,
 *  - MediaPlayer.prepare()/seekTo() never run on the UI thread and re-anchoring
 *    is rate limited, because a blocking prepare is a visible freeze.
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
        private const val TICK_MS = 33L
        private const val MIN_DECODE_PX = 480
    }

    private val clocks = HashMap<String, Clock>()
    private val players = HashMap<String, MediaPlayer>()
    private val frames = HashMap<String, Bitmap>()   // last decoded frame per media layer (main thread only)
    private val sources = ConcurrentHashMap<String, MediaKit.FrameSource>()
    private val decoding = ConcurrentHashMap.newKeySet<String>()
    private val preparing = ConcurrentHashMap.newKeySet<String>()
    private val paths = HashMap<String, String>()
    private val lastSeekAt = HashMap<String, Long>()

    /**
     * Layers whose frames are pushed from OUTSIDE (the live camera owns and
     * reuses its bitmaps). The engine must never recycle those — doing so
     * would tear down a buffer the producer is still drawing into.
     */
    private val externalIds = HashSet<String>()

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

    /** adapts down while decoding cannot keep up, back up when it can */
    @Volatile
    private var adaptiveMaxPx = 0
    private var avgDecodeMs = 0f
    private val newFrames = AtomicBoolean(false)

    private val tick = object : Runnable {
        override fun run() {
            if (!ticking) return
            val now = SystemClock.elapsedRealtime()
            val dt = min(now - lastTick, 120L)
            lastTick = now
            if (anyPlaying()) {
                masterMs += dt
                val dur = project().durationMs()
                if (dur > 0 && masterMs >= dur) masterMs %= dur
                endOfMediaCheck()
                syncPlayers(now)
                if (snapshotLoop) requestFrames(closest = false)
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

    fun attach(projectId: String) {
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
        for (id in ArrayList(sources.keys)) if (!live.contains(id)) releaseSource(id)
    }

    fun detach() {
        stopTicker()
        stopSnapshots()
        releaseAllPlayers()
        recycleFrames()
        for (id in ArrayList(sources.keys)) releaseSource(id)
        clocks.clear()
        paths.clear()
    }

    private fun project(): Project = projectRef()

    fun master(): Long = masterMs

    /** true once since the last call whenever a decoded frame landed */
    fun consumeNewFrames(): Boolean = newFrames.getAndSet(false)

    fun seekTo(ms: Long) {
        masterMs = ms.coerceAtLeast(0L)
        val p = project()
        p.lastPlayheadMs = masterMs
        for (l in p.layers) if (l.isClip()) clocks[l.id]?.seek(masterMs, l.speed)
        syncPlayers(SystemClock.elapsedRealtime())
        refreshFrames()
    }

    fun playAll() {
        val p = project()
        for (l in p.layers) if (l.isClip()) {
            val c = clocks[l.id] ?: continue
            // an ended non-looping clip restarts from 0:00 on play-all
            if (!l.loop && l.durMs > 0 && c.freeze >= l.durMs - 80) { c.freeze = 0L; l.pausedMediaMs = 0L }
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
            if (c != null && !l.loop && l.durMs > 0 && c.freeze >= l.durMs - 80) { c.freeze = 0L }
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

    private fun mediaFile(l: Layer): File? = pathOf(l)?.let { File(it) }

    private fun sourceFor(id: String, path: String): MediaKit.FrameSource {
        val cached = sources[id]
        if (cached != null && cached.path == path) return cached
        cached?.release()
        val s = MediaKit.FrameSource(path)
        sources[id] = s
        return s
    }

    private fun releaseSource(id: String) {
        sources.remove(id)?.let { try { it.release() } catch (_: Exception) { } }
        decoding.remove(id)
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
                mp.setVolume(vol, vol)
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
        try { players[l.id]?.setVolume(v, v) } catch (_: Exception) { }
    }

    // ---------- frame snapshots ----------

    /** Re-decode every visible video layer right now (used on seek / play / pause). */
    fun refreshFrames() {
        val p = project()
        var any = false
        for (l in p.layers) if (l.isClip() && l.visible && !l.relPath.isNullOrBlank()) {
            any = true
            paths[l.id] = pathOf(l) ?: continue
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
            exec.execute {
                try {
                    val src = sourceFor(id, path)
                    val bmp = src.frameAt(t, maxPxFor(), closest)
                    val cost = src.lastDecodeMs
                    handler.post {
                        if (avgDecodeMs <= 0f) avgDecodeMs = cost.toFloat()
                        else avgDecodeMs = avgDecodeMs * 0.7f + cost * 0.3f
                        if (bmp != null) publish(id, bmp)
                    }
                } catch (_: Exception) {
                } finally {
                    decoding.remove(id)
                }
            }
        }
    }

    private fun publish(id: String, bmp: Bitmap) {
        if (project().layerById(id) == null) { bmp.recycle(); return }
        val prev = frames.put(id, bmp)
        if (prev != null && prev !== bmp && !externalIds.contains(id)) prev.recycle()
        newFrames.set(true)
        onFrameReady(masterMs)
    }

    private fun maxPxFor(): Int {
        val base = targetMaxPx.coerceIn(MIN_DECODE_PX, 1920)
        // while paused / scrubbing there is time to be crisp; while playing,
        // staying fluid matters more than the last few pixels
        val want = if (anyPlaying()) base else min(1280, (base * 1.4f).toInt())
        val a = adaptiveMaxPx
        // the adaptive throttle only ever applies to playback decoding
        return if (anyPlaying() && a in MIN_DECODE_PX..base) a else want
    }

    /** Keep the preview fluid on slow decoders instead of dropping to 2 fps. */
    private fun adaptQuality() {
        val base = targetMaxPx.coerceIn(MIN_DECODE_PX, 1920)
        val cur = if (adaptiveMaxPx in MIN_DECODE_PX..base) adaptiveMaxPx else base
        adaptiveMaxPx = when {
            avgDecodeMs > 95f -> (cur * 0.8f).toInt().coerceAtLeast(MIN_DECODE_PX)
            avgDecodeMs in 1f..45f && cur < base -> (cur * 1.3f).toInt().coerceAtMost(base)
            else -> cur
        }
    }

    /** evict + recycle a layer's frame and decoder (main thread). */
    fun evict(id: String) {
        releaseSource(id)
        val external = externalIds.remove(id)
        handler.post {
            frames.remove(id)?.let {
                if (!external) try { it.recycle() } catch (_: Exception) { }
            }
        }
    }

    fun frameOf(l: Layer): Bitmap? = frames[l.id]

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
        for ((id, b) in frames) {
            if (externalIds.contains(id)) continue
            try { b.recycle() } catch (_: Exception) { }
        }
        frames.clear()
    }

    fun release() {
        detach()
        exec.shutdown()
    }
}
