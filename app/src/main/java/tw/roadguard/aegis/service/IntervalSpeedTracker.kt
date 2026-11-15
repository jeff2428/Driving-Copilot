package tw.roadguard.aegis.service

import android.content.Context
import org.json.JSONObject
import tw.roadguard.aegis.model.CameraType
import tw.roadguard.aegis.model.GeoUtils
import tw.roadguard.aegis.model.SpeedCamera
import kotlin.math.roundToInt

/**
 * 區間測速完整生命週期引擎
 * 追蹤起點、在區間內累積真實行駛里程與時間計算精準均速，並提供安全通行時間倒數與超速預警
 * 支援隧道內 GPS 斷訊慣性推估 (Dead Reckoning)、進程重啟快照持久化 (Snapshot Persistence) 與超時安全退場
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
        val displayMessage: String,
        val isDeadReckoning: Boolean = false,
        val zoneLengthMeters: Double = 3200.0
    )

    data class IntervalSessionSnapshot(
        val version: Int = 1,
        val cameraId: String,
        val speedLimit: Int,
        val address: String,
        val zoneLengthMeters: Double,
        val entryTimeMs: Long,
        val lastLocationTimeMs: Long,
        val totalDistanceMeters: Double,
        val lastSpeedKmh: Int,
        val isDeadReckoning: Boolean,
        val state: State,
        val hasAnnouncedStart: Boolean
    )

    private var activeCamera: SpeedCamera? = null
    private var state: State = State.IDLE
    private var entryTimeMs: Long = 0L
    private var lastLocationTimeMs: Long = 0L
    private var lastLat: Double = 0.0
    private var lastLng: Double = 0.0
    private var totalDistanceMeters: Double = 0.0
    private var estimatedZoneLengthMeters: Double = 3000.0

    // 隧道盲區慣性推估 (Dead Reckoning) 狀態
    var isDeadReckoning: Boolean = false
        private set
    private var lastKnownSpeedKmh: Int = 0
    private var lastDeadReckoningTickTimeMs: Long = 0L

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
                displayMessage = "",
                isDeadReckoning = false,
                zoneLengthMeters = 0.0
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

        val prefix = if (isDeadReckoning) "[隧道推估] " else ""
        val msg = when (state) {
            State.APPROACHING_START -> "即將進入區間測速起點 (速限 ${cam.speedLimit})"
            State.IN_ZONE -> if (isOverspeed) "⚠️ ${prefix}區間均速 $avgSpeed km/h 已超速！" else "${prefix}區間均速 $avgSpeed km/h (安全通行剩餘 ${remainingSec}s)"
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
            displayMessage = msg,
            isDeadReckoning = isDeadReckoning,
            zoneLengthMeters = estimatedZoneLengthMeters
        )
    }

    /**
     * 隧道內 GPS 斷訊慣性推估 (Dead Reckoning Tick)
     * 當進入長隧道或盲區無定位時，以最後已知時速推算虛擬行駛里程與剩餘安全秒數
     */
    fun tickDeadReckoning(now: Long = System.currentTimeMillis()): ZoneStatus {
        if (state != State.IN_ZONE && state != State.APPROACHING_END) {
            return getStatus(now)
        }

        // 1. 斷訊超過 15 分鐘 (900 秒)，觸發 ERR_DR_TIMEOUT 安全退場
        if (lastLocationTimeMs > 0L && (now - lastLocationTimeMs) > 900_000L) {
            val distanceAtTimeout = totalDistanceMeters
            reset()
            return ZoneStatus(
                state = State.IDLE,
                camera = null,
                averageSpeedKmh = 0,
                distanceTraveledMeters = distanceAtTimeout,
                elapsedSeconds = 0L,
                remainingSeconds = 0,
                isAverageOverspeed = false,
                displayMessage = "GPS 訊號中斷過久，暫停測速推估",
                isDeadReckoning = false,
                zoneLengthMeters = 0.0
            )
        }

        // 2. 斷訊超過 3 秒啟動盲區推估
        if (lastLocationTimeMs > 0L && (now - lastLocationTimeMs) >= 3000L) {
            if (!isDeadReckoning) {
                isDeadReckoning = true
                lastDeadReckoningTickTimeMs = now
            } else {
                val dtSec = ((now - lastDeadReckoningTickTimeMs).coerceAtLeast(0L)) / 1000.0
                if (dtSec > 0.0) {
                    val simSpeed = if (lastKnownSpeedKmh > 10) lastKnownSpeedKmh else (activeCamera?.speedLimit ?: 60)
                    val metersPerSec = simSpeed / 3.6
                    totalDistanceMeters += metersPerSec * dtSec
                    lastDeadReckoningTickTimeMs = now
                }
            }
        }

        // 3. 檢查推估里程是否已接近終點
        val remainingMeters = (estimatedZoneLengthMeters - totalDistanceMeters).coerceAtLeast(0.0)
        if (remainingMeters <= 400.0 && state == State.IN_ZONE) {
            state = State.APPROACHING_END
        }

        return getStatus(now)
    }

    fun onLocationUpdate(
        lat: Double,
        lng: Double,
        speedKmh: Int,
        nearestIntervalCamera: SpeedCamera?,
        distanceToCameraMeters: Double,
        now: Long = System.currentTimeMillis()
    ): ZoneStatus {
        lastKnownSpeedKmh = speedKmh

        // 若原先處於盲區推估狀態，GPS 恢復後退出推估模式
        if (isDeadReckoning) {
            isDeadReckoning = false
            lastDeadReckoningTickTimeMs = 0L
        }

        // 1. 若處於 IDLE，偵測是否有接近中的區間測速起點
        if (state == State.IDLE) {
            if (nearestIntervalCamera != null && distanceToCameraMeters <= 1200.0) {
                activeCamera = nearestIntervalCamera
                state = State.APPROACHING_START
                estimatedZoneLengthMeters = parseZoneLengthMeters(nearestIntervalCamera.address)
            }
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

    fun isZoneActive(): Boolean {
        return state == State.IN_ZONE || state == State.APPROACHING_END
    }

    /**
     * 建立當前區間狀態快照物件
     */
    fun createSnapshot(): IntervalSessionSnapshot? {
        val cam = activeCamera ?: return null
        if (state != State.IN_ZONE && state != State.APPROACHING_END) return null
        return IntervalSessionSnapshot(
            version = 1,
            cameraId = cam.id,
            speedLimit = cam.speedLimit,
            address = cam.address,
            zoneLengthMeters = estimatedZoneLengthMeters,
            entryTimeMs = entryTimeMs,
            lastLocationTimeMs = lastLocationTimeMs,
            totalDistanceMeters = totalDistanceMeters,
            lastSpeedKmh = lastKnownSpeedKmh,
            isDeadReckoning = isDeadReckoning,
            state = state,
            hasAnnouncedStart = hasAnnouncedStart
        )
    }

    /**
     * 從快照物件無縫復原狀態機
     */
    fun restoreFromSnapshot(snapshot: IntervalSessionSnapshot, now: Long = System.currentTimeMillis()): Boolean {
        if (snapshot.version != 1) return false
        // 逾時 2 小時 (7200 秒 = 7,200,000 毫秒) 拒絕還原 (ERR_SNAP_EXPIRED)
        if (snapshot.entryTimeMs <= 0L || (now - snapshot.entryTimeMs) > 7_200_000L) {
            return false
        }
        // 距離超過 1.5 倍長度拒絕還原 (ERR_SNAP_CORRUPT)
        if (snapshot.totalDistanceMeters < 0.0 || snapshot.totalDistanceMeters > snapshot.zoneLengthMeters * 1.5) {
            return false
        }

        activeCamera = SpeedCamera(
            id = snapshot.cameraId,
            city = "",
            region = "",
            address = snapshot.address,
            dept = "",
            lat = lastLat,
            lng = lastLng,
            directText = "",
            speedLimit = snapshot.speedLimit,
            type = CameraType.INTERVAL
        )
        state = snapshot.state
        estimatedZoneLengthMeters = snapshot.zoneLengthMeters
        entryTimeMs = snapshot.entryTimeMs
        lastLocationTimeMs = snapshot.lastLocationTimeMs
        totalDistanceMeters = snapshot.totalDistanceMeters
        lastKnownSpeedKmh = snapshot.lastSpeedKmh
        isDeadReckoning = snapshot.isDeadReckoning
        hasAnnouncedStart = snapshot.hasAnnouncedStart
        return true
    }

    /**
     * 將快照原子化寫入 SharedPreferences
     */
    fun saveSnapshotToPrefs(context: Context) {
        val snapshot = createSnapshot() ?: return
        try {
            val json = JSONObject().apply {
                put("version", snapshot.version)
                put("cameraId", snapshot.cameraId)
                put("speedLimit", snapshot.speedLimit)
                put("address", snapshot.address)
                put("zoneLengthMeters", snapshot.zoneLengthMeters)
                put("entryTimeMs", snapshot.entryTimeMs)
                put("lastLocationTimeMs", snapshot.lastLocationTimeMs)
                put("totalDistanceMeters", snapshot.totalDistanceMeters)
                put("lastSpeedKmh", snapshot.lastSpeedKmh)
                put("isDeadReckoning", snapshot.isDeadReckoning)
                put("state", snapshot.state.name)
                put("hasAnnouncedStart", snapshot.hasAnnouncedStart)
            }
            val prefs = context.getSharedPreferences("roadguard_interval_snapshot", Context.MODE_PRIVATE)
            prefs.edit().putString("interval_session_snapshot_v1", json.toString()).apply()
        } catch (_: Exception) {}
    }

    /**
     * 從 SharedPreferences 讀取並還原快照
     */
    fun restoreFromSnapshot(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        try {
            val prefs = context.getSharedPreferences("roadguard_interval_snapshot", Context.MODE_PRIVATE)
            val jsonStr = prefs.getString("interval_session_snapshot_v1", null) ?: return false
            val json = JSONObject(jsonStr)
            val snapshot = IntervalSessionSnapshot(
                version = json.optInt("version", 1),
                cameraId = json.getString("cameraId"),
                speedLimit = json.getInt("speedLimit"),
                address = json.optString("address", ""),
                zoneLengthMeters = json.getDouble("zoneLengthMeters"),
                entryTimeMs = json.getLong("entryTimeMs"),
                lastLocationTimeMs = json.getLong("lastLocationTimeMs"),
                totalDistanceMeters = json.getDouble("totalDistanceMeters"),
                lastSpeedKmh = json.optInt("lastSpeedKmh", 0),
                isDeadReckoning = json.optBoolean("isDeadReckoning", false),
                state = State.valueOf(json.optString("state", State.IN_ZONE.name)),
                hasAnnouncedStart = json.optBoolean("hasAnnouncedStart", true)
            )
            val restored = restoreFromSnapshot(snapshot, now)
            if (!restored) {
                clearSnapshot(context)
            }
            return restored
        } catch (_: Exception) {
            clearSnapshot(context)
            return false
        }
    }

    /**
     * 清除持久化快照
     */
    fun clearSnapshot(context: Context) {
        try {
            val prefs = context.getSharedPreferences("roadguard_interval_snapshot", Context.MODE_PRIVATE)
            prefs.edit().remove("interval_session_snapshot_v1").apply()
        } catch (_: Exception) {}
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
        isDeadReckoning = false
        lastKnownSpeedKmh = 0
        lastDeadReckoningTickTimeMs = 0L
    }

    companion object {
        private const val kilometerMark = """(\d+(?:\.\d+)?)\s*(K|公里)?(?:\s*\+\s*(\d{1,3})\s*[m公尺]*)?"""
        private val mileageRange = Regex(
            kilometerMark + """\s*(?:\([^)]*\)|（[^）]*）)?\s*(?:至|到|[-–~～])\s*""" + kilometerMark,
            RegexOption.IGNORE_CASE
        )

        fun parseZoneLengthMeters(address: String): Double {
            val range = mileageRange.findAll(address).firstOrNull {
                it.groups[2] != null || it.groups[5] != null
            } ?: return 3200.0
            fun meters(km: Int, offset: Int): Double =
                range.groupValues[km].toDouble() * 1000 + (range.groups[offset]?.value?.toInt() ?: 0)
            val length = kotlin.math.abs(meters(1, 3) - meters(4, 6))
            return if (length in 500.0..30_000.0) length else 3200.0
        }
    }
}
