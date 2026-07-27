package top.suto.appopt

import android.app.Activity
import android.graphics.Color
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

object SystemBars {
    fun applyEdgeToEdge(
        activity: Activity,
        root: View,
        header: View,
        lightStatusBar: Boolean = false,
        bottomOverlay: View? = null
    ) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        activity.window.statusBarColor = Color.TRANSPARENT
        activity.window.navigationBarColor = activity.getColor(R.color.surface_app)

        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = lightStatusBar
        controller.isAppearanceLightNavigationBars = true

        val rootLeft = root.paddingLeft
        val rootTop = root.paddingTop
        val rootRight = root.paddingRight
        val rootBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            if (bottomOverlay == null) {
                view.setPadding(rootLeft, rootTop, rootRight, rootBottom + nav.bottom)
            } else {
                view.setPadding(rootLeft, rootTop, rootRight, rootBottom)
                bottomOverlay.translationY = -nav.bottom.toFloat()
            }
            insets
        }

        val headerLeft = header.paddingLeft
        val headerTop = header.paddingTop
        val headerRight = header.paddingRight
        val headerBottom = header.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(header) { view, insets ->
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPadding(headerLeft, headerTop + status.top, headerRight, headerBottom)
            insets
        }

        ViewCompat.requestApplyInsets(root)
        ViewCompat.requestApplyInsets(header)
    }
}
