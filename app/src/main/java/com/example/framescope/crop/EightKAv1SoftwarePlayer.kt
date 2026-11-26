package com.example.framescope.crop

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Dedicated AV1 Software-Decoding Player for 8K Editing Playback.
 *
 * Strictly uses Software AV1 Decoders (!isHardwareAccelerated, video/av01) and NEVER uses MediaPlayer
 * or device 8K hardware decoders.
 */
class EightKAv1SoftwarePlayer(
    private val context: Context,
    private val videoUri: Uri,
    private val audioFallbackUri: Uri?,
    private val surface: Surface,
    initialTrimStartMs: Long,
    initialTrimEndMs: Long,
    initialMuted: Boolean,
    initialPlaying: Boolean,
    private val onPositionChanged: (Long) -> Unit,
    private val onError: (String) -> Unit
) {
    private val released = AtomicBoolean(false)
    private val playing = AtomicBoolean(initialPlaying)
    private val muted = AtomicBoolean(initialMuted)
    private val trimStartMs = AtomicLong(initialTrimStartMs.coerceAtLeast(0L))
    private val trimEndMs = AtomicLong(initialTrimEndMs.coerceAtLeast(0L))
    private val pendingSeekMs = AtomicLong(if (initialTrimStartMs > 0L) initialTrimStartMs else 1L)
    private val pendingAudioSeekMs = AtomicLong(if (initialTrimStartMs > 0L) initialTrimStartMs else 0L)
    private val wakeLock = Object()

    private val currentAudioTrack = AtomicReference<AudioTrack?>(null)
    private var workerThread: Thread? = null
    private var audioThread: Thread? = null

    fun start() {
        workerThread = Thread({ runVideoLoop() }, "8K-SW-AV1-Player").apply { start() }
    }

    fun setPlaying(play: Boolean) {
        if (released.get()) return
        val changed = playing.getAndSet(play) != play
        if (changed) {
            synchronized(wakeLock) { wakeLock.notifyAll() }
        }
    }

    fun setMuted(isMuted: Boolean) {
        muted.set(isMuted)
        val vol = if (isMuted) 0f else 1f
        runCatching { currentAudioTrack.get()?.setVolume(vol) }
    }

    fun updateTrimRange(startMs: Long, endMs: Long) {
        trimStartMs.set(startMs.coerceAtLeast(0L))
        trimEndMs.set(endMs.coerceAtLeast(0L))
    }

    fun seekTo(positionMs: Long) {
        if (released.get()) return
        val clamped = positionMs.coerceAtLeast(0L)
        pendingSeekMs.set(clamped)
        pendingAudioSeekMs.set(clamped)
        synchronized(wakeLock) { wakeLock.notifyAll() }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        playing.set(false)
        synchronized(wakeLock) { wakeLock.notifyAll() }
        runCatching { workerThread?.join(600L) }
        runCatching { audioThread?.join(400L) }
        workerThread = null
        audioThread = null
        releaseAudioTrack()
    }

    private fun releaseAudioTrack() {
        currentAudioTrack.getAndSet(null)?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
        }
    }

    private fun runVideoLoop() {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var audioUriToUse: Uri? = null

        try {
            extractor.setDataSource(context, videoUri, null)
            var videoTrackIdx = -1
            var videoFormat: MediaFormat? = null
            var hasAudioInVideoUri = false

            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (videoTrackIdx < 0 && mime.startsWith("video/")) {
                    videoTrackIdx = i
                    videoFormat = fmt
                } else if (mime.startsWith("audio/")) {
                    hasAudioInVideoUri = true
                }
            }

            if (videoTrackIdx < 0 || videoFormat == null) {
                reportError("8K playback error: No video track found.")
                return
            }

            val videoMime = videoFormat.getString(MediaFormat.KEY_MIME).orEmpty()
            if (!videoMime.equals(MediaFormat.MIMETYPE_VIDEO_AV1, ignoreCase = true)) {
                reportError("8K playback error: Expected AV1 track (video/av01) for Software AV1 decoding, found $videoMime.")
                return
            }

            val swAv1Decoders = EightKAv1HevcPipeline.resolveSoftwareAv1Decoders()
            if (swAv1Decoders.isEmpty()) {
                reportError("8K playback error: No Software AV1 Decoder available (8K hardware decoding is disabled).")
                return
            }

            val errors = mutableListOf<String>()
            for (codecName in swAv1Decoders) {
                var candidate: MediaCodec? = null
                val ok = runCatching {
                    candidate = MediaCodec.createByCodecName(codecName)
                    val decFmt = MediaFormat(videoFormat)
                    candidate!!.configure(decFmt, surface, null, 0)
                    candidate!!.start()
                }
                if (ok.isSuccess && candidate != null) {
                    decoder = candidate
                    break
                }
                errors += "$codecName: ${ok.exceptionOrNull()?.message}"
                runCatching { candidate?.stop() }
                runCatching { candidate?.release() }
            }

            if (decoder == null) {
                reportError("8K AV1 Software Decoder failed to initialize (${errors.joinToString("; ")})")
                return
            }

            extractor.selectTrack(videoTrackIdx)

            audioUriToUse = when {
                hasAudioInVideoUri -> videoUri
                audioFallbackUri != null -> audioFallbackUri
                else -> null
            }
            if (audioUriToUse != null) {
                startAudioThread(audioUriToUse)
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var baseWallNs = -1L
            var basePtsUs = -1L
            var seekTargetUs = -1L
            var renderSingleFrameAfterSeek = false
            var lastReportedPosMs = -1L

            while (!released.get()) {
                val reqSeekMs = pendingSeekMs.getAndSet(-1L)
                if (reqSeekMs >= 0L) {
                    seekTargetUs = reqSeekMs * 1_000L
                    renderSingleFrameAfterSeek = !playing.get()
                    runCatching { decoder.flush() }
                    extractor.seekTo(seekTargetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    inputDone = false
                    baseWallNs = -1L
                    basePtsUs = -1L
                }

                val isCurrentlyPlaying = playing.get()
                if (!isCurrentlyPlaying && !renderSingleFrameAfterSeek) {
                    baseWallNs = -1L
                    basePtsUs = -1L
                    synchronized(wakeLock) {
                        if (!released.get() && !playing.get() && pendingSeekMs.get() < 0L) {
                            wakeLock.wait(80L)
                        }
                    }
                    continue
                }

                // Feed AV1 input packets to SW AV1 Decoder
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(4_000L)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val size = extractor.readSampleData(inBuf, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val samplePts = extractor.sampleTime.coerceAtLeast(0L)
                                decoder.queueInputBuffer(inIdx, 0, size, samplePts, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = decoder.dequeueOutputBuffer(info, 8_000L)
                if (outIdx >= 0) {
                    val ptsUs = info.presentationTimeUs
                    val isEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val isCodecConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0

                    if (isCodecConfig || info.size <= 0) {
                        decoder.releaseOutputBuffer(outIdx, false)
                    } else if (seekTargetUs >= 0L && ptsUs + 15_000L < seekTargetUs && !isEos) {
                        decoder.releaseOutputBuffer(outIdx, false)
                    } else {
                        seekTargetUs = -1L
                        val tEndMs = trimEndMs.get()
                        val tEndUs = if (tEndMs > trimStartMs.get()) tEndMs * 1_000L else Long.MAX_VALUE

                        if (isCurrentlyPlaying && tEndUs < Long.MAX_VALUE && ptsUs >= tEndUs) {
                            decoder.releaseOutputBuffer(outIdx, false)
                            val resetPos = trimStartMs.get()
                            pendingSeekMs.compareAndSet(-1L, resetPos)
                            pendingAudioSeekMs.set(resetPos)
                            continue
                        }

                        if (isCurrentlyPlaying) {
                            val nowNs = System.nanoTime()
                            if (baseWallNs < 0L || ptsUs < basePtsUs) {
                                baseWallNs = nowNs
                                basePtsUs = ptsUs
                            } else {
                                val targetWallNs = baseWallNs + (ptsUs - basePtsUs) * 1_000L
                                val waitNs = targetWallNs - nowNs
                                if (waitNs > 2_000_000L) {
                                    val waitMs = (waitNs / 1_000_000L).coerceAtMost(80L)
                                    synchronized(wakeLock) {
                                        if (!released.get() && playing.get() && pendingSeekMs.get() < 0L) {
                                            wakeLock.wait(waitMs)
                                        }
                                    }
                                }
                            }
                        }

                        if (released.get()) {
                            decoder.releaseOutputBuffer(outIdx, false)
                            break
                        }

                        decoder.releaseOutputBuffer(outIdx, true)
                        renderSingleFrameAfterSeek = false

                        val posMs = (ptsUs / 1_000L).coerceAtLeast(0L)
                        if (kotlin.math.abs(posMs - lastReportedPosMs) >= 40L || !isCurrentlyPlaying) {
                            lastReportedPosMs = posMs
                            onPositionChanged(posMs)
                        }
                    }

                    if (isEos) {
                        renderSingleFrameAfterSeek = false
                        if (playing.get()) {
                            val resetPos = trimStartMs.get()
                            pendingSeekMs.compareAndSet(-1L, resetPos)
                            pendingAudioSeekMs.set(resetPos)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            if (!released.get()) {
                reportError("8K AV1 Software Playback failed: ${t.message ?: "unknown error"}")
            }
        } finally {
            releaseAudioTrack()
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun startAudioThread(audioUri: Uri) {
        audioThread = Thread({
            val audioExtractor = MediaExtractor()
            var audioDecoder: MediaCodec? = null
            var audioTrack: AudioTrack? = null
            try {
                audioExtractor.setDataSource(context, audioUri, null)
                var audioTrackIdx = -1
                var audioFmt: MediaFormat? = null
                for (i in 0 until audioExtractor.trackCount) {
                    val f = audioExtractor.getTrackFormat(i)
                    val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (m.startsWith("audio/")) {
                        audioTrackIdx = i
                        audioFmt = f
                        break
                    }
                }
                if (audioTrackIdx < 0 || audioFmt == null) return@Thread
                val mime = audioFmt.getString(MediaFormat.KEY_MIME) ?: return@Thread
                val swAudioDecName = EightKAv1HevcPipeline.resolveSoftwareAudioDecoders(mime).firstOrNull()
                    ?: return@Thread

                audioDecoder = MediaCodec.createByCodecName(swAudioDecName).apply {
                    configure(audioFmt, null, null, 0)
                    start()
                }
                audioExtractor.selectTrack(audioTrackIdx)

                val sampleRate = if (audioFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    audioFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                } else 48_000
                val channelCount = if (audioFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    audioFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
                } else 2
                val channelMask = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
                    .coerceAtLeast(4096)

                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelMask)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                currentAudioTrack.set(audioTrack)
                audioTrack.setVolume(if (muted.get()) 0f else 1f)

                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var pcmScratch = ByteArray(0)

                while (!released.get()) {
                    if (!playing.get()) {
                        if (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            runCatching { audioTrack.pause() }
                            runCatching { audioTrack.flush() }
                        }
                        Thread.sleep(30L)
                        continue
                    }

                    if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack.setVolume(if (muted.get()) 0f else 1f)
                        audioTrack.play()
                    }

                    val curSeek = pendingAudioSeekMs.getAndSet(-1L)
                    if (curSeek >= 0L) {
                        runCatching { audioDecoder.flush() }
                        runCatching { audioTrack.flush() }
                        audioExtractor.seekTo(curSeek * 1_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        inputDone = false
                    }

                    if (!inputDone) {
                        val inIdx = audioDecoder.dequeueInputBuffer(2_000L)
                        if (inIdx >= 0) {
                            val inBuf = audioDecoder.getInputBuffer(inIdx)
                            if (inBuf != null) {
                                val size = audioExtractor.readSampleData(inBuf, 0)
                                if (size < 0) {
                                    audioDecoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    audioDecoder.queueInputBuffer(inIdx, 0, size, audioExtractor.sampleTime.coerceAtLeast(0L), 0)
                                    audioExtractor.advance()
                                }
                            }
                        }
                    }

                    val outIdx = audioDecoder.dequeueOutputBuffer(info, 5_000L)
                    if (outIdx >= 0) {
                        val outBuf = audioDecoder.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            val tEndMs = trimEndMs.get()
                            if (tEndMs > trimStartMs.get() && info.presentationTimeUs >= tEndMs * 1_000L) {
                                audioDecoder.releaseOutputBuffer(outIdx, false)
                                runCatching { audioDecoder.flush() }
                                audioExtractor.seekTo(trimStartMs.get() * 1_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                                inputDone = false
                                continue
                            }
                            if (pcmScratch.size < info.size) {
                                pcmScratch = ByteArray(info.size)
                            }
                            outBuf.position(info.offset)
                            outBuf.get(pcmScratch, 0, info.size)
                            if (playing.get() && !released.get()) {
                                audioTrack.write(pcmScratch, 0, info.size)
                            }
                        }
                        audioDecoder.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            runCatching { audioDecoder.flush() }
                            audioExtractor.seekTo(trimStartMs.get() * 1_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                            inputDone = false
                        }
                    }
                }
            } catch (_: Throwable) {
                // Audio decode error should not abort SW AV1 video decoding
            } finally {
                releaseAudioTrack()
                runCatching { audioDecoder?.stop() }
                runCatching { audioDecoder?.release() }
                runCatching { audioExtractor.release() }
            }
        }, "8K-SW-Audio-Player").apply { start() }
    }

    private fun reportError(message: String) {
        if (!released.get()) {
            onError(message)
        }
    }
}
