package com.videoconverter.android.data

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ResolvedInput(
    val ffmpegPath: String,
    val pfd: ParcelFileDescriptor?,
)

class SourceAccess(private val context: Context) {
    suspend fun resolveInput(uri: Uri): ResolvedInput = withContext(Dispatchers.IO) {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("无法读取源文件")
        ResolvedInput(
            ffmpegPath = "/proc/self/fd/${pfd.fd}",
            pfd = pfd,
        )
    }

    fun cachedInput(uri: Uri): ResolvedInput? {
        val cached = cacheFile(uri)
        return if (cached.isFile) {
            ResolvedInput(ffmpegPath = cached.absolutePath, pfd = null)
        } else {
            null
        }
    }

    suspend fun copyToCache(uri: Uri, opened: ResolvedInput? = null): ResolvedInput =
        withContext(Dispatchers.IO) {
            opened?.pfd?.close()

            val cacheFile = cacheFile(uri)
            val sourceDir = requireNotNull(cacheFile.parentFile).apply { mkdirs() }
            val temporary = File(sourceDir, "${cacheFile.name}.partial")

            try {
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "无法读取源文件" }
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
                if (cacheFile.exists() && !cacheFile.delete()) {
                    throw IOException("无法更新源文件缓存")
                }
                if (!temporary.renameTo(cacheFile)) {
                    throw IOException("无法写入源文件缓存")
                }
            } catch (error: IOException) {
                temporary.delete()
                if (error.isDiskFull()) throw IOException("缓存空间不足", error)
                throw error
            } catch (error: IllegalArgumentException) {
                temporary.delete()
                throw IOException(error.message ?: "无法读取源文件", error)
            }

            ResolvedInput(ffmpegPath = cacheFile.absolutePath, pfd = null)
        }

    private fun cacheFile(uri: Uri): File =
        File(File(context.cacheDir, "sources"), "${uriHash(uri)}-${displayName(uri)}")

    private fun displayName(uri: Uri): String {
        val queried = context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use(Cursor::readDisplayName)
        val fallback = uri.lastPathSegment?.substringAfterLast('/') ?: "source"
        return (queried ?: fallback)
            .replace(Regex("""[\\/\u0000-\u001f]"""), "_")
            .ifBlank { "source" }
    }

    private fun uriHash(uri: Uri): String =
        MessageDigest.getInstance("SHA-256")
            .digest(uri.toString().toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }
}

private fun Cursor.readDisplayName(): String? {
    if (!moveToFirst()) return null
    val index = getColumnIndex(OpenableColumns.DISPLAY_NAME)
    return if (index >= 0 && !isNull(index)) getString(index) else null
}

private fun Throwable.isDiskFull(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is ErrnoException && current.errno == OsConstants.ENOSPC) return true
        if (current.message?.contains("ENOSPC", ignoreCase = true) == true) return true
        if (current.message?.contains("No space left", ignoreCase = true) == true) return true
        current = current.cause
    }
    return false
}
