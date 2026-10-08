package top.qixia.threads.compose.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 保留现有首页结构，同时共用运行环境页面配色。
val MintBackground = OceanBackground
val MintPrimary = OceanPrimary
val MintAction = PorcelainAction
val MintText = OceanText
val MintMuted = OceanTextSecondary
val MintTonal = PorcelainHeader
val MintBorder = OceanOutline

@Composable
fun MintHomeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
        primary = MintAction, onPrimary = Color.White,
        primaryContainer = MintTonal, onPrimaryContainer = MintPrimary,
        background = MintBackground, onBackground = MintText,
        surface = Color.White, onSurface = MintText,
        onSurfaceVariant = MintMuted, outlineVariant = MintBorder
    ), content = content)
}
