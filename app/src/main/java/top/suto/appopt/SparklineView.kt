package top.suto.appopt

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * 线程负载曲线, 严格按 Scene(com.omarea.ui.fps.ThreadCPUView)的绘制逻辑复刻:
 *
 *  - 纵向时间分割线: 把时间轴 5 等分(6 条线, 含左右边界), **实线**, 颜色 #40888888,
 *    线宽 1px。表现"时间刻度感"。
 *  - 横向负载刻度线: 只画 0/25/50/75/100% 五条, **虚线**(DashPathEffect{4,8}),
 *    颜色 #80888888, 线宽 2px。
 *  - 负载曲线: 纯描边(无渐变填充), 线宽 1.2dp, 颜色由调用方按负载等级传入。
 *
 * Y 轴固定 0~100% 满量程(与 Scene 一致, 不做峰值自适应归一化), 这样曲线高度直接
 * 反映真实占比。X 轴按采样点索引等距分布。
 */
class SparklineView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    init {
        // DashPathEffect 在硬件加速层个别设备/版本上可能不绘制虚线段, 用软件层确保表现一致。
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private var values: FloatArray = FloatArray(0)
    // 本段采样的总时长(秒); >0 时在底部按 Scene 方式画时间刻度文字
    private var durationSec: Int = 0

    // Scene: setStrokeWidth(1.2f * 1dp), Paint.Style.STROKE, 抗锯齿
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    // 时间刻度文字(Scene): 居中对齐, 颜色 #a0888888
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#a0888888")
        textAlign = Paint.Align.CENTER
    }
    // 纵向时间分割线(Scene): 实线, #40888888, 线宽 1px
    private val vGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#40888888")
    }
    // 横向负载刻度线(Scene): 虚线 DashPathEffect{4,8}, #80888888, 线宽 2px
    private val hGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#80888888")
        pathEffect = DashPathEffect(floatArrayOf(4f, 8f), 0f)
    }

    private val linePath = Path()

    fun setData(series: FloatArray, color: Int, durationSec: Int = 0) {
        values = series
        this.durationSec = durationSec
        linePaint.color = color
        invalidate()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    /** 把秒数格式化成时间刻度标签(Scene 风格: 0 / 2m / 4m... 或 0 / 30s / 1m...) */
    private fun timeLabel(sec: Float): String {
        val s = sec.toInt()
        return when {
            s <= 0 -> "0"
            s < 60 -> "${s}s"
            s % 60 == 0 -> "${s / 60}m"
            else -> "${s / 60}m${s % 60}s"
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 文字大小: 7.5dp(Scene: f4 = fD * 7.5f)。先算出来好确定底部要留多少给文字。
        val textSize = dp(7.5f)
        textPaint.textSize = textSize

        // Scene 的内边距: 左右各 10dp, 顶部 0。底部要容下时间刻度文字(基线在 baseline+textSize+2)。
        val padLR = dp(10f)
        val padBottom = if (durationSec > 0) textSize + dp(3f) else dp(4f)
        val padTop = dp(2f)
        val plotW = w - padLR * 2
        val plotH = h - padTop - padBottom
        if (plotW <= 0f || plotH <= 0f) return

        val baseline = h - padBottom          // 0% 对应的 y(Scene: height2)

        // 纵向时间分割线: 5 等分 -> 6 条线(Scene: d2 = dB/5, 循环 i5=0..5)。实线。
        // 同时在底部留白区画时间刻度文字(Scene: f5023a.c(i5 * d2))。
        run {
            val segs = 5
            for (i in 0..segs) {
                val x = padLR + plotW * i / segs
                canvas.drawLine(x, padTop, x, baseline, vGridPaint)
                if (durationSec > 0) {
                    val sec = durationSec.toFloat() * i / segs
                    // Scene: 文字基线在 (height - f6) + f4 + 2 处
                    canvas.drawText(timeLabel(sec), x, baseline + textSize + 2f, textPaint)
                }
            }
        }

        // 横向刻度线: 按绘图区高度 4 等分画 5 条(视觉位置同 Scene 的 0/25/50/75/100%)。虚线。
        run {
            for (k in 0..4) {
                val y = baseline - plotH * k / 4f
                canvas.drawLine(padLR, y, w - padLR, y, hGridPaint)
            }
        }

        val n = values.size
        if (n == 0) return

        // Y 轴按本条线自身峰值归一化: 矮行里也能充满起伏(代价: 不同线程间高度不可直接比较,
        // 高低由行内 AVG/MAX 文字体现)。至少给 1% 满量程避免全平除零, 峰值上限封顶 100。
        var maxV = 1f
        for (v in values) if (v > maxV) maxV = v
        if (maxV > 100f) maxV = 100f

        // 曲线: X 按采样索引等距, Y 按峰值归一化填满绘图区
        fun xAt(i: Int): Float =
            if (n == 1) padLR + plotW / 2f else padLR + plotW * i / (n - 1).toFloat()
        fun yAt(v: Float): Float =
            baseline - (v.coerceIn(0f, 100f) / maxV) * plotH

        linePath.reset()
        for (i in 0 until n) {
            val x = xAt(i)
            val y = yAt(values[i])
            if (i == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
        }
        canvas.drawPath(linePath, linePaint)
    }
}
