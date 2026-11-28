package com.example.framescope.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.HdrOn
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.ListAlt
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.framescope.R
import com.example.framescope.encoding.EncodingTab
import com.example.framescope.ui.components.AeroGlassBackground
import com.example.framescope.ui.components.NeumorphicGlassCard
import com.example.framescope.ui.components.NeumorphicIconButton
import com.example.framescope.ui.theme.FrameScopeColors

private data class FixedEncodingSpecItem(
    val title: String,
    val value: String,
    val subtitle: String? = null,
    val icon: ImageVector
)

private val COMMON_SPEC_ITEMS = listOf(
    FixedEncodingSpecItem(
        title = "Bitrate Mode",
        value = "VBR (Variable Bitrate)",
        icon = Icons.Rounded.Tune
    ),
    FixedEncodingSpecItem(
        title = "Color Depth",
        value = "8-bit YUV420",
        icon = Icons.Rounded.GridOn
    ),
    FixedEncodingSpecItem(
        title = "Frame Rate (FPS)",
        value = "Native Display-Driven Frame Capture (Unforced FPS)",
        icon = Icons.Rounded.Speed
    ),
    FixedEncodingSpecItem(
        title = "Color Standard",
        value = "BT.709 (SDR)",
        subtitle = "Fixed accurate SDR color",
        icon = Icons.Rounded.HdrOn
    ),
    FixedEncodingSpecItem(
        title = "Output Container",
        value = "MediaMuxer — MPEG_4 (.mp4)",
        icon = Icons.Rounded.ListAlt
    ),
    FixedEncodingSpecItem(
        title = "Rendering Pipeline",
        value = "Direct Surface + Async Callback + Dedicated Encoder Thread",
        icon = Icons.Rounded.Bolt
    ),
    FixedEncodingSpecItem(
        title = "Keyframe Interval (GOP)",
        value = "2 Seconds (GOP)",
        icon = Icons.Rounded.Key
    ),
    FixedEncodingSpecItem(
        title = "Color Range",
        value = "Limited 16–235",
        icon = Icons.Rounded.Contrast
    ),
    FixedEncodingSpecItem(
        title = "Audio Format",
        value = "Mono AAC-LC, 48 kHz, 128 kbps",
        icon = Icons.Rounded.Audiotrack
    ),
    FixedEncodingSpecItem(
        title = "Chroma Subsampling",
        value = "YUV 4:2:0",
        icon = Icons.Rounded.Palette
    )
)

@Composable
fun EncodingSettingsScreen(
    viewModel: HomeViewModel,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    AeroGlassBackground {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NeumorphicIconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("settings_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = FrameScopeColors.TextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = stringResource(R.string.encoding_settings_title),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = FrameScopeColors.TextPrimary
                    )
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 700.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    ActiveCodecHeaderCard(
                        codec = settings.videoCodec,
                        bitrateMbps = settings.videoCodec.defaultBitrateMbps
                    )

                    COMMON_SPEC_ITEMS.forEach { spec ->
                        EncodingSpecCard(item = spec)
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                }
            }
        }
    }
}

@Composable
private fun ActiveCodecHeaderCard(
    codec: EncodingTab,
    bitrateMbps: Int
) {
    NeumorphicGlassCard(
        modifier = Modifier.testTag("codec_header_${codec.name.lowercase()}"),
        cornerRadius = 22.dp,
        elevation = 7.dp,
        accentBorder = FrameScopeColors.SelectionBlue.copy(alpha = 0.45f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(
                            brush = Brush.linearGradient(
                                listOf(FrameScopeColors.SelectionBlueLight, FrameScopeColors.SelectionBlue)
                            )
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = codec.displayName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = FrameScopeColors.TextPrimary
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = FrameScopeColors.SelectionBlueTint,
                border = BorderStroke(1.dp, FrameScopeColors.SelectionBlue.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(FrameScopeColors.SelectionBlue)
                    )
                    Text(
                        text = "${codec.shortName} • $bitrateMbps Mbps",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = FrameScopeColors.TextPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun EncodingSpecCard(
    item: FixedEncodingSpecItem
) {
    NeumorphicGlassCard(
        cornerRadius = 20.dp,
        elevation = 5.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(FrameScopeColors.InnerSurface)
                        .border(1.dp, FrameScopeColors.GlassRimTop, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint = FrameScopeColors.SelectionBlue,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = FrameScopeColors.TextPrimary
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = FrameScopeColors.InsetTrack.copy(alpha = 0.55f),
                border = BorderStroke(1.dp, FrameScopeColors.GlassRimBottom)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = item.value,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.5.sp
                        ),
                        color = FrameScopeColors.TextPrimary
                    )
                    item.subtitle?.let { sub ->
                        Text(
                            text = sub,
                            style = MaterialTheme.typography.bodyMedium,
                            color = FrameScopeColors.TextSecondary
                        )
                    }
                }
            }
        }
    }
}
