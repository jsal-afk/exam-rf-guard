package com.classroom.rfdetector.core

import org.junit.Assert.*
import org.junit.Test

class ObservationPreservationTest {
    private val tracker = DeviceTracker()
    private fun wifi(address: String, name: String?, at: Long = 1_000) {
        tracker.update(RfObservation(address, ScanSource.WIFI, name, -60, null, name == null, at))
    }
    private fun ble(address: String, name: String? = "Accessory", at: Long = 1_000, rssi: Int? = -60,
        hints: Set<SignalHint> = emptySet()) {
        tracker.update(RfObservation(address, ScanSource.BLE, name, rssi, null, false, at, setOf(76), hints))
    }
    private fun baseline(at: Long = 1_000) = SignalSnapshot.capture("A", at, false, tracker.snapshot(at, true))
    private fun view(baseline: SignalSnapshot, at: Long = 100_000, acknowledged: Set<String> = emptySet()) =
        watchView(tracker.snapshot(at, true), baseline, acknowledged, at)
    private fun WatchView.candidates() = (wifi + bluetooth + uncertain + weak).flatMap { it.devices } + findMy

    @Test fun sameNameHiddenAndSimilarAddressesNeverHideNewWifiObservations() {
        wifi("00:11:22:33:44:01", "SCHOOL")
        wifi("00:11:22:33:44:02", null)
        val baseline = baseline()
        wifi("00:11:22:33:44:03", "SCHOOL", 100_000)
        wifi("00:11:22:33:44:04", null, 100_000)
        wifi("02:11:22:33:44:01", "school", 100_000)
        assertEquals(setOf("00:11:22:33:44:03", "00:11:22:33:44:04", "02:11:22:33:44:01"),
            view(baseline).wifi.flatMap { it.devices }.map { it.key }.toSet())
    }

    @Test fun wifiNameChangesPreserveCaseAndWhitespace() {
        wifi("ap", "SCHOOL")
        val baseline = baseline()
        wifi("ap", "school", 100_000)
        assertEquals("school", view(baseline).wifi.single().strongest.displayName)
        wifi("ap", " SCHOOL ", 101_000)
        assertEquals(" SCHOOL ", view(baseline, 101_000).wifi.single().strongest.displayName)
    }

    @Test fun sameKindReplacementIsVisibleButNotAssertedToBeANewPhysicalDevice() {
        ble("old")
        val baseline = baseline()
        ble("replacement", at = 100_000)
        val result = view(baseline)
        assertEquals("replacement", result.uncertain.single().strongest.key)
        assertEquals(0, result.count)
        assertEquals(1, result.supplementaryCount)
    }

    @Test fun acknowledgementOnlyCoversTheObservedAddress() {
        val baseline = SignalSnapshot("A", 0, false, emptyList())
        ble("checked")
        ble("new", at = 100_000)
        val ack = setOf(radioKey(ScanSource.BLE, "checked"))
        val result = view(baseline, acknowledged = ack)
        assertEquals("new", result.uncertain.single().strongest.key)
        assertTrue(result.candidates().none { it.key == "checked" })
    }

    @Test fun lateNameAndCompanyDetailsDoNotCreateANewDeviceForABaselineAddress() {
        ble("same", name = null)
        val baseline = baseline()
        ble("same", name = "Later name", at = 100_000, hints = setOf(SignalHint.WATCH))
        assertTrue(view(baseline).candidates().isEmpty())
        assertEquals("Later name", view(baseline).knownGroups.single().live.single().displayName)
    }

    @Test fun supplementarySignalsAndDisappearanceRemainAccessible() {
        val baseline = SignalSnapshot("A", 0, false, emptyList())
        ble("weak", at = 100_000, rssi = -90)
        ble("unmeasured", at = 100_000, rssi = null)
        ble("findmy", at = 100_000, hints = setOf(SignalHint.APPLE_FIND_MY))
        val current = view(baseline)
        assertEquals(3, current.supplementaryCount)
        assertEquals(setOf("weak", "unmeasured", "findmy"), current.candidates().map { it.key }.toSet())
        assertEquals(3, view(baseline, 120_000).history.size)
    }

    @Test fun primaryCountCountsAddressesInsideUncertainGroupsNotPhysicalDevices() {
        val baseline = SignalSnapshot("A", 0, false, emptyList())
        ble("one", at = 100_000)
        ble("two", at = 100_000)
        val result = view(baseline)
        assertEquals(1, result.bluetooth.size)
        assertEquals(2, result.count)
        assertEquals(setOf("one", "two"), result.bluetooth.single().devices.map { it.key }.toSet())
    }
}
