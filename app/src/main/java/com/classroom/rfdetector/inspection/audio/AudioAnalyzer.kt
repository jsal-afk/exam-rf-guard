package com.classroom.rfdetector.inspection.audio

import org.apache.commons.math3.transform.DftNormalization
import org.apache.commons.math3.transform.FastFourierTransformer
import org.apache.commons.math3.transform.TransformType
import kotlin.math.*

internal enum class AudioQuality { BASELINE, READY, SILENCE, CLIPPED, PARTIAL, UNSTABLE }
internal data class AudioAnalysis(
    val quality: AudioQuality,
    val validSamples: Long = 0,
    val baselineSeconds: Float = 0f,
    val lowChange: Boolean = false,
    val highChange: Boolean = false,
    val repeating: Boolean = false,
    val columns: List<FloatArray> = emptyList(),
)

/** One non-overlapping Hann-windowed frame at a time. Only derived spectra survive a call. */
internal class AudioAnalyzer(private val sampleRate: Int, private val size: Int = 2048) {
    private val fft = Array(2) { DoubleArray(size) }
    private val window = DoubleArray(size) { 0.5 - 0.5 * cos(2 * PI * it / (size - 1)) }
    private val columns = ArrayDeque<FloatArray>()
    private val envelope = ArrayDeque<Double>()
    private val baselineFrames = ceil(3.0 * sampleRate / size).toInt()
    private var count = 0
    private var samples = 0L
    private var sumLow = 0.0
    private var sumHigh = 0.0
    private var minLow = 1.0
    private var maxLow = 0.0
    private var minHigh = 1.0
    private var maxHigh = 0.0
    private var minLevel = Double.POSITIVE_INFINITY
    private var maxLevel = 0.0
    private var referenceLow = 0.0
    private var referenceHigh = 0.0
    private var ready = false

    fun reset() {
        columns.clear(); envelope.clear(); count = 0; samples = 0
        sumLow = 0.0; sumHigh = 0.0; minLevel = Double.POSITIVE_INFINITY; maxLevel = 0.0
        minLow = 1.0; maxLow = 0.0; minHigh = 1.0; maxHigh = 0.0
        referenceLow = 0.0; referenceHigh = 0.0; ready = false
    }

    fun accept(frame: ShortArray, length: Int = frame.size): AudioAnalysis {
        if (length != size || frame.size < size) return invalidate(AudioQuality.PARTIAL)
        var mean = 0.0
        var clipped = 0
        for (i in 0 until size) {
            mean += frame[i].toDouble() / 32768.0
            if (abs(frame[i].toInt()) >= 32760) clipped++
        }
        if (clipped > size / 100) return invalidate(AudioQuality.CLIPPED)
        mean /= size
        var energy = 0.0
        for (i in 0 until size) {
            val x = frame[i] / 32768.0 - mean
            energy += x * x
            fft[0][i] = x * window[i]
            fft[1][i] = 0.0
        }
        val rms = sqrt(energy / size)
        if (rms < 0.0001) return invalidate(AudioQuality.SILENCE)
        FastFourierTransformer.transformInPlace(fft, DftNormalization.STANDARD, TransformType.FORWARD)
        var total = 0.0; var low = 0.0; var high = 0.0
        val bands = DoubleArray(64)
        for (i in 1..size / 2) {
            val hz = i.toDouble() * sampleRate / size
            val power = (fft[0][i] * fft[0][i] + fft[1][i] * fft[1][i]) / (size.toDouble() * size)
            total += power
            if (hz in 80.0..1500.0) low += power
            if (hz >= 18000.0 && hz <= 21000.0) high += power
            val band = ((i - 1) * bands.size / (size / 2)).coerceIn(0, bands.lastIndex)
            bands[band] += power
        }
        val lowFraction = low / max(total, 1e-20)
        val highFraction = high / max(total, 1e-20)
        columns.addLast(FloatArray(bands.size) { ((10 * log10(max(bands[it], 1e-12)) + 100) / 80).coerceIn(0.0, 1.0).toFloat() })
        if (columns.size > 120) columns.removeFirst()
        samples += size
        if (!ready) {
            count++; sumLow += lowFraction; sumHigh += highFraction
            minLow = min(minLow, lowFraction); maxLow = max(maxLow, lowFraction)
            minHigh = min(minHigh, highFraction); maxHigh = max(maxHigh, highFraction)
            minLevel = min(minLevel, rms); maxLevel = max(maxLevel, rms)
            if (count >= baselineFrames) {
                if (maxLevel > minLevel * 3.0 || maxLow - minLow > 0.35 || maxHigh - minHigh > 0.35) return invalidate(AudioQuality.UNSTABLE)
                referenceLow = sumLow / count; referenceHigh = sumHigh / count; ready = true
            }
        } else {
            envelope.addLast(rms)
            val maxFrames = ceil(6.0 * sampleRate / size).toInt()
            if (envelope.size > maxFrames) envelope.removeFirst()
        }
        return AudioAnalysis(
            if (ready) AudioQuality.READY else AudioQuality.BASELINE, samples,
            min(samples.toFloat() / sampleRate, 3f),
            ready && lowFraction > referenceLow + 0.12 && lowFraction > referenceLow * 1.5,
            ready && highFraction > referenceHigh + 0.05 && highFraction > referenceHigh * 1.5,
            ready && recurrence(), columns.toList(),
        )
    }

    private fun invalidate(quality: AudioQuality): AudioAnalysis { reset(); return AudioAnalysis(quality) }

    private fun recurrence(): Boolean {
        if (envelope.size < ceil(3.0 * sampleRate / size).toInt()) return false
        val minimum = envelope.minOrNull() ?: return false
        val maximum = envelope.maxOrNull() ?: return false
        if (maximum < minimum * 1.2) return false
        val low = minimum + (maximum - minimum) * 0.3
        val high = minimum + (maximum - minimum) * 0.7
        val minLag = max(1, (0.2 * sampleRate / size).toInt())
        val maxLag = (2.0 * sampleRate / size).toInt()
        var armed = false
        var previousOnset = -1
        var previousInterval = -1
        envelope.forEachIndexed { index, value ->
            if (value <= low) armed = true
            if (armed && value >= high) {
                armed = false
                if (previousOnset >= 0) {
                    val interval = index - previousOnset
                    if (interval in minLag..maxLag && previousInterval in minLag..maxLag &&
                        abs(interval - previousInterval) <= max(2, previousInterval / 4)) return true
                    previousInterval = interval
                }
                previousOnset = index
            }
        }
        return false
    }
}
