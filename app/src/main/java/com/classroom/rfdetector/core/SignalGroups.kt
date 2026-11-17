package com.classroom.rfdetector.core

import java.util.Locale

const val ESTABLISHED_MS = 30_000L

/** 광고 단서가 비슷하다는 뜻일 뿐 같은 물리 기기라는 뜻은 아니다. */
private fun bleGroupKey(address: String, name: String, ids: Set<Int>, hints: Set<SignalHint>): String {
    val advertised = advertisedName(address, name)
    return when {
        advertised != null -> "BLE:name:${advertised.lowercase(Locale.ROOT)}"
        ids.isEmpty() && hints.isEmpty() -> "BLE:unknown"
        else -> "BLE:kind:${ids.sorted().joinToString(",")}:${hints.primary?.name.orEmpty()}"
    }
}

/** 앱이 대신 붙인 이름은 광고 이름으로 사용하지 않는다. Wi-Fi 이름에는 이 정규화를 적용하지 않는다. */
fun advertisedName(address: String, name: String): String? {
    val tail = address.takeLast(5)
    return name.trim().takeUnless { it.isEmpty() || it == "이름 없는 AP" || it == "Wi-Fi 기기 $tail" || it == "BLE 기기 $tail" }
}

private fun wifiKey(address: String, name: String, hidden: Boolean): String =
    "${radioKey(ScanSource.WIFI, address)}:${if (hidden) "hidden" else "name:$name"}"

val TrackedDevice.groupKey: String get() = if (source == ScanSource.WIFI) wifiKey(key, displayName, hidden)
    else bleGroupKey(key, displayName, manufacturerIds, hints)
val SnapshotSignal.groupKey: String get() = if (source == ScanSource.WIFI) wifiKey(address, name, hidden)
    else bleGroupKey(address, name, manufacturerIds, hints)

/** devices는 수신이 센 순서다. alert는 동시 수 증가의 지속 여부이며 위험 판정이 아니다. */
data class SignalGroup(val key: String, val devices: List<TrackedDevice>, val baselineCount: Int, val alert: Boolean) {
    val strongest: TrackedDevice get() = devices.first()
}

data class KnownGroup(val key: String, val sample: SnapshotSignal, val baselineCount: Int, val live: List<TrackedDevice>, val last: TrackedDevice?)

data class WatchView(
    val wifi: List<SignalGroup> = emptyList(),
    val bluetooth: List<SignalGroup> = emptyList(),
    val uncertain: List<SignalGroup> = emptyList(),
    val weak: List<SignalGroup> = emptyList(),
    val findMy: List<TrackedDevice> = emptyList(),
    val knownGroups: List<KnownGroup> = emptyList(),
    val reviewed: List<TrackedDevice> = emptyList(),
    val history: List<TrackedDevice> = emptyList(),
) {
    val count: Int get() = wifi.sumOf { it.devices.size } + bluetooth.sumOf { it.devices.size }
    val supplementaryCount: Int get() = (uncertain + weak).sumOf { it.devices.size } + findMy.size
}

private fun SnapshotSignal.matches(device: TrackedDevice): Boolean =
    radioKey(source, address) == device.trackingKey && (source == ScanSource.BLE || groupKey == device.groupKey)

/** 기준선은 정확히 관측한 주소에만 적용한다. 새 BLE 주소는 회전 가능성을 남겨 조용히 보여 준다. */
fun watchView(devices: List<TrackedDevice>, baseline: SignalSnapshot, acknowledged: Set<String>, nowMs: Long): WatchView {
    val baselineByAddress = baseline.signals.associateBy { radioKey(it.source, it.address) }
    val baselineGroups = baseline.signals.groupBy { it.groupKey }
    val live = devices.filter { it.presence != SignalPresence.HISTORY }
    val liveByGroup = live.groupBy { it.groupKey }
    val byAddress = devices.associateBy { it.trackingKey }
    val reviewed = live.filter { it.trackingKey in acknowledged }
    val reviewedGroups = devices.filter { it.trackingKey in acknowledged }.map { it.groupKey }.toSet()
    val candidates = live.filter { device ->
        device.trackingKey !in acknowledged && baselineByAddress[device.trackingKey]?.matches(device) != true
    }
    val (findMy, others) = candidates.partition { it.source == ScanSource.BLE && it.hints.primary == SignalHint.APPLE_FIND_MY }
    val primary = mutableListOf<SignalGroup>()
    val uncertain = mutableListOf<SignalGroup>()
    val weak = mutableListOf<SignalGroup>()
    others.groupBy { it.groupKey }.forEach { (key, members) ->
        val sorted = members.sortedByDescending { it.rssiEma ?: Double.NEGATIVE_INFINITY }
        val before = baselineGroups[key].orEmpty().count { it.presence != SignalPresence.HISTORY }
        val related = key in baselineGroups || key in reviewedGroups
        val current = liveByGroup[key].orEmpty()
        val established = current.count { nowMs - (it.periods.lastOrNull()?.firstSeenMs ?: it.firstSeenMs) >= ESTABLISHED_MS }
        val reviewedCount = current.count { it.trackingKey in acknowledged }
        val group = SignalGroup(key, sorted, before, alert = established > before + reviewedCount)
        when {
            sorted.first().rssiEma == null || sorted.first().rssiEma!! < WEAK_SIGNAL_DBM -> weak += group
            sorted.first().source == ScanSource.BLE && related && !group.alert -> uncertain += group
            else -> primary += group
        }
    }
    val strength = compareByDescending<SignalGroup> { it.strongest.rssiEma ?: Double.NEGATIVE_INFINITY }
    val known = baselineGroups.map { (key, samples) ->
        val matched = samples.mapNotNull { sample ->
            byAddress[radioKey(sample.source, sample.address)]?.takeIf { sample.matches(it) }
        }
        KnownGroup(key, samples.maxBy { it.rssi ?: Double.NEGATIVE_INFINITY }, samples.size,
            matched.filter { it.presence != SignalPresence.HISTORY }.sortedByDescending { it.rssiEma ?: Double.NEGATIVE_INFINITY },
            matched.maxByOrNull { it.lastSeenMs })
    }.sortedWith(compareBy<KnownGroup> { it.sample.source != ScanSource.WIFI }
        .thenByDescending { it.sample.rssi ?: Double.NEGATIVE_INFINITY }.thenBy { it.key })
    return WatchView(
        wifi = primary.filter { it.strongest.source == ScanSource.WIFI }.sortedWith(strength),
        bluetooth = primary.filter { it.strongest.source == ScanSource.BLE }.sortedWith(strength),
        uncertain = uncertain.sortedWith(strength),
        weak = weak.sortedWith(strength),
        findMy = findMy,
        knownGroups = known,
        reviewed = reviewed,
        history = devices.filter { it.presence == SignalPresence.HISTORY }.sortedByDescending { it.lastSeenMs }.take(30),
    )
}
