package app.axiko

import app.axiko.voice.Wake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeAliasesTest {
    @Test
    fun `the default name stays delegated to panel settings`() {
        val text = "Hey, Axiko, назови время"
        val address = Wake.addressedTo(text, listOf(Wake.Target("Axiko", null), Wake.Target("Нора", "nora")))
        assertEquals(Wake.Address("Hey, Axiko".length, null), address)
    }

    @Test
    fun `a longer exact phrase wins over its short prefix`() {
        val text = "Эй, Нора аналитик, посмотри экран"
        val targets = listOf(Wake.Target("Нора", "nora"), Wake.Target("Нора аналитик", "analyst"))
        val address = Wake.addressedTo(text, targets)
        assertEquals(Wake.Address("Эй, Нора аналитик".length, "analyst"), address)
    }

    @Test
    fun `an explicit greeting is part of a multiword wake phrase`() {
        val target = Wake.Target("Эй Нора", "nora")
        assertNull(Wake.addressedTo("Нора, посмотри экран", listOf(target)))
        assertNull(Wake.addressedTo("Можно позвать, эй Нора?", listOf(target)))
        assertEquals(Wake.Address("Эй, Нора".length, "nora"), Wake.addressedTo("Эй, Нора!", listOf(target)))
    }

    @Test
    fun `recognizer spellings must select an unambiguous agent`() {
        val targets = listOf(Wake.Target("Лина", "lina"), Wake.Target("Лира", "lira"))
        assertNull(Wake.addressedTo("Лида, проснись", targets))
        assertEquals(Wake.Address("Лира".length, "lira"), Wake.addressedTo("Лира, проснись", targets))
        assertEquals(Wake.Address("Лида".length, "lina"), Wake.addressedTo("Лида, проснись", targets.take(1)))
    }
}
