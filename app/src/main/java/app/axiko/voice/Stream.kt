package app.axiko.voice

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import app.axiko.core.Engine
import app.axiko.core.Pages
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Record through pauses and recognize each VAD segment outside the recorder thread.
 * Identified callers can distinguish a stopped microphone from the final drained transcript.
 */
object Stream {
    private const val RATE = 16000
    private const val WINDOW = 512
    private const val SHORTEST = RATE / 4
    // Preserve captured segments and end the stream if recognition cannot keep pace for this long.
    private const val MAX_BACKLOG = RATE * 90

    private class Session(
        val number: Int,
        val id: String?,
        val preview: Boolean,
        val durationMs: Long,
    ) {
        @Volatile var stopping = false
        val error = AtomicReference<String?>()
    }

    @Volatile private var current: Session? = null
    @Volatile private var sessionNumber = 0
    @Volatile private var capturing = false
    val active: Boolean get() = current != null
    internal val holdsMicrophone: Boolean get() = active || capturing

    private val recorder = Executors.newSingleThreadExecutor { task ->
        Thread(task, "axiko-stream").apply { isDaemon = true }
    }
    private val recognizing = Executors.newSingleThreadExecutor { task ->
        Thread(task, "axiko-stream-stt").apply { isDaemon = true }
    }
    private val pendingSamples = AtomicInteger()

    @Synchronized
    fun start(maxMinutes: Double, requestId: String? = null, previewText: Boolean = false): Boolean {
        val requested = checkedSpeechId(requestId)
        current?.let {
            check(requested == null || requested == it.id) { "Микрофон уже используется" }
            return true
        }
        if (Live.holdsMicrophone) error("Идёт живой разговор: непрерывное распознавание сейчас недоступно")
        if (Stt.listening) error("Уже слушаю")
        check(pendingSamples.get() < MAX_BACKLOG) { "Распознавание предыдущих фраз ещё не завершилось." }
        if (!Stt.ready) error("Модель распознавания речи не скачана. Откройте «Настройки», вкладка «Голос».")
        if (!Permissions.ensure(Manifest.permission.RECORD_AUDIO)) error("Нет доступа к микрофону")
        val id = requested ?: if (previewText) UUID.randomUUID().toString() else null
        val duration = (maxMinutes.coerceIn(0.1, 180.0) * 60_000).toLong()
        val session = Session(++sessionNumber, id, previewText, duration)
        current = session
        tell(session, true)
        recorder.execute { capture(session) }
        return true
    }

    @Synchronized
    fun stop(requestId: String? = null) {
        val session = current ?: return
        if (requestId != null && requestId != session.id) return
        session.stopping = true
        current = null
        tell(session, false, drained = false)
    }

    private fun tell(session: Session, active: Boolean, drained: Boolean? = null, error: String? = null) {
        val event = JSONObject().put("active", active)
        session.id?.let { event.put("id", it) }
        if (session.id != null && drained != null) event.put("drained", drained)
        if (error != null) event.put("error", error)
        Pages.broadcast("speech.stream", event.toString())
        Engine.fire("speech.stream", event)
    }

    private fun capture(session: Session) {
        capturing = true
        try {
            if (!session.stopping) {
                Wake.yieldMicrophone()
                listen(session)
            }
        } catch (failure: Exception) {
            session.error.compareAndSet(null, failure.message ?: failure.toString())
        } finally {
            capturing = false
            synchronized(this) {
                if (current === session) {
                    current = null
                    if (session.id == null) tell(session, false, error = session.error.get())
                    else tell(session, false, drained = false)
                }
            }
            // This runs after every queued final and the final partial clear has been published.
            if (session.id != null) recognizing.execute {
                tell(session, false, drained = true, error = session.error.get())
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun listen(session: Session) {
        val detector = Stt.vad(0.5f)
        var record: AudioRecord? = null
        val partial = session.id?.takeIf { session.preview }?.let { SpeechPartial(it, "stream") }
        val segmentNumber = AtomicInteger()
        val preview = partial?.let { output ->
            SpeechPreview { text ->
                if (!session.stopping || text.isEmpty()) output.emit(text, segmentNumber.get())
            }
        }
        try {
            val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "Формат микрофона недоступен: $minimum" }
            val microphone = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, RATE * 4),
            )
            record = microphone
            microphone.startRecording()
            val began = System.currentTimeMillis()
            val until = SystemClock.elapsedRealtime() + session.durationMs
            fun take() {
                while (!detector.empty()) {
                    val segment = detector.front()
                    detector.pop()
                    preview?.reset()
                    val number = segmentNumber.getAndIncrement()
                    if (segment.samples.size < SHORTEST) continue
                    val samples = segment.samples
                    val backlog = pendingSamples.addAndGet(samples.size)
                    recognizing.execute {
                        try {
                            heard(session, number, segment.start, samples, began)
                        } finally {
                            pendingSamples.addAndGet(-samples.size)
                        }
                    }
                    if (backlog >= MAX_BACKLOG) session.error.compareAndSet(null, "Распознавание отстаёт от записи. Сохраняю уже записанные фразы.")
                }
            }

            val buffer = ShortArray(WINDOW)
            var filled = 0
            var emptyReads = 0
            while (!session.stopping && session.error.get() == null && SystemClock.elapsedRealtime() < until) {
                val count = microphone.read(buffer, filled, WINDOW - filled)
                check(count >= 0) { "Ошибка чтения микрофона: $count" }
                if (count == 0) {
                    check(++emptyReads < 10) { "Микрофон не отдаёт звук." }
                    Thread.sleep(20)
                    continue
                }
                emptyReads = 0
                filled += count
                if (filled < WINDOW) continue
                filled = 0
                val window = FloatArray(WINDOW) { buffer[it] / 32768f }
                detector.acceptWaveform(window)
                take()
                preview?.accept(window, detector.isSpeechDetected())
            }
            // The last incomplete segment counts even when the user manually stops recording.
            if (filled > 0) detector.acceptWaveform(FloatArray(WINDOW) { if (it < filled) buffer[it] / 32768f else 0f })
            preview?.close()
            detector.flush()
            take()
        } finally {
            preview?.close()
            partial?.close()
            record?.let {
                runCatching { it.stop() }
                it.release()
            }
            detector.release()
        }
    }

    private fun heard(session: Session, number: Int, start: Long, samples: FloatArray, began: Long) {
        // A legacy consumer sees a single global stream. An identified one can still finish after stop.
        if (session.id == null && session.number != sessionNumber) return
        val text = try {
            Stt.decode(samples)
        } catch (failure: Exception) {
            if (session.id != null) session.error.compareAndSet(null, failure.message ?: failure.toString())
            return
        }
        if (text.isBlank() || (session.id == null && session.number != sessionNumber)) return
        val phrase = Stt.timed(start, samples.size, text)
        phrase.put("time", began + phrase.getLong("startMs"))
        session.id?.let { phrase.put("id", it).put("segment", number) }
        Pages.broadcast("speech.phrase", phrase.toString())
        Engine.fire("speech.phrase", phrase)
    }
}
