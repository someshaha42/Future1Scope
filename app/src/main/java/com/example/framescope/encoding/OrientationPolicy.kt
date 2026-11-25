package com.example.framescope.encoding

import android.hardware.display.VirtualDisplay
import com.example.framescope.data.RecordingMode
import com.example.framescope.data.RegionBounds

enum class RecordingOrientation {
    PORTRAIT,
    LANDSCAPE
}

data class VideoDimensions(
    val width: Int,
    val height: Int
) {
    /**
     * Hardware H.264 encoders require even pixel dimensions for YUV 4:2:0 subsampling.
     */
    fun alignEven(): VideoDimensions {
        val evenW = (width and 1.inv()).coerceAtLeast(16)
        val evenH = (height and 1.inv()).coerceAtLeast(16)
        return VideoDimensions(evenW, evenH)
    }
}

fun getPortraitDimensions(
    screenWidth: Int,
    screenHeight: Int
): VideoDimensions {
    return VideoDimensions(
        width = minOf(screenWidth, screenHeight),
        height = maxOf(screenWidth, screenHeight)
    ).alignEven()
}

fun getLandscapeDimensions(
    screenWidth: Int,
    screenHeight: Int
): VideoDimensions {
    return VideoDimensions(
        width = maxOf(screenWidth, screenHeight),
        height = minOf(screenWidth, screenHeight)
    ).alignEven()
}

fun getOutputDimensions(
    mode: RecordingOrientation,
    screenWidth: Int,
    screenHeight: Int
): VideoDimensions {
    return when (mode) {
        RecordingOrientation.PORTRAIT -> getPortraitDimensions(screenWidth, screenHeight)
        RecordingOrientation.LANDSCAPE -> getLandscapeDimensions(screenWidth, screenHeight)
    }
}

fun getCaptureDimensions(
    orientation: RecordingOrientation,
    recordingMode: RecordingMode,
    regionBounds: RegionBounds,
    screenWidth: Int,
    screenHeight: Int
): VideoDimensions {
    val oriented = getOutputDimensions(orientation, screenWidth, screenHeight)
    if (recordingMode == RecordingMode.FULL_SCREEN) {
        return oriented
    }
    val regionW = (oriented.width * regionBounds.widthFraction()).toInt()
    val regionH = (oriented.height * regionBounds.heightFraction()).toInt()
    return VideoDimensions(regionW, regionH).alignEven()
}

class PortraitCapturePolicy {
    fun resolveOutputSize(
        screenWidth: Int,
        screenHeight: Int
    ): VideoDimensions = getPortraitDimensions(screenWidth, screenHeight)

    fun getRotationTransform(
        screenWidth: Int,
        screenHeight: Int
    ): Int = if (screenWidth > screenHeight) 90 else 0
}

class LandscapeCapturePolicy {
    fun resolveOutputSize(
        screenWidth: Int,
        screenHeight: Int
    ): VideoDimensions = getLandscapeDimensions(screenWidth, screenHeight)

    fun getRotationTransform(
        screenWidth: Int,
        screenHeight: Int
    ): Int = if (screenHeight > screenWidth) 90 else 0
}

interface RotationRenderer {
    fun updateInputSize(width: Int, height: Int)
    fun updateOutputSize(width: Int, height: Int)
    fun updateOrientation(mode: RecordingOrientation)
}

class SurfaceRotationCoordinator : RotationRenderer {
    @Volatile
    var inputWidth: Int = 0
        private set

    @Volatile
    var inputHeight: Int = 0
        private set

    @Volatile
    var outputWidth: Int = 0
        private set

    @Volatile
    var outputHeight: Int = 0
        private set

    @Volatile
    var activeMode: RecordingOrientation = RecordingOrientation.PORTRAIT
        private set

    override fun updateInputSize(width: Int, height: Int) {
        inputWidth = width
        inputHeight = height
    }

    override fun updateOutputSize(width: Int, height: Int) {
        outputWidth = width
        outputHeight = height
    }

    override fun updateOrientation(mode: RecordingOrientation) {
        activeMode = mode
    }
}

class CaptureResizeHandler(
    private val mode: RecordingOrientation,
    private val virtualDisplay: VirtualDisplay,
    private val densityDpi: Int,
    private val renderer: RotationRenderer,
    private val lockedWidth: Int = 0,
    private val lockedHeight: Int = 0
) {
    fun onCaptureSizeChanged(
        captureWidth: Int,
        captureHeight: Int
    ) {
        val outputSize = if (lockedWidth > 0 && lockedHeight > 0) {
            VideoDimensions(lockedWidth, lockedHeight)
        } else {
            getOutputDimensions(mode, captureWidth, captureHeight)
        }
        runCatching {
            virtualDisplay.resize(outputSize.width, outputSize.height, densityDpi)
        }
        renderer.updateInputSize(captureWidth, captureHeight)
        renderer.updateOutputSize(outputSize.width, outputSize.height)
        renderer.updateOrientation(mode)
    }
}
