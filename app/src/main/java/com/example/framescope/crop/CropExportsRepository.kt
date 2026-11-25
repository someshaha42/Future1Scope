package com.example.framescope.crop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CropExportsRepository(private val context: Context) {

    fun getVideoExportsDirectory(): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        return File(base, "FrameScopeCrops").apply { if (!exists()) mkdirs() }
    }

    fun getImageExportsDirectory(): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
        return File(base, "FrameScopeCrops").apply { if (!exists()) mkdirs() }
    }

    fun createVideoExportFile(extension: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(getVideoExportsDirectory(), "FrameScope_Crop_$stamp.$extension")
    }

    fun createImageExportFile(extension: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(getImageExportsDirectory(), "FrameScope_Crop_$stamp.$extension")
    }

    suspend fun loadRecentExports(): List<CropExportedItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<CropExportedItem>()

        val videoDirs = listOfNotNull(
            getVideoExportsDirectory(),
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.let { File(it, "FrameScope") }
        )
        videoDirs.distinctBy { it.absolutePath }.forEach { dir ->
            dir.listFiles { f ->
                f.isFile && f.length() > 0L && f.name.startsWith("FrameScope_Crop_") &&
                    f.extension.lowercase(Locale.US) in setOf("mp4", "webm", "3gp")
            }?.forEach { file ->
                items += readVideoItem(file)
            }
        }

        val imageDirs = listOfNotNull(
            getImageExportsDirectory(),
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.let { File(it, "FrameScope") }
        )
        imageDirs.distinctBy { it.absolutePath }.forEach { dir ->
            dir.listFiles { f ->
                f.isFile && f.length() > 0L && f.name.startsWith("FrameScope_Crop_") &&
                    f.extension.lowercase(Locale.US) in setOf("png", "jpg", "jpeg", "webp")
            }?.forEach { file ->
                items += readImageItem(file)
            }
        }

        items.distinctBy { it.filePath }.sortedByDescending { it.exportedAtMs }
    }

    suspend fun loadThumbnail(item: CropExportedItem, targetSizePx: Int = 180): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                when (item.mediaType) {
                    CropMediaType.VIDEO -> {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(item.filePath)
                            retriever.getScaledFrameAtTime(
                                0L,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                targetSizePx,
                                targetSizePx
                            ) ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        } finally {
                            runCatching { retriever.release() }
                        }
                    }
                    CropMediaType.IMAGE -> {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(item.filePath, bounds)
                        var sample = 1
                        var halfW = bounds.outWidth / 2
                        var halfH = bounds.outHeight / 2
                        while (halfW / sample >= targetSizePx && halfH / sample >= targetSizePx) {
                            sample *= 2
                        }
                        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                        BitmapFactory.decodeFile(item.filePath, opts)
                    }
                }
            }.getOrNull()
        }

    suspend fun deleteExport(filePath: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(filePath)
        file.exists() && file.delete()
    }

    private fun readVideoItem(file: File): CropExportedItem {
        val retriever = MediaMetadataRetriever()
        var width = 0
        var height = 0
        var durationMs = 0L
        try {
            retriever.setDataSource(file.absolutePath)
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Throwable) {
        } finally {
            runCatching { retriever.release() }
        }
        return CropExportedItem(
            filePath = file.absolutePath,
            fileName = file.name,
            mediaType = CropMediaType.VIDEO,
            formatLabel = file.extension.uppercase(Locale.US),
            width = width,
            height = height,
            durationMs = durationMs,
            sizeBytes = file.length(),
            exportedAtMs = file.lastModified()
        )
    }

    private fun readImageItem(file: File): CropExportedItem {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }
        return CropExportedItem(
            filePath = file.absolutePath,
            fileName = file.name,
            mediaType = CropMediaType.IMAGE,
            formatLabel = file.extension.uppercase(Locale.US),
            width = opts.outWidth.coerceAtLeast(0),
            height = opts.outHeight.coerceAtLeast(0),
            durationMs = 0L,
            sizeBytes = file.length(),
            exportedAtMs = file.lastModified()
        )
    }
}
