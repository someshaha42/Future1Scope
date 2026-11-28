package com.example.framescope.crop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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
    private val nextNotificationId = AtomicInteger(4300)

    private val _jobs = MutableStateFlow<List<CropExportJob>>(emptyList())
    val jobs: StateFlow<List<CropExportJob>> = _jobs.asStateFlow()

    private val _shareState = MutableStateFlow(CropShareExportUiState())
    val shareState: StateFlow<CropShareExportUiState> = _shareState.asStateFlow()

    private val _lastUpdatedProjectId = MutableStateFlow<Pair<Long, String>?>(null)
    val lastUpdatedProjectId: StateFlow<Pair<Long, String>?> = _lastUpdatedProjectId.asStateFlow()

    private val pendingWorkspaceRequests = ConcurrentHashMap<String, CropWorkspaceExportRequest>()
    private val pendingShareRequest = AtomicReference<CropShareExportRequest?>(null)

    fun enqueueWorkspaceExport(
        projectId: String,
        projectTitle: String,
        configSignature: String,
        request: CropWorkspaceExportRequest
    ): CropExportJob {
        val jobId = UUID.randomUUID().toString()
        val notificationId = nextNotificationId.getAndIncrement()
        val job = CropExportJob(
            jobId = jobId,
            notificationId = notificationId,
            projectId = projectId,
            projectTitle = projectTitle,
            configSignature = configSignature,
            mediaType = request.media.mediaType,
            formatLabel = request.formatLabel,
            outputWidth = request.outputWidth,
            outputHeight = request.outputHeight,
            clickedAtMs = System.currentTimeMillis(),
            progress = 0f,
            statusText = "Processing…",
            status = CropExportJobStatus.RUNNING
        )
        pendingWorkspaceRequests[jobId] = request
        _jobs.update { current -> current + job }
        return job
    }

    fun findActiveOrCompletedJobBySignature(projectId: String, configSignature: String): CropExportJob? {
        if (configSignature.isBlank()) return null
        val matching = _jobs.value.filter {
            it.projectId == projectId && it.configSignature == configSignature
        }
        return matching.firstOrNull { it.status == CropExportJobStatus.RUNNING }
            ?: matching.firstOrNull { it.status == CropExportJobStatus.COMPLETED }
    }

    fun consumeWorkspaceRequest(jobId: String): CropWorkspaceExportRequest? {
        return pendingWorkspaceRequests.remove(jobId)
    }

    fun getJob(jobId: String): CropExportJob? {
        return _jobs.value.firstOrNull { it.jobId == jobId }
    }

    fun updateJobProgress(jobId: String, progress: Float, statusText: String) {
        val clamped = progress.coerceIn(0f, 0.99f)
        _jobs.update { list ->
            list.map { job ->
                if (job.jobId == jobId && job.status == CropExportJobStatus.RUNNING) {
                    job.copy(progress = clamped, statusText = statusText)
                } else {
                    job
                }
            }
        }
    }

    fun completeJobSuccess(jobId: String, result: CropExportState.Success) {
        _jobs.update { list ->
            list.map { job ->
                if (job.jobId == jobId) {
                    job.copy(
                        progress = 1f,
                        statusText = "100% Complete",
                        status = CropExportJobStatus.COMPLETED,
                        outputPath = result.outputPath,
                        fileName = result.fileName,
                        savedToGallery = result.savedToGallery,
                        galleryUriString = result.galleryUriString,
                        errorMessage = null
                    )
                } else {
                    job
                }
            }
        }
    }

    fun failJob(jobId: String, message: String) {
        _jobs.update { list ->
            list.map { job ->
                if (job.jobId == jobId) {
                    job.copy(
                        status = CropExportJobStatus.FAILED,
                        statusText = "Export failed",
                        errorMessage = message
                    )
                } else {
                    job
                }
            }
        }
    }

    fun removeJob(jobId: String) {
        pendingWorkspaceRequests.remove(jobId)
        _jobs.update { list -> list.filterNot { it.jobId == jobId } }
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
