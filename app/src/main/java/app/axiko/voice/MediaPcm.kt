package app.axiko.voice

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes Android-supported audio files and keeps their original PCM sample rate. */
internal object MediaPcm {
    private const val POLL_US = 10_000L
    private const val DECODER_IDLE_NS = 5_000_000_000L

    internal data class Format(val rate: Int, val channels: Int, val encoding: Int) {
        val sampleBytes = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> error("Неподдерживаемый формат звука")
        }

        init {
            check(rate > 0 && channels in 1..32) { "Некорректный формат звука" }
        }

        val frameBytes = channels * sampleBytes
    }

    internal class Decoded(val format: Format, private val pcm: ByteArray) {
        private val frames = pcm.size / format.frameBytes

        init {
            check(frames > 0 && pcm.size % format.frameBytes == 0) { "В файле нет целых звуковых кадров" }
        }

        fun monoSamples(): FloatArray {
            val source = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(frames) { monoFrame(source) }
        }

        fun mono16(): ByteArray {
            if (format.channels == 1 && format.encoding == AudioFormat.ENCODING_PCM_16BIT) return pcm
            val source = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
            val output = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(frames) {
                val sample = (monoFrame(source) * 32768f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                output.putShort(sample.toShort())
            }
            return output.array()
        }

        private fun monoFrame(source: ByteBuffer): Float {
            var sum = 0f
            repeat(format.channels) {
                sum += when (format.encoding) {
                    AudioFormat.ENCODING_PCM_8BIT -> ((source.get().toInt() and 255) - 128) / 128f
                    AudioFormat.ENCODING_PCM_16BIT -> source.short / 32768f
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                        val low = source.get().toInt() and 255
                        val middle = (source.get().toInt() and 255) shl 8
                        val high = source.get().toInt() shl 16
                        (low or middle or high) / 8388608f
                    }
                    AudioFormat.ENCODING_PCM_32BIT -> source.int / 2147483648f
                    else -> source.float.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
                }
            }
            return sum / format.channels
        }
    }

    fun decode(path: String, maxBytes: Int = Int.MAX_VALUE, alive: () -> Boolean = { true }): Decoded {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
            } ?: error("В файле нет звука")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            return if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
                Decoded(pcmFormat(format), readPcm(extractor, maxBytes, alive))
            } else decodeCompressed(extractor, format, mime, maxBytes, alive)
        } finally {
            extractor.release()
        }
    }

    private fun pcmFormat(format: MediaFormat) = Format(
        format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
        format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
        format.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT),
    )

    private fun readPcm(extractor: MediaExtractor, maxBytes: Int, alive: () -> Boolean): ByteArray {
        val pcm = ByteArrayOutputStream()
        var buffer = ByteBuffer.allocate(8192)
        while (true) {
            check(alive()) { "Декодирование звука остановлено" }
            val expected = extractor.sampleSize
            if (expected < 0) break
            check(expected <= maxBytes - pcm.size()) { "Декодированный звук слишком велик" }
            if (expected > buffer.capacity()) buffer = ByteBuffer.allocate(expected.toInt())
            buffer.clear()
            val count = extractor.readSampleData(buffer, 0)
            if (count < 0) break
            pcm.write(buffer.array(), 0, count)
            extractor.advance()
        }
        return pcm.toByteArray()
    }

    private fun decodeCompressed(
        extractor: MediaExtractor, format: MediaFormat, mime: String, maxBytes: Int, alive: () -> Boolean,
    ): Decoded {
        val codec = MediaCodec.createDecoderByType(mime)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            var outputFormat = pcmFormat(format)
            val pcm = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var lastProgress = System.nanoTime()
            while (true) {
                check(alive()) { "Декодирование звука остановлено" }
                if (!inputDone) {
                    val input = codec.dequeueInputBuffer(POLL_US)
                    if (input >= 0) {
                        val size = extractor.readSampleData(codec.getInputBuffer(input)!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        lastProgress = System.nanoTime()
                    }
                }
                val output = codec.dequeueOutputBuffer(info, POLL_US)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val changed = pcmFormat(codec.outputFormat)
                    check(pcm.size() == 0 || changed == outputFormat) { "Формат звука изменился при воспроизведении" }
                    outputFormat = changed
                    lastProgress = System.nanoTime()
                } else if (output >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(info.size <= maxBytes - pcm.size()) { "Декодированный звук слишком велик" }
                            val bytes = ByteArray(info.size)
                            val buffer = codec.getOutputBuffer(output)!!
                            buffer.position(info.offset)
                            buffer.get(bytes)
                            pcm.write(bytes)
                        }
                    } finally {
                        codec.releaseOutputBuffer(output, false)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    lastProgress = System.nanoTime()
                }
                check(System.nanoTime() - lastProgress < DECODER_IDLE_NS) { "Декодер не вернул звук" }
            }
            return Decoded(outputFormat, pcm.toByteArray())
        } finally {
            codec.release()
        }
    }
}
