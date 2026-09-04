package com.mystar.agent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.graphics.PixelFormat
import android.view.Gravity

/**
 * 마지막 getScreenTree() 스냅샷의 노드를 화면 위 사각형으로 표시한다.
 * DroidRun Portal의 OverlayManager 패턴을 참고한 독자 구현(AGPL 코드 미사용).
 */
data class UiBox(
    val id: String,
    val bounds: Rect,
    val label: String,
    val kind: Kind,
) {
    enum class Kind {
        TAP,
        EDIT,
        SCROLL,
        CHECK,
        GENERIC,
    }
}

class TreeBoxOverlay(private val service: AgentAccessibilityService) {

    private var overlayView: OverlayView? = null

    fun show() {
        if (overlayView != null) return
        val view = OverlayView(service)
        val dm = service.resources.displayMetrics
        val params = WindowManager.LayoutParams(
            dm.widthPixels,
            dm.heightPixels,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        try {
            val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.addView(view, params)
            overlayView = view
        } catch (e: Exception) {
            android.util.Log.e(TAG, "failed to show tree box overlay", e)
        }
    }

    fun remove() {
        val view = overlayView ?: return
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            wm.removeView(view)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "failed to remove tree box overlay", e)
        }
        overlayView = null
    }

    fun setVisible(visible: Boolean) {
        overlayView?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun update(boxes: List<UiBox>) {
        overlayView?.setBoxes(boxes)
    }

    private class OverlayView(context: Context) : View(context) {

        private var boxes: List<UiBox> = emptyList()

        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2f)
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dp(11f)
            typeface = Typeface.DEFAULT_BOLD
        }
        private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        init {
            setWillNotDraw(false)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun setBoxes(newBoxes: List<UiBox>) {
            boxes = newBoxes.sortedWith(
                compareBy<UiBox> { it.bounds.width() * it.bounds.height() }
                    .thenBy { it.id },
            )
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for (box in boxes) {
                drawBox(canvas, box)
            }
        }

        private fun drawBox(canvas: Canvas, box: UiBox) {
            val color = colorForKind(box.kind)
            boxPaint.color = color
            canvas.drawRect(box.bounds, boxPaint)

            val tag = box.id
            val textWidth = labelPaint.measureText(tag)
            val pad = dp(3f)
            val textHeight = labelPaint.textSize
            val bgLeft = box.bounds.left.toFloat()
            val bgTop = (box.bounds.top - textHeight - pad * 2).coerceAtLeast(0f)
            val bgRight = bgLeft + textWidth + pad * 2
            val bgBottom = bgTop + textHeight + pad * 2

            labelBgPaint.color = Color.argb(200, Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawRect(bgLeft, bgTop, bgRight, bgBottom, labelBgPaint)
            canvas.drawText(tag, bgLeft + pad, bgBottom - pad, labelPaint)
        }

        private fun colorForKind(kind: UiBox.Kind): Int = when (kind) {
            UiBox.Kind.TAP -> Color.parseColor("#4CAF50")
            UiBox.Kind.EDIT -> Color.parseColor("#2196F3")
            UiBox.Kind.SCROLL -> Color.parseColor("#FF9800")
            UiBox.Kind.CHECK -> Color.parseColor("#9C27B0")
            UiBox.Kind.GENERIC -> Color.parseColor("#B0BEC5")
        }

        private fun dp(value: Float): Float =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
    }

    companion object {
        private const val TAG = "TreeBoxOverlay"
    }
}
