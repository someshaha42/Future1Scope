package com.example.framescope.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.framescope.data.RegionBounds
import com.example.framescope.encoding.RecordingOrientation
import com.example.framescope.encoding.VideoDimensions
import com.example.framescope.encoding.getOutputDimensions
import com.example.framescope.ui.theme.FrameScopeColors

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = FrameScopeColors.CardSurface,
        border = BorderStroke(1.dp, FrameScopeColors.BorderSubtle)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = FrameScopeColors.TextPrimary
                )
                trailing?.invoke()
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun SelectableOptionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor by animateColorAsState(
        targetValue = if (selected) FrameScopeColors.SelectionBlue else FrameScopeColors.BorderSubtle,
        label = "option_border"
    )
    val containerColor by animateColorAsState(
        targetValue = if (selected) FrameScopeColors.SelectionBlueTint else FrameScopeColors.InnerSurface,
        label = "option_bg"
    )
    val iconContainerColor by animateColorAsState(
        targetValue = if (selected) FrameScopeColors.SelectionBlueIconBg else FrameScopeColors.ElevatedSurface,
        label = "icon_bg"
    )

    val shape = RoundedCornerShape(14.dp)

    Surface(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(testTag),
        shape = shape,
        color = containerColor,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(iconContainerColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = if (selected) FrameScopeColors.TextPrimary else FrameScopeColors.TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                if (selected) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(FrameScopeColors.SelectionBlue)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = FrameScopeColors.TextPrimary
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = FrameScopeColors.TextSecondary
            )
        }
    }
}

@Composable
fun AnimatedRegionFramePreview(
    regionBounds: RegionBounds,
    orientation: RecordingOrientation,
    animateBorder: Boolean,
    targetDimensions: VideoDimensions,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "region_border_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850),
            repeatMode = RepeatMode.Reverse
        ),
        label = "border_alpha"
    )

    val aspect = if (orientation == RecordingOrientation.PORTRAIT) 9f / 16f else 16f / 9f
    val borderAlpha = if (animateBorder) pulseAlpha else 0.75f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(FrameScopeColors.InnerSurface)
            .border(1.dp, FrameScopeColors.BorderSubtle, RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (animateBorder) {
                "Region Preview • ${targetDimensions.width}×${targetDimensions.height} (Animated Red Frame)"
            } else {
                "Region Preview • ${targetDimensions.width}×${targetDimensions.height} (Borderless)"
            },
            style = MaterialTheme.typography.labelMedium,
            color = FrameScopeColors.TextSecondary
        )
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(if (orientation == RecordingOrientation.PORTRAIT) 0.44f else 0.78f)
                .aspectRatio(aspect)
                .clip(RoundedCornerShape(10.dp))
                .background(FrameScopeColors.Background)
                .border(1.dp, FrameScopeColors.BorderSubtle, RoundedCornerShape(10.dp))
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val left = size.width * regionBounds.leftFraction
                val top = size.height * regionBounds.topFraction
                val width = size.width * regionBounds.widthFraction()
                val height = size.height * regionBounds.heightFraction()

                drawRect(
                    color = FrameScopeColors.SelectionBlue.copy(alpha = 0.14f),
                    topLeft = Offset(left, top),
                    size = Size(width, height)
                )

                val frameColor = if (animateBorder) {
                    FrameScopeColors.RecordRed.copy(alpha = borderAlpha)
                } else {
                    FrameScopeColors.SelectionBlue.copy(alpha = 0.8f)
                }
                drawRect(
                    color = frameColor,
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    style = Stroke(width = 3.dp.toPx())
                )
            }
        }
    }
}

@Composable
fun RegionEditorDialog(
    initialBounds: RegionBounds,
    orientation: RecordingOrientation,
    screenWidth: Int,
    screenHeight: Int,
    onDismiss: () -> Unit,
    onSave: (RegionBounds) -> Unit
) {
    var left by remember(initialBounds) { mutableFloatStateOf(initialBounds.leftFraction) }
    var top by remember(initialBounds) { mutableFloatStateOf(initialBounds.topFraction) }
    var right by remember(initialBounds) { mutableFloatStateOf(initialBounds.rightFraction) }
    var bottom by remember(initialBounds) { mutableFloatStateOf(initialBounds.bottomFraction) }

    val oriented = remember(orientation, screenWidth, screenHeight) {
        getOutputDimensions(orientation, screenWidth, screenHeight)
    }
    val previewBounds = RegionBounds(left, top, right, bottom)
    val pixelW = ((oriented.width * previewBounds.widthFraction()).toInt() and 1.inv()).coerceAtLeast(16)
    val pixelH = ((oriented.height * previewBounds.heightFraction()).toInt() and 1.inv()).coerceAtLeast(16)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FrameScopeColors.CardSurface,
        titleContentColor = FrameScopeColors.TextPrimary,
        textContentColor = FrameScopeColors.TextSecondary,
        title = {
            Text(
                text = "Configure Capture Region",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Hardware-aligned size: ${pixelW}×${pixelH} px",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FrameScopeColors.SelectionBlue
                )

                Text("Left (${(left * 100).toInt()}%)", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = left,
                    onValueChange = { left = it.coerceAtMost(right - 0.25f) },
                    valueRange = 0f..0.75f,
                    colors = SliderDefaults.colors(
                        thumbColor = FrameScopeColors.SelectionBlue,
                        activeTrackColor = FrameScopeColors.SelectionBlue
                    ),
                    modifier = Modifier.testTag("region_left_slider")
                )

                Text("Right (${(right * 100).toInt()}%)", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = right,
                    onValueChange = { right = it.coerceAtLeast(left + 0.25f) },
                    valueRange = 0.25f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = FrameScopeColors.SelectionBlue,
                        activeTrackColor = FrameScopeColors.SelectionBlue
                    ),
                    modifier = Modifier.testTag("region_right_slider")
                )

                Text("Top (${(top * 100).toInt()}%)", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = top,
                    onValueChange = { top = it.coerceAtMost(bottom - 0.25f) },
                    valueRange = 0f..0.75f,
                    colors = SliderDefaults.colors(
                        thumbColor = FrameScopeColors.SelectionBlue,
                        activeTrackColor = FrameScopeColors.SelectionBlue
                    ),
                    modifier = Modifier.testTag("region_top_slider")
                )

                Text("Bottom (${(bottom * 100).toInt()}%)", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = bottom,
                    onValueChange = { bottom = it.coerceAtLeast(top + 0.25f) },
                    valueRange = 0.25f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = FrameScopeColors.SelectionBlue,
                        activeTrackColor = FrameScopeColors.SelectionBlue
                    ),
                    modifier = Modifier.testTag("region_bottom_slider")
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(previewBounds) },
                modifier = Modifier.testTag("region_save_button")
            ) {
                Text("Apply", color = FrameScopeColors.SelectionBlue)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("region_cancel_button")
            ) {
                Text("Cancel", color = FrameScopeColors.TextSecondary)
            }
        }
    )
}
