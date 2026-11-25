package com.example.framescope.ui.crop

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.framescope.R
import com.example.framescope.crop.CanvasAspectMode
import com.example.framescope.crop.CodecPipelineSupport
import com.example.framescope.crop.CropExportState
import com.example.framescope.crop.CropExportedItem
import com.example.framescope.crop.CropMediaType
import com.example.framescope.ui.theme.FrameScopeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CropToolScreen(
    onBack: () -> Unit,
    onPlayVideo: (String) -> Unit
) {
    val context = LocalContext.current
    val viewModel: CropToolViewModel = viewModel(
        factory = CropToolViewModel.Factory(context.applicationContext)
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var previewImageItem by remember { mutableStateOf<CropExportedItem?>(null) }

    val galleryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.importMedia(uri)
        }
    }

    val fileManagerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.importMedia(uri)
        }
    }

    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearMessage()
    }

    BackHandler(enabled = state.showImportPanel && state.media != null) {
        viewModel.closeImportPanelIfLoaded()
    }

    previewImageItem?.let { item ->
        ExportedImagePreviewDialog(
            item = item,
            onDismiss = { previewImageItem = null }
        )
    }

    if (state.exportState is CropExportState.Success) {
        val result = state.exportState as CropExportState.Success
        AlertDialog(
            onDismissRequest = viewModel::dismissExportState,
            containerColor = FrameScopeColors.CardSurface,
            icon = {
                Icon(
                    imageVector = Icons.Rounded.DownloadDone,
                    contentDescription = null,
                    tint = FrameScopeColors.ReadyGreen
                )
            },
            title = { Text("Export Complete", color = FrameScopeColors.TextPrimary) },
            text = {
                Text(
                    text = if (result.savedToGallery) {
                        "${result.fileName} exported and saved to your Gallery."
                    } else {
                        "${result.fileName} exported to ${result.outputPath}."
                    },
                    color = FrameScopeColors.TextSecondary
                )
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissExportState()
                        viewModel.openImportPanel()
                    }
                ) {
                    Text("View Exports", color = FrameScopeColors.TextSecondary)
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissExportState) {
                    Text("Done", color = FrameScopeColors.SelectionBlue)
                }
            }
        )
    }

    if (state.exportState is CropExportState.Error) {
        val err = (state.exportState as CropExportState.Error).message
        AlertDialog(
            onDismissRequest = viewModel::dismissExportState,
            containerColor = FrameScopeColors.CardSurface,
            title = { Text("Export Error", color = FrameScopeColors.TextPrimary) },
            text = { Text(err, color = FrameScopeColors.TextSecondary) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissExportState) {
                    Text("OK", color = FrameScopeColors.SelectionBlue)
                }
            }
        )
    }

    Scaffold(
        containerColor = FrameScopeColors.Background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.navigationBarsPadding()
            )
        },
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
                    modifier = Modifier.testTag("crop_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = FrameScopeColors.TextPrimary
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.crop_tool_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = FrameScopeColors.TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (state.media != null && !state.showImportPanel) {
                    TextButton(
                        onClick = viewModel::openImportPanel,
                        modifier = Modifier.testTag("crop_change_media_button")
                    ) {
                        Text(
                            text = "Import",
                            color = FrameScopeColors.SelectionBlue,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 680.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (state.isLoadingMedia) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = FrameScopeColors.CardSurface,
                        border = BorderStroke(1.dp, FrameScopeColors.BorderSubtle)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            CircularProgressIndicator(
                                color = FrameScopeColors.SelectionBlue,
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.5.dp
                            )
                            Text(
                                text = "Inspecting media…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = FrameScopeColors.TextPrimary
                            )
                        }
                    }
                }

                if (state.showImportPanel || state.media == null) {
                    ImportInterfaceCard(
                        hasLoadedMedia = state.media != null,
                        onPickAll = {
                            galleryPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                            )
                        },
                        onPickVideo = {
                            galleryPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            )
                        },
                        onPickImage = {
                            galleryPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onPickFromFile = {
                            fileManagerLauncher.launch(arrayOf("image/*", "video/*"))
                        },
                        onCancelImport = viewModel::closeImportPanelIfLoaded
                    )

                    RecentExportsSection(
                        exports = state.recentExports,
                        thumbnails = state.exportThumbnails,
                        onItemClick = { item ->
                            if (item.mediaType == CropMediaType.VIDEO) {
                                onPlayVideo(item.filePath)
                            } else {
                                previewImageItem = item
                            }
                        },
                        onDeleteClick = { item ->
                            viewModel.deleteExportItem(item.filePath)
                        }
                    )
                }

                val media = state.media
                if (media != null && !state.showImportPanel) {
                    // Header row above preview canvas: info left + Canvas, Zoom, Play/Pause on right
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        ) {
                            Text(
                                text = media.displayName,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = FrameScopeColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val fpsSuffix = if (media.mediaType == CropMediaType.VIDEO) " • ${media.fps} FPS" else ""
                            Text(
                                text = "${state.outputWidth} × ${state.outputHeight}$fpsSuffix",
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                color = FrameScopeColors.SelectionBlue,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            WorkspaceHeaderActionButton(
                                label = "Canvas",
                                icon = Icons.Rounded.AspectRatio,
                                active = state.showCanvasOptions || state.canvasAspectMode != CanvasAspectMode.ORIGINAL,
                                testTag = "crop_canvas_button",
                                onClick = viewModel::toggleCanvasOptions
                            )

                            WorkspaceHeaderActionButton(
                                label = "Zoom",
                                icon = Icons.Rounded.ZoomIn,
                                active = state.isZoomEnabled,
                                testTag = "crop_zoom_toggle_button",
                                onClick = viewModel::toggleZoomMode
                            )

                            if (media.mediaType == CropMediaType.VIDEO) {
                                WorkspaceHeaderActionButton(
                                    label = if (state.isVideoPlaying) "Pause" else "Play",
                                    icon = if (state.isVideoPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    active = state.isVideoPlaying,
                                    testTag = "crop_video_play_pause_button",
                                    onClick = viewModel::toggleVideoPlayPause
                                )
                            }
                        }
                    }

                    if (state.showCanvasOptions) {
                        CanvasAspectOptionsBar(
                            selectedMode = state.canvasAspectMode,
                            customWidth = state.customCanvasWidth,
                            customHeight = state.customCanvasHeight,
                            onSelectMode = viewModel::selectCanvasAspectMode,
                            onCustomChanged = viewModel::updateCustomCanvasRatio
                        )
                    }

                    // Preview & interactive gesture crop canvas (no zoom slider)
                    CropWorkspaceCanvas(
                        media = media,
                        previewBitmap = state.previewBitmap,
                        cropRect = state.cropRect,
                        zoom = state.zoom,
                        panOffset = state.panOffset,
                        isZoomEnabled = state.isZoomEnabled,
                        canvasAspectRatio = state.activeCanvasAspectRatio,
                        lockedAspectRatio = state.activeAspectRatioValue,
                        isVideoPlaying = state.isVideoPlaying,
                        trimStartMs = state.trimStartMs,
                        trimEndMs = state.trimEndMs,
                        seekRequestMs = state.seekRequestMs,
                        onCropChanged = viewModel::updateCropRect,
                        onTransformChanged = viewModel::updateTransform,
                        onPanChanged = viewModel::updatePan,
                        onVideoPositionChanged = viewModel::updateVideoPosition,
                        onSeekHandled = viewModel::clearSeekRequest,
                        onVideoPlaybackEnded = { viewModel.setVideoPlaying(false) }
                    )

                    if (media.mediaType == CropMediaType.VIDEO) {
                        VideoTimelineSection(
                            durationMs = media.durationMs,
                            trimStartMs = state.trimStartMs,
                            trimEndMs = state.trimEndMs,
                            currentPositionMs = state.currentVideoPositionMs,
                            onSeek = viewModel::seekVideo,
                            onTrimRangeChanged = viewModel::updateTrimRange
                        )
                    }

                    AspectRatioSection(
                        selected = state.aspectRatio,
                        customWidth = state.customAspectWidth,
                        customHeight = state.customAspectHeight,
                        customError = state.customAspectError,
                        onSelectRatio = viewModel::selectAspectRatio,
                        onCustomChanged = viewModel::updateCustomAspectRatio
                    )

                    if (media.mediaType == CropMediaType.VIDEO) {
                        VideoBitrateModeSection(
                            mode = state.bitrateMode,
                            customBitrateMbps = state.customBitrateMbps,
                            originalBitrateBps = media.originalBitrateBps,
                            onSelectMode = viewModel::selectBitrateMode,
                            onSliderChanged = viewModel::setManualCustomBitrate
                        )

                        VideoFormatSection(
                            options = media.videoExportOptions,
                            selected = state.selectedVideoFormat,
                            onSelect = viewModel::selectVideoFormat
                        )
                    } else {
                        ImageFormatSection(
                            options = media.imageExportOptions,
                            selected = state.selectedImageFormat,
                            onSelect = viewModel::selectImageFormat
                        )
                    }

                    val formatLabel = if (media.mediaType == CropMediaType.VIDEO) {
                        state.selectedVideoFormat?.label ?: "MP4"
                    } else {
                        state.selectedImageFormat?.label ?: "PNG"
                    }
                    ExportActionCard(
                        exportState = state.exportState,
                        formatLabel = formatLabel,
                        outputWidth = state.outputWidth,
                        outputHeight = state.outputHeight,
                        pipelineSupport = state.pipelineSupport,
                        onExport = viewModel::startExport,
                        onCancelExport = viewModel::cancelExport
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun WorkspaceHeaderActionButton(
    label: String,
    icon: ImageVector,
    active: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    Surface(
        shape = shape,
        color = if (active) FrameScopeColors.SelectionBlueTint else FrameScopeColors.CardSurface,
        border = BorderStroke(
            if (active) 1.5.dp else 1.dp,
            if (active) FrameScopeColors.SelectionBlue else FrameScopeColors.BorderSubtle
        ),
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(shape)
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active) FrameScopeColors.SelectionBlue else FrameScopeColors.TextPrimary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                ),
                color = if (active) FrameScopeColors.SelectionBlue else FrameScopeColors.TextPrimary
            )
        }
    }
}

@Composable
private fun ExportActionCard(
    exportState: CropExportState,
    formatLabel: String,
    outputWidth: Int,
    outputHeight: Int,
    pipelineSupport: CodecPipelineSupport?,
    onExport: () -> Unit,
    onCancelExport: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("crop_export_card"),
        shape = RoundedCornerShape(16.dp),
        color = FrameScopeColors.CardSurface,
        border = BorderStroke(
            1.dp,
            if (exportState is CropExportState.Exporting) {
                FrameScopeColors.SelectionBlue
            } else {
                FrameScopeColors.BorderSubtle
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (exportState is CropExportState.Exporting) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = exportState.statusText,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = FrameScopeColors.TextPrimary
                    )
                    Text(
                        text = "${(exportState.progress * 100f).toInt()}%",
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                        color = FrameScopeColors.SelectionBlue
                    )
                }
                LinearProgressIndicator(
                    progress = { exportState.progress.coerceIn(0f, 1f) },
                    color = FrameScopeColors.SelectionBlue,
                    trackColor = FrameScopeColors.InnerSurface,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(
                    onClick = onCancelExport,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Cancel", color = FrameScopeColors.SpecRedText)
                }
            } else {
                Button(
                    onClick = onExport,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = FrameScopeColors.SelectionBlue,
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(vertical = 14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("crop_export_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Crop,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Export $formatLabel (${outputWidth}×${outputHeight})",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            }

            if (pipelineSupport != null) {
                val hasAnyHw = pipelineSupport.isHardwareDecoder || pipelineSupport.isHardwareEncoder
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("crop_export_codec_status"),
                    shape = RoundedCornerShape(10.dp),
                    color = if (hasAnyHw) FrameScopeColors.ReadyGreenBg else FrameScopeColors.InnerSurface,
                    border = BorderStroke(
                        1.dp,
                        if (hasAnyHw) FrameScopeColors.ReadyGreen.copy(alpha = 0.4f) else FrameScopeColors.BorderSubtle
                    )
                ) {
                    Text(
                        text = pipelineSupport.statusText,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (hasAnyHw) FrameScopeColors.ReadyGreen else FrameScopeColors.TextSecondary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ExportedImagePreviewDialog(
    item: CropExportedItem,
    onDismiss: () -> Unit
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, item.filePath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(item.filePath, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 1440 || bounds.outHeight / sample > 1440) {
                    sample *= 2
                }
                BitmapFactory.decodeFile(
                    item.filePath,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
            }.getOrNull()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = FrameScopeColors.CardSurface,
            border = BorderStroke(1.dp, FrameScopeColors.BorderSubtle)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = item.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    color = FrameScopeColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(
                            if (item.width > 0 && item.height > 0) {
                                (item.width.toFloat() / item.height.toFloat()).coerceIn(0.5f, 1.8f)
                            } else {
                                1f
                            }
                        )
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF07090D)),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = bitmap
                    if (bmp != null) {
                        val imgBmp = remember(bmp) { bmp.asImageBitmap() }
                        Image(
                            bitmap = imgBmp,
                            contentDescription = item.fileName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        CircularProgressIndicator(color = FrameScopeColors.SelectionBlue)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${item.formatLabel} • ${item.width}×${item.height}",
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                        color = FrameScopeColors.TextSecondary
                    )
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = FrameScopeColors.SelectionBlue)
                    }
                }
            }
        }
    }
}
