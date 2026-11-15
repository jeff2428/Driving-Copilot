package tw.roadguard.aegis.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import tw.roadguard.aegis.model.*
import tw.roadguard.aegis.service.CameraAlertPolicy
import tw.roadguard.aegis.service.InCallAlertMode
import tw.roadguard.aegis.service.RoadGuardService
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 主畫面設計原則：
 * 1. 地圖全螢幕，是畫面主角。
 * 2. 上方只顯示「下一個需要注意的事」（封閉 > 測速 > 施工，依距離），沒有就收成一條小提示。
 * 3. 左下角時速表 + 速限牌；右側只留 3 顆圓鈕（回到車頭、語音、日夜）。
 * 4. 其他功能收進底部抽屜：附近 / 路線 / 回報 / 設定。
 */
class MainActivity : AppCompatActivity() {

    // ---------- 配色（駕駛介面：深色外框 + 高對比語意色） ----------
    private val cBg = "#0F172A"
    private val cCard = "#111827"
    private val cField = "#1E293B"
    private val cLine = "#334155"
    private val cText = "#F8FAFC"
    private val cSub = "#94A3B8"
    private val cBlue = "#2563EB"
    private val cRed = "#E11D48"
    private val cAmber = "#F59E0B"
    private val cPurple = "#7C3AED"
    private val cGreen = "#10B981"
    private val cSlate = "#64748B"

    private enum class Panel { NONE, NEARBY, REPORT, SETTINGS }

    private var svc: RoadGuardService? = null
    private var bound = false
    private var panel = Panel.NONE
    private var mapModePref = 0 // 0=自動 1=白天 2=夜間
    private var following = true
    private var mapReady = false
    private var hudMirrored = false
    private var nearbyFilter = 0 // 0=全部 1=封閉施工 2=測速
    private var reportLimit = 60
    private var lastMapPush = 0L
    private var lastMapSignature = ""
    private var lastListBuild = 0L

    // ---------- Views ----------
    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var simPill: TextView
    private lateinit var idleChip: TextView
    private lateinit var alertCard: LinearLayout
    private lateinit var alertCardBg: GradientDrawable
    private lateinit var alertIcon: TextView
    private lateinit var alertDist: TextView
    private lateinit var alertType: TextView
    private lateinit var alertDrBadge: TextView
    private lateinit var alertTitle: TextView
    private lateinit var alertDesc: TextView
    private lateinit var alertSecondary: TextView
    private lateinit var alertProgressTrack: LinearLayout
    private lateinit var alertProgressFill: View
    private lateinit var alertProgressFillBg: GradientDrawable
    private lateinit var speedCircle: LinearLayout
    private lateinit var speedCircleBg: GradientDrawable
    private lateinit var speedTv: TextView
    private lateinit var limitBadge: TextView
    private lateinit var compassTv: TextView
    private lateinit var fabRecenter: TextView
    private lateinit var fabVoice: TextView
    private lateinit var fabNight: TextView
    private lateinit var panelScroll: ScrollView
    private lateinit var panelHost: LinearLayout
    private lateinit var navItems: List<Pair<Panel, LinearLayout>>
    private lateinit var startDrivingBtn: TextView

    // 面板內動態區塊
    private var nearbyListHost: LinearLayout? = null
    private var nearbyFreshTv: TextView? = null
    private val snapshotListener: (RadarSnapshot) -> Unit = { s -> runOnUiThread { render(s) } }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            svc = (binder as? RoadGuardService.LocalBinder)?.getService() ?: return
            bound = true
            svc?.addSnapshotListener(snapshotListener)
            styleVoiceFab()
            svc?.adManager?.preloadAd(this@MainActivity)
            svc?.let { s ->
                s.adManager.updateManager.checkForAppUpdate(
                    activity = this@MainActivity,
                    manualCheck = false,
                    proLicenseManager = s.adManager.licenseManager
                )
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            svc?.removeSnapshotListener(snapshotListener)
            svc = null
            bound = false
        }
    }

    companion object {
        const val ACTION_EXIT_APP = "tw.roadguard.aegis.ACTION_EXIT_APP"
    }

    private val exitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_EXIT_APP) {
                finishAffinity()
                kotlin.system.exitProcess(0)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val filter = IntentFilter(ACTION_EXIT_APP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(exitReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(exitReceiver, filter)
        }
        buildUi()
        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        if (!bound && hasLocationPermission()) startRadarService()
        if (bound) styleVoiceFab()
        svc?.adManager?.preloadAd(this)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(exitReceiver)
        } catch (_: Exception) {
        }
        if (bound) {
            svc?.removeSnapshotListener(snapshotListener)
            unbindService(conn)
            bound = false
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (panel != Panel.NONE) openPanel(Panel.NONE) else @Suppress("DEPRECATION") super.onBackPressed()
    }

    /**
     * 關閉程式確認對話框
     */
    private fun confirmExitApp() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("🛑 關閉神盾行車助手")
            .setMessage("確定要完全結束程式？\n這將停止背景 GPS 測速與路況語音預警服務以節省電力。")
            .setPositiveButton("確定關閉") { _, _ ->
                exitAppCleanly()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 徹底清理資源並完全退出 App
     */
    private fun exitAppCleanly() {
        try {
            svc?.let { s ->
                s.removeSnapshotListener(snapshotListener)
                s.hideFloatingOverlay()
            }
            if (bound) {
                unbindService(conn)
                bound = false
            }
            stopService(Intent(this, RoadGuardService::class.java))
        } catch (_: Exception) {
        }
        finishAffinity()
        kotlin.system.exitProcess(0)
    }

    private fun hasLocationPermission() = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun startRadarService() {
        if (bound || !hasLocationPermission()) return
        val intent = Intent(this, RoadGuardService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        bound = bindService(intent, conn, Context.BIND_AUTO_CREATE)
    }

    private fun requestPermissionsIfNeeded() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 2001)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2001) {
            if (hasLocationPermission()) startRadarService()
            else Toast.makeText(this, "需要定位權限才能啟動行車警示", Toast.LENGTH_LONG).show()
        }
    }

    // =====================================================================
    // 小工具
    // =====================================================================
    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun color(hex: String) = Color.parseColor(hex)

    private fun rounded(fill: String, radius: Int, stroke: String? = null, strokeW: Int = 1) = GradientDrawable().apply {
        setColor(color(fill))
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(strokeW), color(stroke))
    }

    private fun oval(fill: String, stroke: String? = null, strokeW: Int = 1) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color(fill))
        if (stroke != null) setStroke(dp(strokeW), color(stroke))
    }

    private fun glassGradient(
        colors: IntArray,
        radius: Int,
        strokeHex: String? = null,
        strokeW: Int = 1
    ) = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
        cornerRadius = dp(radius).toFloat()
        if (strokeHex != null) setStroke(dp(strokeW), color(strokeHex))
    }

    private fun glassOval(
        colors: IntArray,
        strokeHex: String? = null,
        strokeW: Int = 2
    ) = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
        shape = GradientDrawable.OVAL
        if (strokeHex != null) setStroke(dp(strokeW), color(strokeHex))
    }

    private fun tv(text: String, sp: Float, hex: String = cText, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color(hex))
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun fmtDist(m: Double): String =
        if (m >= 1000) String.format(Locale.TAIWAN, "%.1f 公里", m / 1000.0) else "${((m / 10).roundToInt() * 10).coerceAtLeast(10)} 公尺"

    private fun sevLabel(s: EventSeverity) = when (s) {
        EventSeverity.CLOSURE -> "道路封閉"
        EventSeverity.CONSTRUCTION -> "道路施工"
        EventSeverity.HAZARD -> "事故／障礙"
        EventSeverity.CONGESTION -> "車多壅塞"
    }

    private fun sevGlyph(s: EventSeverity) = when (s) {
        EventSeverity.CLOSURE -> "⛔"
        EventSeverity.CONSTRUCTION -> "🚧"
        EventSeverity.HAZARD -> "⚠️"
        EventSeverity.CONGESTION -> "🚗"
    }

    private fun sevColor(s: EventSeverity) = when (s) {
        EventSeverity.CLOSURE -> cRed
        EventSeverity.CONSTRUCTION -> cAmber
        EventSeverity.HAZARD -> cPurple
        EventSeverity.CONGESTION -> cSlate
    }

    private fun isNightNow(): Boolean = when (mapModePref) {
        1 -> false
        2 -> true
        else -> Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it >= 18 || it < 6 }
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(root.windowToken, 0)
    }

    // =====================================================================
    // 版面
    // =====================================================================
    @SuppressLint("SetJavaScriptEnabled")
    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(color(cBg)) }

        // ---- 1. 全螢幕地圖 ----
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(color("#0E1420"))
            addJavascriptInterface(MapBridge(), "Android")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    mapReady = true
                    applyMapMode()
                    svc?.latestSnapshot?.let { pushMap(it, force = true) }
                }
            }
        }
        root.addView(webView, FrameLayout.LayoutParams(MATCH, MATCH))
        loadMap()

        // ---- 2. 前景疊層（不攔截地圖手勢） ----
        val overlay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))

        overlay.addView(buildTopArea(), lp(MATCH, WRAP))

        val middle = FrameLayout(this)
        overlay.addView(middle, lp(MATCH, 0, 1f))
        middle.addView(buildFabColumn(), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.BOTTOM).apply {
            rightMargin = dp(12); bottomMargin = dp(12)
        })
        middle.addView(buildSpeedometer(), FrameLayout.LayoutParams(dp(112), WRAP, Gravity.START or Gravity.BOTTOM).apply {
            leftMargin = dp(10); bottomMargin = dp(6)
        })

        overlay.addView(buildBottomSheet(), lp(MATCH, WRAP))
        setContentView(root)
    }

    private fun loadMap() {
        val html = assets.open("radar_map.html").bufferedReader().use { it.readText() }
        val css = assets.open("leaflet.css").bufferedReader().use { it.readText() }
        val js = assets.open("leaflet.js").bufferedReader().use { it.readText() }
        val page = html.replace("/*LEAFLET_CSS*/", css).replace("/*LEAFLET_JS*/", js)
        // 以 https 為 base，讓圖磚請求帶有正常 Referer
        webView.loadDataWithBaseURL("https://roadguard.tw/", page, "text/html", "UTF-8", null)
    }

    private fun buildTopArea(): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), 0)
        }

        simPill = tv("🛣️ 模擬試駕中・點此切換或停止", 12f, "#1F1300", true).apply {
            background = rounded("#FDE047", 14)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
            setOnClickListener { showSimulationPickerDialog() }
        }
        wrap.addView(simPill, lp(WRAP, WRAP).apply { bottomMargin = dp(8); gravity = Gravity.CENTER_HORIZONTAL })

        idleChip = tv("✓ 前方路況良好・點此看短片免費更新圖資", 13f, cText, true).apply {
            background = glassGradient(intArrayOf(color("#EA0F172A"), color("#E61E293B")), 20, "#38BDF8", 1)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setOnClickListener { triggerRewardedCloudUpdate() }
        }
        wrap.addView(idleChip, lp(WRAP, WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL })

        alertCardBg = glassGradient(intArrayOf(color("#F50B132B"), color("#EB1E293B")), 18, cAmber, 2)
        alertCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = alertCardBg
            setPadding(dp(12), dp(12), dp(14), dp(12))
            visibility = View.GONE
            elevation = dp(8).toFloat()
        }
        alertIcon = tv("🚧", 26f, cText, true).apply { gravity = Gravity.CENTER }
        alertCard.addView(alertIcon, lp(dp(56), dp(56)))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        val distRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        alertDist = tv("1.9 公里", 26f, cText, true)
        alertType = tv("道路施工", 14f, cAmber, true).apply { setPadding(dp(8), 0, 0, dp(3)) }
        distRow.addView(alertDist)
        distRow.addView(alertType)
        col.addView(distRow)

        alertDrBadge = tv("📡 隧道盲區慣性推估中", 11f, "#E0F2FE", true).apply {
            background = rounded("#0284C7", 8)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            visibility = View.GONE
        }
        col.addView(alertDrBadge, lp(WRAP, WRAP).apply { topMargin = dp(2); bottomMargin = dp(2) })

        alertTitle = tv("", 15f, cText, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        alertDesc = tv("", 12.5f, "#CBD5E1").apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        alertSecondary = tv("", 12f, cSub, true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; visibility = View.GONE
            setPadding(0, dp(4), 0, 0)
        }
        alertProgressFillBg = rounded("#38BDF8", 3)
        alertProgressFill = View(this).apply { background = alertProgressFillBg }
        alertProgressTrack = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 1.0f
            background = rounded("#334155", 3)
            addView(alertProgressFill, lp(0, MATCH, 0f))
            visibility = View.GONE
        }
        col.addView(alertTitle)
        col.addView(alertDesc)
        col.addView(alertSecondary)
        col.addView(alertProgressTrack, lp(MATCH, dp(5)).apply { topMargin = dp(6) })
        alertCard.addView(col, lp(0, WRAP, 1f))
        wrap.addView(alertCard, lp(MATCH, WRAP))
        return wrap
    }

    private fun fab(text: String, onClick: () -> Unit) = tv(text, 18f, cText, true).apply {
        gravity = Gravity.CENTER
        background = oval("#F2111827", cLine, 1)
        elevation = dp(4).toFloat()
        setOnClickListener { onClick() }
    }

    private fun buildFabColumn(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fabNight = fab("🌙") { cycleMapMode() }
        fabVoice = fab("🔊") { toggleVoice() }
        fabRecenter = fab("◎") {
            following = true
            if (mapReady) webView.evaluateJavascript("window.recenter();", null)
            styleRecenterFab()
        }
        val fabExit = fab("⏻") { confirmExitApp() }.apply {
            setTextColor(color("#EF4444"))
        }
        for ((i, v) in listOf(fabNight, fabVoice, fabRecenter, fabExit).withIndex()) {
            col.addView(v, lp(dp(48), dp(48)).apply { if (i > 0) topMargin = dp(10) })
        }
        styleRecenterFab()
        return col
    }

    private fun buildSpeedometer(): View {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val box = FrameLayout(this)
        speedCircleBg = glassOval(intArrayOf(color("#F50B132B"), color("#EB1E293B")), "#38BDF8", 3)
        speedCircle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = speedCircleBg
            elevation = dp(8).toFloat()
        }
        speedTv = tv("0", 34f, cText, true).apply { gravity = Gravity.CENTER; includeFontPadding = false }
        speedCircle.addView(speedTv)
        speedCircle.addView(tv("km/h", 11f, cSub).apply { gravity = Gravity.CENTER })
        box.addView(speedCircle, FrameLayout.LayoutParams(dp(92), dp(92), Gravity.BOTTOM or Gravity.START))

        limitBadge = tv("60", 15f, "#111111", true).apply {
            gravity = Gravity.CENTER
            background = oval("#FFFFFF", "#E5383B", 4)
            elevation = dp(10).toFloat()
            visibility = View.GONE
        }
        box.addView(limitBadge, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.TOP or Gravity.END))
        wrapper.addView(box, lp(dp(104), dp(104)))

        compassTv = tv("🧭 北 0°", 11.5f, "#E0F2FE", true).apply {
            gravity = Gravity.CENTER
            background = glassGradient(intArrayOf(color("#EA0B132B"), color("#E61E293B")), 12, "#334155", 1)
            setPadding(dp(10), dp(3), dp(10), dp(3))
        }
        wrapper.addView(compassTv, lp(WRAP, WRAP).apply { topMargin = dp(4) })
        return wrapper
    }

    private fun buildBottomSheet(): View {
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(color("#F50F172A"), color("#FF0B1120"))
            ).apply {
                val r = dp(22).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setStroke(dp(1), color("#1E293B"))
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            elevation = dp(12).toFloat()
            isClickable = true // 抽屜本身吃掉觸控，避免誤觸地圖
        }

        val handle = View(this).apply { background = rounded("#475569", 3) }
        val handleWrap = FrameLayout(this).apply {
            setPadding(0, dp(4), 0, dp(10))
            setOnClickListener { if (panel != Panel.NONE) openPanel(Panel.NONE) else openPanel(Panel.NEARBY) }
        }
        handleWrap.addView(handle, FrameLayout.LayoutParams(dp(40), dp(5), Gravity.CENTER_HORIZONTAL))
        sheet.addView(handleWrap, lp(MATCH, WRAP))

        panelScroll = ScrollView(this).apply { visibility = View.GONE; isVerticalScrollBarEnabled = false }
        panelHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(10)) }
        panelScroll.addView(panelHost)
        val panelH = (resources.displayMetrics.heightPixels * 0.46f).toInt()
        sheet.addView(panelScroll, lp(MATCH, panelH))

        startDrivingBtn = tv("▶  開始行車", 16f, "#FFFFFF", true).apply {
            gravity = Gravity.CENTER
            background = rounded(cBlue, 14)
            setOnClickListener { startDriving() }
        }
        sheet.addView(startDrivingBtn, lp(MATCH, dp(54)).apply { topMargin = dp(10) })

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(2))
        }
        val defs = listOf(
            Triple(Panel.NEARBY, "📍", "附近"),
            Triple(Panel.REPORT, "➕", "回報"),
            Triple(Panel.SETTINGS, "⚙️", "設定")
        )
        navItems = defs.map { (p, icon, label) ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener { openPanel(p) }
            }
            item.addView(tv(icon, 18f).apply { gravity = Gravity.CENTER })
            item.addView(tv(label, 12f, cSub, true).apply { gravity = Gravity.CENTER })
            nav.addView(item, lp(0, WRAP, 1f))
            p to item
        }
        sheet.addView(nav, lp(MATCH, WRAP))
        return sheet
    }

    // =====================================================================
    // 抽屜面板
    // =====================================================================
    private fun openPanel(p: Panel) {
        val target = if (panel == p) Panel.NONE else p
        panel = target
        hideKeyboard()
        panelHost.removeAllViews()
        nearbyListHost = null
        nearbyFreshTv = null

        when (target) {
            Panel.NONE -> {}
            Panel.NEARBY -> buildNearbyPanel()
            Panel.REPORT -> buildReportPanel()
            Panel.SETTINGS -> buildSettingsPanel()
        }
        panelScroll.visibility = if (target == Panel.NONE) View.GONE else View.VISIBLE
        panelScroll.scrollTo(0, 0)

        for ((np, item) in navItems) {
            val on = np == target
            item.background = if (on) rounded("#1E3A8A", 12) else null
            ((item.getChildAt(1)) as TextView).setTextColor(color(if (on) "#BFDBFE" else cSub))
        }
        if (target == Panel.NEARBY) svc?.latestSnapshot?.let { renderNearbyList(it) }
    }

    private fun sectionTitle(text: String, sub: String? = null): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(10)) }
        col.addView(tv(text, 17f, cText, true))
        if (sub != null) col.addView(tv(sub, 12f, cSub).apply { setPadding(0, dp(2), 0, 0) })
        return col
    }

    private fun chipRow(labels: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(10)) }
        labels.forEachIndexed { i, label ->
            val on = i == selected
            row.addView(tv(label, 13f, if (on) "#FFFFFF" else cSub, true).apply {
                gravity = Gravity.CENTER
                background = if (on) rounded(cBlue, 10) else rounded(cField, 10)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setOnClickListener { onPick(i) }
            }, lp(0, WRAP, 1f).apply { if (i > 0) leftMargin = dp(6) })
        }
        return row
    }

    /** 統一的清單列：左側圖示方塊、中間標題說明、右側距離 */
    private fun listRow(
        iconText: String, iconFill: String, iconTextColor: String,
        title: String, sub: String, right: String, onClick: () -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(cCard, 14)
            setPadding(dp(10), dp(10), dp(12), dp(10))
            setOnClickListener { onClick() }
        }
        row.addView(tv(iconText, 16f, iconTextColor, true).apply {
            gravity = Gravity.CENTER
            background = when (iconFill) {
                "CAM" -> oval("#FFFFFF", "#E5383B", 3)
                "HOTSPOT" -> oval("#FFF7ED", "#EA580C", 3)
                else -> rounded(iconFill, 10)
            }
        }, lp(dp(40), dp(40)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(8), 0) }
        col.addView(tv(title, 14f, cText, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        col.addView(tv(sub, 12f, cSub).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        row.addView(col, lp(0, WRAP, 1f))
        row.addView(tv(right, 13f, cText, true))
        val wrap = LinearLayout(this).apply { setPadding(0, 0, 0, dp(8)) }
        wrap.addView(row, lp(MATCH, WRAP))
        return wrap
    }

    private fun eventRow(se: ScoredRoadEvent): View {
        val e = se.event
        val dirPart = if (e.direction.isNotBlank()) " ${e.direction}" else ""
        return listRow(
            sevGlyph(e.severity), sevColor(e.severity), "#FFFFFF",
            "${sevLabel(e.severity)}・${e.roadName}$dirPart",
            if (e.isActiveNow) e.rawComment else "（目前非施工時段）${e.rawComment}",
            fmtDist(se.distanceMeters)
        ) {
            if (mapReady) webView.evaluateJavascript("window.focusPoint(${e.lat},${e.lng});", null)
            showEventDialog(e)
        }
    }

    private fun cameraRow(sc: ScoredCamera): View {
        val c = sc.camera
        val isHotspot = c.type == CameraType.HOTSPOT
        val fill = if (isHotspot) "HOTSPOT" else "CAM"
        val prefix = if (isHotspot) "🚨 " else ""
        return listRow(
            "${c.speedLimit}", fill, if (isHotspot) "#C2410C" else "#111111",
            "$prefix${c.type.label}・速限 ${c.speedLimit}",
            "${c.city}${c.region} ${c.address}",
            fmtDist(sc.distanceMeters)
        ) {
            if (mapReady) webView.evaluateJavascript("window.focusPoint(${c.lat},${c.lng});", null)
        }
    }

    // ---- 附近 ----
    private fun buildNearbyPanel() {
        panelHost.addView(sectionTitle("附近路況與測速"))
        nearbyFreshTv = tv("", 12f, cSub).apply { setPadding(0, 0, 0, dp(8)) }
        panelHost.addView(nearbyFreshTv)

        val isPro = svc?.adManager?.isProMember == true
        val adUpdateBtn = tv(
            if (isPro) "⚡ Pro 尊享：免看廣告立即同步最新雲端圖資" else "🎬 看 5 秒廣告・免費同步最新測速與施工圖資",
            13.5f,
            if (isPro) "#FFFFFF" else "#1F1300",
            true
        ).apply {
            gravity = Gravity.CENTER
            background = if (isPro) rounded("#059669", 12) else rounded("#FBBF24", 12)
            setPadding(dp(12), dp(11), dp(12), dp(11))
            setOnClickListener { triggerRewardedCloudUpdate() }
        }
        panelHost.addView(adUpdateBtn, lp(MATCH, WRAP).apply { bottomMargin = dp(10) })

        panelHost.addView(chipRow(listOf("全部", "封閉・施工", "測速照相"), nearbyFilter) {
            nearbyFilter = it
            openPanel(Panel.NONE); openPanel(Panel.NEARBY)
        })
        nearbyListHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panelHost.addView(nearbyListHost)
    }

    private var tunnelCardDir: String = "S"

    private fun buildHsuehshanTunnelCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded("#111827", 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val lp = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                bottomMargin = dp(12)
            }
            layoutParams = lp
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(tv("🚇 雪隧即時車道建議", 14.5f, "#38BDF8", true), lp(0, WRAP, 1f))

        val btnS = tv("南下 (往宜蘭)", 12f, if (tunnelCardDir == "S") "#FFFFFF" else cSub, true).apply {
            background = if (tunnelCardDir == "S") rounded(cBlue, 8) else rounded(cField, 8)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener {
                tunnelCardDir = "S"
                svc?.latestSnapshot?.let { renderNearbyList(it) }
            }
        }
        val btnN = tv("北上 (往台北)", 12f, if (tunnelCardDir == "N") "#FFFFFF" else cSub, true).apply {
            background = if (tunnelCardDir == "N") rounded(cBlue, 8) else rounded(cField, 8)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener {
                tunnelCardDir = "N"
                svc?.latestSnapshot?.let { renderNearbyList(it) }
            }
        }
        topRow.addView(btnS)
        topRow.addView(btnN, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
        card.addView(topRow)

        val advice = tw.roadguard.aegis.data.HsuehshanTunnelClient.getAdvice(tunnelCardDir, 0.0)

        // 建議車道橫幅
        val adviceBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded("#1E293B", 10)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            val lp = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
            layoutParams = lp
        }
        adviceBanner.addView(tv(if (advice.hasLaneRecommendation) "建議走${advice.recommendedLaneName}（${advice.recommendedLaneSide}）" else "暫無即時資料", 13.5f, "#34D399", true), lp(0, WRAP, 1f))
        adviceBanner.addView(tv(advice.reason, 12f, "#FBBF24"))
        card.addView(adviceBanner)

        // 內側 vs 外側速度對比欄
        val colsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val innerSpeedStr = advice.innerSpeedKmh?.let { "$it km/h" } ?: "--"
        val innerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = if (advice.recommendedLane == "inner") rounded("#064E3B", 10) else rounded("#1F2937", 10)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        innerCol.addView(tv("內側車道 (左線)", 12f, if (advice.recommendedLane == "inner") "#A7F3D0" else cSub))
        innerCol.addView(tv(innerSpeedStr, 17f, if (advice.recommendedLane == "inner") "#34D399" else "#FFFFFF", true))
        val innerMinStr = advice.innerTravelMin?.let { "約 ${String.format("%.1f", it)} 分" } ?: "--"
        innerCol.addView(tv(innerMinStr, 11f, cSub))
        colsRow.addView(innerCol, lp(0, WRAP, 1f))

        val outerSpeedStr = advice.outerSpeedKmh?.let { "$it km/h" } ?: "--"
        val outerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = if (advice.recommendedLane == "outer") rounded("#064E3B", 10) else rounded("#1F2937", 10)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        outerCol.addView(tv("外側車道 (右線)", 12f, if (advice.recommendedLane == "outer") "#A7F3D0" else cSub))
        outerCol.addView(tv(outerSpeedStr, 17f, if (advice.recommendedLane == "outer") "#34D399" else "#FFFFFF", true))
        val outerMinStr = advice.outerTravelMin?.let { "約 ${String.format("%.1f", it)} 分" } ?: "--"
        outerCol.addView(tv(outerMinStr, 11f, cSub))
        colsRow.addView(outerCol, lp(0, WRAP, 1f).apply { leftMargin = dp(8) })
        card.addView(colsRow)

        // 試聽語音與提醒
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
            layoutParams = lp
        }
        bottomRow.addView(tv("⚠️ 雪隧內雙白線禁止變換車道", 11f, "#94A3B8"), lp(0, WRAP, 1f))

        val btnVoice = tv("🔊 試聽語音", 12f, "#38BDF8", true).apply {
            background = rounded("#0F2942", 8)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                svc?.voiceEngine?.announceHsuehshanTunnel(advice.copy(isApproachingEntrance = true), force = true)
            }
        }
        if (advice.hasLaneRecommendation) bottomRow.addView(btnVoice)
        card.addView(bottomRow)

        return card
    }

    private fun renderNearbyList(s: RadarSnapshot) {
        val host = nearbyListHost ?: return
        nearbyFreshTv?.text = "路況更新 ${s.lastUpdatedTime}・測速題庫 ${svc?.repository?.lastCameraSyncDateStr ?: "--"}"
        host.removeAllViews()

        // 🚇 國道5號 雪山隧道即時車道分析 (置頂卡片)
        host.addView(buildHsuehshanTunnelCard())

        val events = if (nearbyFilter == 2) emptyList() else s.nearbyEvents.filter {
            nearbyFilter == 0 || it.event.severity == EventSeverity.CLOSURE || it.event.severity == EventSeverity.CONSTRUCTION
        }.sortedBy { it.distanceMeters }.take(15)
        val cams = if (nearbyFilter == 1) emptyList() else s.nearbyCameras.take(12)

        if (events.isEmpty() && cams.isEmpty()) {
            host.addView(tv("附近沒有施工、封閉或測速照相 👍", 14f, cSub).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(24), 0, dp(24))
            }, lp(MATCH, WRAP))
            return
        }
        events.forEach { host.addView(eventRow(it)) }
        cams.forEach { host.addView(cameraRow(it)) }
    }

    // ---- 路線 ----
    private fun buildReportPanel() {
        panelHost.addView(sectionTitle("回報目前位置", "下次經過會自動提醒你。資料只存在這支手機。"))

        val note = EditText(this).apply {
            hint = "補充說明（可不填）例如：外側車道封閉"
            setHintTextColor(color("#64748B"))
            setTextColor(color(cText))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = rounded(cField, 12)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setSingleLine()
        }
        panelHost.addView(note, lp(MATCH, WRAP).apply { bottomMargin = dp(12) })

        panelHost.addView(tv("⛔  這裡封路／施工", 16f, "#FFFFFF", true).apply {
            gravity = Gravity.CENTER
            background = rounded(cRed, 14)
            setOnClickListener {
                val s = svc ?: return@setOnClickListener
                val desc = note.text.toString().ifBlank { "道路封閉或施工" }
                s.repository.addCustomRoadEvent("我回報的路段", desc, true, s.currentLat, s.currentLng)
                s.recomputeRadarState()
                Toast.makeText(this@MainActivity, "已記下這裡的封路／施工", Toast.LENGTH_SHORT).show()
                openPanel(Panel.NONE)
            }
        }, lp(MATCH, dp(60)).apply { bottomMargin = dp(14) })

        panelHost.addView(tv("這裡有測速照相，速限：", 13f, cSub, true).apply { setPadding(0, 0, 0, dp(8)) })
        val limits = listOf(40, 50, 60, 70, 80, 90, 100, 110)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        limits.chunked(4).forEach { rowVals ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(8)) }
            rowVals.forEachIndexed { i, v ->
                val on = v == reportLimit
                r.addView(tv("$v", 16f, if (on) "#111111" else cText, true).apply {
                    gravity = Gravity.CENTER
                    background = if (on) rounded("#FFFFFF", 12, "#E5383B", 3) else rounded(cField, 12)
                    setOnClickListener { reportLimit = v; openPanel(Panel.NONE); openPanel(Panel.REPORT) }
                }, lp(0, dp(46), 1f).apply { if (i > 0) leftMargin = dp(8) })
            }
            grid.addView(r, lp(MATCH, WRAP))
        }
        panelHost.addView(grid, lp(MATCH, WRAP))

        panelHost.addView(tv("📸  新增測速點（速限 $reportLimit）", 16f, "#FFFFFF", true).apply {
            gravity = Gravity.CENTER
            background = rounded(cBlue, 14)
            setOnClickListener {
                val s = svc ?: return@setOnClickListener
                s.repository.addCustomSpeedCamera(note.text.toString().ifBlank { "我回報的測速點" }, reportLimit, s.currentLat, s.currentLng)
                s.recomputeRadarState()
                Toast.makeText(this@MainActivity, "已新增測速點（速限 $reportLimit）", Toast.LENGTH_SHORT).show()
                openPanel(Panel.NONE)
            }
        }, lp(MATCH, dp(56)).apply { topMargin = dp(4) })

        val mine = (svc?.repository?.customRoadEvents?.size ?: 0) + (svc?.repository?.customCameras?.size ?: 0)
        panelHost.addView(tv("已回報 $mine 筆", 12f, cSub).apply { setPadding(0, dp(10), 0, 0); gravity = Gravity.CENTER }, lp(MATCH, WRAP))
    }

    // ---- 設定 ----
    private fun settingsGroup(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(cCard, 14)
        setPadding(dp(14), dp(6), dp(14), dp(6))
    }

    private fun switchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(title, 14.5f, cText, true))
        col.addView(tv(sub, 12f, cSub))
        row.addView(col, lp(0, WRAP, 1f))
        row.addView(Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, c -> onChange(c) }
        })
        return row
    }

    private fun labeledChips(title: String, labels: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, 0) }
        col.addView(tv(title, 14.5f, cText, true).apply { setPadding(0, 0, 0, dp(8)) })
        col.addView(chipRow(labels, selected, onPick))
        return col
    }

    private fun actionRow(title: String, sub: String, onClick: (TextView) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        val t = tv(title, 14.5f, "#93C5FD", true)
        row.addView(t)
        row.addView(tv(sub, 12f, cSub))
        row.setOnClickListener { onClick(t) }
        return row
    }

    private fun divider() = View(this).apply { setBackgroundColor(color("#1F2937")) }

    private fun buildSettingsPanel() {
        val s = svc
        val repo = s?.repository
        val adMgr = s?.adManager
        val licMgr = adMgr?.licenseManager
        val licStatus = licMgr?.checkLicenseStatus()
        val isPro = licStatus?.isValidPro == true
        panelHost.addView(sectionTitle("設定與雲端圖資中心"))

        // ---- ☁️ 雲端圖資自動同步中心 & Pro 會員授權狀態 ----
        val gCloud = settingsGroup()
        val cams = repo?.cameras ?: emptyList()
        val evs = repo?.roadEvents ?: emptyList()
        val intervalCnt = cams.count { it.type == CameraType.INTERVAL }
        val hotspotCnt = cams.count { it.type == CameraType.HOTSPOT }
        val techCnt = cams.count { it.type == CameraType.TECH }
        val closureCnt = evs.count { !it.isCleared && it.severity == EventSeverity.CLOSURE }
        val constrCnt = evs.count { !it.isCleared && it.severity == EventSeverity.CONSTRUCTION }

        val verHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        verHeader.addView(tv("☁️ 圖資版本 v${repo?.dataVersionStr ?: "2026.10.19"}", 15f, "#38BDF8", true))
        verHeader.addView(tv("通道：${repo?.lastSyncChannelStr ?: "內建題庫"}・更新：${repo?.lastCameraSyncDateStr ?: "--"}", 12f, cSub).apply {
            setPadding(0, dp(2), 0, dp(6))
        })
        verHeader.addView(tv("📸 測速 ${cams.size} 處（區間 $intervalCnt・經常出沒 $hotspotCnt・科技執法 $techCnt）", 12.5f, cText))
        verHeader.addView(tv("🚧 路況 ${evs.size} 筆（⛔ 封閉 $closureCnt・🚧 施工 $constrCnt）", 12.5f, cText).apply {
            setPadding(0, dp(2), 0, dp(10))
        })

        // 醒目激勵廣告更新大按鈕
        val updateCta = tv(
            if (isPro) "⚡ Pro 尊享：免看廣告・立即同步最新雲端圖資"
            else "🎬 看 5 秒廣告・免費更新最新測速與施工圖資",
            14.5f,
            if (isPro) "#FFFFFF" else "#1F1300",
            true
        ).apply {
            gravity = Gravity.CENTER
            background = if (isPro) rounded("#059669", 12) else rounded("#FBBF24", 12)
            setPadding(dp(12), dp(13), dp(12), dp(13))
            setOnClickListener { triggerRewardedCloudUpdate() }
        }
        verHeader.addView(updateCta, lp(MATCH, WRAP))
        gCloud.addView(verHeader)
        gCloud.addView(divider(), lp(MATCH, 1))

        // 客戶 Pro 授權
        val devCode = licMgr?.deviceCode ?: "取得設備碼中"
        gCloud.addView(actionRow(
            if (isPro) "👑 ${licStatus?.planLabel}（效期：${licStatus?.expiryDateStr}）"
            else "👑 升級 Pro 免看廣告＋上車全自動更新（點擊開通）",
            if (isPro) "本機設備碼：$devCode・已享免廣告與背景自動同步特權"
            else "本機設備碼：$devCode・聯繫站長付款領取專屬開通碼即可解鎖"
        ) {
            licMgr?.showClientActivationDialog(this) {
                svc?.recomputeRadarState()
                openPanel(Panel.NONE); openPanel(Panel.SETTINGS)
            }
        })
        gCloud.addView(divider(), lp(MATCH, 1))

        // 📲 App 本體在線自動檢查更新 (In-App OTA Updater)
        gCloud.addView(actionRow(
            "📲 檢查軟體版本更新（目前 v${adMgr?.updateManager?.currentVersionName ?: "2.0.0"}）",
            "在線檢查是否有新版 APK 釋出並支援一鍵下載升級"
        ) {
            adMgr?.updateManager?.checkForAppUpdate(
                activity = this,
                manualCheck = true,
                proLicenseManager = licMgr
            )
        })
        gCloud.addView(divider(), lp(MATCH, 1))

        gCloud.addView(actionRow("圖資來源", repo?.githubRepoSlug.orEmpty()) { showGitHubRepoDialog() })
        panelHost.addView(gCloud, lp(MATCH, WRAP).apply { bottomMargin = dp(12) })

        val g1 = settingsGroup()
        val tol = s?.voiceEngine?.overspeedToleranceKmh ?: 10
        g1.addView(labeledChips("超速提醒寬限", listOf("不寬限", "+5", "+10"), listOf(0, 5, 10).indexOf(tol).coerceAtLeast(0)) { i ->
            svc?.voiceEngine?.overspeedToleranceKmh = listOf(0, 5, 10)[i]
            openPanel(Panel.NONE); openPanel(Panel.SETTINGS)
        })
        g1.addView(divider(), lp(MATCH, 1))
        val callModes = listOf(InCallAlertMode.MUTE_VISUAL, InCallAlertMode.DUCKING, InCallAlertMode.NORMAL)
        val currentCallMode = s?.voiceEngine?.inCallAlertMode ?: InCallAlertMode.MUTE_VISUAL
        g1.addView(labeledChips(
            "通話中語音策略",
            listOf("靜音閃爍", "壓低音量", "正常播報"),
            callModes.indexOf(currentCallMode).coerceAtLeast(0)
        ) { i ->
            svc?.voiceEngine?.inCallAlertMode = callModes[i]
            openPanel(Panel.NONE); openPanel(Panel.SETTINGS)
        })
        g1.addView(divider(), lp(MATCH, 1))
        g1.addView(switchRow("只提醒前方同方向", "過濾對向車道與後方的施工、測速", s?.forwardConeOnly ?: true) {
            svc?.forwardConeOnly = it; svc?.recomputeRadarState()
        })
        g1.addView(divider(), lp(MATCH, 1))
        g1.addView(switchRow("提醒經常出沒點", "提醒警方經常架設移動式測速、雷射槍之取締熱點", s?.voiceEngine?.isHotspotAlertEnabled ?: true) {
            svc?.voiceEngine?.isHotspotAlertEnabled = it
            svc?.recomputeRadarState()
        })
        g1.addView(divider(), lp(MATCH, 1))
        g1.addView(switchRow("通過測速後「叮咚」", "提醒你已經通過測速點", s?.voiceEngine?.isPassChimeEnabled ?: true) {
            svc?.voiceEngine?.isPassChimeEnabled = it
        })
        g1.addView(divider(), lp(MATCH, 1))
        g1.addView(switchRow("懸浮窗支援貼邊收折", "推至螢幕左/右邊界自動收折隱藏，測速與危險時自動彈出", s?.isEdgeDockingEnabled ?: true) {
            svc?.isEdgeDockingEnabled = it
        })
        g1.addView(divider(), lp(MATCH, 1))
        g1.addView(switchRow("雪隧進洞前車道建議", "進入雪山隧道前 2.5km 語音提醒走內側或外側行車順暢", s?.voiceEngine?.isTunnelLaneAlertEnabled ?: true) {
            svc?.voiceEngine?.isTunnelLaneAlertEnabled = it
        })
        panelHost.addView(g1, lp(MATCH, WRAP).apply { bottomMargin = dp(12) })

        val g2 = settingsGroup()
        g2.addView(labeledChips("地圖顏色", listOf("自動", "白天", "夜間"), mapModePref) { i ->
            mapModePref = i; applyMapMode()
            openPanel(Panel.NONE); openPanel(Panel.SETTINGS)
        })
        g2.addView(divider(), lp(MATCH, 1))
        g2.addView(switchRow("HUD 抬頭顯示（鏡像）", "手機平放在儀表板，畫面反射到擋風玻璃", hudMirrored) {
            hudMirrored = it; root.scaleX = if (it) -1f else 1f
        })
        panelHost.addView(g2, lp(MATCH, WRAP).apply { bottomMargin = dp(12) })

        val g3 = settingsGroup()
        g3.addView(actionRow("測試語音", "聽聽提醒的聲音，會暫時壓低音樂音量") {
            svc?.voiceEngine?.speakNow("前方 600 公尺測速照相，速限 60 公里。前方 1.2 公里外側車道封閉施工。", urgentTone = true)
        })
        g3.addView(divider(), lp(MATCH, 1))
        g3.addView(actionRow(
            if (s?.isSimulating == true) "🛣️ 切換／停止模擬試駕" else "🛣️ 模擬試駕（7 條實境路線）",
            "選擇台中環中路、五楊高架、西濱區間、北宜、雪隧或國道警車出沒實測"
        ) {
            showSimulationPickerDialog()
        })
        g3.addView(divider(), lp(MATCH, 1))
        panelHost.addView(g3, lp(MATCH, WRAP).apply { bottomMargin = dp(12) })

        panelHost.addView(tv(
            "資料來源：內政部警政署「測速執法設置點」、警察廣播電臺「即時路況」、內政部國土測繪中心「臺灣通用電子地圖」，依政府資料開放授權條款使用。提醒僅供參考，請依現場標誌與交通規則駕駛。",
            11f, "#64748B"
        ))
    }

    private fun triggerRewardedCloudUpdate() {
        val s = svc ?: return
        s.adManager.showRewardedAdForUpdate(
            activity = this,
            onRewardEarned = {
                Toast.makeText(this, "⏳ 已解鎖獎勵！正在從雲端同步最新測速與施工圖資…", Toast.LENGTH_SHORT).show()
                Thread {
                    val (_, msg) = s.repository.syncAllCloudData(forceCameras = true)
                    runOnUiThread {
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        s.recomputeRadarState()
                        if (panel != Panel.NONE) {
                            val cur = panel
                            openPanel(Panel.NONE)
                            openPanel(cur)
                        }
                    }
                }.start()
            },
            onUpgradeProClicked = {
                s.adManager.licenseManager.showClientActivationDialog(this) {
                    s.recomputeRadarState()
                    if (panel != Panel.NONE) {
                        val cur = panel
                        openPanel(Panel.NONE)
                        openPanel(cur)
                    }
                }
            }
        )
    }

    private fun showGitHubRepoDialog() {
        val repo = svc?.repository ?: return
        val input = EditText(this).apply {
            hint = "例如：yourname/tw-roadguard-aegis（留空則直連政府API）"
            setText(repo.githubRepoSlug)
            setSingleLine()
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("綁定 GitHub Actions 雲端圖資庫")
            .setMessage("將專案推送至 GitHub 後，GitHub Actions 每 6 小時會自動執行爬蟲更新 cloud_data/。\n填入「帳號/倉庫名」即可透過全球 CDN 極速拉取圖資（留空則直接連內政部警政署與警廣 API）。")
            .setView(input)
            .setPositiveButton("儲存並看廣告同步") { _, _ ->
                repo.githubRepoSlug = input.text.toString().trim()
                triggerRewardedCloudUpdate()
            }
            .setNeutralButton("改回政府API直連") { _, _ ->
                repo.githubRepoSlug = ""
                openPanel(Panel.NONE)
                openPanel(Panel.SETTINGS)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSimulationPickerDialog() {
        val sv = svc ?: return
        val routes = arrayOf(
            "路線 1：台中環中路 / 台74（封閉施工 + 固定測速 60）",
            "路線 2：國道1號五楊高架南下（緩撞車施工 + 測速 100）",
            "路線 3：台61線西濱快速（區間測速 80 + 單線管制）",
            "路線 4：北宜公路 33K（經常出沒移動測速 40 + 施工）",
            "路線 5：國道5號雪山隧道南下（進洞建議 ➔ 科技執法）",
            "路線 6：國道5號雪山隧道北上（進洞建議 ➔ 科技執法）",
            "路線 7：國道1號后里避車彎（警車雷射槍超速警戒 110）"
        )
        val builder = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("🛣️ 選擇實境模擬試駕路線")
            .setItems(routes) { _, which ->
                sv.startDemoSimulation(which)
                if (panel != Panel.NONE) openPanel(Panel.NONE)
            }
            .setNegativeButton("取消", null)
        if (sv.isSimulating) {
            builder.setNeutralButton("停止模擬試駕") { _, _ ->
                sv.stopDemoSimulation()
                if (panel != Panel.NONE) openPanel(Panel.NONE)
            }
        }
        builder.show()
    }

    private fun render(s: RadarSnapshot) {
        // 時速表與方位角（僅在行車狀態且超速時才亮紅圈警示）
        speedTv.text = "${s.speedKmh}"
        compassTv.text = "🧭 ${s.bearingLabel} ${s.bearingDeg.roundToInt()}°"
        val activeDriving = s.isDrivingActive || s.isSimulating
        if (s.isOverspeed && activeDriving) {
            speedTv.setTextColor(Color.WHITE)
            speedCircleBg.colors = intArrayOf(color("#F09F1239"), color("#EBE11D48"))
            speedCircleBg.setStroke(dp(3), color("#FDA4AF"))
        } else if (s.inIntervalZone) {
            speedTv.setTextColor(color(cText))
            speedCircleBg.colors = intArrayOf(color("#F50B132B"), color("#EB3B0764"))
            speedCircleBg.setStroke(dp(3), color("#A855F7"))
        } else {
            speedTv.setTextColor(color(cText))
            speedCircleBg.colors = intArrayOf(color("#F50B132B"), color("#EB1E293B"))
            speedCircleBg.setStroke(dp(3), color(if (activeDriving) "#38BDF8" else cLine))
        }
        val activeLimit = s.nearestCamera?.camera?.speedLimit
            ?: if (s.inIntervalZone && s.intervalSpeedLimit > 0) s.intervalSpeedLimit else null
        limitBadge.visibility = if (activeLimit != null) View.VISIBLE else View.GONE
        activeLimit?.let { limitBadge.text = "$it" }

        simPill.visibility = if (s.isSimulating) View.VISIBLE else View.GONE

        if (s.isDrivingActive) {
            startDrivingBtn.text = "⏸️  結束行車"
            startDrivingBtn.background = rounded(cField, 14, cLine, 1)
            startDrivingBtn.setOnClickListener { startDriving() }
        } else {
            startDrivingBtn.text = "▶  開始行車"
            startDrivingBtn.background = rounded(cBlue, 14)
            startDrivingBtn.setOnClickListener { startDriving() }
        }

        renderAlert(s)

        val now = System.currentTimeMillis()
        if (now - lastMapPush > 1000) { lastMapPush = now; pushMap(s) }
        if (panel == Panel.NEARBY && now - lastListBuild > 2500) { lastListBuild = now; renderNearbyList(s) }
    }

    private fun setAlertProgress(fraction: Float, hexColor: String) {
        alertProgressTrack.visibility = View.VISIBLE
        alertProgressFillBg.setColor(color(hexColor))
        val lpFill = alertProgressFill.layoutParams as LinearLayout.LayoutParams
        val targetWeight = fraction.coerceIn(0.05f, 1.0f)
        if (lpFill.weight != targetWeight) {
            lpFill.weight = targetWeight
            alertProgressFill.layoutParams = lpFill
        }
    }

    private fun renderAlert(s: RadarSnapshot) {
        // 未開始行車且非模擬/非導航中：預設不顯示警告，保持安靜待命
        if (!s.isDrivingActive && !s.isSimulating) {
            alertCard.visibility = View.GONE
            alertDrBadge.visibility = View.GONE
            alertProgressTrack.visibility = View.GONE
            idleChip.visibility = View.VISIBLE
            idleChip.text = "⏸️ 雷達待命中・點擊下方「開始行車」啟動測速與路況提醒"
            return
        }

        val tunnel = s.tunnelLaneAdvice
        if (tunnel != null && tunnel.isApproachingEntrance) {
            idleChip.visibility = View.GONE
            alertCard.visibility = View.VISIBLE
            alertDrBadge.visibility = View.GONE
            alertCardBg.setStroke(dp(2), color("#38BDF8"))
            alertIcon.text = "🚇"
            alertIcon.setTextColor(Color.WHITE)
            alertIcon.background = rounded("#0284C7", 14)
            alertDist.text = fmtDist(tunnel.distanceToEntranceMeters)
            alertType.text = if (tunnel.hasLaneRecommendation) "即將進雪隧 · 建議走${tunnel.recommendedLaneName}" else "雪隧 · 暫無即時資料"
            alertType.setTextColor(color("#38BDF8"))
            val innerSpd = tunnel.innerSpeedKmh?.let { "${it}km/h" } ?: "--"
            val outerSpd = tunnel.outerSpeedKmh?.let { "${it}km/h" } ?: "--"
            alertTitle.text = "雪山隧道${tunnel.directionLabel}：${tunnel.reason}"
            val travel = tunnel.tunnelTravelMin?.let { " ｜ 預估 %.1f 分鐘".format(it) }.orEmpty()
            alertDesc.text = "內側 $innerSpd ｜ 外側 $outerSpd$travel。雙白線全程禁止變換車道。"
            if (!tunnel.alertEvent.isNullOrBlank()) {
                alertSecondary.visibility = View.VISIBLE
                alertSecondary.text = "⚠️ 隧道路況通報：${tunnel.alertEvent}"
            } else {
                alertSecondary.visibility = View.GONE
            }
            setAlertProgress(((2500.0 - tunnel.distanceToEntranceMeters) / 2180.0).toFloat(), "#38BDF8")
            alertCard.setOnClickListener {
                tunnelCardDir = tunnel.direction
                openPanel(Panel.NEARBY)
            }
            return
        }

        val cam = s.nearestCamera
        val ev = s.nearestRoadEvent

        // 優先順序：封閉 > 超速中的測速 > 距離較近者
        val showEvent = when {
            ev == null -> false
            cam == null -> true
            ev.event.severity == EventSeverity.CLOSURE -> true
            s.isOverspeed -> false
            else -> ev.distanceMeters < cam.distanceMeters
        }

        if (cam == null && ev == null) {
            if (s.inIntervalZone) {
                idleChip.visibility = View.GONE
                alertCard.visibility = View.VISIBLE
                val zoneColor = if (s.isOverspeed) cRed else "#A855F7"
                alertCardBg.setStroke(dp(2), color(zoneColor))
                alertIcon.text = "⏱️"
                alertIcon.setTextColor(Color.WHITE)
                alertIcon.background = rounded("#6D28D9", 14)
                alertDist.text = "${s.intervalRemainingSec} 秒"
                alertType.text = if (s.isDeadReckoning) "隧道推估安全倒數" else "區間測速監控中"
                alertType.setTextColor(color(zoneColor))
                alertDrBadge.visibility = if (s.isDeadReckoning) View.VISIBLE else View.GONE
                val limitSuffix = if (s.intervalSpeedLimit > 0) "（速限 ${s.intervalSpeedLimit}）" else ""
                alertTitle.text = "區間平均 ${s.intervalAvgSpeedKmh} km/h$limitSuffix"
                alertDesc.text = if (s.isDeadReckoning) {
                    "隧道 GPS 盲區中，依最後車速持續慣性推估里程"
                } else {
                    "請維持穩定車速通過區間終點"
                }
                alertSecondary.visibility = View.GONE
                setAlertProgress(s.intervalProgressFraction, zoneColor)
                return
            }

            alertCard.visibility = View.GONE
            alertDrBadge.visibility = View.GONE
            alertProgressTrack.visibility = View.GONE
            idleChip.visibility = View.VISIBLE
            val isPro = svc?.adManager?.isProMember == true
            idleChip.text = if (isPro) {
                "✓ 路況良好・👑 Pro 自動同步 (${s.totalCamerasCount} 測速 / ${s.totalActiveEventsCount} 路況)"
            } else {
                "✓ 路況良好・🎬 點此看短片更新圖資 (${s.totalCamerasCount} 測速 / ${s.totalActiveEventsCount} 路況)"
            }
            return
        }
        idleChip.visibility = View.GONE
        alertCard.visibility = View.VISIBLE

        if (showEvent && ev != null) {
            val e = ev.event
            val c = sevColor(e.severity)
            alertDrBadge.visibility = View.GONE
            alertCardBg.setStroke(dp(2), color(c))
            alertIcon.text = sevGlyph(e.severity)
            alertIcon.setTextColor(Color.WHITE)
            alertIcon.background = rounded(c, 14)
            alertDist.text = fmtDist(ev.distanceMeters)
            alertType.text = sevLabel(e.severity)
            alertType.setTextColor(color(c))
            alertTitle.text = "${e.roadName} ${e.direction}".trim()
            alertDesc.text = e.rawComment
            alertCard.setOnClickListener { showEventDialog(e) }
            if (cam != null) {
                alertSecondary.visibility = View.VISIBLE
                alertSecondary.text = "接著：${fmtDist(cam.distanceMeters)} ${cam.camera.type.label}・速限 ${cam.camera.speedLimit}"
            } else alertSecondary.visibility = View.GONE
            if (ev.distanceMeters <= 1500.0) {
                setAlertProgress(((1500.0 - ev.distanceMeters) / 1500.0).toFloat(), c)
            } else {
                alertProgressTrack.visibility = View.GONE
            }
        } else if (cam != null) {
            val c = cam.camera
            val isHotspot = c.type == CameraType.HOTSPOT
            val hot = when {
                s.isOverspeed -> cRed
                isHotspot -> "#EA580C"
                else -> "#60A5FA"
            }
            alertDrBadge.visibility = if (s.isDeadReckoning) View.VISIBLE else View.GONE
            alertCardBg.setStroke(dp(2), color(hot))
            alertIcon.text = "${c.speedLimit}"
            alertIcon.setTextColor(if (isHotspot) color("#C2410C") else color("#111111"))
            val iconBorder = if (isHotspot) "#EA580C" else "#E5383B"
            val iconBg = if (isHotspot) "#FFF7ED" else "#FFFFFF"
            alertIcon.background = oval(iconBg, iconBorder, 5)
            alertDist.text = fmtDist(cam.distanceMeters)
            alertType.text = if (s.isOverspeed) "超速！請減速" else c.type.label
            alertType.setTextColor(color(hot))
            alertTitle.text = if (s.inIntervalZone) "區間平均 ${s.intervalAvgSpeedKmh} km/h" else "速限 ${c.speedLimit} 公里"
            alertDesc.text = "${c.city}${c.region} ${c.address}"
            alertCard.setOnClickListener {
                if (mapReady) webView.evaluateJavascript("window.focusPoint(${c.lat},${c.lng});", null)
            }
            if (ev != null) {
                alertSecondary.visibility = View.VISIBLE
                alertSecondary.text = "接著：${fmtDist(ev.distanceMeters)} ${sevLabel(ev.event.severity)}・${ev.event.roadName}"
            } else alertSecondary.visibility = View.GONE
            if (s.inIntervalZone) {
                setAlertProgress(s.intervalProgressFraction, if (s.isOverspeed) cRed else "#A855F7")
            } else {
                val farDist = CameraAlertPolicy.thresholds(c, s.speedKmh.coerceAtLeast(60)).farMeters.coerceAtLeast(300.0)
                val camProgress = (1.0 - (cam.distanceMeters / farDist)).toFloat()
                setAlertProgress(camProgress, if (s.isOverspeed) cRed else if (isHotspot) "#EA580C" else "#00F5D4")
            }
        }
    }

    private fun pushMap(s: RadarSnapshot, force: Boolean = false) {
        if (!mapReady) return
        try {
            val hotCam = s.nearestCamera?.camera?.id
            val hotEv = s.nearestRoadEvent?.event?.uid
            val camerasSource = if (s.mapVisibleCameras.isNotEmpty()) s.mapVisibleCameras else s.nearbyCameras.map { it.camera }
            val eventsSource = if (s.mapVisibleEvents.isNotEmpty()) s.mapVisibleEvents else s.nearbyEvents.map { it.event }
            val sig = String.format(
                Locale.US,
                "%.5f,%.5f,%d,%d,%s,%s,%d,%d",
                s.lat,
                s.lng,
                s.bearingDeg.roundToInt(),
                s.speedKmh,
                hotCam ?: "",
                hotEv ?: "",
                camerasSource.size,
                eventsSource.size
            )
            if (!force && sig == lastMapSignature) return
            lastMapSignature = sig

            val cams = JSONArray()
            camerasSource.forEach { c ->
                val hot = c.id == hotCam
                cams.put(JSONObject().apply {
                    put("id", c.id); put("lat", c.lat); put("lng", c.lng); put("limit", c.speedLimit)
                    put("type", c.type.label); put("address", c.address); put("direct", c.directText)
                    put("interval", c.type == CameraType.INTERVAL)
                    put("hotspot", c.type == CameraType.HOTSPOT)
                    put("hot", hot); put("sig", "${c.speedLimit}-$hot-${c.type.name}")
                })
            }
            val evs = JSONArray()
            eventsSource.forEach { e ->
                val hot = e.uid == hotEv
                evs.put(JSONObject().apply {
                    put("id", e.uid); put("lat", e.lat); put("lng", e.lng); put("severity", e.severity.name)
                    put("label", sevLabel(e.severity)); put("road", e.roadName); put("comment", e.rawComment.take(90))
                    put("schedule", e.scheduleNote); put("hot", hot); put("sig", "${e.severity}-$hot")
                })
            }
            val p = JSONObject().apply {
                put("lat", s.lat); put("lng", s.lng); put("bearing", s.bearingDeg.toDouble()); put("speed", s.speedKmh)
                put("cameras", cams); put("events", evs)
            }
            webView.evaluateJavascript("window.updateRadarMap($p);", null)
        } catch (_: Exception) {
        }
    }

    // =====================================================================
    // 動作
    // =====================================================================
    private fun applyMapMode() {
        val night = isNightNow()
        if (mapReady) webView.evaluateJavascript("window.setNight($night);", null)
        fabNight.text = when (mapModePref) { 1 -> "☀️"; 2 -> "🌙"; else -> "🌗" }
    }

    private fun cycleMapMode() {
        mapModePref = (mapModePref + 1) % 3
        applyMapMode()
        Toast.makeText(this, when (mapModePref) { 1 -> "地圖：白天"; 2 -> "地圖：夜間"; else -> "地圖：依時間自動" }, Toast.LENGTH_SHORT).show()
    }

    private fun toggleVoice() {
        val v = svc?.voiceEngine ?: return
        v.isVoiceEnabled = !v.isVoiceEnabled
        styleVoiceFab()
        Toast.makeText(this, if (v.isVoiceEnabled) "語音提醒：開" else "語音提醒：關（仍會顯示畫面提醒）", Toast.LENGTH_SHORT).show()
    }

    private fun styleVoiceFab() {
        val on = svc?.voiceEngine?.isVoiceEnabled ?: true
        fabVoice.text = if (on) "🔊" else "🔇"
        fabVoice.background = if (on) oval("#F2111827", cLine, 1) else oval("#7F1D1D", "#FCA5A5", 1)
    }

    private fun styleRecenterFab() {
        fabRecenter.setTextColor(color(if (following) cSub else "#FFFFFF"))
        fabRecenter.background = if (following) oval("#F2111827", cLine, 1) else oval(cBlue, "#93C5FD", 1)
    }

    private fun startDriving() {
        if (!hasLocationPermission()) {
            requestPermissionsIfNeeded()
            return
        }
        val s = svc ?: return
        if (s.isDrivingActive) {
            s.stopDrivingMode()
            Toast.makeText(this, "已結束行車，進入待命靜音模式", Toast.LENGTH_SHORT).show()
            return
        }

        val options = arrayOf(
            "🛡️ 啟動本機雷達提醒 (維持在此畫面)",
            "🗺️ 搭配 Google Maps (啟動懸浮窗提醒)"
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("開始行車")
            .setItems(options) { _, which ->
                if (which == 1) {
                    launchWithOverlay(null)
                } else {
                    s.startDrivingMode()
                    Toast.makeText(this, "行車模式已啟動：開始測速與路況預警", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 開啟懸浮提醒後切到 Google Maps；uri 為 null 時直接開 Google Maps 主畫面 */
    private fun launchWithOverlay(uri: String?) {
        svc?.startDrivingMode()
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("需要「顯示在其他應用程式上層」")
                .setMessage("開啟後，時速、速限和前方施工提醒會浮在 Google Maps 上面。")
                .setPositiveButton("去開啟") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
                .setNegativeButton("只用語音") { _, _ -> openGoogleMaps(uri) }
                .show()
            return
        }
        svc?.showFloatingOverlay()
        openGoogleMaps(uri)
    }

    private fun openGoogleMaps(uri: String?) {
        try {
            val intent = if (uri == null) {
                packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
                    ?: Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply { setPackage("com.google.android.apps.maps") }
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri ?: "geo:0,0?q=")))
            } catch (_: Exception) {
                Toast.makeText(this, "找不到 Google Maps", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showEventDialog(e: RoadEvent) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("${sevGlyph(e.severity)} ${sevLabel(e.severity)}・${e.roadName}")
            .setMessage(
                "${e.rawComment}\n\n" +
                        "方向：${e.direction.ifBlank { "未註明" }}\n" +
                        "狀態：${e.scheduleNote}\n" +
                        "來源：${e.sourceDetail}（${e.happenTime}）"
            )
            .setPositiveButton("在 Google Maps 查看") { _, _ ->
                openGoogleMaps("geo:${e.lat},${e.lng}?q=${e.lat},${e.lng}(${Uri.encode(e.roadName)})")
            }
            .setNeutralButton("唸給我聽") { _, _ -> svc?.voiceEngine?.speakNow(e.voiceSummary) }
            .setNegativeButton("關閉", null)
            .show()
    }

    inner class MapBridge {
        @JavascriptInterface
        fun onFollowChanged(v: String) {
            runOnUiThread {
                following = v == "true"
                styleRecenterFab()
            }
        }
    }
}
