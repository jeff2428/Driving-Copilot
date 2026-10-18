package tw.roadguard.aegis.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import tw.roadguard.aegis.ads.AdRevenueManager
import tw.roadguard.aegis.data.TaiwanOpenDataRepository
import tw.roadguard.aegis.model.*
import tw.roadguard.aegis.ui.MainActivity
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class RoadGuardService : Service(), LocationListener {

    inner class LocalBinder : Binder() {
        fun getService(): RoadGuardService = this@RoadGuardService
    }

    private val binder = LocalBinder()
    lateinit var repository: TaiwanOpenDataRepository
        private set
    lateinit var voiceEngine: VoiceAlertEngine
        private set
    lateinit var adManager: AdRevenueManager
        private set

    private var locationManager: LocationManager? = null
    private var windowManager: WindowManager? = null
    private var floatingRootView: View? = null
    private var floatingParams: WindowManager.LayoutParams? = null

    // 懸浮視窗內部元件
    private var floatSpeedLimitTv: TextView? = null
    private var floatCurrentSpeedTv: TextView? = null
    private var floatCameraTitleTv: TextView? = null
    private var floatRoadAlertTv: TextView? = null
    private var floatContainerBg: GradientDrawable? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(RadarSnapshot) -> Unit>()

    // 行車雷達設定與狀態
    var forwardConeOnly: Boolean = true
    var alertRadiusMeters: Double = 2500.0
    var currentLat: Double = 24.1605
    var currentLng: Double = 120.6311
    var currentSpeedKmh: Int = 0
    var currentBearingDeg: Float = 0f

    // 追蹤上一支接近中的測速桿，用於判斷「通過測速桿」播放叮咚解除音
    private var approachingCameraId: String? = null
    private var approachingCameraMinDist: Double = Double.MAX_VALUE

    // 區間測速累計狀態
    private var intervalStartTimestamp: Long = 0L
    private var intervalSpeedSum: Double = 0.0
    private var intervalSampleCount: Int = 0

    // 實境模擬試駕狀態
    var isSimulating: Boolean = false
        private set
    private var simScenarioIndex: Int = 0
    private var simStep: Int = 0
    private var simRouteName: String = ""

    @Volatile
    var latestSnapshot: RadarSnapshot = RadarSnapshot()
        private set

    private val periodicSyncRunnable = object : Runnable {
        override fun run() {
            if (adManager.isProMember && repository.autoDailyUpdateEnabled) {
                Thread {
                    repository.refreshLiveRoadEvents()
                    mainHandler.post { recomputeRadarState() }
                }.start()
            }
            mainHandler.postDelayed(this, 60_000L)
        }
    }

    private val simulationTickRunnable = object : Runnable {
        override fun run() {
            if (!isSimulating) return
            advanceSimulationStep()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = TaiwanOpenDataRepository(this)
        voiceEngine = VoiceAlertEngine(this)
        adManager = AdRevenueManager(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        startForegroundNotification()

        Thread {
            repository.initializeFromLocalAssets()
            mainHandler.post { recomputeRadarState() }
            // 僅 Pro 免廣告會員自動於背景無感拉取雲端新圖資；免費版車主需點擊觀看激勵廣告解鎖更新
            if (adManager.isProMember && repository.autoDailyUpdateEnabled) {
                repository.syncAllCloudData(forceCameras = false)
                mainHandler.post { recomputeRadarState() }
            }
        }.start()

        startGpsTracking()
        mainHandler.postDelayed(periodicSyncRunnable, 60_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun addSnapshotListener(listener: (RadarSnapshot) -> Unit) {
        listeners.add(listener)
        listener(latestSnapshot)
    }

    fun removeSnapshotListener(listener: (RadarSnapshot) -> Unit) {
        listeners.remove(listener)
    }

    private fun startForegroundNotification() {
        val channelId = "tw_roadguard_aegis_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId,
                "神盾測速與施工封路雷達常駐服務",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "於導航期間持續監測前方測速照相、道路施工與封閉管制"
            }
            nm.createNotificationChannel(ch)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setContentTitle("🛡️ 神盾測速 + 施工封閉雷達運作中")
            .setContentText("正在背景監測前方測速照相與全台即時施工/封路")
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1001, notification)
        }
    }

    @SuppressLint("MissingPermission")
    fun startGpsTracking() {
        try {
            if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 2f, this)
            }
            if (locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true) {
                locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 5f, this)
            }
            val lastLoc = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (lastLoc != null && !isSimulating) {
                onLocationChanged(lastLoc)
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    override fun onLocationChanged(location: Location) {
        if (isSimulating) return
        val prevLat = currentLat
        val prevLng = currentLng
        currentLat = location.latitude
        currentLng = location.longitude
        currentSpeedKmh = if (location.hasSpeed()) {
            (location.speed * 3.6f).roundToInt().coerceAtLeast(0)
        } else {
            0
        }
        currentBearingDeg = if (location.hasBearing() && location.bearing != 0f) {
            location.bearing
        } else if (GeoUtils.distanceMeters(prevLat, prevLng, currentLat, currentLng) > 4.0) {
            GeoUtils.bearingDeg(prevLat, prevLng, currentLat, currentLng).toFloat()
        } else {
            currentBearingDeg
        }
        recomputeRadarState()
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    /**
     * 啟動實境模擬試駕：自動挑選或建立真實測速桿 + 道路施工/封閉測試路線
     */
    fun startDemoSimulation(scenarioIdx: Int = 0) {
        simScenarioIndex = scenarioIdx % 3
        simStep = 0
        isSimulating = true
        voiceEngine.resetMemory()
        approachingCameraId = null
        approachingCameraMinDist = Double.MAX_VALUE

        when (simScenarioIndex) {
            0 -> {
                simRouteName = "模擬路線 1：台中環中路/台74線 (市政路右轉道封閉施工 + 固定測速 60)"
                currentLat = 24.1485
                currentLng = 120.6311
                currentBearingDeg = 0f // 往北行駛
                currentSpeedKmh = 74   // 故意略超速以展示神盾超速紅閃警報
                ensureDemoTargetsExist(
                    camLat = 24.1552, camLng = 120.6311, limit = 60, isInterval = false,
                    camAddr = "臺中市西屯區環中路三段(近朝馬路)固定測速",
                    evLat = 24.1605, evLng = 120.6311, isClosure = true,
                    evRoad = "環中路往台灣大道(市政路前)",
                    evDesc = "右轉專用道封閉施工，請提早變換至內側車道或改道黎明路"
                )
            }
            1 -> {
                simRouteName = "模擬路線 2：國道1號五楊高架南下 (機場系統匝道施工緩撞車 + 國道測速 100)"
                currentLat = 25.0815
                currentLng = 121.4419
                currentBearingDeg = 180f // 往南行駛
                currentSpeedKmh = 108
                ensureDemoTargetsExist(
                    camLat = 25.0745, camLng = 121.4419, limit = 100, isInterval = false,
                    camAddr = "國道一號五楊高架南下 50.2K 固定式測速照相",
                    evLat = 25.0698, evLng = 121.4419, isClosure = false,
                    evRoad = "汐止楊梅-國道1號高架南下",
                    evDesc = "機場系統出口匝道前，外側車道施工緩撞車作業中，請注意減速避讓"
                )
            }
            else -> {
                simRouteName = "模擬路線 3：台61線西濱快速道路 (區間測速 80 + 半半施工單車道管制)"
                currentLat = 23.8235
                currentLng = 120.2830
                currentBearingDeg = 180f // 往南行駛
                currentSpeedKmh = 86
                ensureDemoTargetsExist(
                    camLat = 23.8168, camLng = 120.2830, limit = 80, isInterval = true,
                    camAddr = "台61線西濱快速道路 (區間測速起點)",
                    evLat = 23.8112, evLng = 120.2830, isClosure = true,
                    evRoad = "(西濱快速)-台61線南下 214K",
                    evDesc = "公路設施改善工程半半施工，封閉外側車道僅維持單車道通行，請依工區速限減速"
                )
            }
        }

        voiceEngine.speakNow("開始實境模擬試駕：$simRouteName", urgentTone = false)
        mainHandler.removeCallbacks(simulationTickRunnable)
        mainHandler.post(simulationTickRunnable)
    }

    fun stopDemoSimulation() {
        isSimulating = false
        simRouteName = ""
        currentSpeedKmh = 0
        mainHandler.removeCallbacks(simulationTickRunnable)
        recomputeRadarState()
    }

    private fun ensureDemoTargetsExist(
        camLat: Double, camLng: Double, limit: Int, isInterval: Boolean, camAddr: String,
        evLat: Double, evLng: Double, isClosure: Boolean, evRoad: String, evDesc: String
    ) {
        repository.cameras.removeAll { it.id.startsWith("SIM-") }
        repository.roadEvents.removeAll { it.uid.startsWith("SIM-") }

        repository.cameras.add(
            0,
            SpeedCamera(
                id = "SIM-CAM-$simScenarioIndex",
                city = "實境試駕",
                region = "測試路段",
                address = camAddr,
                dept = "警政署測速資料",
                lat = camLat,
                lng = camLng,
                directText = "雙向",
                speedLimit = limit,
                type = if (isInterval) CameraType.INTERVAL else CameraType.FIXED
            )
        )

        val sev = if (isClosure) EventSeverity.CLOSURE else EventSeverity.CONSTRUCTION
        repository.roadEvents.add(
            0,
            RoadEvent(
                uid = "SIM-EV-$simScenarioIndex",
                roadName = evRoad,
                areaName = evRoad,
                direction = if (currentBearingDeg == 0f) "北上" else "南下",
                lat = evLat,
                lng = evLng,
                rawComment = evDesc,
                voiceSummary = "$evRoad，${sev.label}：$evDesc",
                severity = sev,
                category = if (limit >= 80) RoadCategory.FREEWAY else RoadCategory.LOCAL,
                isCleared = false,
                isActiveNow = true,
                scheduleNote = "目前施工管制中",
                happenTime = "剛剛通報",
                sourceDetail = "警廣/養工處即時通報"
            )
        )
    }

    private fun advanceSimulationStep() {
        simStep++
        val rad = Math.toRadians(currentBearingDeg.toDouble())
        // 每秒推進約 22 公尺 (約 80 km/h)
        val deltaDeg = 0.00020
        currentLat += cos(rad) * deltaDeg
        currentLng += sin(rad) * deltaDeg

        // 模擬接近測速桿後自動微調車速
        if (simStep in 18..28) {
            currentSpeedKmh = (currentSpeedKmh - 2).coerceAtLeast(56)
        } else if (simStep > 45) {
            simStep = 0
            startDemoSimulation((simScenarioIndex + 1) % 3)
            return
        }
        recomputeRadarState()
    }

    /**
     * 核心雙雷達運算：找出車頭前方最近的「測速照相」與「施工/封閉事件」並驅動語音與懸浮窗
     */
    fun recomputeRadarState() {
        val dynamicCamRadius = when {
            currentSpeedKmh >= 90 -> 1300.0
            currentSpeedKmh >= 60 -> 900.0
            else -> 650.0
        }
        val dynamicEventRadius = when {
            currentSpeedKmh >= 90 -> 3200.0
            currentSpeedKmh >= 60 -> 2200.0
            else -> alertRadiusMeters
        }

        val candidateCameras = ArrayList<ScoredCamera>()
        for (cam in repository.cameras) {
            val dLat = kotlin.math.abs(cam.lat - currentLat)
            val dLng = kotlin.math.abs(cam.lng - currentLng)
            if (dLat > 0.04 || dLng > 0.04) continue

            val dist = GeoUtils.distanceMeters(currentLat, currentLng, cam.lat, cam.lng)
            if (dist > 3500.0) continue

            val targetBearing = GeoUtils.bearingDeg(currentLat, currentLng, cam.lat, cam.lng)
            val relAngle = GeoUtils.angleDiff(currentBearingDeg.toDouble(), targetBearing)

            val isAhead = if (forwardConeOnly && (currentSpeedKmh > 10 || isSimulating)) {
                relAngle <= 68.0 && GeoUtils.isDirectionCompatible(currentBearingDeg, cam.directText)
            } else {
                true
            }
            if (isAhead) {
                candidateCameras.add(ScoredCamera(cam, dist, relAngle))
            }
        }
        candidateCameras.sortBy { it.distanceMeters }

        val nearestCam = candidateCameras.firstOrNull { it.distanceMeters <= dynamicCamRadius }

        // 檢查是否剛剛通過上一支測速桿 -> 播放神盾「叮咚」解除音
        if (approachingCameraId != null) {
            val stillSame = nearestCam?.camera?.id == approachingCameraId
            if (!stillSame && approachingCameraMinDist <= 260.0) {
                voiceEngine.playCameraPassedChime()
                approachingCameraId = null
                approachingCameraMinDist = Double.MAX_VALUE
            }
        }
        if (nearestCam != null) {
            if (nearestCam.camera.id != approachingCameraId) {
                approachingCameraId = nearestCam.camera.id
                approachingCameraMinDist = nearestCam.distanceMeters
            } else if (nearestCam.distanceMeters < approachingCameraMinDist) {
                approachingCameraMinDist = nearestCam.distanceMeters
            }
            voiceEngine.checkAndAnnounceCamera(nearestCam.camera, nearestCam.distanceMeters, currentSpeedKmh)
        }

        // 區間測速均速計算
        val inInterval = nearestCam?.camera?.type == CameraType.INTERVAL && nearestCam.distanceMeters <= 1200.0
        if (inInterval) {
            if (intervalStartTimestamp == 0L) {
                intervalStartTimestamp = System.currentTimeMillis()
                intervalSpeedSum = 0.0
                intervalSampleCount = 0
            }
            intervalSpeedSum += currentSpeedKmh
            intervalSampleCount++
        } else {
            intervalStartTimestamp = 0L
            intervalSpeedSum = 0.0
            intervalSampleCount = 0
        }
        val intervalAvg = if (intervalSampleCount > 0) (intervalSpeedSum / intervalSampleCount).roundToInt() else currentSpeedKmh
        val intervalRemSec = if (inInterval && nearestCam != null) {
            ((nearestCam.distanceMeters / ((nearestCam.camera.speedLimit / 3.6).coerceAtLeast(10.0)))).roundToInt()
        } else 0

        // 掃描前方施工、封閉與路況事件
        val candidateEvents = ArrayList<ScoredRoadEvent>()
        var activeTotal = 0
        for (ev in repository.roadEvents) {
            if (ev.isCleared) continue
            activeTotal++
            val dist = GeoUtils.distanceMeters(currentLat, currentLng, ev.lat, ev.lng)
            if (dist > 25_000.0) continue

            val targetBearing = GeoUtils.bearingDeg(currentLat, currentLng, ev.lat, ev.lng)
            val relAngle = GeoUtils.angleDiff(currentBearingDeg.toDouble(), targetBearing)

            val isAhead = if (forwardConeOnly && (currentSpeedKmh > 10 || isSimulating)) {
                relAngle <= 72.0 && GeoUtils.isDirectionCompatible(currentBearingDeg, ev.direction)
            } else {
                true
            }
            if (isAhead || dist <= 5000.0) {
                candidateEvents.add(ScoredRoadEvent(ev, dist, relAngle))
            }
        }

        // 前方預警優先挑選：距離在預警半徑內且正在生效的「封閉管制」或「道路施工」
        candidateEvents.sortWith(
            compareBy<ScoredRoadEvent> { it.distanceMeters > dynamicEventRadius }
                .thenBy { if (forwardConeOnly) it.relativeAngleDeg > 72.0 else false }
                .thenBy { it.event.severity.priority }
                .thenBy { it.distanceMeters }
        )

        val alertEvent = candidateEvents.firstOrNull {
            it.distanceMeters <= dynamicEventRadius &&
                    (!forwardConeOnly || it.relativeAngleDeg <= 72.0 || currentSpeedKmh <= 10)
        }
        if (alertEvent != null) {
            voiceEngine.checkAndAnnounceRoadEvent(alertEvent.event, alertEvent.distanceMeters)
        }

        val isOverspeed = nearestCam != null &&
                currentSpeedKmh > (nearestCam.camera.speedLimit + voiceEngine.overspeedToleranceKmh)

        val snapshot = RadarSnapshot(
            lat = currentLat,
            lng = currentLng,
            speedKmh = currentSpeedKmh,
            bearingDeg = currentBearingDeg,
            bearingLabel = GeoUtils.compassLabel(currentBearingDeg),
            isSimulating = isSimulating,
            simRouteName = simRouteName,
            nearestCamera = nearestCam,
            isOverspeed = isOverspeed,
            inIntervalZone = inInterval,
            intervalAvgSpeedKmh = intervalAvg,
            intervalRemainingSec = intervalRemSec,
            nearestRoadEvent = alertEvent,
            nearbyEvents = candidateEvents.take(30),
            nearbyCameras = candidateCameras.take(25),
            lastUpdatedTime = repository.lastSyncTimeStr,
            totalCamerasCount = repository.cameras.size,
            totalActiveEventsCount = activeTotal
        )
        latestSnapshot = snapshot
        updateFloatingOverlayUi(snapshot)
        for (l in listeners) {
            l(snapshot)
        }
    }

    /**
     * 啟動系統級「神盾測速 + 施工封閉」二合一懸浮視窗 (直接浮在 Google Maps 上方)
     */
    @SuppressLint("ClickableViewAccessibility")
    fun showFloatingOverlay(): Boolean {
        if (!Settings.canDrawOverlays(this)) return false
        if (floatingRootView != null) return true

        val dp = { v: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                v.toFloat(),
                resources.displayMetrics
            ).toInt()
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(14), dp(8))
            floatContainerBg = GradientDrawable().apply {
                setColor(Color.parseColor("#EA0B132B"))
                cornerRadius = dp(22).toFloat()
                setStroke(dp(2), Color.parseColor("#38BDF8"))
            }
            background = floatContainerBg
        }

        // 左側：台灣紅圈白底速限牌
        val limitTv = TextView(this).apply {
            text = "60"
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            width = dp(46)
            height = dp(46)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(5), Color.parseColor("#E63946"))
            }
        }
        floatSpeedLimitTv = limitTv
        root.addView(limitTv)

        // 中間：目前 GPS 時速
        val speedCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
        }
        val speedValTv = TextView(this).apply {
            text = "0"
            setTextColor(Color.parseColor("#00F5D4"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val speedUnitTv = TextView(this).apply {
            text = "km/h"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            gravity = Gravity.CENTER
        }
        floatCurrentSpeedTv = speedValTv
        speedCol.addView(speedValTv)
        speedCol.addView(speedUnitTv)
        root.addView(speedCol)

        // 右側：上排測速倒數 + 下排道路施工/封閉警示
        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val camTv = TextView(this).apply {
            text = "📸 測速雷達待命中"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        val roadTv = TextView(this).apply {
            text = "🚧 前方無施工封閉"
            setTextColor(Color.parseColor("#FBBF24"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
        }
        floatCameraTitleTv = camTv
        floatRoadAlertTv = roadTv
        infoCol.addView(camTv)
        infoCol.addView(roadTv)
        root.addView(infoCol)

        // 右側關閉/展開小按鈕
        val closeBtn = TextView(this).apply {
            text = " ✕ "
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(8), dp(4), dp(2), dp(4))
            setOnClickListener { hideFloatingOverlay() }
        }
        root.addView(closeBtn)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(90)
        }

        // 支援手指自由拖曳懸浮視窗，輕點則返回 App 主畫面
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var isMoved = false

        root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    isMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) {
                        isMoved = true
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager?.updateViewLayout(root, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isMoved) {
                        val openIntent = Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        startActivity(openIntent)
                    }
                    true
                }
                else -> false
            }
        }

        return try {
            windowManager?.addView(root, params)
            floatingRootView = root
            floatingParams = params
            updateFloatingOverlayUi(latestSnapshot)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun hideFloatingOverlay() {
        val v = floatingRootView ?: return
        try {
            windowManager?.removeView(v)
        } catch (_: Exception) {
        }
        floatingRootView = null
    }

    fun isFloatingOverlayShowing(): Boolean = floatingRootView != null

    private fun updateFloatingOverlayUi(snap: RadarSnapshot) {
        if (floatingRootView == null) return
        mainHandler.post {
            floatCurrentSpeedTv?.text = "${snap.speedKmh}"
            val cam = snap.nearestCamera
            if (cam != null) {
                floatSpeedLimitTv?.text = "${cam.camera.speedLimit}"
                floatCameraTitleTv?.text =
                    "📸 ${cam.distanceMeters.roundToInt()}m | ${cam.camera.type.label}"
            } else {
                floatSpeedLimitTv?.text = "--"
                floatCameraTitleTv?.text = "📸 前方無測速 (${snap.bearingLabel})"
            }

            val ev = snap.nearestRoadEvent
            if (ev != null) {
                val distLabel = if (ev.distanceMeters >= 1000) {
                    String.format("%.1fkm", ev.distanceMeters / 1000.0)
                } else {
                    "${ev.distanceMeters.roundToInt()}m"
                }
                val icon = if (ev.event.severity == EventSeverity.CLOSURE) "🔴封閉" else "🟠施工"
                floatRoadAlertTv?.text = "$icon $distLabel ${ev.event.roadName.take(10)}"
                floatRoadAlertTv?.setTextColor(
                    if (ev.event.severity == EventSeverity.CLOSURE) Color.parseColor("#FF4D6D")
                    else Color.parseColor("#FBBF24")
                )
            } else {
                floatRoadAlertTv?.text = "✅ 前方路線無施工封閉"
                floatRoadAlertTv?.setTextColor(Color.parseColor("#34D399"))
            }

            // 超速或遇到道路封閉時，懸浮視窗外框變紅警示
            val dp2 = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 2.5f, resources.displayMetrics
            ).toInt()
            if (snap.isOverspeed) {
                floatCurrentSpeedTv?.setTextColor(Color.parseColor("#FF2E4C"))
                floatContainerBg?.setStroke(dp2, Color.parseColor("#FF2E4C"))
            } else if (ev?.event?.severity == EventSeverity.CLOSURE && ev.distanceMeters <= 1500) {
                floatCurrentSpeedTv?.setTextColor(Color.parseColor("#00F5D4"))
                floatContainerBg?.setStroke(dp2, Color.parseColor("#FF9F1C"))
            } else {
                floatCurrentSpeedTv?.setTextColor(Color.parseColor("#00F5D4"))
                floatContainerBg?.setStroke(dp2, Color.parseColor("#38BDF8"))
            }
        }
    }

    override fun onDestroy() {
        hideFloatingOverlay()
        mainHandler.removeCallbacksAndMessages(null)
        try {
            locationManager?.removeUpdates(this)
        } catch (_: Exception) {
        }
        voiceEngine.shutdown()
        super.onDestroy()
    }
}
