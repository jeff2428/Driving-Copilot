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
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 台灣神盾路況行車助手 — Pro 會員硬體綁定授權碼 (HMAC-SHA256) 與發卡管理系統
 * =========================================================================
 * 安全機制：
 * 1. 每支手機依據 ANDROID_ID 產生唯一且不可偽造的 8 碼設備碼，格式：`RG-XXXX-XXXX`
 * 2. 客戶付款（轉帳 / LINE Pay / 街口）後，將 `RG-XXXX-XXXX` 傳給站長（您）。
 * 3. 站長使用「電腦版網頁發卡機」或 App 內「🔐 站長隱藏發卡機（密碼保護）」輸入客戶設備碼，
 *    透過 HMAC-SHA256 私鑰簽章產出綁定該設備與到期日的授權碼：
 *    - 年卡/月卡格式：`PRO-20271018-8F9A2B1C`
 *    - 終身卡格式：`PRO-99991231-4D2E7C9A`
 * 4. 客戶貼上授權碼後，App 驗證 HMAC 簽章與到期日完全吻合才予開通；轉傳給其他手機完全無效！
 * 5. 同時支援雲端 `pro_members.json` 遠端白名單開通與黑名單停權。
 */
class ProLicenseManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "roadguard_pro_license_prefs"
        private const val KEY_LICENSE_CODE = "saved_license_code"
        private const val KEY_ADMIN_PIN = "admin_secret_pin"
        private const val KEY_CONTACT_INFO = "owner_payment_contact_info"
        private const val KEY_CONTACT_URL = "owner_payment_contact_url"

        // 站長專屬 HMAC-SHA256 數位簽章主私鑰（與電腦版發卡機共用同一把私鑰）
        private const val MASTER_HMAC_SECRET = "TW_RoadGuard_Aegis_MasterSecret_2026_#JeffProKey"
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
     * 取得本機唯一設備碼 (格式：RG-XXXX-XXXX)
     */
    val deviceCode: String by lazy {
        @SuppressLint("HardwareIds")
        val rawId = try {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN_DEVICE"
        } catch (_: Exception) {
            "FALLBACK_DEVICE"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(("RG_DEV_$rawId").toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02X".format(it) }.take(8)
        "RG-${hex.substring(0, 4)}-${hex.substring(4, 8)}"
    }

    var savedLicenseKey: String
        get() = prefs.getString(KEY_LICENSE_CODE, "") ?: ""
        private set(value) = prefs.edit().putString(KEY_LICENSE_CODE, value.trim().uppercase(Locale.US)).apply()

    var adminPin: String
        get() = prefs.getString(KEY_ADMIN_PIN, DEFAULT_ADMIN_PIN) ?: DEFAULT_ADMIN_PIN
        set(value) = prefs.edit().putString(KEY_ADMIN_PIN, value.trim().ifBlank { DEFAULT_ADMIN_PIN }).apply()

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
                    val k = keys.next().trim().uppercase(Locale.US)
                    map[k] = whiteObj.optString(k, "99991231").trim()
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
     * 檢查目前手機是否具備有效 Pro 授權
     */
    fun checkLicenseStatus(): LicenseStatus {
        val dev = deviceCode.uppercase(Locale.US)
        if (cloudRevokedSet.contains(dev)) {
            return LicenseStatus(false, "已停權", "--", 0)
        }

        // 1. 先檢查雲端遠端白名單
        val cloudExp = cloudWhitelistExpiryMap[dev]
        if (!cloudExp.isNullOrBlank()) {
            val status = evaluateExpiry(cloudExp, "雲端遠端授權")
            if (status.isValidPro) return status
        }

        // 2. 檢查本機輸入的加密授權碼
        val key = savedLicenseKey
        if (key.isBlank()) {
            return LicenseStatus(false, "免費版（看廣告更新圖資）", "未開通", 0)
        }
        return verifyKeyForDevice(dev, key)
    }

    /**
     * 驗證指定設備碼與授權碼是否合法且未過期
     * 授權碼格式：PRO-YYYYMMDD-XXXXXXXX
     */
    fun verifyKeyForDevice(targetDeviceCode: String, rawKey: String): LicenseStatus {
        val cleanKey = rawKey.trim().uppercase(Locale.US)
        val parts = cleanKey.split("-")
        if (parts.size != 3 || parts[0] != "PRO") {
            return LicenseStatus(false, "授權碼格式錯誤", "--", 0)
        }
        val expiryYmd = parts[1]
        val providedSig = parts[2]
        if (expiryYmd.length != 8 || !expiryYmd.all { it.isDigit() }) {
            return LicenseStatus(false, "日期格式錯誤", "--", 0)
        }

        val expectedSig = computeHmacSignature(targetDeviceCode.trim().uppercase(Locale.US), expiryYmd)
        if (providedSig != expectedSig) {
            return LicenseStatus(false, "授權碼與本機設備碼不符", "--", 0)
        }

        return evaluateExpiry(expiryYmd, if (expiryYmd == "99991231") "👑 終身尊享 Pro 會員" else "👑 Pro 尊享會員")
    }

    private fun evaluateExpiry(expiryYmd: String, label: String): LicenseStatus {
        return try {
            if (expiryYmd == "99991231") {
                return LicenseStatus(true, "👑 終身尊享 Pro 會員", "永久有效", 99999)
            }
            val sdf = SimpleDateFormat("yyyyMMdd", Locale.TAIWAN).apply {
                timeZone = TimeZone.getTimeZone("Asia/Taipei")
            }
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
            savedLicenseKey = rawKey.trim().uppercase(Locale.US)
            Pair(true, "🎉 開通成功！已升級為 ${status.planLabel}（效期至：${status.expiryDateStr}）")
        } else {
            Pair(false, "❌ 開通失敗：${status.planLabel}。請確認是否輸入本機專屬授權碼。")
        }
    }

    fun clearLicense() {
        savedLicenseKey = ""
    }

    /**
     * 站長專用：根據客戶傳來的設備碼 (RG-XXXX-XXXX) 與天數，產生加密授權碼
     * @param durationDays 授權天數；若傳入 <= 0 則代表「終身永久版 (99991231)」
     */
    fun generateKeyForClientDevice(clientDeviceCode: String, durationDays: Int): String {
        val cleanDev = clientDeviceCode.trim().uppercase(Locale.US)
        val expiryYmd = if (durationDays <= 0) {
            "99991231"
        } else {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Taipei"))
            cal.add(Calendar.DAY_OF_YEAR, durationDays)
            SimpleDateFormat("yyyyMMdd", Locale.TAIWAN).apply {
                timeZone = TimeZone.getTimeZone("Asia/Taipei")
            }.format(cal.time)
        }
        val sig = computeHmacSignature(cleanDev, expiryYmd)
        return "PRO-$expiryYmd-$sig"
    }

    private fun computeHmacSignature(devCode: String, expiryYmd: String): String {
        val payload = "${devCode.trim().uppercase(Locale.US)}|${expiryYmd.trim()}"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(MASTER_HMAC_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val bytes = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02X".format(it) }.take(8)
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
            hint = "PRO-YYYYMMDD-XXXXXXXX"
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

    /**
     * 站長專用：密碼驗證與內建隨身發卡機 + 收益後台入口
     */
    fun showAdminLoginDialog(activity: Activity, onAuthenticated: () -> Unit) {
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val input = EditText(activity).apply {
            hint = "請輸入站長管理密碼"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setSingleLine()
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("🔐 站長管理後台驗證")
            .setMessage("此區域僅限軟體擁有者（站長）進入，用於核發客戶 Pro 開通碼與設定 AdMob 廣告收益。")
            .setView(input)
            .setPositiveButton("登入後台") { _, _ ->
                if (input.text.toString().trim() == adminPin) {
                    onAuthenticated()
                } else {
                    Toast.makeText(activity, "❌ 站長密碼錯誤！", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 站長隨身發卡機視窗：輸入客戶傳來的設備碼 `RG-XXXX-XXXX`，一鍵產生月卡/年卡/終身卡開通碼！
     */
    fun showAdminKeygenDialog(activity: Activity, onChanged: () -> Unit) {
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val scroll = ScrollView(activity)
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        scroll.addView(container)

        container.addView(TextView(activity).apply {
            text = "1. 輸入客戶傳來的「車機設備碼」："
            setTextColor(Color.parseColor("#93C5FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
        })
        val clientDevInput = EditText(activity).apply {
            hint = "例如：RG-8F3A-92C1"
            setText(deviceCode) // 預設帶入本機方便站長自己測試
            setSingleLine()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        container.addView(clientDevInput)

        val resultTv = TextView(activity).apply {
            text = "點擊下方按鈕產生專屬開通碼"
            setTextColor(Color.parseColor("#6EE7B7"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(10), 0, dp(10))
        }

        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(4))
        }
        fun makeGenBtn(title: String, days: Int): Button {
            return Button(activity).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setOnClickListener {
                    val targetDev = clientDevInput.text.toString().trim().uppercase(Locale.US)
                    if (!targetDev.startsWith("RG-") || targetDev.length < 10) {
                        Toast.makeText(activity, "請輸入正確的 RG-XXXX-XXXX 設備碼", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val genKey = generateKeyForClientDevice(targetDev, days)
                    resultTv.text = "已產生開通碼（已自動複製）：\n$genKey"
                    val cb = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("Pro開通碼", genKey))
                    Toast.makeText(activity, "已複製開通碼：$genKey，可直接貼給客戶！", Toast.LENGTH_LONG).show()
                }
            }
        }
        btnRow.addView(makeGenBtn("產生月卡(30天)", 30), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        btnRow.addView(makeGenBtn("產生年卡(365天)", 365), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        btnRow.addView(makeGenBtn("產生終身卡", -1), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(btnRow)
        container.addView(resultTv)

        container.addView(TextView(activity).apply {
            text = "2. 修改客戶看到的收款說明與客服連結："
            setTextColor(Color.parseColor("#93C5FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(10), 0, dp(4))
        })
        val contactInput = EditText(activity).apply {
            setText(paymentContactInfo)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        }
        container.addView(contactInput)

        val urlInput = EditText(activity).apply {
            hint = "客服 LINE 或收款連結 (例如 https://line.me/ti/p/...)"
            setText(paymentContactUrl)
            setSingleLine()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        }
        container.addView(urlInput)

        container.addView(TextView(activity).apply {
            text = "3. 修改站長後台管理密碼（目前：$adminPin）："
            setTextColor(Color.parseColor("#93C5FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(10), 0, dp(4))
        })
        val pinInput = EditText(activity).apply {
            setText(adminPin)
            setSingleLine()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        }
        container.addView(pinInput)

        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("🔑 站長隨身 Pro 發卡機與收款設定")
            .setView(scroll)
            .setPositiveButton("儲存設定") { _, _ ->
                paymentContactInfo = contactInput.text.toString()
                paymentContactUrl = urlInput.text.toString()
                adminPin = pinInput.text.toString()
                Toast.makeText(activity, "已儲存收款說明與站長密碼", Toast.LENGTH_SHORT).show()
                onChanged()
            }
            .setNeutralButton("直接幫本機開通終身Pro") { _, _ ->
                val myKey = generateKeyForClientDevice(deviceCode, -1)
                activateWithKey(myKey)
                Toast.makeText(activity, "已為站長本機開通終身 Pro！", Toast.LENGTH_SHORT).show()
                onChanged()
            }
            .setNegativeButton("關閉", null)
            .show()
    }
}
