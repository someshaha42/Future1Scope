package com.example.framescope.data

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
    val bitrateMbps: Int = 150,
    val customBitrateInput: String = "",
    val showRegionBorder: Boolean = true,
    val regionBounds: RegionBounds = RegionBounds(),
    val keepScreenAwake: Boolean = false
) {
    companion object {
        val PRESET_BITRATES_MBPS = listOf(100, 150, 200, 250, 300, 350)
        const val MIN_CUSTOM_BITRATE_MBPS = 1
        const val MAX_CUSTOM_BITRATE_MBPS = 500
    }

    val effectiveBitrateBps: Int
        get() = bitrateMbps.coerceIn(MIN_CUSTOM_BITRATE_MBPS, MAX_CUSTOM_BITRATE_MBPS) * 1_000_000
}
