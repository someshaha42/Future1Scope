package com.example.framescope.encoding

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer

/**
 * One MP4 muxer for the video and audio tracks.
 * All muxer operations are serialized under one lock, with pre-start sample buffering
 * and monotonic timestamp enforcement to ensure valid, playable MP4 files on all devices.
 */
class NativeMuxer(outputPath: String) {

    private data class PendingSample(
        val buffer: ByteBuffer,
        val info: MediaCodec.BufferInfo
    )

    private val lock = Any()
    private val muxer = MediaMuxer(
        outputPath,
        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
    )

    private var videoTrack = -1
    private var audioTrack = -1
    private var audioDisabled = false
    private var started = false
    private var stopped = false
    private var wroteAnySample = false

    private var lastVideoPtsUs = -1L
    private var lastAudioPtsUs = -1L
    private var paused = false
    private var pauseStartedUs = -1L
    private var totalPausedUs = 0L

    private val pendingVideo = ArrayDeque<PendingSample>()
    private val pendingAudio = ArrayDeque<PendingSample>()

    fun disableAudioTrack() {
        synchronized(lock) {
            if (stopped || started) return
            audioDisabled = true
            pendingAudio.clear()
            startIfReady()
        }
    }

    fun addVideoTrack(format: MediaFormat) {
        synchronized(lock) {
            if (stopped || started || videoTrack != -1) return
            videoTrack = muxer.addTrack(format)
            startIfReady()
        }
    }

    fun addAudioTrack(format: MediaFormat) {
        synchronized(lock) {
            if (stopped || started || audioTrack != -1 || audioDisabled) return
            audioTrack = muxer.addTrack(format)
            startIfReady()
        }
    }

    private fun startIfReady() {
        if (!started && videoTrack >= 0 && (audioTrack >= 0 || audioDisabled)) {
            muxer.start()
            started = true
            flushPendingSamples()
        }
    }

    private fun flushPendingSamples() {
        // Ensure video starts cleanly on an IDR keyframe if one is buffered
        if (videoTrack >= 0 && pendingVideo.isNotEmpty()) {
            val firstKeyframeIdx = pendingVideo.indexOfFirst {
                (it.info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
            }
            if (firstKeyframeIdx > 0) {
                repeat(firstKeyframeIdx) { pendingVideo.removeFirst() }
            }
            while (pendingVideo.isNotEmpty()) {
                val sample = pendingVideo.removeFirst()
                writeSampleSafe(videoTrack, sample.buffer, sample.info, isVideo = true)
            }
        } else {
            pendingVideo.clear()
        }

        if (audioTrack >= 0 && pendingAudio.isNotEmpty()) {
            while (pendingAudio.isNotEmpty()) {
                val sample = pendingAudio.removeFirst()
                writeSampleSafe(audioTrack, sample.buffer, sample.info, isVideo = false)
            }
        } else {
            pendingAudio.clear()
        }
    }

    fun setPaused(isPaused: Boolean) {
        synchronized(lock) {
            if (stopped) return
            if (isPaused && !paused) {
                paused = true
                pauseStartedUs = System.nanoTime() / 1_000L
            } else if (!isPaused && paused) {
                val nowUs = System.nanoTime() / 1_000L
                if (pauseStartedUs >= 0L) {
                    totalPausedUs += (nowUs - pauseStartedUs).coerceAtLeast(0L)
                }
                paused = false
                pauseStartedUs = -1L
            }
        }
    }

    fun writeVideo(
        data: ByteBuffer,
        info: MediaCodec.BufferInfo
    ) {
        synchronized(lock) {
            if (stopped || paused || info.size <= 0 || (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                return
            }
            if (!started) {
                if (pendingVideo.size >= MAX_PENDING_VIDEO) {
                    // Keep the most recent keyframe onwards so the buffer stays bounded
                    val nextKey = pendingVideo.drop(1).indexOfFirst {
                        (it.info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    }
                    if (nextKey >= 0) {
                        repeat(nextKey + 1) { pendingVideo.removeFirst() }
                    } else {
                        pendingVideo.removeFirst()
                    }
                }
                pendingVideo.addLast(cloneSample(data, info))
                return
            }
            writeSampleSafe(videoTrack, data, info, isVideo = true)
        }
    }

    fun writeAudio(
        data: ByteBuffer,
        info: MediaCodec.BufferInfo
    ) {
        synchronized(lock) {
            if (stopped || paused || info.size <= 0 || (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                return
            }
            if (!started) {
                if (pendingAudio.size >= MAX_PENDING_AUDIO) {
                    pendingAudio.removeFirst()
                }
                pendingAudio.addLast(cloneSample(data, info))
                return
            }
            writeSampleSafe(audioTrack, data, info, isVideo = false)
        }
    }

    private fun cloneSample(data: ByteBuffer, info: MediaCodec.BufferInfo): PendingSample {
        val dup = data.duplicate()
        dup.position(info.offset)
        dup.limit(info.offset + info.size)
        val copy = ByteBuffer.allocateDirect(info.size)
        copy.put(dup)
        copy.flip()
        val copiedInfo = MediaCodec.BufferInfo().apply {
            set(0, info.size, info.presentationTimeUs, info.flags)
        }
        return PendingSample(copy, copiedInfo)
    }

    private fun writeSampleSafe(
        trackIndex: Int,
        data: ByteBuffer,
        info: MediaCodec.BufferInfo,
        isVideo: Boolean
    ) {
        if (trackIndex < 0 || info.size <= 0) return
        val adjustedPtsUs = (info.presentationTimeUs - totalPausedUs).coerceAtLeast(0L)
        val safePtsUs = if (isVideo) {
            val clamped = if (lastVideoPtsUs >= 0L && adjustedPtsUs <= lastVideoPtsUs) {
                lastVideoPtsUs + 1L
            } else {
                adjustedPtsUs
            }
            lastVideoPtsUs = clamped
            clamped
        } else {
            val clamped = if (lastAudioPtsUs >= 0L && adjustedPtsUs <= lastAudioPtsUs) {
                lastAudioPtsUs + 1L
            } else {
                adjustedPtsUs
            }
            lastAudioPtsUs = clamped
            clamped
        }

        val normalizedInfo = MediaCodec.BufferInfo().apply {
            set(info.offset, info.size, safePtsUs, info.flags)
        }
        muxer.writeSampleData(trackIndex, data, normalizedInfo)
        wroteAnySample = true
    }

    fun isStarted(): Boolean = synchronized(lock) { started }

    fun hasRecordedSamples(): Boolean = synchronized(lock) { wroteAnySample }

    fun stopAndRelease() {
        synchronized(lock) {
            if (stopped) return
            if (!started && ((videoTrack >= 0 && pendingVideo.isNotEmpty()) ||
                    (audioTrack >= 0 && pendingAudio.isNotEmpty()))
            ) {
                runCatching {
                    muxer.start()
                    started = true
                    flushPendingSamples()
                }
            }
            stopped = true
            pendingVideo.clear()
            pendingAudio.clear()
            if (started) {
                if (wroteAnySample) {
                    runCatching { muxer.stop() }
                }
                started = false
            }
            runCatching { muxer.release() }
        }
    }

    private companion object {
        const val MAX_PENDING_VIDEO = 180
        const val MAX_PENDING_AUDIO = 300
    }
}
