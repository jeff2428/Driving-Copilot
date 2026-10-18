package tw.roadguard.aegis.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import android.view.inputmethod.EditorInfo
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

    private enum class Panel { NONE, NEARBY, ROUTE, REPORT, SETTINGS }

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
    private var lastListBuild = 0L
    private var lastScan: RouteScanResult? = null

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
    private lateinit var alertTitle: TextView
    private lateinit var alertDesc: TextView
    private lateinit var alertSecondary: TextView
    private lateinit var speedCircle: LinearLayout
    private lateinit var speedCircleBg: GradientDrawable
    private lateinit var speedTv: TextView
    private lateinit var limitBadge: TextView
    private lateinit var fabRecenter: TextView
    private lateinit var fabVoice: TextView
    private lateinit var fabNight: TextView
    private lateinit var panelScroll: ScrollView
    private lateinit var panelHost: LinearLayout
    private lateinit var navItems: List<Pair<Panel, LinearLayout>>

    // 面板內動態區塊
    private var nearbyListHost: LinearLayout? = null
    private var nearbyFreshTv: TextView? = null
    private var routeInput: EditText? = null
    private var routeResultHost: LinearLayout? = null

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
                    githubRepoSlug = s.repository.githubRepoSlug,
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        startRadarService()
        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        svc?.adManager?.preloadAd(this)
    }

    override fun onDestroy() {
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

    private fun startRadarService() {
        val intent = Intent(this, RoadGuardService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        bindService(intent, conn, Context.BIND_AUTO_CREATE)
    }

    private fun requestPermissionsIfNeeded() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 2001)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        svc?.startGpsTracking()
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
        middle.addView(buildSpeedometer(), FrameLayout.LayoutParams(dp(104), dp(104), Gravity.START or Gravity.BOTTOM).apply {
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

        simPill = tv("🛣️ 模擬試駕中・點此停止", 12f, "#1F1300", true).apply {
            background = rounded("#FDE047", 14)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
            setOnClickListener { svc?.stopDemoSimulation() }
        }
        wrap.addView(simPill, lp(WRAP, WRAP).apply { bottomMargin = dp(8); gravity = Gravity.CENTER_HORIZONTAL })

        idleChip = tv("✓ 前方路況良好・點此看短片免費更新圖資", 13f, cText, true).apply {
            background = rounded("#E6111827", 20, cLine)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setOnClickListener { triggerRewardedCloudUpdate() }
        }
        wrap.addView(idleChip, lp(WRAP, WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL })

        alertCardBg = rounded("#F2111827", 18, cAmber, 2)
        alertCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = alertCardBg
            setPadding(dp(12), dp(12), dp(14), dp(12))
            visibility = View.GONE
            elevation = dp(6).toFloat()
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
        alertTitle = tv("", 15f, cText, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        alertDesc = tv("", 12.5f, "#CBD5E1").apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        alertSecondary = tv("", 12f, cSub, true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; visibility = View.GONE
            setPadding(0, dp(4), 0, 0)
        }
        col.addView(alertTitle)
        col.addView(alertDesc)
        col.addView(alertSecondary)
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
        for ((i, v) in listOf(fabNight, fabVoice, fabRecenter).withIndex()) {
            col.addView(v, lp(dp(48), dp(48)).apply { if (i > 0) topMargin = dp(10) })
        }
        styleRecenterFab()
        return col
    }

    private fun buildSpeedometer(): View {
        val box = FrameLayout(this)
        speedCircleBg = oval("#F2111827", cLine, 3)
        speedCircle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = speedCircleBg
            elevation = dp(6).toFloat()
        }
        speedTv = tv("0", 34f, cText, true).apply { gravity = Gravity.CENTER; includeFontPadding = false }
        speedCircle.addView(speedTv)
        speedCircle.addView(tv("km/h", 11f, cSub).apply { gravity = Gravity.CENTER })
        box.addView(speedCircle, FrameLayout.LayoutParams(dp(92), dp(92), Gravity.BOTTOM or Gravity.START))

        limitBadge = tv("60", 15f, "#111111", true).apply {
            gravity = Gravity.CENTER
            background = oval("#FFFFFF", "#E5383B", 4)
            elevation = dp(8).toFloat()
            visibility = View.GONE
        }
        box.addView(limitBadge, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.TOP or Gravity.END))
        return box
    }

    private fun buildBottomSheet(): View {
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(color(cBg))
                val r = dp(22).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
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

        val search = tv("🔍  要去哪裡？出發前先掃描施工和測速", 14f, cSub).apply {
            background = rounded(cField, 14)
            setPadding(dp(14), dp(13), dp(14), dp(13))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setOnClickListener {
                openPanel(Panel.ROUTE)
                routeInput?.requestFocus()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(routeInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        sheet.addView(search, lp(MATCH, WRAP))

        val start = tv("▶  開始行車　懸浮提醒＋Google Maps", 16f, "#FFFFFF", true).apply {
            gravity = Gravity.CENTER
            background = rounded(cBlue, 14)
            setOnClickListener { startDriving() }
        }
        sheet.addView(start, lp(MATCH, dp(54)).apply { topMargin = dp(10) })

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(2))
        }
        val defs = listOf(
            Triple(Panel.NEARBY, "📍", "附近"),
            Triple(Panel.ROUTE, "🛣️", "路線"),
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
        routeInput = null
        routeResultHost = null

        when (target) {
            Panel.NONE -> {}
            Panel.NEARBY -> buildNearbyPanel()
            Panel.ROUTE -> buildRoutePanel()
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
            background = if (iconFill == "CAM") oval("#FFFFFF", "#E5383B", 3) else rounded(iconFill, 10)
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
        return listRow(
            "${c.speedLimit}", "CAM", "#111111",
            "${c.type.label}・速限 ${c.speedLimit}",
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

    private fun renderNearbyList(s: RadarSnapshot) {
        val host = nearbyListHost ?: return
        nearbyFreshTv?.text = "路況更新 ${s.lastUpdatedTime}・測速題庫 ${svc?.repository?.lastCameraSyncDateStr ?: "--"}"
        host.removeAllViews()

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
    private fun buildRoutePanel() {
        panelHost.addView(sectionTitle("出發前路線掃描", "幫你找出沿路的封閉、施工和測速，有封路會建議繞道"))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val input = EditText(this).apply {
            hint = "輸入目的地，例如：台中歌劇院"
            setHintTextColor(color("#64748B"))
            setTextColor(color(cText))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = rounded(cField, 12)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSingleLine()
            setOnEditorActionListener { v, _, _ -> runScan(v.text.toString()); true }
        }
        routeInput = input
        row.addView(input, lp(0, WRAP, 1f))
        row.addView(tv("掃描", 14f, "#FFFFFF", true).apply {
            gravity = Gravity.CENTER
            background = rounded(cBlue, 12)
            setOnClickListener { runScan(input.text.toString()) }
        }, lp(dp(72), dp(48)).apply { leftMargin = dp(8) })
        panelHost.addView(row, lp(MATCH, WRAP))

        val presetScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setPadding(0, dp(10), 0, dp(4)) }
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("台中歌劇院", "逢甲夜市", "台中高鐵", "鹿港老街", "桃園機場", "台北101", "高雄左營", "日月潭").forEach { p ->
            presets.addView(tv(p, 12.5f, "#CBD5E1").apply {
                background = rounded(cField, 16)
                setPadding(dp(12), dp(7), dp(12), dp(7))
                setOnClickListener { input.setText(p); runScan(p) }
            }, lp(WRAP, WRAP).apply { rightMargin = dp(6) })
        }
        presetScroll.addView(presets)
        panelHost.addView(presetScroll)

        routeResultHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
        panelHost.addView(routeResultHost)
        lastScan?.let { renderScan(it) }
    }

    private fun runScan(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        hideKeyboard()
        val host = routeResultHost ?: return
        host.removeAllViews()
        host.addView(tv("正在規劃「$q」並掃描沿線…", 13f, cSub).apply { setPadding(0, dp(12), 0, dp(12)) })
        val lat = svc?.currentLat ?: 24.1605
        val lng = svc?.currentLng ?: 120.6311
        Thread {
            val res = svc?.repository?.scanRouteCorridor(q, lat, lng)
            runOnUiThread {
                if (res == null) {
                    routeResultHost?.let {
                        it.removeAllViews()
                        it.addView(tv("找不到「$q」，請換個更明確的地名或路名", 13f, "#FCA5A5").apply { setPadding(0, dp(12), 0, dp(12)) })
                    }
                } else {
                    lastScan = res
                    renderScan(res)
                }
            }
        }.start()
    }

    private fun statTile(n: Int, label: String, hex: String): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(cField, 12)
            setPadding(0, dp(10), 0, dp(10))
        }
        col.addView(tv("$n", 22f, if (n > 0) hex else cSub, true).apply { gravity = Gravity.CENTER })
        col.addView(tv(label, 12f, cSub).apply { gravity = Gravity.CENTER })
        return col
    }

    private fun renderScan(res: RouteScanResult) {
        val host = routeResultHost ?: return
        host.removeAllViews()

        if (mapReady) {
            val pts = JSONArray()
            res.routePoints.forEach { pts.put(JSONArray().put(it.first).put(it.second)) }
            webView.evaluateJavascript("window.drawScannedRoute(${JSONObject().put("points", pts)});", null)
        }

        host.addView(tv("${res.destinationName}", 16f, cText, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        host.addView(tv(String.format(Locale.TAIWAN, "約 %.1f 公里・%d 分鐘", res.distanceKm, res.durationMin), 12.5f, cSub)
            .apply { setPadding(0, dp(2), 0, dp(10)) })

        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        stats.addView(statTile(res.closuresOnRoute.size, "封閉", cRed), lp(0, WRAP, 1f))
        stats.addView(statTile(res.constructionsOnRoute.size, "施工", cAmber), lp(0, WRAP, 1f).apply { leftMargin = dp(8) })
        stats.addView(statTile(res.camerasOnRoute.size, "測速", "#60A5FA"), lp(0, WRAP, 1f).apply { leftMargin = dp(8) })
        host.addView(stats, lp(MATCH, WRAP))

        val verdict = when {
            res.closuresOnRoute.isNotEmpty() -> "沿路有 ${res.closuresOnRoute.size} 處封閉，建議走繞道路線" to "#FCA5A5"
            res.constructionsOnRoute.isNotEmpty() -> "沿路有施工，可能車道縮減，請預留時間" to "#FCD34D"
            else -> "沿路沒有封閉或施工 👍" to "#6EE7B7"
        }
        host.addView(tv(verdict.first, 13f, verdict.second, true).apply { setPadding(0, dp(10), 0, dp(10)) })

        val wp = res.bypassWaypoint
        if (wp != null) {
            host.addView(tv("避開封閉，繞道導航", 15f, "#FFFFFF", true).apply {
                gravity = Gravity.CENTER
                background = rounded(cRed, 12)
                setOnClickListener {
                    launchWithOverlay(
                        "https://www.google.com/maps/dir/?api=1&origin=${res.originLat},${res.originLng}" +
                                "&destination=${res.destLat},${res.destLng}&waypoints=${wp.first},${wp.second}&travelmode=driving"
                    )
                }
            }, lp(MATCH, dp(48)).apply { bottomMargin = dp(8) })
        }
        host.addView(tv(if (wp != null) "照原路線導航" else "開始導航", 15f, "#FFFFFF", true).apply {
            gravity = Gravity.CENTER
            background = if (wp != null) rounded(cField, 12, cLine) else rounded(cBlue, 12)
            setOnClickListener { launchWithOverlay("google.navigation:q=${res.destLat},${res.destLng}&mode=d") }
        }, lp(MATCH, dp(48)).apply { bottomMargin = dp(12) })

        (res.closuresOnRoute + res.constructionsOnRoute + res.hazardsOnRoute).take(10).forEach { host.addView(eventRow(it)) }
        res.camerasOnRoute.take(8).forEach { host.addView(cameraRow(it)) }
    }

    // ---- 回報 ----
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
        val techCnt = cams.count { it.type == CameraType.TECH }
        val closureCnt = evs.count { !it.isCleared && it.severity == EventSeverity.CLOSURE }
        val constrCnt = evs.count { !it.isCleared && it.severity == EventSeverity.CONSTRUCTION }

        val verHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        verHeader.addView(tv("☁️ 圖資版本 v${repo?.dataVersionStr ?: "2026.10.18"}", 15f, "#38BDF8", true))
        verHeader.addView(tv("通道：${repo?.lastSyncChannelStr ?: "內建題庫"}・更新：${repo?.lastCameraSyncDateStr ?: "--"}", 12f, cSub).apply {
            setPadding(0, dp(2), 0, dp(6))
        })
        verHeader.addView(tv("📸 測速 ${cams.size} 支（區間 $intervalCnt・科技執法 $techCnt）", 12.5f, cText))
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

        // 👑 客戶付費 Pro 會員開通區（需輸入站長核發之 HMAC-SHA256 授權碼，無法自行免費切換）
        val devCode = licMgr?.deviceCode ?: "RG-0000-0000"
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
                githubRepoSlug = repo?.githubRepoSlug ?: "",
                manualCheck = true,
                proLicenseManager = licMgr
            )
        })
        panelHost.addView(gCloud, lp(MATCH, WRAP).apply { bottomMargin = dp(12) })

        val g1 = settingsGroup()
        val tol = s?.voiceEngine?.overspeedToleranceKmh ?: 10
        g1.addView(labeledChips("超速提醒寬限", listOf("不寬限", "+5", "+10"), listOf(0, 5, 10).indexOf(tol).coerceAtLeast(0)) { i ->
            svc?.voiceEngine?.overspeedToleranceKmh = listOf(0, 5, 10)[i]
            openPanel(Panel.NONE); openPanel(Panel.SETTINGS)
        })
        g1.addView(divider(), lp(MATCH, 1))
        g1.addView(switchRow("只提醒前方同方向", "過濾對向車道與後方的施工、測速", s?.forwardConeOnly ?: true) {
            svc?.forwardConeOnly = it; svc?.recomputeRadarState()
        })
        g1.addView(divider(), lp(MATCH, 1))
        g1.addView(switchRow("通過測速後「叮咚」", "提醒你已經通過測速點", s?.voiceEngine?.isPassChimeEnabled ?: true) {
            svc?.voiceEngine?.isPassChimeEnabled = it
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
        g3.addView(actionRow(if (s?.isSimulating == true) "停止模擬試駕" else "模擬試駕", "在室內體驗測速、超速、施工封閉提醒") {
            val sv = svc ?: return@actionRow
            if (sv.isSimulating) sv.stopDemoSimulation() else sv.startDemoSimulation(0)
            openPanel(Panel.NONE)
        })
        g3.addView(divider(), lp(MATCH, 1))
        // 🔐 站長專屬隱藏後台（需輸入站長密碼，一般客戶無法進入）
        g3.addView(actionRow(
            "🔐 站長管理後台（Pro 發卡機・廣告收益・雲端設定）",
            "僅限站長憑密碼進入核發客戶 Pro 開通碼與檢視廣告收益"
        ) {
            licMgr?.showAdminLoginDialog(this) {
                showOwnerAdminConsoleDialog()
            }
        })
        panelHost.addView(g3, lp(MATCH, WRAP).apply { bottomMargin = dp(12) })

        panelHost.addView(tv(
            "資料來源：內政部警政署「測速執法設置點」、警察廣播電臺「即時路況」、內政部國土測繪中心「臺灣通用電子地圖」，依政府資料開放授權條款使用。提醒僅供參考，請依現場標誌與交通規則駕駛。",
            11f, "#64748B"
        ))
    }

    /**
     * 站長通過密碼驗證後開啟的「站長全功能管理中心」
     */
    private fun showOwnerAdminConsoleDialog() {
        val s = svc ?: return
        val adMgr = s.adManager
        val licMgr = adMgr.licenseManager
        val curSlug = s.repository.githubRepoSlug.ifBlank { "尚未綁定（直連政府API）" }

        val options = arrayOf(
            "🔑 1. 開啟「隨身 Pro 授權碼發卡機」（幫付款客戶算開通碼）",
            "💰 2. 廣告收益統計與 AdMob / 贊助商設定\n   （${adMgr.formatRevenueSummary()}）",
            "🔗 3. 設定 GitHub 雲端圖資與 OTA 更新倉庫\n   （目前：$curSlug）",
            "🧹 4. 清除本機 Pro 授權（退回免費版測試廣告流程）"
        )

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("👑 站長專屬管理中心")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> licMgr.showAdminKeygenDialog(this) {
                        openPanel(Panel.NONE); openPanel(Panel.SETTINGS)
                    }
                    1 -> adMgr.showPublisherConfigDialog(this) {
                        openPanel(Panel.NONE); openPanel(Panel.SETTINGS)
                    }
                    2 -> showGitHubRepoDialog()
                    3 -> {
                        licMgr.clearLicense()
                        Toast.makeText(this, "已清除本機 Pro 授權，目前已切回一般免費版（看廣告更新）", Toast.LENGTH_LONG).show()
                        openPanel(Panel.NONE); openPanel(Panel.SETTINGS)
                    }
                }
            }
            .setNegativeButton("關閉", null)
            .show()
    }

    /**
     * 點擊「更新雲端圖資」時，先播放 5 秒激勵廣告（或 Pro 會員免廣告），完播獲得獎勵後才執行雲端同步
     */
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

    // =====================================================================
    // 即時渲染
    // =====================================================================
    private fun render(s: RadarSnapshot) {
        // 時速表
        speedTv.text = "${s.speedKmh}"
        if (s.isOverspeed) {
            speedTv.setTextColor(Color.WHITE)
            speedCircleBg.setColor(color(cRed))
            speedCircleBg.setStroke(dp(3), color("#FDA4AF"))
        } else {
            speedTv.setTextColor(color(cText))
            speedCircleBg.setColor(color("#F2111827"))
            speedCircleBg.setStroke(dp(3), color(cLine))
        }
        val cam = s.nearestCamera
        limitBadge.visibility = if (cam != null) View.VISIBLE else View.GONE
        cam?.let { limitBadge.text = "${it.camera.speedLimit}" }

        simPill.visibility = if (s.isSimulating) View.VISIBLE else View.GONE

        renderAlert(s)

        val now = System.currentTimeMillis()
        if (now - lastMapPush > 1000) { lastMapPush = now; pushMap(s) }
        if (panel == Panel.NEARBY && now - lastListBuild > 2500) { lastListBuild = now; renderNearbyList(s) }
    }

    private fun renderAlert(s: RadarSnapshot) {
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
            alertCard.visibility = View.GONE
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
        } else if (cam != null) {
            val c = cam.camera
            val hot = if (s.isOverspeed) cRed else "#60A5FA"
            alertCardBg.setStroke(dp(2), color(hot))
            alertIcon.text = "${c.speedLimit}"
            alertIcon.setTextColor(color("#111111"))
            alertIcon.background = oval("#FFFFFF", "#E5383B", 5)
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
        }
    }

    private fun pushMap(s: RadarSnapshot, force: Boolean = false) {
        if (!mapReady) return
        try {
            val hotCam = s.nearestCamera?.camera?.id
            val hotEv = s.nearestRoadEvent?.event?.uid
            val cams = JSONArray()
            s.nearbyCameras.take(25).forEach { sc ->
                val c = sc.camera
                val hot = c.id == hotCam
                cams.put(JSONObject().apply {
                    put("id", c.id); put("lat", c.lat); put("lng", c.lng); put("limit", c.speedLimit)
                    put("type", c.type.label); put("address", c.address); put("direct", c.directText)
                    put("interval", c.type == CameraType.INTERVAL); put("hot", hot); put("sig", "${c.speedLimit}-$hot")
                })
            }
            val evs = JSONArray()
            s.nearbyEvents.take(30).forEach { se ->
                val e = se.event
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
        val scan = lastScan
        if (scan != null) {
            launchWithOverlay("google.navigation:q=${scan.destLat},${scan.destLng}&mode=d")
        } else {
            launchWithOverlay(null)
        }
    }

    /** 開啟懸浮提醒後切到 Google Maps；uri 為 null 時直接開 Google Maps 主畫面 */
    private fun launchWithOverlay(uri: String?) {
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
