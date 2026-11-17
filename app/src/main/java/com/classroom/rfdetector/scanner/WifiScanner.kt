package com.classroom.rfdetector.scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.classroom.rfdetector.core.RfObservation
import com.classroom.rfdetector.core.ScanHealth
import com.classroom.rfdetector.core.ScanState
import com.classroom.rfdetector.core.RssiFilter
import com.classroom.rfdetector.core.ScanSource
import com.classroom.rfdetector.core.radioKey
import com.classroom.rfdetector.core.wifiDetails
import com.classroom.rfdetector.core.wifiHints
import com.classroom.rfdetector.core.wpsDetails
import kotlinx.coroutines.*

/** Read only completed scans; cached observations keep their actual observation time. */
class WifiScanner(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    @Volatile var active: Boolean = false
        private set
    @Volatile var lastError: String? = null
        private set
    /** 이번 탐색에서 끝까지 완료된 탐색 횟수. 빈 교실 기록이 충분한지 판단하는 데 쓴다. */
    @Volatile var completedScans: Int = 0
        private set
    @Volatile private var state = ScanState.STOPPED
    @Volatile private var statusMessage = "수신 중지"
    @Volatile private var lastCompletedAtMs: Long? = null
    private var job: Job? = null
    private var receiver: BroadcastReceiver? = null
    private val observedTimestamps = mutableMapOf<String, Long>()

    fun isEnabled(): Boolean = try { wifi.isWifiEnabled } catch (_: SecurityException) { false }

    fun health(nowMs: Long): ScanHealth {
        if (state == ScanState.COMPLETED && lastCompletedAtMs?.let { nowMs - it > 90_000L } == true) {
            return ScanHealth(ScanState.WAITING, "새 와이파이 결과를 기다리는 중입니다", lastCompletedAtMs)
        }
        return ScanHealth(state, statusMessage, lastCompletedAtMs)
    }

    fun start(onObs: (RfObservation) -> Unit, periodMs: Long = 30_000L) {
        stop()
        lastError = null
        completedScans = 0
        lastCompletedAtMs = null
        state = ScanState.WAITING
        statusMessage = "검색 결과를 기다리는 중"
        observedTimestamps.clear()
        val startedAtElapsedUs = SystemClock.elapsedRealtimeNanos() / 1000
        val listener = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (receiver !== this) return
                if (!intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)) {
                    reportWaiting("갱신 대기 · 시스템이 검색 빈도를 제한할 수 있습니다")
                    return
                }
                try {
                    val now = System.currentTimeMillis()
                    val elapsed = SystemClock.elapsedRealtime()
                    val rawResults = wifi.scanResults
                    val results = rawResults.filter {
                        it.timestamp >= startedAtElapsedUs && elapsed - it.timestamp / 1000 in 0..90_000 &&
                            it.timestamp > (observedTimestamps[radioKey(ScanSource.WIFI, it.BSSID)] ?: Long.MIN_VALUE)
                    }
                    if (rawResults.isNotEmpty() && results.isEmpty()) {
                        reportWaiting("새 결과 대기 · 이전 검색 결과는 다시 세지 않습니다")
                        return
                    }
                    lastError = null
                    results.forEach { result ->
                        val key = radioKey(ScanSource.WIFI, result.BSSID)
                        observedTimestamps[key] = result.timestamp
                        onObs(RfObservation(result.BSSID, ScanSource.WIFI, result.SSID?.takeIf { it.isNotEmpty() }, result.level.takeIf { RssiFilter.isValid(it.toDouble()) }, null,
                            result.SSID.isNullOrEmpty(), now - (elapsed - result.timestamp / 1000), hints = wifiHints(result.SSID), details = details(result)))
                    }
                    completedScans++
                    lastCompletedAtMs = now
                    state = ScanState.COMPLETED
                    statusMessage = "검색 완료${if (rawResults.isEmpty()) " · 관측된 신호 없음" else ""}"
                } catch (_: SecurityException) {
                    fail("와이파이 권한을 확인하세요 · 정확한 위치 권한이 필요합니다")
                }
            }
        }
        receiver = listener
        try {
            ContextCompat.registerReceiver(app, listener, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_EXPORTED)
        } catch (_: SecurityException) {
            receiver = null
            fail("와이파이 검색 권한이 없습니다")
            return
        }
        active = true
        job = scope.launch {
            while (isActive) {
                try {
                    if (!wifi.startScan()) reportWaiting("갱신 대기 · 와이파이와 위치 설정을 확인하세요")
                } catch (_: SecurityException) {
                    fail("와이파이 검색 권한이 없습니다")
                    break
                }
                delay(periodMs)
            }
        }
    }

    private fun details(result: ScanResult): Map<String, String> {
        val modern = Build.VERSION.SDK_INT >= 30
        val details = LinkedHashMap(wifiDetails(result.frequency, result.capabilities, if (modern) result.wifiStandard else null))
        // 부가 정보 블록이 없는 결과에서는 이 조회 자체가 실패할 수 있다.
        if (modern) runCatching { result.informationElements }.getOrNull().orEmpty().filter { it.id == 221 }.forEach { element ->
            val buffer = element.bytes
            details.putAll(wpsDetails(ByteArray(buffer.remaining()).also { buffer.get(it) }))
        }
        return details
    }

    fun stop() {
        active = false
        state = ScanState.STOPPED
        statusMessage = "수신 중지"
        lastError = null
        job?.cancel()
        job = null
        receiver?.let { app.unregisterReceiver(it) }
        receiver = null
    }

    private fun reportWaiting(message: String) {
        lastError = message
        state = ScanState.WAITING
        statusMessage = message
    }

    private fun fail(message: String) {
        stop()
        lastError = message
        state = ScanState.ERROR
        statusMessage = message
    }
}
