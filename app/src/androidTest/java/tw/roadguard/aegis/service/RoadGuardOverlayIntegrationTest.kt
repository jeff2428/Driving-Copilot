package tw.roadguard.aegis.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.app.Activity
import android.location.LocationManager
import android.os.IBinder
import android.os.SystemClock
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import tw.roadguard.aegis.model.*
import tw.roadguard.aegis.ui.MainActivity
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RoadGuardOverlayIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var service: RoadGuardService
    private var connection: ServiceConnection? = null
    private var activity: Activity? = null
    private var originalVoice = true
    private var originalHotspot = true
    private var originalPassChime = true

    @Before fun bindService() {
        instrumentation.uiAutomation.executeShellCommand("cmd statusbar collapse").close()
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val connected = CountDownLatch(1)
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = (binder as RoadGuardService.LocalBinder).getService()
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        assertTrue(context.bindService(Intent(context, RoadGuardService::class.java), connection!!, Context.BIND_AUTO_CREATE))
        assertTrue("Service did not bind", connected.await(10, TimeUnit.SECONDS))
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (service.repository.lastSyncTimeStr == "載入中..." && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertNotEquals("Local road data did not finish loading", "載入中...", service.repository.lastSyncTimeStr)
        instrumentation.waitForIdleSync()
        onMain {
            // Test positions must not race the emulator's real GPS/network callbacks.
            context.getSystemService(LocationManager::class.java).removeUpdates(service)
            originalVoice = service.voiceEngine.isVoiceEnabled
            originalHotspot = service.voiceEngine.isHotspotAlertEnabled
            originalPassChime = service.voiceEngine.isPassChimeEnabled
            service.voiceEngine.isVoiceEnabled = false
            service.voiceEngine.isPassChimeEnabled = false
            service.voiceEngine.isHotspotAlertEnabled = true
            service.stopDemoSimulation()
            service.stopNavigation()
            service.repository.cameras.clear()
            service.repository.roadEvents.clear()
            service.currentLat = 24.1605
            service.currentLng = 120.6311
            service.currentBearingDeg = 0f
            service.currentSpeedKmh = 25
            assertTrue("Overlay permission missing", service.showFloatingOverlay())
        }
        instrumentation.waitForIdleSync()
    }

    @After fun cleanup() {
        if (::service.isInitialized) onMain {
            service.hideFloatingOverlay()
            service.stopNavigation()
            service.voiceEngine.isVoiceEnabled = originalVoice
            service.voiceEngine.isHotspotAlertEnabled = originalHotspot
            service.voiceEngine.isPassChimeEnabled = originalPassChime
            activity?.finish()
        }
        connection?.let { context.unbindService(it) }
        context.stopService(Intent(context, RoadGuardService::class.java))
        instrumentation.waitForIdleSync()
    }

    @Test fun navigationKeepsFreewayCameraVisibleInTrafficAndClearsAfterPassing() {
        val cam = camera(CameraType.FIXED)
        onMain {
            service.repository.cameras.add(cam)
            service.recomputeRadarState()
            assertNull("No navigation: preserve low-speed exclusion", service.latestSnapshot.nearestCamera)
            service.startNavigation(route(cam))
            assertEquals(cam.id, service.latestSnapshot.nearestCamera?.camera?.id)
        }
        instrumentation.waitForIdleSync()
        onMain {
            val view = overlay()
            assertEquals("📸 固定測速", view.cameraTitle.text.toString())
            assertTrue(view.cameraDistanceText.text.toString().contains("850 公尺"))
            assertEquals("110", view.speedLimit.text.toString())
            service.currentSpeedKmh = 130
            service.recomputeRadarState()
        }
        instrumentation.waitForIdleSync()
        onMain {
            assertTrue(service.latestSnapshot.isOverspeed)
            assertEquals(android.graphics.Color.parseColor("#FF2E4C"), overlay().currentSpeed.currentTextColor)
        }
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        File(context.cacheDir, "overlay-service.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
        onMain {
            service.currentLat = cam.lat - 400.0 / 111194.9266
            service.recomputeRadarState()
        }
        instrumentation.waitForIdleSync()
        onMain {
            assertEquals("前方 400 公尺", overlay().cameraDistanceText.text.toString())
            service.currentLat = cam.lat - 100.0 / 111194.9266
            service.recomputeRadarState()
            service.currentLat = cam.lat + 30.0 / 111194.9266
            service.recomputeRadarState()
            assertNull("Passed camera should clear", service.latestSnapshot.nearestCamera)
            service.currentLat = 24.1605
            service.recomputeRadarState()
            assertNull("Passed camera remains in cooldown", service.latestSnapshot.nearestCamera)
        }
    }

    @Test fun hotspotToggleControlsRealRadarCandidate() {
        val cam = camera(CameraType.HOTSPOT)
        onMain {
            service.currentSpeedKmh = 100
            service.repository.cameras.add(cam)
            service.recomputeRadarState()
            assertEquals("Hotspot missing: ${service.latestSnapshot}", cam.id, service.latestSnapshot.nearestCamera?.camera?.id)
            service.voiceEngine.isHotspotAlertEnabled = false
            service.recomputeRadarState()
            assertNull(service.latestSnapshot.nearestCamera)
            service.voiceEngine.isHotspotAlertEnabled = true
            service.recomputeRadarState()
            assertEquals(cam.id, service.latestSnapshot.nearestCamera?.camera?.id)
        }
    }

    @Test fun dockedOverlayExpandsForCameraBeyond500MetersAndRestores() {
        onMain { service.dockFloatingOverlay(RoadGuardService.DockSide.LEFT) }
        SystemClock.sleep(300)
        onMain {
            service.currentSpeedKmh = 100
            service.repository.cameras.add(camera(CameraType.FIXED))
            service.recomputeRadarState()
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        onMain {
            assertTrue("First-stage camera should open docked overlay: ${service.latestSnapshot}", service.autoExpandedForAlert)
            assertTrue(service.latestSnapshot.nearestCamera!!.distanceMeters > 500.0)
            service.repository.cameras.clear()
            service.repository.roadEvents.clear()
            service.recomputeRadarState()
            assertNull(service.latestSnapshot.nearestCamera)
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        onMain {
            assertFalse("Overlay did not dock after alert cleared; event=${service.latestSnapshot.nearestRoadEvent?.event?.uid}", service.autoExpandedForAlert)
            assertEquals(RoadGuardService.DockSide.LEFT, service.dockState)
            service.restoreFloatingOverlayFromDock()
            assertEquals(RoadGuardService.DockSide.NONE, service.dockState)
        }
    }

    @Test fun draggingTallOverlayToBottomKeepsEntireWindowVisible() {
        onMain {
            val field = RoadGuardService::class.java.getDeclaredField("floatingRootView").apply { isAccessible = true }
            val root = field.get(service) as ViewGroup
            val content = overlay()
            val now = SystemClock.uptimeMillis()
            for ((action, y) in listOf(MotionEvent.ACTION_DOWN to 30f,
                MotionEvent.ACTION_MOVE to 10000f, MotionEvent.ACTION_UP to 10000f)) {
                val event = MotionEvent.obtain(now, now, action, 100f, y, 0)
                content.dispatchTouchEvent(event)
                event.recycle()
            }
            val params = root.layoutParams as WindowManager.LayoutParams
            assertTrue("Dragging clips the enlarged overlay below the screen",
                params.y + root.height <= context.resources.displayMetrics.heightPixels)
        }
    }

    @Test fun configurationChangeClampsPositionEvenWhenOverlaySizeDoesNotChange() {
        onMain {
            val field = RoadGuardService::class.java.getDeclaredField("floatingRootView").apply { isAccessible = true }
            val root = field.get(service) as ViewGroup
            val params = root.layoutParams as WindowManager.LayoutParams
            val dm = context.resources.displayMetrics
            params.x = dm.widthPixels + 200
            params.y = dm.heightPixels + 200
            service.onConfigurationChanged(android.content.res.Configuration(context.resources.configuration))
        }
        instrumentation.waitForIdleSync()
        onMain {
            val field = RoadGuardService::class.java.getDeclaredField("floatingRootView").apply { isAccessible = true }
            val root = field.get(service) as ViewGroup
            val params = root.layoutParams as WindowManager.LayoutParams
            val dm = context.resources.displayMetrics
            assertTrue("Rotation leaves overlay offscreen", params.x + root.width <= dm.widthPixels)
            assertTrue("Rotation leaves overlay below screen", params.y + root.height <= dm.heightPixels)
        }
    }

    private fun camera(type: CameraType) = SpeedCamera(
        "instrumentation-$type", "國道一號", "", "國道一號北向100公里", "",
        24.1605 + 850.0 / 111194.9266, 120.6311, "往北", 110, type
    )

    private fun route(cam: SpeedCamera) = NavRoute(
        "測試目的地", 24.1605, 120.6311, cam.lat + 0.01, cam.lng, 2000.0, 120.0,
        emptyList(), listOf(24.1505 to cam.lng, (cam.lat + 0.01) to cam.lng)
    )

    private fun overlay(): FloatingOverlayView {
        val field = RoadGuardService::class.java.getDeclaredField("floatingRootView").apply { isAccessible = true }
        return (field.get(service) as ViewGroup).getChildAt(0) as FloatingOverlayView
    }

    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { block() } catch (t: Throwable) { failure = t } }
        failure?.let { throw it }
    }
}
