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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

object AppToast {
    private var currentToast: Toast? = null
    private var currentDialog: Dialog? = null
    private var currentDialogContent: View? = null
    private var currentDialogHide: Runnable? = null
    private var currentDialogOwner: LifecycleOwner? = null
    private var currentDialogObserver: LifecycleEventObserver? = null
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
        } else {
            dismissCurrentDialog()
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
        val lifecycleOwner = activity as? LifecycleOwner
        if (lifecycleOwner != null &&
            !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            dismissCurrentDialog()
            return false
        }

        val density = activity.resources.displayMetrics.density
        fun dp(value: Float): Int = (value * density + 0.5f).toInt()

        currentToast?.cancel()
        currentToast = null
        dismissCurrentDialog()

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
        var lifecycleObserver: LifecycleEventObserver? = null
        lifecycleObserver = lifecycleOwner?.let { owner ->
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                    lifecycleObserver?.let(owner.lifecycle::removeObserver)
                    if (currentDialog === dialog) {
                        dismissCurrentDialog()
                    } else {
                        cancelAnimation(container)
                        dismissSafely(dialog)
                    }
                }
            }
        }
        dialog.setOnDismissListener {
            cancelAnimation(container)
            lifecycleObserver?.let { observer ->
                lifecycleOwner?.lifecycle?.removeObserver(observer)
            }
            releaseCurrentDialog(dialog)
        }
        try {
            dialog.show()
        } catch (_: WindowManager.BadTokenException) {
            dialog.setOnDismissListener(null)
            return false
        } catch (_: IllegalArgumentException) {
            dialog.setOnDismissListener(null)
            return false
        } catch (_: IllegalStateException) {
            dialog.setOnDismissListener(null)
            return false
        }
        currentDialog = dialog
        currentDialogContent = container
        currentDialogOwner = lifecycleOwner
        currentDialogObserver = lifecycleObserver
        lifecycleObserver?.let { lifecycleOwner?.lifecycle?.addObserver(it) }
        container.post {
            if (currentDialog === dialog && dialog.isShowing &&
                !activity.isFinishing && !activity.isDestroyed) {
                container.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(110L)
                    .start()
            }
        }

        val showMs = if (duration == Toast.LENGTH_LONG) 3200L else 1900L
        val hide = Runnable {
            if (currentDialog !== dialog) return@Runnable
            if (!dialog.isShowing || activity.isFinishing || activity.isDestroyed) {
                dismissCurrentDialog()
                return@Runnable
            }
            container.animate()
                .alpha(0f)
                .translationY(dp(8f).toFloat())
                .setDuration(140L)
                .withEndAction {
                    if (currentDialog === dialog) dismissCurrentDialog()
                }
                .start()
        }
        currentDialogHide = hide
        mainHandler.postDelayed(hide, showMs)
        return true
    }

    private fun releaseCurrentDialog(dialog: Dialog) {
        if (currentDialog !== dialog) return
        currentDialogHide?.let(mainHandler::removeCallbacks)
        currentDialogObserver?.let { observer ->
            currentDialogOwner?.lifecycle?.removeObserver(observer)
        }
        currentDialog = null
        currentDialogContent = null
        currentDialogHide = null
        currentDialogOwner = null
        currentDialogObserver = null
    }

    private fun dismissCurrentDialog() {
        val dialog = currentDialog
        val content = currentDialogContent
        val hide = currentDialogHide
        val owner = currentDialogOwner
        val observer = currentDialogObserver

        currentDialog = null
        currentDialogContent = null
        currentDialogHide = null
        currentDialogOwner = null
        currentDialogObserver = null

        hide?.let(mainHandler::removeCallbacks)
        observer?.let { owner?.lifecycle?.removeObserver(it) }
        cancelAnimation(content)
        dialog?.setOnDismissListener(null)
        dialog?.let(::dismissSafely)
    }

    private fun cancelAnimation(view: View?) {
        view?.animate()
            ?.setListener(null)
            ?.withEndAction(null)
            ?.cancel()
    }

    private fun dismissSafely(dialog: Dialog) {
        try {
            val decor = dialog.window?.decorView
            if (dialog.isShowing && decor?.isAttachedToWindow != false) {
                dialog.dismiss()
            }
        } catch (_: IllegalArgumentException) {
            // 系统已先移除窗口，下面的 finally 仍会断开 Dialog 对 Activity 的引用。
        } catch (_: WindowManager.BadTokenException) {
            // Activity token 已失效，只保留本地清理，不能再操作 WindowManager。
        } catch (_: IllegalStateException) {
            // Activity 正在销毁，避免再次提交窗口事务。
        } finally {
            dialog.setOnDismissListener(null)
        }
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
