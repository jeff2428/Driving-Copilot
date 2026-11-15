package tw.roadguard.aegis.service

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.sin

/**
 * 系統級懸浮視窗 (Floating Overlay View)
 * 支援雙模式：
 * 1. COMPACT_SPEED（無提醒狀態）：僅顯示時速數字與 km/h 單位，超小膠囊佔位，不遮擋導航地圖。
 * 2. ALERT（警示狀態）：依據提醒種類（測速照相、區間測速、道路施工、事故封閉、車道指引）動態展開對應欄位。
 * 3. 支援通話中靜音警報之紅黃動態視覺呼吸邊框閃爍 (Visual Alert Flash)。
 */
internal class FloatingOverlayView(context: Context) : LinearLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Float, color: String, bold: Boolean = false) = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(Color.parseColor(color))
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    var currentMode: OverlayMode = OverlayMode.ALERT
        private set

    var isFlashingAlert: Boolean = false
        private set

    private var flashAnimator: ValueAnimator? = null
    private val argbEvaluator = ArgbEvaluator()

    val speedLimit = label("--", 28f, "#000000", true).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke(dp(5), Color.parseColor("#E63946"))
        }
    }
    val currentSpeed = label("0", 36f, "#00F5D4", true).apply { gravity = Gravity.CENTER }
    val cameraTitle = label("📸 測速雷達待命中", 14f, "#FFFFFF", true)
    val deadReckoningBadge = label("📡 隧道慣性推估", 11f, "#E0F2FE", true).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(Color.parseColor("#0284C7"))
        }
        setPadding(dp(7), dp(2), dp(7), dp(2))
        visibility = View.GONE
    }
    val cameraDistanceText = label("", 28f, "#FFFFFF", true)
    val roadAlert = label("🚧 前方無施工封閉", 12f, "#FBBF24")
    val toggleHint = label(" ▾ ", 13f, "#94A3B8", true).apply { gravity = Gravity.CENTER }

    private val progressFillBg = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(3).toFloat()
        setColor(Color.parseColor("#00F5D4"))
    }
    val progressFill = View(context).apply {
        background = progressFillBg
    }
    val progressTrack = LinearLayout(context).apply {
        orientation = HORIZONTAL
        weightSum = 1.0f
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(3).toFloat()
            setColor(Color.parseColor("#334155"))
        }
        addView(progressFill, LayoutParams(0, LayoutParams.MATCH_PARENT, 0f))
        visibility = View.GONE
    }

    val speedUnitLabel = label("km/h", 12f, "#94A3B8").apply { gravity = Gravity.CENTER }

    val speedColumn = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(8), 0, dp(8), 0)
        addView(currentSpeed)
        addView(speedUnitLabel)
    }
    val speedRow = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        addView(speedLimit)
        addView(speedColumn)
    }
    val infoColumn = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(cameraTitle)
        addView(deadReckoningBadge, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2)
            bottomMargin = dp(2)
        })
        addView(cameraDistanceText)
        addView(roadAlert)
        addView(progressTrack, LayoutParams(LayoutParams.MATCH_PARENT, dp(4)).apply {
            topMargin = dp(5)
        })
    }
    val content = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(speedRow)
        addView(infoColumn)
    }

    init {
        gravity = Gravity.CENTER_VERTICAL
        orientation = HORIZONTAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        layoutParams = LayoutParams(preferredWidth(), LayoutParams.WRAP_CONTENT)
        addView(content, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(toggleHint)
    }

    private fun preferredWidth() = minOf(resources.displayMetrics.widthPixels - dp(32), dp(420)).coerceAtLeast(dp(1))

    /**
     * 通話中靜音模式與超速警示之平滑玻璃呼吸閃爍效果 (Visual Flash)
     */
    fun triggerVisualAlertFlash(isUrgent: Boolean) {
        if (isFlashingAlert) return
        val previousBackground = background
        isFlashingAlert = true
        post {
            val baseStroke = Color.parseColor("#38BDF8")
            val peakStroke = if (isUrgent) Color.parseColor("#FF2E4C") else Color.parseColor("#FBBF24")
            val baseFill = Color.parseColor("#F00B132B")
            val peakFill = if (isUrgent) Color.parseColor("#EA3F0D1D") else Color.parseColor("#EA2E2208")
            startFlashAnimator(baseStroke, peakStroke, baseFill, peakFill, cycles = 5.0)
        }
        postDelayed({
            flashAnimator?.cancel()
            flashAnimator = null
            isFlashingAlert = false
            post {
                background = previousBackground
            }
        }, 2500L)
    }

    /**
     * 國道警車經常出沒點超速警戒之紅藍警燈平滑呼吸閃爍效果 (Police Red-Blue Alert Flash)
     */
    fun triggerPoliceAlertFlash() {
        if (isFlashingAlert) return
        val previousBackground = background
        isFlashingAlert = true
        post {
            val redStroke = Color.parseColor("#FF2E4C")
            val blueStroke = Color.parseColor("#1E90FF")
            val redFill = Color.parseColor("#EA3A0E1B")
            val blueFill = Color.parseColor("#EA0E2242")
            startFlashAnimator(redStroke, blueStroke, redFill, blueFill, cycles = 6.0)
        }
        postDelayed({
            flashAnimator?.cancel()
            flashAnimator = null
            isFlashingAlert = false
            post {
                background = previousBackground
            }
        }, 2500L)
    }

    private fun startFlashAnimator(
        strokeA: Int,
        strokeB: Int,
        fillA: Int,
        fillB: Int,
        cycles: Double
    ) {
        flashAnimator?.cancel()
        val drawable = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(fillA, Color.parseColor("#EB1E293B"))
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(22).toFloat()
            setStroke(dp(3), strokeA)
        }
        background = drawable
        try {
            flashAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 2500L
                interpolator = LinearInterpolator()
                addUpdateListener { va ->
                    if (!isFlashingAlert) return@addUpdateListener
                    val t = va.animatedFraction
                    val wave = ((sin(t * PI * cycles) + 1.0) * 0.5).toFloat()
                    val stroke = argbEvaluator.evaluate(wave, strokeA, strokeB) as Int
                    val fill = argbEvaluator.evaluate(wave, fillA, fillB) as Int
                    drawable.colors = intArrayOf(fill, Color.parseColor("#EB1E293B"))
                    drawable.setStroke(dp(3), stroke)
                }
                start()
            }
        } catch (_: Throwable) {
        }
    }

    private var lastConfig: OverlayDisplayConfig? = null

    fun applyConfig(config: OverlayDisplayConfig) {
        if (lastConfig == config) return
        lastConfig = config
        roadAlert.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (config.cameraTitleText == null) 20f else 14f)
        currentMode = config.mode
        currentSpeed.text = config.speedText
        if (config.isOverspeed) {
            currentSpeed.setTextColor(Color.parseColor("#FF2E4C"))
        } else {
            currentSpeed.setTextColor(Color.parseColor("#00F5D4"))
        }

        if (config.isPoliceAlert && config.isOverspeed && !isFlashingAlert) {
            triggerPoliceAlertFlash()
        }

        if (config.mode == OverlayMode.COMPACT_SPEED) {
            speedLimit.visibility = View.GONE
            infoColumn.visibility = View.GONE
            cameraTitle.visibility = View.GONE
            deadReckoningBadge.visibility = View.GONE
            cameraDistanceText.visibility = View.GONE
            roadAlert.visibility = View.GONE
            progressTrack.visibility = View.GONE
            toggleHint.visibility = View.GONE

            setPadding(dp(14), dp(8), dp(14), dp(8))
            speedColumn.setPadding(dp(4), 0, dp(4), 0)
            content.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        } else {
            infoColumn.visibility = View.VISIBLE
            toggleHint.visibility = View.VISIBLE

            if (config.speedLimitText != null) {
                speedLimit.text = config.speedLimitText
                speedLimit.visibility = View.VISIBLE
            } else {
                speedLimit.visibility = View.GONE
            }

            if (config.cameraTitleText != null) {
                cameraTitle.text = config.cameraTitleText
                cameraTitle.visibility = View.VISIBLE
            } else {
                cameraTitle.visibility = View.GONE
            }

            deadReckoningBadge.visibility = if (config.isDeadReckoning) View.VISIBLE else View.GONE

            if (config.cameraDistanceText != null) {
                cameraDistanceText.text = config.cameraDistanceText
                cameraDistanceText.visibility = View.VISIBLE
            } else {
                cameraDistanceText.visibility = View.GONE
            }

            if (config.roadAlertText != null) {
                roadAlert.text = config.roadAlertText
                roadAlert.setTextColor(Color.parseColor(config.roadAlertColorHex))
                roadAlert.visibility = View.VISIBLE
            } else {
                roadAlert.visibility = View.GONE
            }

            if (config.progressFraction > 0f) {
                progressTrack.visibility = View.VISIBLE
                val fillHex = when {
                    config.isOverspeed -> "#FF2E4C"
                    config.isPoliceAlert -> "#EA580C"
                    config.cameraTitleText?.contains("區間") == true -> "#A855F7"
                    config.cameraTitleText?.contains("雪隧") == true -> "#38BDF8"
                    else -> "#00F5D4"
                }
                progressFillBg.setColor(Color.parseColor(fillHex))
                val fillLp = progressFill.layoutParams as LayoutParams
                val targetWeight = config.progressFraction.coerceIn(0.05f, 1.0f)
                if (fillLp.weight != targetWeight) {
                    fillLp.weight = targetWeight
                    progressFill.layoutParams = fillLp
                }
            } else {
                progressTrack.visibility = View.GONE
            }

            setPadding(dp(12), dp(8), dp(12), dp(8))
            speedColumn.setPadding(dp(8), 0, dp(8), 0)
            content.layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = LayoutParams(preferredWidth(), LayoutParams.WRAP_CONTENT)
        }
        requestLayout()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (currentMode == OverlayMode.ALERT) {
            layoutParams = layoutParams.apply { width = preferredWidth() }
        }
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = View.MeasureSpec.getMode(widthMeasureSpec)
        val isExplicitWidth = widthMode == View.MeasureSpec.EXACTLY
        val isCompact = currentMode == OverlayMode.COMPACT_SPEED && !isExplicitWidth

        if (isCompact) {
            content.orientation = HORIZONTAL
            speedRow.orientation = HORIZONTAL
            speedRow.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            content.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val availableWidth = if (widthMode == View.MeasureSpec.UNSPECIFIED) {
            preferredWidth()
        } else View.MeasureSpec.getSize(widthMeasureSpec)
        val compact = availableWidth < dp(380) || resources.configuration.fontScale > 1.2f
        content.orientation = if (compact) VERTICAL else HORIZONTAL
        val signSize = maxOf(dp(64), (speedLimit.paint.measureText("120") + dp(20)).toInt())
        speedLimit.layoutParams = LayoutParams(signSize, signSize)
        val speedRowWidth = signSize + currentSpeed.paint.measureText("120") + dp(16)
        val contentWidth = availableWidth - paddingLeft - paddingRight - toggleHint.paint.measureText(" ▾ ")
        speedRow.orientation = if (compact && speedRowWidth > contentWidth) VERTICAL else HORIZONTAL
        speedRow.layoutParams = LayoutParams(if (compact) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        infoColumn.layoutParams = if (compact) {
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        } else LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
