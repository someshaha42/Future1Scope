package com.example.framescope.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.framescope.data.AppSettings
import com.example.framescope.data.AudioSourceMode
import com.example.framescope.data.RecordedVideo
import com.example.framescope.data.RecordingMode
import com.example.framescope.data.RecordingsRepository
import com.example.framescope.data.RegionBounds
import com.example.framescope.data.SettingsRepository
import com.example.framescope.encoding.EncoderValidationReport
import com.example.framescope.encoding.EncodingSpec
import com.example.framescope.encoding.EncodingTab
import com.example.framescope.encoding.RecordingOrientation
import com.example.framescope.encoding.RecordingSessionManager
import com.example.framescope.encoding.RecordingSessionState
import com.example.framescope.encoding.VideoDimensions
import com.example.framescope.encoding.getCaptureDimensions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(
    private val settingsRepository: SettingsRepository,
    private val recordingsRepository: RecordingsRepository
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings()
    )

    val sessionState: StateFlow<RecordingSessionState> = RecordingSessionManager.state

    private val _recordings = MutableStateFlow<List<RecordedVideo>>(emptyList())
    val recordings: StateFlow<List<RecordedVideo>> = _recordings.asStateFlow()

    private val _bitrateError = MutableStateFlow<String?>(null)
    val bitrateError: StateFlow<String?> = _bitrateError.asStateFlow()

    private val _encoderReport = MutableStateFlow<EncoderValidationReport?>(null)
    val encoderReport: StateFlow<EncoderValidationReport?> = _encoderReport.asStateFlow()

    init {
        refreshRecordings()
        viewModelScope.launch {
            RecordingSessionManager.lastCompletedFile.collect { path ->
                if (path != null) {
                    refreshRecordings()
                }
            }
        }
        viewModelScope.launch {
            _encoderReport.value = withContext(Dispatchers.Default) {
                val hevc = EncodingSpec.inspectHardwareEncoder(EncodingTab.H265)
                if (hevc.isCompatible) hevc else EncodingSpec.inspectHardwareEncoder(EncodingTab.H264)
            }
        }
    }

    fun refreshRecordings() {
        viewModelScope.launch {
            _recordings.value = recordingsRepository.loadRecordings()
        }
    }

    fun setRecordingMode(mode: RecordingMode) {
        viewModelScope.launch {
            settingsRepository.setRecordingMode(mode)
        }
    }

    fun setOrientation(orientation: RecordingOrientation) {
        viewModelScope.launch {
            settingsRepository.setOrientation(orientation)
        }
    }

    fun setAudioSource(source: AudioSourceMode) {
        viewModelScope.launch {
            settingsRepository.setAudioSource(source)
        }
    }

    fun selectPresetBitrate(mbps: Int) {
        _bitrateError.value = null
        viewModelScope.launch {
            settingsRepository.setPresetBitrate(mbps)
        }
    }

    fun updateCustomBitrate(rawInput: String) {
        val digitsOnly = rawInput.filter { it.isDigit() }
        if (digitsOnly.isEmpty()) {
            _bitrateError.value = null
            viewModelScope.launch {
                settingsRepository.setCustomBitrate("", null)
            }
            return
        }

        val parsed = digitsOnly.toIntOrNull()
        if (parsed == null ||
            parsed < AppSettings.MIN_CUSTOM_BITRATE_MBPS ||
            parsed > AppSettings.MAX_CUSTOM_BITRATE_MBPS
        ) {
            _bitrateError.value =
                "Enter ${AppSettings.MIN_CUSTOM_BITRATE_MBPS}–${AppSettings.MAX_CUSTOM_BITRATE_MBPS} Mbps"
            viewModelScope.launch {
                settingsRepository.setCustomBitrate(digitsOnly, null)
            }
        } else {
            _bitrateError.value = null
            viewModelScope.launch {
                settingsRepository.setCustomBitrate(digitsOnly, parsed)
            }
        }
    }

    fun setShowRegionBorder(show: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShowRegionBorder(show)
        }
    }

    fun setRegionBounds(bounds: RegionBounds) {
        viewModelScope.launch {
            settingsRepository.setRegionBounds(bounds)
        }
    }

    fun toggleKeepScreenAwake() {
        viewModelScope.launch {
            settingsRepository.setKeepScreenAwake(!settings.value.keepScreenAwake)
        }
    }

    fun createOutputFilePath(): String {
        return recordingsRepository.createNewOutputFile().absolutePath
    }

    fun deleteVideo(filePath: String) {
        viewModelScope.launch {
            if (recordingsRepository.deleteRecording(filePath)) {
                refreshRecordings()
            }
        }
    }

    suspend fun exportVideoToGallery(filePath: String): Boolean {
        return recordingsRepository.exportToMediaStore(filePath)
    }

    fun clearRecordingError() {
        RecordingSessionManager.clearError()
    }

    fun computeTargetDimensions(
        currentSettings: AppSettings,
        screenWidth: Int,
        screenHeight: Int
    ): VideoDimensions {
        return getCaptureDimensions(
            orientation = currentSettings.orientation,
            recordingMode = currentSettings.recordingMode,
            regionBounds = currentSettings.regionBounds,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )
    }

    fun buildSummaryLine(
        currentSettings: AppSettings,
        dimensions: VideoDimensions
    ): String {
        val modeLabel = when (currentSettings.recordingMode) {
            RecordingMode.FULL_SCREEN -> "Full Screen"
            RecordingMode.REGION_AREA -> "Region"
        }
        val orientationLabel = when (currentSettings.orientation) {
            RecordingOrientation.PORTRAIT -> "Portrait"
            RecordingOrientation.LANDSCAPE -> "Landscape"
        }
        val audioLabel = when (currentSettings.audioSource) {
            AudioSourceMode.INTERNAL_AUDIO -> "Internal Audio"
            AudioSourceMode.MICROPHONE -> "Microphone"
        }
        return "$modeLabel (${dimensions.width}×${dimensions.height}) • $orientationLabel • $audioLabel"
    }

    class Factory(
        private val settingsRepository: SettingsRepository,
        private val recordingsRepository: RecordingsRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(settingsRepository, recordingsRepository) as T
        }
    }
}
