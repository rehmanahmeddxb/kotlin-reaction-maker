package com.rehman.ahmedreactionstudio.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import com.rehman.ahmedreactionstudio.core.Compositor
import com.rehman.ahmedreactionstudio.core.LayerType
import com.rehman.ahmedreactionstudio.core.MediaKit
import com.rehman.ahmedreactionstudio.core.Project
import com.rehman.ahmedreactionstudio.core.ProjectStore
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Deterministic software exporter.
 *
 * Pipeline: project model -> frame at t (MediaMetadataRetriever + the shared
 * Compositor) -> ARGB bitmap -> NV12/I420 -> MediaCodec encoder -> MediaMuxer.
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
        val quality: Int = 1,          // 0 fast, 1 balanced, 2 high
        val codec: Codec = Codec.H264,
        val outFile: File
    )

    class Result(val ok: Boolean, val message: String, val file: File?)

    /** short-side sizing; keeps the canvas aspect, dims rounded to a multiple of 8 */
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
        fun r8(v: Int) = (v / 8) * 8
        return Pair(r8(w).coerceAtLeast(160), r8(h).coerceAtLeast(160))
    }

    /**
     * Pick an encoder for [mime]; prefer hardware, accept software as fallback.
     * Returns (codec name, color format). VPx encoders only support flexible
     * COLOR_FormatSurface (0x7F000789) for byte-buffer feeds, so we handle that.
     */
    private fun pickEncoder(mime: String): Pair<String, Int> {
        val yuv = intArrayOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,   // 21 NV12
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,        // 19 I420
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible       // 0x7F000789
        )
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
        Thread({
            var res: Result = Result(false, "Unknown export failure", null)
            var codec: MediaCodec? = null
            var muxer: MediaMuxer? = null
            var frameBitmap: Bitmap? = null
            val heldDecoders = ArrayList<Dec>()
            val heldImages = HashMap<String, Bitmap>()
            try {
                val (w, h) = chooseSize(p.aspect.canvasW, p.aspect.canvasH, opts.maxDim)
                val fps = opts.fps
                val bpp = doubleArrayOf(0.06, 0.12, 0.18)[opts.quality.coerceIn(0, 2)]
                val bitrate = (w * h * fps * bpp).toInt().coerceIn(400_000, 24_000_000)
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

                val format = MediaFormat.createVideoFormat(opts.codec.mime, w, h)
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFmt)
                format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                codec = MediaCodec.createByCodecName(encName)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                val muxerFmt = if (opts.codec.webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                muxer = MediaMuxer(opts.outFile.absolutePath, muxerFmt)
                val info = MediaCodec.BufferInfo()
                var trackIdx = -1
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
                val bitmapFor: (com.rehman.ahmedreactionstudio.core.Layer) -> Bitmap? = { l ->
                    when {
                        l.type == LayerType.IMAGE -> imageCache[l.id]
                        l.isVideoLike() -> decoders[l.id]?.current
                        else -> null
                    }
                }

                val frameUs = 1_000_000L / fps
                var ptsUs = 0L
                var frameIdx = 0
                var eosQueued = false
                var eosDone = false
                var lastProgress = -1

                /** drains one or more output buffers; true when EOS was consumed */
                fun drainOnce(): Boolean {
                    while (true) {
                        val outIdx = codec.dequeueOutputBuffer(info, 3000)
                        when {
                            outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                trackIdx = muxer.addTrack(codec.outputFormat)
                                if (!muxerStarted) { muxer.start(); muxerStarted = true }
                            }
                            outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                            outIdx >= 0 -> {
                                if (trackIdx >= 0 && muxerStarted) {
                                    codec.getOutputBuffer(outIdx)?.let {
                                        muxer.writeSampleData(trackIdx, it, info)
                                    }
                                }
                                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                codec.releaseOutputBuffer(outIdx, false)
                                if (eos) return true
                            }
                        }
                    }
                }

                var stall = 0
                while (!eosDone) {
                    if (cancel.get()) {
                        res = Result(false, "Export cancelled", null)
                        onProgress(100, "Done")
                        onDone(res)
                        return@Thread
                    }
                    if (!eosQueued && frameIdx < totalFrames) {
                        val timeMs = frameIdx * 1000L / fps
                        for (l in p.layers) {
                            if (!l.isVideoLike() || l.relPath.isNullOrBlank() || !l.visible) continue
                            val d = decoders[l.id] ?: continue
                            val mediaTime = if (l.playing) (timeMs * l.speed).toLong()
                            else l.pausedMediaMs
                            d.seekTo(mediaTime.coerceAtLeast(0L))
                        }
                        Compositor.draw(ctx, canvas, w, h, p, bitmapFor, timeMs, null)
                        val inIdx = codec.dequeueInputBuffer(20_000)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)
                            if (buf != null) {
                                val bytes = writeYuv(buf, frameBitmap!!, w, h, nv12)
                                codec.queueInputBuffer(inIdx, 0, bytes, ptsUs, 0)
                            }
                        }
                        ptsUs += frameUs
                        frameIdx++
                        val prog = frameIdx * 100 / totalFrames
                        if (prog != lastProgress) {
                            lastProgress = prog
                            onProgress(prog, "Encoding frame $frameIdx / $totalFrames (${opts.codec.label})")
                        }
                        drainOnce()
                        stall = 0
                    } else if (!eosQueued) {
                        val inIdx = codec.dequeueInputBuffer(20_000)
                        if (inIdx >= 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, ptsUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosQueued = true
                        }
                    } else {
                        if (drainOnce()) eosDone = true
                        else {
                            stall++
                            if (stall > 20000) break   // safety: no infinite retries
                        }
                    }
                }

                res = if (cancel.get()) Result(false, "Export cancelled", null)
                else if (opts.outFile.exists() && opts.outFile.length() > 0)
                    Result(true, "Export complete", opts.outFile)
                else Result(false, "Export produced an empty file.", null)
            } catch (e: Exception) {
                res = Result(false, "Export error: ${e.message}", null)
            } finally {
                try { codec?.stop() } catch (_: Exception) { }
                try { codec?.release() } catch (_: Exception) { }
                try { muxer?.stop() } catch (_: Exception) { }
                try { muxer?.release() } catch (_: Exception) { }
                try { frameBitmap?.recycle() } catch (_: Exception) { }
                try { for (d in heldDecoders) d.close() } catch (_: Exception) { }
                for (b in heldImages.values) { try { b.recycle() } catch (_: Exception) { } }
            }
            onProgress(100, "Done")
            onDone(res)
        }, "ahmed-export").start()
    }

    /**
     * Sequential source frames for one layer. Holds ONE MediaMetadataRetriever
     * for the whole export: re-opening the container per frame was the single
     * biggest cost of an export run.
     */
    private class Dec(val path: String, private val maxPx: Int) {
        private val src = MediaKit.FrameSource(path)
        private var cacheTime = Long.MIN_VALUE
        var current: Bitmap? = null
            private set

        fun seekTo(mediaTimeMs: Long) {
            if (current != null && abs(mediaTimeMs - cacheTime) < 25) return
            val b = src.frameAt(mediaTimeMs, maxPx)
            if (b != null) {
                val old = current
                current = b
                cacheTime = mediaTimeMs
                if (old != null && old !== b) old.recycle()
            }
        }

        fun close() {
            try { current?.recycle() } catch (_: Exception) { }
            current = null
            src.release()
        }
    }

    /** ARGB bitmap -> I420 (nv12=false) or NV12/NV12-flexible (nv12=true); returns bytes used */
    private fun writeYuv(dst: java.nio.ByteBuffer, bmp: Bitmap, w: Int, h: Int, nv12: Boolean): Int {
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val ySize = w * h
        val uvSize = ySize / 4
        val base = dst.position()
        val uOff = base + ySize
        val vOff = uOff + uvSize
        var i = 0
        var rowBase = base
        for (j in 0 until h) {
            var bOff = rowBase
            for (k in 0 until w) {
                val c = px[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                dst.put(bOff + k, y.toByte())
                i++
            }
            rowBase += w
        }
        var uPos = uOff
        for (j in 0 until h / 2) {
            for (k in 0 until w / 2) {
                val c = px[(j * 2) * w + (k * 2)]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val cb = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val cr = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                if (nv12) {
                    dst.put(uPos, cb.toByte()); dst.put(uPos + 1, cr.toByte()); uPos += 2
                } else {
                    dst.put(uPos, cb.toByte()); dst.put(vOff + (uPos - uOff), cr.toByte()); uPos += 1
                }
            }
        }
        return ySize + uvSize * 2
    }
}
