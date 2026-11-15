package tw.roadguard.aegis.ads

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 台灣神盾路況行車助手 — App 本體在線自動檢查更新與一鍵下載升級引擎 (In-App OTA Updater)
 * ====================================================================================
 * 運作方式：
 * 1. 每次啟動 App（或於設定頁手動點擊檢查更新）時，向雲端 `cloud_data/app_version.json` 查詢最新版本。
 * 2. 若雲端 `versionCode > 目前 APK versionCode`，自動彈出更新提示視窗顯示更新日誌 (`releaseNotes`)。
 * 3. 車主點擊「🚀 立即一鍵下載更新」，即透過 Android 系統 `DownloadManager` 下載最新 APK 並自動喚起安裝器覆蓋升級！
 */
class AppUpdateManager(private val context: Context) {
    companion object {
        internal fun writeVerifiedApk(input: java.io.InputStream, file: java.io.File, expectedHash: String) {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            try {
                java.security.DigestInputStream(input, digest).use { source ->
                    file.outputStream().use { output -> source.copyTo(output) }
                }
                check(digest.digest().joinToString("") { "%02x".format(it) }.equals(expectedHash, true))
            } catch (e: Exception) {
                file.delete()
                throw e
            }
        }
    }

    val currentVersionCode: Int
        get() = try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (_: Exception) {
            1
        }

    val currentVersionName: String
        get() = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "2.0.0"
        } catch (_: Exception) {
            "2.0.0"
        }

    data class RemoteVersionInfo(
        val versionCode: Int,
        val versionName: String,
        val apkDownloadUrl: String,
        val releaseNotes: String,
        val apkSha256: String,
        val forceUpdate: Boolean
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 檢查雲端是否有新版本 APK
     */
    fun checkForAppUpdate(
        activity: Activity,
        manualCheck: Boolean = false,
        proLicenseManager: ProLicenseManager? = null
    ) {
        val slug = tw.roadguard.aegis.data.TaiwanOpenDataRepository.DEFAULT_REPO

        Thread {
            val baseCandidates = listOf(
                "https://raw.githubusercontent.com/$slug/main/cloud_data",
                "https://cdn.jsdelivr.net/gh/$slug@main/cloud_data"
            )

            // 1. 順便同步雲端 Pro 會員白名單與停權名單 (pro_members.json)
            if (proLicenseManager != null) {
                for (base in baseCandidates) {
                    try {
                        val proJson = fetchText("$base/pro_members.json")
                        proLicenseManager.applyCloudProListJson(proJson)
                        break
                    } catch (_: Exception) {
                    }
                }
            }

            // 2. 檢查 App 版本資訊 (app_version.json)
            var foundInfo: RemoteVersionInfo? = null
            for (base in baseCandidates) {
                try {
                    val verJson = fetchText("$base/app_version.json")
                    val obj = JSONObject(verJson)
                    val vCode = obj.optInt("versionCode", currentVersionCode)
                    val vName = obj.optString("versionName", currentVersionName)
                    val apkUrl = obj.optString(
                        "apkDownloadUrl",
                        "https://github.com/$slug/releases/latest/download/TW-RoadGuard-Aegis.apk"
                    )
                    val notes = obj.optString("releaseNotes", "1. 優化全台測速照相與施工預警精準度\n2. 提升地圖流暢度")
                    val force = obj.optBoolean("forceUpdate", false)
                    foundInfo = RemoteVersionInfo(vCode, vName, apkUrl, notes, obj.optString("apkSha256"), force)
                    break
                } catch (_: Exception) {
                }
            }

            mainHandler.post {
                if (activity.isFinishing) return@post
                val info = foundInfo
                if (info == null) {
                    if (manualCheck) {
                        Toast.makeText(activity, "目前已是最新版本 v$currentVersionName", Toast.LENGTH_SHORT).show()
                    }
                    return@post
                }

                if (info.versionCode > currentVersionCode) {
                    showUpdateDialog(activity, info)
                } else if (manualCheck) {
                    Toast.makeText(activity, "✅ 您目前已是最新版本 v$currentVersionName！", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun showUpdateDialog(activity: Activity, info: RemoteVersionInfo) {
        val builder = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("🚀 發現新版本 v${info.versionName}")
            .setMessage("目前版本：v$currentVersionName\n最新版本：v${info.versionName}\n\n【更新內容】\n${info.releaseNotes}")
            .setCancelable(!info.forceUpdate)
            .setPositiveButton("立即一鍵下載更新") { _, _ ->
                downloadAndInstallApk(activity, info)
            }
        if (!info.forceUpdate) {
            builder.setNegativeButton("稍後再說", null)
        }
        builder.show()
    }

    private fun downloadAndInstallApk(activity: Activity, info: RemoteVersionInfo) {
        if (!info.apkSha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            Toast.makeText(activity, "此版本缺少更新檔校驗資料，請稍後重試", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(activity, "正在下載並驗證更新檔…", Toast.LENGTH_SHORT).show()
        Thread {
            val directory = java.io.File(context.cacheDir, "updates")
            var apk: java.io.File? = null
            try {
                directory.mkdirs()
                val file = java.io.File.createTempFile("release-", ".apk", directory)
                apk = file
                val url = URL(info.apkDownloadUrl)
                require(url.protocol == "https")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 30000
                }
                try {
                    check(conn.responseCode == 200)
                    writeVerifiedApk(conn.inputStream, file, info.apkSha256)
                } finally {
                    conn.disconnect()
                }
                mainHandler.post {
                    if (activity.isFinishing || activity.isDestroyed) {
                        file.delete()
                        return@post
                    }
                    try {
                        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".updates", file)
                        activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    } catch (_: android.content.ActivityNotFoundException) {
                        file.delete()
                        Toast.makeText(activity, "無法開啟系統安裝程式", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (_: Exception) {
                apk?.delete()
                mainHandler.post { Toast.makeText(activity, "下載或校驗失敗，請重試", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun fetchText(urlStr: String): String {
        val sep = if (urlStr.contains("?")) "&" else "?"
        val cacheBustedUrl = "$urlStr${sep}_t=${System.currentTimeMillis()}"
        val conn = (URL(cacheBustedUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0 (TWRoadGuardAegisUpdater/2.0)")
        }
        if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
        return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
