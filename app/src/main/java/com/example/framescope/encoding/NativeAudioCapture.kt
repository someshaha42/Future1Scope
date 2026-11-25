package com.example.framescope.encoding

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import com.example.framescope.data.AudioSourceMode
import java.util.concurrent.atomic.AtomicBoolean

class NativeAudioCapture(
    private val context: Context,
    private val audioSourceMode: AudioSourceMode,
    private val projection: MediaProjection,
    private val encoder: NativeAudioEncoder,
    private val sampleRate: Int = EncodingSpec.AUDIO_SAMPLE_RATE,
    private val onFatalError: (Throwable) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var recorder: AudioRecord? = null
    private var captureThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBuffer > 0) {
            "AudioRecord does not support the required $sampleRate Hz Mono PCM format."
        }

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val recordBufferBytes = maxOf(minBuffer * 2, AAC_FRAME_BYTES * 4)

        val audioRecord = when (audioSourceMode) {
            AudioSourceMode.INTERNAL_AUDIO -> {
                val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(playbackConfig)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(recordBufferBytes)
                    .build()
            }

            AudioSourceMode.MICROPHONE -> {
                val micRecord = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(recordBufferBytes)
                    .build()

                selectPreferredExternalMic(micRecord)
                micRecord
            }
        }

        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
            runCatching { audioRecord.release() }
            "AudioRecord initialization failed for $audioSourceMode."
        }

        recorder = audioRecord
        running.set(true)
        audioRecord.startRecording()

        captureThread = Thread({
            val chunkSize = minOf(AAC_FRAME_BYTES, maxOf(minBuffer, AAC_FRAME_BYTES))
            val buffer = ByteArray(chunkSize)
            var audioAnchorUs = -1L
            var totalFrames = 0L
            try {
                while (running.get()) {
                    val count = audioRecord.read(
                        buffer,
                        0,
                        buffer.size,
                        AudioRecord.READ_BLOCKING
                    )
                    when {
                        count > 0 -> {
                            val nowUs = System.nanoTime() / 1_000L
                            val frames = count / 2L
                            val chunkDurationUs = frames * 1_000_000L / sampleRate
                            if (audioAnchorUs < 0L) {
                                // Anchor on the first actual hardware PCM delivery to match Surface timestamps
                                audioAnchorUs = (nowUs - chunkDurationUs).coerceAtLeast(0L)
                            }
                            var ptsUs = audioAnchorUs + (totalFrames * 1_000_000L / sampleRate)
                            // Re-anchor smoothly if hardware capture stalls > 80ms to keep A/V lip-sync tight
                            val driftUs = nowUs - (ptsUs + chunkDurationUs)
                            if (kotlin.math.abs(driftUs) > MAX_AV_DRIFT_US) {
                                audioAnchorUs += driftUs
                                ptsUs = audioAnchorUs + (totalFrames * 1_000_000L / sampleRate)
                            }
                            encoder.queuePcm(
                                buffer.copyOf(count),
                                count,
                                ptsUs
                            )
                            totalFrames += frames
                        }
                        count == 0 -> {
                            Thread.sleep(5)
                        }
                        else -> {
                            if (running.get()) {
                                if (count == AudioRecord.ERROR_DEAD_OBJECT ||
                                    count == AudioRecord.ERROR_INVALID_OPERATION
                                ) {
                                    onFatalError(IllegalStateException("Audio capture stopped ($count)"))
                                    break
                                }
                                Thread.sleep(10)
                            }
                        }
                    }
                }
            } catch (_: InterruptedException) {
                // Normal shutdown interruption
            } catch (t: Throwable) {
                if (running.get()) onFatalError(t)
            }
        }, "AudioCaptureThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun selectPreferredExternalMic(audioRecord: AudioRecord) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val directExternalTypes = listOf(
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_WIRED_HEADSET
        )
        for (type in directExternalTypes) {
            val match = inputs.firstOrNull { it.type == type }
            if (match != null) {
                audioRecord.setPreferredDevice(match)
                return
            }
        }
        val activeComm = audioManager.communicationDevice
        if (activeComm != null &&
            (activeComm.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                activeComm.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        ) {
            val match = inputs.firstOrNull { it.type == activeComm.type }
            if (match != null) {
                audioRecord.setPreferredDevice(match)
            }
        }
    }

    fun stop() {
        running.set(false)
        runCatching { recorder?.stop() }
        captureThread?.interrupt()
        runCatching { captureThread?.join(800) }
        runCatching { recorder?.release() }
        recorder = null
        captureThread = null
    }

    companion object {
        private const val AAC_FRAME_BYTES = 2048 // 1024 Mono 16-bit samples = 1 AAC frame
        private const val MAX_AV_DRIFT_US = 80_000L
    }
}
