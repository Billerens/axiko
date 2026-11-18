package app.axiko

import app.axiko.voice.Stt
import app.axiko.voice.Tts
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Speech rendered to a file and phrases found in one, in the shape modules get them. */
class AudioFilesTest {
    @Test
    fun wavHeaderDescribesThePcm() {
        val pcm = ByteArray(10) { it.toByte() }
        val file = Tts.wav(pcm, 22_050)
        assertEquals(44 + pcm.size, file.size)
        val header = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(file, 0, 4))
        assertEquals(36 + pcm.size, header.getInt(4))
        assertEquals("WAVE", String(file, 8, 4))
        assertEquals("fmt ", String(file, 12, 4))
        assertEquals(1, header.getShort(20).toInt()) // PCM
        assertEquals(1, header.getShort(22).toInt()) // mono
        assertEquals(22_050, header.getInt(24))
        assertEquals(44_100, header.getInt(28)) // bytes per second
        assertEquals(16, header.getShort(34).toInt())
        assertEquals("data", String(file, 36, 4))
        assertEquals(pcm.size, header.getInt(40))
        assertArrayEquals(pcm, file.copyOfRange(44, file.size))
    }

    @Test
    fun phraseTimesAreMillisecondsAt16kHz() {
        val phrase = Stt.timed(8_000, 24_000, "привет")
        assertEquals(500L, phrase.getLong("startMs"))
        assertEquals(2_000L, phrase.getLong("endMs"))
        assertEquals("привет", phrase.getString("text"))
    }
}
