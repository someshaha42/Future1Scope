package com.example.framescope.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingsRepository(private val context: Context) {

    fun getRecordingsDirectory(): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        return File(base, "FrameScope").apply {
            if (!exists()) mkdirs()
        }
    }

    fun createNewOutputFile(): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(getRecordingsDirectory(), "FrameScope_$stamp.mp4")
    }

    suspend fun loadRecordings(): List<RecordedVideo> = withContext(Dispatchers.IO) {
        val dir = getRecordingsDirectory()
        val files = dir.listFiles { file ->
            file.isFile && file.extension.equals("mp4", ignoreCase = true) && file.length() > 0L
        }?.sortedByDescending { it.lastModified() }.orEmpty()

        files.map { file ->
            readMetadata(file)
        }
    }

    private fun readMetadata(file: File): RecordedVideo {
        val retriever = MediaMetadataRetriever()
        var durationMs = 0L
        var width = 0
        var height = 0
        var bitrate = 0L
        try {
            retriever.setDataSource(file.absolutePath)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toLongOrNull() ?: 0L
        } catch (_: Throwable) {
            // Incomplete or unreadable container falls back to zeros
        } finally {
            runCatching { retriever.release() }
        }
        return RecordedVideo(
            filePath = file.absolutePath,
            fileName = file.name,
            durationMs = durationMs,
            width = width,
            height = height,
            sizeBytes = file.length(),
            bitrateBps = bitrate,
            createdAtMs = file.lastModified()
        )
    }

    suspend fun deleteRecording(filePath: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(filePath)
        file.exists() && file.delete()
    }

    suspend fun exportToMediaStore(filePath: String): Boolean = withContext(Dispatchers.IO) {
        val source = File(filePath)
        if (!source.exists() || source.length() <= 0L) return@withContext false

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, source.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/FrameScope")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext false

        return@withContext try {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { input ->
                    input.copyTo(out)
                }
            }
            val publishValues = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            resolver.update(uri, publishValues, null, null)
            true
        } catch (_: Throwable) {
            resolver.delete(uri, null, null)
            false
        }
    }
}
