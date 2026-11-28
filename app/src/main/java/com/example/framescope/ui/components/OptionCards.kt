package com.example.framescope.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.framescope.capability.OptionSupportInfo
import com.example.framescope.ui.theme.FrameScopeColors

@Composable
fun SupportStatusPill(
    isSupported: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val bgColor = if (isSupported) FrameScopeColors.ReadyGreenBg else FrameScopeColors.SpecRedContainer
    val borderColor = if (isSupported) {
        FrameScopeColors.ReadyGreen.copy(alpha = 0.45f)
    } else {
        FrameScopeColors.SpecRedBorder
    }
    val textColor = if (isSupported) FrameScopeColors.ReadyGreen else FrameScopeColors.SpecRedText
    val label = when {
        compact && isSupported -> "✓"
        compact && !isSupported -> "✕"
        isSupported -> "✓ Supported"
        else -> "✕ Unsupported"
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = if (compact) 10.sp else 11.5.sp,
                fontWeight = FontWeight.Bold
            ),
            color = textColor,
            maxLines = 1,
            modifier = Modifier.padding(
                horizontal = if (compact) 6.dp else 10.dp,
                vertical = if (compact) 1.5.dp else 3.5.dp
            )
        )
    }
}

@Composable
fun AlternativeInUseBanner(
    alternativeName: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(2.5.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(FrameScopeColors.ReadyGreen.copy(alpha = 0.65f))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "Alternative in use",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = if (compact) 10.sp else 11.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = FrameScopeColors.ReadyGreen,
                maxLines = 1
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = alternativeName,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = if (compact) 12.sp else 13.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = FrameScopeColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = FrameScopeColors.ReadyGreenBg,
                    border = BorderStroke(1.dp, FrameScopeColors.ReadyGreen.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = "✓ Active",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = if (compact) 9.5.sp else 10.5.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = FrameScopeColors.ReadyGreen,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.5.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    NeumorphicGlassCard(
        modifier = modifier,
        cornerRadius = 22.dp,
        elevation = 6.dp
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
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
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
    subtitle: String? = null,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportInfo: OptionSupportInfo? = null
) {
    val borderColor by animateColorAsState(
        targetValue = if (selected) FrameScopeColors.SelectionBlue else FrameScopeColors.GlassRimTop,
        label = "option_border"
    )
    val containerTop by animateColorAsState(
        targetValue = if (selected) {
            FrameScopeColors.SelectionBlueTint.copy(alpha = 0.28f)
        } else {
            FrameScopeColors.GlassFillTop
        },
        label = "option_bg_top"
    )
    val containerBottom by animateColorAsState(
        targetValue = if (selected) {
            FrameScopeColors.SelectionBlueTint.copy(alpha = 0.14f)
        } else {
            FrameScopeColors.InnerSurface
        },
        label = "option_bg_bottom"
    )

    val shape = RoundedCornerShape(18.dp)
    val lightShadow = FrameScopeColors.NeuShadowLight
    val darkShadow = FrameScopeColors.NeuShadowDark

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .neumorphicShadow(
                cornerRadius = 18.dp,
                elevation = if (selected) 5.dp else 4.dp,
                lightColor = lightShadow,
                darkColor = darkShadow
            )
            .clip(shape)
            .background(
                brush = Brush.linearGradient(colors = listOf(containerTop, containerBottom))
            )
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        borderColor,
                        if (selected) borderColor.copy(alpha = 0.45f) else FrameScopeColors.GlassRimBottom
                    )
                ),
                shape = shape
            )
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(testTag)
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
                        .size(36.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(
                            brush = if (selected) {
                                Brush.linearGradient(
                                    listOf(
                                        FrameScopeColors.SelectionBlueLight,
                                        FrameScopeColors.SelectionBlue
                                    )
                                )
                            } else {
                                Brush.linearGradient(
                                    listOf(
                                        FrameScopeColors.GlassFillTop,
                                        FrameScopeColors.ElevatedSurface
                                    )
                                )
                            }
                        )
                        .border(
                            width = 1.dp,
                            color = if (selected) Color.White.copy(alpha = 0.4f) else FrameScopeColors.GlassRimTop,
                            shape = RoundedCornerShape(11.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = if (selected) Color.White else FrameScopeColors.TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (supportInfo != null) {
                        SupportStatusPill(
                            isSupported = supportInfo.isSupported,
                            compact = true
                        )
                    }
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(FrameScopeColors.SelectionBlue)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = FrameScopeColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = FrameScopeColors.TextSecondary
                )
            }

            if (supportInfo != null) {
                Spacer(modifier = Modifier.height(6.dp))
                SupportStatusPill(isSupported = supportInfo.isSupported)
                if (!supportInfo.isSupported && !supportInfo.alternativeName.isNullOrBlank()) {
                    AlternativeInUseBanner(
                        alternativeName = supportInfo.alternativeName,
                        compact = true
                    )
                }
            }
        }
    }
}
