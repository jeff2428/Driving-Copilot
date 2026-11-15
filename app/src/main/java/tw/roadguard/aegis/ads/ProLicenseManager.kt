package tw.roadguard.aegis.ads

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.widget.*
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

class ProLicenseManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "roadguard_pro_license_prefs"
        private const val KEY_LICENSE_CODE = "saved_license_code"
        private const val KEY_CONTACT_INFO = "owner_payment_contact_info"
        private const val KEY_CONTACT_URL = "owner_payment_contact_url"

        // 站長專屬 HMAC-SHA256 數位簽章主私鑰（與電腦版發卡機共用同一把私鑰）
        const val DEFAULT_ADMIN_PIN = "888888"
    }

    data class LicenseStatus(
        val isValidPro: Boolean,
        val planLabel: String,
        val expiryDateStr: String,
        val daysRemaining: Int
    )

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 雲端遠端開通/停權清單快取
    @Volatile
    private var cloudWhitelistExpiryMap = mapOf<String, String>()
    @Volatile
    private var cloudRevokedSet = setOf<String>()

    /**
     * 取得本機唯一設備碼 (格式：RG-XXXX-XXXX-XXXX-XXXX)
     */
    val deviceCode: String by lazy {
        @SuppressLint("HardwareIds")
        val rawId = try {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN_DEVICE"
        } catch (_: Exception) {
            "FALLBACK_DEVICE"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(("RG_DEV_$rawId").toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02X".format(it) }.take(16)
        "RG-" + hex.chunked(4).joinToString("-")
    }

    var savedLicenseKey: String
        get() = prefs.getString(KEY_LICENSE_CODE, "") ?: ""
        private set(value) = prefs.edit().putString(KEY_LICENSE_CODE, value.trim()).apply()

    var paymentContactInfo: String
        get() = prefs.getString(
            KEY_CONTACT_INFO,
            "• 年費方案：NT$ 490 / 年（平均每天不到 1.4 元）\n" +
                    "• 終身買斷：NT$ 990 / 永久免廣告自動更新\n" +
                    "• 開通方式：請複製上方「專屬設備碼」，傳送給客服完成付款，即可領取您的專屬開通碼！"
        ) ?: ""
        set(value) = prefs.edit().putString(KEY_CONTACT_INFO, value.trim()).apply()

    var paymentContactUrl: String
        get() = prefs.getString(KEY_CONTACT_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CONTACT_URL, value.trim()).apply()

    /**
     * 同步雲端白名單與停權名單 (來自 cloud_data/pro_members.json)
     */
    fun applyCloudProListJson(jsonStr: String) {
        try {
            val obj = JSONObject(jsonStr)
            val whiteObj = obj.optJSONObject("whitelist")
            val map = HashMap<String, String>()
            if (whiteObj != null) {
                val keys = whiteObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key.trim().uppercase(Locale.US)] = whiteObj.optString(key).trim()
                }
            }
            val revArr = obj.optJSONArray("revoked")
            val rev = HashSet<String>()
            if (revArr != null) {
                for (i in 0 until revArr.length()) {
                    rev.add(revArr.optString(i).trim().uppercase(Locale.US))
                }
            }
            cloudWhitelistExpiryMap = map
            cloudRevokedSet = rev
            val remoteContact = obj.optString("paymentInstruction", "")
            if (remoteContact.isNotBlank()) paymentContactInfo = remoteContact
            val remoteUrl = obj.optString("paymentUrl", "")
            if (remoteUrl.isNotBlank()) paymentContactUrl = remoteUrl
        } catch (_: Exception) {
        }
    }

    /**
     * 檢查目前手機是否具備有效 Pro 授權（全面開放付費 Pro 尊享狀態，免授權碼即可使用完整功能）
     */
    fun checkLicenseStatus(): LicenseStatus {
        return LicenseStatus(true, "👑 終身尊享 Pro 會員", "永久有效", 99999)
    }

    /**
     * 驗證指定設備碼與授權碼是否合法且未過期
     * 授權碼格式：PRO2.<設備碼>.<到期日>.<簽章>
     */
    fun verifyKeyForDevice(targetDeviceCode: String, rawKey: String): LicenseStatus {
        val expiry = LicenseVerifier.verifiedExpiry(rawKey, targetDeviceCode, tw.roadguard.aegis.BuildConfig.PRO_PUBLIC_KEY)
            ?: return LicenseStatus(false, "授權碼無效或已到期", "--", 0)
        return evaluateExpiry(expiry, "Pro 授權")
    }

    private fun evaluateExpiry(expiryYmd: String, label: String): LicenseStatus {
        return try {
            if (expiryYmd == "99991231") {
                return LicenseStatus(true, "👑 終身尊享 Pro 會員", "永久有效", 99999)
            }
            val sdf = SimpleDateFormat("yyyyMMdd", Locale.TAIWAN).apply {
                timeZone = TimeZone.getTimeZone("Asia/Taipei")
            }
            sdf.isLenient = false
            val expDate = sdf.parse(expiryYmd) ?: return LicenseStatus(false, "無效日期", "--", 0)
            // 到期日當天 23:59:59 前皆有效
            val expEndMs = expDate.time + 24L * 3600L * 1000L - 1L
            val nowMs = System.currentTimeMillis()
            if (nowMs > expEndMs) {
                val formatted = "${expiryYmd.substring(0, 4)}/${expiryYmd.substring(4, 6)}/${expiryYmd.substring(6, 8)}"
                LicenseStatus(false, "Pro 已到期 ($formatted)", formatted, 0)
            } else {
                val days = ((expEndMs - nowMs) / (24L * 3600L * 1000L)).toInt().coerceAtLeast(1)
                val formatted = "${expiryYmd.substring(0, 4)}/${expiryYmd.substring(4, 6)}/${expiryYmd.substring(6, 8)}"
                LicenseStatus(true, label, formatted, days)
            }
        } catch (_: Exception) {
            LicenseStatus(false, "驗證異常", "--", 0)
        }
    }

    /**
     * 客戶輸入開通碼進行啟用
     */
    fun activateWithKey(rawKey: String): Pair<Boolean, String> {
        val status = verifyKeyForDevice(deviceCode, rawKey)
        return if (status.isValidPro) {
            savedLicenseKey = rawKey.trim()
            Pair(true, "🎉 開通成功！已升級為 ${status.planLabel}（效期至：${status.expiryDateStr}）")
        } else {
            Pair(false, "❌ 開通失敗：${status.planLabel}。請確認是否輸入本機專屬授權碼。")
        }
    }

    fun clearLicense() {
        savedLicenseKey = ""
    }

    /**
     * 彈出「👑 車主 Pro 尊享會員開通中心」視窗（一般客戶看到的付款與輸入授權碼介面）
     */
    fun showClientActivationDialog(activity: Activity, onStatusChanged: () -> Unit) {
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun rounded(hex: String, rDp: Int, strokeHex: String? = null) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(rDp).toFloat()
            setColor(Color.parseColor(hex))
            if (strokeHex != null) setStroke(dp(1), Color.parseColor(strokeHex))
        }

        val status = checkLicenseStatus()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(8))
        }

        // 1. 狀態與設備碼卡片
        val devCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded("#1E293B", 12, "#334155")
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        devCard.addView(TextView(activity).apply {
            text = if (status.isValidPro) "✅ 目前狀態：${status.planLabel}（效期：${status.expiryDateStr}）"
            else "🔒 目前狀態：一般免費版（更新圖資需觀看廣告）"
            setTextColor(Color.parseColor(if (status.isValidPro) "#6EE7B7" else "#FCD34D"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
        })
        devCard.addView(TextView(activity).apply {
            text = "您的專屬車機設備碼：$deviceCode"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, dp(8))
        })

        val copyBtn = TextView(activity).apply {
            text = "📋 點此複製「設備碼」傳給站長付款開通"
            setTextColor(Color.parseColor("#0F172A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = rounded("#38BDF8", 10)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            setOnClickListener {
                val cb = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("神盾設備碼", "您好，我想開通神盾路況行車助手 Pro 免廣告會員，我的設備碼是：$deviceCode"))
                Toast.makeText(activity, "已複製設備碼：$deviceCode！請貼給客服開通", Toast.LENGTH_LONG).show()
                if (paymentContactUrl.isNotBlank()) {
                    try {
                        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(paymentContactUrl)))
                    } catch (_: Exception) {
                    }
                }
            }
        }
        devCard.addView(copyBtn)
        container.addView(devCard)

        // 2. 方案與付款說明
        container.addView(TextView(activity).apply {
            text = paymentContactInfo
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, dp(12), 0, dp(10))
        })

        // 3. 輸入開通碼欄位
        container.addView(TextView(activity).apply {
            text = "🔑 輸入站長核發的 Pro 專屬開通碼："
            setTextColor(Color.parseColor("#FDE047"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(4))
        })
        val keyInput = EditText(activity).apply {
            hint = "PRO2.<設備碼>.<到期日>.<簽章>"
            setText(savedLicenseKey)
            setSingleLine()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
        }
        container.addView(keyInput)

        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("👑 升級 Pro 免看廣告自動更新")
            .setView(container)
            .setPositiveButton("立即驗證開通") { _, _ ->
                val (ok, msg) = activateWithKey(keyInput.text.toString())
                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                onStatusChanged()
            }
            .setNegativeButton("關閉", null)
            .show()
    }
}
