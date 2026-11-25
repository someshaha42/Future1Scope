package com.example.framescope.data

import com.example.framescope.encoding.EncodingTab
import com.example.framescope.encoding.RecordingOrientation

enum class RecordingMode {
    FULL_SCREEN,
    REGION_AREA
}

enum class AudioSourceMode {
    INTERNAL_AUDIO,
    MICROPHONE
}

data class RegionBounds(
    val leftFraction: Float = 0.08f,
    val topFraction: Float = 0.14f,
    val rightFraction: Float = 0.92f,
    val bottomFraction: Float = 0.80f
) {
    fun widthFraction(): Float = (rightFraction - leftFraction).coerceIn(0.20f, 1.0f)
    fun heightFraction(): Float = (bottomFraction - topFraction).coerceIn(0.20f, 1.0f)
}

data class AppSettings(
    val recordingMode: RecordingMode = RecordingMode.FULL_SCREEN,
    val orientation: RecordingOrientation = RecordingOrientation.PORTRAIT,
    val audioSource: AudioSourceMode = AudioSourceMode.INTERNAL_AUDIO,
    val videoCodec: EncodingTab = EncodingTab.H265,
    val bitrateMbps: Int = EncodingTab.H265.defaultBitrateMbps,
    val showRegionBorder: Boolean = false,
    val regionBounds: RegionBounds = RegionBounds(),
    val keepScreenAwake: Boolean = false
) {
    val effectiveBitrateBps: Int
        get() = videoCodec.defaultBitrateMbps * 1_000_000
}
