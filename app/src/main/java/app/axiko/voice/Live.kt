package app.axiko.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.SystemClock
import app.axiko.App
import app.axiko.agent.Agent
import app.axiko.agent.Conversation
import app.axiko.core.Pages
import com.k2fsa.sherpa.onnx.Vad
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * A live conversation: the microphone stays open, every phrase the owner says goes to the agent,
 * the answer is spoken, and the owner can speak over it. The in-app chat and assistant panel
 * share the audio pipeline and decide separately how to handle the next phrase.
 *
 * The in-app call uses Android's communication audio path for echo cancellation. The system
 * assistant keeps the current system route while recording recognition audio. PCM playback
 * supplies a speaker reference to distinguish a nearby voice from echo. Playback without
 * a reference can still be interrupted from the panel controls.
 *
 * What is still left of the echo is kept from counting as the owner: speaking over the answer
 * means a stretch of sound well above what the answer itself leaves in the microphone, and a
 * phrase made only of the answer's own echo is thrown away, not sent to the agent.
 */
object Live {
    private const val RATE = 16000
    private const val WINDOW = 512

    /** Windows (32 ms each) of loud speech in a row, give or take, that make an interruption. */
    private const val OVER_WINDOWS = 9

    /** Sound quieter than this while the answer plays is what is left of the answer itself. */
    private const val OVER_LEVEL = 0.03

    /** …and so is sound less than this many times louder than the echo has been on average. */
    private const val OVER_ECHO = 3.0

    private const val SPEAKER_ECHO_TAIL_MS = 220L

    /** A conversation nobody continues ends by itself. */
    private const val IDLE_MS = 90_000L

    private class Panel(
        val owner: String,
        val onPhrase: (String) -> Unit,
        val onState: (JSONObject) -> Unit,
        val onInterrupt: () -> Unit,
        @Volatile var conversationIdleMs: Long?,
    )

    private class Session(val chat: Conversation, val panel: Panel?) {
        @Volatile var state = "starting"
        var lastLevelAt = 0L
    }

    private val sessions = Any()
    @Volatile private var current: Session? = null
    @Volatile private var openingOrRecording = false

    val active: Boolean get() = current != null
    internal val holdsMicrophone: Boolean get() = active || openingOrRecording

    // A new session waits for the previous worker to release its recorder and restore routing.
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "axiko-live").apply { isDaemon = true }
    }
    private val cancellation = Executors.newSingleThreadExecutor { task ->
        Thread(task, "axiko-live-stop").apply { isDaemon = true }
    }
    private val microphone = Any()
    private var recorder: Pair<Session, AudioRecord>? = null // guarded by microphone

    /** For checks: how often the answer was cut by a voice, and how many phrases were thrown away as echo. */
    @Volatile
    var interruptions = 0
        private set

    @Volatile
    var echoes = 0
        private set

    fun status(): JSONObject {
        val session = current?.takeIf { it.panel == null }
        return JSONObject().put("active", session != null).put("state", session?.state ?: "off")
            .put("interruptions", interruptions).put("echoes", echoes)
    }

    private fun publish(session: Session, next: String, level: Double = 0.0, error: String? = null) {
        val activeSession = current
        if (next != "off" && activeSession !== session) return
        if (next == "off" && activeSession != null && activeSession !== session &&
            activeSession.panel?.owner == session.panel?.owner) return
        val event = JSONObject().put("active", next != "off").put("state", next).put("level", level)
        if (error != null) event.put("error", error)
        val panel = session.panel
        if (panel != null) runCatching {
            event.put("conversation", next != "off" && panel.conversationIdleMs != null)
            panel.onState(event)
        }
        else Pages.broadcast("live", event.toString())
    }

    private fun tell(session: Session, next: String, level: Double? = null) {
        if (current !== session) return
        val now = SystemClock.elapsedRealtime()
        if (next == session.state && (level == null || now - session.lastLevelAt < 64)) return
        session.state = next
        if (level != null) session.lastLevelAt = now
        publish(session, next, level ?: 0.0)
    }

    fun start(agent: String, thread: String?): Boolean {
        current?.let {
            check(it.panel == null) { "Микрофон занят системным ассистентом" }
            return true
        }
        checkAvailable()
        if (!Permissions.ensure(Manifest.permission.RECORD_AUDIO)) error("Нет доступа к микрофону")
        return begin(Session(Agent.chat(agent, thread), null))
    }

    /** The panel already owns its conversation, which may be temporary. */
    fun startAssistant(
        chat: Conversation,
        owner: String,
        onPhrase: (String) -> Unit,
        onState: (JSONObject) -> Unit,
        onInterrupt: () -> Unit,
        conversationIdleMs: Long? = null,
    ): Boolean {
        require(conversationIdleMs == null || conversationIdleMs >= 0)
        checkAvailable()
        if (App.instance.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            error("Нет доступа к микрофону. Разрешите его в настройках Axiko.")
        }
        return begin(Session(chat, Panel(owner, onPhrase, onState, onInterrupt, conversationIdleMs)))
    }

    /** Promote a listening panel to a conversation without discarding speech in its recorder. */
    fun setAssistantConversation(owner: String, conversationIdleMs: Long?): Boolean {
        require(conversationIdleMs == null || conversationIdleMs >= 0)
        val session = synchronized(sessions) {
            val active = current ?: return false
            val panel = active.panel?.takeIf { it.owner == owner } ?: return false
            panel.conversationIdleMs = conversationIdleMs
            active
        }
        publish(session, session.state)
        return true
    }

    private fun checkAvailable() {
        if (Stream.active || Stt.listening) error("Микрофон уже используется")
        if (!Stt.ready) error("Модель распознавания речи не скачана. Откройте «Настройки», вкладка «Голос».")
    }

    private fun begin(session: Session): Boolean = synchronized(sessions) {
        val previous = current
        if (previous != null) {
            check(previous.panel?.owner == session.panel?.owner) { "Микрофон уже используется" }
            return@synchronized true
        }
        checkAvailable()
        current = session
        worker.execute {
            if (current !== session) return@execute
            var failure: String? = null
            openingOrRecording = true
            try {
                Wake.yieldMicrophone()
                if (current === session) {
                    publish(session, "starting")
                    talk(session)
                }
            } catch (error: Exception) {
                failure = error.message ?: "Не удалось открыть микрофон"
            } finally {
                openingOrRecording = false
                val ended = synchronized(sessions) {
                    if (current === session) {
                        current = null
                        true
                    } else false
                }
                if (ended) publish(session, "off", error = failure)
            }
        }
        true
    }

    /** Closing the ordinary chat's microphone never affects an assistant session. */
    fun stop() {
        stopOwned(null)
    }

    fun stopAssistant(owner: String) {
        stopOwned(owner)
    }

    private fun stopOwned(owner: String?) {
        val session = synchronized(sessions) {
            val found = current ?: return
            if (found.panel?.owner != owner) return
            current = null
            found
        }
        cancellation.execute {
            synchronized(microphone) {
                recorder?.takeIf { it.first === session }?.let { runCatching { it.second.stop() } }
            }
        }
        if (owner == null) Tts.stop()
        publish(session, "off")
    }

    @SuppressLint("MissingPermission")
    private fun talk(session: Session) {
        val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Формат микрофона недоступен: $minimum" }
        val detector = Stt.vad(0.6f)
        try {
            recordVoice(session, detector, minimum)
        } finally {
            detector.release()
        }
    }

    @SuppressLint("MissingPermission")
    private fun recordVoice(session: Session, detector: Vad, minimum: Int) {
        val chat = session.chat
        val panel = session.panel
        val panelSpeech = panel?.let { "assistant:${it.owner}" }
        val audio = App.instance.getSystemService(AudioManager::class.java)
        val callAudio = panel == null
        val modeBefore = audio.mode
        @Suppress("DEPRECATION")
        val speakerBefore = callAudio && Build.VERSION.SDK_INT < 31 && audio.isSpeakerphoneOn
        val source = if (callAudio) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val record = AudioRecord(
            source, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, RATE * 4),
        )
        var echo: AcousticEchoCanceler? = null
        var noise: NoiseSuppressor? = null
        var routed = false
        var selectedSpeaker = false
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "Не удалось открыть микрофон." }
            if (current !== session) return
            echo = runCatching {
                if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } else null
            }.getOrNull()
            noise = runCatching {
                if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } else null
            }.getOrNull()
            if (callAudio) {
                // Keep the regular chat's existing call routing and acoustic echo reference.
                routed = true
                runCatching {
                    audio.mode = AudioManager.MODE_IN_COMMUNICATION
                    if (Build.VERSION.SDK_INT >= 31) {
                        val devices = audio.availableCommunicationDevices
                        val headset = devices.any { it.type in HEADSETS }
                        if (!headset) devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let {
                            selectedSpeaker = audio.setCommunicationDevice(it)
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        audio.isSpeakerphoneOn = true
                    }
                }
                Tts.conversation = true
            }
            synchronized(microphone) {
                if (current !== session) return
                recorder = session to record
                record.startRecording()
            }
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Микрофон не начал запись." }
            tell(session, "listening")
            val buffer = ShortArray(WINDOW)
            var filled = 0
            var emptyReads = 0
            var lastWord = SystemClock.elapsedRealtime()
            // How loud the answer's own echo has been, on average, while it plays.
            var residue = 0.0
            var loud = 0
            // What the phrase being heard is made of: the owner's voice, or only the answer's echo.
            var ownerSpoke = false
            var echoSpoke = false
            var interrupted = false
            val speakerEcho = SpeakerEchoGuard()
            val overlap = SpeakerOverlap()
            val silence = FloatArray(WINDOW)
            var speakerSource: Any? = null
            var speakerDevice: Int? = null
            var interruptedSource: Any? = null
            var suppressingSpeaker = false
            var speakerEchoUntil = 0L
            var idleConfiguration = panel?.conversationIdleMs
            while (current === session) {
                val count = record.read(buffer, filled, WINDOW - filled)
                if (current !== session) break
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
                var energy = 0.0
                val window = FloatArray(WINDOW) {
                    val sample = buffer[it] / 32768f
                    energy += sample * sample
                    sample
                }
                val level = sqrt(energy / WINDOW)
                val now = SystemClock.elapsedRealtime()
                val answering = Tts.speaking
                val speakerOutput = if (panel != null && answering) Tts.roomPlayback() else null
                val activeSpeaker = speakerOutput?.takeUnless { it.source === interruptedSource }
                if (activeSpeaker != null) speakerEchoUntil = now + SPEAKER_ECHO_TAIL_MS
                if (now < speakerEchoUntil) {
                    if (!suppressingSpeaker || (activeSpeaker != null && activeSpeaker.source !== speakerSource)) {
                        detector.reset()
                        ownerSpoke = false
                        echoSpoke = false
                        interrupted = false
                        residue = 0.0
                        loud = 0
                        overlap.reset()
                        speakerSource = activeSpeaker?.source
                        suppressingSpeaker = true
                    }
                    if (activeSpeaker != null && activeSpeaker.deviceId != speakerDevice) {
                        speakerEcho.reset()
                        speakerDevice = activeSpeaker.deviceId
                    }
                    lastWord = now
                    val ownerWindow = activeSpeaker != null && activeSpeaker.id == panelSpeech &&
                        speakerEcho.hearsOwner(activeSpeaker.match(window))
                    val pendingOverlap = overlap.pending
                    val sustained = overlap.record(ownerWindow)
                    if (ownerWindow) {
                        detector.acceptWaveform(window)
                        if (sustained && detector.isSpeechDetected()) {
                            interruptedSource = activeSpeaker.source
                            speakerEchoUntil = 0L
                            suppressingSpeaker = false
                            ownerSpoke = true
                            interrupted = true
                            panel.onInterrupt()
                            interruptions++
                            tell(session, "hearing", level)
                            continue
                        }
                    } else if (pendingOverlap) {
                        if (overlap.pending) detector.acceptWaveform(silence) else detector.reset()
                    }
                    tell(session, "speaking")
                    continue
                }
                if (suppressingSpeaker) {
                    // An unconfirmed fragment during playback cannot become a new request.
                    detector.reset()
                    suppressingSpeaker = false
                }
                detector.acceptWaveform(window)
                val speech = detector.isSpeechDetected()
                if (answering) {
                    // Over the answer only a voice well above the answer's own echo counts, and only
                    // when it lasts: the echo has peaks of its own.
                    val over = speech && level > maxOf(OVER_LEVEL, residue * OVER_ECHO)
                    if (!over) residue = if (residue == 0.0) level else residue * 0.97 + level * 0.03
                    loud = if (over) loud + 1 else maxOf(0, loud - 2)
                    if (speech) echoSpoke = true
                    if (loud >= OVER_WINDOWS) {
                        // The owner speaks over the answer: it falls silent at once, the phrase is heard out.
                        if (panel == null) Tts.stop() else panel.onInterrupt()
                        interruptions++
                        ownerSpoke = true
                        interrupted = panel != null
                        loud = 0
                        lastWord = now
                        tell(session, "hearing", level)
                    } else {
                        tell(session, "speaking")
                    }
                } else {
                    residue = 0.0
                    loud = 0
                    if (speech) {
                        ownerSpoke = true
                        lastWord = now
                        tell(session, "hearing", level)
                    } else {
                        tell(session, if (chat.busy) "thinking" else "listening")
                    }
                }
                while (!detector.empty()) {
                    val samples = detector.front().samples
                    detector.pop()
                    val mine = ownerSpoke || !echoSpoke
                    val minSamples = if (interrupted) RATE / 5 else RATE * 4 / 10
                    ownerSpoke = false
                    echoSpoke = false
                    interrupted = false
                    // What the microphone caught of the answer itself is not the owner's phrase.
                    if (!mine) {
                        echoes++
                        continue
                    }
                    // A confirmed interruption can be a one-word correction.
                    if (samples.size < minSamples) continue
                    tell(session, "thinking")
                    val said = runCatching { Stt.decode(samples) }.getOrDefault("").trim()
                    if (current !== session) break
                    if (said.length < 2) continue
                    lastWord = SystemClock.elapsedRealtime()
                    if (panel != null) {
                        panel.onPhrase(said)
                        continue
                    }
                    Tts.stop()
                    if (chat.busy) {
                        // The agent was still answering the previous phrase: it stops for the new one.
                        chat.stop()
                        val until = SystemClock.elapsedRealtime() + 4000
                        while (current === session && chat.busy && SystemClock.elapsedRealtime() < until) Thread.sleep(80)
                    }
                    if (current !== session) break
                    Tts.voiceTurn = true
                    chat.send(said)
                }
                val configured = panel?.conversationIdleMs
                if (configured != idleConfiguration || answering || (chat.busy && (panel == null || configured != null))) {
                    lastWord = now
                }
                idleConfiguration = configured
                val idleMs = configured ?: IDLE_MS
                if (idleMs > 0 && now - lastWord > idleMs) break
            }
        } finally {
            synchronized(microphone) {
                if (recorder?.second === record) {
                    recorder = null
                    runCatching { record.stop() }
                }
                runCatching { record.release() }
            }
            runCatching { echo?.release() }
            runCatching { noise?.release() }
            if (routed) {
                Tts.conversation = false
                runCatching {
                    if (Build.VERSION.SDK_INT >= 31) {
                        if (selectedSpeaker) audio.clearCommunicationDevice()
                    } else {
                        @Suppress("DEPRECATION")
                        audio.isSpeakerphoneOn = speakerBefore
                    }
                }
                runCatching { audio.mode = modeBefore }
            }
        }
    }

    private val HEADSETS = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
    )
}
