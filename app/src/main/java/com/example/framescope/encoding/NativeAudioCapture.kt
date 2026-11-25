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
    private val onFatalError: (Throwable) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var recorder: AudioRecord? = null
    private var captureThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        val minBuffer = AudioRecord.getMinBufferSize(
            EncodingSpec.AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBuffer > 0) {
            "AudioRecord does not support the required 48 kHz Mono PCM format."
        }

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(EncodingSpec.AUDIO_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

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
                    .setBufferSizeInBytes(minBuffer * 2)
                    .build()
            }

            AudioSourceMode.MICROPHONE -> {
                val micRecord = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(minBuffer * 2)
                    .build()

                selectPreferredExternalMic(micRecord)
                micRecord
            }
        }

        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord initialization failed for $audioSourceMode."
        }

        recorder = audioRecord
        running.set(true)
        audioRecord.startRecording()

        val sessionStartUs = System.nanoTime() / 1_000L

        captureThread = Thread({
            val buffer = ByteArray(minBuffer)
            var totalFrames = 0L
            try {
                while (running.get()) {
                    val count = audioRecord.read(
                        buffer,
                        0,
                        buffer.size,
                        AudioRecord.READ_BLOCKING
                    )
                    if (count > 0) {
                        val bytesPerFrame = 2
                        val frames = count / bytesPerFrame
                        val ptsUs = sessionStartUs +
                            (totalFrames * 1_000_000L / EncodingSpec.AUDIO_SAMPLE_RATE)
                        encoder.queuePcm(
                            buffer.copyOf(count),
                            count,
                            ptsUs
                        )
                        totalFrames += frames
                    }
                }
            } catch (t: Throwable) {
                if (running.get()) onFatalError(t)
            }
        }, "AudioCaptureThread").apply {
            start()
        }
    }

    private fun selectPreferredExternalMic(audioRecord: AudioRecord) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val priorityTypes = listOf(
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        )
        for (type in priorityTypes) {
            val match = inputs.firstOrNull { it.type == type }
            if (match != null) {
                audioRecord.setPreferredDevice(match)
                return
            }
        }
    }

    fun stop() {
        running.set(false)
        runCatching { recorder?.stop() }
        runCatching { captureThread?.join(1_000) }
        runCatching { recorder?.release() }
        recorder = null
        captureThread = null
    }
}
