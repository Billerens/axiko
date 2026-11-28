package app.axiko

import app.axiko.voice.EchoMatch
import app.axiko.voice.PcmEchoReference
import app.axiko.voice.SpeakerCapture
import app.axiko.voice.SpeakerCapture.Window
import app.axiko.voice.SpeakerEchoGuard
import app.axiko.voice.SpeakerOverlap
import java.util.Random as JavaRandom
import kotlin.random.Random
import org.junit.Assert.assertEquals
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
            assertFalse("Turning up the speaker is not a user interruption", guard.hearsOwner(match))
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
                guard.hearsOwner(match) == (person != null))
        }
        assertFalse("Even a calibrated guard needs a live PCM reference", guard.hearsOwner(null))
    }

    @Test
    fun uncalibratedEchoAndAudioNotPlayedYetRemainConservative() {
        val reference = reference(playback)
        val guard = SpeakerEchoGuard()
        val independent = microphone(2760, 0f, signal(64, 10))
        val match = reference.match(independent, (2760 + 64 + 120).toLong() * 12)
        assertNotNull(match)
        assertFalse("Unrelated sound is not trusted without calibration", guard.hearsOwner(match))
        assertFalse("Unplayed queued audio cannot supply a valid match", guard.hearsOwner(reference.match(independent, 0)))
    }

    @Test
    fun learnsMultipleReflectionsAndStillAcceptsVoiceOverThem() {
        val random = JavaRandom(18)
        val source = FloatArray(8_000) { (random.nextFloat() - .5f) * .42f }
        // Only the tail of the PCM track is in the playback-reference ring at any one time.
        val reference = PcmEchoReference(24_000)
        val guard = SpeakerEchoGuard()
        val overlap = SpeakerOverlap()
        var queued = 0
        var interrupted = false
        for (tick in 0 until 48) {
            val start = 350 + tick * 56
            val head = start + 64 + 140
            val next = head + 245
            reference.append(pcm(source.copyOfRange(queued, next)), 0, (next - queued) * 24)
            queued = next
            val microphone = FloatArray(64) { index ->
                val at = start + index
                val person = if (tick >= 12) (random.nextFloat() - .5f) * .24f else 0f
                source[at] * .36f + source[at - 5] * .31f - source[at - 13] * .32f + person
            }
            val match = reference.match(FloatArray(512) { microphone[it / 8] }, head * 12L)
            val sustained = overlap.record(guard.hearsOwner(match))
            if (tick < 12) assertFalse("Reflected speaker audio alone cannot interrupt", sustained)
            else interrupted = interrupted || sustained
        }
        assertTrue("A new voice reaches the interruption threshold over a reflected echo", interrupted)
    }

    @Test
    fun overlapToleratesBriefSyllableGapsButForgetsOldEvidence() {
        val overlap = SpeakerOverlap()
        val phrase = listOf(true, true, false, true, true, false, true, false, true, true)
        assertFalse(phrase.dropLast(1).any(overlap::record))
        assertTrue(overlap.record(phrase.last()))
        repeat(12) { overlap.record(false) }
        assertFalse(overlap.pending)
        assertFalse(overlap.record(true))
    }

    @Test
    fun anUnconfirmedFragmentAndUnreferencedTailCannotBecomeAnOwnerPhrase() {
        val capture = SpeakerCapture()
        assertTrue(capture.enter(Any(), 1))
        repeat(12) {
            assertEquals(Window.DROP, capture.record(mayInterrupt = true) { null })
        }
        repeat(4) { capture.record(mayInterrupt = true) { clearEcho } }
        assertEquals(Window.FOREGROUND, capture.record(mayInterrupt = true) { foreground })
        repeat(11) { assertEquals(Window.SILENCE, capture.record(mayInterrupt = false) { error("The tail cannot start a phrase") }) }
        assertEquals(Window.RESET, capture.record(mayInterrupt = false) { error("The tail cannot start a phrase") })
        assertTrue("Discard pending VAD state before accepting the next independent turn", capture.leave())
    }

    @Test
    fun aProvenInterruptionKeepsItsVoiceWhileEchoContinuesAfterStop() {
        val capture = SpeakerCapture()
        val output = Any()
        assertTrue(capture.enter(output, 1))
        repeat(4) { capture.record(mayInterrupt = true) { clearEcho } }
        repeat(6) { assertEquals(Window.FOREGROUND, capture.record(mayInterrupt = true) { foreground }) }
        assertEquals(Window.INTERRUPTION, capture.record(mayInterrupt = true) { foreground })
        capture.confirm()
        assertFalse("The playback and its tail share an output", capture.enter(output, 1))
        assertEquals(Window.SILENCE, capture.record(mayInterrupt = true) { clearEcho })
        assertEquals(Window.SILENCE, capture.record(mayInterrupt = false) { null })
        assertEquals(Window.FOREGROUND, capture.record(mayInterrupt = false) { foreground })
        assertFalse("Keep the confirmed speech buffered in the VAD", capture.leave())
    }

    @Test
    fun loudSoundDoesNotClaimAnUncalibratedQuietReference() {
        val guard = SpeakerEchoGuard()
        val quietReference = foreground.copy(peakReference = 0.003)
        assertFalse(guard.hearsOwner(quietReference))
        repeat(4) { assertFalse(guard.hearsOwner(quietReference.copy(echo = 0.001, residual = 0.002))) }
        assertTrue(guard.hearsOwner(quietReference))
    }

    private val clearEcho = EchoMatch(0.95, 0.006, 0.06, 0.1, 0.12, 140)
    private val foreground = EchoMatch(0.22, 0.09, 0.019, 0.09, 0.12, 146)

    private fun signal(length: Int, seed: Int): FloatArray {
        val random = Random(seed)
        return FloatArray(length) { (random.nextFloat() - .5f) * .45f }
    }

    private fun pcm(source: FloatArray): ByteArray {
        // Eight mic frames or twelve PCM frames represent one reference sample.
        val pcm = ByteArray(source.size * 12 * 2)
        for (index in 0 until pcm.size / 2) {
            val sample = (source[index / 12] * 32768).toInt()
            pcm[index * 2] = sample.toByte()
            pcm[index * 2 + 1] = (sample shr 8).toByte()
        }
        return pcm
    }

    private fun reference(source: FloatArray): PcmEchoReference {
        val pcm = pcm(source)
        return PcmEchoReference(24_000).also {
            for (offset in pcm.indices step 406) it.append(pcm, offset, minOf(406, pcm.size - offset))
        }
    }

    private fun microphone(start: Int, gain: Float, person: FloatArray? = null): FloatArray = FloatArray(512) { index ->
        val group = index / 8
        .02f + playback[start + group] * gain + (person?.get(group) ?: 0f)
    }
}
