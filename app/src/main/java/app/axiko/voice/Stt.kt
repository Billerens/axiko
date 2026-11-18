package app.axiko.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import app.axiko.App
import app.axiko.agent.Llm
import app.axiko.core.Pages
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Speech to text, on the phone (sherpa-onnx): offline and free. Two models to choose from,
 * each downloaded once: GigaAM v3 — the best open recognizer of Russian (about 225 MB), and
 * Parakeet TDT v3 — 25 European languages, Russian among them (about 465 MB).
 * The system recognizer is the spare for when the chosen model is not there.
 */
object Stt {
    private const val RATE = 16000
    private const val WINDOW = 512
    private const val REPO = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-ctc-giga-am-v3-russian-2025-12-16/resolve/main"
    private val sources = mapOf(
        "tokens.txt" to "$REPO/tokens.txt",
        "silero_vad.onnx" to "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
        "model.int8.onnx" to "$REPO/model.int8.onnx",
    )

    private val dir: File get() = File(App.instance.filesDir, "models/gigaam-v3-ctc")

    private const val PARAKEET =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2"
    private val PARAKEET_FILES = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
    private val parakeetDir: File get() = File(App.instance.filesDir, "models/parakeet-tdt-v3")

    private val vadFile: File get() = File(dir, "silero_vad.onnx")
    private val gigaamReady: Boolean get() = sources.keys.all { File(dir, it).isFile }
    private val parakeetReady: Boolean get() = PARAKEET_FILES.all { File(parakeetDir, it).isFile } && vadFile.isFile

    /** The files of the chosen model are on the phone. */
    val ready: Boolean get() = if (engine == "parakeet") parakeetReady else gigaamReady

    @Volatile
    var downloading = false
        private set

    @Volatile
    var listening = false
        private set

    @Volatile
    private var stopRequested = false

    private var recognizer: Pair<String, OfflineRecognizer>? = null

    private val engine: String get() = App.prefs.getString("stt.engine", "gigaam") ?: "gigaam"

    fun status(): JSONObject = JSONObject()
        .put("engine", engine).put("ready", ready).put("downloading", downloading).put("listening", listening)
        .put("gigaam", gigaamReady).put("parakeet", parakeetReady).put("live", Live.status())
        .put("system", SpeechRecognizer.isRecognitionAvailable(App.instance))
        .put("speaking", Tts.speaking).put("wake", Wake.wanted).put("stream", Stream.active)

    private fun emit(event: String, data: JSONObject) = Pages.broadcast(event, data.toString())

    // ---- model ------------------------------------------------------------------------------

    private fun fetch(url: String, target: File) {
        val partial = File(target.parentFile, target.name + ".part")
        Llm.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            val body = response.body
            if (!response.isSuccessful || body == null) error("Не удалось скачать ${target.name}: HTTP ${response.code}")
            partial.outputStream().use { body.byteStream().copyTo(it) }
        }
        if (!partial.renameTo(target)) error("Не удалось сохранить ${target.name}")
    }

    /** Downloads the model for 25 languages; progress goes to pages as the "stt.download" event. */
    private fun downloadParakeet() {
        if (downloading || parakeetReady) return
        downloading = true
        try {
            dir.mkdirs()
            if (!vadFile.isFile) fetch(sources.getValue("silero_vad.onnx"), vadFile)
            if (!PARAKEET_FILES.all { File(parakeetDir, it).isFile }) {
                parakeetDir.parentFile?.mkdirs()
                app.axiko.core.Archive.fetch(PARAKEET, parakeetDir) { done, total ->
                    emit("stt.download", JSONObject().put("file", "Parakeet v3").put("done", done).put("total", total))
                }
            }
            emit("stt.download", JSONObject().put("ready", true))
        } catch (error: Exception) {
            emit("stt.download", JSONObject().put("error", error.message ?: error.toString()))
            throw error
        } finally {
            downloading = false
        }
    }

    /** Downloads the files of the chosen model; progress goes to pages as the "stt.download" event. */
    fun download() {
        if (engine == "parakeet") return downloadParakeet()
        if (downloading || ready) return
        downloading = true
        try {
            dir.mkdirs()
            for ((name, url) in sources) {
                val target = File(dir, name)
                if (target.isFile) continue
                val partial = File(dir, "$name.part")
                Llm.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    val body = response.body
                    if (!response.isSuccessful || body == null) error("Не удалось скачать $name: HTTP ${response.code}")
                    val total = body.contentLength()
                    var done = 0L
                    var reported = 0L
                    partial.outputStream().use { out ->
                        val buffer = ByteArray(256 * 1024)
                        val input = body.byteStream()
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            done += read
                            val now = System.currentTimeMillis()
                            if (now - reported > 400) {
                                reported = now
                                emit("stt.download", JSONObject().put("file", name).put("done", done).put("total", total))
                            }
                        }
                    }
                    if (!partial.renameTo(target)) error("Не удалось сохранить $name")
                }
            }
            emit("stt.download", JSONObject().put("ready", true))
        } catch (error: Exception) {
            emit("stt.download", JSONObject().put("error", error.message ?: error.toString()))
            throw error
        } finally {
            downloading = false
        }
    }

    @Synchronized
    private fun recognizer(): OfflineRecognizer {
        val wanted = if (engine == "parakeet") "parakeet" else "gigaam"
        recognizer?.let { (made, engine) ->
            if (made == wanted) return engine
            runCatching { engine.release() }
            recognizer = null
        }
        if (!ready) error("Модель распознавания речи не скачана. Откройте «Настройки» и скачайте её.")
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val model = if (wanted == "parakeet") {
            OfflineModelConfig(
                transducer = com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig(
                    encoder = File(parakeetDir, "encoder.int8.onnx").path,
                    decoder = File(parakeetDir, "decoder.int8.onnx").path,
                    joiner = File(parakeetDir, "joiner.int8.onnx").path,
                ),
                tokens = File(parakeetDir, "tokens.txt").path,
                modelType = "nemo_transducer",
                numThreads = threads,
            )
        } else {
            OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = File(dir, "model.int8.onnx").path),
                tokens = File(dir, "tokens.txt").path,
                numThreads = threads,
            )
        }
        return OfflineRecognizer(null, OfflineRecognizerConfig(modelConfig = model)).also { recognizer = wanted to it }
    }

    internal fun vad(silenceSeconds: Float): Vad = Vad(
        null,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = vadFile.path,
                minSilenceDuration = silenceSeconds,
                minSpeechDuration = 0.2f,
                maxSpeechDuration = 24f,
                windowSize = WINDOW,
            ),
            sampleRate = RATE,
            numThreads = 1,
        ),
    )

    /** Text of one stretch of speech (up to about 25 seconds), 16 kHz mono. */
    internal fun decode(samples: FloatArray): String {
        if (samples.size < RATE / 5) return ""
        val engine = recognizer()
        val stream = engine.createStream()
        try {
            stream.acceptWaveform(samples, RATE)
            engine.decode(stream)
            return engine.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    /** Cuts a recording into phrases by its pauses: each phrase with the sample it starts at. */
    private fun cut(samples: FloatArray, each: (Long, FloatArray) -> Unit) {
        val detector = vad(0.4f)
        fun take() {
            while (!detector.empty()) {
                val segment = detector.front()
                detector.pop()
                each(segment.start, segment.samples)
            }
        }
        try {
            var at = 0
            while (at + WINDOW <= samples.size) {
                detector.acceptWaveform(samples.copyOfRange(at, at + WINDOW))
                at += WINDOW
                take()
            }
            detector.flush()
            take()
        } finally {
            detector.release()
        }
    }

    /** Text of a recording of any length: cut into phrases by pauses, then recognized. */
    fun transcribe(samples: FloatArray): String {
        if (samples.size <= RATE * 20) return decode(samples)
        val parts = ArrayList<String>()
        cut(samples) { _, piece -> parts.add(decode(piece)) }
        return parts.filter { it.isNotBlank() }.joinToString(" ")
    }

    /** The phrases of a recording with their time in it: `[{startMs, endMs, text}]`. */
    fun segments(samples: FloatArray): JSONArray {
        val out = JSONArray()
        cut(samples) { start, piece ->
            val text = decode(piece)
            if (text.isNotBlank()) out.put(timed(start, piece.size, text))
        }
        return out
    }

    /** A phrase found at sample [start], [length] samples long (16 kHz), as modules get it. */
    fun timed(start: Long, length: Int, text: String): JSONObject =
        JSONObject().put("startMs", start * 1000 / RATE).put("endMs", (start + length) * 1000 / RATE).put("text", text)

    /** Text of an audio file in any format the phone can decode (wav, mp3, ogg/opus, m4a…). */
    fun transcribeFile(path: String): String = transcribe(decodeAudio(path))

    /** The phrases of an audio file with their time, see [segments]. */
    fun segmentFile(path: String): JSONArray = segments(decodeAudio(path))

    // ---- microphone -------------------------------------------------------------------------

    fun stop() {
        stopRequested = true
    }

    /**
     * Listens for one utterance and returns its text: starts at once, ends after a pause in
     * speech, on [stop], or when nobody speaks. Pages follow it through the "speech" event.
     */
    fun listen(maxSeconds: Int = 45): String {
        if (Thread.currentThread().isInterrupted) return ""
        if (listening || Stream.active) error("Уже слушаю")
        if (!Permissions.ensure(Manifest.permission.RECORD_AUDIO)) error("Нет доступа к микрофону")
        val useSystem = engine == "system" || !ready
        if (useSystem && !SpeechRecognizer.isRecognitionAvailable(App.instance)) {
            error("Модель распознавания речи не скачана. Откройте «Настройки» и скачайте её.")
        }
        listening = true
        stopRequested = false
        Tts.stop()
        try {
            return if (useSystem) listenSystem(maxSeconds) else listenLocal(maxSeconds)
        } finally {
            listening = false
            emit("speech", JSONObject().put("state", "idle"))
        }
    }

    @SuppressLint("MissingPermission")
    private fun listenLocal(maxSeconds: Int): String {
        val detector = vad(1.0f)
        val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, RATE * 2),
        )
        val segments = ArrayList<FloatArray>()
        try {
            record.startRecording()
            emit("speech", JSONObject().put("state", "listening"))
            val buffer = ShortArray(WINDOW)
            val started = System.currentTimeMillis()
            var spoke = false
            var reported = 0L
            while (!stopRequested && !Thread.currentThread().isInterrupted) {
                val elapsed = System.currentTimeMillis() - started
                if (elapsed > maxSeconds * 1000L || (!spoke && elapsed > 8000)) break
                val read = record.read(buffer, 0, WINDOW)
                if (Thread.currentThread().isInterrupted) break
                if (read < WINDOW) continue
                var energy = 0.0
                val window = FloatArray(WINDOW) {
                    val sample = buffer[it] / 32768f
                    energy += sample * sample
                    sample
                }
                detector.acceptWaveform(window)
                if (detector.isSpeechDetected()) spoke = true
                var ended = false
                while (!detector.empty()) {
                    segments.add(detector.front().samples)
                    detector.pop()
                    ended = true
                }
                if (ended && !detector.isSpeechDetected()) break
                val now = System.currentTimeMillis()
                if (now - reported > 120) {
                    reported = now
                    emit(
                        "speech",
                        JSONObject().put("state", if (spoke) "speech" else "listening")
                            .put("level", sqrt(energy / WINDOW).coerceAtMost(1.0)),
                    )
                }
            }
            record.stop()
            if (Thread.currentThread().isInterrupted) return ""
            detector.flush()
            while (!detector.empty()) {
                segments.add(detector.front().samples)
                detector.pop()
            }
        } finally {
            record.release()
            detector.release()
        }
        if (segments.isEmpty() || Thread.currentThread().isInterrupted) return ""
        emit("speech", JSONObject().put("state", "processing"))
        val text = ArrayList<String>()
        for (segment in segments) {
            if (Thread.currentThread().isInterrupted) return ""
            decode(segment).takeIf { it.isNotBlank() }?.let(text::add)
        }
        return if (Thread.currentThread().isInterrupted) "" else text.joinToString(" ")
    }

    private fun listenSystem(maxSeconds: Int): String {
        val done = CountDownLatch(1)
        var text = ""
        var failure: String? = null
        val main = Handler(Looper.getMainLooper())
        var recognizer: SpeechRecognizer? = null
        main.post {
            val created = SpeechRecognizer.createSpeechRecognizer(App.instance)
            recognizer = created
            created.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = emit("speech", JSONObject().put("state", "listening"))
                override fun onBeginningOfSpeech() = emit("speech", JSONObject().put("state", "speech"))
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = emit("speech", JSONObject().put("state", "processing"))
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onError(error: Int) {
                    if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        failure = "Системное распознавание не сработало (код $error)"
                    }
                    done.countDown()
                }

                override fun onResults(results: Bundle?) {
                    text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    done.countDown()
                }
            })
            created.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, app.axiko.core.I18n.language.let { if (it == "ru") "ru-RU" else it }),
            )
        }
        try {
            val deadline = System.currentTimeMillis() + maxSeconds * 1000L
            while (done.count > 0 && System.currentTimeMillis() < deadline && !Thread.currentThread().isInterrupted) {
                if (stopRequested) {
                    main.post { recognizer?.stopListening() }
                    stopRequested = false
                }
                done.await(150, TimeUnit.MILLISECONDS)
            }
        } finally {
            main.post { recognizer?.destroy() }
        }
        if (Thread.currentThread().isInterrupted) return ""
        failure?.let { error(it) }
        return text.trim()
    }

    // ---- audio files ------------------------------------------------------------------------

    /** Decodes an audio file to 16 kHz mono samples. */
    private fun decodeAudio(path: String): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(path)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
        } ?: error("В файле нет звука")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val pcm = java.io.ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (index >= 0) {
                    val chunk = ByteArray(info.size)
                    codec.getOutputBuffer(index)!!.get(chunk)
                    pcm.write(chunk)
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
        val shorts = java.nio.ByteBuffer.wrap(pcm.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frames = shorts.remaining() / channels
        val mono = FloatArray(frames) { frame ->
            var sum = 0f
            for (channel in 0 until channels) sum += shorts.get(frame * channels + channel) / 32768f
            sum / channels
        }
        if (rate == RATE) return mono
        // Linear resampling is enough for speech recognition.
        val length = (frames.toLong() * RATE / rate).toInt()
        return FloatArray(length) { index ->
            val position = index.toDouble() * rate / RATE
            val left = position.toInt().coerceAtMost(frames - 1)
            val right = (left + 1).coerceAtMost(frames - 1)
            val weight = (position - left).toFloat()
            mono[left] * (1 - weight) + mono[right] * weight
        }
    }
}
