package com.example.framescope.encoding

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

enum class EncodingTab(val mimeType: String, val displayName: String) {
    H264(MediaFormat.MIMETYPE_VIDEO_AVC, "H.264")
}

data class EncoderValidationReport(
    val codecName: String,
    val supportsYuv420Surface: Boolean,
    val supportsVbr: Boolean,
    val supportedBitrateRangeMbps: String,
    val isCompatible: Boolean
)

object EncodingSpec {
    const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    const val AUDIO_SAMPLE_RATE = 48_000
    const val AUDIO_CHANNEL_COUNT = 1
    const val AUDIO_BITRATE = 128_000
    const val GOP_SECONDS = 2

    fun configureVideoFormat(
        format: MediaFormat,
        existingProjectVideoBitrate: Int
    ) {
        require(existingProjectVideoBitrate > 0) {
            "The existing project video bitrate must be supplied."
        }
        format.setInteger(MediaFormat.KEY_BIT_RATE, existingProjectVideoBitrate)
        format.setInteger(
            MediaFormat.KEY_BITRATE_MODE,
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
        )
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, GOP_SECONDS)
        format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
        format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        // Display-driven capture: supply nominal operating rate hint without forcing fixed FPS output
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 60)
    }

    fun createAudioFormat(): MediaFormat {
        return MediaFormat.createAudioFormat(
            AUDIO_MIME,
            AUDIO_SAMPLE_RATE,
            AUDIO_CHANNEL_COUNT
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
        }
    }

    fun inspectHardwareEncoder(tab: EncodingTab = EncodingTab.H264): EncoderValidationReport {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val matching = list.codecInfos.firstOrNull { info ->
            info.isEncoder &&
                info.isHardwareAccelerated &&
                info.supportedTypes.any { it.equals(tab.mimeType, ignoreCase = true) }
        } ?: list.codecInfos.firstOrNull { info ->
            info.isEncoder &&
                info.supportedTypes.any { it.equals(tab.mimeType, ignoreCase = true) }
        }

        if (matching == null) {
            return EncoderValidationReport(
                codecName = "No ${tab.displayName} encoder found",
                supportsYuv420Surface = false,
                supportsVbr = false,
                supportedBitrateRangeMbps = "Unavailable",
                isCompatible = false
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
            isCompatible = supportsYuv420 && supportsVbr
        )
    }
}
