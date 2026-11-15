package tw.roadguard.aegis.service

import tw.roadguard.aegis.model.CameraType
import tw.roadguard.aegis.model.EventSeverity
import tw.roadguard.aegis.model.RadarSnapshot
import java.util.Locale
import kotlin.math.roundToInt

internal enum class OverlayMode {
    COMPACT_SPEED, // 平時無提醒：只顯示時速
    ALERT          // 有提醒：動態展開並顯示警示內容
}

internal data class OverlayDisplayConfig(
    val mode: OverlayMode,
    val speedText: String,
    val isOverspeed: Boolean,
    val speedLimitText: String? = null,
    val cameraTitleText: String? = null,
    val cameraDistanceText: String? = null,
    val roadAlertText: String? = null,
    val roadAlertColorHex: String = "#FBBF24",
    val isPoliceAlert: Boolean = false,
    val progressFraction: Float = 0f,
    val isDeadReckoning: Boolean = false
)

internal object OverlayStateEvaluator {
    fun evaluate(
        snap: RadarSnapshot,
        currentZoneStatus: IntervalSpeedTracker.ZoneStatus?
    ): OverlayDisplayConfig {
        val cam = snap.nearestCamera
        val tunnel = snap.tunnelLaneAdvice
        val ev = snap.nearestRoadEvent

        val isCameraAlert = cam != null
        val isZoneAlert = currentZoneStatus != null && (
            currentZoneStatus.state == IntervalSpeedTracker.State.IN_ZONE ||
            currentZoneStatus.state == IntervalSpeedTracker.State.APPROACHING_END
        )
        val isTunnelAlert = tunnel != null && tunnel.isApproachingEntrance
        val isRoadEventAlert = ev != null && ev.distanceMeters <= 1500.0
        val isOverspeed = snap.isOverspeed || (isZoneAlert && currentZoneStatus?.isAverageOverspeed == true)
        val isDeadReckoning = (currentZoneStatus?.isDeadReckoning == true) || snap.isDeadReckoning

        val hasAlert = isCameraAlert || isZoneAlert || isTunnelAlert || isRoadEventAlert || isOverspeed

        if (!hasAlert) {
            return OverlayDisplayConfig(
                mode = OverlayMode.COMPACT_SPEED,
                speedText = "${snap.speedKmh}",
                isOverspeed = false,
                progressFraction = 0f,
                isDeadReckoning = false
            )
        }

        var speedLimitText: String? = null
        var cameraTitleText: String? = null
        var cameraDistanceText: String? = null
        var roadAlertText: String? = null
        var roadAlertColorHex = "#FBBF24"
        var progressFraction = 0f

        when {
            cam != null && !(isZoneAlert && currentZoneStatus?.isAverageOverspeed == true) -> {
                speedLimitText = "${cam.camera.speedLimit}"
                val isHotspot = cam.camera.type == CameraType.HOTSPOT
                val icon = if (isHotspot) "🚔"
                           else if (cam.camera.type == CameraType.INTERVAL) "⏱️"
                           else "📸"
                val label = if (isHotspot) "警車出沒" else cam.camera.type.label
                cameraTitleText = if (isOverspeed) "$icon $label · 已超速" else "$icon $label"
                cameraDistanceText = CameraAlertPolicy.distanceLabel(cam.distanceMeters)
                val farDist = CameraAlertPolicy.thresholds(cam.camera, snap.speedKmh.coerceAtLeast(60)).farMeters.coerceAtLeast(300.0)
                progressFraction = (1.0 - (cam.distanceMeters / farDist)).toFloat().coerceIn(0.05f, 1.0f)
            }
            isZoneAlert -> {
                speedLimitText = "${currentZoneStatus?.camera?.speedLimit ?: "--"}"
                cameraTitleText = "⏱️ 區間均速 ${currentZoneStatus?.averageSpeedKmh} km/h"
                cameraDistanceText = if (currentZoneStatus?.isAverageOverspeed == true) "⚠️ 均速超速！"
                                     else "剩餘 ${currentZoneStatus?.remainingSeconds} 秒"
                val zoneLen = currentZoneStatus?.zoneLengthMeters ?: 0.0
                progressFraction = if (zoneLen > 0.0) {
                    (currentZoneStatus!!.distanceTraveledMeters / zoneLen).toFloat().coerceIn(0.05f, 1.0f)
                } else {
                    snap.intervalProgressFraction.coerceIn(0.05f, 1.0f)
                }
            }
            isTunnelAlert -> {
                speedLimitText = "90"
                val chosenSpeed = if (tunnel?.recommendedLane == "inner") tunnel.innerSpeedKmh else tunnel?.outerSpeedKmh
                val speedStr = if (chosenSpeed != null && chosenSpeed > 0) " (${chosenSpeed}km/h)" else ""
                cameraTitleText = if (tunnel?.hasLaneRecommendation == true) "🚇 建議走${tunnel.recommendedLaneName}$speedStr" else "雪隧 · 暫無即時資料"
                progressFraction = ((2500.0 - tunnel!!.distanceToEntranceMeters) / 2180.0).toFloat().coerceIn(0.05f, 1.0f)
            }
            isOverspeed -> {
                cameraTitleText = "⚠️ 請注意減速"
                progressFraction = if (isRoadEventAlert) {
                    ((1500.0 - ev!!.distanceMeters) / 1500.0).toFloat().coerceIn(0.05f, 1.0f)
                } else {
                    1.0f
                }
            }
        }

        when {
            isTunnelAlert -> {
                val distM = tunnel!!.distanceToEntranceMeters.roundToInt()
                roadAlertText = "⚠️ 距雪隧洞口 ${distM}m · 全程禁換車道"
                roadAlertColorHex = "#38BDF8"
            }
            isRoadEventAlert -> {
                val distLabel = if (ev!!.distanceMeters >= 1000) {
                    String.format(Locale.TAIWAN, "%.1fkm", ev.distanceMeters / 1000.0)
                } else {
                    "${ev.distanceMeters.roundToInt()}m"
                }
                val icon = when (ev.event.severity) {
                    EventSeverity.CLOSURE -> "🔴封閉"
                    EventSeverity.CONSTRUCTION -> "🟠施工"
                    EventSeverity.HAZARD -> "事故／障礙"
                    EventSeverity.CONGESTION -> "壅塞"
                }
                roadAlertText = "$icon $distLabel ${ev.event.roadName.take(10)}"
                roadAlertColorHex = if (ev.event.severity == EventSeverity.CLOSURE) "#FF4D6D" else "#FBBF24"
                if (progressFraction == 0f) {
                    progressFraction = ((1500.0 - ev.distanceMeters) / 1500.0).toFloat().coerceIn(0.05f, 1.0f)
                }
            }
        }

        return OverlayDisplayConfig(
            mode = OverlayMode.ALERT,
            speedText = "${snap.speedKmh}",
            isOverspeed = isOverspeed,
            speedLimitText = speedLimitText,
            cameraTitleText = cameraTitleText,
            cameraDistanceText = cameraDistanceText,
            roadAlertText = roadAlertText,
            roadAlertColorHex = roadAlertColorHex,
            isPoliceAlert = cam?.camera?.type == CameraType.HOTSPOT,
            progressFraction = progressFraction,
            isDeadReckoning = isDeadReckoning
        )
    }
}
