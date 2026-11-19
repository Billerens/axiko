package app.axiko

import app.axiko.core.EventStreams
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** The phone's event streams to browsers: whatever a browser does, the work that emits never waits for it. */
class EventStreamsTest {
    private val pool = Executors.newCachedThreadPool()

    @After fun stop() { pool.shutdownNow() }

    private fun waitUntil(what: String, test: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!test()) {
            check(System.currentTimeMillis() < deadline) { "Не дождались: $what" }
            Thread.sleep(10)
        }
    }

    /** An output nobody reads: a laptop gone to sleep with the page open. Its write ends only when the connection is closed. */
    private class Asleep : OutputStream() {
        val closed = CountDownLatch(1)
        override fun write(b: Int) { closed.await(); throw IOException("closed") }
        override fun write(b: ByteArray, off: Int, len: Int) { closed.await(); throw IOException("closed") }
    }

    @Test fun aBrowserThatDoesNotReadNeverHoldsUpTheWorkAndIsCutOffWhenItFallsBehind() {
        val streams = EventStreams(limit = 50) { pool.execute(it) }
        val asleep = Asleep()
        val asleepClosed = AtomicInteger()
        val awake = ByteArrayOutputStream()
        streams.add(asleep) { asleepClosed.incrementAndGet(); asleep.closed.countDown() }
        streams.add(awake) { }

        // Events come as the work goes, a few at a time: the reading browser keeps up, the sleeping one does not.
        var took = 0L
        for (batch in 0 until 50) {
            val started = System.nanoTime()
            for (i in 0 until 20) streams.send("data: ${batch * 20 + i}\n\n".toByteArray())
            took += System.nanoTime() - started
            Thread.sleep(3)
        }
        assertTrue("Отправка не ждёт сеть: ${took / 1_000_000} мс", took / 1_000_000 < 1000)

        // Fifty events behind, the sleeping browser is cut off: its connection is closed, the stuck write ends.
        waitUntil("спящий браузер отключён") { asleepClosed.get() == 1 }
        waitUntil("бодрый браузер получил всё") { awake.toString(Charsets.UTF_8).contains("data: 999\n\n") }
        assertEquals(1, streams.size)
        val got = awake.toString(Charsets.UTF_8).split("\n\n").filter { it.isNotEmpty() }
        assertEquals((0 until 1000).map { "data: $it" }, got)
        Thread.sleep(50)
        assertEquals("Соединение закрывается один раз", 1, asleepClosed.get())
    }

    @Test fun aConnectionThatFailsIsCutAndClosedAndTheOthersGoOn() {
        val streams = EventStreams { pool.execute(it) }
        val closed = AtomicInteger()
        val broken = object : OutputStream() {
            override fun write(b: Int) = throw IOException("Connection reset")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("Connection reset")
        }
        val healthy = ByteArrayOutputStream()
        streams.add(broken) { closed.incrementAndGet() }
        streams.add(healthy) { }
        streams.send("data: 1\n\n".toByteArray())
        waitUntil("сломанное соединение закрыто") { closed.get() == 1 }
        streams.send("data: 2\n\n".toByteArray())
        waitUntil("второе событие дошло") { healthy.toString(Charsets.UTF_8) == "data: 1\n\ndata: 2\n\n" }
        assertEquals(1, streams.size)
    }

    @Test fun theGreetingComesFirstAndAListenerIsClosedOnceHoweverItEnds() {
        val streams = EventStreams { pool.execute(it) }
        val out = ByteArrayOutputStream()
        val closed = AtomicInteger()
        val listener = streams.add(out, "event: hello\ndata: {}\n\n".toByteArray()) { closed.incrementAndGet() }
        streams.send("data: 1\n\n".toByteArray())
        waitUntil("событие дошло") { out.toString(Charsets.UTF_8).contains("data: 1") }
        assertEquals("event: hello\ndata: {}\n\ndata: 1\n\n", out.toString(Charsets.UTF_8))

        streams.closeAll()
        listener.cut()
        assertEquals(1, closed.get())
        assertFalse(listener.isOpen)
        assertEquals(0, streams.size)
        streams.send("data: 2\n\n".toByteArray())
        Thread.sleep(50)
        assertFalse("После отключения ничего не пишется", out.toString(Charsets.UTF_8).contains("data: 2"))
    }
}
