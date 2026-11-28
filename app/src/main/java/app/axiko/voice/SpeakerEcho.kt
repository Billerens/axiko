package app.axiko.voice

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/** A short reference of PCM actually queued to the speaker, indexed by AudioTrack frame. */
internal class PcmEchoReference(private val sampleRate: Int) {
    private val samples = FloatArray(REFERENCE_RATE)
    private var size = 0L
    private var phase = 0
    private var sum = 0
    private var groupSize = 0

    @Synchronized
    fun append(pcm: ByteArray, offset: Int, length: Int) {
        var at = offset
        val end = offset + length
        while (at + 1 < end) {
            sum += ((pcm[at].toInt() and 255) or (pcm[at + 1].toInt() shl 8)).toShort().toInt()
            groupSize++
            phase += REFERENCE_RATE
            if (phase >= sampleRate) {
                samples[(size % samples.size).toInt()] = sum / (groupSize * 32768f)
                size++
                phase -= sampleRate
                sum = 0
                groupSize = 0
            }
            at += 2
        }
    }

    /** Android's playback head keeps queued future audio out of the reference window. */
    @Synchronized
    private fun around(renderedFrames: Long): FloatArray {
        val rendered = renderedFrames * REFERENCE_RATE / sampleRate
        val end = minOf(size, rendered)
        val start = maxOf(size - samples.size, 0L, rendered - REFERENCE_RATE * 320 / 1000)
        return FloatArray((end - start).coerceAtLeast(0).toInt()) { index ->
            samples[((start + index) % samples.size).toInt()]
        }
    }

    fun match(window: FloatArray, renderedFrames: Long): EchoMatch? {
        val microphone = FloatArray(window.size / CAPTURE_GROUP) { index ->
            var total = 0f
            val start = index * CAPTURE_GROUP
            for (at in start until start + CAPTURE_GROUP) total += window[at]
            total / CAPTURE_GROUP
        }
        val reference = around(renderedFrames)
        if (microphone.size < 2 || reference.size < microphone.size) return null

        val mean = microphone.average()
        var microphonePower = 0.0
        for (index in microphone.indices) {
            val value = (microphone[index] - mean).toFloat()
            microphone[index] = value
            microphonePower += value * value
        }
        var bestSimilarity = 0.0
        var bestPower = 0.0
        var bestOffset = 0
        var peakPower = 0.0
        var referenceSum = 0.0
        var referenceSquare = 0.0
        for (index in microphone.indices) {
            val value = reference[index]
            referenceSum += value
            referenceSquare += value * value
        }
        for (offset in 0..reference.size - microphone.size) {
            if (offset > 0) {
                val first = reference[offset - 1]
                val last = reference[offset + microphone.lastIndex]
                referenceSum += last - first
                referenceSquare += last * last - first * first
            }
            val power = (referenceSquare - referenceSum * referenceSum / microphone.size).coerceAtLeast(0.0)
            peakPower = maxOf(peakPower, power)
            if (power < 0.0001 || microphonePower < 0.0001) continue
            var dot = 0.0
            for (index in microphone.indices) dot += reference[offset + index] * microphone[index]
            val similarity = abs(dot) / sqrt(power * microphonePower)
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity.coerceAtMost(1.0)
                bestPower = power
                bestOffset = offset
            }
        }
        val microphoneLevel = sqrt(microphonePower / microphone.size)
        val echo = bestSimilarity * microphoneLevel
        return EchoMatch(
            similarity = bestSimilarity,
            residual = sqrt((microphoneLevel * microphoneLevel - echo * echo).coerceAtLeast(0.0)),
            echo = echo,
            reference = sqrt(bestPower / microphone.size),
            peakReference = sqrt(peakPower / microphone.size),
            delaySamples = reference.size - bestOffset - microphone.size,
        )
    }

    private companion object {
        const val REFERENCE_RATE = 2_000
        const val CAPTURE_GROUP = 16_000 / REFERENCE_RATE
    }
}

internal data class EchoMatch(
    val similarity: Double,
    val residual: Double,
    val echo: Double,
    val reference: Double,
    val peakReference: Double,
    val delaySamples: Int,
)

/** Learn the room's reflected echo from repeated matches at approximately the same delay. */
internal class SpeakerEchoGuard {
    private var calibration = 0
    private var delay: Int? = null
    private var reflection = 0.0
    private var noise = 0.0

    fun reset() {
        calibration = 0
        delay = null
        reflection = 0.0
        noise = 0.0
    }

    fun hearsOwner(match: EchoMatch?): Boolean {
        if (match == null) return false
        val level = hypot(match.echo, match.residual)
        val quiet = level < 0.018
        if (match.peakReference < 0.012) {
            if (quiet) learnNoise(level)
            return !quiet && calibration >= 4 && match.residual >= maxOf(0.018, noise * 2.4)
        }

        val aligned = match.reference >= 0.010 && match.similarity >= 0.57 &&
            (delay?.let { abs(it - match.delaySamples) <= 36 } != false || match.similarity >= 0.88)
        val cleanEcho = match.similarity >= 0.88 && match.residual <= maxOf(0.016, match.echo * 0.55)
        val farEnd = maxOf(0.52, reflection * 1.25) * match.echo
        val independent = !quiet && !cleanEcho && match.residual > maxOf(0.017, noise * 2.4, farEnd)
        if (quiet || aligned && (calibration < 4 || !independent)) {
            if (quiet) {
                learnNoise(level)
                return false
            } else {
                val ratio = match.residual / match.echo
                reflection = if (delay == null) ratio else reflection * 0.9 + ratio * 0.1
                delay = match.delaySamples
            }
            calibration = (calibration + 1).coerceAtMost(4)
            return false
        }
        return calibration >= 4 && independent
    }

    private fun learnNoise(level: Double) {
        noise = if (noise == 0.0) level else noise * 0.9 + level * 0.1
        calibration = (calibration + 1).coerceAtMost(4)
    }
}

/** Evidence accumulates within 384 ms so short gaps between syllables do not discard the onset. */
internal class SpeakerOverlap {
    private var windows = 0
    val pending: Boolean get() = windows != 0

    fun record(owner: Boolean): Boolean {
        windows = ((windows shl 1) or (if (owner) 1 else 0)) and 0xfff
        return Integer.bitCount(windows) >= 7
    }

    fun reset() { windows = 0 }
}

/** Only confirmed foreground speech can survive the transition out of the speaker's echo tail. */
internal class SpeakerCapture {
    enum class Window { DROP, RESET, SILENCE, FOREGROUND, INTERRUPTION }

    private val echo = SpeakerEchoGuard()
    private val overlap = SpeakerOverlap()
    private var source: Any? = null
    private var device: Int? = null
    var confirmed = false
        private set

    fun enter(output: Any, deviceId: Int?): Boolean {
        if (source === output && device == deviceId) return false
        source = output
        device = deviceId
        confirmed = false
        echo.reset()
        overlap.reset()
        return true
    }

    fun record(mayInterrupt: Boolean, match: () -> EchoMatch?): Window {
        val owner = (mayInterrupt || confirmed) && echo.hearsOwner(match())
        if (confirmed) return if (owner) Window.FOREGROUND else Window.SILENCE

        val pending = overlap.pending
        val sustained = overlap.record(owner)
        return when {
            sustained && owner -> Window.INTERRUPTION
            owner -> Window.FOREGROUND
            overlap.pending -> Window.SILENCE
            pending -> Window.RESET
            else -> Window.DROP
        }
    }

    fun confirm() { confirmed = true }

    fun leave(): Boolean {
        if (source == null) return false
        source = null
        val discard = !confirmed
        confirmed = false
        return discard
    }
}
