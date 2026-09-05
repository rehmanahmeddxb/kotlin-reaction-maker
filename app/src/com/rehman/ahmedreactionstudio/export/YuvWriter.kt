package com.rehman.ahmedreactionstudio.export

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import java.nio.ByteBuffer

/**
 * ARGB bitmap → encoder input.
 *
 * Root cause this exists: the old exporter/recorder treated every YUV color
 * format as tightly-packed NV12/I420 and wrote absolute indices into the
 * MediaCodec ByteBuffer. Hardware encoders almost always advertise
 * COLOR_FormatYUV420Flexible and expect **strided** planes (rowStride ≥ width,
 * UV pixelStride 1 or 2). Feeding packed NV12 into a strided buffer produces
 * a bitstream that muxes "successfully" and then will not play on Android
 * Gallery, desktop players, or anything that actually decodes it.
 *
 * The correct path (API 21+, our min is 26) is [MediaCodec.getInputImage]:
 * the Image's planes already have the layout this encoder wants. We convert
 * ARGB → BT.601 limited-range YUV into those planes. Packed NV12/I420 is
 * kept only as a fallback when getInputImage returns null.
 *
 * BT.709 limited-range matches EncoderConfig's COLOR_STANDARD_BT709 +
 * COLOR_RANGE_LIMITED metadata. Using 601 coefficients under 709 tags was
 * a small hue shift; using the wrong *layout* (packed vs strided) is what
 * made files unplayable.
 */
object YuvWriter {

    private val localPx = ThreadLocal<IntArray>()

    /** Fill encoder input [index] from [bmp]. Returns bytes to pass to queueInputBuffer, or 0 on failure. */
    fun fillInput(
        codec: MediaCodec,
        index: Int,
        bmp: Bitmap,
        w: Int,
        h: Int,
        packedNv12: Boolean
    ): Int {
        if (bmp.isRecycled || w <= 0 || h <= 0) return 0
        val image = try { codec.getInputImage(index) } catch (_: Exception) { null }
        if (image != null) {
            val n = fillImage(image, bmp, w, h)
            if (n > 0) return n
        }
        val buf = try { codec.getInputBuffer(index) } catch (_: Exception) { null } ?: return 0
        buf.clear()
        return fillPacked(buf, bmp, w, h, packedNv12)
    }

    /** Packed NV12/I420 into a ByteBuffer. Used by tests and the Image-less fallback. */
    fun fillPacked(dst: ByteBuffer, bmp: Bitmap, w: Int, h: Int, nv12: Boolean): Int {
        val px = pixelsOf(bmp, w, h)
        val ySize = w * h
        val uvSize = ySize / 4
        val need = ySize + uvSize * 2
        if (dst.capacity() < need) return 0
        val base = dst.position()
        var i = 0
        var rowBase = base
        for (j in 0 until h) {
            for (k in 0 until w) {
                dst.put(rowBase + k, yOf(px[i]).toByte())
                i++
            }
            rowBase += w
        }
        val uOff = base + ySize
        val vOff = uOff + uvSize
        var uPos = uOff
        for (j in 0 until h / 2) {
            for (k in 0 until w / 2) {
                val c = px[(j * 2) * w + (k * 2)]
                val cb = uOf(c)
                val cr = vOf(c)
                if (nv12) {
                    dst.put(uPos, cb.toByte())
                    dst.put(uPos + 1, cr.toByte())
                    uPos += 2
                } else {
                    dst.put(uPos, cb.toByte())
                    dst.put(vOff + (uPos - uOff), cr.toByte())
                    uPos += 1
                }
            }
        }
        return need
    }

    private fun fillImage(image: Image, bmp: Bitmap, srcW: Int, srcH: Int): Int {
        val planes = image.planes
        if (planes.size < 3) return 0
        val iw = image.width
        val ih = image.height
        if (iw <= 0 || ih <= 0) return 0
        val w = (minOf(srcW, iw) and 1.inv()).coerceAtLeast(2)
        val h = (minOf(srcH, ih) and 1.inv()).coerceAtLeast(2)
        val px = pixelsOf(bmp, w, h)

        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        // Image plane buffers are often slices: index 0 is not the start of
        // valid data. Android documents using the current position as the base.
        val yBase = yBuf.position()
        val uBase = uBuf.position()
        val vBase = vBuf.position()
        val yRS = yPlane.rowStride
        val yPS = yPlane.pixelStride.coerceAtLeast(1)
        val uRS = uPlane.rowStride
        val uPS = uPlane.pixelStride.coerceAtLeast(1)
        val vRS = vPlane.rowStride
        val vPS = vPlane.pixelStride.coerceAtLeast(1)

        for (j in 0 until h) {
            val row = yBase + j * yRS
            var i = j * w
            for (k in 0 until w) {
                val off = row + k * yPS
                if (off < yBuf.capacity()) yBuf.put(off, yOf(px[i]).toByte())
                i++
            }
        }
        for (j in 0 until h / 2) {
            val uRow = uBase + j * uRS
            val vRow = vBase + j * vRS
            for (k in 0 until w / 2) {
                val c = px[(j * 2) * w + (k * 2)]
                val uOff = uRow + k * uPS
                val vOff = vRow + k * vPS
                if (uOff < uBuf.capacity()) uBuf.put(uOff, uOf(c).toByte())
                if (vOff < vBuf.capacity()) vBuf.put(vOff, vOf(c).toByte())
            }
        }
        // queueInputBuffer wants a byte count. Strided images are larger than
        // packed 4:2:0; the encoder reads via the Image, so capacity is safe.
        return yBuf.capacity().coerceAtLeast(w * h * 3 / 2)
    }

    private fun pixelsOf(bmp: Bitmap, w: Int, h: Int): IntArray {
        val need = w * h
        var a = localPx.get()
        if (a == null || a.size < need) {
            a = IntArray(need)
            localPx.set(a)
        }
        val bw = bmp.width
        val bh = bmp.height
        if (bw == w && bh == h) {
            bmp.getPixels(a, 0, w, 0, 0, w, h)
        } else {
            // Encoder size and bitmap size should match. If they don't (a
            // session rebuild, an odd crop), sample nearest-neighbour rather
            // than crash the encode thread.
            val tmp = IntArray(bw * bh)
            bmp.getPixels(tmp, 0, bw, 0, 0, bw, bh)
            for (j in 0 until h) {
                val sy = (j.toLong() * bh / h).toInt().coerceIn(0, bh - 1)
                for (i in 0 until w) {
                    val sx = (i.toLong() * bw / w).toInt().coerceIn(0, bw - 1)
                    a[j * w + i] = tmp[sy * bw + sx]
                }
            }
        }
        return a
    }

    /** BT.709 limited-range Y (16..235) from packed ARGB int. */
    fun yOf(c: Int): Int {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        var y = ((47 * r + 157 * g + 16 * b + 128) shr 8) + 16
        if (y < 16) y = 16 else if (y > 235) y = 235
        return y
    }

    /** BT.709 limited-range Cb (16..240). */
    fun uOf(c: Int): Int {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        var cb = ((-26 * r - 87 * g + 112 * b + 128) shr 8) + 128
        if (cb < 16) cb = 16 else if (cb > 240) cb = 240
        return cb
    }

    /** BT.709 limited-range Cr (16..240). */
    fun vOf(c: Int): Int {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        var cr = ((112 * r - 102 * g - 10 * b + 128) shr 8) + 128
        if (cr < 16) cr = 16 else if (cr > 240) cr = 240
        return cr
    }
}

/**
 * One encoder/muxer timeline. Presentation timestamps MUST be strictly
 * increasing per track; a backwards or zero EOS timestamp is a classic way
 * to produce an MP4 that MediaMuxer finalizes and every player then rejects.
 */
class MonotonicPts(private val startUs: Long = 0L) {
    private var last = -1L

    fun next(candidateUs: Long): Long {
        var v = candidateUs
        if (v < startUs) v = startUs
        if (v <= last) v = last + 1L
        last = v
        return v
    }

    fun lastOr(fallback: Long): Long = if (last >= 0L) last else fallback
}
