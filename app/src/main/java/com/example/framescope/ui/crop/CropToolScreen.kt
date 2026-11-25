package com.example.framescope.ui.crop

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.framescope.R
import com.example.framescope.crop.CropAspectRatio
import com.example.framescope.crop.CropExportState
import com.example.framescope.crop.CropMediaType
import com.example.framescope.crop.VideoBitrateMode
import com.example.framescope.ui.components.SectionCard
import com.example.framescope.ui.theme.FrameScopeColors
import java.util.Locale

@Composable
fun CropToolScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: CropToolViewModel = viewModel(
        factory = CropToolViewModel.Factory(context.applicationContext)
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Direct Gallery Picker (Photo Picker) for All, Video, and Image
    val galleryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.importMedia(uri)
        }
    }

    // Direct Android File Manager (SAF OpenDocument) for Pick From File
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
                }

                val media = state.media
                if (media != null && !state.showImportPanel) {
                    // Header strip above 1:1 canvas with dimensions & Video Play button on the right
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = media.displayName,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = FrameScopeColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val fpsSuffix = if (media.mediaType == CropMediaType.VIDEO) " • ${media.fps} FPS" else ""
                            Text(
                                text = "${state.outputWidth} × ${state.outputHeight} (max ${media.displayWidth}×${media.displayHeight})$fpsSuffix",
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                color = FrameScopeColors.SelectionBlue
                            )
                        }

                        if (media.mediaType == CropMediaType.VIDEO) {
                            Surface(
                                shape = CircleShape,
                                color = FrameScopeColors.SelectionBlueTint,
                                border = BorderStroke(1.dp, FrameScopeColors.SelectionBlue),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .clip(CircleShape)
                                    .clickable(onClick = viewModel::toggleVideoPlayPause)
                                    .testTag("crop_video_play_pause_button")
                            ) {
                                Box(
                                    modifier = Modifier.size(40.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (state.isVideoPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        contentDescription = if (state.isVideoPlaying) "Pause video" else "Play video",
                                        tint = FrameScopeColors.TextPrimary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Square 1:1 preview & interactive crop canvas
                    CropWorkspaceCanvas(
                        media = media,
                        previewBitmap = state.previewBitmap,
                        cropRect = state.cropRect,
                        zoom = state.zoom,
                        panOffset = state.panOffset,
                        lockedAspectRatio = state.activeAspectRatioValue,
                        isVideoPlaying = state.isVideoPlaying,
                        onCropChanged = viewModel::updateCropRect,
                        onPanChanged = viewModel::updatePan,
                        onVideoPlaybackEnded = { viewModel.setVideoPlaying(false) }
                    )

                    // Zoom control bar
                    ZoomControlCard(
                        zoom = state.zoom,
                        onZoomChange = viewModel::updateZoom
                    )

                    // Horizontal Aspect Ratio Controls
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

                    // Export progress or primary Export button
                    val exportState = state.exportState
                    if (exportState is CropExportState.Exporting) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = FrameScopeColors.CardSurface,
                            border = BorderStroke(1.dp, FrameScopeColors.SelectionBlue)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
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
                                    onClick = viewModel::cancelExport,
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text("Cancel", color = FrameScopeColors.SpecRedText)
                                }
                            }
                        }
                    } else {
                        val formatLabel = if (media.mediaType == CropMediaType.VIDEO) {
                            state.selectedVideoFormat?.label ?: "MP4"
                        } else {
                            state.selectedImageFormat?.label ?: "PNG"
                        }
                        Button(
                            onClick = viewModel::startExport,
                            shape = RoundedCornerShape(14.dp),
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
                                text = "Export $formatLabel (${state.outputWidth}×${state.outputHeight})",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun ImportInterfaceCard(
    hasLoadedMedia: Boolean,
    onPickAll: () -> Unit,
    onPickVideo: () -> Unit,
    onPickImage: () -> Unit,
    onPickFromFile: () -> Unit,
    onCancelImport: () -> Unit
) {
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
            // Single horizontal row with All, Video, Image (direct Gallery)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ImportTileButton(
                    label = "All",
                    icon = Icons.Rounded.Collections,
                    testTag = "crop_import_all",
                    onClick = onPickAll,
                    modifier = Modifier.weight(1f)
                )
                ImportTileButton(
                    label = "Video",
                    icon = Icons.Rounded.VideoLibrary,
                    testTag = "crop_import_video",
                    onClick = onPickVideo,
                    modifier = Modifier.weight(1f)
                )
                ImportTileButton(
                    label = "Image",
                    icon = Icons.Rounded.Image,
                    testTag = "crop_import_image",
                    onClick = onPickImage,
                    modifier = Modifier.weight(1f)
                )
            }

            // Rectangular option directly below for Android File Manager
            OutlinedButton(
                onClick = onPickFromFile,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, FrameScopeColors.SelectionBlue),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = FrameScopeColors.SelectionBlueTint,
                    contentColor = FrameScopeColors.TextPrimary
                ),
                contentPadding = PaddingValues(vertical = 13.dp),
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
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        }
    }
}

@Composable
private fun ImportTileButton(
    label: String,
    icon: ImageVector,
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
                .padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = FrameScopeColors.SelectionBlue,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = FrameScopeColors.TextPrimary
            )
        }
    }
}

@Composable
private fun ZoomControlCard(
    zoom: Float,
    onZoomChange: (Float) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = FrameScopeColors.CardSurface,
        border = BorderStroke(1.dp, FrameScopeColors.BorderSubtle)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.ZoomIn,
                contentDescription = "Zoom",
                tint = FrameScopeColors.TextSecondary,
                modifier = Modifier.size(18.dp)
            )
            Slider(
                value = zoom,
                onValueChange = onZoomChange,
                valueRange = 1f..5f,
                colors = SliderDefaults.colors(
                    thumbColor = FrameScopeColors.SelectionBlue,
                    activeTrackColor = FrameScopeColors.SelectionBlue,
                    inactiveTrackColor = FrameScopeColors.InnerSurface
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("crop_zoom_slider")
            )
            Text(
                text = String.format(Locale.US, "%.1fx", zoom),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                ),
                color = FrameScopeColors.SelectionBlue
            )
            if (zoom > 1.02f) {
                IconButton(
                    onClick = { onZoomChange(1f) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Reset zoom",
                        tint = FrameScopeColors.TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AspectRatioSection(
    selected: CropAspectRatio,
    customWidth: String,
    customHeight: String,
    customError: String?,
    onSelectRatio: (CropAspectRatio) -> Unit,
    onCustomChanged: (String, String) -> Unit
) {
    SectionCard(title = "Aspect Ratio") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CropAspectRatio.entries.forEach { ratio ->
                    val isSelected = selected == ratio
                    val chipShape = RoundedCornerShape(10.dp)
                    Surface(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(chipShape)
                            .clickable { onSelectRatio(ratio) }
                            .testTag("aspect_chip_${ratio.name.lowercase()}"),
                        shape = chipShape,
                        color = if (isSelected) FrameScopeColors.SelectionBlueTint else FrameScopeColors.InnerSurface,
                        border = BorderStroke(
                            if (isSelected) 1.5.dp else 1.dp,
                            if (isSelected) FrameScopeColors.SelectionBlue else FrameScopeColors.BorderSubtle
                        )
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = ratio.label,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                ),
                                color = if (isSelected) FrameScopeColors.SelectionBlue else FrameScopeColors.TextPrimary
                            )
                        }
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
                        onValueChange = { onCustomChanged(it, customHeight) },
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
                        onValueChange = { onCustomChanged(customWidth, it) },
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
        }
    }
}

@Composable
private fun VideoBitrateModeSection(
    mode: VideoBitrateMode,
    customBitrateMbps: Float,
    originalBitrateBps: Long?,
    onSelectMode: (VideoBitrateMode) -> Unit,
    onSliderChanged: (Float) -> Unit
) {
    val origMbpsText = if (originalBitrateBps != null && originalBitrateBps > 0L) {
        String.format(Locale.US, "%.1f Mbps", originalBitrateBps / 1_000_000.0)
    } else {
        "Unavailable"
    }

    SectionCard(title = "Bitrate Mode") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Mode A: Custom (includes automatic calculation + manual slider 0-50 Mbps)
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

            // Mode B: Original Preservation
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Original Preservation",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = FrameScopeColors.TextPrimary
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
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
            }
        }
    }
}

@Composable
private fun ImageFormatSection(
    options: List<com.example.framescope.crop.ImageExportFormatOption>,
    selected: com.example.framescope.crop.ImageExportFormatOption?,
    onSelect: (com.example.framescope.crop.ImageExportFormatOption) -> Unit
) {
    SectionCard(title = "Export Format") {
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
                    testTag = "image_format_${option.id.name.lowercase()}",
                    onClick = { onSelect(option) }
                )
            }
        }
    }
}

@Composable
private fun VideoFormatSection(
    options: List<com.example.framescope.crop.VideoExportFormatOption>,
    selected: com.example.framescope.crop.VideoExportFormatOption?,
    onSelect: (com.example.framescope.crop.VideoExportFormatOption) -> Unit
) {
    SectionCard(title = "Export Format") {
        if (options.isEmpty()) {
            Text(
                text = "No supported video export muxer/encoder found on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = FrameScopeColors.SpecRedText
            )
        } else {
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
                        testTag = "video_format_${option.container.name.lowercase()}",
                        onClick = { onSelect(option) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FormatChip(
    label: String,
    selected: Boolean,
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
        Box(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (selected) FrameScopeColors.SelectionBlue else FrameScopeColors.TextPrimary
            )
        }
    }
}
