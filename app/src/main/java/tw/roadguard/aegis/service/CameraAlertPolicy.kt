package tw.roadguard.aegis.service

import tw.roadguard.aegis.model.CameraType
import tw.roadguard.aegis.model.SpeedCamera
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/** Shared by radar locking, voice alerts and the overlay. Distances are GPS straight-line meters. */
internal object CameraAlertPolicy {
    data class Thresholds(val farMeters: Double, val nearMeters: Double, val beepMeters: Double)

    private val freewayName = Regex("國道[一二三四五六七八九十0-9]|國[一二三四五六七八九十0-9]|高速公路")

    fun isFreeway(camera: SpeedCamera): Boolean {
        val road = camera.address
        // An intersection or a direction pointing toward a freeway is still a surface road.
        if (road.contains("路口") || road.contains("機慢車") || road.contains("方向")) return false
        return freewayName.containsMatchIn(road) || camera.city.startsWith("國道")
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

    fun distanceLabel(distanceMeters: Double): String = if (distanceMeters >= 1000.0) {
        String.format(Locale.TAIWAN, "前方 %.1f 公里", distanceMeters / 1000.0)
    } else {
        "前方 ${distanceMeters.roundToInt().coerceAtLeast(0)} 公尺"
    }

    fun message(camera: SpeedCamera, stage: Int, distanceMeters: Double, isOver: Boolean): String {
        val meters = ((distanceMeters / 50).toInt() * 50).coerceAtLeast(50)
        val kind = if (camera.type == CameraType.HOTSPOT) "經常出沒移動式測速" else camera.type.label
        val prefix = if (stage == 1) "注意，" else if (isOver) "警告！" else "接近，"
        val warning = if (stage == 2 && isOver) "，您目前已超速，請立即減速！" else ""
        return "${prefix}前方 $meters 公尺，$kind，速限 ${camera.speedLimit} 公里$warning"
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
