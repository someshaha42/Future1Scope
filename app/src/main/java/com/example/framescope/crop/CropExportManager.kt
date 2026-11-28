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
    val project: CropProjectRecord,
    val jobId: String = "",
    val notificationId: Int = 0
)

data class CropShareExportReady(
    val shareUriString: String,
    val mimeType: String,
    val updatedProject: CropProjectRecord
)

data class CropShareExportUiState(
    val isExporting: Boolean = false,
    val activeJobId: String? = null,
    val projectId: String? = null,
    val projectTitle: String = "",
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
    private val pendingShareRequests = ConcurrentHashMap<String, CropShareExportRequest>()
    private val shareTargetJobId = AtomicReference<String?>(null)

    fun enqueueWorkspaceExport(
        projectId: String,
        projectTitle: String,
        configSignature: String,
        request: CropWorkspaceExportRequest
    ): CropExportJob {
        val jobId = UUID.randomUUID().toString()
        val notificationId = nextNotificationId.getAndIncrement()
        val rec = request.projectRecord
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
            status = CropExportJobStatus.RUNNING,
            cropLeft = rec?.cropLeft ?: request.cropRect.left,
            cropTop = rec?.cropTop ?: request.cropRect.top,
            cropRight = rec?.cropRight ?: request.cropRect.right,
            cropBottom = rec?.cropBottom ?: request.cropRect.bottom,
            aspectRatioName = rec?.aspectRatioName ?: CropAspectRatio.FREE.name,
            customAspectWidth = rec?.customAspectWidth ?: "4",
            customAspectHeight = rec?.customAspectHeight ?: "3",
            canvasAspectModeName = rec?.canvasAspectModeName ?: CanvasAspectMode.ORIGINAL.name,
            customCanvasWidth = rec?.customCanvasWidth ?: "4",
            customCanvasHeight = rec?.customCanvasHeight ?: "3",
            trimStartMs = request.trimStartMs,
            trimEndMs = request.trimEndMs,
            bitrateModeName = rec?.bitrateModeName ?: VideoBitrateMode.ORIGINAL_PRESERVATION.name,
            customBitrateMbps = rec?.customBitrateMbps ?: 12f,
            selectedFormatLabel = rec?.selectedFormatLabel ?: request.formatLabel,
            hasSavedEditSnapshot = true
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

    fun findRunningJobForProject(projectId: String): CropExportJob? {
        return _jobs.value.firstOrNull {
            it.projectId == projectId && it.status == CropExportJobStatus.RUNNING
        }
    }

    fun consumeWorkspaceRequest(jobId: String): CropWorkspaceExportRequest? {
        return pendingWorkspaceRequests.remove(jobId)
    }

    fun getJob(jobId: String): CropExportJob? {
        return _jobs.value.firstOrNull { it.jobId == jobId }
    }

    fun updateJobMetadata(
        jobId: String,
        formatLabel: String,
        outputWidth: Int,
        outputHeight: Int,
        configSignature: String
    ) {
        _jobs.update { list ->
            list.map { job ->
                if (job.jobId == jobId) {
                    job.copy(
                        formatLabel = formatLabel,
                        outputWidth = outputWidth,
                        outputHeight = outputHeight,
                        configSignature = configSignature
                    )
                } else {
                    job
                }
            }
        }
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
        if (shareTargetJobId.get() == jobId) {
            _shareState.update { current ->
                current.copy(
                    isExporting = true,
                    progress = clamped,
                    statusText = statusText,
                    errorMessage = null
                )
            }
        }
    }

    fun completeJobSuccess(
        jobId: String,
        result: CropExportState.Success,
        completedAtMs: Long = System.currentTimeMillis()
    ) {
        _jobs.update { list ->
            list.map { job ->
                if (job.jobId == jobId) {
                    job.copy(
                        completedAtMs = completedAtMs,
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
        if (shareTargetJobId.compareAndSet(jobId, null)) {
            failShareExport(message)
        }
    }

    fun removeJob(jobId: String) {
        pendingWorkspaceRequests.remove(jobId)
        pendingShareRequests.remove(jobId)
        _jobs.update { list -> list.filterNot { it.jobId == jobId } }
        if (shareTargetJobId.compareAndSet(jobId, null)) {
            _shareState.value = CropShareExportUiState()
        }
    }

    fun enqueueShareExport(project: CropProjectRecord): CropExportJob {
        val jobId = UUID.randomUUID().toString()
        val notificationId = nextNotificationId.getAndIncrement()
        val fallbackFormat = project.selectedFormatLabel
            ?: if (project.mediaType == CropMediaType.VIDEO) "MP4" else "PNG"
        val job = CropExportJob(
            jobId = jobId,
            notificationId = notificationId,
            projectId = project.id,
            projectTitle = project.title,
            configSignature = "",
            mediaType = project.mediaType,
            formatLabel = fallbackFormat,
            outputWidth = project.outputWidth,
            outputHeight = project.outputHeight,
            clickedAtMs = System.currentTimeMillis(),
            progress = 0f,
            statusText = "Processing…",
            status = CropExportJobStatus.RUNNING,
            cropLeft = project.cropLeft,
            cropTop = project.cropTop,
            cropRight = project.cropRight,
            cropBottom = project.cropBottom,
            aspectRatioName = project.aspectRatioName,
            customAspectWidth = project.customAspectWidth,
            customAspectHeight = project.customAspectHeight,
            canvasAspectModeName = project.canvasAspectModeName,
            customCanvasWidth = project.customCanvasWidth,
            customCanvasHeight = project.customCanvasHeight,
            trimStartMs = project.trimStartMs,
            trimEndMs = project.trimEndMs,
            bitrateModeName = project.bitrateModeName,
            customBitrateMbps = project.customBitrateMbps,
            selectedFormatLabel = fallbackFormat,
            hasSavedEditSnapshot = true
        )
        pendingShareRequests[jobId] = CropShareExportRequest(
            project = project,
            jobId = jobId,
            notificationId = notificationId
        )
        shareTargetJobId.set(jobId)
        _jobs.update { current -> current + job }
        _shareState.value = CropShareExportUiState(
            isExporting = true,
            activeJobId = jobId,
            projectId = project.id,
            projectTitle = project.title,
            progress = 0f,
            statusText = "Processing…"
        )
        return job
    }

    fun attachShareToExistingJob(job: CropExportJob) {
        shareTargetJobId.set(job.jobId)
        _shareState.value = CropShareExportUiState(
            isExporting = true,
            activeJobId = job.jobId,
            projectId = job.projectId,
            projectTitle = job.projectTitle,
            progress = job.progress,
            statusText = job.statusText
        )
    }

    fun consumeShareTargetForJob(jobId: String): Boolean {
        return shareTargetJobId.compareAndSet(jobId, null)
    }

    fun consumeShareRequest(jobId: String): CropShareExportRequest? {
        return pendingShareRequests.remove(jobId)
    }

    fun completeShareSuccess(ready: CropShareExportReady) {
        shareTargetJobId.set(null)
        _shareState.value = CropShareExportUiState(
            isExporting = false,
            activeJobId = null,
            projectId = null,
            projectTitle = "",
            progress = 0f,
            statusText = "",
            errorMessage = null,
            readyEvent = ready
        )
    }

    fun failShareExport(message: String) {
        shareTargetJobId.set(null)
        _shareState.value = CropShareExportUiState(
            isExporting = false,
            activeJobId = null,
            projectId = null,
            projectTitle = "",
            progress = 0f,
            statusText = "",
            errorMessage = message,
            readyEvent = null
        )
    }

    fun resetShareExportState() {
        shareTargetJobId.set(null)
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
