package com.example.framescope.capability

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import com.example.framescope.crop.CanvasAspectMode
import com.example.framescope.crop.CropAspectRatio
import com.example.framescope.crop.CropImportSource
import com.example.framescope.crop.CropMediaType
import com.example.framescope.crop.ImageExportFormatId
import com.example.framescope.crop.ImageExportFormatOption
import com.example.framescope.crop.ImportedMediaInfo
import com.example.framescope.crop.VideoBitrateMode
import com.example.framescope.crop.VideoContainerFormat
import java.util.Locale

data class OptionSupportInfo(
    val isSupported: Boolean,
    val detailText: String? = null,
    val alternativeName: String? = null
)

data class CropCapabilitiesSnapshot(
    val cropToolSupport: OptionSupportInfo = OptionSupportInfo(true, "MediaCodec & ImageDecoder"),
    val importSupport: Map<CropImportSource, OptionSupportInfo> = emptyMap(),
    val canvasRatioSupport: Map<CanvasAspectMode, OptionSupportInfo> = emptyMap()
)

object DeviceCapabilityEngine {

    private val cachedCodecInfos: Array<MediaCodecInfo> by lazy {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
    }

    fun probeCropCapabilities(context: Context): CropCapabilitiesSnapshot {
        val pm = context.packageManager
        val hasAnyVideoCodec = cachedCodecInfos.any { info ->
            info.isEncoder && !info.isAlias && (
                info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) } ||
                    info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) } ||
                    info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_MPEG4, ignoreCase = true) }
                )
        }

        val pickIntent = Intent(MediaStore.ACTION_PICK_IMAGES)
        val hasPhotoPicker = runCatching {
            ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context) ||
                pm.queryIntentActivities(pickIntent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty() ||
                pm.queryIntentActivities(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), 0).isNotEmpty()
        }.getOrDefault(true)

        val openDocIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        val hasOpenDocument = runCatching {
            pm.queryIntentActivities(openDocIntent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty() ||
                pm.queryIntentActivities(Intent(Intent.ACTION_GET_CONTENT).setType("*/*"), 0).isNotEmpty()
        }.getOrDefault(true)

        val importSupport = mapOf(
            CropImportSource.ALL to if (hasPhotoPicker) {
                OptionSupportInfo(true)
            } else {
                OptionSupportInfo(false, alternativeName = "Pick From File (SAF)")
            },
            CropImportSource.VIDEO to if (hasPhotoPicker) {
                OptionSupportInfo(true)
            } else {
                OptionSupportInfo(false, alternativeName = "Pick From File (SAF)")
            },
            CropImportSource.IMAGE to if (hasPhotoPicker) {
                OptionSupportInfo(true)
            } else {
                OptionSupportInfo(false, alternativeName = "Pick From File (SAF)")
            },
            CropImportSource.FILE to if (hasOpenDocument) {
                OptionSupportInfo(true)
            } else {
                OptionSupportInfo(false, alternativeName = "System Gallery Picker")
            }
        )

        val cropToolSupport = if (hasAnyVideoCodec) {
            OptionSupportInfo(true, detailText = "Photo & Video Supported")
        } else {
            OptionSupportInfo(
                isSupported = false,
                alternativeName = "Image Crop & Software Video"
            )
        }

        val canvasSupport = CanvasAspectMode.entries.associateWith {
            OptionSupportInfo(true)
        }

        return CropCapabilitiesSnapshot(
            cropToolSupport = cropToolSupport,
            importSupport = importSupport,
            canvasRatioSupport = canvasSupport
        )
    }

    fun probeAspectRatiosForMedia(
        media: ImportedMediaInfo?,
        customWidth: String,
        customHeight: String
    ): Map<CropAspectRatio, OptionSupportInfo> {
        if (media == null) {
            return CropAspectRatio.entries.associateWith { OptionSupportInfo(true) }
        }
        val minSide = if (media.mediaType == CropMediaType.VIDEO) 16 else 1
        return CropAspectRatio.entries.associateWith { ratio ->
            when (ratio) {
                CropAspectRatio.FREE -> OptionSupportInfo(true)
                CropAspectRatio.CUSTOM -> {
                    val w = customWidth.toFloatOrNull()
                    val h = customHeight.toFloatOrNull()
                    if (w == null || h == null || w <= 0f || h <= 0f) {
                        OptionSupportInfo(
                            isSupported = false,
                            alternativeName = "Free (Safe Crop Bounds)"
                        )
                    } else {
                        val target = (w / h).coerceIn(0.05f, 20f)
                        val validDims = canFitAspectInMedia(media.displayWidth, media.displayHeight, target, minSide)
                        if (validDims) {
                            OptionSupportInfo(true)
                        } else {
                            OptionSupportInfo(
                                isSupported = false,
                                alternativeName = "Even-Aligned Safe Bounds"
                            )
                        }
                    }
                }
                else -> {
                    val target = ratio.ratio ?: 1f
                    val validDims = canFitAspectInMedia(media.displayWidth, media.displayHeight, target, minSide)
                    if (validDims) {
                        OptionSupportInfo(true)
                    } else {
                        OptionSupportInfo(
                            isSupported = false,
                            alternativeName = "Free (Safe Crop Bounds)"
                        )
                    }
                }
            }
        }
    }

    private fun canFitAspectInMedia(
        mediaW: Int,
        mediaH: Int,
        targetRatio: Float,
        minSide: Int
    ): Boolean {
        if (mediaW < minSide || mediaH < minSide) return false
        val srcRatio = mediaW.toFloat() / mediaH.toFloat()
        val (cropW, cropH) = if (srcRatio > targetRatio) {
            (mediaH * targetRatio) to mediaH.toFloat()
        } else {
            mediaW.toFloat() to (mediaW / targetRatio)
        }
        return cropW >= minSide && cropH >= minSide
    }

    fun probeCropVideoBitrateModes(
        media: ImportedMediaInfo?,
        customBitrateMbps: Float,
        container: VideoContainerFormat?
    ): Map<VideoBitrateMode, OptionSupportInfo> {
        val range = findPrimaryEncoderBitrateRange(cachedCodecInfos, media?.sourceVideoMime, container ?: VideoContainerFormat.MP4)
        val origBps = media?.originalBitrateBps
        val origInfo = when {
            origBps == null || origBps <= 0L -> OptionSupportInfo(
                isSupported = true,
                detailText = "Auto Original"
            )
            origBps in range.first.toLong()..range.second.toLong() -> OptionSupportInfo(
                isSupported = true,
                detailText = String.format(Locale.getDefault(), "%.1f Mbps", origBps / 1_000_000.0)
            )
            else -> {
                val clampedMbps = origBps.coerceIn(range.first.toLong(), range.second.toLong()) / 1_000_000.0
                OptionSupportInfo(
                    isSupported = true,
                    detailText = String.format(Locale.getDefault(), "%.1f Mbps (Codec Clamped)", clampedMbps)
                )
            }
        }

        return mapOf(
            VideoBitrateMode.ORIGINAL_PRESERVATION to origInfo
        )
    }

    private fun findPrimaryEncoderBitrateRange(
        codecInfos: Array<MediaCodecInfo>,
        sourceVideoMime: String?,
        container: VideoContainerFormat?
    ): Pair<Int, Int> {
        val mimes = when (container ?: VideoContainerFormat.MP4) {
            VideoContainerFormat.MP4 -> {
                if (sourceVideoMime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)) {
                    listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)
                } else {
                    listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)
                }
            }
            VideoContainerFormat.WEBM -> listOf(MediaFormat.MIMETYPE_VIDEO_VP9, MediaFormat.MIMETYPE_VIDEO_VP8)
            VideoContainerFormat.THREE_GP -> listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_H263)
        }

        for (mime in mimes) {
            val infos = codecInfos.filter { info ->
                !info.isAlias && info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }.sortedByDescending { it.isHardwareAccelerated }

            for (info in infos) {
                val videoCaps = runCatching { info.getCapabilitiesForType(mime).videoCapabilities }.getOrNull()
                    ?: continue
                val low = videoCaps.bitrateRange.lower.coerceAtLeast(250_000)
                val high = videoCaps.bitrateRange.upper.coerceAtLeast(low)
                return low to high
            }
        }
        return 250_000 to 50_000_000
    }

    fun probeImageExportSupport(
        option: ImageExportFormatOption,
        sourceHasAlpha: Boolean
    ): OptionSupportInfo {
        return when (option.id) {
            ImageExportFormatId.PNG -> OptionSupportInfo(true, detailText = "Lossless")
            ImageExportFormatId.JPG -> OptionSupportInfo(true, detailText = "Compatible")
            ImageExportFormatId.ORIGINAL -> {
                val ext = option.extension.lowercase(Locale.US)
                if (ext in setOf("png", "jpg", "jpeg", "webp")) {
                    OptionSupportInfo(true, detailText = "Native Encode")
                } else {
                    OptionSupportInfo(
                        isSupported = false,
                        detailText = "Decode-only",
                        alternativeName = if (sourceHasAlpha) "PNG (Lossless)" else "JPG (95% Quality)"
                    )
                }
            }
        }
    }

    fun probeVideoExportSupport(
        container: VideoContainerFormat,
        fallbackLabel: String
    ): OptionSupportInfo {
        val codecInfos = cachedCodecInfos
        val hasSurfaceForContainer = when (container) {
            VideoContainerFormat.MP4 -> hasAnyVideoEncoder(
                codecInfos, MediaFormat.MIMETYPE_VIDEO_AVC, requireSurface = true
            ) || hasAnyVideoEncoder(
                codecInfos, MediaFormat.MIMETYPE_VIDEO_HEVC, requireSurface = true
            )
            VideoContainerFormat.WEBM -> hasAnyVideoEncoder(
                codecInfos, MediaFormat.MIMETYPE_VIDEO_VP9, requireSurface = true
            ) || hasAnyVideoEncoder(
                codecInfos, MediaFormat.MIMETYPE_VIDEO_VP8, requireSurface = true
            )
            VideoContainerFormat.THREE_GP -> hasAnyVideoEncoder(
                codecInfos, MediaFormat.MIMETYPE_VIDEO_AVC, requireSurface = true
            ) || hasAnyVideoEncoder(
                codecInfos, MediaFormat.MIMETYPE_VIDEO_H263, requireSurface = true
            )
        }

        return if (hasSurfaceForContainer) {
            OptionSupportInfo(true)
        } else {
            OptionSupportInfo(
                isSupported = false,
                alternativeName = fallbackLabel
            )
        }
    }

    private fun hasAnyVideoEncoder(
        codecInfos: Array<MediaCodecInfo>,
        mime: String,
        requireSurface: Boolean = true
    ): Boolean {
        return codecInfos.any { info ->
            if (!info.isEncoder || info.isAlias || !info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) {
                return@any false
            }
            if (!requireSurface) return@any true
            runCatching {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in
                    info.getCapabilitiesForType(mime).colorFormats
            }.getOrDefault(false)
        }
    }
}
