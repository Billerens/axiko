package app.axiko.voice

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Possible text for the current phrase from the existing local models. Only the VAD's full
 * segment is recognized for sending or saving. Preview work has fixed input and never queues
 * behind an earlier preview.
 */
internal class SpeechPreview internal constructor(
    private val decode: (FloatArray) -> String?,
    private val submit: (() -> Unit) -> Boolean,
    private val publish: (String) -> Unit,
) : AutoCloseable {
    constructor(publish: (String) -> Unit) : this(Stt::tryDecodePreview, Worker::submit, publish)

    private companion object {
        const val RATE = 16000
        const val FIRST = RATE * 8 / 10
        const val STEP = RATE * 9 / 10
        const val CHUNK = RATE * 4
        const val LIMIT = RATE * 26
        const val LEAD = RATE / 4
    }

    private val pcm = FloatArray(LIMIT)
    private val beforeSpeech = ArrayDeque<FloatArray>()
    private var leadSamples = 0
    private var received = 0
    private var decoded = 0
    private var next = FIRST
    private var prefix = ""
    private var visible = ""
    private var revision = 0L
    private var inFlight = false
    private var closed = false

    /** Call with windows that belong to the owner, including any pre-roll held by the caller. */
    @Synchronized
    fun accept(samples: FloatArray) {
        if (closed) return
        append(samples)
        if (inFlight || received < next || received - decoded < FIRST) return
        val end = minOf(received, decoded + CHUNK)
        val snapshot = pcm.copyOfRange(decoded, end)
        val current = revision
        inFlight = submit {
            val text = runCatching { decode(snapshot) }.getOrNull()
            complete(current, end, text)
        }
        // Try a new snapshot later if the worker is still finishing a different phrase.
        next = received + if (inFlight) STEP else RATE / 4
    }

    /** Keep the onset of the phrase while waiting for the VAD to confirm speech. */
    @Synchronized
    fun accept(samples: FloatArray, speech: Boolean) {
        if (closed) return
        if (speech) {
            beforeSpeech.forEach(::append)
            beforeSpeech.clear()
            leadSamples = 0
            accept(samples)
        } else {
            beforeSpeech.addLast(samples)
            leadSamples += samples.size
            while (leadSamples > LEAD) leadSamples -= beforeSpeech.removeFirst().size
        }
    }

    private fun append(samples: FloatArray) {
        val length = minOf(samples.size, LIMIT - received)
        samples.copyInto(pcm, received, endIndex = length)
        received += length
    }

    @Synchronized
    private fun complete(current: Long, end: Int, text: String?) {
        if (closed || current != revision) return
        inFlight = false
        if (text == null) return
        val combined = listOf(prefix, text.trim()).filter(String::isNotEmpty).joinToString(" ")
        if (end - decoded == CHUNK) {
            decoded = end
            prefix = combined
        }
        if (combined.isNotEmpty()) show(combined)
    }

    private fun show(text: String) {
        if (visible == text) return
        visible = text
        // Publication is ordered with reset. Callbacks enqueue events and never decode on the recorder.
        runCatching { publish(text) }
    }

    @Synchronized
    fun reset() {
        revision++
        inFlight = false
        beforeSpeech.clear()
        leadSamples = 0
        received = 0
        decoded = 0
        next = FIRST
        prefix = ""
        show("")
    }

    @Synchronized
    override fun close() {
        closed = true
        reset()
    }

    private object Worker {
        private val busy = AtomicBoolean()
        private val worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "axiko-stt-preview").apply { isDaemon = true }
        }

        fun submit(task: () -> Unit): Boolean {
            if (!busy.compareAndSet(false, true)) return false
            worker.execute {
                try {
                    task()
                } finally {
                    busy.set(false)
                }
            }
            return true
        }
    }
}
