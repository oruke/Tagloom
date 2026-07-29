package com.oruke.tagloom.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Tagloom 浅色主题，以中性编辑画布和单一功能强调色支持长时间写作。 */
private val TagloomLightColors = lightColorScheme(
    primary = Color(0xFF006B5F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7EEE9),
    onPrimaryContainer = Color(0xFF133C37),
    secondary = Color(0xFF52625F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6ECEA),
    onSecondaryContainer = Color(0xFF25312F),
    background = Color(0xFFF8F9F8),
    onBackground = Color(0xFF191C1B),
    surface = Color(0xFFFCFDFC),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFF0F2F1),
    onSurfaceVariant = Color(0xFF52605C),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F5F4),
    surfaceContainer = Color(0xFFECEFED),
    surfaceContainerHigh = Color(0xFFE8EBEA),
    surfaceContainerHighest = Color(0xFFE1E5E3),
    outline = Color(0xFF737C79),
    outlineVariant = Color(0xFFD7DEDB),
    error = Color(0xFFB3261E),
)

/** Tagloom 深色主题，保持侧栏、画布和浮层之间的清晰层级。 */
private val TagloomDarkColors = darkColorScheme(
    primary = Color(0xFF87D5C9),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF164F47),
    onPrimaryContainer = Color(0xFFB7EEE5),
    secondary = Color(0xFFB8C9C5),
    onSecondary = Color(0xFF243330),
    secondaryContainer = Color(0xFF354642),
    onSecondaryContainer = Color(0xFFD4E5E1),
    background = Color(0xFF151817),
    onBackground = Color(0xFFE4E8E6),
    surface = Color(0xFF1B1E1D),
    onSurface = Color(0xFFE4E8E6),
    surfaceVariant = Color(0xFF292E2C),
    onSurfaceVariant = Color(0xFFBCC5C2),
    surfaceContainerLowest = Color(0xFF111413),
    surfaceContainerLow = Color(0xFF181B1A),
    surfaceContainer = Color(0xFF202422),
    surfaceContainerHigh = Color(0xFF292E2C),
    surfaceContainerHighest = Color(0xFF333936),
    outline = Color(0xFF929C98),
    outlineVariant = Color(0xFF3C4441),
    error = Color(0xFFFFB4AB),
)

/** 编辑器使用的紧凑知识写作排版比例。 */
private val TagloomTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 26.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    ),
)

/** 桌面组件使用的克制圆角层级。 */
private val TagloomShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(14.dp),
)

/**
 * 应用 Tagloom 的颜色、排版和形状系统。
 *
 * @param darkTheme 是否使用深色配色。
 * @param content 主题内 UI 内容。
 */
@Composable
fun TagloomTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) TagloomDarkColors else TagloomLightColors,
        typography = TagloomTypography,
        shapes = TagloomShapes,
        content = content,
    )
}
