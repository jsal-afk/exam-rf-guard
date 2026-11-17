package com.classroom.rfdetector

import android.content.ContextWrapper
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import com.classroom.rfdetector.core.ScanState
import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanSessionTest {
    @get:Rule val directory = TemporaryFolder()
    private lateinit var vm: AppViewModel

    private fun newViewModel(): AppViewModel {
        val app = RuntimeEnvironment.getApplication()
        return AppViewModel(object : ContextWrapper(app) { override fun getFilesDir() = directory.root }).also { vm = it; awaitStorage() }
    }

    @Before fun create() {
        newViewModel()
        vm.setSim(true)
        vm.startScan()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(5, vm.devices.value.size)
    }
    @After fun close() { vm.shutdown() }

    private fun awaitStorage() {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!vm.snapshotBusy.value) return
            Thread.sleep(5)
        }
        fail("Snapshot operation did not finish")
    }

    private fun saveEmptyRoom() {
        vm.takeBaseline()
        awaitStorage()
        assertTrue(vm.baselineSet.value)
    }

    @Test fun emptyRoomRecordStartsWatchingAndOnlyNewKindsAreFlagged() {
        assertFalse(vm.baselineSet.value)
        assertEquals(0, vm.watch.value.count)
        saveEmptyRoom()
        assertEquals(0, vm.watch.value.count)
        assertEquals(5, vm.watch.value.knownGroups.size)

        vm.simAddDevice()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        val group = vm.watch.value.bluetooth.single()
        assertEquals(0, group.baselineCount)

        vm.acknowledge(group.strongest.trackingKey)
        assertEquals(0, vm.watch.value.count)
    }

    @Test fun leavingAndReturningContinuesTheSameSessionAndCountsTheGap() {
        saveEmptyRoom()
        val session = vm.scanSession.value!!
        vm.acknowledge(vm.devices.value.first().trackingKey)
        vm.pauseForBackground()
        assertFalse(vm.scanning.value)
        vm.resumeFromBackground()
        assertTrue(vm.scanning.value)
        assertEquals(session.id, vm.scanSession.value!!.id)
        assertEquals(1, vm.scanSession.value!!.pauses)
        assertEquals(5, vm.devices.value.size)
        assertTrue(vm.baselineSet.value)
        assertEquals(1, vm.acknowledged.value.size)

        vm.stopScan()
        vm.resumeFromBackground()
        assertFalse("A manual stop stays stopped", vm.scanning.value)
        vm.startScan()
        assertNotEquals(session.id, vm.scanSession.value!!.id)
        assertTrue("The same empty-room record keeps being used", vm.baselineSet.value)
        assertTrue(vm.acknowledged.value.isEmpty())
    }

    @Test fun restartingFromTheEmptyRoomClearsTheRecordAndCollectsAgain() {
        saveEmptyRoom()
        val session = vm.scanSession.value!!.id
        vm.restartFromEmptyRoom()
        assertTrue(vm.scanning.value)
        assertFalse(vm.baselineSet.value)
        assertNull(vm.baselineSnapshot.value)
        assertNotEquals(session, vm.scanSession.value!!.id)
    }

    @Test fun onlyTheLatestRealEmptyRoomRecordIsOfferedAfterReopening() {
        saveEmptyRoom()
        vm.shutdown()
        // 가상 전파로 저장한 기록은 실제 감시에 이어 쓰지 않는다.
        assertNull(newViewModel().savedBaseline.value)
    }

    @Test fun acknowledgingOneAddressDoesNotHideNewSameNameMembers() {
        saveEmptyRoom()
        val now = System.currentTimeMillis()
        vm.tracker.update(com.classroom.rfdetector.core.RfObservation("checked", com.classroom.rfdetector.core.ScanSource.BLE, "Accessory", -60, null, false, now))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        vm.acknowledge("BLE:CHECKED")
        vm.tracker.update(com.classroom.rfdetector.core.RfObservation("new", com.classroom.rfdetector.core.ScanSource.BLE, "Accessory", -60, null, false, now + 100))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertTrue(vm.watch.value.uncertain.any { group -> group.devices.any { it.key == "new" } })
        assertTrue(vm.watch.value.reviewed.any { it.key == "checked" })
        vm.undoAcknowledge("BLE:CHECKED")
        assertTrue(vm.acknowledged.value.isEmpty())
    }

    private fun startRealRadios(): WifiManager {
        vm.stopScan()
        vm.setSim(false)
        val app = RuntimeEnvironment.getApplication()
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setEnabled(true)
        (app.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled = true
        shadowOf(app.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager).setLocationEnabled(true)
        vm.startScan()
        return app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private fun completeWifi() {
        RuntimeEnvironment.getApplication().sendBroadcast(Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            .putExtra(WifiManager.EXTRA_RESULTS_UPDATED, true))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun elapsedTimeAloneCannotSaveFailedOrUnfinishedCollection() {
        startRealRadios()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(4))
        assertFalse(vm.canSaveBaseline())
        vm.takeBaseline()
        assertFalse(vm.baselineSet.value)
    }

    @Test fun collectionRequiresActiveTimeAndCompletedWifiButAcceptsSuccessfulEmptyScans() {
        val manager = startRealRadios()
        shadowOf(manager).setScanResults(emptyList())
        completeWifi()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        completeWifi()
        assertFalse(vm.canSaveBaseline())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        vm.pauseForBackground()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(4))
        vm.resumeFromBackground()
        completeWifi()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        completeWifi()
        assertFalse(vm.canSaveBaseline())
        // Successful scan completions keep collection healthy; no AP is required.
        repeat(5) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(25))
            completeWifi()
        }
        assertTrue(vm.canSaveBaseline())
        vm.takeBaseline()
        awaitStorage()
        assertTrue(vm.baselineSet.value)
        assertTrue(vm.baselineSnapshot.value!!.signals.isEmpty())
    }

    @Test fun scannerFailureIsPublishedWithoutAutomaticRetry() {
        startRealRadios()
        val app = RuntimeEnvironment.getApplication()
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter.bluetoothLeScanner).scanCallbacks.single().onScanFailed(2)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertTrue(vm.scanning.value)
        assertEquals(ScanState.ERROR, vm.bleHealth.value.state)
        assertTrue(shadowOf(adapter.bluetoothLeScanner).scanCallbacks.isEmpty())
        vm.restartRadios()
        assertEquals(ScanState.WAITING, vm.bleHealth.value.state)
        assertTrue(vm.ble.active)
    }
}
