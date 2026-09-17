package com.videoconverter.android.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.videoconverter.android.R
import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.allocateOutputPath
import com.videoconverter.android.domain.outputCollisionStamp
import com.videoconverter.android.domain.partialOutputPath
import com.videoconverter.android.domain.uniqueFileName
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun defaultRelativePath(): String = mediaStoreRelativePath(OutputTarget.Kind.Downloads)

fun mediaStoreRelativePath(kind: OutputTarget.Kind): String = when (kind) {
    OutputTarget.Kind.Gallery -> "DCIM/轻转码"
    OutputTarget.Kind.Movies -> "Movies/轻转码"
    OutputTarget.Kind.Downloads -> "Download/轻转码"
    OutputTarget.Kind.Music -> "Music/轻转码"
    OutputTarget.Kind.Documents -> "Documents/轻转码"
    OutputTarget.Kind.SafTree, OutputTarget.Kind.AppExternal ->
        throw IllegalArgumentException("not a MediaStore target")
}

data class OutputTarget(
    val kind: Kind,
    val treeUri: String? = null,
) {
    enum class Kind { Downloads, SafTree, AppExternal, Gallery, Movies, Music, Documents }
}

fun exportedLocations(job: Job): List<String> =
    job.outputPaths.ifEmpty { listOfNotNull(job.outputPath) }

fun outputTargetForJob(kindName: String?, treeUri: String?, fallback: OutputTarget): OutputTarget {
    val kind = OutputTarget.Kind.entries.firstOrNull { it.name == kindName } ?: return fallback
    return OutputTarget(kind, treeUri)
}

fun stampJobOutputTarget(job: Job, target: OutputTarget): Job =
    job.copy(outputKind = target.kind.name, outputTreeUri = target.treeUri)

data class JobOutput(
    val partial: File,
    val final: File,
)

data class ExportedOutput(
    val location: String,
    val target: OutputTarget,
)

fun uniqueDisplayName(
    stem: String,
    ext: String,
    existing: Set<String>,
    clock: () -> String = ::outputCollisionStamp,
): String = uniqueFileName(stem, ext, { it in existing }, clock)

class OutputStore(
    private val context: Context,
    private val sessionStore: SessionStore = SessionStore(context),
) {
    fun createJobOutput(jobId: String, stem: String, ext: String): JobOutput {
        val jobDir = File(context.filesDir, "jobs/${safeSegment(jobId, "job")}").apply {
            if (!exists() && !mkdirs()) throw IOException(context.getString(R.string.error_cannot_create_temp))
        }
        val final = File(jobDir, "${safeSegment(stem, "output")}.${safeExtension(ext)}")
        val partial = File(partialOutputPath(final.absolutePath))
        return JobOutput(partial = partial, final = final)
    }

    fun finalizeJobOutput(output: JobOutput): File {
        if (!output.partial.isFile) throw IOException(context.getString(R.string.error_temp_missing))
        try {
            Files.move(
                output.partial.toPath(),
                output.final.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: IOException) {
            throw IOException(context.getString(R.string.error_cannot_finish_output), error)
        }
        return output.final
    }

    suspend fun export(
        source: File,
        stem: String,
        ext: String,
        mimeType: String,
        target: OutputTarget,
    ): ExportedOutput = withContext(Dispatchers.IO) {
        require(source.isFile) { context.getString(R.string.error_output_missing) }
        when (target.kind) {
            OutputTarget.Kind.Downloads,
            OutputTarget.Kind.Gallery,
            OutputTarget.Kind.Movies,
            OutputTarget.Kind.Music,
            OutputTarget.Kind.Documents,
            -> exportToMediaStore(source, stem, ext, mimeType, target.kind)
            OutputTarget.Kind.SafTree -> exportToSaf(source, stem, ext, mimeType, target)
            OutputTarget.Kind.AppExternal -> exportToAppExternal(source, stem, ext)
        }
    }

    private suspend fun exportToMediaStore(
        source: File,
        stem: String,
        ext: String,
        mimeType: String,
        kind: OutputTarget.Kind,
    ): ExportedOutput {
        val resolver = context.contentResolver
        val collection = mediaStoreCollection(kind, mimeType)
        val relativePath = "${mediaStoreRelativePath(kind)}/"
        val displayName = try {
            uniqueDisplayName(stem, ext, mediaStoreNames(collection, relativePath))
        } catch (_: Exception) {
            return exportToAppExternal(source, stem, ext)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val destination = try {
            resolver.insert(collection, values)
        } catch (_: Exception) {
            null
        } ?: return exportToAppExternal(source, stem, ext)

        try {
            resolver.openOutputStream(destination, "w").use { output ->
                requireNotNull(output) { context.getString(R.string.error_cannot_write_output) }
                source.inputStream().use { input -> input.copyTo(output) }
            }
            requireMediaStorePublished(
                resolver.update(
                    destination,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                ),
                context.getString(R.string.error_cannot_publish),
            )
        } catch (error: Exception) {
            resolver.delete(destination, null, null)
            throw IOException(context.getString(R.string.error_cannot_write_output), error)
        }
        return ExportedOutput(
            location = destination.toString(),
            target = OutputTarget(kind),
        )
    }

    private fun mediaStoreNames(collection: Uri, relativePath: String): Set<String> {
        val names = mutableSetOf<String>()
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(relativePath),
            null,
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            while (nameIndex >= 0 && cursor.moveToNext()) names += cursor.getString(nameIndex)
        }
        return names
    }

    private fun exportToSaf(
        source: File,
        stem: String,
        ext: String,
        mimeType: String,
        target: OutputTarget,
    ): ExportedOutput = mapSafExportErrors(context.getString(R.string.error_cannot_write_output_reselect)) {
        val uri = target.treeUri?.let(Uri::parse) ?: throw outputDirectoryError(context)
        if (!hasWritePermission(uri)) throw outputDirectoryError(context)
        val tree = DocumentFile.fromTreeUri(context, uri)
            ?.takeIf { it.isDirectory && it.canWrite() }
            ?: throw outputDirectoryError(context)
        val displayName = uniqueDisplayName(stem, ext, tree.listFiles().mapNotNull { it.name }.toSet())
        val destination = tree.createFile(mimeType, displayName) ?: throw outputDirectoryError(context)
        try {
            context.contentResolver.openOutputStream(destination.uri, "w").use { output ->
                requireNotNull(output) { context.getString(R.string.error_cannot_write_output_reselect) }
                source.inputStream().use { input -> input.copyTo(output) }
            }
        } catch (error: Exception) {
            destination.delete()
            throw IOException(context.getString(R.string.error_cannot_write_output_reselect), error)
        }
        ExportedOutput(destination.uri.toString(), target)
    }

    private suspend fun exportToAppExternal(
        source: File,
        stem: String,
        ext: String,
    ): ExportedOutput {
        val root = context.getExternalFilesDir(null) ?: throw IOException(context.getString(R.string.error_cannot_write_app_output))
        val outputDir = File(root, "轻转码").apply {
            if (!exists() && !mkdirs()) throw IOException(context.getString(R.string.error_cannot_create_app_output))
        }
        val outputPath = allocateOutputPath(
            outputDir.absolutePath,
            stem,
            ext,
            exists = { File(it).exists() },
        )
        val destination = File(outputPath)
        source.copyTo(destination)
        val actualTarget = OutputTarget(OutputTarget.Kind.AppExternal, outputDir.absolutePath)
        sessionStore.saveOutputTarget(actualTarget)
        return ExportedOutput(destination.absolutePath, actualTarget)
    }

    private fun hasWritePermission(uri: Uri): Boolean {
        val persisted = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
        if (persisted) return true
        return context.checkUriPermission(
            uri,
            Process.myPid(),
            Process.myUid(),
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun deleteExported(location: String?) {
        if (location.isNullOrBlank()) return
        val parsed = Uri.parse(location)
        if (parsed.scheme == "content") {
            context.contentResolver.delete(parsed, null, null)
            return
        }
        File(location).delete()
    }

    fun renameExported(location: String, newDisplayName: String): String {
        val parsed = Uri.parse(location)
        if (parsed.scheme == "content") {
            val renamed = runCatching {
                android.provider.DocumentsContract.renameDocument(
                    context.contentResolver,
                    parsed,
                    newDisplayName,
                )
            }.getOrNull()
            if (renamed != null) return renamed.toString()
            val updated = context.contentResolver.update(
                parsed,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, newDisplayName)
                },
                null,
                null,
            )
            if (updated <= 0) throw IOException(context.getString(R.string.error_cannot_rename_output))
            return location
        }
        val source = File(location)
        val destination = File(source.parentFile, newDisplayName)
        if (destination.exists() && destination.absolutePath != source.absolutePath) {
            throw IOException(context.getString(R.string.error_duplicate_name))
        }
        if (!source.renameTo(destination)) throw IOException(context.getString(R.string.error_cannot_rename_output))
        return destination.absolutePath
    }
}

private fun safeSegment(value: String, fallback: String): String =
    value.replace(Regex("""[\\/\u0000-\u001f]"""), "_").ifBlank { fallback }

private fun safeExtension(value: String): String =
    value.trimStart('.').replace(Regex("""[^A-Za-z0-9]"""), "").ifBlank { "bin" }

private fun outputDirectoryError(context: Context): IOException =
    IOException(context.getString(R.string.error_cannot_write_output_reselect))

internal fun requireMediaStorePublished(
    updatedRows: Int,
    message: String = "Could not publish the output file",
) {
    if (updatedRows == 0) throw IOException(message)
}

internal fun mediaStoreCollection(kind: OutputTarget.Kind, mimeType: String): Uri {
    val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
    return when (kind) {
        OutputTarget.Kind.Downloads -> MediaStore.Downloads.getContentUri(volume)
        OutputTarget.Kind.Music -> MediaStore.Audio.Media.getContentUri(volume)
        OutputTarget.Kind.Documents -> MediaStore.Files.getContentUri(volume)
        OutputTarget.Kind.Gallery, OutputTarget.Kind.Movies -> when {
            mimeType.startsWith("audio/") -> MediaStore.Audio.Media.getContentUri(volume)
            mimeType.startsWith("image/") -> MediaStore.Images.Media.getContentUri(volume)
            else -> MediaStore.Video.Media.getContentUri(volume)
        }
        OutputTarget.Kind.SafTree, OutputTarget.Kind.AppExternal ->
            throw IllegalArgumentException("not a MediaStore target")
    }
}

internal fun <T> mapSafExportErrors(
    message: String = "Could not write the output folder. Please pick it again.",
    block: () -> T,
): T =
    try {
        block()
    } catch (error: Exception) {
        throw IOException(message, error)
    }
