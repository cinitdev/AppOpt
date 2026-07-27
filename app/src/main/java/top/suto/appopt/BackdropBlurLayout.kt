package top.suto.appopt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.SystemClock
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

    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        captureBackdrop()
        true
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
        if (width != oldWidth || height != oldHeight) releaseSnapshot()
    }

    private fun attachListener() {
        val view = target ?: return
        if (!isAttachedToWindow || listenerAttached) return
        view.viewTreeObserver.addOnPreDrawListener(preDrawListener)
        listenerAttached = true
    }

    private fun detachListener() {
        val observer = target?.viewTreeObserver
        if (listenerAttached && observer?.isAlive == true) {
            observer.removeOnPreDrawListener(preDrawListener)
        }
        listenerAttached = false
    }

    private fun captureBackdrop() {
        val source = target ?: return
        if (capturing || width <= 0 || height <= 0 || !source.isShown) return
        val now = SystemClock.uptimeMillis()
        if (now - lastCaptureAt < CAPTURE_INTERVAL_MS) return
        lastCaptureAt = now

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
        const val SAMPLE_SCALE = 0.5f
        const val CAPTURE_INTERVAL_MS = 32L
    }
}
