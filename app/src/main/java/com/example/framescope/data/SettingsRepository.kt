package com.example.framescope.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.framescope.encoding.EncodingTab
import com.example.framescope.encoding.GpuRenderingApi
import com.example.framescope.encoding.RecordingOrientation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "framescope_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val RECORDING_MODE = stringPreferencesKey("recording_mode")
        val ORIENTATION = stringPreferencesKey("recording_orientation")
        val AUDIO_SOURCE = stringPreferencesKey("audio_source")
        val GPU_RENDERING_API = stringPreferencesKey("gpu_rendering_api")
        val VIDEO_CODEC = stringPreferencesKey("video_codec")
        val BITRATE_MBPS = intPreferencesKey("bitrate_mbps")
        val CUSTOM_BITRATE_INPUT = stringPreferencesKey("custom_bitrate_input")
        val SHOW_REGION_BORDER = booleanPreferencesKey("show_region_border")
        val REGION_LEFT = floatPreferencesKey("region_left")
        val REGION_TOP = floatPreferencesKey("region_top")
        val REGION_RIGHT = floatPreferencesKey("region_right")
        val REGION_BOTTOM = floatPreferencesKey("region_bottom")
        val KEEP_SCREEN_AWAKE = booleanPreferencesKey("keep_screen_awake")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        val orientation = prefs[Keys.ORIENTATION]?.let { raw ->
            runCatching { RecordingOrientation.valueOf(raw) }.getOrNull()
        } ?: RecordingOrientation.PORTRAIT

        val audioSource = prefs[Keys.AUDIO_SOURCE]?.let { raw ->
            runCatching { AudioSourceMode.valueOf(raw) }.getOrNull()
        } ?: AudioSourceMode.INTERNAL_AUDIO

        val gpuApi = prefs[Keys.GPU_RENDERING_API]?.let { raw ->
            runCatching { GpuRenderingApi.valueOf(raw) }.getOrNull()
        } ?: GpuRenderingApi.VULKAN_1_3

        val videoCodec = prefs[Keys.VIDEO_CODEC]?.let { raw ->
            runCatching { EncodingTab.valueOf(raw) }.getOrNull()
        } ?: EncodingTab.AV1

        val bitrateMbps = prefs[Keys.BITRATE_MBPS] ?: videoCodec.defaultBitrateMbps

        AppSettings(
            recordingMode = RecordingMode.FULL_SCREEN,
            orientation = orientation,
            audioSource = audioSource,
            gpuRenderingApi = gpuApi,
            videoCodec = videoCodec,
            bitrateMbps = bitrateMbps,
            customBitrateInput = prefs[Keys.CUSTOM_BITRATE_INPUT] ?: "",
            showRegionBorder = false,
            regionBounds = RegionBounds(
                leftFraction = prefs[Keys.REGION_LEFT] ?: 0.08f,
                topFraction = prefs[Keys.REGION_TOP] ?: 0.14f,
                rightFraction = prefs[Keys.REGION_RIGHT] ?: 0.92f,
                bottomFraction = prefs[Keys.REGION_BOTTOM] ?: 0.80f
            ),
            keepScreenAwake = prefs[Keys.KEEP_SCREEN_AWAKE] ?: false
        )
    }

    suspend fun setRecordingMode(mode: RecordingMode) {
        context.settingsDataStore.edit { it[Keys.RECORDING_MODE] = mode.name }
    }

    suspend fun setOrientation(orientation: RecordingOrientation) {
        context.settingsDataStore.edit { it[Keys.ORIENTATION] = orientation.name }
    }

    suspend fun setAudioSource(source: AudioSourceMode) {
        context.settingsDataStore.edit { it[Keys.AUDIO_SOURCE] = source.name }
    }

    suspend fun setGpuRenderingApi(api: GpuRenderingApi) {
        context.settingsDataStore.edit { it[Keys.GPU_RENDERING_API] = api.name }
    }

    suspend fun setVideoCodec(tab: EncodingTab, applyDefaultBitrate: Boolean = true) {
        context.settingsDataStore.edit {
            it[Keys.VIDEO_CODEC] = tab.name
            if (applyDefaultBitrate) {
                it[Keys.BITRATE_MBPS] = tab.defaultBitrateMbps
                it[Keys.CUSTOM_BITRATE_INPUT] = ""
            }
        }
    }

    suspend fun setPresetBitrate(mbps: Int) {
        context.settingsDataStore.edit {
            it[Keys.BITRATE_MBPS] = mbps
            it[Keys.CUSTOM_BITRATE_INPUT] = ""
        }
    }

    suspend fun setCustomBitrate(input: String, parsedMbps: Int?) {
        context.settingsDataStore.edit {
            it[Keys.CUSTOM_BITRATE_INPUT] = input
            if (parsedMbps != null) {
                it[Keys.BITRATE_MBPS] = parsedMbps
            }
        }
    }

    suspend fun setShowRegionBorder(show: Boolean) {
        context.settingsDataStore.edit { it[Keys.SHOW_REGION_BORDER] = show }
    }

    suspend fun setRegionBounds(bounds: RegionBounds) {
        context.settingsDataStore.edit {
            it[Keys.REGION_LEFT] = bounds.leftFraction
            it[Keys.REGION_TOP] = bounds.topFraction
            it[Keys.REGION_RIGHT] = bounds.rightFraction
            it[Keys.REGION_BOTTOM] = bounds.bottomFraction
        }
    }

    suspend fun setKeepScreenAwake(keepAwake: Boolean) {
        context.settingsDataStore.edit { it[Keys.KEEP_SCREEN_AWAKE] = keepAwake }
    }
}
