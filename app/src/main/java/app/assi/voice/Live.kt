package app.assi.voice

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.media.audiofx.NoiseSuppressor
import app.assi.App
import app.assi.agent.Agent
import app.assi.core.Pages
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * A live conversation: the microphone stays open, every phrase the owner says goes to the agent,
 * the answer is spoken — and the owner can speak over it. The moment they do, the voice falls
 * silent and the agent drops what it was saying to hear the new phrase out.
 *
 * For the phone not to hear itself, the whole conversation is held the way a call is: the audio
 * is switched to the communication mode, the microphone is the call's microphone and the voice
 * sounds as the other party of a call — only then does the phone take its own voice out of what
 * it hears (checked on a Galaxy Fold: played as ordinary media, the voice came back through the
 * microphone as speech even at the lowest volume, and the assistant cut itself short).
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

    /** A conversation nobody continues ends by itself. */
    private const val IDLE_MS = 90_000L

    @Volatile
    private var generation = 0

    @Volatile
    var active = false
        private set

    @Volatile
    private var state = "off"

    /** For checks: how often the answer was cut by a voice, and how many phrases were thrown away as echo. */
    @Volatile
    var interruptions = 0
        private set

    @Volatile
    var echoes = 0
        private set

    fun status(): JSONObject = JSONObject().put("active", active).put("state", state)
        .put("interruptions", interruptions).put("echoes", echoes)

    private fun tell(next: String, level: Double? = null) {
        if (next == state && level == null) return
        state = next
        Pages.broadcast("live", JSONObject().put("state", next).put("level", level ?: 0.0).toString())
    }

    fun start(agent: String, thread: String?): Boolean {
        if (active) return true
        if (Stream.active) error("Уже слушаю")
        if (!Stt.ready) error("Модель распознавания речи не скачана. Откройте «Настройки», вкладка «Голос».")
        if (!Permissions.ensure(Manifest.permission.RECORD_AUDIO)) error("Нет доступа к микрофону")
        active = true
        val ticket = ++generation
        Thread({
            try {
                talk(ticket, agent, thread)
            } catch (failure: Exception) {
                Pages.broadcast("live", JSONObject().put("state", "off").put("error", failure.message ?: failure.toString()).toString())
            } finally {
                if (ticket == generation) {
                    active = false
                    tell("off")
                }
            }
        }, "assi-live").apply { isDaemon = true }.start()
        return true
    }

    fun stop() {
        generation++
        active = false
        Tts.stop()
        tell("off")
    }

    @SuppressLint("MissingPermission")
    private fun talk(ticket: Int, agent: String, thread: String?) {
        val chat = Agent.chat(agent, thread)
        val detector = Stt.vad(0.6f)
        val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, RATE * 4),
        )
        val echo = runCatching { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } else null }.getOrNull()
        val noise = runCatching { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } else null }.getOrNull()
        // The audio as in a call, the loudspeaker as its "earpiece" — unless a headset is in use.
        val audio = App.instance.getSystemService(AudioManager::class.java)
        val modeBefore = audio.mode
        runCatching {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                val devices = audio.availableCommunicationDevices
                val headset = devices.any { it.type in HEADSETS }
                if (!headset) devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { audio.setCommunicationDevice(it) }
            } else {
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = true
            }
        }
        Tts.conversation = true
        try {
            record.startRecording()
            tell("listening")
            val buffer = ShortArray(WINDOW)
            var lastWord = System.currentTimeMillis()
            // How loud the answer's own echo has been, on average, while it plays.
            var residue = 0.0
            var loud = 0
            // What the phrase being heard is made of: the owner's voice, or only the answer's echo.
            var ownerSpoke = false
            var echoSpoke = false
            while (ticket == generation) {
                if (record.read(buffer, 0, WINDOW) < WINDOW) continue
                var energy = 0.0
                val window = FloatArray(WINDOW) {
                    val sample = buffer[it] / 32768f
                    energy += sample * sample
                    sample
                }
                val level = sqrt(energy / WINDOW)
                detector.acceptWaveform(window)
                val now = System.currentTimeMillis()
                val answering = Tts.speaking
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
                        Tts.stop()
                        interruptions++
                        ownerSpoke = true
                        loud = 0
                        lastWord = now
                        tell("hearing", level)
                    } else {
                        tell("speaking")
                    }
                } else {
                    residue = 0.0
                    loud = 0
                    if (speech) {
                        ownerSpoke = true
                        lastWord = now
                        tell("hearing", level)
                    } else {
                        tell(if (chat.busy) "thinking" else "listening")
                    }
                }
                while (!detector.empty()) {
                    val samples = detector.front().samples
                    detector.pop()
                    val mine = ownerSpoke || !echoSpoke
                    ownerSpoke = false
                    echoSpoke = false
                    // What the microphone caught of the answer itself is not the owner's phrase.
                    if (!mine) {
                        echoes++
                        continue
                    }
                    // A cough or a knock is not a phrase.
                    if (samples.size < RATE * 4 / 10) continue
                    tell("thinking")
                    val said = runCatching { Stt.decode(samples) }.getOrDefault("").trim()
                    if (said.length < 2) continue
                    lastWord = System.currentTimeMillis()
                    Tts.stop()
                    if (chat.busy) {
                        // The agent was still answering the previous phrase: it stops for the new one.
                        chat.stop()
                        val until = System.currentTimeMillis() + 4000
                        while (chat.busy && System.currentTimeMillis() < until) Thread.sleep(80)
                    }
                    Tts.voiceTurn = true
                    chat.send(said)
                }
                if (answering || chat.busy) lastWord = now
                if (now - lastWord > IDLE_MS) break
            }
        } finally {
            Tts.conversation = false
            runCatching {
                if (Build.VERSION.SDK_INT >= 31) {
                    audio.clearCommunicationDevice()
                } else {
                    @Suppress("DEPRECATION")
                    audio.isSpeakerphoneOn = false
                }
            }
            runCatching { audio.mode = modeBefore }
            runCatching { echo?.release() }
            runCatching { noise?.release() }
            runCatching { record.stop() }
            record.release()
            detector.release()
        }
    }

    private val HEADSETS = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
    )
}
