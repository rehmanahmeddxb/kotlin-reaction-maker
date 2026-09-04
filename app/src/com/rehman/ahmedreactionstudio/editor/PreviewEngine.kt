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
        fun seek(master: Long, speed: Float) { freeze = (master * speed).toLong(); resumeAt = master; wasPlaying = true }
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
                syncPlayers(now)
                if (snapshotLoop) requestFrames(closest = false)
                if (now - lastAdapt > 1000L) { lastAdapt = now; adaptQuality() }
            }
            onFrameReady(masterMs)
            handler.postDelayed(this, TICK_MS)
        }
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
            if (!l.isVideoLike()) continue
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
        for (l in p.layers) if (l.isVideoLike()) clocks[l.id]?.seek(masterMs, l.speed)
        syncPlayers(SystemClock.elapsedRealtime())
        refreshFrames()
    }

    fun playAll() {
        val p = project()
        for (l in p.layers) if (l.isVideoLike()) {
            l.playing = true
            clocks[l.id]?.resume(masterMs)
        }
        startTicker()
        refreshFrames()
    }

    fun pauseAll() {
        val p = project()
        for (l in p.layers) if (l.isVideoLike()) {
            l.playing = false
            clocks[l.id]?.pause(masterMs)
        }
        pausePlayers()
    }

    fun toggleLayerPlay(l: Layer): Boolean {
        // returns new state
        if (!l.isVideoLike()) return l.playing
        if (l.playing) {
            l.playing = false
            clocks[l.id]?.pause(masterMs)
            l.pausedMediaMs = clocks[l.id]?.freeze ?: 0L
            playerOf(l.id)?.let { try { it.pause() } catch (_: Exception) { } }
        } else {
            l.playing = true
            l.pausedMediaMs = 0L
            clocks[l.id]?.resume(masterMs)
            startTicker()
        }
        refreshFrames()
        return l.playing
    }

    fun anyPlaying(): Boolean {
        val p = project()
        for (l in p.layers) if (l.isVideoLike() && l.playing && l.visible) return true
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
        if (l.muted || !l.isVideoLike()) return
        if (players.containsKey(l.id) || preparing.contains(l.id)) return
        val path = paths[l.id] ?: pathOf(l) ?: return
        preparing.add(l.id)
        val id = l.id
        val vol = l.volume
        exec.execute {
            var mp: MediaPlayer? = null
            try {
                mp = MediaPlayer()
                mp.setDataSource(path)
                mp.isLooping = true
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
                if (lay == null || !lay.playing || !lay.visible || lay.muted) {
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
            if (!l.isVideoLike()) continue
            val playingNow = l.playing && l.visible && !l.muted
            val mp = players[l.id]
            if (playingNow) {
                if (mp == null) { ensureAudioPlayer(l); continue }
                try {
                    if (!mp.isPlaying) {
                        mp.seekTo(mediaTimeOf(l).toInt())
                        mp.start()
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

    /** media time (ms) of a layer right now */
    fun mediaTimeOf(l: Layer): Long = clocks[l.id]?.media(masterMs, l.speed) ?: 0L

    fun setVolume(l: Layer, v: Float) {
        l.volume = v
        try { players[l.id]?.setVolume(v, v) } catch (_: Exception) { }
    }

    // ---------- frame snapshots ----------

    /** Re-decode every visible video layer right now (used on seek / play / pause). */
    fun refreshFrames() {
        val p = project()
        var any = false
        for (l in p.layers) if (l.isVideoLike() && l.visible && !l.relPath.isNullOrBlank()) {
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
            if (!l.isVideoLike() || !l.visible || l.relPath.isNullOrBlank()) continue
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
        if (prev != null && prev !== bmp) prev.recycle()
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
        handler.post {
            frames.remove(id)?.let { try { it.recycle() } catch (_: Exception) { } }
        }
    }

    fun frameOf(l: Layer): Bitmap? = frames[l.id]

    fun setFrame(l: Layer, bmp: Bitmap?) {
        if (bmp == null) return
        frames.put(l.id, bmp)
        newFrames.set(true)
    }

    fun recycleFrames() {
        for ((_, b) in frames) { try { b.recycle() } catch (_: Exception) { } }
        frames.clear()
    }

    fun release() {
        detach()
        exec.shutdown()
    }
}
