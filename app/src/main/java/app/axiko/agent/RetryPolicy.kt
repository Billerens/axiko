package app.axiko.agent

import kotlin.math.ceil
import kotlin.math.pow

/** The retry count excludes the first model request. The local cap is independent of Retry-After. */
internal class RetryPolicy private constructor(
    val limit: Int,
    private val initialMs: Long,
    private val multiplier: Double,
    private val maxMs: Long,
) {
    fun allows(attempt: Int): Boolean = limit == UNLIMITED || attempt < limit

    fun delayMs(attempt: Int, retryAfterMs: Long): Long {
        val local = ceil((initialMs * multiplier.pow(attempt.coerceAtLeast(0))).coerceAtMost(maxMs.toDouble())).toLong()
        return maxOf(local, retryAfterMs)
    }

    companion object {
        const val UNLIMITED = -1

        fun read(limit: String?, initialSec: String?, factor: String?, maxSec: String?): RetryPolicy {
            val count = if (limit?.trim() == "unlimited") UNLIMITED else limit?.trim()?.toIntOrNull()?.coerceIn(0, 10) ?: 2
            val initial = (initialSec?.trim()?.toIntOrNull()?.coerceIn(1, 300) ?: 1) * 1_000L
            val multiplier = factor?.trim()?.toDoubleOrNull()?.takeIf(Double::isFinite)?.coerceIn(1.0, 5.0) ?: 2.0
            val max = (maxSec?.trim()?.toIntOrNull()?.coerceIn(1, 3_600) ?: 60) * 1_000L
            return RetryPolicy(count, initial, multiplier, maxOf(initial, max))
        }

        fun nextAttempt(attempt: Int): Int = attempt.coerceAtMost(Int.MAX_VALUE - 1) + 1
    }
}

/** Sleeps without polling the connection. Stop wakes the current wait, even for a huge Retry-After. */
internal class RetryWaiter(private val nowMs: () -> Long) {
    private val lock = Object()

    fun remaining(delayMs: Long, startedAtMs: Long): Long =
        (delayMs - (nowMs() - startedAtMs).coerceAtLeast(0)).coerceAtLeast(0)

    fun wake() = synchronized(lock) { lock.notifyAll() }

    fun await(delayMs: Long, startedAtMs: Long, cancelled: () -> Boolean): Boolean = synchronized(lock) {
        while (!cancelled()) {
            val left = remaining(delayMs, startedAtMs)
            if (left == 0L) return true
            // Bound each wait to avoid overflowing a platform timer and to recheck after sleep.
            lock.wait(left.coerceAtMost(60_000L))
        }
        false
    }
}
