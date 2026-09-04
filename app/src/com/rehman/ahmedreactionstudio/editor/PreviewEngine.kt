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
import java.util.concurrent.Executors
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

    private val clocks = HashMap<String, Clock>()
    private val players = HashMap<String, MediaPlayer>()
    private val frames = HashMap<String, Bitmap>()   // last decoded frame per media layer

    private val exec = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var masterMs = 0L
    private var lastTick = 0L
    private var ticking = false
    private var snapshotLoop = false
    private var decodeBusy = false
    private var projectId: String = ""

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
            }
            onFrameReady(masterMs)
            handler.postDelayed(this, 33L)
        }
    }

    fun attach(projectId: String) {
        detach()
        this.projectId = projectId
        val p = project()
        clocks.clear()
        for (l in p.layers) if (l.isVideoLike()) {
            val c = Clock()
            c.freeze = l.pausedMediaMs
            c.wasPlaying = l.playing
            c.resumeAt = 0L
            clocks[l.id] = c
        }
    }

    fun detach() {
        stopTicker()
        stopSnapshots()
        releaseAllPlayers()
        recycleFrames()
        clocks.clear()
    }

    private fun project(): Project = projectRef()

    fun master(): Long = masterMs

    fun seekTo(ms: Long) {
        masterMs = ms.coerceAtLeast(0L)
        val p = project()
        p.lastPlayheadMs = masterMs
        for (l in p.layers) if (l.isVideoLike()) clocks[l.id]?.seek(masterMs, l.speed)
        syncPlayers(SystemClock.elapsedRealtime())
        refreshFrames()
    }

    fun playAll() {
        val now = SystemClock.elapsedRealtime()
        val p = project()
        for (l in p.layers) if (l.isVideoLike()) {
            l.playing = true
            clocks[l.id]?.resume(if (l.playing) masterMs else 0L)
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
            playerOf(l.id)?.pause()
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

    private fun mediaFile(l: Layer): File? {
        val rel = l.relPath ?: return null
        val f = File(store.projectDir(projectId), rel)
        return if (f.exists()) f else null
    }

    private fun ensureAudioPlayer(l: Layer) {
        if (l.muted || !l.isVideoLike()) return
        val f = mediaFile(l) ?: return
        var mp = players[l.id]
        if (mp == null) {
            try {
                mp = MediaPlayer()
                mp.setDataSource(f.absolutePath)
                mp.isLooping = true
                mp.setVolume(l.volume, l.volume)
                mp.prepare()
                mp.setOnPreparedListener { it.start() }
                players[l.id] = mp
            } catch (_: Exception) { }
        } else {
            try { mp.setVolume(l.volume, l.volume) } catch (_: Exception) { }
        }
    }

    private fun syncPlayers(now: Long) {
        val p = project()
        for (l in p.layers) {
            if (!l.isVideoLike()) continue
            val c = clocks[l.id] ?: continue
            val playingNow = l.playing && l.visible
            val mp = players[l.id]
            if (playingNow && !l.muted) {
                if (mp == null || !mp.isPlaying) {
                    ensureAudioPlayer(l)
                    players[l.id]?.let { if (!it.isPlaying) { try { it.seekTo(c.media(masterMs, l.speed).toInt()); it.start() } catch (_: Exception) { } } }
                } else {
                    // drift guard: re-anchor only when > 400 ms off
                    try {
                        val target = c.media(masterMs, l.speed)
                        val cur = mp.currentPosition.toLong()
                        if (kotlin.math.abs(cur - target) > 400) mp.seekTo(target.toInt())
                    } catch (_: Exception) { }
                }
            } else {
                if (mp != null && mp.isPlaying) { try { mp.pause() } catch (_: Exception) { } }
            }
        }
    }

    private fun pausePlayers() {
        for ((_, mp) in players) { try { if (mp.isPlaying) mp.pause() } catch (_: Exception) { } }
    }

    fun releaseAllPlayers() {
        for ((_, mp) in players) { try { mp.release() } catch (_: Exception) { } }
        players.clear()
    }

    fun playerOf(id: String): MediaPlayer? = players[id]

    /** media time (ms) of a layer right now */
    fun mediaTimeOf(l: Layer): Long = clocks[l.id]?.media(masterMs, l.speed) ?: 0L

    fun setVolume(l: Layer, v: Float) {
        l.volume = v
        players[l.id]?.setVolume(v, v)
    }

    // ---------- frame snapshots ----------

    /** Re-decode every visible video layer right now (used on seek / play / pause). */
    fun refreshFrames() {
        val p = project()
        val jobs = ArrayList<Pair<Layer, Long>>()
        for (l in p.layers) {
            if (l.isVideoLike() && l.visible && !l.relPath.isNullOrBlank()) {
                jobs.add(Pair(l, mediaTimeOf(l)))
            }
        }
        if (jobs.isEmpty()) { onFrameReady(masterMs); return }
        exec.execute {
            for ((l, t) in jobs) {
                decodeInto(l, t)
            }
            handler.post { onFrameReady(masterMs) }
        }
    }

    /** Continuous low-rate snapshot loop while master is playing. */
    fun startSnapshots() {
        if (snapshotLoop) return
        snapshotLoop = true
        handler.post { snapshotStep() }
    }

    fun stopSnapshots() { snapshotLoop = false }

    private fun snapshotStep() {
        if (!snapshotLoop) return
        if (!anyPlaying()) { onFrameReady(masterMs); handler.postDelayed({ snapshotStep() }, 150L); return }
        if (decodeBusy) { handler.postDelayed({ snapshotStep() }, 60L); return }
        val p = project()
        val jobs = ArrayList<Pair<Layer, Long>>()
        for (l in p.layers) if (l.isVideoLike() && l.playing && l.visible && !l.relPath.isNullOrBlank()) {
            jobs.add(Pair(l, mediaTimeOf(l)))
        }
        if (jobs.isEmpty()) {
            handler.postDelayed({ snapshotStep() }, 150L)
            return
        }
        decodeBusy = true
        exec.execute {
            for ((l, t) in jobs) decodeInto(l, t)
            decodeBusy = false
            handler.post { onFrameReady(masterMs) }
            handler.postDelayed({ snapshotStep() }, 66L)
        }
    }

    private fun decodeInto(l: Layer, t: Long) {
        val f = mediaFile(l) ?: return
        val key = l.id
        val wantMs = t.coerceAtLeast(0L)
        val bmp = MediaKit.videoFrame(f.absolutePath, wantMs, if (project().layers.size > 2) 640 else 960)
        if (bmp != null) {
            handler.post {
                if (project().layerById(key) != null) {
                    val prev = frames.put(key, bmp)
                    if (prev != null && prev !== bmp) prev.recycle()
                } else {
                    bmp.recycle()
                }
            }
        }
    }

    /** evict + recycle a layer's frame (main thread). */
    fun evict(id: String) {
        handler.post {
            frames.remove(id)?.let { try { it.recycle() } catch (_: Exception) { } }
        }
    }

    fun frameOf(l: Layer): Bitmap? = frames[l.id]

    fun setFrame(l: Layer, bmp: Bitmap?) {
        if (bmp == null) return
        frames.put(l.id, bmp)
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
