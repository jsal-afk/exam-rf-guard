package com.classroom.rfdetector.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignalHintsTest {
    @get:Rule val directory = TemporaryFolder()

    private fun ad(type: Int, vararg data: Int) = byteArrayOf((data.size + 1).toByte(), type.toByte()) + data.map { it.toByte() }
    private val flags = ad(0x01, 0x06)
    // 상태 1바이트, 공개키 조각 22바이트, 키 상위 비트와 hint 각 1바이트의 합성 형식.
    private val findMyFrame = ad(0xFF, *(listOf(0x4C, 0x00, 0x12, 0x19, 0x10) + (1..22) + listOf(0, 0)).toIntArray())

    @Test fun appearanceCategoryNamesTheDeviceKind() {
        assertEquals(setOf(SignalHint.WATCH), bleHints(flags + ad(0x19, 0xC1, 0x00)))
        assertEquals(setOf(SignalHint.GLASSES), bleHints(ad(0x19, 0xC0, 0x01)))
        assertEquals(setOf(SignalHint.EARPHONES), bleHints(ad(0x19, 0x41, 0x09)))
        assertEquals(setOf(SignalHint.HEARING_AID), bleHints(ad(0x19, 0x40, 0x0A)))
        assertEquals(setOf(SignalHint.PHONE), bleHints(ad(0x19, 0x40, 0x00)))
        assertEquals(emptySet<SignalHint>(), bleHints(ad(0x19, 0x00, 0x00)))
    }

    @Test fun appleMessagesSeparateActiveDevicesEarbudsAndFindMyBeacons() {
        // Handoff(0x0C) 뒤에 Nearby Info(0x10)가 이어지는 켜진 아이폰의 광고
        assertEquals(setOf(SignalHint.APPLE_ACTIVE), bleHints(flags + ad(0xFF, 0x4C, 0x00, 0x0C, 0x02, 0xAA, 0xBB, 0x10, 0x02, 0x01, 0x18)))
        assertEquals(setOf(SignalHint.APPLE_AUDIO), bleHints(ad(0xFF, 0x4C, 0x00, 0x07, 0x02, 0x01, 0x02)))
        assertEquals(setOf(SignalHint.APPLE_FIND_MY), bleHints(findMyFrame))
        // 다른 회사가 같은 종류 바이트를 써도 Apple로 읽지 않는다.
        assertEquals(emptySet<SignalHint>(), bleHints(ad(0xFF, 0x75, 0x00, 0x10, 0x02, 0x01, 0x18)))
    }

    @Test fun fastPairWindowsAndPriorityAreRead() {
        assertEquals(setOf(SignalHint.FAST_PAIR), bleHints(ad(0x16, 0x2C, 0xFE, 0x01, 0x02, 0x03)))
        assertEquals(setOf(SignalHint.FAST_PAIR), bleHints(ad(0x03, 0x0F, 0x18, 0x2C, 0xFE)))
        assertEquals(setOf(SignalHint.WINDOWS), bleHints(ad(0xFF, 0x06, 0x00, 0x01, 0x09)))
        val both = bleHints(ad(0x19, 0xC1, 0x00) + ad(0xFF, 0x4C, 0x00, 0x10, 0x02, 0x01, 0x18))
        assertEquals(setOf(SignalHint.WATCH, SignalHint.APPLE_ACTIVE), both)
        assertEquals(SignalHint.WATCH, both.primary)
    }

    @Test fun truncatedOrEmptyAdvertisementIsIgnored() {
        assertEquals(emptySet<SignalHint>(), bleHints(null))
        assertEquals(emptySet<SignalHint>(), bleHints(byteArrayOf(0, 0, 0)))
        assertEquals(emptySet<SignalHint>(), bleHints(byteArrayOf(9, 0x19, 0xC1.toByte())))
        assertEquals(emptySet<SignalHint>(), bleHints(byteArrayOf(2, 0xFF.toByte(), 0x4C)))
        assertEquals(emptySet<SignalHint>(), bleHints(ad(0xFF, 0x4C, 0x00, 0x10)))
    }

    @Test fun malformedAppleInnerLengthDoesNotProduceFindMy() {
        assertTrue(bleHints(ad(0xFF, 0x4C, 0x00, 0x12, 0x08, 0x01)).isEmpty())
        val compound = ad(0xFF, 0x4C, 0x00, 0x10, 0x01, 0x01, 0x12, 0x04, 0x01)
        assertEquals(setOf(SignalHint.APPLE_ACTIVE), bleHints(compound))
    }

    @Test fun findMyRequiresTheKnownCompleteBodyShape() {
        for (length in listOf(0, 1, 2, 24, 26)) {
            val data = listOf(0x4C, 0, 0x12, length) + List(length) { 1 }
            assertFalse(bleHints(ad(0xFF, *data.toIntArray())).contains(SignalHint.APPLE_FIND_MY))
        }
        assertFalse(bleHints(findMyFrame.dropLast(1).toByteArray()).contains(SignalHint.APPLE_FIND_MY))
        assertTrue(bleHints(findMyFrame).contains(SignalHint.APPLE_FIND_MY))
    }

    @Test fun multipleAdvertisedCluesRemainIndependent() {
        val bytes = ad(0x19, 0xC1, 0x00) + ad(0x03, 0xF0, 0xFD, 0xED, 0xFE, 0xAA, 0xFE) + findMyFrame
        assertEquals(setOf(SignalHint.WATCH, SignalHint.HEARING_AID, SignalHint.TAG, SignalHint.BEACON, SignalHint.APPLE_FIND_MY), bleHints(bytes))
    }

    @Test fun phoneHotspotAndWifiDirectNamesAreCandidatesOnly() {
        assertEquals(setOf(SignalHint.HOTSPOT), wifiHints("홍길동의 iPhone"))
        assertEquals(setOf(SignalHint.HOTSPOT), wifiHints("Galaxy S24 1234"))
        assertEquals(setOf(SignalHint.HOTSPOT), wifiHints("AndroidAP_5521"))
        assertEquals(setOf(SignalHint.WIFI_DIRECT), wifiHints("DIRECT-a1-Galaxy S24"))
        assertEquals(emptySet<SignalHint>(), wifiHints("SCHOOL_WIFI_5G"))
        assertEquals(emptySet<SignalHint>(), wifiHints(null))
    }

    @Test fun hintsFlowFromObservationToSavedSnapshotAndEquipmentType() {
        val tracker = DeviceTracker()
        tracker.update(RfObservation("AA:BB:CC:00:00:01", ScanSource.BLE, null, -60, null, false, 1000, hints = setOf(SignalHint.APPLE_ACTIVE)))
        tracker.update(RfObservation("AA:BB:CC:00:00:01", ScanSource.BLE, null, -60, null, false, 2000, hints = setOf(SignalHint.WATCH)))
        tracker.update(RfObservation("AA:BB:CC:00:00:02", ScanSource.WIFI, "Galaxy S24", -90, null, false, 2000, hints = wifiHints("Galaxy S24")))
        val devices = tracker.snapshot(2000)
        val watch = devices.first { it.source == ScanSource.BLE }
        assertEquals(setOf(SignalHint.APPLE_ACTIVE, SignalHint.WATCH), watch.hints)

        val store = SnapshotStore(directory.root)
        store.save(listOf(SignalSnapshot.capture("A", 3000, false, devices)))
        val loaded = store.load().single().signals.associateBy { it.source }
        assertEquals(setOf(SignalHint.APPLE_ACTIVE, SignalHint.WATCH), loaded.getValue(ScanSource.BLE).hints)
    }
}
