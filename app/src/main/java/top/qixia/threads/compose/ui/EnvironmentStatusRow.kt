package top.qixia.threads.compose.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    onPermission: (() -> Unit)? = null
) {
    val action = onPermission?.takeIf { !available && !loading }
    val statusColor = if (loading) MintMuted else if (available) MintAction else OceanWarning
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    Row(Modifier.fillMaxWidth().heightIn(min = 59.dp)
        .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = Color.White, shape = RoundedCornerShape(10.dp), border = BorderStroke(.8.dp, MintBorder)) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                HomeLineIcon(icon, null, 18.dp, PorcelainOnTonal)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(3.dp))
            Text(detail, color = MintMuted, fontSize = 11.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(8.dp))
        if (!loading && available) {
            HomeLineIcon(R.drawable.ic_mint_check, null, 13.dp, statusColor)
            Spacer(Modifier.width(4.dp))
        }
        Text(if (loading) "读取中" else if (available) availableLabel else unavailableLabel,
            modifier = if (action != null) Modifier
                .background(if (pressed || focused) statusColor.copy(alpha = .08f) else Color.Transparent,
                    RoundedCornerShape(8.dp))
                .clickable(interactionSource = interactionSource, indication = null,
                    role = Role.Button, onClickLabel = "授权$title", onClick = action)
                .padding(horizontal = 8.dp, vertical = 6.dp) else Modifier,
            color = statusColor, fontSize = 12.sp, lineHeight = 18.sp)
    }
    HorizontalDivider(thickness = .6.dp, color = MintBorder)
}
