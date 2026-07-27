package top.suto.appopt

import android.content.Context
import android.view.View
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment

/** 单 Activity 顶级页面共用的生命周期与系统栏适配。 */
abstract class TopLevelFragment : Fragment() {
    private lateinit var applicationContext: Context

    protected val appContext: Context
        get() = applicationContext

    protected val contentResolver
        get() = applicationContext.contentResolver

    protected val packageManager
        get() = applicationContext.packageManager

    override fun onAttach(context: Context) {
        super.onAttach(context)
        applicationContext = context.applicationContext
    }

    /** 顶级导航真正显示当前页面后调用；预热创建隐藏页面时不会触发。 */
    open fun onTopLevelPageSelected() = Unit

    protected fun getColor(@ColorRes colorRes: Int): Int =
        ContextCompat.getColor(requireContext(), colorRes)

    protected val isFinishing: Boolean
        get() = activity?.isFinishing != false

    protected val isDestroyed: Boolean
        get() = activity?.isDestroyed != false || view == null

    protected fun runOnUiThread(action: () -> Unit) {
        activity?.runOnUiThread {
            if (isAdded && view != null) action()
        }
    }

    protected fun prepareTopLevelPage(header: View) {
        val left = header.paddingLeft
        val top = header.paddingTop
        val right = header.paddingRight
        val bottom = header.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(header) { view, insets ->
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPadding(left, top + status.top, right, bottom)
            insets
        }
        ViewCompat.requestApplyInsets(header)
    }
}
