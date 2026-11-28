package app.axiko

import app.axiko.voice.SpeechPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechPreviewTest {
    private class Fixture {
        val pending = ArrayDeque<() -> Unit>()
        val snapshots = mutableListOf<FloatArray>()
        val published = mutableListOf<String>()
        var transcript: String? = "первые слова"
        val preview = SpeechPreview(
            decode = { samples -> snapshots.add(samples); transcript },
            submit = { task -> pending.addLast(task); true },
            publish = published::add,
        )

        fun work() = pending.removeFirst().invoke()
    }

    @Test
    fun snapshotsWaitForSpeechAndRetainItsOnset() {
        val fixture = Fixture()
        repeat(10) { frame -> fixture.preview.accept(FloatArray(512) { frame.toFloat() }, false) }
        assertTrue(fixture.pending.isEmpty())
        repeat(18) { fixture.preview.accept(FloatArray(512) { 10f }, true) }
        assertEquals(1, fixture.pending.size)
        assertTrue(fixture.published.isEmpty())
        fixture.work()
        val input = fixture.snapshots.single()
        assertEquals(12_800, input.size)
        assertEquals(3f, input.first(), 0f)
        assertEquals(10f, input.last(), 0f)
        assertEquals(listOf("первые слова"), fixture.published)
    }

    @Test
    fun onlyOneSnapshotRunsAndLongPhrasesKeepEarlierPreviewText() {
        val fixture = Fixture()
        fixture.preview.accept(FloatArray(64_000))
        fixture.preview.accept(FloatArray(14_400))
        assertEquals(1, fixture.pending.size)
        fixture.work()
        fixture.transcript = "дальше по теме"
        fixture.preview.accept(FloatArray(512))
        fixture.work()
        assertEquals(listOf(64_000, 14_912), fixture.snapshots.map { it.size })
        assertEquals(listOf("первые слова", "первые слова дальше по теме"), fixture.published)
    }

    @Test
    fun resetAndCloseSuppressLateResultsAndClearVisibleTextOnce() {
        val fixture = Fixture()
        fixture.preview.accept(FloatArray(12_800))
        fixture.preview.reset()
        fixture.work()
        assertTrue(fixture.published.isEmpty())
        fixture.preview.accept(FloatArray(12_800))
        fixture.work()
        fixture.preview.accept(FloatArray(14_400))
        fixture.preview.close()
        fixture.work()
        fixture.preview.close()
        fixture.preview.accept(FloatArray(40_000))
        assertTrue(fixture.pending.isEmpty())
        assertEquals(listOf("первые слова", ""), fixture.published)
    }

    @Test
    fun unavailableDecoderLetsLaterSpeechTryAgain() {
        val fixture = Fixture()
        fixture.transcript = null
        fixture.preview.accept(FloatArray(12_800))
        fixture.work()
        assertTrue(fixture.published.isEmpty())
        fixture.transcript = "первый черновик"
        fixture.preview.accept(FloatArray(14_400))
        fixture.work()
        assertEquals(listOf(12_800, 27_200), fixture.snapshots.map { it.size })
        assertEquals(listOf("первый черновик"), fixture.published)
    }
}
