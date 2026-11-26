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
                        fps = runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE) }
                            .getOrElse {
                                runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE).roundToInt() }.getOrDefault(0)
                            }
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
        check(hasCompatibleDecoder(videoMime!!)) {
            "No decoder available on this device for $videoMime."
        }

        val normalizedRot = ((rotation % 360) + 360) % 360
        val (dispW, dispH) = if (normalizedRot == 90 || normalizedRot == 270) {
            rawH to rawW
        } else {
            rawW to rawH
        }

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
        val canExportWebm = hasSurfaceEncoderForAny(
            codecInfos,
            listOf(MediaFormat.MIMETYPE_VIDEO_VP9, MediaFormat.MIMETYPE_VIDEO_VP8)
        )
        val canExport3gp = hasSurfaceEncoderForAny(
            codecInfos,
            listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_H263)
        )

        val fallbackLabel = when {
            canExportMp4 -> "MP4 (H.264)"
            canExportWebm -> "WEBM (VP8/VP9)"
            canExport3gp -> "3GP"
            else -> "MP4 Software Safe"
        }

        val isOriginalMp4 = originalExt.equals("mp4", ignoreCase = true) ||
            originalExt.equals("m4v", ignoreCase = true) ||
            originalMime.equals("video/mp4", ignoreCase = true)
        val isOriginalWebm = originalExt.equals("webm", ignoreCase = true) ||
            originalMime.equals("video/webm", ignoreCase = true)
        val isOriginal3gp = originalExt.equals("3gp", ignoreCase = true) ||
            originalExt.equals("3gpp", ignoreCase = true) ||
            originalMime.equals("video/3gpp", ignoreCase = true)

        val mp4Opt = VideoExportFormatOption(
            label = if (isOriginalMp4) "MP4 (Original)" else "MP4",
            container = VideoContainerFormat.MP4,
            isOriginal = isOriginalMp4,
            supportInfo = DeviceCapabilityEngine.probeVideoExportSupport(
                container = VideoContainerFormat.MP4,
                fallbackLabel = fallbackLabel
            )
        )
        val webmOpt = VideoExportFormatOption(
            label = if (isOriginalWebm) "Original (WEBM)" else "WEBM",
            container = VideoContainerFormat.WEBM,
            isOriginal = isOriginalWebm,
            supportInfo = DeviceCapabilityEngine.probeVideoExportSupport(
                container = VideoContainerFormat.WEBM,
                fallbackLabel = fallbackLabel
            )
        )
        val threeGpOpt = VideoExportFormatOption(
            label = if (isOriginal3gp) "Original (3GP)" else "3GP",
            container = VideoContainerFormat.THREE_GP,
            isOriginal = isOriginal3gp,
            supportInfo = DeviceCapabilityEngine.probeVideoExportSupport(
                container = VideoContainerFormat.THREE_GP,
                fallbackLabel = fallbackLabel
            )
        )

        return when {
            isOriginalWebm -> listOf(webmOpt, mp4Opt, threeGpOpt)
            isOriginal3gp -> listOf(threeGpOpt, mp4Opt, webmOpt)
            else -> listOf(mp4Opt, webmOpt, threeGpOpt)
        }
    }

    private fun hasCompatibleDecoder(mime: String): Boolean {
        if (hasDecoderForMime(mime)) return true
        if (mime.equals("video/dolby-vision", ignoreCase = true)) {
            return hasDecoderForMime(MediaFormat.MIMETYPE_VIDEO_HEVC)
        }
        return false
    }

    private fun hasDecoderForMime(mime: String): Boolean {
        return cachedCodecInfos.any { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
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
