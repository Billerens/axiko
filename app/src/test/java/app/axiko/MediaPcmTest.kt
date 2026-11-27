package app.axiko

import android.media.AudioFormat
import app.axiko.voice.MediaPcm
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaPcmTest {
    @Test
    fun mono16PreservesOriginalRateAndSamples() {
        val bytes = sampleBytes(-32768, 0, 1234, 32767)
        val audio = MediaPcm.Decoded(MediaPcm.Format(24_000, 1, AudioFormat.ENCODING_PCM_16BIT), bytes)
        assertEquals(24_000, audio.format.rate)
        assertArrayEquals(bytes, audio.mono16())
    }

    @Test
    fun downmixFeedsSpeechRecognitionAndPlaybackTheSameFrames() {
        val stereo = sampleBytes(-32768, 0, 10_000, 30_000, -1024, 1024)
        val audio = MediaPcm.Decoded(MediaPcm.Format(44_100, 2, AudioFormat.ENCODING_PCM_16BIT), stereo)
        assertArrayEquals(sampleBytes(-16384, 20_000, 0), audio.mono16())
        assertArrayEquals(floatArrayOf(-0.5f, 20_000 / 32768f, 0f), audio.monoSamples(), 0f)
    }

    @Test
    fun packed24BitSamplesPreserveTheirSign() {
        val packed = byteArrayOf(0, 0, -128, -1, -1, 127)
        val audio = MediaPcm.Decoded(MediaPcm.Format(48_000, 1, AudioFormat.ENCODING_PCM_24BIT_PACKED), packed)
        assertArrayEquals(sampleBytes(-32768, 32767), audio.mono16())
    }

    private fun sampleBytes(vararg samples: Int): ByteArray =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (sample in samples) putShort(sample.toShort())
        }.array()
}
