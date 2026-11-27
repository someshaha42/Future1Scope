package com.example.framescope.encoding

import android.media.MediaCodecInfo
import android.media.MediaFormat

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

object EncodingSpec {
    const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    const val AUDIO_SAMPLE_RATE = 48_000
    const val AUDIO_CHANNEL_COUNT = 1
    const val AUDIO_BITRATE = 128_000
    const val GOP_SECONDS = 2

    fun configureVideoFormat(
        format: MediaFormat,
        existingProjectVideoBitrate: Int,
        bitrateMode: Int?,
        frameRate: Int,
        includeColorMetadata: Boolean = true
    ) {
        require(existingProjectVideoBitrate > 0) {
            "The existing project video bitrate must be supplied."
        }
        format.setInteger(MediaFormat.KEY_BIT_RATE, existingProjectVideoBitrate)
        if (bitrateMode != null) {
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateMode)
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
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate.coerceAtLeast(1))
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
}
