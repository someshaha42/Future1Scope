package com.example.framescope.crop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

data class CropWorkspaceExportRequest(
    val media: ImportedMediaInfo,
    val cropRect: NormalizedCropRect,
    val imageFormat: ImageExportFormatOption?,
    val videoFormat: VideoExportFormatOption?,
    val formatLabel: String,
    val outputWidth: Int,
    val outputHeight: Int,
    val trimStartMs: Long,
    val trimEndMs: Long,
    val projectRecord: CropProjectRecord?
)

data class CropShareExportRequest(
    val project: CropProjectRecord
)

data class CropShareExportReady(
    val shareUriString: String,
    val mimeType: String,
    val updatedProject: CropProjectRecord
)

data class CropShareExportUiState(
    val isExporting: Boolean = false,
    val progress: Float = 0f,
    val statusText: String = "",
    val errorMessage: String? = null,
    val readyEvent: CropShareExportReady? = null
)

object CropExportManager {
    private val _exportState = MutableStateFlow<CropExportState>(CropExportState.Idle)
    val exportState: StateFlow<CropExportState> = _exportState.asStateFlow()

    private val _shareState = MutableStateFlow(CropShareExportUiState())
    val shareState: StateFlow<CropShareExportUiState> = _shareState.asStateFlow()

    private val _lastUpdatedProjectId = MutableStateFlow<Pair<Long, String>?>(null)
    val lastUpdatedProjectId: StateFlow<Pair<Long, String>?> = _lastUpdatedProjectId.asStateFlow()

    private val pendingWorkspaceRequest = AtomicReference<CropWorkspaceExportRequest?>(null)
    private val pendingShareRequest = AtomicReference<CropShareExportRequest?>(null)

    fun enqueueWorkspaceExport(request: CropWorkspaceExportRequest) {
        pendingWorkspaceRequest.set(request)
        _exportState.value = CropExportState.Exporting(0.02f, "Starting export…")
    }

    fun consumeWorkspaceRequest(): CropWorkspaceExportRequest? {
        return pendingWorkspaceRequest.getAndSet(null)
    }

    fun enqueueShareExport(request: CropShareExportRequest) {
        pendingShareRequest.set(request)
        _shareState.value = CropShareExportUiState(
            isExporting = true,
            progress = 0.05f,
            statusText = "Exporting project to share…"
        )
    }

    fun consumeShareRequest(): CropShareExportRequest? {
        return pendingShareRequest.getAndSet(null)
    }

    fun updateExportState(state: CropExportState) {
        _exportState.value = state
    }

    fun resetExportState() {
        pendingWorkspaceRequest.set(null)
        _exportState.value = CropExportState.Idle
    }

    fun updateShareProgress(progress: Float, statusText: String) {
        _shareState.value = _shareState.value.copy(
            isExporting = true,
            progress = progress,
            statusText = statusText,
            errorMessage = null
        )
    }

    fun completeShareSuccess(ready: CropShareExportReady) {
        _shareState.value = CropShareExportUiState(
            isExporting = false,
            progress = 0f,
            statusText = "",
            errorMessage = null,
            readyEvent = ready
        )
    }

    fun failShareExport(message: String) {
        _shareState.value = CropShareExportUiState(
            isExporting = false,
            progress = 0f,
            statusText = "",
            errorMessage = message,
            readyEvent = null
        )
    }

    fun resetShareExportState() {
        pendingShareRequest.set(null)
        _shareState.value = CropShareExportUiState()
    }

    fun consumeShareReadyEvent() {
        _shareState.value = _shareState.value.copy(readyEvent = null)
    }

    fun consumeShareError() {
        _shareState.value = _shareState.value.copy(errorMessage = null)
    }

    fun notifyProjectUpdated(projectId: String) {
        _lastUpdatedProjectId.value = System.nanoTime() to projectId
    }
}
