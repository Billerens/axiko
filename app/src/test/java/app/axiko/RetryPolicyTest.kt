package app.axiko

import app.axiko.agent.RetryPolicy
import app.axiko.agent.RetryWaiter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {
    @Test
    fun missingSettingsKeepTwoAutomaticRetries() {
        val policy = RetryPolicy.read(null, null, null, null)
        assertEquals(2, policy.limit)
        assertEquals(1_000L, policy.delayMs(0, 0))
        assertEquals(2_000L, policy.delayMs(1, 0))
        assertTrue(policy.allows(1))
        assertFalse(policy.allows(2))
    }

    @Test
    fun zeroDisablesAutomaticRetryAndOldNumericLimitStillStopsAtTen() {
        assertFalse(RetryPolicy.read("0", null, null, null).allows(0))
        val policy = RetryPolicy.read("99", null, null, null)
        assertTrue(policy.allows(9))
        assertFalse(policy.allows(10))
    }

    @Test
    fun unlimitedCanAdvancePastTenWithoutWrappingTheCounter() {
        val policy = RetryPolicy.read("unlimited", "1", "2", "60")
        assertEquals(-1, policy.limit)
        var attempt = 0
        repeat(100) {
            assertTrue(policy.allows(attempt))
            attempt = RetryPolicy.nextAttempt(attempt)
        }
        assertEquals(100, attempt)
        assertEquals(60_000L, policy.delayMs(attempt, 0))
        assertEquals(Int.MAX_VALUE, RetryPolicy.nextAttempt(Int.MAX_VALUE))
        assertTrue(policy.allows(Int.MAX_VALUE))
        assertEquals(60_000L, policy.delayMs(Int.MAX_VALUE, 0))
    }

    @Test
    fun localDelayProgressionCanBeFractionalOrFixed() {
        val fractional = RetryPolicy.read("unlimited", "2", "1.5", "5")
        assertEquals(listOf(2_000L, 3_000L, 4_500L, 5_000L), (0..3).map { fractional.delayMs(it, 0) })
        val fixed = RetryPolicy.read("unlimited", "3", "1", "60")
        assertEquals(3_000L, fixed.delayMs(Int.MAX_VALUE, 0))
    }

    @Test
    fun localCapIsAtLeastInitialAndProviderWaitCanExceedIt() {
        val policy = RetryPolicy.read("unlimited", "30", "5", "15")
        assertEquals(30_000L, policy.delayMs(3, 0))
        assertEquals(600_000L, policy.delayMs(3, 600_000))
        assertEquals(Long.MAX_VALUE, policy.delayMs(Int.MAX_VALUE, Long.MAX_VALUE))
    }

    @Test
    fun invalidAndOutOfRangeSettingsAreNormalized() {
        val normalized = RetryPolicy.read("invalid", "0", "NaN", "99999")
        assertEquals(2, normalized.limit)
        assertEquals(1_000L, normalized.delayMs(0, -1))
        assertEquals(2_000L, normalized.delayMs(1, 0))
        assertEquals(3_600_000L, normalized.delayMs(Int.MAX_VALUE, 0))
    }

    @Test
    fun reconnectReportsRemainingTimeAndExpiredWaitCanContinue() {
        var now = 10_000L
        val waiter = RetryWaiter { now }
        assertEquals(1_500L, waiter.remaining(5_000L, 6_500L))
        now = 11_501L
        assertEquals(0L, waiter.remaining(5_000L, 6_500L))
        assertTrue(waiter.await(5_000L, 6_500L) { false })
        assertFalse(waiter.await(5_000L, 6_500L) { true })
    }

    @Test
    fun stopWakesAnUnlimitedRetryWaitingOnALongProviderDelay() {
        val executor = Executors.newSingleThreadExecutor()
        val waiter = RetryWaiter { 5_000L }
        val stopped = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        try {
            val result = executor.submit<Boolean> {
                waiter.await(Long.MAX_VALUE, 5_000L) {
                    entered.countDown()
                    stopped.get()
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            stopped.set(true)
            waiter.wake()
            assertFalse(result.get(2, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }
}
