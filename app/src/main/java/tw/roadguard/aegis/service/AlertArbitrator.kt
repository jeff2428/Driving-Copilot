package tw.roadguard.aegis.service

import tw.roadguard.aegis.model.EventSeverity
import tw.roadguard.aegis.model.RoadEvent
import tw.roadguard.aegis.model.CameraType
import tw.roadguard.aegis.model.SpeedCamera

enum class AlertPriority(val level: Int) {
    EMERGENCY_OVERSPEED(1), // 最高優先級：超速警告
    INTERVAL_END(2),        // 次高：區間終點結算
    TECH_CAMERA(3),         // 一般相機/科技執法
    ROAD_CLOSURE(4),        // 道路封閉
    ROAD_WORK(5),           // 一般施工路況
    IDLE(99)
}

enum class InCallAlertMode {
    MUTE_VISUAL,            // 通話中靜音，懸浮窗視覺閃爍 (預設)
    DUCKING,                // 降低通話音量播報
    NORMAL                  // 正常播報
}

data class ArbitrationInput(
    val currentSpeedKmh: Int,
    val speedLimitKmh: Int,
    val distanceToCameraMeters: Double? = null,
    val nearestCamera: SpeedCamera? = null,
    val activeRoadEvent: RoadEvent? = null,
    val isIntervalEnd: Boolean = false,
    val intervalAvgSpeedKmh: Int = 0,
    val isInCall: Boolean = false,
    val inCallAlertMode: InCallAlertMode = InCallAlertMode.MUTE_VISUAL
)

data class ArbitrationOutput(
    val priority: AlertPriority,
    val isVoiceAllowed: Boolean,
    val isUrgentTone: Boolean,
    val isFlushQueue: Boolean,
    val isVisualFlashRequired: Boolean,
    val speechText: String?,
    val displayMessage: String
)

/**
 * 警報多事件優先級仲裁引擎
 * 對標 doc/proposal.md 2.3 與 doc/data-contracts.md 2
 */
object AlertArbitrator {

    fun arbitrate(input: ArbitrationInput): ArbitrationOutput {
        val hasCamera = input.nearestCamera != null && (input.distanceToCameraMeters ?: Double.MAX_VALUE) <= 1000.0
        val isHotspot = input.nearestCamera?.type == CameraType.HOTSPOT
        val effectiveLimit = if (input.speedLimitKmh > 0) input.speedLimitKmh else (input.nearestCamera?.speedLimit ?: 0)
        val isOverspeed = effectiveLimit > 0 && input.currentSpeedKmh > effectiveLimit
        val hasClosure = input.activeRoadEvent?.severity == EventSeverity.CLOSURE
        val hasEvent = input.activeRoadEvent != null && !input.activeRoadEvent.isCleared

        // 1. 通話中靜音視覺模式 (InCallAlertMode.MUTE_VISUAL)
        if (input.isInCall && input.inCallAlertMode == InCallAlertMode.MUTE_VISUAL) {
            val prio = when {
                isOverspeed -> AlertPriority.EMERGENCY_OVERSPEED
                input.isIntervalEnd -> AlertPriority.INTERVAL_END
                hasCamera -> AlertPriority.TECH_CAMERA
                hasClosure -> AlertPriority.ROAD_CLOSURE
                hasEvent -> AlertPriority.ROAD_WORK
                else -> AlertPriority.IDLE
            }
            val visualMsg = when {
                isOverspeed && isHotspot -> "通話中視覺警示：⚠️ 警車出沒超速！"
                isOverspeed -> "通話中視覺警示：⚠️ 已超速！"
                hasCamera && isHotspot -> "通話中視覺警示：前方警車經常出沒點"
                hasCamera -> "通話中視覺警示：前方有測速相機"
                input.isIntervalEnd -> "通話中視覺警示：區間終點"
                hasClosure -> "通話中視覺警示：道路封閉"
                hasEvent -> "通話中視覺警示：道路施工"
                else -> ""
            }
            return ArbitrationOutput(
                priority = prio,
                isVoiceAllowed = false,
                isUrgentTone = false,
                isFlushQueue = false,
                isVisualFlashRequired = prio != AlertPriority.IDLE,
                speechText = null,
                displayMessage = visualMsg
            )
        }

        // 2. 超速搶佔最高優先級 (EMERGENCY_OVERSPEED)
        if (isOverspeed && hasCamera) {
            val dist = input.distanceToCameraMeters?.toInt() ?: 0
            val distText = if (dist > 0) "前方 ${dist} 公尺" else "前方"
            val speech = if (isHotspot) {
                "警告！您已超速，${distText}有警車經常出沒點，速限 ${effectiveLimit}，請立即減速！"
            } else {
                "警告！您已超速，${distText}有測速照相，速限 ${effectiveLimit}，請立即減速！"
            }
            val disp = if (isHotspot) {
                "⚠️ 警車常出沒！超速警示 (速限 $effectiveLimit)"
            } else {
                "⚠️ 超速警告！速限 $effectiveLimit km/h"
            }
            return ArbitrationOutput(
                priority = AlertPriority.EMERGENCY_OVERSPEED,
                isVoiceAllowed = true,
                isUrgentTone = true,
                isFlushQueue = true,
                isVisualFlashRequired = true,
                speechText = speech,
                displayMessage = disp
            )
        }

        // 3. 區間終點結算 (INTERVAL_END)
        if (input.isIntervalEnd) {
            val isZoneOver = effectiveLimit > 0 && input.intervalAvgSpeedKmh > effectiveLimit
            val statusText = if (isZoneOver) "平均時速已超速" else "安全通過"
            return ArbitrationOutput(
                priority = if (isZoneOver) AlertPriority.EMERGENCY_OVERSPEED else AlertPriority.INTERVAL_END,
                isVoiceAllowed = true,
                isUrgentTone = isZoneOver,
                isFlushQueue = isZoneOver,
                isVisualFlashRequired = isZoneOver,
                speechText = "通過區間測速終點，區間平均時速 ${input.intervalAvgSpeedKmh} 公里，$statusText",
                displayMessage = "區間終點結算: 均速 ${input.intervalAvgSpeedKmh} km/h ($statusText)"
            )
        }

        // 4. 多重路況智慧融合 (相機 + 施工/封閉併發)
        if (hasCamera && hasEvent) {
            val ev = input.activeRoadEvent!!
            val distMeters = (((input.distanceToCameraMeters ?: 300.0) / 50).toInt() * 50).coerceAtLeast(50)
            val camPrefix = "注意，前方 $distMeters 公尺有測速照相"
            val tail = if (ev.severity == EventSeverity.CLOSURE) "，同時該路段有道路封閉，請減速準備改道。" else "，同時前方有${ev.voiceSummary}，請減速小心行駛。"
            return ArbitrationOutput(
                priority = AlertPriority.TECH_CAMERA,
                isVoiceAllowed = true,
                isUrgentTone = ev.severity == EventSeverity.CLOSURE,
                isFlushQueue = false,
                isVisualFlashRequired = false,
                speechText = camPrefix + tail,
                displayMessage = "前方 $distMeters 公尺相機 + ${ev.roadName}"
            )
        }

        // 5. 單一相機或科技執法 (TECH_CAMERA)
        if (hasCamera) {
            val cam = input.nearestCamera!!
            val isHotspotCam = cam.type == CameraType.HOTSPOT
            val distMeters = (((input.distanceToCameraMeters ?: 300.0) / 50).toInt() * 50).coerceAtLeast(50)
            val speech = if (isHotspotCam) {
                "注意，前方 $distMeters 公尺有警車經常出沒點，速限 ${cam.speedLimit}"
            } else {
                "前方 $distMeters 公尺有測速照相，速限 ${cam.speedLimit}"
            }
            val disp = if (isHotspotCam) {
                "🚔 警車出沒 ${distMeters}m (速限 ${cam.speedLimit})"
            } else {
                "測速照相 ${distMeters}m (速限 ${cam.speedLimit})"
            }
            return ArbitrationOutput(
                priority = AlertPriority.TECH_CAMERA,
                isVoiceAllowed = true,
                isUrgentTone = false,
                isFlushQueue = false,
                isVisualFlashRequired = false,
                speechText = speech,
                displayMessage = disp
            )
        }

        // 6. 單一道路事件 (ROAD_CLOSURE / ROAD_WORK)
        if (hasEvent) {
            val ev = input.activeRoadEvent!!
            val prio = if (ev.severity == EventSeverity.CLOSURE) AlertPriority.ROAD_CLOSURE else AlertPriority.ROAD_WORK
            return ArbitrationOutput(
                priority = prio,
                isVoiceAllowed = true,
                isUrgentTone = ev.severity == EventSeverity.CLOSURE,
                isFlushQueue = false,
                isVisualFlashRequired = false,
                speechText = if (ev.severity == EventSeverity.CLOSURE) "前方路段道路封閉，請減速小心行駛" else "前方路段有${ev.voiceSummary}",
                displayMessage = "${ev.roadName}: ${ev.voiceSummary}"
            )
        }

        // 7. 常態巡航 (IDLE)
        return ArbitrationOutput(
            priority = AlertPriority.IDLE,
            isVoiceAllowed = false,
            isUrgentTone = false,
            isFlushQueue = false,
            isVisualFlashRequired = false,
            speechText = null,
            displayMessage = ""
        )
    }
}
