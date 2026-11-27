package com.example.framescope.ui.crop

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    isPreviewMode: Boolean,
    canvasAspectRatio: Float,
    cropCompositionAspectRatio: Float,
    lockedAspectRatio: Float?,
    maxWorkspaceHeightDp: Dp,
    isVideoPlaying: Boolean,
    isVideoMuted: Boolean = false,
    trimStartMs: Long,
    trimEndMs: Long,
    seekRequestMs: Long?,
    onCropGestureStart: () -> Unit,
    onCropGestureEnd: () -> Unit,
    onCropChanged: (NormalizedCropRect) -> Unit,
    onTransformChanged: (Float, Offset) -> Unit,
    onPanChanged: (Offset) -> Unit,
    onVideoPositionChanged: (Long) -> Unit,
    onSeekHandled: () -> Unit,
    onVideoPlaybackEnded: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val cornerHitRadiusPx = with(density) { 40.dp.toPx() }
    val edgeHitRadiusPx = with(density) { 30.dp.toPx() }
    val minCropPx = with(density) { 42.dp.toPx() }
    val innerMarginPx = with(density) { 12.dp.toPx() }

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        val targetWorkspaceRatio = if (isPreviewMode) {
            cropCompositionAspectRatio.coerceIn(0.2f, 5f)
        } else {
            canvasAspectRatio.coerceIn(0.35f, 2.5f)
        }

        val availW = maxWidth
        val availH = maxWorkspaceHeightDp.coerceAtLeast(240.dp)
        val widthFromH = availH * targetWorkspaceRatio
        val targetCanvasWidthDp = minOf(availW, widthFromH)
        val targetCanvasHeightDp = (targetCanvasWidthDp / targetWorkspaceRatio).coerceAtLeast(100.dp)

        val canvasWidthDp by animateDpAsState(
            targetValue = targetCanvasWidthDp,
            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
            label = "preview_canvas_width"
        )
        val canvasHeightDp by animateDpAsState(
            targetValue = targetCanvasHeightDp,
            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
            label = "preview_canvas_height"
        )

        val canvasWidthPx = with(density) { canvasWidthDp.toPx() }.coerceAtLeast(1f)
        val canvasHeightPx = with(density) { canvasHeightDp.toPx() }.coerceAtLeast(1f)

        val srcW = media.displayWidth.toFloat().coerceAtLeast(1f)
        val srcH = media.displayHeight.toFloat().coerceAtLeast(1f)
        val srcRatio = srcW / srcH

        // Editing coordinates: media is permanently centered & fitted proportionally inside the single canvas.
        val fitW = (canvasWidthPx - innerMarginPx * 2f).coerceAtLeast(1f)
        val fitH = (canvasHeightPx - innerMarginPx * 2f).coerceAtLeast(1f)
        val fitRatio = fitW / fitH
        val (editMediaW, editMediaH) = if (srcRatio >= fitRatio) {
            fitW to (fitW / srcRatio)
        } else {
            (fitH * srcRatio) to fitH
        }
        val editMediaLeft = (canvasWidthPx - editMediaW) / 2f
        val editMediaTop = (canvasHeightPx - editMediaH) / 2f

        // Preview coordinates: keep media container fitted inside canvas (never clamped by parent!)
        // and apply uniform graphicsLayer scale + translation so ONLY the selected crop area zooms
        // cleanly to fill the reshaped Preview Canvas edge-to-edge without distortion.
        val previewBaseScale = minOf(canvasWidthPx / srcW, canvasHeightPx / srcH)
        val previewBaseW = (srcW * previewBaseScale).coerceAtLeast(1f)
        val previewBaseH = (srcH * previewBaseScale).coerceAtLeast(1f)
        val previewBaseLeft = (canvasWidthPx - previewBaseW) * 0.5f
        val previewBaseTop = (canvasHeightPx - previewBaseH) * 0.5f

        val cropBaseW = (cropRect.widthFraction * previewBaseW).coerceAtLeast(1f)
        val cropBaseH = (cropRect.heightFraction * previewBaseH).coerceAtLeast(1f)
        val previewZoomScale = if (isPreviewMode) {
            minOf(canvasWidthPx / cropBaseW, canvasHeightPx / cropBaseH).coerceAtLeast(1f)
        } else {
            1f
        }
        val cropCenterNormX = (cropRect.left + cropRect.right) * 0.5f
        val cropCenterNormY = (cropRect.top + cropRect.bottom) * 0.5f
        val previewTx = if (isPreviewMode) {
            -((cropCenterNormX - 0.5f) * previewBaseW) * previewZoomScale
        } else {
            0f
        }
        val previewTy = if (isPreviewMode) {
            -((cropCenterNormY - 0.5f) * previewBaseH) * previewZoomScale
        } else {
            0f
        }

        val activeMediaLeft = if (isPreviewMode) previewBaseLeft else editMediaLeft
        val activeMediaTop = if (isPreviewMode) previewBaseTop else editMediaTop
        val activeMediaW = if (isPreviewMode) previewBaseW else editMediaW
        val activeMediaH = if (isPreviewMode) previewBaseH else editMediaH

        val mediaWidthDp = with(density) { activeMediaW.toDp() }
        val mediaHeightDp = with(density) { activeMediaH.toDp() }

        // Generous pan range when zoomed so corners & edges can always be centered in every direction.
        val maxPanX = if (zoom > 1.001f) canvasWidthPx * zoom * 0.5f else canvasWidthPx * 0.25f
        val maxPanY = if (zoom > 1.001f) canvasHeightPx * zoom * 0.5f else canvasHeightPx * 0.25f

        val clampedPan = if (isPreviewMode) {
            Offset.Zero
        } else {
            Offset(
                x = panOffset.x.coerceIn(-maxPanX, maxPanX),
                y = panOffset.y.coerceIn(-maxPanY, maxPanY)
            )
        }

        val latestCrop by rememberUpdatedState(cropRect)
        val latestZoom by rememberUpdatedState(zoom)
        val latestPan by rememberUpdatedState(clampedPan)
        val latestZoomEnabled by rememberUpdatedState(isZoomEnabled)
        val latestPreviewMode by rememberUpdatedState(isPreviewMode)
        val latestAspect by rememberUpdatedState(lockedAspectRatio)
        val latestCanvasW by rememberUpdatedState(canvasWidthPx)
        val latestCanvasH by rememberUpdatedState(canvasHeightPx)
        val latestMediaLeft by rememberUpdatedState(editMediaLeft)
        val latestMediaTop by rememberUpdatedState(editMediaTop)
        val latestMediaW by rememberUpdatedState(editMediaW)
        val latestMediaH by rememberUpdatedState(editMediaH)
        val latestPreviewBaseW by rememberUpdatedState(previewBaseW)
        val latestPreviewBaseH by rememberUpdatedState(previewBaseH)
        val latestPreviewZoom by rememberUpdatedState(previewZoomScale)
        val latestSrcRatio by rememberUpdatedState(srcRatio)

        val workspaceBorderColor = when {
            isPreviewMode -> FrameScopeColors.SelectionBlue
            isZoomEnabled -> FrameScopeColors.SelectionBlue
            else -> FrameScopeColors.BorderSubtle
        }

        // Single Central Workspace Box (no nested/double frame layers)
        Box(
            modifier = Modifier
                .size(canvasWidthDp, canvasHeightDp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF070A10))
                .border(
                    width = if (isZoomEnabled || isPreviewMode) 1.75.dp else 1.dp,
                    color = workspaceBorderColor,
                    shape = RoundedCornerShape(14.dp)
                )
                .clipToBounds()
                .testTag("crop_preview_canvas")
                .pointerInput(media.uri) {
                    awaitEachGesture {
                        val firstDown = awaitFirstDown(requireUnconsumed = false)
                        firstDown.consume()

                        if (latestPreviewMode) {
                            // Preview Mode:
                            // - Canvas size & frame stay completely fixed (no edge/corner resizing or canvas dragging).
                            // - 2-finger pinch zooms the media object directly inside the cropped frame.
                            // - 1-finger drag moves the media object left/right/up/down inside the cropped frame.
                            onCropGestureStart()
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressedChanges = event.changes.filter { it.pressed }
                                    if (pressedChanges.isEmpty()) break

                                    val displayW = (latestPreviewBaseW * latestPreviewZoom).coerceAtLeast(1f)
                                    val displayH = (latestPreviewBaseH * latestPreviewZoom).coerceAtLeast(1f)
                                    val cur = latestCrop

                                    if (pressedChanges.size >= 2) {
                                        val zoomDelta = event.calculateZoom()
                                        val panDelta = event.calculatePan()

                                        val curW = cur.widthFraction
                                        val curH = cur.heightFraction
                                        val ratioNorm = (curW / curH).coerceIn(0.05f, 20f)
                                        val maxW = minOf(1f, ratioNorm)
                                        val minW = maxOf(0.0001f, 0.0001f * ratioNorm).coerceAtMost(maxW)

                                        val safeZoom = if (zoomDelta.isFinite() && zoomDelta > 0.01f) zoomDelta else 1f
                                        val targetW = (curW / safeZoom).coerceIn(minW, maxW)
                                        val targetH = (targetW / ratioNorm).coerceIn(0.0001f, 1f)

                                        val centerX = (cur.left + cur.right) * 0.5f - (panDelta.x / displayW)
                                        val centerY = (cur.top + cur.bottom) * 0.5f - (panDelta.y / displayH)

                                        val newLeft = (centerX - targetW * 0.5f).coerceIn(0f, (1f - targetW).coerceAtLeast(0f))
                                        val newTop = (centerY - targetH * 0.5f).coerceIn(0f, (1f - targetH).coerceAtLeast(0f))

                                        onCropChanged(
                                            NormalizedCropRect(
                                                left = newLeft,
                                                top = newTop,
                                                right = newLeft + targetW,
                                                bottom = newTop + targetH
                                            )
                                        )
                                        pressedChanges.forEach { it.consume() }
                                    } else {
                                        val primary = pressedChanges.first()
                                        val screenDelta = primary.positionChange()
                                        if (screenDelta != Offset.Zero) {
                                            primary.consume()
                                            val dLeft = -screenDelta.x / displayW
                                            val dTop = -screenDelta.y / displayH
                                            val wFrac = cur.widthFraction
                                            val hFrac = cur.heightFraction
                                            val newLeft = (cur.left + dLeft).coerceIn(0f, (1f - wFrac).coerceAtLeast(0f))
                                            val newTop = (cur.top + dTop).coerceIn(0f, (1f - hFrac).coerceAtLeast(0f))
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
                                }
                            } finally {
                                onCropGestureEnd()
                            }
                            return@awaitEachGesture
                        }

                        if (latestZoomEnabled) {
                            // Normal Zoom Mode: crop handles & selection box are LOCKED.
                            // 1-finger drag ALWAYS pans the Canvas Viewport; 2-finger pinch zooms + pans.
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressedChanges = event.changes.filter { it.pressed }
                                if (pressedChanges.isEmpty()) break

                                if (pressedChanges.size >= 2) {
                                    val zoomDelta = event.calculateZoom()
                                    val panDelta = event.calculatePan()
                                    val safeDelta = if (zoomDelta.isFinite() && zoomDelta > 0.01f) zoomDelta else 1f
                                    val nextZoom = (latestZoom * safeDelta).coerceIn(1f, 10_000f)
                                    val nextMaxX = if (nextZoom > 1.001f) latestCanvasW * nextZoom * 0.5f else latestCanvasW * 0.25f
                                    val nextMaxY = if (nextZoom > 1.001f) latestCanvasH * nextZoom * 0.5f else latestCanvasH * 0.25f
                                    val nextPan = Offset(
                                        x = (latestPan.x + panDelta.x).coerceIn(-nextMaxX, nextMaxX),
                                        y = (latestPan.y + panDelta.y).coerceIn(-nextMaxY, nextMaxY)
                                    )
                                    onTransformChanged(nextZoom, nextPan)
                                    pressedChanges.forEach { it.consume() }
                                } else {
                                    val primary = pressedChanges.first()
                                    val screenDelta = primary.positionChange()
                                    if (screenDelta != Offset.Zero) {
                                        primary.consume()
                                        val curZoom = latestZoom.coerceAtLeast(1f)
                                        val maxX = if (curZoom > 1.001f) latestCanvasW * curZoom * 0.5f else latestCanvasW * 0.25f
                                        val maxY = if (curZoom > 1.001f) latestCanvasH * curZoom * 0.5f else latestCanvasH * 0.25f
                                        val nextPan = Offset(
                                            x = (latestPan.x + screenDelta.x).coerceIn(-maxX, maxX),
                                            y = (latestPan.y + screenDelta.y).coerceIn(-maxY, maxY)
                                        )
                                        onPanChanged(nextPan)
                                    }
                                }
                            }
                            return@awaitEachGesture
                        }

                        // Normal Crop Editing Mode: map touch into Canvas Viewport coordinates.
                        val cx = latestCanvasW / 2f
                        val cy = latestCanvasH / 2f
                        val z = latestZoom.coerceAtLeast(1f)
                        val localDown = Offset(
                            x = cx + (firstDown.position.x - cx - latestPan.x) / z,
                            y = cy + (firstDown.position.y - cy - latestPan.y) / z
                        )

                        val hitRect = Rect(
                            left = latestMediaLeft + latestCrop.left * latestMediaW,
                            top = latestMediaTop + latestCrop.top * latestMediaH,
                            right = latestMediaLeft + latestCrop.right * latestMediaW,
                            bottom = latestMediaTop + latestCrop.bottom * latestMediaH
                        )
                        val dragTarget = hitTestCropTarget(
                            pos = localDown,
                            rect = hitRect,
                            cornerRadius = cornerHitRadiusPx / z,
                            edgeRadius = edgeHitRadiusPx / z
                        )

                        val isEditableTarget = dragTarget != CropDragTarget.NONE &&
                            dragTarget != CropDragTarget.OUTSIDE_CROP
                        if (isEditableTarget) {
                            onCropGestureStart()
                        }

                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressedChanges = event.changes.filter { it.pressed }
                                if (pressedChanges.isEmpty()) break

                                if (!isEditableTarget || latestZoomEnabled || latestPreviewMode) {
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
                                        CropDragTarget.CENTER_CONTENT -> {
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

                                        CropDragTarget.NONE,
                                        CropDragTarget.OUTSIDE_CROP -> Unit

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
                        } finally {
                            if (isEditableTarget) {
                                onCropGestureEnd()
                            }
                        }
                    }
                }
        ) {
            // Single Canvas Viewport: fixed in Preview Mode; zoomed & panned in Normal Editing Mode.
            val viewportScale = if (isPreviewMode) 1f else zoom
            val viewportPan = if (isPreviewMode) Offset.Zero else clampedPan

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = viewportScale
                        scaleY = viewportScale
                        translationX = viewportPan.x
                        translationY = viewportPan.y
                    }
            ) {
                // Locked Imported Media Object: cannot be dragged independently in Normal Mode.
                // In Preview Mode, scaled & centered smoothly via graphicsLayer to fit the reshaped canvas.
                Box(
                    modifier = Modifier
                        .offset { IntOffset(activeMediaLeft.roundToInt(), activeMediaTop.roundToInt()) }
                        .size(mediaWidthDp, mediaHeightDp)
                        .graphicsLayer {
                            scaleX = previewZoomScale
                            scaleY = previewZoomScale
                            translationX = previewTx
                            translationY = previewTy
                        }
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
                            isMuted = isVideoMuted,
                            trimStartMs = trimStartMs,
                            trimEndMs = trimEndMs,
                            seekRequestMs = seekRequestMs,
                            onPositionChanged = onVideoPositionChanged,
                            onSeekHandled = onSeekHandled,
                            onPlaybackError = onVideoPlaybackEnded
                        )
                    }
                }

                // Crop Selection Overlay (hidden completely in Live Crop Preview Mode)
                if (!isPreviewMode) {
                    val cropCanvasRect = Rect(
                        left = editMediaLeft + cropRect.left * editMediaW,
                        top = editMediaTop + cropRect.top * editMediaH,
                        right = editMediaLeft + cropRect.right * editMediaW,
                        bottom = editMediaTop + cropRect.bottom * editMediaH
                    )

                    val selectionBlueColor = FrameScopeColors.SelectionBlue

                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val scrimColor = Color.Black.copy(alpha = 0.58f)
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
                            val overlayAlpha = if (isZoomEnabled) 0.55f else 1f
                            val guideColor = Color.White.copy(alpha = 0.42f * overlayAlpha)
                            val thirdW = cWidth / 3f
                            val thirdH = cHeight / 3f
                            for (i in 1..2) {
                                drawLine(
                                    color = guideColor,
                                    start = Offset(cLeft + thirdW * i, cTop),
                                    end = Offset(cLeft + thirdW * i, cBottom),
                                    strokeWidth = 0.9.dp.toPx() * invZoom
                                )
                                drawLine(
                                    color = guideColor,
                                    start = Offset(cLeft, cTop + thirdH * i),
                                    end = Offset(cRight, cTop + thirdH * i),
                                    strokeWidth = 0.9.dp.toPx() * invZoom
                                )
                            }

                            val borderColor = selectionBlueColor.copy(alpha = overlayAlpha)
                            drawRect(
                                color = borderColor,
                                topLeft = Offset(cLeft, cTop),
                                size = Size(cWidth, cHeight),
                                style = Stroke(width = 2.dp.toPx() * invZoom)
                            )

                            // 4 Corner Circle Handles matching reference
                            val cornerRadius = 7.dp.toPx() * invZoom
                            val handleFill = Color.White.copy(alpha = overlayAlpha)
                            val handleStroke = selectionBlueColor.copy(alpha = 0.85f * overlayAlpha)
                            listOf(
                                Offset(cLeft, cTop),
                                Offset(cRight, cTop),
                                Offset(cLeft, cBottom),
                                Offset(cRight, cBottom)
                            ).forEach { corner ->
                                drawCircle(
                                    color = handleFill,
                                    radius = cornerRadius,
                                    center = corner
                                )
                                drawCircle(
                                    color = handleStroke,
                                    radius = cornerRadius,
                                    center = corner,
                                    style = Stroke(width = 1.5.dp.toPx() * invZoom)
                                )
                            }

                            // 4 Side-Edge Pill Handles matching reference
                            val pillLong = minOf(22.dp.toPx() * invZoom, cWidth * 0.3f, cHeight * 0.3f)
                            val pillShort = 5.5.dp.toPx() * invZoom
                            val pillCorner = CornerRadius(pillShort / 2f, pillShort / 2f)
                            val midX = (cLeft + cRight) * 0.5f
                            val midY = (cTop + cBottom) * 0.5f

                            // Top pill
                            drawRoundRect(
                                color = handleFill,
                                topLeft = Offset(midX - pillLong * 0.5f, cTop - pillShort * 0.5f),
                                size = Size(pillLong, pillShort),
                                cornerRadius = pillCorner
                            )
                            drawRoundRect(
                                color = handleStroke,
                                topLeft = Offset(midX - pillLong * 0.5f, cTop - pillShort * 0.5f),
                                size = Size(pillLong, pillShort),
                                cornerRadius = pillCorner,
                                style = Stroke(width = 1.dp.toPx() * invZoom)
                            )

                            // Bottom pill
                            drawRoundRect(
                                color = handleFill,
                                topLeft = Offset(midX - pillLong * 0.5f, cBottom - pillShort * 0.5f),
                                size = Size(pillLong, pillShort),
                                cornerRadius = pillCorner
                            )
                            drawRoundRect(
                                color = handleStroke,
                                topLeft = Offset(midX - pillLong * 0.5f, cBottom - pillShort * 0.5f),
                                size = Size(pillLong, pillShort),
                                cornerRadius = pillCorner,
                                style = Stroke(width = 1.dp.toPx() * invZoom)
                            )

                            // Left pill
                            drawRoundRect(
                                color = handleFill,
                                topLeft = Offset(cLeft - pillShort * 0.5f, midY - pillLong * 0.5f),
                                size = Size(pillShort, pillLong),
                                cornerRadius = pillCorner
                            )
                            drawRoundRect(
                                color = handleStroke,
                                topLeft = Offset(cLeft - pillShort * 0.5f, midY - pillLong * 0.5f),
                                size = Size(pillShort, pillLong),
                                cornerRadius = pillCorner,
                                style = Stroke(width = 1.dp.toPx() * invZoom)
                            )

                            // Right pill
                            drawRoundRect(
                                color = handleFill,
                                topLeft = Offset(cRight - pillShort * 0.5f, midY - pillLong * 0.5f),
                                size = Size(pillShort, pillLong),
                                cornerRadius = pillCorner
                            )
                            drawRoundRect(
                                color = handleStroke,
                                topLeft = Offset(cRight - pillShort * 0.5f, midY - pillLong * 0.5f),
                                size = Size(pillShort, pillLong),
                                cornerRadius = pillCorner,
                                style = Stroke(width = 1.dp.toPx() * invZoom)
                            )
                        }
                    }
                }
            }

            if (isZoomEnabled && !isPreviewMode) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = FrameScopeColors.CardSurface.copy(alpha = 0.9f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    Text(
                        text = "Canvas Zoom ${String.format(java.util.Locale.getDefault(), "%.1fx", zoom)} • Crop Locked",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = FrameScopeColors.SelectionBlue,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CropVideoTexturePreview(
    media: ImportedMediaInfo,
    isPlaying: Boolean,
    isMuted: Boolean,
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

    LaunchedEffect(isMuted, isPrepared) {
        val player = mediaPlayer ?: return@LaunchedEffect
        if (!isPrepared) return@LaunchedEffect
        val vol = if (isMuted) 0f else 1f
        runCatching { player.setVolume(vol, vol) }
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
            delay(50L)
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
                                val vol = if (isMuted) 0f else 1f
                                runCatching { mp.setVolume(vol, vol) }
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
            val deltaW = if (abs(dx) >= abs(dy * normRatio)) dx else dy * normRatio
            val maxW = minOf(1f - l, (1f - t) * normRatio)
            val newW = (r - l + deltaW).coerceIn(effectiveMinW, maxW.coerceAtLeast(effectiveMinW))
            val newH = newW / normRatio
            r = l + newW
            b = t + newH
        }
        CropDragTarget.TOP_LEFT -> {
            val deltaW = if (abs(dx) >= abs(dy * normRatio)) -dx else -dy * normRatio
            val maxW = minOf(r, b * normRatio)
            val newW = (r - l + deltaW).coerceIn(effectiveMinW, maxW.coerceAtLeast(effectiveMinW))
            val newH = newW / normRatio
            l = r - newW
            t = b - newH
        }
        CropDragTarget.TOP_RIGHT -> {
            val deltaW = if (abs(dx) >= abs(dy * normRatio)) dx else -dy * normRatio
            val maxW = minOf(1f - l, b * normRatio)
            val newW = (r - l + deltaW).coerceIn(effectiveMinW, maxW.coerceAtLeast(effectiveMinW))
            val newH = newW / normRatio
            r = l + newW
            t = b - newH
        }
        CropDragTarget.BOTTOM_LEFT -> {
            val deltaW = if (abs(dx) >= abs(dy * normRatio)) -dx else dy * normRatio
            val maxW = minOf(r, (1f - t) * normRatio)
            val newW = (r - l + deltaW).coerceIn(effectiveMinW, maxW.coerceAtLeast(effectiveMinW))
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
