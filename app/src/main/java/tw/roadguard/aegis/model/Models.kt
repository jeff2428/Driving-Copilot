package tw.roadguard.aegis.model

import kotlin.math.*

enum class CameraType(val label: String) {
    FIXED("固定測速"),
    INTERVAL("區間測速"),
    TECH("科技執法"),
    HOTSPOT("經常出沒點"),
    CUSTOM("自訂測速黑點")
}

data class SpeedCamera(
    val id: String,
    val city: String,
    val region: String,
    val address: String,
    val dept: String,
    val lat: Double,
    val lng: Double,
    val directText: String,
    val speedLimit: Int,
    val type: CameraType,
    val isCustom: Boolean = false
)

enum class EventSeverity(val label: String, val priority: Int, val colorHex: String) {
    CLOSURE("道路封閉/管制", 1, "#FF2E4C"),
    CONSTRUCTION("道路施工/縮減", 2, "#FF9F1C"),
    HAZARD("交通事故/障礙", 3, "#B5179E"),
    CONGESTION("車多壅塞", 4, "#FACC15")
}

enum class RoadCategory(val label: String) {
    FREEWAY("國道高速"),
    EXPRESSWAY("省道/快速道路"),
    LOCAL("一般市區/縣道")
}

data class RoadEvent(
    val uid: String,
    val roadName: String,
    val areaName: String,
    val direction: String,
    val lat: Double,
    val lng: Double,
    val rawComment: String,
    val voiceSummary: String,
    val severity: EventSeverity,
    val category: RoadCategory,
    val isCleared: Boolean,
    val isActiveNow: Boolean,
    val scheduleNote: String,
    val happenTime: String,
    val sourceDetail: String,
    val isCustom: Boolean = false
)

data class ScoredCamera(
    val camera: SpeedCamera,
    val distanceMeters: Double,
    val relativeAngleDeg: Double
)

data class ScoredRoadEvent(
    val event: RoadEvent,
    val distanceMeters: Double,
    val relativeAngleDeg: Double
)

data class NavStep(
    val stepIndex: Int,
    val roadName: String,
    val instruction: String,
    val maneuverType: String,
    val maneuverModifier: String,
    val maneuverIcon: String,
    val distanceMeters: Double,
    val durationSec: Double,
    val maneuverLat: Double,
    val maneuverLng: Double,
    val stepPoints: List<Pair<Double, Double>> = emptyList()
)

data class NavRoute(
    val destinationName: String,
    val originLat: Double,
    val originLng: Double,
    val destLat: Double,
    val destLng: Double,
    val totalDistanceMeters: Double,
    val totalDurationSec: Double,
    val steps: List<NavStep>,
    val routePoints: List<Pair<Double, Double>>,
    val closuresOnRoute: List<ScoredRoadEvent> = emptyList(),
    val constructionsOnRoute: List<ScoredRoadEvent> = emptyList(),
    val camerasOnRoute: List<ScoredCamera> = emptyList(),
    val bypassWaypoint: Pair<Double, Double>? = null
)

data class NavState(
    val isNavigating: Boolean = false,
    val destinationName: String = "",
    val currentStepIndex: Int = 0,
    val currentStep: NavStep? = null,
    val nextStep: NavStep? = null,
    val distanceToTurnMeters: Double = 0.0,
    val distanceToNextTurnMeters: Double = 0.0,
    val remainingDistanceMeters: Double = 0.0,
    val remainingDistMeters: Double = 0.0,
    val remainingDurationSec: Double = 0.0,
    val remainingTimeSec: Double = 0.0,
    val isOffRoute: Boolean = false,
    val currentRoadName: String = "",
    val roadName: String = "",
    val maneuverIcon: String = "⬆️",
    val instruction: String = "",
    val nextHint: String = "",
    val etaFormatted: String = "--:--"
)

data class DestinationCandidate(
    val name: String,
    val address: String,
    val lat: Double,
    val lng: Double,
    val category: String = "地點",
    val distanceMeters: Double = 0.0
)

data class RouteScanResult(
    val destinationName: String,
    val originLat: Double,
    val originLng: Double,
    val destLat: Double,
    val destLng: Double,
    val distanceKm: Double,
    val durationMin: Int,
    val routePoints: List<Pair<Double, Double>>,
    val closuresOnRoute: List<ScoredRoadEvent>,
    val constructionsOnRoute: List<ScoredRoadEvent>,
    val hazardsOnRoute: List<ScoredRoadEvent>,
    val camerasOnRoute: List<ScoredCamera>,
    val bypassWaypoint: Pair<Double, Double>?,
    val navRoute: NavRoute? = null
)

data class HsuehshanAdvice(
    val direction: String = "S", // "S" (南下往宜蘭) or "N" (北上往台北)
    val directionLabel: String = "南下 (往宜蘭)",
    val towards: String = "往宜蘭",
    val distanceToEntranceMeters: Double = 0.0,
    val recommendedLane: String = "outer", // "inner" or "outer"
    val recommendedLaneName: String = "外側車道",
    val recommendedLaneSide: String = "右線",
    val reason: String = "外側車道較順暢",
    val diffKmh: Int = 0,
    val innerSpeedKmh: Int? = null,
    val outerSpeedKmh: Int? = null,
    val innerTravelMin: Double? = null,
    val outerTravelMin: Double? = null,
    val tunnelTravelMin: Double? = null,
    val alertEvent: String? = null,
    val isApproachingEntrance: Boolean = false,
    val dataTime: String = "--:--"
)

data class RadarSnapshot(
    val lat: Double = 24.1605,
    val lng: Double = 120.6311,
    val speedKmh: Int = 0,
    val bearingDeg: Float = 0f,
    val bearingLabel: String = "北",
    val isDrivingActive: Boolean = false,
    val isSimulating: Boolean = false,
    val simRouteName: String = "",
    val nearestCamera: ScoredCamera? = null,
    val isOverspeed: Boolean = false,
    val inIntervalZone: Boolean = false,
    val intervalAvgSpeedKmh: Int = 0,
    val intervalRemainingSec: Int = 0,
    val nearestRoadEvent: ScoredRoadEvent? = null,
    val nearbyEvents: List<ScoredRoadEvent> = emptyList(),
    val nearbyCameras: List<ScoredCamera> = emptyList(),
    val mapVisibleCameras: List<SpeedCamera> = emptyList(),
    val mapVisibleEvents: List<RoadEvent> = emptyList(),
    val lastUpdatedTime: String = "--:--",
    val totalCamerasCount: Int = 0,
    val totalActiveEventsCount: Int = 0,
    val navState: NavState? = null,
    val tunnelLaneAdvice: HsuehshanAdvice? = null
)

object GeoUtils {
    private const val EARTH_RADIUS_M = 6371000.0

    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }

    fun bearingDeg(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLambda = Math.toRadians(lng2 - lng1)
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun angleDiff(deg1: Double, deg2: Double): Double {
        val diff = abs(deg1 - deg2) % 360.0
        return if (diff > 180.0) 360.0 - diff else diff
    }

    fun compassLabel(deg: Float): String {
        val normalized = ((deg % 360f) + 360f) % 360f
        return when {
            normalized < 22.5f || normalized >= 337.5f -> "北"
            normalized < 67.5f -> "東北"
            normalized < 112.5f -> "東"
            normalized < 157.5f -> "東南"
            normalized < 202.5f -> "南"
            normalized < 247.5f -> "西南"
            normalized < 292.5f -> "西"
            else -> "西北"
        }
    }

    /**
     * 計算橫向偏離距離 (Cross-Track Distance, 公尺)：目標點偏離車頭行進軸線的垂直距離
     */
    fun crossTrackDistanceMeters(distanceMeters: Double, relativeAngleDeg: Double): Double {
        return abs(distanceMeters * sin(Math.toRadians(relativeAngleDeg)))
    }

    /**
     * 計算前向縱向距離 (Along-Track Distance, 公尺)：目標點在車頭正前方的投影距離
     */
    fun alongTrackDistanceMeters(distanceMeters: Double, relativeAngleDeg: Double): Double {
        return distanceMeters * cos(Math.toRadians(relativeAngleDeg))
    }

    /**
     * 判斷目前座標是否已進入國道5號雪山隧道內部 (坪林端 15.2K 與 頭城端 28.1K 之間走廊)
     */
    fun isInsideHsuehshanTunnel(lat: Double, lng: Double): Boolean {
        val aLat = 24.9365
        val aLng = 121.7142
        val bLat = 24.8512
        val bLng = 121.7960
        val dx = bLng - aLng
        val dy = bLat - aLat
        val lenSq = dx * dx + dy * dy
        if (lenSq == 0.0) return false
        val t = ((lng - aLng) * dx + (lat - aLat) * dy) / lenSq
        // t 介於 0.02 ~ 0.98 代表已進入兩端洞口連線區間內
        if (t !in 0.02..0.98) return false
        val projLat = aLat + t * dy
        val projLng = aLng + t * dx
        val lateralDist = distanceMeters(lat, lng, projLat, projLng)
        return lateralDist <= 650.0
    }

    private data class ParsedDir(
        val singleNorth: Boolean = false,
        val singleSouth: Boolean = false,
        val singleEast: Boolean = false,
        val singleWest: Boolean = false,
        val axisNorthSouth: Boolean = false,
        val axisEastWest: Boolean = false
    ) {
        val hasSingle: Boolean
            get() = singleNorth || singleSouth || singleEast || singleWest
    }

    private fun parseDirectionText(text: String): ParsedDir {
        if (text.isBlank()) return ParsedDir()
        val isBidirNS = text.contains("南北雙向") || text.contains("南北向") || text.contains("往南北") || text == "南北"
        val isBidirEW = text.contains("東西雙向") || text.contains("東西向") || text.contains("往東西") || text == "東西"
        val isGenericBidir = text.contains("雙向") || text.contains("全線") || text.contains("全向")

        if (isBidirNS) return ParsedDir(axisNorthSouth = true)
        if (isBidirEW) return ParsedDir(axisEastWest = true)
        if (isGenericBidir) return ParsedDir()

        val goingNorth = text.contains("北上") || text.contains("往北") ||
                text.contains("南向北") || text.contains("南往北") ||
                (text.contains("北向") && !text.contains("北向南"))
        val goingSouth = text.contains("南下") || text.contains("往南") ||
                text.contains("北向南") || text.contains("北往南") ||
                (text.contains("南向") && !text.contains("南向北"))
        val goingEast = text.contains("東行") || text.contains("往東") ||
                text.contains("西向東") || text.contains("西往東") ||
                (text.contains("東向") && !text.contains("東向西"))
        val goingWest = text.contains("西行") || text.contains("往西") ||
                text.contains("東向西") || text.contains("東往西") ||
                (text.contains("西向") && !text.contains("西向東"))

        if (goingNorth && goingSouth) return ParsedDir(axisNorthSouth = true)
        if (goingEast && goingWest) return ParsedDir(axisEastWest = true)

        return ParsedDir(
            singleNorth = goingNorth,
            singleSouth = goingSouth,
            singleEast = goingEast,
            singleWest = goingWest
        )
    }

    /**
     * 同時結合測速桿「設置地址 (address)」與「拍攝方向 (directText)」進行高精度方向匹配
     */
    fun isCameraDirectionCompatible(driverBearing: Float, address: String, directText: String): Boolean {
        val b = ((driverBearing % 360f) + 360f) % 360f
        val dDir = parseDirectionText(directText)
        val aDir = parseDirectionText(address)

        // 若 directText 為雙向，但 address 明確標註單向（例如 address="中山路五段南下", direct="南北雙向"），以單向為準
        val effective = when {
            dDir.hasSingle -> dDir
            aDir.hasSingle -> aDir
            dDir.axisNorthSouth || aDir.axisNorthSouth -> ParsedDir(axisNorthSouth = true)
            dDir.axisEastWest || aDir.axisEastWest -> ParsedDir(axisEastWest = true)
            else -> ParsedDir()
        }

        val isFreeway5 = address.contains("國道五號") || address.contains("國道5號") || address.contains("雪山隧道")

        if (effective.hasSingle) {
            if (isFreeway5) {
                if (effective.singleNorth) return angleDiff(b.toDouble(), 325.0) <= 65.0 || angleDiff(b.toDouble(), 0.0) <= 58.0
                if (effective.singleSouth) return angleDiff(b.toDouble(), 140.0) <= 65.0 || angleDiff(b.toDouble(), 180.0) <= 58.0
            }
            if (effective.singleNorth && angleDiff(b.toDouble(), 0.0) <= 58.0) return true
            if (effective.singleSouth && angleDiff(b.toDouble(), 180.0) <= 58.0) return true
            if (effective.singleEast && angleDiff(b.toDouble(), 90.0) <= 58.0) return true
            if (effective.singleWest && angleDiff(b.toDouble(), 270.0) <= 58.0) return true
            return false
        }

        // 南北雙向道路：過濾東西向橫越路口的車輛
        if (effective.axisNorthSouth) {
            return angleDiff(b.toDouble(), 0.0) <= 56.0 || angleDiff(b.toDouble(), 180.0) <= 56.0
        }
        // 東西雙向道路：過濾南北向橫越路口的車輛
        if (effective.axisEastWest) {
            return angleDiff(b.toDouble(), 90.0) <= 56.0 || angleDiff(b.toDouble(), 270.0) <= 56.0
        }

        return true
    }

    /**
     * 比對車主行進方向 (0=北, 90=東, 180=南, 270=西) 與路況方向文字是否相容
     */
    fun isDirectionCompatible(driverBearing: Float, directionText: String): Boolean {
        return isCameraDirectionCompatible(driverBearing, "", directionText)
    }

    /**
     * 計算點 P 到線段 AB 的最短距離 (公尺)
     */
    fun pointToSegmentMeters(
        pLat: Double, pLng: Double,
        aLat: Double, aLng: Double,
        bLat: Double, bLng: Double
    ): Double {
        val dx = bLng - aLng
        val dy = bLat - aLat
        if (dx == 0.0 && dy == 0.0) {
            return distanceMeters(pLat, pLng, aLat, aLng)
        }
        val t = (((pLng - aLng) * dx + (pLat - aLat) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        val projLat = aLat + t * dy
        val projLng = aLng + t * dx
        return distanceMeters(pLat, pLng, projLat, projLng)
    }
}
