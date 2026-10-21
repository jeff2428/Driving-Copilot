package tw.roadguard.aegis.service

import org.junit.Assert.*
import org.junit.Test
import tw.roadguard.aegis.model.CameraType
import tw.roadguard.aegis.model.SpeedCamera

class IntervalSpeedTrackerTest {
    private fun intervalCamera(address: String = "西濱快速公路19K至23.1K", limit: Int = 80) =
        SpeedCamera("test-interval", "苗栗縣", "竹南鎮", address, "", 24.6850, 120.8750, "南向", limit, CameraType.INTERVAL)

    @Test
    fun parseZoneLengthCorrectly() {
        assertEquals(4100.0, IntervalSpeedTracker.parseZoneLengthMeters("西濱快速公路19K至23.1K"), 0.1)
        assertEquals(4500.0, IntervalSpeedTracker.parseZoneLengthMeters("金門大橋5K至0.5K(西向)"), 0.1)
        assertEquals(3200.0, IntervalSpeedTracker.parseZoneLengthMeters("未標記里程區間測速"), 0.1)
    }

    @Test
    fun fullIntervalZoneLifecycle() {
        val tracker = IntervalSpeedTracker()
        val cam = intervalCamera()
        val baseTime = 1000000L

        // 1. 1000m 外接近起點
        var status = tracker.onLocationUpdate(24.6950, 120.8750, 80, cam, 1000.0, baseTime)
        assertEquals(IntervalSpeedTracker.State.APPROACHING_START, status.state)

        // 2. 通過起點 (進入區間，距相機 20m)
        status = tracker.onLocationUpdate(24.6850, 120.8750, 80, cam, 20.0, baseTime + 1000)
        assertEquals(IntervalSpeedTracker.State.IN_ZONE, status.state)

        // 3. 在區間內行駛 180 秒，前進 4000 公尺 (約時速 80 km/h)
        status = tracker.onLocationUpdate(24.6500, 120.8750, 80, null, 9999.0, baseTime + 181000)
        assertTrue(status.distanceTraveledMeters > 3000.0)
        assertFalse(status.isAverageOverspeed)

        // 4. 重設
        tracker.reset()
        assertEquals(IntervalSpeedTracker.State.IDLE, tracker.getStatus().state)
    }

    @Test
    fun overspeedDetectionTriggersWarning() {
        val tracker = IntervalSpeedTracker()
        val cam = intervalCamera(limit = 60)
        val t0 = 1000000L

        tracker.onLocationUpdate(24.6950, 120.8750, 90, cam, 1000.0, t0)
        // 進入起點
        tracker.onLocationUpdate(24.6850, 120.8750, 90, cam, 20.0, t0 + 1000)
        // 快速行駛 100 秒前進 3000m (約時速 108 km/h > 60)
        val status = tracker.onLocationUpdate(24.6580, 120.8750, 90, null, 9999.0, t0 + 101000)
        assertTrue(status.averageSpeedKmh > 60)
        assertTrue(status.isAverageOverspeed)
        assertTrue(tracker.shouldTriggerOverspeedVoice(t0 + 102000))
    }
}
