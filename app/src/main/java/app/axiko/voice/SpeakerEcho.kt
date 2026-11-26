package app.axiko.voice

import kotlin.math.abs
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
)

/** Conservative evidence of a nearby voice. VAD and several consecutive windows are still required. */
internal class SpeakerEchoGuard {
    private var calibration = 0
    private var speakerGain = 0.0
    private var residualFloor = 0.0

    fun reset() {
        calibration = 0
        speakerGain = 0.0
        residualFloor = 0.0
    }

    fun hearsOwner(level: Double, match: EchoMatch?): Boolean {
        if (match == null) return false
        // A quiet mic during playback demonstrates echo suppression by the device. A closely
        // correlated window instead lets us estimate the speaker's leakage into the mic.
        val echoOnly = match.similarity >= 0.78 && match.residual < maxOf(0.014, match.echo * 0.42)
        if (match.peakReference >= 0.020 && (level < 0.012 || echoOnly)) {
            val gain = if (echoOnly && match.reference > 0.005) match.echo / match.reference else 0.0
            speakerGain = if (calibration == 0) gain else speakerGain * 0.9 + gain * 0.1
            residualFloor = if (calibration == 0) match.residual else residualFloor * 0.9 + match.residual * 0.1
            calibration = (calibration + 1).coerceAtMost(4)
        }
        if (calibration < 4 || echoOnly) return false
        val expectedEcho = maxOf(match.echo, speakerGain * match.peakReference)
        return level >= 0.035 && match.residual > maxOf(0.022, residualFloor * 2.5, expectedEcho * 0.68)
    }
}
