package app.assi.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import app.assi.App
import app.assi.agent.Llm
import app.assi.agent.Models
import app.assi.agent.Usage
import app.assi.core.Pages
import app.assi.core.Workspace
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Text to speech. The main voice is a cloud model of the "tts" role: `POST {baseUrl}/audio/speech`,
 * the OpenAI audio API that OpenRouter and others speak. The reference is Gemini Flash TTS:
 * a voice by name and a manner in words, answered as raw PCM that is played as it arrives.
 * The spare is the voice on the phone ([LocalTts]): it speaks when the cloud one is not set up
 * or does not answer, or always — the owner chooses ("tts.engine": auto, cloud, local).
 */
object Tts {
    const val ROLE = "tts"
    private const val DEFAULT_RATE = 24_000
    private const val CHUNK = 600
    private const val GEMINI_STYLE = "provider.options.google-ai-studio.speech_metadata.style"

    val enabled: Boolean get() = App.prefs.getString("tts.on", "off") == "on"

    /** The next answer is spoken even with the voice off: it was asked for by voice alone. */
    @Volatile
    var voiceTurn = false

    /**
     * A live conversation is on: the voice sounds as the other party of a call does, which is what
     * lets the phone take it out of what the microphone hears (see [Live]).
     */
    @Volatile
    var conversation = false

    private val queued = java.util.concurrent.atomic.AtomicInteger(0)

    /** Something is being said or waits to be. */
    val speaking: Boolean get() = queued.get() > 0

    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "assi-tts").apply { isDaemon = true } }

    /** Grows on every stop: queued phrases of an older generation are dropped. */
    @Volatile
    private var generation = 0

    @Volatile
    private var call: Call? = null

    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    private var player: MediaPlayer? = null

    /** Speaks the text after whatever is being spoken now; [onPhone] asks for the phone's own voice. */
    fun say(text: String, onPhone: Boolean = false) {
        val ticket = generation
        val phrases = phrases(speakable(text))
        if (phrases.isEmpty()) return
        queued.incrementAndGet()
        worker.execute {
            try {
                for (phrase in phrases) {
                    if (ticket != generation) return@execute
                    if (onPhone) speakLocal(phrase, ticket) else speak(phrase, ticket)
                }
            } catch (error: Exception) {
                if (ticket == generation) {
                    Pages.broadcast("tts", JSONObject().put("error", error.message ?: error.toString()).toString())
                }
            } finally {
                queued.decrementAndGet()
            }
        }
    }

    /** Silences the current phrase and forgets the queued ones. */
    fun stop() {
        generation++
        call?.cancel()
        runCatching { track?.pause(); track?.flush() }
        runCatching { player?.stop() }
    }

    /** What is worth saying aloud: no code, no markup, no addresses. */
    fun speakable(text: String): String = text
        .replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("`([^`]*)`"), "$1")
        .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("https?://\\S+"), " ")
        .replace(Regex("(?m)^\\s*(#{1,6}|[-*•]|\\d+[.)])\\s+"), "")
        .replace(Regex("[*_~|]"), "")
        .replace(Regex("[ \\t]+"), " ")
        .trim()

    /** Sentences packed into pieces small enough to start sounding quickly. */
    private fun phrases(text: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (sentence in text.split(Regex("(?<=[.!?…])\\s+|\\n+"))) {
            val piece = sentence.trim()
            if (piece.isEmpty()) continue
            if (current.isNotEmpty() && current.length + piece.length > CHUNK) {
                out.add(current.toString())
                current.clear()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(piece)
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }

    /** After a failure the cloud voice is not tried again for a while: the phrases go on at once. */
    @Volatile
    private var cloudDownUntil = 0L

    private fun cloudSet(): Boolean = runCatching { Models.resolve(ROLE, inherit = false).model.isNotBlank() }.getOrDefault(false)

    private fun speak(text: String, ticket: Int) {
        val mode = App.prefs.getString("tts.engine", "auto")
        val spare = LocalTts.ready
        if (spare && (mode == "local" || (mode != "cloud" && (!cloudSet() || System.currentTimeMillis() < cloudDownUntil)))) {
            speakLocal(text, ticket)
            return
        }
        try {
            speakCloud(text, ticket)
        } catch (error: Exception) {
            if (ticket != generation) return
            if (!spare || mode == "cloud") throw error
            cloudDownUntil = System.currentTimeMillis() + 60_000
            speakLocal(text, ticket)
        }
    }

    private fun speakLocal(text: String, ticket: Int) {
        val output = newTrack(LocalTts.rate())
        track = output
        try {
            output.play()
            var frames = 0L
            LocalTts.speak(text) { samples ->
                if (ticket == generation) {
                    val pcm = ShortArray(samples.size) { (samples[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
                    output.write(pcm, 0, pcm.size)
                    frames += pcm.size
                }
                ticket == generation
            }
            drain(output, frames, ticket)
        } finally {
            track = null
            runCatching { output.stop() }
            output.release()
        }
    }

    /** The cloud voice's request: the text in the voice and the manner the role's options name. */
    private fun cloudRequest(target: Models.Target, text: String): Request {
        val gemini = target.model.contains("gemini", ignoreCase = true)
        val body = JSONObject()
            .put("model", target.model)
            .put("input", text)
            .put("voice", target.options.optString("voice").ifBlank { if (gemini) "Zephyr" else "alloy" })
            .put("response_format", target.options.optString("format").ifBlank { "pcm" })
        val style = target.options.optString("style").trim()
        if (style.isNotEmpty()) {
            // Where the manner goes differs by vendor: a dotted path into the request body.
            val path = target.options.optString("styleParam").ifBlank { if (gemini) GEMINI_STYLE else "instructions" }
            var node = body
            val keys = path.split('.')
            for (key in keys.dropLast(1)) {
                node = node.optJSONObject(key) ?: JSONObject().also { node.put(key, it) }
            }
            node.put(keys.last(), style)
        }
        return Request.Builder().url(target.baseUrl + "/audio/speech").apply {
            if (target.key.isNotEmpty()) header("Authorization", "Bearer ${target.key}")
            header("X-Title", "Axiko")
        }.post(body.toString().toRequestBody("application/json".toMediaType())).build()
    }

    /** The answer of the cloud voice when it is not sound. */
    private fun failed(response: okhttp3.Response): Nothing {
        val detail = runCatching { JSONObject(response.body?.string().orEmpty()).optJSONObject("error")?.optString("message") }.getOrNull()
        error("Синтез речи не удался (${response.code})" + (detail?.let { ": $it" } ?: ""))
    }

    /** Raw 16-bit PCM is what Gemini gives; others encode (mp3, wav…) and say so. */
    private fun raw(type: String): Boolean = type.isEmpty() || type.startsWith("audio/pcm") || type.startsWith("audio/l16") ||
        type.startsWith("application/octet-stream")

    private fun rate(type: String): Int = Regex("rate=(\\d+)").find(type)?.groupValues?.get(1)?.toIntOrNull() ?: DEFAULT_RATE

    private fun speakCloud(text: String, ticket: Int) {
        val started = Llm.client.newCall(cloudRequest(Models.resolve(ROLE, inherit = false), text))
        call = started
        try {
            started.execute().use { response ->
                val source = response.body
                if (!response.isSuccessful || source == null) failed(response)
                val type = response.header("Content-Type").orEmpty().lowercase()
                if (raw(type)) {
                    playPcm(source.byteStream(), rate(type), ticket)
                } else {
                    val file = File(App.instance.cacheDir, "tts-${System.nanoTime()}")
                    file.outputStream().use { source.byteStream().copyTo(it) }
                    if (ticket == generation) playFile(file)
                    file.delete()
                }
            }
        } catch (error: java.io.IOException) {
            if (ticket == generation) throw error
        } finally {
            call = null
        }
    }

    // ---- into a file ------------------------------------------------------------------------

    /**
     * Says the text into a file rather than aloud — a game's line, a story for the night. 'cloud'
     * is the voice of the tts role, counted against the daily limit; 'local' the phone's own; with
     * no mode the owner's choice of voice decides. Raw sound is written as WAV, a provider's own
     * encoding is kept and the file's extension follows it. Returns the path written.
     */
    fun render(text: String, path: String, mode: String?): String {
        val spoken = speakable(text)
        if (spoken.isBlank()) error("Нечего озвучивать")
        val cloud = when (mode) {
            "cloud" -> true
            "local" -> false
            else -> App.prefs.getString("tts.engine", "auto") != "local" && cloudSet() || !LocalTts.ready
        }
        return if (cloud) renderCloud(spoken, path) else renderLocal(spoken, path)
    }

    private fun renderLocal(text: String, path: String): String {
        val pcm = java.io.ByteArrayOutputStream()
        for (phrase in phrases(text)) {
            for (sample in LocalTts.samples(phrase)) {
                val value = (sample.coerceIn(-1f, 1f) * 32767f).toInt()
                pcm.write(value and 0xFF)
                pcm.write(value shr 8 and 0xFF)
            }
        }
        return save(path, "wav", wav(pcm.toByteArray(), LocalTts.rate()))
    }

    private fun renderCloud(text: String, path: String): String {
        Usage.guard()
        val target = Models.resolve(ROLE, inherit = false)
        if (target.model.isBlank()) error("Облачный голос не настроен: назначьте модель роли tts в «Настройках»")
        Llm.client.newCall(cloudRequest(target, text)).execute().use { response ->
            val source = response.body
            if (!response.isSuccessful || source == null) failed(response)
            val bytes = source.bytes()
            // The provider reports no tokens for speech: the call itself is counted.
            Usage.record(ROLE, target.model, JSONObject())
            val type = response.header("Content-Type").orEmpty().lowercase()
            return if (raw(type)) save(path, "wav", wav(bytes, rate(type))) else save(path, extension(type, path), bytes)
        }
    }

    /** The file's extension for an encoded answer; the one asked for when the type says nothing known. */
    private fun extension(type: String, path: String): String = when {
        "mpeg" in type || "mp3" in type -> "mp3"
        "wav" in type || "wave" in type -> "wav"
        "ogg" in type || "opus" in type -> "ogg"
        "aac" in type -> "aac"
        "flac" in type -> "flac"
        "mp4" in type || "m4a" in type -> "m4a"
        else -> path.substringAfterLast('/').substringAfterLast('.', "audio")
    }

    /** Writes the sound under the asked path, its extension made to match what it is. */
    private fun save(path: String, extension: String, bytes: ByteArray): String {
        val name = path.substringAfterLast('/')
        val stem = if ('.' in name) path.substringBeforeLast('.') else path
        val target = "$stem.$extension"
        val file = Workspace.resolve(target, write = true)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return target
    }

    /** 16-bit mono PCM (little-endian) wrapped as a WAV file. */
    fun wav(pcm: ByteArray, rate: Int): ByteArray {
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(pcm.size)
        return header.array() + pcm
    }

    private fun attributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (conversation) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    private fun newTrack(rate: Int): AudioTrack {
        val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(attributes())
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setBufferSizeInBytes(maxOf(minimum, rate))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /** Lets what is already written sound out. */
    private fun drain(output: AudioTrack, frames: Long, ticket: Int) {
        val deadline = System.currentTimeMillis() + 60_000
        while (ticket == generation && output.playbackHeadPosition < frames && System.currentTimeMillis() < deadline) {
            Thread.sleep(40)
        }
    }

    /** Plays 16-bit mono PCM as it downloads. */
    private fun playPcm(input: java.io.InputStream, rate: Int, ticket: Int) {
        val output = newTrack(rate)
        track = output
        try {
            output.play()
            val buffer = ByteArray(8192)
            var carried = 0
            var frames = 0L
            while (ticket == generation) {
                val read = input.read(buffer, carried, buffer.size - carried)
                if (read < 0) break
                val available = carried + read
                val whole = available - available % 2
                if (whole > 0) {
                    output.write(buffer, 0, whole)
                    frames += whole / 2
                }
                carried = available - whole
                if (carried > 0) buffer[0] = buffer[whole]
            }
            drain(output, frames, ticket)
        } finally {
            track = null
            runCatching { output.stop() }
            output.release()
        }
    }

    /** Plays an encoded answer (mp3, wav…) from a provider that does not give raw PCM. */
    private fun playFile(file: File) {
        val done = CountDownLatch(1)
        val media = MediaPlayer()
        player = media
        try {
            media.setAudioAttributes(attributes())
            media.setDataSource(file.path)
            media.setOnCompletionListener { done.countDown() }
            media.setOnErrorListener { _, _, _ -> done.countDown(); true }
            media.prepare()
            media.start()
            val ticket = generation
            while (done.count > 0 && ticket == generation) done.await(100, TimeUnit.MILLISECONDS)
        } finally {
            player = null
            runCatching { media.release() }
        }
    }
}

/**
 * Speaks an answer while it is still being written: text comes in pieces, and every finished
 * sentence goes to the voice at once, so the first words sound long before the last are known.
 */
class Speaker {
    private val buffer = StringBuilder()

    fun feed(piece: String) {
        buffer.append(piece)
        // Code is never read aloud: wait until an opened block is closed.
        if (Regex("```").findAll(buffer).count() % 2 == 1) return
        val end = Regex("[.!?…](?=\\s)|\n").findAll(buffer).lastOrNull()?.range?.last ?: return
        if (end + 1 < MIN) return
        Tts.say(buffer.substring(0, end + 1))
        buffer.delete(0, end + 1)
    }

    /** The answer is complete: whatever is left is spoken too. */
    fun finish() {
        if (buffer.isNotBlank()) Tts.say(buffer.toString())
        buffer.clear()
    }

    private companion object {
        /** Shorter scraps wait for the next sentence, so the voice does not stutter. */
        const val MIN = 40
    }
}
