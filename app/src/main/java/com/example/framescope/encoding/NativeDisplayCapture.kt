package com.example.framescope.encoding

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.view.Surface

class NativeDisplayCapture(
    private val projection: MediaProjection
) {
    private var virtualDisplay: VirtualDisplay? = null
    var resizeHandler: CaptureResizeHandler? = null
        private set

    fun start(
        width: Int,
        height: Int,
        densityDpi: Int,
        encoderSurface: Surface,
        orientation: RecordingOrientation,
        renderer: RotationRenderer
    ) {
        check(virtualDisplay == null)
        val created = projection.createVirtualDisplay(
            "FrameScopeDisplayCapture",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            encoderSurface,
            null,
            null
        ) ?: error("VirtualDisplay creation failed.")

        virtualDisplay = created
        resizeHandler = CaptureResizeHandler(
            mode = orientation,
            virtualDisplay = created,
            densityDpi = densityDpi,
            renderer = renderer
        )
    }

    fun stop() {
        resizeHandler = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
    }
}
