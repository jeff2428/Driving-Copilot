package tw.roadguard.aegis.service

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Reflows the same large numerals on narrow screens and with enlarged system fonts. */
internal class FloatingOverlayView(context: Context) : LinearLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Float, color: String, bold: Boolean = false) = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(Color.parseColor(color))
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

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
    val cameraDistanceText = label("", 28f, "#FFFFFF", true)
    val roadAlert = label("🚧 前方無施工封閉", 12f, "#FBBF24")
    val toggleHint = label(" ▾ ", 13f, "#94A3B8", true).apply { gravity = Gravity.CENTER }

    private val speedColumn = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(8), 0, dp(8), 0)
        addView(currentSpeed)
        addView(label("km/h", 12f, "#94A3B8").apply { gravity = Gravity.CENTER })
    }
    private val speedRow = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        addView(speedLimit)
        addView(speedColumn)
    }
    private val infoColumn = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(cameraTitle)
        addView(cameraDistanceText)
        addView(roadAlert)
    }
    private val content = LinearLayout(context).apply {
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        layoutParams = layoutParams.apply { width = preferredWidth() }
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = if (View.MeasureSpec.getMode(widthMeasureSpec) == View.MeasureSpec.UNSPECIFIED) {
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
