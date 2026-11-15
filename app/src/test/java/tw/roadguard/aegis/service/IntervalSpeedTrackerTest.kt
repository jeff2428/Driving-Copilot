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
    fun officialAddressFormatsUseTheFirstMileageRange() {
        val cases = listOf(
            "金門大橋5K至0.5K(西向)" to 4500.0,
            "金門大橋0.4K至5.1K(東向)" to 4700.0,
            "台三線31.8k至30.5k" to 1300.0,
            "台9線19K至23.1K（雙向）" to 4100.0,
            "台9線26.2K至32.2K（雙向）" to 6000.0,
            "台9線33.3K至37.2K（雙向）" to 3900.0,
            "台26線尖山段8.5-13.7K" to 5200.0,
            "臺11線24.1K-26.6K" to 2500.0,
            "台7乙線5.3K至8.1K（雙向）" to 2800.0,
            "臺9戊線9.92K(壽卡休憩亭)至3.94K森永區間測速" to 5980.0,
            "台3線82.2公里至84.3公里" to 2100.0,
            "台9線129k+404m至136k+883m(觀音隧道)" to 7479.0,
            "台9線137K+295m至141K+458m(谷風隧道)" to 4163.0,
            "台62線快速道路1.7K至0.7K(瑪陵隧道東往西)區間測速" to 1000.0,
            "鹿港鎮台61線177至168.1公里(快速道路)(20公噸以上大貨車速限80公里/小時)" to 8900.0,
            "臺11線129.61至126.13K(小馬)" to 3480.0,
            "台13甲線8.3公里至9.5公里及9.8公里至11公里" to 1200.0,
            "台15線鳳鼻隧道" to 3200.0,
        )
        for ((address, expected) in cases) {
            assertEquals(address, expected, IntervalSpeedTracker.parseZoneLengthMeters(address), 0.1)
        }
    }

    @Test
    fun firstFixInsideStartThresholdEntersAndTimesOutOnlyAfter900Seconds() {
        val tracker = IntervalSpeedTracker()
        val status = tracker.onLocationUpdate(24.685, 120.875, 80, intervalCamera(), 20.0, 1000L)
        assertEquals(IntervalSpeedTracker.State.IN_ZONE, status.state)
        assertTrue(tracker.tickDeadReckoning(901000L).isDeadReckoning)
        assertEquals(IntervalSpeedTracker.State.IDLE, tracker.tickDeadReckoning(901001L).state)
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

    @Test
    fun deadReckoningInTunnelWhenGpsLost() {
        val tracker = IntervalSpeedTracker()
        val cam = intervalCamera(limit = 80)
        val t0 = 1000000L

        // 1. 進入起點
        tracker.onLocationUpdate(24.6950, 120.8750, 80, cam, 1000.0, t0)
        tracker.onLocationUpdate(24.6850, 120.8750, 80, cam, 20.0, t0 + 1000)

        // 2. 正常行駛 10 秒 (80 km/h = 22.22 m/s -> ~222m)
        tracker.onLocationUpdate(24.6830, 120.8750, 80, null, 9999.0, t0 + 11000)

        // 3. 進入隧道，GPS 斷訊超過 3 秒，呼叫 tickDeadReckoning (推進 30 秒)
        var status = tracker.tickDeadReckoning(t0 + 15000)
        assertTrue("斷訊 4 秒應啟動盲區推估", status.isDeadReckoning)

        // 再推進至 40 秒
        status = tracker.tickDeadReckoning(t0 + 41000)
        assertTrue(status.isDeadReckoning)
        assertTrue("推估里程應累計增加", status.distanceTraveledMeters > 500.0)

        // 4. GPS 重新恢復定位
        status = tracker.onLocationUpdate(24.6700, 120.8750, 80, null, 9999.0, t0 + 42000)
        assertFalse("GPS 恢復後應退出推估狀態", status.isDeadReckoning)

        // 5. 斷訊超過 900 秒 (15 分鐘) 應安全退場
        val timeoutTracker = IntervalSpeedTracker()
        timeoutTracker.onLocationUpdate(24.6950, 120.8750, 80, cam, 20.0, t0)
        val timeoutStatus = timeoutTracker.tickDeadReckoning(t0 + 905000L)
        assertFalse("超逾 15 分鐘應退出推估", timeoutStatus.isDeadReckoning)
        assertTrue(timeoutStatus.displayMessage.contains("中斷") || timeoutStatus.state == IntervalSpeedTracker.State.IDLE)
    }

    @Test
    fun snapshotCreationAndSeamlessRestoration() {
        val tracker1 = IntervalSpeedTracker()
        val cam = intervalCamera(limit = 80)
        val t0 = 1000000L

        // 1. 車輛進入區間測速起點
        tracker1.onLocationUpdate(24.6950, 120.8750, 80, cam, 1000.0, t0)
        tracker1.onLocationUpdate(24.6850, 120.8750, 80, cam, 20.0, t0 + 1000)

        // 2. 在區間內行駛 30 秒
        tracker1.onLocationUpdate(24.6750, 120.8750, 80, null, 9999.0, t0 + 31000)
        assertTrue(tracker1.isZoneActive())

        // 3. 擷取快照
        val snapshot = tracker1.createSnapshot()
        assertNotNull(snapshot)
        assertEquals("test-interval", snapshot!!.cameraId)
        assertEquals(80, snapshot.speedLimit)
        assertEquals(IntervalSpeedTracker.State.IN_ZONE, snapshot.state)
        assertTrue(snapshot.totalDistanceMeters > 0.0)

        // 4. 模擬系統崩潰/被殺，全新實例化 tracker2
        val tracker2 = IntervalSpeedTracker()
        assertEquals(IntervalSpeedTracker.State.IDLE, tracker2.getStatus().state)

        // 5. 無縫復原
        val restored = tracker2.restoreFromSnapshot(snapshot, t0 + 32000)
        assertTrue("快照必須成功還原", restored)
        val status2 = tracker2.getStatus(t0 + 32000)
        assertEquals(IntervalSpeedTracker.State.IN_ZONE, status2.state)
        assertEquals(snapshot.totalDistanceMeters, status2.distanceTraveledMeters, 0.01)
        assertEquals(80, status2.camera?.speedLimit)
    }

    @Test
    fun snapshotRejectsExpiredOrOverflowData() {
        val tracker = IntervalSpeedTracker()
        val t0 = 1000000L
        val validSnapshot = IntervalSpeedTracker.IntervalSessionSnapshot(
            version = 1,
            cameraId = "cam_01",
            speedLimit = 80,
            address = "西濱快速公路",
            zoneLengthMeters = 3000.0,
            entryTimeMs = t0,
            lastLocationTimeMs = t0 + 10000,
            totalDistanceMeters = 1000.0,
            lastSpeedKmh = 80,
            isDeadReckoning = false,
            state = IntervalSpeedTracker.State.IN_ZONE,
            hasAnnouncedStart = true
        )

        // 正常還原應為 true
        assertTrue(tracker.restoreFromSnapshot(validSnapshot, t0 + 20000))

        // 逾時 > 2 小時 (7200秒) 應被拒絕 (ERR_SNAP_EXPIRED)
        val expiredSnapshot = validSnapshot.copy(entryTimeMs = t0)
        assertFalse(tracker.restoreFromSnapshot(expiredSnapshot, t0 + 7_201_000L))

        // 異常距離 (> 1.5 倍區間長度) 應被拒絕 (ERR_SNAP_CORRUPT)
        val overflowSnapshot = validSnapshot.copy(totalDistanceMeters = 3000.0 * 1.6)
        assertFalse(tracker.restoreFromSnapshot(overflowSnapshot, t0 + 20000))

        // 非法版本號應被拒絕
        val badVersionSnapshot = validSnapshot.copy(version = 99)
        assertFalse(tracker.restoreFromSnapshot(badVersionSnapshot, t0 + 20000))
    }
}
