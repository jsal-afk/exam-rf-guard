package com.classroom.rfdetector.inspection.magnetic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MagneticModelTest {
    private fun sample(model: MagneticModel, time: Long, field: Float = 40f, ax: Float = 0f) {
        model.acceleration(ax, 0f, 9.8f, time)
        model.field(field, 0f, 0f, 3, time)
    }
    private fun settle(model: MagneticModel, start: Long = 1_000_000_000L): Long {
        repeat(22) { sample(model, start + it * 100_000_000L) }
        return start + 2_100_000_000L
    }

    @Test fun stableBaselineMagnitudeAndOrientation() {
        val model = MagneticModel(1000f, 0.1f)
        model.start()
        var t = settle(model)
        assertEquals(MagneticPhase.SMALL, model.reading.phase)
        t += 100_000_000L
        model.acceleration(0f, 0f, 9.8f, t)
        model.field(0f, 40f, 0f, 3, t)
        assertEquals(MagneticPhase.SMALL, model.reading.phase)
        assertEquals(0f, model.reading.delta!!, 0.001f)
        sample(model, t + 100_000_000L, field = 50f)
        assertEquals(MagneticPhase.LARGE, model.reading.phase)
        assertEquals(10f, model.reading.delta!!, 0.001f)
        assertTrue(model.reading.history.size >= 3)
    }

    @Test fun movementPreventsBaselineAndAfterwardPausesThenResumes() {
        val model = MagneticModel(1000f, 0.1f)
        model.start()
        repeat(40) { sample(model, 1_000_000_000L + it * 100_000_000L, ax = if (it % 2 == 0) 0f else 2f) }
        assertEquals(MagneticPhase.BASELINE, model.reading.phase)
        settle(model, 6_000_000_000L)
        assertEquals(MagneticPhase.STALE, model.reading.phase)
        model.start()
        settle(model)
        sample(model, 3_200_000_000L, field = 50f, ax = 1f)
        assertEquals(MagneticPhase.MOVING, model.reading.phase)
        repeat(9) { sample(model, 3_300_000_000L + it * 100_000_000L, field = 50f, ax = 1f) }
        assertEquals(MagneticPhase.MOVING, model.reading.phase)
        sample(model, 4_200_000_000L, field = 50f, ax = 1f)
        assertEquals(MagneticPhase.LARGE, model.reading.phase)
        assertEquals(10f, model.reading.delta!!, 0.001f)
    }

    @Test fun invalidSamplesAccuracyAndSaturationRequireRetake() {
        val bad = listOf(Float.NaN, Float.POSITIVE_INFINITY, 100f, -100f)
        for (value in bad) {
            val model = MagneticModel(100f, 0.1f)
            model.start()
            val t = settle(model) + 100_000_000L
            model.acceleration(0f, 0f, 9.8f, t)
            model.field(value, 0f, 0f, 3, t)
            assertEquals(MagneticPhase.RETAKE, model.reading.phase)
            assertTrue(model.reading.history.isEmpty())
        }
        val model = MagneticModel(100f, 0.1f)
        model.start()
        val t = settle(model) + 100_000_000L
        model.acceleration(0f, 0f, 9.8f, t)
        model.field(40f, 0f, 0f, 1, t)
        assertEquals(MagneticPhase.RETAKE, model.reading.phase)
        model.start()
        settle(model)
        model.accuracyLost()
        assertEquals(MagneticPhase.RETAKE, model.reading.phase)
    }

    @Test fun staleStreamsAndResetCannotShowOldMeasurements() {
        val model = MagneticModel(100f, 0.1f)
        model.start(1_000_000_000L)
        model.tick(1_800_000_001L)
        assertEquals(MagneticPhase.STALE, model.reading.phase)
        model.start()
        val t = settle(model)
        model.tick(t + MagneticModel.STALE_NS + 1)
        assertEquals(MagneticPhase.STALE, model.reading.phase)
        assertTrue(model.reading.history.isEmpty())
        model.start()
        settle(model)
        model.stop()
        assertEquals(MagneticPhase.IDLE, model.reading.phase)
        assertTrue(model.reading.history.isEmpty())
    }

    @Test fun unstableFieldDoesNotEstablishBaselineAndHistoryIsBounded() {
        val model = MagneticModel(1000f, 0.1f)
        model.start()
        repeat(25) { sample(model, 1_000_000_000L + it * 100_000_000L, if (it % 2 == 0) 40f else 48f) }
        assertEquals(MagneticPhase.BASELINE, model.reading.phase)
        model.start()
        val end = settle(model)
        repeat(100) { sample(model, end + (it + 1) * 100_000_000L, 46f) }
        assertEquals(80, model.reading.history.size)
        assertEquals(MagneticPhase.LARGE, model.reading.phase)
    }
}
