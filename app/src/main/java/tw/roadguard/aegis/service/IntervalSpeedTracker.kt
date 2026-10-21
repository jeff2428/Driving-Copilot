package tw.roadguard.aegis.service

import tw.roadguard.aegis.model.GeoUtils
import tw.roadguard.aegis.model.SpeedCamera
import kotlin.math.roundToInt

/**
 * 區間測速完整生命週期引擎
 * 追蹤起點、在區間內累積真實行駛里程與時間計算精準均速，並提供安全通行時間倒數與超速預警
 */
class IntervalSpeedTracker {
    enum class State {
        IDLE,
        APPROACHING_START,
        IN_ZONE,
        APPROACHING_END,
        COMPLETED
    }

    data class ZoneStatus(
        val state: State,
        val camera: SpeedCamera?,
        val averageSpeedKmh: Int,
        val distanceTraveledMeters: Double,
        val elapsedSeconds: Long,
        val remainingSeconds: Int,
        val isAverageOverspeed: Boolean,
        val displayMessage: String
    )

    private var activeCamera: SpeedCamera? = null
    private var state: State = State.IDLE
    private var entryTimeMs: Long = 0L
    private var lastLocationTimeMs: Long = 0L
    private var lastLat: Double = 0.0
    private var lastLng: Double = 0.0
    private var totalDistanceMeters: Double = 0.0
    private var estimatedZoneLengthMeters: Double = 3000.0

    // 是否已播報過起點或終點
    var hasAnnouncedStart: Boolean = false
        private set
    var hasAnnouncedEnd: Boolean = false
        private set
    private var lastOverspeedWarnTimeMs: Long = 0L

    fun getStatus(now: Long = System.currentTimeMillis()): ZoneStatus {
        val cam = activeCamera
        if (cam == null || state == State.IDLE) {
            return ZoneStatus(
                state = State.IDLE,
                camera = null,
                averageSpeedKmh = 0,
                distanceTraveledMeters = 0.0,
                elapsedSeconds = 0L,
                remainingSeconds = 0,
                isAverageOverspeed = false,
                displayMessage = ""
            )
        }

        val elapsedSec = if (entryTimeMs > 0L) (now - entryTimeMs) / 1000L else 0L
        val avgSpeed = if (elapsedSec > 2 && totalDistanceMeters > 50.0) {
            ((totalDistanceMeters / elapsedSec) * 3.6).roundToInt()
        } else {
            0
        }

        val targetTotalSec = (estimatedZoneLengthMeters / (cam.speedLimit / 3.6)).roundToInt()
        val remainingSec = (targetTotalSec - elapsedSec).toInt().coerceAtLeast(0)
        val isOverspeed = avgSpeed > cam.speedLimit

        val msg = when (state) {
            State.APPROACHING_START -> "即將進入區間測速起點 (速限 ${cam.speedLimit})"
            State.IN_ZONE -> if (isOverspeed) "⚠️ 區間均速 $avgSpeed km/h 已超速！" else "區間均速 $avgSpeed km/h (安全通行剩餘 ${remainingSec}s)"
            State.APPROACHING_END -> "即將抵達區間測速終點 (目前均速 $avgSpeed)"
            State.COMPLETED -> "區間測速結束，均速 $avgSpeed km/h"
            State.IDLE -> ""
        }

        return ZoneStatus(
            state = state,
            camera = cam,
            averageSpeedKmh = avgSpeed,
            distanceTraveledMeters = totalDistanceMeters,
            elapsedSeconds = elapsedSec,
            remainingSeconds = remainingSec,
            isAverageOverspeed = isOverspeed,
            displayMessage = msg
        )
    }

    fun onLocationUpdate(
        lat: Double,
        lng: Double,
        speedKmh: Int,
        nearestIntervalCamera: SpeedCamera?,
        distanceToCameraMeters: Double,
        now: Long = System.currentTimeMillis()
    ): ZoneStatus {
        // 1. 若處於 IDLE，偵測是否有接近中的區間測速起點
        if (state == State.IDLE) {
            if (nearestIntervalCamera != null && distanceToCameraMeters <= 1200.0) {
                activeCamera = nearestIntervalCamera
                state = State.APPROACHING_START
                estimatedZoneLengthMeters = parseZoneLengthMeters(nearestIntervalCamera.address)
            }
            return getStatus(now)
        }

        val cam = activeCamera ?: return getStatus(now)

        // 2. 接近起點階段：判斷是否剛通過起點 (距離開始拉遠或距離 <= 25m)
        if (state == State.APPROACHING_START) {
            if (distanceToCameraMeters <= 35.0 || (distanceToCameraMeters <= 80.0 && speedKmh > 10)) {
                // 正式進入區間
                state = State.IN_ZONE
                entryTimeMs = now
                lastLocationTimeMs = now
                lastLat = lat
                lastLng = lng
                totalDistanceMeters = 0.0
            }
            return getStatus(now)
        }

        // 3. 區間進行中 (IN_ZONE)：累計里程與時間
        if (state == State.IN_ZONE || state == State.APPROACHING_END) {
            if (lastLat != 0.0 && lastLng != 0.0) {
                val stepDist = GeoUtils.distanceMeters(lastLat, lastLng, lat, lng)
                val dtSec = (now - lastLocationTimeMs).coerceAtLeast(1L) / 1000.0
                val maxAllowedDist = maxOf(60.0, dtSec * 60.0) // 容許最高 216 km/h，避免超速誤判或跨秒取樣
                if (stepDist <= maxAllowedDist) {
                    totalDistanceMeters += stepDist
                }
            }
            lastLat = lat
            lastLng = lng
            lastLocationTimeMs = now

            // 判斷是否接近終點：當累計里程接近預估區間長度，或偵測到終點相機
            val remainingMeters = (estimatedZoneLengthMeters - totalDistanceMeters).coerceAtLeast(0.0)
            if (remainingMeters <= 400.0 && state == State.IN_ZONE) {
                state = State.APPROACHING_END
            }

            // 離開區間判斷
            if (totalDistanceMeters >= estimatedZoneLengthMeters + 100.0 || (remainingMeters <= 20.0 && speedKmh > 10)) {
                state = State.COMPLETED
            }
        }

        return getStatus(now)
    }

    /**
     * 檢查是否需要觸發「區間均速超速警告語音」（至少間隔 12 秒提醒一次）
     */
    fun shouldTriggerOverspeedVoice(now: Long = System.currentTimeMillis()): Boolean {
        val status = getStatus(now)
        if (status.state != State.IN_ZONE && status.state != State.APPROACHING_END) return false
        if (!status.isAverageOverspeed) return false
        if (now - lastOverspeedWarnTimeMs < 12_000L) return false

        lastOverspeedWarnTimeMs = now
        return true
    }

    fun markStartAnnounced() {
        hasAnnouncedStart = true
    }

    fun markEndAnnounced() {
        hasAnnouncedEnd = true
    }

    fun reset() {
        activeCamera = null
        state = State.IDLE
        entryTimeMs = 0L
        lastLocationTimeMs = 0L
        lastLat = 0.0
        lastLng = 0.0
        totalDistanceMeters = 0.0
        hasAnnouncedStart = false
        hasAnnouncedEnd = false
        lastOverspeedWarnTimeMs = 0L
    }

    companion object {
        private val lengthRegex = Regex("(\\d+(?:\\.\\d+)?)[Kk千][米公至到-]?(\\d+(?:\\.\\d+)?)?[Kk]?")

        fun parseZoneLengthMeters(address: String): Double {
            // 從地址中嘗試解析長度，如 "5K至0.5K" 或 "19K至23.1K"
            try {
                if (address.contains("至") || address.contains("到") || address.contains("-")) {
                    val numbers = Regex("(\\d+(?:\\.\\d+)?)").findAll(address).map { it.value.toDouble() }.toList()
                    if (numbers.size >= 2) {
                        val diff = kotlin.math.abs(numbers[1] - numbers[0])
                        if (diff in 0.5..30.0) {
                            return diff * 1000.0
                        }
                    }
                }
            } catch (_: Exception) {}
            return 3200.0 // 預設平均長度約 3.2 公里
        }
    }
}
