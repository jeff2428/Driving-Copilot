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
import java.net.URLEncoder
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

    val cameras = CopyOnWriteArrayList<SpeedCamera>()
    val roadEvents = CopyOnWriteArrayList<RoadEvent>()
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

    fun initializeFromLocalAssets() {
        loadManifestVersion()
        loadCustomPoints()
        loadCamerasFromAssetOrCache()
        loadRoadEventsFromAssetOrCache()
    }

    private fun loadManifestVersion() {
        try {
            val cacheManifest = File(context.filesDir, CACHE_MANIFEST_FILE)
            val jsonText = if (cacheManifest.exists()) {
                cacheManifest.readText(Charsets.UTF_8)
            } else {
                context.assets.open("cloud_manifest.json").bufferedReader(Charsets.UTF_8).readText()
            }
            val obj = JSONObject(jsonText)
            dataVersionStr = obj.optString("version", dataVersionStr)
            val updatedAt = obj.optString("updatedAt", "")
            if (updatedAt.isNotBlank()) {
                lastCameraSyncDateStr = updatedAt
            }
        } catch (_: Exception) {
        }
    }

    fun shouldAutoUpdateCameras(): Boolean {
        if (!autoDailyUpdateEnabled) return false
        val cacheFile = File(context.filesDir, CACHE_CAMERAS_FILE)
        if (!cacheFile.exists()) return true
        val ageMs = System.currentTimeMillis() - cacheFile.lastModified()
        return ageMs > 24L * 60L * 60L * 1000L // 超過 24 小時自動更新
    }

    private fun loadCamerasFromAssetOrCache() {
        try {
            val cacheFile = File(context.filesDir, CACHE_CAMERAS_FILE)
            val lines = if (cacheFile.exists() && cacheFile.length() > 10_000) {
                lastCameraSyncDateStr = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.TAIWAN)
                    .format(Date(cacheFile.lastModified()))
                cacheFile.readLines(Charsets.UTF_8)
            } else {
                context.assets.open("taiwan_cameras_seed.csv").bufferedReader(Charsets.UTF_8).readLines()
            }
            val parsed = parseCamerasCsv(lines)
            if (parsed.isNotEmpty()) {
                cameras.clear()
                cameras.addAll(parsed)
                cameras.addAll(customCameras)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadRoadEventsFromAssetOrCache() {
        try {
            val cacheFile = File(context.filesDir, CACHE_ROAD_EVENTS_FILE)
            val jsonStr = if (cacheFile.exists() && cacheFile.length() > 1_000) {
                cacheFile.readText(Charsets.UTF_8)
            } else {
                context.assets.open("taiwan_road_events_seed.json").bufferedReader(Charsets.UTF_8).readText()
            }
            val parsed = parsePbsRoadEventsJson(jsonStr)
            if (parsed.isNotEmpty()) {
                roadEvents.clear()
                roadEvents.addAll(parsed)
                roadEvents.addAll(0, customRoadEvents)
                lastSyncTimeStr = SimpleDateFormat("HH:mm:ss", Locale.TAIWAN).format(Date())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 雙通道雲端自動同步：
     * 1. 若有設定 GitHub Actions 雲端倉庫 (owner/repo)，優先從 jsDelivr / GitHub Raw CDN 拉取清洗後的最新圖資與 manifest.json
     * 2. 自動備援直連【內政部警政署 Open Data】與【警廣即時路況 API】確保永遠能抓到最新資料
     */
    fun syncAllCloudData(forceCameras: Boolean = true): Pair<Boolean, String> {
        val slug = githubRepoSlug.trim().removePrefix("https://github.com/").trim('/')
        if (slug.contains("/")) {
            val cdnCandidates = listOf(
                "https://cdn.jsdelivr.net/gh/$slug@main/cloud_data",
                "https://raw.githubusercontent.com/$slug/main/cloud_data"
            )
            for (base in cdnCandidates) {
                try {
                    val manifestText = fetchUrlText("$base/manifest.json", 10000)
                    val manifest = JSONObject(manifestText)
                    val remoteVer = manifest.optString("version", "")
                    val csvText = fetchUrlText("$base/taiwan_cameras_latest.csv", 15000)
                    val evText = fetchUrlText("$base/taiwan_road_events_latest.json", 15000)

                    val parsedCams = parseCamerasCsv(csvText.lines())
                    val parsedEvs = parsePbsRoadEventsJson(evText)
                    if (parsedCams.size > 500 && parsedEvs.isNotEmpty()) {
                        File(context.filesDir, CACHE_MANIFEST_FILE).writeText(manifestText, Charsets.UTF_8)
                        File(context.filesDir, CACHE_CAMERAS_FILE).writeText(csvText, Charsets.UTF_8)
                        File(context.filesDir, CACHE_ROAD_EVENTS_FILE).writeText(evText, Charsets.UTF_8)

                        dataVersionStr = remoteVer.ifBlank {
                            SimpleDateFormat("yyyy.MM.dd.HHmm", Locale.TAIWAN).format(Date())
                        }
                        lastCameraSyncDateStr = manifest.optString("updatedAt", dataVersionStr)
                        lastSyncTimeStr = SimpleDateFormat("HH:mm:ss", Locale.TAIWAN).format(Date())
                        lastSyncChannelStr = "GitHub Actions CDN ($slug)"

                        cameras.clear()
                        cameras.addAll(parsedCams)
                        cameras.addAll(customCameras)

                        roadEvents.clear()
                        roadEvents.addAll(parsedEvs)
                        roadEvents.addAll(0, customRoadEvents)
                        return Pair(true, "已從 GitHub 雲端同步 v$dataVersionStr（測速 ${cameras.size} 支・路況 ${roadEvents.size} 筆）")
                    }
                } catch (_: Exception) {
                }
            }
        }

        // 通道 B：直連內政部警政署 + 警廣即時路況 Open Data
        val evOk = refreshLiveRoadEvents()
        val camOk = if (forceCameras || shouldAutoUpdateCameras()) refreshLiveCameras() else true
        if (evOk || camOk) {
            dataVersionStr = SimpleDateFormat("yyyy.MM.dd.HHmm", Locale.TAIWAN).format(Date())
            lastSyncChannelStr = "內政部警政署＋警廣 OpenData 直連"
            val manifestObj = JSONObject().apply {
                put("version", dataVersionStr)
                put("updatedAt", lastCameraSyncDateStr)
                put("cameraCount", cameras.size)
                put("eventCount", roadEvents.size)
            }
            try {
                File(context.filesDir, CACHE_MANIFEST_FILE).writeText(manifestObj.toString(), Charsets.UTF_8)
            } catch (_: Exception) {
            }
            return Pair(true, "已同步最新圖資 v$dataVersionStr（測速 ${cameras.size} 支・施工路況 ${roadEvents.size} 筆）")
        }
        return Pair(false, "目前使用離線快取圖資 v$dataVersionStr（測速 ${cameras.size} 支・路況 ${roadEvents.size} 筆）")
    }

    private fun fetchUrlText(urlStr: String, timeoutMs: Int): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; TWRoadGuardAegis/2.0)")
        }
        if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
        return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /**
     * 從內政部警廣 API 同步最新 1,000 筆路況與施工封閉事件
     */
    fun refreshLiveRoadEvents(): Boolean {
        return try {
            val jsonText = fetchUrlText(PBS_OPEN_DATA_URL, 12000)
            if (jsonText.contains("\"result\"")) {
                File(context.filesDir, CACHE_ROAD_EVENTS_FILE).writeText(jsonText, Charsets.UTF_8)
                val parsed = parsePbsRoadEventsJson(jsonText)
                if (parsed.isNotEmpty()) {
                    roadEvents.clear()
                    roadEvents.addAll(parsed)
                    roadEvents.addAll(0, customRoadEvents)
                    lastSyncTimeStr = SimpleDateFormat("HH:mm:ss", Locale.TAIWAN).format(Date())
                    return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 從內政部警政署 API 更新全國測速照相 CSV
     */
    fun refreshLiveCameras(): Boolean {
        return try {
            val csvText = fetchUrlText(NPA_CAMERAS_CSV_URL, 15000)
            val lines = csvText.lines()
            val parsed = parseCamerasCsv(lines)
            if (parsed.size > 500) {
                val cacheFile = File(context.filesDir, CACHE_CAMERAS_FILE)
                cacheFile.writeText(csvText, Charsets.UTF_8)
                lastCameraSyncDateStr = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.TAIWAN)
                    .format(Date(cacheFile.lastModified()))
                cameras.clear()
                cameras.addAll(parsed)
                cameras.addAll(customCameras)
                return true
            }
            false
        } catch (e: Exception) {
            false
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
        customRoadEvents.add(0, event)
        roadEvents.add(0, event)
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
        customCameras.add(0, cam)
        cameras.add(0, cam)
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

    /**
     * 出發前路線走廊掃描：輸入目的地名稱或座標，找出沿線 350m 內的道路封閉、施工與測速照相，並計算繞道點
     */
    fun scanRouteCorridor(destinationQuery: String, originLat: Double, originLng: Double): RouteScanResult? {
        val resolvedDest = resolveTaiwanDestination(destinationQuery) ?: return null
        val (destName, destLat, destLng) = resolvedDest

        val routePoints = ArrayList<Pair<Double, Double>>()
        var distanceKm = GeoUtils.distanceMeters(originLat, originLng, destLat, destLng) / 1000.0
        var durationMin = ((distanceKm / 60.0) * 60).toInt().coerceAtLeast(3)

        // 呼叫 OSRM 取得真實道路幾何座標
        try {
            val osrmUrl =
                "https://router.project-osrm.org/route/v1/driving/$originLng,$originLat;$destLng,$destLat?overview=full&geometries=geojson"
            val conn = (URL(osrmUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "Mozilla/5.0 (TWRoadGuardAegis)")
            }
            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val routes = json.optJSONArray("routes")
                if (routes != null && routes.length() > 0) {
                    val route0 = routes.getJSONObject(0)
                    distanceKm = route0.optDouble("distance", distanceKm * 1000.0) / 1000.0
                    durationMin = (route0.optDouble("duration", durationMin * 60.0) / 60.0).toInt().coerceAtLeast(1)
                    val coords = route0.optJSONObject("geometry")?.optJSONArray("coordinates")
                    if (coords != null) {
                        val step = (coords.length() / 120).coerceAtLeast(1)
                        for (i in 0 until coords.length() step step) {
                            val pt = coords.getJSONArray(i)
                            routePoints.add(Pair(pt.getDouble(1), pt.getDouble(0)))
                        }
                        val lastPt = coords.getJSONArray(coords.length() - 1)
                        routePoints.add(Pair(lastPt.getDouble(1), lastPt.getDouble(0)))
                    }
                }
            }
        } catch (_: Exception) {
        }

        if (routePoints.size < 2) {
            // 備援插值路線點
            val steps = 25
            for (i in 0..steps) {
                val r = i.toDouble() / steps
                routePoints.add(
                    Pair(
                        originLat + (destLat - originLat) * r,
                        originLng + (destLng - originLng) * r
                    )
                )
            }
        }

        val closures = ArrayList<ScoredRoadEvent>()
        val constructions = ArrayList<ScoredRoadEvent>()
        val hazards = ArrayList<ScoredRoadEvent>()
        val routeCameras = ArrayList<ScoredCamera>()

        // 掃描路線走廊 350 公尺內的所有有效施工與封閉點
        for (ev in roadEvents) {
            if (ev.isCleared) continue
            val minD = minDistanceToPolylineMeters(ev.lat, ev.lng, routePoints)
            if (minD <= 380.0) {
                val distFromOrigin = GeoUtils.distanceMeters(originLat, originLng, ev.lat, ev.lng)
                val scored = ScoredRoadEvent(ev, distFromOrigin, 0.0)
                when (ev.severity) {
                    EventSeverity.CLOSURE -> closures.add(scored)
                    EventSeverity.CONSTRUCTION -> constructions.add(scored)
                    else -> hazards.add(scored)
                }
            }
        }

        // 掃描路線走廊 180 公尺內的測速照相
        for (cam in cameras) {
            val minD = minDistanceToPolylineMeters(cam.lat, cam.lng, routePoints)
            if (minD <= 180.0) {
                val distFromOrigin = GeoUtils.distanceMeters(originLat, originLng, cam.lat, cam.lng)
                routeCameras.add(ScoredCamera(cam, distFromOrigin, 0.0))
            }
        }

        closures.sortBy { it.distanceMeters }
        constructions.sortBy { it.distanceMeters }
        routeCameras.sortBy { it.distanceMeters }

        // 若有封閉或嚴重施工點，計算一個垂直偏移約 1.2km 的繞道途經點供 Google Maps 避開該路段
        val criticalEvent = closures.firstOrNull()?.event ?: constructions.firstOrNull()?.event
        val bypassWaypoint = if (criticalEvent != null) {
            val bearingRad = Math.toRadians(GeoUtils.bearingDeg(originLat, originLng, destLat, destLng) + 90.0)
            val offsetDeg = 0.011 // 約 1.2 公里橫向繞道
            Pair(
                criticalEvent.lat + cos(bearingRad) * offsetDeg,
                criticalEvent.lng + sin(bearingRad) * offsetDeg
            )
        } else null

        return RouteScanResult(
            destinationName = destName,
            originLat = originLat,
            originLng = originLng,
            destLat = destLat,
            destLng = destLng,
            distanceKm = distanceKm,
            durationMin = durationMin,
            routePoints = routePoints,
            closuresOnRoute = closures,
            constructionsOnRoute = constructions,
            hazardsOnRoute = hazards,
            camerasOnRoute = routeCameras,
            bypassWaypoint = bypassWaypoint
        )
    }

    private fun minDistanceToPolylineMeters(lat: Double, lng: Double, pts: List<Pair<Double, Double>>): Double {
        var minD = Double.MAX_VALUE
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            // 快速經緯度框過濾提升效能
            val minLat = minOf(a.first, b.first) - 0.01
            val maxLat = maxOf(a.first, b.first) + 0.01
            val minLng = minOf(a.second, b.second) - 0.01
            val maxLng = maxOf(a.second, b.second) + 0.01
            if (lat !in minLat..maxLat || lng !in minLng..maxLng) continue

            val d = GeoUtils.pointToSegmentMeters(lat, lng, a.first, a.second, b.first, b.second)
            if (d < minD) minD = d
        }
        return minD
    }

    private fun resolveTaiwanDestination(query: String): Triple<String, Double, Double>? {
        val q = query.trim()
        if (q.isEmpty()) return null

        // 內建台灣熱門地標與常施工幹道快速解析庫（秒回且免網路延遲）
        val presets = mapOf(
            "台北車站" to Triple("台北車站", 25.0478, 121.5170),
            "台北101" to Triple("台北 101 信義商圈", 25.0339, 121.5645),
            "桃園機場" to Triple("桃園國際機場 (國2端)", 25.0797, 121.2342),
            "新竹科學園區" to Triple("新竹科學園區 (國1竹科)", 24.7816, 121.0061),
            "台中歌劇院" to Triple("台中國家歌劇院 (市政路/台灣大道)", 24.1627, 120.6403),
            "台中高鐵" to Triple("高鐵台中站 (台74烏日段)", 24.1121, 120.6156),
            "逢甲夜市" to Triple("台中逢甲商圈 (環中路/河南路)", 24.1788, 120.6458),
            "鹿港老街" to Triple("彰化鹿港 (台61西濱快速道路段)", 24.0566, 120.4313),
            "日月潭" to Triple("南投日月潭 (國6/台21線)", 23.8661, 120.9158),
            "阿里山" to Triple("嘉義阿里山 (台18線施工熱區)", 23.5103, 120.8025),
            "台南火車站" to Triple("台南火車站 (東區/永康)", 22.9971, 120.2126),
            "高雄左營" to Triple("高鐵左營站 (國10末端)", 22.6874, 120.3075),
            "高雄夢時代" to Triple("高雄前鎮夢時代 (中山四路)", 22.5951, 120.3069),
            "宜蘭礁溪" to Triple("宜蘭礁溪 (國5雪山隧道)", 24.8235, 121.7711),
            "花蓮市區" to Triple("花蓮市區 (台9線蘇花改區間測速段)", 23.9872, 121.6015)
        )
        for ((k, v) in presets) {
            if (q.contains(k) || k.contains(q)) return v
        }

        // 呼叫 OSM Nominatim 搜尋台灣任一地址或路名
        return try {
            val encoded = URLEncoder.encode("台灣 $q", "UTF-8")
            val url = "https://nominatim.openstreetmap.org/search?format=json&limit=1&countrycodes=tw&q=$encoded"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "Mozilla/5.0 (TWRoadGuardAegis/1.0)")
                setRequestProperty("Accept-Language", "zh-TW,zh;q=0.9")
            }
            if (conn.responseCode == 200) {
                val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
                if (arr.length() > 0) {
                    val obj = arr.getJSONObject(0)
                    val lat = obj.getString("lat").toDouble()
                    val lon = obj.getString("lon").toDouble()
                    val displayName = obj.optString("display_name", q).split(",").firstOrNull() ?: q
                    Triple(displayName, lat, lon)
                } else null
            } else null
        } catch (_: Exception) {
            null
        }
    }
}
