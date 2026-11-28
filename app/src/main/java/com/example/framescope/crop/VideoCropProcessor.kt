package com.example.framescope.crop

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Surface
import android.graphics.SurfaceTexture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

object VideoCropProcessor {

    private const val MIN_SAFE_ENCODER_BITRATE_BPS = 250_000
    private const val CODEC_WAIT_US = 2_000L
    private const val PROGRESS_UPDATE_INTERVAL_MS = 100L
    private const val DEFAULT_ENCODER_FRAME_RATE = 30
    private const val TURBO_OPERATING_RATE = Short.MAX_VALUE.toInt()
    private const val LONG_VIDEO_THRESHOLD_US = 15L * 60L * 1_000_000L

    private val cachedCodecInfos: Array<MediaCodecInfo> by lazy {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
    }

    private data class SelectedVideoEncoder(
        val codecName: String?,
        val mimeType: String,
        val isHardware: Boolean,
        val isSoftwareOnly: Boolean,
        val width: Int,
        val height: Int,
        val frameRate: Int,
        val bitrateBps: Int,
        val bitrateMode: Int?,
        val fastestComplexity: Int?
    )

    suspend fun exportCroppedVideo(
        context: Context,
        media: ImportedMediaInfo,
        cropRect: NormalizedCropRect,
        formatOption: VideoExportFormatOption,
        targetBitrateBps: Int,
        trimStartMs: Long = 0L,
        trimEndMs: Long = media.durationMs,
        allowLosslessPassthrough: Boolean = false,
        onProgress: (Float, String) -> Unit
    ): CropExportState = withContext(Dispatchers.IO) {
        var outFile: File? = null

        var videoPfd: ParcelFileDescriptor? = null
        var audioPfd: ParcelFileDescriptor? = null
        val videoExtractor = MediaExtractor()
        var audioExtractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var surfaceTexture: SurfaceTexture? = null
        var decoderSurface: Surface? = null
        var encoderInputSurface: Surface? = null
        var frameThread: HandlerThread? = null
        var glContext: EglCropSurface? = null

        var muxerStarted = false

        try {
            onProgress(0.02f, "Preparing encoder…")

            videoPfd = openExtractorDataSource(context, media.uri, videoExtractor)
            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var videoInputFormat: MediaFormat? = null
            var audioInputFormat: MediaFormat? = null

            for (i in 0 until videoExtractor.trackCount) {
                val fmt = videoExtractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (videoTrackIndex < 0 && mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoInputFormat = fmt
                } else if (audioTrackIndex < 0 && mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioInputFormat = fmt
                }
            }

            check(videoTrackIndex >= 0 && videoInputFormat != null) {
                "Unable to locate a decodable video track."
            }

            val targetContainer = VideoContainerFormat.MP4
            val isFullCrop = cropRect.left <= 0.002f &&
                cropRect.top <= 0.002f &&
                cropRect.right >= 0.998f &&
                cropRect.bottom >= 0.998f
            val isFullStart = trimStartMs <= 50L
            val isFullEnd = media.durationMs <= 0L || trimEndMs <= 0L || trimEndMs >= media.durationMs - 50L
            val sourceMime = videoInputFormat.getString(MediaFormat.KEY_MIME)
            if (allowLosslessPassthrough &&
                isFullCrop &&
                isFullStart &&
                isVideoCompatibleWithContainer(sourceMime, targetContainer)
            ) {
                val compatibleAudio = audioInputFormat?.takeIf {
                    isAudioCompatibleWithContainer(
                        it.getString(MediaFormat.KEY_MIME),
                        targetContainer
                    )
                }
                val passthroughFile = CropExportsRepository(context).createVideoExportFile(targetContainer.extension)
                outFile = passthroughFile
                val passthroughResult = runLosslessRemux(
                    context = context,
                    media = media,
                    videoExtractor = videoExtractor,
                    videoTrackIndex = videoTrackIndex,
                    videoInputFormat = videoInputFormat,
                    audioTrackIndex = audioTrackIndex,
                    compatibleAudioFormat = compatibleAudio,
                    container = targetContainer,
                    outFile = passthroughFile,
                    trimEndMs = if (isFullEnd) Long.MAX_VALUE else trimEndMs,
                    onProgress = onProgress
                )
                if (passthroughResult != null) {
                    return@withContext passthroughResult
                }
                runCatching { passthroughFile.delete() }
                outFile = null
            }

            val trimStartUs = trimStartMs.coerceAtLeast(0L) * 1_000L
            val effectiveEndMs = if (trimEndMs > trimStartMs) trimEndMs else media.durationMs
            val trimEndUs = if (effectiveEndMs > 0L) effectiveEndMs * 1_000L else Long.MAX_VALUE
            val durationUs = ((if (trimEndUs < Long.MAX_VALUE) trimEndUs else (media.durationMs * 1_000L)) - trimStartUs)
                .coerceAtLeast(1L)
            val keyFrameIntervalSec = if (durationUs >= LONG_VIDEO_THRESHOLD_US) 3 else 2

            val rawOutW = ((cropRect.widthFraction * media.displayWidth).roundToInt() and 1.inv())
                .coerceIn(16, media.displayWidth)
            val rawOutH = ((cropRect.heightFraction * media.displayHeight).roundToInt() and 1.inv())
                .coerceIn(16, media.displayHeight)

            val sourceFps = if (media.fps > 0) media.fps else DEFAULT_ENCODER_FRAME_RATE
            val encoderCandidates = resolveVideoEncoderCandidates(
                requestedContainer = targetContainer,
                preferredMime = media.sourceVideoMime,
                requestedWidth = rawOutW,
                requestedHeight = rawOutH,
                maxSourceWidth = media.displayWidth,
                maxSourceHeight = media.displayHeight,
                frameRate = sourceFps,
                requestedBitrateBps = targetBitrateBps.coerceAtLeast(MIN_SAFE_ENCODER_BITRATE_BPS)
            )
            if (encoderCandidates.isEmpty()) {
                return@withContext CropExportState.Error(
                    "No compatible video encoder found for ${formatOption.label}."
                )
            }

            var effectiveContainer: VideoContainerFormat? = null
            var activeEncoder: MediaCodec? = null
            var activeInputSurface: Surface? = null
            for ((candidateContainer, candidateSpec) in encoderCandidates) {
                var probeEncoder: MediaCodec? = null
                var probeSurface: Surface? = null
                val ok = runCatching {
                    probeEncoder = if (candidateSpec.codecName != null) {
                        MediaCodec.createByCodecName(candidateSpec.codecName)
                    } else {
                        MediaCodec.createEncoderByType(candidateSpec.mimeType)
                    }
                    configureVideoEncoderWithFallback(
                        encoder = probeEncoder!!,
                        spec = candidateSpec,
                        iFrameIntervalSec = keyFrameIntervalSec,
                        colorStandard = media.colorStandard,
                        colorRange = media.colorRange,
                        colorTransfer = media.colorTransfer
                    )
                    probeSurface = probeEncoder!!.createInputSurface()
                    probeEncoder!!.start()
                }.isSuccess
                if (ok && probeEncoder != null && probeSurface != null) {
                    activeEncoder = probeEncoder
                    activeInputSurface = probeSurface
                    effectiveContainer = candidateContainer
                    break
                } else {
                    runCatching { probeSurface?.release() }
                    runCatching { probeEncoder?.stop() }
                    runCatching { probeEncoder?.release() }
                }
            }

            val finalContainer = effectiveContainer
            encoder = activeEncoder
            encoderInputSurface = activeInputSurface
            if (encoder == null || encoderInputSurface == null || finalContainer == null) {
                return@withContext CropExportState.Error(
                    "All hardware and software video encoders rejected the export format."
                )
            }

            outFile = CropExportsRepository(context).createVideoExportFile(finalContainer.extension)

            glContext = EglCropSurface(
                encoderSurface = encoderInputSurface,
                cropRect = cropRect,
                rotationDegrees = media.rotationDegrees
            )
            glContext.makeCurrent()

            frameThread = HandlerThread(
                "CropDecoderFrameThread",
                android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
            ).apply { start() }
            val frameHandler = Handler(frameThread.looper)

            surfaceTexture = glContext.createSurfaceTexture(frameHandler)
            decoderSurface = Surface(surfaceTexture)

            val inputMime = videoInputFormat.getString(MediaFormat.KEY_MIME)
                ?: return@withContext CropExportState.Error("Missing source video MIME type.")
            val decoderCandidates = resolveCandidateDecoders(inputMime)
            var activeDecoder: MediaCodec? = null
            for ((codecName, overrideMime) in decoderCandidates) {
                activeDecoder = tryConfigureAndStartDecoder(
                    codecName = codecName,
                    sourceFormat = videoInputFormat,
                    overrideMime = overrideMime,
                    decoderSurface = decoderSurface
                )
                if (activeDecoder != null) break
            }
            if (activeDecoder == null) {
                activeDecoder = tryConfigureAndStartDecoder(
                    codecName = null,
                    sourceFormat = videoInputFormat,
                    overrideMime = null,
                    decoderSurface = decoderSurface
                )
            }
            decoder = activeDecoder ?: return@withContext CropExportState.Error(
                "No compatible video decoder could start for $inputMime."
            )

            muxer = MediaMuxer(outFile.absolutePath, finalContainer.muxerOutputFormat)

            val compatibleAudioFormat = audioInputFormat?.takeIf {
                isAudioCompatibleWithContainer(
                    it.getString(MediaFormat.KEY_MIME),
                    finalContainer
                )
            }

            videoExtractor.selectTrack(videoTrackIndex)

            if (trimStartUs > 0L) {
                videoExtractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }

            var muxerVideoTrack = -1
            var muxerAudioTrack = -1
            val lastVideoPtsUs = AtomicLong(-1L)

            val stopRequested = AtomicBoolean(false)
            val asyncError = AtomicReference<Throwable?>(null)
            val encoderFinished = AtomicBoolean(false)
            val muxerReady = AtomicBoolean(false)
            val muxerLock = Any()

            // Open a dedicated parallel audio extractor so audio chunks are interleaved in real-time
            // during video encoding (eliminates scanning multi-GB 2h files a second time!)
            var parallelAudioReady = false
            if (compatibleAudioFormat != null && audioTrackIndex >= 0) {
                val extraExtractor = MediaExtractor()
                val opened = runCatching {
                    audioPfd = openExtractorDataSource(context, media.uri, extraExtractor)
                    extraExtractor.selectTrack(audioTrackIndex)
                    extraExtractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                }.isSuccess
                if (opened) {
                    audioExtractor = extraExtractor
                    parallelAudioReady = true
                } else {
                    runCatching { extraExtractor.release() }
                    runCatching { audioPfd?.close() }
                    audioPfd = null
                }
            }

            onProgress(0.05f, "Cropping video frames…")

            // Stage 1: Dedicated decoder input feeder keeps hardware decoder input queue saturated
            val feederThread = Thread({
                var inputDone = false
                var pastTrimCount = 0
                try {
                    while (!stopRequested.get() && !inputDone) {
                        val inIndex = decoder.dequeueInputBuffer(CODEC_WAIT_US)
                        if (inIndex < 0) continue

                        val inBuf = decoder.getInputBuffer(inIndex) ?: continue
                        val sampleSize = videoExtractor.readSampleData(inBuf, 0)
                        val samplePtsUs = videoExtractor.sampleTime.coerceAtLeast(0L)
                        val pastTrimLimit = trimEndUs < Long.MAX_VALUE && samplePtsUs > trimEndUs + 250_000L
                        if (pastTrimLimit) {
                            pastTrimCount++
                        } else {
                            pastTrimCount = 0
                        }

                        if (sampleSize < 0 || pastTrimCount >= 4) {
                            decoder.queueInputBuffer(
                                inIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(
                                inIndex,
                                0,
                                sampleSize,
                                samplePtsUs,
                                0
                            )
                            videoExtractor.advance()
                        }
                    }
                } catch (t: Throwable) {
                    if (!stopRequested.get()) {
                        asyncError.compareAndSet(null, t)
                        stopRequested.set(true)
                    }
                }
            }, "CropDecoderFeeder").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }

            // Stage 3: Dedicated encoder output drainer
            val drainerThread = Thread({
                val encInfo = MediaCodec.BufferInfo()
                val videoWeight = if (compatibleAudioFormat != null && !parallelAudioReady) 0.88f else 0.92f
                var lastProgressMs = 0L
                try {
                    while (!stopRequested.get() && !encoderFinished.get()) {
                        val encIndex = encoder.dequeueOutputBuffer(encInfo, CODEC_WAIT_US)
                        when {
                            encIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                            encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                synchronized(muxerLock) {
                                    check(!muxerStarted) { "Encoder format changed after muxer started." }
                                    val encOutFormat = encoder.outputFormat
                                    muxerVideoTrack = muxer.addTrack(encOutFormat)
                                    if (compatibleAudioFormat != null) {
                                        muxerAudioTrack = muxer.addTrack(compatibleAudioFormat)
                                    }
                                    muxer.start()
                                    muxerStarted = true
                                    muxerReady.set(true)
                                }
                            }
                            encIndex >= 0 -> {
                                val encodedBuf = encoder.getOutputBuffer(encIndex)
                                if (encodedBuf != null &&
                                    (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                                    encInfo.size > 0 &&
                                    muxerReady.get() &&
                                    muxerVideoTrack >= 0
                                ) {
                                    val prevVideoPts = lastVideoPtsUs.get()
                                    val pts = if (encInfo.presentationTimeUs <= prevVideoPts) {
                                        prevVideoPts + 1L
                                    } else {
                                        encInfo.presentationTimeUs
                                    }
                                    lastVideoPtsUs.set(pts)
                                    encInfo.presentationTimeUs = pts

                                    encodedBuf.position(encInfo.offset)
                                    encodedBuf.limit(encInfo.offset + encInfo.size)
                                    synchronized(muxerLock) {
                                        if (muxerStarted) {
                                            muxer.writeSampleData(muxerVideoTrack, encodedBuf, encInfo)
                                        }
                                    }

                                    val nowMs = System.currentTimeMillis()
                                    val isEos = (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                                    if (isEos || nowMs - lastProgressMs >= PROGRESS_UPDATE_INTERVAL_MS) {
                                        lastProgressMs = nowMs
                                        val ratio = (pts.toFloat() / durationUs.toFloat()).coerceIn(0f, 1f)
                                        onProgress(0.05f + ratio * videoWeight, "Cropping video frames…")
                                    }
                                }
                                encoder.releaseOutputBuffer(encIndex, false)
                                if ((encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                    encoderFinished.set(true)
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    if (!stopRequested.get()) {
                        asyncError.compareAndSet(null, t)
                        stopRequested.set(true)
                    }
                }
            }, "CropEncoderDrainer").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }

            // Stage 3b: Parallel interleaved audio muxer paced alongside encoded video timestamps
            val parallelAudioThread: Thread? = if (parallelAudioReady && audioExtractor != null) {
                Thread({
                    try {
                        while (!muxerReady.get() && !stopRequested.get() && !encoderFinished.get()) {
                            Thread.sleep(10L)
                        }
                        if (stopRequested.get() || !muxerReady.get() || muxerAudioTrack < 0) return@Thread

                        val maxAudioSize = (compatibleAudioFormat?.let {
                            if (it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                                it.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                            } else null
                        } ?: 256 * 1024).coerceAtLeast(64 * 1024)

                        val audioBuf = ByteBuffer.allocateDirect(maxAudioSize)
                        val audioInfo = MediaCodec.BufferInfo()
                        var lastAudioPtsUs = -1L

                        while (!stopRequested.get()) {
                            val sampleSize = audioExtractor.readSampleData(audioBuf, 0)
                            if (sampleSize < 0) break
                            val rawSampleTime = audioExtractor.sampleTime.coerceAtLeast(0L)
                            if (rawSampleTime > trimEndUs) break
                            if (rawSampleTime < trimStartUs) {
                                if (!audioExtractor.advance()) break
                                continue
                            }
                            val rebasedTime = (rawSampleTime - trimStartUs).coerceAtLeast(0L)

                            while (!stopRequested.get() && !encoderFinished.get() && rebasedTime > lastVideoPtsUs.get() + 1_500_000L) {
                                Thread.sleep(12L)
                            }
                            if (stopRequested.get()) break

                            if (encoderFinished.get()) {
                                val maxVideoPts = lastVideoPtsUs.get()
                                if (maxVideoPts > 0L && rebasedTime > maxVideoPts + 100_000L) {
                                    break
                                }
                            }

                            val pts = if (rebasedTime <= lastAudioPtsUs) lastAudioPtsUs + 1L else rebasedTime
                            lastAudioPtsUs = pts

                            audioInfo.set(0, sampleSize, pts, audioExtractor.sampleFlags)
                            audioBuf.position(0)
                            audioBuf.limit(sampleSize)
                            synchronized(muxerLock) {
                                if (muxerStarted && muxerAudioTrack >= 0) {
                                    muxer.writeSampleData(muxerAudioTrack, audioBuf, audioInfo)
                                }
                            }
                            if (!audioExtractor.advance()) break
                        }
                    } catch (_: InterruptedException) {
                        // Normal cancellation cleanup
                    } catch (t: Throwable) {
                        if (!stopRequested.get()) {
                            asyncError.compareAndSet(null, t)
                            stopRequested.set(true)
                        }
                    }
                }, "CropAudioMuxer").apply { start() }
            } else {
                null
            }

            try {
                // Stage 2: GL render loop on EGL-current thread
                val decInfo = MediaCodec.BufferInfo()
                var decoderDone = false
                while (!decoderDone && !stopRequested.get()) {
                    if (!coroutineContext.isActive) {
                        stopRequested.set(true)
                        throw InterruptedException("Export cancelled.")
                    }
                    asyncError.get()?.let { err ->
                        stopRequested.set(true)
                        throw err
                    }

                    val outIndex = decoder.dequeueOutputBuffer(decInfo, CODEC_WAIT_US)
                    if (outIndex >= 0) {
                        val ptsUs = decInfo.presentationTimeUs
                        val withinRange = ptsUs >= trimStartUs && ptsUs <= trimEndUs
                        val doRender = decInfo.size > 0 &&
                            withinRange &&
                            (decInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        decoder.releaseOutputBuffer(outIndex, doRender)
                        if (doRender) {
                            if (!glContext.awaitNewImage(stopRequested)) {
                                if (!coroutineContext.isActive) {
                                    stopRequested.set(true)
                                    throw InterruptedException("Export cancelled.")
                                }
                                asyncError.get()?.let { throw it }
                            }
                            glContext.drawCroppedFrame((ptsUs - trimStartUs).coerceAtLeast(0L))
                        }
                        if ((decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decoderDone = true
                            encoder.signalEndOfInputStream()
                        }
                    }
                }

                // Wait for encoder drainer to flush remaining encoded packets cleanly
                while (drainerThread.isAlive && !stopRequested.get()) {
                    if (!coroutineContext.isActive) {
                        stopRequested.set(true)
                        throw InterruptedException("Export cancelled.")
                    }
                    asyncError.get()?.let { err ->
                        stopRequested.set(true)
                        throw err
                    }
                    drainerThread.join(40L)
                }

                if (parallelAudioThread != null) {
                    onProgress(0.95f, "Muxing audio track…")
                    while (parallelAudioThread.isAlive && !stopRequested.get()) {
                        if (!coroutineContext.isActive) {
                            stopRequested.set(true)
                            throw InterruptedException("Export cancelled.")
                        }
                        parallelAudioThread.join(40L)
                    }
                }

                asyncError.get()?.let { throw it }
            } finally {
                stopRequested.set(true)
                runCatching { feederThread.join(500L) }
                runCatching { drainerThread.join(500L) }
                runCatching { parallelAudioThread?.join(500L) }
            }

            // Fallback sequential audio pass ONLY if parallel audio extractor could not be opened
            if (!parallelAudioReady && muxerStarted && muxerAudioTrack >= 0 && audioTrackIndex >= 0) {
                onProgress(0.94f, "Muxing audio track…")
                videoExtractor.unselectTrack(videoTrackIndex)
                videoExtractor.selectTrack(audioTrackIndex)
                videoExtractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                val maxAudioSize = (compatibleAudioFormat?.let {
                    if (it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        it.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                    } else null
                } ?: 256 * 1024).coerceAtLeast(64 * 1024)

                val audioBuf = ByteBuffer.allocateDirect(maxAudioSize)
                val audioInfo = MediaCodec.BufferInfo()
                var lastAudioPtsUs = -1L

                while (coroutineContext.isActive) {
                    val sampleSize = videoExtractor.readSampleData(audioBuf, 0)
                    if (sampleSize < 0) break
                    val rawSampleTime = videoExtractor.sampleTime.coerceAtLeast(0L)
                    if (rawSampleTime > trimEndUs) break
                    if (rawSampleTime < trimStartUs) {
                        if (!videoExtractor.advance()) break
                        continue
                    }
                    val rebasedTime = (rawSampleTime - trimStartUs).coerceAtLeast(0L)
                    val maxVideoPts = lastVideoPtsUs.get()
                    if (maxVideoPts > 0L && rebasedTime > maxVideoPts + 100_000L) {
                        break
                    }
                    val pts = if (rebasedTime <= lastAudioPtsUs) lastAudioPtsUs + 1L else rebasedTime
                    lastAudioPtsUs = pts

                    audioInfo.set(
                        0,
                        sampleSize,
                        pts,
                        videoExtractor.sampleFlags
                    )
                    audioBuf.position(0)
                    audioBuf.limit(sampleSize)
                    synchronized(muxerLock) {
                        muxer.writeSampleData(muxerAudioTrack, audioBuf, audioInfo)
                    }
                    if (!videoExtractor.advance()) break
                }
            }

            synchronized(muxerLock) {
                if (muxerStarted) {
                    muxer.stop()
                    muxerStarted = false
                }
            }

            onProgress(0.98f, "Saving to gallery…")
            val galleryUri = exportVideoToMediaStore(
                context = context,
                file = outFile,
                mimeType = finalContainer.mimeType
            )

            CropExportState.Success(
                outputPath = outFile.absolutePath,
                fileName = outFile.name,
                savedToGallery = galleryUri != null,
                mediaType = CropMediaType.VIDEO,
                galleryUriString = galleryUri
            )
        } catch (t: Throwable) {
            runCatching { outFile?.delete() }
            if (t is InterruptedException || t is kotlinx.coroutines.CancellationException) {
                CropExportState.Idle
            } else {
                CropExportState.Error(t.message ?: "Failed to crop and export video.")
            }
        } finally {
            runCatching { audioExtractor?.release() }
            runCatching { audioPfd?.close() }
            runCatching { videoExtractor.release() }
            runCatching { videoPfd?.close() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            if (muxerStarted) {
                runCatching { muxer?.stop() }
            }
            runCatching { muxer?.release() }
            runCatching { decoderSurface?.release() }
            runCatching { surfaceTexture?.release() }
            runCatching { frameThread?.quitSafely() }
            runCatching { glContext?.release() }
            runCatching { encoderInputSurface?.release() }
        }
    }

    private fun openExtractorDataSource(
        context: Context,
        uri: android.net.Uri,
        extractor: MediaExtractor
    ): ParcelFileDescriptor? {
        val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
        if (pfd != null) {
            val ok = runCatching {
                extractor.setDataSource(pfd.fileDescriptor)
            }.isSuccess
            if (ok) return pfd
            runCatching { pfd.close() }
        }
        extractor.setDataSource(context, uri, null)
        return null
    }

    private fun tryConfigureAndStartDecoder(
        codecName: String?,
        sourceFormat: MediaFormat,
        overrideMime: String?,
        decoderSurface: Surface
    ): MediaCodec? {
        val targetMime = overrideMime ?: sourceFormat.getString(MediaFormat.KEY_MIME) ?: return null
        data class DecoderAttempt(
            val operatingRate: Int?,
            val priority: Int?
        )
        val attempts = listOf(
            DecoderAttempt(operatingRate = TURBO_OPERATING_RATE, priority = 1),
            DecoderAttempt(operatingRate = 480, priority = 1),
            DecoderAttempt(operatingRate = null, priority = null)
        )
        for (attempt in attempts) {
            var probeDecoder: MediaCodec? = null
            val ok = runCatching {
                probeDecoder = if (codecName != null) {
                    MediaCodec.createByCodecName(codecName)
                } else {
                    MediaCodec.createDecoderByType(targetMime)
                }
                // Ensure SurfaceTexture receives raw frames cleanly without Surface-level rotation surprises
                val decoderFormat = MediaFormat(sourceFormat).apply {
                    if (overrideMime != null) {
                        setString(MediaFormat.KEY_MIME, overrideMime)
                    }
                    if (containsKey(MediaFormat.KEY_ROTATION)) {
                        setInteger(MediaFormat.KEY_ROTATION, 0)
                    }
                    if (attempt.operatingRate != null) {
                        setInteger(MediaFormat.KEY_OPERATING_RATE, attempt.operatingRate)
                    }
                    if (attempt.priority != null) {
                        setInteger(MediaFormat.KEY_PRIORITY, attempt.priority)
                    }
                }
                probeDecoder!!.configure(decoderFormat, decoderSurface, null, 0)
                probeDecoder!!.start()
            }.isSuccess
            if (ok && probeDecoder != null) {
                return probeDecoder
            }
            runCatching { probeDecoder?.stop() }
            runCatching { probeDecoder?.release() }
        }
        return null
    }

    private fun resolveCandidateDecoders(mimeType: String): List<Pair<String, String?>> {
        val codecInfos = cachedCodecInfos
        val primary = codecInfos
            .filter { info ->
                !info.isAlias && !info.isEncoder && info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
            }
            .sortedByDescending { info ->
                (if (info.isHardwareAccelerated && !info.isSoftwareOnly) 2 else 0) +
                    (if (info.isVendor) 1 else 0)
            }
            .map { it.name to null }

        if (!mimeType.equals("video/dolby-vision", ignoreCase = true)) {
            return primary
        }
        val hevcFallbacks = codecInfos
            .filter { info ->
                !info.isAlias && !info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) }
            }
            .sortedByDescending { info ->
                (if (info.isHardwareAccelerated && !info.isSoftwareOnly) 2 else 0) +
                    (if (info.isVendor) 1 else 0)
            }
            .map { it.name to MediaFormat.MIMETYPE_VIDEO_HEVC }
        return primary + hevcFallbacks
    }

    private fun resolveVideoEncoderCandidates(
        requestedContainer: VideoContainerFormat,
        preferredMime: String?,
        requestedWidth: Int,
        requestedHeight: Int,
        maxSourceWidth: Int,
        maxSourceHeight: Int,
        frameRate: Int,
        requestedBitrateBps: Int
    ): List<Pair<VideoContainerFormat, SelectedVideoEncoder>> {
        val containerOrder = buildList {
            add(requestedContainer)
            if (requestedContainer != VideoContainerFormat.MP4) {
                add(VideoContainerFormat.MP4)
            }
        }
        val codecInfos = cachedCodecInfos
        val candidates = mutableListOf<Pair<VideoContainerFormat, SelectedVideoEncoder>>()
        val seenKeys = HashSet<String>()

        for (container in containerOrder) {
            val candidateMimes = when (container) {
                VideoContainerFormat.MP4 -> {
                    if (preferredMime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)) {
                        listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)
                    } else {
                        listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)
                    }
                }
                VideoContainerFormat.WEBM -> listOf(
                    MediaFormat.MIMETYPE_VIDEO_VP9,
                    MediaFormat.MIMETYPE_VIDEO_VP8
                )
                VideoContainerFormat.THREE_GP -> listOf(
                    MediaFormat.MIMETYPE_VIDEO_AVC,
                    MediaFormat.MIMETYPE_VIDEO_H263
                )
            }

            val hwCandidates = mutableListOf<Pair<VideoContainerFormat, SelectedVideoEncoder>>()
            val swCandidates = mutableListOf<Pair<VideoContainerFormat, SelectedVideoEncoder>>()

            for (mime in candidateMimes) {
                val encoders = codecInfos.filter { info ->
                    info.isEncoder &&
                        !info.isAlias &&
                        info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                        supportsSurface(info, mime)
                }
                for (info in encoders) {
                    if (!seenKeys.add("${container.name}|${info.name}|$mime")) continue
                    val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
                    val videoCaps = caps.videoCapabilities ?: continue
                    val encoderCaps = caps.encoderCapabilities

                    val (outW, outH) = fitDimensionsWithoutUpscale(
                        videoCaps = videoCaps,
                        requestedW = requestedWidth,
                        requestedH = requestedHeight,
                        maxSourceW = maxSourceWidth,
                        maxSourceH = maxSourceHeight
                    ) ?: continue

                    val rawLow = videoCaps.bitrateRange.lower.coerceAtLeast(1)
                    val rawHigh = videoCaps.bitrateRange.upper.coerceAtLeast(rawLow)
                    if (rawHigh < 64_000) continue
                    val safeLow = rawLow.coerceAtLeast(MIN_SAFE_ENCODER_BITRATE_BPS).coerceAtMost(rawHigh)
                    val safeBitrate = requestedBitrateBps.coerceIn(safeLow, rawHigh)

                    val bitrateMode = when {
                        encoderCaps == null -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                        encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) ->
                            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                        encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) ->
                            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                        else -> null
                    }

                    val fastestComplexity = runCatching { encoderCaps?.complexityRange?.lower }.getOrNull()

                    val entry = container to SelectedVideoEncoder(
                        codecName = info.name,
                        mimeType = mime,
                        isHardware = info.isHardwareAccelerated,
                        isSoftwareOnly = info.isSoftwareOnly,
                        width = outW,
                        height = outH,
                        frameRate = frameRate,
                        bitrateBps = safeBitrate,
                        bitrateMode = bitrateMode,
                        fastestComplexity = fastestComplexity
                    )
                    if (info.isHardwareAccelerated && !info.isSoftwareOnly) {
                        hwCandidates += entry
                    } else {
                        swCandidates += entry
                    }
                }
            }

            // Strictly prefer verified hardware-accelerated encoders first with exact dimension alignment
            candidates += hwCandidates

            // System-default fallback before software encoders
            for (mime in candidateMimes) {
                candidates += container to SelectedVideoEncoder(
                    codecName = null,
                    mimeType = mime,
                    isHardware = true,
                    isSoftwareOnly = false,
                    width = (requestedWidth and 15.inv()).coerceAtLeast(16),
                    height = (requestedHeight and 15.inv()).coerceAtLeast(16),
                    frameRate = frameRate,
                    bitrateBps = requestedBitrateBps,
                    bitrateMode = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                    fastestComplexity = null
                )
            }

            // Software-only encoders strictly last resort
            candidates += swCandidates
        }
        return candidates
    }

    private fun configureVideoEncoderWithFallback(
        encoder: MediaCodec,
        spec: SelectedVideoEncoder,
        iFrameIntervalSec: Int,
        colorStandard: Int?,
        colorRange: Int?,
        colorTransfer: Int?
    ) {
        data class Attempt(
            val bitrateMode: Int?,
            val includeColorMetadata: Boolean,
            val operatingRate: Int?,
            val priority: Int?,
            val includeComplexity: Boolean
        )

        val hasColorMetadata = colorStandard != null || colorRange != null || colorTransfer != null
        val hasComplexity = spec.fastestComplexity != null
        val attempts = buildList {
            if (hasColorMetadata) {
                add(Attempt(spec.bitrateMode, true, TURBO_OPERATING_RATE, 1, hasComplexity))
                add(Attempt(spec.bitrateMode, true, 480, 1, hasComplexity))
                add(Attempt(spec.bitrateMode, true, null, null, false))
            }
            add(Attempt(spec.bitrateMode, false, TURBO_OPERATING_RATE, 1, hasComplexity))
            add(Attempt(spec.bitrateMode, false, 480, 1, hasComplexity))
            add(Attempt(spec.bitrateMode, false, null, null, false))
            if (spec.bitrateMode != null) {
                add(Attempt(null, false, null, null, false))
            }
        }

        var lastError: Throwable? = null
        for (attempt in attempts) {
            val format = MediaFormat.createVideoFormat(spec.mimeType, spec.width, spec.height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, spec.bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, spec.frameRate.coerceAtLeast(1))
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameIntervalSec)
                if (attempt.operatingRate != null) {
                    setInteger(MediaFormat.KEY_OPERATING_RATE, attempt.operatingRate)
                }
                if (attempt.priority != null) {
                    setInteger(MediaFormat.KEY_PRIORITY, attempt.priority)
                }
                if (attempt.includeComplexity && spec.fastestComplexity != null) {
                    setInteger(MediaFormat.KEY_COMPLEXITY, spec.fastestComplexity)
                }
                if (attempt.includeColorMetadata) {
                    colorStandard?.let { setInteger(MediaFormat.KEY_COLOR_STANDARD, it) }
                    colorRange?.let { setInteger(MediaFormat.KEY_COLOR_RANGE, it) }
                    colorTransfer?.let { setInteger(MediaFormat.KEY_COLOR_TRANSFER, it) }
                }
                if (attempt.bitrateMode != null) {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, attempt.bitrateMode)
                }
            }

            val res = runCatching {
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            if (res.isSuccess) return
            lastError = res.exceptionOrNull()
            runCatching { encoder.reset() }
        }
        throw lastError ?: IllegalStateException("Unable to configure video encoder.")
    }

    private suspend fun runLosslessRemux(
        context: Context,
        media: ImportedMediaInfo,
        videoExtractor: MediaExtractor,
        videoTrackIndex: Int,
        videoInputFormat: MediaFormat,
        audioTrackIndex: Int,
        compatibleAudioFormat: MediaFormat?,
        container: VideoContainerFormat,
        outFile: File,
        trimEndMs: Long,
        onProgress: (Float, String) -> Unit
    ): CropExportState? {
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        val trimEndUs = if (trimEndMs > 0L && trimEndMs < Long.MAX_VALUE) trimEndMs * 1_000L else Long.MAX_VALUE
        val effectiveDurationUs = (if (trimEndUs < Long.MAX_VALUE) trimEndUs else (media.durationMs * 1_000L)).coerceAtLeast(1L)
        return try {
            muxer = MediaMuxer(outFile.absolutePath, container.muxerOutputFormat)
            if (media.rotationDegrees != 0) {
                runCatching { muxer.setOrientationHint(media.rotationDegrees) }
            }
            val muxerVideoTrack = muxer.addTrack(videoInputFormat)
            val muxerAudioTrack = if (compatibleAudioFormat != null && audioTrackIndex >= 0) {
                muxer.addTrack(compatibleAudioFormat)
            } else {
                -1
            }
            muxer.start()
            muxerStarted = true

            onProgress(0.15f, "Copying lossless video stream…")
            videoExtractor.selectTrack(videoTrackIndex)
            val maxVideoSize = (if (videoInputFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                videoInputFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                1024 * 1024
            }).coerceAtLeast(512 * 1024)
            val videoBuf = ByteBuffer.allocateDirect(maxVideoSize)
            val info = MediaCodec.BufferInfo()
            var lastVideoPts = -1L
            var lastProgressMs = 0L

            while (coroutineContext.isActive) {
                val sampleSize = videoExtractor.readSampleData(videoBuf, 0)
                if (sampleSize < 0) break
                val rawPts = videoExtractor.sampleTime.coerceAtLeast(0L)
                if (rawPts > trimEndUs) break
                val pts = if (rawPts <= lastVideoPts) lastVideoPts + 1L else rawPts
                lastVideoPts = pts
                info.set(0, sampleSize, pts, videoExtractor.sampleFlags)
                videoBuf.position(0)
                videoBuf.limit(sampleSize)
                muxer.writeSampleData(muxerVideoTrack, videoBuf, info)

                val nowMs = System.currentTimeMillis()
                if (nowMs - lastProgressMs >= PROGRESS_UPDATE_INTERVAL_MS) {
                    lastProgressMs = nowMs
                    val ratio = (pts.toFloat() / effectiveDurationUs.toFloat()).coerceIn(0f, 1f)
                    onProgress(0.15f + ratio * 0.72f, "Copying lossless video stream…")
                }
                if (!videoExtractor.advance()) break
            }

            if (muxerAudioTrack >= 0 && compatibleAudioFormat != null && audioTrackIndex >= 0) {
                onProgress(0.90f, "Muxing audio track…")
                videoExtractor.unselectTrack(videoTrackIndex)
                videoExtractor.selectTrack(audioTrackIndex)
                videoExtractor.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val maxAudioSize = (if (compatibleAudioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    compatibleAudioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                } else {
                    256 * 1024
                }).coerceAtLeast(64 * 1024)
                val audioBuf = ByteBuffer.allocateDirect(maxAudioSize)
                var lastAudioPts = -1L
                while (coroutineContext.isActive) {
                    val sampleSize = videoExtractor.readSampleData(audioBuf, 0)
                    if (sampleSize < 0) break
                    val rawPts = videoExtractor.sampleTime.coerceAtLeast(0L)
                    if (rawPts > trimEndUs) break
                    if (lastVideoPts > 0L && rawPts > lastVideoPts + 100_000L) break
                    val pts = if (rawPts <= lastAudioPts) lastAudioPts + 1L else rawPts
                    lastAudioPts = pts
                    info.set(0, sampleSize, pts, videoExtractor.sampleFlags)
                    audioBuf.position(0)
                    audioBuf.limit(sampleSize)
                    muxer.writeSampleData(muxerAudioTrack, audioBuf, info)
                    if (!videoExtractor.advance()) break
                }
            }

            muxer.stop()
            muxerStarted = false

            onProgress(0.98f, "Saving to gallery…")
            val galleryUri = exportVideoToMediaStore(
                context = context,
                file = outFile,
                mimeType = container.mimeType
            )
            CropExportState.Success(
                outputPath = outFile.absolutePath,
                fileName = outFile.name,
                savedToGallery = galleryUri != null,
                mediaType = CropMediaType.VIDEO,
                galleryUriString = galleryUri
            )
        } catch (t: Throwable) {
            if (t is InterruptedException || t is kotlinx.coroutines.CancellationException) {
                throw t
            }
            runCatching {
                if (audioTrackIndex >= 0) videoExtractor.unselectTrack(audioTrackIndex)
                videoExtractor.unselectTrack(videoTrackIndex)
                videoExtractor.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            null
        } finally {
            if (muxerStarted) {
                runCatching { muxer?.stop() }
            }
            runCatching { muxer?.release() }
        }
    }

    private fun isVideoCompatibleWithContainer(
        videoMime: String?,
        container: VideoContainerFormat
    ): Boolean {
        if (videoMime.isNullOrBlank()) return false
        return when (container) {
            VideoContainerFormat.MP4 -> videoMime.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) ||
                videoMime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
            VideoContainerFormat.WEBM -> videoMime.equals(MediaFormat.MIMETYPE_VIDEO_VP9, ignoreCase = true) ||
                videoMime.equals(MediaFormat.MIMETYPE_VIDEO_VP8, ignoreCase = true)
            VideoContainerFormat.THREE_GP -> videoMime.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) ||
                videoMime.equals(MediaFormat.MIMETYPE_VIDEO_H263, ignoreCase = true)
        }
    }

    private fun supportsSurface(info: MediaCodecInfo, mimeType: String): Boolean {
        val caps = runCatching { info.getCapabilitiesForType(mimeType) }.getOrNull() ?: return false
        return MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in caps.colorFormats
    }

    private fun fitDimensionsWithoutUpscale(
        videoCaps: MediaCodecInfo.VideoCapabilities,
        requestedW: Int,
        requestedH: Int,
        maxSourceW: Int,
        maxSourceH: Int
    ): Pair<Int, Int>? {
        val wAlign = videoCaps.widthAlignment.coerceAtLeast(2).let { if (it % 2 == 0) it else it * 2 }
        val hAlign = videoCaps.heightAlignment.coerceAtLeast(2).let { if (it % 2 == 0) it else it * 2 }
        val minW = videoCaps.supportedWidths.lower.coerceAtLeast(wAlign)
        val minH = videoCaps.supportedHeights.lower.coerceAtLeast(hAlign)
        val maxW = videoCaps.supportedWidths.upper.coerceAtMost(maxSourceW)
        val maxH = videoCaps.supportedHeights.upper.coerceAtMost(maxSourceH)

        if (maxW < minW || maxH < minH) return null

        fun alignDown(v: Int, align: Int, minVal: Int): Int {
            return ((v / align) * align).coerceAtLeast(minVal)
        }

        val w0 = alignDown(requestedW.coerceIn(minW, maxW), wAlign, minW)
        val h0 = alignDown(requestedH.coerceIn(minH, maxH), hAlign, minH)
        if (videoCaps.isSizeSupported(w0, h0)) return w0 to h0

        val scales = listOf(0.85f, 0.75f, 0.5f, 0.35f)
        for (s in scales) {
            val sw = alignDown((requestedW * s).roundToInt().coerceIn(minW, maxW), wAlign, minW)
            val sh = alignDown((requestedH * s).roundToInt().coerceIn(minH, maxH), hAlign, minH)
            if (videoCaps.isSizeSupported(sw, sh)) return sw to sh
        }
        return null
    }

    private fun isAudioCompatibleWithContainer(
        audioMime: String?,
        container: VideoContainerFormat
    ): Boolean {
        if (audioMime.isNullOrBlank()) return false
        return when (container) {
            VideoContainerFormat.MP4 -> audioMime.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true)
            VideoContainerFormat.WEBM -> audioMime.equals(MediaFormat.MIMETYPE_AUDIO_OPUS, ignoreCase = true) ||
                audioMime.equals(MediaFormat.MIMETYPE_AUDIO_VORBIS, ignoreCase = true)
            VideoContainerFormat.THREE_GP -> audioMime.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true) ||
                audioMime.equals(MediaFormat.MIMETYPE_AUDIO_AMR_NB, ignoreCase = true) ||
                audioMime.equals(MediaFormat.MIMETYPE_AUDIO_AMR_WB, ignoreCase = true)
        }
    }

    private fun exportVideoToMediaStore(
        context: Context,
        file: File,
        mimeType: String
    ): String? {
        if (!file.exists() || file.length() <= 0L) return null
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, mimeType)
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/FrameScope")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            var copiedViaChannel = false
            resolver.openFileDescriptor(uri, "w")?.use { pfd ->
                FileInputStream(file).channel.use { src ->
                    FileOutputStream(pfd.fileDescriptor).channel.use { dst ->
                        val total = src.size()
                        var transferred = 0L
                        val chunk = 16L * 1024L * 1024L
                        while (transferred < total) {
                            val written = src.transferTo(transferred, chunk, dst)
                            if (written <= 0L) break
                            transferred += written
                        }
                        copiedViaChannel = transferred >= total
                    }
                }
            }
            if (!copiedViaChannel) {
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { input -> input.copyTo(out, bufferSize = 2 * 1024 * 1024) }
                }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            uri.toString()
        } catch (_: Throwable) {
            resolver.delete(uri, null, null)
            null
        }
    }

    private class EglCropSurface(
        encoderSurface: Surface,
        cropRect: NormalizedCropRect,
        rotationDegrees: Int
    ) {
        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var textureId = 0
        private var program = 0
        private var aPosHandle = 0
        private var aTexHandle = 0
        private var uStMatrixHandle = 0
        private var surfaceTexture: SurfaceTexture? = null

        private val frameSync = Object()
        @Volatile
        private var frameAvailable = false

        private val stMatrix = FloatArray(16)
        private val vertexBuffer: FloatBuffer
        private val texBuffer: FloatBuffer

        init {
            val positions = floatArrayOf(
                -1f, -1f,
                 1f, -1f,
                -1f,  1f,
                 1f,  1f
            )
            vertexBuffer = ByteBuffer.allocateDirect(positions.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(positions)
                    position(0)
                }

            // Display-space corners matching triangle strip (-1,-1), (1,-1), (-1,1), (1,1)
            val displayCorners = arrayOf(
                cropRect.left to cropRect.bottom,
                cropRect.right to cropRect.bottom,
                cropRect.left to cropRect.top,
                cropRect.right to cropRect.top
            )
            val texCoords = FloatArray(8)
            for (i in 0 until 4) {
                val (dx, dy) = displayCorners[i]
                val (rx, ry) = when (rotationDegrees) {
                    90 -> dy to (1f - dx)
                    180 -> (1f - dx) to (1f - dy)
                    270 -> (1f - dy) to dx
                    else -> dx to dy
                }
                texCoords[i * 2] = rx.coerceIn(0f, 1f)
                texCoords[i * 2 + 1] = (1f - ry).coerceIn(0f, 1f)
            }
            texBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(texCoords)
                    position(0)
                }

            initEgl(encoderSurface)
        }

        private fun initEgl(encoderSurface: Surface) {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "Unable to acquire EGLDisplay." }

            val version = IntArray(2)
            check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                "Unable to initialize EGL14."
            }

            val configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGLExt.EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            check(EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
                "Unable to choose EGLConfig."
            }
            val config = configs[0] ?: error("No matching EGLConfig found.")

            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            check(eglContext != EGL14.EGL_NO_CONTEXT) { "Unable to create EGLContext." }

            val surfAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, encoderSurface, surfAttribs, 0)
            check(eglSurface != EGL14.EGL_NO_SURFACE) { "Unable to create EGLWindowSurface." }
        }

        fun makeCurrent() {
            check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                "eglMakeCurrent failed."
            }
            setupGlShader()
        }

        fun createSurfaceTexture(handler: Handler): SurfaceTexture {
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            return SurfaceTexture(textureId).also { st ->
                st.setOnFrameAvailableListener({
                    synchronized(frameSync) {
                        frameAvailable = true
                        frameSync.notifyAll()
                    }
                }, handler)
                surfaceTexture = st
            }
        }

        fun awaitNewImage(stopRequested: AtomicBoolean): Boolean {
            synchronized(frameSync) {
                val deadline = System.currentTimeMillis() + 3_000L
                while (!frameAvailable) {
                    if (stopRequested.get()) return false
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0L) {
                        error("Timed out waiting for decoded video frame.")
                    }
                    frameSync.wait(minOf(remaining, 20L))
                }
                frameAvailable = false
            }
            surfaceTexture?.updateTexImage()
            return true
        }

        fun drawCroppedFrame(ptsUs: Long) {
            val st = surfaceTexture ?: return
            st.getTransformMatrix(stMatrix)

            GLES20.glUniformMatrix4fv(uStMatrixHandle, 1, false, stMatrix, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ptsUs * 1_000L)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }

        private fun setupGlShader() {
            val vertexSrc = """
                attribute vec4 aPosition;
                attribute vec4 aTextureCoord;
                uniform mat4 uSTMatrix;
                varying vec2 vTextureCoord;
                void main() {
                    gl_Position = aPosition;
                    vTextureCoord = (uSTMatrix * aTextureCoord).xy;
                }
            """.trimIndent()

            val fragmentSrc = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vTextureCoord;
                uniform samplerExternalOES sTexture;
                void main() {
                    gl_FragColor = texture2D(sTexture, vTextureCoord);
                }
            """.trimIndent()

            val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
            val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)

            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            check(linkStatus[0] == GLES20.GL_TRUE) {
                "GL program link failed: ${GLES20.glGetProgramInfoLog(program)}"
            }

            aPosHandle = GLES20.glGetAttribLocation(program, "aPosition")
            aTexHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
            uStMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")

            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glDisable(GLES20.GL_DITHER)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

            vertexBuffer.position(0)
            GLES20.glVertexAttribPointer(aPosHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            GLES20.glEnableVertexAttribArray(aPosHandle)

            texBuffer.position(0)
            GLES20.glVertexAttribPointer(aTexHandle, 2, GLES20.GL_FLOAT, false, 0, texBuffer)
            GLES20.glEnableVertexAttribArray(aTexHandle)
        }

        private fun compileShader(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) {
                "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}"
            }
            return shader
        }

        fun release() {
            if (program != 0) {
                runCatching { GLES20.glDeleteProgram(program) }
                program = 0
            }
            if (textureId != 0) {
                runCatching { GLES20.glDeleteTextures(1, intArrayOf(textureId), 0) }
                textureId = 0
            }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                }
                EGL14.eglTerminate(eglDisplay)
            }
            eglSurface = EGL14.EGL_NO_SURFACE
            eglContext = EGL14.EGL_NO_CONTEXT
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }
}
