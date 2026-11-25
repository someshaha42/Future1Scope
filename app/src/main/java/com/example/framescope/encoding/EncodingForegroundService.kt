package com.example.framescope.encoding

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import java.util.concurrent.atomic.AtomicBoolean

class EncodingForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.example.framescope.encoding.START"
        const val ACTION_STOP = "com.example.framescope.encoding.STOP"
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

        private const val CHANNEL_ID = "framescope_encoding"
        private const val NOTIFICATION_ID = 4101
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val teardownLock = Any()
    private val tearingDown = AtomicBoolean(false)

    private var timerJob: Job? = null
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var muxer: NativeMuxer? = null
    private var videoEncoder: NativeVideoEncoder? = null
    private var audioEncoder: NativeAudioEncoder? = null
    private var audioCapture: NativeAudioCapture? = null
    private var displayCapture: NativeDisplayCapture? = null
    private var currentOutputPath: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> stopRecording(fatalError = null)
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent) {
        tearingDown.set(false)
        createNotificationChannel()
        val notification = createNotification("00:00:00")

        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )

        try {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                ?: error("MediaProjection consent data is missing.")

            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mediaProjection = manager.getMediaProjection(resultCode, resultData)
                ?: error("MediaProjection could not be initialized.")
            projection = mediaProjection

            val tab = when (intent.getStringExtra(EXTRA_TAB)) {
                EncodingTab.H264.name -> EncodingTab.H264
                else -> EncodingTab.H264
            }
            val orientation = intent.getStringExtra(EXTRA_ORIENTATION)?.let { raw ->
                runCatching { RecordingOrientation.valueOf(raw) }.getOrNull()
            } ?: RecordingOrientation.PORTRAIT
            val audioSource = intent.getStringExtra(EXTRA_AUDIO_SOURCE)?.let { raw ->
                runCatching { AudioSourceMode.valueOf(raw) }.getOrNull()
            } ?: AudioSourceMode.INTERNAL_AUDIO

            val width = intent.getIntExtra(EXTRA_WIDTH, 0)
            val height = intent.getIntExtra(EXTRA_HEIGHT, 0)
            val density = intent.getIntExtra(EXTRA_DENSITY, 0)
            val bitrate = intent.getIntExtra(EXTRA_BITRATE, 0)
            val outputPath = intent.getStringExtra(EXTRA_OUTPUT_PATH)
                ?: error("Output MP4 path is missing.")
            val summary = intent.getStringExtra(EXTRA_SUMMARY) ?: "${width}×${height}"
            val showBorder = intent.getBooleanExtra(EXTRA_SHOW_BORDER, true)
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
                onFatalError = { err ->
                    stopRecording(fatalError = err.message ?: "Video encoder error")
                }
            )
            videoEncoder = vEncoder

            val aEncoder = NativeAudioEncoder(
                muxer = nativeMuxer,
                onFatalError = { err ->
                    stopRecording(fatalError = err.message ?: "Audio encoder error")
                }
            )
            audioEncoder = aEncoder

            vEncoder.start()
            aEncoder.start()

            val aCapture = NativeAudioCapture(
                context = applicationContext,
                audioSourceMode = audioSource,
                projection = mediaProjection,
                encoder = aEncoder,
                onFatalError = { err ->
                    stopRecording(fatalError = err.message ?: "Audio capture error")
                }
            )
            audioCapture = aCapture
            aCapture.start()

            val rotationCoordinator = SurfaceRotationCoordinator().apply {
                updateInputSize(width, height)
                updateOutputSize(width, height)
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
                width = width,
                height = height,
                densityDpi = density,
                encoderSurface = vEncoder.inputSurface,
                orientation = orientation,
                renderer = rotationCoordinator
            )

            RecordingSessionManager.updateState(
                RecordingSessionState.Recording(
                    elapsedSeconds = 0L,
                    outputPath = outputPath,
                    summaryText = summary,
                    showRegionBorder = showBorder,
                    isRegionMode = isRegion
                )
            )

            timerJob?.cancel()
            timerJob = serviceScope.launch {
                var elapsed = 0L
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                while (isActive) {
                    delay(1_000L)
                    elapsed += 1L
                    RecordingSessionManager.updateState(
                        RecordingSessionState.Recording(
                            elapsedSeconds = elapsed,
                            outputPath = outputPath,
                            summaryText = summary,
                            showRegionBorder = showBorder,
                            isRegionMode = isRegion
                        )
                    )
                    nm.notify(NOTIFICATION_ID, createNotification(formatElapsed(elapsed)))
                }
            }
        } catch (t: Throwable) {
            stopRecording(fatalError = t.message ?: "Unable to start screen capture")
        }
    }

    private fun stopRecording(fatalError: String?) {
        if (!tearingDown.compareAndSet(false, true)) return

        RecordingSessionManager.updateState(RecordingSessionState.Stopping)
        timerJob?.cancel()
        timerJob = null

        val completedPath = currentOutputPath

        serviceScope.launch(Dispatchers.IO) {
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

                runCatching { muxer?.stopAndRelease() }
                muxer = null
            }

            if (fatalError == null && completedPath != null) {
                val outFile = File(completedPath)
                if (outFile.exists() && outFile.length() > 0L) {
                    RecordingsRepository(applicationContext).exportToMediaStore(completedPath)
                    RecordingSessionManager.markCompleted(completedPath)
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

            currentOutputPath = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
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

    private fun createNotification(elapsedFormatted: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
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

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText("${getString(R.string.notification_text)} • $elapsedFormatted")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(openPending)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.notification_action_stop),
                    stopPending
                ).build()
            )
            .setOngoing(true)
            .build()
    }

    private fun formatElapsed(totalSeconds: Long): String {
        val hrs = totalSeconds / 3600
        val mins = (totalSeconds % 3600) / 60
        val secs = totalSeconds % 60
        return String.format("%02d:%02d:%02d", hrs, mins, secs)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopRecording(fatalError = null)
        serviceScope.cancel()
        super.onDestroy()
    }
}
