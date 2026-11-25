package com.example.framescope.ui.crop

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.framescope.capability.OptionSupportInfo
import com.example.framescope.crop.CanvasAspectMode
import com.example.framescope.crop.CropAspectRatio
import com.example.framescope.crop.CropImportSource
import com.example.framescope.crop.CropMediaType
import com.example.framescope.crop.CropProjectRecord
import com.example.framescope.crop.ImageExportFormatOption
import com.example.framescope.crop.VideoBitrateMode
import com.example.framescope.crop.VideoExportFormatOption
import java.io.File
import com.example.framescope.ui.components.AlternativeInUseBanner
import com.example.framescope.ui.components.SectionCard
import com.example.framescope.ui.components.SupportStatusPill
import com.example.framescope.ui.theme.FrameScopeColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

private enum class TimelineDragTarget {
    NONE,
    START_HANDLE,
    END_HANDLE,
    PLAYHEAD,
    TRACK_SCRUB
}

@Composable
internal fun ImportInterfaceCard(
    hasLoadedMedia: Boolean,
    importSupport: Map<CropImportSource, OptionSupportInfo> = emptyMap(),
    onPickAll: () -> Unit,
    onPickVideo: () -> Unit,
    onPickImage: () -> Unit,
    onPickFromFile: () -> Unit,
    onCancelImport: () -> Unit
) {
    val allInfo = importSupport[CropImportSource.ALL] ?: OptionSupportInfo(true)
    val videoInfo = importSupport[CropImportSource.VIDEO] ?: OptionSupportInfo(true)
    val imageInfo = importSupport[CropImportSource.IMAGE] ?: OptionSupportInfo(true)
    val fileInfo = importSupport[CropImportSource.FILE] ?: OptionSupportInfo(true)

    SectionCard(
        title = "Import Media",
        trailing = if (hasLoadedMedia) {
            {
                TextButton(
                    onClick = onCancelImport,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("Close", color = FrameScopeColors.TextSecondary)
                }
            }
        } else {
            null
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ImportTileButton(
                    label = "All",
                    icon = Icons.Rounded.Collections,
                    supportInfo = allInfo,
                    testTag = "crop_import_all",
                    onClick = onPickAll,
                    modifier = Modifier.weight(1f)
                )
                ImportTileButton(
                    label = "Video",
                    icon = Icons.Rounded.VideoLibrary,
                    supportInfo = videoInfo,
                    testTag = "crop_import_video",
                    onClick = onPickVideo,
                    modifier = Modifier.weight(1f)
                )
                ImportTileButton(
                    label = "Image",
                    icon = Icons.Rounded.Image,
                    supportInfo = imageInfo,
                    testTag = "crop_import_image",
                    onClick = onPickImage,
                    modifier = Modifier.weight(1f)
                )
            }

            OutlinedButton(
                onClick = onPickFromFile,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, FrameScopeColors.SelectionBlue),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = FrameScopeColors.SelectionBlueTint,
                    contentColor = FrameScopeColors.TextPrimary
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 13.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("crop_import_file")
            ) {
                Icon(
                    imageVector = Icons.Rounded.FolderOpen,
                    contentDescription = null,
                    tint = FrameScopeColors.SelectionBlue,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Pick From File",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f)
                )
                SupportStatusPill(isSupported = fileInfo.isSupported)
            }

            val unsupportedFallback = listOf(allInfo, videoInfo, imageInfo, fileInfo)
                .firstOrNull { !it.isSupported && !it.alternativeName.isNullOrBlank() }
                ?.alternativeName
            if (unsupportedFallback != null) {
                AlternativeInUseBanner(alternativeName = unsupportedFallback)
            }
        }
    }
}

@Composable
private fun ImportTileButton(
    label: String,
    icon: ImageVector,
    supportInfo: OptionSupportInfo,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(shape)
            .clickable(onClick = onClick)
            .testTag(testTag),
        shape = shape,
        color = FrameScopeColors.InnerSurface,
        border = BorderStroke(1.dp, FrameScopeColors.BorderSubtle)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.width(18.dp))
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = FrameScopeColors.SelectionBlue,
                    modifier = Modifier.size(22.dp)
                )
                SupportStatusPill(
                    isSupported = supportInfo.isSupported,
                    compact = true
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = FrameScopeColors.TextPrimary
            )
        }
    }
}

@Composable
internal fun CanvasAspectOptionsBar(
    customWidth: String,
    customHeight: String,
    onCustomChanged: (String, String) -> Unit,
    onClose: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("crop_canvas_options_bar"),
        shape = RoundedCornerShape(12.dp),
        color = FrameScopeColors.CardSurface,
        border = BorderStroke(1.dp, FrameScopeColors.SelectionBlue.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = customWidth,
                onValueChange = { onCustomChanged(it, customHeight) },
                singleLine = true,
                label = { Text("Canvas W") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = FrameScopeColors.SelectionBlue,
                    unfocusedBorderColor = FrameScopeColors.BorderSubtle,
                    focusedTextColor = FrameScopeColors.TextPrimary,
                    unfocusedTextColor = FrameScopeColors.TextPrimary
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("custom_canvas_width")
            )
            Text(":", color = FrameScopeColors.TextSecondary, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = customHeight,
                onValueChange = { onCustomChanged(customWidth, it) },
                singleLine = true,
                label = { Text("Canvas H") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = FrameScopeColors.SelectionBlue,
                    unfocusedBorderColor = FrameScopeColors.BorderSubtle,
                    focusedTextColor = FrameScopeColors.TextPrimary,
                    unfocusedTextColor = FrameScopeColors.TextPrimary
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("custom_canvas_height")
            )
            TextButton(
                onClick = onClose,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Text("Done", color = FrameScopeColors.SelectionBlue, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
internal fun VideoTimelineSection(
    durationMs: Long,
    trimStartMs: Long,
    trimEndMs: Long,
    currentPositionMs: Long,
    isVideoPlaying: Boolean = false,
    onTogglePlayPause: (() -> Unit)? = null,
    onSeek: (Long, Boolean) -> Unit,
    onTrimRangeChanged: (Long, Long) -> Unit
) {
    val safeDuration = durationMs.coerceAtLeast(1L)
    val safeStart = trimStartMs.coerceIn(0L, safeDuration)
    val safeEnd = trimEndMs.coerceIn(safeStart, safeDuration).let {
        if (it <= safeStart && safeDuration > 0L) safeDuration else it
    }
    val safePos = currentPositionMs.coerceIn(0L, safeDuration)

    val density = LocalDensity.current
    val handleHitPx = with(density) { 22.dp.toPx() }
    val trackPadPx = with(density) { 14.dp.toPx() }
    val tapSlopPx = with(density) { 10.dp.toPx() }

    val latestDuration by rememberUpdatedState(safeDuration)
    val latestStart by rememberUpdatedState(safeStart)
    val latestEnd by rememberUpdatedState(safeEnd)
    val latestPos by rememberUpdatedState(safePos)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("crop_video_timeline_card"),
        shape = RoundedCornerShape(14.dp),
        color = FrameScopeColors.CardSurface,
        border = BorderStroke(1.dp, FrameScopeColors.BorderSubtle)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Start ${formatTimelineMs(safeStart)}",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = FrameScopeColors.TextSecondary
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (onTogglePlayPause != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isVideoPlaying) FrameScopeColors.SelectionBlueTint else FrameScopeColors.InnerSurface,
                            border = BorderStroke(
                                1.dp,
                                if (isVideoPlaying) FrameScopeColors.SelectionBlue else FrameScopeColors.BorderSubtle
                            ),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onTogglePlayPause)
                                .testTag("crop_video_play_pause_button")
                        ) {
                            Icon(
                                imageVector = if (isVideoPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (isVideoPlaying) "Pause" else "Play",
                                tint = if (isVideoPlaying) FrameScopeColors.SelectionBlue else FrameScopeColors.TextPrimary,
                                modifier = Modifier
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .size(18.dp)
                            )
                        }
                    }
                    Text(
                        text = formatTimelineMs(safePos),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        ),
                        color = FrameScopeColors.SelectionBlue
                    )
                }
                Text(
                    text = "End ${formatTimelineMs(safeEnd)}",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = FrameScopeColors.TextSecondary
                )
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("crop_video_timeline")
                    .pointerInput(durationMs) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()

                            val trackLeft = trackPadPx
                            val trackRight = (size.width - trackPadPx).coerceAtLeast(trackLeft + 1f)
                            val trackWidth = (trackRight - trackLeft).coerceAtLeast(1f)
                            val dur = latestDuration.toFloat().coerceAtLeast(1f)

                            fun msToX(ms: Long): Float =
                                trackLeft + (ms.toFloat() / dur).coerceIn(0f, 1f) * trackWidth

                            fun xToMs(x: Float): Long =
                                (((x - trackLeft) / trackWidth).coerceIn(0f, 1f) * dur).roundToLong()

                            val startX = msToX(latestStart)
                            val endX = msToX(latestEnd)
                            val playX = msToX(latestPos)
                            val downX = down.position.x

                            val dPlay = abs(downX - playX)
                            val dStart = abs(downX - startX)
                            val dEnd = abs(downX - endX)

                            val target = when {
                                dPlay <= handleHitPx && dPlay < dStart && dPlay < dEnd -> TimelineDragTarget.PLAYHEAD
                                dStart <= handleHitPx && dStart <= dEnd -> TimelineDragTarget.START_HANDLE
                                dEnd <= handleHitPx -> TimelineDragTarget.END_HANDLE
                                dPlay <= handleHitPx -> TimelineDragTarget.PLAYHEAD
                                else -> TimelineDragTarget.TRACK_SCRUB
                            }

                            var totalMoved = 0f
                            var lastX = downX

                            while (true) {
                                val event = awaitPointerEvent()
                                val primary = event.changes.firstOrNull() ?: break
                                if (!primary.pressed) {
                                    primary.consume()
                                    if (target == TimelineDragTarget.TRACK_SCRUB && totalMoved <= tapSlopPx) {
                                        val tappedMs = xToMs(primary.position.x)
                                        onSeek(tappedMs, true)
                                    }
                                    break
                                }

                                val delta = primary.positionChange()
                                if (delta != Offset.Zero) {
                                    primary.consume()
                                    totalMoved += abs(delta.x)
                                    lastX = primary.position.x
                                    val draggedMs = xToMs(lastX)

                                    when (target) {
                                        TimelineDragTarget.START_HANDLE -> {
                                            val minSpan = minOf(500L, latestDuration)
                                            val nextStart = draggedMs.coerceIn(0L, (latestEnd - minSpan).coerceAtLeast(0L))
                                            onTrimRangeChanged(nextStart, latestEnd)
                                            onSeek(nextStart, false)
                                        }
                                        TimelineDragTarget.END_HANDLE -> {
                                            val minSpan = minOf(500L, latestDuration)
                                            val nextEnd = draggedMs.coerceIn((latestStart + minSpan).coerceAtMost(latestDuration), latestDuration)
                                            onTrimRangeChanged(latestStart, nextEnd)
                                            onSeek(nextEnd, false)
                                        }
                                        TimelineDragTarget.PLAYHEAD,
                                        TimelineDragTarget.TRACK_SCRUB -> {
                                            if (totalMoved > tapSlopPx || target == TimelineDragTarget.PLAYHEAD) {
                                                onSeek(draggedMs, false)
                                            }
                                        }
                                        TimelineDragTarget.NONE -> Unit
                                    }
                                }
                            }
                        }
                    }
            ) {
                val trackLeft = trackPadPx
                val trackRight = (size.width - trackPadPx).coerceAtLeast(trackLeft + 1f)
                val trackWidth = (trackRight - trackLeft).coerceAtLeast(1f)
                val centerY = size.height / 2f
                val trackHeight = 14.dp.toPx()
                val trackTop = centerY - trackHeight / 2f

                val startFrac = (safeStart.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
                val endFrac = (safeEnd.toFloat() / safeDuration.toFloat()).coerceIn(startFrac, 1f)
                val posFrac = (safePos.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)

                val startX = trackLeft + startFrac * trackWidth
                val endX = trackLeft + endFrac * trackWidth
                val playX = trackLeft + posFrac * trackWidth

                // Full track background
                drawRoundRect(
                    color = FrameScopeColors.InnerSurface,
                    topLeft = Offset(trackLeft, trackTop),
                    size = Size(trackWidth, trackHeight),
                    cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                )

                // Active trim segment
                val activeW = (endX - startX).coerceAtLeast(2f)
                drawRoundRect(
                    color = FrameScopeColors.SelectionBlueTint,
                    topLeft = Offset(startX, trackTop),
                    size = Size(activeW, trackHeight),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )
                drawLine(
                    color = FrameScopeColors.SelectionBlue,
                    start = Offset(startX, trackTop),
                    end = Offset(endX, trackTop),
                    strokeWidth = 2.dp.toPx()
                )
                drawLine(
                    color = FrameScopeColors.SelectionBlue,
                    start = Offset(startX, trackTop + trackHeight),
                    end = Offset(endX, trackTop + trackHeight),
                    strokeWidth = 2.dp.toPx()
                )

                // Start & End trim handles
                val handleW = 7.dp.toPx()
                val handleH = 26.dp.toPx()
                drawRoundRect(
                    color = FrameScopeColors.SelectionBlue,
                    topLeft = Offset(startX - handleW / 2f, centerY - handleH / 2f),
                    size = Size(handleW, handleH),
                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                )
                drawRoundRect(
                    color = FrameScopeColors.SelectionBlue,
                    topLeft = Offset(endX - handleW / 2f, centerY - handleH / 2f),
                    size = Size(handleW, handleH),
                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                )

                // Playhead line + knob
                drawLine(
                    color = Color.White,
                    start = Offset(playX, centerY - 17.dp.toPx()),
                    end = Offset(playX, centerY + 17.dp.toPx()),
                    strokeWidth = 2.5.dp.toPx()
                )
                drawCircle(
                    color = Color.White,
                    radius = 6.5.dp.toPx(),
                    center = Offset(playX, centerY)
                )
                drawCircle(
                    color = FrameScopeColors.SelectionBlue,
                    radius = 3.5.dp.toPx(),
                    center = Offset(playX, centerY)
                )
            }
        }
    }
}

@Composable
internal fun AspectRatioSection(
    selected: CropAspectRatio,
    customWidth: String,
    customHeight: String,
    customError: String?,
    enabled: Boolean = true,
    aspectRatioSupport: Map<CropAspectRatio, OptionSupportInfo> = emptyMap(),
    onSelectRatio: (CropAspectRatio) -> Unit,
    onCustomChanged: (String, String) -> Unit
) {
    val selectedSupport = aspectRatioSupport[selected] ?: OptionSupportInfo(true)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.55f)
            .testTag("selection_aspect_ratio_section"),
        shape = RoundedCornerShape(16.dp),
        color = FrameScopeColors.CardSurface,
        border = BorderStroke(1.dp, FrameScopeColors.BorderSubtle)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Reference-style centered pill header with horizontal blue wing lines
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.5.dp)
                        .background(FrameScopeColors.SelectionBlue.copy(alpha = 0.65f))
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = FrameScopeColors.SelectionBlueTint,
                    border = BorderStroke(1.25.dp, FrameScopeColors.SelectionBlue)
                ) {
                    Text(
                        text = "Selection Area Aspect Ratio",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.5.sp
                        ),
                        color = FrameScopeColors.TextPrimary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.5.dp)
                        .background(FrameScopeColors.SelectionBlue.copy(alpha = 0.65f))
                )
            }

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val tileCount = CropAspectRatio.entries.size
                val spacing = 5.dp
                val fitWidth = (maxWidth - spacing * (tileCount - 1)) / tileCount
                val useEvenRow = fitWidth >= 46.dp

                Row(
                    modifier = if (useEvenRow) {
                        Modifier.fillMaxWidth()
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    },
                    horizontalArrangement = Arrangement.spacedBy(spacing)
                ) {
                    CropAspectRatio.entries.forEach { ratio ->
                        val isSelected = selected == ratio
                        val ratioInfo = aspectRatioSupport[ratio] ?: OptionSupportInfo(true)
                        val tileModifier = if (useEvenRow) {
                            Modifier.weight(1f)
                        } else {
                            Modifier.widthIn(min = 50.dp)
                        }
                        AspectRatioTile(
                            ratio = ratio,
                            selected = isSelected,
                            enabled = enabled,
                            supportInfo = ratioInfo,
                            onClick = { onSelectRatio(ratio) },
                            modifier = tileModifier
                        )
                    }
                }
            }

            if (selected == CropAspectRatio.CUSTOM) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = customWidth,
                        onValueChange = { if (enabled) onCustomChanged(it, customHeight) },
                        enabled = enabled,
                        singleLine = true,
                        label = { Text("W") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = FrameScopeColors.SelectionBlue,
                            unfocusedBorderColor = FrameScopeColors.BorderSubtle,
                            focusedTextColor = FrameScopeColors.TextPrimary,
                            unfocusedTextColor = FrameScopeColors.TextPrimary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("custom_ratio_width")
                    )
                    Text(":", color = FrameScopeColors.TextSecondary, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = customHeight,
                        onValueChange = { if (enabled) onCustomChanged(customWidth, it) },
                        enabled = enabled,
                        singleLine = true,
                        label = { Text("H") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = FrameScopeColors.SelectionBlue,
                            unfocusedBorderColor = FrameScopeColors.BorderSubtle,
                            focusedTextColor = FrameScopeColors.TextPrimary,
                            unfocusedTextColor = FrameScopeColors.TextPrimary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("custom_ratio_height")
                    )
                }
                if (customError != null) {
                    Text(
                        text = customError,
                        style = MaterialTheme.typography.labelMedium,
                        color = FrameScopeColors.SpecRedText
                    )
                }
            }

            if (!selectedSupport.isSupported && !selectedSupport.alternativeName.isNullOrBlank()) {
                AlternativeInUseBanner(alternativeName = selectedSupport.alternativeName)
            }
        }
    }
}

@Composable
private fun AspectRatioTile(
    ratio: CropAspectRatio,
    selected: Boolean,
    enabled: Boolean,
    supportInfo: OptionSupportInfo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(11.dp)
    val tintColor = if (selected) Color.White else FrameScopeColors.TextSecondary
    Surface(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick)
            .testTag("aspect_chip_${ratio.name.lowercase()}"),
        shape = shape,
        color = if (selected) FrameScopeColors.SelectionBlueTint else FrameScopeColors.InnerSurface,
        border = BorderStroke(
            width = if (selected) 1.75.dp else 1.dp,
            color = if (selected) FrameScopeColors.SelectionBlue else FrameScopeColors.BorderSubtle
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 3.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            AspectRatioGlyph(
                ratio = ratio,
                tint = tintColor,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = ratio.label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    fontSize = 10.5.sp
                ),
                color = if (selected) FrameScopeColors.TextPrimary else FrameScopeColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
            SupportStatusPill(
                isSupported = supportInfo.isSupported,
                compact = true
            )
        }
    }
}

@Composable
private fun AspectRatioGlyph(
    ratio: CropAspectRatio,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val strokePx = 1.6.dp.toPx()
        val pad = 2.dp.toPx()
        val availW = (size.width - pad * 2f).coerceAtLeast(4f)
        val availH = (size.height - pad * 2f).coerceAtLeast(4f)

        when (ratio) {
            CropAspectRatio.FREE -> {
                val l = pad
                val t = pad
                val r = size.width - pad
                val b = size.height - pad
                val seg = availW * 0.3f
                // Top-left bracket
                drawLine(tint, Offset(l, t + seg), Offset(l, t), strokePx, StrokeCap.Round)
                drawLine(tint, Offset(l, t), Offset(l + seg, t), strokePx, StrokeCap.Round)
                // Top-right bracket
                drawLine(tint, Offset(r - seg, t), Offset(r, t), strokePx, StrokeCap.Round)
                drawLine(tint, Offset(r, t), Offset(r, t + seg), strokePx, StrokeCap.Round)
                // Bottom-left bracket
                drawLine(tint, Offset(l, b - seg), Offset(l, b), strokePx, StrokeCap.Round)
                drawLine(tint, Offset(l, b), Offset(l + seg, b), strokePx, StrokeCap.Round)
                // Bottom-right bracket
                drawLine(tint, Offset(r - seg, b), Offset(r, b), strokePx, StrokeCap.Round)
                drawLine(tint, Offset(r, b), Offset(r, b - seg), strokePx, StrokeCap.Round)
            }

            CropAspectRatio.CUSTOM -> {
                val l = pad
                val t = pad
                val w = availW
                val h = availH
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(l, t),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                    style = Stroke(width = strokePx)
                )
                val cx = size.width * 0.5f
                val cy = size.height * 0.5f
                val cross = 2.8.dp.toPx()
                drawLine(tint, Offset(cx - cross, cy), Offset(cx + cross, cy), strokePx, StrokeCap.Round)
                drawLine(tint, Offset(cx, cy - cross), Offset(cx, cy + cross), strokePx, StrokeCap.Round)
            }

            else -> {
                val target = ratio.ratio ?: 1f
                val (rw, rh) = if (target >= 1f) {
                    availW to (availW / target).coerceAtLeast(5f)
                } else {
                    (availH * target).coerceAtLeast(5f) to availH
                }
                val l = (size.width - rw) * 0.5f
                val t = (size.height - rh) * 0.5f
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(l, t),
                    size = Size(rw, rh),
                    cornerRadius = CornerRadius(2.5.dp.toPx(), 2.5.dp.toPx()),
                    style = Stroke(width = strokePx)
                )
            }
        }
    }
}

@Composable
internal fun VideoBitrateModeSection(
    mode: VideoBitrateMode,
    customBitrateMbps: Float,
    originalBitrateBps: Long?,
    bitrateModeSupport: Map<VideoBitrateMode, OptionSupportInfo> = emptyMap(),
    onSelectMode: (VideoBitrateMode) -> Unit,
    onSliderChanged: (Float) -> Unit
) {
    val origMbpsText = if (originalBitrateBps != null && originalBitrateBps > 0L) {
        String.format(Locale.US, "%.1f Mbps", originalBitrateBps / 1_000_000.0)
    } else {
        "Unavailable"
    }
    val customSupport = bitrateModeSupport[VideoBitrateMode.CUSTOM] ?: OptionSupportInfo(true)
    val origSupport = bitrateModeSupport[VideoBitrateMode.ORIGINAL_PRESERVATION]
        ?: OptionSupportInfo(originalBitrateBps != null && originalBitrateBps > 0L)

    SectionCard(title = "Bitrate Mode") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val customSelected = mode == VideoBitrateMode.CUSTOM
            val customBorder by animateColorAsState(
                if (customSelected) FrameScopeColors.SelectionBlue else FrameScopeColors.BorderSubtle,
                label = "custom_bitrate_border"
            )
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSelectMode(VideoBitrateMode.CUSTOM) }
                    .testTag("bitrate_mode_custom"),
                shape = RoundedCornerShape(12.dp),
                color = if (customSelected) FrameScopeColors.SelectionBlueTint else FrameScopeColors.InnerSurface,
                border = BorderStroke(if (customSelected) 1.5.dp else 1.dp, customBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Custom",
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = FrameScopeColors.TextPrimary
                            )
                            Text(
                                text = String.format(Locale.US, "%.1f Mbps", customBitrateMbps),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = FrameScopeColors.SelectionBlue
                            )
                        }
                        SupportStatusPill(isSupported = customSupport.isSupported)
                    }

                    if (!customSupport.isSupported && !customSupport.alternativeName.isNullOrBlank()) {
                        AlternativeInUseBanner(alternativeName = customSupport.alternativeName)
                    }

                    Slider(
                        value = customBitrateMbps.coerceIn(0f, 50f),
                        onValueChange = onSliderChanged,
                        valueRange = 0f..50f,
                        colors = SliderDefaults.colors(
                            thumbColor = FrameScopeColors.SelectionBlue,
                            activeTrackColor = FrameScopeColors.SelectionBlue,
                            inactiveTrackColor = FrameScopeColors.ElevatedSurface
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("crop_bitrate_slider")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "0 Mbps",
                            style = MaterialTheme.typography.labelMedium,
                            color = FrameScopeColors.TextMuted
                        )
                        Text(
                            text = "50 Mbps",
                            style = MaterialTheme.typography.labelMedium,
                            color = FrameScopeColors.TextMuted
                        )
                    }
                }
            }

            val origSelected = mode == VideoBitrateMode.ORIGINAL_PRESERVATION
            val origBorder by animateColorAsState(
                if (origSelected) FrameScopeColors.SelectionBlue else FrameScopeColors.BorderSubtle,
                label = "orig_bitrate_border"
            )
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSelectMode(VideoBitrateMode.ORIGINAL_PRESERVATION) }
                    .testTag("bitrate_mode_original"),
                shape = RoundedCornerShape(12.dp),
                color = if (origSelected) FrameScopeColors.SelectionBlueTint else FrameScopeColors.InnerSurface,
                border = BorderStroke(if (origSelected) 1.5.dp else 1.dp, origBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "Original Preservation",
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = FrameScopeColors.TextPrimary
                            )
                            Text(
                                text = origMbpsText,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = if (originalBitrateBps != null && originalBitrateBps > 0L) {
                                    FrameScopeColors.ReadyGreen
                                } else {
                                    FrameScopeColors.SpecRedText
                                }
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SupportStatusPill(isSupported = origSupport.isSupported)
                            if (origSelected) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = "Selected",
                                    tint = FrameScopeColors.SelectionBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    if (!origSupport.isSupported && !origSupport.alternativeName.isNullOrBlank()) {
                        AlternativeInUseBanner(alternativeName = origSupport.alternativeName)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ImageFormatSection(
    options: List<ImageExportFormatOption>,
    selected: ImageExportFormatOption?,
    onSelect: (ImageExportFormatOption) -> Unit
) {
    SectionCard(title = "Export Format") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                options.forEach { option ->
                    val isSelected = selected?.id == option.id
                    FormatChip(
                        label = option.label,
                        selected = isSelected,
                        supportInfo = option.supportInfo,
                        testTag = "image_format_${option.id.name.lowercase()}",
                        onClick = { onSelect(option) }
                    )
                }
            }

            if (selected != null && !selected.supportInfo.isSupported && !selected.supportInfo.alternativeName.isNullOrBlank()) {
                AlternativeInUseBanner(alternativeName = selected.supportInfo.alternativeName)
            }
        }
    }
}

@Composable
internal fun VideoFormatSection(
    options: List<VideoExportFormatOption>,
    selected: VideoExportFormatOption?,
    onSelect: (VideoExportFormatOption) -> Unit
) {
    SectionCard(title = "Export Format") {
        if (options.isEmpty()) {
            Text(
                text = "No supported video export muxer/encoder found on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = FrameScopeColors.SpecRedText
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    options.forEach { option ->
                        val isSelected = selected == option
                        FormatChip(
                            label = option.label,
                            selected = isSelected,
                            supportInfo = option.supportInfo,
                            testTag = "video_format_${option.container.name.lowercase()}",
                            onClick = { onSelect(option) }
                        )
                    }
                }

                if (selected != null && !selected.supportInfo.isSupported && !selected.supportInfo.alternativeName.isNullOrBlank()) {
                    AlternativeInUseBanner(alternativeName = selected.supportInfo.alternativeName)
                }
            }
        }
    }
}

@Composable
private fun FormatChip(
    label: String,
    selected: Boolean,
    supportInfo: OptionSupportInfo,
    testTag: String,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    Surface(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(shape)
            .clickable(onClick = onClick)
            .testTag(testTag),
        shape = shape,
        color = if (selected) FrameScopeColors.SelectionBlueTint else FrameScopeColors.InnerSurface,
        border = BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) FrameScopeColors.SelectionBlue else FrameScopeColors.BorderSubtle
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (selected) FrameScopeColors.SelectionBlue else FrameScopeColors.TextPrimary
            )
            SupportStatusPill(
                isSupported = supportInfo.isSupported,
                compact = true
            )
        }
    }
}

@Composable
internal fun RecentProjectsSection(
    projects: List<CropProjectRecord>,
    thumbnails: Map<String, Bitmap>,
    onOpenSettings: () -> Unit,
    onOpenProject: (CropProjectRecord) -> Unit,
    onPlayExported: (CropProjectRecord) -> Unit,
    onRequestDelete: (CropProjectRecord) -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("hh:mm a • MMM d", Locale.getDefault()) }

    SectionCard(
        title = "Recent Projects",
        trailing = {
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("crop_recent_settings_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = "Project settings",
                    tint = FrameScopeColors.TextSecondary
                )
            }
        }
    ) {
        if (projects.isEmpty()) {
            Text(
                text = "No recent crop projects yet. Import a photo or video to start.",
                style = MaterialTheme.typography.bodyMedium,
                color = FrameScopeColors.TextMuted,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                projects.forEachIndexed { index, project ->
                    val thumb = thumbnails[project.id]
                    val expFileExists = !project.exportedFilePath.isNullOrBlank() &&
                        File(project.exportedFilePath).exists()
                    val cardShape = RoundedCornerShape(12.dp)

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("recent_project_item_$index"),
                        shape = cardShape,
                        color = FrameScopeColors.InnerSurface,
                        border = BorderStroke(1.dp, FrameScopeColors.BorderSubtle)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // Tapping project card opens the project directly in its saved editing state
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                                    .clickable { onOpenProject(project) }
                                    .padding(10.dp)
                                    .testTag("open_project_item_$index"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(FrameScopeColors.ElevatedSurface),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (thumb != null) {
                                        val imgBmp = remember(thumb) { thumb.asImageBitmap() }
                                        Image(
                                            bitmap = imgBmp,
                                            contentDescription = project.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        if (project.mediaType == CropMediaType.VIDEO) {
                                            Icon(
                                                imageVector = Icons.Rounded.PlayCircleOutline,
                                                contentDescription = null,
                                                tint = Color.White.copy(alpha = 0.9f),
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    } else {
                                        Icon(
                                            imageVector = if (project.mediaType == CropMediaType.VIDEO) {
                                                Icons.Rounded.VideoLibrary
                                            } else {
                                                Icons.Rounded.Image
                                            },
                                            contentDescription = project.title,
                                            tint = FrameScopeColors.SelectionBlue,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }

                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = project.title,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = FrameScopeColors.TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = FrameScopeColors.SelectionBlueTint,
                                            border = BorderStroke(1.dp, FrameScopeColors.SelectionBlue)
                                        ) {
                                            Text(
                                                text = project.aspectRatio.label,
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                color = FrameScopeColors.SelectionBlue,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }

                                        if (expFileExists && !project.exportedFormatLabel.isNullOrBlank()) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = FrameScopeColors.ReadyGreenBg,
                                                border = BorderStroke(1.dp, FrameScopeColors.ReadyGreen.copy(alpha = 0.5f))
                                            ) {
                                                Text(
                                                    text = project.exportedFormatLabel,
                                                    style = MaterialTheme.typography.labelMedium.copy(
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold
                                                    ),
                                                    color = FrameScopeColors.ReadyGreen,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        } else {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = FrameScopeColors.ElevatedSurface,
                                                border = BorderStroke(1.dp, FrameScopeColors.BorderSubtle)
                                            ) {
                                                Text(
                                                    text = "Draft",
                                                    style = MaterialTheme.typography.labelMedium.copy(
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    ),
                                                    color = FrameScopeColors.TextSecondary,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }

                                        val w = if (project.exportedWidth > 0) project.exportedWidth else project.outputWidth
                                        val h = if (project.exportedHeight > 0) project.exportedHeight else project.outputHeight
                                        val dims = if (w > 0 && h > 0) "${w}×${h}" else ""
                                        if (dims.isNotEmpty()) {
                                            Text(
                                                text = dims,
                                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                                color = FrameScopeColors.TextSecondary,
                                                maxLines = 1
                                            )
                                        }
                                    }

                                    val sourceNote = if (project.localCopyPath != null) "App Copy" else "Gallery Source"
                                    Text(
                                        text = "${timeFormat.format(Date(project.updatedAtMs))} • $sourceNote",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = FrameScopeColors.TextMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            HorizontalDivider(
                                thickness = 1.dp,
                                color = FrameScopeColors.BorderSubtle.copy(alpha = 0.7f)
                            )

                            // Bottom Action Bar: Delete on the LEFT, Play on the RIGHT
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = FrameScopeColors.SpecRedContainer.copy(alpha = 0.65f),
                                    border = BorderStroke(1.dp, FrameScopeColors.SpecRedBorder),
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onRequestDelete(project) }
                                        .testTag("delete_project_item_$index")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = "Delete ${project.title}",
                                            tint = FrameScopeColors.SpecRedText,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = "Delete",
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 12.sp
                                            ),
                                            color = FrameScopeColors.SpecRedText
                                        )
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (expFileExists) {
                                        FrameScopeColors.SelectionBlueTint
                                    } else {
                                        FrameScopeColors.ElevatedSurface
                                    },
                                    border = BorderStroke(
                                        1.dp,
                                        if (expFileExists) {
                                            FrameScopeColors.SelectionBlue
                                        } else {
                                            FrameScopeColors.BorderSubtle
                                        }
                                    ),
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .alpha(if (expFileExists) 1f else 0.45f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(enabled = expFileExists) { onPlayExported(project) }
                                        .testTag("play_project_item_$index")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.PlayArrow,
                                            contentDescription = if (project.mediaType == CropMediaType.VIDEO) {
                                                "Play exported video"
                                            } else {
                                                "View exported image"
                                            },
                                            tint = if (expFileExists) {
                                                FrameScopeColors.SelectionBlue
                                            } else {
                                                FrameScopeColors.TextMuted
                                            },
                                            modifier = Modifier.size(17.dp)
                                        )
                                        Text(
                                            text = if (project.mediaType == CropMediaType.VIDEO) "Play" else "View",
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 12.sp
                                            ),
                                            color = if (expFileExists) {
                                                FrameScopeColors.TextPrimary
                                            } else {
                                                FrameScopeColors.TextMuted
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProjectSourceSettingsDialog(
    copyOriginalSourceEnabled: Boolean,
    onToggleCopyOriginalSource: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FrameScopeColors.CardSurface,
        title = {
            Text(
                text = "Recent Projects Settings",
                color = FrameScopeColors.TextPrimary,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = FrameScopeColors.InnerSurface,
                    border = BorderStroke(1.dp, FrameScopeColors.BorderSubtle)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Copy Original Source",
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = FrameScopeColors.TextPrimary
                            )
                            Text(
                                text = if (copyOriginalSourceEnabled) {
                                    "Enabled: Imported photos and videos are copied inside app storage so projects remain even if deleted from Gallery."
                                } else {
                                    "Disabled (Default): Uses the original Gallery file directly without copying. If deleted from Gallery, the project is removed."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = FrameScopeColors.TextSecondary
                            )
                        }
                        Switch(
                            checked = copyOriginalSourceEnabled,
                            onCheckedChange = onToggleCopyOriginalSource,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = FrameScopeColors.SelectionBlue,
                                uncheckedThumbColor = FrameScopeColors.TextSecondary,
                                uncheckedTrackColor = FrameScopeColors.ElevatedSurface
                            ),
                            modifier = Modifier.testTag("copy_original_source_switch")
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("crop_recent_settings_close")
            ) {
                Text("Back", color = FrameScopeColors.SelectionBlue, fontWeight = FontWeight.SemiBold)
            }
        }
    )
}

internal fun formatTimelineMs(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) / 1_000L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    val hrs = mins / 60L
    return if (hrs > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hrs, mins % 60L, secs)
    } else {
        String.format(Locale.US, "%02d:%02d", mins, secs)
    }
}
