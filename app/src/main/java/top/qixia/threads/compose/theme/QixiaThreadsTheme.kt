package top.qixia.threads.compose.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 清晰科技风：采用用户 HTML 中的蓝色方案，始终使用浅色配色。
// 保留现有样式变量名称，让正式页面和弹窗使用统一设计体系。
val OceanBackground = Color(0xFFF5F8FF)
val OceanSurface = Color.White
val OceanSurfaceHigh = Color(0xFFF0F5FF)
val OceanPrimary = Color(0xFF356FE5)
val OceanSecondary = Color(0xFF4FCE91)
val OceanSuccess = Color(0xFF27704E)
val OceanWarning = Color(0xFFAA5713)
val OceanError = Color(0xFFB93838)
val OceanText = Color(0xFF182235)
val OceanTextSecondary = Color(0xFF647086)
val OceanOutline = Color(0xFFDCE5F3)
val OceanDivider = Color(0xFFEDF0F5)
val PorcelainHeader = Color(0xFFE4EDFF)
val PorcelainOnTonal = Color(0xFF2F5FBD)
val PorcelainAction = Color(0xFF2F63D2)
val ClearTechGradientEnd = Color(0xFF6D8FF2)
val ClearTechBackgroundEnd = Color(0xFFF8FAFC)

private val PorcelainColors = lightColorScheme(
    primary = PorcelainAction, onPrimary = Color.White,
    primaryContainer = PorcelainHeader, onPrimaryContainer = PorcelainOnTonal,
    secondary = OceanSuccess, onSecondary = Color.White,
    secondaryContainer = PorcelainHeader, onSecondaryContainer = PorcelainOnTonal,
    tertiary = OceanWarning, onTertiary = Color.White,
    error = OceanError, onError = Color.White,
    errorContainer = Color(0xFFFCEAEA), onErrorContainer = Color(0xFF8A2424),
    background = OceanBackground, onBackground = OceanText,
    surface = OceanSurface, onSurface = OceanText,
    surfaceVariant = OceanSurfaceHigh, onSurfaceVariant = OceanTextSecondary,
    surfaceContainer = OceanSurfaceHigh, surfaceContainerLow = OceanSurface,
    surfaceContainerHigh = OceanSurfaceHigh,
    outline = OceanOutline, outlineVariant = OceanDivider,
    surfaceTint = Color.Transparent
)

@Composable
fun QixiaThreadsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PorcelainColors,
        typography = Typography(bodyLarge = TextStyle(
            fontFamily = FontFamily.SansSerif, fontSize = 16.sp,
            // 显式设置字号的标签使用自身字体度量，避免紧凑卡片
            // 继承 Material 正文的 24sp 行高。
            platformStyle = PlatformTextStyle(includeFontPadding = false)
        )),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(17.dp), large = RoundedCornerShape(23.dp),
            extraLarge = RoundedCornerShape(28.dp)
        ),
        content = content
    )
}
