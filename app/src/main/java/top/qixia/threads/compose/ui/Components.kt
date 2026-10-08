package top.qixia.threads.compose.ui

import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import top.qixia.threads.R
import top.qixia.threads.RuleConfigLogic
import top.qixia.threads.compose.theme.*
import top.qixia.threads.compose.AppItemModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val applicationIconCache = LruCache<String, Drawable.ConstantState>(48)
private val applicationIconDispatcher = Dispatchers.IO.limitedParallelism(2)

@Composable
fun ScreenHeader(
    title: String,
    status: String? = null,
    statusColor: Color = OceanTextSecondary,
    onMore: (() -> Unit)? = null,
    subtitle: String = when (title) {
        "控制台", "应用与校准", "添加应用", "已配置应用" -> "应用管理与性能校准"
        "运行环境" -> "权限、模块与更新"
        "历史记录" -> "负载会话与本地平均帧率"
        "运行日志" -> "守护进程与前台助手"
        "设置" -> "自动校准与性能策略"
        else -> stringResource(R.string.app_name)
    },
    extraAction: (@Composable () -> Unit)? = null
) {
    Surface(color = Color.Transparent) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.qixia_page_eyebrow), color = OceanTextSecondary,
                    fontSize = 9.sp, lineHeight = 14.sp, letterSpacing = .6.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(title, color = OceanText, fontSize = 27.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(4.dp))
                Text(subtitle, color = OceanTextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    extraAction?.invoke()
                    if (onMore != null) {
                        IconButton(onClick = onMore, modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(PorcelainHeader)) {
                            Icon(Icons.Outlined.Refresh, "刷新", tint = PorcelainOnTonal, modifier = Modifier.size(22.dp))
                        }
                    } else if (extraAction == null) {
                        Image(painterResource(R.drawable.ic_qixia_mark), stringResource(R.string.app_name), Modifier.size(40.dp)
                            .clip(RoundedCornerShape(13.dp)).background(PorcelainHeader).padding(6.dp), contentScale = ContentScale.Fit)
                    }
                }
                if (status != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(statusColor)
                        Spacer(Modifier.width(4.dp))
                        Text(status, color = statusColor, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
fun PorcelainPage(
    title: String,
    contentPadding: PaddingValues,
    subtitle: String? = null,
    status: String? = null,
    statusColor: Color = OceanTextSecondary,
    onRefresh: (() -> Unit)? = null,
    contentSpacing: androidx.compose.ui.unit.Dp = 12.dp,
    headerExtra: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit
) {
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(OceanBackground, ClearTechBackgroundEnd))).padding(contentPadding)) {
        if (subtitle == null) ScreenHeader(title, status, statusColor, onRefresh, extraAction = headerExtra)
        else ScreenHeader(title, status, statusColor, onRefresh, subtitle, headerExtra)
        key(title) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(20.dp, 2.dp, 20.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(contentSpacing),
                content = content
            )
        }
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(6.dp).clip(CircleShape).background(color))
}

@Composable
fun StatusPill(label: String, color: Color = PorcelainOnTonal, modifier: Modifier = Modifier) {
    val foreground = if (color == OceanPrimary) PorcelainOnTonal else color
    Row(modifier.clip(CircleShape).background(color.copy(alpha = .08f).compositeOver(Color.White)).padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        StatusDot(foreground)
        Spacer(Modifier.width(5.dp))
        Text(label, color = foreground, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun GroupedSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier.fillMaxWidth(), color = OceanSurface, shape = RoundedCornerShape(17.dp),
        shadowElevation = 1.dp, content = content)
}

@Composable
fun Panel(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    GroupedSurface(modifier) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = OceanText, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (action != null) {
                    if (onAction != null) TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(action, fontSize = 12.sp, color = PorcelainOnTonal)
                    } else Text(action, color = PorcelainOnTonal, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun SectionTitle(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = OceanText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action, color = PorcelainOnTonal) }
    }
}

@Composable
fun PorcelainTabs(labels: List<String>, selected: Int, onSelected: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PorcelainHeader).padding(4.dp).selectableGroup()) {
        labels.forEachIndexed { index, label ->
            Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                .background(if (index == selected) OceanSurface else Color.Transparent)
                .selectable(index == selected, role = Role.Tab, onClick = { onSelected(index) })
                .heightIn(min = 48.dp).padding(horizontal = 4.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(label, color = if (index == selected) PorcelainOnTonal else OceanTextSecondary,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
fun SearchField(query: String, onQuery: (String) -> Unit, hint: String = "搜索应用或包名", trailing: (@Composable () -> Unit)? = null) {
    OutlinedTextField(
        query, onQuery, Modifier.fillMaxWidth(), placeholder = { Text(hint, fontSize = 13.sp) },
        singleLine = true, shape = RoundedCornerShape(16.dp),
        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
        leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(20.dp)) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, "清除搜索") }
            else trailing?.invoke()
        },
        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = OceanSurface, focusedContainerColor = OceanSurface,
            unfocusedBorderColor = OceanOutline, focusedBorderColor = OceanPrimary)
    )
}

@Composable
fun AppPackageIcon(app: AppItemModel, contentDescription: String, modifier: Modifier = Modifier) {
    if (app.isSystemComponent) {
        Image(painterResource(R.drawable.ic_linux), contentDescription,
            modifier.size(44.dp).clip(RoundedCornerShape(13.dp)), contentScale = ContentScale.Fit)
        return
    }
    val context = LocalContext.current.applicationContext
    val drawable by produceState(app.icon, app.packageName, app.icon, app.iconVersion, app.installed) {
        value = app.icon
        if (value == null && app.installed) {
            value = withContext(applicationIconDispatcher) {
                val key = "${app.packageName}:${app.iconVersion}"
                applicationIconCache.get(key)?.newDrawable(context.resources) ?: runCatching {
                    context.packageManager.getApplicationIcon(app.packageName).also { icon ->
                        icon.constantState?.let { applicationIconCache.put(key, it) }
                    }
                }.getOrNull()
            }
        }
    }
    AppDrawableIcon(drawable, contentDescription, modifier)
}

@Composable
fun AppDrawableIcon(drawable: Drawable?, contentDescription: String, modifier: Modifier = Modifier) {
    if (drawable == null) {
        Image(painterResource(R.drawable.ic_qixia_mark), contentDescription,
            modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(OceanSurfaceHigh).padding(4.dp))
    } else {
        val bitmap = remember(drawable) { drawable.toBitmap(width = 144, height = 144).asImageBitmap() }
        Image(bitmap, contentDescription, modifier.size(44.dp).clip(RoundedCornerShape(13.dp)), contentScale = ContentScale.Crop)
    }
}

@Composable
fun InfoRow(
    title: String, description: String, leading: @Composable () -> Unit,
    trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null, showDivider: Boolean = true
) {
    Column(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { leading() }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = OceanText, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
                if (description.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(description, color = OceanTextSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
            Spacer(Modifier.width(8.dp))
            trailing?.invoke()
        }
        if (showDivider) HorizontalDivider(color = OceanDivider)
    }
}

@Composable
fun CoreSelector(available: Set<Int>, selected: Set<Int>, onSelected: (Set<Int>) -> Unit, enabled: Boolean = true) {
    if (available.isEmpty()) {
        Text("暂未读取到 CPU 核心，请先检查运行环境", color = OceanWarning, fontSize = 12.sp)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        available.sorted().chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { cpu ->
                    val checked = cpu in selected
                    Surface(
                        modifier = Modifier.weight(1f).heightIn(min = 58.dp).clip(RoundedCornerShape(16.dp))
                            .selectable(checked, enabled, Role.Checkbox) { onSelected(if (checked) selected - cpu else selected + cpu) },
                        shape = RoundedCornerShape(16.dp), color = if (checked) OceanPrimary else OceanSurfaceHigh
                    ) {
                        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("CPU", color = if (checked) Color.White else OceanTextSecondary, fontSize = 9.sp)
                            Text("$cpu", color = if (checked) Color.White else OceanText, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun PorcelainSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Switch(checked, onCheckedChange, enabled = enabled, colors = SwitchDefaults.colors(
        checkedThumbColor = Color.White, checkedTrackColor = OceanPrimary,
        uncheckedThumbColor = OceanTextSecondary, uncheckedTrackColor = OceanOutline,
        uncheckedBorderColor = OceanTextSecondary
    ))
}

@Composable
fun LoadingState(label: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        Spacer(Modifier.height(14.dp))
        Text(label, color = OceanTextSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun EmptyState(title: String, description: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.ic_qixia_mark), null, Modifier.size(56.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, color = OceanText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        Text(description, color = OceanTextSecondary, fontSize = 13.sp, lineHeight = 20.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
