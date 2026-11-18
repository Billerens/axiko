package app.axiko.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import app.axiko.App
import app.axiko.agent.Agent
import app.axiko.core.Archive
import app.axiko.core.Engine
import app.axiko.core.Pages
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.Vad
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * The name the assistant answers to. With the wake word on, the microphone is listened to all
 * the time; a phrase that starts with the name ("Axiko, который час?") goes to the main agent,
 * and the answer is spoken.
 *
 * Two ways to listen. The thorough one recognizes every phrase heard with the big speech model
 * and looks for the name in the text: accurate, but it costs battery. The light one keeps a
 * small streaming model (a 26 MB Russian zipformer) on the microphone, and only a phrase in
 * which it caught something like the name goes to the big model — which then confirms it. The
 * small model is set to hear too much rather than too little: a false alarm costs one
 * recognition, a miss costs the owner's patience. A short name that sounds like common
 * syllables ("Асси") is caught less surely than a longer, distinct one ("Марфа", "Джарвис").
 */
object Wake {
    private const val PACK =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16.tar.bz2"
    const val MEGABYTES = 23
    private const val RATE = 16000
    private const val WINDOW = 512
    private val FILES = listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt")
    private val LEADS = setOf("эй", "окей", "ок", "слушай", "привет", "hey", "ok", "okay")

    private val dir: File get() = File(App.instance.filesDir, "models/kws-ru")

    /** The small model is on the phone. */
    val lightReady: Boolean get() = FILES.all { File(dir, it).isFile }

    @Volatile
    var downloading = false
        private set

    val wanted: Boolean get() = App.prefs.getString("wake.on", "off") == "on"

    val name: String get() = Agent.name

    private val thorough: Boolean get() = App.prefs.getString("wake.mode", "light") == "full"

    fun status(): JSONObject {
        val spottable = lightReady && runCatching { keywords(name).isNotEmpty() }.getOrDefault(false)
        return JSONObject()
            .put("on", wanted).put("name", name).put("mode", if (thorough) "full" else "light")
            .put("lightReady", lightReady).put("downloading", downloading).put("megabytes", MEGABYTES)
            // The small model spells words with Russian letters only: another name is left to the big one.
            .put("spottable", spottable)
            .put("running", running).put("captures", captures).put("captureErrors", captureErrors)
            .put("lastError", lastError)
    }

    private fun emit(data: JSONObject) = Pages.broadcast("wake.download", data.toString())

    /** Downloads the small model; progress goes to pages as the "wake.download" event. */
    fun download() {
        if (downloading || lightReady) return
        downloading = true
        try {
            dir.parentFile?.mkdirs()
            Archive.fetch(PACK, dir) { done, total -> emit(JSONObject().put("done", done).put("total", total)) }
            emit(JSONObject().put("ready", true))
            restart()
        } catch (error: Exception) {
            emit(JSONObject().put("error", error.message ?: error.toString()))
            throw error
        } finally {
            downloading = false
        }
    }

    fun set(on: Boolean) {
        App.prefs.edit().putString("wake.on", if (on) "on" else "off").apply()
        if (on) start() else stop()
    }

    fun rename(value: String) {
        Agent.rename(value)
        restart()
    }

    /** Settings changed: the listening starts over with them. */
    fun restart() {
        stop()
        start()
    }

    // ---- the name in text -----------------------------------------------------------------------

    private fun words(text: String): List<MatchResult> = Regex("[\\p{L}\\d]+").findAll(text).toList()

    private val LATIN_PAIRS = listOf(
        "shch" to "щ", "sch" to "щ", "sh" to "ш", "ch" to "ч", "zh" to "ж", "kh" to "х", "ts" to "ц",
        "ya" to "я", "yu" to "ю", "yo" to "е", "ck" to "к", "ph" to "ф", "th" to "т", "oo" to "у", "ee" to "и"
    )
    private val LATIN_LETTERS = mapOf(
        'a' to "а", 'b' to "б", 'c' to "к", 'd' to "д", 'e' to "е", 'f' to "ф", 'g' to "г", 'h' to "х", 'i' to "и",
        'j' to "дж", 'k' to "к", 'l' to "л", 'm' to "м", 'n' to "н", 'o' to "о", 'p' to "п", 'q' to "к", 'r' to "р",
        's' to "с", 't' to "т", 'u' to "у", 'v' to "в", 'w' to "в", 'x' to "кс", 'y' to "и", 'z' to "з"
    )

    /**
     * A name written with Latin letters as a Russian recognizer hears and writes it: "Axiko" is
     * said «аксико». Text without Latin letters comes back as it is.
     */
    internal fun russian(text: String): String {
        if (text.none { it in 'a'..'z' || it in 'A'..'Z' }) return text
        val lower = text.lowercase()
        val out = StringBuilder()
        var at = 0
        while (at < lower.length) {
            val pair = LATIN_PAIRS.firstOrNull { lower.startsWith(it.first, at) }
            if (pair != null) {
                out.append(pair.second)
                at += pair.first.length
            } else {
                out.append(LATIN_LETTERS[lower[at]] ?: lower[at].toString())
                at++
            }
        }
        return out.toString()
    }

    private fun plain(word: String): String {
        val lower = russian(word).lowercase().replace('ё', 'е')
        return lower.filterIndexed { index, char -> index == 0 || char != lower[index - 1] }
    }

    private fun distance(a: String, b: String): Int {
        var row = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val next = IntArray(b.length + 1)
            next[0] = i
            for (j in 1..b.length) {
                next[j] = minOf(row[j] + 1, next[j - 1] + 1, row[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            row = next
        }
        return row[b.length]
    }

    /** The same word as a recognizer may write it: doubled letters, а/о and the last vowel vary. */
    private fun alike(heard: String, wanted: String): Boolean {
        val a = plain(heard)
        val b = plain(wanted)
        if (a == b) return true
        if (b.length < 3 || a.isEmpty() || b.isEmpty()) return false
        val sameStart = a[0] == b[0] || (a[0] in "ао" && b[0] in "ао")
        return sameStart && distance(a, b) <= if (b.length >= 6) 2 else 1
    }

    /**
     * Where the request begins when the phrase starts with the name ("Axiko, …", "Эй, Axiko, …"),
     * or null when it was not addressed to the assistant.
     */
    fun nameEnd(heard: String, wanted: String = name): Int? {
        val said = words(heard)
        val target = words(wanted).map { it.value }
        if (target.isEmpty()) return null
        for (start in 0..1) {
            if (start == 1 && (said.isEmpty() || plain(said[0].value) !in LEADS)) break
            if (said.size < start + target.size) continue
            if (target.indices.all { alike(said[start + it].value, target[it]) }) return said[start + target.size - 1].range.last + 1
        }
        return null
    }

    // ---- the name for the small model -------------------------------------------------------------

    private var vocabulary: Set<String>? = null

    private fun vocabulary(): Set<String> = vocabulary ?: File(dir, "tokens.txt").readLines()
        .map { it.substringBeforeLast(' ') }.toSet().also { vocabulary = it }

    /** Every way to spell the word with the model's tokens, the shortest first. */
    private fun spellings(word: String, limit: Int): List<List<String>> {
        val tokens = vocabulary()
        val found = ArrayList<List<String>>()
        fun walk(rest: String, done: List<String>) {
            if (found.size > 200) return
            if (rest.isEmpty()) {
                found.add(done)
                return
            }
            for (size in rest.length downTo 1) {
                val token = (if (done.isEmpty()) "▁" else "") + rest.substring(0, size)
                if (token in tokens) walk(rest.substring(size), done + token)
            }
        }
        walk(word, emptyList())
        return found.sortedBy { it.size }.take(limit)
    }

    private fun soundAlikes(word: String): Set<String> {
        val base = word.lowercase().replace('ё', 'е')
        val out = linkedSetOf(base, plain(base))
        for (variant in out.toList()) {
            if (variant[0] in "ао") out.add((if (variant[0] == 'а') "о" else "а") + variant.substring(1))
        }
        for (variant in out.toList()) {
            if (variant.endsWith("и")) out.add(variant.dropLast(1) + "е")
        }
        return out
    }

    /** The lines of the small model's keyword list for the name; empty when it cannot spell it. */
    private fun keywords(wanted: String): List<String> {
        val parts = words(russian(wanted)).map { it.value }
        if (parts.isEmpty()) return emptyList()
        var lines = listOf(emptyList<String>())
        for (part in parts) {
            val options = soundAlikes(part).flatMap { spellings(it, if (parts.size == 1) 4 else 2) }.distinct()
            if (options.isEmpty()) return emptyList()
            lines = lines.flatMap { line -> options.map { line + it } }.take(32)
        }
        return lines.map { it.joinToString(" ") + " @name" }.distinct()
    }

    /** The caller owns this model until all of its streams are released. */
    private fun makeSpotter(wanted: String): KeywordSpotter? {
        val lines = keywords(wanted)
        if (lines.isEmpty()) return null
        val list = File.createTempFile("wake-keywords-", ".txt", App.instance.cacheDir)
        try {
          list.writeText(lines.joinToString("\n") + "\n")
          return KeywordSpotter(
            null,
            KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(dir, "encoder.int8.onnx").path,
                        decoder = File(dir, "decoder.onnx").path,
                        joiner = File(dir, "joiner.int8.onnx").path,
                    ),
                    tokens = File(dir, "tokens.txt").path,
                    numThreads = 1,
                ),
                keywordsFile = list.path,
                // A wide net: what it catches is checked by the big model anyway.
                keywordsScore = 3.0f,
                keywordsThreshold = 0.03f,
                numTrailingBlanks = 1,
            ),
          )
        } finally {
            list.delete()
        }
    }

    /** Whether the small model catches the name in these samples (16 kHz): for checks. */
    fun spot(samples: FloatArray, wanted: String = name): Boolean {
        val engine = makeSpotter(wanted) ?: error("Лёгкая модель не может записать имя «$wanted» своими буквами")
        try {
          val stream = engine.createStream()
          try {
            val padded = FloatArray(RATE / 2) + samples + FloatArray(RATE * 3 / 4)
            var at = 0
            while (at < padded.size) {
                val end = minOf(at + 1600, padded.size)
                stream.acceptWaveform(padded.copyOfRange(at, end), RATE)
                while (engine.isReady(stream)) {
                    engine.decode(stream)
                    if (engine.getResult(stream).keyword.isNotEmpty()) return true
                }
                at = end
            }
            return false
          } finally {
            stream.release()
          }
        } finally {
            engine.release()
        }
    }

    // ---- listening --------------------------------------------------------------------------------

    /** Grows on every stop: a loop of an older generation ends. */
    @Volatile
    private var generation = 0

    @Volatile
    private var running = false

    @Volatile private var captures = 0L
    @Volatile private var captureErrors = 0L
    @Volatile private var lastError = ""

    // Serial tasks wait for the previous session's finally, without joining on the UI thread.
    private val listener = Executors.newSingleThreadExecutor { task ->
        Thread(task, "axiko-wake").apply { isDaemon = true }
    }
    private val cancellation = Executors.newSingleThreadExecutor { task ->
        Thread(task, "axiko-wake-stop").apply { isDaemon = true }
    }
    private var worker: Thread? = null // guarded by this
    private val captureLock = Any()
    private data class Capture(val ticket: Int, val record: AudioRecord)
    private var activeCapture: Capture? = null // guarded by captureLock

    @Synchronized
    fun start() {
        if (running || !wanted || !Stt.ready) return
        if (App.instance.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        running = true
        val ticket = ++generation
        listener.execute {
            var gate: KeywordSpotter? = null
            var failures = 0
            try {
              synchronized(this) {
                  if (ticket != generation || !wanted) return@execute
                  worker = Thread.currentThread()
              }
              gate = if (!thorough && lightReady) runCatching { makeSpotter(name) }.getOrNull() else null
              while (ticket == generation && wanted) {
                // The microphone is someone else's for now; the watch resumes when it is free.
                if (Stt.listening || Tts.speaking || Live.active || Stream.active) {
                    Thread.sleep(300)
                    continue
                }
                val heard = try {
                    phrase(ticket, gate).also { failures = 0; lastError = "" }
                } catch (error: Exception) {
                    if (ticket != generation || !wanted) break
                    if (error is InterruptedException) throw error
                    captureErrors++
                    lastError = error.message ?: error.javaClass.simpleName
                    // A revoked permission or failed recorder must not become a CPU-tight retry loop.
                    if (failures == 0) Engine.host.log("error", "Голосовой вызов: $lastError")
                    failures = (failures + 1).coerceAtMost(5)
                    Thread.sleep((1000L shl (failures - 1)).coerceAtMost(15_000))
                    continue
                }
                if (ticket != generation || !wanted) break
                if (heard.isNullOrBlank()) continue
                val end = nameEnd(heard) ?: continue
                var command = heard.substring(end).trim(' ', ',', '.', '!', '?', '-', '—')
                if (command.length < 3) {
                    // Only the name was said: a short tone, then the request itself.
                    runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 60).startTone(ToneGenerator.TONE_PROP_BEEP, 120) }
                    command = runCatching { Stt.listen(20) }.getOrDefault("")
                }
                if (ticket != generation || !wanted) break
                if (command.isBlank()) continue
                Tts.voiceTurn = true
                Agent.chat(Agent.MAIN).send(command)
              }
            } catch (stopped: InterruptedException) {
                // stop() wakes the busy-microphone wait and failure backoff immediately.
            } finally {
                try {
                    gate?.release()
                } finally {
                    synchronized(this) {
                        if (worker === Thread.currentThread()) worker = null
                        if (ticket == generation) running = false
                        Thread.interrupted()
                    }
                }
            }
        }
    }

    fun stop() {
        synchronized(this) {
            generation++
            running = false
            worker?.interrupt()
        }
        // Native start/stop/release may block. UI never waits for this lock or the worker.
        // A delayed cancellation may see a new recorder: only stop a stale generation.
        cancellation.execute {
            synchronized(captureLock) {
                activeCapture?.takeIf { it.ticket != generation }?.let { runCatching { it.record.stop() } }
            }
        }
    }

    /** Keep one recorder and VAD through silence; release when speech is captured or another voice mode needs the mic. */
    @SuppressLint("MissingPermission")
    private fun phrase(ticket: Int, gate: KeywordSpotter?): String? {
        val stream = gate?.createStream()
        var detectorToRelease: Vad? = null
        var recordToRelease: AudioRecord? = null
        var segment: FloatArray? = null
        try {
            val detector = Stt.vad(0.7f).also { detectorToRelease = it }
            val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "Формат микрофона недоступен: $minimum" }
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, RATE * 2),
            ).also { recordToRelease = it }
            check(record.state == AudioRecord.STATE_INITIALIZED) { "Не удалось открыть микрофон." }
            synchronized(captureLock) {
                if (ticket != generation || !wanted) return null
                activeCapture = Capture(ticket, record)
                record.startRecording()
            }
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Микрофон не начал запись." }
            captures++
            val buffer = ShortArray(WINDOW)
            var filled = 0
            var emptyReads = 0
            // Without the small model every phrase counts as possibly addressed to the assistant.
            var named = gate == null
            while (ticket == generation && wanted && !Stt.listening && !Tts.speaking && !Live.active && !Stream.active && segment == null) {
                val count = record.read(buffer, filled, WINDOW - filled)
                if (ticket != generation || !wanted) break
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
                if (gate != null && stream != null) {
                    stream.acceptWaveform(window, RATE)
                    while (gate.isReady(stream)) {
                        gate.decode(stream)
                        if (gate.getResult(stream).keyword.isNotEmpty()) {
                            named = true
                            gate.reset(stream)
                        }
                    }
                }
                if (!detector.empty()) {
                    val samples = detector.front().samples
                    detector.pop()
                    if (named) segment = samples
                }
            }
        } finally {
            try {
                synchronized(captureLock) {
                    if (activeCapture?.record === recordToRelease) activeCapture = null
                    recordToRelease?.release()
                }
            } finally {
                try { detectorToRelease?.release() } finally { stream?.release() }
            }
        }
        return if (ticket == generation && wanted) segment?.let { Stt.decode(it) } else null
    }
}
