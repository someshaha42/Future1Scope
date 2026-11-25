package com.example.framescope.ui.welcome

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.framescope.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun WelcomeScreen(
    onEnter: () -> Unit
) {
    var selectedElement by remember { mutableStateOf<WelcomeElementId?>(null) }
    var hasEntered by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Pressing system Back on the Welcome Screen also enters the application
    BackHandler(enabled = !hasEntered) {
        if (!hasEntered) {
            hasEntered = true
            onEnter()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .testTag("welcome_screen_root")
    ) {
        val compactVertical = maxHeight < 660.dp
        val photoSize = (minOf(maxWidth * 0.54f, maxHeight * 0.28f)).coerceIn(140.dp, 224.dp)

        // Full-screen scenic backdrop + clickable decorative elements
        WelcomeScenicBackdrop(
            selectedId = selectedElement,
            onSelect = { id -> selectedElement = id },
            onBackgroundTap = { selectedElement = null }
        )

        // Foreground interactive Welcome elements
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp)
                .padding(top = if (compactVertical) 8.dp else 16.dp, bottom = 70.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Bengali Welcome Title + Tricolor Swoosh
            PoppableWelcomeElement(
                elementId = WelcomeElementId.SWAGATAM,
                selectedId = selectedElement,
                onSelect = { selectedElement = it },
                glowShape = RoundedCornerShape(20.dp),
                testTag = "welcome_swagatam"
            ) { isSelected ->
                SwagatamHeader(
                    compact = compactVertical,
                    isSelected = isSelected
                )
            }

            // 2. Actual 1:1 Square Profile Photograph with slightly rounded corners (NOT circular)
            PoppableWelcomeElement(
                elementId = WelcomeElementId.PROFILE_PHOTO,
                selectedId = selectedElement,
                onSelect = { selectedElement = it },
                glowShape = RoundedCornerShape(20.dp),
                popScale = 1.045f,
                testTag = "welcome_profile_photo"
            ) { isSelected ->
                val shape = RoundedCornerShape(20.dp)
                Box(
                    modifier = Modifier
                        .size(photoSize)
                        .aspectRatio(1f)
                        .shadow(
                            elevation = if (isSelected) 22.dp else 12.dp,
                            shape = shape,
                            ambientColor = Color(0xFFFFF59D),
                            spotColor = Color(0xFF000000)
                        )
                        .clip(shape)
                        .border(
                            width = if (isSelected) 3.dp else 2.5.dp,
                            brush = Brush.linearGradient(
                                colors = if (isSelected) {
                                    listOf(Color(0xFFFFF176), Color.White, Color(0xFFFFD54F))
                                } else {
                                    listOf(Color.White.copy(alpha = 0.92f), Color(0xFFFFE082))
                                }
                            ),
                            shape = shape
                        )
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.welcome_profile_photo),
                        contentDescription = "Raju Bera",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // 3. Info Pills & ENTER Button Column
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 460.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (compactVertical) 8.dp else 12.dp)
            ) {
                // Blue Pill: Location Pin | [S 5] | India Flag + India
                LocationInfoPill(
                    selectedElement = selectedElement,
                    onSelect = { selectedElement = it }
                )

                // Purple Pill: Profile Icon | Raju Bera
                ProfileNamePill(
                    selectedElement = selectedElement,
                    onSelect = { selectedElement = it }
                )

                // Large Prominent Green ENTER Button (ONLY navigation trigger)
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
            }
        }
    }
}

@Composable
private fun SwagatamHeader(
    compact: Boolean,
    isSelected: Boolean
) {
    val fontSize = if (compact) 44.sp else 54.sp
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            // 3D drop shadow layer
            Text(
                text = "স্বাগতম",
                style = TextStyle(
                    fontSize = fontSize,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF7F0000)
                ),
                modifier = Modifier.offset(x = 2.dp, y = 4.dp)
            )
            // White outline halo
            Text(
                text = "স্বাগতম",
                style = TextStyle(
                    fontSize = fontSize,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    drawStyle = Stroke(
                        width = 11f,
                        join = StrokeJoin.Round
                    )
                )
            )
            // Primary vivid red fill
            Text(
                text = "স্বাগতম",
                style = TextStyle(
                    fontSize = fontSize,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isSelected) Color(0xFFFF2E2E) else Color(0xFFE51515)
                )
            )
        }

        // Curved tricolor swoosh beneath স্বাগতম
        Canvas(
            modifier = Modifier
                .width(if (compact) 210.dp else 248.dp)
                .height(18.dp)
        ) {
            val w = size.width
            val h = size.height
            val saffron = Path().apply {
                moveTo(0f, h * 0.75f)
                quadraticTo(w * 0.50f, -h * 0.10f, w, h * 0.60f)
                quadraticTo(w * 0.50f, h * 0.25f, 0f, h * 0.90f)
                close()
            }
            val white = Path().apply {
                moveTo(0f, h * 0.88f)
                quadraticTo(w * 0.50f, h * 0.20f, w, h * 0.68f)
                quadraticTo(w * 0.50f, h * 0.45f, 0f, h)
                close()
            }
            val green = Path().apply {
                moveTo(w * 0.08f, h * 0.95f)
                quadraticTo(w * 0.52f, h * 0.40f, w, h * 0.78f)
                quadraticTo(w * 0.52f, h * 0.65f, w * 0.08f, h)
                close()
            }
            drawPath(saffron, Color(0xFFFF7A00))
            drawPath(white, Color.White)
            drawPath(green, Color(0xFF138808))
        }
    }
}

@Composable
private fun LocationInfoPill(
    selectedElement: WelcomeElementId?,
    onSelect: (WelcomeElementId) -> Unit
) {
    val pillShape = RoundedCornerShape(30.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(10.dp, pillShape, spotColor = Color(0xFF041C68))
            .clip(pillShape)
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color(0xFF0D5BF2),
                        Color(0xFF0A43C4),
                        Color(0xFF072B8A)
                    )
                )
            )
            .border(
                width = 2.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color(0xFF93C5FD),
                        Color(0xFFFDE047),
                        Color(0xFF93C5FD)
                    )
                ),
                shape = pillShape
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Red Location Pin Icon
            PoppableWelcomeElement(
                elementId = WelcomeElementId.LOCATION_PIN,
                selectedId = selectedElement,
                onSelect = onSelect,
                glowShape = RoundedCornerShape(14.dp),
                popScale = 1.14f,
                testTag = "welcome_location_icon"
            ) {
                Canvas(modifier = Modifier.size(30.dp)) {
                    val w = size.width
                    val h = size.height
                    // White base ring
                    drawOval(
                        color = Color.White,
                        topLeft = Offset(w * 0.15f, h * 0.76f),
                        size = androidx.compose.ui.geometry.Size(w * 0.70f, h * 0.20f),
                        style = Stroke(width = 2.dp.toPx())
                    )
                    // Red pin teardrop
                    val pinPath = Path().apply {
                        moveTo(w * 0.50f, h * 0.86f)
                        cubicTo(w * 0.16f, h * 0.56f, w * 0.14f, h * 0.20f, w * 0.50f, h * 0.08f)
                        cubicTo(w * 0.86f, h * 0.20f, w * 0.84f, h * 0.56f, w * 0.50f, h * 0.86f)
                        close()
                    }
                    drawPath(pinPath, Color(0xFFFF1E1E))
                    drawPath(pinPath, Color.White, style = Stroke(width = 1.6.dp.toPx()))
                    drawCircle(Color.White, w * 0.15f, Offset(w * 0.50f, h * 0.36f))
                }
            }

            VerticalPillDivider()

            // S 5 inside its own slightly rounded rectangular boundary
            PoppableWelcomeElement(
                elementId = WelcomeElementId.S5_BADGE,
                selectedId = selectedElement,
                onSelect = onSelect,
                glowShape = RoundedCornerShape(10.dp),
                popScale = 1.10f,
                testTag = "welcome_s5_badge"
            ) { isSelected ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) Color(0xFF1D4ED8) else Color(0x33FFFFFF),
                    border = BorderStroke(
                        width = if (isSelected) 2.dp else 1.5.dp,
                        color = if (isSelected) Color(0xFFFDE047) else Color.White.copy(alpha = 0.85f)
                    )
                ) {
                    Text(
                        text = "S 5",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 21.sp,
                            letterSpacing = 1.sp
                        ),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }

            VerticalPillDivider()

            // Indian Flag Icon + India Text (each independently clickable & poppable)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PoppableWelcomeElement(
                    elementId = WelcomeElementId.INDIA_FLAG,
                    selectedId = selectedElement,
                    onSelect = onSelect,
                    glowShape = RoundedCornerShape(6.dp),
                    popScale = 1.14f,
                    testTag = "welcome_india_flag"
                ) {
                    Canvas(
                        modifier = Modifier
                            .size(width = 40.dp, height = 27.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                    ) {
                        drawMiniIndianFlag()
                    }
                }

                PoppableWelcomeElement(
                    elementId = WelcomeElementId.INDIA_TEXT,
                    selectedId = selectedElement,
                    onSelect = onSelect,
                    glowShape = RoundedCornerShape(8.dp),
                    popScale = 1.10f,
                    testTag = "welcome_india_text"
                ) {
                    Text(
                        text = "India",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 21.sp
                        ),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileNamePill(
    selectedElement: WelcomeElementId?,
    onSelect: (WelcomeElementId) -> Unit
) {
    val pillShape = RoundedCornerShape(30.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(10.dp, pillShape, spotColor = Color(0xFF4A0072))
            .clip(pillShape)
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color(0xFF7B1FA2),
                        Color(0xFF9C27B0),
                        Color(0xFF6A1B9A)
                    )
                )
            )
            .border(
                width = 2.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color(0xFFE9D5FF),
                        Color(0xFFFDE047),
                        Color(0xFFE9D5FF)
                    )
                ),
                shape = pillShape
            )
            .padding(horizontal = 18.dp, vertical = 10.dp)
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
                glowShape = RoundedCornerShape(14.dp),
                popScale = 1.15f,
                testTag = "welcome_profile_icon"
            ) {
                Icon(
                    imageVector = Icons.Rounded.Person,
                    contentDescription = "Profile",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))
            VerticalPillDivider()
            Spacer(modifier = Modifier.width(14.dp))

            PoppableWelcomeElement(
                elementId = WelcomeElementId.RAJU_BERA,
                selectedId = selectedElement,
                onSelect = onSelect,
                glowShape = RoundedCornerShape(10.dp),
                popScale = 1.09f,
                testTag = "welcome_raju_bera_text"
            ) {
                Text(
                    text = "Raju Bera",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    ),
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
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
    val pillShape = RoundedCornerShape(32.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth(0.84f)
            .minimumInteractiveComponentSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(14.dp, pillShape, spotColor = Color(0xFF004D0E))
            .clip(pillShape)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF00C832),
                        Color(0xFF009B22),
                        Color(0xFF007516)
                    )
                )
            )
            .border(
                width = 2.5.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color(0xFFD9F99D),
                        Color(0xFFFEF08A),
                        Color(0xFFD9F99D)
                    )
                ),
                shape = pillShape
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .testTag("welcome_enter_button")
            .padding(vertical = 13.dp, horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Login,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(30.dp)
            )
            Text(
                text = "ENTER",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 24.sp,
                    letterSpacing = 1.5.sp
                ),
                color = Color.White
            )
        }
    }
}

@Composable
private fun VerticalPillDivider() {
    Box(
        modifier = Modifier
            .width(1.5.dp)
            .height(24.dp)
            .background(Color.White.copy(alpha = 0.65f))
    )
}
