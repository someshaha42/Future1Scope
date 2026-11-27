package com.example.framescope.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class FrameScopeColorPalette(
    val background: Color,
    val cardSurface: Color,
    val innerSurface: Color,
    val elevatedSurface: Color,
    val borderSubtle: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val selectionBlue: Color,
    val selectionBlueTint: Color,
    val selectionBlueIconBg: Color,
    val recordRed: Color,
    val recordRedDark: Color,
    val specRedBorder: Color,
    val specRedContainer: Color,
    val specRedText: Color,
    val readyGreen: Color,
    val readyGreenBg: Color
)

private val DefaultDarkPalette = FrameScopeColorPalette(
    background = Color(0xFF0B0E14),
    cardSurface = Color(0xFF12161F),
    innerSurface = Color(0xFF181D28),
    elevatedSurface = Color(0xFF1E2432),
    borderSubtle = Color(0xFF252D3D),
    textPrimary = Color(0xFFF3F6FC),
    textSecondary = Color(0xFF94A3B8),
    textMuted = Color(0xFF64748B),
    selectionBlue = Color(0xFF3B82F6),
    selectionBlueTint = Color(0xFF15223B),
    selectionBlueIconBg = Color(0xFF2563EB),
    recordRed = Color(0xFFE5252A),
    recordRedDark = Color(0xFFB91C1C),
    specRedBorder = Color(0xFF7F1D1D),
    specRedContainer = Color(0xFF251215),
    specRedText = Color(0xFFF87171),
    readyGreen = Color(0xFF22C55E),
    readyGreenBg = Color(0xFF0E2A1E)
)

private val LocalFrameScopeColors = staticCompositionLocalOf { DefaultDarkPalette }

object FrameScopeColors {
    val Background: Color
        @Composable @ReadOnlyComposable get() = LocalFrameScopeColors.current.background
    val CardSurface: Color
        @Composable @ReadOnlyComposable get() = LocalFrameScopeColors.current.cardSurface
    val InnerSurface: Color
        @Composable @ReadOnlyComposable get() = LocalFrameScopeColors.current.innerSurface
    val ElevatedSurface: Color
        @Composable @ReadOnlyComposable get() = LocalFrameScopeColors.current.elevatedSurface
    val BorderSubtle: Color
        @Composable @ReadOnlyComposable get() = LocalFrameScopeColors.current.borderSubtle

    val TextPrimary: Color
        @Composable @ReadOnlyComposable get() = LocalFrameScopeColors.current.textPrimary
    val TextSecondary: Color
        @Composable @ReadOnlyComposable get() = LocalFrameScopeColors.current.textSecondary
    val TextMuted: Color
        @Composable @ReadOnlyComposable get() = LocalFrameScopeColors.current.textMuted

    val SelectionBlue: Color
        @Composable @ReadOnlyComposable get() = LocalFrameScopeColors.current.selectionBlue
    val SelectionBlueTint: Color
        @Composable @ReadOnlyComposable get() = LocalFrameScopeColors.current.selectionBlueTint
    val SelectionBlueIconBg: Color
        @Composable @ReadOnlyComposable get() = LocalFrameScopeColors.current.selectionBlueIconBg

    val RecordRed: Color
        @Composable @ReadOnlyComposable get() = LocalFrameScopeColors.current.recordRed
    val RecordRedDark: Color
        @Composable @ReadOnlyComposable get() = LocalFrameScopeColors.current.recordRedDark
    val SpecRedBorder: Color
        @Composable @ReadOnlyComposable get() = LocalFrameScopeColors.current.specRedBorder
    val SpecRedContainer: Color
        @Composable @ReadOnlyComposable get() = LocalFrameScopeColors.current.specRedContainer
    val SpecRedText: Color
        @Composable @ReadOnlyComposable get() = LocalFrameScopeColors.current.specRedText

    val ReadyGreen: Color
        @Composable @ReadOnlyComposable get() = LocalFrameScopeColors.current.readyGreen
    val ReadyGreenBg: Color
        @Composable @ReadOnlyComposable get() = LocalFrameScopeColors.current.readyGreenBg
}

private val FrameScopeTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 21.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
)

private val FrameScopeShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp)
)

@Composable
fun FrameScopeTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    val colorScheme = if (darkTheme) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }

    val palette = if (darkTheme) {
        FrameScopeColorPalette(
            background = colorScheme.background,
            cardSurface = colorScheme.surfaceContainerLow,
            innerSurface = colorScheme.surfaceContainer,
            elevatedSurface = colorScheme.surfaceContainerHigh,
            borderSubtle = colorScheme.outlineVariant,
            textPrimary = colorScheme.onSurface,
            textSecondary = colorScheme.onSurfaceVariant,
            textMuted = colorScheme.outline,
            selectionBlue = colorScheme.primary,
            selectionBlueTint = colorScheme.primaryContainer,
            selectionBlueIconBg = colorScheme.primaryContainer,
            recordRed = Color(0xFFE5252A),
            recordRedDark = Color(0xFFB91C1C),
            specRedBorder = Color(0xFF7F1D1D),
            specRedContainer = Color(0xFF2B1418),
            specRedText = Color(0xFFF87171),
            readyGreen = Color(0xFF22C55E),
            readyGreenBg = Color(0xFF0E2A1E)
        )
    } else {
        FrameScopeColorPalette(
            background = colorScheme.background,
            cardSurface = colorScheme.surfaceContainerLow,
            innerSurface = colorScheme.surfaceContainer,
            elevatedSurface = colorScheme.surfaceContainerHigh,
            borderSubtle = colorScheme.outlineVariant,
            textPrimary = colorScheme.onSurface,
            textSecondary = colorScheme.onSurfaceVariant,
            textMuted = colorScheme.outline,
            selectionBlue = colorScheme.primary,
            selectionBlueTint = colorScheme.primaryContainer,
            selectionBlueIconBg = colorScheme.primaryContainer,
            recordRed = Color(0xFFDC2626),
            recordRedDark = Color(0xFF991B1B),
            specRedBorder = Color(0xFFFCA5A5),
            specRedContainer = Color(0xFFFEE2E2),
            specRedText = Color(0xFFB91C1C),
            readyGreen = Color(0xFF15803D),
            readyGreenBg = Color(0xFFDCFCE7)
        )
    }

    CompositionLocalProvider(LocalFrameScopeColors provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = FrameScopeTypography,
            shapes = FrameScopeShapes,
            content = content
        )
    }
}
