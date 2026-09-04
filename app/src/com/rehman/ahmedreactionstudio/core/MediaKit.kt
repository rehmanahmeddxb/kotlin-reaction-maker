package com.rehman.ahmedreactionstudio.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
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
     * Frame of a video at media-time ms. Keeps source rotation in the bitmap.
     * Safe for any thread; caller must recycle/retain results.
     */
    fun videoFrame(path: String, ms: Long, maxPx: Int = 1280): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            val t = ms.coerceAtLeast(0L) * 1000L
            val bmp = r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            var out = bmp
            if (rot != 0) {
                val m = Matrix(); m.postRotate(rot.toFloat())
                out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (out !== bmp) bmp.recycle()
            }
            val scale = maxPx.toFloat() / kotlin.math.max(out.width, out.height)
            if (scale < 1f) {
                val m = Matrix(); m.postScale(scale, scale)
                val small = Bitmap.createBitmap(out, 0, 0, out.width, out.height, m, true)
                if (small !== out) out.recycle()
                return small
            }
            out
        } catch (_: Exception) { null } finally {
            try { r.release() } catch (_: Exception) { }
        }
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
