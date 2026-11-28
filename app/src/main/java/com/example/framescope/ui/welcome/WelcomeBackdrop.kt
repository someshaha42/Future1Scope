package com.example.framescope.ui.welcome

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.framescope.ui.theme.FrameScopeColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal enum class WelcomeElementId {
    SWAGATAM,
    PROFILE_PHOTO,
    LOCATION_PIN,
    S5_BADGE,
    INDIA_FLAG,
    INDIA_TEXT,
    PROFILE_ICON,
    RAJU_BERA,
    INDIA_GATE,
    WAVING_FLAG,
    FLYING_DOVES,
    TOP_LEAVES,
    FLOWERS,
    BOTTOM_CHAKRA
}

@Composable
internal fun PoppableWelcomeElement(
    elementId: WelcomeElementId,
    selectedId: WelcomeElementId?,
    onSelect: (WelcomeElementId) -> Unit,
    modifier: Modifier = Modifier,
    glowShape: Shape = RoundedCornerShape(16.dp),
    riseDp: Dp = 8.dp,
    popScale: Float = 1.05f,
    testTag: String? = null,
    content: @Composable BoxScope.(isSelected: Boolean) -> Unit
) {
    val isSelected = selectedId == elementId
    val animScale by animateFloatAsState(
        targetValue = if (isSelected) popScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "welcome_scale_${elementId.name}"
    )
    val animOffsetY by animateDpAsState(
        targetValue = if (isSelected) -riseDp else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "welcome_offset_${elementId.name}"
    )
    val animElevation by animateDpAsState(
        targetValue = if (isSelected) 16.dp else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "welcome_elev_${elementId.name}"
    )

    val baseModifier = if (testTag != null) modifier.testTag(testTag) else modifier

    Box(
        modifier = baseModifier
            .offset(y = animOffsetY)
            .graphicsLayer {
                scaleX = animScale
                scaleY = animScale
            }
            .then(
                if (animElevation > 0.5.dp) {
                    Modifier.shadow(
                        elevation = animElevation,
                        shape = glowShape,
                        ambientColor = FrameScopeColors.SelectionBlueLight,
                        spotColor = FrameScopeColors.SelectionBlue
                    )
                } else {
                    Modifier
                }
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onSelect(elementId)
            },
        contentAlignment = Alignment.Center
    ) {
        content(isSelected)
    }
}

@Composable
internal fun WelcomeScenicBackdrop(
    selectedId: WelcomeElementId?,
    onSelect: (WelcomeElementId) -> Unit,
    onBackgroundTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = FrameScopeColors.IsDark

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onBackgroundTap
            )
    ) {
        // Atmospheric Glassmorphism + Minimalist gradient sky with soft glowing orbs
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            val bgColors = if (isDark) {
                listOf(
                    Color(0xFF0A1228),
                    Color(0xFF152447),
                    Color(0xFF1D2448),
                    Color(0xFF0B101D)
                )
            } else {
                listOf(
                    Color(0xFF7CA7F9),
                    Color(0xFFAEC8FC),
                    Color(0xFFDCE7FB),
                    Color(0xFFEAF0FA)
                )
            }

            drawRect(
                brush = Brush.verticalGradient(colors = bgColors),
                size = Size(w, h)
            )

            // Soft luminous glassmorphism orbs (blue, periwinkle, warm sunset peach)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        if (isDark) Color(0x553B82F6) else Color(0x66FFFFFF),
                        Color.Transparent
                    ),
                    center = Offset(w * 0.50f, h * 0.34f),
                    radius = w * 0.58f
                ),
                center = Offset(w * 0.50f, h * 0.34f),
                radius = w * 0.58f
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        if (isDark) Color(0x448B5CF6) else Color(0x55F9A8D4),
                        Color.Transparent
                    ),
                    center = Offset(w * 0.88f, h * 0.44f),
                    radius = w * 0.46f
                ),
                center = Offset(w * 0.88f, h * 0.44f),
                radius = w * 0.46f
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        if (isDark) Color(0x330EA5E9) else Color(0x55FDE68A),
                        Color.Transparent
                    ),
                    center = Offset(w * 0.14f, h * 0.62f),
                    radius = w * 0.42f
                ),
                center = Offset(w * 0.14f, h * 0.62f),
                radius = w * 0.42f
            )
        }

        // Subtle minimalist ambient accents (preserving interactive testTags)
        PoppableWelcomeElement(
            elementId = WelcomeElementId.FLYING_DOVES,
            selectedId = selectedId,
            onSelect = onSelect,
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .align(Alignment.TopCenter)
                .offset(y = 92.dp),
            riseDp = 6.dp,
            popScale = 1.04f,
            testTag = "welcome_decor_doves"
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val silhouetteColor = Color.White.copy(alpha = if (isDark) 0.20f else 0.52f)
                drawDove(Offset(size.width * 0.14f, size.height * 0.42f), 20.dp.toPx(), flip = false, color = silhouetteColor)
                drawDove(Offset(size.width * 0.86f, size.height * 0.34f), 19.dp.toPx(), flip = true, color = silhouetteColor)
            }
        }

        PoppableWelcomeElement(
            elementId = WelcomeElementId.BOTTOM_CHAKRA,
            selectedId = selectedId,
            onSelect = onSelect,
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .align(Alignment.BottomCenter),
            riseDp = 4.dp,
            popScale = 1.04f,
            testTag = "welcome_decor_chakra"
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                drawAshokaChakra(
                    center = Offset(w * 0.50f, h * 0.50f),
                    radius = 18.dp.toPx(),
                    color = if (isDark) Color.White.copy(alpha = 0.25f) else Color(0xFF1D4ED8).copy(alpha = 0.22f)
                )
            }
        }
    }
}

private fun DrawScope.drawDove(center: Offset, wingSpan: Float, flip: Boolean, color: Color) {
    val dir = if (flip) -1f else 1f
    val wingPath = Path().apply {
        moveTo(center.x, center.y)
        quadraticTo(
            center.x - dir * wingSpan * 0.55f,
            center.y - wingSpan * 0.75f,
            center.x - dir * wingSpan,
            center.y - wingSpan * 0.35f
        )
        quadraticTo(
            center.x - dir * wingSpan * 0.45f,
            center.y - wingSpan * 0.10f,
            center.x,
            center.y + wingSpan * 0.18f
        )
        quadraticTo(
            center.x + dir * wingSpan * 0.45f,
            center.y - wingSpan * 0.25f,
            center.x + dir * wingSpan * 0.75f,
            center.y - wingSpan * 0.50f
        )
        quadraticTo(
            center.x + dir * wingSpan * 0.35f,
            center.y + wingSpan * 0.15f,
            center.x,
            center.y + wingSpan * 0.22f
        )
        close()
    }
    drawPath(wingPath, color)
}

internal fun DrawScope.drawAshokaChakra(center: Offset, radius: Float, color: Color) {
    drawCircle(color = color, radius = radius, center = center, style = Stroke(width = (radius * 0.14f).coerceAtLeast(1.2f)))
    drawCircle(color = color, radius = radius * 0.18f, center = center)
    for (i in 0 until 24) {
        val angle = (i * 15f) * PI.toFloat() / 180f
        val inner = Offset(center.x + cos(angle) * (radius * 0.18f), center.y + sin(angle) * (radius * 0.18f))
        val outer = Offset(center.x + cos(angle) * radius, center.y + sin(angle) * radius)
        drawLine(
            color = color,
            start = inner,
            end = outer,
            strokeWidth = (radius * 0.06f).coerceAtLeast(0.8f)
        )
    }
}

internal fun DrawScope.drawMiniIndianFlag() {
    val w = size.width
    val h = size.height
    val bandH = h / 3f
    drawRoundRect(
        color = Color.White,
        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
        size = Size(w, h)
    )
    drawRect(Color(0xFFFF7A00), Offset.Zero, Size(w, bandH))
    drawRect(Color.White, Offset(0f, bandH), Size(w, bandH))
    drawRect(Color(0xFF138808), Offset(0f, bandH * 2f), Size(w, bandH))
    drawAshokaChakra(Offset(w / 2f, h / 2f), bandH * 0.42f, Color(0xFF0A2A9F))
}
