package tw.roadguard.aegis.service

import android.os.Handler
import android.os.Looper
import tw.roadguard.aegis.data.TaiwanOpenDataRepository
import tw.roadguard.aegis.model.GeoUtils
import tw.roadguard.aegis.model.NavRoute
import tw.roadguard.aegis.model.NavState
import tw.roadguard.aegis.model.NavStep
import kotlin.math.roundToInt

class NavigationEngine(
    private val repository: TaiwanOpenDataRepository,
    private val voiceAlertEngine: VoiceAlertEngine
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    var activeRoute: NavRoute? = null
        private set
    var currentStepIndex: Int = 0
        private set
    var isNavigating: Boolean = false
        private set

    @Volatile
    var currentNavState: NavState? = null
        private set

    private var lastAnnouncedStepIndex = -1
    private var announcedMilestoneFar = false   // ~500m - 700m
    private var announcedMilestoneMid = false   // ~150m - 200m
    private var announcedMilestoneNear = false  // ~30m - 50m

    private var consecutiveOffRouteCount = 0
    private var isRerouting = false
    private var lastRerouteTime = 0L

    var onNavStateListener: ((NavState) -> Unit)? = null

    fun startNavigation(route: NavRoute) {
        activeRoute = route
        currentStepIndex = 0
        isNavigating = true
        lastAnnouncedStepIndex = -1
        announcedMilestoneFar = false
        announcedMilestoneMid = false
        announcedMilestoneNear = false
        consecutiveOffRouteCount = 0
        isRerouting = false

        val firstStep = route.steps.firstOrNull()
        val nextStep = if (route.steps.size > 1) route.steps[1] else null

        val state = NavState(
            isNavigating = true,
            destinationName = route.destinationName,
            currentStep = firstStep,
            nextStep = nextStep,
            distanceToNextTurnMeters = firstStep?.distanceMeters ?: 0.0,
            remainingDistanceMeters = route.totalDistanceMeters,
            remainingDurationSec = route.totalDurationSec,
            isOffRoute = false,
            currentRoadName = firstStep?.roadName ?: "",
            maneuverIcon = firstStep?.maneuverIcon ?: "⬆️",
            instruction = firstStep?.instruction ?: "出發，往 ${route.destinationName} 方向前進"
        )
        currentNavState = state
        onNavStateListener?.invoke(state)

        // 語音播報起步提示
        val startMsg = "開始導航，往 ${route.destinationName}，全程約 ${String.format("%.1f", route.totalDistanceMeters / 1000.0)} 公里。${firstStep?.instruction ?: ""}"
        voiceAlertEngine.speakNavigation(startMsg)
    }

    fun stopNavigation(reachDestination: Boolean = false) {
        if (!isNavigating) return
        isNavigating = false
        activeRoute = null
        currentStepIndex = 0

        val state = NavState(
            isNavigating = false,
            destinationName = "",
            instruction = if (reachDestination) "已抵達目的地附近，導航結束" else "導航已結束"
        )
        currentNavState = state
        onNavStateListener?.invoke(state)

        if (reachDestination) {
            voiceAlertEngine.speakNavigation("已抵達目的地附近，神盾導航結束。")
        }
    }

    fun updateLocation(lat: Double, lng: Double, speedKmh: Double, bearingDeg: Double) {
        if (!isNavigating) return
        val route = activeRoute ?: return
        val steps = route.steps
        if (steps.isEmpty()) {
            stopNavigation(reachDestination = true)
            return
        }

        // 檢查是否已到達最終目的地 (距離終點 < 35 公尺)
        val distToDest = GeoUtils.distanceMeters(lat, lng, route.destLat, route.destLng)
        if (distToDest <= 35.0) {
            stopNavigation(reachDestination = true)
            return
        }

        // 檢查偏離航線：計算當前位置距離全路線 polyline 的最短距離
        val distToRoute = minDistanceToRoute(lat, lng, route.routePoints)
        val isOffRoute = distToRoute > 55.0

        if (isOffRoute) {
            consecutiveOffRouteCount++
            if (consecutiveOffRouteCount >= 3 && !isRerouting && System.currentTimeMillis() - lastRerouteTime > 12_000L) {
                triggerReroute(lat, lng, route)
            }
        } else {
            consecutiveOffRouteCount = 0
        }

        // 更新步進索引
        if (currentStepIndex >= steps.size) {
            currentStepIndex = steps.size - 1
        }
        val currentStep = steps[currentStepIndex]

        // 距離當前轉彎點 (maneuverLat, maneuverLng) 的距離
        val distToTurn = GeoUtils.distanceMeters(lat, lng, currentStep.maneuverLat, currentStep.maneuverLng)

        // 步進切換判斷：
        // 若已接近當前轉彎點 (<= 28m) 且不是最後一步，切換到下一步
        // 或者當前步進有 nextStep，且距離 nextStep maneuver 更近
        val nextStep = if (currentStepIndex + 1 < steps.size) steps[currentStepIndex + 1] else null
        if (nextStep != null) {
            val distToNextTurn = GeoUtils.distanceMeters(lat, lng, nextStep.maneuverLat, nextStep.maneuverLng)
            // 當已到達或略微越過當前步進點
            if (distToTurn <= 28.0 || (distToNextTurn < distToTurn && distToTurn < 60.0)) {
                currentStepIndex++
                announcedMilestoneFar = false
                announcedMilestoneMid = false
                announcedMilestoneNear = false
            }
        }

        val effectiveStep = steps[currentStepIndex.coerceAtMost(steps.size - 1)]
        val effectiveNextStep = if (currentStepIndex + 1 < steps.size) steps[currentStepIndex + 1] else null
        val curTurnDist = GeoUtils.distanceMeters(lat, lng, effectiveStep.maneuverLat, effectiveStep.maneuverLng)

        // 計算剩餘距離與時間
        var remDist = curTurnDist
        for (i in (currentStepIndex + 1) until steps.size) {
            remDist += steps[i].distanceMeters
        }
        val effSpeed = if (speedKmh > 15.0) speedKmh else 45.0
        val remSec = (remDist / (effSpeed * 1000.0 / 3600.0)).coerceAtLeast(10.0)

        // 語音播報邏輯
        checkVoiceAnnouncements(effectiveStep, curTurnDist)

        val navState = NavState(
            isNavigating = true,
            destinationName = route.destinationName,
            currentStep = effectiveStep,
            nextStep = effectiveNextStep,
            distanceToNextTurnMeters = curTurnDist,
            remainingDistanceMeters = remDist,
            remainingDurationSec = remSec,
            isOffRoute = isOffRoute,
            currentRoadName = effectiveStep.roadName.ifBlank { "前方路段" },
            maneuverIcon = effectiveStep.maneuverIcon,
            instruction = effectiveStep.instruction
        )
        currentNavState = navState
        mainHandler.post { onNavStateListener?.invoke(navState) }
    }

    private fun checkVoiceAnnouncements(step: NavStep, distToTurn: Double) {
        if (currentStepIndex != lastAnnouncedStepIndex) {
            lastAnnouncedStepIndex = currentStepIndex
            announcedMilestoneFar = false
            announcedMilestoneMid = false
            announcedMilestoneNear = false

            // 若步進距離較長 (> 600m)，初次進步進時播報
            if (distToTurn > 600.0) {
                val roundD = ((distToTurn / 100).roundToInt() * 100)
                voiceAlertEngine.speakNavigation("沿著 ${step.roadName.ifBlank { "目前道路" }} 繼續前進 $roundD 公尺")
            }
        }

        // 遠程提醒 (~500m)
        if (!announcedMilestoneFar && distToTurn in 450.0..580.0) {
            announcedMilestoneFar = true
            voiceAlertEngine.speakNavigation("500公尺後，${step.instruction}")
            return
        }

        // 中程提醒 (~150m)
        if (!announcedMilestoneMid && distToTurn in 130.0..220.0) {
            announcedMilestoneMid = true
            val roundD = ((distToTurn / 50).roundToInt() * 50)
            voiceAlertEngine.speakNavigation("${roundD}公尺後，${step.instruction}")
            return
        }

        // 轉彎點即時提醒 (~30m - 50m)
        if (!announcedMilestoneNear && distToTurn <= 50.0) {
            announcedMilestoneNear = true
            voiceAlertEngine.speakNavigation(step.instruction)
            return
        }
    }

    private fun triggerReroute(currentLat: Double, currentLng: Double, route: NavRoute) {
        isRerouting = true
        lastRerouteTime = System.currentTimeMillis()
        voiceAlertEngine.speakNavigation("已偏離路線，正在重新規劃路徑...")

        Thread {
            try {
                val newCorridor = repository.scanRouteCorridorDirect(
                    destName = route.destinationName,
                    destLat = route.destLat,
                    destLng = route.destLng,
                    originLat = currentLat,
                    originLng = currentLng
                )
                val newRoute = newCorridor.navRoute
                mainHandler.post {
                    isRerouting = false
                    if (newRoute != null && isNavigating) {
                        activeRoute = newRoute
                        currentStepIndex = 0
                        lastAnnouncedStepIndex = -1
                        announcedMilestoneFar = false
                        announcedMilestoneMid = false
                        announcedMilestoneNear = false
                        consecutiveOffRouteCount = 0
                        voiceAlertEngine.speakNavigation("路線已重新規劃，請依指示行駛。")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                mainHandler.post { isRerouting = false }
            }
        }.start()
    }

    private fun minDistanceToRoute(lat: Double, lng: Double, points: List<Pair<Double, Double>>): Double {
        if (points.size < 2) return 0.0
        var minD = Double.MAX_VALUE
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val d = GeoUtils.pointToSegmentMeters(lat, lng, a.first, a.second, b.first, b.second)
            if (d < minD) minD = d
        }
        return minD
    }
}
