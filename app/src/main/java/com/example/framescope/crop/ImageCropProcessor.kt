package com.example.framescope.crop

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.roundToInt

object ImageCropProcessor {

    suspend fun loadPreviewBitmap(
        context: Context,
        media: ImportedMediaInfo,
        maxDimension: Int = 2048
    ): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val source = ImageDecoder.createSource(context.contentResolver, media.uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_DEFAULT
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
        var croppedBitmap: Bitmap? = null
        var finalBitmap: Bitmap? = null

        try {
            croppedBitmap = decodeCroppedBitmapFast(context, media.uri, cropRect)
                ?: return@withContext CropExportState.Error("Unable to decode the selected image.")

            val outW = croppedBitmap.width.coerceAtLeast(1)
            val outH = croppedBitmap.height.coerceAtLeast(1)

            val outputSpec = resolveCompressSpec(formatOption, croppedBitmap.hasAlpha())
            val outFile = CropExportsRepository(context).createImageExportFile(outputSpec.extension)

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

            BufferedOutputStream(FileOutputStream(outFile), 256 * 1024).use { bos ->
                val ok = if (outputSpec.useUncompressedPng) {
                    writeUncompressedPng(finalBitmap, bos)
                } else {
                    finalBitmap.compress(outputSpec.compressFormat, outputSpec.quality, bos)
                }
                if (!ok) {
                    return@withContext CropExportState.Error("Image encoder failed for ${outputSpec.extension.uppercase(Locale.US)}.")
                }
            }

            val galleryUri = exportImageToMediaStore(
                context = context,
                file = outFile,
                mimeType = outputSpec.mimeType
            )

            CropExportState.Success(
                outputPath = outFile.absolutePath,
                fileName = outFile.name,
                savedToGallery = galleryUri != null,
                mediaType = CropMediaType.IMAGE,
                galleryUriString = galleryUri
            )
        } catch (t: Throwable) {
            CropExportState.Error(t.message ?: "Failed to crop and export image.")
        } finally {
            if (finalBitmap != null && finalBitmap !== croppedBitmap) {
                runCatching { finalBitmap.recycle() }
            }
            runCatching { croppedBitmap?.recycle() }
        }
    }

    private fun decodeCroppedBitmapFast(
        context: Context,
        uri: android.net.Uri,
        cropRect: NormalizedCropRect
    ): Bitmap? {
        val resolver = context.contentResolver
        val isFullCrop = cropRect.left <= 0.001f &&
            cropRect.top <= 0.001f &&
            cropRect.right >= 0.999f &&
            cropRect.bottom >= 0.999f

        // 1. Primary single-open native path: ImageDecoder handles crop rect + orientation directly in one pass
        val decoderCropped = runCatching {
            val source = ImageDecoder.createSource(resolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                if (!isFullCrop) {
                    val srcW = info.size.width
                    val srcH = info.size.height
                    if (srcW > 0 && srcH > 0) {
                        val leftPx = (cropRect.left * srcW).roundToInt().coerceIn(0, srcW - 1)
                        val topPx = (cropRect.top * srcH).roundToInt().coerceIn(0, srcH - 1)
                        val rightPx = (cropRect.right * srcW).roundToInt().coerceIn(leftPx + 1, srcW)
                        val bottomPx = (cropRect.bottom * srcH).roundToInt().coerceIn(topPx + 1, srcH)
                        decoder.crop = Rect(leftPx, topPx, rightPx, bottomPx)
                    }
                }
            }
        }.getOrNull()
        if (decoderCropped != null) return decoderCropped

        // 2. Fallback region decode for streams ImageDecoder did not accept directly
        if (!isFullCrop) {
            val orientation = resolver.openInputStream(uri)?.use { input ->
                runCatching {
                    ExifInterface(input).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL

            if (orientation == ExifInterface.ORIENTATION_NORMAL ||
                orientation == ExifInterface.ORIENTATION_UNDEFINED
            ) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    val leftPx = (cropRect.left * bounds.outWidth).roundToInt().coerceIn(0, bounds.outWidth - 1)
                    val topPx = (cropRect.top * bounds.outHeight).roundToInt().coerceIn(0, bounds.outHeight - 1)
                    val rightPx = (cropRect.right * bounds.outWidth).roundToInt().coerceIn(leftPx + 1, bounds.outWidth)
                    val bottomPx = (cropRect.bottom * bounds.outHeight).roundToInt().coerceIn(topPx + 1, bounds.outHeight)
                    val regionBitmap = runCatching {
                        resolver.openInputStream(uri)?.use { stream ->
                            val regionDecoder = BitmapRegionDecoder.newInstance(stream, false)
                            try {
                                regionDecoder?.decodeRegion(
                                    Rect(leftPx, topPx, rightPx, bottomPx),
                                    BitmapFactory.Options().apply {
                                        inPreferredConfig = Bitmap.Config.ARGB_8888
                                    }
                                )
                            } finally {
                                regionDecoder?.recycle()
                            }
                        }
                    }.getOrNull()
                    if (regionBitmap != null) return regionBitmap
                }
            }
        }

        // 3. Safe fallback: full decode + sub-bitmap crop
        val sourceBitmap = decodeFullResolutionBitmap(context, uri) ?: return null
        return try {
            val srcW = sourceBitmap.width
            val srcH = sourceBitmap.height
            val leftPx = (cropRect.left * srcW).roundToInt().coerceIn(0, srcW - 1)
            val topPx = (cropRect.top * srcH).roundToInt().coerceIn(0, srcH - 1)
            val rightPx = (cropRect.right * srcW).roundToInt().coerceIn(leftPx + 1, srcW)
            val bottomPx = (cropRect.bottom * srcH).roundToInt().coerceIn(topPx + 1, srcH)
            val outW = (rightPx - leftPx).coerceIn(1, srcW)
            val outH = (bottomPx - topPx).coerceIn(1, srcH)
            val sub = Bitmap.createBitmap(sourceBitmap, leftPx, topPx, outW, outH)
            if (sub !== sourceBitmap) {
                runCatching { sourceBitmap.recycle() }
            }
            sub
        } catch (t: Throwable) {
            runCatching { sourceBitmap.recycle() }
            throw t
        }
    }

    private fun decodeFullResolutionBitmap(context: Context, uri: android.net.Uri): Bitmap? {
        val direct = try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
        } catch (_: Throwable) {
            null
        }
        if (direct != null) return direct

        val caps = listOf(0, 4096, 2560)
        for (cap in caps) {
            val attempt = try {
                decodeWithBitmapFactory(context, uri, maxDimension = cap)
            } catch (_: OutOfMemoryError) {
                null
            }
            if (attempt != null) return attempt
        }
        return null
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

        var raw: Bitmap? = null
        var attemptSample = sampleSize
        repeat(4) {
            if (raw != null) return@repeat
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = attemptSample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            raw = try {
                resolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, decodeOpts)
                }
            } catch (_: OutOfMemoryError) {
                attemptSample *= 2
                null
            }
        }
        val decoded = raw ?: return null

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
            else -> return decoded
        }

        return runCatching {
            val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (rotated !== decoded) decoded.recycle()
            rotated
        }.getOrDefault(decoded)
    }

    private data class CompressSpec(
        val compressFormat: Bitmap.CompressFormat,
        val quality: Int,
        val extension: String,
        val mimeType: String,
        val supportsAlpha: Boolean,
        val useUncompressedPng: Boolean = false
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
                supportsAlpha = true,
                useUncompressedPng = true
            )
            ImageExportFormatId.JPG -> CompressSpec(
                compressFormat = Bitmap.CompressFormat.JPEG,
                quality = 100,
                extension = "jpg",
                mimeType = "image/jpeg",
                supportsAlpha = false
            )
            ImageExportFormatId.ORIGINAL -> {
                when (option.extension.lowercase(Locale.US)) {
                    "jpg", "jpeg" -> CompressSpec(
                        compressFormat = Bitmap.CompressFormat.JPEG,
                        quality = 100,
                        extension = option.extension,
                        mimeType = "image/jpeg",
                        supportsAlpha = false
                    )
                    "webp" -> CompressSpec(
                        compressFormat = Bitmap.CompressFormat.WEBP_LOSSLESS,
                        quality = 0,
                        extension = "webp",
                        mimeType = "image/webp",
                        supportsAlpha = true
                    )
                    else -> CompressSpec(
                        compressFormat = Bitmap.CompressFormat.PNG,
                        quality = 100,
                        extension = "png",
                        mimeType = "image/png",
                        supportsAlpha = true,
                        useUncompressedPng = true
                    )
                }
            }
        }
    }

    private fun writeUncompressedPng(bitmap: Bitmap, out: OutputStream): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return false

        val rgbaBitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return false
        }

        return try {
            // PNG signature
            out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

            // IHDR chunk: 8-bit RGBA (color type 6), no compression filter overhead, non-interlaced
            val ihdr = ByteArray(13)
            writeIntBigEndian(ihdr, 0, width)
            writeIntBigEndian(ihdr, 4, height)
            ihdr[8] = 8
            ihdr[9] = 6
            ihdr[10] = 0
            ihdr[11] = 0
            ihdr[12] = 0
            writePngChunk(out, IHDR_TAG, ihdr, 13)

            // IDAT chunks using stored DEFLATE (NO_COMPRESSION) so pixels are 100% lossless with zero CPU compression
            val idatOut = IdatChunkOutputStream(out)
            val deflater = Deflater(Deflater.NO_COMPRESSION)
            try {
                DeflaterOutputStream(idatOut, deflater, 128 * 1024).use { defStream ->
                    val stripRows = minOf(64, height)
                    val stripInts = IntArray(width * stripRows)
                    val rowStride = 1 + width * 4
                    val stripBytes = ByteArray(stripRows * rowStride)

                    var y = 0
                    while (y < height) {
                        val rows = minOf(stripRows, height - y)
                        rgbaBitmap.getPixels(stripInts, 0, width, 0, y, width, rows)
                        var dst = 0
                        for (r in 0 until rows) {
                            stripBytes[dst++] = 0
                            val srcOffset = r * width
                            for (x in 0 until width) {
                                val argb = stripInts[srcOffset + x]
                                stripBytes[dst++] = (argb ushr 16).toByte()
                                stripBytes[dst++] = (argb ushr 8).toByte()
                                stripBytes[dst++] = argb.toByte()
                                stripBytes[dst++] = (argb ushr 24).toByte()
                            }
                        }
                        defStream.write(stripBytes, 0, rows * rowStride)
                        y += rows
                    }
                    defStream.finish()
                }
                idatOut.flushBuffer()
            } finally {
                deflater.end()
            }

            // IEND chunk
            writePngChunk(out, IEND_TAG, EMPTY_BYTES, 0)
            out.flush()
            true
        } finally {
            if (rgbaBitmap !== bitmap) {
                runCatching { rgbaBitmap.recycle() }
            }
        }
    }

    private class IdatChunkOutputStream(
        private val out: OutputStream,
        bufferCapacity: Int = 256 * 1024
    ) : OutputStream() {
        private val buffer = ByteArray(bufferCapacity)
        private var count = 0

        override fun write(b: Int) {
            if (count >= buffer.size) {
                flushBuffer()
            }
            buffer[count++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var offset = off
            var remaining = len
            while (remaining > 0) {
                if (count >= buffer.size) {
                    flushBuffer()
                }
                val chunk = minOf(remaining, buffer.size - count)
                System.arraycopy(b, offset, buffer, count, chunk)
                count += chunk
                offset += chunk
                remaining -= chunk
            }
        }

        fun flushBuffer() {
            if (count > 0) {
                writePngChunk(out, IDAT_TAG, buffer, count)
                count = 0
            }
        }

        override fun flush() {
            flushBuffer()
            out.flush()
        }

        override fun close() {
            flushBuffer()
        }
    }

    private fun writePngChunk(out: OutputStream, tag: ByteArray, data: ByteArray, length: Int) {
        val lenBytes = ByteArray(4)
        writeIntBigEndian(lenBytes, 0, length)
        out.write(lenBytes)
        out.write(tag)
        if (length > 0) {
            out.write(data, 0, length)
        }
        val crc = CRC32()
        crc.update(tag)
        if (length > 0) {
            crc.update(data, 0, length)
        }
        val crcVal = crc.value.toInt()
        val crcBytes = ByteArray(4)
        writeIntBigEndian(crcBytes, 0, crcVal)
        out.write(crcBytes)
    }

    private fun writeIntBigEndian(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value ushr 24).toByte()
        dst[offset + 1] = (value ushr 16).toByte()
        dst[offset + 2] = (value ushr 8).toByte()
        dst[offset + 3] = value.toByte()
    }

    private val IHDR_TAG = byteArrayOf('I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte())
    private val IDAT_TAG = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), 'A'.code.toByte(), 'T'.code.toByte())
    private val IEND_TAG = byteArrayOf('I'.code.toByte(), 'E'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte())
    private val EMPTY_BYTES = ByteArray(0)

    private fun exportImageToMediaStore(
        context: Context,
        file: File,
        mimeType: String
    ): String? {
        if (!file.exists() || file.length() <= 0L) return null
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/FrameScope")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out, bufferSize = 512 * 1024) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri.toString()
        } catch (_: Throwable) {
            resolver.delete(uri, null, null)
            null
        }
    }
}
