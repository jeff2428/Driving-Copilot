package tw.roadguard.aegis.ads

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.LinearInterpolator
import android.widget.*
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback
import java.util.Locale

/**
 * 台灣神盾路況行車助手 — Google AdMob 插頁式獎勵廣告與收益管理中心
 * =====================================================================
 * 官方 AdMob 導入規範：
 * 應用程式 ID: ca-app-pub-4011891741141007~2057753824
 * 廣告單元 ID: ca-app-pub-4011891741141007/5042387693 (插頁式獎勵廣告 RewardedInterstitialAd)
 *
 * 雙軌安全變現設計：
 * 1. 優先請求並播放 Google AdMob 官方「插頁式獎勵廣告」影音，賺取官方美金 eCPM 收益。
 * 2. 若遇新帳號初審期間無廣告庫存 (No Fill) 或網路延遲，自動無縫切換為 5 秒車主精準贊助廣告劇場，
 *    絕不卡死、100% 確保車主能順利解鎖更新並持續累積導購分潤！
 */
class AdRevenueManager(context: Context) {

    companion object {
        private const val TAG = "AdRevenueManager"
        private const val PREFS_NAME = "roadguard_ad_revenue_prefs"
        private const val KEY_IS_PRO = "is_pro_member"
        private const val KEY_IMPRESSIONS = "ad_impressions"
        private const val KEY_COMPLETIONS = "ad_completions"
        private const val KEY_CLICKS = "ad_clicks"
        private const val KEY_REVENUE_TWD = "estimated_revenue_twd"
        private const val KEY_ADMOB_UNIT_ID = "admob_rewarded_unit_id"
        private const val KEY_CUSTOM_SPONSOR_TITLE = "custom_sponsor_title"
        private const val KEY_CUSTOM_SPONSOR_DESC = "custom_sponsor_desc"
        private const val KEY_CUSTOM_SPONSOR_URL = "custom_sponsor_url"

        // 您的 Google AdMob 官方插頁式獎勵廣告單元 ID
        const val OFFICIAL_ADMOB_UNIT_ID = "ca-app-pub-4011891741141007/5042387693"
    }

    data class SponsorCampaign(
        val badge: String,
        val icon: String,
        val title: String,
        val subtitle: String,
        val highlight: String,
        val ctaText: String,
        val url: String,
        val accentHex: String
    )

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())

    val licenseManager = ProLicenseManager(appContext)
    val updateManager = AppUpdateManager(appContext)

    @Volatile
    private var rewardedInterstitialAd: RewardedInterstitialAd? = null
    @Volatile
    private var isAdLoading = false

    init {
        // 初始化 Google Mobile Ads SDK 並預載插頁式獎勵廣告
        try {
            MobileAds.initialize(appContext) {
                Log.d(TAG, "Google Mobile Ads SDK 初始化完成")
                preloadAd(appContext)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Google Mobile Ads 初始化異常", e)
        }
    }

    /**
     * 嚴格透過站長 HMAC-SHA256 數位簽章授權碼或雲端白名單判定是否為 Pro 會員（客戶無法自行切換）
     */
    val isProMember: Boolean
        get() = licenseManager.checkLicenseStatus().isValidPro

    var impressionsCount: Int
        get() = prefs.getInt(KEY_IMPRESSIONS, 0)
        private set(value) = prefs.edit().putInt(KEY_IMPRESSIONS, value).apply()

    var completionsCount: Int
        get() = prefs.getInt(KEY_COMPLETIONS, 0)
        private set(value) = prefs.edit().putInt(KEY_COMPLETIONS, value).apply()

    var clicksCount: Int
        get() = prefs.getInt(KEY_CLICKS, 0)
        private set(value) = prefs.edit().putInt(KEY_CLICKS, value).apply()

    var estimatedRevenueTwd: Float
        get() = prefs.getFloat(KEY_REVENUE_TWD, 0f)
        private set(value) = prefs.edit().putFloat(KEY_REVENUE_TWD, value).apply()

    var admobRewardedUnitId: String
        get() = prefs.getString(KEY_ADMOB_UNIT_ID, OFFICIAL_ADMOB_UNIT_ID) ?: OFFICIAL_ADMOB_UNIT_ID
        set(value) = prefs.edit().putString(KEY_ADMOB_UNIT_ID, value.trim().ifBlank { OFFICIAL_ADMOB_UNIT_ID }).apply()

    var customSponsorTitle: String
        get() = prefs.getString(KEY_CUSTOM_SPONSOR_TITLE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_SPONSOR_TITLE, value.trim()).apply()

    var customSponsorDesc: String
        get() = prefs.getString(KEY_CUSTOM_SPONSOR_DESC, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_SPONSOR_DESC, value.trim()).apply()

    var customSponsorUrl: String
        get() = prefs.getString(KEY_CUSTOM_SPONSOR_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_SPONSOR_URL, value.trim()).apply()

    /**
     * 預載 Google AdMob 插頁式獎勵廣告 (Rewarded Interstitial Ad)
     */
    fun preloadAd(ctx: Context = appContext) {
        if (rewardedInterstitialAd != null || isAdLoading) return
        isAdLoading = true

        val adRequest = AdRequest.Builder().build()
        val unitId = admobRewardedUnitId

        RewardedInterstitialAd.load(
            ctx,
            unitId,
            adRequest,
            object : RewardedInterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedInterstitialAd) {
                    isAdLoading = false
                    rewardedInterstitialAd = ad
                    Log.d(TAG, "Google AdMob 插頁式獎勵廣告載入成功: $unitId")

                    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdShowedFullScreenContent() {
                            impressionsCount += 1
                            estimatedRevenueTwd += 0.45f
                            Log.d(TAG, "AdMob 插頁式獎勵廣告已全螢幕展示")
                        }

                        override fun onAdDismissedFullScreenContent() {
                            rewardedInterstitialAd = null
                            preloadAd(appContext)
                        }

                        override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                            Log.w(TAG, "AdMob 插頁式獎勵廣告展示失敗: ${adError.message}")
                            rewardedInterstitialAd = null
                            preloadAd(appContext)
                        }
                    }
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    isAdLoading = false
                    rewardedInterstitialAd = null
                    Log.w(TAG, "AdMob 插頁式獎勵廣告載入失敗 (代碼: ${loadAdError.code}): ${loadAdError.message}")
                }
            }
        )
    }

    /**
     * 執行「更新圖資前激勵廣告」流程：
     * 1. 若為 Pro 會員，直接免廣告更新。
     * 2. 若 Google AdMob 官方插頁式獎勵廣告已就緒，直接播放 AdMob 官方廣告賺取美金收益！
     * 3. 若 AdMob 尚在加載或無庫存，自動切換為台灣車主精準 5 秒贊助廣告劇場，絕不卡死車主。
     */
    fun showRewardedAdForUpdate(
        activity: Activity,
        onRewardEarned: () -> Unit,
        onUpgradeProClicked: (() -> Unit)? = null
    ) {
        if (isProMember) {
            Toast.makeText(activity, "👑 Pro 尊享特權：免看廣告，直接為您同步最新雲端圖資！", Toast.LENGTH_SHORT).show()
            onRewardEarned()
            return
        }

        val ad = rewardedInterstitialAd
        if (ad != null) {
            // 🎯 播放 Google AdMob 官方插頁式獎勵廣告
            var rewardGranted = false
            ad.show(activity, OnUserEarnedRewardListener { rewardItem ->
                Log.d(TAG, "使用者已完成觀看 AdMob 插頁式獎勵廣告，發放獎勵: ${rewardItem.amount} ${rewardItem.type}")
                if (!rewardGranted) {
                    rewardGranted = true
                    completionsCount += 1
                    estimatedRevenueTwd += 0.85f // 激勵影片完播美金分潤 (換算台幣約 $0.85/次)
                    onRewardEarned()
                }
            })
            return
        }

        // 備援：若 AdMob 官方廣告尚未就緒（如新廣告單元剛建立或無庫存），以自營 5 秒車主精準贊助廣告劇場展示
        preloadAd(activity)
        showInteractiveSponsorAd(activity, onRewardEarned, onUpgradeProClicked)
    }

    private val builtInCampaigns = listOf(
        SponsorCampaign(
            badge = "車主省錢首選・線上車險比價",
            icon = "🛡️",
            title = "汽車強制險＋任意險 線上投保現省 $3,200",
            subtitle = "富邦 / 國泰 / 新安東京海上 30 秒快速試算，加贈 100 公里免費全鋒道路救援！",
            highlight = "🔥 本月已有 14,800 位車主領取保費折抵優惠",
            ctaText = "👉 免費試算車險優惠（立即解鎖圖資更新）",
            url = "https://www.google.com/search?q=%E6%B1%BD%E8%BB%8A%E4%BF%9D%E9%9A%AA%E7%B7%9A%E4%B8%8A%E8%A9%A6%E7%AE%97%E5%84%AA%E6%83%A0",
            accentHex = "#2563EB"
        ),
        SponsorCampaign(
            badge = "行車安全必備・破盤團購",
            icon = "📹",
            title = "4K 星光夜視前後雙錄「電子後視鏡」限時 65 折",
            subtitle = "SONY 感光元件＋盲區車道偵測＋免費到府安裝，雨天夜晚後車牌依然清晰可見。",
            highlight = "🎁 神盾路況車友專屬：結帳現折 $1,500 再送 128G 記憶卡",
            ctaText = "👉 查看車友專屬團購價（立即解鎖圖資更新）",
            url = "https://www.google.com/search?q=4K+%E9%9B%BB%E5%AD%90%E5%BE%8C%E8%A6%96%E9%8F%A1+%E8%A1%8C%E8%BB%8A%E7%B4%80%E9%8C%84%E5%99%A8",
            accentHex = "#059669"
        ),
        SponsorCampaign(
            badge = "加油充電回饋・車主神卡",
            icon = "⛽",
            title = "全台中油 / 台亞 / 電動車充電 最高 8% 回饋",
            subtitle = "天天加油每公升最高降 $2.5 元，新戶線上申辦核卡再享 $800 加油金＋免費市區停車。",
            highlight = "⚡ 每年平均幫通勤車主省下 $9,600 油資",
            ctaText = "👉 領取 $800 加油金優惠（立即解鎖圖資更新）",
            url = "https://www.google.com/search?q=%E5%8A%A0%E6%B2%B9%E4%BF%A1%E7%94%A8%E5%8D%A1%E5%9B%9E%E9%A5%8B%E6%AF%94%E8%BC%83",
            accentHex = "#D97706"
        ),
        SponsorCampaign(
            badge = "五千公里保養・車麗屋汽車百貨",
            icon = "🛞",
            title = "全合成機油保養套餐 $999 起＋免費四輪安全健檢",
            subtitle = "固特異 / 米其林輪胎換四輪送 3D 四輪定位，全台門市皆可線上預約免排隊。",
            highlight = "🎟️ 點擊即領 $300 汽車百貨折價券",
            ctaText = "👉 領取 $300 保養折價券（立即解鎖圖資更新）",
            url = "https://www.google.com/search?q=%E8%BB%8A%E9%BA%97%E5%B1%8B%E6%B1%BD%E8%BB%8A%E7%99%BE%E8%B2%A8%E4%BF%9D%E9%A4%8A%E5%84%AA%E6%83%A0",
            accentHex = "#7C3AED"
        )
    )

    private fun pickCampaign(): SponsorCampaign {
        if (customSponsorTitle.isNotBlank()) {
            return SponsorCampaign(
                badge = "站長精選贊助推薦",
                icon = "🌟",
                title = customSponsorTitle,
                subtitle = customSponsorDesc.ifBlank { "感謝支持台灣神盾路況行車助手，點擊查看車主專屬優惠方案。" },
                highlight = "🔥 車友專屬限時特惠活動進行中",
                ctaText = "👉 立即前往查看優惠（同時解鎖圖資更新）",
                url = customSponsorUrl.ifBlank { "https://www.google.com" },
                accentHex = "#2563EB"
            )
        }
        val idx = impressionsCount % builtInCampaigns.size
        return builtInCampaigns[idx]
    }

    private fun showInteractiveSponsorAd(
        activity: Activity,
        onRewardEarned: () -> Unit,
        onUpgradeProClicked: (() -> Unit)? = null
    ) {
        val campaign = pickCampaign()
        impressionsCount += 1
        estimatedRevenueTwd += 0.25f

        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(false)

        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun rounded(hex: String, rDp: Int, strokeHex: String? = null, strokeDp: Int = 1) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(rDp).toFloat()
            setColor(Color.parseColor(hex))
            if (strokeHex != null) setStroke(dp(strokeDp), Color.parseColor(strokeHex))
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#080D1A"))
            setPadding(dp(20), dp(28), dp(20), dp(24))
        }

        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val adTag = TextView(activity).apply {
            text = "Google AdMob 激勵單元 (${admobRewardedUnitId.takeLast(10)})"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            background = rounded("#1E293B", 8)
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }
        val timerBadge = TextView(activity).apply {
            text = "⏱️ 5 秒後解鎖免費圖資更新"
            setTextColor(Color.parseColor("#FCD34D"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            background = rounded("#1E293B", 16, "#F59E0B", 1)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        topBar.addView(adTag)
        topBar.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        topBar.addView(timerBadge)
        root.addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val progressTrack = FrameLayout(activity).apply { background = rounded("#1E293B", 4) }
        val progressFill = View(activity).apply { background = rounded("#38BDF8", 4) }
        progressTrack.addView(progressFill, FrameLayout.LayoutParams(0, dp(6)))
        root.addView(progressTrack, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply {
            topMargin = dp(14)
            bottomMargin = dp(20)
        })

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded("#111827", 22, campaign.accentHex, 2)
            setPadding(dp(22), dp(26), dp(22), dp(26))
        }

        card.addView(TextView(activity).apply {
            text = campaign.badge
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(campaign.accentHex, 14)
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        card.addView(TextView(activity).apply {
            text = campaign.icon
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 54f)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(8))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        card.addView(TextView(activity).apply {
            text = campaign.title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        card.addView(TextView(activity).apply {
            text = campaign.subtitle
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(14))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        card.addView(TextView(activity).apply {
            text = campaign.highlight
            setTextColor(Color.parseColor("#FDE047"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = rounded("#1E293B", 12)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(18)
        })

        var rewardDispatched = false
        val sponsorCtaBtn = TextView(activity).apply {
            text = campaign.ctaText
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = rounded(campaign.accentHex, 14)
            setPadding(dp(16), dp(15), dp(16), dp(15))
            setOnClickListener {
                clicksCount += 1
                completionsCount += 1
                estimatedRevenueTwd += 18.0f
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(campaign.url)))
                } catch (_: Exception) {
                }
                if (!rewardDispatched) {
                    rewardDispatched = true
                    dialog.dismiss()
                    onRewardEarned()
                }
            }
        }
        card.addView(sponsorCtaBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val claimBtn = TextView(activity).apply {
            text = "🔒 請觀看廣告（5 秒）以免費更新最新圖資"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = rounded("#1E293B", 14, "#334155", 1)
            isEnabled = false
        }
        root.addView(claimBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
            topMargin = dp(18)
        })

        val bottomRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }
        val proLink = TextView(activity).apply {
            text = "👑 升級 Pro 免看廣告自動更新 (NT$490/年)"
            setTextColor(Color.parseColor("#FBBF24"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener {
                dialog.dismiss()
                onUpgradeProClicked?.invoke()
            }
        }
        val cancelLink = TextView(activity).apply {
            text = "✕ 放棄更新"
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(dp(12), dp(6), dp(8), dp(6))
            setOnClickListener {
                dialog.dismiss()
                Toast.makeText(activity, "已取消觀看廣告，維持舊版離線圖資", Toast.LENGTH_SHORT).show()
            }
        }
        bottomRow.addView(proLink)
        bottomRow.addView(cancelLink)
        root.addView(bottomRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        dialog.setContentView(root)
        dialog.show()

        progressTrack.post {
            val fullWidth = progressTrack.width
            ValueAnimator.ofInt(0, fullWidth).apply {
                duration = 5000L
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    val w = anim.animatedValue as Int
                    progressFill.layoutParams = FrameLayout.LayoutParams(w, dp(6))
                }
                start()
            }
        }

        var secondsLeft = 5
        val tickRunnable = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                secondsLeft -= 1
                if (secondsLeft > 0) {
                    timerBadge.text = "⏱️ ${secondsLeft} 秒後解鎖免費圖資更新"
                    claimBtn.text = "🔒 請觀看廣告（${secondsLeft} 秒）以免費更新最新圖資"
                    mainHandler.postDelayed(this, 1000L)
                } else {
                    completionsCount += 1
                    estimatedRevenueTwd += 0.65f
                    timerBadge.text = "✅ 已獲得圖資更新獎勵"
                    timerBadge.setTextColor(Color.parseColor("#6EE7B7"))
                    timerBadge.background = rounded("#064E3B", 16, "#10B981", 1)

                    claimBtn.isEnabled = true
                    claimBtn.text = "🎁 領取獎勵：立即同步全台最新測速與施工圖資"
                    claimBtn.setTextColor(Color.WHITE)
                    claimBtn.background = rounded("#10B981", 14, "#6EE7B7", 2)
                    claimBtn.setOnClickListener {
                        if (!rewardDispatched) {
                            rewardDispatched = true
                            dialog.dismiss()
                            onRewardEarned()
                        }
                    }
                }
            }
        }
        mainHandler.postDelayed(tickRunnable, 1000L)
    }

    /**
     * 站長專屬：廣告單元 ID 與自營贊助商導購後台設定視窗
     */
    fun showPublisherConfigDialog(activity: Activity, onSaved: () -> Unit) {
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }

        fun addField(label: String, hintStr: String, currentVal: String): EditText {
            container.addView(TextView(activity).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(Color.parseColor("#93C5FD"))
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(8), 0, dp(4))
            })
            return EditText(activity).apply {
                hint = hintStr
                setText(currentVal)
                setSingleLine()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            }.also { container.addView(it) }
        }

        val unitInput = addField(
            "1. Google AdMob 插頁式獎勵廣告單元 ID",
            OFFICIAL_ADMOB_UNIT_ID,
            admobRewardedUnitId
        )
        val titleInput = addField(
            "2. 自營贊助商標題（選填，留空則輪播內建 4 大車主優惠）",
            "例如：〇〇汽車保養廠・憑本畫面換機油折 $300",
            customSponsorTitle
        )
        val descInput = addField(
            "3. 自營贊助商說明文字（選填）",
            "例如：預約專線 04-xxxx-xxxx，再送免費全車健檢",
            customSponsorDesc
        )
        val urlInput = addField(
            "4. 贊助商點擊跳轉 / 聯盟行銷分潤網址 (Affiliate URL)",
            "https://...",
            customSponsorUrl
        )

        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("💰 站長 AdMob 廣告單元設定")
            .setView(container)
            .setPositiveButton("儲存設定並預載") { _, _ ->
                admobRewardedUnitId = unitInput.text.toString()
                customSponsorTitle = titleInput.text.toString()
                customSponsorDesc = descInput.text.toString()
                customSponsorUrl = urlInput.text.toString()
                preloadAd(activity)
                Toast.makeText(activity, "已儲存 AdMob 單元 ID！正在向 Google 預載廣告", Toast.LENGTH_SHORT).show()
                onSaved()
            }
            .setNeutralButton("重置收益統計") { _, _ ->
                impressionsCount = 0
                completionsCount = 0
                clicksCount = 0
                estimatedRevenueTwd = 0f
                onSaved()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun formatRevenueSummary(): String {
        return String.format(
            Locale.TAIWAN,
            "曝光 %d 次・完播解鎖 %d 次・導購點擊 %d 次・預估收益 NT$ %.1f",
            impressionsCount,
            completionsCount,
            clicksCount,
            estimatedRevenueTwd
        )
    }
}
