package com.rehman.ahmedreactionstudio.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.rehman.ahmedreactionstudio.core.Compositor
import com.rehman.ahmedreactionstudio.core.Layer
import com.rehman.ahmedreactionstudio.core.LayerType
import com.rehman.ahmedreactionstudio.core.MediaKit
import com.rehman.ahmedreactionstudio.core.Project
import com.rehman.ahmedreactionstudio.core.ProjectStore
import com.rehman.ahmedreactionstudio.core.gpu.GpuVideoPipeline
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Deterministic software exporter.
 *
 * Pipeline: project model -> frame at t (continuous MediaCodec GPU decode +
 * the shared Compositor) -> ARGB bitmap -> NV12/I420 -> MediaCodec encoder
 * -> MediaMuxer. Falls back to MediaMetadataRetriever only when a file cannot
 * open on the HW path.
 *
 * Audio: each clip's audio is pre-decoded to mono 44.1 kHz PCM (handling
 * muted/solo), mixed per-chunk and encoded to AAC alongside video so the
 * export has the same audible mix as the preview.
 *
 * Output codecs (user selectable):
 *   H.264 (AVC)  -> MP4 container   (.mp4)
 *   H.265 (HEVC) -> MP4 container   (.mp4)   when the device has an encoder
 *   VP8          -> WebM container  (.webm)
 *   VP9          -> WebM container  (.webm)
 *
 * AVI is accepted on IMPORT (the framework decodes any installed codec); the
 * framework provides no AVI muxer, so AVI export is not offered.
 */
object Exporter {

    private const val TAG = "AhmedExporter"

    /** codecs the export dialog can offer */
    enum class Codec(val mime: String, val label: String, val ext: String, val webm: Boolean) {
        H264(MediaFormat.MIMETYPE_VIDEO_AVC, "H.264 / AVC", "mp4", false),
        H265(MediaFormat.MIMETYPE_VIDEO_HEVC, "H.265 / HEVC", "mp4", false),
        VP8(MediaFormat.MIMETYPE_VIDEO_VP8, "VP8 (WebM)", "webm", true),
        VP9(MediaFormat.MIMETYPE_VIDEO_VP9, "VP9 (WebM)", "webm", true);

        companion object {
            /** codecs actually supported by an encoder on this device */
            fun available(): List<Codec> {
                val mimes = HashSet<String>()
                try {
                    for (ci in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                        if (!ci.isEncoder) continue
                        for (t in ci.supportedTypes) mimes.add(t)
                    }
                } catch (_: Exception) { }
                return entries.filter { mimes.contains(it.mime) }
            }
        }
    }

    data class Options(
        val fps: Int = 30,
        val maxDim: Int = 720,
        /** index into [EncoderConfig.Quality]: 0 tiny, 1 small, 2 balanced, 3 high */
        val quality: Int = 2,
        val codec: Codec = Codec.H264,
        val outFile: File,
        /** max-compatibility mode: H.264 at the call site, tight GOP, default rate control */
        val compat: Boolean = false,
        /**
         * Last decoded frame of each LIVE camera layer, keyed by layer id.
         * Offline export cannot seek a live feed; without this the camera
         * box is encoded as the background color (the "black camera" bug).
         * Recording (CompositionRecorder) is what captures live motion.
         */
        val liveFrames: Map<String, Bitmap> = emptyMap()
    )

    class Result(val ok: Boolean, val message: String, val file: File?, val retry: Boolean = false)

    /** short-side sizing; keeps the canvas aspect, dims rounded to a multiple of 16 (encoder alignment) */
    fun chooseSize(cw: Int, ch: Int, maxDim: Int): Pair<Int, Int> {
        val landscape = cw >= ch
        val target = maxDim.coerceIn(240, 1920)
        val (w, h) = if (landscape) {
            val ww = target
            Pair(ww, (ww.toDouble() * ch / cw).roundToInt())
        } else {
            val hh = target
            Pair((hh.toDouble() * cw / ch).roundToInt(), hh)
        }
        fun r16(v: Int) = (v / 16) * 16
        return Pair(r16(w).coerceAtLeast(160), r16(h).coerceAtLeast(160))
    }

    /**
     * Pick an encoder for [mime]; prefer hardware, accept software as fallback.
     * Returns (codec name, color format). Surface-only encoders are skipped
     * because the exporter feeds YUV byte buffers (not an input Surface).
     */
    internal fun pickEncoder(mime: String): Pair<String, Int> {
        // Flexible FIRST: that is the format getInputImage() is defined for.
        // Packed NV12/I420 are fallbacks for software encoders that still
        // want a tightly packed ByteBuffer.
        val yuv = intArrayOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,      // 0x7F000789
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,   // 21 NV12
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar        // 19 I420
        )
        val surfaceOnly = 2130708361 // COLOR_FormatSurface
        val infos = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.supportedTypes.contains(mime) }
        } catch (_: Exception) { emptyList() }
        // hardware first, then software
        val ordered = infos.sortedBy { ci ->
            val name = ci.name.lowercase()
            if (name.startsWith("omx.") || name.startsWith("c2.")) {
                if (name.contains("google") || name.contains("c2.android") || name.contains("sw")) 1 else 0
            } else 1
        }
        for (ci in ordered) {
            try {
                val caps = ci.getCapabilitiesForType(mime)
                val onlySurface = caps.colorFormats.size == 1 && caps.colorFormats[0] == surfaceOnly
                if (onlySurface) continue
                for (f in yuv) if (caps.colorFormats.contains(f)) return Pair(ci.name, f)
            } catch (_: Exception) { }
        }
        for (ci in ordered) {
            try {
                val caps = ci.getCapabilitiesForType(mime)
                for (f in yuv) if (caps.colorFormats.contains(f)) return Pair(ci.name, f)
            } catch (_: Exception) { }
        }
        return Pair("", -1)
    }

    fun export(
        p: Project,
        store: ProjectStore,
        opts: Options,
        cancel: AtomicBoolean,
        onProgress: (Int, String) -> Unit,
        onDone: (Result) -> Unit
    ) {
        exportInternal(p, store, opts, cancel, onProgress) { res ->
            // A file the device itself cannot play back is never reported as
            // success: retry once with max-compatibility settings (H.264 +
            // safe encoder config) before admitting failure.
            if (!res.ok && res.retry && !cancel.get()) {
                try { opts.outFile.delete() } catch (_: Exception) { }
                onProgress(2, "Trying compatibility mode (H.264)")
                exportInternal(p, store, opts.copy(codec = Codec.H264, compat = true),
                    cancel, onProgress, onDone)
            } else onDone(res)
        }
    }

    internal fun exportInternal(
        p: Project,
        store: ProjectStore,
        opts: Options,
        cancel: AtomicBoolean,
        onProgress: (Int, String) -> Unit,
        onDone: (Result) -> Unit
    ) {
        Thread({
            var res: Result = Result(false, "Unknown export failure", null)
            var vCodec: MediaCodec? = null
            var aCodec: MediaCodec? = null
            var muxer: MediaMuxer? = null
            var frameBitmap: Bitmap? = null
            val heldDecoders = ArrayList<Dec>()
            val heldImages = HashMap<String, Bitmap>()
            try {
                val (w, h) = chooseSize(p.aspect.canvasW, p.aspect.canvasH, opts.maxDim)
                val fps = opts.fps
                val quality = EncoderConfig.Quality.of(opts.quality)
                val durationMs = p.durationMs()
                val totalFrames = (durationMs * fps / 1000L).toInt().coerceAtLeast(1)

                onProgress(1, "Choosing ${opts.codec.label} encoder")
                val (encName, colorFmt) = pickEncoder(opts.codec.mime)
                if (encName.isEmpty() || colorFmt < 0) {
                    res = Result(false, "No compatible ${opts.codec.label} encoder on this device.", null)
                    onProgress(100, "Done")
                    onDone(res)
                    return@Thread
                }
                // NV12 packing for SemiPlanar; I420 packing for Planar; Flexible treated as NV12
                val nv12 = colorFmt != MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar

                // OBS-class tuning: long GOP + VBR + resolution-aware bitrate.
                // This is where "hours of footage in a few hundred MB" comes
                // from; see EncoderConfig for the reasoning.
                val vFormat = EncoderConfig.videoFormat(
                    opts.codec.mime, w, h, fps, colorFmt, quality,
                    liveRecorder = false, compat = opts.compat
                )
                vCodec = MediaCodec.createByCodecName(encName)
                try {
                    vCodec.configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                } catch (e: Exception) {
                    // Tuned config rejected by this encoder: one immediate retry
                    // with the safe config before giving up on the codec.
                    if (opts.compat) throw e
                    val safe = EncoderConfig.videoFormat(
                        opts.codec.mime, w, h, fps, colorFmt, quality,
                        liveRecorder = false, compat = true
                    )
                    vCodec.configure(safe, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }
                vCodec.start()
                val muxerFmt = if (opts.codec.webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                muxer = MediaMuxer(opts.outFile.absolutePath, muxerFmt)
                val vInfo = MediaCodec.BufferInfo()
                val aInfo = MediaCodec.BufferInfo()
                var vTrack = -1
                var aTrack = -1
                var muxerStarted = false

                frameBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(frameBitmap!!)
                val ctx = Compositor.Ctx()

                // decode sources
                val decoders = HashMap<String, Dec>()
                val imageCache = heldImages
                for (l in p.layers) {
                    if (l.type == LayerType.IMAGE && !l.relPath.isNullOrBlank()) {
                        try {
                            MediaKit.image(File(store.projectDir(p.id), l.relPath!!).absolutePath)
                                ?.let { imageCache[l.id] = it }
                        } catch (_: Exception) { }
                    } else if (l.isVideoLike() && !l.relPath.isNullOrBlank()) {
                        val f = File(store.projectDir(p.id), l.relPath!!)
                        if (f.exists()) {
                            val d = Dec(f.absolutePath, max(w, h))
                            decoders[l.id] = d
                            heldDecoders.add(d)
                        }
                    }
                }
                val bitmapFor: (Layer) -> Bitmap? = { l ->
                    when {
                        l.isLive() -> opts.liveFrames[l.id]
                        l.type == LayerType.IMAGE -> imageCache[l.id]
                        l.isVideoLike() -> decoders[l.id]?.current
                        else -> null
                    }
                }

                // ---- audio preparation (offline mix of clip audio, respecting mute/solo/loop) ----
                val audioClips = ArrayList<ClipState>()
                var audioEnabled = false
                // effective muted respects solo
                val anySolo = p.layers.any { it.solo }
                fun effectiveMuted(l: com.rehman.ahmedreactionstudio.core.Layer): Boolean =
                    l.muted || (anySolo && !l.solo)
                for (l in p.layers) {
                    if (!l.isVideoLike() || l.relPath.isNullOrBlank()) continue
                    if (effectiveMuted(l)) continue
                    val f = File(store.projectDir(p.id), l.relPath!!)
                    if (!f.exists()) continue
                    try {
                        val pcm = AudioDecode.toPcmMono(f.absolutePath) ?: continue
                        if (pcm.data.isEmpty()) continue
                        // audio follows the SAME playback state the video path uses below:
                        // a paused source is silent, speed scales the read position
                        audioClips.add(ClipState(pcm.data, l.volume, l.loop, l.durMs,
                            l.playing, l.speed))
                    } catch (_: Exception) { }
                }
                audioEnabled = audioClips.isNotEmpty() && !opts.codec.webm // WebM muxer audio support varies; keep video-only for WebM for now
                var audioSamplesDone = 0L
                val audioRate = 44100
                val audioChunk = 1024
                val mixer = Mixer(audioChunk)
                val totalAudioSamples = if (audioEnabled) durationMs * audioRate / 1000L else 0L
                if (audioEnabled) {
                    try {
                        val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, audioRate, 1)
                        af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        af.setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                        af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
                        aCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                        aCodec.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        aCodec.start()
                    } catch (_: Exception) {
                        aCodec = null
                        audioEnabled = false
                    }
                }

                val frameUs = 1_000_000L / fps
                var ptsUs = 0L
                var frameIdx = 0
                var vEosQueued = false
                var vEosDone = false
                var aEosQueued = false
                var aEosDone = !audioEnabled
                var lastProgress = -1
                val vPts = MonotonicPts()
                val aClock = MonotonicPts()
                var muxerFailed = false

                fun tryStartMuxer() {
                    if (muxerStarted || muxerFailed) return
                    if (vTrack < 0) return
                    if (audioEnabled && aTrack < 0) return
                    try {
                        muxer!!.start()
                        muxerStarted = true
                    } catch (e: Exception) {
                        Log.e(TAG, "muxer.start failed", e)
                        muxerFailed = true
                    }
                }

                fun writeTrack(
                    codec: MediaCodec, track: Int, idx: Int, info: MediaCodec.BufferInfo, clock: MonotonicPts
                ): Boolean {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    try {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            codec.releaseOutputBuffer(idx, false)
                            return false
                        }
                        if (track >= 0 && muxerStarted && info.size > 0) {
                            val buf = codec.getOutputBuffer(idx)
                            if (buf != null) {
                                info.presentationTimeUs = clock.next(info.presentationTimeUs)
                                val end = info.offset + info.size
                                if (end <= buf.capacity()) {
                                    buf.position(info.offset)
                                    buf.limit(end)
                                    muxer!!.writeSampleData(track, buf, info)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "writeSampleData", e)
                    } finally {
                        try { codec.releaseOutputBuffer(idx, false) } catch (_: Exception) { }
                    }
                    return eos
                }

                fun drainVideo(): Boolean {
                    while (true) {
                        val outIdx = vCodec!!.dequeueOutputBuffer(vInfo, 3000)
                        when {
                            outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                try { vTrack = muxer!!.addTrack(vCodec!!.outputFormat) }
                                catch (e: Exception) {
                                    Log.e(TAG, "add video track", e)
                                    muxerFailed = true
                                    return true
                                }
                                tryStartMuxer()
                            }
                            outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                            outIdx >= 0 -> {
                                if (writeTrack(vCodec!!, vTrack, outIdx, vInfo, vPts)) return true
                            }
                            else -> return false
                        }
                    }
                }

                fun drainAudio(): Boolean {
                    val ac = aCodec ?: return true
                    while (true) {
                        val outIdx = ac.dequeueOutputBuffer(aInfo, 3000)
                        when {
                            outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                try { aTrack = muxer!!.addTrack(ac.outputFormat) }
                                catch (e: Exception) {
                                    Log.w(TAG, "add audio track; muxing video-only", e)
                                    audioEnabled = false
                                    aEosDone = true
                                    tryStartMuxer()
                                    return true
                                }
                                tryStartMuxer()
                            }
                            outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                            outIdx >= 0 -> {
                                if (writeTrack(ac, aTrack, outIdx, aInfo, aClock)) return true
                            }
                            else -> return false
                        }
                    }
                }

                var stall = 0
                while (!(vEosDone && aEosDone)) {
                    if (cancel.get()) {
                        res = Result(false, "Export cancelled", null)
                        onProgress(100, "Done")
                        onDone(res)
                        return@Thread
                    }
                    // ---- video ----
                    if (!vEosQueued && frameIdx < totalFrames) {
                        val timeMs = frameIdx * 1000L / fps
                        for (l in p.layers) {
                            if (!l.isVideoLike() || l.relPath.isNullOrBlank() || !l.visible) continue
                            val d = decoders[l.id] ?: continue
                            var mediaTime = if (l.playing) (timeMs * l.speed).toLong()
                            else l.pausedMediaMs
                            if (l.durMs > 0) {
                                mediaTime = if (l.loop) mediaTime % l.durMs
                                else mediaTime.coerceAtMost(l.durMs - 33)
                            }
                            d.seekTo(mediaTime.coerceAtLeast(0L))
                        }
                        Compositor.draw(ctx, canvas, w, h, p, bitmapFor, timeMs, null)
                        val inIdx = vCodec!!.dequeueInputBuffer(20_000)
                        if (inIdx >= 0) {
                            val bytes = YuvWriter.fillInput(vCodec!!, inIdx, frameBitmap!!, w, h, nv12)
                            val pts = vPts.next(ptsUs)
                            vCodec!!.queueInputBuffer(inIdx, 0, bytes.coerceAtLeast(0), pts, 0)
                        }
                        ptsUs += frameUs
                        frameIdx++
                        val prog = (frameIdx * 70 / totalFrames).coerceAtMost(70) // video drives 70% of progress
                        if (prog != lastProgress && prog <= 70) {
                            lastProgress = prog
                            onProgress(prog, "Encoding video $frameIdx / $totalFrames")
                        }
                        if (drainVideo()) vEosDone = true
                        if (audioEnabled) {
                            // keep audio roughly in sync: encode a few chunks per frame
                            val chunksPerFrame = max(1, (audioRate / fps / audioChunk.toFloat()).toInt() + 1)
                            repeat(chunksPerFrame) {
                                if (audioSamplesDone < totalAudioSamples && !aEosQueued) {
                                    if (encodeAudioChunk(aCodec!!, audioClips, audioSamplesDone, audioChunk, mixer))
                                        audioSamplesDone += audioChunk
                                    else drainAudio()   // encoder full: free output, retry next frame
                                }
                            }
                            if (drainAudio()) aEosDone = true
                        }
                        stall = 0
                    } else if (!vEosQueued) {
                        val inIdx = vCodec!!.dequeueInputBuffer(20_000)
                        if (inIdx >= 0) {
                            val eosPts = vPts.next(ptsUs)
                            vCodec!!.queueInputBuffer(inIdx, 0, 0, eosPts,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            vEosQueued = true
                        }
                        // also queue audio EOS if needed
                        if (audioEnabled && !aEosQueued) {
                            // finish remaining audio
                            var tailStall = 0
                            while (audioSamplesDone < totalAudioSamples && tailStall < 500) {
                                if (encodeAudioChunk(aCodec!!, audioClips, audioSamplesDone, audioChunk, mixer)) {
                                    audioSamplesDone += audioChunk; tailStall = 0
                                } else tailStall++
                                if (drainAudio()) { aEosDone = true; break }
                            }
                            val aIdx = aCodec!!.dequeueInputBuffer(20_000)
                            if (aIdx >= 0) {
                                // EOS carries the running PTS, not 0: a backwards
                                // timestamp here breaks strict muxers/players.
                                aCodec!!.queueInputBuffer(aIdx, 0, 0, aClock.next(audioPts(audioSamplesDone)),
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                aEosQueued = true
                            }
                        }
                    } else {
                        var vDone = vEosDone
                        var aDone = aEosDone
                        if (!vDone) { if (drainVideo()) vDone = true; vEosDone = vDone }
                        if (audioEnabled && !aDone) { if (drainAudio()) aDone = true; aEosDone = aDone }
                        if (vDone && aDone) break
                        else {
                            stall++
                            if (stall > 20000) break   // safety: no infinite retries
                            // also keep feeding audio if video already EOS but audio not
                            if (audioEnabled && !aEosDone && !aEosQueued) {
                                if (audioSamplesDone < totalAudioSamples) {
                                    if (encodeAudioChunk(aCodec!!, audioClips, audioSamplesDone, audioChunk, mixer))
                                        audioSamplesDone += audioChunk
                                } else {
                                    val aIdx = aCodec!!.dequeueInputBuffer(20_000)
                                    if (aIdx >= 0) {
                                        aCodec!!.queueInputBuffer(aIdx, 0, 0,
                                            aClock.next(audioPts(audioSamplesDone)),
                                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                        aEosQueued = true
                                    }
                                }
                            }
                        }
                    }
                }

                // final drain after EOS
                var finalStall = 0
                while (!(vEosDone && aEosDone) && finalStall < 200) {
                    var progressed = false
                    if (!vEosDone && drainVideo()) { vEosDone = true; progressed = true }
                    if (audioEnabled && !aEosDone && drainAudio()) { aEosDone = true; progressed = true }
                    if (!progressed) finalStall++ else finalStall = 0
                }

                // Finalize BEFORE validating: the moov box (the index that makes
                // an MP4 playable at all) is only written by muxer.stop(), so
                // probing earlier always sees a "broken" file. The finally block
                // below is then a no-op (everything already released + nulled).
                var finalized = false
                try {
                    if (muxerStarted) { muxer?.stop(); finalized = true }
                } catch (_: Exception) { }
                try { muxer?.release() } catch (_: Exception) { }
                muxer = null
                try { vCodec?.stop() } catch (_: Exception) { }
                try { vCodec?.release() } catch (_: Exception) { }
                vCodec = null
                try { aCodec?.stop() } catch (_: Exception) { }
                try { aCodec?.release() } catch (_: Exception) { }
                aCodec = null

                // Strict validation: the file must exist, have real bytes, AND
                // be probed back by this device's own framework (width + duration).
                // Anything less is a failure that triggers the compat retry —
                // a broken file is never reported as "Export complete".
                res = if (cancel.get()) Result(false, "Export cancelled", null)
                else if (muxerFailed) Result(false,
                    "Export could not start the media container.", null, retry = !opts.compat)
                else if (!finalized) Result(false,
                    "Export could not finalize the video file.", null, retry = !opts.compat)
                else {
                    val report = ExportValidator.validate(
                        opts.outFile.absolutePath, expectAudio = audioEnabled
                    )
                    if (!report.ok) {
                        Result(false, report.message, null, retry = !opts.compat)
                    } else Result(true, "Export complete", opts.outFile)
                }
            } catch (e: Exception) {
                res = Result(false, "Export error: ${e.message}", null)
            } finally {
                try { vCodec?.stop() } catch (_: Exception) { }
                try { vCodec?.release() } catch (_: Exception) { }
                try { aCodec?.stop() } catch (_: Exception) { }
                try { aCodec?.release() } catch (_: Exception) { }
                try { if (muxer != null) muxer?.stop() } catch (_: Exception) { }
                try { muxer?.release() } catch (_: Exception) { }
                try { frameBitmap?.recycle() } catch (_: Exception) { }
                try { for (d in heldDecoders) d.close() } catch (_: Exception) { }
                for (b in heldImages.values) { try { b.recycle() } catch (_: Exception) { } }
            }
            onProgress(100, "Done")
            onDone(res)
        }, "ahmed-export").start()
    }

    /** one clip in the offline mix: a composition-timeline cursor + its static level / play state */
    private class ClipState(
        pcm: ShortArray, val volume: Float, loop: Boolean, durMs: Long,
        val playing: Boolean = true, speed: Float = 1f
    ) {
        val cursor = ClipCursor(pcm, durMs, loop, speed)
    }

    /** mono 44.1 kHz sample position -> presentation microseconds */
    private fun audioPts(samples: Long): Long = AudioMath.samplesToUs(samples)

    /** per-export mixer scratch: limiter state (instant attack, slow release) + float mix bus */
    private class Mixer(chunk: Int) {
        val limiter = Limiter()
        val mix = FloatArray(chunk)
    }

    /**
     * Mix one chunk at composition sample position [baseSamples] and queue it.
     * The caller advances the sample clock ONLY when this returns true, so a
     * chunk the encoder could not accept is retried, never skipped — the
     * audio PTS can never run ahead of the audio actually encoded.
     */
    private fun encodeAudioChunk(
        codec: MediaCodec, clips: List<ClipState>, baseSamples: Long, chunk: Int, mixer: Mixer
    ): Boolean {
        val inIdx = codec.dequeueInputBuffer(20_000)
        if (inIdx < 0) return false
        val buf = codec.getInputBuffer(inIdx) ?: return false
        val n = chunk
        val mix = mixer.mix
        java.util.Arrays.fill(mix, 0, n, 0f)
        for (c in clips) {
            // audio follows the SAME playback state the video path uses: a paused
            // source is silent, speed scales the read position, loops wrap at durMs
            if (!c.playing || c.volume <= 0.0005f) continue
            c.cursor.seekComposition(baseSamples)
            c.cursor.mixInto(mix, 0, n, c.volume)
        }
        val mixed = ShortArray(n)
        mixer.limiter.apply(mix, n, mixed)
        buf.clear()
        buf.order(ByteOrder.nativeOrder())   // MediaCodec PCM is native-endian
        buf.asShortBuffer().put(mixed)
        codec.queueInputBuffer(inIdx, 0, mixed.size * 2, audioPts(baseSamples), 0)
        return true
    }

    /**
     * Sequential source frames for one layer.
     *
     * Prefers continuous MediaCodec (same GPU path as the live preview) so
     * export walks the file forward instead of seek-grabbing every frame.
     * Falls back to a cached MediaMetadataRetriever when HW open fails.
     */
    private class Dec(val path: String, private val maxPx: Int) {
        private val gpuId = "export_" + Integer.toHexString(System.identityHashCode(this)) +
            "_" + path.hashCode()
        private var useGpu = true
        private var soft: MediaKit.FrameSource? = null
        private var cacheTime = Long.MIN_VALUE
        private var lastSoft: Bitmap? = null
        var current: Bitmap? = null
            private set

        init {
            try {
                GpuVideoPipeline.getOrCreate(gpuId, path)
            } catch (_: Exception) {
                useGpu = false
                soft = MediaKit.FrameSource(path)
            }
        }

        fun seekTo(mediaTimeMs: Long) {
            if (current != null && abs(mediaTimeMs - cacheTime) < 25) return
            if (useGpu) {
                var got = false
                GpuVideoPipeline.runSync(timeoutMs = 8_000L) {
                    val d = GpuVideoPipeline.decoder(gpuId)
                    if (d == null) {
                        useGpu = false
                        return@runSync
                    }
                    // forceSeek on large jumps; continuous advance otherwise
                    val force = cacheTime == Long.MIN_VALUE ||
                        abs(mediaTimeMs - cacheTime) > 200L ||
                        mediaTimeMs + 40L < cacheTime
                    got = d.advanceTo(mediaTimeMs, maxPx, forceSeek = force)
                    if (got) {
                        current = d.currentBitmap()
                        cacheTime = mediaTimeMs
                    }
                }
                if (got && current != null) return
                // one-shot fallback for this frame
                if (soft == null) soft = MediaKit.FrameSource(path)
            }
            val s = soft ?: return
            val b = s.frameAt(mediaTimeMs, maxPx)
            if (b != null) {
                val old = lastSoft
                lastSoft = b
                current = b
                cacheTime = mediaTimeMs
                if (old != null && old !== b) try { old.recycle() } catch (_: Exception) { }
            }
        }

        fun close() {
            try { GpuVideoPipeline.releaseDecoder(gpuId) } catch (_: Exception) { }
            try { lastSoft?.recycle() } catch (_: Exception) { }
            lastSoft = null
            current = null
            soft?.release()
            soft = null
        }
    }

    /** Packed NV12/I420 fallback (tests + software encoders without getInputImage). */
    internal fun writeYuv(dst: java.nio.ByteBuffer, bmp: Bitmap, w: Int, h: Int, nv12: Boolean): Int =
        YuvWriter.fillPacked(dst, bmp, w, h, nv12)
}
