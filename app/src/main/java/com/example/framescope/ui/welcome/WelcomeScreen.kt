package com.example.framescope.ui.welcome

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.framescope.R
import com.example.framescope.ui.components.NeumorphicGlassCard
import com.example.framescope.ui.components.neumorphicShadow
import com.example.framescope.ui.theme.FrameScopeColors
import com.example.framescope.ui.theme.OutfitFontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun WelcomeScreen(
    onEnter: () -> Unit
) {
    var selectedElement by remember { mutableStateOf<WelcomeElementId?>(null) }
    var hasEntered by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val latestOnEnter by rememberUpdatedState(onEnter)
    val entranceProgress = remember { Animatable(0f) }
    val timelineProgress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch {
            entranceProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 850, easing = FastOutSlowInEasing)
            )
        }
        timelineProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 4000, easing = LinearEasing)
        )
        if (!hasEntered) {
            hasEntered = true
            latestOnEnter()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .testTag("welcome_screen_root")
    ) {
        val compactVertical = maxHeight < 660.dp
        val photoSize = (minOf(maxWidth * 0.50f, maxHeight * 0.26f)).coerceIn(136.dp, 206.dp)

        WelcomeScenicBackdrop(
            selectedId = selectedElement,
            onSelect = { id -> selectedElement = id },
            onBackgroundTap = { selectedElement = null }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp)
                .padding(top = if (compactVertical) 14.dp else 24.dp, bottom = 74.dp)
                .graphicsLayer {
                    val p = entranceProgress.value
                    alpha = 0.25f + (0.75f * p)
                    translationY = (1f - p) * 26f
                    val scale = 0.95f + (0.05f * p)
                    scaleX = scale
                    scaleY = scale
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Minimalist Welcome Header with subtle tricolor ribbon
            PoppableWelcomeElement(
                elementId = WelcomeElementId.SWAGATAM,
                selectedId = selectedElement,
                onSelect = { selectedElement = it },
                glowShape = RoundedCornerShape(22.dp),
                testTag = "welcome_swagatam"
            ) { isSelected ->
                SwagatamHeader(
                    compact = compactVertical,
                    isSelected = isSelected
                )
            }

            // 2. Neumorphic + Glassmorphic framed 1:1 Profile Photo
            PoppableWelcomeElement(
                elementId = WelcomeElementId.PROFILE_PHOTO,
                selectedId = selectedElement,
                onSelect = { selectedElement = it },
                glowShape = RoundedCornerShape(28.dp),
                popScale = 1.045f,
                testTag = "welcome_profile_photo"
            ) { isSelected ->
                val outerShape = RoundedCornerShape(28.dp)
                val innerShape = RoundedCornerShape(22.dp)
                Box(
                    modifier = Modifier
                        .size(photoSize + 16.dp)
                        .neumorphicShadow(
                            cornerRadius = 28.dp,
                            elevation = if (isSelected) 12.dp else 8.dp,
                            lightColor = FrameScopeColors.NeuShadowLight,
                            darkColor = FrameScopeColors.NeuShadowDark
                        )
                        .clip(outerShape)
                        .background(
                            brush = Brush.linearGradient(
                                listOf(FrameScopeColors.GlassFillTop, FrameScopeColors.GlassFillBottom)
                            )
                        )
                        .border(
                            width = if (isSelected) 2.dp else 1.25.dp,
                            brush = Brush.linearGradient(
                                listOf(
                                    if (isSelected) FrameScopeColors.SelectionBlue else FrameScopeColors.GlassRimTop,
                                    FrameScopeColors.GlassRimBottom
                                )
                            ),
                            shape = outerShape
                        )
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .aspectRatio(1f)
                            .clip(innerShape)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.welcome_profile_photo),
                            contentDescription = "Raju Bera",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // 3. Frosted Glass + Neumorphic Identity Card & Minimalist ENTER CTA
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 440.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (compactVertical) 12.dp else 16.dp)
            ) {
                NeumorphicGlassCard(
                    cornerRadius = 26.dp,
                    elevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        LocationInfoRow(
                            selectedElement = selectedElement,
                            onSelect = { selectedElement = it }
                        )

                        HorizontalDivider(
                            thickness = 1.dp,
                            color = FrameScopeColors.GlassRimBottom
                        )

                        ProfileNameRow(
                            selectedElement = selectedElement,
                            onSelect = { selectedElement = it }
                        )
                    }
                }

                WelcomeEnterButton(
                    enabled = !hasEntered,
                    onClick = {
                        if (!hasEntered) {
                            hasEntered = true
                            coroutineScope.launch {
                                delay(95L)
                                onEnter()
                            }
                        }
                    }
                )

                // Subtle 4-second glassmorphic progress bar synced to welcome animation
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.52f)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(FrameScopeColors.InsetTrack.copy(alpha = 0.65f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(timelineProgress.value.coerceIn(0f, 1f))
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(
                                brush = Brush.horizontalGradient(
                                    listOf(
                                        FrameScopeColors.SelectionBlueLight,
                                        FrameScopeColors.SelectionBlue
                                    )
                                )
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun SwagatamHeader(
    compact: Boolean,
    isSelected: Boolean
) {
    val fontSize = if (compact) 40.sp else 48.sp
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "স্বাগতম",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontSize = fontSize,
                fontWeight = FontWeight.ExtraBold,
                color = if (isSelected) FrameScopeColors.SelectionBlue else FrameScopeColors.TextPrimary
            )
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Minimalist curved tricolor accent swoosh
        Canvas(
            modifier = Modifier
                .width(if (compact) 160.dp else 196.dp)
                .height(10.dp)
        ) {
            val w = size.width
            val h = size.height
            val saffron = Path().apply {
                moveTo(0f, h * 0.45f)
                quadraticTo(w * 0.50f, 0f, w, h * 0.45f)
                quadraticTo(w * 0.50f, h * 0.25f, 0f, h * 0.62f)
                close()
            }
            val white = Path().apply {
                moveTo(0f, h * 0.62f)
                quadraticTo(w * 0.50f, h * 0.25f, w, h * 0.62f)
                quadraticTo(w * 0.50f, h * 0.48f, 0f, h * 0.80f)
                close()
            }
            val green = Path().apply {
                moveTo(w * 0.06f, h * 0.80f)
                quadraticTo(w * 0.50f, h * 0.48f, w * 0.94f, h * 0.80f)
                quadraticTo(w * 0.50f, h * 0.70f, w * 0.06f, h * 0.96f)
                close()
            }
            drawPath(saffron, Color(0xFFFF7A00))
            drawPath(white, Color.White)
            drawPath(green, Color(0xFF138808))
        }
    }
}

@Composable
private fun LocationInfoRow(
    selectedElement: WelcomeElementId?,
    onSelect: (WelcomeElementId) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        PoppableWelcomeElement(
            elementId = WelcomeElementId.LOCATION_PIN,
            selectedId = selectedElement,
            onSelect = onSelect,
            glowShape = CircleShape,
            popScale = 1.12f,
            testTag = "welcome_location_icon"
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(FrameScopeColors.SpecRedContainer)
                    .border(1.dp, FrameScopeColors.SpecRedBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.LocationOn,
                    contentDescription = "Location",
                    tint = FrameScopeColors.RecordRed,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        PoppableWelcomeElement(
            elementId = WelcomeElementId.S5_BADGE,
            selectedId = selectedElement,
            onSelect = onSelect,
            glowShape = RoundedCornerShape(12.dp),
            popScale = 1.08f,
            testTag = "welcome_s5_badge"
        ) { isSelected ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isSelected) FrameScopeColors.SelectionBlue else FrameScopeColors.SelectionBlueTint,
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isSelected) Color.White.copy(alpha = 0.6f) else FrameScopeColors.SelectionBlue.copy(alpha = 0.45f)
                )
            ) {
                Text(
                    text = "S 5",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = if (isSelected) Color.White else FrameScopeColors.SelectionBlue,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PoppableWelcomeElement(
                elementId = WelcomeElementId.INDIA_FLAG,
                selectedId = selectedElement,
                onSelect = onSelect,
                glowShape = RoundedCornerShape(6.dp),
                popScale = 1.12f,
                testTag = "welcome_india_flag"
            ) {
                Canvas(
                    modifier = Modifier
                        .size(width = 34.dp, height = 23.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .border(1.dp, FrameScopeColors.GlassRimTop, RoundedCornerShape(5.dp))
                ) {
                    drawMiniIndianFlag()
                }
            }

            PoppableWelcomeElement(
                elementId = WelcomeElementId.INDIA_TEXT,
                selectedId = selectedElement,
                onSelect = onSelect,
                glowShape = RoundedCornerShape(8.dp),
                popScale = 1.08f,
                testTag = "welcome_india_text"
            ) {
                Text(
                    text = "India",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    ),
                    color = FrameScopeColors.TextPrimary,
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun ProfileNameRow(
    selectedElement: WelcomeElementId?,
    onSelect: (WelcomeElementId) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        PoppableWelcomeElement(
            elementId = WelcomeElementId.PROFILE_ICON,
            selectedId = selectedElement,
            onSelect = onSelect,
            glowShape = CircleShape,
            popScale = 1.12f,
            testTag = "welcome_profile_icon"
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(
                        brush = Brush.linearGradient(
                            listOf(FrameScopeColors.SelectionBlueLight, FrameScopeColors.SelectionBlue)
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Person,
                    contentDescription = "Profile",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        PoppableWelcomeElement(
            elementId = WelcomeElementId.RAJU_BERA,
            selectedId = selectedElement,
            onSelect = onSelect,
            glowShape = RoundedCornerShape(10.dp),
            popScale = 1.07f,
            testTag = "welcome_raju_bera_text"
        ) {
            Text(
                text = "Raju Bera",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 21.sp
                ),
                color = FrameScopeColors.TextPrimary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun WelcomeEnterButton(
    enabled: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "enter_btn_scale"
    )
    val pillShape = RoundedCornerShape(50)

    Box(
        modifier = Modifier
            .fillMaxWidth(0.78f)
            .minimumInteractiveComponentSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = 14.dp,
                shape = pillShape,
                ambientColor = FrameScopeColors.SelectionBlue.copy(alpha = 0.45f),
                spotColor = FrameScopeColors.SelectionBlue.copy(alpha = 0.6f)
            )
            .clip(pillShape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        FrameScopeColors.SelectionBlueLight,
                        FrameScopeColors.SelectionBlue
                    )
                )
            )
            .border(
                width = 1.25.dp,
                color = Color.White.copy(alpha = 0.45f),
                shape = pillShape
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .testTag("welcome_enter_button")
            .padding(vertical = 15.dp, horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "ENTER",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    letterSpacing = 1.2.sp
                ),
                color = Color.White
            )
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
