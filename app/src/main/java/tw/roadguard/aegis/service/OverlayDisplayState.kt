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
    val roadAlertColorHex: String = "#FBBF24"
)

internal object OverlayStateEvaluator {
    fun evaluate(
        snap: RadarSnapshot,
        currentZoneStatus: IntervalSpeedTracker.ZoneStatus?
    ): OverlayDisplayConfig {
        val cam = snap.nearestCamera
        val tunnel = snap.tunnelLaneAdvice
        val ev = snap.nearestRoadEvent
        val nav = snap.navState

        val isCameraAlert = cam != null
        val isZoneAlert = currentZoneStatus != null && (
            currentZoneStatus.state == IntervalSpeedTracker.State.IN_ZONE ||
            currentZoneStatus.state == IntervalSpeedTracker.State.APPROACHING_END
        )
        val isTunnelAlert = tunnel != null && tunnel.isApproachingEntrance
        val isRoadEventAlert = ev != null && ev.distanceMeters <= 1500.0
        val isNavTurnAlert = nav != null && nav.isNavigating && nav.distanceToNextTurnMeters <= 1000.0
        val isOverspeed = snap.isOverspeed

        val hasAlert = isCameraAlert || isZoneAlert || isTunnelAlert || isRoadEventAlert || isNavTurnAlert || isOverspeed

        if (!hasAlert) {
            return OverlayDisplayConfig(
                mode = OverlayMode.COMPACT_SPEED,
                speedText = "${snap.speedKmh}",
                isOverspeed = false
            )
        }

        var speedLimitText: String? = null
        var cameraTitleText: String? = null
        var cameraDistanceText: String? = null
        var roadAlertText: String? = null
        var roadAlertColorHex = "#FBBF24"

        when {
            cam != null -> {
                speedLimitText = "${cam.camera.speedLimit}"
                val icon = if (cam.camera.type == CameraType.HOTSPOT) "🚨" 
                           else if (cam.camera.type == CameraType.INTERVAL) "⏱️" 
                           else "📸"
                cameraTitleText = "$icon ${cam.camera.type.label}"
                cameraDistanceText = CameraAlertPolicy.distanceLabel(cam.distanceMeters)
            }
            isZoneAlert -> {
                speedLimitText = "${currentZoneStatus?.camera?.speedLimit ?: "--"}"
                cameraTitleText = "⏱️ 區間均速 ${currentZoneStatus?.averageSpeedKmh} km/h"
                cameraDistanceText = if (currentZoneStatus?.isAverageOverspeed == true) "⚠️ 均速超速！" 
                                     else "剩餘 ${currentZoneStatus?.remainingSeconds} 秒"
            }
            isTunnelAlert -> {
                speedLimitText = "90"
                val chosenSpeed = if (tunnel?.recommendedLane == "inner") tunnel.innerSpeedKmh else tunnel?.outerSpeedKmh
                val speedStr = if (chosenSpeed != null && chosenSpeed > 0) " (${chosenSpeed}km/h)" else ""
                cameraTitleText = "🚇 建議走${tunnel?.recommendedLaneName}$speedStr"
            }
            isNavTurnAlert -> {
                val distKmStr = String.format(Locale.TAIWAN, "%.1f", nav!!.remainingDistanceMeters / 1000.0)
                cameraTitleText = "🧭 ${nav.currentRoadName} (${distKmStr}km)"
            }
            isOverspeed -> {
                cameraTitleText = "⚠️ 請注意減速"
            }
        }

        when {
            isTunnelAlert -> {
                val distM = tunnel!!.distanceToEntranceMeters.roundToInt()
                roadAlertText = "⚠️ 距雪隧洞口 ${distM}m · 全程禁換車道"
                roadAlertColorHex = "#38BDF8"
            }
            isNavTurnAlert -> {
                val turnDist = if (nav!!.distanceToNextTurnMeters >= 1000) {
                    String.format(Locale.TAIWAN, "%.1fkm", nav.distanceToNextTurnMeters / 1000.0)
                } else {
                    "${nav.distanceToNextTurnMeters.roundToInt()}m"
                }
                roadAlertText = "${nav.maneuverIcon} $turnDist ${nav.instruction.take(12)}"
                roadAlertColorHex = "#38BDF8"
            }
            isRoadEventAlert -> {
                val distLabel = if (ev!!.distanceMeters >= 1000) {
                    String.format(Locale.TAIWAN, "%.1fkm", ev.distanceMeters / 1000.0)
                } else {
                    "${ev.distanceMeters.roundToInt()}m"
                }
                val icon = if (ev.event.severity == EventSeverity.CLOSURE) "🔴封閉" else "🟠施工"
                roadAlertText = "$icon $distLabel ${ev.event.roadName.take(10)}"
                roadAlertColorHex = if (ev.event.severity == EventSeverity.CLOSURE) "#FF4D6D" else "#FBBF24"
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
            roadAlertColorHex = roadAlertColorHex
        )
    }
}
