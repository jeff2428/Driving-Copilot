package tw.roadguard.aegis.service

import tw.roadguard.aegis.model.CameraType
import tw.roadguard.aegis.model.SpeedCamera
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 測速照相警報策略與幾何判定引擎 (Camera Alert Policy & Geometry Engine)
 * 負責國道/快速/高架立體分流、向量通過門閥、科技執法細分、以及兩階段語音詞組裝。
 */
internal object CameraAlertPolicy {
    data class Thresholds(val farMeters: Double, val nearMeters: Double, val beepMeters: Double)

    enum class TechType(val label: String) {
        RED_LIGHT("取締闖紅燈"),
        PEDESTRIAN("取締未禮讓行人"),
        ILLEGAL_TURN("取締違規轉彎與標線"),
        DOUBLE_WHITE_LINE("取締跨越雙白線"),
        ILLEGAL_PARKING("取締違規停車"),
        SPECIAL_LANE("取締佔用專用道"),
        GENERAL("多功能科技執法")
    }

    private val freewayName = Regex("國道[一二三四五六七八九十0-9]|國[一二三四五六七八九十0-9]|高速公路")
    private val elevatedRegex = Regex("高架|快速|台6[1-8]|台7[2-8]|台8[2-8]|市民大道高架|建國高架|新生高架|洲美|環河快|環東|水源快速")

    fun isFreeway(camera: SpeedCamera): Boolean {
        val road = camera.address
        // 排除引道、路口與機慢車道
        if (road.contains("路口") || road.contains("機慢車") || road.contains("方向")) return false
        return freewayName.containsMatchIn(road) || camera.city.startsWith("國道")
    }

    fun isElevatedRoad(camera: SpeedCamera): Boolean {
        val road = camera.address
        if (road.contains("路口") || road.contains("街口") || road.contains("巷口") || road.contains("平面") || road.contains("機慢車") || road.contains("橋下")) return false
        return elevatedRegex.containsMatchIn(road)
    }

    fun parseTechEnforcementType(camera: SpeedCamera): TechType {
        val text = camera.address + " " + camera.directText
        return when {
            text.contains("闖紅燈") || text.contains("紅燈") -> TechType.RED_LIGHT
            text.contains("不停讓") || text.contains("未禮讓") || text.contains("行人") -> TechType.PEDESTRIAN
            text.contains("雙白線") || text.contains("跨越") -> TechType.DOUBLE_WHITE_LINE
            text.contains("標誌") || text.contains("標線") || text.contains("轉彎") || text.contains("左轉") -> TechType.ILLEGAL_TURN
            text.contains("違停") || text.contains("停車") || text.contains("臨停") -> TechType.ILLEGAL_PARKING
            text.contains("公車") || text.contains("專用道") -> TechType.SPECIAL_LANE
            else -> TechType.GENERAL
        }
    }

    fun thresholds(camera: SpeedCamera, speedKmh: Int): Thresholds = when {
        isFreeway(camera) -> Thresholds(1000.0, 400.0, 550.0)
        camera.speedLimit >= 80 || speedKmh >= 80 -> Thresholds(900.0, 380.0, 550.0)
        camera.speedLimit >= 60 || speedKmh >= 60 -> Thresholds(600.0, 260.0, 400.0)
        else -> Thresholds(420.0, 180.0, 280.0)
    }

    fun lockRadiusMeters(camera: SpeedCamera, speedKmh: Int): Double {
        val originalRadius = when {
            camera.speedLimit >= 80 || speedKmh >= 82 -> 1150.0
            camera.speedLimit >= 60 || speedKmh >= 58 -> 750.0
            else -> 500.0
        }
        return maxOf(originalRadius, thresholds(camera, speedKmh).farMeters)
    }

    fun isOnNavigationCorridor(cameraDistance: Double?, vehicleDistance: Double?): Boolean =
        cameraDistance != null && vehicleDistance != null && cameraDistance <= 45.0 && vehicleDistance <= 55.0

    fun excludeAtLowSpeed(
        camera: SpeedCamera, speedKmh: Int, simulating: Boolean, insideTunnel: Boolean,
        alreadyApproaching: Boolean, onNavigationCorridor: Boolean
    ): Boolean {
        val isRamp = listOf("匝道", "出口", "入口", "交流道").any(camera.address::contains)
        return !simulating && !insideTunnel && speedKmh in 1..48 && camera.speedLimit >= 100 &&
            camera.address.contains("國道") && !isRamp && !alreadyApproaching && !onNavigationCorridor
    }

    /**
     * 法向量通過門閥 (Vector Pass Gate)：
     * 利用沿行進方向之投影距離 (alongTrack) 與橫向偏距 (crossTrack)，
     * 只有在車輛曾真實接近至特定門檻半徑且跨越垂直法平面 (alongTrack <= -5m) 時才判定通過，
     * 徹底杜絕山路急彎 (夾角瞬間轉為 >72°) 之提早誤鳴。
     */
    fun isCameraPassedByVector(
        currentDist: Double,
        relAngleDeg: Double,
        minApproachedDist: Double,
        isFreewayOrElevated: Boolean
    ): Boolean {
        val approachLimit = if (isFreewayOrElevated) 80.0 else 45.0
        // 車輛必須曾在有效物理門檻內逼近該相機
        if (minApproachedDist > approachLimit) return false

        val rad = Math.toRadians(relAngleDeg)
        val alongTrack = currentDist * cos(rad)
        val crossTrack = currentDist * sin(rad)

        // 1. 沿車頭行進方向已越過法平面 (<= -5.0m 在車尾方向) 且橫向偏距在道路寬度內 (<= 40m)
        val passedOrthogonal = alongTrack <= -5.0 && abs(crossTrack) <= 40.0
        // 2. 或極近距離 (<= 35m) 角度已完全轉向至側後方 (> 95度)
        val passedSide = relAngleDeg > 95.0 && currentDist <= 35.0

        return passedOrthogonal || passedSide
    }

    fun distanceLabel(distanceMeters: Double): String = if (distanceMeters >= 1000.0) {
        String.format(Locale.TAIWAN, "前方 %.1f 公里", distanceMeters / 1000.0)
    } else {
        "前方 ${distanceMeters.roundToInt().coerceAtLeast(0)} 公尺"
    }

    fun message(camera: SpeedCamera, stage: Int, distanceMeters: Double, isOver: Boolean): String {
        val meters = ((distanceMeters / 50).toInt() * 50).coerceAtLeast(50)
        val kind = when (camera.type) {
            CameraType.HOTSPOT -> "經常出沒移動式測速"
            CameraType.TECH -> {
                val techType = parseTechEnforcementType(camera)
                if (techType == TechType.GENERAL) "多功能科技執法" else "科技執法，${techType.label}"
            }
            CameraType.INTERVAL -> "區間測速"
            else -> camera.type.label
        }
        val prefix = if (stage == 1) "注意，" else if (isOver) "警告！" else "接近，"
        val warning = if (stage == 2 && isOver) "，您目前已超速，請立即減速！" else ""
        val speedStr = if (camera.speedLimit > 0) "，速限 ${camera.speedLimit} 公里" else ""
        return "${prefix}前方 $meters 公尺，$kind$speedStr$warning"
    }
}

/** A null result suppresses all alerts; zero allows a recurring overspeed beep without repeating speech. */
internal class CameraAlertTracker {
    private val stages = ConcurrentHashMap<String, Int>()
    private val distances = ConcurrentHashMap<String, Double>()

    fun update(camera: SpeedCamera, distanceMeters: Double, speedKmh: Int): Int? {
        val previousDistance = distances.put(camera.id, distanceMeters)
        if (previousDistance != null && distanceMeters > previousDistance + 6.0) return null
        val thresholds = CameraAlertPolicy.thresholds(camera, speedKmh)
        val stage = when {
            distanceMeters <= thresholds.nearMeters -> 2
            distanceMeters <= thresholds.farMeters -> 1
            else -> return null
        }
        if (stage <= (stages[camera.id] ?: 0)) return 0
        stages[camera.id] = stage
        return stage
    }

    fun clear() {
        stages.clear()
        distances.clear()
    }
}
