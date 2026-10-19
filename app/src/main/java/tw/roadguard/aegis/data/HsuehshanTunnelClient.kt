package tw.roadguard.aegis.data

import android.util.Log
import org.json.JSONObject
import tw.roadguard.aegis.model.HsuehshanAdvice
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 國道5號 雪山隧道即時車道建議客戶端
 * 資料來源：交通部高速公路局「交通資料庫」即時彙整 (https://hsuehshan-tunnel.pages.dev/api/tunnel)
 */
object HsuehshanTunnelClient {

    private const val TAG = "HsuehshanClient"
    private const val API_URL = "https://hsuehshan-tunnel.pages.dev/api/tunnel"
    private const val CACHE_DURATION_MS = 25_000L // 快取 25 秒

    @Volatile
    private var cachedReport: JSONObject? = null
    @Volatile
    private var lastFetchTimestamp: Long = 0L
    @Volatile
    private var isFetching: Boolean = false

    private val executor = Executors.newSingleThreadExecutor()

    /**
     * 非同步在背景預熱或重新拉取最新雪隧數據
     */
    fun refreshAsync(force: Boolean = false, onComplete: ((Boolean) -> Unit)? = null) {
        val now = System.currentTimeMillis()
        if (!force && cachedReport != null && (now - lastFetchTimestamp < CACHE_DURATION_MS)) {
            onComplete?.invoke(true)
            return
        }
        if (isFetching) {
            onComplete?.invoke(false)
            return
        }
        isFetching = true

        executor.execute {
            val ok = fetchSync()
            isFetching = false
            onComplete?.invoke(ok)
        }
    }

    private fun fetchSync(): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(API_URL)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
                useCaches = false
                setRequestProperty("User-Agent", "RoadGuard-Aegis/2.0")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val json = JSONObject(text)
                cachedReport = json
                lastFetchTimestamp = System.currentTimeMillis()
                Log.d(TAG, "成功取得雪隧即時車道資料: ${json.optString("updateTime")}")
                true
            } else {
                Log.w(TAG, "雪隧 API 回應非 200: ${conn.responseCode}")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "取得雪隧資料失敗: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 取得指定行向 (S=南下往宜蘭, N=北上往台北) 的雪隧即時車道建議
     */
    fun getAdvice(direction: String, distanceMeters: Double): HsuehshanAdvice {
        val dirKey = if (direction.equals("N", ignoreCase = true)) "N" else "S"
        val dirLabel = if (dirKey == "N") "北上 (往台北)" else "南下 (往宜蘭)"
        val towards = if (dirKey == "N") "往台北" else "往宜蘭"

        // 若資料過期，背景非同步更新
        if (System.currentTimeMillis() - lastFetchTimestamp > CACHE_DURATION_MS) {
            refreshAsync()
        }

        val root = cachedReport
        if (root == null) {
            // 尚未載入完成時的友善預設降級資料
            return HsuehshanAdvice(
                direction = dirKey,
                directionLabel = dirLabel,
                towards = towards,
                distanceToEntranceMeters = distanceMeters,
                recommendedLane = "outer",
                recommendedLaneName = "外側車道",
                recommendedLaneSide = "右線",
                reason = "雪隧即時資料連線中，請依現場指示行駛",
                diffKmh = 0,
                innerSpeedKmh = null,
                outerSpeedKmh = null,
                innerTravelMin = null,
                outerTravelMin = null,
                tunnelTravelMin = 9.0,
                alertEvent = null,
                isApproachingEntrance = distanceMeters in 300.0..2500.0,
                dataTime = "--:--"
            )
        }

        try {
            val dirObj = root.optJSONObject(dirKey) ?: return fallbackAdvice(dirKey, dirLabel, towards, distanceMeters)
            val recObj = dirObj.optJSONObject("recommend")
            val innerObj = dirObj.optJSONObject("inner")
            val outerObj = dirObj.optJSONObject("outer")

            val recLane = recObj?.optString("lane", "outer") ?: "outer"
            val recName = if (recLane == "inner") "內側車道" else "外側車道"
            val recSide = if (recLane == "inner") "左線" else "右線"
            var reason = recObj?.optString("reason", "行車較順暢") ?: "行車較順暢"

            val diffKmh = recObj?.optInt("diffKmh", 0) ?: 0
            if (diffKmh >= 3) {
                reason = "時速快 $diffKmh 公里"
            }

            val innerSpeed = if (innerObj != null && !innerObj.isNull("avgSpeed")) innerObj.optInt("avgSpeed") else null
            val outerSpeed = if (outerObj != null && !outerObj.isNull("avgSpeed")) outerObj.optInt("avgSpeed") else null

            val innerMin = if (innerObj != null && !innerObj.isNull("travelMin")) innerObj.optDouble("travelMin") else null
            val outerMin = if (outerObj != null && !outerObj.isNull("travelMin")) outerObj.optDouble("travelMin") else null

            val chosenMin = if (recLane == "inner") innerMin else outerMin
            val travelMin = chosenMin ?: 8.5

            // 檢查隧道內是否有嚴重事故路況
            var alertEvent: String? = null
            val evList = root.optJSONArray("events")
            if (evList != null) {
                for (i in 0 until evList.length()) {
                    val ev = evList.optJSONObject(i) ?: continue
                    val evDir = ev.optString("direction", "")
                    val inTunnel = ev.optBoolean("inTunnel", false)
                    val isConstruction = ev.optBoolean("isConstruction", false)
                    if (inTunnel && (evDir == dirKey || evDir == "B") && !isConstruction) {
                        alertEvent = ev.optString("description", "")
                        break
                    }
                }
            }

            val dataTime = dirObj.optString("dataTime", "--:--").let {
                if (it.length >= 16) it.substring(11, 16) else it
            }

            return HsuehshanAdvice(
                direction = dirKey,
                directionLabel = dirLabel,
                towards = towards,
                distanceToEntranceMeters = distanceMeters,
                recommendedLane = recLane,
                recommendedLaneName = recName,
                recommendedLaneSide = recSide,
                reason = reason,
                diffKmh = diffKmh,
                innerSpeedKmh = innerSpeed,
                outerSpeedKmh = outerSpeed,
                innerTravelMin = innerMin,
                outerTravelMin = outerMin,
                tunnelTravelMin = travelMin,
                alertEvent = alertEvent,
                isApproachingEntrance = distanceMeters in 300.0..2500.0,
                dataTime = dataTime
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return fallbackAdvice(dirKey, dirLabel, towards, distanceMeters)
        }
    }

    private fun fallbackAdvice(dirKey: String, dirLabel: String, towards: String, distanceMeters: Double): HsuehshanAdvice {
        return HsuehshanAdvice(
            direction = dirKey,
            directionLabel = dirLabel,
            towards = towards,
            distanceToEntranceMeters = distanceMeters,
            recommendedLane = "outer",
            recommendedLaneName = "外側車道",
            recommendedLaneSide = "右線",
            reason = "雙白線禁止變換車道，請依現場車流選定車道",
            diffKmh = 0,
            innerSpeedKmh = null,
            outerSpeedKmh = null,
            innerTravelMin = null,
            outerTravelMin = null,
            tunnelTravelMin = 8.5,
            alertEvent = null,
            isApproachingEntrance = distanceMeters in 300.0..2500.0,
            dataTime = "--:--"
        )
    }
}
