package io.github.xiaohaoz1.jiarenxia.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = JrxPrimaryLight,
    onPrimary = Color.White,
    primaryContainer = JrxPrimaryContainerLight,
    onPrimaryContainer = JrxOnPrimaryContainerLight,
    secondary = JrxSecondaryLight,
    onSecondary = Color.White,
    secondaryContainer = JrxSecondaryContainerLight,
    onSecondaryContainer = JrxOnSecondaryContainerLight,
    tertiary = JrxTertiaryLight,
    onTertiary = Color.White,
    tertiaryContainer = JrxTertiaryContainerLight,
    onTertiaryContainer = JrxOnTertiaryContainerLight,
    error = JrxErrorLight,
    onError = Color.White,
    errorContainer = JrxErrorContainerLight,
    background = JrxBackgroundLight,
    onBackground = JrxOnBackgroundLight,
    surface = JrxBackgroundLight,
    onSurface = JrxOnBackgroundLight,
    surfaceContainerLowest = JrxSurfaceContainerLowestLight,
    surfaceContainerLow = JrxSurfaceContainerLowLight,
    surfaceContainer = JrxSurfaceContainerLight,
    surfaceContainerHigh = JrxSurfaceContainerHighLight,
    surfaceContainerHighest = JrxSurfaceContainerHighestLight,
    surfaceVariant = JrxSurfaceVariantLight,
    onSurfaceVariant = JrxOnSurfaceVariantLight,
    outline = JrxOutlineLight,
    outlineVariant = JrxOutlineVariantLight,
    surfaceTint = JrxPrimaryLight
)

private val DarkColors = darkColorScheme(
    primary = JrxPrimaryDark,
    onPrimary = JrxOnPrimaryDark,
    primaryContainer = JrxPrimaryContainerDark,
    onPrimaryContainer = JrxPrimaryContainerLight,
    secondary = JrxSecondaryDark,
    onSecondary = JrxOnSecondaryDark,
    secondaryContainer = JrxSecondaryContainerDark,
    onSecondaryContainer = JrxSecondaryContainerLight,
    tertiary = JrxTertiaryDark,
    onTertiary = JrxOnTertiaryDark,
    tertiaryContainer = JrxTertiaryContainerDark,
    onTertiaryContainer = JrxTertiaryContainerLight,
    error = JrxErrorDark,
    onError = Color(0xFF690005),
    errorContainer = JrxErrorContainerDark,
    background = JrxBackgroundDark,
    onBackground = JrxOnBackgroundDark,
    surface = JrxBackgroundDark,
    onSurface = JrxOnBackgroundDark,
    surfaceContainerLowest = JrxSurfaceContainerLowestDark,
    surfaceContainerLow = JrxSurfaceContainerLowDark,
    surfaceContainer = JrxSurfaceContainerDark,
    surfaceContainerHigh = JrxSurfaceContainerHighDark,
    surfaceContainerHighest = JrxSurfaceContainerHighestDark,
    surfaceVariant = JrxSurfaceVariantDark,
    onSurfaceVariant = JrxOnSurfaceVariantDark,
    outline = JrxOutlineDark,
    outlineVariant = JrxOutlineVariantDark,
    surfaceTint = JrxPrimaryDark
)

/** 圆角：2026-10-09 改版统一收到 14dp 一档（原 12/16/20/28 四档偏厚）。 */
private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

/**
 * 品牌扩展色（随亮暗切换）：亲属侧别环、页头渐变、已故/语义色。
 */
data class JrxColors(
    /** 父系（父亲及父系祖辈）：主色。 */
    val paternal: Color,
    /** 母系（母亲及母系祖辈）。 */
    val maternal: Color,
    /** 配偶侧（配偶与配偶父母）。 */
    val spouseSide: Color,
    /** 已故标记色。 */
    val deceased: Color,
    /** 页头底色（与背景同色）。 */
    val pageHeaderBackground: Color,
    val success: Color,
    val warning: Color
)

val LightJrxColors = JrxColors(
    paternal = JrxPrimaryLight,
    maternal = JrxTertiaryLight,
    spouseSide = Color(0xFF9A5B2E),
    deceased = Color(0xFF8A817D),
    pageHeaderBackground = JrxBackgroundLight,
    success = Color(0xFF2F6B4F),
    warning = Color(0xFFB26A00)
)

val DarkJrxColors = JrxColors(
    paternal = JrxPrimaryDark,
    maternal = Color(0xFFA6D2A2),
    spouseSide = Color(0xFFFFB688),
    deceased = Color(0xFFA39B97),
    pageHeaderBackground = JrxBackgroundDark,
    success = Color(0xFF86C9A0),
    warning = Color(0xFFFFB95C)
)

val LocalJrxColors = staticCompositionLocalOf { LightJrxColors }

/**
 * 主题（明暗已判定）：温馨暖色固定配色，不使用动态取色。
 */
@Composable
fun JrxTheme(
    dark: Boolean,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalJrxColors provides if (dark) DarkJrxColors else LightJrxColors) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}
