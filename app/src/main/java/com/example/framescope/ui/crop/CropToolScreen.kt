package com.example.framescope.ui.crop

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.framescope.R
import com.example.framescope.capability.OptionSupportInfo
import com.example.framescope.crop.CanvasAspectMode
import com.example.framescope.crop.CodecPipelineSupport
import com.example.framescope.crop.CropExportState
import com.example.framescope.crop.CropMediaType
import com.example.framescope.crop.CropProjectRecord
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import com.example.framescope.ui.components.AeroGlassBackground
import com.example.framescope.ui.components.NeumorphicGlassCard
import com.example.framescope.ui.components.NeumorphicIconButton
import com.example.framescope.ui.components.SupportStatusPill
import com.example.framescope.ui.theme.FrameScopeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    val coroutineScope = rememberCoroutineScope()
    var previewImageProject by remember { mutableStateOf<CropProjectRecord?>(null) }
    var viewingRecentProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var showProjectSettingsDialog by remember { mutableStateOf(false) }
    var pendingDeleteProject by remember { mutableStateOf<CropProjectRecord?>(null) }
    var canvasMenuExpanded by remember { mutableStateOf(false) }

    val galleryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
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

    LaunchedEffect(state.showImportPanel) {
        if (state.showImportPanel) {
            viewModel.refreshRecentProjects()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.saveProjectOnExit()
        }
    }

    var pendingNotificationAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        pendingNotificationAction?.invoke()
        pendingNotificationAction = null
    }

    fun runWithExportNotificationPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingNotificationAction = action
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(state.shareExportReady, lifecycleOwner) {
        if (state.shareExportReady == null) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val latest = viewModel.uiState.value.shareExportReady ?: return@repeatOnLifecycle
            viewingRecentProjectId = latest.updatedProject.id
            val shareUri = runCatching { Uri.parse(latest.shareUriString) }.getOrNull()
            viewModel.consumeShareReadyEvent()
            if (shareUri != null) {
                val err = launchShareSheet(context, shareUri, latest.mimeType)
                if (err != null) {
                    snackbarHostState.showSnackbar(err)
                }
            }
        }
    }

    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearMessage()
    }

    BackHandler(enabled = state.isPreviewMode && viewingRecentProjectId == null) {
        viewModel.exitPreviewMode()
    }

    BackHandler(enabled = !state.isPreviewMode && state.showImportPanel && state.media != null && viewingRecentProjectId == null) {
        viewModel.closeImportPanelIfLoaded()
    }

    previewImageProject?.let { project ->
        ExportedImagePreviewDialog(
            project = project,
            onDismiss = { previewImageProject = null }
        )
    }

    if (showProjectSettingsDialog) {
        ProjectSourceSettingsDialog(
            copyOriginalSourceEnabled = state.copyOriginalSourceEnabled,
            onToggleCopyOriginalSource = viewModel::setCopyOriginalSource,
            onDismiss = { showProjectSettingsDialog = false }
        )
    }

    pendingDeleteProject?.let { project ->
        AlertDialog(
            onDismissRequest = { pendingDeleteProject = null },
            containerColor = FrameScopeColors.BackgroundSecondary,
            title = {
                Text(
                    text = "Delete Project?",
                    color = FrameScopeColors.TextPrimary
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to delete \"${project.title}\"? Your exported file in the Gallery will not be deleted.",
                    color = FrameScopeColors.TextSecondary
                )
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDeleteProject = null },
                    modifier = Modifier.testTag("cancel_delete_project_button")
                ) {
                    Text("Cancel", color = FrameScopeColors.TextSecondary)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteProject(project.id)
                        pendingDeleteProject = null
                    },
                    modifier = Modifier.testTag("confirm_delete_project_button")
                ) {
                    Text("Delete", color = FrameScopeColors.SpecRedText, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (state.exportState is CropExportState.Success) {
        val result = state.exportState as CropExportState.Success
        AlertDialog(
            onDismissRequest = viewModel::dismissExportState,
            containerColor = FrameScopeColors.BackgroundSecondary,
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
                    Text("View Projects", color = FrameScopeColors.TextSecondary)
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
            containerColor = FrameScopeColors.BackgroundSecondary,
            title = { Text("Export Error", color = FrameScopeColors.TextPrimary) },
            text = { Text(err, color = FrameScopeColors.TextSecondary) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissExportState) {
                    Text("OK", color = FrameScopeColors.SelectionBlue)
                }
            }
        )
    }

    val isWorkspaceEditingVisible = state.media != null && !state.showImportPanel

    AeroGlassBackground {
        Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.navigationBarsPadding()
            )
        },
        topBar = {
            if (isWorkspaceEditingVisible) {
                CropWorkspaceTopToolbar(
                    canvasAspectMode = state.canvasAspectMode,
                    canvasRatioSupport = state.cropCapabilities.canvasRatioSupport,
                    canvasMenuExpanded = canvasMenuExpanded,
                    canUndo = state.canUndo,
                    canRedo = state.canRedo,
                    canReset = state.canReset,
                    isZoomEnabled = state.isZoomEnabled,
                    isPreviewMode = state.isPreviewMode,
                    onBack = {
                        if (state.isPreviewMode) {
                            viewModel.exitPreviewMode()
                        } else {
                            viewModel.saveProjectOnExit()
                            onBack()
                        }
                    },
                    onOpenCanvasMenu = {
                        if (!state.isPreviewMode) {
                            canvasMenuExpanded = true
                        }
                    },
                    onDismissCanvasMenu = { canvasMenuExpanded = false },
                    onSelectCanvasMode = { mode ->
                        canvasMenuExpanded = false
                        viewModel.selectCanvasAspectMode(mode)
                    },
                    onUndo = viewModel::undo,
                    onRedo = viewModel::redo,
                    onReset = viewModel::resetCropSelection,
                    onToggleZoom = viewModel::toggleZoomMode
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NeumorphicIconButton(
                        onClick = {
                            viewModel.saveProjectOnExit()
                            onBack()
                        },
                        modifier = Modifier.testTag("crop_back_button")
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
                        text = stringResource(R.string.crop_tool_title),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = FrameScopeColors.TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
        ) {
            val maxWorkspaceHeightDp = (maxHeight * 0.56f).coerceIn(300.dp, 540.dp)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 680.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
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
                            importSupport = state.cropCapabilities.importSupport,
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

                        RecentProjectsSection(
                            projects = state.recentProjects,
                            thumbnails = state.projectThumbnails,
                            onOpenSettings = { showProjectSettingsDialog = true },
                            onOpenInGallery = { project ->
                                viewingRecentProjectId = project.id
                            },
                            onEditProject = viewModel::openProject,
                            onRequestDelete = { project ->
                                pendingDeleteProject = project
                            }
                        )
                    }

                    val media = state.media
                    if (media != null && !state.showImportPanel) {
                        if (state.showCanvasOptions && state.canvasAspectMode == CanvasAspectMode.CUSTOM && !state.isPreviewMode) {
                            CanvasAspectOptionsBar(
                                customWidth = state.customCanvasWidth,
                                customHeight = state.customCanvasHeight,
                                onCustomChanged = viewModel::updateCustomCanvasRatio,
                                onClose = viewModel::closeCanvasOptions
                            )
                        }

                        // Single Central Workspace Canvas (renders editing OR non-destructive Live Crop Preview)
                        CropWorkspaceCanvas(
                            media = media,
                            previewBitmap = state.previewBitmap,
                            cropRect = state.cropRect,
                            zoom = state.zoom,
                            panOffset = state.panOffset,
                            isZoomEnabled = state.isZoomEnabled,
                            isPreviewMode = state.isPreviewMode,
                            canvasAspectRatio = state.activeCanvasAspectRatio,
                            cropCompositionAspectRatio = state.cropCompositionAspectRatio,
                            lockedAspectRatio = state.activeAspectRatioValue,
                            maxWorkspaceHeightDp = maxWorkspaceHeightDp,
                            isVideoPlaying = state.isVideoPlaying,
                            isVideoMuted = state.isVideoMuted,
                            trimStartMs = state.trimStartMs,
                            trimEndMs = state.trimEndMs,
                            seekRequestMs = state.seekRequestMs,
                            onCropGestureStart = viewModel::beginCropGesture,
                            onCropGestureEnd = viewModel::commitCropGesture,
                            onCropChanged = viewModel::updateCropRect,
                            onTransformChanged = viewModel::updateTransform,
                            onPanChanged = viewModel::updatePan,
                            onVideoPositionChanged = viewModel::updateVideoPosition,
                            onSeekHandled = viewModel::clearSeekRequest,
                            onVideoPlaybackEnded = { viewModel.setVideoPlaying(false) }
                        )

                        // Action Strip directly below Workspace:
                        // Left: Dimensions + (Import when editing OR Volume Mute/Unmute in Preview Mode)
                        // Right: Play/Pause + Preview/Stop
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f, fill = false),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val fpsSuffix = if (media.mediaType == CropMediaType.VIDEO) " • ${media.fps} FPS" else ""
                                Text(
                                    text = "${state.outputWidth} × ${state.outputHeight}$fpsSuffix",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontFamily = FontFamily.Default,
                                        fontFeatureSettings = "tnum",
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = FrameScopeColors.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (!state.isPreviewMode) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = FrameScopeColors.InnerSurface,
                                        border = BorderStroke(1.dp, FrameScopeColors.BorderSubtle),
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable(onClick = viewModel::openImportPanel)
                                            .testTag("crop_change_media_button")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.FolderOpen,
                                                contentDescription = "Import media",
                                                tint = FrameScopeColors.SelectionBlue,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = "Import",
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                ),
                                                color = FrameScopeColors.TextPrimary,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                } else if (media.mediaType == CropMediaType.VIDEO) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (state.isVideoMuted) {
                                            FrameScopeColors.SpecRedContainer.copy(alpha = 0.7f)
                                        } else {
                                            FrameScopeColors.SelectionBlueTint
                                        },
                                        border = BorderStroke(
                                            1.dp,
                                            if (state.isVideoMuted) {
                                                FrameScopeColors.SpecRedBorder
                                            } else {
                                                FrameScopeColors.SelectionBlue
                                            }
                                        ),
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable(onClick = viewModel::toggleVideoMuted)
                                            .testTag("crop_preview_mute_button")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (state.isVideoMuted) {
                                                    Icons.AutoMirrored.Rounded.VolumeOff
                                                } else {
                                                    Icons.AutoMirrored.Rounded.VolumeUp
                                                },
                                                contentDescription = if (state.isVideoMuted) "Unmute volume" else "Mute volume",
                                                tint = if (state.isVideoMuted) {
                                                    FrameScopeColors.SpecRedText
                                                } else {
                                                    FrameScopeColors.SelectionBlue
                                                },
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Text(
                                                text = if (state.isVideoMuted) "Muted" else "Sound",
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                ),
                                                color = if (state.isVideoMuted) {
                                                    FrameScopeColors.SpecRedText
                                                } else {
                                                    FrameScopeColors.TextPrimary
                                                },
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (media.mediaType == CropMediaType.VIDEO) {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = FrameScopeColors.SelectionBlueTint,
                                        border = BorderStroke(1.25.dp, FrameScopeColors.SelectionBlue),
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable(onClick = viewModel::toggleVideoPlayPause)
                                            .testTag("crop_preview_play_pause_button")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (state.isVideoPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                                contentDescription = if (state.isVideoPlaying) "Pause" else "Play",
                                                tint = FrameScopeColors.SelectionBlue,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = if (state.isVideoPlaying) "Pause" else "Play",
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    fontSize = 12.5.sp,
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                color = FrameScopeColors.TextPrimary,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }

                                if (!state.isPreviewMode) {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = FrameScopeColors.SelectionBlueTint,
                                        border = BorderStroke(1.25.dp, FrameScopeColors.SelectionBlue),
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable(onClick = viewModel::enterPreviewMode)
                                            .testTag("crop_preview_button")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.PlayArrow,
                                                contentDescription = "Preview cropped media",
                                                tint = FrameScopeColors.SelectionBlue,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = "Preview",
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    fontSize = 12.5.sp,
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                color = FrameScopeColors.TextPrimary,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                } else {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = FrameScopeColors.SpecRedContainer,
                                        border = BorderStroke(1.25.dp, FrameScopeColors.RecordRed),
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable(onClick = viewModel::exitPreviewMode)
                                            .testTag("crop_stop_preview_button")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Stop,
                                                contentDescription = "Stop preview",
                                                tint = FrameScopeColors.SpecRedText,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = "Stop",
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    fontSize = 12.5.sp,
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                color = FrameScopeColors.TextPrimary,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        AspectRatioSection(
                            selected = state.aspectRatio,
                            customWidth = state.customAspectWidth,
                            customHeight = state.customAspectHeight,
                            customError = state.customAspectError,
                            enabled = true,
                            aspectRatioSupport = state.aspectRatioSupport,
                            onSelectRatio = viewModel::selectAspectRatio,
                            onCustomChanged = viewModel::updateCustomAspectRatio
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

                            VideoBitrateModeSection(
                                originalBitrateBps = media.originalBitrateBps,
                                bitrateModeSupport = state.bitrateModeSupport
                            )

                            VideoFormatSection(
                                options = media.videoExportOptions,
                                sourceExtension = media.extension
                            )
                        } else {
                            ImageFormatSection(
                                options = media.imageExportOptions,
                                selected = state.selectedImageFormat,
                                onSelect = viewModel::selectImageFormat
                            )
                        }

                        val formatLabel = if (media.mediaType == CropMediaType.VIDEO) {
                            "MP4"
                        } else {
                            state.selectedImageFormat?.label ?: "PNG"
                        }
                        ExportActionCard(
                            exportState = state.exportState,
                            formatLabel = formatLabel,
                            outputWidth = state.outputWidth,
                            outputHeight = state.outputHeight,
                            pipelineSupport = state.pipelineSupport,
                            onExport = { runWithExportNotificationPermission(viewModel::startExport) },
                            onCancelExport = viewModel::cancelExport
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }
            }
        }
        }

        val activeViewerProject = viewingRecentProjectId?.let { selectedId ->
            state.recentProjects.firstOrNull { it.id == selectedId }
        }
        if (activeViewerProject != null) {
            ProjectMediaViewer(
                project = activeViewerProject,
                isExportingForShare = state.isSharingProjectExport,
                exportProgress = state.shareExportProgress,
                exportStatusText = state.shareExportStatus,
                onBack = { viewingRecentProjectId = null },
                onOpenProject = { proj ->
                    viewingRecentProjectId = null
                    viewModel.openProject(proj)
                },
                onShareProject = { proj ->
                    runWithExportNotificationPermission {
                        viewModel.shareProjectFromViewer(proj) { shareUri, mimeType, updated ->
                            viewingRecentProjectId = updated.id
                            val err = launchShareSheet(context, shareUri, mimeType)
                            if (err != null) {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(err)
                                }
                            }
                        }
                    }
                },
                onCancelShareExport = viewModel::cancelShareProjectExport
            )
        }
        }
    }
}

@Composable
private fun CropWorkspaceTopToolbar(
    canvasAspectMode: CanvasAspectMode,
    canvasRatioSupport: Map<CanvasAspectMode, OptionSupportInfo>,
    canvasMenuExpanded: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    canReset: Boolean,
    isZoomEnabled: Boolean,
    isPreviewMode: Boolean,
    onBack: () -> Unit,
    onOpenCanvasMenu: () -> Unit,
    onDismissCanvasMenu: () -> Unit,
    onSelectCanvasMode: (CanvasAspectMode) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onReset: () -> Unit,
    onToggleZoom: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(FrameScopeColors.CardSurface)
            .statusBarsPadding()
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(42.dp)
                .testTag("crop_back_button")
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                tint = FrameScopeColors.TextPrimary
            )
        }

        Text(
            text = stringResource(R.string.crop_tool_title),
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            ),
            color = FrameScopeColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        // Canvas Aspect Ratio Dropdown Control (independent of Crop Selection Aspect Ratio)
        Box {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .alpha(if (isPreviewMode) 0.45f else 1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = !isPreviewMode, onClick = onOpenCanvasMenu)
                    .padding(horizontal = 3.dp, vertical = 2.dp)
                    .testTag("crop_canvas_button")
            ) {
                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = FrameScopeColors.SelectionBlueTint,
                    border = BorderStroke(1.25.dp, FrameScopeColors.SelectionBlue)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = canvasAspectMode.label,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            ),
                            color = FrameScopeColors.TextPrimary,
                            maxLines = 1
                        )
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "Select canvas ratio",
                            tint = FrameScopeColors.SelectionBlue,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Text(
                    text = "Canvas Ratio",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = FrameScopeColors.SelectionBlue,
                    maxLines = 1
                )
            }

            DropdownMenu(
                expanded = canvasMenuExpanded,
                onDismissRequest = onDismissCanvasMenu,
                containerColor = FrameScopeColors.CardSurface
            ) {
                CanvasAspectMode.entries.forEach { mode ->
                    val isSelected = mode == canvasAspectMode
                    val modeInfo = canvasRatioSupport[mode] ?: OptionSupportInfo(true)
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = mode.label,
                                    color = if (isSelected) FrameScopeColors.SelectionBlue else FrameScopeColors.TextPrimary,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                                SupportStatusPill(
                                    isSupported = modeInfo.isSupported,
                                    compact = true
                                )
                            }
                        },
                        trailingIcon = if (isSelected) {
                            {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = FrameScopeColors.SelectionBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        } else {
                            null
                        },
                        onClick = { onSelectCanvasMode(mode) },
                        modifier = Modifier.testTag("canvas_ratio_${mode.name.lowercase()}")
                    )
                }
            }
        }

        ToolbarIconLabelAction(
            label = "Undo",
            icon = Icons.AutoMirrored.Rounded.Undo,
            enabled = canUndo,
            active = false,
            testTag = "crop_undo_button",
            onClick = onUndo
        )

        ToolbarIconLabelAction(
            label = "Redo",
            icon = Icons.AutoMirrored.Rounded.Redo,
            enabled = canRedo,
            active = false,
            testTag = "crop_redo_button",
            onClick = onRedo
        )

        ToolbarIconLabelAction(
            label = "Reset",
            icon = Icons.Rounded.Refresh,
            enabled = canReset,
            active = false,
            testTag = "crop_reset_button",
            onClick = onReset
        )

        Box(
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .width(1.dp)
                .height(28.dp)
                .background(FrameScopeColors.BorderSubtle)
        )

        ToolbarIconLabelAction(
            label = "Zoom",
            icon = Icons.Rounded.ZoomIn,
            enabled = true,
            active = isZoomEnabled,
            activeColor = FrameScopeColors.SelectionBlue,
            testTag = "crop_zoom_toggle_button",
            onClick = onToggleZoom
        )
    }
}

@Composable
private fun ToolbarIconLabelAction(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    active: Boolean,
    activeColor: Color = FrameScopeColors.SelectionBlue,
    testTag: String,
    onClick: () -> Unit
) {
    val contentColor = when {
        !enabled -> FrameScopeColors.TextMuted.copy(alpha = 0.5f)
        active -> activeColor
        else -> FrameScopeColors.TextPrimary
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(9.dp))
            .background(if (active) FrameScopeColors.SelectionBlueTint else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 4.dp)
            .testTag(testTag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(19.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 10.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
            ),
            color = contentColor,
            maxLines = 1
        )
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
    NeumorphicGlassCard(
        modifier = Modifier.testTag("crop_export_card"),
        cornerRadius = 22.dp,
        elevation = 6.dp,
        accentBorder = if (exportState is CropExportState.Exporting) {
            FrameScopeColors.SelectionBlue
        } else {
            null
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
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
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = FontFamily.Default,
                            fontFeatureSettings = "tnum"
                        ),
                        color = FrameScopeColors.SelectionBlue
                    )
                }
                LinearProgressIndicator(
                    progress = { exportState.progress.coerceIn(0f, 1f) },
                    color = FrameScopeColors.SelectionBlue,
                    trackColor = FrameScopeColors.InsetTrack,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(50))
                )
                TextButton(
                    onClick = onCancelExport,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Cancel", color = FrameScopeColors.SpecRedText)
                }
            } else {
                val btnShape = RoundedCornerShape(50)
                Button(
                    onClick = onExport,
                    shape = btnShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = FrameScopeColors.SelectionBlue,
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(vertical = 15.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .shadow(
                            elevation = 10.dp,
                            shape = btnShape,
                            ambientColor = FrameScopeColors.SelectionBlue.copy(alpha = 0.4f),
                            spotColor = FrameScopeColors.SelectionBlue.copy(alpha = 0.5f)
                        )
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
                    shape = RoundedCornerShape(12.dp),
                    color = if (hasAnyHw) FrameScopeColors.ReadyGreenBg else FrameScopeColors.InsetTrack.copy(alpha = 0.55f),
                    border = BorderStroke(
                        1.dp,
                        if (hasAnyHw) FrameScopeColors.ReadyGreen.copy(alpha = 0.4f) else FrameScopeColors.GlassRimBottom
                    )
                ) {
                    Text(
                        text = pipelineSupport.statusText,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (hasAnyHw) FrameScopeColors.ReadyGreen else FrameScopeColors.TextSecondary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ExportedImagePreviewDialog(
    project: CropProjectRecord,
    onDismiss: () -> Unit
) {
    val filePath = project.exportedFilePath.orEmpty()
    val imgW = if (project.exportedWidth > 0) project.exportedWidth else project.outputWidth
    val imgH = if (project.exportedHeight > 0) project.exportedHeight else project.outputHeight
    val formatLabel = project.exportedFormatLabel ?: "Image"

    val bitmap by produceState<Bitmap?>(initialValue = null, filePath) {
        value = withContext(Dispatchers.IO) {
            if (filePath.isBlank()) return@withContext null
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(filePath, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 1440 || bounds.outHeight / sample > 1440) {
                    sample *= 2
                }
                BitmapFactory.decodeFile(
                    filePath,
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
                    text = project.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = FrameScopeColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(
                            if (imgW > 0 && imgH > 0) {
                                (imgW.toFloat() / imgH.toFloat()).coerceIn(0.5f, 1.8f)
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
                            contentDescription = project.title,
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
                        text = "$formatLabel • ${imgW}×${imgH}",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = FontFamily.Default,
                            fontFeatureSettings = "tnum"
                        ),
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

private fun launchShareSheet(
    context: Context,
    shareUri: Uri,
    mimeType: String
): String? {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, shareUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return runCatching {
        context.startActivity(Intent.createChooser(sendIntent, "Share"))
        null
    }.getOrElse {
        "Unable to open share sheet."
    }
}
