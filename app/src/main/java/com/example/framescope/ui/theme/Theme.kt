package com.example.framescope.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.framescope.R

val OutfitFontFamily = FontFamily(
    Font(R.font.outfit, FontWeight.Normal),
    Font(R.font.outfit, FontWeight.Medium),
    Font(R.font.outfit, FontWeight.SemiBold),
    Font(R.font.outfit, FontWeight.Bold),
    Font(R.font.outfit, FontWeight.ExtraBold)
)

val PlusJakartaSansFontFamily = FontFamily(
    Font(R.font.plus_jakarta_sans, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans, FontWeight.Bold)
)

@Immutable
data class FrameScopeColorPalette(
    val isDark: Boolean,
    val background: Color,
    val backgroundSecondary: Color,
    val cardSurface: Color,
    val innerSurface: Color,
    val elevatedSurface: Color,
    val borderSubtle: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val selectionBlue: Color,
    val selectionBlueLight: Color,
    val selectionBlueTint: Color,
    val selectionBlueIconBg: Color,
    val recordRed: Color,
    val recordRedDark: Color,
    val specRedBorder: Color,
    val specRedContainer: Color,
    val specRedText: Color,
    val readyGreen: Color,
    val readyGreenBg: Color,
    // Combined Neumorphic + Glassmorphism + Minimalist tokens
    val neuShadowLight: Color,
    val neuShadowDark: Color,
    val glassFillTop: Color,
    val glassFillBottom: Color,
    val glassRimTop: Color,
    val glassRimBottom: Color,
    val ambientOrbOne: Color,
    val ambientOrbTwo: Color,
    val ambientOrbThree: Color,
    val insetTrack: Color
)

private val AeroGlassLightPalette = FrameScopeColorPalette(
    isDark = false,
    background = Color(0xFFEAF0FA),
    backgroundSecondary = Color(0xFFF3F6FC),
    cardSurface = Color(0xB8F5F8FF),
    innerSurface = Color(0xFFE6EDF8),
    elevatedSurface = Color(0xFFF1F5FD),
    borderSubtle = Color(0x99FFFFFF),
    textPrimary = Color(0xFF131C2E),
    textSecondary = Color(0xFF4F5D78),
    textMuted = Color(0xFF7B89A4),
    selectionBlue = Color(0xFF2563EB),
    selectionBlueLight = Color(0xFF60A5FA),
    selectionBlueTint = Color(0x283B82F6),
    selectionBlueIconBg = Color(0xFF2563EB),
    recordRed = Color(0xFFE11D48),
    recordRedDark = Color(0xFFBE123C),
    specRedBorder = Color(0x66F43F5E),
    specRedContainer = Color(0x1EE11D48),
    specRedText = Color(0xFFBE123C),
    readyGreen = Color(0xFF15803D),
    readyGreenBg = Color(0x2016A34A),
    neuShadowLight = Color(0xF2FFFFFF),
    neuShadowDark = Color(0x9CB8C7E0),
    glassFillTop = Color(0xCCFFFFFF),
    glassFillBottom = Color(0x88EBF1FC),
    glassRimTop = Color(0xE6FFFFFF),
    glassRimBottom = Color(0x55C7D5EC),
    ambientOrbOne = Color(0x5260A5FA),
    ambientOrbTwo = Color(0x45818CF8),
    ambientOrbThree = Color(0x3AF9A8D4),
    insetTrack = Color(0xFFD9E3F2)
)

private val AeroGlassDarkPalette = FrameScopeColorPalette(
    isDark = true,
    background = Color(0xFF0C111D),
    backgroundSecondary = Color(0xFF121928),
    cardSurface = Color(0xA6172033),
    innerSurface = Color(0xFF141C2B),
    elevatedSurface = Color(0xFF1B2538),
    borderSubtle = Color(0x2EFFFFFF),
    textPrimary = Color(0xFFF1F5FD),
    textSecondary = Color(0xFFA0AEC6),
    textMuted = Color(0xFF6E7D99),
    selectionBlue = Color(0xFF4F8BFF),
    selectionBlueLight = Color(0xFF93C5FD),
    selectionBlueTint = Color(0x333B82F6),
    selectionBlueIconBg = Color(0xFF2563EB),
    recordRed = Color(0xFFF43F5E),
    recordRedDark = Color(0xFFE11D48),
    specRedBorder = Color(0x66FB7185),
    specRedContainer = Color(0x26E11D48),
    specRedText = Color(0xFFFB7185),
    readyGreen = Color(0xFF4ADE80),
    readyGreenBg = Color(0x2622C55E),
    neuShadowLight = Color(0x55293650),
    neuShadowDark = Color(0xCC04070D),
    glassFillTop = Color(0x22FFFFFF),
    glassFillBottom = Color(0x0E94A3B8),
    glassRimTop = Color(0x42FFFFFF),
    glassRimBottom = Color(0x12FFFFFF),
    ambientOrbOne = Color(0x3D2563EB),
    ambientOrbTwo = Color(0x336366F1),
    ambientOrbThree = Color(0x260EA5E9),
    insetTrack = Color(0xFF0A0F1A)
)

private val LocalFrameScopeColors = staticCompositionLocalOf { AeroGlassLightPalette }

object FrameScopeColors {
    val IsDark: Boolean
        @Composable @ReadOnlyComposable get() = LocalFrameScopeColors.current.isDark
    val Background: Color
        @Composable @ReadOnlyComposable get() = LocalFrameScopeColors.current.background
    val BackgroundSecondary: Color
        @Composable @ReadOnlyComposable get() = LocalFrameScopeColors.current.backgroundSecondary
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
    val SelectionBlueLight: Color
        @Composable @ReadOnlyComposable get() = LocalFrameScopeColors.current.selectionBlueLight
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

    val NeuShadowLight: Color
        @Composable @ReadOnlyComposable get() = LocalFrameScopeColors.current.neuShadowLight
    val NeuShadowDark: Color
        @Composable @ReadOnlyComposable get() = LocalFrameScopeColors.current.neuShadowDark
    val GlassFillTop: Color
        @Composable @ReadOnlyComposable get() = LocalFrameScopeColors.current.glassFillTop
    val GlassFillBottom: Color
        @Composable @ReadOnlyComposable get() = LocalFrameScopeColors.current.glassFillBottom
    val GlassRimTop: Color
        @Composable @ReadOnlyComposable get() = LocalFrameScopeColors.current.glassRimTop
    val GlassRimBottom: Color
        @Composable @ReadOnlyComposable get() = LocalFrameScopeColors.current.glassRimBottom
    val AmbientOrbOne: Color
        @Composable @ReadOnlyComposable get() = LocalFrameScopeColors.current.ambientOrbOne
    val AmbientOrbTwo: Color
        @Composable @ReadOnlyComposable get() = LocalFrameScopeColors.current.ambientOrbTwo
    val AmbientOrbThree: Color
        @Composable @ReadOnlyComposable get() = LocalFrameScopeColors.current.ambientOrbThree
    val InsetTrack: Color
        @Composable @ReadOnlyComposable get() = LocalFrameScopeColors.current.insetTrack
}

private val FrameScopeTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.5).sp
    ),
    titleLarge = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.25).sp
    ),
    titleMedium = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.1).sp
    ),
    bodyLarge = TextStyle(
        fontFamily = PlusJakartaSansFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 21.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = PlusJakartaSansFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontFamily = PlusJakartaSansFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
)

private val FrameScopeShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp)
)

private val LightM3Scheme = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE7FE),
    onPrimaryContainer = Color(0xFF0F2B6B),
    secondary = Color(0xFF4F5D78),
    onSecondary = Color.White,
    background = Color(0xFFEAF0FA),
    onBackground = Color(0xFF131C2E),
    surface = Color(0xFFF3F6FC),
    onSurface = Color(0xFF131C2E),
    surfaceVariant = Color(0xFFE1E9F6),
    onSurfaceVariant = Color(0xFF4F5D78),
    outline = Color(0xFF8A97B0),
    outlineVariant = Color(0xFFC9D4E8),
    error = Color(0xFFE11D48),
    onError = Color.White
)

private val DarkM3Scheme = darkColorScheme(
    primary = Color(0xFF4F8BFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1A3365),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFFA0AEC6),
    onSecondary = Color(0xFF0C111D),
    background = Color(0xFF0C111D),
    onBackground = Color(0xFFF1F5FD),
    surface = Color(0xFF131A29),
    onSurface = Color(0xFFF1F5FD),
    surfaceVariant = Color(0xFF1B2538),
    onSurfaceVariant = Color(0xFFA0AEC6),
    outline = Color(0xFF6E7D99),
    outlineVariant = Color(0xFF27334A),
    error = Color(0xFFF43F5E),
    onError = Color.White
)

@Composable
fun FrameScopeTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = if (darkTheme) DarkM3Scheme else LightM3Scheme
    val palette = if (darkTheme) AeroGlassDarkPalette else AeroGlassLightPalette

    CompositionLocalProvider(LocalFrameScopeColors provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = FrameScopeTypography,
            shapes = FrameScopeShapes,
            content = content
        )
    }
}
