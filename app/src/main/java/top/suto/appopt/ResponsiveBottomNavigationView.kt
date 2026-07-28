package top.suto.appopt

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/** 全尺寸响应式底栏，显式保留图标和文字，避免系统尺寸变化时裁剪标题。 */
class ResponsiveBottomNavigationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private data class ItemSpec(val id: Int, val title: String, val icon: Int)

    private val itemSpecs = listOf(
        ItemSpec(R.id.navApps, "应用", R.drawable.ic_apps),
        ItemSpec(R.id.navEnvironment, "运行环境", R.drawable.ic_environment),
        ItemSpec(R.id.navHistory, "历史记录", R.drawable.ic_history),
        ItemSpec(R.id.navLog, "日志", R.drawable.ic_log),
        ItemSpec(R.id.navSettings, "设置", R.drawable.ic_settings)
    )
    private val itemViews = LinkedHashMap<Int, View>()
    private val iconViews = LinkedHashMap<Int, ImageView>()
    private val labelViews = LinkedHashMap<Int, TextView>()
    private val indicatorViews = LinkedHashMap<Int, View>()
    private var itemSelectedListener: ((Int) -> Boolean)? = null
    private var itemReselectedListener: ((Int) -> Unit)? = null
    private var selectedId = View.NO_ID

    var selectedItemId: Int
        get() = selectedId
        set(value) {
            setSelectedItem(value, notify = true)
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        clipChildren = false
        clipToPadding = false
        setPadding(dp(6f), dp(5f), dp(6f), dp(5f))
        buildItems()
    }

    fun setOnItemSelectedListener(listener: ((Int) -> Boolean)?) {
        itemSelectedListener = listener
    }

    fun setOnItemReselectedListener(listener: ((Int) -> Unit)?) {
        itemReselectedListener = listener
    }

    fun setSelectedItemSilently(itemId: Int) {
        setSelectedItem(itemId, notify = false)
    }

    private fun buildItems() {
        itemSpecs.forEach { spec ->
            val item = FrameLayout(context).apply {
                id = spec.id
                isClickable = true
                isFocusable = true
                contentDescription = spec.title
                foreground = resolveSelectableForeground()
                layoutParams = LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener {
                    if (selectedId == spec.id) {
                        itemReselectedListener?.invoke(spec.id)
                    } else {
                        selectedItemId = spec.id
                    }
                }
            }

            val content = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            }

            val iconFrame = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(44f), dp(30f))
            }
            val indicator = View(context).apply {
                layoutParams = FrameLayout.LayoutParams(dp(44f), dp(30f), Gravity.CENTER)
                background = roundedBackground(ContextCompat.getColor(context, R.color.surface_selected), 18f)
                visibility = View.INVISIBLE
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val icon = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(dp(24f), dp(24f), Gravity.CENTER)
                setImageResource(spec.icon)
                imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.nav_inactive)
                )
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            iconFrame.addView(indicator)
            iconFrame.addView(icon)

            val label = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(18f)
                ).apply { topMargin = dp(2f) }
                gravity = Gravity.CENTER
                setText(spec.title)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setSingleLine(true)
                ellipsize = null
                setTextColor(ContextCompat.getColor(context, R.color.nav_inactive))
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            }
            content.addView(iconFrame)
            content.addView(label)
            item.addView(content)
            addView(item)
            itemViews[spec.id] = item
            iconViews[spec.id] = icon
            labelViews[spec.id] = label
            indicatorViews[spec.id] = indicator
        }
    }

    private fun setSelectedItem(itemId: Int, notify: Boolean) {
        if (itemId !in itemViews) return
        if (selectedId == itemId) {
            if (notify) itemReselectedListener?.invoke(itemId)
            return
        }
        val previous = selectedId
        updateSelection(itemId)
        if (notify && itemSelectedListener?.invoke(itemId) == false) {
            updateSelection(previous)
        }
    }

    private fun updateSelection(itemId: Int) {
        selectedId = itemId
        itemViews.forEach { (id, item) ->
            val selected = id == itemId
            item.isSelected = selected
            item.isActivated = selected
            indicatorViews[id]?.visibility = if (selected) View.VISIBLE else View.INVISIBLE
            val color = ContextCompat.getColor(
                context,
                if (selected) R.color.brand_primary else R.color.nav_inactive
            )
            iconViews[id]?.imageTintList = ColorStateList.valueOf(color)
            labelViews[id]?.setTextColor(color)
        }
    }

    private fun resolveSelectableForeground() =
        context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
            .let { array ->
                val drawable = array.getDrawable(0)
                array.recycle()
                drawable
            }

    private fun roundedBackground(color: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()
}
