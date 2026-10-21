@file:Suppress("DEPRECATION")

package tw.roadguard.aegis.service

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.util.TypedValue
import java.io.File

class FloatingOverlayViewTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Test fun threeDigitSpeedOnNarrowScreen() = verifyLayout(320, 1.0f, "850", "公尺")
    @Test fun largeSystemFontAndKilometerDistance() = verifyLayout(320, 2.0f, "1.2", "公里")
    @Test fun wideLayout() = verifyLayout(420, 1.0f, "850", "公尺")

    private fun verifyLayout(widthDp: Int, fontScale: Float, distance: String, unit: String) = onMain {
        val base = instrumentation.targetContext
        val config = Configuration(base.resources.configuration).apply { this.fontScale = fontScale }
        val context = base.createConfigurationContext(config)
        val view = FloatingOverlayView(context)
        view.currentSpeed.text = "120"
        view.speedLimit.text = "110"
        view.cameraTitle.text = "🚨 經常出沒點"
        view.cameraDistanceText.text = "前方 $distance $unit"
        view.roadAlert.text = "↗ 350m 靠右行駛前往交流道"
        val width = (widthDp * context.resources.displayMetrics.density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        assertEquals(width, view.measuredWidth)
        fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics)
        assertEquals(sp(36f), view.currentSpeed.textSize, 0.1f)
        assertEquals(sp(28f), view.speedLimit.textSize, 0.1f)
        assertEquals(sp(28f), view.cameraDistanceText.textSize, 0.1f)
        checkTextFits(view)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(base.cacheDir, "overlay-${widthDp}-${fontScale}.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun checkTextFits(view: View) {
        if (view is TextView && view.visibility == View.VISIBLE && view.text.isNotEmpty()) {
            val layout = view.layout
            assertNotNull("No text layout: ${view.text}", layout)
            assertEquals("Truncated text: ${view.text}", view.text.length, layout.getLineEnd(layout.lineCount - 1))
            assertTrue("Clipped height: ${view.text}", layout.height <= view.height - view.paddingTop - view.paddingBottom)
            for (line in 0 until layout.lineCount) {
                assertEquals("Ellipsized text: ${view.text}", 0, layout.getEllipsisCount(line))
                assertTrue("Clipped width: ${view.text}", layout.getLineWidth(line) <= view.width - view.paddingLeft - view.paddingRight + 1)
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child.visibility != View.GONE) {
                    assertTrue("Child extends outside overlay", child.right <= view.width && child.bottom <= view.height)
                    checkTextFits(child)
                }
            }
        }
    }

    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { block() } catch (t: Throwable) { failure = t } }
        failure?.let { throw it }
    }
}
