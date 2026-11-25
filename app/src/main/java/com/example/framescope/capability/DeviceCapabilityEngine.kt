package com.example.framescope.capability

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.provider.MediaStore
import com.example.framescope.crop.CanvasAspectMode
import com.example.framescope.crop.CropAspectRatio
import com.example.framescope.crop.CropImportSource
import com.example.framescope.crop.CropMediaType
import com.example.framescope.crop.ImageExportFormatId
import com.example.framescope.crop.ImageExportFormatOption
import com.example.framescope.crop.ImportedMediaInfo
import com.example.framescope.crop.VideoBitrateMode
import com.example.framescope.crop.VideoContainerFormat
import com.example.framescope.crop.VideoExportFormatOption
import com.example.framescope.data.AudioSourceMode
import com.example.framescope.encoding.EncodingSpec
import com.example.framescope.encoding.EncodingTab
import com.example.framescope.encoding.RecordingOrientation
import com.example.framescope.encoding.VideoCodecCapability
import com.example.framescope.encoding.getOutputDimensions
import java.util.Locale

data class OptionSupportInfo(
    val isSupported: Boolean,
    val detailText: String? = null,
    val alternativeName: String? = null
)

enum class EffectiveAudioStrategy {
    INTERNAL_AUDIO,
    MICROPHONE,
    VIDEO_ONLY
}

data class HomeCapabilitiesSnapshot(
    val codecCapabilities: Map<EncodingTab, VideoCodecCapability> = emptyMap(),
    val codecSupport: Map<EncodingTab, OptionSupportInfo> = emptyMap(),
    val orientationSupport: Map<RecordingOrientation, OptionSupportInfo> = emptyMap(),
    val audioSourceSupport: Map<AudioSourceMode, OptionSupportInfo> = emptyMap(),
    val effectiveVideoCodec: EncodingTab = EncodingTab.H264,
    val effectiveVideoAlternativeLabel: String = EncodingTab.H264.displayName,
    val effectiveAudioStrategy: EffectiveAudioStrategy = EffectiveAudioStrategy.INTERNAL_AUDIO,
    val resolvedAudioSampleRate: Int = EncodingSpec.AUDIO_SAMPLE_RATE,
    val encodingSpecSupport: Map<String, OptionSupportInfo> = emptyMap()
)

data class CropCapabilitiesSnapshot(
    val cropToolSupport: OptionSupportInfo = OptionSupportInfo(true, "MediaCodec & ImageDecoder"),
    val importSupport: Map<CropImportSource, OptionSupportInfo> = emptyMap(),
    val canvasRatioSupport: Map<CanvasAspectMode, OptionSupportInfo> = emptyMap()
)

object DeviceCapabilityEngine {

    fun probeHomeCapabilities(
        context: Context,
        screenWidth: Int,
        screenHeight: Int,
        selectedVideoCodec: EncodingTab
    ): HomeCapabilitiesSnapshot {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val rawCodecs = EncodingSpec.detectAllVideoCodecs()

        val hasFallbackMpeg4 = hasAnyVideoEncoder(codecList, MediaFormat.MIMETYPE_VIDEO_MPEG4)
        val hasFallbackAvcSw = hasAnyVideoEncoder(codecList, MediaFormat.MIMETYPE_VIDEO_AVC, requireSurface = false)
        val minimalVideoFallbackLabel = when {
            hasFallbackAvcSw -> "H.264 Software Safe (720p)"
            hasFallbackMpeg4 -> "MPEG-4 Visual Safe (720p)"
            else -> "Device Safe Compatibility Mode"
        }

        val codecSupport = EncodingTab.entries.associateWith { tab ->
            val cap = rawCodecs[tab]
            if (cap?.isSupported == true) {
                OptionSupportInfo(
                    isSupported = true,
                    detailText = cap.statusText
                )
            } else {
                val peer = EncodingTab.entries.firstOrNull { it != tab && rawCodecs[it]?.isSupported == true }
                OptionSupportInfo(
                    isSupported = false,
                    detailText = "Unsupported",
                    alternativeName = peer?.displayName ?: minimalVideoFallbackLabel
                )
            }
        }

        val effectiveVideoTab = if (rawCodecs[selectedVideoCodec]?.isSupported == true) {
            selectedVideoCodec
        } else {
            EncodingTab.entries.firstOrNull { rawCodecs[it]?.isSupported == true } ?: EncodingTab.H264
        }
        val effectiveVideoLabel = if (rawCodecs[effectiveVideoTab]?.isSupported == true) {
            effectiveVideoTab.displayName
        } else {
            minimalVideoFallbackLabel
        }

        val orientationSupport = probeOrientations(
            codecList = codecList,
            activeTab = effectiveVideoTab,
            activeCap = rawCodecs[effectiveVideoTab],
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )

        val audioProbe = probeAudioInternal(context, codecList)

        val specSupport = buildEncodingSpecSupport(
            codecList = codecList,
            activeTab = effectiveVideoTab,
            activeCap = rawCodecs[effectiveVideoTab],
            audioProbe = audioProbe,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )

        return HomeCapabilitiesSnapshot(
            codecCapabilities = rawCodecs,
            codecSupport = codecSupport,
            orientationSupport = orientationSupport,
            audioSourceSupport = audioProbe.supportMap,
            effectiveVideoCodec = effectiveVideoTab,
            effectiveVideoAlternativeLabel = effectiveVideoLabel,
            effectiveAudioStrategy = audioProbe.defaultStrategy,
            resolvedAudioSampleRate = audioProbe.bestSampleRate,
            encodingSpecSupport = specSupport
        )
    }

    private fun probeOrientations(
        codecList: MediaCodecList,
        activeTab: EncodingTab,
        activeCap: VideoCodecCapability?,
        screenWidth: Int,
        screenHeight: Int
    ): Map<RecordingOrientation, OptionSupportInfo> {
        val codecInfo = activeCap?.codecName?.let { name ->
            codecList.codecInfos.firstOrNull { it.name == name }
        }
        val videoCaps = codecInfo?.let { info ->
            runCatching { info.getCapabilitiesForType(activeTab.mimeType).videoCapabilities }.getOrNull()
        }

        fun supportsExactOrientation(orientation: RecordingOrientation): Boolean {
            if (videoCaps == null) return true
            val dims = getOutputDimensions(orientation, screenWidth, screenHeight)
            return videoCaps.isSizeSupported(dims.width, dims.height) ||
                (dims.width <= videoCaps.supportedWidths.upper &&
                    dims.height <= videoCaps.supportedHeights.upper &&
                    dims.width >= videoCaps.supportedWidths.lower &&
                    dims.height >= videoCaps.supportedHeights.lower)
        }

        val portraitSupported = supportsExactOrientation(RecordingOrientation.PORTRAIT)
        val landscapeSupported = supportsExactOrientation(RecordingOrientation.LANDSCAPE)

        val portraitInfo = if (portraitSupported) {
            OptionSupportInfo(isSupported = true, detailText = "Native Portrait Fit")
        } else {
            OptionSupportInfo(
                isSupported = false,
                detailText = "Size constrained",
                alternativeName = if (landscapeSupported) "Landscape" else "Auto-Scaled Portrait"
            )
        }

        val landscapeInfo = if (landscapeSupported) {
            OptionSupportInfo(isSupported = true, detailText = "Native Landscape Fit")
        } else {
            OptionSupportInfo(
                isSupported = false,
                detailText = "Size constrained",
                alternativeName = if (portraitSupported) "Portrait" else "Auto-Scaled Landscape"
            )
        }

        return mapOf(
            RecordingOrientation.PORTRAIT to portraitInfo,
            RecordingOrientation.LANDSCAPE to landscapeInfo
        )
    }

    private data class AudioProbeResult(
        val supportMap: Map<AudioSourceMode, OptionSupportInfo>,
        val hasAacEncoder: Boolean,
        val supports48kHz: Boolean,
        val bestSampleRate: Int,
        val defaultStrategy: EffectiveAudioStrategy
    )

    private fun probeAudioInternal(
        context: Context,
        codecList: MediaCodecList
    ): AudioProbeResult {
        val hasAacEncoder = codecList.codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(EncodingSpec.AUDIO_MIME, ignoreCase = true) }
        }

        val candidateRates = listOf(48_000, 44_100, 16_000)
        val supportedRates = candidateRates.filter { rate ->
            runCatching {
                AudioRecord.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ) > 0
            }.getOrDefault(false)
        }
        val supports48k = 48_000 in supportedRates
        val bestRate = supportedRates.firstOrNull() ?: 44_100
        val hasPcmCapture = supportedRates.isNotEmpty()

        val pm = context.packageManager
        val hasMicFeature = runCatching { pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) }
            .getOrDefault(true)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val hasMicInputDevice = runCatching {
            audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS)?.isNotEmpty() == true
        }.getOrDefault(hasMicFeature)

        val internalSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            hasAacEncoder &&
            hasPcmCapture

        val micSupported = (hasMicFeature || hasMicInputDevice) &&
            hasAacEncoder &&
            hasPcmCapture

        val internalInfo = if (internalSupported) {
            OptionSupportInfo(
                isSupported = true,
                detailText = if (supports48k) "48 kHz AAC" else "${bestRate / 1000} kHz AAC"
            )
        } else {
            OptionSupportInfo(
                isSupported = false,
                detailText = "Unavailable",
                alternativeName = if (micSupported) "Microphone" else "Video-Only (Muted Audio)"
            )
        }

        val micInfo = if (micSupported) {
            OptionSupportInfo(
                isSupported = true,
                detailText = if (supports48k) "48 kHz AAC" else "${bestRate / 1000} kHz AAC"
            )
        } else {
            OptionSupportInfo(
                isSupported = false,
                detailText = "No mic hardware",
                alternativeName = if (internalSupported) "Internal Audio" else "Video-Only (Muted Audio)"
            )
        }

        val defaultStrategy = when {
            internalSupported -> EffectiveAudioStrategy.INTERNAL_AUDIO
            micSupported -> EffectiveAudioStrategy.MICROPHONE
            else -> EffectiveAudioStrategy.VIDEO_ONLY
        }

        return AudioProbeResult(
            supportMap = mapOf(
                AudioSourceMode.INTERNAL_AUDIO to internalInfo,
                AudioSourceMode.MICROPHONE to micInfo
            ),
            hasAacEncoder = hasAacEncoder,
            supports48kHz = supports48k,
            bestSampleRate = bestRate,
            defaultStrategy = defaultStrategy
        )
    }

    fun resolveAudioExecutionStrategy(
        context: Context,
        requested: AudioSourceMode
    ): Pair<EffectiveAudioStrategy, Int> {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val probe = probeAudioInternal(context, list)
        val requestedSupported = probe.supportMap[requested]?.isSupported == true
        val strategy = when {
            requestedSupported -> when (requested) {
                AudioSourceMode.INTERNAL_AUDIO -> EffectiveAudioStrategy.INTERNAL_AUDIO
                AudioSourceMode.MICROPHONE -> EffectiveAudioStrategy.MICROPHONE
            }
            probe.supportMap[AudioSourceMode.MICROPHONE]?.isSupported == true -> EffectiveAudioStrategy.MICROPHONE
            probe.supportMap[AudioSourceMode.INTERNAL_AUDIO]?.isSupported == true -> EffectiveAudioStrategy.INTERNAL_AUDIO
            else -> EffectiveAudioStrategy.VIDEO_ONLY
        }
        return strategy to probe.bestSampleRate
    }

    private fun buildEncodingSpecSupport(
        codecList: MediaCodecList,
        activeTab: EncodingTab,
        activeCap: VideoCodecCapability?,
        audioProbe: AudioProbeResult,
        screenWidth: Int,
        screenHeight: Int
    ): Map<String, OptionSupportInfo> {
        val codecInfo = activeCap?.codecName?.let { name ->
            codecList.codecInfos.firstOrNull { it.name == name }
        }
        val caps = codecInfo?.let { info ->
            runCatching { info.getCapabilitiesForType(activeTab.mimeType) }.getOrNull()
        }
        val encCaps = caps?.encoderCapabilities
        val videoCaps = caps?.videoCapabilities

        val supportsVbr = encCaps?.isBitrateModeSupported(
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
        ) == true
        val supportsCbr = encCaps?.isBitrateModeSupported(
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
        ) == true

        val supportsSurface = caps != null &&
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in caps.colorFormats
        val supportsYuv420 = caps != null && (
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in caps.colorFormats ||
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar in caps.colorFormats ||
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar in caps.colorFormats ||
                supportsSurface
            )

        val portraitDims = getOutputDimensions(RecordingOrientation.PORTRAIT, screenWidth, screenHeight)
        val supports60Fps = videoCaps?.let { vc ->
            runCatching {
                val range = vc.getSupportedFrameRatesFor(portraitDims.width, portraitDims.height)
                range.upper >= 58.0
            }.getOrDefault(vc.supportedFrameRates.upper >= 58)
        } ?: false

        val isHw = activeCap?.isHardwareAccelerated == true

        val audioSpecSupport = when {
            audioProbe.hasAacEncoder && audioProbe.supports48kHz -> OptionSupportInfo(
                isSupported = true,
                detailText = "48 kHz AAC-LC"
            )
            audioProbe.hasAacEncoder -> OptionSupportInfo(
                isSupported = false,
                alternativeName = "AAC-LC ${audioProbe.bestSampleRate / 1000} kHz"
            )
            else -> OptionSupportInfo(
                isSupported = false,
                alternativeName = "Video-Only Safe Track"
            )
        }

        return mapOf(
            "Bitrate Mode" to if (supportsVbr) {
                OptionSupportInfo(true)
            } else {
                OptionSupportInfo(
                    isSupported = false,
                    alternativeName = if (supportsCbr) "CBR (Constant Bitrate)" else "Default Codec Bitrate"
                )
            },
            "Color Depth" to if (supportsYuv420) {
                OptionSupportInfo(true)
            } else {
                OptionSupportInfo(false, alternativeName = "Flexible YUV Safe Format")
            },
            "Frame Rate (FPS)" to if (supports60Fps) {
                OptionSupportInfo(true)
            } else {
                OptionSupportInfo(false, alternativeName = "Adaptive 30 FPS Safe Cap")
            },
            "Color Standard" to if (isHw) {
                OptionSupportInfo(true)
            } else {
                OptionSupportInfo(false, alternativeName = "Default SDR Color Space")
            },
            "Output Container" to OptionSupportInfo(true),
            "Rendering Pipeline" to if (supportsSurface && isHw) {
                OptionSupportInfo(true)
            } else if (supportsSurface) {
                OptionSupportInfo(false, alternativeName = "Software Surface Callback")
            } else {
                OptionSupportInfo(false, alternativeName = "Software Buffer Safe Mode")
            },
            "Keyframe Interval (GOP)" to OptionSupportInfo(true),
            "Color Range" to if (isHw) {
                OptionSupportInfo(true)
            } else {
                OptionSupportInfo(false, alternativeName = "Codec Default Color Range")
            },
            "Audio Format" to audioSpecSupport,
            "Chroma Subsampling" to if (supportsYuv420) {
                OptionSupportInfo(true)
            } else {
                OptionSupportInfo(false, alternativeName = "Auto YUV420 Alignment")
            }
        )
    }

    fun probeCropCapabilities(context: Context): CropCapabilitiesSnapshot {
        val pm = context.packageManager
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val hasAnyVideoCodec = list.codecInfos.any { info ->
            info.isEncoder && (
                info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) } ||
                    info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) } ||
                    info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_MPEG4, ignoreCase = true) }
                )
        }

        val pickIntent = Intent(MediaStore.ACTION_PICK_IMAGES)
        val hasPhotoPicker = runCatching {
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
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val range = findPrimaryEncoderBitrateRange(list, media?.sourceVideoMime, container)
        val lowerMbps = (range.first / 1_000_000f).coerceAtLeast(0.25f)
        val upperMbps = (range.second / 1_000_000f).coerceAtLeast(lowerMbps)

        val customSupported = customBitrateMbps in 0.25f..upperMbps
        val customInfo = if (customSupported) {
            OptionSupportInfo(true)
        } else {
            val clamped = customBitrateMbps.coerceIn(lowerMbps, upperMbps)
            OptionSupportInfo(
                isSupported = false,
                alternativeName = String.format(Locale.US, "%.1f Mbps Encoder Safe", clamped)
            )
        }

        val origBps = media?.originalBitrateBps
        val origSupported = origBps != null &&
            origBps > 0L &&
            origBps in range.first.toLong()..range.second.toLong()

        val origInfo = if (origSupported) {
            OptionSupportInfo(true)
        } else {
            val reason = if (origBps == null || origBps <= 0L) "No metadata" else "Out of range"
            OptionSupportInfo(
                isSupported = false,
                detailText = reason,
                alternativeName = String.format(Locale.US, "Custom (%.1f Mbps)", customBitrateMbps.coerceIn(0.25f, upperMbps))
            )
        }

        return mapOf(
            VideoBitrateMode.CUSTOM to customInfo,
            VideoBitrateMode.ORIGINAL_PRESERVATION to origInfo
        )
    }

    private fun findPrimaryEncoderBitrateRange(
        list: MediaCodecList,
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
            val infos = list.codecInfos.filter { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
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
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val hasSurfaceForContainer = when (container) {
            VideoContainerFormat.MP4 -> hasAnyVideoEncoder(
                list, MediaFormat.MIMETYPE_VIDEO_AVC, requireSurface = true
            ) || hasAnyVideoEncoder(
                list, MediaFormat.MIMETYPE_VIDEO_HEVC, requireSurface = true
            )
            VideoContainerFormat.WEBM -> hasAnyVideoEncoder(
                list, MediaFormat.MIMETYPE_VIDEO_VP9, requireSurface = true
            ) || hasAnyVideoEncoder(
                list, MediaFormat.MIMETYPE_VIDEO_VP8, requireSurface = true
            )
            VideoContainerFormat.THREE_GP -> hasAnyVideoEncoder(
                list, MediaFormat.MIMETYPE_VIDEO_AVC, requireSurface = true
            ) || hasAnyVideoEncoder(
                list, MediaFormat.MIMETYPE_VIDEO_H263, requireSurface = true
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
        list: MediaCodecList,
        mime: String,
        requireSurface: Boolean = true
    ): Boolean {
        return list.codecInfos.any { info ->
            if (!info.isEncoder || !info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) {
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
