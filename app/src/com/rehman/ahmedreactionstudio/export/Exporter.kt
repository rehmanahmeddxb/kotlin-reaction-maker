package com.rehman.ahmedreactionstudio.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import com.rehman.ahmedreactionstudio.core.Compositor
import com.rehman.ahmedreactionstudio.core.Layer
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
 * Deterministic H.264 exporter.
 *
 * Pipeline (software compositor, spec section 39):
 *   project model -> frame at t (MediaMetadataRetriever + shared Compositor)
 *   -> ARGB bitmap -> NV12/I420 -> MediaCodec AVC encoder (byte-buffer mode)
 *   -> MediaMuxer -> MP4.
 *
 * The exact same Compositor draws the on-screen preview, so export geometry
 * always matches what the user saw in the editor.
 *
 * Audio muxing is intentionally deferred: the master plan's own MVP list
 * ("first usable MVP", section 111) puts the audio mixer in a later stage.
 */
object Exporter {

    data class Options(
        val fps: Int = 30,
        val maxDim: Int = 720,
        val quality: Int = 1,          // 0 fast, 1 balanced, 2 high
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

    /** pick a hardware AVC encoder when possible; prefer NV12/I420 byte layouts */
    private fun pickEncoder(): Pair<String, Int> {
        val wanted = intArrayOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,   // 21 NV12
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar        // 19 I420
        )
        for (ci in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (!ci.isEncoder || !ci.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_AVC)) continue
            try {
                val caps = ci.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                for (f in wanted) if (caps.colorFormats.contains(f)) return Pair(ci.name, f)
            } catch (_: Exception) { }
        }
        for (ci in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (!ci.isEncoder || !ci.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_AVC)) continue
            try {
                val caps = ci.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                for (f in wanted) if (caps.colorFormats.contains(f)) return Pair(ci.name, f)
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
            try {
                val (w, h) = chooseSize(p.aspect.canvasW, p.aspect.canvasH, opts.maxDim)
                val fps = opts.fps
                val bpp = doubleArrayOf(0.06, 0.12, 0.18)[opts.quality.coerceIn(0, 2)]
                val bitrate = (w * h * fps * bpp).toInt().coerceIn(400_000, 24_000_000)
                val durationMs = p.durationMs()
                val totalFrames = (durationMs * fps / 1000L).toInt().coerceAtLeast(1)

                onProgress(1, "Choosing H.264 encoder")
                val (encName, colorFmt) = pickEncoder()
                if (encName.isEmpty() || colorFmt < 0) {
                    res = Result(false, "No compatible H.264 encoder on this device.", null)
                    onProgress(100, "Done")
                    onDone(res)
                    return@Thread
                }
                val nv12 = colorFmt == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar

                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFmt)
                format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                codec = MediaCodec.createByCodecName(encName)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                muxer = MediaMuxer(opts.outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val info = MediaCodec.BufferInfo()
                var trackIdx = -1
                var muxerStarted = false

                frameBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(frameBitmap!!)
                val ctx = Compositor.Ctx()

                // decode sources
                val decoders = HashMap<String, Dec>()
                val imageCache = HashMap<String, Bitmap>()
                for (l in p.layers) {
                    if (l.type == LayerType.IMAGE && !l.relPath.isNullOrBlank()) {
                        try {
                            MediaKit.image(File(store.projectDir(p.id), l.relPath!!).absolutePath)
                                ?.let { imageCache[l.id] = it }
                        } catch (_: Exception) { }
                    } else if (l.isVideoLike() && !l.relPath.isNullOrBlank()) {
                        val f = File(store.projectDir(p.id), l.relPath!!)
                        if (f.exists()) decoders[l.id] = Dec(f.absolutePath, max(w, h))
                    }
                }
                val bitmapFor: (Layer) -> Bitmap? = { l ->
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
                        val outIdx = codec!!.dequeueOutputBuffer(info, 3000)
                        when {
                            outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                trackIdx = muxer!!.addTrack(codec!!.outputFormat)
                                if (!muxerStarted) { muxer!!.start(); muxerStarted = true }
                            }
                            outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                            outIdx >= 0 -> {
                                if (trackIdx >= 0 && muxerStarted) {
                                    codec!!.getOutputBuffer(outIdx)?.let {
                                        muxer!!.writeSampleData(trackIdx, it, info)
                                    }
                                }
                                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                codec!!.releaseOutputBuffer(outIdx, false)
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
                        val inIdx = codec!!.dequeueInputBuffer(20_000)
                        if (inIdx >= 0) {
                            val buf = codec!!.getInputBuffer(inIdx)
                            if (buf != null) {
                                val bytes = writeYuv(buf, frameBitmap!!, w, h, nv12)
                                codec!!.queueInputBuffer(inIdx, 0, bytes, ptsUs, 0)
                            }
                        }
                        ptsUs += frameUs
                        frameIdx++
                        val prog = frameIdx * 100 / totalFrames
                        if (prog != lastProgress) {
                            lastProgress = prog
                            onProgress(prog, "Encoding frame $frameIdx / $totalFrames")
                        }
                        drainOnce()
                        stall = 0
                    } else if (!eosQueued) {
                        val inIdx = codec!!.dequeueInputBuffer(20_000)
                        if (inIdx >= 0) {
                            codec!!.queueInputBuffer(inIdx, 0, 0, ptsUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosQueued = true
                        }
                    } else {
                        if (drainOnce()) eosDone = true
                        else {
                            stall++
                            if (stall > 20000) break   // safety: no infinite retries (spec 47)
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
            }
            onProgress(100, "Done")
            onDone(res)
        }, "ahmed-export").start()
    }

    private class Dec(val path: String, private val maxPx: Int) {
        private var cacheTime = Long.MIN_VALUE
        var current: Bitmap? = null
            private set

        fun seekTo(mediaTimeMs: Long) {
            if (current != null && abs(mediaTimeMs - cacheTime) < 25) return
            val b = MediaKit.videoFrame(path, mediaTimeMs, maxPx)
            if (b != null) {
                val old = current
                current = b
                cacheTime = mediaTimeMs
                if (old != null && old !== b) old.recycle()
            }
        }

        fun release() {
            try { current?.recycle() } catch (_: Exception) { }
            current = null
        }
    }

    /** ARGB bitmap -> I420 (nv12=false) or NV12 (nv12=true); returns bytes used */
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
