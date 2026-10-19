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
    val lastUpdatedTime: String = "--:--",
    val totalCamerasCount: Int = 0,
    val totalActiveEventsCount: Int = 0,
    val navState: NavState? = null
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
     * 比對車主行進方向 (0=北, 90=東, 180=南, 270=西) 與測速桿/路況方向文字是否相容
     */
    fun isDirectionCompatible(driverBearing: Float, directionText: String): Boolean {
        if (directionText.isBlank() || directionText.contains("雙向") || directionText.contains("全線")) {
            return true
        }
        val b = ((driverBearing % 360f) + 360f) % 360f
        // 注意：「南向北」或「北上」或「往北」代表車輛往北開；「北向南」或「南下」代表車輛往南開
        val goingNorth = directionText.contains("北上") || directionText.contains("往北") ||
                directionText.contains("南向北") || directionText.contains("南往北") ||
                (directionText.startsWith("北向") && !directionText.contains("北向南"))
        val goingSouth = directionText.contains("南下") || directionText.contains("往南") ||
                directionText.contains("北向南") || directionText.contains("北往南") ||
                (directionText.startsWith("南向") && !directionText.contains("南向北"))
        val goingEast = directionText.contains("東行") || directionText.contains("往東") ||
                directionText.contains("西向東") || directionText.contains("西往東") ||
                (directionText.startsWith("東向") && !directionText.contains("東向西"))
        val goingWest = directionText.contains("西行") || directionText.contains("往西") ||
                directionText.contains("東向西") || directionText.contains("東往西") ||
                (directionText.startsWith("西向") && !directionText.contains("西向東"))

        if (goingNorth && goingSouth) return true
        if (goingEast && goingWest) return true

        if (goingNorth) return angleDiff(b.toDouble(), 0.0) <= 85.0
        if (goingSouth) return angleDiff(b.toDouble(), 180.0) <= 85.0
        if (goingEast) return angleDiff(b.toDouble(), 90.0) <= 85.0
        if (goingWest) return angleDiff(b.toDouble(), 270.0) <= 85.0

        return true
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
