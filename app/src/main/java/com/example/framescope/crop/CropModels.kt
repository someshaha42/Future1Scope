package com.example.framescope.crop

import android.net.Uri

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
    ORIGINAL("Original", null),
    RATIO_9_16("9:16", 9f / 16f),
    RATIO_16_9("16:9", 16f / 9f),
    RATIO_4_5("4:5", 4f / 5f),
    RATIO_1_1("1:1", 1f),
    RATIO_2_3("2:3", 2f / 3f),
    CUSTOM("Custom", null)
}

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
    val supportsTransparency: Boolean
)

enum class VideoContainerFormat(
    val extension: String,
    val mimeType: String,
    val muxerOutputFormat: Int
) {
    MP4("mp4", "video/mp4", android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4),
    WEBM("webm", "video/webm", android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM),
    THREE_GP("3gp", "video/3gpp", android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP)
}

data class VideoExportFormatOption(
    val label: String,
    val container: VideoContainerFormat,
    val isOriginal: Boolean
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
