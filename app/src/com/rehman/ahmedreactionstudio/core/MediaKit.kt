package com.rehman.ahmedreactionstudio.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import java.io.File

/** Probed metadata of one media file. */
data class MediaInfo(
    val durMs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val mime: String?
)

/**
 * Framework-API media probing + frame extraction.
 * Videos are decoded on demand (never stored in memory, spec 53).
 */
object MediaKit {

    fun probe(path: String): MediaInfo {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            val d = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val mime = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            MediaInfo(d, w, h, rot, mime)
        } catch (_: Exception) {
            MediaInfo(0, 0, 0, 0, null)
        } finally {
            try { r.release() } catch (_: Exception) { }
        }
    }

    fun probe(uri: Uri, ctx: Context): MediaInfo {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val d = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            MediaInfo(d, w, h, rot, null)
        } catch (_: Exception) {
            MediaInfo(0, 0, 0, 0, null)
        } finally {
            try { r.release() } catch (_: Exception) { }
        }
    }

    fun isImageMime(mime: String?): Boolean =
        mime != null && (mime.startsWith("image/") || mime == "image/webp")

    /**
     * A REUSABLE frame decoder for one media file.
     *
     * Opening a MediaMetadataRetriever parses the whole container (tens of ms
     * on a phone) and the preview used to pay that for every single frame, on
     * top of a full-resolution CLOSEST_SYNC decode — that is what made an
     * imported clip stutter. Keeping ONE retriever per file and walking it
     * forward with PREVIOUS_SYNC, decoded straight to the preview size, is an
     * order of magnitude cheaper.
     *
     * Not thread safe by contract: one layer, one caller — [frameAt] is
     * synchronized so an accidental second caller cannot corrupt it.
     */
    class FrameSource(val path: String) {
        private var r: MediaMetadataRetriever? = null
        private var rot = 0
        private var broken = false

        /** wall-clock cost of the most recent [frameAt] (0 = none yet) */
        var lastDecodeMs = 0L
            private set
        /** true once the decoder returned a frame at least once */
        var produced = false
            private set

        @Synchronized
        private fun retriever(): MediaMetadataRetriever? {
            if (broken) return null
            r?.let { return it }
            val x = try {
                val nr = MediaMetadataRetriever()
                nr.setDataSource(path)
                rot = nr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
                nr
            } catch (_: Exception) {
                broken = true
                null
            }
            r = x
            return x
        }

        /**
         * Frame at [ms] of media time, longest side <= [maxPx] (0 = source size).
         * [closest] trades speed for accuracy: playback wants PREVIOUS_SYNC,
         * a scrub/seek wants CLOSEST_SYNC.
         */
        @Synchronized
        fun frameAt(ms: Long, maxPx: Int, closest: Boolean = false): Bitmap? {
            if (broken) return null
            val rr = retriever() ?: return null
            val t0 = SystemClock.elapsedRealtime()
            val tUs = ms.coerceAtLeast(0L) * 1000L
            val opt = if (closest) MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            else MediaMetadataRetriever.OPTION_PREVIOUS_SYNC
            var raw: Bitmap? = null
            if (android.os.Build.VERSION.SDK_INT >= 27 && maxPx > 0) {
                raw = try { rr.getScaledFrameAtTime(tUs, opt, maxPx, maxPx) } catch (_: Exception) { null }
            }
            if (raw == null) {
                raw = try { rr.getFrameAtTime(tUs, opt) } catch (_: Exception) { null }
            }
            lastDecodeMs = SystemClock.elapsedRealtime() - t0
            if (raw == null) return null
            produced = true
            return postProcess(raw, maxPx)
        }

        /** one Matrix pass for rotation + (fallback) downscale, no extra copies */
        private fun postProcess(src: Bitmap, maxPx: Int): Bitmap {
            val scale = if (maxPx > 0) maxPx.toFloat() / kotlin.math.max(src.width, src.height) else 1f
            val needScale = scale < 0.999f
            if (rot == 0 && !needScale) return src
            val m = Matrix()
            if (rot != 0) m.postRotate(rot.toFloat())
            if (needScale) m.postScale(scale, scale)
            val out = try {
                Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            } catch (_: Exception) { null }
            if (out != null && out !== src) src.recycle()
            return out ?: src
        }

        @Synchronized
        fun release() {
            try { r?.release() } catch (_: Exception) { }
            r = null
            broken = true
        }
    }

    /**
     * One-shot frame of a video at media-time ms (opens and closes its own
     * retriever). For repeated reads of the same file use [FrameSource].
     */
    fun videoFrame(path: String, ms: Long, maxPx: Int = 1280, closest: Boolean = false): Bitmap? {
        val s = FrameSource(path)
        return try { s.frameAt(ms, maxPx, closest) } finally { s.release() }
    }

    fun image(path: String, maxPx: Int = 2048): Bitmap? {
        return try {
            val opts = BitmapFactory.Options()
            opts.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, opts)
            var sample = 1
            val big = kotlin.math.max(opts.outWidth, opts.outHeight)
            while (big / (sample * 2) > maxPx) sample *= 2
            val o2 = BitmapFactory.Options(); o2.inSampleSize = sample
            BitmapFactory.decodeFile(path, o2)
        } catch (_: Exception) { null }
    }

    /** Copies content-URI content into an app-private file (SAF one-shot import). */
    fun copyContentToFile(ctx: Context, uri: Uri, dest: File): Boolean {
        return try {
            dest.parentFile?.mkdirs()
            val ins = ctx.contentResolver.openInputStream(uri) ?: return false
            val out = dest.outputStream()
            ins.copyTo(out)
            out.flush(); out.close(); ins.close()
            true
        } catch (_: Exception) { false }
    }
}
