package app.assi

import android.app.NotificationManager
import android.service.notification.NotificationListenerService
import app.assi.sense.Dnd
import app.assi.sense.Notices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What modules learn from a chat notification and from the phone's quiet modes. */
class NoticesTest {
    @Test
    fun ownMessagesHaveNoSender() {
        // Telegram-like: the owner's messages carry the "self" person, told apart by key.
        assertNull(Notices.sender("Илья", "me", "Илья", "me"))
        assertEquals("Аня", Notices.sender("Аня", "anya", "Илья", "me"))
        // The same name as the owner but another person: keys win over names.
        assertEquals("Илья", Notices.sender("Илья", "other", "Илья", "me"))
        // Apps without keys: the name decides.
        assertNull(Notices.sender("Вы", null, "Вы", null))
        assertEquals("Аня", Notices.sender("Аня", null, "Вы", null))
        // No sender at all is the owner's own message.
        assertNull(Notices.sender(null, null, "Вы", null))
        assertNull(Notices.sender(" ", "x", null, null))
    }

    @Test
    fun groupAsTheAppSaysOrByTitle() {
        assertTrue(Notices.group(true, null))
        assertFalse(Notices.group(false, "Семья"))
        assertTrue(Notices.group(null, "Семья"))
        assertFalse(Notices.group(null, ""))
        assertFalse(Notices.group(null, null))
    }

    @Test
    fun removalReasons() {
        assertEquals("click", Notices.reason(NotificationListenerService.REASON_CLICK))
        assertEquals("cancel", Notices.reason(NotificationListenerService.REASON_CANCEL))
        assertEquals("cancel", Notices.reason(NotificationListenerService.REASON_CANCEL_ALL))
        assertEquals("cancel", Notices.reason(NotificationListenerService.REASON_GROUP_SUMMARY_CANCELED))
        assertEquals("app", Notices.reason(NotificationListenerService.REASON_APP_CANCEL))
        assertEquals("app", Notices.reason(NotificationListenerService.REASON_APP_CANCEL_ALL))
        assertEquals("other", Notices.reason(NotificationListenerService.REASON_TIMEOUT))
        assertEquals("other", Notices.reason(NotificationListenerService.REASON_LISTENER_CANCEL))
    }

    @Test
    fun quietModesRoundTrip() {
        for (name in listOf("all", "priority", "alarms", "none")) assertEquals(name, Dnd.name(Dnd.code(name)!!))
        assertNull(Dnd.code("silent"))
        assertEquals("all", Dnd.name(NotificationManager.INTERRUPTION_FILTER_UNKNOWN))
    }
}
