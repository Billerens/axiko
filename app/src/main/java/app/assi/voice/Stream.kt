package app.assi.voice

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import app.assi.core.Engine
import app.assi.core.Pages
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Continuous recognition: the microphone stays open and every phrase, cut off by the pause
 * after it, is recognized and handed on at once as the "speech.phrase" event, to pages and to
 * scripts — for a game that listens through a whole round, for dictation, for anything a single
 * listen (which ends at the first pause) is too short for. The watch for the name rests meanwhile
 * and comes back by itself (see [Wake]).
 */
object Stream {
    private const val RATE = 16000
    private const val WINDOW = 512

    /** Shorter than this is a knock or a cough, not a phrase. */
    private const val SHORTEST = RATE / 4

    @Volatile
    var active = false
        private set

    /** Grows on every start and stop: the loop of an older stream ends. */
    @Volatile
    private var generation = 0

    fun start(maxMinutes: Double): Boolean {
        if (active) return true
        if (Live.active) error("Идёт живой разговор: непрерывное распознавание сейчас недоступно")
        if (Stt.listening) error("Уже слушаю")
        if (!Stt.ready) error("Модель распознавания речи не скачана. Откройте «Настройки», вкладка «Голос».")
        if (!Permissions.ensure(Manifest.permission.RECORD_AUDIO)) error("Нет доступа к микрофону")
        active = true
        val ticket = ++generation
        val until = System.currentTimeMillis() + (maxMinutes.coerceIn(0.1, 180.0) * 60_000).toLong()
        Thread({
            try {
                listen(ticket, until)
            } catch (failure: Exception) {
                if (ticket == generation) tell(false, failure.message ?: failure.toString())
            } finally {
                if (ticket == generation) {
                    active = false
                    tell(false)
                }
            }
        }, "assi-stream").apply { isDaemon = true }.start()
        tell(true)
        return true
    }

    fun stop() {
        if (!active) return
        generation++
        active = false
        tell(false)
    }

    private fun tell(on: Boolean, error: String? = null) {
        val event = JSONObject().put("active", on)
        if (error != null) event.put("error", error)
        Pages.broadcast("speech.stream", event.toString())
        Engine.fire("speech.stream", event)
    }

    @SuppressLint("MissingPermission")
    private fun listen(ticket: Int, until: Long) {
        val detector = Stt.vad(0.5f)
        val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, RATE * 4),
        )
        // Phrases are recognized one after another beside the microphone, which must not wait for them.
        val recognizing = Executors.newSingleThreadExecutor { task -> Thread(task, "assi-stream-stt").apply { isDaemon = true } }
        val began = System.currentTimeMillis()
        fun take() {
            while (!detector.empty()) {
                val segment = detector.front()
                detector.pop()
                if (segment.samples.size >= SHORTEST) recognizing.execute { heard(segment.start, segment.samples, began) }
            }
        }
        try {
            record.startRecording()
            val buffer = ShortArray(WINDOW)
            while (ticket == generation && System.currentTimeMillis() < until) {
                if (record.read(buffer, 0, WINDOW) < WINDOW) continue
                detector.acceptWaveform(FloatArray(WINDOW) { buffer[it] / 32768f })
                take()
            }
            // A phrase cut short by the stop still counts: it was said before it.
            detector.flush()
            take()
        } finally {
            runCatching { record.stop() }
            record.release()
            detector.release()
            recognizing.shutdown()
        }
    }

    /** One phrase: its text with where it lies from the start of the stream, and when it began. */
    private fun heard(start: Long, samples: FloatArray, began: Long) {
        val text = runCatching { Stt.decode(samples) }.getOrDefault("")
        if (text.isBlank()) return
        val phrase = Stt.timed(start, samples.size, text)
        phrase.put("time", began + phrase.getLong("startMs"))
        Pages.broadcast("speech.phrase", phrase.toString())
        Engine.fire("speech.phrase", phrase)
    }
}
