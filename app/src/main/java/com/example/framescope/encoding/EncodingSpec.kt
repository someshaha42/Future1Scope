package com.example.framescope.encoding

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import kotlin.math.roundToInt

enum class EncodingTab(
    val mimeType: String,
    val displayName: String,
    val shortName: String,
    val defaultBitrateMbps: Int
) {
    H264(
        mimeType = MediaFormat.MIMETYPE_VIDEO_AVC,
        displayName = "H.264 (AVC)",
        shortName = "H.264",
        defaultBitrateMbps = 45
    ),
    H265(
        mimeType = MediaFormat.MIMETYPE_VIDEO_HEVC,
        displayName = "H.265 (HEVC)",
        shortName = "H.265",
        defaultBitrateMbps = 30
    )
}

data class VideoCodecCapability(
    val tab: EncodingTab,
    val isSupported: Boolean,
    val isHardwareAccelerated: Boolean,
    val codecName: String?,
    val statusText: String
)

data class EncoderValidationReport(
    val codecName: String,
    val supportsYuv420Surface: Boolean,
    val supportsVbr: Boolean,
    val supportedBitrateRangeMbps: String,
    val isCompatible: Boolean,
    val isHardwareAccelerated: Boolean = true
)

data class ResolvedVideoConfig(
    val tab: EncodingTab,
    val codecName: String,
    val isHardwareAccelerated: Boolean,
    val width: Int,
    val height: Int,
    val bitrateBps: Int,
    val bitrateMode: Int?,
    val frameRate: Int,
    val profile: Int?,
    val supportsMaxBFramesZero: Boolean
)

object EncodingSpec {
    const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    const val AUDIO_SAMPLE_RATE = 48_000
    const val AUDIO_CHANNEL_COUNT = 1
    const val AUDIO_BITRATE = 128_000
    const val GOP_SECONDS = 2

    fun detectAllVideoCodecs(): Map<EncodingTab, VideoCodecCapability> {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        return EncodingTab.entries.associateWith { tab ->
            detectCodecCapability(tab, list)
        }
    }

    fun selectDefaultVideoCodec(
        supportMap: Map<EncodingTab, VideoCodecCapability>
    ): EncodingTab {
        if (supportMap[EncodingTab.H265]?.isSupported == true) return EncodingTab.H265
        if (supportMap[EncodingTab.H264]?.isSupported == true) return EncodingTab.H264
        return EncodingTab.H265
    }

    private fun detectCodecCapability(
        tab: EncodingTab,
        list: MediaCodecList
    ): VideoCodecCapability {
        val encoders = list.codecInfos.filter { info ->
            info.isEncoder &&
                info.supportedTypes.any { it.equals(tab.mimeType, ignoreCase = true) } &&
                supportsSurfaceInput(info, tab.mimeType)
        }

        // Prioritize Hardware for all three codecs; fall back to Software if Hardware unavailable
        val hwEncoder = encoders.firstOrNull { it.isHardwareAccelerated }
        if (hwEncoder != null) {
            return VideoCodecCapability(
                tab = tab,
                isSupported = true,
                isHardwareAccelerated = true,
                codecName = hwEncoder.name,
                statusText = "✓ Hardware Supported"
            )
        }

        val swEncoder = encoders.firstOrNull { !it.isHardwareAccelerated }
        return if (swEncoder != null) {
            VideoCodecCapability(
                tab = tab,
                isSupported = true,
                isHardwareAccelerated = false,
                codecName = swEncoder.name,
                statusText = "✓ Software Supported"
            )
        } else {
            VideoCodecCapability(
                tab = tab,
                isSupported = false,
                isHardwareAccelerated = false,
                codecName = null,
                statusText = "Unsupported"
            )
        }
    }

    fun resolveCompatibleVideoConfig(
        tab: EncodingTab,
        requestedWidth: Int,
        requestedHeight: Int,
        requestedBitrateBps: Int,
        preferredFrameRate: Int = 60
    ): ResolvedVideoConfig {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val capability = detectCodecCapability(tab, list)
        val codecName = capability.codecName
        check(capability.isSupported && !codecName.isNullOrBlank()) {
            "${tab.displayName} encoder is unavailable on this device."
        }

        val matching = list.codecInfos.first { it.name == codecName }
        val caps = matching.getCapabilitiesForType(tab.mimeType)
        val videoCaps = caps.videoCapabilities
        val encoderCaps = caps.encoderCapabilities

        val (alignedW, alignedH) = if (videoCaps != null) {
            fitSupportedDimensions(videoCaps, requestedWidth, requestedHeight)
        } else {
            Pair(
                (requestedWidth and 1.inv()).coerceAtLeast(16),
                (requestedHeight and 1.inv()).coerceAtLeast(16)
            )
        }

        val clampedBitrate = if (videoCaps != null) {
            requestedBitrateBps.coerceIn(
                videoCaps.bitrateRange.lower.coerceAtLeast(500_000),
                videoCaps.bitrateRange.upper.coerceAtLeast(videoCaps.bitrateRange.lower)
            )
        } else {
            requestedBitrateBps.coerceAtLeast(500_000)
        }

        val bitrateMode = when {
            encoderCaps == null -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) ->
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) ->
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            else -> null
        }

        val desiredFps = preferredFrameRate.coerceIn(30, 60)
        val frameRate = if (videoCaps != null) {
            val achievable = runCatching {
                videoCaps.getSupportedFrameRatesFor(alignedW, alignedH)
            }.getOrNull()
            if (achievable != null) {
                desiredFps.toDouble().coerceIn(achievable.lower, achievable.upper)
                    .roundToInt()
                    .coerceAtLeast(24)
            } else {
                desiredFps.coerceIn(
                    videoCaps.supportedFrameRates.lower.coerceAtLeast(24),
                    videoCaps.supportedFrameRates.upper.coerceAtLeast(24)
                )
            }
        } else {
            desiredFps
        }

        val selectedProfile = when (tab) {
            EncodingTab.H265 -> caps.profileLevels?.firstOrNull {
                it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
            }?.profile
            EncodingTab.H264 -> null
        }

        return ResolvedVideoConfig(
            tab = tab,
            codecName = codecName,
            isHardwareAccelerated = capability.isHardwareAccelerated,
            width = alignedW,
            height = alignedH,
            bitrateBps = clampedBitrate,
            bitrateMode = bitrateMode,
            frameRate = frameRate,
            profile = selectedProfile,
            supportsMaxBFramesZero = capability.isHardwareAccelerated
        )
    }

    private fun supportsSurfaceInput(info: MediaCodecInfo, mimeType: String): Boolean {
        val caps = runCatching { info.getCapabilitiesForType(mimeType) }.getOrNull() ?: return false
        return MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in caps.colorFormats
    }

    private fun fitSupportedDimensions(
        videoCaps: MediaCodecInfo.VideoCapabilities,
        rawWidth: Int,
        rawHeight: Int
    ): Pair<Int, Int> {
        val wAlign = videoCaps.widthAlignment.coerceAtLeast(2)
        val hAlign = videoCaps.heightAlignment.coerceAtLeast(2)

        fun alignDown(value: Int, alignment: Int, minVal: Int): Int {
            val evenAlign = if (alignment % 2 == 0) alignment else alignment * 2
            val aligned = (value / evenAlign) * evenAlign
            return aligned.coerceAtLeast(minVal)
        }

        val minW = videoCaps.supportedWidths.lower.coerceAtLeast(wAlign)
        val minH = videoCaps.supportedHeights.lower.coerceAtLeast(hAlign)
        val maxW = videoCaps.supportedWidths.upper
        val maxH = videoCaps.supportedHeights.upper

        var targetW = alignDown(rawWidth.coerceIn(minW, maxW), wAlign, minW)
        var targetH = alignDown(rawHeight.coerceIn(minH, maxH), hAlign, minH)

        if (videoCaps.isSizeSupported(targetW, targetH)) {
            return targetW to targetH
        }

        val scales = listOf(0.85f, 0.75f, 0.66f, 0.5f, 0.35f)
        for (scale in scales) {
            val scaledW = alignDown((rawWidth * scale).roundToInt().coerceIn(minW, maxW), wAlign, minW)
            val scaledH = alignDown((rawHeight * scale).roundToInt().coerceIn(minH, maxH), hAlign, minH)
            if (videoCaps.isSizeSupported(scaledW, scaledH)) {
                return scaledW to scaledH
            }
        }

        targetW = alignDown(rawWidth.coerceAtMost(maxW), wAlign, minW)
        targetH = alignDown(rawHeight.coerceAtMost(maxH), hAlign, minH)
        return targetW to targetH
    }

    fun configureVideoFormat(
        format: MediaFormat,
        existingProjectVideoBitrate: Int,
        bitrateMode: Int?,
        frameRate: Int,
        profile: Int? = null,
        includeColorMetadata: Boolean = true,
        disableBFrames: Boolean = false
    ) {
        require(existingProjectVideoBitrate > 0) {
            "The existing project video bitrate must be supplied."
        }
        format.setInteger(MediaFormat.KEY_BIT_RATE, existingProjectVideoBitrate)
        if (bitrateMode != null) {
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateMode)
        }
        if (profile != null) {
            format.setInteger(MediaFormat.KEY_PROFILE, profile)
        }
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, GOP_SECONDS)
        if (includeColorMetadata) {
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        if (disableBFrames) {
            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate.coerceAtLeast(24))
    }

    fun createAudioFormat(sampleRate: Int = AUDIO_SAMPLE_RATE): MediaFormat {
        return MediaFormat.createAudioFormat(
            AUDIO_MIME,
            sampleRate,
            AUDIO_CHANNEL_COUNT
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
    }

    fun inspectHardwareEncoder(tab: EncodingTab = EncodingTab.H265): EncoderValidationReport {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val capability = detectCodecCapability(tab, list)
        val matchingName = capability.codecName
        val matching = matchingName?.let { name ->
            list.codecInfos.firstOrNull { it.name == name }
        }

        if (matching == null || !capability.isSupported) {
            return EncoderValidationReport(
                codecName = "Unavailable (${tab.displayName})",
                supportsYuv420Surface = false,
                supportsVbr = false,
                supportedBitrateRangeMbps = "Unavailable",
                isCompatible = false,
                isHardwareAccelerated = false
            )
        }

        val caps = matching.getCapabilitiesForType(tab.mimeType)
        val colorFormats = caps.colorFormats.toSet()
        val supportsSurface = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in colorFormats
        val supportsYuv420 =
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in colorFormats ||
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar in colorFormats ||
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar in colorFormats ||
                supportsSurface

        val encoderCaps = caps.encoderCapabilities
        val supportsVbr = encoderCaps?.isBitrateModeSupported(
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
        ) == true

        val videoCaps = caps.videoCapabilities
        val rangeText = if (videoCaps != null) {
            val minMbps = (videoCaps.bitrateRange.lower / 1_000_000.0).coerceAtLeast(0.1)
            val maxMbps = videoCaps.bitrateRange.upper / 1_000_000.0
            String.format("%.1f – %.0f Mbps", minMbps, maxMbps)
        } else {
            "Unknown"
        }

        return EncoderValidationReport(
            codecName = matching.name,
            supportsYuv420Surface = supportsYuv420,
            supportsVbr = supportsVbr,
            supportedBitrateRangeMbps = rangeText,
            isCompatible = supportsYuv420,
            isHardwareAccelerated = capability.isHardwareAccelerated
        )
    }
}
