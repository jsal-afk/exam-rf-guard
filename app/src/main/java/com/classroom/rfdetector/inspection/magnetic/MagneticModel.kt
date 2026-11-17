package com.classroom.rfdetector.inspection.magnetic

import kotlin.math.abs
import kotlin.math.sqrt

internal enum class MagneticPhase { IDLE, BASELINE, MOVING, SMALL, LARGE, RETAKE, STALE }

internal data class MagneticReading(
    val phase: MagneticPhase = MagneticPhase.IDLE,
    val field: Float? = null,
    val delta: Float? = null,
    val history: List<Float> = emptyList(),
)

/** Timestamps use the same monotonic nanosecond clock as SensorEvent.timestamp. */
internal class MagneticModel(private val maxRange: Float, private val resolution: Float) {
    var reading = MagneticReading()
        private set
    private val baselineSamples = mutableListOf<Float>()
    private var baselineStart = 0L
    private var baseline: Float? = null
    private var acceleration: FloatArray? = null
    private var accelerationTime = 0L
    private var fieldTime = 0L
    private var lastMotionTime = 0L
    private var startedAt = 0L

    fun start(now: Long = 0L) {
        baseline = null
        baselineSamples.clear()
        baselineStart = 0L
        acceleration = null
        accelerationTime = 0L
        fieldTime = 0L
        lastMotionTime = 0L
        startedAt = now
        reading = MagneticReading(MagneticPhase.BASELINE)
    }

    fun stop() {
        start()
        reading = MagneticReading()
    }

    fun acceleration(x: Float, y: Float, z: Float, time: Long) {
        if (reading.phase == MagneticPhase.IDLE || reading.phase == MagneticPhase.RETAKE || reading.phase == MagneticPhase.STALE) return
        if (!x.isFinite() || !y.isFinite() || !z.isFinite() || time <= accelerationTime) {
            invalidate(MagneticPhase.RETAKE)
            acceleration = null
            accelerationTime = 0L
            return
        }
        if (accelerationTime != 0L && time - accelerationTime > STALE_NS) {
            invalidate(MagneticPhase.STALE)
            return
        }
        val previous = acceleration
        val moved = previous != null && vectorMagnitude(x - previous[0], y - previous[1], z - previous[2]) > 0.45f
        if (previous == null || moved) acceleration = floatArrayOf(x, y, z)
        accelerationTime = time
        if (moved) {
            lastMotionTime = time
            if (baseline != null) reading = reading.copy(phase = MagneticPhase.MOVING, field = null, delta = null) else clearBaselineWindow()
        }
    }

    fun field(x: Float, y: Float, z: Float, accuracy: Int, time: Long) {
        if (reading.phase == MagneticPhase.IDLE || reading.phase == MagneticPhase.RETAKE || reading.phase == MagneticPhase.STALE) return
        if (time <= fieldTime || !x.isFinite() || !y.isFinite() || !z.isFinite() ||
            !maxRange.isFinite() || maxRange <= 0f ||
            abs(x) >= maxRange || abs(y) >= maxRange || abs(z) >= maxRange || accuracy < 2
        ) {
            invalidate(MagneticPhase.RETAKE)
            return
        }
        if (fieldTime != 0L && time - fieldTime > STALE_NS) {
            invalidate(MagneticPhase.STALE)
            return
        }
        fieldTime = time
        if (accelerationTime == 0L || abs(time - accelerationTime) > STALE_NS) {
            clearBaselineWindow()
            if (baseline != null) invalidate(MagneticPhase.STALE)
            return
        }
        val magnitude = vectorMagnitude(x, y, z)
        if (!magnitude.isFinite()) {
            invalidate(MagneticPhase.RETAKE)
            return
        }
        if (lastMotionTime != 0L && time - lastMotionTime < 1_000_000_000L) {
            clearBaselineWindow()
            return
        }
        val reference = baseline
        if (reference == null) {
            if (baselineSamples.isEmpty()) baselineStart = time
            baselineSamples.add(magnitude)
            if (baselineSamples.size > 256) baselineSamples.removeAt(0)
            if (baselineSamples.size >= 15 && time - baselineStart >= 2_000_000_000L) {
                val sorted = baselineSamples.sorted()
                val median = sorted[sorted.size / 2]
                // Reject unstable field during capture, not just a moving phone.
                if (sorted.last() - sorted.first() > maxOf(3f, usableResolution() * 6f)) {
                    clearBaselineWindow()
                } else {
                    baseline = median
                    baselineSamples.clear()
                    reading = MagneticReading(MagneticPhase.SMALL, magnitude, 0f, listOf(0f))
                }
            }
            return
        }
        val delta = abs(magnitude - reference)
        val history = (reading.history + delta).takeLast(80)
        reading = MagneticReading(if (delta >= maxOf(5f, usableResolution() * 6f)) MagneticPhase.LARGE else MagneticPhase.SMALL, magnitude, delta, history)
    }

    fun tick(now: Long) {
        if (reading.phase == MagneticPhase.IDLE || reading.phase == MagneticPhase.RETAKE || reading.phase == MagneticPhase.STALE) return
        if (now - (if (fieldTime == 0L) startedAt else fieldTime) > STALE_NS ||
            now - (if (accelerationTime == 0L) startedAt else accelerationTime) > STALE_NS) invalidate(MagneticPhase.STALE)
    }

    fun accuracyLost() {
        if (reading.phase != MagneticPhase.IDLE) invalidate(MagneticPhase.RETAKE)
    }

    private fun usableResolution() = if (resolution.isFinite() && resolution > 0f) resolution else 0f
    private fun clearBaselineWindow() { baselineSamples.clear(); baselineStart = 0L }
    private fun invalidate(phase: MagneticPhase) {
        baseline = null
        clearBaselineWindow()
        reading = MagneticReading(phase)
    }

    companion object {
        const val STALE_NS = 750_000_000L
        private fun vectorMagnitude(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)
    }
}
