package com.classroom.rfdetector.core

import org.junit.Assert.*
import org.junit.Test

class DistanceEstimateTest {
    @Test fun invalidStrengthDoesNotInventDistance() {
        listOf(null, Double.NaN, Double.POSITIVE_INFINITY, 0.0, -200.0).forEach { assertNull(estimateDistance(it)) }
    }

    @Test fun weakerSignalHasLargerModelDistanceAndOneMeterReferenceIsHonored() {
        assertEquals(1.0, estimateDistance(-59.0, -59.0)!!.meters, 0.0001)
        assertTrue(estimateDistance(-80.0)!!.meters > estimateDistance(-60.0)!!.meters)
        assertEquals(1.0, estimateDistance(-70.0, -70.0)!!.meters, 0.0001)
    }

    @Test fun strengthClassificationRejectsInvalidValuesAndOrdersValidValues() {
        listOf(null, Double.NaN, Double.POSITIVE_INFINITY, 0.0, 127.0, -128.0, 1.0).forEach {
            assertEquals(DistanceClass.UNKNOWN, RssiFilter.classifyRssi(it))
        }
        assertEquals(DistanceClass.VERY_CLOSE, RssiFilter.classifyRssi(-40.0))
        assertEquals(DistanceClass.CLOSE, RssiFilter.classifyRssi(-70.0))
        assertEquals(DistanceClass.NEAR, RssiFilter.classifyRssi(-80.0))
        assertEquals(DistanceClass.FAR, RssiFilter.classifyRssi(-100.0))
    }
}
