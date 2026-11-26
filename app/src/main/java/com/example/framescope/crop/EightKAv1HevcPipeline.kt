package com.example.framescope.crop

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * Dedicated 8K-ONLY (7680x4320 / 4320x7680) Pipeline:
 * - Import & Editing Playback: Software-Based AV1 (video/av01) transcode & Software AV1 decoding.
 * - Export: Software-Based H.265 / HEVC (video/hevc) encoding in MP4 container.
 *
 * Strictly isolated from all non-8K resolutions (4K, 2K, 1080p, 720p, etc.).
 * Never uses hardware video decoders, never downscales 8K resolution, and never silently switches codecs.
 */
object EightKAv1HevcPipeline {

    private const val WIDTH_8K = 7680
    private const val HEIGHT_8K = 4320
    private const val CODEC_WAIT_US = 10_000L
    private const val PROGRESS_UPDATE_INTERVAL_MS = 60L

    private val cachedCodecInfos: Array<MediaCodecInfo> by lazy {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
    }

    fun is8kResolution(width: Int, height: Int): Boolean {
        return (width == WIDTH_8K && height == HEIGHT_8K) ||
            (width == HEIGHT_8K && height == WIDTH_8K)
    }

    fun isStrictSoftwareCodec(info: MediaCodecInfo): Boolean {
        if (info.isAlias) return false
        if (info.isHardwareAccelerated) return false
        return info.isSoftwareOnly ||
            info.name.startsWith("c2.android.", ignoreCase = true) ||
            info.name.startsWith("OMX.google.", ignoreCase = true)
    }

    fun resolveSoftwareDecoders(mimeType: String): List<Pair<String, String?>> {
        val primary = cachedCodecInfos
            .filter { info ->
                !info.isEncoder &&
                    isStrictSoftwareCodec(info) &&
                    info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
            }
            .sortedWith(
                compareByDescending<MediaCodecInfo> { it.isSoftwareOnly }
                    .thenByDescending { it.name.contains("dav1d", ignoreCase = true) }
                    .thenByDescending { it.name.startsWith("c2.android.", ignoreCase = true) }
            )
            .map { it.name to null }

        if (!mimeType.equals("video/dolby-vision", ignoreCase = true)) {
            return primary
        }
        val hevcFallbacks = cachedCodecInfos
            .filter { info ->
                !info.isEncoder &&
                    isStrictSoftwareCodec(info) &&
                    info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) }
            }
            .sortedWith(
                compareByDescending<MediaCodecInfo> { it.isSoftwareOnly }
                    .thenByDescending { it.name.startsWith("c2.android.", ignoreCase = true) }
            )
            .map { it.name to MediaFormat.MIMETYPE_VIDEO_HEVC }
        return primary + hevcFallbacks
    }

    fun resolveSoftwareAv1Decoders(): List<String> {
        return resolveSoftwareDecoders(MediaFormat.MIMETYPE_VIDEO_AV1).map { it.first }
    }

    fun resolveSoftwareAv1Encoders(): List<MediaCodecInfo> {
        return cachedCodecInfos
            .filter { info ->
                info.isEncoder &&
                    isStrictSoftwareCodec(info) &&
                    info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AV1, ignoreCase = true) }
            }
            .sortedWith(
                compareByDescending<MediaCodecInfo> { it.isSoftwareOnly }
                    .thenByDescending { it.name.startsWith("c2.android.", ignoreCase = true) }
            )
    }

    fun resolveSoftwareHevcEncoders(): List<MediaCodecInfo> {
        return cachedCodecInfos
            .filter { info ->
                info.isEncoder &&
                    isStrictSoftwareCodec(info) &&
                    info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) }
            }
            .sortedWith(
                compareByDescending<MediaCodecInfo> { it.isSoftwareOnly }
                    .thenByDescending { it.name.startsWith("c2.android.", ignoreCase = true) }
            )
    }

    fun resolveSoftwareAudioDecoders(mimeType: String): List<String> {
        return cachedCodecInfos
            .filter { info ->
                !info.isEncoder &&
                    isStrictSoftwareCodec(info) &&
                    info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
            }
            .sortedByDescending { it.isSoftwareOnly }
            .map { it.name }
    }

    private fun resolveSoftwareAacEncoders(): List<String> {
        return cachedCodecInfos
            .filter { info ->
                info.isEncoder &&
                    isStrictSoftwareCodec(info) &&
                    info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true) }
            }
            .sortedByDescending { it.isSoftwareOnly }
            .map { it.name }
    }

    fun detectBitDepth(
        mime: String?,
        profile: Int?,
        colorTransfer: Int?,
        format: MediaFormat?
    ): Int {
        if (format != null && format.containsKey(MediaFormat.KEY_COLOR_FORMAT)) {
            val cf = format.getInteger(MediaFormat.KEY_COLOR_FORMAT)
            if (cf == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010) return 10
        }
        if (colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084 ||
            colorTransfer == MediaFormat.COLOR_TRANSFER_HLG
        ) {
            return 10
        }
        if (profile != null && mime != null) {
            when {
                mime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) -> {
                    if (profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                        profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                        profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                    ) {
                        return 10
                    }
                }
                mime.equals(MediaFormat.MIMETYPE_VIDEO_VP9, ignoreCase = true) -> {
                    if (profile == MediaCodecInfo.CodecProfileLevel.VP9Profile2 ||
                        profile == MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR ||
                        profile == MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR10Plus ||
                        profile == MediaCodecInfo.CodecProfileLevel.VP9Profile3 ||
                        profile == MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR ||
                        profile == MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR10Plus
                    ) {
                        return 10
                    }
                }
                mime.equals(MediaFormat.MIMETYPE_VIDEO_AV1, ignoreCase = true) -> {
                    if (profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10 ||
                        profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10 ||
                        profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus
                    ) {
                        return 10
                    }
                }
            }
        }
        return 8
    }

    /**
     * 8K Import Processing (AV1):
     * - Disables Hardware Video Decoding.
     * - Decodes any incoming 8K codec using a compatible Software Decoder.
     * - If source is already AV1 (video/av01), verifies Software AV1 Decoder and uses source directly.
     * - If source is any other codec, converts decoded video into AV1 (video/av01) using a Software AV1 Encoder,
     *   preserving 7680x4320, frame rate, aspect ratio, and color info without deleting/replacing the original file.
     */
    suspend fun prepare8kAv1Workspace(
        context: Context,
        projectId: String,
        media: ImportedMediaInfo,
        existingWorkspacePath: String? = null,
        onProgress: (String) -> Unit = {}
    ): Result<String?> = withContext(Dispatchers.IO) {
        runCatching {
            check(media.is8kUhd) {
                "8K AV1 workspace requested for non-8K resolution (${media.rawWidth}×${media.rawHeight})."
            }

            val sourceMime = media.sourceVideoMime
                ?: error("Missing source video codec information for 8K media.")

            val swSourceDecoders = resolveSoftwareDecoders(sourceMime)
            check(swSourceDecoders.isNotEmpty()) {
                "8K compatibility error: No Software Decoder is available on this device for $sourceMime (hardware decoding is disabled for 8K)."
            }

            val swAv1Decoders = resolveSoftwareAv1Decoders()
            check(swAv1Decoders.isNotEmpty()) {
                "8K compatibility error: No AV1 Software Decoder (video/av01) is available on this device for 8K editing playback."
            }

            if (sourceMime.equals(MediaFormat.MIMETYPE_VIDEO_AV1, ignoreCase = true)) {
                onProgress("Verifying 8K AV1 Software Decoder…")
                verifySoftwareAv1Decodable(context, media.uri, swAv1Decoders)
                return@runCatching null
            }

            val existing = existingWorkspacePath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
            if (existing != null) {
                return@runCatching existing.absolutePath
            }

            val swAv1Encoders = resolveSoftwareAv1Encoders()
            check(swAv1Encoders.isNotEmpty()) {
                "8K compatibility error: Source codec is $sourceMime, and no Software AV1 Encoder (video/av01) is available on this device to convert 8K video to AV1."
            }

            val workspaceDir = File(context.filesDir, "crop_projects/av1_8k").apply {
                if (!exists()) mkdirs()
            }
            val workspaceFile = File(workspaceDir, "${projectId}_8k_av1.mp4")
            val tempFile = File(workspaceDir, "${projectId}_8k_av1.tmp.mp4")
            runCatching { tempFile.delete() }

            onProgress("Converting 8K video to Software AV1 (${media.rawWidth}×${media.rawHeight})…")
            try {
                runSoftwareTranscodePipeline(
                    context = context,
                    sourceUri = media.uri,
                    media = media,
                    targetMime = MediaFormat.MIMETYPE_VIDEO_AV1,
                    cropRect = NormalizedCropRect(0f, 0f, 1f, 1f),
                    targetWidth = media.rawWidth,
                    targetHeight = media.rawHeight,
                    keepOriginalRawOrientation = true,
                    trimStartMs = 0L,
                    trimEndMs = media.durationMs,
                    outFile = tempFile,
                    preferLossless = false,
                    onProgress = { _, _ -> }
                )
                if (workspaceFile.exists()) workspaceFile.delete()
                check(tempFile.renameTo(workspaceFile)) {
                    "Failed to finalize 8K AV1 workspace file."
                }
                workspaceFile.absolutePath
            } catch (t: Throwable) {
                runCatching { tempFile.delete() }
                runCatching { workspaceFile.delete() }
                throw t
            }
        }
    }

    /**
     * 8K Export Processing (H.265 / HEVC):
     * - Exports at original 8K resolution (7680x4320) or cropped dimensions without downscaling.
     * - Strictly targets H.265 (video/hevc) in MP4 container (never switches to H.264/VP9/AV1).
     * - Uses Software Decoding (never HW decoder) and Software-Based H.265 Encoding.
     */
    suspend fun export8kHevcVideo(
        context: Context,
        media: ImportedMediaInfo,
        cropRect: NormalizedCropRect,
        trimStartMs: Long,
        trimEndMs: Long,
        onProgress: (Float, String) -> Unit
    ): CropExportState = withContext(Dispatchers.IO) {
        var outFile: File? = null
        try {
            check(media.is8kUhd) {
                "8K H.265 export called on non-8K video."
            }

            val swHevcEncoders = resolveSoftwareHevcEncoders()
            if (swHevcEncoders.isEmpty()) {
                return@withContext CropExportState.Error(
                    "8K H.265 export error: No Software H.265 (HEVC) Encoder is available on this device (codec switching or downscaling is disabled for 8K)."
                )
            }

            val isFullCrop = cropRect.left <= 0.002f &&
                cropRect.top <= 0.002f &&
                cropRect.right >= 0.998f &&
                cropRect.bottom >= 0.998f
            val isFullStart = trimStartMs <= 50L
            val isFullEnd = media.durationMs <= 0L || trimEndMs <= 0L || trimEndMs >= media.durationMs - 50L

            outFile = CropExportsRepository(context).createVideoExportFile(VideoContainerFormat.MP4.extension)

            // If source is already H.265/HEVC and uncropped/untrimmed, do lossless HEVC MP4 remux
            if (media.sourceVideoMime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) &&
                isFullCrop &&
                isFullStart &&
                isFullEnd
            ) {
                onProgress(0.05f, "Exporting lossless 8K H.265 stream…")
                val remuxed = tryLosslessHevcRemux(
                    context = context,
                    sourceUri = media.uri,
                    media = media,
                    outFile = outFile,
                    onProgress = onProgress
                )
                if (remuxed != null) {
                    return@withContext remuxed
                }
            }

            val outW = ((cropRect.widthFraction * media.displayWidth).roundToInt() and 1.inv())
                .coerceIn(16, media.displayWidth)
            val outH = ((cropRect.heightFraction * media.displayHeight).roundToInt() and 1.inv())
                .coerceIn(16, media.displayHeight)

            val decodeUri = resolveBest8kDecodeUri(context, media)
            val keepRawOrientation = isFullCrop && media.rotationDegrees != 0
            val encW = if (keepRawOrientation) media.rawWidth else outW
            val encH = if (keepRawOrientation) media.rawHeight else outH

            onProgress(0.03f, "Initializing 8K Software H.265 Encoder (${encW}×${encH})…")

            runSoftwareTranscodePipeline(
                context = context,
                sourceUri = decodeUri,
                media = media,
                targetMime = MediaFormat.MIMETYPE_VIDEO_HEVC,
                cropRect = cropRect,
                targetWidth = encW,
                targetHeight = encH,
                keepOriginalRawOrientation = keepRawOrientation,
                trimStartMs = trimStartMs,
                trimEndMs = trimEndMs,
                outFile = outFile,
                preferLossless = true,
                onProgress = onProgress
            )

            onProgress(0.98f, "Saving 8K H.265 video to gallery…")
            val galleryUri = exportVideoToMediaStore(
                context = context,
                file = outFile,
                mimeType = VideoContainerFormat.MP4.mimeType
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
                CropExportState.Error(t.message ?: "8K Software H.265 export failed.")
            }
        }
    }

    private fun resolveBest8kDecodeUri(context: Context, media: ImportedMediaInfo): Uri {
        val originalAccessible = runCatching {
            if (media.uri.scheme == "file") {
                media.uri.path?.let { File(it).exists() } == true
            } else {
                context.contentResolver.openAssetFileDescriptor(media.uri, "r")?.use { true } ?: false
            }
        }.getOrDefault(false)
        if (originalAccessible && !media.sourceVideoMime.isNullOrBlank() &&
            resolveSoftwareDecoders(media.sourceVideoMime).isNotEmpty()
        ) {
            return media.uri
        }
        val wsFile = media.av1WorkspacePath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
        if (wsFile != null) {
            return Uri.fromFile(wsFile)
        }
        return media.uri
    }

    private fun verifySoftwareAv1Decodable(
        context: Context,
        uri: Uri,
        swAv1Decoders: List<String>
    ) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.equals(MediaFormat.MIMETYPE_VIDEO_AV1, ignoreCase = true)) {
                    trackFormat = fmt
                    break
                }
            }
            val fmt = trackFormat ?: error("AV1 video track (video/av01) not found in source file.")
            val errors = mutableListOf<String>()
            for (codecName in swAv1Decoders) {
                val ok = runCatching {
                    decoder = MediaCodec.createByCodecName(codecName)
                    val decFmt = MediaFormat(fmt)
                    decoder!!.configure(decFmt, null, null, 0)
                    decoder!!.start()
                }
                if (ok.isSuccess) return
                errors += "$codecName: ${ok.exceptionOrNull()?.message ?: "configure failed"}"
                runCatching { decoder?.stop() }
                runCatching { decoder?.release() }
                decoder = null
            }
            error("8K AV1 Software Decoder could not initialize for 7680×4320 (${errors.joinToString("; ")})")
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private data class ConfiguredSwVideoEncoder(
        val codec: MediaCodec,
        val codecName: String,
        val targetMime: String,
        val colorFormat: Int,
        val is10Bit: Boolean,
        val isLosslessMode: Boolean
    )

    private fun openConfiguredSoftwareEncoder(
        targetMime: String,
        width: Int,
        height: Int,
        media: ImportedMediaInfo,
        preferLossless: Boolean
    ): ConfiguredSwVideoEncoder {
        val isAv1 = targetMime.equals(MediaFormat.MIMETYPE_VIDEO_AV1, ignoreCase = true)
        val encoders = if (isAv1) resolveSoftwareAv1Encoders() else resolveSoftwareHevcEncoders()
        val label = if (isAv1) "AV1" else "H.265 (HEVC)"
        check(encoders.isNotEmpty()) {
            "No Software $label Encoder found on this device for 8K (${width}×${height})."
        }

        val fpsInt = (media.exactFps?.roundToInt() ?: media.fps).coerceAtLeast(1)
        val want10Bit = media.bitDepth >= 10
        val baseBitrate = (media.originalBitrateBps ?: 100_000_000L)
            .coerceAtLeast(if (preferLossless) 120_000_000L else 65_000_000L)

        val failureReasons = mutableListOf<String>()

        for (info in encoders) {
            val caps = runCatching { info.getCapabilitiesForType(targetMime) }.getOrNull()
            val videoCaps = caps?.videoCapabilities
            val encCaps = caps?.encoderCapabilities
            val supportedColorFormats = caps?.colorFormats?.toSet().orEmpty()

            val supportsProfile10 = if (isAv1) {
                caps?.profileLevels?.any {
                    it.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10 ||
                        it.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10 ||
                        it.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus
                } == true
            } else {
                caps?.profileLevels?.any {
                    it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                } == true
            }

            val bufferColorCandidates = buildList {
                if (want10Bit && supportsProfile10 &&
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010 in supportedColorFormats
                ) {
                    add(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010 to true)
                }
                if (MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in supportedColorFormats) {
                    add(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible to false)
                }
                if (MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar in supportedColorFormats) {
                    add(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar to false)
                }
                if (MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar in supportedColorFormats) {
                    add(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar to false)
                }
                if (isEmpty()) {
                    add(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible to false)
                }
            }

            val bitrateUpper = videoCaps?.bitrateRange?.upper ?: 200_000_000
            val bitrateLower = videoCaps?.bitrateRange?.lower ?: 250_000
            val safeBitrate = baseBitrate.coerceIn(bitrateLower.toLong(), bitrateUpper.toLong()).toInt()

            val supportsCq = encCaps?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ) == true
            val supportsVbr = encCaps?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) == true
            val maxQuality = encCaps?.qualityRange?.upper ?: 100

            data class Attempt(
                val colorFormat: Int,
                val use10Bit: Boolean,
                val bitrateMode: Int?,
                val quality: Int?,
                val losslessAttempt: Boolean,
                val includeColorInfo: Boolean
            )

            val attempts = buildList {
                for ((cf, is10) in bufferColorCandidates) {
                    if (supportsCq) {
                        add(
                            Attempt(
                                colorFormat = cf,
                                use10Bit = is10,
                                bitrateMode = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ,
                                quality = if (preferLossless) maxQuality else (maxQuality * 0.9f).roundToInt(),
                                losslessAttempt = preferLossless,
                                includeColorInfo = true
                            )
                        )
                    }
                    if (supportsVbr || !supportsCq) {
                        add(
                            Attempt(
                                colorFormat = cf,
                                use10Bit = is10,
                                bitrateMode = if (supportsVbr) MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR else null,
                                quality = null,
                                losslessAttempt = false,
                                includeColorInfo = true
                            )
                        )
                    }
                    add(
                        Attempt(
                            colorFormat = cf,
                            use10Bit = is10,
                            bitrateMode = null,
                            quality = null,
                            losslessAttempt = false,
                            includeColorInfo = false
                        )
                    )
                }
            }

            for (attempt in attempts) {
                var codec: MediaCodec? = null
                val res = runCatching {
                    codec = MediaCodec.createByCodecName(info.name)
                    val format = MediaFormat.createVideoFormat(targetMime, width, height).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, attempt.colorFormat)
                        setInteger(MediaFormat.KEY_BIT_RATE, safeBitrate)
                        setInteger(MediaFormat.KEY_FRAME_RATE, fpsInt)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

                        if (attempt.use10Bit) {
                            val profile10 = if (isAv1) {
                                when (media.colorTransfer) {
                                    MediaFormat.COLOR_TRANSFER_ST2084,
                                    MediaFormat.COLOR_TRANSFER_HLG ->
                                        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10
                                    else -> MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10
                                }
                            } else {
                                when (media.colorTransfer) {
                                    MediaFormat.COLOR_TRANSFER_ST2084,
                                    MediaFormat.COLOR_TRANSFER_HLG ->
                                        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
                                    else -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                                }
                            }
                            setInteger(MediaFormat.KEY_PROFILE, profile10)
                        }

                        if (attempt.includeColorInfo) {
                            media.colorStandard?.let { setInteger(MediaFormat.KEY_COLOR_STANDARD, it) }
                            media.colorRange?.let { setInteger(MediaFormat.KEY_COLOR_RANGE, it) }
                            media.colorTransfer?.let { setInteger(MediaFormat.KEY_COLOR_TRANSFER, it) }
                        }

                        if (attempt.bitrateMode != null) {
                            setInteger(MediaFormat.KEY_BITRATE_MODE, attempt.bitrateMode)
                        }
                        if (attempt.quality != null) {
                            setInteger(MediaFormat.KEY_QUALITY, attempt.quality)
                        }
                    }
                    codec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    codec!!.start()
                }
                if (res.isSuccess && codec != null) {
                    return ConfiguredSwVideoEncoder(
                        codec = codec!!,
                        codecName = info.name,
                        targetMime = targetMime,
                        colorFormat = attempt.colorFormat,
                        is10Bit = attempt.use10Bit,
                        isLosslessMode = attempt.losslessAttempt
                    )
                }
                failureReasons += "${info.name} (cf=${attempt.colorFormat}, ${width}x${height}): ${res.exceptionOrNull()?.message}"
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
            }
        }

        error(
            "8K Software $label Encoder failed to configure at ${width}×${height} without downscaling: " +
                failureReasons.distinct().take(3).joinToString(" | ")
        )
    }

    private fun openConfiguredSoftwareDecoder(
        sourceFormat: MediaFormat,
        mime: String
    ): MediaCodec {
        val candidates = resolveSoftwareDecoders(mime)
        check(candidates.isNotEmpty()) {
            "No Software Decoder available for $mime."
        }
        val errors = mutableListOf<String>()
        for ((codecName, overrideMime) in candidates) {
            var decoder: MediaCodec? = null
            val res = runCatching {
                decoder = MediaCodec.createByCodecName(codecName)
                val decFmt = MediaFormat(sourceFormat).apply {
                    if (overrideMime != null) {
                        setString(MediaFormat.KEY_MIME, overrideMime)
                    }
                    if (containsKey(MediaFormat.KEY_ROTATION)) {
                        setInteger(MediaFormat.KEY_ROTATION, 0)
                    }
                }
                decoder!!.configure(decFmt, null, null, 0)
                decoder!!.start()
            }
            if (res.isSuccess && decoder != null) {
                return decoder!!
            }
            errors += "$codecName: ${res.exceptionOrNull()?.message}"
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
        }
        error("8K Software Decoder failed for $mime: ${errors.joinToString("; ")}")
    }

    private suspend fun runSoftwareTranscodePipeline(
        context: Context,
        sourceUri: Uri,
        media: ImportedMediaInfo,
        targetMime: String,
        cropRect: NormalizedCropRect,
        targetWidth: Int,
        targetHeight: Int,
        keepOriginalRawOrientation: Boolean,
        trimStartMs: Long,
        trimEndMs: Long,
        outFile: File,
        preferLossless: Boolean,
        onProgress: (Float, String) -> Unit
    ) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            extractor.setDataSource(context, sourceUri, null)
            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var videoInputFormat: MediaFormat? = null
            var audioInputFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
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
                "No video track found in 8K source."
            }

            val inputMime = videoInputFormat.getString(MediaFormat.KEY_MIME)
                ?: error("Missing video MIME type.")

            val configuredEncoder = openConfiguredSoftwareEncoder(
                targetMime = targetMime,
                width = targetWidth,
                height = targetHeight,
                media = media,
                preferLossless = preferLossless
            )
            encoder = configuredEncoder.codec
            decoder = openConfiguredSoftwareDecoder(videoInputFormat, inputMime)

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (keepOriginalRawOrientation && media.rotationDegrees != 0) {
                runCatching { muxer.setOrientationHint(media.rotationDegrees) }
            }

            val audioMime = audioInputFormat?.getString(MediaFormat.KEY_MIME)
            val canPassthroughAudio = audioMime != null &&
                audioMime.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true)
            val swAudioTranscodePlan = if (!canPassthroughAudio && audioInputFormat != null && audioMime != null) {
                prepareAacAudioTranscodePlan(audioInputFormat, audioMime)
            } else {
                null
            }

            var muxerVideoTrack = -1
            var muxerAudioTrack = -1

            extractor.selectTrack(videoTrackIndex)
            val trimStartUs = trimStartMs.coerceAtLeast(0L) * 1_000L
            val effectiveEndMs = if (trimEndMs > trimStartMs) trimEndMs else media.durationMs
            val trimEndUs = if (effectiveEndMs > 0L) effectiveEndMs * 1_000L else Long.MAX_VALUE
            val durationUs = ((if (trimEndUs < Long.MAX_VALUE) trimEndUs else (media.durationMs * 1_000L)) - trimStartUs)
                .coerceAtLeast(1L)

            if (trimStartUs > 0L) {
                extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }

            val dispW = media.displayWidth
            val dispH = media.displayHeight
            val cropDispX = if (keepOriginalRawOrientation) 0 else {
                ((cropRect.left * dispW).roundToInt() and 1.inv()).coerceIn(0, (dispW - targetWidth).coerceAtLeast(0))
            }
            val cropDispY = if (keepOriginalRawOrientation) 0 else {
                ((cropRect.top * dispH).roundToInt() and 1.inv()).coerceIn(0, (dispH - targetHeight).coerceAtLeast(0))
            }
            val effectiveRotateDeg = if (keepOriginalRawOrientation) 0 else media.rotationDegrees

            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var extractorDone = false
            var decoderDone = false
            var encoderInputSignaledEos = false
            var encoderDone = false
            var lastVideoPtsUs = -1L
            var lastProgressMs = 0L
            var pastTrimCount = 0

            val codecLabel = if (targetMime.equals(MediaFormat.MIMETYPE_VIDEO_AV1, ignoreCase = true)) {
                "8K SW AV1"
            } else {
                "8K SW H.265"
            }
            val statusLabel = "$codecLabel (${configuredEncoder.codecName})…"
            onProgress(0.06f, statusLabel)

            while (!encoderDone) {
                if (!coroutineContext.isActive) {
                    throw InterruptedException("8K transcoding cancelled.")
                }

                // 1. Feed SW Decoder
                if (!extractorDone) {
                    val inIdx = decoder.dequeueInputBuffer(0L)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            val samplePtsUs = extractor.sampleTime.coerceAtLeast(0L)
                            val pastTrim = trimEndUs < Long.MAX_VALUE && samplePtsUs > trimEndUs + 250_000L
                            if (pastTrim) pastTrimCount++ else pastTrimCount = 0

                            if (sampleSize < 0 || pastTrimCount >= 4) {
                                decoder.queueInputBuffer(
                                    inIdx,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                extractorDone = true
                            } else {
                                decoder.queueInputBuffer(inIdx, 0, sampleSize, samplePtsUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                // 2. Drain SW Decoder -> Crop YUV -> Feed SW Encoder
                if (!decoderDone) {
                    val outIdx = decoder.dequeueOutputBuffer(decInfo, CODEC_WAIT_US)
                    if (outIdx >= 0) {
                        val ptsUs = decInfo.presentationTimeUs
                        val isCodecConfig = (decInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val isEos = (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val withinRange = decInfo.size > 0 && !isCodecConfig && ptsUs >= trimStartUs && ptsUs <= trimEndUs

                        if (withinRange) {
                            val rebasedPts = (ptsUs - trimStartUs).coerceAtLeast(0L)
                            val decImg = runCatching { decoder.getOutputImage(outIdx) }.getOrNull()
                            val encInIdx = dequeueInputBufferBlocking(encoder)

                            if (decImg != null) {
                                decImg.use { srcImage ->
                                    val dstImage = runCatching { encoder.getInputImage(encInIdx) }.getOrNull()
                                    if (dstImage != null) {
                                        dstImage.use { targetImg ->
                                            copyCroppedYuvImage(
                                                src = srcImage,
                                                dst = targetImg,
                                                cropDispX = cropDispX,
                                                cropDispY = cropDispY,
                                                outW = targetWidth,
                                                outH = targetHeight,
                                                rotateDeg = effectiveRotateDeg
                                            )
                                        }
                                        val bufSize = estimateYuvBufferSize(targetWidth, targetHeight, configuredEncoder.is10Bit)
                                        encoder.queueInputBuffer(encInIdx, 0, bufSize, rebasedPts, 0)
                                    } else {
                                        val dstBuf = encoder.getInputBuffer(encInIdx)
                                            ?: error("SW encoder returned null input buffer.")
                                        val written = copyCroppedYuvImageToBuffer(
                                            src = srcImage,
                                            dst = dstBuf,
                                            dstColorFormat = configuredEncoder.colorFormat,
                                            cropDispX = cropDispX,
                                            cropDispY = cropDispY,
                                            outW = targetWidth,
                                            outH = targetHeight,
                                            rotateDeg = effectiveRotateDeg
                                        )
                                        encoder.queueInputBuffer(encInIdx, 0, written, rebasedPts, 0)
                                    }
                                }
                            } else {
                                val rawOutBuf = decoder.getOutputBuffer(outIdx)
                                    ?: error("8K Software Decoder produced neither Image nor ByteBuffer.")
                                val rawDecFmt = decoder.outputFormat
                                val dstBuf = encoder.getInputBuffer(encInIdx)
                                    ?: error("SW encoder returned null input buffer.")
                                val written = copyRawDecoderYuvBuffer(
                                    srcBuf = rawOutBuf,
                                    srcOffset = decInfo.offset,
                                    srcFmt = rawDecFmt,
                                    dstBuf = dstBuf,
                                    dstColorFormat = configuredEncoder.colorFormat,
                                    cropDispX = cropDispX,
                                    cropDispY = cropDispY,
                                    outW = targetWidth,
                                    outH = targetHeight,
                                    rotateDeg = effectiveRotateDeg
                                )
                                encoder.queueInputBuffer(encInIdx, 0, written, rebasedPts, 0)
                            }
                        }

                        decoder.releaseOutputBuffer(outIdx, false)
                        if (isEos) {
                            decoderDone = true
                            if (!encoderInputSignaledEos) {
                                val eosIdx = dequeueInputBufferBlocking(encoder)
                                encoder.queueInputBuffer(
                                    eosIdx,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                encoderInputSignaledEos = true
                            }
                        }
                    }
                }

                // 3. Drain SW Encoder -> MP4 Muxer
                var draining = true
                while (draining && !encoderDone) {
                    val encIdx = encoder.dequeueOutputBuffer(encInfo, if (decoderDone) CODEC_WAIT_US else 0L)
                    when {
                        encIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> draining = false
                        encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "Encoder format changed after MP4 muxer started." }
                            muxerVideoTrack = muxer.addTrack(encoder.outputFormat)
                            if (canPassthroughAudio && audioInputFormat != null) {
                                muxerAudioTrack = muxer.addTrack(audioInputFormat)
                            } else if (swAudioTranscodePlan != null) {
                                muxerAudioTrack = muxer.addTrack(swAudioTranscodePlan.muxerAudioFormat)
                            }
                            muxer.start()
                            muxerStarted = true
                        }
                        encIdx >= 0 -> {
                            val encBuf = encoder.getOutputBuffer(encIdx)
                            if (encBuf != null &&
                                (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                                encInfo.size > 0 &&
                                muxerStarted &&
                                muxerVideoTrack >= 0
                            ) {
                                val pts = if (encInfo.presentationTimeUs <= lastVideoPtsUs) {
                                    lastVideoPtsUs + 1L
                                } else {
                                    encInfo.presentationTimeUs
                                }
                                lastVideoPtsUs = pts
                                encInfo.presentationTimeUs = pts

                                encBuf.position(encInfo.offset)
                                encBuf.limit(encInfo.offset + encInfo.size)
                                muxer.writeSampleData(muxerVideoTrack, encBuf, encInfo)

                                val nowMs = System.currentTimeMillis()
                                val isEos = (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                                if (isEos || nowMs - lastProgressMs >= PROGRESS_UPDATE_INTERVAL_MS) {
                                    lastProgressMs = nowMs
                                    val ratio = (pts.toFloat() / durationUs.toFloat()).coerceIn(0f, 1f)
                                    val weight = if (audioTrackIndex >= 0) 0.88f else 0.96f
                                    onProgress(0.06f + ratio * weight, statusLabel)
                                }
                            }
                            encoder.releaseOutputBuffer(encIdx, false)
                            if ((encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                encoderDone = true
                            }
                        }
                    }
                }
            }

            // 4. Mux audio track (AAC passthrough OR SW transcode to AAC)
            if (muxerStarted && muxerAudioTrack >= 0 && audioTrackIndex >= 0 && audioInputFormat != null) {
                onProgress(0.95f, "Muxing audio track…")
                extractor.unselectTrack(videoTrackIndex)
                extractor.selectTrack(audioTrackIndex)
                extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                if (canPassthroughAudio) {
                    copyAudioTrackPassthrough(
                        extractor = extractor,
                        audioFormat = audioInputFormat,
                        muxer = muxer,
                        muxerAudioTrack = muxerAudioTrack,
                        trimStartUs = trimStartUs,
                        trimEndUs = trimEndUs,
                        maxVideoPtsUs = lastVideoPtsUs
                    )
                } else if (swAudioTranscodePlan != null) {
                    transcodeAudioToMp4(
                        extractor = extractor,
                        plan = swAudioTranscodePlan,
                        muxer = muxer,
                        muxerAudioTrack = muxerAudioTrack,
                        trimStartUs = trimStartUs,
                        trimEndUs = trimEndUs,
                        maxVideoPtsUs = lastVideoPtsUs
                    )
                }
            }

            if (muxerStarted) {
                muxer.stop()
                muxerStarted = false
            }
        } finally {
            runCatching { extractor.release() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private suspend fun dequeueInputBufferBlocking(codec: MediaCodec): Int {
        while (coroutineContext.isActive) {
            val idx = codec.dequeueInputBuffer(CODEC_WAIT_US)
            if (idx >= 0) return idx
        }
        throw InterruptedException("8K operation cancelled.")
    }

    private suspend fun tryLosslessHevcRemux(
        context: Context,
        sourceUri: Uri,
        media: ImportedMediaInfo,
        outFile: File,
        onProgress: (Float, String) -> Unit
    ): CropExportState? {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        return try {
            extractor.setDataSource(context, sourceUri, null)
            var videoIdx = -1
            var audioIdx = -1
            var videoFmt: MediaFormat? = null
            var audioFmt: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (videoIdx < 0 && m.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)) {
                    videoIdx = i
                    videoFmt = f
                } else if (audioIdx < 0 && m.startsWith("audio/")) {
                    audioIdx = i
                    audioFmt = f
                }
            }
            if (videoIdx < 0 || videoFmt == null) return null

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (media.rotationDegrees != 0) {
                runCatching { muxer.setOrientationHint(media.rotationDegrees) }
            }
            val muxerVideoTrack = muxer.addTrack(videoFmt)

            val audioMime = audioFmt?.getString(MediaFormat.KEY_MIME)
            val canPassthroughAudio = audioMime != null &&
                audioMime.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true)
            val audioTranscodePlan = if (!canPassthroughAudio && audioFmt != null && audioMime != null) {
                prepareAacAudioTranscodePlan(audioFmt, audioMime)
            } else {
                null
            }
            val muxerAudioTrack = when {
                canPassthroughAudio && audioFmt != null -> muxer.addTrack(audioFmt)
                audioTranscodePlan != null -> muxer.addTrack(audioTranscodePlan.muxerAudioFormat)
                else -> -1
            }

            muxer.start()
            muxerStarted = true

            extractor.selectTrack(videoIdx)
            val maxSize = (if (videoFmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                videoFmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                2 * 1024 * 1024
            }).coerceAtLeast(1024 * 1024)
            val buf = ByteBuffer.allocateDirect(maxSize)
            val info = MediaCodec.BufferInfo()
            var lastVideoPts = -1L
            val durationUs = (media.durationMs * 1_000L).coerceAtLeast(1L)
            var lastProgressMs = 0L

            while (coroutineContext.isActive) {
                val size = extractor.readSampleData(buf, 0)
                if (size < 0) break
                val rawPts = extractor.sampleTime.coerceAtLeast(0L)
                val pts = if (rawPts <= lastVideoPts) lastVideoPts + 1L else rawPts
                lastVideoPts = pts
                info.set(0, size, pts, extractor.sampleFlags)
                buf.position(0)
                buf.limit(size)
                muxer.writeSampleData(muxerVideoTrack, buf, info)

                val nowMs = System.currentTimeMillis()
                if (nowMs - lastProgressMs >= PROGRESS_UPDATE_INTERVAL_MS) {
                    lastProgressMs = nowMs
                    val ratio = (pts.toFloat() / durationUs.toFloat()).coerceIn(0f, 1f)
                    onProgress(0.08f + ratio * 0.80f, "Exporting lossless 8K H.265 stream…")
                }
                if (!extractor.advance()) break
            }

            if (muxerAudioTrack >= 0 && audioIdx >= 0 && audioFmt != null) {
                onProgress(0.92f, "Muxing audio track…")
                extractor.unselectTrack(videoIdx)
                extractor.selectTrack(audioIdx)
                extractor.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                if (canPassthroughAudio) {
                    copyAudioTrackPassthrough(extractor, audioFmt, muxer, muxerAudioTrack, 0L, Long.MAX_VALUE, lastVideoPts)
                } else if (audioTranscodePlan != null) {
                    transcodeAudioToMp4(extractor, audioTranscodePlan, muxer, muxerAudioTrack, 0L, Long.MAX_VALUE, lastVideoPts)
                }
            }

            muxer.stop()
            muxerStarted = false

            onProgress(0.98f, "Saving 8K H.265 video to gallery…")
            val galleryUri = exportVideoToMediaStore(context, outFile, VideoContainerFormat.MP4.mimeType)
            CropExportState.Success(
                outputPath = outFile.absolutePath,
                fileName = outFile.name,
                savedToGallery = galleryUri != null,
                mediaType = CropMediaType.VIDEO,
                galleryUriString = galleryUri
            )
        } catch (t: Throwable) {
            if (t is InterruptedException || t is kotlinx.coroutines.CancellationException) throw t
            null
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    private data class AudioTranscodePlan(
        val sourceFormat: MediaFormat,
        val decoderName: String,
        val encoderName: String,
        val muxerAudioFormat: MediaFormat
    )

    private fun prepareAacAudioTranscodePlan(
        sourceFormat: MediaFormat,
        sourceMime: String
    ): AudioTranscodePlan? {
        val decName = resolveSoftwareAudioDecoders(sourceMime).firstOrNull() ?: return null
        val encName = resolveSoftwareAacEncoders().firstOrNull() ?: return null

        val sampleRate = if (sourceFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            sourceFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else {
            48_000
        }
        val channels = if (sourceFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            sourceFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
        } else {
            2
        }

        var probeEnc: MediaCodec? = null
        return try {
            probeEnc = MediaCodec.createByCodecName(encName)
            val encFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 1) 128_000 else 192_000)
            }
            probeEnc.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            probeEnc.start()
            AudioTranscodePlan(
                sourceFormat = sourceFormat,
                decoderName = decName,
                encoderName = encName,
                muxerAudioFormat = encFmt
            )
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { probeEnc?.stop() }
            runCatching { probeEnc?.release() }
        }
    }

    private suspend fun copyAudioTrackPassthrough(
        extractor: MediaExtractor,
        audioFormat: MediaFormat,
        muxer: MediaMuxer,
        muxerAudioTrack: Int,
        trimStartUs: Long,
        trimEndUs: Long,
        maxVideoPtsUs: Long
    ) {
        val maxAudioSize = (if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else {
            256 * 1024
        }).coerceAtLeast(64 * 1024)
        val audioBuf = ByteBuffer.allocateDirect(maxAudioSize)
        val audioInfo = MediaCodec.BufferInfo()
        var lastAudioPts = -1L

        while (coroutineContext.isActive) {
            val size = extractor.readSampleData(audioBuf, 0)
            if (size < 0) break
            val rawPts = extractor.sampleTime.coerceAtLeast(0L)
            if (rawPts > trimEndUs) break
            if (rawPts < trimStartUs) {
                if (!extractor.advance()) break
                continue
            }
            val rebased = (rawPts - trimStartUs).coerceAtLeast(0L)
            if (maxVideoPtsUs > 0L && rebased > maxVideoPtsUs + 100_000L) break
            val pts = if (rebased <= lastAudioPts) lastAudioPts + 1L else rebased
            lastAudioPts = pts

            audioInfo.set(0, size, pts, extractor.sampleFlags)
            audioBuf.position(0)
            audioBuf.limit(size)
            muxer.writeSampleData(muxerAudioTrack, audioBuf, audioInfo)
            if (!extractor.advance()) break
        }
    }

    private suspend fun transcodeAudioToMp4(
        extractor: MediaExtractor,
        plan: AudioTranscodePlan,
        muxer: MediaMuxer,
        muxerAudioTrack: Int,
        trimStartUs: Long,
        trimEndUs: Long,
        maxVideoPtsUs: Long
    ) {
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        try {
            decoder = MediaCodec.createByCodecName(plan.decoderName)
            decoder.configure(plan.sourceFormat, null, null, 0)
            decoder.start()

            encoder = MediaCodec.createByCodecName(plan.encoderName)
            encoder.configure(plan.muxerAudioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var extractorDone = false
            var decoderDone = false
            var encoderDone = false
            var lastAudioPts = -1L

            while (!encoderDone && coroutineContext.isActive) {
                if (!extractorDone) {
                    val inIdx = decoder.dequeueInputBuffer(0L)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            val samplePts = extractor.sampleTime.coerceAtLeast(0L)
                            if (sampleSize < 0 || samplePts > trimEndUs) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                extractorDone = true
                            } else {
                                decoder.queueInputBuffer(inIdx, 0, sampleSize, samplePts, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                if (!decoderDone) {
                    val outIdx = decoder.dequeueOutputBuffer(decInfo, 5_000L)
                    if (outIdx >= 0) {
                        val isEos = (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val pts = decInfo.presentationTimeUs
                        val inRange = decInfo.size > 0 && pts >= trimStartUs && pts <= trimEndUs
                        if (inRange) {
                            val pcmBuf = decoder.getOutputBuffer(outIdx)
                            if (pcmBuf != null) {
                                val encInIdx = encoder.dequeueInputBuffer(CODEC_WAIT_US)
                                if (encInIdx >= 0) {
                                    val encInBuf = encoder.getInputBuffer(encInIdx)
                                    if (encInBuf != null) {
                                        pcmBuf.position(decInfo.offset)
                                        pcmBuf.limit(decInfo.offset + decInfo.size)
                                        val copySize = minOf(pcmBuf.remaining(), encInBuf.capacity())
                                        encInBuf.clear()
                                        val slice = pcmBuf.slice()
                                        slice.limit(copySize)
                                        encInBuf.put(slice)
                                        encoder.queueInputBuffer(
                                            encInIdx,
                                            0,
                                            copySize,
                                            (pts - trimStartUs).coerceAtLeast(0L),
                                            0
                                        )
                                    }
                                }
                            }
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        if (isEos) {
                            decoderDone = true
                            val encInIdx = dequeueInputBufferBlocking(encoder)
                            encoder.queueInputBuffer(encInIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        }
                    }
                }

                var draining = true
                while (draining && !encoderDone) {
                    val encIdx = encoder.dequeueOutputBuffer(encInfo, if (decoderDone) 5_000L else 0L)
                    when {
                        encIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> draining = false
                        encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        encIdx >= 0 -> {
                            val encBuf = encoder.getOutputBuffer(encIdx)
                            if (encBuf != null &&
                                (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                                encInfo.size > 0
                            ) {
                                val rawPts = encInfo.presentationTimeUs.coerceAtLeast(0L)
                                if (maxVideoPtsUs <= 0L || rawPts <= maxVideoPtsUs + 100_000L) {
                                    val pts = if (rawPts <= lastAudioPts) lastAudioPts + 1L else rawPts
                                    lastAudioPts = pts
                                    encInfo.presentationTimeUs = pts
                                    encBuf.position(encInfo.offset)
                                    encBuf.limit(encInfo.offset + encInfo.size)
                                    muxer.writeSampleData(muxerAudioTrack, encBuf, encInfo)
                                }
                            }
                            encoder.releaseOutputBuffer(encIdx, false)
                            if ((encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                encoderDone = true
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // Audio transcode failure shouldn't crash video-only export
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
        }
    }

    private fun estimateYuvBufferSize(width: Int, height: Int, is10Bit: Boolean): Int {
        val bytesPerSample = if (is10Bit) 2 else 1
        return width * height * 3 / 2 * bytesPerSample
    }

    private fun copyCroppedYuvImage(
        src: Image,
        dst: Image,
        cropDispX: Int,
        cropDispY: Int,
        outW: Int,
        outH: Int,
        rotateDeg: Int
    ) {
        val srcRect = src.cropRect
        val srcRawW = srcRect.width()
        val srcRawH = srcRect.height()

        val srcPlanes = src.planes
        val dstPlanes = dst.planes
        val isSrcP010 = src.format == ImageFormat.YCBCR_P010
        val isDstP010 = dst.format == ImageFormat.YCBCR_P010

        if (rotateDeg == 0 && !isSrcP010 && !isDstP010) {
            copyPlane8Bit(srcPlanes[0], dstPlanes[0], srcRect.left + cropDispX, srcRect.top + cropDispY, outW, outH)
            copyPlane8Bit(srcPlanes[1], dstPlanes[1], (srcRect.left + cropDispX) / 2, (srcRect.top + cropDispY) / 2, outW / 2, outH / 2)
            copyPlane8Bit(srcPlanes[2], dstPlanes[2], (srcRect.left + cropDispX) / 2, (srcRect.top + cropDispY) / 2, outW / 2, outH / 2)
            return
        }

        if (rotateDeg == 0 && isSrcP010 && isDstP010) {
            copyPlane16Bit(srcPlanes[0], dstPlanes[0], srcRect.left + cropDispX, srcRect.top + cropDispY, outW, outH)
            copyPlane16Bit(srcPlanes[1], dstPlanes[1], (srcRect.left + cropDispX) / 2, (srcRect.top + cropDispY) / 2, outW / 2, outH / 2)
            copyPlane16Bit(srcPlanes[2], dstPlanes[2], (srcRect.left + cropDispX) / 2, (srcRect.top + cropDispY) / 2, outW / 2, outH / 2)
            return
        }

        val srcYBuf = srcPlanes[0].buffer
        val srcUBuf = srcPlanes[1].buffer
        val srcVBuf = srcPlanes[2].buffer
        val srcYRowStride = srcPlanes[0].rowStride
        val srcYPixStride = srcPlanes[0].pixelStride
        val srcURowStride = srcPlanes[1].rowStride
        val srcUPixStride = srcPlanes[1].pixelStride
        val srcVRowStride = srcPlanes[2].rowStride
        val srcVPixStride = srcPlanes[2].pixelStride

        val dstYBuf = dstPlanes[0].buffer
        val dstUBuf = dstPlanes[1].buffer
        val dstVBuf = dstPlanes[2].buffer
        val dstYRowStride = dstPlanes[0].rowStride
        val dstYPixStride = dstPlanes[0].pixelStride
        val dstURowStride = dstPlanes[1].rowStride
        val dstUPixStride = dstPlanes[1].pixelStride
        val dstVRowStride = dstPlanes[2].rowStride
        val dstVPixStride = dstPlanes[2].pixelStride

        fun mapDisplayToRaw(dx: Int, dy: Int): Pair<Int, Int> {
            return when (rotateDeg) {
                90 -> dy to (srcRawH - 1 - dx).coerceIn(0, srcRawH - 1)
                180 -> (srcRawW - 1 - dx).coerceIn(0, srcRawW - 1) to (srcRawH - 1 - dy).coerceIn(0, srcRawH - 1)
                270 -> (srcRawW - 1 - dy).coerceIn(0, srcRawW - 1) to dx
                else -> dx.coerceIn(0, srcRawW - 1) to dy.coerceIn(0, srcRawH - 1)
            }
        }

        for (y in 0 until outH) {
            val dy = cropDispY + y
            for (x in 0 until outW) {
                val dx = cropDispX + x
                val (rx, ry) = mapDisplayToRaw(dx, dy)
                val sx = srcRect.left + rx
                val sy = srcRect.top + ry
                val yVal = if (isSrcP010) {
                    val idx = sy * srcYRowStride + sx * srcYPixStride
                    ((srcYBuf.get(idx + 1).toInt() and 0xFF) shl 8) or (srcYBuf.get(idx).toInt() and 0xFF)
                } else {
                    srcYBuf.get(sy * srcYRowStride + sx * srcYPixStride).toInt() and 0xFF
                }
                val dstIdx = y * dstYRowStride + x * dstYPixStride
                if (isDstP010) {
                    val v16 = if (isSrcP010) yVal else (yVal shl 8)
                    dstYBuf.put(dstIdx, (v16 and 0xFF).toByte())
                    dstYBuf.put(dstIdx + 1, ((v16 ushr 8) and 0xFF).toByte())
                } else {
                    val v8 = if (isSrcP010) (yVal ushr 8) else yVal
                    dstYBuf.put(dstIdx, v8.toByte())
                }
            }
        }

        val chromaW = outW / 2
        val chromaH = outH / 2
        for (cy in 0 until chromaH) {
            val dy = cropDispY + cy * 2
            for (cx in 0 until chromaW) {
                val dx = cropDispX + cx * 2
                val (rx, ry) = mapDisplayToRaw(dx, dy)
                val scx = (srcRect.left + rx) / 2
                val scy = (srcRect.top + ry) / 2

                val uVal = if (isSrcP010) {
                    val idx = scy * srcURowStride + scx * srcUPixStride
                    ((srcUBuf.get(idx + 1).toInt() and 0xFF) shl 8) or (srcUBuf.get(idx).toInt() and 0xFF)
                } else {
                    srcUBuf.get(scy * srcURowStride + scx * srcUPixStride).toInt() and 0xFF
                }
                val vVal = if (isSrcP010) {
                    val idx = scy * srcVRowStride + scx * srcVPixStride
                    ((srcVBuf.get(idx + 1).toInt() and 0xFF) shl 8) or (srcVBuf.get(idx).toInt() and 0xFF)
                } else {
                    srcVBuf.get(scy * srcVRowStride + scx * srcVPixStride).toInt() and 0xFF
                }

                val dstUIdx = cy * dstURowStride + cx * dstUPixStride
                val dstVIdx = cy * dstVRowStride + cx * dstVPixStride
                if (isDstP010) {
                    val u16 = if (isSrcP010) uVal else (uVal shl 8)
                    val v16 = if (isSrcP010) vVal else (vVal shl 8)
                    dstUBuf.put(dstUIdx, (u16 and 0xFF).toByte())
                    dstUBuf.put(dstUIdx + 1, ((u16 ushr 8) and 0xFF).toByte())
                    dstVBuf.put(dstVIdx, (v16 and 0xFF).toByte())
                    dstVBuf.put(dstVIdx + 1, ((v16 ushr 8) and 0xFF).toByte())
                } else {
                    val u8 = if (isSrcP010) (uVal ushr 8) else uVal
                    val v8 = if (isSrcP010) (vVal ushr 8) else vVal
                    dstUBuf.put(dstUIdx, u8.toByte())
                    dstVBuf.put(dstVIdx, v8.toByte())
                }
            }
        }
    }

    private fun copyPlane8Bit(
        srcPlane: Image.Plane,
        dstPlane: Image.Plane,
        srcStartX: Int,
        srcStartY: Int,
        width: Int,
        height: Int
    ) {
        val srcBuf = srcPlane.buffer
        val dstBuf = dstPlane.buffer
        val srcRowStride = srcPlane.rowStride
        val srcPixStride = srcPlane.pixelStride
        val dstRowStride = dstPlane.rowStride
        val dstPixStride = dstPlane.pixelStride

        if (srcPixStride == 1 && dstPixStride == 1) {
            val rowScratch = ByteArray(width)
            for (y in 0 until height) {
                val srcOffset = (srcStartY + y) * srcRowStride + srcStartX
                srcBuf.position(srcOffset)
                srcBuf.get(rowScratch, 0, width)
                dstBuf.position(y * dstRowStride)
                dstBuf.put(rowScratch, 0, width)
            }
        } else {
            for (y in 0 until height) {
                var sIdx = (srcStartY + y) * srcRowStride + srcStartX * srcPixStride
                var dIdx = y * dstRowStride
                for (x in 0 until width) {
                    dstBuf.put(dIdx, srcBuf.get(sIdx))
                    sIdx += srcPixStride
                    dIdx += dstPixStride
                }
            }
        }
    }

    private fun copyPlane16Bit(
        srcPlane: Image.Plane,
        dstPlane: Image.Plane,
        srcStartX: Int,
        srcStartY: Int,
        width: Int,
        height: Int
    ) {
        val srcBuf = srcPlane.buffer
        val dstBuf = dstPlane.buffer
        val srcRowStride = srcPlane.rowStride
        val srcPixStride = srcPlane.pixelStride
        val dstRowStride = dstPlane.rowStride
        val dstPixStride = dstPlane.pixelStride

        if (srcPixStride == 2 && dstPixStride == 2) {
            val rowBytes = width * 2
            val rowScratch = ByteArray(rowBytes)
            for (y in 0 until height) {
                val srcOffset = (srcStartY + y) * srcRowStride + srcStartX * 2
                srcBuf.position(srcOffset)
                srcBuf.get(rowScratch, 0, rowBytes)
                dstBuf.position(y * dstRowStride)
                dstBuf.put(rowScratch, 0, rowBytes)
            }
        } else {
            for (y in 0 until height) {
                var sIdx = (srcStartY + y) * srcRowStride + srcStartX * srcPixStride
                var dIdx = y * dstRowStride
                for (x in 0 until width) {
                    dstBuf.put(dIdx, srcBuf.get(sIdx))
                    dstBuf.put(dIdx + 1, srcBuf.get(sIdx + 1))
                    sIdx += srcPixStride
                    dIdx += dstPixStride
                }
            }
        }
    }

    private fun copyCroppedYuvImageToBuffer(
        src: Image,
        dst: ByteBuffer,
        dstColorFormat: Int,
        cropDispX: Int,
        cropDispY: Int,
        outW: Int,
        outH: Int,
        rotateDeg: Int
    ): Int {
        val srcRect = src.cropRect
        val isSrcP010 = src.format == ImageFormat.YCBCR_P010
        val srcPlanes = src.planes
        val yPlane = srcPlanes[0]
        val uPlane = srcPlanes[1]
        val vPlane = srcPlanes[2]

        dst.clear()
        val ySize = outW * outH
        val cSize = (outW / 2) * (outH / 2)
        val isSemiPlanar = dstColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar

        if (rotateDeg == 0 && !isSrcP010) {
            val yRowScratch = ByteArray(outW)
            for (y in 0 until outH) {
                val off = (srcRect.top + cropDispY + y) * yPlane.rowStride + (srcRect.left + cropDispX) * yPlane.pixelStride
                if (yPlane.pixelStride == 1) {
                    yPlane.buffer.position(off)
                    yPlane.buffer.get(yRowScratch, 0, outW)
                    dst.put(yRowScratch)
                } else {
                    for (x in 0 until outW) {
                        dst.put(yPlane.buffer.get(off + x * yPlane.pixelStride))
                    }
                }
            }
            val cw = outW / 2
            val ch = outH / 2
            if (isSemiPlanar) {
                for (cy in 0 until ch) {
                    val uOff = (srcRect.top / 2 + cropDispY / 2 + cy) * uPlane.rowStride + (srcRect.left / 2 + cropDispX / 2) * uPlane.pixelStride
                    val vOff = (srcRect.top / 2 + cropDispY / 2 + cy) * vPlane.rowStride + (srcRect.left / 2 + cropDispX / 2) * vPlane.pixelStride
                    for (cx in 0 until cw) {
                        dst.put(uPlane.buffer.get(uOff + cx * uPlane.pixelStride))
                        dst.put(vPlane.buffer.get(vOff + cx * vPlane.pixelStride))
                    }
                }
            } else {
                val cRow = ByteArray(cw)
                for (cy in 0 until ch) {
                    val uOff = (srcRect.top / 2 + cropDispY / 2 + cy) * uPlane.rowStride + (srcRect.left / 2 + cropDispX / 2) * uPlane.pixelStride
                    if (uPlane.pixelStride == 1) {
                        uPlane.buffer.position(uOff)
                        uPlane.buffer.get(cRow, 0, cw)
                        dst.put(cRow)
                    } else {
                        for (cx in 0 until cw) {
                            dst.put(uPlane.buffer.get(uOff + cx * uPlane.pixelStride))
                        }
                    }
                }
                for (cy in 0 until ch) {
                    val vOff = (srcRect.top / 2 + cropDispY / 2 + cy) * vPlane.rowStride + (srcRect.left / 2 + cropDispX / 2) * vPlane.pixelStride
                    if (vPlane.pixelStride == 1) {
                        vPlane.buffer.position(vOff)
                        vPlane.buffer.get(cRow, 0, cw)
                        dst.put(cRow)
                    } else {
                        for (cx in 0 until cw) {
                            dst.put(vPlane.buffer.get(vOff + cx * vPlane.pixelStride))
                        }
                    }
                }
            }
            return ySize + cSize * 2
        }

        val srcRawW = srcRect.width()
        val srcRawH = srcRect.height()
        fun map(dx: Int, dy: Int): Pair<Int, Int> = when (rotateDeg) {
            90 -> dy to (srcRawH - 1 - dx).coerceIn(0, srcRawH - 1)
            180 -> (srcRawW - 1 - dx).coerceIn(0, srcRawW - 1) to (srcRawH - 1 - dy).coerceIn(0, srcRawH - 1)
            270 -> (srcRawW - 1 - dy).coerceIn(0, srcRawW - 1) to dx
            else -> dx.coerceIn(0, srcRawW - 1) to dy.coerceIn(0, srcRawH - 1)
        }
        fun readSample8(plane: Image.Plane, x: Int, y: Int): Byte {
            val idx = y * plane.rowStride + x * plane.pixelStride
            return if (isSrcP010) plane.buffer.get(idx + 1) else plane.buffer.get(idx)
        }
        for (y in 0 until outH) {
            for (x in 0 until outW) {
                val (rx, ry) = map(cropDispX + x, cropDispY + y)
                dst.put(readSample8(yPlane, srcRect.left + rx, srcRect.top + ry))
            }
        }
        val cw = outW / 2
        val ch = outH / 2
        if (isSemiPlanar) {
            for (cy in 0 until ch) {
                for (cx in 0 until cw) {
                    val (rx, ry) = map(cropDispX + cx * 2, cropDispY + cy * 2)
                    val sx = (srcRect.left + rx) / 2
                    val sy = (srcRect.top + ry) / 2
                    dst.put(readSample8(uPlane, sx, sy))
                    dst.put(readSample8(vPlane, sx, sy))
                }
            }
        } else {
            for (cy in 0 until ch) {
                for (cx in 0 until cw) {
                    val (rx, ry) = map(cropDispX + cx * 2, cropDispY + cy * 2)
                    dst.put(readSample8(uPlane, (srcRect.left + rx) / 2, (srcRect.top + ry) / 2))
                }
            }
            for (cy in 0 until ch) {
                for (cx in 0 until cw) {
                    val (rx, ry) = map(cropDispX + cx * 2, cropDispY + cy * 2)
                    dst.put(readSample8(vPlane, (srcRect.left + rx) / 2, (srcRect.top + ry) / 2))
                }
            }
        }
        return ySize + cSize * 2
    }

    private fun copyRawDecoderYuvBuffer(
        srcBuf: ByteBuffer,
        srcOffset: Int,
        srcFmt: MediaFormat,
        dstBuf: ByteBuffer,
        dstColorFormat: Int,
        cropDispX: Int,
        cropDispY: Int,
        outW: Int,
        outH: Int,
        rotateDeg: Int
    ): Int {
        val rawW = if (srcFmt.containsKey(MediaFormat.KEY_WIDTH)) srcFmt.getInteger(MediaFormat.KEY_WIDTH) else WIDTH_8K
        val rawH = if (srcFmt.containsKey(MediaFormat.KEY_HEIGHT)) srcFmt.getInteger(MediaFormat.KEY_HEIGHT) else HEIGHT_8K
        val stride = if (srcFmt.containsKey(MediaFormat.KEY_STRIDE)) srcFmt.getInteger(MediaFormat.KEY_STRIDE) else rawW
        val sliceH = if (srcFmt.containsKey(MediaFormat.KEY_SLICE_HEIGHT)) srcFmt.getInteger(MediaFormat.KEY_SLICE_HEIGHT) else rawH
        val srcCf = if (srcFmt.containsKey(MediaFormat.KEY_COLOR_FORMAT)) srcFmt.getInteger(MediaFormat.KEY_COLOR_FORMAT) else 0
        val srcSemiPlanar = srcCf == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar ||
            srcCf == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar
        val dstSemiPlanar = dstColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar

        val uBase = srcOffset + stride * sliceH
        val vBase = uBase + (stride / 2) * (sliceH / 2)

        fun map(dx: Int, dy: Int): Pair<Int, Int> = when (rotateDeg) {
            90 -> dy to (rawH - 1 - dx).coerceIn(0, rawH - 1)
            180 -> (rawW - 1 - dx).coerceIn(0, rawW - 1) to (rawH - 1 - dy).coerceIn(0, rawH - 1)
            270 -> (rawW - 1 - dy).coerceIn(0, rawW - 1) to dx
            else -> dx.coerceIn(0, rawW - 1) to dy.coerceIn(0, rawH - 1)
        }

        dstBuf.clear()
        for (y in 0 until outH) {
            for (x in 0 until outW) {
                val (rx, ry) = map(cropDispX + x, cropDispY + y)
                dstBuf.put(srcBuf.get(srcOffset + ry * stride + rx))
            }
        }
        val cw = outW / 2
        val ch = outH / 2
        val cStride = if (srcSemiPlanar) stride else stride / 2
        if (dstSemiPlanar) {
            for (cy in 0 until ch) {
                for (cx in 0 until cw) {
                    val (rx, ry) = map(cropDispX + cx * 2, cropDispY + cy * 2)
                    val sx = rx / 2
                    val sy = ry / 2
                    if (srcSemiPlanar) {
                        val idx = uBase + sy * cStride + sx * 2
                        dstBuf.put(srcBuf.get(idx))
                        dstBuf.put(srcBuf.get(idx + 1))
                    } else {
                        dstBuf.put(srcBuf.get(uBase + sy * cStride + sx))
                        dstBuf.put(srcBuf.get(vBase + sy * cStride + sx))
                    }
                }
            }
        } else {
            for (cy in 0 until ch) {
                for (cx in 0 until cw) {
                    val (rx, ry) = map(cropDispX + cx * 2, cropDispY + cy * 2)
                    val sx = rx / 2
                    val sy = ry / 2
                    val b = if (srcSemiPlanar) srcBuf.get(uBase + sy * cStride + sx * 2) else srcBuf.get(uBase + sy * cStride + sx)
                    dstBuf.put(b)
                }
            }
            for (cy in 0 until ch) {
                for (cx in 0 until cw) {
                    val (rx, ry) = map(cropDispX + cx * 2, cropDispY + cy * 2)
                    val sx = rx / 2
                    val sy = ry / 2
                    val b = if (srcSemiPlanar) srcBuf.get(uBase + sy * cStride + sx * 2 + 1) else srcBuf.get(vBase + sy * cStride + sx)
                    dstBuf.put(b)
                }
            }
        }
        return outW * outH * 3 / 2
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
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out, bufferSize = 512 * 1024) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            uri.toString()
        } catch (_: Throwable) {
            resolver.delete(uri, null, null)
            null
        }
    }
}
