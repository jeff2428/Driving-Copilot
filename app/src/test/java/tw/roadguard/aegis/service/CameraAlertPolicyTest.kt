package tw.roadguard.aegis.service

import org.junit.Assert.*
import org.junit.Test
import tw.roadguard.aegis.model.CameraType
import tw.roadguard.aegis.model.GeoUtils
import tw.roadguard.aegis.model.SpeedCamera

class CameraAlertPolicyTest {
    private fun camera(
        type: CameraType = CameraType.FIXED,
        address: String = "國道一號南向306.1公里",
        limit: Int = 110
    ) = SpeedCamera("test", "", "", address, "", 23.16412, 120.23150, "往南", limit, type)

    @Test fun freewayFixedAndHotspotUseSameThresholdsEvenInTraffic() {
        for (type in listOf(CameraType.FIXED, CameraType.HOTSPOT)) {
            val cam = camera(type)
            for (speed in listOf(0, 25, 110)) {
                val thresholds = CameraAlertPolicy.thresholds(cam, speed)
                assertEquals(1000.0, thresholds.farMeters, 0.0)
                assertEquals(400.0, thresholds.nearMeters, 0.0)
                assertTrue(CameraAlertPolicy.lockRadiusMeters(cam, speed) >= thresholds.farMeters)
            }
        }
    }

    @Test fun localRoadThresholdsStayUnchanged() {
        for ((limit, expected) in listOf(40 to (420.0 to 180.0), 60 to (600.0 to 260.0), 80 to (900.0 to 380.0))) {
            val thresholds = CameraAlertPolicy.thresholds(camera(address = "台1線", limit = limit), 30)
            assertEquals(expected.first, thresholds.farMeters, 0.0)
            assertEquals(expected.second, thresholds.nearMeters, 0.0)
        }
        assertFalse(CameraAlertPolicy.isFreeway(camera(address = "台1線", limit = 100)))
    }

    @Test fun freewayRecognitionUsesRoadDescription() {
        assertTrue(CameraAlertPolicy.isFreeway(camera(address = "中山高速公路南下50公里")))
        assertTrue(CameraAlertPolicy.isFreeway(camera(address = "國3南下100公里")))
        assertFalse(CameraAlertPolicy.isFreeway(camera(address = "中正東路往國道二號方向", limit = 50)))
        assertFalse(CameraAlertPolicy.isFreeway(camera(address = "三商街高速公路口機慢車專用道往南415公尺處", limit = 40)))
    }

    @Test fun onlyRouteConfirmedCameraBypassesLowSpeedExclusion() {
        val cam = camera()
        assertTrue(CameraAlertPolicy.excludeAtLowSpeed(cam, 25, false, false, false, false))
        assertFalse(CameraAlertPolicy.excludeAtLowSpeed(cam, 25, false, false, false, true))
        assertFalse(CameraAlertPolicy.excludeAtLowSpeed(cam, 25, false, false, true, false))
        assertFalse(CameraAlertPolicy.excludeAtLowSpeed(cam, 25, true, false, false, false))
        assertFalse(CameraAlertPolicy.excludeAtLowSpeed(cam, 25, false, true, false, false))
        assertTrue(CameraAlertPolicy.isOnNavigationCorridor(45.0, 55.0))
        assertFalse(CameraAlertPolicy.isOnNavigationCorridor(46.0, 0.0))
        assertFalse(CameraAlertPolicy.isOnNavigationCorridor(0.0, 56.0))
        assertFalse(CameraAlertPolicy.isOnNavigationCorridor(null, null))
    }

    @Test fun navigationDoesNotBypassOppositeDirectionOrParallelRoadFilters() {
        assertFalse(GeoUtils.isCameraDirectionCompatible(0f, camera().address, "往南"))
        assertTrue(GeoUtils.isCameraDirectionCompatible(180f, camera().address, "往南"))
        assertTrue(GeoUtils.crossTrackDistanceMeters(300.0, 20.0) > 48.0)
    }

    @Test fun eachStageAnnouncesOnceAndMovingAwayDoesNotAdvanceStage() {
        for (type in listOf(CameraType.FIXED, CameraType.HOTSPOT)) {
            val cam = camera(type)
            val tracker = CameraAlertTracker()
            assertNull(tracker.update(cam, 1001.0, 80))
            assertEquals(1, tracker.update(cam, 1000.0, 80))
            assertEquals(0, tracker.update(cam, 850.0, 80))
            assertEquals(0, tracker.update(cam, 401.0, 80))
            assertEquals(2, tracker.update(cam, 400.0, 80))
            assertEquals(0, tracker.update(cam, 250.0, 80))
            assertNull(tracker.update(cam, 300.0, 80))
            tracker.clear()
            assertEquals(1, tracker.update(cam, 900.0, 80))
        }
    }

    @Test fun nearStageAlwaysIncludesDistanceAndLimit() {
        for (type in listOf(CameraType.FIXED, CameraType.HOTSPOT)) {
            for (isOver in listOf(false, true)) {
                val text = CameraAlertPolicy.message(camera(type), 2, 400.0, isOver)
                assertTrue(text.contains("400 公尺"))
                assertTrue(text.contains("110"))
            }
        }
    }

    @Test fun distanceFormattingHandlesMetersAndKilometers() {
        assertEquals("前方 850 公尺", CameraAlertPolicy.distanceLabel(850.0))
        assertEquals("前方 1.0 公里", CameraAlertPolicy.distanceLabel(1000.0))
        assertEquals("前方 1.2 公里", CameraAlertPolicy.distanceLabel(1150.0))
        assertEquals("前方 0 公尺", CameraAlertPolicy.distanceLabel(0.0))
    }

    @Test fun mountainHairpinCurveDoesNotTriggerPassPrematurely() {
        // 山路急彎：夾角突然轉到側後方 85 度，但直線距離仍在 120m (minApproachedDist = 110m)
        assertFalse(CameraAlertPolicy.isCameraPassedByVector(
            currentDist = 120.0,
            relAngleDeg = 85.0,
            minApproachedDist = 110.0,
            isFreewayOrElevated = false
        ))

        // 真實通過平面道路測速桿 (曾逼近至 10m，目前距離 15m 夾角 160 度)
        assertTrue(CameraAlertPolicy.isCameraPassedByVector(
            currentDist = 15.0,
            relAngleDeg = 160.0,
            minApproachedDist = 10.0,
            isFreewayOrElevated = false
        ))

        // 真實通過高架/國道路側門架 (曾逼近至 25m，目前距離 30m 夾角 150 度)
        assertTrue(CameraAlertPolicy.isCameraPassedByVector(
            currentDist = 30.0,
            relAngleDeg = 150.0,
            minApproachedDist = 25.0,
            isFreewayOrElevated = true
        ))
    }

    @Test fun elevatedRoadIdentificationFiltersSurfaceIntersections() {
        assertTrue(CameraAlertPolicy.isElevatedRoad(camera(address = "市民大道高架東向12K")))
        assertTrue(CameraAlertPolicy.isElevatedRoad(camera(address = "台64線西向18K")))
        assertTrue(CameraAlertPolicy.isElevatedRoad(camera(address = "建國高架南下")))
        assertFalse(CameraAlertPolicy.isElevatedRoad(camera(address = "市民大道四段與延吉街口")))
        assertFalse(CameraAlertPolicy.isElevatedRoad(camera(address = "建國南路二段平面")))
    }

    @Test fun techEnforcementClassificationIdentifiesSpecificViolations() {
        val camRedLight = camera(type = CameraType.TECH, address = "中正路與中山路口(取締闖紅燈)")
        assertEquals(CameraAlertPolicy.TechType.RED_LIGHT, CameraAlertPolicy.parseTechEnforcementType(camRedLight))
        val msgRedLight = CameraAlertPolicy.message(camRedLight, 1, 400.0, false)
        assertTrue(msgRedLight.contains("取締闖紅燈"))

        val camPedestrian = camera(type = CameraType.TECH, address = "博愛路未禮讓行人取締")
        assertEquals(CameraAlertPolicy.TechType.PEDESTRIAN, CameraAlertPolicy.parseTechEnforcementType(camPedestrian))

        val camBus = camera(type = CameraType.TECH, address = "信義路公車專用道")
        assertEquals(CameraAlertPolicy.TechType.SPECIAL_LANE, CameraAlertPolicy.parseTechEnforcementType(camBus))
    }
}
