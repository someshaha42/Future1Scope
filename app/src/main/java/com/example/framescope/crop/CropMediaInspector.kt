package com.example.framescope.crop

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.example.framescope.capability.DeviceCapabilityEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

class UnsupportedHardwareResolutionException(
    val resolutionTier: String,
    val width: Int,
    val height: Int,
    val popupMessage: String
) : IllegalStateException(popupMessage)

object CropMediaInspector {

    private val cachedCodecInfos: Array<MediaCodecInfo> by lazy {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
    }

    private val IMAGE_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "webp", "heic", "heif", "avif", "bmp", "gif", "wbmp", "dng", "tiff", "tif"
    )
    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "m4v", "mov", "mkv", "webm", "3gp", "3gpp", "avi", "ts", "mts", "flv"
    )

    suspend fun inspect(context: Context, uri: Uri): Result<ImportedMediaInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val rawMime = resolver.getType(uri)?.lowercase(Locale.US).orEmpty()
            val displayName = queryDisplayName(context, uri)
            val ext = displayName.substringAfterLast('.', "")
                .lowercase(Locale.US)
                .ifBlank { mimeToDefaultExtension(rawMime) }

            val guessedVideo = rawMime.startsWith("video/") || ext in VIDEO_EXTENSIONS
            val guessedImage = rawMime.startsWith("image/") || ext in IMAGE_EXTENSIONS

            when {
                guessedVideo -> inspectVideo(context, uri, displayName, rawMime, ext)
                guessedImage -> inspectImage(context, uri, displayName, rawMime, ext)
                else -> {
                    // Unknown MIME/extension: probe image first, then video
                    runCatching { inspectImage(context, uri, displayName, rawMime, ext) }
                        .getOrElse { inspectVideo(context, uri, displayName, rawMime, ext) }
                }
            }
        }
    }

    private fun inspectImage(
        context: Context,
        uri: Uri,
        displayName: String,
        rawMime: String,
        ext: String
    ): ImportedMediaInfo {
        val resolver = context.contentResolver
        var width = 0
        var height = 0
        var hasAlpha = false
        var detectedMime = rawMime

        // Fast header-only bounds probe first (avoids full-image pixel decode during inspection)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            resolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, opts)
            }
        }
        if (opts.outWidth > 0 && opts.outHeight > 0) {
            val orientation = runCatching {
                resolver.openInputStream(uri)?.use { input ->
                    ExifInterface(input).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                }
            }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
            val swapsAxes = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
                orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                orientation == ExifInterface.ORIENTATION_TRANSVERSE
            width = if (swapsAxes) opts.outHeight else opts.outWidth
            height = if (swapsAxes) opts.outWidth else opts.outHeight
            if (!opts.outMimeType.isNullOrBlank()) {
                detectedMime = opts.outMimeType
            }
        }

        // Fallback to ImageDecoder header probe for formats BitmapFactory bounds didn't recognize
        if (width <= 0 || height <= 0) {
            runCatching {
                val source = ImageDecoder.createSource(resolver, uri)
                ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                    width = info.size.width
                    height = info.size.height
                    detectedMime = info.mimeType.ifBlank { detectedMime }
                    decoder.setTargetSize(1, 1)
                }
            }
        }

        // Quick alpha check on PNG/WEBP/GIF/HEIC
        if (detectedMime.contains("png") ||
            detectedMime.contains("webp") ||
            detectedMime.contains("gif") ||
            ext in setOf("png", "webp", "gif", "heic", "heif", "avif")
        ) {
            hasAlpha = true
        }

        check(width > 0 && height > 0) {
            "Unable to decode image dimensions or unsupported image format."
        }

        val resolvedExt = ext.ifBlank { mimeToDefaultExtension(detectedMime) }.ifBlank { "png" }
        val exportOptions = buildImageExportOptions(resolvedExt, detectedMime, hasAlpha)

        return ImportedMediaInfo(
            uri = uri,
            displayName = displayName,
            mimeType = detectedMime.ifBlank { "image/$resolvedExt" },
            extension = resolvedExt,
            mediaType = CropMediaType.IMAGE,
            rawWidth = width,
            rawHeight = height,
            rotationDegrees = 0,
            displayWidth = width,
            displayHeight = height,
            hasAlpha = hasAlpha,
            imageExportOptions = exportOptions
        )
    }

    private fun inspectVideo(
        context: Context,
        uri: Uri,
        displayName: String,
        rawMime: String,
        ext: String
    ): ImportedMediaInfo {
        val extractor = MediaExtractor()
        var retriever: MediaMetadataRetriever? = null

        var rawW = 0
        var rawH = 0
        var rotation = 0
        var durationMs = 0L
        var fps = 0
        var colorStandard: Int? = null
        var colorRange: Int? = null
        var colorTransfer: Int? = null
        var originalBitrate: Long? = null
        var videoMime: String? = null

        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    videoMime = mime
                    if (format.containsKey(MediaFormat.KEY_WIDTH)) {
                        rawW = format.getInteger(MediaFormat.KEY_WIDTH)
                    }
                    if (format.containsKey(MediaFormat.KEY_HEIGHT)) {
                        rawH = format.getInteger(MediaFormat.KEY_HEIGHT)
                    }
                    if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                        rotation = format.getInteger(MediaFormat.KEY_ROTATION)
                    }
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1_000L
                    }
                    if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        val fVal = runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE) }
                            .getOrElse {
                                runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrDefault(0f)
                            }
                        if (fVal > 0f) {
                            fps = fVal.roundToInt()
                        }
                    }
                    if (format.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
                        colorStandard = runCatching { format.getInteger(MediaFormat.KEY_COLOR_STANDARD) }.getOrNull()
                    }
                    if (format.containsKey(MediaFormat.KEY_COLOR_RANGE)) {
                        colorRange = runCatching { format.getInteger(MediaFormat.KEY_COLOR_RANGE) }.getOrNull()
                    }
                    if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                        colorTransfer = runCatching { format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) }.getOrNull()
                    }
                    if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        val trackBitrate = format.getInteger(MediaFormat.KEY_BIT_RATE).toLong()
                        if (trackBitrate > 0L) {
                            originalBitrate = trackBitrate
                        }
                    }
                    break
                }
            }

            if (rawW <= 0 || rawH <= 0 || durationMs <= 0L || originalBitrate == null) {
                runCatching {
                    val mmr = MediaMetadataRetriever().also { retriever = it }
                    mmr.setDataSource(context, uri)
                    if (rawW <= 0) {
                        rawW = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                            ?.toIntOrNull() ?: 0
                    }
                    if (rawH <= 0) {
                        rawH = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                            ?.toIntOrNull() ?: 0
                    }
                    if (rotation == 0) {
                        rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                            ?.toIntOrNull() ?: 0
                    }
                    if (durationMs <= 0L) {
                        durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull() ?: 0L
                    }
                    if (originalBitrate == null) {
                        val retrieverBitrate = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                            ?.toLongOrNull()
                        if (retrieverBitrate != null && retrieverBitrate > 0L) {
                            originalBitrate = retrieverBitrate
                        }
                    }
                }
            }
        } finally {
            runCatching { retriever?.release() }
            runCatching { extractor.release() }
        }

        check(videoMime != null && rawW > 0 && rawH > 0) {
            "No decodable video track found or unsupported video format."
        }

        val normalizedRot = ((rotation % 360) + 360) % 360
        val (dispW, dispH) = if (normalizedRot == 90 || normalizedRot == 270) {
            rawH to rawW
        } else {
            rawW to rawH
        }

        // Strict processor hardware decoder & encoder capability check (software codecs ignored)
        validateHardwareResolutionSupport(
            videoMime = videoMime!!,
            rawW = rawW,
            rawH = rawH,
            dispW = dispW,
            dispH = dispH
        )

        val resolvedExt = ext.ifBlank { mimeToDefaultExtension(rawMime) }.ifBlank { "mp4" }
        val videoExportOptions = buildVideoExportOptions(resolvedExt, rawMime)

        return ImportedMediaInfo(
            uri = uri,
            displayName = displayName,
            mimeType = rawMime.ifBlank { videoMime!! },
            extension = resolvedExt,
            mediaType = CropMediaType.VIDEO,
            rawWidth = rawW,
            rawHeight = rawH,
            rotationDegrees = normalizedRot,
            displayWidth = dispW,
            displayHeight = dispH,
            durationMs = durationMs.coerceAtLeast(0L),
            fps = if (fps > 0) fps.coerceIn(15, 120) else 30,
            originalBitrateBps = originalBitrate,
            colorStandard = colorStandard,
            colorRange = colorRange,
            colorTransfer = colorTransfer,
            sourceVideoMime = videoMime,
            videoExportOptions = videoExportOptions
        )
    }

    private fun buildImageExportOptions(
        originalExt: String,
        originalMime: String,
        sourceHasAlpha: Boolean
    ): List<ImageExportFormatOption> {
        val upperExt = originalExt.uppercase(Locale.US)
        val (origMime, origSupportsAlpha) = when (originalExt.lowercase(Locale.US)) {
            "png" -> "image/png" to true
            "jpg", "jpeg" -> "image/jpeg" to false
            "webp" -> "image/webp" to true
            else -> originalMime.ifBlank { "image/png" } to sourceHasAlpha
        }
        val rawOptions = listOf(
            ImageExportFormatOption(
                id = ImageExportFormatId.PNG,
                label = "PNG",
                extension = "png",
                mimeType = "image/png",
                supportsTransparency = true
            ),
            ImageExportFormatOption(
                id = ImageExportFormatId.JPG,
                label = "JPG",
                extension = "jpg",
                mimeType = "image/jpeg",
                supportsTransparency = false
            ),
            ImageExportFormatOption(
                id = ImageExportFormatId.ORIGINAL,
                label = "Original ($upperExt)",
                extension = originalExt.lowercase(Locale.US),
                mimeType = origMime,
                supportsTransparency = origSupportsAlpha
            )
        )
        return rawOptions.map { opt ->
            opt.copy(
                supportInfo = DeviceCapabilityEngine.probeImageExportSupport(
                    option = opt,
                    sourceHasAlpha = sourceHasAlpha
                )
            )
        }
    }

    private fun buildVideoExportOptions(
        originalExt: String,
        originalMime: String
    ): List<VideoExportFormatOption> {
        val codecInfos = cachedCodecInfos
        val canExportMp4 = hasSurfaceEncoderForAny(
            codecInfos,
            listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)
        )

        val fallbackLabel = if (canExportMp4) {
            "MP4 (H.264)"
        } else {
            "MP4 Software Safe"
        }

        val isOriginalMp4 = originalExt.equals("mp4", ignoreCase = true) ||
            originalExt.equals("m4v", ignoreCase = true) ||
            originalMime.equals("video/mp4", ignoreCase = true)

        val mp4Opt = VideoExportFormatOption(
            label = "MP4",
            container = VideoContainerFormat.MP4,
            isOriginal = isOriginalMp4,
            supportInfo = DeviceCapabilityEngine.probeVideoExportSupport(
                container = VideoContainerFormat.MP4,
                fallbackLabel = fallbackLabel
            )
        )

        return listOf(mp4Opt)
    }

    private fun validateHardwareResolutionSupport(
        videoMime: String,
        rawW: Int,
        rawH: Int,
        dispW: Int,
        dispH: Int
    ) {
        val codecInfos = cachedCodecInfos
        val decoderMimes = buildList {
            add(videoMime)
            if (videoMime.equals("video/dolby-vision", ignoreCase = true)) {
                add(MediaFormat.MIMETYPE_VIDEO_HEVC)
            }
        }

        // Only inspect genuine processor hardware decoders (MediaTek / Qualcomm / Exynos / Tensor / etc.)
        val hwDecoderPairs = mutableListOf<Pair<MediaCodecInfo, MediaCodecInfo.VideoCapabilities>>()
        for (mime in decoderMimes) {
            for (info in codecInfos) {
                if (info.isAlias || info.isEncoder || !info.isHardwareAccelerated || info.isSoftwareOnly) continue
                val matchedType = info.supportedTypes.firstOrNull { it.equals(mime, ignoreCase = true) } ?: continue
                val vCaps = runCatching { info.getCapabilitiesForType(matchedType).videoCapabilities }.getOrNull() ?: continue
                hwDecoderPairs += info to vCaps
            }
        }

        // Only inspect genuine processor hardware encoders capable of Surface-input MP4 export
        val encoderMimes = listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)
        val hwEncoderPairs = mutableListOf<Pair<MediaCodecInfo, MediaCodecInfo.VideoCapabilities>>()
        for (mime in encoderMimes) {
            for (info in codecInfos) {
                if (info.isAlias || !info.isEncoder || !info.isHardwareAccelerated || info.isSoftwareOnly) continue
                val matchedType = info.supportedTypes.firstOrNull { it.equals(mime, ignoreCase = true) } ?: continue
                val caps = runCatching { info.getCapabilitiesForType(matchedType) }.getOrNull() ?: continue
                val supportsSurface = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in caps.colorFormats
                val vCaps = caps.videoCapabilities ?: continue
                if (supportsSurface) {
                    hwEncoderPairs += info to vCaps
                }
            }
        }

        val hwDecoderSupportsSize = hwDecoderPairs.any { (_, caps) ->
            isSizeSupportedInAnyOrientation(caps, rawW, rawH)
        }
        val hwEncoderSupportsSize = hwEncoderPairs.any { (_, caps) ->
            isSizeSupportedInAnyOrientation(caps, rawW, rawH)
        }

        if (!hwDecoderSupportsSize || !hwEncoderSupportsSize) {
            val tierName = classifyResolutionTierName(dispW, dispH)
            val hwDecMax = describeHardwareMaxResolution(hwDecoderPairs.map { it.second })
            val hwEncMax = describeHardwareMaxResolution(hwEncoderPairs.map { it.second })
            val reasonLine = when {
                hwDecoderPairs.isEmpty() && hwEncoderPairs.isEmpty() ->
                    "No hardware decoder or hardware encoder was detected for this processor."
                !hwDecoderSupportsSize && !hwEncoderSupportsSize ->
                    "Exceeds both processor hardware decoder max ($hwDecMax) and hardware encoder max ($hwEncMax)."
                !hwEncoderSupportsSize ->
                    "Exceeds processor hardware encoder max ($hwEncMax)."
                else ->
                    "Exceeds processor hardware decoder max ($hwDecMax)."
            }
            val popupMessage =
                "This resolution is $tierName (${dispW}×${dispH}) — not supported your device.\n\n" +
                    "$reasonLine\n" +
                    "Hardware Decoder Max: $hwDecMax\n" +
                    "Hardware Encoder Max: $hwEncMax"
            throw UnsupportedHardwareResolutionException(
                resolutionTier = tierName,
                width = dispW,
                height = dispH,
                popupMessage = popupMessage
            )
        }
    }

    private fun isSizeSupportedInAnyOrientation(
        caps: MediaCodecInfo.VideoCapabilities,
        width: Int,
        height: Int
    ): Boolean {
        if (width <= 0 || height <= 0) return false
        return runCatching {
            caps.isSizeSupported(width, height) || caps.isSizeSupported(height, width)
        }.getOrDefault(false)
    }

    private fun classifyResolutionTierName(w: Int, h: Int): String {
        val longSide = maxOf(w, h)
        val shortSide = minOf(w, h)
        return when {
            longSide >= 7600 || shortSide >= 4300 -> "8K"
            longSide >= 5700 || shortSide >= 3200 -> "6K"
            longSide >= 5000 || shortSide >= 2800 -> "5K"
            longSide >= 3800 || shortSide >= 2100 -> "4K"
            longSide >= 2500 || shortSide >= 1400 -> "2K"
            longSide >= 1900 || shortSide >= 1070 -> "1080p"
            longSide >= 1270 || shortSide >= 710 -> "720p"
            longSide >= 850 || shortSide >= 470 -> "480p"
            else -> "${w}×${h}"
        }
    }

    private fun describeHardwareMaxResolution(capsList: List<MediaCodecInfo.VideoCapabilities>): String {
        if (capsList.isEmpty()) return "Unavailable"
        val standardTiers = listOf(
            Triple("8K", 7680, 4320),
            Triple("6K", 5760, 3240),
            Triple("5K", 5120, 2880),
            Triple("4K", 3840, 2160),
            Triple("2K", 2560, 1440),
            Triple("1080p", 1920, 1080),
            Triple("720p", 1280, 720),
            Triple("480p", 854, 480)
        )
        for ((tier, tw, th) in standardTiers) {
            if (capsList.any { isSizeSupportedInAnyOrientation(it, tw, th) }) {
                return "$tier (${tw}×${th})"
            }
        }
        val best = capsList.maxByOrNull {
            it.supportedWidths.upper.toLong() * it.supportedHeights.upper.toLong()
        } ?: return "Unavailable"
        val maxW = best.supportedWidths.upper
        val maxH = runCatching { best.getSupportedHeightsFor(maxW).upper }.getOrDefault(best.supportedHeights.upper)
        return "${maxW}×${maxH}"
    }

    private fun hasSurfaceEncoderForAny(codecInfos: Array<MediaCodecInfo>, mimes: List<String>): Boolean {
        return codecInfos.any { info ->
            !info.isAlias && info.isEncoder && info.supportedTypes.any { type ->
                mimes.any { target ->
                    type.equals(target, ignoreCase = true) &&
                        runCatching {
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in
                                info.getCapabilitiesForType(type).colorFormats
                        }.getOrDefault(false)
                }
            }
        }
    }

    fun inspectVideoPipelineSupport(
        sourceVideoMime: String?,
        container: VideoContainerFormat?
    ): CodecPipelineSupport {
        val codecInfos = cachedCodecInfos

        val decoderInfo = if (!sourceVideoMime.isNullOrBlank()) {
            val decoders = codecInfos.filter { info ->
                !info.isAlias && !info.isEncoder && info.supportedTypes.any { it.equals(sourceVideoMime, ignoreCase = true) }
            }
            decoders.firstOrNull { it.isHardwareAccelerated } ?: decoders.firstOrNull()
        } else {
            null
        }

        val targetContainer = container ?: VideoContainerFormat.MP4
        val encoderMimes = when (targetContainer) {
            VideoContainerFormat.MP4 -> {
                if (sourceVideoMime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)) {
                    listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)
                } else {
                    listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)
                }
            }
            VideoContainerFormat.WEBM -> listOf(MediaFormat.MIMETYPE_VIDEO_VP9, MediaFormat.MIMETYPE_VIDEO_VP8)
            VideoContainerFormat.THREE_GP -> listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_H263)
        }

        val matchingEncoders = encoderMimes.flatMap { mime ->
            codecInfos.filter { info ->
                !info.isAlias &&
                    info.isEncoder &&
                    info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                    runCatching {
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in
                            info.getCapabilitiesForType(mime).colorFormats
                    }.getOrDefault(false)
            }
        }
        val encoderInfo = matchingEncoders.firstOrNull { it.isHardwareAccelerated }
            ?: matchingEncoders.firstOrNull()

        val hwDecoder = decoderInfo?.isHardwareAccelerated == true
        val hwEncoder = encoderInfo?.isHardwareAccelerated == true

        val statusText = when {
            hwDecoder && hwEncoder -> "✓ Hardware Decoder & Hardware Encoder"
            !hwDecoder && !hwEncoder -> "✓ Software Decoder & Software Encoder"
            hwDecoder -> "✓ Hardware Decoder • Software Encoder"
            else -> "✓ Software Decoder • Hardware Encoder"
        }

        return CodecPipelineSupport(
            isHardwareDecoder = hwDecoder,
            isHardwareEncoder = hwEncoder,
            decoderName = decoderInfo?.name,
            encoderName = encoderInfo?.name,
            statusText = statusText
        )
    }

    fun calculateRecommendedBitrateMbps(
        outputWidth: Int,
        outputHeight: Int,
        fps: Int,
        sourceVideoMime: String? = null
    ): Float {
        val w = outputWidth.coerceAtLeast(16)
        val h = outputHeight.coerceAtLeast(16)
        val pixels = w.toDouble() * h.toDouble()
        val safeFps = fps.coerceIn(15, 120).toDouble()
        val isHevcOrAv1 = sourceVideoMime?.contains("hevc", ignoreCase = true) == true ||
            sourceVideoMime?.contains("av01", ignoreCase = true) == true ||
            sourceVideoMime?.contains("vp9", ignoreCase = true) == true

        // Bits-per-pixel complexity factor calibrated for crisp mobile/desktop playback
        val bpp = if (isHevcOrAv1) 0.14 else 0.21
        val fpsScale = (safeFps / 30.0).pow(0.75) * 30.0
        val rawBps = pixels * fpsScale * bpp
        val rawMbps = (rawBps / 1_000_000.0).toFloat()
        val stepped = (rawMbps * 2f).roundToInt() / 2f
        return stepped.coerceIn(1.0f, 50.0f)
    }

    private fun queryDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val name = cursor.getString(idx)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "media_item" } ?: "media_item"
    }

    private fun mimeToDefaultExtension(mime: String): String {
        return when (mime.lowercase(Locale.US)) {
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            "image/heic" -> "heic"
            "image/heif" -> "heif"
            "image/avif" -> "avif"
            "image/gif" -> "gif"
            "image/bmp" -> "bmp"
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "video/3gpp" -> "3gp"
            "video/quicktime" -> "mov"
            "video/x-matroska" -> "mkv"
            else -> ""
        }
    }
}
