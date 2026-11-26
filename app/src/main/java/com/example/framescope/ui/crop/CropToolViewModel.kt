package com.example.framescope.ui.crop

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.ui.geometry.Offset
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.framescope.capability.CropCapabilitiesSnapshot
import com.example.framescope.capability.DeviceCapabilityEngine
import com.example.framescope.capability.OptionSupportInfo
import com.example.framescope.crop.CanvasAspectMode
import com.example.framescope.crop.CodecPipelineSupport
import com.example.framescope.crop.CropAspectRatio
import com.example.framescope.crop.CropEditSnapshot
import com.example.framescope.crop.CropExportState
import com.example.framescope.crop.CropExportsRepository
import com.example.framescope.crop.CropMediaInspector
import com.example.framescope.crop.CropMediaType
import com.example.framescope.crop.CropProjectRecord
import com.example.framescope.crop.CropProjectsRepository
import com.example.framescope.crop.EightKVp9Pipeline
import com.example.framescope.crop.ImageCropProcessor
import com.example.framescope.crop.ImageExportFormatOption
import com.example.framescope.crop.ImportedMediaInfo
import com.example.framescope.crop.NormalizedCropRect
import com.example.framescope.crop.VideoBitrateMode
import com.example.framescope.crop.VideoCropProcessor
import com.example.framescope.crop.VideoExportFormatOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

data class CropToolUiState(
    val isLoadingMedia: Boolean = false,
    val media: ImportedMediaInfo? = null,
    val previewBitmap: Bitmap? = null,
    val showImportPanel: Boolean = true,
    val currentProjectId: String? = null,
    val currentProjectTitle: String? = null,
    val currentSourceUriString: String? = null,
    val currentLocalCopyPath: String? = null,
    val copyOriginalSourceEnabled: Boolean = false,
    val cropRect: NormalizedCropRect = NormalizedCropRect(),
    val zoom: Float = 1f,
    val panOffset: Offset = Offset.Zero,
    val isZoomEnabled: Boolean = false,
    val isPreviewMode: Boolean = false,
    val canvasAspectMode: CanvasAspectMode = CanvasAspectMode.ORIGINAL,
    val showCanvasOptions: Boolean = false,
    val customCanvasWidth: String = "4",
    val customCanvasHeight: String = "3",
    val aspectRatio: CropAspectRatio = CropAspectRatio.FREE,
    val customAspectWidth: String = "4",
    val customAspectHeight: String = "3",
    val customAspectError: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val canReset: Boolean = false,
    val isVideoPlaying: Boolean = false,
    val isVideoMuted: Boolean = false,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val currentVideoPositionMs: Long = 0L,
    val seekRequestMs: Long? = null,
    val bitrateMode: VideoBitrateMode = VideoBitrateMode.ORIGINAL_PRESERVATION,
    val customBitrateMbps: Float = 12f,
    val selectedImageFormat: ImageExportFormatOption? = null,
    val selectedVideoFormat: VideoExportFormatOption? = null,
    val pipelineSupport: CodecPipelineSupport? = null,
    val cropCapabilities: CropCapabilitiesSnapshot = CropCapabilitiesSnapshot(),
    val aspectRatioSupport: Map<CropAspectRatio, OptionSupportInfo> = emptyMap(),
    val bitrateModeSupport: Map<VideoBitrateMode, OptionSupportInfo> = emptyMap(),
    val recentProjects: List<CropProjectRecord> = emptyList(),
    val projectThumbnails: Map<String, Bitmap> = emptyMap(),
    val exportState: CropExportState = CropExportState.Idle,
    val isSharingProjectExport: Boolean = false,
    val shareExportProgress: Float = 0f,
    val shareExportStatus: String = "",
    val message: String? = null
) {
    val outputWidth: Int
        get() {
            val info = media ?: return 0
            val raw = (cropRect.widthFraction * info.displayWidth).roundToInt()
            return if (info.mediaType == CropMediaType.VIDEO) {
                (raw and 1.inv()).coerceIn(16, info.displayWidth)
            } else {
                raw.coerceIn(1, info.displayWidth)
            }
        }

    val outputHeight: Int
        get() {
            val info = media ?: return 0
            val raw = (cropRect.heightFraction * info.displayHeight).roundToInt()
            return if (info.mediaType == CropMediaType.VIDEO) {
                (raw and 1.inv()).coerceIn(16, info.displayHeight)
            } else {
                raw.coerceIn(1, info.displayHeight)
            }
        }

    val activeAspectRatioValue: Float?
        get() = when (aspectRatio) {
            CropAspectRatio.FREE -> null
            CropAspectRatio.CUSTOM -> {
                val w = customAspectWidth.toFloatOrNull()
                val h = customAspectHeight.toFloatOrNull()
                if (w != null && h != null && w > 0f && h > 0f) w / h else null
            }
            else -> aspectRatio.ratio
        }

    val activeCanvasAspectRatio: Float
        get() {
            val info = media
            return when (canvasAspectMode) {
                CanvasAspectMode.ORIGINAL -> if (info != null) {
                    (info.displayWidth.toFloat() / info.displayHeight.toFloat().coerceAtLeast(1f))
                        .coerceIn(0.35f, 2.5f)
                } else 1f
                CanvasAspectMode.RATIO_1_1 -> 1f
                CanvasAspectMode.CUSTOM -> {
                    val w = customCanvasWidth.toFloatOrNull()
                    val h = customCanvasHeight.toFloatOrNull()
                    if (w != null && h != null && w > 0f && h > 0f) {
                        (w / h).coerceIn(0.35f, 2.5f)
                    } else 1f
                }
                else -> canvasAspectMode.ratio ?: 1f
            }
        }

    val cropCompositionAspectRatio: Float
        get() {
            val info = media ?: return 1f
            val locked = activeAspectRatioValue
            if (locked != null && locked > 0f) {
                return locked.coerceIn(0.2f, 5f)
            }
            val cropW = (cropRect.widthFraction * info.displayWidth).coerceAtLeast(1f)
            val cropH = (cropRect.heightFraction * info.displayHeight).coerceAtLeast(1f)
            return (cropW / cropH).coerceIn(0.2f, 5f)
        }
}

class CropToolViewModel(
    private val appContext: Context
) : ViewModel() {

    private val projectsRepository = CropProjectsRepository(appContext)
    private val _uiState = MutableStateFlow(CropToolUiState())
    val uiState: StateFlow<CropToolUiState> = _uiState.asStateFlow()

    private var exportJob: Job? = null
    private var shareExportJob: Job? = null

    private val initialSnapshot = CropEditSnapshot()
    private val undoStack = ArrayDeque<CropEditSnapshot>()
    private val redoStack = ArrayDeque<CropEditSnapshot>()
    private var pendingGestureStartSnapshot: CropEditSnapshot? = null

    init {
        val caps = DeviceCapabilityEngine.probeCropCapabilities(appContext)
        _uiState.update { it.copy(cropCapabilities = caps) }
        viewModelScope.launch {
            projectsRepository.copyOriginalSourceFlow.collect { enabled ->
                _uiState.update { it.copy(copyOriginalSourceEnabled = enabled) }
            }
        }
        refreshRecentProjects()
    }

    private fun CropToolUiState.withDynamicSupport(): CropToolUiState {
        val aspectSupport = DeviceCapabilityEngine.probeAspectRatiosForMedia(
            media = media,
            customWidth = customAspectWidth,
            customHeight = customAspectHeight
        )
        val bitrateSupport = if (media?.mediaType == CropMediaType.VIDEO) {
            DeviceCapabilityEngine.probeCropVideoBitrateModes(
                media = media,
                customBitrateMbps = customBitrateMbps,
                container = selectedVideoFormat?.container
            )
        } else {
            emptyMap()
        }
        return copy(
            aspectRatioSupport = aspectSupport,
            bitrateModeSupport = bitrateSupport
        )
    }

    private fun CropToolUiState.toSnapshot(): CropEditSnapshot = CropEditSnapshot(
        cropRect = cropRect,
        aspectRatio = aspectRatio,
        customAspectWidth = customAspectWidth,
        customAspectHeight = customAspectHeight
    )

    private fun pushHistory(previous: CropEditSnapshot, current: CropEditSnapshot) {
        if (previous == current) return
        if (undoStack.size >= MAX_HISTORY_ENTRIES) {
            undoStack.removeFirst()
        }
        undoStack.addLast(previous)
        redoStack.clear()
    }

    private fun CropToolUiState.withHistoryFlags(): CropToolUiState = copy(
        canUndo = undoStack.isNotEmpty(),
        canRedo = redoStack.isNotEmpty(),
        canReset = toSnapshot() != initialSnapshot
    )

    fun setCopyOriginalSource(enabled: Boolean) {
        viewModelScope.launch {
            projectsRepository.setCopyOriginalSource(enabled)
        }
    }

    fun beginCropGesture() {
        if (pendingGestureStartSnapshot == null) {
            pendingGestureStartSnapshot = _uiState.value.toSnapshot()
        }
    }

    fun commitCropGesture() {
        val startSnap = pendingGestureStartSnapshot ?: return
        pendingGestureStartSnapshot = null
        val curSnap = _uiState.value.toSnapshot()
        if (startSnap != curSnap) {
            pushHistory(startSnap, curSnap)
            _uiState.update { it.withHistoryFlags() }
            persistCurrentProjectAsync()
        }
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        pendingGestureStartSnapshot = null
        val prevSnap = undoStack.removeLast()
        val curSnap = _uiState.value.toSnapshot()
        redoStack.addLast(curSnap)
        _uiState.update { state ->
            state.copy(
                cropRect = prevSnap.cropRect,
                aspectRatio = prevSnap.aspectRatio,
                customAspectWidth = prevSnap.customAspectWidth,
                customAspectHeight = prevSnap.customAspectHeight,
                customAspectError = null
            ).withHistoryFlags().withDynamicSupport()
        }
        persistCurrentProjectAsync()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        pendingGestureStartSnapshot = null
        val nextSnap = redoStack.removeLast()
        val curSnap = _uiState.value.toSnapshot()
        undoStack.addLast(curSnap)
        _uiState.update { state ->
            state.copy(
                cropRect = nextSnap.cropRect,
                aspectRatio = nextSnap.aspectRatio,
                customAspectWidth = nextSnap.customAspectWidth,
                customAspectHeight = nextSnap.customAspectHeight,
                customAspectError = null
            ).withHistoryFlags().withDynamicSupport()
        }
        persistCurrentProjectAsync()
    }

    fun resetCropSelection() {
        pendingGestureStartSnapshot = null
        val curSnap = _uiState.value.toSnapshot()
        if (curSnap == initialSnapshot) return
        pushHistory(curSnap, initialSnapshot)
        _uiState.update { state ->
            state.copy(
                cropRect = initialSnapshot.cropRect,
                aspectRatio = initialSnapshot.aspectRatio,
                customAspectWidth = initialSnapshot.customAspectWidth,
                customAspectHeight = initialSnapshot.customAspectHeight,
                customAspectError = null
            ).withHistoryFlags().withDynamicSupport()
        }
        persistCurrentProjectAsync()
    }

    fun enterPreviewMode() {
        val state = _uiState.value
        val media = state.media ?: return
        if (state.isPreviewMode) return
        val start = state.trimStartMs
        val end = if (state.trimEndMs > start) state.trimEndMs else media.durationMs
        val needsSeek = media.mediaType == CropMediaType.VIDEO &&
            (state.currentVideoPositionMs < start || (end > 0L && state.currentVideoPositionMs >= end))
        _uiState.update { prev ->
            prev.copy(
                isPreviewMode = true,
                showCanvasOptions = false,
                isZoomEnabled = false,
                seekRequestMs = if (needsSeek) start else prev.seekRequestMs
            )
        }
    }

    fun exitPreviewMode() {
        if (!_uiState.value.isPreviewMode) return
        _uiState.update { prev ->
            prev.copy(
                isPreviewMode = false
            )
        }
    }

    fun refreshRecentProjects() {
        viewModelScope.launch {
            val items = projectsRepository.loadValidProjects()
            _uiState.update { it.copy(recentProjects = items) }
            items.forEach { item ->
                if (!_uiState.value.projectThumbnails.containsKey(item.id)) {
                    val thumb = projectsRepository.loadThumbnail(item)
                    if (thumb != null) {
                        _uiState.update { state ->
                            state.copy(projectThumbnails = state.projectThumbnails + (item.id to thumb))
                        }
                    }
                }
            }
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            if (projectsRepository.deleteProject(projectId)) {
                _uiState.update { state ->
                    val deletedActive = state.currentProjectId == projectId
                    state.copy(
                        recentProjects = state.recentProjects.filterNot { it.id == projectId },
                        projectThumbnails = state.projectThumbnails - projectId,
                        currentProjectId = if (deletedActive) null else state.currentProjectId,
                        currentLocalCopyPath = if (deletedActive) null else state.currentLocalCopyPath
                    )
                }
            }
        }
    }

    fun openImportPanel() {
        saveProjectNowAsync()
        _uiState.update {
            it.copy(
                showImportPanel = true,
                isVideoPlaying = false,
                isPreviewMode = false
            )
        }
        refreshRecentProjects()
    }

    fun saveProjectOnExit() {
        saveProjectNowAsync()
    }

    fun closeImportPanelIfLoaded() {
        if (_uiState.value.media != null) {
            _uiState.update { it.copy(showImportPanel = false) }
        }
    }

    fun importMedia(uri: Uri) {
        exportJob?.cancel()
        undoStack.clear()
        redoStack.clear()
        pendingGestureStartSnapshot = null

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingMedia = true,
                    isVideoPlaying = false,
                    isPreviewMode = false,
                    exportState = CropExportState.Idle,
                    message = null
                )
            }

            val initialResult = CropMediaInspector.inspect(appContext, uri)
            initialResult.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoadingMedia = false,
                        message = err.message ?: "Unable to import selected file."
                    )
                }
            }.onSuccess { inspectedInfo ->
                val projectId = UUID.randomUUID().toString()
                val shouldCopy = _uiState.value.copyOriginalSourceEnabled

                val localCopyPath = if (shouldCopy) {
                    projectsRepository.copySourceToInternalStorage(
                        projectId = projectId,
                        sourceUri = uri,
                        extension = inspectedInfo.extension
                    )
                } else {
                    null
                }

                val activeUri = if (localCopyPath != null) {
                    Uri.fromFile(File(localCopyPath))
                } else {
                    uri
                }

                val baseInfo = if (activeUri != uri) {
                    CropMediaInspector.inspect(appContext, activeUri).getOrDefault(inspectedInfo)
                        .copy(uri = activeUri)
                } else {
                    inspectedInfo
                }

                val info = if (baseInfo.is8kUhd) {
                    val wsResult = EightKVp9Pipeline.prepare8kVp9Workspace(
                        context = appContext,
                        projectId = projectId,
                        media = baseInfo
                    )
                    if (wsResult.isFailure) {
                        if (localCopyPath != null) runCatching { File(localCopyPath).delete() }
                        _uiState.update {
                            it.copy(
                                isLoadingMedia = false,
                                message = wsResult.exceptionOrNull()?.message
                                    ?: "8K Software VP9 import processing failed."
                            )
                        }
                        return@onSuccess
                    }
                    baseInfo.copy(vp9WorkspacePath = wsResult.getOrNull())
                } else {
                    baseInfo
                }

                val bitmap = if (info.mediaType == CropMediaType.IMAGE) {
                    ImageCropProcessor.loadPreviewBitmap(appContext, info)
                } else {
                    null
                }

                if (info.mediaType == CropMediaType.IMAGE && bitmap == null) {
                    if (localCopyPath != null) runCatching { File(localCopyPath).delete() }
                    _uiState.update {
                        it.copy(
                            isLoadingMedia = false,
                            message = "Unable to decode selected image."
                        )
                    }
                    return@onSuccess
                }

                val oldBitmap = _uiState.value.previewBitmap
                if (oldBitmap != null && oldBitmap !== bitmap) {
                    runCatching { oldBitmap.recycle() }
                }

                val firstVideoFormat = info.videoExportOptions.firstOrNull {
                    it.isOriginal && it.supportInfo.isSupported
                } ?: info.videoExportOptions.firstOrNull {
                    it.supportInfo.isSupported
                } ?: info.videoExportOptions.firstOrNull()

                val pipelineSupport = withContext(Dispatchers.Default) {
                    if (info.mediaType == CropMediaType.VIDEO) {
                        CropMediaInspector.inspectVideoPipelineSupport(
                            sourceVideoMime = info.sourceVideoMime,
                            container = firstVideoFormat?.container,
                            is8kUhd = info.is8kUhd
                        )
                    } else {
                        CodecPipelineSupport(
                            isHardwareDecoder = true,
                            isHardwareEncoder = true,
                            decoderName = "ImageDecoder",
                            encoderName = "Bitmap",
                            statusText = "✓ Hardware Image Decoder"
                        )
                    }
                }

                // Directly copy the video's original bitrate into Custom bitrate (no auto estimation!)
                val initialCustomMbps = if (info.mediaType == CropMediaType.VIDEO) {
                    val origBps = info.originalBitrateBps
                    if (origBps != null && origBps > 0L) {
                        (origBps / 1_000_000.0f).coerceIn(0f, 50f)
                    } else {
                        12f
                    }
                } else {
                    12f
                }

                val projectTitle = CropExportsRepository(appContext).formatClockDateTitle(System.currentTimeMillis())

                _uiState.update { prev ->
                    prev.copy(
                        isLoadingMedia = false,
                        media = info,
                        previewBitmap = bitmap,
                        showImportPanel = false,
                        currentProjectId = projectId,
                        currentProjectTitle = projectTitle,
                        currentSourceUriString = uri.toString(),
                        currentLocalCopyPath = localCopyPath,
                        cropRect = NormalizedCropRect(0f, 0f, 1f, 1f),
                        zoom = 1f,
                        panOffset = Offset.Zero,
                        isZoomEnabled = false,
                        isPreviewMode = false,
                        canvasAspectMode = CanvasAspectMode.ORIGINAL,
                        showCanvasOptions = false,
                        aspectRatio = CropAspectRatio.FREE,
                        customAspectWidth = "4",
                        customAspectHeight = "3",
                        customAspectError = null,
                        canUndo = false,
                        canRedo = false,
                        canReset = false,
                        isVideoPlaying = false,
                        isVideoMuted = false,
                        trimStartMs = 0L,
                        trimEndMs = info.durationMs,
                        currentVideoPositionMs = 0L,
                        seekRequestMs = null,
                        bitrateMode = VideoBitrateMode.ORIGINAL_PRESERVATION,
                        customBitrateMbps = initialCustomMbps,
                        selectedImageFormat = info.imageExportOptions.firstOrNull(),
                        selectedVideoFormat = firstVideoFormat,
                        pipelineSupport = pipelineSupport,
                        exportState = CropExportState.Idle
                    ).withDynamicSupport()
                }

                val record = buildProjectRecordFromState(_uiState.value)
                if (record != null) {
                    projectsRepository.upsertProject(record)
                    refreshRecentProjects()
                }
            }
        }
    }

    fun openProject(project: CropProjectRecord) {
        exportJob?.cancel()
        undoStack.clear()
        redoStack.clear()
        pendingGestureStartSnapshot = null

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingMedia = true,
                    isVideoPlaying = false,
                    isPreviewMode = false,
                    exportState = CropExportState.Idle,
                    message = null
                )
            }

            val accessible = projectsRepository.isProjectSourceAccessible(project)
            if (!accessible) {
                projectsRepository.deleteProject(project.id)
                refreshRecentProjects()
                _uiState.update {
                    it.copy(
                        isLoadingMedia = false,
                        message = "Original source media is no longer available in Gallery; project removed."
                    )
                }
                return@launch
            }

            val localFile = project.localCopyPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
            val uriToLoad = if (localFile != null) {
                Uri.fromFile(localFile)
            } else {
                Uri.parse(project.sourceUriString)
            }

            val result = CropMediaInspector.inspect(appContext, uriToLoad)
            result.onFailure { err ->
                val msg = err.message
                if (msg != null && msg.contains("8K", ignoreCase = true)) {
                    _uiState.update {
                        it.copy(
                            isLoadingMedia = false,
                            message = msg
                        )
                    }
                } else {
                    projectsRepository.deleteProject(project.id)
                    refreshRecentProjects()
                    _uiState.update {
                        it.copy(
                            isLoadingMedia = false,
                            message = "Unable to open project media; project removed."
                        )
                    }
                }
            }.onSuccess { inspected ->
                val baseInfo = inspected.copy(uri = uriToLoad)
                val info = if (baseInfo.is8kUhd) {
                    val wsResult = EightKVp9Pipeline.prepare8kVp9Workspace(
                        context = appContext,
                        projectId = project.id,
                        media = baseInfo,
                        existingWorkspacePath = project.vp9WorkspacePath
                    )
                    if (wsResult.isFailure) {
                        _uiState.update {
                            it.copy(
                                isLoadingMedia = false,
                                message = wsResult.exceptionOrNull()?.message
                                    ?: "8K Software VP9 playback preparation failed."
                            )
                        }
                        return@onSuccess
                    }
                    baseInfo.copy(vp9WorkspacePath = wsResult.getOrNull())
                } else {
                    baseInfo
                }

                val bitmap = if (info.mediaType == CropMediaType.IMAGE) {
                    ImageCropProcessor.loadPreviewBitmap(appContext, info)
                } else {
                    null
                }

                if (info.mediaType == CropMediaType.IMAGE && bitmap == null) {
                    _uiState.update {
                        it.copy(
                            isLoadingMedia = false,
                            message = "Unable to decode project image."
                        )
                    }
                    return@onSuccess
                }

                val oldBitmap = _uiState.value.previewBitmap
                if (oldBitmap != null && oldBitmap !== bitmap) {
                    runCatching { oldBitmap.recycle() }
                }

                val savedImageFormat = info.imageExportOptions.firstOrNull {
                    it.label == project.selectedFormatLabel
                } ?: info.imageExportOptions.firstOrNull()

                val savedVideoFormat = info.videoExportOptions.firstOrNull()

                val pipelineSupport = withContext(Dispatchers.Default) {
                    if (info.mediaType == CropMediaType.VIDEO) {
                        CropMediaInspector.inspectVideoPipelineSupport(
                            sourceVideoMime = info.sourceVideoMime,
                            container = com.example.framescope.crop.VideoContainerFormat.MP4,
                            is8kUhd = info.is8kUhd
                        )
                    } else {
                        CodecPipelineSupport(
                            isHardwareDecoder = true,
                            isHardwareEncoder = true,
                            decoderName = "ImageDecoder",
                            encoderName = "Bitmap",
                            statusText = "✓ Hardware Image Decoder"
                        )
                    }
                }

                val restoredCrop = NormalizedCropRect(
                    left = project.cropLeft.coerceIn(0f, 0.96f),
                    top = project.cropTop.coerceIn(0f, 0.96f),
                    right = project.cropRight.coerceIn(0.04f, 1f),
                    bottom = project.cropBottom.coerceIn(0.04f, 1f)
                )
                val restoredAspect = runCatching {
                    CropAspectRatio.valueOf(project.aspectRatioName)
                }.getOrDefault(CropAspectRatio.FREE)
                val restoredCanvasMode = runCatching {
                    CanvasAspectMode.valueOf(project.canvasAspectModeName)
                }.getOrDefault(CanvasAspectMode.ORIGINAL)

                val restoredTrimStart = project.trimStartMs.coerceIn(0L, info.durationMs)
                val restoredTrimEnd = if (project.trimEndMs > restoredTrimStart) {
                    project.trimEndMs.coerceAtMost(info.durationMs)
                } else {
                    info.durationMs
                }

                val origBps = info.originalBitrateBps
                val restoredBitrateMbps = if (origBps != null && origBps > 0L) {
                    (origBps / 1_000_000f).coerceIn(0f, 50f)
                } else {
                    12f
                }

                _uiState.update { prev ->
                    prev.copy(
                        isLoadingMedia = false,
                        media = info,
                        previewBitmap = bitmap,
                        showImportPanel = false,
                        currentProjectId = project.id,
                        currentProjectTitle = project.title,
                        currentSourceUriString = project.sourceUriString,
                        currentLocalCopyPath = project.localCopyPath,
                        cropRect = restoredCrop,
                        zoom = 1f,
                        panOffset = Offset.Zero,
                        isZoomEnabled = false,
                        isPreviewMode = false,
                        canvasAspectMode = restoredCanvasMode,
                        showCanvasOptions = false,
                        customCanvasWidth = project.customCanvasWidth,
                        customCanvasHeight = project.customCanvasHeight,
                        aspectRatio = restoredAspect,
                        customAspectWidth = project.customAspectWidth,
                        customAspectHeight = project.customAspectHeight,
                        customAspectError = null,
                        canUndo = false,
                        canRedo = false,
                        isVideoPlaying = false,
                        isVideoMuted = false,
                        trimStartMs = restoredTrimStart,
                        trimEndMs = restoredTrimEnd,
                        currentVideoPositionMs = restoredTrimStart,
                        seekRequestMs = if (info.mediaType == CropMediaType.VIDEO && restoredTrimStart > 0L) {
                            restoredTrimStart
                        } else {
                            null
                        },
                        bitrateMode = VideoBitrateMode.ORIGINAL_PRESERVATION,
                        customBitrateMbps = restoredBitrateMbps,
                        selectedImageFormat = savedImageFormat,
                        selectedVideoFormat = savedVideoFormat,
                        pipelineSupport = pipelineSupport,
                        exportState = CropExportState.Idle
                    ).withHistoryFlags().withDynamicSupport()
                }
            }
        }
    }

    fun toggleZoomMode() {
        _uiState.update { state ->
            val nextEnabled = !state.isZoomEnabled
            state.copy(
                isZoomEnabled = nextEnabled,
                showCanvasOptions = false
            )
        }
    }

    fun toggleCanvasOptions() {
        if (_uiState.value.isPreviewMode) return
        _uiState.update { state ->
            state.copy(showCanvasOptions = !state.showCanvasOptions)
        }
    }

    fun closeCanvasOptions() {
        _uiState.update { it.copy(showCanvasOptions = false) }
    }

    fun selectCanvasAspectMode(mode: CanvasAspectMode) {
        if (_uiState.value.isPreviewMode) return
        _uiState.update { state ->
            state.copy(
                canvasAspectMode = mode,
                showCanvasOptions = mode == CanvasAspectMode.CUSTOM
            )
        }
        persistCurrentProjectAsync()
    }

    fun updateCustomCanvasRatio(widthStr: String, heightStr: String) {
        val cleanW = widthStr.filter { it.isDigit() || it == '.' }
        val cleanH = heightStr.filter { it.isDigit() || it == '.' }
        _uiState.update {
            it.copy(
                canvasAspectMode = CanvasAspectMode.CUSTOM,
                customCanvasWidth = cleanW,
                customCanvasHeight = cleanH
            )
        }
        persistCurrentProjectAsync()
    }

    fun toggleVideoPlayPause() {
        _uiState.update { state ->
            if (state.media?.mediaType != CropMediaType.VIDEO) return@update state
            val nextPlay = !state.isVideoPlaying
            val curPos = state.currentVideoPositionMs
            val start = state.trimStartMs
            val end = if (state.trimEndMs > start) state.trimEndMs else state.media.durationMs
            val seekTarget = if (nextPlay && (curPos < start || (end > 0L && curPos >= end))) {
                start
            } else {
                null
            }
            state.copy(
                isVideoPlaying = nextPlay,
                seekRequestMs = seekTarget ?: state.seekRequestMs
            )
        }
    }

    fun setVideoPlaying(playing: Boolean) {
        _uiState.update { it.copy(isVideoPlaying = playing) }
    }

    fun toggleVideoMuted() {
        _uiState.update { it.copy(isVideoMuted = !it.isVideoMuted) }
    }

    fun updateVideoPosition(positionMs: Long) {
        _uiState.update { it.copy(currentVideoPositionMs = positionMs) }
    }

    fun seekVideo(positionMs: Long, autoPlay: Boolean) {
        val duration = _uiState.value.media?.durationMs ?: return
        val clamped = positionMs.coerceIn(0L, duration.coerceAtLeast(0L))
        _uiState.update { state ->
            state.copy(
                currentVideoPositionMs = clamped,
                seekRequestMs = clamped,
                isVideoPlaying = if (autoPlay) true else state.isVideoPlaying
            )
        }
    }

    fun clearSeekRequest() {
        _uiState.update { it.copy(seekRequestMs = null) }
    }

    fun updateTrimRange(startMs: Long, endMs: Long) {
        val duration = _uiState.value.media?.durationMs ?: return
        val minSpan = minOf(500L, duration)
        val clampedStart = startMs.coerceIn(0L, (duration - minSpan).coerceAtLeast(0L))
        val clampedEnd = endMs.coerceIn((clampedStart + minSpan).coerceAtMost(duration), duration)
        _uiState.update { state ->
            val adjustedPos = state.currentVideoPositionMs.coerceIn(clampedStart, clampedEnd)
            state.copy(
                trimStartMs = clampedStart,
                trimEndMs = clampedEnd,
                currentVideoPositionMs = adjustedPos
            )
        }
        persistCurrentProjectAsync()
    }

    fun updateTransform(newZoom: Float, newPan: Offset) {
        val clampedZoom = newZoom.coerceIn(1f, 5f)
        _uiState.update { state ->
            state.copy(
                zoom = clampedZoom,
                panOffset = if (clampedZoom <= 1.001f) Offset.Zero else newPan
            )
        }
    }

    fun updatePan(newPan: Offset) {
        _uiState.update { it.copy(panOffset = newPan) }
    }

    fun updateCropRect(newCrop: NormalizedCropRect) {
        _uiState.update { state ->
            val sanitized = NormalizedCropRect(
                left = newCrop.left.coerceIn(0f, 0.96f),
                top = newCrop.top.coerceIn(0f, 0.96f),
                right = newCrop.right.coerceIn(0.04f, 1f),
                bottom = newCrop.bottom.coerceIn(0.04f, 1f)
            )
            state.copy(cropRect = sanitized).withHistoryFlags()
        }
    }

    fun selectAspectRatio(ratio: CropAspectRatio) {
        val stateBefore = _uiState.value
        val info = stateBefore.media ?: return

        val prevSnap = stateBefore.toSnapshot()
        if (ratio == CropAspectRatio.CUSTOM) {
            val parsedW = stateBefore.customAspectWidth.toFloatOrNull()
            val parsedH = stateBefore.customAspectHeight.toFloatOrNull()
            val targetRatio = if (parsedW != null && parsedH != null && parsedW > 0f && parsedH > 0f) {
                parsedW / parsedH
            } else {
                1f
            }
            val centered = computeCenteredCropRect(info.displayWidth, info.displayHeight, targetRatio)
            val nextState = stateBefore.copy(
                aspectRatio = CropAspectRatio.CUSTOM,
                cropRect = centered,
                customAspectError = null
            )
            pushHistory(prevSnap, nextState.toSnapshot())
            _uiState.value = nextState.withHistoryFlags().withDynamicSupport()
            persistCurrentProjectAsync()
            return
        }

        val centered = if (ratio == CropAspectRatio.FREE) {
            stateBefore.cropRect
        } else {
            computeCenteredCropRect(info.displayWidth, info.displayHeight, ratio.ratio!!)
        }

        val nextState = stateBefore.copy(
            aspectRatio = ratio,
            cropRect = centered,
            customAspectError = null
        )
        pushHistory(prevSnap, nextState.toSnapshot())
        _uiState.value = nextState.withHistoryFlags().withDynamicSupport()
        persistCurrentProjectAsync()
    }

    fun updateCustomAspectRatio(widthStr: String, heightStr: String) {
        val stateBefore = _uiState.value
        val cleanW = widthStr.filter { it.isDigit() || it == '.' }
        val cleanH = heightStr.filter { it.isDigit() || it == '.' }
        val wVal = cleanW.toFloatOrNull()
        val hVal = cleanH.toFloatOrNull()
        val info = stateBefore.media

        if (wVal == null || hVal == null || wVal <= 0f || hVal <= 0f) {
            _uiState.update {
                it.copy(
                    aspectRatio = CropAspectRatio.CUSTOM,
                    customAspectWidth = cleanW,
                    customAspectHeight = cleanH,
                    customAspectError = "Enter positive ratio numbers"
                ).withDynamicSupport()
            }
            return
        }

        val prevSnap = stateBefore.toSnapshot()
        val targetRatio = (wVal / hVal).coerceIn(0.05f, 20f)
        val centered = if (info != null) {
            computeCenteredCropRect(info.displayWidth, info.displayHeight, targetRatio)
        } else {
            NormalizedCropRect()
        }

        val nextState = stateBefore.copy(
            aspectRatio = CropAspectRatio.CUSTOM,
            customAspectWidth = cleanW,
            customAspectHeight = cleanH,
            customAspectError = null,
            cropRect = centered
        )
        pushHistory(prevSnap, nextState.toSnapshot())
        _uiState.value = nextState.withHistoryFlags().withDynamicSupport()
        persistCurrentProjectAsync()
    }

    fun selectBitrateMode(mode: VideoBitrateMode) {
        _uiState.update {
            it.copy(bitrateMode = mode).withDynamicSupport()
        }
        persistCurrentProjectAsync()
    }

    fun setManualCustomBitrate(mbps: Float) {
        val clamped = mbps.coerceIn(0f, 50f)
        _uiState.update {
            it.copy(
                bitrateMode = VideoBitrateMode.CUSTOM,
                customBitrateMbps = clamped
            ).withDynamicSupport()
        }
        persistCurrentProjectAsync()
    }

    fun selectImageFormat(option: ImageExportFormatOption) {
        _uiState.update { it.copy(selectedImageFormat = option) }
        persistCurrentProjectAsync()
    }

    fun selectVideoFormat(option: VideoExportFormatOption) {
        val media = _uiState.value.media
        viewModelScope.launch {
            val effectiveContainer = if (option.supportInfo.isSupported) {
                option.container
            } else {
                media?.videoExportOptions?.firstOrNull { it.supportInfo.isSupported }?.container
                    ?: com.example.framescope.crop.VideoContainerFormat.MP4
            }
            val support = withContext(Dispatchers.Default) {
                CropMediaInspector.inspectVideoPipelineSupport(
                    sourceVideoMime = media?.sourceVideoMime,
                    container = effectiveContainer,
                    is8kUhd = media?.is8kUhd == true
                )
            }
            _uiState.update {
                it.copy(
                    selectedVideoFormat = option,
                    pipelineSupport = support
                ).withDynamicSupport()
            }
            persistCurrentProjectAsync()
        }
    }

    fun startExport() {
        val state = _uiState.value
        val media = state.media ?: return
        if (state.exportState is CropExportState.Exporting) return

        _uiState.update { it.copy(isVideoPlaying = false) }

        exportJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    exportState = CropExportState.Exporting(0.02f, "Starting export…"),
                    message = null
                )
            }

            val formatLabel = if (media.mediaType == CropMediaType.VIDEO) {
                "MP4"
            } else {
                val chosen = state.selectedImageFormat
                if (chosen != null && !chosen.supportInfo.isSupported && !chosen.supportInfo.alternativeName.isNullOrBlank()) {
                    chosen.supportInfo.alternativeName
                } else {
                    chosen?.label ?: "PNG"
                }
            }

            val result = when (media.mediaType) {
                CropMediaType.IMAGE -> {
                    val format = state.selectedImageFormat ?: media.imageExportOptions.first()
                    _uiState.update {
                        it.copy(exportState = CropExportState.Exporting(0.35f, "Cropping image…"))
                    }
                    ImageCropProcessor.exportCroppedImage(
                        context = appContext,
                        media = media,
                        cropRect = state.cropRect,
                        formatOption = format
                    )
                }
                CropMediaType.VIDEO -> {
                    val effectiveFormat = media.videoExportOptions.firstOrNull()
                        ?: com.example.framescope.crop.VideoExportFormatOption(
                            label = "MP4",
                            container = com.example.framescope.crop.VideoContainerFormat.MP4,
                            isOriginal = media.extension.equals("mp4", ignoreCase = true)
                        )
                    val orig = media.originalBitrateBps
                    val targetBps = if (orig != null && orig > 0L) {
                        orig.coerceIn(250_000L, 100_000_000L).toInt()
                    } else {
                        (state.outputWidth.toLong() * state.outputHeight.toLong() * 6L)
                            .coerceIn(1_500_000L, 25_000_000L)
                            .toInt()
                    }

                    VideoCropProcessor.exportCroppedVideo(
                        context = appContext,
                        media = media,
                        cropRect = state.cropRect,
                        formatOption = effectiveFormat,
                        targetBitrateBps = targetBps,
                        trimStartMs = state.trimStartMs,
                        trimEndMs = state.trimEndMs,
                        allowLosslessPassthrough = true,
                        onProgress = { progress, text ->
                            _uiState.update {
                                it.copy(exportState = CropExportState.Exporting(progress, text))
                            }
                        }
                    )
                }
            }

            _uiState.update { it.copy(exportState = result) }
            if (result is CropExportState.Success) {
                val now = System.currentTimeMillis()
                val currentSnapshotState = _uiState.value
                val resolvedFormatLabel = if (media.is8kUhd && result.fileName.endsWith(".webm", ignoreCase = true)) {
                    "VP9"
                } else {
                    formatLabel
                }
                val record = buildProjectRecordFromState(currentSnapshotState)?.copy(
                    exportedFilePath = result.outputPath,
                    exportedGalleryUriString = result.galleryUriString,
                    exportedFormatLabel = resolvedFormatLabel,
                    exportedWidth = currentSnapshotState.outputWidth,
                    exportedHeight = currentSnapshotState.outputHeight,
                    exportedDurationMs = if (media.mediaType == CropMediaType.VIDEO) {
                        (currentSnapshotState.trimEndMs - currentSnapshotState.trimStartMs).coerceAtLeast(0L)
                    } else {
                        0L
                    },
                    updatedAtMs = now,
                    exportedAtMs = now
                )
                if (record != null) {
                    projectsRepository.upsertProject(record)
                    // Refresh thumbnail cleanly for updated export
                    _uiState.update { s ->
                        s.copy(projectThumbnails = s.projectThumbnails - record.id)
                    }
                    refreshRecentProjects()
                }
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
        _uiState.update { it.copy(exportState = CropExportState.Idle) }
    }

    fun dismissExportState() {
        _uiState.update { it.copy(exportState = CropExportState.Idle) }
    }

    fun cancelShareProjectExport() {
        shareExportJob?.cancel()
        shareExportJob = null
        _uiState.update {
            it.copy(
                isSharingProjectExport = false,
                shareExportProgress = 0f,
                shareExportStatus = ""
            )
        }
    }

    fun shareProjectFromViewer(
        project: CropProjectRecord,
        onShareReady: (Uri, String, CropProjectRecord) -> Unit
    ) {
        if (_uiState.value.isSharingProjectExport) return
        val authority = "${appContext.packageName}.fileprovider"
        val defaultMime = if (project.mediaType == CropMediaType.VIDEO) "video/mp4" else "image/*"

        val expPath = project.exportedFilePath
        if (!expPath.isNullOrBlank()) {
            val file = File(expPath)
            if (file.exists() && file.length() > 0L) {
                val uri = runCatching { FileProvider.getUriForFile(appContext, authority, file) }.getOrNull()
                if (uri != null) {
                    val mime = MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(file.extension.lowercase(Locale.US))
                        ?: defaultMime
                    onShareReady(uri, mime, project)
                    return
                }
            }
        }

        val galleryStr = project.exportedGalleryUriString
        if (!galleryStr.isNullOrBlank()) {
            val uri = runCatching { Uri.parse(galleryStr) }.getOrNull()
            if (uri != null) {
                val readable = runCatching {
                    appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
                }.getOrDefault(false)
                if (readable) {
                    val mime = appContext.contentResolver.getType(uri) ?: defaultMime
                    onShareReady(uri, mime, project)
                    return
                }
            }
        }

        // Draft project has not been exported yet: export with saved crop & trim, then launch Share
        shareExportJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSharingProjectExport = true,
                    shareExportProgress = 0.05f,
                    shareExportStatus = "Exporting project to share…",
                    message = null
                )
            }

            val localFile = project.localCopyPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
            val sourceUri = if (localFile != null) {
                Uri.fromFile(localFile)
            } else {
                Uri.parse(project.sourceUriString)
            }

            val inspectedResult = CropMediaInspector.inspect(appContext, sourceUri)
            val inspected = inspectedResult.getOrNull()?.copy(uri = sourceUri)
            if (inspected == null) {
                _uiState.update {
                    it.copy(
                        isSharingProjectExport = false,
                        message = "Unable to access source media for export."
                    )
                }
                return@launch
            }

            val crop = NormalizedCropRect(
                left = project.cropLeft.coerceIn(0f, 0.96f),
                top = project.cropTop.coerceIn(0f, 0.96f),
                right = project.cropRight.coerceIn(0.04f, 1f),
                bottom = project.cropBottom.coerceIn(0.04f, 1f)
            )
            val outW = (crop.widthFraction * inspected.displayWidth).roundToInt().let { raw ->
                if (inspected.mediaType == CropMediaType.VIDEO) (raw and 1.inv()).coerceIn(16, inspected.displayWidth)
                else raw.coerceIn(1, inspected.displayWidth)
            }
            val outH = (crop.heightFraction * inspected.displayHeight).roundToInt().let { raw ->
                if (inspected.mediaType == CropMediaType.VIDEO) (raw and 1.inv()).coerceIn(16, inspected.displayHeight)
                else raw.coerceIn(1, inspected.displayHeight)
            }

            val formatLabel = if (inspected.mediaType == CropMediaType.VIDEO) {
                "MP4"
            } else {
                inspected.imageExportOptions.firstOrNull { it.label == project.selectedFormatLabel }?.label
                    ?: inspected.imageExportOptions.firstOrNull()?.label
                    ?: "PNG"
            }

            val result = when (inspected.mediaType) {
                CropMediaType.IMAGE -> {
                    val format = inspected.imageExportOptions.firstOrNull {
                        it.label == project.selectedFormatLabel
                    } ?: inspected.imageExportOptions.first()
                    _uiState.update {
                        it.copy(shareExportProgress = 0.4f, shareExportStatus = "Exporting photo…")
                    }
                    ImageCropProcessor.exportCroppedImage(
                        context = appContext,
                        media = inspected,
                        cropRect = crop,
                        formatOption = format
                    )
                }

                CropMediaType.VIDEO -> {
                    val format = inspected.videoExportOptions.firstOrNull()
                        ?: com.example.framescope.crop.VideoExportFormatOption(
                            label = "MP4",
                            container = com.example.framescope.crop.VideoContainerFormat.MP4,
                            isOriginal = inspected.extension.equals("mp4", ignoreCase = true)
                        )
                    val orig = inspected.originalBitrateBps
                    val targetBps = if (orig != null && orig > 0L) {
                        orig.coerceIn(250_000L, 100_000_000L).toInt()
                    } else {
                        (outW.toLong() * outH.toLong() * 6L).coerceIn(1_500_000L, 25_000_000L).toInt()
                    }
                    val trimStart = project.trimStartMs.coerceIn(0L, inspected.durationMs)
                    val trimEnd = if (project.trimEndMs > trimStart) {
                        project.trimEndMs.coerceAtMost(inspected.durationMs)
                    } else {
                        inspected.durationMs
                    }
                    VideoCropProcessor.exportCroppedVideo(
                        context = appContext,
                        media = inspected,
                        cropRect = crop,
                        formatOption = format,
                        targetBitrateBps = targetBps,
                        trimStartMs = trimStart,
                        trimEndMs = trimEnd,
                        allowLosslessPassthrough = true,
                        onProgress = { progress, text ->
                            _uiState.update {
                                it.copy(
                                    shareExportProgress = progress,
                                    shareExportStatus = text
                                )
                            }
                        }
                    )
                }
            }

            when (result) {
                is CropExportState.Success -> {
                    val now = System.currentTimeMillis()
                    val trimDuration = if (inspected.mediaType == CropMediaType.VIDEO) {
                        val s = project.trimStartMs.coerceIn(0L, inspected.durationMs)
                        val e = if (project.trimEndMs > s) project.trimEndMs.coerceAtMost(inspected.durationMs) else inspected.durationMs
                        (e - s).coerceAtLeast(0L)
                    } else {
                        0L
                    }
                    val resolvedFormatLabel = if (inspected.is8kUhd && result.fileName.endsWith(".webm", ignoreCase = true)) {
                        "VP9"
                    } else {
                        formatLabel
                    }
                    val updatedRecord = project.copy(
                        exportedFilePath = result.outputPath,
                        exportedGalleryUriString = result.galleryUriString,
                        exportedFormatLabel = resolvedFormatLabel,
                        exportedWidth = outW,
                        exportedHeight = outH,
                        exportedDurationMs = trimDuration,
                        updatedAtMs = now,
                        exportedAtMs = now
                    )
                    projectsRepository.upsertProject(updatedRecord)
                    refreshRecentProjects()

                    val outFile = File(result.outputPath)
                    val shareUri = runCatching { FileProvider.getUriForFile(appContext, authority, outFile) }.getOrNull()
                        ?: result.galleryUriString?.let { runCatching { Uri.parse(it) }.getOrNull() }
                    val mime = MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(outFile.extension.lowercase(Locale.US))
                        ?: defaultMime

                    _uiState.update {
                        it.copy(
                            isSharingProjectExport = false,
                            shareExportProgress = 0f,
                            shareExportStatus = ""
                        )
                    }
                    if (shareUri != null) {
                        onShareReady(shareUri, mime, updatedRecord)
                    } else {
                        _uiState.update { it.copy(message = "Export finished, but unable to prepare share link.") }
                    }
                }

                is CropExportState.Error -> {
                    _uiState.update {
                        it.copy(
                            isSharingProjectExport = false,
                            shareExportProgress = 0f,
                            shareExportStatus = "",
                            message = result.message
                        )
                    }
                }

                else -> {
                    _uiState.update {
                        it.copy(
                            isSharingProjectExport = false,
                            shareExportProgress = 0f,
                            shareExportStatus = ""
                        )
                    }
                }
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun reportPlaybackError(errorMsg: String) {
        _uiState.update {
            it.copy(
                isVideoPlaying = false,
                message = errorMsg
            )
        }
    }

    private fun saveProjectNowAsync() {
        val record = buildProjectRecordFromState(_uiState.value) ?: return
        viewModelScope.launch {
            projectsRepository.upsertProject(record)
        }
    }

    private fun persistCurrentProjectAsync() {
        val record = buildProjectRecordFromState(_uiState.value) ?: return
        viewModelScope.launch {
            projectsRepository.upsertProject(record)
            _uiState.update { s ->
                val updated = s.recentProjects.toMutableList()
                val idx = updated.indexOfFirst { it.id == record.id }
                if (idx >= 0) {
                    updated[idx] = record
                } else {
                    updated.add(0, record)
                }
                s.copy(recentProjects = updated.sortedByDescending { it.updatedAtMs })
            }
        }
    }

    private fun buildProjectRecordFromState(state: CropToolUiState): CropProjectRecord? {
        val media = state.media ?: return null
        val projectId = state.currentProjectId ?: return null
        val sourceUriStr = state.currentSourceUriString ?: media.uri.toString()
        val existing = state.recentProjects.firstOrNull { it.id == projectId }
        val formatLabel = if (media.mediaType == CropMediaType.VIDEO) {
            state.selectedVideoFormat?.label
        } else {
            state.selectedImageFormat?.label
        }

        val resolvedTitle = existing?.title
            ?: state.currentProjectTitle
            ?: CropExportsRepository(appContext).formatClockDateTitle(System.currentTimeMillis())

        return CropProjectRecord(
            id = projectId,
            title = resolvedTitle,
            mediaTypeName = media.mediaType.name,
            sourceUriString = sourceUriStr,
            localCopyPath = state.currentLocalCopyPath,
            cropLeft = state.cropRect.left,
            cropTop = state.cropRect.top,
            cropRight = state.cropRect.right,
            cropBottom = state.cropRect.bottom,
            aspectRatioName = state.aspectRatio.name,
            customAspectWidth = state.customAspectWidth,
            customAspectHeight = state.customAspectHeight,
            canvasAspectModeName = state.canvasAspectMode.name,
            customCanvasWidth = state.customCanvasWidth,
            customCanvasHeight = state.customCanvasHeight,
            trimStartMs = state.trimStartMs,
            trimEndMs = state.trimEndMs,
            bitrateModeName = state.bitrateMode.name,
            customBitrateMbps = state.customBitrateMbps,
            selectedFormatLabel = formatLabel,
            outputWidth = state.outputWidth,
            outputHeight = state.outputHeight,
            durationMs = media.durationMs,
            exportedFilePath = existing?.exportedFilePath,
            exportedGalleryUriString = existing?.exportedGalleryUriString,
            exportedFormatLabel = existing?.exportedFormatLabel,
            exportedWidth = existing?.exportedWidth ?: 0,
            exportedHeight = existing?.exportedHeight ?: 0,
            exportedDurationMs = existing?.exportedDurationMs ?: 0L,
            updatedAtMs = System.currentTimeMillis(),
            exportedAtMs = existing?.exportedAtMs,
            vp9WorkspacePath = media.vp9WorkspacePath
        )
    }

    private fun computeCenteredCropRect(
        dispW: Int,
        dispH: Int,
        targetRatio: Float
    ): NormalizedCropRect {
        val srcRatio = dispW.toFloat() / dispH.toFloat().coerceAtLeast(1f)
        val (wFrac, hFrac) = if (targetRatio >= srcRatio) {
            1f to (srcRatio / targetRatio).coerceIn(0.05f, 1f)
        } else {
            (targetRatio / srcRatio).coerceIn(0.05f, 1f) to 1f
        }
        val left = (1f - wFrac) / 2f
        val top = (1f - hFrac) / 2f
        return NormalizedCropRect(
            left = left,
            top = top,
            right = left + wFrac,
            bottom = top + hFrac
        )
    }

    override fun onCleared() {
        exportJob?.cancel()
        runCatching { _uiState.value.previewBitmap?.recycle() }
        _uiState.value.projectThumbnails.values.forEach { bmp ->
            runCatching { bmp.recycle() }
        }
        super.onCleared()
    }

    class Factory(private val appContext: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CropToolViewModel(appContext.applicationContext) as T
        }
    }

    private companion object {
        const val MAX_HISTORY_ENTRIES = 60
    }
}
