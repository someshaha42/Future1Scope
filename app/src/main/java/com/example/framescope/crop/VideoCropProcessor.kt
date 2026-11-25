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
import android.provider.MediaStore
import android.view.Surface
import android.graphics.SurfaceTexture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

object VideoCropProcessor {

    private const val MIN_SAFE_ENCODER_BITRATE_BPS = 250_000
    private const val TIMEOUT_US = 10_000L

    private data class SelectedVideoEncoder(
        val codecName: String,
        val mimeType: String,
        val isHardware: Boolean,
        val width: Int,
        val height: Int,
        val bitrateBps: Int,
        val bitrateMode: Int?,
        val frameRate: Int,
        val profile: Int?
    )

    suspend fun exportCroppedVideo(
        context: Context,
        media: ImportedMediaInfo,
        cropRect: NormalizedCropRect,
        formatOption: VideoExportFormatOption,
        targetBitrateBps: Int,
        onProgress: (Float, String) -> Unit
    ): CropExportState = withContext(Dispatchers.IO) {
        val outDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir,
            "FrameScope"
        ).apply { if (!exists()) mkdirs() }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val outFile = File(outDir, "FrameScope_Crop_$stamp.${formatOption.container.extension}")

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

            videoExtractor.setDataSource(context, media.uri, null)
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

            val rawOutW = (cropRect.widthFraction * media.displayWidth).roundToInt()
                .coerceIn(16, media.displayWidth)
            val rawOutH = (cropRect.heightFraction * media.displayHeight).roundToInt()
                .coerceIn(16, media.displayHeight)

            val selectedEncoder = resolveVideoEncoder(
                container = formatOption.container,
                preferredMime = media.sourceVideoMime,
                requestedWidth = rawOutW,
                requestedHeight = rawOutH,
                maxSourceWidth = media.displayWidth,
                maxSourceHeight = media.displayHeight,
                requestedBitrateBps = targetBitrateBps.coerceAtLeast(MIN_SAFE_ENCODER_BITRATE_BPS),
                fps = media.fps
            ) ?: return@withContext CropExportState.Error(
                "No compatible video encoder found for ${formatOption.label}."
            )

            encoder = MediaCodec.createByCodecName(selectedEncoder.codecName)
            configureVideoEncoderWithFallback(encoder, selectedEncoder, media)
            encoderInputSurface = encoder.createInputSurface()

            glContext = EglCropSurface(
                encoderSurface = encoderInputSurface,
                cropRect = cropRect,
                rotationDegrees = media.rotationDegrees
            )
            glContext.makeCurrent()

            frameThread = HandlerThread("CropDecoderFrameThread").apply { start() }
            val frameHandler = Handler(frameThread.looper)

            surfaceTexture = glContext.createSurfaceTexture(frameHandler)
            decoderSurface = Surface(surfaceTexture)

            val inputMime = videoInputFormat.getString(MediaFormat.KEY_MIME)
                ?: return@withContext CropExportState.Error("Missing source video MIME type.")
            decoder = createVideoDecoder(inputMime)
            // Ensure SurfaceTexture receives raw frames cleanly without Surface-level rotation surprises
            val decoderFormat = MediaFormat(videoInputFormat).apply {
                if (containsKey(MediaFormat.KEY_ROTATION)) {
                    setInteger(MediaFormat.KEY_ROTATION, 0)
                }
            }
            decoder.configure(decoderFormat, decoderSurface, null, 0)

            muxer = MediaMuxer(outFile.absolutePath, formatOption.container.muxerOutputFormat)
            if (media.locationLat != null && media.locationLon != null) {
                runCatching { muxer.setLocation(media.locationLat, media.locationLon) }
            }

            val compatibleAudioFormat = audioInputFormat?.takeIf {
                isAudioCompatibleWithContainer(
                    it.getString(MediaFormat.KEY_MIME),
                    formatOption.container
                )
            }

            encoder.start()
            decoder.start()
            videoExtractor.selectTrack(videoTrackIndex)

            val durationUs = (media.durationMs * 1_000L).coerceAtLeast(1L)
            var muxerVideoTrack = -1
            var muxerAudioTrack = -1
            var lastVideoPtsUs = -1L

            var inputDone = false
            var decoderDone = false
            var encoderDone = false
            val bufferInfo = MediaCodec.BufferInfo()

            onProgress(0.05f, "Cropping video frames…")

            while (!encoderDone) {
                if (!coroutineContext.isActive) {
                    throw InterruptedException("Export cancelled.")
                }

                // 1. Feed compressed frames to decoder
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = decoder.getInputBuffer(inIndex)
                        if (inBuf != null) {
                            val sampleSize = videoExtractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(
                                    inIndex,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                val ptsUs = videoExtractor.sampleTime.coerceAtLeast(0L)
                                decoder.queueInputBuffer(
                                    inIndex,
                                    0,
                                    sampleSize,
                                    ptsUs,
                                    0
                                )
                                videoExtractor.advance()
                            }
                        }
                    }
                }

                // 2. Drain decoder output to SurfaceTexture -> render cropped quad to encoder surface
                if (!decoderDone) {
                    val outIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    if (outIndex >= 0) {
                        val doRender = bufferInfo.size > 0 &&
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        decoder.releaseOutputBuffer(outIndex, doRender)
                        if (doRender) {
                            glContext.awaitNewImage()
                            glContext.drawCroppedFrame(bufferInfo.presentationTimeUs)
                        }
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decoderDone = true
                            encoder.signalEndOfInputStream()
                        }
                    }
                }

                // 3. Drain encoder output to MediaMuxer
                var drainMore = true
                while (drainMore) {
                    val encIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    when {
                        encIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            drainMore = false
                        }
                        encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "Encoder format changed after muxer started." }
                            val encOutFormat = encoder.outputFormat
                            muxerVideoTrack = muxer.addTrack(encOutFormat)
                            if (compatibleAudioFormat != null) {
                                muxerAudioTrack = muxer.addTrack(compatibleAudioFormat)
                            }
                            muxer.start()
                            muxerStarted = true
                        }
                        encIndex >= 0 -> {
                            val encodedBuf = encoder.getOutputBuffer(encIndex)
                            if (encodedBuf != null &&
                                (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                                bufferInfo.size > 0 &&
                                muxerStarted &&
                                muxerVideoTrack >= 0
                            ) {
                                val pts = if (bufferInfo.presentationTimeUs <= lastVideoPtsUs) {
                                    lastVideoPtsUs + 1L
                                } else {
                                    bufferInfo.presentationTimeUs
                                }
                                lastVideoPtsUs = pts
                                bufferInfo.presentationTimeUs = pts

                                encodedBuf.position(bufferInfo.offset)
                                encodedBuf.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(muxerVideoTrack, encodedBuf, bufferInfo)

                                val videoWeight = if (muxerAudioTrack >= 0) 0.88f else 0.96f
                                val ratio = (pts.toFloat() / durationUs.toFloat()).coerceIn(0f, 1f)
                                onProgress(0.05f + ratio * videoWeight, "Cropping video frames…")
                            }
                            encoder.releaseOutputBuffer(encIndex, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                encoderDone = true
                                drainMore = false
                            }
                        }
                    }
                }
            }

            // 4. Copy compatible audio track cleanly
            if (muxerStarted && muxerAudioTrack >= 0 && audioTrackIndex >= 0) {
                onProgress(0.94f, "Muxing audio track…")
                audioExtractor = MediaExtractor().apply {
                    setDataSource(context, media.uri, null)
                    selectTrack(audioTrackIndex)
                }
                val maxAudioSize = (compatibleAudioFormat?.let {
                    if (it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        it.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                    } else null
                } ?: 256 * 1024).coerceAtLeast(64 * 1024)

                val audioBuf = ByteBuffer.allocateDirect(maxAudioSize)
                val audioInfo = MediaCodec.BufferInfo()
                var lastAudioPtsUs = -1L

                while (coroutineContext.isActive) {
                    val sampleSize = audioExtractor.readSampleData(audioBuf, 0)
                    if (sampleSize < 0) break
                    val sampleTime = audioExtractor.sampleTime.coerceAtLeast(0L)
                    if (lastVideoPtsUs > 0L && sampleTime > lastVideoPtsUs + 100_000L) {
                        break
                    }
                    val pts = if (sampleTime <= lastAudioPtsUs) lastAudioPtsUs + 1L else sampleTime
                    lastAudioPtsUs = pts

                    audioInfo.set(
                        0,
                        sampleSize,
                        pts,
                        audioExtractor.sampleFlags
                    )
                    audioBuf.position(0)
                    audioBuf.limit(sampleSize)
                    muxer.writeSampleData(muxerAudioTrack, audioBuf, audioInfo)
                    if (!audioExtractor.advance()) break
                }
            }

            if (muxerStarted) {
                muxer.stop()
                muxerStarted = false
            }

            onProgress(0.98f, "Saving to gallery…")
            val savedToGallery = exportVideoToMediaStore(
                context = context,
                file = outFile,
                mimeType = formatOption.container.mimeType
            )

            CropExportState.Success(
                outputPath = outFile.absolutePath,
                fileName = outFile.name,
                savedToGallery = savedToGallery,
                mediaType = CropMediaType.VIDEO
            )
        } catch (t: Throwable) {
            runCatching { outFile.delete() }
            if (t is InterruptedException || t is kotlinx.coroutines.CancellationException) {
                CropExportState.Idle
            } else {
                CropExportState.Error(t.message ?: "Failed to crop and export video.")
            }
        } finally {
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor?.release() }
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

    private fun createVideoDecoder(mimeType: String): MediaCodec {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val hw = list.codecInfos.firstOrNull { info ->
            !info.isEncoder && info.isHardwareAccelerated &&
                info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
        }
        if (hw != null) {
            return MediaCodec.createByCodecName(hw.name)
        }
        return MediaCodec.createDecoderByType(mimeType)
    }

    private fun resolveVideoEncoder(
        container: VideoContainerFormat,
        preferredMime: String?,
        requestedWidth: Int,
        requestedHeight: Int,
        maxSourceWidth: Int,
        maxSourceHeight: Int,
        requestedBitrateBps: Int,
        fps: Int
    ): SelectedVideoEncoder? {
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

        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (mime in candidateMimes) {
            val encoders = list.codecInfos.filter { info ->
                info.isEncoder &&
                    info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                    supportsSurface(info, mime)
            }
            val ordered = encoders.sortedByDescending { it.isHardwareAccelerated }
            for (info in ordered) {
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

                val safeBitrate = requestedBitrateBps.coerceIn(
                    videoCaps.bitrateRange.lower.coerceAtLeast(MIN_SAFE_ENCODER_BITRATE_BPS),
                    videoCaps.bitrateRange.upper.coerceAtLeast(videoCaps.bitrateRange.lower)
                )

                val bitrateMode = when {
                    encoderCaps == null -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                    encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) ->
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                    encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) ->
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    else -> null
                }

                val safeFps = runCatching {
                    val range = videoCaps.getSupportedFrameRatesFor(outW, outH)
                    fps.toDouble().coerceIn(range.lower, range.upper).roundToInt().coerceAtLeast(15)
                }.getOrDefault(fps.coerceIn(15, 60))

                val profile = when (mime) {
                    MediaFormat.MIMETYPE_VIDEO_HEVC -> caps.profileLevels?.firstOrNull {
                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
                    }?.profile
                    MediaFormat.MIMETYPE_VIDEO_AVC -> caps.profileLevels?.firstOrNull {
                        it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                    }?.profile
                    else -> null
                }

                return SelectedVideoEncoder(
                    codecName = info.name,
                    mimeType = mime,
                    isHardware = info.isHardwareAccelerated,
                    width = outW,
                    height = outH,
                    bitrateBps = safeBitrate,
                    bitrateMode = bitrateMode,
                    frameRate = safeFps,
                    profile = profile
                )
            }
        }
        return null
    }

    private fun configureVideoEncoderWithFallback(
        encoder: MediaCodec,
        spec: SelectedVideoEncoder,
        media: ImportedMediaInfo
    ) {
        data class Attempt(
            val bitrateMode: Int?,
            val profile: Int?,
            val includeColor: Boolean
        )

        val attempts = listOf(
            Attempt(spec.bitrateMode, spec.profile, includeColor = true),
            Attempt(spec.bitrateMode, null, includeColor = true),
            Attempt(spec.bitrateMode, null, includeColor = false),
            Attempt(null, null, includeColor = false)
        )

        var lastError: Throwable? = null
        for (attempt in attempts) {
            val format = MediaFormat.createVideoFormat(spec.mimeType, spec.width, spec.height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, spec.bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, spec.frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                if (attempt.bitrateMode != null) {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, attempt.bitrateMode)
                }
                if (attempt.profile != null) {
                    setInteger(MediaFormat.KEY_PROFILE, attempt.profile)
                }
                if (attempt.includeColor) {
                    media.colorStandard?.let { setInteger(MediaFormat.KEY_COLOR_STANDARD, it) }
                    media.colorRange?.let { setInteger(MediaFormat.KEY_COLOR_RANGE, it) }
                    media.colorTransfer?.let { setInteger(MediaFormat.KEY_COLOR_TRANSFER, it) }
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
    ): Boolean {
        if (!file.exists() || file.length() <= 0L) return false
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, mimeType)
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/FrameScope")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out, bufferSize = 256 * 1024) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            true
        } catch (_: Throwable) {
            resolver.delete(uri, null, null)
            false
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

        fun awaitNewImage() {
            synchronized(frameSync) {
                val deadline = System.currentTimeMillis() + 2_500L
                while (!frameAvailable) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0L) {
                        error("Timed out waiting for decoded video frame.")
                    }
                    frameSync.wait(remaining)
                }
                frameAvailable = false
            }
            surfaceTexture?.updateTexImage()
        }

        fun drawCroppedFrame(ptsUs: Long) {
            val st = surfaceTexture ?: return
            st.getTransformMatrix(stMatrix)

            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

            GLES20.glUniformMatrix4fv(uStMatrixHandle, 1, false, stMatrix, 0)

            vertexBuffer.position(0)
            GLES20.glVertexAttribPointer(aPosHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            GLES20.glEnableVertexAttribArray(aPosHandle)

            texBuffer.position(0)
            GLES20.glVertexAttribPointer(aTexHandle, 2, GLES20.GL_FLOAT, false, 0, texBuffer)
            GLES20.glEnableVertexAttribArray(aTexHandle)

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
