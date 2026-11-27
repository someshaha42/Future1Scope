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
import com.example.framescope.encoding.EncodingTab
import com.example.framescope.encoding.RecordingOrientation
import com.example.framescope.encoding.RecordingSessionManager
import com.example.framescope.encoding.RecordingSessionState
import com.example.framescope.encoding.VideoDimensions
import com.example.framescope.encoding.getCaptureDimensions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val appContext: Context,
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
        viewModelScope.launch {
            settingsRepository.setVideoCodec(tab)
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
        val codecLabel = currentSettings.videoCodec.shortName
        val orientationLabel = when (currentSettings.orientation) {
            RecordingOrientation.PORTRAIT -> "Portrait"
            RecordingOrientation.LANDSCAPE -> "Landscape"
        }
        val audioLabel = when (currentSettings.audioSource) {
            AudioSourceMode.INTERNAL_AUDIO -> "Internal Audio"
            AudioSourceMode.MICROPHONE -> "Microphone"
        }
        return "$codecLabel (${dimensions.width}×${dimensions.height}) • $orientationLabel • $audioLabel"
    }

    class Factory(
        private val appContext: Context,
        private val settingsRepository: SettingsRepository,
        private val recordingsRepository: RecordingsRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(appContext.applicationContext, settingsRepository, recordingsRepository) as T
        }
    }
}
