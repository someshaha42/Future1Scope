package com.example.framescope.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.framescope.data.AppSettings
import com.example.framescope.data.AudioSourceMode
import com.example.framescope.data.RecordedVideo
import com.example.framescope.data.RecordingMode
import com.example.framescope.data.RecordingsRepository
import com.example.framescope.data.SettingsRepository
import com.example.framescope.encoding.EncoderValidationReport
import com.example.framescope.encoding.EncodingSpec
import com.example.framescope.encoding.EncodingTab
import com.example.framescope.encoding.RecordingOrientation
import com.example.framescope.encoding.RecordingSessionManager
import com.example.framescope.encoding.RecordingSessionState
import com.example.framescope.encoding.VideoCodecCapability
import com.example.framescope.encoding.VideoDimensions
import com.example.framescope.encoding.getCaptureDimensions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

    private val _codecCapabilities = MutableStateFlow<Map<EncodingTab, VideoCodecCapability>>(emptyMap())
    val codecCapabilities: StateFlow<Map<EncodingTab, VideoCodecCapability>> = _codecCapabilities.asStateFlow()

    private val _encoderReport = MutableStateFlow<EncoderValidationReport?>(null)
    val encoderReport: StateFlow<EncoderValidationReport?> = _encoderReport.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

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
            val detectedCodecs = withContext(Dispatchers.Default) {
                EncodingSpec.detectAllVideoCodecs()
            }
            _codecCapabilities.value = detectedCodecs

            val current = settingsRepository.settings.first()

            val activeCodec = if (detectedCodecs[current.videoCodec]?.isSupported == true) {
                current.videoCodec
            } else {
                val fallbackCodec = EncodingSpec.selectDefaultVideoCodec(detectedCodecs)
                if (fallbackCodec != current.videoCodec) {
                    settingsRepository.setVideoCodec(fallbackCodec)
                }
                fallbackCodec
            }

            _encoderReport.value = withContext(Dispatchers.Default) {
                EncodingSpec.inspectHardwareEncoder(activeCodec)
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

    fun selectVideoCodec(tab: EncodingTab) {
        val capability = _codecCapabilities.value[tab]
        if (capability == null || !capability.isSupported) {
            _statusMessage.value = "${tab.displayName} is unsupported on this device."
            return
        }
        viewModelScope.launch {
            settingsRepository.setVideoCodec(tab)
            _encoderReport.value = withContext(Dispatchers.Default) {
                EncodingSpec.inspectHardwareEncoder(tab)
            }
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
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
        val orientationLabel = when (currentSettings.orientation) {
            RecordingOrientation.PORTRAIT -> "Portrait"
            RecordingOrientation.LANDSCAPE -> "Landscape"
        }
        val audioLabel = when (currentSettings.audioSource) {
            AudioSourceMode.INTERNAL_AUDIO -> "Internal Audio"
            AudioSourceMode.MICROPHONE -> "Microphone"
        }
        return "${currentSettings.videoCodec.shortName} (${dimensions.width}×${dimensions.height}) • $orientationLabel • $audioLabel"
    }

    class Factory(
        @Suppress("unused")
        private val appContext: Context,
        private val settingsRepository: SettingsRepository,
        private val recordingsRepository: RecordingsRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(settingsRepository, recordingsRepository) as T
        }
    }
}
