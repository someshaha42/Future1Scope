package com.example.framescope.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object FrameScopeColors {
    val Background = Color(0xFF0B0E14)
    val CardSurface = Color(0xFF12161F)
    val InnerSurface = Color(0xFF181D28)
    val ElevatedSurface = Color(0xFF1E2432)
    val BorderSubtle = Color(0xFF252D3D)

    val TextPrimary = Color(0xFFF3F6FC)
    val TextSecondary = Color(0xFF94A3B8)
    val TextMuted = Color(0xFF64748B)

    val SelectionBlue = Color(0xFF3B82F6)
    val SelectionBlueTint = Color(0xFF15223B)
    val SelectionBlueIconBg = Color(0xFF2563EB)

    val RecordRed = Color(0xFFE5252A)
    val RecordRedDark = Color(0xFFB91C1C)
    val SpecRedBorder = Color(0xFF7F1D1D)
    val SpecRedContainer = Color(0xFF251215)
    val SpecRedText = Color(0xFFF87171)

    val ReadyGreen = Color(0xFF22C55E)
    val ReadyGreenBg = Color(0xFF0E2A1E)
}

private val FrameScopeDarkScheme = darkColorScheme(
    primary = FrameScopeColors.SelectionBlue,
    onPrimary = Color.White,
    primaryContainer = FrameScopeColors.SelectionBlueTint,
    onPrimaryContainer = FrameScopeColors.TextPrimary,
    secondary = FrameScopeColors.RecordRed,
    onSecondary = Color.White,
    secondaryContainer = FrameScopeColors.SpecRedContainer,
    onSecondaryContainer = FrameScopeColors.SpecRedText,
    background = FrameScopeColors.Background,
    onBackground = FrameScopeColors.TextPrimary,
    surface = FrameScopeColors.CardSurface,
    onSurface = FrameScopeColors.TextPrimary,
    surfaceVariant = FrameScopeColors.InnerSurface,
    onSurfaceVariant = FrameScopeColors.TextSecondary,
    outline = FrameScopeColors.BorderSubtle
)

private val FrameScopeTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        color = FrameScopeColors.TextPrimary
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        color = FrameScopeColors.TextPrimary
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        color = FrameScopeColors.TextPrimary
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        color = FrameScopeColors.TextPrimary
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = FrameScopeColors.TextSecondary
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = FrameScopeColors.TextSecondary
    )
)

private val FrameScopeShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp)
)

@Composable
fun FrameScopeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FrameScopeDarkScheme,
        typography = FrameScopeTypography,
        shapes = FrameScopeShapes,
        content = content
    )
}
