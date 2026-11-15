package tw.roadguard.aegis.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import tw.roadguard.aegis.model.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.cos
import kotlin.math.sin

class TaiwanOpenDataRepository(private val context: Context) {

    companion object {
        private const val PBS_OPEN_DATA_URL = "https://rtr.pbs.gov.tw/NMP103_PbsWS/resources/roadData/opendata"
        private const val NPA_CAMERAS_CSV_URL =
            "https://opdadm.moi.gov.tw/api/v1/no-auth/resource/api/dataset/EA5E6FCD-B82D-43B7-A5CF-E9893253187E/resource/AA22AE1B-D30A-4B93-83E4-5ABB9D5CB989/download"

        private const val CACHE_CAMERAS_FILE = "cameras_cache.csv"
        private const val CACHE_ROAD_EVENTS_FILE = "road_events_cache.json"
        private const val CACHE_MANIFEST_FILE = "manifest_cache.json"
        private const val CUSTOM_POINTS_FILE = "custom_points.json"
        private const val PREFS_NAME = "roadguard_cloud_prefs"
        private const val KEY_AUTO_DAILY = "auto_daily_update"
        private const val KEY_GITHUB_REPO = "github_repo_slug"
        const val DEFAULT_REPO = "jeff2428/Driving-Copilot"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class RoadData(
        val cameras: CopyOnWriteArrayList<SpeedCamera> = CopyOnWriteArrayList(),
        val roadEvents: CopyOnWriteArrayList<RoadEvent> = CopyOnWriteArrayList()
    )

    @Volatile var roadData = RoadData()
        private set
    val cameras get() = roadData.cameras
    val roadEvents get() = roadData.roadEvents
    private val pointLock = Any()
    private val cache = android.util.AtomicFile(File(context.filesDir, "road-data-v1.json"))
    private var lastCsv = ""
    private var camerasUpdatedAt = 0L

    fun editDemoTargets(edit: (RoadData) -> Unit) = synchronized(pointLock) { edit(roadData) }

    val customCameras = CopyOnWriteArrayList<SpeedCamera>()
    val customRoadEvents = CopyOnWriteArrayList<RoadEvent>()

    @Volatile
    var lastSyncTimeStr: String = "載入中..."
        private set

    @Volatile
    var lastCameraSyncDateStr: String = "內建題庫 (可線上更新)"
        private set

    @Volatile
    var dataVersionStr: String = "2026.10.17"
        private set

    @Volatile
    var lastSyncChannelStr: String = "內建警政署＋警廣圖資"
        private set

    var autoDailyUpdateEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DAILY, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_DAILY, value).apply()

    var githubRepoSlug: String
        get() = prefs.getString(KEY_GITHUB_REPO, DEFAULT_REPO)?.ifBlank { DEFAULT_REPO } ?: DEFAULT_REPO
        set(value) = prefs.edit().putString(KEY_GITHUB_REPO, value.trim()).apply()

    @Synchronized fun initializeFromLocalAssets() {
        loadCustomPoints()
        fun load(raw: JSONObject): Boolean = try {
            installBatch(raw, persist = false)
            true
        } catch (_: Exception) { false }

        val saved = try { JSONObject(cache.openRead().bufferedReader().use { it.readText() }) } catch (_: Exception) { null }
        if (saved != null && load(saved)) return
        fun legacyOrSeed(file: String, asset: String, useLegacy: Boolean): String {
            val old = File(context.filesDir, file)
            return if (useLegacy && old.isFile) old.readText()
            else context.assets.open(asset).bufferedReader().use { it.readText() }
        }
        for (useLegacy in listOf(true, false)) {
            try {
                val manifest = JSONObject(legacyOrSeed(CACHE_MANIFEST_FILE, "cloud_manifest.json", useLegacy))
                val batch = JSONObject().put("version", manifest.optString("version", dataVersionStr))
                    .put("date", manifest.optString("updatedAt")).put("channel", "離線圖資")
                    .put("csv", legacyOrSeed(CACHE_CAMERAS_FILE, "taiwan_cameras_seed.csv", useLegacy))
                    .put("events", legacyOrSeed(CACHE_ROAD_EVENTS_FILE, "taiwan_road_events_seed.json", useLegacy))
                if (load(batch)) return
            } catch (_: Exception) { /* 下一輪使用內建圖資 */ }
        }
    }

    private fun shouldAutoUpdateCameras() =
        autoDailyUpdateEnabled && System.currentTimeMillis() - camerasUpdatedAt > 86_400_000L

    @Synchronized internal fun installBatch(batch: JSONObject, persist: Boolean) {
        val csv = batch.getString("csv")
        val events = batch.getString("events")
        val version = batch.getString("version")
        require(version.isNotBlank())
        val parsedCameras = parseCamerasCsv(csv.lines())
        val parsedEvents = parsePbsRoadEventsJson(events)
        require(parsedCameras.size > 500 && parsedEvents.isNotEmpty())
        if (persist) {
            val stream = cache.startWrite()
            try {
                stream.write(batch.toString().toByteArray(Charsets.UTF_8))
                cache.finishWrite(stream)
            } catch (e: Exception) {
                cache.failWrite(stream)
                throw e
            }
        }
        synchronized(pointLock) {
            roadData = RoadData(
                CopyOnWriteArrayList(customCameras + parsedCameras + cameras.filter { it.id.startsWith("SIM-") }),
                CopyOnWriteArrayList(customRoadEvents + parsedEvents + roadEvents.filter { it.uid.startsWith("SIM-") })
            )
        }
        lastCsv = csv
        camerasUpdatedAt = batch.optLong("cameraTime")
        dataVersionStr = version
        lastCameraSyncDateStr = batch.optString("date", version)
        lastSyncChannelStr = batch.optString("channel")
        lastSyncTimeStr = SimpleDateFormat("HH:mm:ss", Locale.TAIWAN).format(Date())
    }

    @Synchronized fun syncAllCloudData(forceCameras: Boolean = true): Pair<Boolean, String> {
        val slug = githubRepoSlug.trim().removePrefix("https://github.com/").trim('/')
        if (slug.contains("/")) {
            for (base in listOf("https://cdn.jsdelivr.net/gh/$slug@main/cloud_data", "https://raw.githubusercontent.com/$slug/main/cloud_data")) {
                try {
                    val manifest = JSONObject(fetchUrlText("$base/manifest.json", 10000))
                    val csv = fetchUrlText("$base/taiwan_cameras_latest.csv", 15000)
                    val events = fetchUrlText("$base/taiwan_road_events_latest.json", 15000)
                    for ((name, content) in listOf("sha256Cameras" to csv, "sha256Events" to events)) {
                        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
                        require(digest.joinToString("") { "%02x".format(it) }.equals(manifest.getString(name), true))
                    }
                    val batch = JSONObject().put("csv", csv).put("events", events)
                        .put("version", manifest.getString("version")).put("date", manifest.getString("updatedAt"))
                        .put("cameraTime", System.currentTimeMillis()).put("channel", "GitHub Actions CDN ($slug)")
                    installBatch(batch, persist = true)
                    return true to "已同步圖資 v$dataVersionStr（測速 ${cameras.size} 支・路況 ${roadEvents.size} 筆）"
                } catch (_: Exception) { /* 試下一個來源 */ }
            }
        }
        val ok = refreshDirect(forceCameras || shouldAutoUpdateCameras() || lastCsv.isEmpty())
        return ok to if (ok) "已同步最新圖資 v$dataVersionStr" else "更新失敗，沿用圖資 v$dataVersionStr"
    }

    private fun refreshDirect(updateCameras: Boolean): Boolean = try {
        val csv = if (updateCameras) fetchUrlText(NPA_CAMERAS_CSV_URL, 15000) else lastCsv
        val events = fetchUrlText(PBS_OPEN_DATA_URL, 12000)
        val now = Date()
        val batch = JSONObject().put("csv", csv).put("events", events)
            .put("date", if (updateCameras) SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.TAIWAN).format(now) else lastCameraSyncDateStr)
            .put("cameraTime", if (updateCameras) now.time else camerasUpdatedAt)
            .put("version", SimpleDateFormat("yyyy.MM.dd.HHmm", Locale.TAIWAN).format(now))
            .put("channel", "內政部警政署＋警廣 OpenData 直連")
        installBatch(batch, persist = true)
        true
    } catch (_: Exception) { false }

    @Synchronized fun refreshLiveRoadEvents(): Boolean = refreshDirect(lastCsv.isEmpty())

    private fun fetchUrlText(urlStr: String, timeoutMs: Int): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; TWRoadGuardAegis/2.0)")
        }
        return try {
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            val bytes = conn.inputStream.use { it.readBytes() }
            val expectedSize = conn.contentLengthLong
            check(expectedSize < 0 || bytes.size.toLong() == expectedSize)
            bytes.toString(Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseCamerasCsv(lines: List<String>): List<SpeedCamera> {
        val result = ArrayList<SpeedCamera>(2000)
        var index = 0
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("\uFEFFCityName") || line.startsWith("CityName") || line.startsWith("設置縣市")) {
                continue
            }
            val cols = line.split(",")
            if (cols.size < 9) continue
            val city = cols[0].trim()
            val region = cols[1].trim()
            val address = cols[2].trim()
            val dept = cols[3].trim()
            val lng = cols[5].trim().toDoubleOrNull() ?: continue
            val lat = cols[6].trim().toDoubleOrNull() ?: continue
            if (lat !in 21.5..26.5 || lng !in 118.0..122.5) continue

            val directText = cols[7].trim()
            val limitStr = cols[8].trim().filter { it.isDigit() }
            val speedLimit = limitStr.toIntOrNull()?.coerceIn(20, 120) ?: 60

            val cameraType = when {
                address.contains("區間") || directText.contains("區間") -> CameraType.INTERVAL
                address.contains("科技執法") || directText.contains("科技執法") || address.contains("不停讓") -> CameraType.TECH
                address.contains("經常出沒") || directText.contains("經常出沒") ||
                    address.contains("移動") || directText.contains("移動") ||
                    address.contains("流動") || directText.contains("流動") ||
                    address.contains("三腳架") || directText.contains("三腳架") ||
                    address.contains("雷射") || directText.contains("雷射") -> CameraType.HOTSPOT
                else -> CameraType.FIXED
            }

            index++
            result.add(
                SpeedCamera(
                    id = "NPA-$index",
                    city = city,
                    region = region,
                    address = address,
                    dept = dept,
                    lat = lat,
                    lng = lng,
                    directText = directText,
                    speedLimit = speedLimit,
                    type = cameraType
                )
            )
        }
        return result
    }

    private fun parsePbsRoadEventsJson(jsonStr: String): List<RoadEvent> {
        val list = ArrayList<RoadEvent>(1000)
        val startIdx = jsonStr.indexOf('{')
        if (startIdx < 0) return list
        val root = JSONObject(jsonStr.substring(startIdx))
        val arr = root.optJSONArray("result") ?: return list

        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Taipei"))
        val currentHour = cal.get(Calendar.HOUR_OF_DAY)

        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val uid = item.optString("UID", "PBS-$i")
            val roadType = item.optString("roadtype", "").trim()
            val areaNm = item.optString("areaNm", "").trim()
            val road = item.optString("road", "").trim()
            val direction = item.optString("direction", "").trim()
            val rawComment = item.optString("comment", "")
                .replace("<br>", " ")
                .replace("<br/>", " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            val srcDetail = item.optString("srcdetail", "").trim()
            val happenDate = item.optString("happendate", "")
            val happenTime = item.optString("happentime", "").take(5)

            var lng = item.optString("x1", "").trim().toDoubleOrNull() ?: 0.0
            var lat = item.optString("y1", "").trim().toDoubleOrNull() ?: 0.0

            // 若為粗略座標 (如台中市交大預設 120.52, 24.2)，根據路名自動補正到對應市區座標
            val refinedCoord = refineCoarseCoordinates(lat, lng, areaNm, road, rawComment, srcDetail)
            lat = refinedCoord.first
            lng = refinedCoord.second
            if (lat !in 21.5..26.5 || lng !in 118.0..122.5) continue

            // 檢查是否已排除
            val isCleared = rawComment.contains("已排除") ||
                    rawComment.contains(":排除") ||
                    rawComment.contains("：排除") ||
                    rawComment.contains("自行駛離") ||
                    rawComment.contains("恢復通行") ||
                    rawComment.contains("解除管制") ||
                    rawComment.contains("狀況排除")

            // 判斷嚴重等級
            val combinedText = "$roadType $areaNm $road $rawComment"
            val severity = when {
                combinedText.contains("封閉") ||
                        combinedText.contains("管制") ||
                        combinedText.contains("阻斷") ||
                        combinedText.contains("禁止通行") ||
                        combinedText.contains("全線封閉") ||
                        combinedText.contains("單線雙向") ||
                        roadType == "交通管制" ||
                        roadType == "災變" -> EventSeverity.CLOSURE

                roadType == "道路施工" ||
                        combinedText.contains("施工") ||
                        combinedText.contains("緩撞車") ||
                        combinedText.contains("車道縮減") ||
                        combinedText.contains("工程") ||
                        combinedText.contains("銑鋪") ||
                        combinedText.contains("養護") -> EventSeverity.CONSTRUCTION

                roadType == "事故" ||
                        roadType == "交通障礙" ||
                        roadType == "號誌故障" ||
                        combinedText.contains("事故") ||
                        combinedText.contains("散落") ||
                        combinedText.contains("掉落") ||
                        combinedText.contains("故障車") -> EventSeverity.HAZARD

                else -> EventSeverity.CONGESTION
            }

            // 判斷道路類別
            val roadTitle = when {
                road.isNotBlank() && areaNm.isNotBlank() -> "$areaNm $road"
                areaNm.isNotBlank() -> areaNm
                road.isNotBlank() -> road
                else -> extractRoadNameFromComment(rawComment, srcDetail)
            }

            val category = when {
                roadTitle.contains("國道") || roadTitle.contains("高速公路") || roadTitle.contains("五楊") || roadTitle.contains("雪山隧道") ->
                    RoadCategory.FREEWAY
                roadTitle.contains("台") || roadTitle.contains("快速") || roadTitle.contains("西濱") || roadTitle.contains("公路") ->
                    RoadCategory.EXPRESSWAY
                else -> RoadCategory.LOCAL
            }

            // 解析施工時段
            val (isActiveNow, scheduleNote) = evaluateScheduleWindow(rawComment, currentHour)

            // 產生精簡行車語音播報句
            val voiceSummary = buildConciseVoiceSummary(roadTitle, direction, severity, rawComment)

            list.add(
                RoadEvent(
                    uid = uid,
                    roadName = roadTitle,
                    areaName = areaNm,
                    direction = direction,
                    lat = lat,
                    lng = lng,
                    rawComment = rawComment,
                    voiceSummary = voiceSummary,
                    severity = severity,
                    category = category,
                    isCleared = isCleared,
                    isActiveNow = isActiveNow,
                    scheduleNote = scheduleNote,
                    happenTime = "$happenDate $happenTime".trim(),
                    sourceDetail = srcDetail.ifBlank { "警廣即時路況" }
                )
            )
        }

        // 依優先級排序：正在生效的封閉管制 -> 道路施工 -> 事故障礙 -> 壅塞
        return list.sortedWith(
            compareBy<RoadEvent> { it.isCleared }
                .thenByDescending { it.isActiveNow }
                .thenBy { it.severity.priority }
        )
    }

    private fun refineCoarseCoordinates(
        lat: Double,
        lng: Double,
        areaNm: String,
        road: String,
        comment: String,
        srcDetail: String
    ): Pair<Double, Double> {
        val isCoarseTaichung = (lat == 24.2 && lng == 120.52) || (lat == 0.0 || lng == 0.0)
        if (!isCoarseTaichung) return Pair(lat, lng)

        val text = "$areaNm $road $comment $srcDetail"
        return when {
            text.contains("崇德路") && text.contains("進化北路") -> Pair(24.1628, 120.6845)
            text.contains("環中路") && text.contains("市政路") -> Pair(24.1605, 120.6311)
            text.contains("台灣大道") && text.contains("文心路") -> Pair(24.1619, 120.6468)
            text.contains("台灣大道") -> Pair(24.1642, 120.6395)
            text.contains("文心路") -> Pair(24.1535, 120.6465)
            text.contains("環中路") -> Pair(24.1480, 120.6258)
            text.contains("五權西路") -> Pair(24.1411, 120.6382)
            text.contains("中清路") -> Pair(24.1812, 120.6632)
            text.contains("松竹路") -> Pair(24.1856, 120.6915)
            text.contains("北屯") -> Pair(24.1820, 120.6860)
            text.contains("西屯") -> Pair(24.1650, 120.6350)
            text.contains("南屯") -> Pair(24.1390, 120.6400)
            text.contains("臺中") || text.contains("台中") -> Pair(24.1585, 120.6540)
            text.contains("臺北") || text.contains("台北") -> Pair(25.0478, 121.5319)
            text.contains("新北") -> Pair(25.0120, 121.4650)
            text.contains("桃園") -> Pair(24.9936, 121.3010)
            text.contains("臺南") || text.contains("台南") -> Pair(22.9997, 120.2270)
            text.contains("高雄") -> Pair(22.6273, 120.3014)
            else -> if (lat != 0.0 && lng != 0.0) Pair(lat, lng) else Pair(24.1631, 120.6403)
        }
    }

    private fun extractRoadNameFromComment(comment: String, srcDetail: String): String {
        val prefix = comment.take(16).split(" ").firstOrNull().orEmpty()
        return when {
            prefix.isNotBlank() -> prefix
            srcDetail.isNotBlank() -> srcDetail
            else -> "一般道路"
        }
    }

    private fun evaluateScheduleWindow(comment: String, currentHour: Int): Pair<Boolean, String> {
        // 檢查常見夜間施工 (如 22時至翌日6時、21時至翌日6時)
        val nightRegex = Regex("(\\d{1,2})時至翌日(\\d{1,2})時")
        val nightMatch = nightRegex.find(comment)
        if (nightMatch != null) {
            val startH = nightMatch.groupValues[1].toIntOrNull() ?: 22
            val endH = nightMatch.groupValues[2].toIntOrNull() ?: 6
            val active = currentHour >= startH || currentHour < endH
            return Pair(active, "夜間施工 ${startH}:00~翌${endH}:00")
        }

        // 檢查日間施工 (如 每日8時至17時、每日7時至17時、9時至16時)
        val dayRegex = Regex("每日\\s*(\\d{1,2})\\s*時.*?至\\s*(\\d{1,2})\\s*時")
        val dayMatch = dayRegex.find(comment)
        if (dayMatch != null) {
            val startH = dayMatch.groupValues[1].toIntOrNull() ?: 8
            val endH = dayMatch.groupValues[2].toIntOrNull() ?: 17
            val active = currentHour in startH until endH
            return Pair(active, "每日施工 ${startH}:00~${endH}:00")
        }

        if (comment.contains("全天候封閉") || comment.contains("全天封閉")) {
            return Pair(true, "全天候封閉管制")
        }
        return Pair(true, "即時路況生效中")
    }

    private fun buildConciseVoiceSummary(
        roadTitle: String,
        direction: String,
        severity: EventSeverity,
        rawComment: String
    ): String {
        var cleaned = rawComment
            .replace(Regex("\\*[^*]+\\*"), "") // 移除 *1159坪控:排除* 等內部標記
            .replace(Regex("自\\d{2,3}年\\d{1,2}月\\d{1,2}日.*?止，?"), "")
            .replace("請用路人行經該路段小心駕駛。", "")
            .replace("請用路人行經該路段減速慢行", "")
            .replace("請注意路況並小心駕駛。", "")
            .replace("造成不便敬請見諒。", "")
            .replace("造成不便尚祈見諒。", "")
            .trim()
        if (cleaned.length > 52) {
            cleaned = cleaned.take(52) + "..."
        }
        val dirPart = if (direction.isNotBlank() && !roadTitle.contains(direction)) "$direction，" else ""
        return "${roadTitle}${dirPart}${severity.label}：$cleaned"
    }

    /**
     * 新增車主自訂測速黑點或臨時施工封閉點
     */
    fun addCustomRoadEvent(
        roadName: String,
        description: String,
        isClosure: Boolean,
        lat: Double,
        lng: Double
    ): RoadEvent {
        val severity = if (isClosure) EventSeverity.CLOSURE else EventSeverity.CONSTRUCTION
        val event = RoadEvent(
            uid = "CUSTOM-${System.currentTimeMillis()}",
            roadName = roadName.ifBlank { "車主標記路段" },
            areaName = "自訂回報",
            direction = "雙向",
            lat = lat,
            lng = lng,
            rawComment = description,
            voiceSummary = "${roadName}，${severity.label}：$description",
            severity = severity,
            category = RoadCategory.LOCAL,
            isCleared = false,
            isActiveNow = true,
            scheduleNote = "車主即時標記",
            happenTime = SimpleDateFormat("HH:mm", Locale.TAIWAN).format(Date()),
            sourceDetail = "車主自訂釘選",
            isCustom = true
        )
        synchronized(pointLock) {
            customRoadEvents.add(0, event)
            roadEvents.add(0, event)
        }
        saveCustomPoints()
        return event
    }

    fun addCustomSpeedCamera(
        address: String,
        speedLimit: Int,
        lat: Double,
        lng: Double
    ): SpeedCamera {
        val cam = SpeedCamera(
            id = "CUSTOM-CAM-${System.currentTimeMillis()}",
            city = "自訂",
            region = "車主標記",
            address = address.ifBlank { "常出沒移動式測速點" },
            dept = "車主自訂黑點",
            lat = lat,
            lng = lng,
            directText = "雙向",
            speedLimit = speedLimit.coerceIn(30, 110),
            type = CameraType.CUSTOM,
            isCustom = true
        )
        synchronized(pointLock) {
            customCameras.add(0, cam)
            cameras.add(0, cam)
        }
        saveCustomPoints()
        return cam
    }

    private fun saveCustomPoints() {
        try {
            val root = JSONObject()
            val evArr = JSONArray()
            for (ev in customRoadEvents) {
                evArr.put(JSONObject().apply {
                    put("uid", ev.uid)
                    put("roadName", ev.roadName)
                    put("rawComment", ev.rawComment)
                    put("isClosure", ev.severity == EventSeverity.CLOSURE)
                    put("lat", ev.lat)
                    put("lng", ev.lng)
                    put("happenTime", ev.happenTime)
                })
            }
            val camArr = JSONArray()
            for (c in customCameras) {
                camArr.put(JSONObject().apply {
                    put("id", c.id)
                    put("address", c.address)
                    put("speedLimit", c.speedLimit)
                    put("lat", c.lat)
                    put("lng", c.lng)
                })
            }
            root.put("events", evArr)
            root.put("cameras", camArr)
            File(context.filesDir, CUSTOM_POINTS_FILE).writeText(root.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadCustomPoints() {
        try {
            val f = File(context.filesDir, CUSTOM_POINTS_FILE)
            if (!f.exists()) return
            val root = JSONObject(f.readText(Charsets.UTF_8))
            val evArr = root.optJSONArray("events")
            if (evArr != null) {
                for (i in 0 until evArr.length()) {
                    val o = evArr.getJSONObject(i)
                    val isClosure = o.optBoolean("isClosure", true)
                    val severity = if (isClosure) EventSeverity.CLOSURE else EventSeverity.CONSTRUCTION
                    val roadName = o.optString("roadName", "自訂路段")
                    val desc = o.optString("rawComment", "道路封閉施工")
                    customRoadEvents.add(
                        RoadEvent(
                            uid = o.optString("uid"),
                            roadName = roadName,
                            areaName = "自訂回報",
                            direction = "雙向",
                            lat = o.optDouble("lat"),
                            lng = o.optDouble("lng"),
                            rawComment = desc,
                            voiceSummary = "${roadName}，${severity.label}：$desc",
                            severity = severity,
                            category = RoadCategory.LOCAL,
                            isCleared = false,
                            isActiveNow = true,
                            scheduleNote = "車主即時標記",
                            happenTime = o.optString("happenTime"),
                            sourceDetail = "車主自訂釘選",
                            isCustom = true
                        )
                    )
                }
            }
            val camArr = root.optJSONArray("cameras")
            if (camArr != null) {
                for (i in 0 until camArr.length()) {
                    val o = camArr.getJSONObject(i)
                    customCameras.add(
                        SpeedCamera(
                            id = o.optString("id"),
                            city = "自訂",
                            region = "車主標記",
                            address = o.optString("address"),
                            dept = "車主自訂黑點",
                            lat = o.optDouble("lat"),
                            lng = o.optDouble("lng"),
                            directText = "雙向",
                            speedLimit = o.optInt("speedLimit", 60),
                            type = CameraType.CUSTOM,
                            isCustom = true
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
