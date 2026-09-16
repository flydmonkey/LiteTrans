package com.videoconverter.android.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.videoconverter.android.domain.allocateOutputPath
import com.videoconverter.android.domain.partialOutputPath
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun defaultRelativePath(): String = "Download/轻转码"

data class OutputTarget(
    val kind: Kind,
    val treeUri: String? = null,
) {
    enum class Kind { Downloads, SafTree, AppExternal }
}

data class JobOutput(
    val partial: File,
    val final: File,
)

data class ExportedOutput(
    val location: String,
    val target: OutputTarget,
)

fun uniqueDisplayName(stem: String, ext: String, existing: Set<String>): String {
    val candidate = "$stem.$ext"
    if (candidate !in existing) return candidate

    var index = 1
    while ("$stem-$index.$ext" in existing) {
        index++
    }
    return "$stem-$index.$ext"
}

class OutputStore(
    private val context: Context,
    private val sessionStore: SessionStore = SessionStore(context),
) {
    fun createJobOutput(jobId: String, stem: String, ext: String): JobOutput {
        val jobDir = File(context.filesDir, "jobs/${safeSegment(jobId, "job")}").apply {
            if (!exists() && !mkdirs()) throw IOException("无法创建转码临时目录")
        }
        val final = File(jobDir, "${safeSegment(stem, "output")}.${safeExtension(ext)}")
        val partial = File(partialOutputPath(final.absolutePath))
        return JobOutput(partial = partial, final = final)
    }

    fun finalizeJobOutput(output: JobOutput): File {
        if (!output.partial.isFile) throw IOException("转码临时文件不存在")
        try {
            Files.move(
                output.partial.toPath(),
                output.final.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: IOException) {
            throw IOException("无法完成转码输出", error)
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
        require(source.isFile) { "转码输出不存在" }
        when (target.kind) {
            OutputTarget.Kind.Downloads -> exportToDownloadsOrFallback(source, stem, ext, mimeType)
            OutputTarget.Kind.SafTree -> exportToSaf(source, stem, ext, mimeType, target)
            OutputTarget.Kind.AppExternal -> exportToAppExternal(source, stem, ext)
        }
    }

    private suspend fun exportToDownloadsOrFallback(
        source: File,
        stem: String,
        ext: String,
        mimeType: String,
    ): ExportedOutput {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val displayName = try {
            uniqueDisplayName(stem, ext, mediaStoreNames(collection))
        } catch (_: Exception) {
            return exportToAppExternal(source, stem, ext)
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "${defaultRelativePath()}/")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val destination = try {
            resolver.insert(collection, values)
        } catch (_: Exception) {
            null
        } ?: return exportToAppExternal(source, stem, ext)

        try {
            resolver.openOutputStream(destination, "w").use { output ->
                requireNotNull(output) { "无法写入输出文件" }
                source.inputStream().use { input -> input.copyTo(output) }
            }
            requireMediaStorePublished(
                resolver.update(
                    destination,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null,
                    null,
                ),
            )
        } catch (error: Exception) {
            resolver.delete(destination, null, null)
            throw IOException("无法写入输出文件", error)
        }
        return ExportedOutput(
            location = destination.toString(),
            target = OutputTarget(OutputTarget.Kind.Downloads),
        )
    }

    private fun mediaStoreNames(collection: Uri): Set<String> {
        val names = mutableSetOf<String>()
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Downloads.DISPLAY_NAME),
            "${MediaStore.Downloads.RELATIVE_PATH} = ?",
            arrayOf("${defaultRelativePath()}/"),
            null,
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
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
    ): ExportedOutput = mapSafExportErrors {
        val uri = target.treeUri?.let(Uri::parse) ?: throw outputDirectoryError()
        if (!hasWritePermission(uri)) throw outputDirectoryError()
        val tree = DocumentFile.fromTreeUri(context, uri)
            ?.takeIf { it.isDirectory && it.canWrite() }
            ?: throw outputDirectoryError()
        val displayName = uniqueDisplayName(stem, ext, tree.listFiles().mapNotNull { it.name }.toSet())
        val destination = tree.createFile(mimeType, displayName) ?: throw outputDirectoryError()
        try {
            context.contentResolver.openOutputStream(destination.uri, "w").use { output ->
                requireNotNull(output) { "无法写入输出目录，请重新选择" }
                source.inputStream().use { input -> input.copyTo(output) }
            }
        } catch (error: Exception) {
            destination.delete()
            throw IOException("无法写入输出目录，请重新选择", error)
        }
        ExportedOutput(destination.uri.toString(), target)
    }

    private suspend fun exportToAppExternal(
        source: File,
        stem: String,
        ext: String,
    ): ExportedOutput {
        val root = context.getExternalFilesDir(null) ?: throw IOException("无法写入应用输出目录")
        val outputDir = File(root, "轻转码").apply {
            if (!exists() && !mkdirs()) throw IOException("无法创建应用输出目录")
        }
        val outputPath = allocateOutputPath(outputDir.absolutePath, stem, ext) { File(it).exists() }
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
}

private fun safeSegment(value: String, fallback: String): String =
    value.replace(Regex("""[\\/\u0000-\u001f]"""), "_").ifBlank { fallback }

private fun safeExtension(value: String): String =
    value.trimStart('.').replace(Regex("""[^A-Za-z0-9]"""), "").ifBlank { "bin" }

private fun outputDirectoryError(): IOException =
    IOException("无法写入输出目录，请重新选择")

internal fun requireMediaStorePublished(updatedRows: Int) {
    if (updatedRows == 0) throw IOException("无法发布输出文件")
}

internal fun <T> mapSafExportErrors(block: () -> T): T =
    try {
        block()
    } catch (error: Exception) {
        throw IOException("无法写入输出目录，请重新选择", error)
    }
