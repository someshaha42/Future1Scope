package com.example.framescope.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.framescope.encoding.EncodingTab
import com.example.framescope.encoding.RecordingOrientation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "framescope_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val RECORDING_MODE = stringPreferencesKey("recording_mode")
        val ORIENTATION = stringPreferencesKey("recording_orientation")
        val AUDIO_SOURCE = stringPreferencesKey("audio_source")
        val VIDEO_CODEC = stringPreferencesKey("video_codec")
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

        val videoCodec = prefs[Keys.VIDEO_CODEC]?.let { raw ->
            runCatching { EncodingTab.valueOf(raw) }.getOrNull()
        } ?: EncodingTab.H265

        AppSettings(
            recordingMode = RecordingMode.FULL_SCREEN,
            orientation = orientation,
            audioSource = audioSource,
            videoCodec = videoCodec,
            bitrateMbps = videoCodec.defaultBitrateMbps,
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

    suspend fun setVideoCodec(tab: EncodingTab) {
        context.settingsDataStore.edit {
            it[Keys.VIDEO_CODEC] = tab.name
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
