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
    riseDp: Dp = 10.dp,
    popScale: Float = 1.06f,
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
        targetValue = if (isSelected) 18.dp else 0.dp,
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
                        ambientColor = Color(0xFFFFF59D),
                        spotColor = Color(0xFFFFEB3B)
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
    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onBackgroundTap
            )
    ) {
        // Full-bleed sky, clouds, sunlit horizon, and lush green meadow
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val horizonY = h * 0.56f

            // Sky gradient
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF065CE8),
                        Color(0xFF2196F3),
                        Color(0xFF7DD3FC),
                        Color(0xFFE0F7FA)
                    ),
                    startY = 0f,
                    endY = horizonY
                ),
                size = Size(w, horizonY + 16f)
            )

            // Center radiant sun glow behind the portrait
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFFFFFFF).copy(alpha = 0.72f),
                        Color(0xFFB3E5FC).copy(alpha = 0.28f),
                        Color.Transparent
                    ),
                    center = Offset(w * 0.5f, h * 0.37f),
                    radius = w * 0.55f
                ),
                center = Offset(w * 0.5f, h * 0.37f),
                radius = w * 0.55f
            )

            // Soft clouds
            drawCloudCluster(Offset(w * 0.14f, h * 0.42f), w * 0.18f)
            drawCloudCluster(Offset(w * 0.86f, h * 0.40f), w * 0.20f)
            drawCloudCluster(Offset(w * 0.22f, h * 0.23f), w * 0.11f)

            // Meadow grass lower half
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF76E029),
                        Color(0xFF2EC425),
                        Color(0xFF159418),
                        Color(0xFF0A6310)
                    ),
                    startY = horizonY - 20f,
                    endY = h
                ),
                topLeft = Offset(0f, horizonY - 20f),
                size = Size(w, h - horizonY + 20f)
            )

            // Sunburst glow near bottom behind ENTER & Chakra
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFFFF176).copy(alpha = 0.55f),
                        Color(0xFFAEEA00).copy(alpha = 0.18f),
                        Color.Transparent
                    ),
                    center = Offset(w * 0.5f, h * 0.88f),
                    radius = w * 0.52f
                ),
                center = Offset(w * 0.5f, h * 0.88f),
                radius = w * 0.52f
            )

            // Horizon foliage silhouettes
            drawTreeCluster(Offset(w * 0.06f, horizonY), w * 0.14f)
            drawTreeCluster(Offset(w * 0.94f, horizonY), w * 0.15f)
        }

        // Top corner green leaves (clickable decorative element)
        PoppableWelcomeElement(
            elementId = WelcomeElementId.TOP_LEAVES,
            selectedId = selectedId,
            onSelect = onSelect,
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .align(Alignment.TopCenter),
            riseDp = 6.dp,
            popScale = 1.03f,
            testTag = "welcome_decor_leaves"
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawLeafCluster(topLeft = true)
                drawLeafCluster(topLeft = false)
            }
        }

        // Flying white doves (clickable decorative element)
        PoppableWelcomeElement(
            elementId = WelcomeElementId.FLYING_DOVES,
            selectedId = selectedId,
            onSelect = onSelect,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .align(Alignment.TopCenter)
                .offset(y = 115.dp),
            riseDp = 10.dp,
            popScale = 1.05f,
            testTag = "welcome_decor_doves"
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawDove(Offset(size.width * 0.14f, size.height * 0.36f), 26.dp.toPx(), flip = false)
                drawDove(Offset(size.width * 0.88f, size.height * 0.30f), 25.dp.toPx(), flip = true)
                drawDove(Offset(size.width * 0.72f, size.height * 0.54f), 15.dp.toPx(), flip = false)
            }
        }

        // India Gate monument on mid-left (clickable decorative element)
        PoppableWelcomeElement(
            elementId = WelcomeElementId.INDIA_GATE,
            selectedId = selectedId,
            onSelect = onSelect,
            modifier = Modifier
                .size(width = 118.dp, height = 160.dp)
                .align(Alignment.CenterStart)
                .offset(x = 8.dp, y = (-36).dp),
            riseDp = 10.dp,
            popScale = 1.06f,
            testTag = "welcome_decor_india_gate"
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawIndiaGate()
            }
        }

        // Waving Indian National Flag on mid-right (clickable decorative element)
        PoppableWelcomeElement(
            elementId = WelcomeElementId.WAVING_FLAG,
            selectedId = selectedId,
            onSelect = onSelect,
            modifier = Modifier
                .size(width = 112.dp, height = 176.dp)
                .align(Alignment.CenterEnd)
                .offset(x = (-8).dp, y = (-36).dp),
            riseDp = 10.dp,
            popScale = 1.06f,
            testTag = "welcome_decor_flag"
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawFlagOnPole()
            }
        }

        // Pink flowers along lower grass edges (clickable decorative element)
        PoppableWelcomeElement(
            elementId = WelcomeElementId.FLOWERS,
            selectedId = selectedId,
            onSelect = onSelect,
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .align(Alignment.BottomCenter)
                .offset(y = (-68).dp),
            riseDp = 8.dp,
            popScale = 1.04f,
            testTag = "welcome_decor_flowers"
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawWildFlower(Offset(size.width * 0.06f, size.height * 0.25f), 13.dp.toPx())
                drawWildFlower(Offset(size.width * 0.09f, size.height * 0.56f), 16.dp.toPx())
                drawWildFlower(Offset(size.width * 0.94f, size.height * 0.26f), 14.dp.toPx())
                drawWildFlower(Offset(size.width * 0.90f, size.height * 0.54f), 16.dp.toPx())
            }
        }

        // Bottom tricolor ribbon & Ashoka Chakra emblem (clickable decorative element)
        PoppableWelcomeElement(
            elementId = WelcomeElementId.BOTTOM_CHAKRA,
            selectedId = selectedId,
            onSelect = onSelect,
            modifier = Modifier
                .fillMaxWidth()
                .height(82.dp)
                .align(Alignment.BottomCenter),
            riseDp = 8.dp,
            popScale = 1.04f,
            testTag = "welcome_decor_chakra"
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawBottomTricolorEmblem()
            }
        }
    }
}

private fun DrawScope.drawCloudCluster(center: Offset, width: Float) {
    val cloudColor = Color.White.copy(alpha = 0.85f)
    drawCircle(cloudColor, width * 0.34f, center)
    drawCircle(cloudColor, width * 0.27f, Offset(center.x - width * 0.26f, center.y + width * 0.08f))
    drawCircle(cloudColor, width * 0.25f, Offset(center.x + width * 0.25f, center.y + width * 0.10f))
}

private fun DrawScope.drawTreeCluster(base: Offset, radius: Float) {
    drawCircle(Color(0xFF1B8A20), radius, Offset(base.x, base.y - radius * 0.3f))
    drawCircle(Color(0xFF2EB82E), radius * 0.72f, Offset(base.x - radius * 0.45f, base.y - radius * 0.15f))
    drawCircle(Color(0xFF39C936), radius * 0.68f, Offset(base.x + radius * 0.45f, base.y - radius * 0.12f))
}

private fun DrawScope.drawLeafCluster(topLeft: Boolean) {
    val leafDark = Color(0xFF2E9E18)
    val leafBright = Color(0xFF76D624)
    val sign = if (topLeft) 1f else -1f
    val startX = if (topLeft) 0f else size.width

    val angles = listOf(15f, 38f, 62f, 80f)
    angles.forEachIndexed { idx, deg ->
        val rad = deg * PI.toFloat() / 180f
        val len = (42 + idx * 8).dp.toPx()
        val tip = Offset(startX + sign * cos(rad) * len, sin(rad) * len)
        val mid = Offset(startX + sign * cos(rad) * (len * 0.55f), sin(rad) * (len * 0.55f))
        val path = Path().apply {
            moveTo(startX, 0f)
            quadraticTo(mid.x - sign * 14.dp.toPx(), mid.y + 12.dp.toPx(), tip.x, tip.y)
            quadraticTo(mid.x + sign * 14.dp.toPx(), mid.y - 8.dp.toPx(), startX, 0f)
            close()
        }
        drawPath(path, if (idx % 2 == 0) leafBright else leafDark)
    }
}

private fun DrawScope.drawDove(center: Offset, wingSpan: Float, flip: Boolean) {
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
    drawPath(wingPath, Color.White)
}

private fun DrawScope.drawIndiaGate() {
    val w = size.width
    val h = size.height
    val stoneLight = Color(0xFFF3B882)
    val stoneMid = Color(0xFFDF9862)
    val stoneDark = Color(0xFFB56E3E)

    // Stepped top crown
    drawRect(stoneMid, Offset(w * 0.16f, h * 0.08f), Size(w * 0.68f, h * 0.07f))
    drawRect(stoneLight, Offset(w * 0.12f, h * 0.15f), Size(w * 0.76f, h * 0.09f))
    drawRect(stoneDark, Offset(w * 0.12f, h * 0.23f), Size(w * 0.76f, h * 0.025f))

    // Main gate body with central arch cutout
    val archPath = Path().apply {
        moveTo(w * 0.14f, h * 0.255f)
        lineTo(w * 0.86f, h * 0.255f)
        lineTo(w * 0.90f, h * 0.90f)
        lineTo(w * 0.66f, h * 0.90f)
        lineTo(w * 0.66f, h * 0.58f)
        quadraticTo(w * 0.50f, h * 0.41f, w * 0.34f, h * 0.58f)
        lineTo(w * 0.34f, h * 0.90f)
        lineTo(w * 0.10f, h * 0.90f)
        close()
    }
    drawPath(archPath, stoneLight)
    drawPath(archPath, stoneDark, style = Stroke(width = 1.5.dp.toPx()))
}

private fun DrawScope.drawFlagOnPole() {
    val w = size.width
    val h = size.height
    val poleX = w * 0.14f

    // Pole + golden finial
    drawLine(
        color = Color(0xFFD4AF37),
        start = Offset(poleX, h * 0.08f),
        end = Offset(poleX, h * 0.94f),
        strokeWidth = 4.dp.toPx(),
        cap = StrokeCap.Round
    )
    drawCircle(Color(0xFFFFD54F), 5.dp.toPx(), Offset(poleX, h * 0.065f))

    val flagLeft = poleX + 2.dp.toPx()
    val flagRight = w * 0.94f
    val bandH = h * 0.135f
    val topY = h * 0.12f

    // Saffron
    drawWaveBand(flagLeft, flagRight, topY, bandH, Color(0xFFFF7A00))
    // White
    drawWaveBand(flagLeft, flagRight, topY + bandH, bandH, Color.White)
    // Green
    drawWaveBand(flagLeft, flagRight, topY + bandH * 2f, bandH, Color(0xFF138808))

    // Ashoka Chakra in white band
    val chakraCenter = Offset((flagLeft + flagRight) / 2f, topY + bandH * 1.52f)
    val chakraR = bandH * 0.42f
    drawAshokaChakra(chakraCenter, chakraR, Color(0xFF0A2A9F))
}

private fun DrawScope.drawWaveBand(left: Float, right: Float, top: Float, height: Float, color: Color) {
    val mid = (left + right) / 2f
    val path = Path().apply {
        moveTo(left, top)
        quadraticTo(mid, top + 6.dp.toPx(), right, top + 4.dp.toPx())
        lineTo(right, top + height + 4.dp.toPx())
        quadraticTo(mid, top + height + 6.dp.toPx(), left, top + height)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawWildFlower(center: Offset, radius: Float) {
    val petalColor = Color(0xFFEC4899)
    val centerColor = Color(0xFFFACC15)
    for (i in 0 until 6) {
        val angle = (i * 60f) * PI.toFloat() / 180f
        val pCenter = Offset(
            center.x + cos(angle) * radius * 0.65f,
            center.y + sin(angle) * radius * 0.65f
        )
        drawCircle(petalColor, radius * 0.45f, pCenter)
    }
    drawCircle(centerColor, radius * 0.35f, center)
}

private fun DrawScope.drawBottomTricolorEmblem() {
    val w = size.width
    val h = size.height
    val bandH = h * 0.25f

    // Saffron ribbon wave
    val saffron = Path().apply {
        moveTo(0f, h * 0.14f)
        quadraticTo(w * 0.25f, h * 0.36f, w * 0.50f, h * 0.40f)
        quadraticTo(w * 0.75f, h * 0.36f, w, h * 0.14f)
        lineTo(w, h * 0.14f + bandH)
        quadraticTo(w * 0.75f, h * 0.36f + bandH, w * 0.50f, h * 0.40f + bandH)
        quadraticTo(w * 0.25f, h * 0.36f + bandH, 0f, h * 0.14f + bandH)
        close()
    }
    drawPath(saffron, Color(0xFFFF7A00))

    // White ribbon wave
    val white = Path().apply {
        moveTo(0f, h * 0.14f + bandH)
        quadraticTo(w * 0.25f, h * 0.36f + bandH, w * 0.50f, h * 0.40f + bandH)
        quadraticTo(w * 0.75f, h * 0.36f + bandH, w, h * 0.14f + bandH)
        lineTo(w, h * 0.14f + bandH * 2f)
        quadraticTo(w * 0.75f, h * 0.36f + bandH * 2f, w * 0.50f, h * 0.40f + bandH * 2f)
        quadraticTo(w * 0.25f, h * 0.36f + bandH * 2f, 0f, h * 0.14f + bandH * 2f)
        close()
    }
    drawPath(white, Color.White)

    // Green ribbon wave
    val green = Path().apply {
        moveTo(0f, h * 0.14f + bandH * 2f)
        quadraticTo(w * 0.25f, h * 0.36f + bandH * 2f, w * 0.50f, h * 0.40f + bandH * 2f)
        quadraticTo(w * 0.75f, h * 0.36f + bandH * 2f, w, h * 0.14f + bandH * 2f)
        lineTo(w, h)
        lineTo(0f, h)
        close()
    }
    drawPath(green, Color(0xFF138808))

    // Centered glowing Ashoka Chakra medallion
    val chakraCenter = Offset(w * 0.50f, h * 0.54f)
    val medR = 23.dp.toPx()
    drawCircle(Color.White, medR + 3.dp.toPx(), chakraCenter)
    drawAshokaChakra(chakraCenter, medR, Color(0xFF0A2A9F))
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
