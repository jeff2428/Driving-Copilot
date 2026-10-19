package tw.roadguard.aegis.data

import android.content.Context
import android.location.Address
import android.location.Geocoder
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
        private const val KEY_GOOGLE_PLACES_KEY = "google_places_api_key"
        private const val KEY_RECENT_SEARCHES = "recent_search_history"
        const val DEFAULT_REPO = "jeff2428/Driving-Copilot"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var googlePlacesApiKey: String
        get() = prefs.getString(KEY_GOOGLE_PLACES_KEY, "") ?: ""
        set(v) = prefs.edit().putString(KEY_GOOGLE_PLACES_KEY, v.trim()).apply()

    fun getRecentDestinations(): List<DestinationCandidate> {
        val jsonStr = prefs.getString(KEY_RECENT_SEARCHES, null) ?: return emptyList()
        val list = ArrayList<DestinationCandidate>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    DestinationCandidate(
                        name = o.getString("name"),
                        address = o.getString("address"),
                        lat = o.getDouble("lat"),
                        lng = o.getDouble("lng"),
                        category = o.optString("category", "歷史記錄"),
                        distanceMeters = o.optDouble("distanceMeters", 0.0)
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    fun saveRecentDestination(cand: DestinationCandidate) {
        val current = getRecentDestinations().toMutableList()
        current.removeAll { it.name == cand.name || (kotlin.math.abs(it.lat - cand.lat) < 0.0005 && kotlin.math.abs(it.lng - cand.lng) < 0.0005) }
        current.add(0, cand)
        val limited = current.take(12)
        try {
            val arr = JSONArray()
            for (c in limited) {
                arr.put(JSONObject().apply {
                    put("name", c.name)
                    put("address", c.address)
                    put("lat", c.lat)
                    put("lng", c.lng)
                    put("category", c.category)
                    put("distanceMeters", c.distanceMeters)
                })
            }
            prefs.edit().putString(KEY_RECENT_SEARCHES, arr.toString()).apply()
        } catch (_: Exception) {}
    }

    fun clearRecentDestinations() {
        prefs.edit().remove(KEY_RECENT_SEARCHES).apply()
    }

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
        return scanRouteCorridorDirect(destName, destLat, destLng, originLat, originLng)
    }

    fun scanRouteCorridorDirect(
        destName: String,
        destLat: Double,
        destLng: Double,
        originLat: Double,
        originLng: Double
    ): RouteScanResult {
        val routePoints = ArrayList<Pair<Double, Double>>()
        var distanceKm = GeoUtils.distanceMeters(originLat, originLng, destLat, destLng) / 1000.0
        var durationMin = ((distanceKm / 60.0) * 60).toInt().coerceAtLeast(3)

        val navSteps = ArrayList<NavStep>()

        // 呼叫 OSRM 取得真實道路幾何座標與詳細轉彎步進指示 (steps=true)
        try {
            val osrmUrl =
                "https://router.project-osrm.org/route/v1/driving/$originLng,$originLat;$destLng,$destLat?overview=full&geometries=geojson&steps=true"
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
                        val step = (coords.length() / 240).coerceAtLeast(1)
                        for (i in 0 until coords.length() step step) {
                            val pt = coords.getJSONArray(i)
                            routePoints.add(Pair(pt.getDouble(1), pt.getDouble(0)))
                        }
                        val lastPt = coords.getJSONArray(coords.length() - 1)
                        routePoints.add(Pair(lastPt.getDouble(1), lastPt.getDouble(0)))
                    }

                    // 解析每一個轉彎動作 (steps)
                    val legs = route0.optJSONArray("legs")
                    if (legs != null && legs.length() > 0) {
                        val stepsArr = legs.getJSONObject(0).optJSONArray("steps")
                        if (stepsArr != null) {
                            for (sIdx in 0 until stepsArr.length()) {
                                val sObj = stepsArr.getJSONObject(sIdx)
                                val sName = sObj.optString("name", "").trim()
                                val sDist = sObj.optDouble("distance", 0.0)
                                val sDuration = sObj.optDouble("duration", 0.0)
                                val manObj = sObj.optJSONObject("maneuver")
                                val manType = manObj?.optString("type", "turn") ?: "turn"
                                val manMod = manObj?.optString("modifier", "straight") ?: "straight"
                                val locArr = manObj?.optJSONArray("location")
                                val manLng = locArr?.optDouble(0) ?: 0.0
                                val manLat = locArr?.optDouble(1) ?: 0.0

                                val (inst, icon) = parseManeuverToChinese(manType, manMod, sName, destName)

                                val stepPts = ArrayList<Pair<Double, Double>>()
                                val geomCoords = sObj.optJSONObject("geometry")?.optJSONArray("coordinates")
                                if (geomCoords != null) {
                                    for (g in 0 until geomCoords.length()) {
                                        val p = geomCoords.getJSONArray(g)
                                        stepPts.add(Pair(p.getDouble(1), p.getDouble(0)))
                                    }
                                }

                                navSteps.add(
                                    NavStep(
                                        stepIndex = sIdx,
                                        roadName = sName.ifBlank { if (sIdx == 0) "出發路段" else "接續道路" },
                                        instruction = inst,
                                        maneuverType = manType,
                                        maneuverModifier = manMod,
                                        maneuverIcon = icon,
                                        distanceMeters = sDist,
                                        durationSec = sDuration,
                                        maneuverLat = manLat,
                                        maneuverLng = manLng,
                                        stepPoints = stepPts
                                    )
                                )
                            }
                        }
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

        if (navSteps.isEmpty()) {
            navSteps.add(
                NavStep(
                    stepIndex = 0,
                    roadName = "出發路段",
                    instruction = "出發，往 $destName 方向行駛",
                    maneuverType = "depart",
                    maneuverModifier = "straight",
                    maneuverIcon = "⬆️",
                    distanceMeters = distanceKm * 1000.0,
                    durationSec = durationMin * 60.0,
                    maneuverLat = originLat,
                    maneuverLng = originLng,
                    stepPoints = routePoints
                )
            )
            navSteps.add(
                NavStep(
                    stepIndex = 1,
                    roadName = destName,
                    instruction = "抵達目的地：$destName",
                    maneuverType = "arrive",
                    maneuverModifier = "straight",
                    maneuverIcon = "🏁",
                    distanceMeters = 0.0,
                    durationSec = 0.0,
                    maneuverLat = destLat,
                    maneuverLng = destLng,
                    stepPoints = emptyList()
                )
            )
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

        // 若有封閉或嚴重施工點，計算一個垂直偏移約 1.2km 的繞道途經點供避開該路段
        val criticalEvent = closures.firstOrNull()?.event ?: constructions.firstOrNull()?.event
        val bypassWaypoint = if (criticalEvent != null) {
            val bearingRad = Math.toRadians(GeoUtils.bearingDeg(originLat, originLng, destLat, destLng) + 90.0)
            val offsetDeg = 0.011 // 約 1.2 公里橫向繞道
            Pair(
                criticalEvent.lat + cos(bearingRad) * offsetDeg,
                criticalEvent.lng + sin(bearingRad) * offsetDeg
            )
        } else null

        val navRoute = NavRoute(
            destinationName = destName,
            originLat = originLat,
            originLng = originLng,
            destLat = destLat,
            destLng = destLng,
            totalDistanceMeters = distanceKm * 1000.0,
            totalDurationSec = durationMin * 60.0,
            steps = navSteps,
            routePoints = routePoints,
            closuresOnRoute = closures,
            constructionsOnRoute = constructions,
            camerasOnRoute = routeCameras,
            bypassWaypoint = bypassWaypoint
        )

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
            bypassWaypoint = bypassWaypoint,
            navRoute = navRoute
        )
    }

    private fun parseManeuverToChinese(
        type: String,
        modifier: String,
        roadName: String,
        destName: String
    ): Pair<String, String> {
        val r = roadName.ifBlank { "前方道路" }
        return when (type) {
            "depart" -> Pair("出發，沿著 $r 前行", "⬆️")
            "arrive" -> Pair("抵達目的地：$destName", "🏁")
            "roundabout", "rotary" -> Pair("進入圓環，往 $r", "🔄")
            "merge" -> Pair("匯入 $r", "↖️")
            "on ramp" -> Pair("上匝道，進入 $r", "↗️")
            "off ramp" -> Pair("下匝道，進入 $r", "↘️")
            "fork" -> when {
                modifier.contains("left") -> Pair("走左側岔路，進入 $r", "↖️")
                modifier.contains("right") -> Pair("走右側岔路，進入 $r", "↗️")
                else -> Pair("走岔路，進入 $r", "⬆️")
            }
            "turn" -> when (modifier) {
                "sharp left" -> Pair("向左急轉，進入 $r", "⬅️")
                "left" -> Pair("向左轉，進入 $r", "⬅️")
                "slight left" -> Pair("靠左行駛，進入 $r", "↖️")
                "sharp right" -> Pair("向右急轉，進入 $r", "➡️")
                "right" -> Pair("向右轉，進入 $r", "➡️")
                "slight right" -> Pair("靠右行駛，進入 $r", "↗️")
                "uturn" -> Pair("請迴轉，進入 $r", "↩️")
                else -> Pair("轉向進入 $r", "⬆️")
            }
            "new name" -> Pair("直行，接續 $r", "⬆️")
            "end of road" -> when {
                modifier.contains("left") -> Pair("路底左轉，進入 $r", "⬅️")
                modifier.contains("right") -> Pair("路底右轉，進入 $r", "➡️")
                else -> Pair("路底轉向，進入 $r", "⬆️")
            }
            else -> when {
                modifier.contains("left") -> Pair("向左轉，進入 $r", "⬅️")
                modifier.contains("right") -> Pair("向右轉，進入 $r", "➡️")
                else -> Pair("沿 $r 繼續行駛", "⬆️")
            }
        }
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

    data class PresetPlace(val name: String, val address: String, val lat: Double, val lng: Double, val category: String)

    val taiwanPresets = listOf(
        // ---- 交通樞紐 ----
        PresetPlace("台北車站", "台北市中正區北平西路3號", 25.0478, 121.5170, "交通樞紐"),
        PresetPlace("板橋車站", "新北市板橋區縣民大道二段7號", 25.0136, 121.4632, "交通樞紐"),
        PresetPlace("桃園機場第一航廈", "桃園市大園區航站南路15號", 25.0818, 121.2393, "交通樞紐"),
        PresetPlace("桃園機場第二航廈", "桃園市大園區航站南路9號", 25.0776, 121.2327, "交通樞紐"),
        PresetPlace("高鐵新竹站", "新竹縣竹北市高鐵七路6號", 24.8083, 121.0404, "交通樞紐"),
        PresetPlace("高鐵台中站", "台中市烏日區高鐵東一路26號", 24.1121, 120.6156, "交通樞紐"),
        PresetPlace("台中火車站", "台中市中區台灣大道一段1號", 24.1373, 120.6869, "交通樞紐"),
        PresetPlace("高鐵嘉義站", "嘉義縣太保市高鐵西路168號", 23.4590, 120.3235, "交通樞紐"),
        PresetPlace("台南火車站", "台南市東區前鋒路210號", 22.9971, 120.2126, "交通樞紐"),
        PresetPlace("高鐵台南站", "台南市歸仁區歸仁大道100號", 22.9248, 120.2858, "交通樞紐"),
        PresetPlace("高鐵左營站", "高雄市左營區高鐵路105號", 22.6874, 120.3075, "交通樞紐"),
        PresetPlace("高雄火車站", "高雄市三民區建國二路318號", 22.6397, 120.3021, "交通樞紐"),

        // ---- 好市多 Costco 全台門市 ----
        PresetPlace("好市多 內湖店", "台北市內湖區舊宗路一段268號", 25.0628, 121.5746, "量販賣場"),
        PresetPlace("好市多 北投店", "台北市北投區立德路117號", 25.1278, 121.4727, "量販賣場"),
        PresetPlace("好市多 中和店", "新北市中和區中山路二段347號", 25.0039, 121.4988, "量販賣場"),
        PresetPlace("好市多 汐止店", "新北市汐止區大同路一段158號", 25.0597, 121.6393, "量販賣場"),
        PresetPlace("好市多 新莊店", "新北市新莊區建國一路138號", 25.0298, 121.4278, "量販賣場"),
        PresetPlace("好市多 南崁店", "桃園市蘆竹區南崁路一段369號", 25.0537, 121.2868, "量販賣場"),
        PresetPlace("好市多 中壢店", "桃園市中壢區民族路六段508號", 24.9664, 121.1398, "量販賣場"),
        PresetPlace("好市多 新竹店", "新竹市東區慈雲路188號", 24.7963, 121.0135, "量販賣場"),
        PresetPlace("好市多 台中南屯店", "台中市南屯區文心南三路289號", 24.1350, 120.6475, "量販賣場"),
        PresetPlace("好市多 北台中店", "台中市北屯區敦富路366號", 24.1953, 120.7061, "量販賣場"),
        PresetPlace("好市多 嘉義店", "嘉義市東區忠孝路668號", 23.5043, 120.4533, "量販賣場"),
        PresetPlace("好市多 台南店", "台南市北區和緯路四段8號", 23.0101, 120.1915, "量販賣場"),
        PresetPlace("好市多 高雄店", "高雄市前鎮區中華五路656號", 22.6062, 120.3048, "量販賣場"),
        PresetPlace("好市多 大順店", "高雄市鼓山區大順一路111號", 22.6566, 120.3093, "量販賣場"),

        // ---- 熱門百貨商場與 Outlet ----
        PresetPlace("台北101", "台北市信義區信義路五段7號 (信義商圈)", 25.0339, 121.5645, "百貨商場"),
        PresetPlace("微風信義", "台北市信義區忠孝東路五段68號", 25.0405, 121.5666, "百貨商場"),
        PresetPlace("微風廣場", "台北市松山區復興南路一段39號", 25.0458, 121.5447, "百貨商場"),
        PresetPlace("遠東SOGO 台北忠孝館", "台北市大安區忠孝東路四段45號", 25.0418, 121.5442, "百貨商場"),
        PresetPlace("板橋大遠百", "新北市板橋區新站路28號", 25.0134, 121.4658, "百貨商場"),
        PresetPlace("MITSUI OUTLET PARK 林口", "新北市林口區文化三路一段356號", 25.0709, 121.3653, "休閒商場"),
        PresetPlace("遠東巨城購物中心", "新竹市東區中央路229號", 24.8097, 120.9750, "百貨商場"),
        PresetPlace("新竹科學園區", "新竹市東區新安路 (國道1號竹科交流道)", 24.7816, 121.0061, "科技園區"),
        PresetPlace("台中國家歌劇院", "台中市西屯區惠來路二段101號 (七期市政特區)", 24.1627, 120.6403, "文化地標"),
        PresetPlace("新光三越 台中中港店", "台中市西屯區台灣大道三段301號", 24.1648, 120.6433, "百貨商場"),
        PresetPlace("台中大遠百", "台中市西屯區台灣大道三段251號", 24.1641, 120.6421, "百貨商場"),
        PresetPlace("中友百貨", "台中市北區三民路三段161號 (一中商圈)", 24.1524, 120.6848, "百貨商場"),
        PresetPlace("Mitsui Shopping Park LaLaport 台中", "台中市東區進德路600號", 24.1357, 120.6922, "休閒商場"),
        PresetPlace("MITSUI OUTLET PARK 台中港", "台中市梧棲區臺灣大道十段168號", 24.2558, 120.5222, "休閒商場"),
        PresetPlace("逢甲夜市", "台中市西屯區文華路 (逢甲大學商圈)", 24.1788, 120.6458, "熱門商圈"),
        PresetPlace("新光三越 台南新天地", "台南市中西區西門路一段658號", 22.9868, 120.1983, "百貨商場"),
        PresetPlace("南紡購物中心", "台南市東區中華東路一段366號", 22.9912, 120.2325, "百貨商場"),
        PresetPlace("漢神巨蛋購物廣場", "高雄市左營區博愛二路777號", 22.6698, 120.3023, "百貨商場"),
        PresetPlace("義享天地", "高雄市鼓山區大順一路115號", 22.6565, 120.3087, "百貨商場"),
        PresetPlace("高雄夢時代", "高雄市前鎮區中華五路789號", 22.5951, 120.3069, "熱門商圈"),

        // ---- 知名醫學中心 ----
        PresetPlace("台大醫院", "台北市中正區中山南路7號", 25.0416, 121.5178, "醫療院所"),
        PresetPlace("台北榮民總醫院", "台北市北投區石牌路二段201號", 25.1206, 121.5204, "醫療院所"),
        PresetPlace("林口長庚紀念醫院", "桃園市龜山區復興街5號", 25.0601, 121.3692, "醫療院所"),
        PresetPlace("台中榮民總醫院", "台中市西屯區台灣大道四段1650號", 24.1849, 120.6022, "醫療院所"),
        PresetPlace("中國醫藥大學附設醫院", "台中市北區育德路2號", 24.1565, 120.6826, "醫療院所"),
        PresetPlace("成大醫院", "台南市北區勝利路138號", 23.0016, 120.2209, "醫療院所"),
        PresetPlace("奇美醫院", "台南市永康區中華路901號", 23.0185, 120.2246, "醫療院所"),
        PresetPlace("高雄長庚紀念醫院", "高雄市鳥松區大埤路123號", 22.6521, 120.3582, "醫療院所"),
        PresetPlace("花蓮慈濟醫院", "花蓮縣花蓮市中央路三段707號", 23.9989, 121.5975, "醫療院所"),

        // ---- 知名觀光風景區 ----
        PresetPlace("鹿港老街", "彰化縣鹿港鎮瑤林街 (台61西濱段)", 24.0566, 120.4313, "歷史觀光"),
        PresetPlace("日月潭水社碼頭", "南投縣魚池鄉中山路163號", 23.8661, 120.9158, "熱門風景區"),
        PresetPlace("清境農場", "南投縣仁愛鄉仁和路170號", 24.0583, 121.1627, "熱門風景區"),
        PresetPlace("阿里山國家森林遊樂區", "嘉義縣阿里山鄉中正村59號", 23.5103, 120.8025, "熱門風景區"),
        PresetPlace("墾丁大街", "屏東縣恆春鎮墾丁路", 21.9450, 120.7972, "熱門風景區"),
        PresetPlace("宜蘭礁溪溫泉", "宜蘭縣礁溪鄉礁溪路五段", 24.8235, 121.7711, "溫泉觀光"),
        PresetPlace("花蓮太魯閣", "花蓮縣秀林鄉富世291號", 24.1593, 121.6225, "熱門風景區")
    )

    /**
     * 五層混合高精準搜尋架構（Multi-Tier Hybrid Search Engine）
     * 支援 Google Geocoder、Photon 空間加權、Nominatim 修復版、門牌智慧分解與自訂 API Key
     */
    fun searchDestinations(query: String, originLat: Double, originLng: Double): List<DestinationCandidate> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        val results = ArrayList<DestinationCandidate>()

        // 1. 座標直接輸入辨識 (例如: 24.1627, 120.6403)
        val parts = q.split(",", "，")
        if (parts.size == 2) {
            val lat = parts[0].trim().toDoubleOrNull()
            val lng = parts[1].trim().toDoubleOrNull()
            if (lat != null && lng != null && lat in 21.0..26.5 && lng in 118.0..123.5) {
                val dist = GeoUtils.distanceMeters(originLat, originLng, lat, lng)
                return listOf(
                    DestinationCandidate(
                        name = "自訂座標點",
                        address = String.format(Locale.TAIWAN, "緯度 %.4f, 經度 %.4f", lat, lng),
                        lat = lat,
                        lng = lng,
                        category = "座標",
                        distanceMeters = dist
                    )
                )
            }
        }

        // 2. 比對全台內建高頻地標庫 (Tier 1)
        for (p in taiwanPresets) {
            val hit = p.name.contains(q, ignoreCase = true) ||
                    p.address.contains(q, ignoreCase = true) ||
                    q.contains(p.name, ignoreCase = true)
            if (hit) {
                val dist = GeoUtils.distanceMeters(originLat, originLng, p.lat, p.lng)
                results.add(
                    DestinationCandidate(
                        name = p.name,
                        address = p.address,
                        lat = p.lat,
                        lng = p.lng,
                        category = p.category,
                        distanceMeters = dist
                    )
                )
            }
        }

        // 3. 若有設定 Google Places API Key，優先調用官方 API (Tier 1.5)
        val customKey = googlePlacesApiKey
        if (customKey.isNotBlank()) {
            results.addAll(queryGooglePlacesApi(q, customKey, originLat, originLng))
        }

        // 4. Android 原生 Google Geocoder 直連 (Tier 2，免 Key，台灣 Android 預設搭載)
        val googleCandidates = queryAndroidGoogleGeocoder(q, originLat, originLng)
        for (cand in googleCandidates) {
            if (results.none { isSameLocation(it, cand) }) {
                results.add(cand)
            }
        }

        // 5. Photon 空間加權搜尋 (Tier 3，依車主所在座標就近加權)
        val photonCandidates = queryPhotonGeocoder(q, originLat, originLng)
        for (cand in photonCandidates) {
            if (results.none { isSameLocation(it, cand) }) {
                results.add(cand)
            }
        }

        // 6. OSM Nominatim 台灣修復版 (Tier 4，去除干擾前綴，鎖定台灣範圍)
        val nominatimCandidates = queryNominatimGeocoder(q, originLat, originLng)
        for (cand in nominatimCandidates) {
            if (results.none { isSameLocation(it, cand) }) {
                results.add(cand)
            }
        }

        // 7. 台灣門牌號碼智慧分解器 (Tier 5，遇純門牌號查無結果時，分解主要路段降級定位)
        if (results.isEmpty() && isPotentialTaiwanAddress(q)) {
            val fallbackCandidates = smartDecomposeTaiwanAddress(q, originLat, originLng)
            results.addAll(fallbackCandidates)
        }

        // 8. 排序演算法：完全符合查詢字詞者最優先，其餘依直線距離由近到遠排序
        results.sortWith(
            compareBy<DestinationCandidate> {
                val matchScore = when {
                    it.name.equals(q, ignoreCase = true) -> 0
                    it.name.startsWith(q, ignoreCase = true) -> 1
                    it.name.contains(q, ignoreCase = true) -> 2
                    else -> 3
                }
                matchScore
            }.thenBy { it.distanceMeters }
        )

        return results.take(18)
    }

    private fun isSameLocation(a: DestinationCandidate, b: DestinationCandidate): Boolean {
        if (a.name == b.name && a.address == b.address) return true
        val dist = GeoUtils.distanceMeters(a.lat, a.lng, b.lat, b.lng)
        return dist < 65.0
    }

    /**
     * Tier 2: 呼叫 Android 內建 Google 地圖服務 Geocoder (免 API Key，直接由 GMS 後端驅動)
     */
    private fun queryAndroidGoogleGeocoder(q: String, originLat: Double, originLng: Double): List<DestinationCandidate> {
        val list = ArrayList<DestinationCandidate>()
        if (!Geocoder.isPresent()) return list
        try {
            val geocoder = Geocoder(context, Locale.TAIWAN)
            // 台灣地理邊界：南緯 21.5, 東經 119.0, 北緯 26.0, 東經 122.5
            val addresses: List<Address>? = geocoder.getFromLocationName(
                q,
                8,
                21.5,
                119.0,
                26.0,
                122.5
            )
            if (!addresses.isNullOrEmpty()) {
                for (addr in addresses) {
                    val lat = addr.latitude
                    val lng = addr.longitude
                    if (lat !in 21.0..26.5 || lng !in 118.0..123.5) continue

                    val line0 = (0..addr.maxAddressLineIndex).mapNotNull { addr.getAddressLine(it) }.joinToString(" ")
                    val fullAddress = cleanTaiwanAddressLine(line0, addr)
                    val feat = addr.featureName
                    val road = addr.thoroughfare
                    val house = addr.subThoroughfare

                    val name = when {
                        !feat.isNullOrBlank() && !feat.contains("號") && feat != road -> feat
                        !road.isNullOrBlank() && !house.isNullOrBlank() -> "$road $house"
                        !road.isNullOrBlank() -> road
                        else -> q
                    }

                    val cat = when {
                        name.contains("好市多") || name.contains("Costco") -> "量販賣場"
                        name.contains("新光三越") || name.contains("遠百") || name.contains("SOGO") || name.contains("微風") -> "百貨商場"
                        name.contains("醫院") || name.contains("診所") -> "醫療院所"
                        name.contains("站") || name.contains("機場") -> "交通樞紐"
                        name.contains("店") || name.contains("門市") || name.contains("館") -> "商家門市"
                        name.contains("路") || name.contains("街") || name.contains("號") -> "門牌地址"
                        else -> "Google定位"
                    }

                    val dist = GeoUtils.distanceMeters(originLat, originLng, lat, lng)
                    list.add(
                        DestinationCandidate(
                            name = name,
                            address = fullAddress,
                            lat = lat,
                            lng = lng,
                            category = cat,
                            distanceMeters = dist
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return list
    }

    private fun cleanTaiwanAddressLine(raw: String, addr: Address): String {
        var s = raw.trim()
        if (s.startsWith("台灣") || s.startsWith("臺灣")) s = s.substring(2).trim()
        s = s.replace(Regex("^[0-9]{3,5}"), "").trim()
        if (s.startsWith("台灣") || s.startsWith("臺灣")) s = s.substring(2).trim()
        if (s.isBlank()) {
            val city = addr.adminArea ?: addr.subAdminArea ?: ""
            val town = addr.locality ?: addr.subLocality ?: ""
            val road = addr.thoroughfare ?: ""
            val house = addr.subThoroughfare ?: ""
            s = "$city$town$road$house".trim()
        }
        return s.ifBlank { raw }
    }

    /**
     * Tier 3: 呼叫 Komoot Photon 空間加權地理編碼引擎 (依車輛座標就近排序)
     */
    private fun queryPhotonGeocoder(q: String, originLat: Double, originLng: Double): List<DestinationCandidate> {
        val list = ArrayList<DestinationCandidate>()
        try {
            val encoded = URLEncoder.encode(q, "UTF-8")
            val url = "https://photon.komoot.io/api/?q=$encoded&lat=$originLat&lon=$originLng&limit=6"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "Mozilla/5.0 (TWRoadGuardAegis/2.0)")
            }
            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val features = json.optJSONArray("features")
                if (features != null) {
                    for (i in 0 until features.length()) {
                        val f = features.getJSONObject(i)
                        val geom = f.optJSONObject("geometry")
                        val coords = geom?.optJSONArray("coordinates") ?: continue
                        val lng = coords.getDouble(0)
                        val lat = coords.getDouble(1)
                        if (lat !in 21.0..26.5 || lng !in 118.0..123.5) continue

                        val props = f.optJSONObject("properties") ?: JSONObject()
                        val countryCode = props.optString("countrycode", "")
                        if (countryCode.isNotBlank() && countryCode.uppercase(Locale.ROOT) != "TW") continue

                        val name = props.optString("name").ifBlank { q }
                        val city = props.optString("city").ifBlank { props.optString("state", "") }
                        val district = props.optString("district").ifBlank { props.optString("locality", "") }
                        val street = props.optString("street", "")
                        val house = props.optString("housenumber", "")
                        val housePart = if (house.isNotBlank()) "${house}號" else ""

                        val addrParts = listOf(city, district, street, housePart).filter { it.isNotBlank() }
                        val fullAddress = if (addrParts.isNotEmpty()) addrParts.joinToString("") else "$city $district $street".trim()

                        val dist = GeoUtils.distanceMeters(originLat, originLng, lat, lng)
                        val osmKey = props.optString("osm_key", "")
                        val cat = when {
                            osmKey == "shop" || osmKey == "amenity" -> "商家門市"
                            osmKey == "tourism" || osmKey == "leisure" -> "休閒景點"
                            name.contains("醫院") || name.contains("診所") -> "醫療院所"
                            street.isNotBlank() -> "街道門牌"
                            else -> "空間定位"
                        }
                        list.add(
                            DestinationCandidate(
                                name = name,
                                address = fullAddress.ifBlank { "$name (台灣)" },
                                lat = lat,
                                lng = lng,
                                category = cat,
                                distanceMeters = dist
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}
        return list
    }

    /**
     * Tier 4: OSM Nominatim 台灣修復版 (無字串前綴干擾，帶入台灣邊界框約束)
     */
    private fun queryNominatimGeocoder(q: String, originLat: Double, originLng: Double): List<DestinationCandidate> {
        val list = ArrayList<DestinationCandidate>()
        try {
            val encoded = URLEncoder.encode(q, "UTF-8")
            val url = "https://nominatim.openstreetmap.org/search?format=json&limit=6&countrycodes=tw&viewbox=119.5,25.4,122.2,21.8&bounded=0&addressdetails=1&q=$encoded"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "Mozilla/5.0 (TWRoadGuardAegis/2.0)")
                setRequestProperty("Accept-Language", "zh-TW,zh;q=0.9")
            }
            if (conn.responseCode == 200) {
                val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val lat = obj.getString("lat").toDouble()
                    val lon = obj.getString("lon").toDouble()
                    if (lat !in 21.0..26.5 || lon !in 118.0..123.5) continue

                    val rawDisplay = obj.optString("display_name", q)
                    val addrObj = obj.optJSONObject("address")
                    val placeName = obj.optString("name").ifBlank {
                        rawDisplay.split(",").firstOrNull()?.trim() ?: q
                    }
                    val fullAddress = formatTaiwanAddress(addrObj, rawDisplay)
                    val dist = GeoUtils.distanceMeters(originLat, originLng, lat, lon)
                    val cat = obj.optString("type", "地點")

                    list.add(
                        DestinationCandidate(
                            name = placeName,
                            address = fullAddress,
                            lat = lat,
                            lng = lon,
                            category = cat,
                            distanceMeters = dist
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return list
    }

    /**
     * Tier 5: 台灣門牌號碼智慧分解器 (遇純門牌號查無結果時，分解出主要路段並降級定位)
     */
    private fun smartDecomposeTaiwanAddress(q: String, originLat: Double, originLng: Double): List<DestinationCandidate> {
        val list = ArrayList<DestinationCandidate>()
        val regex = Regex("^(.*?([縣市].*?[鄉鎮市區].*?[路街道段]))(.*?[0-9一二三四五六七八九十百]+號?.*)?$")
        val match = regex.find(q)
        if (match != null) {
            val roadCore = match.groupValues[1]
            val extraPart = match.groupValues.getOrNull(3)?.trim() ?: ""
            if (roadCore.isNotBlank() && roadCore != q) {
                val subResults = queryPhotonGeocoder(roadCore, originLat, originLng).ifEmpty {
                    queryNominatimGeocoder(roadCore, originLat, originLng)
                }
                for (cand in subResults) {
                    val suffix = if (extraPart.isNotBlank()) " (約${extraPart}附近)" else ""
                    list.add(
                        DestinationCandidate(
                            name = "${cand.name}$suffix",
                            address = q,
                            lat = cand.lat,
                            lng = cand.lng,
                            category = "門牌路段",
                            distanceMeters = cand.distanceMeters
                        )
                    )
                }
            }
        }
        return list
    }

    private fun isPotentialTaiwanAddress(q: String): Boolean {
        return (q.contains("市") || q.contains("縣")) &&
                (q.contains("路") || q.contains("街") || q.contains("大道") || q.contains("段"))
    }

    /**
     * 可選 Tier 1.5: 使用者個人 Google Places API Key
     */
    private fun queryGooglePlacesApi(q: String, key: String, originLat: Double, originLng: Double): List<DestinationCandidate> {
        val list = ArrayList<DestinationCandidate>()
        try {
            val encoded = URLEncoder.encode(q, "UTF-8")
            val url = "https://maps.googleapis.com/maps/api/place/textsearch/json?query=$encoded&location=$originLat,$originLng&radius=50000&language=zh-TW&key=$key"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
            }
            if (conn.responseCode == 200) {
                val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val results = root.optJSONArray("results")
                if (results != null) {
                    for (i in 0 until results.length()) {
                        val item = results.getJSONObject(i)
                        val name = item.optString("name", q)
                        val addr = item.optString("formatted_address", "")
                        val geom = item.optJSONObject("geometry")
                        val loc = geom?.optJSONObject("location") ?: continue
                        val lat = loc.getDouble("lat")
                        val lng = loc.getDouble("lng")
                        val dist = GeoUtils.distanceMeters(originLat, originLng, lat, lng)
                        list.add(
                            DestinationCandidate(
                                name = name,
                                address = addr.replace("台灣", "").replace("臺灣", "").trim(),
                                lat = lat,
                                lng = lng,
                                category = "Google地標",
                                distanceMeters = dist
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}
        return list
    }

    private fun formatTaiwanAddress(addrObj: JSONObject?, displayName: String): String {
        if (addrObj != null) {
            val city = addrObj.optString("city").ifBlank { addrObj.optString("county") }
            val town = addrObj.optString("town").ifBlank {
                addrObj.optString("suburb").ifBlank { addrObj.optString("district") }
            }
            val road = addrObj.optString("road")
            val house = addrObj.optString("house_number")
            val components = listOf(city, town, road, house).filter { it.isNotBlank() }
            if (components.isNotEmpty()) {
                return components.joinToString("")
            }
        }
        // 後備方案：整理 displayName（移除重複與國名）
        val tokens = displayName.split(",").map { it.trim() }
            .filter { it.isNotBlank() && it != "臺灣" && it != "台灣" && !it.all { c -> c.isDigit() } }
        return if (tokens.size > 1) {
            tokens.drop(1).reversed().joinToString("")
        } else {
            displayName
        }
    }

    private fun resolveTaiwanDestination(query: String): Triple<String, Double, Double>? {
        val candidates = searchDestinations(query, 24.1605, 120.6311)
        val first = candidates.firstOrNull() ?: return null
        return Triple(first.name, first.lat, first.lng)
    }
}
