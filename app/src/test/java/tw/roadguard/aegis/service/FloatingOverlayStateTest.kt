package tw.roadguard.aegis.service

import org.junit.Assert.*
import org.junit.Test
import tw.roadguard.aegis.model.*

class FloatingOverlayStateTest {
    private fun testCamera(type: CameraType = CameraType.FIXED, limit: Int = 110) = SpeedCamera(
        id = "test-cam",
        city = "台中市",
        region = "西屯區",
        address = "國道一號北向100公里",
        dept = "公警局",
        lat = 24.1605,
        lng = 120.6311,
        directText = "往北",
        speedLimit = limit,
        type = type
    )

    private fun testEvent(severity: EventSeverity = EventSeverity.CLOSURE, road: String = "國道一號") = RoadEvent(
        uid = "test-ev",
        roadName = road,
        areaName = "台中市",
        direction = "北向",
        lat = 24.1605,
        lng = 120.6311,
        rawComment = "全線封閉施工",
        voiceSummary = "國道一號北向封閉",
        severity = severity,
        category = RoadCategory.FREEWAY,
        isCleared = false,
        isActiveNow = true,
        scheduleNote = "",
        happenTime = "2026-10-22 09:00",
        sourceDetail = "高公局"
    )

    @Test
    fun cruisingWithNoCameraShowsCompactSpeedOnly() {
        val snap = RadarSnapshot(
            speedKmh = 65,
            nearestCamera = null,
            nearestRoadEvent = null,
            isOverspeed = false
        )
        val config = OverlayStateEvaluator.evaluate(snap, null)
        assertEquals(OverlayMode.COMPACT_SPEED, config.mode)
        assertEquals("65", config.speedText)
        assertFalse(config.isOverspeed)
        assertNull(config.speedLimitText)
        assertNull(config.cameraTitleText)
        assertNull(config.cameraDistanceText)
        assertNull(config.roadAlertText)
    }

    @Test
    fun approachingFixedCameraSwitchesToAlertWithDetails() {
        val cam = testCamera(CameraType.FIXED, 110)
        val snap = RadarSnapshot(
            speedKmh = 105,
            nearestCamera = ScoredCamera(cam, 850.0, 0.0),
            isOverspeed = false
        )
        val config = OverlayStateEvaluator.evaluate(snap, null)
        assertEquals(OverlayMode.ALERT, config.mode)
        assertEquals("105", config.speedText)
        assertEquals("110", config.speedLimitText)
        assertEquals("📸 固定測速", config.cameraTitleText)
        assertEquals("前方 850 公尺", config.cameraDistanceText)
        assertNull(config.roadAlertText)
    }

    @Test
    fun inIntervalSpeedZoneShowsAverageSpeedAndCountdown() {
        val cam = testCamera(CameraType.INTERVAL, 90)
        val zoneStatus = IntervalSpeedTracker.ZoneStatus(
            state = IntervalSpeedTracker.State.IN_ZONE,
            camera = cam,
            averageSpeedKmh = 88,
            distanceTraveledMeters = 1500.0,
            elapsedSeconds = 60L,
            remainingSeconds = 45,
            isAverageOverspeed = false,
            displayMessage = "區間均速 88 km/h"
        )
        val snap = RadarSnapshot(
            speedKmh = 85,
            inIntervalZone = true,
            isOverspeed = false
        )
        val config = OverlayStateEvaluator.evaluate(snap, zoneStatus)
        assertEquals(OverlayMode.ALERT, config.mode)
        assertEquals("85", config.speedText)
        assertEquals("90", config.speedLimitText)
        assertEquals("⏱️ 區間均速 88 km/h", config.cameraTitleText)
        assertEquals("剩餘 45 秒", config.cameraDistanceText)
        assertNull(config.roadAlertText)
    }

    @Test
    fun roadClosureAheadShowsRoadAlertWithoutCameraInfo() {
        val event = testEvent(EventSeverity.CLOSURE, "國道一號")
        val snap = RadarSnapshot(
            speedKmh = 50,
            nearestCamera = null,
            nearestRoadEvent = ScoredRoadEvent(event, 600.0, 0.0),
            isOverspeed = false
        )
        val config = OverlayStateEvaluator.evaluate(snap, null)
        assertEquals(OverlayMode.ALERT, config.mode)
        assertEquals("50", config.speedText)
        assertNull(config.speedLimitText)
        assertNull(config.cameraTitleText)
        assertNull(config.cameraDistanceText)
        assertNotNull(config.roadAlertText)
        assertTrue(config.roadAlertText!!.contains("🔴封閉 600m"))
    }

    @Test
    fun tunnelAdviceApproachingEntranceShowsLaneAdvice() {
        val advice = HsuehshanAdvice(
            isApproachingEntrance = true,
            recommendedLane = "outer",
            recommendedLaneName = "外側車道",
            distanceToEntranceMeters = 800.0,
            innerSpeedKmh = 60,
            outerSpeedKmh = 75
        )
        val snap = RadarSnapshot(
            speedKmh = 70,
            tunnelLaneAdvice = advice,
            isOverspeed = false
        )
        val config = OverlayStateEvaluator.evaluate(snap, null)
        assertEquals(OverlayMode.ALERT, config.mode)
        assertEquals("90", config.speedLimitText)
        assertEquals("🚇 建議走外側車道 (75km/h)", config.cameraTitleText)
        assertTrue(config.roadAlertText!!.contains("距雪隧洞口 800m"))
    }

    @Test
    fun overspeedWithoutCameraShowsOverspeedAlert() {
        val snap = RadarSnapshot(
            speedKmh = 125,
            nearestCamera = null,
            isOverspeed = true
        )
        val config = OverlayStateEvaluator.evaluate(snap, null)
        assertEquals(OverlayMode.ALERT, config.mode)
        assertTrue(config.isOverspeed)
        assertEquals("125", config.speedText)
        assertEquals("⚠️ 請注意減速", config.cameraTitleText)
    }

    @Test
    fun clearingAlertReturnsToCompactSpeed() {
        // 警示解除，車速正常
        val snap = RadarSnapshot(
            speedKmh = 70,
            nearestCamera = null,
            nearestRoadEvent = null,
            isOverspeed = false
        )
        val config = OverlayStateEvaluator.evaluate(snap, null)
        assertEquals(OverlayMode.COMPACT_SPEED, config.mode)
    }
}
