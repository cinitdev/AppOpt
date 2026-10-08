package top.qixia.threads

import android.content.Context
import android.app.ActivityManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.SystemClock
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.ceil

class BackdropBlurLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val blurLayer = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setRenderEffect(
            RenderEffect.createBlurEffect(
                9f * resources.displayMetrics.density,
                9f * resources.displayMetrics.density,
                Shader.TileMode.CLAMP
            )
        )
    }
    private val targetLocation = IntArray(2)
    private val blurLocation = IntArray(2)
    private var target: View? = null
    private var snapshot: Bitmap? = null
    private var snapshotCanvas: Canvas? = null
    private var listenerAttached = false
    private var capturing = false
    private var lastCaptureAt = 0L
    private var capturePosted = false
    private val realtimeCaptureEnabled: Boolean
        get() {
            val lowRam = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.isLowRamDevice == true
            val animationsEnabled = runCatching {
                Settings.Global.getFloat(
                    context.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f
                ) > 0f
            }.getOrDefault(true)
            return !lowRam && animationsEnabled
        }

    private val captureRunnable = Runnable {
        capturePosted = false
        captureBackdrop()
    }
    private val scrollChangedListener = ViewTreeObserver.OnScrollChangedListener {
        requestCapture()
    }
    private val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        requestCapture()
    }

    init {
        addView(
            blurLayer,
            0,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
    }

    fun setupWith(target: View) {
        detachListener()
        this.target = target
        attachListener()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachListener()
    }

    override fun onDetachedFromWindow() {
        detachListener()
        releaseSnapshot()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width != oldWidth || height != oldHeight) {
            releaseSnapshot()
            requestCapture(immediate = true)
        }
    }

    private fun attachListener() {
        val view = target ?: return
        if (!isAttachedToWindow || listenerAttached) return
        if (realtimeCaptureEnabled) {
            view.viewTreeObserver.addOnScrollChangedListener(scrollChangedListener)
            view.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)
        }
        listenerAttached = true
        requestCapture(immediate = true)
    }

    private fun detachListener() {
        val observer = target?.viewTreeObserver
        if (listenerAttached && observer?.isAlive == true) {
            observer.removeOnScrollChangedListener(scrollChangedListener)
            observer.removeOnGlobalLayoutListener(globalLayoutListener)
        }
        removeCallbacks(captureRunnable)
        capturePosted = false
        listenerAttached = false
    }

    private fun requestCapture(immediate: Boolean = false) {
        if (!isAttachedToWindow || target == null) return
        if (capturePosted) {
            if (!immediate) return
            removeCallbacks(captureRunnable)
            capturePosted = false
        }
        val elapsed = SystemClock.uptimeMillis() - lastCaptureAt
        val delay = if (immediate) 0L else (CAPTURE_INTERVAL_MS - elapsed).coerceAtLeast(0L)
        capturePosted = true
        postDelayed(captureRunnable, delay)
    }

    private fun captureBackdrop() {
        val source = target ?: return
        if (capturing || width <= 0 || height <= 0 || !source.isShown) return
        lastCaptureAt = SystemClock.uptimeMillis()

        ensureSnapshot()
        val bitmap = snapshot ?: return
        val canvas = snapshotCanvas ?: return
        bitmap.eraseColor(Color.TRANSPARENT)
        source.getLocationInWindow(targetLocation)
        getLocationInWindow(blurLocation)

        capturing = true
        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val saveCount = canvas.save()
            canvas.scale(SAMPLE_SCALE, SAMPLE_SCALE)
            canvas.translate(
                (targetLocation[0] - blurLocation[0]).toFloat(),
                (targetLocation[1] - blurLocation[1]).toFloat()
            )
            source.draw(canvas)
            canvas.restoreToCount(saveCount)
            blurLayer.invalidate()
        } finally {
            capturing = false
        }
    }

    private fun ensureSnapshot() {
        val targetWidth = ceil(width * SAMPLE_SCALE).toInt().coerceAtLeast(1)
        val targetHeight = ceil(height * SAMPLE_SCALE).toInt().coerceAtLeast(1)
        val current = snapshot
        if (current != null && current.width == targetWidth && current.height == targetHeight) return
        releaseSnapshot()
        snapshot = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also {
            snapshotCanvas = Canvas(it)
            blurLayer.setImageBitmap(it)
        }
    }

    private fun releaseSnapshot() {
        blurLayer.setImageDrawable(null)
        snapshotCanvas = null
        snapshot?.recycle()
        snapshot = null
    }

    private companion object {
        const val SAMPLE_SCALE = 0.25f
        // 背景抓取必须在主线程调用 View.draw；约 6 FPS 足以维持悬浮底栏的动态质感，
        // 同时避免列表滑动时每 80ms 重绘整页。
        const val CAPTURE_INTERVAL_MS = 160L
    }
}
