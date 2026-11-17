package com.classroom.rfdetector.core

import kotlin.math.pow

/** 수신 세기 평활과 강도 등급. 거리 모델은 보정 가정이 있는 별도 계산에만 쓴다. */
object RssiFilter {
    /** 보정 정보가 없을 때 사용하는 1m 기준 RSSI 가정값. BLE 표준 보정값은 아니다. */
    const val DEFAULT_TX_DBM = -59.0
    /** 실내 경로손실 지수 */
    const val PATH_N = 2.7

    fun isValid(rssi: Double?): Boolean = rssi != null && rssi.isFinite() && rssi in -127.0..-1.0

    fun classifyRssi(rssi: Double?): DistanceClass {
        if (!isValid(rssi)) return DistanceClass.UNKNOWN
        return when {
            rssi!! >= -65 -> DistanceClass.VERY_CLOSE
            rssi >= -75 -> DistanceClass.CLOSE
            rssi >= -85 -> DistanceClass.NEAR
            else -> DistanceClass.FAR
        }
    }

    /** 지수 이동평균 */
    fun ema(prev: Double?, sample: Int, alpha: Double = 0.25): Double =
        prev?.let { it + alpha * (sample - it) } ?: sample.toDouble()

    /** 로그 거리 경로손실 모델 */
    fun distanceM(rssi: Double, txDbm: Double = DEFAULT_TX_DBM): Double =
        10.0.pow((txDbm - rssi) / (10 * PATH_N))

}
