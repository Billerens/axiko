package app.axiko.core

import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The browsers that listen to the phone's events: one stream for each window of the web interface.
 *
 * Each listener has a queue and a writer of its own, so whoever emits an event only puts it into the
 * queues and never waits for a network. A browser that does not read — a laptop gone to sleep with
 * the page open, a network that went away without a word — would otherwise hold up the very work that
 * emits: the agent's answer would stop mid-word on the phone as well. A listener that falls [limit]
 * events behind, or whose connection fails, is cut off: its connection is closed, the browser notices,
 * connects again and reads the state anew.
 */
class EventStreams(private val limit: Int = 4000, private val run: (Runnable) -> Unit) {

    inner class Listener internal constructor(private val out: OutputStream, private val close: () -> Unit) {
        private val queue = ArrayBlockingQueue<ByteArray>(limit)
        private val open = AtomicBoolean(true)

        val isOpen: Boolean get() = open.get()

        internal fun offer(bytes: ByteArray) {
            if (open.get() && !queue.offer(bytes)) cut()
        }

        internal fun write() {
            try {
                while (open.get()) {
                    val bytes = queue.poll(1, TimeUnit.SECONDS) ?: continue
                    out.write(bytes)
                    // What else is waiting goes out with it, in one flush.
                    while (true) out.write(queue.poll() ?: break)
                    out.flush()
                }
            } catch (_: Exception) {
                // The connection failed: cut below.
            } finally {
                cut()
            }
        }

        /** Stops writing and closes the connection; a write stuck on a dead network fails with it. */
        fun cut() {
            if (!open.compareAndSet(true, false)) return
            listeners.remove(this)
            queue.clear()
            runCatching { close() }
        }
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    /** How many browsers listen now. */
    val size: Int get() = listeners.size

    /**
     * Starts writing events to [out], [first] before any of them. [close] closes the connection
     * behind [out]: it is called once, when the listener is cut off for any reason.
     */
    fun add(out: OutputStream, first: ByteArray? = null, close: () -> Unit): Listener {
        val listener = Listener(out, close)
        first?.let(listener::offer)
        listeners.add(listener)
        run(Runnable { listener.write() })
        return listener
    }

    /** Puts [bytes] into every listener's queue; returns at once whatever the networks do. */
    fun send(bytes: ByteArray) {
        for (listener in listeners) listener.offer(bytes)
    }

    fun closeAll() {
        for (listener in listeners) listener.cut()
    }
}
