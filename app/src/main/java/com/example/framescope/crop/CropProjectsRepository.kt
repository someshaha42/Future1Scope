package com.example.framescope.crop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

private val Context.cropPrefsDataStore by preferencesDataStore(name = "framescope_crop_prefs")

class CropProjectsRepository(private val context: Context) {

    private object Keys {
        val COPY_ORIGINAL_SOURCE = booleanPreferencesKey("copy_original_source")
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val storeMutex = Mutex()

    private val projectsDir: File
        get() = File(context.filesDir, "crop_projects").apply { if (!exists()) mkdirs() }

    private val copiedSourcesDir: File
        get() = File(projectsDir, "sources").apply { if (!exists()) mkdirs() }

    private val projectsFile: File
        get() = File(projectsDir, "projects.json")

    // Default is OFF (false): original gallery URI is referenced without duplicating files into app storage.
    val copyOriginalSourceFlow: Flow<Boolean> = context.cropPrefsDataStore.data.map { prefs ->
        prefs[Keys.COPY_ORIGINAL_SOURCE] ?: false
    }

    suspend fun setCopyOriginalSource(enabled: Boolean) {
        context.cropPrefsDataStore.edit { prefs ->
            prefs[Keys.COPY_ORIGINAL_SOURCE] = enabled
        }
    }

    suspend fun copySourceToInternalStorage(
        projectId: String,
        sourceUri: Uri,
        extension: String
    ): String? = withContext(Dispatchers.IO) {
        val safeExt = extension.ifBlank { "dat" }.lowercase()
        val targetFile = File(copiedSourcesDir, "${projectId}.$safeExt")
        val ok = runCatching {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output, bufferSize = 256 * 1024)
                }
                true
            } ?: false
        }.getOrDefault(false)

        if (ok && targetFile.exists() && targetFile.length() > 0L) {
            targetFile.absolutePath
        } else {
            runCatching { targetFile.delete() }
            null
        }
    }

    suspend fun isProjectSourceAccessible(project: CropProjectRecord): Boolean = withContext(Dispatchers.IO) {
        val copyPath = project.localCopyPath
        if (!copyPath.isNullOrBlank()) {
            val f = File(copyPath)
            return@withContext f.exists() && f.length() > 0L
        }

        val uri = runCatching { Uri.parse(project.sourceUriString) }.getOrNull() ?: return@withContext false
        if (uri.scheme == "file") {
            val f = uri.path?.let { File(it) }
            return@withContext f != null && f.exists() && f.length() > 0L
        }

        runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                afd.length != 0L
            } ?: false
        }.getOrDefault(false)
    }

    suspend fun loadValidProjects(): List<CropProjectRecord> = withContext(Dispatchers.IO) {
        storeMutex.withLock {
            val raw = readAllLocked()
            if (raw.isEmpty()) return@withLock emptyList()

            val valid = mutableListOf<CropProjectRecord>()
            var changed = false

            for (item in raw) {
                if (!isProjectSourceAccessibleInternal(item)) {
                    changed = true
                    continue
                }
                val expPath = item.exportedFilePath
                val sanitized = if (!expPath.isNullOrBlank() && !File(expPath).exists()) {
                    changed = true
                    item.copy(exportedFilePath = null)
                } else {
                    item
                }
                valid += sanitized
            }

            if (changed) {
                writeAllLocked(valid)
            }

            valid.sortedByDescending { it.updatedAtMs }
        }
    }

    suspend fun upsertProject(project: CropProjectRecord) = withContext(Dispatchers.IO) {
        storeMutex.withLock {
            val list = readAllLocked().toMutableList()
            val idx = list.indexOfFirst { it.id == project.id }
            if (idx >= 0) {
                list[idx] = project
            } else {
                list.add(0, project)
            }
            writeAllLocked(list)
        }
    }

    suspend fun deleteProject(projectId: String): Boolean = withContext(Dispatchers.IO) {
        storeMutex.withLock {
            val list = readAllLocked().toMutableList()
            val target = list.firstOrNull { it.id == projectId } ?: return@withLock false
            list.removeAll { it.id == projectId }
            writeAllLocked(list)

            // Delete internal app copy of source if Copy Original Source was enabled for this project.
            target.localCopyPath?.let { path ->
                runCatching { File(path).delete() }
            }

            // Delete app-private working export file ONLY (public Gallery MediaStore exports stay untouched!).
            target.exportedFilePath?.let { expPath ->
                val expFile = File(expPath)
                if (expFile.exists() && isInsideAppPrivateExports(expFile)) {
                    runCatching { expFile.delete() }
                }
            }
            true
        }
    }

    suspend fun loadThumbnail(project: CropProjectRecord, targetSizePx: Int = 180): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                val expFile = project.exportedFilePath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
                val localFile = project.localCopyPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
                val sourceUri = runCatching { Uri.parse(project.sourceUriString) }.getOrNull()

                when (project.mediaType) {
                    CropMediaType.VIDEO -> {
                        val retriever = MediaMetadataRetriever()
                        try {
                            when {
                                expFile != null -> retriever.setDataSource(expFile.absolutePath)
                                localFile != null -> retriever.setDataSource(localFile.absolutePath)
                                sourceUri != null -> retriever.setDataSource(context, sourceUri)
                                else -> return@withContext null
                            }
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
                        when {
                            expFile != null -> decodeSampledFile(expFile, targetSizePx)
                            localFile != null -> decodeSampledFile(localFile, targetSizePx)
                            sourceUri != null -> decodeSampledUri(sourceUri, targetSizePx)
                            else -> null
                        }
                    }
                }
            }.getOrNull()
        }

    private fun decodeSampledFile(file: File, targetSizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        var halfW = bounds.outWidth / 2
        var halfH = bounds.outHeight / 2
        while (halfW / sample >= targetSizePx && halfH / sample >= targetSizePx) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    private fun decodeSampledUri(uri: Uri, targetSizePx: Int): Bitmap? {
        return runCatching {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_DEFAULT
                val maxSide = maxOf(info.size.width, info.size.height)
                if (maxSide > targetSizePx && targetSizePx > 0) {
                    val scale = targetSizePx.toFloat() / maxSide.toFloat()
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1)
                    )
                }
            }
        }.getOrNull()
    }

    private fun isProjectSourceAccessibleInternal(project: CropProjectRecord): Boolean {
        val copyPath = project.localCopyPath
        if (!copyPath.isNullOrBlank()) {
            val f = File(copyPath)
            return f.exists() && f.length() > 0L
        }

        val uri = runCatching { Uri.parse(project.sourceUriString) }.getOrNull() ?: return false
        if (uri.scheme == "file") {
            val f = uri.path?.let { File(it) }
            return f != null && f.exists() && f.length() > 0L
        }

        return runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                afd.length != 0L
            } ?: false
        }.getOrDefault(false)
    }

    private fun isInsideAppPrivateExports(file: File): Boolean {
        val abs = file.absolutePath
        val extMovies = context.getExternalFilesDir(null)?.absolutePath
        val filesBase = context.filesDir.absolutePath
        return (extMovies != null && abs.startsWith(extMovies)) || abs.startsWith(filesBase)
    }

    private fun readAllLocked(): List<CropProjectRecord> {
        val file = projectsFile
        if (!file.exists() || file.length() <= 0L) return emptyList()
        return runCatching {
            json.decodeFromString<List<CropProjectRecord>>(file.readText())
        }.getOrDefault(emptyList())
    }

    private fun writeAllLocked(list: List<CropProjectRecord>) {
        runCatching {
            val tmp = File(projectsDir, "projects.json.tmp")
            tmp.writeText(json.encodeToString(list))
            if (projectsFile.exists()) {
                projectsFile.delete()
            }
            tmp.renameTo(projectsFile)
        }
    }
}
