package com.example.framescope.ui.components

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.framescope.ui.theme.FrameScopeColors

/**
 * Ambient atmospheric gradient backdrop that gives Glassmorphism cards depth and warmth
 * while preserving a clean, airy Minimalist foundation.
 */
@Composable
fun AeroGlassBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val bgTop = FrameScopeColors.Background
    val bgBottom = FrameScopeColors.BackgroundSecondary
    val orbOne = FrameScopeColors.AmbientOrbOne
    val orbTwo = FrameScopeColors.AmbientOrbTwo
    val orbThree = FrameScopeColors.AmbientOrbThree

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(bgTop, bgBottom)
                )
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(orbOne, Color.Transparent),
                    center = Offset(w * 0.16f, h * 0.14f),
                    radius = w * 0.65f
                ),
                center = Offset(w * 0.16f, h * 0.14f),
                radius = w * 0.65f
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(orbTwo, Color.Transparent),
                    center = Offset(w * 0.88f, h * 0.28f),
                    radius = w * 0.60f
                ),
                center = Offset(w * 0.88f, h * 0.28f),
                radius = w * 0.60f
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(orbThree, Color.Transparent),
                    center = Offset(w * 0.50f, h * 0.84f),
                    radius = w * 0.72f
                ),
                center = Offset(w * 0.50f, h * 0.84f),
                radius = w * 0.72f
            )
        }
        content()
    }
}

/**
 * Draws soft dual-direction Neumorphic shadows (top-left light highlight + bottom-right cool shadow).
 */
fun Modifier.neumorphicShadow(
    cornerRadius: Dp = 22.dp,
    elevation: Dp = 7.dp,
    lightColor: Color,
    darkColor: Color
): Modifier = this.drawBehind {
    val radiusPx = cornerRadius.toPx()
    val blurPx = (elevation.toPx() * 1.65f).coerceAtLeast(1f)
    val offsetPx = (elevation.toPx() * 0.72f).coerceAtLeast(1f)

    drawIntoCanvas { canvas ->
        val frameworkCanvas = canvas.nativeCanvas

        val darkPaint = Paint().apply {
            isAntiAlias = true
            color = darkColor.toArgb()
            maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
        }
        frameworkCanvas.drawRoundRect(
            offsetPx,
            offsetPx,
            size.width + offsetPx,
            size.height + offsetPx,
            radiusPx,
            radiusPx,
            darkPaint
        )

        val lightPaint = Paint().apply {
            isAntiAlias = true
            color = lightColor.toArgb()
            maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
        }
        frameworkCanvas.drawRoundRect(
            -offsetPx,
            -offsetPx,
            size.width - offsetPx,
            size.height - offsetPx,
            radiusPx,
            radiusPx,
            lightPaint
        )
    }
}

/**
 * Signature combined card:
 * - Neumorphic soft extruded dual shadows
 * - Glassmorphic translucent frosted gradient fill + specular edge rim
 * - Minimalist clean geometry
 */
@Composable
fun NeumorphicGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    elevation: Dp = 6.dp,
    accentBorder: Color? = null,
    fillColors: List<Color>? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    val lightShadow = FrameScopeColors.NeuShadowLight
    val darkShadow = FrameScopeColors.NeuShadowDark
    val fillTop = fillColors?.firstOrNull() ?: FrameScopeColors.GlassFillTop
    val fillBottom = fillColors?.lastOrNull() ?: FrameScopeColors.GlassFillBottom
    val rimTop = accentBorder?.copy(alpha = 0.85f) ?: FrameScopeColors.GlassRimTop
    val rimBottom = accentBorder?.copy(alpha = 0.28f) ?: FrameScopeColors.GlassRimBottom

    Box(
        modifier = modifier
            .fillMaxWidth()
            .neumorphicShadow(
                cornerRadius = cornerRadius,
                elevation = elevation,
                lightColor = lightShadow,
                darkColor = darkShadow
            )
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(fillTop, fillBottom),
                    start = Offset.Zero,
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                )
            )
            .border(
                width = if (accentBorder != null) 1.4.dp else 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(rimTop, rimBottom),
                    start = Offset.Zero,
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                ),
                shape = shape
            )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            content = content
        )
    }
}

/**
 * Circular Neumorphic + Glassmorphic icon button inspired by the Neumorphic top control bar.
 */
@Composable
fun NeumorphicIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    active: Boolean = false,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "neu_icon_scale"
    )

    val lightShadow = FrameScopeColors.NeuShadowLight
    val darkShadow = FrameScopeColors.NeuShadowDark
    val bgTop = if (active) FrameScopeColors.SelectionBlueTint else FrameScopeColors.GlassFillTop
    val bgBottom = if (active) FrameScopeColors.SelectionBlueTint.copy(alpha = 0.14f) else FrameScopeColors.InnerSurface
    val rimBrush = if (active) {
        Brush.linearGradient(
            listOf(
                FrameScopeColors.SelectionBlueLight,
                FrameScopeColors.SelectionBlue.copy(alpha = 0.55f)
            )
        )
    } else {
        Brush.linearGradient(
            listOf(
                FrameScopeColors.GlassRimTop,
                FrameScopeColors.GlassRimBottom
            )
        )
    }

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .size(size)
            .neumorphicShadow(
                cornerRadius = size / 2,
                elevation = if (isPressed) 2.dp else 5.dp,
                lightColor = lightShadow,
                darkColor = darkShadow
            )
            .clip(CircleShape)
            .background(
                brush = Brush.linearGradient(colors = listOf(bgTop, bgBottom))
            )
            .border(width = 1.dp, brush = rimBrush, shape = CircleShape)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/**
 * Interactive Neumorphic + Glassmorphic pill/chip container.
 */
@Composable
fun NeumorphicInteractiveSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    cornerRadius: Dp = 16.dp,
    selected: Boolean = false,
    enabled: Boolean = true,
    elevation: Dp = 4.dp,
    selectedColor: Color = FrameScopeColors.SelectionBlueTint,
    selectedBorderColor: Color = FrameScopeColors.SelectionBlue,
    content: @Composable BoxScope.() -> Unit
) {
    val lightShadow = FrameScopeColors.NeuShadowLight
    val darkShadow = FrameScopeColors.NeuShadowDark

    val fillBrush = if (selected) {
        Brush.linearGradient(
            colors = listOf(
                selectedColor.copy(alpha = (selectedColor.alpha + 0.12f).coerceAtMost(0.95f)),
                selectedColor
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                FrameScopeColors.GlassFillTop,
                FrameScopeColors.InnerSurface
            )
        )
    }

    val borderBrush = if (selected) {
        Brush.linearGradient(
            colors = listOf(
                selectedBorderColor,
                selectedBorderColor.copy(alpha = 0.45f)
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                FrameScopeColors.GlassRimTop,
                FrameScopeColors.GlassRimBottom
            )
        )
    }

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .neumorphicShadow(
                cornerRadius = cornerRadius,
                elevation = if (selected) elevation + 1.dp else elevation,
                lightColor = lightShadow,
                darkColor = darkShadow
            )
            .clip(shape)
            .background(brush = fillBrush)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                brush = borderBrush,
                shape = shape
            )
            .clickable(enabled = enabled, onClick = onClick),
        content = content
    )
}
