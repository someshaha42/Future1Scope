package com.example.framescope.crop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.example.framescope.MainActivity
import com.example.framescope.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

class CropExportForegroundService : Service() {

    companion object {
        const val ACTION_START_EXPORT = "com.example.framescope.crop.START_EXPORT"
        const val ACTION_START_SHARE_EXPORT = "com.example.framescope.crop.START_SHARE_EXPORT"
        const val ACTION_CANCEL_EXPORT = "com.example.framescope.crop.CANCEL_EXPORT"

        private const val CHANNEL_ID = "framescope_crop_export"
        private const val PROGRESS_NOTIFICATION_ID = 4201
        private const val COMPLETE_NOTIFICATION_ID = 4202
        private const val NOTIFY_MIN_INTERVAL_MS = 400L
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    @Volatile
    private var lastNotifyMs = 0L

    @Volatile
    private var lastNotifyPct = -1

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_EXPORT -> {
                val request = CropExportManager.consumeWorkspaceRequest()
                if (request == null) {
                    if (activeJob?.isActive != true) {
                        stopSelf()
                    }
                    return START_NOT_STICKY
                }
                startExportForeground(
                    title = getString(R.string.export_notification_title),
                    statusText = "Starting export…",
                    progress = 0.02f
                )
                activeJob?.cancel()
                activeJob = serviceScope.launch {
                    runWorkspaceExport(request)
                }
            }

            ACTION_START_SHARE_EXPORT -> {
                val request = CropExportManager.consumeShareRequest()
                if (request == null) {
                    if (activeJob?.isActive != true) {
                        stopSelf()
                    }
                    return START_NOT_STICKY
                }
                startExportForeground(
                    title = getString(R.string.export_notification_title),
                    statusText = "Exporting project to share…",
                    progress = 0.05f
                )
                activeJob?.cancel()
                activeJob = serviceScope.launch {
                    runShareExport(request)
                }
            }

            ACTION_CANCEL_EXPORT -> {
                cancelActiveExport()
            }

            else -> {
                if (activeJob?.isActive != true) {
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startExportForeground(
        title: String,
        statusText: String,
        progress: Float
    ) {
        createNotificationChannel()
        lastNotifyMs = System.currentTimeMillis()
        lastNotifyPct = (progress * 100f).toInt().coerceIn(0, 100)
        val notification = buildProgressNotification(
            title = title,
            statusText = statusText,
            progress = progress
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            startForeground(
                PROGRESS_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
        } else {
            startForeground(
                PROGRESS_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        }
    }

    private suspend fun runWorkspaceExport(request: CropWorkspaceExportRequest) {
        val media = request.media
        var finalState: CropExportState = CropExportState.Idle
        try {
            val result = when (media.mediaType) {
                CropMediaType.IMAGE -> {
                    val format = request.imageFormat ?: media.imageExportOptions.first()
                    val status = "Cropping image…"
                    CropExportManager.updateExportState(CropExportState.Exporting(0.35f, status))
                    maybeUpdateProgressNotification(
                        title = getString(R.string.export_notification_title),
                        statusText = status,
                        progress = 0.35f,
                        force = true
                    )
                    ImageCropProcessor.exportCroppedImage(
                        context = applicationContext,
                        media = media,
                        cropRect = request.cropRect,
                        formatOption = format
                    )
                }

                CropMediaType.VIDEO -> {
                    val effectiveFormat = request.videoFormat
                        ?: media.videoExportOptions.firstOrNull()
                        ?: VideoExportFormatOption(
                            label = "MP4",
                            container = VideoContainerFormat.MP4,
                            isOriginal = media.extension.equals("mp4", ignoreCase = true)
                        )
                    val orig = media.originalBitrateBps
                    val targetBps = if (orig != null && orig > 0L) {
                        orig.coerceIn(250_000L, Int.MAX_VALUE.toLong()).toInt()
                    } else {
                        (request.outputWidth.toLong() * request.outputHeight.toLong() * 6L)
                            .coerceIn(1_500_000L, Int.MAX_VALUE.toLong())
                            .toInt()
                    }
                    VideoCropProcessor.exportCroppedVideo(
                        context = applicationContext,
                        media = media,
                        cropRect = request.cropRect,
                        formatOption = effectiveFormat,
                        targetBitrateBps = targetBps,
                        trimStartMs = request.trimStartMs,
                        trimEndMs = request.trimEndMs,
                        allowLosslessPassthrough = true,
                        onProgress = { progress, text ->
                            CropExportManager.updateExportState(
                                CropExportState.Exporting(progress, text)
                            )
                            maybeUpdateProgressNotification(
                                title = getString(R.string.export_notification_title),
                                statusText = text,
                                progress = progress
                            )
                        }
                    )
                }
            }

            finalState = result
            if (result is CropExportState.Success) {
                val now = System.currentTimeMillis()
                val updatedRecord = request.projectRecord?.copy(
                    exportedFilePath = result.outputPath,
                    exportedGalleryUriString = result.galleryUriString,
                    exportedFormatLabel = request.formatLabel,
                    exportedWidth = request.outputWidth,
                    exportedHeight = request.outputHeight,
                    exportedDurationMs = if (media.mediaType == CropMediaType.VIDEO) {
                        (request.trimEndMs - request.trimStartMs).coerceAtLeast(0L)
                    } else {
                        0L
                    },
                    updatedAtMs = now,
                    exportedAtMs = now
                )
                if (updatedRecord != null) {
                    CropProjectsRepository(applicationContext).upsertProject(updatedRecord)
                    CropExportManager.notifyProjectUpdated(updatedRecord.id)
                }
                postCompletionNotification(
                    title = getString(R.string.export_notification_complete_title),
                    text = if (result.savedToGallery) {
                        "${result.fileName} saved to Gallery"
                    } else {
                        "${result.fileName} ready"
                    }
                )
            } else if (result is CropExportState.Error) {
                postCompletionNotification(
                    title = "Export Failed",
                    text = result.message
                )
            }

            CropExportManager.updateExportState(result)
        } catch (_: CancellationException) {
            CropExportManager.resetExportState()
        } catch (t: Throwable) {
            val err = CropExportState.Error(t.message ?: "Failed to crop and export media.")
            finalState = err
            CropExportManager.updateExportState(err)
            postCompletionNotification(
                title = "Export Failed",
                text = err.message
            )
        } finally {
            finishForegroundSession(finalState)
        }
    }

    private suspend fun runShareExport(request: CropShareExportRequest) {
        val project = request.project
        val authority = "${applicationContext.packageName}.fileprovider"
        val defaultMime = if (project.mediaType == CropMediaType.VIDEO) "video/mp4" else "image/*"
        try {
            val localFile = project.localCopyPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
            val sourceUri = if (localFile != null) {
                Uri.fromFile(localFile)
            } else {
                Uri.parse(project.sourceUriString)
            }

            val inspected = CropMediaInspector.inspect(applicationContext, sourceUri)
                .getOrNull()
                ?.copy(uri = sourceUri)
            if (inspected == null) {
                CropExportManager.failShareExport("Unable to access source media for export.")
                return
            }

            val safeLeft = project.cropLeft.coerceIn(0f, 0.9999f)
            val safeTop = project.cropTop.coerceIn(0f, 0.9999f)
            val crop = NormalizedCropRect(
                left = safeLeft,
                top = safeTop,
                right = project.cropRight.coerceIn(safeLeft + 0.0001f, 1f),
                bottom = project.cropBottom.coerceIn(safeTop + 0.0001f, 1f)
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
                    CropExportManager.updateShareProgress(0.4f, "Exporting photo…")
                    maybeUpdateProgressNotification(
                        title = getString(R.string.export_notification_title),
                        statusText = "Exporting photo…",
                        progress = 0.4f,
                        force = true
                    )
                    ImageCropProcessor.exportCroppedImage(
                        context = applicationContext,
                        media = inspected,
                        cropRect = crop,
                        formatOption = format
                    )
                }

                CropMediaType.VIDEO -> {
                    val format = inspected.videoExportOptions.firstOrNull()
                        ?: VideoExportFormatOption(
                            label = "MP4",
                            container = VideoContainerFormat.MP4,
                            isOriginal = inspected.extension.equals("mp4", ignoreCase = true)
                        )
                    val orig = inspected.originalBitrateBps
                    val targetBps = if (orig != null && orig > 0L) {
                        orig.coerceIn(250_000L, Int.MAX_VALUE.toLong()).toInt()
                    } else {
                        (outW.toLong() * outH.toLong() * 6L)
                            .coerceIn(1_500_000L, Int.MAX_VALUE.toLong())
                            .toInt()
                    }
                    val trimStart = project.trimStartMs.coerceIn(0L, inspected.durationMs)
                    val trimEnd = if (project.trimEndMs > trimStart) {
                        project.trimEndMs.coerceAtMost(inspected.durationMs)
                    } else {
                        inspected.durationMs
                    }
                    VideoCropProcessor.exportCroppedVideo(
                        context = applicationContext,
                        media = inspected,
                        cropRect = crop,
                        formatOption = format,
                        targetBitrateBps = targetBps,
                        trimStartMs = trimStart,
                        trimEndMs = trimEnd,
                        allowLosslessPassthrough = true,
                        onProgress = { progress, text ->
                            CropExportManager.updateShareProgress(progress, text)
                            maybeUpdateProgressNotification(
                                title = getString(R.string.export_notification_title),
                                statusText = text,
                                progress = progress
                            )
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
                    val updatedRecord = project.copy(
                        exportedFilePath = result.outputPath,
                        exportedGalleryUriString = result.galleryUriString,
                        exportedFormatLabel = formatLabel,
                        exportedWidth = outW,
                        exportedHeight = outH,
                        exportedDurationMs = trimDuration,
                        updatedAtMs = now,
                        exportedAtMs = now
                    )
                    CropProjectsRepository(applicationContext).upsertProject(updatedRecord)
                    CropExportManager.notifyProjectUpdated(updatedRecord.id)

                    val outFile = File(result.outputPath)
                    val shareUri = runCatching { FileProvider.getUriForFile(applicationContext, authority, outFile) }.getOrNull()
                        ?: result.galleryUriString?.let { runCatching { Uri.parse(it) }.getOrNull() }
                    val mime = MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(outFile.extension.lowercase(Locale.US))
                        ?: defaultMime

                    if (shareUri != null) {
                        CropExportManager.completeShareSuccess(
                            CropShareExportReady(
                                shareUriString = shareUri.toString(),
                                mimeType = mime,
                                updatedProject = updatedRecord
                            )
                        )
                        postCompletionNotification(
                            title = getString(R.string.export_notification_complete_title),
                            text = "${result.fileName} is ready to share"
                        )
                    } else {
                        CropExportManager.failShareExport("Export finished, but unable to prepare share link.")
                    }
                }

                is CropExportState.Error -> {
                    CropExportManager.failShareExport(result.message)
                    postCompletionNotification(
                        title = "Export Failed",
                        text = result.message
                    )
                }

                else -> {
                    CropExportManager.resetShareExportState()
                }
            }
        } catch (_: CancellationException) {
            CropExportManager.resetShareExportState()
        } catch (t: Throwable) {
            CropExportManager.failShareExport(t.message ?: "Failed to export project.")
        } finally {
            finishForegroundSession(CropExportState.Idle)
        }
    }

    private fun cancelActiveExport() {
        activeJob?.cancel()
        activeJob = null
        CropExportManager.resetExportState()
        CropExportManager.resetShareExportState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun finishForegroundSession(@Suppress("UNUSED_PARAMETER") state: CropExportState) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        cancelActiveExport()
        super.onTimeout(startId, fgsType)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.export_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun maybeUpdateProgressNotification(
        title: String,
        statusText: String,
        progress: Float,
        force: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        val pct = (progress * 100f).toInt().coerceIn(0, 100)
        if (!force && ((now - lastNotifyMs < NOTIFY_MIN_INTERVAL_MS) || (pct == lastNotifyPct && now - lastNotifyMs < 1_000L))) {
            return
        }
        lastNotifyMs = now
        lastNotifyPct = pct
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        runCatching {
            nm.notify(
                PROGRESS_NOTIFICATION_ID,
                buildProgressNotification(title, statusText, progress)
            )
        }
    }

    private fun buildProgressNotification(
        title: String,
        statusText: String,
        progress: Float
    ): Notification {
        val pct = (progress * 100f).toInt().coerceIn(0, 100)
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            10,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, CropExportForegroundService::class.java).apply {
            action = ACTION_CANCEL_EXPORT
        }
        val cancelPending = PendingIntent.getService(
            this,
            11,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelAction = Notification.Action.Builder(
            null,
            getString(R.string.export_notification_cancel),
            cancelPending
        ).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("$statusText • $pct%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openPending)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, pct, false)
            .addAction(cancelAction)
            .build()
    }

    private fun postCompletionNotification(title: String, text: String) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            12,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(openPending)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(COMPLETE_NOTIFICATION_ID, notification) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
