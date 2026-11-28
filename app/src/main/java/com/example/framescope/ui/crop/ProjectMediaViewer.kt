package com.example.framescope.ui.crop

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.example.framescope.crop.CanvasAspectMode
import com.example.framescope.crop.CropMediaType
import com.example.framescope.crop.CropProjectRecord
import com.example.framescope.crop.NormalizedCropRect
import com.example.framescope.ui.theme.FrameScopeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private enum class ViewerHudMode {
    NONE,
    BRIGHTNESS,
    VOLUME
}

private enum class ViewerDragAxis {
    UNDECIDED,
    BRIGHTNESS,
    VOLUME,
    SWIPE_PROJECT,
    PAN_ZOOMED
}

private data class ResolvedPlaybackSource(
    val uri: Uri?,
    val isExportedFile: Boolean
)

private val GoogleFilesAccentBlue = Color(0xFFA8C7FA)
private val GoogleFilesSurface = Color(0xFF1F2024)
private val GoogleFilesSurfaceAlt = Color(0xFF2D2E33)

private const val VIEWER_PREFS_NAME = "framescope_viewer_prefs"
private const val KEY_SKIP_SECONDS = "double_tap_skip_seconds"

@OptIn(UnstableApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun ProjectMediaViewer(
    project: CropProjectRecord,
    projects: List<CropProjectRecord> = listOf(project),
    isExportingForShare: Boolean,
    exportProgress: Float,
    exportStatusText: String,
    onBack: () -> Unit,
    onSelectProject: (CropProjectRecord) -> Unit = {},
    onOpenProject: (CropProjectRecord) -> Unit,
    onShareProject: (CropProjectRecord) -> Unit,
    onCancelShareExport: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember(context) {
        context.getSharedPreferences(VIEWER_PREFS_NAME, Context.MODE_PRIVATE)
    }

    val currentIndex = remember(project.id, projects) {
        projects.indexOfFirst { it.id == project.id }
    }
    val prevProject = if (currentIndex > 0) projects.getOrNull(currentIndex - 1) else null
    val nextProject = if (currentIndex >= 0 && currentIndex < projects.lastIndex) {
        projects.getOrNull(currentIndex + 1)
    } else {
        null
    }

    val playbackSource = remember(
        project.id,
        project.exportedFilePath,
        project.exportedGalleryUriString,
        project.localCopyPath,
        project.sourceUriString
    ) {
        resolveProjectPlaybackSource(context, project)
    }
    val resolvedUri = playbackSource.uri

    // Tap-only Portrait / Landscape toggle (never connected to orientation sensors)
    var isLandscape by rememberSaveable { mutableStateOf(false) }

    val initialOrientation = remember(activity) {
        activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    val initialWindowBrightness = remember(activity) {
        activity?.window?.attributes?.screenBrightness
            ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }

    DisposableEffect(activity) {
        onDispose {
            activity?.requestedOrientation = initialOrientation
            if (activity != null) {
                val attrs = activity.window.attributes
                attrs.screenBrightness = initialWindowBrightness
                activity.window.attributes = attrs
            }
        }
    }

    LaunchedEffect(activity, isLandscape) {
        if (activity != null) {
            activity.requestedOrientation = if (isLandscape) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
    }

    // Customizable Double-Tap Skip Seconds (persisted: presets 2s, 5s, 10s, 20s, or Custom)
    var skipSeconds by remember {
        mutableFloatStateOf(
            prefs.getFloat(KEY_SKIP_SECONDS, 10f).coerceIn(0.5f, 300f)
        )
    }
    var playbackSpeed by rememberSaveable(project.id) { mutableFloatStateOf(1f) }
    var isLooping by rememberSaveable(project.id) { mutableStateOf(false) }

    // Overflow & Dialog states
    var showMoreMenu by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showSkipDialog by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }

    BackHandler {
        when {
            showMoreMenu -> showMoreMenu = false
            showSpeedDialog -> showSpeedDialog = false
            showSkipDialog -> showSkipDialog = false
            showInfoDialog -> showInfoDialog = false
            !isExportingForShare -> onBack()
        }
    }

    // Brightness state (0.02f..1.0f)
    var brightnessLevel by remember {
        mutableFloatStateOf(readInitialBrightness(context, activity))
    }

    // Volume state (0f..1f) for right-half vertical swipe
    val maxStreamVol = remember(audioManager) {
        (audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15).coerceAtLeast(1)
    }
    var volumeLevel by remember {
        mutableFloatStateOf(
            ((audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 8).toFloat() / maxStreamVol.toFloat())
                .coerceIn(0f, 1f)
        )
    }

    // Vertical swipe HUD feedback
    var activeHudMode by remember { mutableStateOf(ViewerHudMode.NONE) }
    var hudTriggerNonce by remember { mutableIntStateOf(0) }
    var isVerticalHudDragging by remember { mutableStateOf(false) }

    LaunchedEffect(hudTriggerNonce, isVerticalHudDragging) {
        if (activeHudMode != ViewerHudMode.NONE && !isVerticalHudDragging) {
            delay(950L)
            activeHudMode = ViewerHudMode.NONE
        }
    }

    // Files by Google overlay visibility & double-tap seek indicator
    var controlsVisible by remember(project.id) { mutableStateOf(true) }
    var controlsInteractionNonce by remember { mutableIntStateOf(0) }
    var doubleTapSeekDisplaySeconds by remember { mutableFloatStateOf(0f) }
    var doubleTapSeekNonce by remember { mutableIntStateOf(0) }

    LaunchedEffect(doubleTapSeekNonce) {
        if (doubleTapSeekDisplaySeconds != 0f) {
            delay(750L)
            doubleTapSeekDisplaySeconds = 0f
        }
    }

    // Video playback state (Media3 ExoPlayer): Auto-play on open
    var isPlayerPrepared by remember(project.id, resolvedUri) { mutableStateOf(false) }
    var isPlaying by remember(project.id, resolvedUri) {
        mutableStateOf(project.mediaType == CropMediaType.VIDEO)
    }
    var hasCompleted by remember(project.id, resolvedUri) { mutableStateOf(false) }
    var isLifecycleResumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    val isDraftTrimmed = !playbackSource.isExportedFile &&
        project.mediaType == CropMediaType.VIDEO &&
        project.trimEndMs > project.trimStartMs

    var videoDurationMs by remember(project.id, resolvedUri) {
        val fallbackDur = if (playbackSource.isExportedFile && project.exportedDurationMs > 0L) {
            project.exportedDurationMs
        } else {
            project.durationMs
        }
        mutableLongStateOf(fallbackDur.coerceAtLeast(0L))
    }
    var currentPosMs by remember(project.id, resolvedUri) {
        mutableLongStateOf(if (isDraftTrimmed) project.trimStartMs.coerceAtLeast(0L) else 0L)
    }
    var isScrubbing by remember(project.id, resolvedUri) { mutableStateOf(false) }
    var scrubPreviewMs by remember(project.id, resolvedUri) { mutableLongStateOf(0L) }
    var sourceVideoWidth by remember(project.id, resolvedUri) { mutableIntStateOf(0) }
    var sourceVideoHeight by remember(project.id, resolvedUri) { mutableIntStateOf(0) }
    var playbackError by remember(project.id, resolvedUri) { mutableStateOf<String?>(null) }

    val exoPlayer = remember(project.id, resolvedUri, project.mediaType) {
        if (project.mediaType == CropMediaType.VIDEO && resolvedUri != null) {
            runCatching {
                val renderersFactory = DefaultRenderersFactory(context).apply {
                    setEnableDecoderFallback(true)
                }
                ExoPlayer.Builder(context, renderersFactory).build().apply {
                    repeatMode = if (isLooping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                    playbackParameters = PlaybackParameters(playbackSpeed)
                    volume = 1f
                    setMediaItem(MediaItem.fromUri(resolvedUri))
                    if (isDraftTrimmed && project.trimStartMs > 0L) {
                        seekTo(project.trimStartMs)
                    }
                    prepare()
                    playWhenReady = isPlaying && isLifecycleResumed
                }
            }.onFailure { err ->
                playbackError = err.message ?: "Unable to initialize video player."
            }.getOrNull()
        } else {
            null
        }
    }

    DisposableEffect(exoPlayer) {
        val player = exoPlayer
        val listener = if (player != null) {
            object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        val par = videoSize.pixelWidthHeightRatio
                            .takeIf { it.isFinite() && it > 0f } ?: 1f
                        sourceVideoWidth = (videoSize.width * par).roundToInt().coerceAtLeast(1)
                        sourceVideoHeight = videoSize.height
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            isPlayerPrepared = true
                            playbackError = null
                            val dur = player.duration
                            if (dur != C.TIME_UNSET && dur > 0L) {
                                videoDurationMs = dur
                            }
                        }

                        Player.STATE_ENDED -> {
                            if (!isLooping) {
                                isPlaying = false
                                hasCompleted = true
                                currentPosMs = videoDurationMs
                                controlsVisible = true
                            }
                        }

                        else -> Unit
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    isPlayerPrepared = false
                    isPlaying = false
                    playbackError = error.localizedMessage ?: "Playback failed (${error.errorCodeName})."
                }
            }.also { player.addListener(it) }
        } else {
            null
        }

        onDispose {
            isPlayerPrepared = false
            if (player != null) {
                if (listener != null) {
                    player.removeListener(listener)
                }
                runCatching { player.release() }
            }
        }
    }

    LaunchedEffect(
        project.id,
        controlsVisible,
        isPlaying,
        isScrubbing,
        showMoreMenu,
        showSpeedDialog,
        showSkipDialog,
        showInfoDialog,
        controlsInteractionNonce
    ) {
        if (controlsVisible &&
            project.mediaType == CropMediaType.VIDEO &&
            isPlaying &&
            !isScrubbing &&
            !showMoreMenu &&
            !showSpeedDialog &&
            !showSkipDialog &&
            !showInfoDialog
        ) {
            delay(3200L)
            controlsVisible = false
        }
    }

    // Pause video + audio when app leaves foreground, auto-resume on return unless paused manually
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    isLifecycleResumed = true
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    isLifecycleResumed = false
                    exoPlayer?.let { player ->
                        runCatching {
                            currentPosMs = player.currentPosition.coerceAtLeast(0L)
                            player.playWhenReady = false
                        }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(exoPlayer, isPlaying, isLifecycleResumed, project.id) {
        val player = exoPlayer ?: return@LaunchedEffect
        val shouldPlay = isPlaying && isLifecycleResumed
        if (shouldPlay) {
            val minStart = if (isDraftTrimmed) project.trimStartMs.coerceAtLeast(0L) else 0L
            val maxEnd = if (isDraftTrimmed) project.trimEndMs else videoDurationMs
            if (hasCompleted || (maxEnd > minStart && currentPosMs >= maxEnd - 80L)) {
                player.seekTo(minStart)
                currentPosMs = minStart
                hasCompleted = false
            }
            if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
            player.playWhenReady = true
        } else {
            currentPosMs = player.currentPosition.coerceAtLeast(0L)
            player.playWhenReady = false
        }
    }

    LaunchedEffect(exoPlayer, playbackSpeed) {
        exoPlayer?.playbackParameters = PlaybackParameters(playbackSpeed)
    }

    LaunchedEffect(exoPlayer, isLooping) {
        exoPlayer?.repeatMode = if (isLooping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    LaunchedEffect(exoPlayer, isPlaying, isLifecycleResumed, isScrubbing, isLooping, project.id) {
        val player = exoPlayer ?: return@LaunchedEffect
        if (!isPlaying || !isLifecycleResumed || isScrubbing) return@LaunchedEffect
        while (isActive) {
            val pos = player.currentPosition.coerceAtLeast(0L)
            currentPosMs = pos
            if (isDraftTrimmed && project.trimEndMs > project.trimStartMs && pos >= project.trimEndMs) {
                if (isLooping) {
                    player.seekTo(project.trimStartMs)
                    currentPosMs = project.trimStartMs
                } else {
                    player.playWhenReady = false
                    isPlaying = false
                    hasCompleted = true
                    controlsVisible = true
                    break
                }
            }
            delay(50L)
        }
    }

    // Image loading state
    val imageBitmap by produceState<Bitmap?>(initialValue = null, project.id, resolvedUri, project.mediaType) {
        value = if (project.mediaType == CropMediaType.IMAGE && resolvedUri != null) {
            loadViewerBitmap(context, resolvedUri)
        } else {
            null
        }
    }

    // Two-finger pinch-to-zoom & pan state (ONLY for the media inside the viewport)
    var zoom by remember(project.id, resolvedUri) { mutableFloatStateOf(1f) }
    var panOffset by remember(project.id, resolvedUri) { mutableStateOf(Offset.Zero) }

    // Horizontal left/right project swipe offset
    var rawSwipeOffsetX by remember(project.id) { mutableFloatStateOf(0f) }
    var isSwipingProject by remember(project.id) { mutableStateOf(false) }
    val animatedSwipeX by animateFloatAsState(
        targetValue = rawSwipeOffsetX,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "viewer_project_swipe_x"
    )
    val effectiveSwipeX = if (isSwipingProject) rawSwipeOffsetX else animatedSwipeX

    // Tap / Double-tap state (Files by Google behavior: single tap ALWAYS toggles show/hide controls!)
    var lastTapUpTimeMs by remember { mutableLongStateOf(0L) }
    var lastTapPos by remember { mutableStateOf(Offset.Zero) }
    var hasPendingSingleTap by remember { mutableStateOf(false) }
    var pendingSingleTapNonce by remember { mutableIntStateOf(0) }

    val latestControlsVisible by rememberUpdatedState(controlsVisible)
    LaunchedEffect(pendingSingleTapNonce) {
        if (hasPendingSingleTap) {
            delay(230L)
            if (hasPendingSingleTap) {
                hasPendingSingleTap = false
                controlsVisible = !latestControlsVisible
                if (controlsVisible) {
                    controlsInteractionNonce++
                }
            }
        }
    }

    val latestZoom by rememberUpdatedState(zoom)
    val latestPan by rememberUpdatedState(panOffset)
    val latestBrightness by rememberUpdatedState(brightnessLevel)
    val latestVolume by rememberUpdatedState(volumeLevel)
    val latestSkipSeconds by rememberUpdatedState(skipSeconds)
    val latestMediaType by rememberUpdatedState(project.mediaType)
    val latestDurationMs by rememberUpdatedState(videoDurationMs)
    val latestExoPlayer by rememberUpdatedState(exoPlayer)
    val latestIsPrepared by rememberUpdatedState(isPlayerPrepared)
    val latestPrevProject by rememberUpdatedState(prevProject)
    val latestNextProject by rememberUpdatedState(nextProject)
    val latestOnSelectProject by rememberUpdatedState(onSelectProject)

    val density = LocalDensity.current
    val tapSlopPx = with(density) { 10.dp.toPx() }
    val doubleTapDistPx = with(density) { 64.dp.toPx() }
    val horizontalSwipeThresholdMinPx = with(density) { 72.dp.toPx() }

    // Draft crop rect (applied non-destructively when viewing unexported drafts)
    val draftCrop = remember(project) {
        val l = project.cropLeft.coerceIn(0f, 0.9999f)
        val t = project.cropTop.coerceIn(0f, 0.9999f)
        NormalizedCropRect(
            left = l,
            top = t,
            right = project.cropRight.coerceIn(l + 0.0001f, 1f),
            bottom = project.cropBottom.coerceIn(t + 0.0001f, 1f)
        )
    }
    val applyDraftCrop = !playbackSource.isExportedFile && (
        draftCrop.left > 0.002f ||
            draftCrop.top > 0.002f ||
            draftCrop.right < 0.998f ||
            draftCrop.bottom < 0.998f
        )

    // Determine source dimensions and aspect ratio
    val activeSourceDims = remember(project, playbackSource.isExportedFile, sourceVideoWidth, sourceVideoHeight, imageBitmap) {
        when (project.mediaType) {
            CropMediaType.VIDEO -> {
                val w = when {
                    sourceVideoWidth > 0 -> sourceVideoWidth
                    playbackSource.isExportedFile && project.exportedWidth > 0 -> project.exportedWidth
                    project.outputWidth > 0 -> project.outputWidth
                    else -> 16
                }
                val h = when {
                    sourceVideoHeight > 0 -> sourceVideoHeight
                    playbackSource.isExportedFile && project.exportedHeight > 0 -> project.exportedHeight
                    project.outputHeight > 0 -> project.outputHeight
                    else -> 9
                }
                w to h
            }
            CropMediaType.IMAGE -> {
                val bmp = imageBitmap
                val w = when {
                    bmp != null && bmp.width > 0 -> bmp.width
                    playbackSource.isExportedFile && project.exportedWidth > 0 -> project.exportedWidth
                    project.outputWidth > 0 -> project.outputWidth
                    else -> 1
                }
                val h = when {
                    bmp != null && bmp.height > 0 -> bmp.height
                    playbackSource.isExportedFile && project.exportedHeight > 0 -> project.exportedHeight
                    project.outputHeight > 0 -> project.outputHeight
                    else -> 1
                }
                w to h
            }
        }
    }

    val displayDims = remember(project, playbackSource.isExportedFile, applyDraftCrop, draftCrop, activeSourceDims) {
        val (srcW, srcH) = activeSourceDims
        if (playbackSource.isExportedFile) {
            val w = if (project.exportedWidth > 0) project.exportedWidth else srcW
            val h = if (project.exportedHeight > 0) project.exportedHeight else srcH
            w to h
        } else if (applyDraftCrop) {
            val w = (draftCrop.widthFraction * srcW).roundToInt().coerceAtLeast(1)
            val h = (draftCrop.heightFraction * srcH).roundToInt().coerceAtLeast(1)
            w to h
        } else {
            srcW to srcH
        }
    }

    val mediaRatio = remember(displayDims) {
        val (dw, dh) = displayDims
        (dw.toFloat() / dh.toFloat().coerceAtLeast(1f)).coerceIn(0.2f, 5f)
    }

    val aspectSummaryText = remember(project, playbackSource.isExportedFile, displayDims) {
        buildViewerAspectSummary(
            width = displayDims.first,
            height = displayDims.second,
            project = project,
            isExportedFile = playbackSource.isExportedFile
        )
    }

    // Pure Black Files by Google Viewer Surface
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("project_media_viewer_screen"),
        color = Color.Black
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Media Viewport (full-screen black stage; pinch-to-zoom & pan ONLY affect the media!)
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .testTag("viewer_media_stage"),
                contentAlignment = Alignment.Center
            ) {
                val boxW = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
                val boxH = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
                val boxRatio = boxW / boxH
                val (fittedW, fittedH) = if (mediaRatio >= boxRatio) {
                    boxW to (boxW / mediaRatio)
                } else {
                    (boxH * mediaRatio) to boxH
                }
                val frameBottomPx = ((boxH + fittedH) * 0.5f).coerceIn(0f, boxH)
                val topSafePx = WindowInsets.statusBars.getTop(density).toFloat()
                val bottomSafePx = WindowInsets.navigationBars.getBottom(density).toFloat()
                val barGapPx = with(density) { 8.dp.toPx() }
                val barBottomMarginPx = bottomSafePx + with(density) { 8.dp.toPx() }
                val fallbackBarHeightPx = with(density) {
                    if (project.mediaType == CropMediaType.VIDEO) 98.dp.toPx() else 56.dp.toPx()
                }
                var measuredBarHeightPx by remember { mutableFloatStateOf(0f) }
                val effectiveBarHeightPx = if (measuredBarHeightPx > 0f) measuredBarHeightPx else fallbackBarHeightPx
                val maxBarTopPx = (boxH - effectiveBarHeightPx - barBottomMarginPx).coerceAtLeast(topSafePx)
                val controlBarTopPx = (frameBottomPx + barGapPx).coerceIn(topSafePx, maxBarTopPx)

                val latestBoxW by rememberUpdatedState(boxW)
                val latestBoxH by rememberUpdatedState(boxH)
                val latestFittedW by rememberUpdatedState(fittedW)
                val latestFittedH by rememberUpdatedState(fittedH)

                fun clampPanForZoom(rawPan: Offset, curZoom: Float): Offset {
                    if (curZoom <= 1.02f) return Offset.Zero
                    val maxX = (((latestFittedW * curZoom) - latestBoxW) * 0.5f)
                        .coerceAtLeast(latestBoxW * (curZoom - 1f) * 0.28f)
                    val maxY = (((latestFittedH * curZoom) - latestBoxH) * 0.5f)
                        .coerceAtLeast(latestBoxH * (curZoom - 1f) * 0.28f)
                    return Offset(
                        x = rawPan.x.coerceIn(-maxX, maxX),
                        y = rawPan.y.coerceIn(-maxY, maxY)
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(project.id, resolvedUri, project.mediaType) {
                            awaitEachGesture {
                                val firstDown = awaitFirstDown(requireUnconsumed = false)
                                firstDown.consume()

                                val startX = firstDown.position.x
                                var lastPos = firstDown.position
                                var totalMovement = 0f
                                var totalDx = 0f
                                var totalDy = 0f
                                var isMultiTouch = false
                                var dragAxis = if (latestZoom > 1.02f) {
                                    ViewerDragAxis.PAN_ZOOMED
                                } else {
                                    ViewerDragAxis.UNDECIDED
                                }

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.isEmpty()) {
                                        if (dragAxis == ViewerDragAxis.SWIPE_PROJECT) {
                                            val swipeThreshold = minOf(
                                                latestBoxW * 0.18f,
                                                horizontalSwipeThresholdMinPx
                                            )
                                            when {
                                                rawSwipeOffsetX <= -swipeThreshold && latestNextProject != null -> {
                                                    rawSwipeOffsetX = 0f
                                                    isSwipingProject = false
                                                    latestOnSelectProject(latestNextProject!!)
                                                }
                                                rawSwipeOffsetX >= swipeThreshold && latestPrevProject != null -> {
                                                    rawSwipeOffsetX = 0f
                                                    isSwipingProject = false
                                                    latestOnSelectProject(latestPrevProject!!)
                                                }
                                                else -> {
                                                    rawSwipeOffsetX = 0f
                                                    isSwipingProject = false
                                                }
                                            }
                                        } else if (!isMultiTouch && totalMovement <= tapSlopPx) {
                                            // Files by Google behavior:
                                            // Single tap anywhere toggles UI visibility (NEVER triggers play/pause!).
                                            // Double tap left/right seeks by custom skipSeconds (or toggles zoom on photo).
                                            val now = SystemClock.uptimeMillis()
                                            val dist = (lastPos - lastTapPos).getDistance()
                                            if (hasPendingSingleTap && (now - lastTapUpTimeMs) <= 250L && dist <= doubleTapDistPx) {
                                                hasPendingSingleTap = false
                                                lastTapUpTimeMs = 0L
                                                if (latestMediaType == CropMediaType.VIDEO) {
                                                    val player = latestExoPlayer
                                                    val skipMs = (latestSkipSeconds * 1000f).roundToLong().coerceAtLeast(100L)
                                                    val minStart = if (isDraftTrimmed) project.trimStartMs.coerceAtLeast(0L) else 0L
                                                    val maxEnd = if (isDraftTrimmed && project.trimEndMs > minStart) {
                                                        project.trimEndMs
                                                    } else {
                                                        latestDurationMs.coerceAtLeast(1L)
                                                    }
                                                    if (lastPos.x < latestBoxW * 0.5f) {
                                                        if (player != null && latestIsPrepared) {
                                                            val cur = player.currentPosition.coerceAtLeast(0L)
                                                            val target = (cur - skipMs).coerceIn(minStart, maxEnd)
                                                            player.seekTo(target)
                                                            currentPosMs = target
                                                            hasCompleted = false
                                                        }
                                                        doubleTapSeekDisplaySeconds = -latestSkipSeconds
                                                        doubleTapSeekNonce++
                                                    } else {
                                                        if (player != null && latestIsPrepared) {
                                                            val cur = player.currentPosition.coerceAtLeast(0L)
                                                            val target = (cur + skipMs).coerceIn(minStart, maxEnd)
                                                            player.seekTo(target)
                                                            currentPosMs = target
                                                        }
                                                        doubleTapSeekDisplaySeconds = latestSkipSeconds
                                                        doubleTapSeekNonce++
                                                    }
                                                } else {
                                                    if (latestZoom > 1.05f) {
                                                        zoom = 1f
                                                        panOffset = Offset.Zero
                                                    } else {
                                                        zoom = 2.5f
                                                    }
                                                }
                                            } else {
                                                lastTapUpTimeMs = now
                                                lastTapPos = lastPos
                                                hasPendingSingleTap = true
                                                pendingSingleTapNonce++
                                            }
                                        }
                                        isVerticalHudDragging = false
                                        isSwipingProject = false
                                        break
                                    }

                                    if (pressed.size >= 2) {
                                        isMultiTouch = true
                                        isVerticalHudDragging = false
                                        isSwipingProject = false
                                        rawSwipeOffsetX = 0f
                                        hasPendingSingleTap = false
                                        dragAxis = ViewerDragAxis.PAN_ZOOMED
                                        val zoomDelta = event.calculateZoom()
                                        val panDelta = event.calculatePan()
                                        val safeDelta = if (zoomDelta.isFinite() && zoomDelta > 0.01f) zoomDelta else 1f
                                        val nextZoom = (latestZoom * safeDelta).coerceIn(1f, 12f)
                                        val nextPan = if (nextZoom <= 1.02f) {
                                            Offset.Zero
                                        } else {
                                            clampPanForZoom(latestPan + panDelta, nextZoom)
                                        }
                                        zoom = if (nextZoom <= 1.02f) 1f else nextZoom
                                        panOffset = nextPan
                                        pressed.forEach { it.consume() }
                                    } else {
                                        val primary = pressed.first()
                                        lastPos = primary.position
                                        val delta = primary.positionChange()
                                        if (delta != Offset.Zero) {
                                            totalMovement += delta.getDistance()
                                            totalDx += delta.x
                                            totalDy += delta.y
                                            primary.consume()

                                            if (latestZoom > 1.02f) {
                                                panOffset = clampPanForZoom(latestPan + delta, latestZoom)
                                            } else {
                                                if (dragAxis == ViewerDragAxis.UNDECIDED && totalMovement > tapSlopPx) {
                                                    dragAxis = if (abs(totalDx) >= abs(totalDy)) {
                                                        ViewerDragAxis.SWIPE_PROJECT
                                                    } else if (startX < latestBoxW * 0.5f) {
                                                        ViewerDragAxis.BRIGHTNESS
                                                    } else {
                                                        ViewerDragAxis.VOLUME
                                                    }
                                                }

                                                when (dragAxis) {
                                                    ViewerDragAxis.SWIPE_PROJECT -> {
                                                        isSwipingProject = true
                                                        val movingToNext = rawSwipeOffsetX + delta.x < 0f
                                                        val hasTarget = if (movingToNext) {
                                                            latestNextProject != null
                                                        } else {
                                                            latestPrevProject != null
                                                        }
                                                        val resistance = if (hasTarget) 1f else 0.25f
                                                        rawSwipeOffsetX = (rawSwipeOffsetX + delta.x * resistance)
                                                            .coerceIn(-latestBoxW * 0.65f, latestBoxW * 0.65f)
                                                    }

                                                    ViewerDragAxis.BRIGHTNESS -> {
                                                        isVerticalHudDragging = true
                                                        val sens = 1f / (latestBoxH * 0.55f).coerceAtLeast(180f)
                                                        val nextBright = (latestBrightness - delta.y * sens).coerceIn(0.02f, 1f)
                                                        brightnessLevel = nextBright
                                                        if (activity != null) {
                                                            val attrs = activity.window.attributes
                                                            attrs.screenBrightness = nextBright
                                                            activity.window.attributes = attrs
                                                        }
                                                        activeHudMode = ViewerHudMode.BRIGHTNESS
                                                        hudTriggerNonce++
                                                    }

                                                    ViewerDragAxis.VOLUME -> {
                                                        isVerticalHudDragging = true
                                                        val sens = 1f / (latestBoxH * 0.55f).coerceAtLeast(180f)
                                                        val nextVol = (latestVolume - delta.y * sens).coerceIn(0f, 1f)
                                                        volumeLevel = nextVol
                                                        val targetStep = (nextVol * maxStreamVol).roundToInt().coerceIn(0, maxStreamVol)
                                                        audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, targetStep, 0)
                                                        activeHudMode = ViewerHudMode.VOLUME
                                                        hudTriggerNonce++
                                                    }

                                                    else -> Unit
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val activePan = if (zoom <= 1.02f) Offset.Zero else panOffset

                    // Zoomable, Pannable & Horizontally Swipeable Media Canvas
                    Box(
                        modifier = Modifier
                            .graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                translationX = activePan.x + effectiveSwipeX
                                translationY = activePan.y
                            }
                            .aspectRatio(mediaRatio, matchHeightConstraintsFirst = mediaRatio < boxRatio)
                            .clipToBounds()
                            .testTag("viewer_media_frame"),
                        contentAlignment = Alignment.Center
                    ) {
                        if (resolvedUri == null || playbackError != null) {
                            Text(
                                text = playbackError ?: "Unable to load media.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.padding(24.dp)
                            )
                        } else {
                            val (srcW, srcH) = activeSourceDims
                            val srcRatio = (srcW.toFloat() / srcH.toFloat().coerceAtLeast(1f)).coerceIn(0.2f, 5f)
                            val cropCenterX = (draftCrop.left + draftCrop.right) * 0.5f
                            val cropCenterY = (draftCrop.top + draftCrop.bottom) * 0.5f

                            BoxWithConstraints(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clipToBounds(),
                                contentAlignment = Alignment.Center
                            ) {
                                val frameW = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
                                val frameH = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
                                val baseScale = minOf(frameW / srcW.toFloat(), frameH / srcH.toFloat())
                                val baseW = (srcW * baseScale).coerceAtLeast(1f)
                                val baseH = (srcH * baseScale).coerceAtLeast(1f)
                                val innerCropScale = if (applyDraftCrop) {
                                    minOf(
                                        frameW / (draftCrop.widthFraction * baseW).coerceAtLeast(1f),
                                        frameH / (draftCrop.heightFraction * baseH).coerceAtLeast(1f)
                                    ).coerceAtLeast(1f)
                                } else {
                                    1f
                                }
                                val cropTx = if (applyDraftCrop) {
                                    -((cropCenterX - 0.5f) * baseW) * innerCropScale
                                } else {
                                    0f
                                }
                                val cropTy = if (applyDraftCrop) {
                                    -((cropCenterY - 0.5f) * baseH) * innerCropScale
                                } else {
                                    0f
                                }

                                Box(
                                    modifier = Modifier
                                        .size(
                                            with(density) { baseW.toDp() },
                                            with(density) { baseH.toDp() }
                                        )
                                        .graphicsLayer {
                                            scaleX = innerCropScale
                                            scaleY = innerCropScale
                                            translationX = cropTx
                                            translationY = cropTy
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (project.mediaType == CropMediaType.VIDEO) {
                                        if (exoPlayer != null) {
                                            key(project.id, resolvedUri) {
                                                PlayerSurface(
                                                    player = exoPlayer,
                                                    surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .aspectRatio(srcRatio)
                                                        .testTag("viewer_video_surface")
                                                )
                                            }
                                            if (!isPlayerPrepared) {
                                                CircularProgressIndicator(color = GoogleFilesAccentBlue)
                                            }
                                        }
                                    } else {
                                        val bmp = imageBitmap
                                        if (bmp != null) {
                                            val imgBmp = remember(bmp) { bmp.asImageBitmap() }
                                            Image(
                                                bitmap = imgBmp,
                                                contentDescription = project.title,
                                                contentScale = ContentScale.FillBounds,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .testTag("viewer_image_surface")
                                            )
                                        } else {
                                            CircularProgressIndicator(color = GoogleFilesAccentBlue)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Double-tap Seek Ripple Indicator (Left / Right) showing chosen skip seconds
                AnimatedVisibility(
                    visible = doubleTapSeekDisplaySeconds != 0f,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(
                            if (doubleTapSeekDisplaySeconds < 0f) Alignment.CenterStart else Alignment.CenterEnd
                        )
                        .padding(horizontal = 36.dp)
                ) {
                    val absSec = abs(doubleTapSeekDisplaySeconds)
                    val secText = formatSkipSecondsLabel(absSec)
                    Surface(
                        shape = CircleShape,
                        color = Color(0xCC1F2024),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f))
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Icon(
                                imageVector = if (doubleTapSeekDisplaySeconds < 0f) {
                                    Icons.Rounded.FastRewind
                                } else {
                                    Icons.Rounded.FastForward
                                },
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                            Text(
                                text = if (doubleTapSeekDisplaySeconds < 0f) "-$secText" else "+$secText",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                        }
                    }
                }

                // Vertical Brightness / Volume HUD indicator
                AnimatedVisibility(
                    visible = activeHudMode != ViewerHudMode.NONE,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    val isBrightness = activeHudMode == ViewerHudMode.BRIGHTNESS
                    val valueFrac = if (isBrightness) brightnessLevel else volumeLevel
                    val pct = (valueFrac * 100f).roundToInt().coerceIn(0, 100)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xCC1F2024),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                        modifier = Modifier.testTag("viewer_vertical_hud")
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (isBrightness) {
                                    Icons.Rounded.BrightnessHigh
                                } else if (pct == 0) {
                                    Icons.AutoMirrored.Rounded.VolumeOff
                                } else {
                                    Icons.AutoMirrored.Rounded.VolumeUp
                                },
                                contentDescription = null,
                                tint = GoogleFilesAccentBlue,
                                modifier = Modifier.size(22.dp)
                            )
                            Box(
                                modifier = Modifier
                                    .width(8.dp)
                                    .height(92.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.White.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .fillMaxHeight(valueFrac.coerceIn(0.02f, 1f))
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(GoogleFilesAccentBlue)
                                )
                            }
                            Text(
                                text = "$pct%",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = FontFamily.Default,
                                    fontFeatureSettings = "tnum",
                                    fontWeight = FontWeight.Bold
                                ),
                                color = Color.White
                            )
                        }
                    }
                }

                // Files by Google Bottom Controls Overlay: anchored directly beneath the video/photo canvas bottom edge
                AnimatedVisibility(
                    visible = controlsVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .offset { IntOffset(0, controlBarTopPx.roundToInt()) }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { size ->
                                if (size.height > 0) {
                                    measuredBarHeightPx = size.height.toFloat()
                                }
                            }
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color(0x88000000), Color(0xCC000000))
                                )
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                controlsInteractionNonce++
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .testTag("viewer_control_bar"),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (project.mediaType == CropMediaType.VIDEO) {
                            val safeDuration = videoDurationMs.coerceAtLeast(1L)
                            val displayPos = if (isScrubbing) {
                                scrubPreviewMs
                            } else {
                                currentPosMs.coerceIn(0L, safeDuration)
                            }

                            // Slim Google Files Native Seekbar with Elapsed & Total Duration
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = formatTimelineMs(displayPos),
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontFamily = FontFamily.Default,
                                        fontFeatureSettings = "tnum",
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 12.sp
                                    ),
                                    color = Color.White
                                )

                                SlimGoogleFilesSeekBar(
                                    positionMs = displayPos,
                                    durationMs = safeDuration,
                                    onSeek = { targetMs ->
                                        isScrubbing = true
                                        hasCompleted = false
                                        scrubPreviewMs = targetMs
                                        currentPosMs = targetMs
                                        exoPlayer?.seekTo(targetMs)
                                        controlsInteractionNonce++
                                    },
                                    onSeekFinished = {
                                        isScrubbing = false
                                        controlsInteractionNonce++
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("viewer_timeline_slider")
                                )

                                Text(
                                    text = formatTimelineMs(safeDuration),
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontFamily = FontFamily.Default,
                                        fontFeatureSettings = "tnum",
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 12.sp
                                    ),
                                    color = Color.White.copy(alpha = 0.85f)
                                )
                            }

                            // Bottom Quick Chips Row: Skip Seconds picker chip on left + Tap-only Rotate button on right
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = GoogleFilesSurfaceAlt.copy(alpha = 0.9f),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .clip(RoundedCornerShape(50))
                                        .clickable {
                                            showSkipDialog = true
                                            controlsInteractionNonce++
                                        }
                                        .testTag("viewer_skip_config_button")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Timer,
                                            contentDescription = "Configure skip seconds",
                                            tint = GoogleFilesAccentBlue,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = "Skip ${formatSkipSecondsLabel(skipSeconds)}",
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 12.sp
                                            ),
                                            color = Color.White
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = {
                                        isLandscape = !isLandscape
                                        controlsInteractionNonce++
                                    },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("viewer_rotate_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ScreenRotation,
                                        contentDescription = if (isLandscape) {
                                            "Rotate to portrait"
                                        } else {
                                            "Rotate to landscape"
                                        },
                                        tint = if (isLandscape) GoogleFilesAccentBlue else Color.White
                                    )
                                }
                            }
                        } else {
                            // Photo Mode Bottom Bar: Single Tap-Only Rotate button
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.End
                            ) {
                                IconButton(
                                    onClick = { isLandscape = !isLandscape },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("viewer_rotate_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ScreenRotation,
                                        contentDescription = if (isLandscape) {
                                            "Rotate to portrait"
                                        } else {
                                            "Rotate to landscape"
                                        },
                                        tint = if (isLandscape) GoogleFilesAccentBlue else Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Files by Google Center Play / Pause Button (ONLY visible when controlsVisible == true!)
            // Tapping the screen hides/shows controls; tapping THIS center button toggles Play/Pause!
            AnimatedVisibility(
                visible = controlsVisible && project.mediaType == CropMediaType.VIDEO && !isExportingForShare,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color(0x99000000),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .clickable {
                            isPlaying = !isPlaying
                            controlsInteractionNonce++
                        }
                        .testTag("viewer_play_pause_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = when {
                                hasCompleted && !isPlaying -> Icons.Rounded.Replay
                                isPlaying -> Icons.Rounded.Pause
                                else -> Icons.Rounded.PlayArrow
                            },
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }

            // Files by Google Top Bar: Back + File Title + Share + 3-Dot Overflow Menu
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xCC000000), Color(0x66000000), Color.Transparent)
                            )
                        )
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                if (!isExportingForShare) onBack()
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("viewer_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }

                        Text(
                            text = project.title,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Medium,
                                fontSize = 16.sp
                            ),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp)
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                if (!isExportingForShare) {
                                    onShareProject(project)
                                }
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("viewer_share_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Share,
                                contentDescription = "Share",
                                tint = Color.White
                            )
                        }

                        Box {
                            IconButton(
                                onClick = {
                                    showMoreMenu = true
                                    controlsInteractionNonce++
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("viewer_more_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MoreVert,
                                    contentDescription = "More options",
                                    tint = Color.White
                                )
                            }

                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false },
                                containerColor = GoogleFilesSurface
                            ) {
                                if (project.mediaType == CropMediaType.VIDEO) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "Skip seconds (${formatSkipSecondsLabel(skipSeconds)})",
                                                color = Color.White
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Rounded.Timer,
                                                contentDescription = null,
                                                tint = GoogleFilesAccentBlue
                                            )
                                        },
                                        onClick = {
                                            showMoreMenu = false
                                            showSkipDialog = true
                                        },
                                        modifier = Modifier.testTag("viewer_menu_skip_seconds")
                                    )

                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "Playback speed (${formatSpeedLabel(playbackSpeed)})",
                                                color = Color.White
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Rounded.Speed,
                                                contentDescription = null,
                                                tint = GoogleFilesAccentBlue
                                            )
                                        },
                                        onClick = {
                                            showMoreMenu = false
                                            showSpeedDialog = true
                                        },
                                        modifier = Modifier.testTag("viewer_menu_speed")
                                    )

                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (isLooping) "Loop: On" else "Loop: Off",
                                                color = Color.White
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = if (isLooping) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                                                contentDescription = null,
                                                tint = if (isLooping) GoogleFilesAccentBlue else Color.White.copy(alpha = 0.8f)
                                            )
                                        },
                                        onClick = {
                                            isLooping = !isLooping
                                            showMoreMenu = false
                                            controlsInteractionNonce++
                                        },
                                        modifier = Modifier.testTag("viewer_menu_loop")
                                    )
                                }

                                DropdownMenuItem(
                                    text = { Text("File info", color = Color.White) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.Info,
                                            contentDescription = null,
                                            tint = Color.White.copy(alpha = 0.85f)
                                        )
                                    },
                                    onClick = {
                                        showMoreMenu = false
                                        showInfoDialog = true
                                    },
                                    modifier = Modifier.testTag("viewer_menu_info")
                                )
                            }
                        }
                    }
                }
            }


            // Double-Tap Skip Seconds Preset & Custom Dialog (2s, 5s, 10s, 20s, Custom)
            if (showSkipDialog) {
                val presets = listOf(2f, 5f, 10f, 20f)
                val isPreset = presets.any { abs(it - skipSeconds) < 0.01f }
                var useCustomInput by remember { mutableStateOf(!isPreset) }
                var customInputText by remember {
                    mutableStateOf(
                        if (skipSeconds % 1f == 0f) {
                            skipSeconds.toInt().toString()
                        } else {
                            String.format(Locale.US, "%.1f", skipSeconds)
                        }
                    )
                }
                var customErrorText by remember { mutableStateOf<String?>(null) }

                AlertDialog(
                    onDismissRequest = { showSkipDialog = false },
                    containerColor = GoogleFilesSurface,
                    title = {
                        Text(
                            text = "Double-tap skip interval",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = "Choose how many seconds to jump when double-tapping left or right.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.74f)
                            )

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                presets.forEach { presetSec ->
                                    val selected = !useCustomInput && abs(presetSec - skipSeconds) < 0.01f
                                    FilterChip(
                                        selected = selected,
                                        onClick = {
                                            useCustomInput = false
                                            customErrorText = null
                                            skipSeconds = presetSec
                                            prefs.edit().putFloat(KEY_SKIP_SECONDS, presetSec).apply()
                                            showSkipDialog = false
                                            controlsInteractionNonce++
                                        },
                                        label = {
                                            Text(formatSkipSecondsLabel(presetSec))
                                        },
                                        leadingIcon = if (selected) {
                                            {
                                                Icon(
                                                    imageVector = Icons.Rounded.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        } else null,
                                        colors = FilterChipDefaults.filterChipColors(
                                            containerColor = GoogleFilesSurfaceAlt,
                                            labelColor = Color.White,
                                            selectedContainerColor = GoogleFilesAccentBlue.copy(alpha = 0.24f),
                                            selectedLabelColor = GoogleFilesAccentBlue,
                                            selectedLeadingIconColor = GoogleFilesAccentBlue
                                        ),
                                        modifier = Modifier.testTag("viewer_skip_preset_${presetSec.toInt()}")
                                    )
                                }

                                FilterChip(
                                    selected = useCustomInput,
                                    onClick = {
                                        useCustomInput = true
                                        customErrorText = null
                                    },
                                    label = { Text("Custom") },
                                    leadingIcon = if (useCustomInput) {
                                        {
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    } else null,
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = GoogleFilesSurfaceAlt,
                                        labelColor = Color.White,
                                        selectedContainerColor = GoogleFilesAccentBlue.copy(alpha = 0.24f),
                                        selectedLabelColor = GoogleFilesAccentBlue,
                                        selectedLeadingIconColor = GoogleFilesAccentBlue
                                    ),
                                    modifier = Modifier.testTag("viewer_skip_preset_custom")
                                )
                            }

                            if (useCustomInput) {
                                OutlinedTextField(
                                    value = customInputText,
                                    onValueChange = { raw ->
                                        customInputText = raw.filter { it.isDigit() || it == '.' }
                                        customErrorText = null
                                    },
                                    label = { Text("Seconds (0.5 - 300)") },
                                    suffix = { Text("s", color = Color.White.copy(alpha = 0.7f)) },
                                    singleLine = true,
                                    isError = customErrorText != null,
                                    supportingText = customErrorText?.let { err ->
                                        { Text(err, color = FrameScopeColors.SpecRedText) }
                                    },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedBorderColor = GoogleFilesAccentBlue,
                                        unfocusedBorderColor = Color.White.copy(alpha = 0.35f),
                                        focusedLabelColor = GoogleFilesAccentBlue,
                                        unfocusedLabelColor = Color.White.copy(alpha = 0.7f)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("viewer_skip_custom_input")
                                )
                            }
                        }
                    },
                    confirmButton = {
                        if (useCustomInput) {
                            TextButton(
                                onClick = {
                                    val parsed = customInputText.toFloatOrNull()
                                    if (parsed == null || parsed < 0.5f || parsed > 300f) {
                                        customErrorText = "Enter a value between 0.5 and 300 seconds."
                                    } else {
                                        skipSeconds = parsed
                                        prefs.edit().putFloat(KEY_SKIP_SECONDS, parsed).apply()
                                        showSkipDialog = false
                                        controlsInteractionNonce++
                                    }
                                },
                                modifier = Modifier.testTag("viewer_skip_custom_apply")
                            ) {
                                Text("Apply", color = GoogleFilesAccentBlue)
                            }
                        } else {
                            TextButton(onClick = { showSkipDialog = false }) {
                                Text("Close", color = GoogleFilesAccentBlue)
                            }
                        }
                    },
                    dismissButton = {
                        if (useCustomInput) {
                            TextButton(onClick = { showSkipDialog = false }) {
                                Text("Cancel", color = Color.White.copy(alpha = 0.78f))
                            }
                        }
                    }
                )
            }

            // Files by Google Playback Speed Picker Dialog
            if (showSpeedDialog) {
                val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                AlertDialog(
                    onDismissRequest = { showSpeedDialog = false },
                    containerColor = GoogleFilesSurface,
                    title = {
                        Text(
                            text = "Playback speed",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White
                        )
                    },
                    text = {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            speeds.forEach { spd ->
                                val selected = abs(spd - playbackSpeed) < 0.01f
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        playbackSpeed = spd
                                        showSpeedDialog = false
                                        controlsInteractionNonce++
                                    },
                                    label = { Text(formatSpeedLabel(spd)) },
                                    leadingIcon = if (selected) {
                                        {
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    } else null,
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = GoogleFilesSurfaceAlt,
                                        labelColor = Color.White,
                                        selectedContainerColor = GoogleFilesAccentBlue.copy(alpha = 0.24f),
                                        selectedLabelColor = GoogleFilesAccentBlue,
                                        selectedLeadingIconColor = GoogleFilesAccentBlue
                                    )
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showSpeedDialog = false }) {
                            Text("Close", color = GoogleFilesAccentBlue)
                        }
                    }
                )
            }

            // Files by Google Media File Info Dialog
            if (showInfoDialog) {
                AlertDialog(
                    onDismissRequest = { showInfoDialog = false },
                    containerColor = GoogleFilesSurface,
                    title = {
                        Text(
                            text = "File info",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Name: ${project.title}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                            Text(
                                text = "Details: $aspectSummaryText",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                            if (project.mediaType == CropMediaType.VIDEO && videoDurationMs > 0L) {
                                Text(
                                    text = "Duration: ${formatTimelineMs(videoDurationMs)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.8f)
                                )
                            }
                            if (projects.size > 1 && currentIndex >= 0) {
                                Text(
                                    text = "Item ${currentIndex + 1} of ${projects.size} (Swipe left/right to switch)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.7f)
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showInfoDialog = false }) {
                            Text("OK", color = GoogleFilesAccentBlue)
                        }
                    }
                )
            }

            // Export Progress Overlay when sharing an unexported project draft
            if (isExportingForShare) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xB3000000))
                        .clickable(enabled = false) {},
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = GoogleFilesSurface,
                        border = BorderStroke(1.dp, GoogleFilesAccentBlue),
                        modifier = Modifier
                            .fillMaxWidth(0.84f)
                            .testTag("viewer_share_export_overlay")
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = exportStatusText.ifBlank { "Preparing to share…" },
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = Color.White
                                )
                                Text(
                                    text = "${(exportProgress * 100f).toInt()}%",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontFamily = FontFamily.Default,
                                        fontFeatureSettings = "tnum"
                                    ),
                                    color = GoogleFilesAccentBlue
                                )
                            }
                            LinearProgressIndicator(
                                progress = { exportProgress.coerceIn(0f, 1f) },
                                color = GoogleFilesAccentBlue,
                                trackColor = GoogleFilesSurfaceAlt,
                                modifier = Modifier.fillMaxWidth()
                            )
                            TextButton(
                                onClick = onCancelShareExport,
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("Cancel", color = FrameScopeColors.SpecRedText)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SlimGoogleFilesSeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    onSeekFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val safeDuration = durationMs.coerceAtLeast(1L)
    val fraction = (positionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    var isDragging by remember { mutableStateOf(false) }

    val latestDuration by rememberUpdatedState(safeDuration)
    val latestOnSeek by rememberUpdatedState(onSeek)
    val latestOnSeekFinished by rememberUpdatedState(onSeekFinished)

    val thumbRadiusDp by animateFloatAsState(
        targetValue = if (isDragging) 8.5f else 6.5f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "seekbar_thumb_radius"
    )

    Box(
        modifier = modifier
            .height(48.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                setProgress { targetFraction ->
                    val clamped = targetFraction.coerceIn(0f, 1f)
                    val targetMs = (clamped * latestDuration).roundToLong().coerceIn(0L, latestDuration)
                    latestOnSeek(targetMs)
                    latestOnSeekFinished()
                    true
                }
            }
            .pointerInput(safeDuration) {
                val maxThumbPadPx = 9.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    isDragging = true
                    val usableWidth = (size.width - maxThumbPadPx * 2f).coerceAtLeast(1f)
                    fun seekAt(x: Float) {
                        val frac = ((x - maxThumbPadPx) / usableWidth).coerceIn(0f, 1f)
                        val targetMs = (frac * latestDuration).roundToLong().coerceIn(0L, latestDuration)
                        latestOnSeek(targetMs)
                    }
                    seekAt(down.position.x)

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: event.changes.firstOrNull()
                        if (change == null || !change.pressed) {
                            break
                        }
                        change.consume()
                        seekAt(change.position.x)
                    }
                    isDragging = false
                    latestOnSeekFinished()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val padPx = 9.dp.toPx()
            val trackHeightPx = 3.5.dp.toPx()
            val trackLeft = padPx
            val trackRight = (size.width - padPx).coerceAtLeast(trackLeft + 1f)
            val trackWidth = trackRight - trackLeft
            val centerY = size.height * 0.5f
            val trackTop = centerY - trackHeightPx * 0.5f
            val corner = CornerRadius(trackHeightPx * 0.5f, trackHeightPx * 0.5f)
            val thumbCenterX = trackLeft + trackWidth * fraction

            drawRoundRect(
                color = Color.White.copy(alpha = 0.28f),
                topLeft = Offset(trackLeft, trackTop),
                size = Size(trackWidth, trackHeightPx),
                cornerRadius = corner
            )

            val activeWidth = (thumbCenterX - trackLeft).coerceAtLeast(0f)
            if (activeWidth > 0f) {
                drawRoundRect(
                    color = GoogleFilesAccentBlue,
                    topLeft = Offset(trackLeft, trackTop),
                    size = Size(activeWidth, trackHeightPx),
                    cornerRadius = corner
                )
            }

            if (isDragging) {
                drawCircle(
                    color = GoogleFilesAccentBlue.copy(alpha = 0.24f),
                    radius = 15.dp.toPx(),
                    center = Offset(thumbCenterX, centerY)
                )
            }

            drawCircle(
                color = GoogleFilesAccentBlue,
                radius = thumbRadiusDp.dp.toPx(),
                center = Offset(thumbCenterX, centerY)
            )
        }
    }
}

private fun formatSkipSecondsLabel(seconds: Float): String {
    return if (abs(seconds - seconds.roundToInt()) < 0.01f) {
        "${seconds.roundToInt()}s"
    } else {
        String.format(Locale.US, "%.1fs", seconds)
    }
}

private fun formatSpeedLabel(speed: Float): String {
    return if (abs(speed - speed.roundToInt()) < 0.01f) {
        "${speed.roundToInt()}x"
    } else {
        String.format(Locale.US, "%.2gx", speed)
    }
}

private fun buildViewerAspectSummary(
    width: Int,
    height: Int,
    project: CropProjectRecord,
    isExportedFile: Boolean
): String {
    val ratioLabel = describeAspectRatio(width, height)
    val orientationLabel = when {
        width > height -> "Landscape"
        height > width -> "Portrait"
        else -> "Square"
    }
    val canvasMode = runCatching {
        CanvasAspectMode.valueOf(project.canvasAspectModeName)
    }.getOrDefault(CanvasAspectMode.ORIGINAL)
    val canvasPart = if (canvasMode != CanvasAspectMode.ORIGINAL) {
        " • Canvas ${canvasMode.label}"
    } else {
        ""
    }
    val stagePart = if (isExportedFile) "Exported" else "Draft"
    return "$ratioLabel $orientationLabel • ${width}×${height}$canvasPart • $stagePart"
}

private fun describeAspectRatio(width: Int, height: Int): String {
    if (width <= 0 || height <= 0) return "Auto"
    val ratio = width.toFloat() / height.toFloat()
    val known = listOf(
        16f / 9f to "16:9",
        9f / 16f to "9:16",
        4f / 3f to "4:3",
        3f / 4f to "3:4",
        4f / 5f to "4:5",
        5f / 4f to "5:4",
        1f to "1:1",
        2f / 3f to "2:3",
        3f / 2f to "3:2",
        21f / 9f to "21:9",
        9f / 21f to "9:21",
        18f / 9f to "2:1",
        9f / 18f to "1:2",
        20f / 9f to "20:9",
        9f / 20f to "9:20"
    )
    for ((target, label) in known) {
        if (abs(ratio - target) / target <= 0.025f) {
            return label
        }
    }
    val g = gcd(width, height)
    val sw = width / g
    val sh = height / g
    return if (sw <= 32 && sh <= 32) {
        "$sw:$sh"
    } else if (width >= height) {
        String.format(Locale.US, "%.2f:1", ratio)
    } else {
        String.format(Locale.US, "1:%.2f", height.toFloat() / width.toFloat())
    }
}

private fun gcd(a: Int, b: Int): Int {
    var x = abs(a)
    var y = abs(b)
    while (y != 0) {
        val t = x % y
        x = y
        y = t
    }
    return x.coerceAtLeast(1)
}

private fun resolveProjectPlaybackSource(
    context: Context,
    project: CropProjectRecord
): ResolvedPlaybackSource {
    val expPath = project.exportedFilePath
    if (!expPath.isNullOrBlank()) {
        val file = File(expPath)
        if (file.exists() && file.length() > 0L) {
            return ResolvedPlaybackSource(Uri.fromFile(file), isExportedFile = true)
        }
    }

    val galleryStr = project.exportedGalleryUriString
    if (!galleryStr.isNullOrBlank()) {
        val uri = runCatching { Uri.parse(galleryStr) }.getOrNull()
        if (uri != null) {
            val readable = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
            }.getOrDefault(false)
            if (readable) {
                return ResolvedPlaybackSource(uri, isExportedFile = true)
            }
        }
    }

    val localPath = project.localCopyPath
    if (!localPath.isNullOrBlank()) {
        val file = File(localPath)
        if (file.exists() && file.length() > 0L) {
            return ResolvedPlaybackSource(Uri.fromFile(file), isExportedFile = false)
        }
    }

    return ResolvedPlaybackSource(
        uri = runCatching { Uri.parse(project.sourceUriString) }.getOrNull(),
        isExportedFile = false
    )
}

private suspend fun loadViewerBitmap(
    context: Context,
    uri: Uri,
    maxDim: Int = 2560
): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_DEFAULT
            val w = info.size.width
            val h = info.size.height
            val maxSide = maxOf(w, h)
            if (maxSide > maxDim && maxDim > 0) {
                val scale = maxDim.toFloat() / maxSide.toFloat()
                decoder.setTargetSize(
                    (w * scale).roundToInt().coerceAtLeast(1),
                    (h * scale).roundToInt().coerceAtLeast(1)
                )
            }
        }
    }.getOrElse {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            var sample = 1
            while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
                sample *= 2
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(
                    it,
                    null,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
            }
        }.getOrNull()
    }
}

private fun readInitialBrightness(context: Context, activity: Activity?): Float {
    val winBright = activity?.window?.attributes?.screenBrightness ?: -1f
    if (winBright in 0.01f..1f) return winBright
    val sysVal = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
    }.getOrDefault(128)
    return (sysVal / 255f).coerceIn(0.05f, 1f)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
