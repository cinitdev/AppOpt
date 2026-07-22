# format_cpu_ranges函数用法：
# $(format_cpu_ranges "$e_core")           表示能效小核
# $(format_cpu_ranges "$p_core")           表示性能中核
# $(format_cpu_ranges "$hp_core")          为高性能大核
# 也可以组合一起用：
# $(format_cpu_ranges "$e_core $p_core")  为小核与中核
# $(format_cpu_ranges "$p_core $hp_core") 为中核与大核
common_rules="
# 将 '微信' 渲染线程与主线程绑定到中大核
com.tencent.mm=$(format_cpu_ranges "$e_core $p_core") {
	RenderThread=$(format_cpu_ranges "$hp_core")
	com.tencent.mm=$(format_cpu_ranges "$p_core $hp_core")
}

# 将 '微信' 消息推送进程绑定到小核
com.tencent.mm:push=$(format_cpu_ranges "$e_core")

# 将 'QQ' 主线程与渲染线程绑定到中大核
com.tencent.mobileqq {
	encent.mobileqq=$(format_cpu_ranges "$p_core $hp_core")
	RenderThread=$(format_cpu_ranges "$hp_core")
}

# 将 'QQ' 消息推送进程绑定到小核
com.tencent.mobileqq:MSF=$(format_cpu_ranges "$e_core")

# 将 '淘宝' 主线程绑定到大核
com.taobao.taobao {
	m.taobao.taobao=$(format_cpu_ranges "$hp_core")
	RenderThread=$(format_cpu_ranges "$p_core $hp_core")
}

# 将 '酷安' 渲染线程绑定到大核
com.coolapk.market {
	RenderThread=$(format_cpu_ranges "$hp_core")
	.coolapk.market=$(format_cpu_ranges "$p_core $hp_core")
}

# 将 '抖音' 关键线程绑定到中大核
com.ss.android.ugc.aweme {
	main=$(format_cpu_ranges "$hp_core")
	RenderThread=$(format_cpu_ranges "$hp_core")
	droid.ugc.aweme=$(format_cpu_ranges "$p_core $hp_core")
}

# 将 '支付宝' 渲染线程、主线程与扫一扫线程绑定到中大核
com.eg.android.AlipayGphone {
	RenderThread=$(format_cpu_ranges "$hp_core")
	id.AlipayGphone=$(format_cpu_ranges "$p_core $hp_core")
	ScanRecognize=$(format_cpu_ranges "$hp_core")
}

# 将 '高德地图' 渲染线程与主线程绑定到中大核
com.autonavi.minimap {
	RenderThread=$(format_cpu_ranges "$hp_core")
	utonavi.minimap=$(format_cpu_ranges "$p_core $hp_core")
}

# 将 'Android图形显示组件'渲染引擎线程绑定到大核
surfaceflinger{RenderEngine}=$(format_cpu_ranges "$hp_core")

# 允许 'Android图形显示组件' 使用所有CPU核心$all_core
surfaceflinger=$all_core

# 将 '系统界面' 渲染引擎线程与主线程绑定到中大核
com.android.systemui {
	RenderThread=$(format_cpu_ranges "$hp_core")
	ndroid.systemui=$(format_cpu_ranges "$p_core $hp_core")
}
"

RULES_CONFIG_FILE="${APPOPT_RULES_FILE:-$MODPATH/applist.conf}"
echo "$common_rules" >> "$RULES_CONFIG_FILE"
unset common_rules RULES_CONFIG_FILE
