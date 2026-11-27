package app.axiko

import app.axiko.voice.LocalTts
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalTtsTest {
    @Test
    fun removesExpressionCuesBeforeLocalSpeech() {
        assertEquals("Увы.", LocalTts.spokenText("<BREATH><breath> Увы. <sad>"))
        assertEquals("Да!", LocalTts.spokenText("Да <sigh>!"))
        assertEquals("Да нет", LocalTts.spokenText("Да<laugh>нет"))
        assertEquals("Уже дома?", LocalTts.spokenText("<surprise>Уже дома?</surprise>"))
        assertEquals("", LocalTts.spokenText("<sad><Cough> <throatclear>"))
    }

    @Test
    fun preservesOtherTextAndLineBreaks() {
        val text = "Число < 3, <HTML>, [1],  happy."
        assertEquals(text, LocalTts.spokenText(text))
        assertEquals("Сначала.\nПотом.", LocalTts.spokenText("Сначала.\n<breath> Потом."))
    }
}
