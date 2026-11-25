package com.example.framescope.crop

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

object ImageCropProcessor {

    private val PRESERVABLE_EXIF_TAGS = listOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_COLOR_SPACE
    )

    suspend fun loadPreviewBitmap(
        context: Context,
        media: ImportedMediaInfo,
        maxDimension: Int = 2048
    ): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val source = ImageDecoder.createSource(context.contentResolver, media.uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                val w = info.size.width
                val h = info.size.height
                val maxSide = maxOf(w, h)
                if (maxSide > maxDimension && maxDimension > 0) {
                    val scale = maxDimension.toFloat() / maxSide.toFloat()
                    decoder.setTargetSize(
                        (w * scale).roundToInt().coerceAtLeast(1),
                        (h * scale).roundToInt().coerceAtLeast(1)
                    )
                }
            }
        }.getOrElse {
            decodeWithBitmapFactory(context, media.uri, maxDimension)
        }
    }

    suspend fun exportCroppedImage(
        context: Context,
        media: ImportedMediaInfo,
        cropRect: NormalizedCropRect,
        formatOption: ImageExportFormatOption
    ): CropExportState = withContext(Dispatchers.IO) {
        var sourceBitmap: Bitmap? = null
        var croppedBitmap: Bitmap? = null
        var finalBitmap: Bitmap? = null

        try {
            sourceBitmap = decodeFullResolutionBitmap(context, media.uri)
                ?: return@withContext CropExportState.Error("Unable to decode the selected image.")

            val srcW = sourceBitmap.width
            val srcH = sourceBitmap.height

            val leftPx = (cropRect.left * srcW).roundToInt().coerceIn(0, srcW - 1)
            val topPx = (cropRect.top * srcH).roundToInt().coerceIn(0, srcH - 1)
            val rightPx = (cropRect.right * srcW).roundToInt().coerceIn(leftPx + 1, srcW)
            val bottomPx = (cropRect.bottom * srcH).roundToInt().coerceIn(topPx + 1, srcH)

            val outW = (rightPx - leftPx).coerceIn(1, srcW)
            val outH = (bottomPx - topPx).coerceIn(1, srcH)

            croppedBitmap = Bitmap.createBitmap(sourceBitmap, leftPx, topPx, outW, outH)

            val outputSpec = resolveCompressSpec(formatOption, croppedBitmap.hasAlpha())
            val outDir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir,
                "FrameScope"
            ).apply { if (!exists()) mkdirs() }

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val outFile = File(outDir, "FrameScope_Crop_$stamp.${outputSpec.extension}")

            finalBitmap = if (!outputSpec.supportsAlpha && croppedBitmap.hasAlpha()) {
                val targetColorSpace = croppedBitmap.colorSpace
                    ?: android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
                val opaque = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888, false, targetColorSpace)
                val canvas = Canvas(opaque)
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(croppedBitmap, 0f, 0f, null)
                opaque
            } else {
                croppedBitmap
            }

            FileOutputStream(outFile).use { fos ->
                val ok = finalBitmap.compress(outputSpec.compressFormat, outputSpec.quality, fos)
                if (!ok) {
                    return@withContext CropExportState.Error("Image encoder failed for ${outputSpec.extension.uppercase(Locale.US)}.")
                }
            }

            // Preserve compatible EXIF metadata while accurately updating dimensions & orientation
            preserveExifMetadata(context, media.uri, outFile, outW, outH)

            val savedToGallery = exportImageToMediaStore(
                context = context,
                file = outFile,
                mimeType = outputSpec.mimeType
            )

            CropExportState.Success(
                outputPath = outFile.absolutePath,
                fileName = outFile.name,
                savedToGallery = savedToGallery,
                mediaType = CropMediaType.IMAGE
            )
        } catch (t: Throwable) {
            CropExportState.Error(t.message ?: "Failed to crop and export image.")
        } finally {
            if (finalBitmap != null && finalBitmap !== croppedBitmap) {
                runCatching { finalBitmap.recycle() }
            }
            if (croppedBitmap != null && croppedBitmap !== sourceBitmap) {
                runCatching { croppedBitmap.recycle() }
            }
            runCatching { sourceBitmap?.recycle() }
        }
    }

    private fun decodeFullResolutionBitmap(context: Context, uri: android.net.Uri): Bitmap? {
        return runCatching {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
        }.getOrElse {
            decodeWithBitmapFactory(context, uri, maxDimension = 0)
        }
    }

    private fun decodeWithBitmapFactory(
        context: Context,
        uri: android.net.Uri,
        maxDimension: Int
    ): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        var sampleSize = 1
        if (maxDimension > 0 && bounds.outWidth > 0 && bounds.outHeight > 0) {
            var halfW = bounds.outWidth / 2
            var halfH = bounds.outHeight / 2
            while (halfW / sampleSize >= maxDimension && halfH / sampleSize >= maxDimension) {
                sampleSize *= 2
            }
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: return null

        val orientation = resolver.openInputStream(uri)?.use { input ->
            runCatching {
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return raw
        }

        return runCatching {
            val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
            if (rotated !== raw) raw.recycle()
            rotated
        }.getOrDefault(raw)
    }

    private data class CompressSpec(
        val compressFormat: Bitmap.CompressFormat,
        val quality: Int,
        val extension: String,
        val mimeType: String,
        val supportsAlpha: Boolean
    )

    private fun resolveCompressSpec(
        option: ImageExportFormatOption,
        sourceHasAlpha: Boolean
    ): CompressSpec {
        return when (option.id) {
            ImageExportFormatId.PNG -> CompressSpec(
                compressFormat = Bitmap.CompressFormat.PNG,
                quality = 100,
                extension = "png",
                mimeType = "image/png",
                supportsAlpha = true
            )
            ImageExportFormatId.JPG -> CompressSpec(
                compressFormat = Bitmap.CompressFormat.JPEG,
                quality = 95,
                extension = "jpg",
                mimeType = "image/jpeg",
                supportsAlpha = false
            )
            ImageExportFormatId.ORIGINAL -> {
                when (option.extension.lowercase(Locale.US)) {
                    "jpg", "jpeg" -> CompressSpec(
                        compressFormat = Bitmap.CompressFormat.JPEG,
                        quality = 95,
                        extension = option.extension,
                        mimeType = "image/jpeg",
                        supportsAlpha = false
                    )
                    "webp" -> CompressSpec(
                        compressFormat = if (sourceHasAlpha) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY,
                        quality = if (sourceHasAlpha) 100 else 95,
                        extension = "webp",
                        mimeType = "image/webp",
                        supportsAlpha = true
                    )
                    else -> CompressSpec(
                        compressFormat = Bitmap.CompressFormat.PNG,
                        quality = 100,
                        extension = "png",
                        mimeType = "image/png",
                        supportsAlpha = true
                    )
                }
            }
        }
    }

    private fun preserveExifMetadata(
        context: Context,
        sourceUri: android.net.Uri,
        destFile: File,
        newWidth: Int,
        newHeight: Int
    ) {
        runCatching {
            val sourceTags = mutableMapOf<String, String>()
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                val srcExif = ExifInterface(input)
                for (tag in PRESERVABLE_EXIF_TAGS) {
                    val value = srcExif.getAttribute(tag)
                    if (!value.isNullOrBlank()) {
                        sourceTags[tag] = value
                    }
                }
            }

            val dstExif = ExifInterface(destFile.absolutePath)
            sourceTags.forEach { (tag, value) ->
                dstExif.setAttribute(tag, value)
            }
            dstExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            dstExif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, newWidth.toString())
            dstExif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, newHeight.toString())
            dstExif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, newWidth.toString())
            dstExif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, newHeight.toString())
            dstExif.saveAttributes()
        }
    }

    private fun exportImageToMediaStore(
        context: Context,
        file: File,
        mimeType: String
    ): Boolean {
        if (!file.exists() || file.length() <= 0L) return false
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/FrameScope")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out, bufferSize = 256 * 1024) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            true
        } catch (_: Throwable) {
            resolver.delete(uri, null, null)
            false
        }
    }
}
