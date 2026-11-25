package com.example.framescope.ui.crop

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.framescope.crop.CropMediaType
import com.example.framescope.crop.ImportedMediaInfo
import com.example.framescope.crop.NormalizedCropRect
import com.example.framescope.ui.theme.FrameScopeColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class CropDragTarget {
    NONE,
    OUTSIDE_CROP,
    CENTER_CONTENT,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    LEFT_EDGE,
    TOP_EDGE,
    RIGHT_EDGE,
    BOTTOM_EDGE
}

@Composable
fun CropWorkspaceCanvas(
    media: ImportedMediaInfo,
    previewBitmap: Bitmap?,
    cropRect: NormalizedCropRect,
    zoom: Float,
    panOffset: Offset,
    isZoomEnabled: Boolean,
    canvasAspectRatio: Float,
    lockedAspectRatio: Float?,
    isVideoPlaying: Boolean,
    trimStartMs: Long,
    trimEndMs: Long,
    seekRequestMs: Long?,
    onCropChanged: (NormalizedCropRect) -> Unit,
    onTransformChanged: (Float, Offset) -> Unit,
    onPanChanged: (Offset) -> Unit,
    onVideoPositionChanged: (Long) -> Unit,
    onSeekHandled: () -> Unit,
    onVideoPlaybackEnded: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val cornerHitRadiusPx = with(density) { 38.dp.toPx() }
    val edgeHitRadiusPx = with(density) { 30.dp.toPx() }
    val minCropPx = with(density) { 42.dp.toPx() }
    val innerMarginPx = with(density) { 14.dp.toPx() }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF04060A))
            .border(1.dp, FrameScopeColors.BorderSubtle, RoundedCornerShape(16.dp))
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        val safeCanvasRatio = canvasAspectRatio.coerceIn(0.35f, 2.5f)
        val availW = maxWidth
        val availH = minOf(maxWidth / safeCanvasRatio, 408.dp)
        val canvasWidthDp = minOf(availW, availH * safeCanvasRatio)
        val canvasHeightDp = canvasWidthDp / safeCanvasRatio

        val canvasWidthPx = with(density) { canvasWidthDp.toPx() }.coerceAtLeast(1f)
        val canvasHeightPx = with(density) { canvasHeightDp.toPx() }.coerceAtLeast(1f)

        val fitW = (canvasWidthPx - innerMarginPx * 2f).coerceAtLeast(1f)
        val fitH = (canvasHeightPx - innerMarginPx * 2f).coerceAtLeast(1f)

        val srcW = media.displayWidth.toFloat().coerceAtLeast(1f)
        val srcH = media.displayHeight.toFloat().coerceAtLeast(1f)
        val srcRatio = srcW / srcH
        val fitRatio = fitW / fitH

        val (baseW, baseH) = if (srcRatio >= fitRatio) {
            fitW to (fitW / srcRatio)
        } else {
            (fitH * srcRatio) to fitH
        }

        // Media ALWAYS remains centered inside the canvas; zoom transforms the canvas viewport itself.
        val baseMediaLeft = (canvasWidthPx - baseW) / 2f
        val baseMediaTop = (canvasHeightPx - baseH) / 2f
        val mediaWidthDp = with(density) { baseW.toDp() }
        val mediaHeightDp = with(density) { baseH.toDp() }

        val maxPanX = (canvasWidthPx * (zoom - 1f) / 2f).coerceAtLeast(0f)
        val maxPanY = (canvasHeightPx * (zoom - 1f) / 2f).coerceAtLeast(0f)

        val clampedPan = Offset(
            x = panOffset.x.coerceIn(-maxPanX, maxPanX),
            y = panOffset.y.coerceIn(-maxPanY, maxPanY)
        )

        val latestCrop by rememberUpdatedState(cropRect)
        val latestZoom by rememberUpdatedState(zoom)
        val latestPan by rememberUpdatedState(clampedPan)
        val latestZoomEnabled by rememberUpdatedState(isZoomEnabled)
        val latestAspect by rememberUpdatedState(lockedAspectRatio)
        val latestCanvasW by rememberUpdatedState(canvasWidthPx)
        val latestCanvasH by rememberUpdatedState(canvasHeightPx)
        val latestMediaLeft by rememberUpdatedState(baseMediaLeft)
        val latestMediaTop by rememberUpdatedState(baseMediaTop)
        val latestMediaW by rememberUpdatedState(baseW)
        val latestMediaH by rememberUpdatedState(baseH)
        val latestSrcRatio by rememberUpdatedState(srcRatio)

        Box(
            modifier = Modifier
                .size(canvasWidthDp, canvasHeightDp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF0B0F17))
                .border(
                    1.25.dp,
                    FrameScopeColors.SelectionBlue.copy(alpha = 0.5f),
                    RoundedCornerShape(12.dp)
                )
                .clipToBounds()
                .testTag("crop_preview_canvas")
                .pointerInput(media.uri) {
                    awaitEachGesture {
                        val firstDown = awaitFirstDown(requireUnconsumed = false)
                        firstDown.consume()

                        val cx = latestCanvasW / 2f
                        val cy = latestCanvasH / 2f
                        val z = latestZoom.coerceAtLeast(1f)
                        val localDown = Offset(
                            x = cx + (firstDown.position.x - cx - latestPan.x) / z,
                            y = cy + (firstDown.position.y - cy - latestPan.y) / z
                        )

                        val curLeft = latestMediaLeft + latestCrop.left * latestMediaW
                        val curTop = latestMediaTop + latestCrop.top * latestMediaH
                        val curRight = latestMediaLeft + latestCrop.right * latestMediaW
                        val curBottom = latestMediaTop + latestCrop.bottom * latestMediaH

                        val dragTarget = hitTestCropTarget(
                            pos = localDown,
                            rect = Rect(curLeft, curTop, curRight, curBottom),
                            cornerRadius = cornerHitRadiusPx / z,
                            edgeRadius = edgeHitRadiusPx / z
                        )

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressedChanges = event.changes.filter { it.pressed }
                            if (pressedChanges.isEmpty()) break

                            if (pressedChanges.size >= 2 && latestZoomEnabled) {
                                val zoomDelta = event.calculateZoom()
                                val panDelta = event.calculatePan()
                                val nextZoom = (latestZoom * zoomDelta).coerceIn(1f, 5f)
                                val nextMaxPanX = (latestCanvasW * (nextZoom - 1f) / 2f).coerceAtLeast(0f)
                                val nextMaxPanY = (latestCanvasH * (nextZoom - 1f) / 2f).coerceAtLeast(0f)
                                val nextPan = Offset(
                                    x = (latestPan.x + panDelta.x).coerceIn(-nextMaxPanX, nextMaxPanX),
                                    y = (latestPan.y + panDelta.y).coerceIn(-nextMaxPanY, nextMaxPanY)
                                )
                                onTransformChanged(nextZoom, nextPan)
                                pressedChanges.forEach { it.consume() }
                                continue
                            }

                            val primary = pressedChanges.first()
                            val screenDelta = primary.positionChange()
                            if (screenDelta != Offset.Zero) {
                                primary.consume()
                                val curZoom = latestZoom.coerceAtLeast(1f)
                                val cur = latestCrop
                                val mW = latestMediaW.coerceAtLeast(1f)
                                val mH = latestMediaH.coerceAtLeast(1f)
                                val delta = Offset(screenDelta.x / curZoom, screenDelta.y / curZoom)

                                when (dragTarget) {
                                    CropDragTarget.OUTSIDE_CROP -> {
                                        if (latestZoomEnabled && curZoom > 1.02f) {
                                            val maxX = (latestCanvasW * (curZoom - 1f) / 2f).coerceAtLeast(0f)
                                            val maxY = (latestCanvasH * (curZoom - 1f) / 2f).coerceAtLeast(0f)
                                            val nextPan = Offset(
                                                x = (latestPan.x + screenDelta.x).coerceIn(-maxX, maxX),
                                                y = (latestPan.y + screenDelta.y).coerceIn(-maxY, maxY)
                                            )
                                            onPanChanged(nextPan)
                                        }
                                    }

                                    CropDragTarget.CENTER_CONTENT -> {
                                        val isFullFrame = cur.widthFraction >= 0.995f && cur.heightFraction >= 0.995f
                                        if (isFullFrame && latestZoomEnabled && curZoom > 1.02f) {
                                            val maxX = (latestCanvasW * (curZoom - 1f) / 2f).coerceAtLeast(0f)
                                            val maxY = (latestCanvasH * (curZoom - 1f) / 2f).coerceAtLeast(0f)
                                            val nextPan = Offset(
                                                x = (latestPan.x + screenDelta.x).coerceIn(-maxX, maxX),
                                                y = (latestPan.y + screenDelta.y).coerceIn(-maxY, maxY)
                                            )
                                            onPanChanged(nextPan)
                                        } else {
                                            val dLeft = delta.x / mW
                                            val dTop = delta.y / mH
                                            val wFrac = cur.widthFraction
                                            val hFrac = cur.heightFraction
                                            val newLeft = (cur.left + dLeft).coerceIn(0f, 1f - wFrac)
                                            val newTop = (cur.top + dTop).coerceIn(0f, 1f - hFrac)
                                            onCropChanged(
                                                NormalizedCropRect(
                                                    left = newLeft,
                                                    top = newTop,
                                                    right = newLeft + wFrac,
                                                    bottom = newTop + hFrac
                                                )
                                            )
                                        }
                                    }

                                    CropDragTarget.NONE -> Unit

                                    else -> {
                                        val minWFrac = (minCropPx / mW).coerceIn(0.04f, 0.8f)
                                        val minHFrac = (minCropPx / mH).coerceIn(0.04f, 0.8f)
                                        val dx = delta.x / mW
                                        val dy = delta.y / mH

                                        val updated = computeResizedCropRect(
                                            current = cur,
                                            target = dragTarget,
                                            dx = dx,
                                            dy = dy,
                                            minWFrac = minWFrac,
                                            minHFrac = minHFrac,
                                            lockedRatio = latestAspect,
                                            sourceRatio = latestSrcRatio
                                        )
                                        onCropChanged(updated)
                                    }
                                }
                            }
                        }
                    }
                }
        ) {
            // Single zoomed viewport containing both media + crop overlay so media NEVER shifts independently.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = clampedPan.x
                        translationY = clampedPan.y
                    }
            ) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(baseMediaLeft.roundToInt(), baseMediaTop.roundToInt()) }
                        .size(mediaWidthDp, mediaHeightDp)
                ) {
                    if (media.mediaType == CropMediaType.IMAGE && previewBitmap != null) {
                        val imageBitmap = remember(previewBitmap) { previewBitmap.asImageBitmap() }
                        Image(
                            bitmap = imageBitmap,
                            contentDescription = media.displayName,
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (media.mediaType == CropMediaType.VIDEO) {
                        CropVideoTexturePreview(
                            media = media,
                            isPlaying = isVideoPlaying,
                            trimStartMs = trimStartMs,
                            trimEndMs = trimEndMs,
                            seekRequestMs = seekRequestMs,
                            onPositionChanged = onVideoPositionChanged,
                            onSeekHandled = onSeekHandled,
                            onPlaybackError = onVideoPlaybackEnded
                        )
                    }
                }

                val cropCanvasRect = Rect(
                    left = baseMediaLeft + cropRect.left * baseW,
                    top = baseMediaTop + cropRect.top * baseH,
                    right = baseMediaLeft + cropRect.right * baseW,
                    bottom = baseMediaTop + cropRect.bottom * baseH
                )

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val scrimColor = Color.Black.copy(alpha = 0.56f)
                    val cLeft = cropCanvasRect.left.coerceIn(0f, size.width)
                    val cTop = cropCanvasRect.top.coerceIn(0f, size.height)
                    val cRight = cropCanvasRect.right.coerceIn(cLeft, size.width)
                    val cBottom = cropCanvasRect.bottom.coerceIn(cTop, size.height)
                    val cWidth = (cRight - cLeft).coerceAtLeast(0f)
                    val cHeight = (cBottom - cTop).coerceAtLeast(0f)

                    if (cTop > 0f) {
                        drawRect(scrimColor, Offset.Zero, Size(size.width, cTop))
                    }
                    if (cBottom < size.height) {
                        drawRect(scrimColor, Offset(0f, cBottom), Size(size.width, size.height - cBottom))
                    }
                    if (cLeft > 0f && cHeight > 0f) {
                        drawRect(scrimColor, Offset(0f, cTop), Size(cLeft, cHeight))
                    }
                    if (cRight < size.width && cHeight > 0f) {
                        drawRect(scrimColor, Offset(cRight, cTop), Size(size.width - cRight, cHeight))
                    }

                    if (cWidth > 2f && cHeight > 2f) {
                        val invZoom = 1f / zoom.coerceAtLeast(1f)
                        val guideColor = Color.White.copy(alpha = 0.28f)
                        val thirdW = cWidth / 3f
                        val thirdH = cHeight / 3f
                        for (i in 1..2) {
                            drawLine(
                                color = guideColor,
                                start = Offset(cLeft + thirdW * i, cTop),
                                end = Offset(cLeft + thirdW * i, cBottom),
                                strokeWidth = 1.dp.toPx() * invZoom
                            )
                            drawLine(
                                color = guideColor,
                                start = Offset(cLeft, cTop + thirdH * i),
                                end = Offset(cRight, cTop + thirdH * i),
                                strokeWidth = 1.dp.toPx() * invZoom
                            )
                        }

                        drawRect(
                            color = FrameScopeColors.SelectionBlue,
                            topLeft = Offset(cLeft, cTop),
                            size = Size(cWidth, cHeight),
                            style = Stroke(width = 2.dp.toPx() * invZoom)
                        )

                        val bracketLen = minOf(22.dp.toPx() * invZoom, cWidth * 0.32f, cHeight * 0.32f)
                        val cornerStroke = 4.dp.toPx() * invZoom
                        val bracketPath = Path().apply {
                            moveTo(cLeft, cTop + bracketLen)
                            lineTo(cLeft, cTop)
                            lineTo(cLeft + bracketLen, cTop)

                            moveTo(cRight - bracketLen, cTop)
                            lineTo(cRight, cTop)
                            lineTo(cRight, cTop + bracketLen)

                            moveTo(cLeft, cBottom - bracketLen)
                            lineTo(cLeft, cBottom)
                            lineTo(cLeft + bracketLen, cBottom)

                            moveTo(cRight - bracketLen, cBottom)
                            lineTo(cRight, cBottom)
                            lineTo(cRight, cBottom - bracketLen)
                        }
                        drawPath(
                            path = bracketPath,
                            color = Color.White,
                            style = Stroke(width = cornerStroke, cap = StrokeCap.Round)
                        )

                        val dotRadius = 5.dp.toPx() * invZoom
                        listOf(
                            Offset(cLeft, cTop),
                            Offset(cRight, cTop),
                            Offset(cLeft, cBottom),
                            Offset(cRight, cBottom)
                        ).forEach { corner ->
                            drawCircle(
                                color = FrameScopeColors.SelectionBlue,
                                radius = dotRadius,
                                center = corner
                            )
                            drawCircle(
                                color = Color.White,
                                radius = dotRadius,
                                center = corner,
                                style = Stroke(width = 1.5.dp.toPx() * invZoom)
                            )
                        }

                        val edgeHandleLen = minOf(24.dp.toPx() * invZoom, cWidth * 0.28f, cHeight * 0.28f)
                        val edgeStroke = 3.2.dp.toPx() * invZoom
                        val midX = (cLeft + cRight) / 2f
                        val midY = (cTop + cBottom) / 2f
                        drawLine(
                            color = Color.White,
                            start = Offset(midX - edgeHandleLen / 2f, cTop),
                            end = Offset(midX + edgeHandleLen / 2f, cTop),
                            strokeWidth = edgeStroke,
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            color = Color.White,
                            start = Offset(midX - edgeHandleLen / 2f, cBottom),
                            end = Offset(midX + edgeHandleLen / 2f, cBottom),
                            strokeWidth = edgeStroke,
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            color = Color.White,
                            start = Offset(cLeft, midY - edgeHandleLen / 2f),
                            end = Offset(cLeft, midY + edgeHandleLen / 2f),
                            strokeWidth = edgeStroke,
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            color = Color.White,
                            start = Offset(cRight, midY - edgeHandleLen / 2f),
                            end = Offset(cRight, midY + edgeHandleLen / 2f),
                            strokeWidth = edgeStroke,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CropVideoTexturePreview(
    media: ImportedMediaInfo,
    isPlaying: Boolean,
    trimStartMs: Long,
    trimEndMs: Long,
    seekRequestMs: Long?,
    onPositionChanged: (Long) -> Unit,
    onSeekHandled: () -> Unit,
    onPlaybackError: () -> Unit
) {
    val context = LocalContext.current
    var mediaPlayer by remember(media.uri) { mutableStateOf<MediaPlayer?>(null) }
    var isPrepared by remember(media.uri) { mutableStateOf(false) }

    DisposableEffect(media.uri) {
        onDispose {
            isPrepared = false
            runCatching { mediaPlayer?.stop() }
            runCatching { mediaPlayer?.release() }
            mediaPlayer = null
        }
    }

    LaunchedEffect(isPlaying, isPrepared) {
        val player = mediaPlayer ?: return@LaunchedEffect
        if (!isPrepared) return@LaunchedEffect
        runCatching {
            if (isPlaying && !player.isPlaying) {
                player.start()
            } else if (!isPlaying && player.isPlaying) {
                player.pause()
            }
        }
    }

    LaunchedEffect(seekRequestMs, isPrepared) {
        val targetMs = seekRequestMs ?: return@LaunchedEffect
        val player = mediaPlayer ?: return@LaunchedEffect
        if (!isPrepared) return@LaunchedEffect
        runCatching {
            player.seekTo(targetMs, MediaPlayer.SEEK_CLOSEST)
            onPositionChanged(targetMs)
        }
        onSeekHandled()
    }

    LaunchedEffect(isPlaying, isPrepared, trimStartMs, trimEndMs) {
        val player = mediaPlayer ?: return@LaunchedEffect
        if (!isPrepared || !isPlaying) return@LaunchedEffect
        while (isActive) {
            val pos = runCatching { player.currentPosition.toLong() }.getOrDefault(0L)
            onPositionChanged(pos)
            if (trimEndMs > trimStartMs && pos >= trimEndMs) {
                runCatching { player.seekTo(trimStartMs, MediaPlayer.SEEK_CLOSEST) }
                onPositionChanged(trimStartMs)
            }
            delay(60L)
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
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
                        val player = MediaPlayer()
                        mediaPlayer = player
                        runCatching {
                            player.setDataSource(context, media.uri)
                            player.setSurface(surface)
                            player.isLooping = true
                            player.setOnPreparedListener { mp ->
                                isPrepared = true
                                runCatching { mp.seekTo(1L, MediaPlayer.SEEK_CLOSEST) }
                                if (isPlaying) {
                                    runCatching { mp.start() }
                                }
                            }
                            player.setOnErrorListener { _, _, _ ->
                                onPlaybackError()
                                true
                            }
                            player.prepareAsync()
                        }.onFailure {
                            onPlaybackError()
                        }
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) = Unit

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        isPrepared = false
                        runCatching { mediaPlayer?.stop() }
                        runCatching { mediaPlayer?.release() }
                        mediaPlayer = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                }
            }
        },
        update = { view ->
            view.requestLayout()
        }
    )
}

private fun hitTestCropTarget(
    pos: Offset,
    rect: Rect,
    cornerRadius: Float,
    edgeRadius: Float
): CropDragTarget {
    val dTopLeft = (pos - Offset(rect.left, rect.top)).getDistance()
    val dTopRight = (pos - Offset(rect.right, rect.top)).getDistance()
    val dBottomLeft = (pos - Offset(rect.left, rect.bottom)).getDistance()
    val dBottomRight = (pos - Offset(rect.right, rect.bottom)).getDistance()

    val nearestCorner = listOf(
        CropDragTarget.TOP_LEFT to dTopLeft,
        CropDragTarget.TOP_RIGHT to dTopRight,
        CropDragTarget.BOTTOM_LEFT to dBottomLeft,
        CropDragTarget.BOTTOM_RIGHT to dBottomRight
    ).minByOrNull { it.second }

    if (nearestCorner != null && nearestCorner.second <= cornerRadius) {
        return nearestCorner.first
    }

    val nearLeft = abs(pos.x - rect.left) <= edgeRadius
    val nearRight = abs(pos.x - rect.right) <= edgeRadius
    val nearTop = abs(pos.y - rect.top) <= edgeRadius
    val nearBottom = abs(pos.y - rect.bottom) <= edgeRadius

    val withinX = pos.x in (rect.left - edgeRadius)..(rect.right + edgeRadius)
    val withinY = pos.y in (rect.top - edgeRadius)..(rect.bottom + edgeRadius)

    return when {
        nearLeft && withinY -> CropDragTarget.LEFT_EDGE
        nearRight && withinY -> CropDragTarget.RIGHT_EDGE
        nearTop && withinX -> CropDragTarget.TOP_EDGE
        nearBottom && withinX -> CropDragTarget.BOTTOM_EDGE
        pos.x in rect.left..rect.right && pos.y in rect.top..rect.bottom -> CropDragTarget.CENTER_CONTENT
        else -> CropDragTarget.OUTSIDE_CROP
    }
}

private fun computeResizedCropRect(
    current: NormalizedCropRect,
    target: CropDragTarget,
    dx: Float,
    dy: Float,
    minWFrac: Float,
    minHFrac: Float,
    lockedRatio: Float?,
    sourceRatio: Float
): NormalizedCropRect {
    if (lockedRatio == null) {
        var l = current.left
        var t = current.top
        var r = current.right
        var b = current.bottom

        when (target) {
            CropDragTarget.TOP_LEFT -> {
                l = (l + dx).coerceIn(0f, r - minWFrac)
                t = (t + dy).coerceIn(0f, b - minHFrac)
            }
            CropDragTarget.TOP_RIGHT -> {
                r = (r + dx).coerceIn(l + minWFrac, 1f)
                t = (t + dy).coerceIn(0f, b - minHFrac)
            }
            CropDragTarget.BOTTOM_LEFT -> {
                l = (l + dx).coerceIn(0f, r - minWFrac)
                b = (b + dy).coerceIn(t + minHFrac, 1f)
            }
            CropDragTarget.BOTTOM_RIGHT -> {
                r = (r + dx).coerceIn(l + minWFrac, 1f)
                b = (b + dy).coerceIn(t + minHFrac, 1f)
            }
            CropDragTarget.LEFT_EDGE -> l = (l + dx).coerceIn(0f, r - minWFrac)
            CropDragTarget.RIGHT_EDGE -> r = (r + dx).coerceIn(l + minWFrac, 1f)
            CropDragTarget.TOP_EDGE -> t = (t + dy).coerceIn(0f, b - minHFrac)
            CropDragTarget.BOTTOM_EDGE -> b = (b + dy).coerceIn(t + minHFrac, 1f)
            else -> Unit
        }
        return NormalizedCropRect(l, t, r, b)
    }

    val normRatio = (lockedRatio / sourceRatio).coerceIn(0.05f, 20f)
    val effectiveMinW = maxOf(minWFrac, minHFrac * normRatio)
    val effectiveMinH = effectiveMinW / normRatio

    var l = current.left
    var t = current.top
    var r = current.right
    var b = current.bottom

    when (target) {
        CropDragTarget.BOTTOM_RIGHT -> {
            val maxW = minOf(1f - l, (1f - t) * normRatio)
            val newW = (r - l + dx).coerceIn(effectiveMinW, maxW.coerceAtLeast(effectiveMinW))
            val newH = newW / normRatio
            r = l + newW
            b = t + newH
        }
        CropDragTarget.TOP_LEFT -> {
            val maxW = minOf(r, b * normRatio)
            val newW = (r - l - dx).coerceIn(effectiveMinW, maxW.coerceAtLeast(effectiveMinW))
            val newH = newW / normRatio
            l = r - newW
            t = b - newH
        }
        CropDragTarget.TOP_RIGHT -> {
            val maxW = minOf(1f - l, b * normRatio)
            val newW = (r - l + dx).coerceIn(effectiveMinW, maxW.coerceAtLeast(effectiveMinW))
            val newH = newW / normRatio
            r = l + newW
            t = b - newH
        }
        CropDragTarget.BOTTOM_LEFT -> {
            val maxW = minOf(r, (1f - t) * normRatio)
            val newW = (r - l - dx).coerceIn(effectiveMinW, maxW.coerceAtLeast(effectiveMinW))
            val newH = newW / normRatio
            l = r - newW
            b = t + newH
        }
        CropDragTarget.RIGHT_EDGE -> {
            val centerY = (t + b) / 2f
            val maxHalfH = minOf(centerY, 1f - centerY)
            val maxW = minOf(1f - l, maxHalfH * 2f * normRatio)
            val newW = (r - l + dx).coerceIn(effectiveMinW, maxW.coerceAtLeast(effectiveMinW))
            val newH = newW / normRatio
            r = l + newW
            t = (centerY - newH / 2f).coerceIn(0f, 1f - newH)
            b = t + newH
        }
        CropDragTarget.LEFT_EDGE -> {
            val centerY = (t + b) / 2f
            val maxHalfH = minOf(centerY, 1f - centerY)
            val maxW = minOf(r, maxHalfH * 2f * normRatio)
            val newW = (r - l - dx).coerceIn(effectiveMinW, maxW.coerceAtLeast(effectiveMinW))
            val newH = newW / normRatio
            l = r - newW
            t = (centerY - newH / 2f).coerceIn(0f, 1f - newH)
            b = t + newH
        }
        CropDragTarget.BOTTOM_EDGE -> {
            val centerX = (l + r) / 2f
            val maxHalfW = minOf(centerX, 1f - centerX)
            val maxH = minOf(1f - t, (maxHalfW * 2f) / normRatio)
            val newH = (b - t + dy).coerceIn(effectiveMinH, maxH.coerceAtLeast(effectiveMinH))
            val newW = newH * normRatio
            b = t + newH
            l = (centerX - newW / 2f).coerceIn(0f, 1f - newW)
            r = l + newW
        }
        CropDragTarget.TOP_EDGE -> {
            val centerX = (l + r) / 2f
            val maxHalfW = minOf(centerX, 1f - centerX)
            val maxH = minOf(b, (maxHalfW * 2f) / normRatio)
            val newH = (b - t - dy).coerceIn(effectiveMinH, maxH.coerceAtLeast(effectiveMinH))
            val newW = newH * normRatio
            t = b - newH
            l = (centerX - newW / 2f).coerceIn(0f, 1f - newW)
            r = l + newW
        }
        else -> Unit
    }

    return NormalizedCropRect(
        left = l.coerceIn(0f, 1f),
        top = t.coerceIn(0f, 1f),
        right = r.coerceIn(0f, 1f),
        bottom = b.coerceIn(0f, 1f)
    )
}
