package app.axiko.voice

import app.axiko.App
import app.axiko.core.Archive
import app.axiko.core.Pages
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import org.json.JSONObject
import java.io.File

/**
 * A voice that lives on the phone: Supertonic 3 (Supertone, 2026) through sherpa-onnx. It speaks
 * 31 languages, Russian among them, in ten voices, without a network and faster than real time.
 * It is the spare for the cloud voice — and the only voice when no cloud one is set up.
 */
object LocalTts {
    private const val PACK =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2"
    const val MEGABYTES = 123
    const val VOICES = 10
    private const val CHUNK_PAUSE_SECONDS = 0.3f
    private val FILES = listOf(
        "duration_predictor.int8.onnx", "text_encoder.int8.onnx", "vector_estimator.int8.onnx",
        "vocoder.int8.onnx", "tts.json", "unicode_indexer.bin", "voice.bin",
    )

    private val dir: File get() = File(App.instance.filesDir, "models/tts/supertonic-3")

    val ready: Boolean get() = FILES.all { File(dir, it).isFile }

    @Volatile
    var downloading = false
        private set

    private val voice: Int get() = (App.prefs.getString("tts.local.voice", "0")?.toIntOrNull() ?: 0).coerceIn(0, VOICES - 1)
    private val speed: Float get() = (App.prefs.getString("tts.local.speed", "1")?.toFloatOrNull() ?: 1f).coerceIn(0.6f, 1.8f)

    fun status(): JSONObject = JSONObject()
        .put("ready", ready).put("downloading", downloading).put("megabytes", MEGABYTES)
        .put("voices", VOICES).put("voice", voice).put("speed", speed.toDouble())

    private fun emit(data: JSONObject) = Pages.broadcast("tts.download", data.toString())

    /** Downloads the voice; progress goes to pages as the "tts.download" event. */
    fun download() {
        if (downloading || ready) return
        downloading = true
        try {
            dir.parentFile?.mkdirs()
            Archive.fetch(PACK, dir) { done, total -> emit(JSONObject().put("done", done).put("total", total)) }
            emit(JSONObject().put("ready", true))
        } catch (error: Exception) {
            emit(JSONObject().put("error", error.message ?: error.toString()))
            throw error
        } finally {
            downloading = false
        }
    }

    fun remove() {
        release()
        dir.deleteRecursively()
    }

    // ---- the engine ---------------------------------------------------------------------------

    private var engine: OfflineTts? = null
    private var lastUse = 0L

    @Synchronized
    private fun engine(): OfflineTts {
        engine?.let { return it }
        if (!ready) error("Локальный голос не скачан. Откройте «Настройки», вкладка «Голос».")
        fun path(name: String) = File(dir, name).path
        val created = OfflineTts(
            null,
            OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    supertonic = OfflineTtsSupertonicModelConfig(
                        durationPredictor = path("duration_predictor.int8.onnx"),
                        textEncoder = path("text_encoder.int8.onnx"),
                        vectorEstimator = path("vector_estimator.int8.onnx"),
                        vocoder = path("vocoder.int8.onnx"),
                        ttsJson = path("tts.json"),
                        unicodeIndexer = path("unicode_indexer.bin"),
                        voiceStyle = path("voice.bin"),
                    ),
                    numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
                ),
            ),
        )
        engine = created
        // The voice holds memory: it is let go after a few idle minutes.
        Thread({
            while (true) {
                Thread.sleep(60_000)
                synchronized(this) {
                    if (engine == null) return@Thread
                    if (System.currentTimeMillis() - lastUse > 5 * 60_000) {
                        release()
                        return@Thread
                    }
                }
            }
        }, "axiko-tts-idle").apply { isDaemon = true }.start()
        return created
    }

    @Synchronized
    private fun release() {
        runCatching { engine?.release() }
        engine = null
    }

    private val expressionTags = Regex(
        """[ \t]*(?:</?(?:angry|breath|cough|laugh|sad|scream|sigh|surprise|throatclear|yawn)>[ \t]*)+""",
        RegexOption.IGNORE_CASE,
    )

    /** Supertonic can pronounce expression cues aloud, so omit them from local speech. */
    internal fun spokenText(text: String): String = expressionTags.replace(text) { cue ->
        val before = text.getOrNull(cue.range.first - 1)
        val after = text.getOrNull(cue.range.last + 1)
        when {
            before == null || after == null -> ""
            before.isWhitespace() || after.isWhitespace() -> ""
            before in "«“([{" || after in ".,!?…;:»”)]}" -> ""
            else -> " "
        }
    }

    /** The language the text is mostly written in: the voice has to be told. */
    private fun language(text: String): String {
        val cyrillic = text.count { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
        val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        return if (latin > cyrillic) "en" else "ru"
    }

    @Synchronized
    fun rate(): Int = engine().sampleRate()

    /**
     * Says the text: samples (mono, −1…1, at [rate]) go to [sink] as they are made; the sink
     * answers false to stop. [sid] picks one of the voices; by default the owner's choice.
     */
    @Synchronized
    fun speak(text: String, sid: Int = voice, sink: (FloatArray) -> Boolean) {
        val spoken = spokenText(text)
        if (spoken.isBlank()) return
        val tts = engine()
        lastUse = System.currentTimeMillis()
        val config = GenerationConfig(
            sid = sid,
            speed = speed,
            numSteps = 5,
            extra = mapOf("lang" to language(spoken), "silence_duration" to CHUNK_PAUSE_SECONDS.toString()),
        )
        val pauseSamples = (tts.sampleRate() * CHUNK_PAUSE_SECONDS).toInt()
        var fed = false
        var stopped = false
        // A named class, not a lambda: the native side looks its method up by its exact signature.
        val pass = object : Function1<FloatArray, Int> {
            override fun invoke(samples: FloatArray): Int {
                if (stopped) return 0
                if (samples.isEmpty()) return 1
                // Sherpa adds this silence to its final result, but sends bare chunks here.
                if (fed && !sink(FloatArray(pauseSamples))) {
                    stopped = true
                    return 0
                }
                fed = true
                stopped = !sink(samples)
                return if (stopped) 0 else 1
            }
        }
        val audio = tts.generateWithConfigAndCallback(spoken, config, pass)
        // Some models hand everything over only at the end.
        if (!fed && audio.samples.isNotEmpty()) sink(audio.samples)
        lastUse = System.currentTimeMillis()
    }

    /** The whole phrase as samples at the voice's own [rate]. */
    fun samples(text: String, sid: Int = voice): FloatArray {
        val parts = ArrayList<FloatArray>()
        speak(text, sid) { parts.add(it); true }
        val whole = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            part.copyInto(whole, at)
            at += part.size
        }
        return whole
    }

    /** The whole phrase as samples at 16 kHz: what a recognizer takes. */
    fun render(text: String, sid: Int = voice): FloatArray {
        val source = samples(text, sid)
        if (source.isEmpty()) return source
        val from = rate()
        val length = (source.size.toLong() * 16000 / from).toInt()
        return FloatArray(length) { index ->
            val position = index.toDouble() * from / 16000
            val left = position.toInt().coerceAtMost(source.size - 1)
            val right = (left + 1).coerceAtMost(source.size - 1)
            val weight = (position - left).toFloat()
            source[left] * (1 - weight) + source[right] * weight
        }
    }
}
