package com.example.framescope.encoding

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface RecordingSessionState {
    data object Idle : RecordingSessionState

    data class Recording(
        val elapsedSeconds: Long,
        val outputPath: String,
        val summaryText: String,
        val showRegionBorder: Boolean,
        val isRegionMode: Boolean,
        val isPaused: Boolean = false
    ) : RecordingSessionState

    data object Stopping : RecordingSessionState

    data class Error(val message: String) : RecordingSessionState
}

object RecordingSessionManager {
    private val _state = MutableStateFlow<RecordingSessionState>(RecordingSessionState.Idle)
    val state: StateFlow<RecordingSessionState> = _state.asStateFlow()

    private val _lastCompletedFile = MutableStateFlow<String?>(null)
    val lastCompletedFile: StateFlow<String?> = _lastCompletedFile.asStateFlow()

    fun updateState(newState: RecordingSessionState) {
        _state.value = newState
    }

    fun markCompleted(outputPath: String) {
        _lastCompletedFile.value = outputPath
    }

    fun clearError() {
        if (_state.value is RecordingSessionState.Error) {
            _state.value = RecordingSessionState.Idle
        }
    }
}
