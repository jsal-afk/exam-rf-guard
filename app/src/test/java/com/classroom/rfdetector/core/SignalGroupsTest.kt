package com.classroom.rfdetector.core

import org.junit.Assert.*
import org.junit.Test

class SignalGroupsTest {
    private val tracker = DeviceTracker()
    private fun ble(address: String, atMs: Long, name: String? = null) =
        tracker.update(RfObservation(address, ScanSource.BLE, name, -60, null, false, atMs, setOf(76), setOf(SignalHint.APPLE_ACTIVE)))
    private fun baselineAt(nowMs: Long) = SignalSnapshot.capture("A", nowMs, false, tracker.snapshot(nowMs, true))
    private fun view(baseline: SignalSnapshot, nowMs: Long) = watchView(tracker.snapshot(nowMs, true), baseline, emptySet(), nowMs)

    @Test fun rotatingAddressIsVisibleWithoutIncreasingPrimaryWarnings() {
        ble("old", 1_000)
        val baseline = baselineAt(1_000)
        ble("old", 900_000)
        ble("rotated", 900_000)
        val overlap = view(baseline, 901_000)
        assertEquals(0, overlap.count)
        assertEquals("rotated", overlap.uncertain.single().strongest.key)
        for (time in 910_000L..940_000L step 10_000L) ble("rotated", time)
        val settled = view(baseline, 941_000)
        assertEquals(0, settled.count)
        assertEquals("rotated", settled.uncertain.single().strongest.key)
        assertTrue(settled.history.any { it.key == "old" })
    }

    @Test fun sustainedAdditionalMemberBecomesPrimaryWithoutHidingOtherMembers() {
        ble("one", 1_000); ble("two", 1_000)
        val baseline = baselineAt(1_000)
        for (time in 600_000L..640_000L step 10_000L) {
            ble("one", time); ble("two", time); ble("added", time)
        }
        val result = view(baseline, 641_000)
        val group = result.bluetooth.single()
        assertEquals(2, group.baselineCount)
        assertEquals("added", group.devices.single().key)
        assertTrue(group.alert)
        assertEquals(setOf("one", "two"), result.knownGroups.single().live.map { it.key }.toSet())
    }

    @Test fun newKindShowsImmediatelyThenMovesToHistoryOnDisappearance() {
        ble("room", 1_000)
        val baseline = baselineAt(1_000)
        ble("earbuds", 100_000, "Earbuds")
        val first = view(baseline, 101_000).bluetooth.single()
        assertEquals("earbuds", first.devices.single().key)
        assertFalse(first.alert)
        for (time in 110_000L..140_000L step 10_000L) ble("earbuds", time, "Earbuds")
        assertTrue(view(baseline, 141_000).bluetooth.single().alert)
        val gone = view(baseline, 200_000)
        assertTrue(gone.bluetooth.isEmpty())
        assertTrue(gone.history.any { it.key == "earbuds" })
    }

    @Test fun baselineHistoryDoesNotBecomeProofThatANewAddressWasPresent() {
        ble("departed", 1_000)
        val baseline = baselineAt(30_000)
        ble("new", 100_000)
        val result = view(baseline, 101_000)
        assertEquals("new", result.uncertain.single().strongest.key)
        assertTrue(result.knownGroups.single().live.isEmpty())
        assertEquals("departed", result.knownGroups.single().sample.address)
    }
}
