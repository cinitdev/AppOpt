package top.suto.appopt

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.max

/** 在真实界面上绘制半透明遮罩，只保留目标区域明亮。 */
class UsageGuideOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val targetRect = RectF()
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB8000000.toInt()
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.dp.toFloat()
        color = context.getColor(R.color.brand_on_primary)
    }
    private var outlineAlpha = 1f
    private var touchThroughTarget: View? = null
    private var targetGestureStarted = false
    private var spotlightGeneration = 0
    private val pulseAnimator = ValueAnimator.ofFloat(0.45f, 1f).apply {
        duration = 850L
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            outlineAlpha = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        pulseAnimator.start()
    }

    override fun onDetachedFromWindow() {
        pulseAnimator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val mask = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            if (!targetRect.isEmpty) {
                addRoundRect(targetRect, 10.dp.toFloat(), 10.dp.toFloat(), Path.Direction.CW)
            }
        }
        canvas.drawPath(mask, dimPaint)
        if (!targetRect.isEmpty) {
            outlinePaint.alpha = (outlineAlpha * 255).toInt()
            canvas.drawRoundRect(
                targetRect,
                10.dp.toFloat(),
                10.dp.toFloat(),
                outlinePaint
            )
        }
    }

    fun clearSpotlight() {
        spotlightGeneration++
        targetRect.setEmpty()
        touchThroughTarget = null
        targetGestureStarted = false
        invalidate()
    }

    fun spotlight(
        target: View,
        coachCard: View,
        allowTargetTouch: Boolean = false
    ) {
        val generation = ++spotlightGeneration
        post {
            applySpotlight(generation, target, coachCard, allowTargetTouch)
        }
    }

    private fun applySpotlight(
        generation: Int,
        target: View,
        coachCard: View,
        allowTargetTouch: Boolean
    ) {
        if (generation != spotlightGeneration || !isAttachedToWindow) return
        if (width <= 0 || height <= 0) {
            postOnAnimation {
                applySpotlight(generation, target, coachCard, allowTargetTouch)
            }
            return
        }
        val global = Rect()
        val overlayLocation = IntArray(2)
        getLocationOnScreen(overlayLocation)
        if (target.getGlobalVisibleRect(global) && global.width() > 0 && global.height() > 0) {
            targetRect.set(
                (global.left - overlayLocation[0] - 6.dp).toFloat(),
                (global.top - overlayLocation[1] - 6.dp).toFloat(),
                (global.right - overlayLocation[0] + 6.dp).toFloat(),
                (global.bottom - overlayLocation[1] + 6.dp).toFloat()
            )
            targetRect.left = targetRect.left.coerceAtLeast(8.dp.toFloat())
            targetRect.top = targetRect.top.coerceAtLeast(8.dp.toFloat())
            targetRect.right = targetRect.right.coerceAtMost((width - 8.dp).toFloat())
            targetRect.bottom = targetRect.bottom.coerceAtMost((height - 8.dp).toFloat())
        } else {
            val centerY = height * 0.42f
            targetRect.set(16.dp.toFloat(), centerY - 36.dp, (width - 16.dp).toFloat(), centerY + 36.dp)
        }
        touchThroughTarget = target.takeIf { allowTargetTouch }
        positionCoachCard(coachCard)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val target = touchThroughTarget
        val insideTarget = target != null && targetRect.contains(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                targetGestureStarted = insideTarget
                if (insideTarget) dispatchTouchToTarget(checkNotNull(target), event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (targetGestureStarted && target != null) dispatchTouchToTarget(target, event)
            }
            MotionEvent.ACTION_UP -> {
                if (targetGestureStarted && target != null) dispatchTouchToTarget(target, event)
                targetGestureStarted = false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (targetGestureStarted && target != null) dispatchTouchToTarget(target, event)
                targetGestureStarted = false
            }
        }
        return true
    }

    private fun dispatchTouchToTarget(target: View, event: MotionEvent) {
        val overlayLocation = IntArray(2)
        val targetLocation = IntArray(2)
        getLocationOnScreen(overlayLocation)
        target.getLocationOnScreen(targetLocation)
        MotionEvent.obtain(event).also { forwarded ->
            forwarded.offsetLocation(
                (overlayLocation[0] - targetLocation[0]).toFloat(),
                (overlayLocation[1] - targetLocation[1]).toFloat()
            )
            target.dispatchTouchEvent(forwarded)
            forwarded.recycle()
        }
    }

    private fun positionCoachCard(card: View) {
        val horizontalMargin = 16.dp
        val availableWidth = max(1, width - horizontalMargin * 2)
        val cardWidth = minOf(availableWidth, 560.dp)
        val overlayHeight = height
        card.measure(
            MeasureSpec.makeMeasureSpec(cardWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(overlayHeight, MeasureSpec.AT_MOST)
        )
        val cardHeight = card.measuredHeight
        val gap = 14.dp
        val topCandidate = (targetRect.top - cardHeight - gap).toInt()
        val bottomCandidate = (targetRect.bottom + gap).toInt()
        val topMargin = when {
            bottomCandidate + cardHeight <= height - 16.dp -> bottomCandidate
            topCandidate >= 16.dp -> topCandidate
            targetRect.centerY() < height / 2f -> height - cardHeight - 20.dp
            else -> 20.dp
        }
        card.layoutParams = (card.layoutParams as LayoutParams).apply {
            width = cardWidth
            height = LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            leftMargin = 0
            rightMargin = 0
            this.topMargin = topMargin.coerceIn(
                16.dp,
                max(16.dp, overlayHeight - cardHeight - 16.dp)
            )
            bottomMargin = 0
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
