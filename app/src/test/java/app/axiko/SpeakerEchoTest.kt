package app.axiko

import app.axiko.voice.PcmEchoReference
import app.axiko.voice.SpeakerEchoGuard
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerEchoTest {
    private val playback = signal(3400, 27)

    @Test
    fun followsPlaybackHeadAcrossRingWrapAndRejectsLouderEcho() {
        val reference = reference(playback)
        val guard = SpeakerEchoGuard()
        for (index in 0..7) {
            val start = 2700 + index * 64
            val window = microphone(start, if (index < 4) 0.4f else 0.9f)
            // A 70 ms delay between the recorded echo and the playback head.
            val match = requireNotNull(reference.match(window, (start + 64 + 140).toLong() * 12))
            assertTrue("Delayed echo stays aligned, including after wrap", match.similarity > .999)
            assertFalse("Turning up the speaker is not a user interruption", guard.hearsOwner(level(window), match))
        }
    }

    @Test
    fun distinguishesForegroundVoiceAfterCalibration() {
        val reference = reference(playback)
        val guard = SpeakerEchoGuard()
        for (index in 0..8) {
            val start = 2720 + index * 56
            val person = if (index < 4) null else signal(64, 39 + index)
            val window = microphone(start, 0.36f, person)
            val match = reference.match(window, (start + 64 + 115).toLong() * 12)
            assertTrue("A loud independent voice is accepted over established echo",
                guard.hearsOwner(level(window), match) == (person != null))
        }
        assertFalse("Even a calibrated guard needs a live PCM reference", guard.hearsOwner(.2, null))
    }

    @Test
    fun uncalibratedEchoAndAudioNotPlayedYetRemainConservative() {
        val reference = reference(playback)
        val guard = SpeakerEchoGuard()
        val independent = microphone(2760, 0f, signal(64, 10))
        val match = reference.match(independent, (2760 + 64 + 120).toLong() * 12)
        assertNotNull(match)
        assertFalse("Unrelated sound is not trusted without calibration", guard.hearsOwner(level(independent), match))
        assertFalse("Unplayed queued audio cannot supply a valid match",
            guard.hearsOwner(level(independent), reference.match(independent, 0)))
    }

    private fun signal(length: Int, seed: Int): FloatArray {
        val random = Random(seed)
        return FloatArray(length) { (random.nextFloat() - .5f) * .45f }
    }

    private fun reference(source: FloatArray): PcmEchoReference {
        // Eight mic frames or twelve PCM frames represent one reference sample.
        val pcm = ByteArray(source.size * 12 * 2)
        for (index in 0 until pcm.size / 2) {
            val sample = (source[index / 12] * 32768).toInt()
            pcm[index * 2] = sample.toByte()
            pcm[index * 2 + 1] = (sample shr 8).toByte()
        }
        return PcmEchoReference(24_000).also {
            for (offset in pcm.indices step 406) it.append(pcm, offset, minOf(406, pcm.size - offset))
        }
    }

    private fun microphone(start: Int, gain: Float, person: FloatArray? = null): FloatArray = FloatArray(512) { index ->
        val group = index / 8
        .02f + playback[start + group] * gain + (person?.get(group) ?: 0f)
    }

    private fun level(window: FloatArray): Double = sqrt(window.map { (it * it).toDouble() }.average())
}
