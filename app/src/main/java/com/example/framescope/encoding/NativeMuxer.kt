package com.example.framescope.encoding

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer

/**
 * One MP4 muxer for the video and audio tracks.
 * All muxer operations are serialized under one lock.
 */
class NativeMuxer(outputPath: String) {

    private val lock = Any()
    private val muxer = MediaMuxer(
        outputPath,
        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
    )

    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private var stopped = false

    fun addVideoTrack(format: MediaFormat) {
        synchronized(lock) {
            if (stopped || started || videoTrack != -1) return
            videoTrack = muxer.addTrack(format)
            startIfReady()
        }
    }

    fun addAudioTrack(format: MediaFormat) {
        synchronized(lock) {
            if (stopped || started || audioTrack != -1) return
            audioTrack = muxer.addTrack(format)
            startIfReady()
        }
    }

    private fun startIfReady() {
        if (!started && videoTrack >= 0 && audioTrack >= 0) {
            muxer.start()
            started = true
        }
    }

    fun writeVideo(
        data: ByteBuffer,
        info: MediaCodec.BufferInfo
    ) {
        synchronized(lock) {
            if (started && !stopped && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                muxer.writeSampleData(videoTrack, data, info)
            }
        }
    }

    fun writeAudio(
        data: ByteBuffer,
        info: MediaCodec.BufferInfo
    ) {
        synchronized(lock) {
            if (started && !stopped && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                muxer.writeSampleData(audioTrack, data, info)
            }
        }
    }

    fun isStarted(): Boolean = synchronized(lock) { started }

    fun stopAndRelease() {
        synchronized(lock) {
            if (stopped) return
            stopped = true
            if (started) {
                runCatching { muxer.stop() }
                started = false
            }
            runCatching { muxer.release() }
        }
    }
}
