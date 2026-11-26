package com.example.framescope.ui.crop

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.provider.Settings
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.framescope.crop.CropMediaType
import com.example.framescope.crop.CropProjectRecord
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
    PAN_ZOOMED
}

@Composable
internal fun ProjectMediaViewer(
    project: CropProjectRecord,
    isExportingForShare: Boolean,
    exportProgress: Float,
    exportStatusText: String,
    onBack: () -> Unit,
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

    val resolvedUri = remember(
        project.id,
        project.exportedFilePath,
        project.exportedGalleryUriString,
        project.localCopyPath,
        project.sourceUriString
    ) {
        resolveProjectPlaybackUri(context, project)
    }

    var isLandscape by remember { mutableStateOf(false) }

    // Default orientation & window brightness restoration
    val initialOrientation = remember(activity) {
        activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    val initialWindowBrightness = remember(activity) {
        activity?.window?.attributes?.screenBrightness
            ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }

    DisposableEffect(activity) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
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
        activity?.requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    BackHandler {
        if (!isExportingForShare) {
            onBack()
        }
    }

    // Brightness state (0.02f..1.0f)
    var brightnessLevel by remember {
        mutableFloatStateOf(readInitialBrightness(context, activity))
    }

    // Volume state (0f..1f) + Mute state (default unmuted)
    val maxStreamVol = remember(audioManager) {
        (audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15).coerceAtLeast(1)
    }
    var volumeLevel by remember {
        mutableFloatStateOf(
            ((audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 8).toFloat() / maxStreamVol.toFloat())
                .coerceIn(0f, 1f)
        )
    }
    var isMuted by remember(project.id) { mutableStateOf(false) }

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

    // Video playback state (starts PAUSED; no auto-play)
    var mediaPlayerRef by remember(resolvedUri) { mutableStateOf<MediaPlayer?>(null) }
    var isPlayerPrepared by remember(resolvedUri) { mutableStateOf(false) }
    var isPlaying by remember(resolvedUri) { mutableStateOf(false) }
    var videoDurationMs by remember(resolvedUri) {
        val fallbackDur = if (project.exportedDurationMs > 0L) project.exportedDurationMs else project.durationMs
        mutableLongStateOf(fallbackDur.coerceAtLeast(0L))
    }
    var currentPosMs by remember(resolvedUri) { mutableLongStateOf(0L) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPreviewMs by remember { mutableLongStateOf(0L) }
    var videoWidth by remember(resolvedUri) {
        val w = if (project.exportedWidth > 0) project.exportedWidth else project.outputWidth
        mutableIntStateOf(w.coerceAtLeast(0))
    }
    var videoHeight by remember(resolvedUri) {
        val h = if (project.exportedHeight > 0) project.exportedHeight else project.outputHeight
        mutableIntStateOf(h.coerceAtLeast(0))
    }
    var playbackError by remember(resolvedUri) { mutableStateOf<String?>(null) }

    // Pause video if lifecycle goes to ON_PAUSE
    DisposableEffect(lifecycleOwner, mediaPlayerRef) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                isPlaying = false
                runCatching {
                    if (mediaPlayerRef?.isPlaying == true) {
                        mediaPlayerRef?.pause()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(isPlaying, isPlayerPrepared) {
        val mp = mediaPlayerRef ?: return@LaunchedEffect
        if (!isPlayerPrepared) return@LaunchedEffect
        runCatching {
            if (isPlaying && !mp.isPlaying) {
                if (videoDurationMs > 0L && currentPosMs >= videoDurationMs - 80L) {
                    mp.seekTo(0L, MediaPlayer.SEEK_CLOSEST)
                    currentPosMs = 0L
                }
                mp.start()
            } else if (!isPlaying && mp.isPlaying) {
                mp.pause()
            }
        }
    }

    LaunchedEffect(isMuted, isPlayerPrepared) {
        val mp = mediaPlayerRef ?: return@LaunchedEffect
        if (!isPlayerPrepared) return@LaunchedEffect
        val vol = if (isMuted) 0f else 1f
        runCatching { mp.setVolume(vol, vol) }
    }

    LaunchedEffect(isPlaying, isPlayerPrepared, isScrubbing) {
        val mp = mediaPlayerRef ?: return@LaunchedEffect
        if (!isPlayerPrepared || !isPlaying || isScrubbing) return@LaunchedEffect
        while (isActive) {
            val pos = runCatching { mp.currentPosition.toLong() }.getOrDefault(currentPosMs)
            currentPosMs = pos.coerceAtLeast(0L)
            delay(60L)
        }
    }

    // Image loading state
    val imageBitmap by produceState<Bitmap?>(initialValue = null, resolvedUri, project.mediaType) {
        value = if (project.mediaType == CropMediaType.IMAGE && resolvedUri != null) {
            loadViewerBitmap(context, resolvedUri)
        } else {
            null
        }
    }

    // Zoom & Pan state (pan ONLY allowed when zoom > 1.02f)
    var zoom by remember(resolvedUri) { mutableFloatStateOf(1f) }
    var panOffset by remember(resolvedUri) { mutableStateOf(Offset.Zero) }

    val latestZoom by rememberUpdatedState(zoom)
    val latestPan by rememberUpdatedState(panOffset)
    val latestBrightness by rememberUpdatedState(brightnessLevel)
    val latestVolume by rememberUpdatedState(volumeLevel)
    val latestIsMuted by rememberUpdatedState(isMuted)
    val latestMediaType by rememberUpdatedState(project.mediaType)

    val density = LocalDensity.current
    val tapSlopPx = with(density) { 10.dp.toPx() }

    val mediaRatio = remember(project, videoWidth, videoHeight, imageBitmap) {
        when (project.mediaType) {
            CropMediaType.VIDEO -> {
                if (videoWidth > 0 && videoHeight > 0) {
                    (videoWidth.toFloat() / videoHeight.toFloat()).coerceIn(0.2f, 5f)
                } else {
                    16f / 9f
                }
            }
            CropMediaType.IMAGE -> {
                val bmp = imageBitmap
                if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                    (bmp.width.toFloat() / bmp.height.toFloat()).coerceIn(0.2f, 5f)
                } else if (project.exportedWidth > 0 && project.exportedHeight > 0) {
                    (project.exportedWidth.toFloat() / project.exportedHeight.toFloat()).coerceIn(0.2f, 5f)
                } else {
                    1f
                }
            }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("project_media_viewer_screen"),
        color = Color(0xFF04060A)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Main Media Viewport with Pinch-Zoom, Conditional Pan, Swipe Volume/Brightness, and Tap-to-Play/Pause
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

                val latestBoxW by rememberUpdatedState(boxW)
                val latestBoxH by rememberUpdatedState(boxH)
                val latestFittedW by rememberUpdatedState(fittedW)
                val latestFittedH by rememberUpdatedState(fittedH)

                fun clampPanForZoom(rawPan: Offset, curZoom: Float): Offset {
                    if (curZoom <= 1.02f) return Offset.Zero
                    val maxX = (((latestFittedW * curZoom) - latestBoxW) * 0.5f)
                        .coerceAtLeast(latestBoxW * (curZoom - 1f) * 0.25f)
                    val maxY = (((latestFittedH * curZoom) - latestBoxH) * 0.5f)
                        .coerceAtLeast(latestBoxH * (curZoom - 1f) * 0.25f)
                    return Offset(
                        x = rawPan.x.coerceIn(-maxX, maxX),
                        y = rawPan.y.coerceIn(-maxY, maxY)
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(resolvedUri, project.mediaType) {
                            awaitEachGesture {
                                val firstDown = awaitFirstDown(requireUnconsumed = false)
                                firstDown.consume()

                                val startX = firstDown.position.x
                                var totalMovement = 0f
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
                                        if (!isMultiTouch &&
                                            totalMovement <= tapSlopPx &&
                                            latestMediaType == CropMediaType.VIDEO
                                        ) {
                                            isPlaying = !isPlaying
                                        }
                                        isVerticalHudDragging = false
                                        break
                                    }

                                    if (pressed.size >= 2) {
                                        isMultiTouch = true
                                        isVerticalHudDragging = false
                                        dragAxis = ViewerDragAxis.PAN_ZOOMED
                                        val zoomDelta = event.calculateZoom()
                                        val panDelta = event.calculatePan()
                                        val nextZoom = (latestZoom * zoomDelta).coerceIn(1f, 5f)
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
                                        val delta = primary.positionChange()
                                        if (delta != Offset.Zero) {
                                            totalMovement += delta.getDistance()
                                            primary.consume()

                                            if (latestZoom > 1.02f) {
                                                // Zoomed in: 1-finger drag pans across all edges/directions
                                                panOffset = clampPanForZoom(latestPan + delta, latestZoom)
                                            } else {
                                                // Normal unzoomed state: panning is locked; vertical swipe adjusts Brightness (left) / Volume (right)
                                                if (dragAxis == ViewerDragAxis.UNDECIDED && totalMovement > tapSlopPx) {
                                                    dragAxis = if (abs(delta.y) >= abs(delta.x)) {
                                                        if (startX < latestBoxW * 0.5f) {
                                                            ViewerDragAxis.BRIGHTNESS
                                                        } else {
                                                            ViewerDragAxis.VOLUME
                                                        }
                                                    } else {
                                                        ViewerDragAxis.UNDECIDED
                                                    }
                                                }

                                                when (dragAxis) {
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
                                                        if (nextVol > 0.01f && latestIsMuted) {
                                                            isMuted = false
                                                        }
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
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                translationX = activePan.x
                                translationY = activePan.y
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (resolvedUri == null || playbackError != null) {
                            Text(
                                text = playbackError ?: "Unable to load media.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = FrameScopeColors.TextSecondary,
                                modifier = Modifier.padding(24.dp)
                            )
                        } else if (project.mediaType == CropMediaType.VIDEO) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .aspectRatio(mediaRatio, matchHeightConstraintsFirst = mediaRatio < boxRatio),
                                contentAlignment = Alignment.Center
                            ) {
                                AndroidView(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .testTag("viewer_video_surface"),
                                    factory = { ctx ->
                                        TextureView(ctx).apply {
                                            isOpaque = false
                                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                                override fun onSurfaceTextureAvailable(
                                                    surfaceTexture: SurfaceTexture,
                                                    width: Int,
                                                    height: Int
                                                ) {
                                                    val surface = Surface(surfaceTexture)
                                                    val mp = MediaPlayer()
                                                    mediaPlayerRef = mp
                                                    runCatching {
                                                        mp.setDataSource(ctx, resolvedUri)
                                                        mp.setSurface(surface)
                                                        mp.isLooping = false
                                                        mp.setOnPreparedListener { prepared ->
                                                            isPlayerPrepared = true
                                                            val dur = prepared.duration.toLong().coerceAtLeast(0L)
                                                            if (dur > 0L) videoDurationMs = dur
                                                            if (prepared.videoWidth > 0 && prepared.videoHeight > 0) {
                                                                videoWidth = prepared.videoWidth
                                                                videoHeight = prepared.videoHeight
                                                            }
                                                            val vol = if (isMuted) 0f else 1f
                                                            runCatching { prepared.setVolume(vol, vol) }
                                                            // Show initial frame cleanly without auto-playing
                                                            runCatching { prepared.seekTo(1L, MediaPlayer.SEEK_CLOSEST) }
                                                        }
                                                        mp.setOnCompletionListener {
                                                            isPlaying = false
                                                            currentPosMs = videoDurationMs
                                                        }
                                                        mp.setOnErrorListener { _, what, extra ->
                                                            isPlaying = false
                                                            playbackError = "Playback failed (code $what/$extra)."
                                                            true
                                                        }
                                                        mp.prepareAsync()
                                                    }.onFailure {
                                                        playbackError = it.message ?: "Unable to play video."
                                                    }
                                                }

                                                override fun onSurfaceTextureSizeChanged(
                                                    surface: SurfaceTexture,
                                                    width: Int,
                                                    height: Int
                                                ) = Unit

                                                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                                    isPlayerPrepared = false
                                                    isPlaying = false
                                                    runCatching { mediaPlayerRef?.stop() }
                                                    runCatching { mediaPlayerRef?.release() }
                                                    mediaPlayerRef = null
                                                    return true
                                                }

                                                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                                            }
                                        }
                                    }
                                )
                            }
                        } else {
                            val bmp = imageBitmap
                            if (bmp != null) {
                                val imgBmp = remember(bmp) { bmp.asImageBitmap() }
                                Image(
                                    bitmap = imgBmp,
                                    contentDescription = project.title,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .testTag("viewer_image_surface")
                                )
                            } else {
                                CircularProgressIndicator(color = FrameScopeColors.SelectionBlue)
                            }
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
                        color = Color(0xCC0B1018),
                        border = BorderStroke(1.dp, FrameScopeColors.BorderSubtle),
                        modifier = Modifier.testTag("viewer_vertical_hud")
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (isBrightness) {
                                    Icons.Rounded.BrightnessHigh
                                } else if (pct == 0 || isMuted) {
                                    Icons.AutoMirrored.Rounded.VolumeOff
                                } else {
                                    Icons.AutoMirrored.Rounded.VolumeUp
                                },
                                contentDescription = null,
                                tint = FrameScopeColors.SelectionBlue,
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
                                        .background(FrameScopeColors.SelectionBlue)
                                )
                            }
                            Text(
                                text = "$pct%",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = Color.White
                            )
                        }
                    }
                }
            }

            // Top Bar: Back + Open Project (Left) & Share (Right)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color(0xA605070B))
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(
                        onClick = {
                            if (!isExportingForShare) onBack()
                        },
                        modifier = Modifier.testTag("viewer_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = FrameScopeColors.SelectionBlueTint,
                        border = BorderStroke(1.dp, FrameScopeColors.SelectionBlue),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = !isExportingForShare) {
                                onOpenProject(project)
                            }
                            .testTag("viewer_open_project_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = "Open Project",
                                tint = FrameScopeColors.SelectionBlue,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Open Project",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.5.sp
                                ),
                                color = Color.White,
                                maxLines = 1
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (zoom > 1.02f) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color(0xCC0D1624),
                            border = BorderStroke(1.dp, FrameScopeColors.SelectionBlue),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .clip(RoundedCornerShape(50))
                                .clickable {
                                    zoom = 1f
                                    panOffset = Offset.Zero
                                }
                                .testTag("viewer_reset_zoom_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = "Reset zoom",
                                    tint = FrameScopeColors.SelectionBlue,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "${String.format(Locale.US, "%.1fx", zoom)}",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = Color.White
                                )
                            }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = FrameScopeColors.ReadyGreenBg,
                        border = BorderStroke(1.dp, FrameScopeColors.ReadyGreen.copy(alpha = 0.7f)),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = !isExportingForShare) {
                                onShareProject(project)
                            }
                            .testTag("viewer_share_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Share,
                                contentDescription = "Share",
                                tint = FrameScopeColors.ReadyGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Share",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.5.sp
                                ),
                                color = Color.White,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // Bottom Controls Bar:
            // Video -> Play/Pause (left), Timeline 0..Duration Slider (middle), Mute + Rotate (right)
            // Photo -> Title/Resolution (left), Rotate (right)
            Surface(
                color = Color(0xB805070B),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
            ) {
                if (project.mediaType == CropMediaType.VIDEO) {
                    val safeDuration = videoDurationMs.coerceAtLeast(1L)
                    val displayPos = if (isScrubbing) scrubPreviewMs else currentPosMs.coerceIn(0L, safeDuration)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = FrameScopeColors.SelectionBlueTint,
                                border = BorderStroke(1.dp, FrameScopeColors.SelectionBlue),
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        isPlaying = !isPlaying
                                    }
                                    .testTag("viewer_play_pause_button")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        contentDescription = if (isPlaying) "Pause" else "Play",
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }

                            Text(
                                text = formatTimelineMs(displayPos),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.5.sp
                                ),
                                color = Color.White
                            )

                            Slider(
                                value = displayPos.toFloat(),
                                onValueChange = { newVal ->
                                    isScrubbing = true
                                    val targetMs = newVal.roundToLong().coerceIn(0L, safeDuration)
                                    scrubPreviewMs = targetMs
                                    currentPosMs = targetMs
                                    runCatching {
                                        mediaPlayerRef?.seekTo(targetMs, MediaPlayer.SEEK_CLOSEST)
                                    }
                                },
                                onValueChangeFinished = {
                                    isScrubbing = false
                                },
                                valueRange = 0f..safeDuration.toFloat(),
                                colors = SliderDefaults.colors(
                                    thumbColor = FrameScopeColors.SelectionBlue,
                                    activeTrackColor = FrameScopeColors.SelectionBlue,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("viewer_timeline_slider")
                            )

                            Text(
                                text = formatTimelineMs(safeDuration),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.5.sp
                                ),
                                color = FrameScopeColors.TextSecondary
                            )

                            IconButton(
                                onClick = { isMuted = !isMuted },
                                modifier = Modifier
                                    .size(42.dp)
                                    .testTag("viewer_mute_button")
                            ) {
                                Icon(
                                    imageVector = if (isMuted) {
                                        Icons.AutoMirrored.Rounded.VolumeOff
                                    } else {
                                        Icons.AutoMirrored.Rounded.VolumeUp
                                    },
                                    contentDescription = if (isMuted) "Unmute" else "Mute",
                                    tint = if (isMuted) FrameScopeColors.SpecRedText else Color.White,
                                    modifier = Modifier.size(21.dp)
                                )
                            }

                            IconButton(
                                onClick = { isLandscape = !isLandscape },
                                modifier = Modifier
                                    .size(42.dp)
                                    .testTag("viewer_rotate_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ScreenRotation,
                                    contentDescription = "Rotate screen",
                                    tint = if (isLandscape) FrameScopeColors.SelectionBlue else Color.White,
                                    modifier = Modifier.size(21.dp)
                                )
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = project.title,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val w = if (project.exportedWidth > 0) project.exportedWidth else project.outputWidth
                            val h = if (project.exportedHeight > 0) project.exportedHeight else project.outputHeight
                            if (w > 0 && h > 0) {
                                Text(
                                    text = "${w}×${h}",
                                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                    color = FrameScopeColors.TextSecondary
                                )
                            }
                        }

                        IconButton(
                            onClick = { isLandscape = !isLandscape },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("viewer_rotate_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ScreenRotation,
                                contentDescription = "Rotate screen",
                                tint = if (isLandscape) FrameScopeColors.SelectionBlue else Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
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
                        color = FrameScopeColors.CardSurface,
                        border = BorderStroke(1.dp, FrameScopeColors.SelectionBlue),
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
                                    text = exportStatusText.ifBlank { "Exporting project to share…" },
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = FrameScopeColors.TextPrimary
                                )
                                Text(
                                    text = "${(exportProgress * 100f).toInt()}%",
                                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                    color = FrameScopeColors.SelectionBlue
                                )
                            }
                            LinearProgressIndicator(
                                progress = { exportProgress.coerceIn(0f, 1f) },
                                color = FrameScopeColors.SelectionBlue,
                                trackColor = FrameScopeColors.InnerSurface,
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

private fun resolveProjectPlaybackUri(
    context: Context,
    project: CropProjectRecord
): Uri? {
    val expPath = project.exportedFilePath
    if (!expPath.isNullOrBlank()) {
        val file = File(expPath)
        if (file.exists() && file.length() > 0L) {
            return Uri.fromFile(file)
        }
    }

    val galleryStr = project.exportedGalleryUriString
    if (!galleryStr.isNullOrBlank()) {
        val uri = runCatching { Uri.parse(galleryStr) }.getOrNull()
        if (uri != null) {
            val readable = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
            }.getOrDefault(false)
            if (readable) return uri
        }
    }

    val localPath = project.localCopyPath
    if (!localPath.isNullOrBlank()) {
        val file = File(localPath)
        if (file.exists() && file.length() > 0L) {
            return Uri.fromFile(file)
        }
    }

    return runCatching { Uri.parse(project.sourceUriString) }.getOrNull()
}

private suspend fun loadViewerBitmap(
    context: Context,
    uri: Uri,
    maxDim: Int = 2560
): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
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
