package app.assi

import app.assi.core.Jobs
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class JobTimingTest {
    @Test fun recurringMaintenanceCanBeBatchedWhileRemindersStayExact() {
        assertFalse(Jobs.needsExact(JSONObject().put("every", 15)))
        assertTrue(Jobs.needsExact(JSONObject().put("at", "08:30")))
        assertTrue(Jobs.needsExact(JSONObject().put("once", 1_800_000_000_000L)))
        assertTrue(Jobs.needsExact(JSONObject().put("every", 15).put("exact", true)))
    }

    @Test fun batchingWindowIsBoundedForShortLongAndMalformedIntervals() {
        assertEquals(5 * 60_000L, Jobs.batchWindow(JSONObject().put("every", 15)))
        assertEquals(60_000L, Jobs.batchWindow(JSONObject().put("every", 1)))
        assertEquals(15 * 60_000L, Jobs.batchWindow(JSONObject().put("every", Int.MAX_VALUE)))
    }
}
