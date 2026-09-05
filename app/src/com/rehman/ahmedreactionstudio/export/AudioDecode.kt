package com.rehman.ahmedreactionstudio.export

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A clip's audio track decoded to mono 16-bit PCM at a fixed sample rate. */
class PcmClip(val data: ShortArray, val sampleRate: Int)

/**
 * Decodes a media file's audio track to mono 16-bit PCM at [TARGET_RATE],
 * so several clips + the microphone can be summed sample-by-sample.
 *
 * Handles the two PCM encodings the framework audio decoders produce
 * (16-bit and float), and resamples / downmixes to a single common format.
 */
object AudioDecode {

    private val TARGET_RATE = AudioConfig.SAMPLE_RATE

    fun toPcmMono(path: String): PcmClip? {
        val extractor = MediaExtractor()
        try { extractor.setDataSource(path) } catch (_: Exception) {
            extractor.release(); return null
        }

        var trackIdx = -1
        var mime: String? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val m = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (m.startsWith("audio/")) { trackIdx = i; mime = m; break }
        }
        if (trackIdx < 0 || mime == null) { extractor.release(); return null }
        extractor.selectTrack(trackIdx)
        val format = extractor.getTrackFormat(trackIdx)

        val codec = try { MediaCodec.createDecoderByType(mime) } catch (_: Exception) { null }
        if (codec == null) { extractor.release(); return null }
        try { codec.configure(format, null, null, 0) } catch (_: Exception) {
            extractor.release(); return null
        }
        codec.start()

        var nativeRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
        var nativeChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0
        var pcmFloat = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
            format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT

        val info = MediaCodec.BufferInfo()
        val chunks = ArrayList<ShortArray>()
        var totalSamples = 0
        var eosInput = false
        var eosOutput = false
        var stall = 0

        while (!eosOutput && stall < 500) {
            if (!eosInput) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)
                    val n = extractor.readSampleData(buf!!, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosInput = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // The decoder output format is authoritative: always adopt
                    // it (the old code kept stale extractor values, so a file
                    // whose container said mono but decoded stereo — or vice
                    // versa — was downmixed with the wrong channel count,
                    // producing half-speed/double-speed garbage).
                    val of = codec.outputFormat
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        val r = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (r > 0) nativeRate = r
                    }
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        val c = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (c > 0) nativeChannels = c
                    }
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING))
                        pcmFloat = of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> stall++
                outIdx >= 0 -> {
                    val buf = codec.getOutputBuffer(outIdx)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val arr = readSamples(buf, info.size, pcmFloat)
                        chunks.add(arr)
                        totalSamples += arr.size
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outIdx, false)
                    if (eos) eosOutput = true
                    stall = 0
                }
                else -> stall++
            }
        }
        try { codec.stop() } catch (_: Exception) { }
        try { codec.release() } catch (_: Exception) { }
        try { extractor.release() } catch (_: Exception) { }

        if (totalSamples == 0) return null
        if (nativeRate <= 0) nativeRate = TARGET_RATE
        if (nativeChannels <= 0) nativeChannels = 1
        val mono = resampleToMono(chunks, totalSamples, nativeRate, nativeChannels)
        return if (mono.isEmpty()) null else PcmClip(mono, TARGET_RATE)
    }

    /** Read [size] bytes of decoder output as 16-bit samples (handles float PCM). */
    private fun readSamples(buf: ByteBuffer, size: Int, pcmFloat: Boolean): ShortArray {
        // STEP 3: MediaCodec raw-audio buffers are NATIVE byte order
        // (little-endian on Android). ByteBuffer defaults to BIG_ENDIAN, so
        // reading without this swaps every sample's bytes -> harsh distorted
        // noise. The official MediaCodec docs show exactly this call.
        try { buf.order(ByteOrder.nativeOrder()) } catch (_: Exception) { }
        return if (pcmFloat) {
            val n = size / 4
            val arr = ShortArray(n)
            for (i in 0 until n) {
                val f = buf.float
                val v = (f * 32767f).toInt().coerceIn(-32768, 32767)
                arr[i] = v.toShort()
            }
            arr
        } else {
            val n = size / 2
            val arr = ShortArray(n)
            buf.asShortBuffer().get(arr)
            arr
        }
    }

    /** Flatten, resample to [TARGET_RATE] and downmix to mono with linear interpolation. */
    private fun resampleToMono(
        chunks: List<ShortArray>,
        totalSamples: Int,
        nativeRate: Int,
        nativeChannels: Int
    ): ShortArray {
        val flat = ShortArray(totalSamples)
        var pos = 0
        for (c in chunks) { System.arraycopy(c, 0, flat, pos, c.size); pos += c.size }
        val nativeFrames = totalSamples / nativeChannels
        if (nativeFrames <= 0) return ShortArray(0)
        val outFrames = (nativeFrames.toLong() * TARGET_RATE / nativeRate).toInt().coerceAtLeast(1)
        val out = ShortArray(outFrames)
        for (i in 0 until outFrames) {
            val fpos = i.toDouble() * nativeRate / TARGET_RATE
            val idx = fpos.toInt()
            val frac = (fpos - idx).toFloat()
            var sum = 0f
            for (c in 0 until nativeChannels) {
                val a = flat[idx * nativeChannels + c].toFloat()
                val b = flat[(idx + 1).coerceAtMost(nativeFrames - 1) * nativeChannels + c].toFloat()
                sum += a * (1f - frac) + b * frac
            }
            val mono = (sum / nativeChannels).toInt().coerceIn(-32768, 32767)
            out[i] = mono.toShort()
        }
        return out
    }
}
