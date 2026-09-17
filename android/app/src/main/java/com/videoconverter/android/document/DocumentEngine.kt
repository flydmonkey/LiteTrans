package com.videoconverter.android.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.videoconverter.android.R
import com.videoconverter.android.data.OutputStore
import com.videoconverter.android.data.OutputTarget
import com.videoconverter.android.data.SourceAccess
import com.videoconverter.android.domain.DocumentSourceKind
import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.clampPageRange
import com.videoconverter.android.domain.documentExtension
import com.videoconverter.android.domain.documentOutputFileName
import com.videoconverter.android.domain.documentSourceKind
import com.videoconverter.android.domain.isDocumentPreset
import com.videoconverter.android.domain.sourceStem
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun shouldRunDocumentEngine(preset: String): Boolean = isDocumentPreset(preset)

fun planDocumentOutputs(job: Job): List<Pair<String, String>> {
    val ext = documentExtension(job.config.preset, job.config.container)
    val stem = sourceStem(job.displayName)
    val total = plannedOutputCount(job)
    return (1..total).map { index ->
        val fileName = documentOutputFileName(stem, index, total, ext)
        fileName.substringBeforeLast('.') to ext
    }
}

class DocumentEngine(
    context: Context,
    private val sourceAccess: SourceAccess = SourceAccess(context),
    private val outputStore: OutputStore = OutputStore(context),
) {
    private val app = context.applicationContext
    private val cancelledIds = mutableSetOf<String>()
    private val cancelLock = Any()

    init {
        PDFBoxResourceLoader.init(context)
    }

    fun cancel(jobId: String) {
        synchronized(cancelLock) { cancelledIds += jobId }
    }

    fun wasCancelled(jobId: String): Boolean = synchronized(cancelLock) {
        jobId in cancelledIds
    }

    suspend fun convert(
        job: Job,
        target: OutputTarget,
        onProgress: (Double) -> Unit = {},
    ): Job = withContext(Dispatchers.IO) {
        var destDir: File? = null
        try {
            if (wasCancelled(job.id)) {
                return@withContext job.copy(status = JobStatus.Cancelled, error = null)
            }

            val uri = Uri.parse(job.sourceUri)
            val source = sourceAccess.cachedInput(uri) ?: sourceAccess.copyToCache(uri)
            val sourceFile = File(source.ffmpegPath)
            val planned = planDocumentOutputs(job)
            val first = planned.first()
            destDir = outputStore.createJobOutput(job.id, first.first, first.second).partial.parentFile
            val produced = produceOutputs(job, sourceFile, requireNotNull(destDir)) { index, total ->
                if (wasCancelled(job.id)) {
                    throw ConvertCancelled()
                }
                onProgress(index.toDouble() / total * 100.0)
            }

            val locations = produced.map { file ->
                if (wasCancelled(job.id)) {
                    throw ConvertCancelled()
                }
                outputStore.export(
                    source = file,
                    stem = file.nameWithoutExtension,
                    ext = file.extension,
                    mimeType = documentMimeType(file.extension),
                    target = target,
                ).location
            }

            job.copy(
                status = JobStatus.Completed,
                progress = 100.0,
                error = null,
                outputPath = locations.first(),
                outputPaths = locations,
            )
        } catch (_: ConvertCancelled) {
            job.copy(status = JobStatus.Cancelled, error = null)
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalStateException) {
            if (wasCancelled(job.id)) {
                job.copy(status = JobStatus.Cancelled, error = null)
            } else {
                job.copy(status = JobStatus.Failed, error = error.message)
            }
        } catch (error: Exception) {
            if (wasCancelled(job.id)) {
                job.copy(status = JobStatus.Cancelled, error = null)
            } else {
                job.copy(
                    status = JobStatus.Failed,
                    error = error.message ?: app.getString(R.string.error_convert_failed),
                )
            }
        } finally {
            destDir?.deleteRecursively()
            synchronized(cancelLock) { cancelledIds -= job.id }
        }
    }

    private fun produceOutputs(
        job: Job,
        source: File,
        destDir: File,
        onItem: (index: Int, total: Int) -> Unit,
    ): List<File> {
        destDir.mkdirs()
        val planned = planDocumentOutputs(job)
        val total = planned.size
        val stem = sourceStem(job.displayName)
        val quality = job.config.quality ?: "original"
        val sourceExt = source.extension.ifBlank {
            job.displayName.substringAfterLast('.').lowercase()
        }.lowercase()

        return when (val preset = job.config.preset) {
            "pdf-split" -> {
                val (start, end) = pageRange(job, source)
                splitPdf(
                    source,
                    start,
                    end,
                    destDir,
                    stem,
                    shouldCancel = { wasCancelled(job.id) },
                    cancelled = app.getString(R.string.error_cancelled),
                    encryptedPdf = app.getString(R.string.error_encrypted_pdf),
                ).also { files ->
                    files.indices.forEach { onItem(it + 1, total) }
                }
            }
            "pdf-txt" -> {
                val (start, end) = pageRange(job, source)
                val dest = plannedFile(destDir, planned.first())
                dest.writeText(
                    extractPdfText(
                        source,
                        start,
                        end,
                        noText = app.getString(R.string.error_no_extractable_text),
                        encryptedPdf = app.getString(R.string.error_encrypted_pdf),
                    ),
                )
                onItem(1, total)
                listOf(dest)
            }
            "pdf-compress" -> {
                val (start, end) = pageRange(job, source)
                val dest = plannedFile(destDir, planned.first())
                compressPdf(
                    source,
                    quality,
                    dest,
                    start,
                    end,
                    encryptedPdf = app.getString(R.string.error_encrypted_pdf),
                )
                onItem(1, total)
                listOf(dest)
            }
            "pdf-image" -> {
                val (start, end) = pageRange(job, source)
                val ext = planned.first().second
                val maxEdge = pdfImageMaxEdge(quality)
                val jpegQuality = imageCompressFormat("image-$ext", ext, quality).second
                (start..end).mapIndexed { index, page ->
                    val dest = plannedFile(destDir, planned[index])
                    val bitmap = renderPdfPage(
                        source,
                        page - 1,
                        maxEdge,
                        encryptedPdf = app.getString(R.string.error_encrypted_pdf),
                    )
                    dest.outputStream().use { encodeBitmap(bitmap, ext, jpegQuality, it) }
                    onItem(index + 1, total)
                    dest
                }
            }
            "office-pdf" -> {
                val dest = plannedFile(destDir, planned.first())
                val bytes = source.readBytes()
                val blocks = when (documentSourceKind(job.displayName) ?: documentSourceKind(source.name)) {
                    DocumentSourceKind.Word -> officeBlocksFromDocx(
                        bytes,
                        app.getString(R.string.error_cannot_convert_document),
                    )
                    DocumentSourceKind.Excel -> officeBlocksFromXlsx(
                        bytes,
                        app.getString(R.string.error_cannot_convert_document),
                    )
                    else -> error(app.getString(R.string.error_cannot_convert_document))
                }
                writeOfficePdf(blocks, dest, app.getString(R.string.error_cannot_convert_document))
                onItem(1, total)
                listOf(dest)
            }
            else -> {
                val (ext, jpegQuality) = imageCompressFormat(preset, sourceExt, quality)
                val dest = File(destDir, "${sourceStem(job.displayName)}.$ext")
                val bitmap = decodeSourceBitmap(source, app.getString(R.string.error_cannot_read_image))
                val scale = if (ext == "png" || ext == "bmp") {
                    scaleForQuality(quality, preset)
                } else {
                    1f
                }
                val scaled = scaleBitmap(bitmap, scale)
                dest.outputStream().use { encodeBitmap(scaled, ext, jpegQuality, it) }
                onItem(1, total)
                listOf(dest)
            }
        }
    }
}

private class ConvertCancelled : RuntimeException()

private fun plannedOutputCount(job: Job): Int {
    val preset = job.config.preset
    if (
        preset == "pdf-txt" ||
        preset == "pdf-compress" ||
        preset == "office-pdf" ||
        preset.startsWith("image-")
    ) {
        return 1
    }
    val pageCount = job.media.pageCount ?: 1
    val (start, end) = clampPageRange(
        job.media.pageStart ?: 1,
        job.media.pageEnd ?: pageCount,
        pageCount,
    )
    return end - start + 1
}

private fun pageRange(job: Job, source: File): Pair<Int, Int> {
    val pageCount = job.media.pageCount ?: pdfPageCount(source)
    return clampPageRange(
        job.media.pageStart ?: 1,
        job.media.pageEnd ?: pageCount,
        pageCount,
    )
}

private fun plannedFile(destDir: File, planned: Pair<String, String>): File =
    File(destDir, "${planned.first}.${planned.second}")

private fun documentMimeType(ext: String): String = when (ext.lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    "gif" -> "image/gif"
    "pdf" -> "application/pdf"
    "txt" -> "text/plain"
    else -> "application/octet-stream"
}

private fun decodeSourceBitmap(file: File, cannotReadImage: String): Bitmap {
    BitmapFactory.decodeFile(file.absolutePath)?.let { return it }
    return try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file))
    } catch (error: Exception) {
        throw IllegalStateException(cannotReadImage, error)
    }
}

private fun scaleBitmap(bitmap: Bitmap, scale: Float): Bitmap {
    if (scale >= 1f) return bitmap
    val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
    val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, width, height, true)
}
