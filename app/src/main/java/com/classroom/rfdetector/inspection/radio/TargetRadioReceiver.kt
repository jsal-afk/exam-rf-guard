package com.classroom.rfdetector.inspection.radio

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.classroom.rfdetector.core.ScanSource
import com.classroom.rfdetector.core.radioKey

/** 선택한 주소만 받는 일회성 도구. 기존 감시가 있으면 Wi-Fi 요청 주기를 추가하지 않는다. */
class TargetRadioReceiver(context: Context) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var bleCallback: ScanCallback? = null
    private var wifiReceiver: BroadcastReceiver? = null
    private var wifiRequest: Runnable? = null
    private var generation = 0
    var active = false
        private set
    var message = "수신을 시작하세요"
        private set

    fun start(source: ScanSource, address: String, monitoring: Boolean,
              onPacket: (String, Long, Int) -> Unit, onState: () -> Unit) {
        stop()
        val current = generation
        val key = radioKey(source, address)
        val began = SystemClock.elapsedRealtimeNanos()
        fun failure(text: String) { if (generation == current) { stop(); message = text; onState() } }
        fun packet(timestamp: Long, rssi: Int) {
            if (!active || generation != current || timestamp <= began || timestamp > SystemClock.elapsedRealtimeNanos()) return
            onPacket(key, timestamp, rssi)
            message = "선택한 신호를 받고 있습니다"
            onState()
        }
        try {
            active = true
            message = if (source == ScanSource.WIFI) "새 와이파이 결과 대기 · 보통 30초 이상 걸립니다" else "선택한 블루투스 신호 대기"
            if (source == ScanSource.BLE) {
                val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                val scanner = if (adapter?.isEnabled == true) adapter.bluetoothLeScanner else null
                if (scanner == null) { failure("블루투스와 근처 기기 권한을 확인하세요"); return }
                val callback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        try {
                            if (radioKey(source, result.device.address) != key) return
                            handler.post { packet(result.timestampNanos, result.rssi) }
                        } catch (_: SecurityException) { handler.post { failure("근처 기기 권한을 확인하세요") } }
                    }
                    override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { onScanResult(0, it) } }
                    override fun onScanFailed(errorCode: Int) { handler.post { failure("신호 수신 오류 ($errorCode) · 잠시 뒤 다시 시작하세요") } }
                }
                bleCallback = callback
                scanner.startScan(listOf(ScanFilter.Builder().setDeviceAddress(address).build()),
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setLegacy(false).setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED).build(), callback)
            } else {
                val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (wifi == null || !wifi.isWifiEnabled) { failure("와이파이와 위치 설정을 확인하세요"); return }
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (generation != current || !active) return
                        if (!intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)) {
                            message = "와이파이 갱신 대기 · 시스템 제한일 수 있습니다"; onState(); return
                        }
                        try {
                            for (result in wifi.scanResults) {
                                if (radioKey(source, result.BSSID) == key) packet(result.timestamp * 1000, result.level)
                            }
                        } catch (_: SecurityException) { failure("정확한 위치 권한을 확인하세요") }
                    }
                }
                wifiReceiver = receiver
                ContextCompat.registerReceiver(app, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_EXPORTED)
                if (!monitoring) {
                    val request = object : Runnable {
                        override fun run() {
                            if (!active || generation != current) return
                            try {
                                if (!wifi.startScan()) { message = "와이파이 갱신 대기 · 빠른 재요청은 하지 않습니다"; onState() }
                            } catch (_: SecurityException) { failure("정확한 위치 권한을 확인하세요"); return }
                            handler.postDelayed(this, 30_000L)
                        }
                    }
                    wifiRequest = request
                    // 재시작도 즉시 탐색을 연달아 요청하지 않는다.
                    handler.postDelayed(request, 30_000L)
                }
            }
            onState()
        } catch (_: SecurityException) { failure("근처 기기·정확한 위치 권한을 확인하세요") }
        catch (_: IllegalStateException) { failure("무선 기능을 열지 못했습니다 · 설정을 확인하세요") }
        catch (_: IllegalArgumentException) { failure("선택한 주소로 수신을 시작하지 못했습니다") }
    }

    fun stop() {
        generation++
        active = false
        wifiRequest?.let { handler.removeCallbacks(it) }; wifiRequest = null
        wifiReceiver?.let { runCatching { app.unregisterReceiver(it) } }; wifiReceiver = null
        val callback = bleCallback
        bleCallback = null
        if (callback != null) {
            try { (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner?.stopScan(callback) }
            catch (_: SecurityException) { /* 권한이 회수되어도 도구의 콜백은 더 받지 않는다. */ }
            catch (_: IllegalStateException) { /* 라디오가 먼저 꺼졌을 수 있다. */ }
        }
        message = "수신을 멈췄습니다"
    }
}
