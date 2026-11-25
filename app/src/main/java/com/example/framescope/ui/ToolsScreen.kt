package com.example.framescope.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.framescope.R
import com.example.framescope.capability.DeviceCapabilityEngine
import com.example.framescope.capability.OptionSupportInfo
import com.example.framescope.ui.components.AlternativeInUseBanner
import com.example.framescope.ui.components.SupportStatusPill
import com.example.framescope.ui.theme.FrameScopeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class ToolEntry(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val testTag: String,
    val supportInfo: OptionSupportInfo,
    val onClick: () -> Unit
)

@Composable
fun ToolsScreen(
    onOpenCropTool: () -> Unit
) {
    val context = LocalContext.current
    val cropSupport by produceState(
        initialValue = OptionSupportInfo(true, detailText = "Photo & Video Supported"),
        context
    ) {
        value = withContext(Dispatchers.Default) {
            DeviceCapabilityEngine.probeCropCapabilities(context).cropToolSupport
        }
    }

    val tools = listOf(
        ToolEntry(
            id = "crop",
            title = stringResource(R.string.tool_crop_title),
            description = stringResource(R.string.tool_crop_desc),
            icon = Icons.Rounded.Crop,
            testTag = "tool_card_crop",
            supportInfo = cropSupport,
            onClick = onOpenCropTool
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FrameScopeColors.Background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(FrameScopeColors.Background)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(FrameScopeColors.SelectionBlueTint)
                    .border(1.dp, FrameScopeColors.SelectionBlue, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Build,
                    contentDescription = null,
                    tint = FrameScopeColors.SelectionBlue,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.tab_tools),
                style = MaterialTheme.typography.titleLarge,
                color = FrameScopeColors.TextPrimary
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 680.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                tools.forEach { tool ->
                    ToolCard(tool = tool)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ToolCard(tool: ToolEntry) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clip(shape)
            .clickable(onClick = tool.onClick)
            .testTag(tool.testTag),
        shape = shape,
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
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(FrameScopeColors.SelectionBlueTint)
                        .border(1.dp, FrameScopeColors.SelectionBlue, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = tool.icon,
                        contentDescription = tool.title,
                        tint = FrameScopeColors.SelectionBlue,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = tool.title,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = FrameScopeColors.TextPrimary
                        )
                        SupportStatusPill(isSupported = tool.supportInfo.isSupported)
                    }
                    Text(
                        text = tool.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = FrameScopeColors.TextSecondary
                    )
                }

                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = FrameScopeColors.TextSecondary
                )
            }

            if (!tool.supportInfo.isSupported && !tool.supportInfo.alternativeName.isNullOrBlank()) {
                AlternativeInUseBanner(alternativeName = tool.supportInfo.alternativeName)
            }
        }
    }
}
