package top.qixia.threads.compose.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.compose.*
import top.qixia.threads.compose.theme.*
import qixia.refresh.RefreshPolicy
import kotlinx.coroutines.delay

private fun refreshValue(raw: String, rates: List<Float>): Float? {
    val value = raw.toFloatOrNull()?.takeIf(Float::isFinite) ?: return null
    if (value == -2f) return value
    val supported = RefreshPolicy.displayRates(rates.toFloatArray())
    return if (supported.isNotEmpty()) RefreshPolicy.resolve(value,supported) else value.takeIf { it > 0 }
}

internal fun refreshHz(raw: String, rates: List<Float> = emptyList()): String =
    when(val value = refreshValue(raw,rates)) {
        -2f -> "跟随全局"
        null -> "未获取"
        else -> "${RefreshPolicy.rateLabel(value)} Hz"
    }

internal fun refreshChoice(raw: String, rates: List<Float>): String {
    val label = refreshHz(raw, rates)
    val hz = refreshValue(raw,rates)
    val sorted = RefreshPolicy.displayRates(rates.toFloatArray()).toList()
    val tier = when {
        hz == null || sorted.size < 2 -> null
        hz == sorted.first() -> "高"
        hz == sorted.last() -> "低"
        sorted.size == 3 && hz == sorted[1] -> "中"
        else -> null
    }
    return if (tier == null) label else "$tier (${label.replace(" ", "")})"
}

@Composable
internal fun RefreshRateScreen(
    state: RefreshRateUiState, padding: PaddingValues, initialPackage: String?,
    onBack: () -> Unit, onChange: (String, String?) -> Unit
) {
    var tab by rememberSaveable(initialPackage) { mutableIntStateOf(if (initialPackage == null) 0 else 1) }
    var query by rememberSaveable(initialPackage) { mutableStateOf(initialPackage.orEmpty()) }
    var selectedApp by rememberSaveable(initialPackage) { mutableStateOf(initialPackage) }
    val p = state.policy
    val enabled = state.available && !state.saving && !state.loading
    val modeRates = remember(state.rates) { RefreshPolicy.displayRates(state.rates.toFloatArray()).toList() }
    val savedModes = remember(p,modeRates) { p.displayModeIds(modeRates.toFloatArray()) }
    var modesDraft by rememberSaveable(savedModes,modeRates) { mutableStateOf(savedModes) }
    val normalizedModes = modesDraft.trim().replace(Regex(" +")," ")
    val boundModes = remember(normalizedModes,modeRates) {
        runCatching { RefreshPolicy.bindDisplayModes(normalizedModes,modeRates.toFloatArray()) }.getOrNull()
    }
    val validModes = boundModes != null
    val change by rememberUpdatedState(onChange)
    fun saveModes() {
        if(enabled && boundModes != null && normalizedModes != savedModes) change("display_modes",boundModes)
    }
    val leave = { saveModes(); onBack() }
    BackHandler(onBack = leave)
    // 在页面层等待输入完成，滚出输入框或返回时不会丢失已经填写完整的映射。
    LaunchedEffect(modesDraft,enabled,savedModes,modeRates) {
        if(enabled && validModes && normalizedModes != savedModes) { delay(700); saveModes() }
    }
    Column(Modifier.fillMaxSize().background(OceanBackground).padding(padding)) {
        Row(Modifier.fillMaxWidth().padding(start=8.dp,end=12.dp,top=8.dp,bottom=12.dp), verticalAlignment=Alignment.CenterVertically) {
            IconButton(leave) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回",tint=OceanText) }
            Text("刷新率管理",fontSize=22.sp,fontWeight=FontWeight.Bold,color=OceanText,modifier=Modifier.weight(1f))
            Switch(p.on("enabled"),{ onChange("enabled",if(it) "1" else "0") },enabled=enabled,
                modifier=Modifier.semantics { contentDescription="启用刷新率管理" })
        }
        Column(Modifier.padding(horizontal=20.dp)) {
            if(state.saving) Text("正在保存",fontSize=11.sp,color=OceanTextSecondary)
            listOfNotNull(state.message,state.runtime["error"]?.takeIf(String::isNotBlank),
                state.runtime["verification"]?.takeIf { p.on("enabled") && it.isNotBlank() },
                state.runtime["input_error"]?.takeIf { p.on("enabled") && it.isNotBlank() }).forEach {
                Text(it,Modifier.padding(vertical=8.dp),color=OceanWarning,fontSize=12.sp,lineHeight=18.sp)
            }
            PorcelainTabs(listOf("全局策略","应用规则"),tab,{tab=it})
            Spacer(Modifier.height(12.dp))
        }
        if(state.loading) { LoadingState("读取刷新率与应用"); return@Column }
        if(tab == 0) LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(start=20.dp,end=20.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            item { RefreshSection("应用/游戏") {
                RefreshRateSelect("活动刷新率","触摸屏幕或按下按键时的刷新率",p.get("active_hz"),state.rates,enabled) { onChange("active_hz",it) }
                RefreshRateSelect("闲置刷新率","未操作手机时的刷新率",p.get("idle_hz"),state.rates,enabled) { onChange("idle_hz",it) }
            } }
            item { RefreshGroup {
                RefreshRateSelect("低电量刷新率","电池电量过低时的刷新率",p.get("battery_hz"),state.rates,enabled) { onChange("battery_hz",it) }
                RefreshSlider("低电量阈值(%)","电量低于此值时判定为低电量",p.integer("battery_threshold"),0..30,"",enabled) { onChange("battery_threshold",it.toString()) }
            } }
            item { RefreshGroup {
                RefreshRateSelect("锁屏刷新率","停留在锁屏界面时的刷新率",p.get("lock_hz"),state.rates,enabled) { onChange("lock_hz",it) }
                RefreshRateSelect("手势刷新率","触发手势或操作桌面时的刷新率",p.get("gesture_hz"),state.rates,enabled) { onChange("gesture_hz",it) }
            } }
            item { RefreshGroup {
                RefreshSlider("低亮度阈值(%)","屏幕亮度低于此值时判定为低亮度",p.integer("brightness_threshold"),0..100,"",enabled) { onChange("brightness_threshold",it.toString()) }
                RefreshRateSelect("低亮度刷新率","屏幕处于较低亮度时的刷新率",p.get("brightness_hz"),state.rates,enabled) { onChange("brightness_hz",it) }
            } }
            item { RefreshGroup {
                RefreshSlider("点按活动时长(毫秒)","轻触或点击屏幕后判定为活动状态的时长",p.integer("tap_ms"),300..5000,"",enabled,100) { onChange("tap_ms",it.toString()) }
                RefreshSlider("滑动活动时长(毫秒)","在屏幕上长距离滑动后判定为活动状态的时长",p.integer("scroll_ms"),500..5000,"",enabled,100) { onChange("scroll_ms",it.toString()) }
            } }
            item { RefreshGroup {
                RefreshToggle("严格模式","在前台变化或请求被覆盖时重申，持续失败会降低重试频率",p.on("strict",state.runtime["automatic_strict"]=="1"),enabled) { onChange("strict",if(it) "1" else "0") }
                RefreshToggle("兼容模式","使用当前设备对应的系统设置；自动默认值随设备适配",p.on("compatible",state.runtime["automatic_compatible"]=="1"),enabled) { onChange("compatible",if(it) "1" else "0") }
                RefreshDisplayModes(modeRates,modesDraft,validModes,enabled,{modesDraft=it},::saveModes)
            } }
        } else {
            Box(Modifier.padding(horizontal=20.dp).padding(bottom=10.dp)) { SearchField(query,{query=it}) }
            val apps = remember(state.apps,query) { state.apps.filter { it.name.contains(query,true) || it.packageName.contains(query,true) } }
            LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(start=20.dp,end=20.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                item { Text("${apps.size} 个应用 · 点按设置闲置／活动档位",fontSize=11.sp,color=OceanTextSecondary) }
                items(apps,key={it.packageName}) { app ->
                    val rule=p.values["app."+app.packageName]?.split(',')
                    Surface(onClick={selectedApp=app.packageName},enabled=enabled,color=OceanSurface,shape=RoundedCornerShape(20.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                RefreshAppIcon(app)
                                Spacer(Modifier.width(10.dp)); Text(app.name,color=OceanText,fontSize=15.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                                Icon(Icons.Outlined.ChevronRight,null,tint=OceanTextSecondary,modifier=Modifier.size(18.dp))
                            }
                            Text(app.packageName,color=OceanTextSecondary,fontSize=10.sp,modifier=Modifier.padding(top=5.dp))
                            Text(if(rule==null) "跟随全局策略" else "闲置 ${refreshHz(rule[0],state.rates)}  /  活动 ${refreshHz(rule[1],state.rates)}",color=PorcelainOnTonal,fontSize=12.sp,modifier=Modifier.padding(top=10.dp))
                        }
                    }
                }
            }
        }
    }
    selectedApp?.let { pkg ->
        val app=state.apps.firstOrNull { it.packageName==pkg }
        if(!state.loading) RefreshAppSheet(app?:RefreshApp(pkg,pkg),p,state.rates,enabled,{selectedApp=null}) { value -> onChange("app.$pkg",value);selectedApp=null }
    }
}

@Composable private fun RefreshGroup(content: @Composable ColumnScope.()->Unit) { Surface(color=OceanSurface,shape=RoundedCornerShape(22.dp)) { Column(Modifier.fillMaxWidth(),content=content) } }
@Composable private fun RefreshAppIcon(app: RefreshApp) {
    val model = remember(app.packageName, app.name) { AppItemModel(app.packageName, app.name, installed=true, icon=null) }
    AppPackageIcon(model, "${app.name}图标", Modifier.size(40.dp))
}
@Composable private fun RefreshSection(title:String,content:@Composable ColumnScope.()->Unit) {
    Column { Text(title,fontSize=12.sp,fontWeight=FontWeight.SemiBold,color=OceanTextSecondary,modifier=Modifier.padding(start=4.dp,bottom=8.dp));RefreshGroup(content) }
}
@Composable private fun RefreshToggle(title:String,description:String,checked:Boolean,enabled:Boolean,onChange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title,color=OceanText,fontSize=14.sp,fontWeight=FontWeight.Medium); Text(description,color=OceanTextSecondary,fontSize=11.sp,lineHeight=16.sp,modifier=Modifier.padding(top=4.dp)) }
        Spacer(Modifier.width(12.dp));Switch(checked,onChange,enabled=enabled,modifier=Modifier.semantics { contentDescription=title })
    }
}
@Composable private fun RefreshSlider(title:String,description:String,value:Int,range:IntRange,unit:String,enabled:Boolean,step:Int=1,onChange:(Int)->Unit) {
    var draft by remember(value) {mutableFloatStateOf(value.coerceIn(range).toFloat())}
    Column(Modifier.padding(16.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(title,color=OceanText,fontSize=14.sp,fontWeight=FontWeight.Medium)
            Text("${draft.toInt()} $unit",color=PorcelainOnTonal,fontSize=12.sp)
        }
        Slider(draft,{draft=(it/step).toInt().times(step).coerceIn(range).toFloat()},enabled=enabled,
            valueRange=range.first.toFloat()..range.last.toFloat(),onValueChangeFinished={if(draft.toInt()!=value) onChange(draft.toInt())})
        Text(description,color=OceanTextSecondary,fontSize=11.sp)
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun RefreshRateSelect(
    title:String, description:String, value:String, rates:List<Float>, enabled:Boolean,
    inherit:Boolean=false, onSelect:(String)->Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val options = remember(rates,inherit) {
        buildList { if(inherit) add("-2");addAll(rates.distinct().sortedDescending().map(Float::toString)) }
    }
    val selected = refreshValue(value,rates)
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(title,color=OceanText,fontSize=14.sp,fontWeight=FontWeight.Medium)
        Spacer(Modifier.height(9.dp))
        // 菜单锚定当前设置项，选择后立即收起，不再覆盖整页或叠加选择弹窗。
        ExposedDropdownMenuBox(
            expanded=expanded && enabled,
            onExpandedChange={ if(enabled && options.isNotEmpty()) expanded=it }
        ) {
            Surface(
                modifier=Modifier.fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable,enabled && options.isNotEmpty())
                    .semantics { contentDescription=title },
                color=OceanSurfaceHigh,shape=RoundedCornerShape(12.dp)
            ) {
                Row(Modifier.heightIn(min=48.dp).padding(horizontal=14.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(refreshChoice(value,rates),Modifier.weight(1f),color=if(enabled) PorcelainOnTonal else OceanTextSecondary,fontSize=16.sp)
                    Icon(if(expanded && enabled) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null,
                        tint=OceanTextSecondary,modifier=Modifier.size(20.dp))
                }
            }
            ExposedDropdownMenu(
                expanded=expanded && enabled,onDismissRequest={expanded=false},
                modifier=Modifier.heightIn(max=320.dp),shape=RoundedCornerShape(12.dp),containerColor=OceanSurface
            ) {
                options.forEach { option ->
                    val checked=selected==option.toFloatOrNull()
                    DropdownMenuItem(
                        text={Text(refreshChoice(option,rates),color=if(checked) PorcelainOnTonal else OceanText,
                            fontWeight=if(checked) FontWeight.SemiBold else FontWeight.Normal)},
                        onClick={expanded=false;onSelect(option)},
                        modifier=Modifier.background(if(checked) OceanSurfaceHigh else androidx.compose.ui.graphics.Color.Transparent)
                            .semantics { this.selected=checked },
                        trailingIcon={if(checked) Icon(Icons.Outlined.Check,null,tint=PorcelainOnTonal,modifier=Modifier.size(20.dp))}
                    )
                }
            }
        }
        Text(description,color=OceanTextSecondary,fontSize=11.sp,lineHeight=16.sp,modifier=Modifier.padding(top=7.dp))
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun RefreshAppSheet(app:RefreshApp,p:RefreshPolicy,rates:List<Float>,enabled:Boolean,onDismiss:()->Unit,onSave:(String?)->Unit) {
    val original=p.values["app."+app.packageName]?.split(',')?:listOf("-2","-2")
    var idle by rememberSaveable(app.packageName) {mutableStateOf(original[0])}
    var active by rememberSaveable(app.packageName) {mutableStateOf(original[1])}
    ModalBottomSheet(onDismissRequest=onDismiss,containerColor=OceanBackground,
        sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),sheetGesturesEnabled=false,
        dragHandle=null,shape=RoundedCornerShape(topStart=28.dp,topEnd=28.dp)) {
        Column(Modifier.fillMaxWidth().heightIn(max=LocalConfiguration.current.screenHeightDp.dp * .85f).testTag("app-refresh-sheet")) {
            Row(Modifier.fillMaxWidth().padding(start=20.dp,end=10.dp,top=16.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically) {
                RefreshAppIcon(app)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.name,color=OceanText,fontSize=18.sp,fontWeight=FontWeight.Bold)
                    Text("应用刷新率",color=OceanTextSecondary,fontSize=12.sp)
                }
                IconButton(onDismiss) {Icon(Icons.Outlined.Close,"关闭应用刷新率",tint=OceanTextSecondary)}
            }
            Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()).padding(horizontal=20.dp)) {
                Text(app.packageName,color=OceanTextSecondary,fontSize=11.sp,modifier=Modifier.padding(bottom=12.dp))
                RefreshGroup {
                    RefreshRateSelect("活动刷新率","触摸屏幕或按键时",active,rates,enabled,inherit=true) {active=it}
                    HorizontalDivider(Modifier.padding(horizontal=16.dp),color=OceanOutline.copy(alpha=.6f))
                    RefreshRateSelect("闲置刷新率","未操作手机时",idle,rates,enabled,inherit=true) {idle=it}
                }
                Text("游戏需要持续高刷时，可将闲置与活动设为同一档。",fontSize=12.sp,lineHeight=18.sp,
                    color=OceanTextSecondary,modifier=Modifier.padding(vertical=14.dp))
            }
            Column(Modifier.fillMaxWidth().padding(horizontal=20.dp).padding(top=8.dp,bottom=8.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Button({onSave("$idle,$active")},enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),
                    shape=RoundedCornerShape(16.dp),colors=ButtonDefaults.buttonColors(containerColor=PorcelainAction)) {Text("完成")}
                TextButton({onSave(null)},enabled=enabled) {Text("清除该应用规则",color=OceanTextSecondary)}
            }
        }
    }
}
@Composable private fun RefreshDisplayModes(rates:List<Float>,draft:String,valid:Boolean,enabled:Boolean,onEdit:(String)->Unit,onDone:()->Unit) {
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("DisplayModes" + rates.joinToString("") { " [${RefreshPolicy.rateLabel(it)}]" },fontSize=14.sp,fontWeight=FontWeight.Medium,color=OceanText)
        Spacer(Modifier.height(9.dp))
        OutlinedTextField(draft,onEdit,enabled=enabled && rates.isNotEmpty(),singleLine=true,
            modifier=Modifier.fillMaxWidth().semantics {contentDescription="DisplayModes"},
            shape=RoundedCornerShape(12.dp),placeholder={Text(rates.joinToString(" ") { "-1" })},isError=rates.isNotEmpty() && !valid,
            keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),
            keyboardActions=KeyboardActions(onDone={onDone();focus.clearFocus()}),
            colors=OutlinedTextFieldDefaults.colors(unfocusedContainerColor=OceanSurfaceHigh,focusedContainerColor=OceanSurfaceHigh,
                unfocusedBorderColor=androidx.compose.ui.graphics.Color.Transparent,focusedBorderColor=PorcelainOnTonal))
        Text(when {
            rates.isEmpty() -> "尚未获取设备当前分辨率的刷新率档位。"
            valid -> "按上方设备刷新率顺序填写 DisplayModeID，以空格分隔。-1 表示自动匹配；不了解时保持默认即可。"
            else -> "当前设备有 ${rates.size} 个刷新率档位，请填写对应数量的 ID；-1 表示自动，其余为非负整数。"
        },color=if(valid || rates.isEmpty()) OceanTextSecondary else OceanWarning,fontSize=11.sp,lineHeight=16.sp,modifier=Modifier.padding(top=7.dp))
    }
}
