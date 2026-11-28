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
import android.text.format.DateFormat
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

class CropExportForegroundService : Service() {

    companion object {
        const val ACTION_START_EXPORT = "com.example.framescope.crop.START_EXPORT"
        const val ACTION_START_SHARE_EXPORT = "com.example.framescope.crop.START_SHARE_EXPORT"
        const val ACTION_CANCEL_EXPORT = "com.example.framescope.crop.CANCEL_EXPORT"
        const val EXTRA_JOB_ID = "com.example.framescope.crop.EXTRA_JOB_ID"

        private const val CHANNEL_ID = "framescope_crop_export"
        private const val SHARE_JOB_KEY = "__share__"
        private const val SHARE_NOTIFICATION_ID = 4205
        private const val SHARE_COMPLETE_NOTIFICATION_ID = 4206
        private const val COMPLETE_NOTIFICATION_OFFSET = 10000
        private const val NOTIFY_MIN_INTERVAL_MS = 350L
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeNotificationIds = ConcurrentHashMap<String, Int>()
    private val lastNotifyByJobId = ConcurrentHashMap<String, Pair<Long, Int>>()
    private val foregroundLock = Any()

    @Volatile
    private var currentForegroundNotificationId: Int? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_EXPORT -> {
                val jobId = intent.getStringExtra(EXTRA_JOB_ID)
                val request = jobId?.let { CropExportManager.consumeWorkspaceRequest(it) }
                val job = jobId?.let { CropExportManager.getJob(it) }
                if (jobId == null || request == null || job == null) {
                    if (activeJobs.isEmpty()) {
                        stopSelf()
                    }
                    return START_NOT_STICKY
                }

                createNotificationChannel()
                postOrStartForegroundForJob(
                    jobKey = job.jobId,
                    notifId = job.notificationId,
                    notification = buildJobProgressNotification(job),
                    initialPct = 0
                )

                val coroutineJob = serviceScope.launch {
                    runWorkspaceExport(job, request)
                }
                activeJobs[job.jobId] = coroutineJob
            }

            ACTION_START_SHARE_EXPORT -> {
                val request = CropExportManager.consumeShareRequest()
                if (request == null) {
                    if (activeJobs.isEmpty()) {
                        stopSelf()
                    }
                    return START_NOT_STICKY
                }

                createNotificationChannel()
                activeJobs[SHARE_JOB_KEY]?.cancel()
                val shareNotif = buildShareProgressNotification(
                    statusText = "Exporting project to share…",
                    progress = 0.05f
                )
                postOrStartForegroundForJob(
                    jobKey = SHARE_JOB_KEY,
                    notifId = SHARE_NOTIFICATION_ID,
                    notification = shareNotif,
                    initialPct = 5
                )

                val coroutineJob = serviceScope.launch {
                    runShareExport(request)
                }
                activeJobs[SHARE_JOB_KEY] = coroutineJob
            }

            ACTION_CANCEL_EXPORT -> {
                val jobId = intent.getStringExtra(EXTRA_JOB_ID)
                if (jobId.isNullOrBlank()) {
                    cancelAllActiveExports()
                } else {
                    cancelSingleExport(jobId)
                }
            }

            else -> {
                if (activeJobs.isEmpty()) {
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun postOrStartForegroundForJob(
        jobKey: String,
        notifId: Int,
        notification: Notification,
        initialPct: Int
    ) {
        synchronized(foregroundLock) {
            activeNotificationIds[jobKey] = notifId
            lastNotifyByJobId[jobKey] = System.currentTimeMillis() to initialPct
            if (currentForegroundNotificationId == null) {
                startForegroundCompat(notifId, notification)
                currentForegroundNotificationId = notifId
            } else {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                runCatching { nm.notify(notifId, notification) }
            }
        }
    }

    private fun startForegroundCompat(notifId: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            startForeground(
                notifId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
        } else {
            startForeground(
                notifId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        }
    }

    private suspend fun runWorkspaceExport(
        initialJob: CropExportJob,
        request: CropWorkspaceExportRequest
    ) {
        val jobId = initialJob.jobId
        val media = request.media
        try {
            CropExportManager.updateJobProgress(jobId, 0.0f, "Processing…")
            maybeUpdateJobProgressNotification(
                jobKey = jobId,
                force = true
            )

            val result = when (media.mediaType) {
                CropMediaType.IMAGE -> {
                    val format = request.imageFormat ?: media.imageExportOptions.first()
                    ImageCropProcessor.exportCroppedImage(
                        context = applicationContext,
                        media = media,
                        cropRect = request.cropRect,
                        formatOption = format,
                        onProgress = { progress, text ->
                            CropExportManager.updateJobProgress(jobId, progress, text)
                            maybeUpdateJobProgressNotification(jobKey = jobId)
                        }
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
                            CropExportManager.updateJobProgress(jobId, progress, text)
                            maybeUpdateJobProgressNotification(jobKey = jobId)
                        }
                    )
                }
            }

            when (result) {
                is CropExportState.Success -> {
                    val now = System.currentTimeMillis()
                    val repo = CropProjectsRepository(applicationContext)
                    val latestBase = request.projectRecord?.id?.let { repo.getProjectById(it) }
                        ?: request.projectRecord
                    val updatedRecord = latestBase?.copy(
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
                        exportedAtMs = now,
                        lastExportedConfigSignature = initialJob.configSignature.ifBlank { latestBase.lastExportedConfigSignature }
                    )
                    if (updatedRecord != null) {
                        repo.upsertProject(updatedRecord)
                        CropExportManager.notifyProjectUpdated(updatedRecord.id)
                    }
                    CropExportManager.completeJobSuccess(jobId, result)
                    val timeLabel = formatClickTime(initialJob.clickedAtMs)
                    postCompletionNotification(
                        notificationId = initialJob.notificationId + COMPLETE_NOTIFICATION_OFFSET,
                        requestCode = initialJob.notificationId + COMPLETE_NOTIFICATION_OFFSET,
                        title = "${getString(R.string.export_notification_complete_title)} • 100%",
                        text = if (result.savedToGallery) {
                            "${result.fileName} saved to Gallery ($timeLabel)"
                        } else {
                            "${result.fileName} ready ($timeLabel)"
                        }
                    )
                }

                is CropExportState.Error -> {
                    CropExportManager.failJob(jobId, result.message)
                    postCompletionNotification(
                        notificationId = initialJob.notificationId + COMPLETE_NOTIFICATION_OFFSET,
                        requestCode = initialJob.notificationId + COMPLETE_NOTIFICATION_OFFSET,
                        title = "Export Failed (${formatClickTime(initialJob.clickedAtMs)})",
                        text = result.message
                    )
                }

                else -> {
                    CropExportManager.removeJob(jobId)
                }
            }
        } catch (_: CancellationException) {
            CropExportManager.removeJob(jobId)
        } catch (t: Throwable) {
            val message = t.message ?: "Failed to crop and export media."
            CropExportManager.failJob(jobId, message)
            postCompletionNotification(
                notificationId = initialJob.notificationId + COMPLETE_NOTIFICATION_OFFSET,
                requestCode = initialJob.notificationId + COMPLETE_NOTIFICATION_OFFSET,
                title = "Export Failed (${formatClickTime(initialJob.clickedAtMs)})",
                text = message
            )
        } finally {
            finishJobSession(jobId, initialJob.notificationId)
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
                    ImageCropProcessor.exportCroppedImage(
                        context = applicationContext,
                        media = inspected,
                        cropRect = crop,
                        formatOption = format,
                        onProgress = { progress, text ->
                            CropExportManager.updateShareProgress(progress, text)
                            maybeUpdateShareProgressNotification(text, progress)
                        }
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
                            maybeUpdateShareProgressNotification(text, progress)
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
                    val repo = CropProjectsRepository(applicationContext)
                    val latestBase = repo.getProjectById(project.id) ?: project
                    val updatedRecord = latestBase.copy(
                        exportedFilePath = result.outputPath,
                        exportedGalleryUriString = result.galleryUriString,
                        exportedFormatLabel = formatLabel,
                        exportedWidth = outW,
                        exportedHeight = outH,
                        exportedDurationMs = trimDuration,
                        updatedAtMs = now,
                        exportedAtMs = now
                    )
                    repo.upsertProject(updatedRecord)
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
                            notificationId = SHARE_COMPLETE_NOTIFICATION_ID,
                            requestCode = SHARE_COMPLETE_NOTIFICATION_ID,
                            title = "${getString(R.string.export_notification_complete_title)} • 100%",
                            text = "${result.fileName} is ready to share"
                        )
                    } else {
                        CropExportManager.failShareExport("Export finished, but unable to prepare share link.")
                    }
                }

                is CropExportState.Error -> {
                    CropExportManager.failShareExport(result.message)
                    postCompletionNotification(
                        notificationId = SHARE_COMPLETE_NOTIFICATION_ID,
                        requestCode = SHARE_COMPLETE_NOTIFICATION_ID,
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
            finishJobSession(SHARE_JOB_KEY, SHARE_NOTIFICATION_ID)
        }
    }

    private fun cancelSingleExport(jobId: String) {
        if (jobId == SHARE_JOB_KEY) {
            activeJobs[SHARE_JOB_KEY]?.cancel()
            CropExportManager.resetShareExportState()
            finishJobSession(SHARE_JOB_KEY, SHARE_NOTIFICATION_ID)
            return
        }
        val notifId = activeNotificationIds[jobId] ?: CropExportManager.getJob(jobId)?.notificationId
        activeJobs[jobId]?.cancel()
        CropExportManager.removeJob(jobId)
        if (notifId != null) {
            finishJobSession(jobId, notifId)
        } else if (activeJobs.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun cancelAllActiveExports() {
        val keys = activeJobs.keys.toList()
        keys.forEach { key ->
            activeJobs[key]?.cancel()
            if (key == SHARE_JOB_KEY) {
                CropExportManager.resetShareExportState()
            } else {
                CropExportManager.removeJob(key)
            }
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        activeNotificationIds.values.forEach { notifId ->
            runCatching { nm.cancel(notifId) }
        }
        synchronized(foregroundLock) {
            activeJobs.clear()
            activeNotificationIds.clear()
            lastNotifyByJobId.clear()
            currentForegroundNotificationId = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun finishJobSession(jobKey: String, notifId: Int) {
        synchronized(foregroundLock) {
            activeJobs.remove(jobKey)
            activeNotificationIds.remove(jobKey)
            lastNotifyByJobId.remove(jobKey)

            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (activeNotificationIds.isEmpty()) {
                currentForegroundNotificationId = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                if (currentForegroundNotificationId == notifId) {
                    val nextEntry = activeNotificationIds.entries.firstOrNull()
                    if (nextEntry != null) {
                        val nextKey = nextEntry.key
                        val nextNotifId = nextEntry.value
                        val nextNotification = if (nextKey == SHARE_JOB_KEY) {
                            val shareState = CropExportManager.shareState.value
                            buildShareProgressNotification(
                                statusText = shareState.statusText.ifBlank { "Exporting project to share…" },
                                progress = shareState.progress
                            )
                        } else {
                            CropExportManager.getJob(nextKey)?.let { buildJobProgressNotification(it) }
                        }
                        if (nextNotification != null) {
                            startForegroundCompat(nextNotifId, nextNotification)
                            currentForegroundNotificationId = nextNotifId
                        }
                    }
                }
                runCatching { nm.cancel(notifId) }
            }
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        cancelAllActiveExports()
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

    private fun maybeUpdateJobProgressNotification(
        jobKey: String,
        force: Boolean = false
    ) {
        val job = CropExportManager.getJob(jobKey) ?: return
        if (job.status != CropExportJobStatus.RUNNING) return
        val now = System.currentTimeMillis()
        val pct = (job.progress * 100f).toInt().coerceIn(0, 100)
        val prev = lastNotifyByJobId[jobKey]
        if (!force && prev != null && ((now - prev.first < NOTIFY_MIN_INTERVAL_MS) || (pct == prev.second && now - prev.first < 900L))) {
            return
        }
        lastNotifyByJobId[jobKey] = now to pct
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        runCatching {
            nm.notify(job.notificationId, buildJobProgressNotification(job))
        }
    }

    private fun maybeUpdateShareProgressNotification(
        statusText: String,
        progress: Float,
        force: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        val pct = (progress * 100f).toInt().coerceIn(0, 100)
        val prev = lastNotifyByJobId[SHARE_JOB_KEY]
        if (!force && prev != null && ((now - prev.first < NOTIFY_MIN_INTERVAL_MS) || (pct == prev.second && now - prev.first < 900L))) {
            return
        }
        lastNotifyByJobId[SHARE_JOB_KEY] = now to pct
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        runCatching {
            nm.notify(
                SHARE_NOTIFICATION_ID,
                buildShareProgressNotification(statusText, progress)
            )
        }
    }

    private fun buildJobProgressNotification(job: CropExportJob): Notification {
        val pct = (job.progress * 100f).toInt().coerceIn(0, 100)
        val timeText = formatClickTime(job.clickedAtMs)
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            job.notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, CropExportForegroundService::class.java).apply {
            action = ACTION_CANCEL_EXPORT
            putExtra(EXTRA_JOB_ID, job.jobId)
        }
        val cancelPending = PendingIntent.getService(
            this,
            job.notificationId,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelAction = Notification.Action.Builder(
            null,
            getString(R.string.export_notification_cancel),
            cancelPending
        ).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("${getString(R.string.export_notification_title)} • ${job.formatLabel} ($timeText)")
            .setContentText("${job.statusText} • $pct%")
            .setSubText(job.projectTitle)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openPending)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, pct, false)
            .addAction(cancelAction)
            .build()
    }

    private fun buildShareProgressNotification(
        statusText: String,
        progress: Float
    ): Notification {
        val pct = (progress * 100f).toInt().coerceIn(0, 100)
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            SHARE_NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, CropExportForegroundService::class.java).apply {
            action = ACTION_CANCEL_EXPORT
            putExtra(EXTRA_JOB_ID, SHARE_JOB_KEY)
        }
        val cancelPending = PendingIntent.getService(
            this,
            SHARE_NOTIFICATION_ID,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelAction = Notification.Action.Builder(
            null,
            getString(R.string.export_notification_cancel),
            cancelPending
        ).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.export_notification_title))
            .setContentText("$statusText • $pct%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openPending)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, pct, false)
            .addAction(cancelAction)
            .build()
    }

    private fun postCompletionNotification(
        notificationId: Int,
        requestCode: Int,
        title: String,
        text: String
    ) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            requestCode,
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
        runCatching { nm.notify(notificationId, notification) }
    }

    private fun formatClickTime(timestampMs: Long): String {
        val pattern = if (DateFormat.is24HourFormat(applicationContext)) "HH:mm:ss" else "h:mm:ss a"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestampMs))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
