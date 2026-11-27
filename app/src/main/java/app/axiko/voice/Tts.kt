package app.axiko.voice

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import app.axiko.App
import app.axiko.agent.Llm
import app.axiko.agent.Models
import app.axiko.agent.Usage
import app.axiko.core.Pages
import app.axiko.core.Workspace
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Text to speech. The main voice is a cloud model of the "tts" role: `POST {baseUrl}/audio/speech`,
 * the OpenAI audio API that OpenRouter and others speak. The reference is Gemini Flash TTS:
 * a voice by name and a manner in words, answered as raw PCM that is played as it arrives.
 * The spare is the voice on the phone ([LocalTts]): it speaks when the cloud one is not set up
 * or does not answer, or always — the owner chooses ("tts.engine": auto, cloud, local).
 *
 * Two threads keep the voice going without gaps: one asks for the sound of the next piece while
 * the other plays the current one into a single audio track. Pieces that wait together are asked
 * for as one, so an answer written faster than it is spoken goes in fewer, longer requests.
 */
object Tts {
    const val ROLE = "tts"
    private const val DEFAULT_RATE = 24_000
    private const val CHUNK = 600
    private const val MAX_ECHO_PCM_BYTES = 16 * 1024 * 1024
    private const val GEMINI_STYLE = "provider.options.google-ai-studio.speech_metadata.style"

    /** A sample quieter than this, of 32767, counts as silence at the start of a piece. */
    private const val QUIET = 400

    /** The silence kept before the first sound of a piece that follows another one. */
    private const val LEAD_MS = 120

    val enabled: Boolean get() = App.prefs.getString("tts.on", "off") == "on"

    /** The next answer is spoken even with the voice off: it was asked for by voice alone. */
    @Volatile
    var voiceTurn = false

    /**
     * The regular in-app voice chat uses call audio for its echo reference.
     * On-screen replies explicitly use assistant audio.
     */
    @Volatile
    var conversation = false

    /** Text to be spoken: [id] names what a page asked to hear; [opens] and [closes] mark its ends. */
    private class Piece(
        val text: String, val onPhone: Boolean, val callAudio: Boolean, val ticket: Int,
        val id: String, val opens: Boolean, val closes: Boolean,
    )

    /** The sound of one or more pieces, filled by the fetching thread while the playing one reads it. */
    private class Clip(val ticket: Int, val pieces: List<Piece>) {
        val id = pieces.first().id
        val callAudio = pieces.first().callAudio
        @Volatile var cancelled = false
        @Volatile var request: Call? = null

        fun alive() = !cancelled && ticket == generation

        private val lock = Object()
        private val parts = ArrayDeque<ByteArray>()
        private var finished = false

        /** Taken by the player: the fetching thread may start on the next clip. */
        var started = false

        /** Samples a second of the raw sound, known before its first part is put. */
        @Volatile
        var rate = 0

        /** A provider's encoded answer (mp3, wav…), played whole once it has come. */
        @Volatile
        var file: File? = null

        val text: String get() = pieces.joinToString(" ") { it.text }

        fun put(bytes: ByteArray) = synchronized(lock) { parts.add(bytes); lock.notifyAll() }

        fun finish() = synchronized(lock) { finished = true; lock.notifyAll() }

        fun empty(): Boolean = synchronized(lock) { parts.isEmpty() }

        /** The next part of the sound, waited for; null once all of it has come or [alive] says stop. */
        fun next(alive: () -> Boolean): ByteArray? = synchronized(lock) {
            while (parts.isEmpty() && !finished && alive()) lock.wait(100)
            parts.removeFirstOrNull()
        }
    }

    /** Guards [pending], [clips] and [generation]; waited on by both threads. */
    private val state = Object()
    private val pending = ArrayDeque<Piece>()
    private val clips = ArrayDeque<Clip>()

    /** Something is being said or waits to be. */
    val speaking: Boolean get() = synchronized(state) { pending.isNotEmpty() || clips.isNotEmpty() }

    fun speakingFor(id: String): Boolean = synchronized(state) {
        pending.any { it.id == id } || clips.any { it.id == id && it.alive() }
    }

    fun stopFor(id: String) {
        val requests = synchronized(state) {
            pending.removeAll { it.id == id }
            clips.filter { it.id == id }.mapNotNull {
                it.cancelled = true
                it.request
            }
        }
        requests.forEach { it.cancel() }
    }

    /** Grows on every stop: pieces and clips of an older generation are dropped. */
    @Volatile
    private var generation = 0

    @Volatile
    private var call: Call? = null

    @Volatile
    private var track: PcmPlayback? = null

    @Volatile
    private var player: MediaPlayer? = null

    private class PcmPlayback(val output: AudioTrack, val reference: PcmEchoReference, @Volatile var id: String)

    internal class RoomPlayback(
        val source: Any, val id: String, private val reference: PcmEchoReference?, private val renderedFrames: Long,
        val deviceId: Int? = null,
    ) {
        fun match(window: FloatArray): EchoMatch? = reference?.match(window, renderedFrames)
    }

    /** Capture the playback head and its PCM together. The MediaPlayer fallback has no reference. */
    internal fun roomPlayback(): RoomPlayback? {
        val output = track
        if (output != null) return runCatching {
            val sink = output.output
            val device = sink.routedDevice
            if (sink.playState != AudioTrack.PLAYSTATE_PLAYING || device?.type in HEADPHONES) null
            else RoomPlayback(output, output.id, output.reference, sink.playbackHeadPosition.toLong() and 0xFFFF_FFFFL, device?.id)
        }.getOrElse { RoomPlayback(output, output.id, null, 0L) }
        val media = player ?: return null
        return runCatching {
            val device = media.routedDevice
            if (media.isPlaying && device?.type !in HEADPHONES) RoomPlayback(media, "", null, 0L, device?.id) else null
        }.getOrNull()
    }

    private val HEADPHONES = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_HEARING_AID,
    )

    private val threads by lazy {
        Thread(::fetching, "axiko-tts-fetch").apply { isDaemon = true; start() }
        Thread(::playing, "axiko-tts-play").apply { isDaemon = true; start() }
        true
    }

    /**
     * Speaks the text after whatever is being spoken now; [onPhone] asks for the phone's own voice.
     * [callAudio] selects the playback usage before the pieces enter the queue.
     * With an [id], pages hear the "tts" event `{say: id, state: "start"}` when it begins to sound
     * and `{say: id, state: "end"}` when it has been said.
     */
    fun say(text: String, onPhone: Boolean = false, id: String = "", callAudio: Boolean = conversation) {
        val phrases = phrases(speakable(text))
        if (phrases.isEmpty()) {
            if (id.isNotEmpty()) told(id, "end")
            return
        }
        threads
        synchronized(state) {
            val ticket = generation
            phrases.forEachIndexed { index, phrase ->
                pending.add(Piece(phrase, onPhone, callAudio, ticket, id, index == 0, index == phrases.lastIndex))
            }
            state.notifyAll()
        }
    }

    /**
     * Silences the current piece and forgets the waiting ones. Pages hear `{state: "stopped", ids}`
     * with the ids of what will not be said now.
     */
    fun stop() {
        val ids = org.json.JSONArray()
        val dropped = synchronized(state) {
            generation++
            (pending.map { it.id } + clips.flatMap { clip -> clip.pieces.map { it.id } })
                .filter { it.isNotEmpty() }.distinct().forEach { ids.put(it) }
            pending.clear()
            val gone = clips.filter { !it.started }
            clips.removeAll(gone.toSet())
            state.notifyAll()
            gone
        }
        dropped.forEach { clip -> clip.file?.delete() }
        call?.cancel()
        runCatching { track?.output?.let { it.pause(); it.flush() } }
        runCatching { player?.stop() }
        Pages.broadcast("tts", JSONObject().put("state", "stopped").put("ids", ids).toString())
    }

    private fun told(id: String, what: String) {
        Pages.broadcast("tts", JSONObject().put("say", id).put("state", what).toString())
        if (id.startsWith("assistant:")) app.axiko.sense.OnScreenAssistant.voiceEvent(id.removePrefix("assistant:"))
    }

    private fun failure(error: Exception) {
        Pages.broadcast("tts", JSONObject().put("error", error.message ?: error.toString()).toString())
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

    // ---- fetching: the sound of the next clip while the current one plays ----------------------

    /**
     * Takes the waiting pieces as one clip — as many as fit in [CHUNK] and belong together — and
     * fills it with sound. A new clip is started only when the player has taken the one before, so
     * the sound is at most one clip ahead of the voice and a stop wastes little.
     */
    private fun fetching() {
        while (true) {
            val clip = synchronized(state) {
                while (pending.isEmpty() || clips.any { !it.started }) state.wait()
                val first = pending.removeFirst()
                val group = arrayListOf(first)
                var length = first.text.length
                while (pending.isNotEmpty()) {
                    val next = pending.first()
                    if (next.ticket != first.ticket || next.onPhone != first.onPhone || next.id != first.id || next.callAudio != first.callAudio) break
                    if (length + 1 + next.text.length > CHUNK) break
                    group.add(pending.removeFirst())
                    length += 1 + next.text.length
                }
                Clip(first.ticket, group).also { clips.add(it); state.notifyAll() }
            }
            try {
                fill(clip)
            } catch (error: Exception) {
                if (clip.alive()) failure(error)
            } finally {
                clip.finish()
            }
        }
    }

    /** After a failure the cloud voice is not tried again for a while: the pieces go on at once. */
    @Volatile
    private var cloudDownUntil = 0L

    private fun cloudSet(): Boolean = runCatching { Models.resolve(ROLE, inherit = false).model.isNotBlank() }.getOrDefault(false)

    private fun fill(clip: Clip) {
        if (!clip.alive()) return
        val mode = App.prefs.getString("tts.engine", "auto")
        val spare = LocalTts.ready
        val phone = clip.pieces.first().onPhone
        if (phone || spare && (mode == "local" || (mode != "cloud" && (!cloudSet() || System.currentTimeMillis() < cloudDownUntil)))) {
            fillLocal(clip)
            return
        }
        try {
            fillCloud(clip)
        } catch (error: Exception) {
            if (!clip.alive()) return
            // Sound that has already come is not said twice: the rest of the clip is lost instead.
            if (!spare || mode == "cloud" || !clip.empty() || clip.file != null) throw error
            cloudDownUntil = System.currentTimeMillis() + 60_000
            fillLocal(clip)
        }
    }

    private fun fillLocal(clip: Clip) {
        clip.rate = LocalTts.rate()
        LocalTts.speak(clip.text) { samples ->
            if (clip.alive()) {
                val bytes = ByteArray(samples.size * 2)
                for (index in samples.indices) {
                    val value = (samples[index].coerceIn(-1f, 1f) * 32767f).toInt()
                    bytes[index * 2] = (value and 0xFF).toByte()
                    bytes[index * 2 + 1] = (value shr 8 and 0xFF).toByte()
                }
                clip.put(bytes)
            }
            clip.alive()
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

    private fun fillCloud(clip: Clip) {
        val started = Llm.client.newCall(cloudRequest(Models.resolve(ROLE, inherit = false), clip.text))
        clip.request = started
        call = started
        try {
            if (!clip.alive()) return
            started.execute().use { response ->
                val source = response.body
                if (!response.isSuccessful || source == null) failed(response)
                val type = response.header("Content-Type").orEmpty().lowercase()
                if (raw(type)) {
                    clip.rate = rate(type)
                    val input = source.byteStream()
                    val buffer = ByteArray(8192)
                    var carried = 0
                    while (clip.alive()) {
                        val read = input.read(buffer, carried, buffer.size - carried)
                        if (read < 0) break
                        val available = carried + read
                        val whole = available - available % 2
                        if (whole > 0) clip.put(buffer.copyOfRange(0, whole))
                        carried = available - whole
                        if (carried > 0) buffer[0] = buffer[whole]
                    }
                } else {
                    fillEncoded(clip, source.byteStream())
                }
            }
        } catch (error: java.io.IOException) {
            if (clip.alive()) throw error
        } finally {
            clip.request = null
            call = null
        }
    }

    private fun fillEncoded(clip: Clip, input: java.io.InputStream) {
        val file = File.createTempFile("tts-", null, App.instance.cacheDir)
        try {
            file.outputStream().use { input.copyTo(it) }
            if (!clip.alive()) return
            if (clip.id.startsWith("assistant:") && !clip.callAudio) {
                val pcm = runCatching { MediaPcm.decode(file.path, MAX_ECHO_PCM_BYTES, clip::alive) }.getOrNull()
                if (!clip.alive()) return
                if (pcm != null) {
                    clip.rate = pcm.format.rate
                    clip.put(pcm.mono16())
                    return
                }
            }
            // Fallback for a format MediaPlayer can play without giving us its samples.
            clip.file = file
            if (!clip.alive()) clip.file = null
        } finally {
            if (clip.file != file) file.delete()
        }
    }

    // ---- playing: clip after clip into one track ------------------------------------------------

    private fun playing() {
        var output: PcmPlayback? = null
        var outputRate = 0
        var outputCall = false
        var written = 0L
        fun close() {
            track = null
            output?.output?.let { runCatching { it.stop() }; it.release() }
            output = null
            written = 0
        }
        while (true) {
            val clip = synchronized(state) {
                var next = clips.firstOrNull { !it.started }
                while (next == null) {
                    state.wait()
                    next = clips.firstOrNull { !it.started }
                }
                next.started = true
                state.notifyAll()
                next
            }
            val alive = clip::alive
            try {
                var first = clip.next(alive)
                if (alive() && (first != null || clip.file != null)) clip.pieces.filter { it.opens && it.id.isNotEmpty() }.forEach { told(it.id, "start") }
                if (first != null && alive()) {
                    // One track for clip after clip: a new one only when the sound or its kind changes.
                    val continuing = output != null && outputRate == clip.rate && outputCall == clip.callAudio
                    if (!continuing) {
                        close()
                        val sink = newTrack(clip.rate, clip.callAudio).also { it.play() }
                        val playback = PcmPlayback(sink, PcmEchoReference(clip.rate), clip.id)
                        output = playback
                        track = playback
                        outputRate = clip.rate
                        outputCall = clip.callAudio
                    }
                    val playback = output!!
                    playback.id = clip.id
                    val sink = playback.output
                    // A piece after another one starts with its own silence: most of it is left out.
                    var lead: ByteArray? = if (continuing) ByteArray(0) else null
                    val keep = (clip.rate * 2 * LEAD_MS / 1000) and 1.inv()
                    while (first != null && alive()) {
                        var data: ByteArray = first
                        if (lead != null) {
                            val at = loud(data)
                            if (at < 0) {
                                lead = tail(lead + data, keep)
                                data = ByteArray(0)
                            } else {
                                data = tail(lead + data.copyOfRange(0, at), keep) + data.copyOfRange(at, data.size)
                                lead = null
                            }
                        }
                        var offset = 0
                        while (offset < data.size && alive()) {
                            val count = minOf(8192, data.size - offset)
                            // Never blocking: a stop pauses the track, and a write must not wait on it.
                            val done = sink.write(data, offset, count, AudioTrack.WRITE_NON_BLOCKING)
                            if (done < 0) break
                            if (done == 0) Thread.sleep(10) else playback.reference.append(data, offset, done)
                            offset += done
                            written += done / 2
                        }
                        first = clip.next(alive)
                    }
                } else if (clip.file != null && alive()) {
                    close()
                    playFile(clip.file!!, clip.callAudio, alive)
                }
                clip.file?.delete()
                // The sound is let out to its end before a page hears the end, and before the track goes.
                val more = synchronized(state) { clips.any { !it.started && it !== clip } }
                val ends = clip.pieces.any { it.closes && it.id.isNotEmpty() }
                if (output != null && alive() && (ends || !more)) drain(output!!.output, written, alive)
                if (!alive()) runCatching { output?.output?.let { it.pause(); it.flush() } }
                if (!more || !alive()) close()
                if (alive()) clip.pieces.filter { it.closes && it.id.isNotEmpty() }.forEach { told(it.id, "end") }
            } catch (error: Exception) {
                close()
                if (alive()) failure(error)
            } finally {
                synchronized(state) {
                    clips.remove(clip)
                    state.notifyAll()
                }
            }
        }
    }

    /** Where the first sound above [QUIET] begins, in bytes; -1 when all of it is silence. */
    private fun loud(bytes: ByteArray): Int {
        var index = 0
        while (index + 1 < bytes.size) {
            val sample = ((bytes[index].toInt() and 0xFF) or (bytes[index + 1].toInt() shl 8)).toShort().toInt()
            if (sample > QUIET || sample < -QUIET) return index
            index += 2
        }
        return -1
    }

    private fun tail(bytes: ByteArray, count: Int): ByteArray =
        if (bytes.size <= count) bytes else bytes.copyOfRange(bytes.size - count, bytes.size)

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

    private fun attributes(callAudio: Boolean): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (callAudio) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    private fun newTrack(rate: Int, callAudio: Boolean): AudioTrack {
        val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(attributes(callAudio))
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setBufferSizeInBytes(maxOf(minimum, rate))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /** Lets what is already written sound out. */
    private fun drain(output: AudioTrack, frames: Long, alive: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 60_000
        while (alive() && output.playbackHeadPosition < frames && System.currentTimeMillis() < deadline) {
            Thread.sleep(40)
        }
    }

    /** Plays an encoded answer (mp3, wav…) from a provider that does not give raw PCM. */
    private fun playFile(file: File, callAudio: Boolean, alive: () -> Boolean) {
        val done = CountDownLatch(1)
        val media = MediaPlayer()
        player = media
        try {
            media.setAudioAttributes(attributes(callAudio))
            media.setDataSource(file.path)
            media.setOnCompletionListener { done.countDown() }
            media.setOnErrorListener { _, _, _ -> done.countDown(); true }
            media.prepare()
            media.start()
            while (done.count > 0 && alive()) done.await(100, TimeUnit.MILLISECONDS)
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
class Speaker(private val output: (String) -> Unit = { Tts.say(it) }) {
    private val buffer = StringBuilder()
    internal var queuedSpeech = false
        private set

    private fun speak(text: String) {
        output(text)
        queuedSpeech = true
    }

    fun feed(piece: String) {
        buffer.append(piece)
        // Code is never read aloud: wait until an opened block is closed.
        if (Regex("```").findAll(buffer).count() % 2 == 1) return
        val end = Regex("[.!?…](?=\\s)|\n").findAll(buffer).lastOrNull()?.range?.last ?: return
        if (end + 1 < MIN) return
        speak(buffer.substring(0, end + 1))
        buffer.delete(0, end + 1)
    }

    /** The answer is complete: whatever is left is spoken too. */
    fun finish() {
        if (buffer.isNotBlank()) speak(buffer.toString())
        buffer.clear()
    }

    private companion object {
        /** Shorter scraps wait for the next sentence, so the voice does not stutter. */
        const val MIN = 40
    }
}
