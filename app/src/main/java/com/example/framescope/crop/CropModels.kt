package com.example.framescope.crop

import android.media.MediaMuxer
import android.net.Uri
import com.example.framescope.capability.OptionSupportInfo
import kotlinx.serialization.Serializable

enum class CropImportSource {
    ALL,
    VIDEO,
    IMAGE,
    FILE
}

enum class CropMediaType {
    IMAGE,
    VIDEO
}

enum class CropAspectRatio(val label: String, val ratio: Float?) {
    FREE("Free", null),
    RATIO_9_16("9:16", 9f / 16f),
    RATIO_16_9("16:9", 16f / 9f),
    RATIO_4_5("4:5", 4f / 5f),
    RATIO_1_1("1:1", 1f),
    RATIO_2_3("2:3", 2f / 3f),
    CUSTOM("Custom", null)
}

enum class CanvasAspectMode(val label: String, val ratio: Float?) {
    ORIGINAL("Original", null),
    RATIO_9_16("9:16", 9f / 16f),
    RATIO_16_9("16:9", 16f / 9f),
    RATIO_4_5("4:5", 4f / 5f),
    RATIO_1_1("1:1", 1f),
    RATIO_2_3("2:3", 2f / 3f),
    CUSTOM("Custom", null)
}

data class CropEditSnapshot(
    val cropRect: NormalizedCropRect = NormalizedCropRect(0f, 0f, 1f, 1f),
    val aspectRatio: CropAspectRatio = CropAspectRatio.FREE,
    val customAspectWidth: String = "4",
    val customAspectHeight: String = "3"
)

enum class VideoBitrateMode {
    CUSTOM,
    ORIGINAL_PRESERVATION
}

enum class ImageExportFormatId {
    PNG,
    JPG,
    ORIGINAL
}

data class ImageExportFormatOption(
    val id: ImageExportFormatId,
    val label: String,
    val extension: String,
    val mimeType: String,
    val supportsTransparency: Boolean,
    val supportInfo: OptionSupportInfo = OptionSupportInfo(isSupported = true)
)

enum class VideoContainerFormat(
    val extension: String,
    val mimeType: String,
    val muxerOutputFormat: Int
) {
    MP4("mp4", "video/mp4", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4),
    WEBM("webm", "video/webm", MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM),
    THREE_GP("3gp", "video/3gpp", MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP)
}

data class VideoExportFormatOption(
    val label: String,
    val container: VideoContainerFormat,
    val isOriginal: Boolean,
    val supportInfo: OptionSupportInfo = OptionSupportInfo(isSupported = true)
)

data class CodecPipelineSupport(
    val isHardwareDecoder: Boolean,
    val isHardwareEncoder: Boolean,
    val decoderName: String?,
    val encoderName: String?,
    val statusText: String
)

data class NormalizedCropRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f
) {
    val widthFraction: Float get() = (right - left).coerceIn(0.02f, 1f)
    val heightFraction: Float get() = (bottom - top).coerceIn(0.02f, 1f)
}

data class ImportedMediaInfo(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val extension: String,
    val mediaType: CropMediaType,
    val rawWidth: Int,
    val rawHeight: Int,
    val rotationDegrees: Int,
    val displayWidth: Int,
    val displayHeight: Int,
    val durationMs: Long = 0L,
    val fps: Int = 30,
    val originalBitrateBps: Long? = null,
    val hasAlpha: Boolean = false,
    val colorStandard: Int? = null,
    val colorRange: Int? = null,
    val colorTransfer: Int? = null,
    val locationLat: Float? = null,
    val locationLon: Float? = null,
    val sourceVideoMime: String? = null,
    val imageExportOptions: List<ImageExportFormatOption> = emptyList(),
    val videoExportOptions: List<VideoExportFormatOption> = emptyList()
)

data class CropExportedItem(
    val filePath: String,
    val fileName: String,
    val mediaType: CropMediaType,
    val formatLabel: String,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val sizeBytes: Long,
    val exportedAtMs: Long
)

sealed interface CropExportState {
    data object Idle : CropExportState
    data class Exporting(val progress: Float, val statusText: String) : CropExportState
    data class Success(
        val outputPath: String,
        val fileName: String,
        val savedToGallery: Boolean,
        val mediaType: CropMediaType
    ) : CropExportState
    data class Error(val message: String) : CropExportState
}

@Serializable
data class CropProjectRecord(
    val id: String,
    val title: String,
    val mediaTypeName: String,
    val sourceUriString: String,
    val localCopyPath: String? = null,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
    val aspectRatioName: String = CropAspectRatio.FREE.name,
    val customAspectWidth: String = "4",
    val customAspectHeight: String = "3",
    val canvasAspectModeName: String = CanvasAspectMode.ORIGINAL.name,
    val customCanvasWidth: String = "4",
    val customCanvasHeight: String = "3",
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val bitrateModeName: String = VideoBitrateMode.CUSTOM.name,
    val customBitrateMbps: Float = 12f,
    val selectedFormatLabel: String? = null,
    val outputWidth: Int = 0,
    val outputHeight: Int = 0,
    val durationMs: Long = 0L,
    val exportedFilePath: String? = null,
    val exportedFormatLabel: String? = null,
    val exportedWidth: Int = 0,
    val exportedHeight: Int = 0,
    val exportedDurationMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val exportedAtMs: Long? = null
) {
    val mediaType: CropMediaType
        get() = runCatching { CropMediaType.valueOf(mediaTypeName) }.getOrDefault(CropMediaType.VIDEO)

    val aspectRatio: CropAspectRatio
        get() = runCatching { CropAspectRatio.valueOf(aspectRatioName) }.getOrDefault(CropAspectRatio.FREE)
}
