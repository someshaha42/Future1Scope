package com.example.framescope.encoding

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.view.Surface

class NativeDisplayCapture(
    private val projection: MediaProjection
) {
    private var virtualDisplay: VirtualDisplay? = null
    private var gpuPipeline: GpuSurfacePipeline? = null
    var resizeHandler: CaptureResizeHandler? = null
        private set

    fun start(
        width: Int,
        height: Int,
        densityDpi: Int,
        encoderSurface: Surface,
        gpuApi: GpuRenderingApi,
        orientation: RecordingOrientation,
        renderer: RotationRenderer,
        onFatalError: (Throwable) -> Unit
    ) {
        check(virtualDisplay == null)

        val pipeline = createGpuSurfacePipeline(
            api = gpuApi,
            width = width,
            height = height,
            encoderSurface = encoderSurface,
            onFatalError = onFatalError
        )
        pipeline.start()
        gpuPipeline = pipeline

        val created = projection.createVirtualDisplay(
            "FrameScopeDisplayCapture",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            pipeline.inputSurface,
            null,
            null
        ) ?: run {
            runCatching { pipeline.stop() }
            gpuPipeline = null
            error("VirtualDisplay creation failed.")
        }

        virtualDisplay = created
        resizeHandler = CaptureResizeHandler(
            mode = orientation,
            virtualDisplay = created,
            densityDpi = densityDpi,
            renderer = renderer,
            lockedWidth = width,
            lockedHeight = height
        )
    }

    fun stop() {
        resizeHandler = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { gpuPipeline?.stop() }
        gpuPipeline = null
    }
}
