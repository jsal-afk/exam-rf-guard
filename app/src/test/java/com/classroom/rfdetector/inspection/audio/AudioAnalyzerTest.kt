package com.classroom.rfdetector.inspection.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioAnalyzerTest {
    private val rate = 48000
    private val size = 2048
    private fun tone(hz: Double, amplitude: Double = 0.2, index: Int = 0) = ShortArray(size) {
        (32767 * amplitude * sin(2 * PI * hz * (index.toLong() * size + it) / rate)).toInt().coerceIn(-32768, 32767).toShort()
    }
    private fun baseline(analyzer: AudioAnalyzer, amplitude: Double = 0.2): AudioAnalysis {
        var result = AudioAnalysis(AudioQuality.BASELINE)
        repeat(71) { result = analyzer.accept(tone(4000.0, amplitude, it)) }
        assertEquals(AudioQuality.READY, result.quality)
        return result
    }

    @Test fun silenceDcClippingAndPartialInvalidateAnExistingBaseline() {
        val analyzer = AudioAnalyzer(rate)
        baseline(analyzer)
        assertEquals(AudioQuality.SILENCE, analyzer.accept(ShortArray(size)).quality)
        assertEquals(AudioQuality.SILENCE, analyzer.accept(ShortArray(size) { 1000 }).quality)
        assertEquals(AudioQuality.CLIPPED, analyzer.accept(ShortArray(size) { Short.MAX_VALUE }).quality)
        assertEquals(AudioQuality.PARTIAL, analyzer.accept(tone(1000.0), size - 1).quality)
        assertEquals(AudioQuality.BASELINE, analyzer.accept(tone(1000.0)).quality)
    }

    @Test fun bandChangesAreNormalizedAndNyquistIsNotHighBand() {
        val analyzer = AudioAnalyzer(rate)
        baseline(analyzer)
        val scaled = analyzer.accept(tone(4000.0, 0.7))
        assertFalse(scaled.lowChange); assertFalse(scaled.highChange)
        val low = analyzer.accept(tone(500.0))
        assertTrue(low.lowChange); assertFalse(low.highChange)
        val high = analyzer.accept(tone(19500.0))
        assertTrue(high.highChange); assertFalse(high.lowChange)
        val edge = analyzer.accept(tone(23900.0))
        assertFalse(edge.highChange); assertFalse(edge.lowChange)
        assertTrue(edge.columns.all { column -> column.all { it.isFinite() && it in 0f..1f } })
    }

    @Test fun repeatingEnvelopeRequiresSeveralSecondsAndRejectsFixedHum() {
        val analyzer = AudioAnalyzer(rate)
        baseline(analyzer)
        var result = analyzer.accept(tone(4000.0))
        repeat(90) { result = analyzer.accept(tone(4000.0, 0.2, it)) }
        assertFalse(result.repeating)
        analyzer.reset(); baseline(analyzer)
        repeat(110) { result = analyzer.accept(tone(4000.0, 0.08 + 0.32 * it / 109, it)) }
        assertFalse(result.repeating)
        analyzer.reset(); baseline(analyzer)
        repeat(110) { result = analyzer.accept(tone(4000.0, if (it < 55) 0.08 else 0.4, it)) }
        assertFalse(result.repeating)
        analyzer.reset(); baseline(analyzer)
        repeat(110) { result = analyzer.accept(tone(4000.0, if ((it / 8) % 2 == 0) 0.08 else 0.4, it)) }
        assertTrue(result.repeating)
        assertTrue(result.columns.size <= 120)
    }

    @Test fun contaminatedBaselineIsRejectedAndResetStartsFresh() {
        val analyzer = AudioAnalyzer(rate)
        var result = AudioAnalysis(AudioQuality.BASELINE)
        repeat(71) { result = analyzer.accept(tone(if (it < 35) 4000.0 else 500.0, index = it)) }
        assertEquals(AudioQuality.UNSTABLE, result.quality)
        assertEquals(0L, result.validSamples)
        baseline(analyzer)
        analyzer.reset()
        val fresh = analyzer.accept(tone(4000.0))
        assertEquals(AudioQuality.BASELINE, fresh.quality)
        assertEquals(size.toLong(), fresh.validSamples)
        assertEquals(1, fresh.columns.size)
    }
}
