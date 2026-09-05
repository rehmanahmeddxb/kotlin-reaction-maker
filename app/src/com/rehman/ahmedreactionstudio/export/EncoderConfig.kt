package com.rehman.ahmedreactionstudio.export

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build

/**
 * OBS / BANDICAM-CLASS ENCODER TUNING.
 *
 * The old settings are why a long recording came out enormous:
 *
 *   KEY_I_FRAME_INTERVAL = 1     -> a keyframe EVERY SECOND. A keyframe costs
 *                                   roughly 10x a predicted frame, so at 30 fps
 *                                   about a quarter of the whole file was spent
 *                                   re-sending pictures that barely changed.
 *                                   OBS and Bandicam default to 2-5 s.
 *   no bitrate mode              -> the encoder picks CBR on most chipsets and
 *                                   pads quiet, static passages up to the full
 *                                   bitrate with literally nothing in them.
 *   no B-frames                  -> ~15-20 % of the possible saving left on the
 *                                   table on any device that supports them.
 *   flat bits-per-pixel table    -> 0.06/0.12/0.18 regardless of resolution,
 *                                   which massively over-spends at 720p+.
 *
 * Fixing those four things is where "big length in very few MB" comes from, and
 * none of it touches the preview, so playback smoothness cannot regress.
 */
object EncoderConfig {

    /** Export quality presets, smallest first. */
    enum class Quality(val label: String, val hint: String) {
        TINY("Tiny", "smallest file · good for chat apps"),
        SMALL("Small", "small file · very good quality"),
        BALANCED("Balanced", "recommended · OBS-like"),
        HIGH("High", "near-master quality");

        companion object {
            fun of(index: Int): Quality = entries[index.coerceIn(0, entries.size - 1)]
        }
    }

    /** Keyframe spacing. Long GOP = far smaller files; still seekable. */
    private const val GOP_SECONDS_EXPORT = 4
    /** The live recorder keeps a tighter GOP so a crash still leaves it playable. */
    private const val GOP_SECONDS_RECORD = 2

    /**
     * Bits per pixel per frame, tuned per preset.
     *
     * Crucially this is scaled DOWN as the frame grows: perceived quality tracks
     * bits-per-pixel sub-linearly, so a 1080p frame does not need 2.25x the bits
     * of a 720p frame to look equally good. The flat table the old code used is
     * what made high-resolution exports balloon.
     */
    private fun bitsPerPixel(q: Quality, w: Int, h: Int, hevc: Boolean): Double {
        val base = when (q) {
            Quality.TINY -> 0.022
            Quality.SMALL -> 0.035
            Quality.BALANCED -> 0.055
            Quality.HIGH -> 0.085
        }
        // resolution compensation: 720p is the reference point
        val pixels = (w.toLong() * h).coerceAtLeast(1L).toDouble()
        val ref = 1280.0 * 720.0
        val scale = Math.pow(ref / pixels, 0.22).coerceIn(0.55, 1.6)
        // HEVC reaches the same quality at roughly 60 % of the H.264 bitrate
        val codecFactor = if (hevc) 0.62 else 1.0
        return base * scale * codecFactor
    }

    /** Target bitrate in bits/second for the given output. */
    fun bitrateFor(q: Quality, w: Int, h: Int, fps: Int, mime: String): Int {
        val hevc = mime == MediaFormat.MIMETYPE_VIDEO_HEVC ||
            mime == MediaFormat.MIMETYPE_VIDEO_VP9
        val bpp = bitsPerPixel(q, w, h, hevc)
        val raw = w.toDouble() * h * fps * bpp
        return raw.toInt().coerceIn(250_000, 24_000_000)
    }

    /** Rough predicted size, so the export sheet can show real numbers. */
    fun predictedBytes(q: Quality, w: Int, h: Int, fps: Int, mime: String, durationMs: Long): Long {
        val video = bitrateFor(q, w, h, fps, mime).toLong()
        val audio = 128_000L
        return (video + audio) / 8L * (durationMs.coerceAtLeast(0L) / 1000L).coerceAtLeast(1L)
    }

    fun megabytesPerMinute(q: Quality, w: Int, h: Int, fps: Int, mime: String): Double =
        predictedBytes(q, w, h, fps, mime, 60_000L) / (1024.0 * 1024.0)

    /**
     * Build a fully tuned encoder format.
     *
     * Reliability rules (learned from unplayable-export bugs):
     *  - NEVER force a profile/level or B-frames: on several chipsets a
     *    byte-buffer HW encoder configured with High@L4.1 + B-frames emits a
     *    stream the device's own players reject outright ("file doesn't play
     *    at all" despite megabytes of samples). The encoder default (usually
     *    Baseline/Constrained, no B-frames) plays everywhere; VBR + long GOP
     *    keep almost all of the size win.
     *
     * @param liveRecorder true for the real-time RECORD path (tighter GOP)
     * @param compat true for the max-compatibility retry (tight GOP, CBR-ish
     *               default rate control, H.264 only at the call site)
     */
    fun videoFormat(
        mime: String,
        w: Int,
        h: Int,
        fps: Int,
        colorFormat: Int,
        quality: Quality,
        liveRecorder: Boolean = false,
        compat: Boolean = false
    ): MediaFormat {
        val fmt = MediaFormat.createVideoFormat(mime, w, h)
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrateFor(quality, w, h, fps, mime))
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        fmt.setInteger(
            MediaFormat.KEY_I_FRAME_INTERVAL,
            if (liveRecorder || compat) GOP_SECONDS_RECORD else GOP_SECONDS_EXPORT
        )

        // VBR: spend bits on motion, save them on static passages. This alone is
        // a large part of the OBS/Bandicam size advantage. Skipped in compat
        // mode, where the encoder default rate control is the safest choice.
        if (!compat) {
            try {
                fmt.setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                )
            } catch (_: Throwable) { }
        }

        // Keep colour metadata explicit so players do not guess (and shift hue).
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                fmt.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                fmt.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                fmt.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            } catch (_: Throwable) { }
        }
        return fmt
    }
}
