package com.example.framescope.ui.crop

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.framescope.crop.CropAspectRatio
import com.example.framescope.crop.CropExportState
import com.example.framescope.crop.CropMediaInspector
import com.example.framescope.crop.CropMediaType
import com.example.framescope.crop.ImageCropProcessor
import com.example.framescope.crop.ImageExportFormatOption
import com.example.framescope.crop.ImportedMediaInfo
import com.example.framescope.crop.NormalizedCropRect
import com.example.framescope.crop.VideoBitrateMode
import com.example.framescope.crop.VideoCropProcessor
import com.example.framescope.crop.VideoExportFormatOption
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class CropToolUiState(
    val isLoadingMedia: Boolean = false,
    val media: ImportedMediaInfo? = null,
    val previewBitmap: Bitmap? = null,
    val showImportPanel: Boolean = true,
    val cropRect: NormalizedCropRect = NormalizedCropRect(),
    val zoom: Float = 1f,
    val panOffset: Offset = Offset.Zero,
    val aspectRatio: CropAspectRatio = CropAspectRatio.ORIGINAL,
    val customAspectWidth: String = "4",
    val customAspectHeight: String = "3",
    val customAspectError: String? = null,
    val isVideoPlaying: Boolean = false,
    val bitrateMode: VideoBitrateMode = VideoBitrateMode.CUSTOM,
    val customBitrateMbps: Float = 12f,
    val recommendedBitrateMbps: Float = 12f,
    val hasUserAdjustedBitrate: Boolean = false,
    val selectedImageFormat: ImageExportFormatOption? = null,
    val selectedVideoFormat: VideoExportFormatOption? = null,
    val exportState: CropExportState = CropExportState.Idle,
    val message: String? = null
) {
    val outputWidth: Int
        get() {
            val info = media ?: return 0
            val raw = (cropRect.widthFraction * info.displayWidth).roundToInt()
            return if (info.mediaType == CropMediaType.VIDEO) {
                (raw and 1.inv()).coerceIn(16, info.displayWidth)
            } else {
                raw.coerceIn(1, info.displayWidth)
            }
        }

    val outputHeight: Int
        get() {
            val info = media ?: return 0
            val raw = (cropRect.heightFraction * info.displayHeight).roundToInt()
            return if (info.mediaType == CropMediaType.VIDEO) {
                (raw and 1.inv()).coerceIn(16, info.displayHeight)
            } else {
                raw.coerceIn(1, info.displayHeight)
            }
        }

    val activeAspectRatioValue: Float?
        get() = when (aspectRatio) {
            CropAspectRatio.ORIGINAL -> null
            CropAspectRatio.CUSTOM -> {
                val w = customAspectWidth.toFloatOrNull()
                val h = customAspectHeight.toFloatOrNull()
                if (w != null && h != null && w > 0f && h > 0f) w / h else null
            }
            else -> aspectRatio.ratio
        }
}

class CropToolViewModel(
    private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(CropToolUiState())
    val uiState: StateFlow<CropToolUiState> = _uiState.asStateFlow()

    private var exportJob: Job? = null

    fun openImportPanel() {
        _uiState.update { it.copy(showImportPanel = true, isVideoPlaying = false) }
    }

    fun closeImportPanelIfLoaded() {
        if (_uiState.value.media != null) {
            _uiState.update { it.copy(showImportPanel = false) }
        }
    }

    fun importMedia(uri: Uri) {
        exportJob?.cancel()
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingMedia = true,
                    isVideoPlaying = false,
                    exportState = CropExportState.Idle,
                    message = null
                )
            }

            val result = CropMediaInspector.inspect(appContext, uri)
            result.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoadingMedia = false,
                        message = err.message ?: "Unable to import selected file."
                    )
                }
            }.onSuccess { info ->
                val bitmap = if (info.mediaType == CropMediaType.IMAGE) {
                    ImageCropProcessor.loadPreviewBitmap(appContext, info)
                } else {
                    null
                }

                if (info.mediaType == CropMediaType.IMAGE && bitmap == null) {
                    _uiState.update {
                        it.copy(
                            isLoadingMedia = false,
                            message = "Unable to decode selected image."
                        )
                    }
                    return@onSuccess
                }

                val oldBitmap = _uiState.value.previewBitmap
                if (oldBitmap != null && oldBitmap !== bitmap) {
                    runCatching { oldBitmap.recycle() }
                }

                val recommended = if (info.mediaType == CropMediaType.VIDEO) {
                    CropMediaInspector.calculateRecommendedBitrateMbps(
                        outputWidth = info.displayWidth,
                        outputHeight = info.displayHeight,
                        fps = info.fps,
                        sourceVideoMime = info.sourceVideoMime
                    )
                } else {
                    12f
                }

                _uiState.value = CropToolUiState(
                    isLoadingMedia = false,
                    media = info,
                    previewBitmap = bitmap,
                    showImportPanel = false,
                    cropRect = NormalizedCropRect(0f, 0f, 1f, 1f),
                    zoom = 1f,
                    panOffset = Offset.Zero,
                    aspectRatio = CropAspectRatio.ORIGINAL,
                    isVideoPlaying = false,
                    bitrateMode = VideoBitrateMode.CUSTOM,
                    customBitrateMbps = recommended,
                    recommendedBitrateMbps = recommended,
                    hasUserAdjustedBitrate = false,
                    selectedImageFormat = info.imageExportOptions.firstOrNull(),
                    selectedVideoFormat = info.videoExportOptions.firstOrNull(),
                    exportState = CropExportState.Idle
                )
            }
        }
    }

    fun toggleVideoPlayPause() {
        _uiState.update { state ->
            if (state.media?.mediaType != CropMediaType.VIDEO) return@update state
            state.copy(isVideoPlaying = !state.isVideoPlaying)
        }
    }

    fun setVideoPlaying(playing: Boolean) {
        _uiState.update { it.copy(isVideoPlaying = playing) }
    }

    fun updateZoom(newZoom: Float) {
        val clamped = newZoom.coerceIn(1f, 5f)
        _uiState.update { state ->
            val updatedPan = if (clamped <= 1.001f) Offset.Zero else state.panOffset
            state.copy(zoom = clamped, panOffset = updatedPan)
        }
    }

    fun updatePan(newPan: Offset) {
        _uiState.update { it.copy(panOffset = newPan) }
    }

    fun updateCropRect(newCrop: NormalizedCropRect) {
        _uiState.update { state ->
            val sanitized = NormalizedCropRect(
                left = newCrop.left.coerceIn(0f, 0.96f),
                top = newCrop.top.coerceIn(0f, 0.96f),
                right = newCrop.right.coerceIn(0.04f, 1f),
                bottom = newCrop.bottom.coerceIn(0.04f, 1f)
            )
            recalculateAutoBitrateIfNeeded(state.copy(cropRect = sanitized))
        }
    }

    fun selectAspectRatio(ratio: CropAspectRatio) {
        val info = _uiState.value.media ?: return
        if (ratio == CropAspectRatio.CUSTOM) {
            val parsedW = _uiState.value.customAspectWidth.toFloatOrNull()
            val parsedH = _uiState.value.customAspectHeight.toFloatOrNull()
            val targetRatio = if (parsedW != null && parsedH != null && parsedW > 0f && parsedH > 0f) {
                parsedW / parsedH
            } else {
                1f
            }
            val centered = computeCenteredCropRect(info.displayWidth, info.displayHeight, targetRatio)
            _uiState.update { state ->
                recalculateAutoBitrateIfNeeded(
                    state.copy(
                        aspectRatio = CropAspectRatio.CUSTOM,
                        cropRect = centered,
                        customAspectError = null
                    )
                )
            }
            return
        }

        val centered = if (ratio == CropAspectRatio.ORIGINAL) {
            NormalizedCropRect(0f, 0f, 1f, 1f)
        } else {
            computeCenteredCropRect(info.displayWidth, info.displayHeight, ratio.ratio!!)
        }

        _uiState.update { state ->
            recalculateAutoBitrateIfNeeded(
                state.copy(
                    aspectRatio = ratio,
                    cropRect = centered,
                    customAspectError = null
                )
            )
        }
    }

    fun updateCustomAspectRatio(widthStr: String, heightStr: String) {
        val cleanW = widthStr.filter { it.isDigit() || it == '.' }
        val cleanH = heightStr.filter { it.isDigit() || it == '.' }
        val wVal = cleanW.toFloatOrNull()
        val hVal = cleanH.toFloatOrNull()
        val info = _uiState.value.media

        if (wVal == null || hVal == null || wVal <= 0f || hVal <= 0f) {
            _uiState.update {
                it.copy(
                    aspectRatio = CropAspectRatio.CUSTOM,
                    customAspectWidth = cleanW,
                    customAspectHeight = cleanH,
                    customAspectError = "Enter positive ratio numbers"
                )
            }
            return
        }

        val targetRatio = (wVal / hVal).coerceIn(0.05f, 20f)
        val centered = if (info != null) {
            computeCenteredCropRect(info.displayWidth, info.displayHeight, targetRatio)
        } else {
            NormalizedCropRect()
        }

        _uiState.update { state ->
            recalculateAutoBitrateIfNeeded(
                state.copy(
                    aspectRatio = CropAspectRatio.CUSTOM,
                    customAspectWidth = cleanW,
                    customAspectHeight = cleanH,
                    customAspectError = null,
                    cropRect = centered
                )
            )
        }
    }

    fun selectBitrateMode(mode: VideoBitrateMode) {
        val media = _uiState.value.media ?: return
        if (mode == VideoBitrateMode.ORIGINAL_PRESERVATION &&
            (media.originalBitrateBps == null || media.originalBitrateBps <= 0L)
        ) {
            _uiState.update {
                it.copy(
                    message = "Original bitrate metadata is unavailable for this video. Custom mode remains active."
                )
            }
            return
        }
        _uiState.update { it.copy(bitrateMode = mode) }
    }

    fun setManualCustomBitrate(mbps: Float) {
        val clamped = mbps.coerceIn(0f, 50f)
        _uiState.update {
            it.copy(
                bitrateMode = VideoBitrateMode.CUSTOM,
                customBitrateMbps = clamped,
                hasUserAdjustedBitrate = true
            )
        }
    }

    fun selectImageFormat(option: ImageExportFormatOption) {
        _uiState.update { it.copy(selectedImageFormat = option) }
    }

    fun selectVideoFormat(option: VideoExportFormatOption) {
        _uiState.update { it.copy(selectedVideoFormat = option) }
    }

    fun startExport() {
        val state = _uiState.value
        val media = state.media ?: return
        if (state.exportState is CropExportState.Exporting) return

        _uiState.update { it.copy(isVideoPlaying = false) }

        exportJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    exportState = CropExportState.Exporting(0.02f, "Starting export…"),
                    message = null
                )
            }

            val result = when (media.mediaType) {
                CropMediaType.IMAGE -> {
                    val format = state.selectedImageFormat ?: media.imageExportOptions.first()
                    _uiState.update {
                        it.copy(exportState = CropExportState.Exporting(0.35f, "Cropping image…"))
                    }
                    ImageCropProcessor.exportCroppedImage(
                        context = appContext,
                        media = media,
                        cropRect = state.cropRect,
                        formatOption = format
                    )
                }
                CropMediaType.VIDEO -> {
                    val format = state.selectedVideoFormat ?: media.videoExportOptions.firstOrNull()
                    if (format == null) {
                        CropExportState.Error("No compatible video export format found on this device.")
                    } else {
                        val targetBps = when (state.bitrateMode) {
                            VideoBitrateMode.ORIGINAL_PRESERVATION -> {
                                val orig = media.originalBitrateBps
                                if (orig == null || orig <= 0L) {
                                    _uiState.update {
                                        it.copy(
                                            exportState = CropExportState.Error(
                                                "Original bitrate metadata is unavailable for this file. Switch to Custom bitrate mode."
                                            )
                                        )
                                    }
                                    return@launch
                                }
                                orig.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                            }
                            VideoBitrateMode.CUSTOM -> {
                                // Never pass 0 bps directly to MediaCodec
                                (state.customBitrateMbps * 1_000_000f).roundToInt()
                                    .coerceAtLeast(250_000)
                            }
                        }

                        VideoCropProcessor.exportCroppedVideo(
                            context = appContext,
                            media = media,
                            cropRect = state.cropRect,
                            formatOption = format,
                            targetBitrateBps = targetBps,
                            onProgress = { progress, text ->
                                _uiState.update {
                                    it.copy(exportState = CropExportState.Exporting(progress, text))
                                }
                            }
                        )
                    }
                }
            }

            _uiState.update { it.copy(exportState = result) }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
        _uiState.update { it.copy(exportState = CropExportState.Idle) }
    }

    fun dismissExportState() {
        _uiState.update { it.copy(exportState = CropExportState.Idle) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun recalculateAutoBitrateIfNeeded(state: CropToolUiState): CropToolUiState {
        val media = state.media ?: return state
        if (media.mediaType != CropMediaType.VIDEO) return state

        val recommended = CropMediaInspector.calculateRecommendedBitrateMbps(
            outputWidth = state.outputWidth,
            outputHeight = state.outputHeight,
            fps = media.fps,
            sourceVideoMime = media.sourceVideoMime
        )
        return if (state.hasUserAdjustedBitrate) {
            state.copy(recommendedBitrateMbps = recommended)
        } else {
            state.copy(
                recommendedBitrateMbps = recommended,
                customBitrateMbps = recommended
            )
        }
    }

    private fun computeCenteredCropRect(
        dispW: Int,
        dispH: Int,
        targetRatio: Float
    ): NormalizedCropRect {
        val srcRatio = dispW.toFloat() / dispH.toFloat().coerceAtLeast(1f)
        val (wFrac, hFrac) = if (targetRatio >= srcRatio) {
            1f to (srcRatio / targetRatio).coerceIn(0.05f, 1f)
        } else {
            (targetRatio / srcRatio).coerceIn(0.05f, 1f) to 1f
        }
        val left = (1f - wFrac) / 2f
        val top = (1f - hFrac) / 2f
        return NormalizedCropRect(
            left = left,
            top = top,
            right = left + wFrac,
            bottom = top + hFrac
        )
    }

    override fun onCleared() {
        exportJob?.cancel()
        runCatching { _uiState.value.previewBitmap?.recycle() }
        super.onCleared()
    }

    class Factory(private val appContext: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CropToolViewModel(appContext.applicationContext) as T
        }
    }
}
