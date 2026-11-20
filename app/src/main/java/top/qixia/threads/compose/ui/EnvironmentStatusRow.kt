package top.qixia.threads.compose.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qixia.threads.R
import top.qixia.threads.compose.theme.*

/** 首页状态弹窗中显示的权限与服务状态。 */
@Composable
internal fun EnvironmentStatusRow(
    @DrawableRes icon: Int, title: String, detail: String,
    available: Boolean, loading: Boolean, availableLabel: String, unavailableLabel: String,
    onPermission: (() -> Unit)? = null,
    neutral: Boolean = false,
    divider: Boolean = true
) {
    val action = onPermission?.takeIf { !available && !loading }
    val statusColor = if (loading || neutral) OceanTextSecondary else if (available) MintAction else OceanWarning
    val rowModifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).then(
        if (action != null) Modifier.clickable(role = Role.Button, onClickLabel = "打开${title}设置", onClick = action)
        else Modifier
    )
    Row(rowModifier.heightIn(min = 68.dp).padding(vertical = 13.dp), verticalAlignment = Alignment.Top) {
        Surface(color = OceanSurfaceHigh, shape = RoundedCornerShape(12.dp)) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                HomeLineIcon(icon, null, 20.dp, PorcelainOnTonal)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            // 状态和标题同一层，说明独占下一行；窄屏或大字体时状态自然换行。
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stacked = maxWidth < 260.dp || LocalDensity.current.fontScale > 1.25f
                val badge: @Composable () -> Unit = {
                    Row(Modifier.clip(RoundedCornerShape(8.dp)).background(statusColor.copy(alpha = .08f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (available && !loading && !neutral) HomeLineIcon(R.drawable.ic_mint_check, null, 11.dp, statusColor)
                        Text(if (loading) "检查中" else if (available) availableLabel else unavailableLabel,
                            color = statusColor, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
                        if (action != null) HomeLineIcon(R.drawable.ic_mint_chevron, null, 10.dp, statusColor)
                    }
                }
                if (stacked) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(title, fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
                        badge()
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(8.dp))
                        badge()
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            Text(detail, color = OceanTextSecondary, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
    if (divider) HorizontalDivider(Modifier.padding(start = 48.dp), thickness = .6.dp, color = MintBorder)
}
