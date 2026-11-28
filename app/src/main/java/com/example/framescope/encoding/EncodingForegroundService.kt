package com.example.framescope.encoding

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.example.framescope.MainActivity
import com.example.framescope.R
import com.example.framescope.data.AudioSourceMode
import com.example.framescope.data.RecordingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class EncodingForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.example.framescope.encoding.START"
        const val ACTION_STOP = "com.example.framescope.encoding.STOP"
        const val ACTION_PAUSE = "com.example.framescope.encoding.PAUSE"
        const val ACTION_RESUME = "com.example.framescope.encoding.RESUME"
        const val ACTION_TOGGLE_PAUSE = "com.example.framescope.encoding.TOGGLE_PAUSE"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        const val EXTRA_TAB = "encoding_tab"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_DENSITY = "density_dpi"
        const val EXTRA_BITRATE = "existing_video_bitrate"
        const val EXTRA_OUTPUT_PATH = "output_path"
        const val EXTRA_ORIENTATION = "recording_orientation"
        const val EXTRA_AUDIO_SOURCE = "audio_source"
        const val EXTRA_SUMMARY = "summary_text"
        const val EXTRA_SHOW_BORDER = "show_border"
        const val EXTRA_IS_REGION = "is_region"
        const val EXTRA_FRAME_RATE = "frame_rate"

        private const val CHANNEL_ID = "framescope_encoding"
        private const val NOTIFICATION_ID = 4101
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val teardownLock = Any()
    private val sessionActive = AtomicBoolean(false)
    private val tearingDown = AtomicBoolean(false)

    @Volatile
    private var teardownLatch: CountDownLatch? = null

    private var timerJob: Job? = null
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var muxer: NativeMuxer? = null
    private var videoEncoder: NativeVideoEncoder? = null
    private var audioEncoder: NativeAudioEncoder? = null
    private var audioCapture: NativeAudioCapture? = null
    private var displayCapture: NativeDisplayCapture? = null
    private var currentOutputPath: String? = null

    @Volatile
    private var isPaused = false
    @Volatile
    private var elapsedSeconds = 0L
    private var activeSummary = ""
    private var activeNotificationDesc = ""
    private var activeShowBorder = false
    private var activeIsRegion = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (sessionActive.compareAndSet(false, true)) {
                    startRecording(intent)
                }
            }
            ACTION_PAUSE -> setPausedState(true)
            ACTION_RESUME -> setPausedState(false)
            ACTION_TOGGLE_PAUSE -> setPausedState(!isPaused)
            ACTION_STOP -> stopRecording(fatalError = null)
        }
        return START_NOT_STICKY
    }

    private fun setPausedState(pause: Boolean) {
        if (!sessionActive.get() || tearingDown.get() || isPaused == pause) return
        isPaused = pause
        if (pause) {
            muxer?.setPaused(true)
            audioCapture?.setPaused(true)
            displayCapture?.pause()
        } else {
            displayCapture?.resume()
            audioCapture?.setPaused(false)
            muxer?.setPaused(false)
        }

        val path = currentOutputPath ?: return
        RecordingSessionManager.updateState(
            RecordingSessionState.Recording(
                elapsedSeconds = elapsedSeconds,
                outputPath = path,
                summaryText = activeSummary,
                showRegionBorder = activeShowBorder,
                isRegionMode = activeIsRegion,
                isPaused = pause
            )
        )
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        runCatching {
            nm.notify(
                NOTIFICATION_ID,
                createNotification(
                    elapsedFormatted = formatElapsed(elapsedSeconds),
                    pipelineLabel = activeNotificationDesc,
                    paused = pause
                )
            )
        }
    }

    private fun startRecording(intent: Intent) {
        tearingDown.set(false)
        isPaused = false
        elapsedSeconds = 0L
        teardownLatch = CountDownLatch(1)
        createNotificationChannel()
        val notification = createNotification("00:00:00", paused = false)

        val audioSource = intent.getStringExtra(EXTRA_AUDIO_SOURCE)?.let { raw ->
            runCatching { AudioSourceMode.valueOf(raw) }.getOrNull()
        } ?: AudioSourceMode.INTERNAL_AUDIO

        val hasMicPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val serviceTypes = if (audioSource == AudioSourceMode.MICROPHONE && hasMicPermission) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }

        startForeground(
            NOTIFICATION_ID,
            notification,
            serviceTypes
        )

        try {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val resultData = IntentCompat.getParcelableExtra(
                intent,
                EXTRA_RESULT_DATA,
                Intent::class.java
            ) ?: error("MediaProjection consent data is missing.")

            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mediaProjection = manager.getMediaProjection(resultCode, resultData)
                ?: error("MediaProjection could not be initialized.")
            projection = mediaProjection

            val tab = intent.getStringExtra(EXTRA_TAB)?.let { raw ->
                runCatching { EncodingTab.valueOf(raw) }.getOrNull()
            } ?: EncodingTab.H265

            val orientation = intent.getStringExtra(EXTRA_ORIENTATION)?.let { raw ->
                runCatching { RecordingOrientation.valueOf(raw) }.getOrNull()
            } ?: RecordingOrientation.PORTRAIT

            val width = intent.getIntExtra(EXTRA_WIDTH, 0)
            val height = intent.getIntExtra(EXTRA_HEIGHT, 0)
            val density = intent.getIntExtra(EXTRA_DENSITY, 0)
            val bitrate = intent.getIntExtra(EXTRA_BITRATE, tab.defaultBitrateMbps * 1_000_000)
            val preferredFrameRate = intent.getIntExtra(EXTRA_FRAME_RATE, 60).coerceAtLeast(1)
            val outputPath = intent.getStringExtra(EXTRA_OUTPUT_PATH)
                ?: error("Output MP4 path is missing.")
            val showBorder = intent.getBooleanExtra(EXTRA_SHOW_BORDER, false)
            val isRegion = intent.getBooleanExtra(EXTRA_IS_REGION, false)

            check(width > 0 && height > 0 && density > 0 && bitrate > 0) {
                "Invalid display dimensions or bitrate supplied."
            }

            currentOutputPath = outputPath
            val nativeMuxer = NativeMuxer(outputPath)
            muxer = nativeMuxer

            val vEncoder = NativeVideoEncoder(
                tab = tab,
                width = width,
                height = height,
                existingProjectVideoBitrate = bitrate,
                muxer = nativeMuxer,
                preferredFrameRate = preferredFrameRate,
                onFatalError = { err ->
                    stopRecording(fatalError = err.message ?: "Video encoder error")
                }
            )
            videoEncoder = vEncoder
            vEncoder.start()

            val targetWidth = vEncoder.configuredWidth
            val targetHeight = vEncoder.configuredHeight
            val audioSampleRate = EncodingSpec.AUDIO_SAMPLE_RATE

            val aEnc = NativeAudioEncoder(
                muxer = nativeMuxer,
                sampleRate = audioSampleRate,
                onFatalError = { err ->
                    stopRecording(fatalError = err.message ?: "Audio encoder error")
                }
            ).also {
                audioEncoder = it
                it.start()
            }

            NativeAudioCapture(
                context = applicationContext,
                audioSourceMode = audioSource,
                projection = mediaProjection,
                encoder = aEnc,
                sampleRate = audioSampleRate,
                onFatalError = { err ->
                    stopRecording(fatalError = err.message ?: "Audio capture error")
                }
            ).also {
                audioCapture = it
                it.start()
            }

            val activeAudioLabel = when (audioSource) {
                AudioSourceMode.INTERNAL_AUDIO -> "Internal Audio"
                AudioSourceMode.MICROPHONE -> "Microphone"
            }

            val rotationCoordinator = SurfaceRotationCoordinator().apply {
                updateInputSize(targetWidth, targetHeight)
                updateOutputSize(targetWidth, targetHeight)
                updateOrientation(orientation)
            }

            val dCapture = NativeDisplayCapture(mediaProjection)
            displayCapture = dCapture

            val callback = object : MediaProjection.Callback() {
                override fun onCapturedContentResize(newWidth: Int, newHeight: Int) {
                    dCapture.resizeHandler?.onCaptureSizeChanged(newWidth, newHeight)
                }

                override fun onStop() {
                    stopRecording(fatalError = null)
                }
            }
            projectionCallback = callback
            mediaProjection.registerCallback(callback, Handler(Looper.getMainLooper()))

            dCapture.start(
                width = targetWidth,
                height = targetHeight,
                densityDpi = density,
                encoderSurface = vEncoder.inputSurface,
                orientation = orientation,
                renderer = rotationCoordinator
            )

            val summary = buildActiveSummary(
                width = targetWidth,
                height = targetHeight,
                tab = vEncoder.activeTab,
                orientation = orientation,
                audioLabelOverride = activeAudioLabel,
                fallbackSummary = intent.getStringExtra(EXTRA_SUMMARY)
            )

            activeSummary = summary
            activeShowBorder = showBorder
            activeIsRegion = isRegion
            val notificationDesc = "${vEncoder.activeTab.shortName} • ${targetWidth}×${targetHeight}"
            activeNotificationDesc = notificationDesc

            RecordingSessionManager.updateState(
                RecordingSessionState.Recording(
                    elapsedSeconds = 0L,
                    outputPath = outputPath,
                    summaryText = summary,
                    showRegionBorder = showBorder,
                    isRegionMode = isRegion,
                    isPaused = false
                )
            )

            val nmInitial = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            runCatching {
                nmInitial.notify(
                    NOTIFICATION_ID,
                    createNotification(
                        elapsedFormatted = "00:00:00",
                        pipelineLabel = notificationDesc,
                        paused = false
                    )
                )
            }

            timerJob?.cancel()
            timerJob = serviceScope.launch {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                while (isActive) {
                    delay(1_000L)
                    if (!isPaused) {
                        elapsedSeconds += 1L
                        val currentElapsed = elapsedSeconds
                        RecordingSessionManager.updateState(
                            RecordingSessionState.Recording(
                                elapsedSeconds = currentElapsed,
                                outputPath = outputPath,
                                summaryText = summary,
                                showRegionBorder = showBorder,
                                isRegionMode = isRegion,
                                isPaused = false
                            )
                        )
                        runCatching {
                            nm.notify(
                                NOTIFICATION_ID,
                                createNotification(
                                    elapsedFormatted = formatElapsed(currentElapsed),
                                    pipelineLabel = notificationDesc,
                                    paused = false
                                )
                            )
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            stopRecording(fatalError = t.message ?: "Unable to start screen capture")
        }
    }

    private fun buildActiveSummary(
        width: Int,
        height: Int,
        tab: EncodingTab,
        orientation: RecordingOrientation,
        audioLabelOverride: String,
        fallbackSummary: String?
    ): String {
        val orientationLabel = when (orientation) {
            RecordingOrientation.PORTRAIT -> "Portrait"
            RecordingOrientation.LANDSCAPE -> "Landscape"
        }
        return if (width > 0 && height > 0) {
            "${tab.shortName} (${width}×${height}) • $orientationLabel • $audioLabelOverride"
        } else {
            fallbackSummary ?: "${width}×${height}"
        }
    }

    private fun stopRecording(fatalError: String?) {
        if (!tearingDown.compareAndSet(false, true)) return

        RecordingSessionManager.updateState(RecordingSessionState.Stopping)
        timerJob?.cancel()
        timerJob = null

        val completedPath = currentOutputPath

        serviceScope.launch(Dispatchers.IO) {
            performTeardown(fatalError, completedPath)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun performTeardown(fatalError: String?, completedPath: String?) {
        try {
            var hasSamples = false
            synchronized(teardownLock) {
                runCatching { displayCapture?.stop() }
                displayCapture = null

                runCatching { audioCapture?.stop() }
                audioCapture = null

                runCatching { videoEncoder?.stop() }
                videoEncoder = null

                runCatching { audioEncoder?.stop() }
                audioEncoder = null

                projectionCallback?.let { cb ->
                    runCatching { projection?.unregisterCallback(cb) }
                }
                projectionCallback = null

                runCatching { projection?.stop() }
                projection = null

                hasSamples = muxer?.hasRecordedSamples() == true
                runCatching { muxer?.stopAndRelease() }
                if (!hasSamples) {
                    hasSamples = muxer?.hasRecordedSamples() == true
                }
                muxer = null
            }

            if (fatalError == null && completedPath != null) {
                val outFile = File(completedPath)
                if (outFile.exists() && outFile.length() > 0L && hasSamples) {
                    runCatching {
                        RecordingsRepository(applicationContext).exportToMediaStore(completedPath)
                    }
                    RecordingSessionManager.markCompleted(completedPath)
                } else {
                    runCatching { outFile.delete() }
                }
                RecordingSessionManager.updateState(RecordingSessionState.Idle)
            } else if (fatalError != null) {
                completedPath?.let { path ->
                    runCatching { File(path).delete() }
                }
                RecordingSessionManager.updateState(RecordingSessionState.Error(fatalError))
            } else {
                RecordingSessionManager.updateState(RecordingSessionState.Idle)
            }
        } finally {
            currentOutputPath = null
            sessionActive.set(false)
            teardownLatch?.countDown()
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(
        elapsedFormatted: String,
        pipelineLabel: String = getString(R.string.notification_text),
        paused: Boolean = false
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val togglePauseIntent = Intent(this, EncodingForegroundService::class.java).apply {
            action = ACTION_TOGGLE_PAUSE
        }
        val togglePausePending = PendingIntent.getService(
            this,
            2,
            togglePauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, EncodingForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (paused) {
            getString(R.string.recording_paused)
        } else {
            pipelineLabel
        }
        val pauseResumeText = if (paused) {
            getString(R.string.notification_action_resume)
        } else {
            getString(R.string.notification_action_pause)
        }
        val pauseBgRes = if (paused) R.drawable.bg_notif_pill_resume else R.drawable.bg_notif_pill_pause
        val dotRes = if (paused) R.drawable.bg_notif_dot_paused else R.drawable.bg_notif_dot_recording

        val expandedViews = RemoteViews(packageName, R.layout.notification_recording).apply {
            setImageViewResource(R.id.notif_status_dot, dotRes)
            setTextViewText(R.id.notif_status_label, statusText)
            setTextViewText(R.id.notif_timer_text, elapsedFormatted)
            setTextViewText(R.id.notif_btn_pause_resume, pauseResumeText)
            setInt(R.id.notif_btn_pause_resume, "setBackgroundResource", pauseBgRes)
            setOnClickPendingIntent(R.id.notif_btn_pause_resume, togglePausePending)
            setOnClickPendingIntent(R.id.notif_btn_stop, stopPending)
        }

        val compactViews = RemoteViews(packageName, R.layout.notification_recording_compact).apply {
            setImageViewResource(R.id.notif_compact_dot, dotRes)
            setTextViewText(R.id.notif_compact_timer, elapsedFormatted)
            setTextViewText(R.id.notif_compact_pause_resume, pauseResumeText)
            setInt(R.id.notif_compact_pause_resume, "setBackgroundResource", pauseBgRes)
            setOnClickPendingIntent(R.id.notif_compact_pause_resume, togglePausePending)
            setOnClickPendingIntent(R.id.notif_compact_stop, stopPending)
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText("$statusText • $elapsedFormatted")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(openPending)
            .setOnlyAlertOnce(true)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(compactViews)
            .setCustomBigContentView(expandedViews)
            .setOngoing(true)
            .build()
    }

    private fun formatElapsed(totalSeconds: Long): String {
        val hrs = totalSeconds / 3600
        val mins = (totalSeconds % 3600) / 60
        val secs = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hrs, mins, secs)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopRecording(fatalError = null)
        super.onTimeout(startId, fgsType)
    }

    override fun onDestroy() {
        if (sessionActive.get() || tearingDown.get()) {
            stopRecording(fatalError = null)
            runCatching { teardownLatch?.await(3_500, TimeUnit.MILLISECONDS) }
        }
        serviceScope.cancel()
        super.onDestroy()
    }
}
