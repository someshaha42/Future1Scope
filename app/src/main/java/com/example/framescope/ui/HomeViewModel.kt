package com.example.framescope.ui

import android.content.Context
import android.view.WindowManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.framescope.capability.DeviceCapabilityEngine
import com.example.framescope.capability.EffectiveAudioStrategy
import com.example.framescope.capability.HomeCapabilitiesSnapshot
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

    private val _codecCapabilities = MutableStateFlow<Map<EncodingTab, VideoCodecCapability>>(emptyMap())
    val codecCapabilities: StateFlow<Map<EncodingTab, VideoCodecCapability>> = _codecCapabilities.asStateFlow()

    private val _homeCapabilities = MutableStateFlow(HomeCapabilitiesSnapshot())
    val homeCapabilities: StateFlow<HomeCapabilitiesSnapshot> = _homeCapabilities.asStateFlow()

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
            val current = settingsRepository.settings.first()
            refreshCapabilitiesFor(current.videoCodec)
        }
    }

    private suspend fun refreshCapabilitiesFor(selectedCodec: EncodingTab) {
        val (w, h) = readScreenBounds()
        val snapshot = withContext(Dispatchers.Default) {
            DeviceCapabilityEngine.probeHomeCapabilities(
                context = appContext,
                screenWidth = w,
                screenHeight = h,
                selectedVideoCodec = selectedCodec
            )
        }
        _codecCapabilities.value = snapshot.codecCapabilities
        _homeCapabilities.value = snapshot
        _encoderReport.value = withContext(Dispatchers.Default) {
            EncodingSpec.inspectHardwareEncoder(snapshot.effectiveVideoCodec)
        }
    }

    private fun readScreenBounds(): Pair<Int, Int> {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val bounds = wm?.maximumWindowMetrics?.bounds
        val w = bounds?.width()?.coerceAtLeast(720) ?: 1080
        val h = bounds?.height()?.coerceAtLeast(1280) ?: 2400
        return w to h
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
            refreshCapabilitiesFor(tab)
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
        val caps = _homeCapabilities.value
        val codecLabel = if (caps.codecSupport[currentSettings.videoCodec]?.isSupported == false) {
            caps.effectiveVideoCodec.shortName
        } else {
            currentSettings.videoCodec.shortName
        }
        val orientationLabel = when (currentSettings.orientation) {
            RecordingOrientation.PORTRAIT -> "Portrait"
            RecordingOrientation.LANDSCAPE -> "Landscape"
        }
        val audioInfo = caps.audioSourceSupport[currentSettings.audioSource]
        val audioLabel = if (audioInfo != null && !audioInfo.isSupported) {
            when (caps.effectiveAudioStrategy) {
                EffectiveAudioStrategy.INTERNAL_AUDIO -> "Internal Audio"
                EffectiveAudioStrategy.MICROPHONE -> "Microphone"
                EffectiveAudioStrategy.VIDEO_ONLY -> "Video-Only"
            }
        } else {
            when (currentSettings.audioSource) {
                AudioSourceMode.INTERNAL_AUDIO -> "Internal Audio"
                AudioSourceMode.MICROPHONE -> "Microphone"
            }
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
