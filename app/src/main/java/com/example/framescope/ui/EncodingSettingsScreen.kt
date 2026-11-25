package com.example.framescope.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.HdrOn
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.ListAlt
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.framescope.R
import com.example.framescope.encoding.EncoderValidationReport
import com.example.framescope.encoding.EncodingTab
import com.example.framescope.encoding.GpuApiSupport
import com.example.framescope.encoding.GpuRenderingApi
import com.example.framescope.encoding.VideoCodecCapability
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
        value = "GPU Surface + Async Callback + Dedicated Encoder Thread",
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
    val gpuSupport by viewModel.gpuSupport.collectAsStateWithLifecycle()
    val codecCapabilities by viewModel.codecCapabilities.collectAsStateWithLifecycle()
    val encoderReport by viewModel.encoderReport.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = FrameScopeColors.Background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(FrameScopeColors.Background)
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("settings_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = FrameScopeColors.TextPrimary
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.encoding_settings_title),
                    style = MaterialTheme.typography.titleLarge,
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 700.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ActiveGpuHeaderCard(
                    api = settings.gpuRenderingApi,
                    support = gpuSupport[settings.gpuRenderingApi]
                )

                ActiveCodecHeaderCard(
                    codec = settings.videoCodec,
                    bitrateMbps = settings.bitrateMbps,
                    capability = codecCapabilities[settings.videoCodec]
                )

                COMMON_SPEC_ITEMS.forEach { spec ->
                    EncodingSpecCard(item = spec)
                }

                encoderReport?.let { report ->
                    HardwareValidationCard(report = report)
                }

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun ActiveGpuHeaderCard(
    api: GpuRenderingApi,
    support: GpuApiSupport?
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_gpu_header"),
        shape = RoundedCornerShape(18.dp),
        color = FrameScopeColors.CardSurface,
        border = BorderStroke(1.dp, FrameScopeColors.BorderSubtle)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(FrameScopeColors.ElevatedSurface),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Memory,
                        contentDescription = null,
                        tint = FrameScopeColors.TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = "GPU Rendering API",
                    style = MaterialTheme.typography.titleMedium,
                    color = FrameScopeColors.TextPrimary
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = FrameScopeColors.SelectionBlueTint,
                border = BorderStroke(1.dp, FrameScopeColors.SelectionBlue)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${api.displayName} (${support?.hardwareDetail ?: "GPU"})",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = FrameScopeColors.TextPrimary
                    )
                    Text(
                        text = support?.statusText ?: "✓ Supported",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = FrameScopeColors.ReadyGreen
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveCodecHeaderCard(
    codec: EncodingTab,
    bitrateMbps: Int,
    capability: VideoCodecCapability?
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("codec_header_${codec.name.lowercase()}"),
        shape = RoundedCornerShape(18.dp),
        color = FrameScopeColors.CardSurface,
        border = BorderStroke(1.dp, FrameScopeColors.BorderSubtle)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(FrameScopeColors.ElevatedSurface),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = null,
                        tint = FrameScopeColors.TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = codec.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = FrameScopeColors.TextPrimary
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = FrameScopeColors.SpecRedContainer,
                border = BorderStroke(1.dp, FrameScopeColors.SpecRedBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(FrameScopeColors.RecordRed)
                        )
                        Text(
                            text = "${codec.shortName} • $bitrateMbps Mbps",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = FrameScopeColors.TextPrimary
                        )
                    }
                    Text(
                        text = capability?.statusText ?: "",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = FrameScopeColors.ReadyGreen
                    )
                }
            }
        }
    }
}

@Composable
private fun EncodingSpecCard(item: FixedEncodingSpecItem) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = FrameScopeColors.CardSurface,
        border = BorderStroke(1.dp, FrameScopeColors.BorderSubtle)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(FrameScopeColors.ElevatedSurface),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint = FrameScopeColors.TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = FrameScopeColors.TextPrimary
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = FrameScopeColors.SpecRedContainer,
                border = BorderStroke(1.dp, FrameScopeColors.SpecRedBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = item.value,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        ),
                        color = FrameScopeColors.SpecRedText
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

@Composable
private fun HardwareValidationCard(report: EncoderValidationReport) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("hardware_encoder_validation_card"),
        shape = RoundedCornerShape(18.dp),
        color = FrameScopeColors.CardSurface,
        border = BorderStroke(1.dp, FrameScopeColors.BorderSubtle)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (report.isCompatible) {
                        Icons.Rounded.CheckCircle
                    } else {
                        Icons.Rounded.ErrorOutline
                    },
                    contentDescription = null,
                    tint = if (report.isCompatible) {
                        FrameScopeColors.ReadyGreen
                    } else {
                        FrameScopeColors.SpecRedText
                    },
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = if (report.isHardwareAccelerated) {
                        "Device Hardware Encoder"
                    } else {
                        "Device Software Encoder"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = FrameScopeColors.TextPrimary
                )
            }

            Text(
                text = "Codec: ${report.codecName}",
                style = MaterialTheme.typography.bodyMedium,
                color = FrameScopeColors.TextSecondary
            )
            Text(
                text = "VBR Supported: ${if (report.supportsVbr) "Yes" else "No"} • " +
                    "YUV420 Surface: ${if (report.supportsYuv420Surface) "Yes" else "No"}",
                style = MaterialTheme.typography.bodyMedium,
                color = FrameScopeColors.TextSecondary
            )
            Text(
                text = "Encoder Bitrate Range: ${report.supportedBitrateRangeMbps}",
                style = MaterialTheme.typography.bodyMedium,
                color = FrameScopeColors.TextSecondary
            )
        }
    }
}
