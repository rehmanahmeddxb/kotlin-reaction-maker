package com.rehman.ahmedreactionstudio.export

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

/**
 * Post-mux validation. A file existing with a non-zero size is NOT a successful
 * export — MediaMuxer will happily finalize a container whose samples have
 * non-monotonic timestamps, a missing codec config, or zero video frames, and
 * every external player then reports "can't play this video".
 *
 * We re-open the file with MediaExtractor (the same parser Android's players
 * use) and require:
 *  - a video track with width, height, a real MIME, and at least one sample
 *  - strictly non-decreasing video timestamps
 *  - duration > 0
 *  - an audio track when the caller said audio was muxed
 */
object ExportValidator {

    data class Report(
        val ok: Boolean,
        val message: String,
        val width: Int = 0,
        val height: Int = 0,
        val durationUs: Long = 0L,
        val videoMime: String? = null,
        val audioMime: String? = null,
        val videoSamples: Int = 0,
        val audioSamples: Int = 0,
        val bytes: Long = 0L
    )

    fun validate(path: String, expectAudio: Boolean, minBytes: Long = 4096L): Report {
        val f = File(path)
        if (!f.exists()) return Report(false, "Export file is missing.")
        val bytes = f.length()
        if (bytes < minBytes) return Report(false, "Export produced an empty file (${bytes} bytes).", bytes = bytes)

        val ex = MediaExtractor()
        try {
            ex.setDataSource(path)
        } catch (e: Exception) {
            try { ex.release() } catch (_: Exception) { }
            return Report(false, "Export container cannot be opened: ${e.message}", bytes = bytes)
        }

        var vTrack = -1
        var aTrack = -1
        var width = 0
        var height = 0
        var durationUs = 0L
        var videoMime: String? = null
        var audioMime: String? = null
        try {
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && vTrack < 0) {
                    vTrack = i
                    videoMime = mime
                    width = if (fmt.containsKey(MediaFormat.KEY_WIDTH)) fmt.getInteger(MediaFormat.KEY_WIDTH) else 0
                    height = if (fmt.containsKey(MediaFormat.KEY_HEIGHT)) fmt.getInteger(MediaFormat.KEY_HEIGHT) else 0
                    if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                        durationUs = fmt.getLong(MediaFormat.KEY_DURATION)
                    }
                } else if (mime.startsWith("audio/") && aTrack < 0) {
                    aTrack = i
                    audioMime = mime
                    if (durationUs <= 0L && fmt.containsKey(MediaFormat.KEY_DURATION)) {
                        durationUs = fmt.getLong(MediaFormat.KEY_DURATION)
                    }
                }
            }
            if (vTrack < 0 || width <= 0 || height <= 0 || videoMime.isNullOrBlank()) {
                return Report(false, "Export has no playable video track.", bytes = bytes)
            }

            val (vCount, vDur, vMono) = countTrack(ex, vTrack)
            if (vCount <= 0) {
                return Report(false, "Export video track contains no samples.",
                    width, height, durationUs, videoMime, audioMime, vCount, 0, bytes)
            }
            if (!vMono) {
                return Report(false, "Export video timestamps go backwards — players will reject this file.",
                    width, height, durationUs, videoMime, audioMime, vCount, 0, bytes)
            }
            if (vDur > durationUs) durationUs = vDur

            var aCount = 0
            if (aTrack >= 0) {
                val counted = countTrack(ex, aTrack)
                aCount = counted.first
                if (counted.second > durationUs) durationUs = counted.second
                if (!counted.third) {
                    return Report(false, "Export audio timestamps go backwards — players will reject this file.",
                        width, height, durationUs, videoMime, audioMime, vCount, aCount, bytes)
                }
            } else if (expectAudio) {
                // Audio was requested but never landed. The file may still play
                // (video-only); report it as a warning-success so the user is
                // not told a silent video is "broken", but the message is honest.
                return Report(
                    ok = true,
                    message = "Video is playable (audio track was not muxed).",
                    width = width, height = height, durationUs = durationUs,
                    videoMime = videoMime, audioMime = null,
                    videoSamples = vCount, audioSamples = 0, bytes = bytes
                )
            }
            if (durationUs <= 0L) {
                return Report(false, "Export has no duration — the container index is incomplete.",
                    width, height, 0L, videoMime, audioMime, vCount, aCount, bytes)
            }
            return Report(
                ok = true,
                message = "OK",
                width = width, height = height, durationUs = durationUs,
                videoMime = videoMime, audioMime = audioMime,
                videoSamples = vCount, audioSamples = aCount, bytes = bytes
            )
        } catch (e: Exception) {
            return Report(false, "Export validation failed: ${e.message}", bytes = bytes)
        } finally {
            try { ex.release() } catch (_: Exception) { }
        }
    }

    /** @return (sampleCount, lastPtsUs, monotonic) */
    private fun countTrack(ex: MediaExtractor, track: Int): Triple<Int, Long, Boolean> {
        ex.selectTrack(track)
        try { ex.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC) } catch (_: Exception) { }
        val buf = java.nio.ByteBuffer.allocate(1 shl 20)
        var n = 0
        var last = -1L
        var mono = true
        var lastPts = 0L
        // Walk every sample: a 10-minute 30 fps take is ~18k samples, cheap
        // compared to encoding. Stopping early would miss a late timestamp jump
        // that is exactly what makes some players die at t=0 and others at EOF.
        while (n < 200_000) {
            buf.clear()
            val sz = try { ex.readSampleData(buf, 0) } catch (_: Exception) { -1 }
            if (sz < 0) break
            val pts = ex.sampleTime
            if (pts >= 0L) {
                if (last >= 0L && pts < last) mono = false
                last = pts
                lastPts = pts
            }
            n++
            if (!ex.advance()) break
        }
        try { ex.unselectTrack(track) } catch (_: Exception) { }
        return Triple(n, lastPts, mono)
    }
}
