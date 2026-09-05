package com.rehman.ahmedreactionstudio.export

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * THE ONE PLACE A FINISHED VIDEO IS SAVED.
 *
 * The old code had three different half-savers (the exporter, the recorder and
 * UI.publishToGallery), and every one of them could report success while
 * nothing landed anywhere the user could reach:
 *
 *  - exports were written to getExternalFilesDir(MOVIES) — that is
 *    /Android/data/<pkg>/files/…, which Android 11+ hides from the Gallery AND
 *    from almost every file manager, so "it is not saving" was literally true;
 *  - publishToGallery swallowed every failure and still called back, so the
 *    dialog said "Saved to Gallery" over a MediaStore insert that had thrown;
 *  - the insert was never verified, so a 0-byte row counted as a success;
 *  - WebM output was published with an mp4 MIME type;
 *  - below API 29 nothing was published at all.
 *
 * [publishVideo] fixes all of that: it tries the public locations in order,
 * VERIFIES that bytes actually arrived, and returns exactly where the file
 * really is so the UI can tell the truth.
 */
object MediaSave {

    /** Public album folder name, used on every Android version. */
    const val ALBUM = "AhmedReactionStudio"

    /**
     * Where the file really ended up.
     *
     * @param uri       content URI when the file is in the media store (shareable, viewable)
     * @param path      filesystem path when we know it (null for pure MediaStore saves)
     * @param bytes     verified size actually written
     * @param location  human-readable location for the dialog
     * @param publiclyVisible true when a file manager / Gallery can see it
     */
    class Saved(
        val uri: Uri?,
        val path: String?,
        val bytes: Long,
        val location: String,
        val publiclyVisible: Boolean
    )

    fun mimeFor(ext: String): String = when (ext.lowercase()) {
        "webm" -> "video/webm"
        else -> "video/mp4"
    }

    /**
     * Copy [src] into a user-visible location and delete [src] on success.
     *
     * Order of attempts:
     *   1. MediaStore -> Movies/AhmedReactionStudio          (API 29+, verified)
     *   2. public Movies/AhmedReactionStudio on the SD card  (+ media scan)
     *   3. app-external Movies folder                        (always works; the
     *      caller is told plainly that it is an app folder)
     *
     * @return null only when every single attempt failed.
     */
    fun publishVideo(ctx: Context, src: File, displayName: String, mime: String): Saved? {
        if (!src.exists() || src.length() <= 0L) return null
        val expected = src.length()

        // ---- 1. MediaStore (scoped storage, shows in Gallery + Files) ----
        if (Build.VERSION.SDK_INT >= 29) {
            val saved = viaMediaStore(ctx, src, displayName, mime, expected)
            if (saved != null) {
                try { src.delete() } catch (_: Exception) { }
                return saved
            }
        }

        // ---- 2. real public Movies folder ----
        try {
            val pub = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                ALBUM
            )
            if (pub.mkdirs() || pub.isDirectory) {
                val dest = File(pub, displayName)
                src.copyTo(dest, overwrite = true)
                if (dest.exists() && dest.length() >= expected) {
                    // make it appear in the Gallery immediately
                    val uri = scan(ctx, dest, mime)
                    try { src.delete() } catch (_: Exception) { }
                    return Saved(uri, dest.absolutePath, dest.length(),
                        "Movies/$ALBUM", true)
                }
                try { dest.delete() } catch (_: Exception) { }
            }
        } catch (_: Throwable) { }

        // ---- 3. app-external folder (last resort, and we SAY so) ----
        try {
            val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), ALBUM)
            dir.mkdirs()
            val dest = File(dir, displayName)
            src.copyTo(dest, overwrite = true)
            if (dest.exists() && dest.length() > 0L) {
                try { src.delete() } catch (_: Exception) { }
                return Saved(null, dest.absolutePath, dest.length(),
                    dest.absolutePath, false)
            }
        } catch (_: Throwable) { }

        return null
    }

    /**
     * Insert + stream + verify. Any failure rolls the pending row back so a
     * broken 0-byte entry never pollutes the user's Gallery.
     */
    private fun viaMediaStore(
        ctx: Context, src: File, name: String, mime: String, expected: Long
    ): Saved? {
        val resolver = ctx.contentResolver
        var uri: Uri? = null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$ALBUM")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            var written = 0L
            resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { inp -> written = inp.copyTo(out) }
                out.flush()
            } ?: return rollback(ctx, uri)

            // VERIFY: a successful insert means nothing if no bytes landed.
            if (written < expected) return rollback(ctx, uri)
            val onDisk = sizeOf(ctx, uri)
            if (onDisk <= 0L) return rollback(ctx, uri)

            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Saved(uri, null, onDisk, "Movies/$ALBUM (Gallery)", true)
        } catch (_: Throwable) {
            rollback(ctx, uri)
        }
    }

    private fun rollback(ctx: Context, uri: Uri?): Saved? {
        if (uri != null) try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) { }
        return null
    }

    /** Real byte count behind a content URI (0 when unreadable). */
    private fun sizeOf(ctx: Context, uri: Uri): Long = try {
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize.coerceAtLeast(0L) } ?: 0L
    } catch (_: Throwable) { 0L }

    /** Media-scan a real file and return the content URI the scanner assigned. */
    private fun scan(ctx: Context, f: File, mime: String): Uri? {
        var result: Uri? = null
        try {
            val latch = java.util.concurrent.CountDownLatch(1)
            MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), arrayOf(mime)) { _, u ->
                result = u
                latch.countDown()
            }
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Throwable) { }
        if (result == null) {
            // scanner did not answer in time — register the row ourselves
            result = try {
                val values = ContentValues().apply {
                    @Suppress("DEPRECATION")
                    put(MediaStore.Video.Media.DATA, f.absolutePath)
                    put(MediaStore.Video.Media.MIME_TYPE, mime)
                    put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
                }
                ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            } catch (_: Throwable) { null }
        }
        return result
    }
}
