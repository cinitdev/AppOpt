package top.suto.appopt

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.view.WindowManager

object AppToast {
    private var currentToast: Toast? = null
    private var currentDialog: Dialog? = null
    private var currentDialogHide: Runnable? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @SuppressLint("ShowToast")
    @Suppress("DEPRECATION")
    fun show(context: Context, message: String, duration: Int = Toast.LENGTH_SHORT) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(context, message, duration) }
            return
        }

        val activity = context.findActivity()
        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            if (showOverActivity(activity, message, duration)) return
        }

        val appContext = context.applicationContext
        val density = appContext.resources.displayMetrics.density
        fun dp(value: Float): Int = (value * density + 0.5f).toInt()

        val container = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48f)
            setPadding(dp(18f), dp(11f), dp(18f), dp(11f))
            background = toastBackground(dp(18f).toFloat())
        }
        container.addView(TextView(appContext).apply {
            text = message
            setTextColor(Color.parseColor("#1A1A2E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            maxLines = 3
        })

        currentToast?.cancel()
        currentToast = Toast(appContext).apply {
            view = container
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(86f))
            this.duration = duration
            show()
        }
    }

    /** 使用独立、不可交互的应用窗口，确保提示显示在 BottomSheet 和普通 Dialog 上方。 */
    private fun showOverActivity(activity: Activity, message: String, duration: Int): Boolean {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Float): Int = (value * density + 0.5f).toInt()

        currentToast?.cancel()
        currentToast = null
        currentDialogHide?.let(mainHandler::removeCallbacks)
        currentDialogHide = null
        currentDialog?.dismiss()
        currentDialog = null

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(46f)
            setPadding(dp(15f), dp(10f), dp(17f), dp(10f))
            background = toastBackground(dp(18f).toFloat())
            elevation = dp(12f).toFloat()
            alpha = 0f
            translationY = dp(10f).toFloat()
        }

        container.addView(View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#5B5BD6"))
            }
        }, LinearLayout.LayoutParams(dp(8f), dp(8f)).apply {
            marginEnd = dp(11f)
        })

        container.addView(TextView(activity).apply {
            text = message
            setTextColor(Color.parseColor("#1A1A2E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            maxLines = 4
            maxWidth = (activity.resources.displayMetrics.widthPixels - dp(88f))
                .coerceAtLeast(dp(180f))
                .coerceAtMost(dp(420f))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar).apply {
            setCancelable(false)
            setCanceledOnTouchOutside(false)
            setContentView(container)
        }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            )
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            attributes = attributes.apply {
                width = WindowManager.LayoutParams.WRAP_CONTENT
                height = WindowManager.LayoutParams.WRAP_CONTENT
                y = dp(86f)
                dimAmount = 0f
            }
        }
        dialog.setOnDismissListener {
            if (currentDialog === dialog) {
                currentDialogHide?.let(mainHandler::removeCallbacks)
                currentDialogHide = null
                currentDialog = null
            }
        }
        try {
            dialog.show()
        } catch (_: WindowManager.BadTokenException) {
            return false
        } catch (_: IllegalStateException) {
            return false
        }
        currentDialog = dialog
        container.post {
            if (dialog.isShowing) {
                container.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(110L)
                    .start()
            }
        }

        val showMs = if (duration == Toast.LENGTH_LONG) 3200L else 1900L
        val hide = Runnable {
            if (currentDialog !== dialog || !dialog.isShowing) return@Runnable
            container.animate()
                .alpha(0f)
                .translationY(dp(8f).toFloat())
                .setDuration(140L)
                .withEndAction {
                    if (currentDialog === dialog) dialog.dismiss()
                }
                .start()
        }
        currentDialogHide = hide
        mainHandler.postDelayed(hide, showMs)
        return true
    }

    private fun toastBackground(radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(Color.parseColor("#FAFFFFFF"))
            setStroke(1, Color.parseColor("#E3E1FF"))
        }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
