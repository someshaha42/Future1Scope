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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class CropDragTarget {
    NONE,
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
    lockedAspectRatio: Float?,
    isVideoPlaying: Boolean,
    onCropChanged: (NormalizedCropRect) -> Unit,
    onPanChanged: (Offset) -> Unit,
    onVideoPlaybackEnded: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val hitRadiusPx = with(density) { 26.dp.toPx() }
    val minCropPx = with(density) { 44.dp.toPx() }

    val latestCrop by rememberUpdatedState(cropRect)
    val latestZoom by rememberUpdatedState(zoom)
    val latestPan by rememberUpdatedState(panOffset)
    val latestAspect by rememberUpdatedState(lockedAspectRatio)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF07090D))
            .border(1.dp, FrameScopeColors.BorderSubtle, RoundedCornerShape(16.dp))
            .clipToBounds()
            .testTag("crop_preview_canvas")
    ) {
        val canvasSizePx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val srcW = media.displayWidth.toFloat().coerceAtLeast(1f)
        val srcH = media.displayHeight.toFloat().coerceAtLeast(1f)
        val srcRatio = srcW / srcH

        val (baseW, baseH) = if (srcRatio >= 1f) {
            canvasSizePx to (canvasSizePx / srcRatio)
        } else {
            (canvasSizePx * srcRatio) to canvasSizePx
        }

        val mediaW = baseW * zoom
        val mediaH = baseH * zoom

        // Clamp pan so zoomed content stays centered/recoverable and never flies off screen
        val maxPanX = ((mediaW - canvasSizePx) / 2f).coerceAtLeast(0f) +
            ((canvasSizePx - baseW).coerceAtLeast(0f) / 2f) * (zoom - 1f).coerceAtLeast(0f)
        val maxPanY = ((mediaH - canvasSizePx) / 2f).coerceAtLeast(0f) +
            ((canvasSizePx - baseH).coerceAtLeast(0f) / 2f) * (zoom - 1f).coerceAtLeast(0f)

        val clampedPan = Offset(
            x = panOffset.x.coerceIn(-maxPanX, maxPanX),
            y = panOffset.y.coerceIn(-maxPanY, maxPanY)
        )

        val mediaLeft = (canvasSizePx - mediaW) / 2f + clampedPan.x
        val mediaTop = (canvasSizePx - mediaH) / 2f + clampedPan.y
        val mediaWidthDp = with(density) { mediaW.toDp() }
        val mediaHeightDp = with(density) { mediaH.toDp() }

        Box(
            modifier = Modifier
                .offset { IntOffset(mediaLeft.roundToInt(), mediaTop.roundToInt()) }
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
                    onPlaybackError = onVideoPlaybackEnded
                )
            }
        }

        val cropCanvasRect = Rect(
            left = mediaLeft + cropRect.left * mediaW,
            top = mediaTop + cropRect.top * mediaH,
            right = mediaLeft + cropRect.right * mediaW,
            bottom = mediaTop + cropRect.bottom * mediaH
        )

        var activeTarget by remember { mutableStateOf(CropDragTarget.NONE) }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(media.uri, mediaW, mediaH, canvasSizePx) {
                    detectDragGestures(
                        onDragStart = { start ->
                            val curLeft = mediaLeft + latestCrop.left * mediaW
                            val curTop = mediaTop + latestCrop.top * mediaH
                            val curRight = mediaLeft + latestCrop.right * mediaW
                            val curBottom = mediaTop + latestCrop.bottom * mediaH
                            activeTarget = hitTestCropTarget(
                                pos = start,
                                rect = Rect(curLeft, curTop, curRight, curBottom),
                                hitRadius = hitRadiusPx
                            )
                        },
                        onDragEnd = { activeTarget = CropDragTarget.NONE },
                        onDragCancel = { activeTarget = CropDragTarget.NONE },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val cur = latestCrop
                            val ratio = latestAspect

                            if (activeTarget == CropDragTarget.CENTER_CONTENT) {
                                if (latestZoom > 1.02f) {
                                    // Pan content under crop selection
                                    val nextPan = Offset(
                                        x = (latestPan.x + dragAmount.x).coerceIn(-maxPanX, maxPanX),
                                        y = (latestPan.y + dragAmount.y).coerceIn(-maxPanY, maxPanY)
                                    )
                                    onPanChanged(nextPan)
                                } else {
                                    // Move crop box within source bounds when zoom is 1x
                                    val dLeft = dragAmount.x / mediaW
                                    val dTop = dragAmount.y / mediaH
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
                                return@detectDragGestures
                            }

                            val minWFrac = (minCropPx / mediaW).coerceIn(0.05f, 0.8f)
                            val minHFrac = (minCropPx / mediaH).coerceIn(0.05f, 0.8f)
                            val dx = dragAmount.x / mediaW
                            val dy = dragAmount.y / mediaH

                            val updated = computeResizedCropRect(
                                current = cur,
                                target = activeTarget,
                                dx = dx,
                                dy = dy,
                                minWFrac = minWFrac,
                                minHFrac = minHFrac,
                                lockedRatio = ratio,
                                sourceRatio = srcRatio
                            )
                            onCropChanged(updated)
                        }
                    )
                }
        ) {
            val scrimColor = Color.Black.copy(alpha = 0.56f)
            val cLeft = cropCanvasRect.left.coerceIn(0f, size.width)
            val cTop = cropCanvasRect.top.coerceIn(0f, size.height)
            val cRight = cropCanvasRect.right.coerceIn(cLeft, size.width)
            val cBottom = cropCanvasRect.bottom.coerceIn(cTop, size.height)
            val cWidth = (cRight - cLeft).coerceAtLeast(0f)
            val cHeight = (cBottom - cTop).coerceAtLeast(0f)

            // Scrim outside crop rectangle (top, bottom, left, right strips)
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
                // Rule-of-thirds guides
                val guideColor = Color.White.copy(alpha = 0.28f)
                val thirdW = cWidth / 3f
                val thirdH = cHeight / 3f
                for (i in 1..2) {
                    drawLine(
                        color = guideColor,
                        start = Offset(cLeft + thirdW * i, cTop),
                        end = Offset(cLeft + thirdW * i, cBottom),
                        strokeWidth = 1.dp.toPx()
                    )
                    drawLine(
                        color = guideColor,
                        start = Offset(cLeft, cTop + thirdH * i),
                        end = Offset(cRight, cTop + thirdH * i),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                // Crop rectangle border
                drawRect(
                    color = FrameScopeColors.SelectionBlue,
                    topLeft = Offset(cLeft, cTop),
                    size = Size(cWidth, cHeight),
                    style = Stroke(width = 2.dp.toPx())
                )

                // Corner L-brackets
                val bracketLen = minOf(20.dp.toPx(), cWidth * 0.3f, cHeight * 0.3f)
                val cornerStroke = 3.5.dp.toPx()
                val bracketPath = Path().apply {
                    // Top-left
                    moveTo(cLeft, cTop + bracketLen)
                    lineTo(cLeft, cTop)
                    lineTo(cLeft + bracketLen, cTop)
                    // Top-right
                    moveTo(cRight - bracketLen, cTop)
                    lineTo(cRight, cTop)
                    lineTo(cRight, cTop + bracketLen)
                    // Bottom-left
                    moveTo(cLeft, cBottom - bracketLen)
                    lineTo(cLeft, cBottom)
                    lineTo(cLeft + bracketLen, cBottom)
                    // Bottom-right
                    moveTo(cRight - bracketLen, cBottom)
                    lineTo(cRight, cBottom)
                    lineTo(cRight, cBottom - bracketLen)
                }
                drawPath(
                    path = bracketPath,
                    color = Color.White,
                    style = Stroke(width = cornerStroke, cap = StrokeCap.Round)
                )

                // Edge midpoint handles
                val edgeHandleLen = minOf(22.dp.toPx(), cWidth * 0.28f, cHeight * 0.28f)
                val edgeStroke = 3.dp.toPx()
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

@Composable
private fun CropVideoTexturePreview(
    media: ImportedMediaInfo,
    isPlaying: Boolean,
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

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            TextureView(ctx).apply {
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
                                runCatching { mp.seekTo(1) }
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
        }
    )
}

private fun hitTestCropTarget(
    pos: Offset,
    rect: Rect,
    hitRadius: Float
): CropDragTarget {
    val nearLeft = abs(pos.x - rect.left) <= hitRadius
    val nearRight = abs(pos.x - rect.right) <= hitRadius
    val nearTop = abs(pos.y - rect.top) <= hitRadius
    val nearBottom = abs(pos.y - rect.bottom) <= hitRadius

    val withinX = pos.x in (rect.left - hitRadius)..(rect.right + hitRadius)
    val withinY = pos.y in (rect.top - hitRadius)..(rect.bottom + hitRadius)

    return when {
        nearLeft && nearTop -> CropDragTarget.TOP_LEFT
        nearRight && nearTop -> CropDragTarget.TOP_RIGHT
        nearLeft && nearBottom -> CropDragTarget.BOTTOM_LEFT
        nearRight && nearBottom -> CropDragTarget.BOTTOM_RIGHT
        nearLeft && withinY -> CropDragTarget.LEFT_EDGE
        nearRight && withinY -> CropDragTarget.RIGHT_EDGE
        nearTop && withinX -> CropDragTarget.TOP_EDGE
        nearBottom && withinX -> CropDragTarget.BOTTOM_EDGE
        else -> CropDragTarget.CENTER_CONTENT
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
            CropDragTarget.CENTER_CONTENT, CropDragTarget.NONE -> Unit
        }
        return NormalizedCropRect(l, t, r, b)
    }

    // Normalized ratio factor: wFrac / hFrac = lockedRatio / sourceRatio
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
        CropDragTarget.CENTER_CONTENT, CropDragTarget.NONE -> Unit
    }

    return NormalizedCropRect(
        left = l.coerceIn(0f, 1f),
        top = t.coerceIn(0f, 1f),
        right = r.coerceIn(0f, 1f),
        bottom = b.coerceIn(0f, 1f)
    )
}
