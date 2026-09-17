package com.videoconverter.android.document

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.videoconverter.android.domain.clampPageRange
import com.videoconverter.android.domain.documentOutputFileName
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

fun pdfImageMaxEdge(quality: String): Int = when (quality) {
    "high" -> 1600
    "small" -> 800
    else -> 1200
}

fun pdfPageCount(file: File): Int = openReadablePdf(file).use { it.numberOfPages }

fun assertPdfReadable(file: File) {
    openReadablePdf(file).close()
}

fun extractPdfText(file: File, start: Int, end: Int): String = openReadablePdf(file).use { document ->
    val (lo, hi) = clampPageRange(start, end, document.numberOfPages)
    val stripper = PDFTextStripper()
    stripper.startPage = lo
    stripper.endPage = hi
    val text = stripper.getText(document)
    if (text.trim().isEmpty()) error("没有可提取的文字")
    text
}

fun splitPdf(file: File, start: Int, end: Int, destDir: File, stem: String): List<File> {
    destDir.mkdirs()
    return openReadablePdf(file).use { document ->
        val (lo, hi) = clampPageRange(start, end, document.numberOfPages)
        val total = hi - lo + 1
        (lo..hi).map { pageNumber ->
            val dest = File(destDir, documentOutputFileName(stem, pageNumber - lo + 1, total, "pdf"))
            PDDocument().use { out ->
                out.importPage(document.getPage(pageNumber - 1))
                out.save(dest)
            }
            dest
        }
    }
}

fun compressPdf(file: File, quality: String, dest: File) {
    openReadablePdf(file).use { document ->
        val maxEdge = pdfImageMaxEdge(quality)
        val jpegQuality = when (quality) {
            "high" -> 0.85f
            "small" -> 0.5f
            else -> 0.7f
        }
        for (page in document.pages) {
            compressPageImages(document, page.resources, maxEdge, jpegQuality)
        }
        document.save(dest)
    }
}

fun renderPdfPage(file: File, pageIndex0: Int, maxEdge: Int): Bitmap {
    assertPdfReadable(file)
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
        PdfRenderer(pfd).use { renderer ->
            renderer.openPage(pageIndex0).use { page ->
                val srcW = page.width.coerceAtLeast(1)
                val srcH = page.height.coerceAtLeast(1)
                val scale = maxEdge.toFloat() / max(srcW, srcH)
                val width = (srcW * scale).roundToInt().coerceAtLeast(1)
                val height = (srcH * scale).roundToInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap
            }
        }
    }
}

private fun openReadablePdf(file: File): PDDocument {
    val document = try {
        PDDocument.load(file)
    } catch (e: InvalidPasswordException) {
        throw IllegalStateException("不支持加密 PDF", e)
    }
    if (document.isEncrypted) {
        document.close()
        error("不支持加密 PDF")
    }
    return document
}

private fun compressPageImages(
    document: PDDocument,
    resources: PDResources?,
    maxEdge: Int,
    jpegQuality: Float,
) {
    if (resources == null) return
    for (name in resources.xObjectNames) {
        val image = resources.getXObject(name) as? PDImageXObject ?: continue
        val width = image.width
        val height = image.height
        if (width <= maxEdge && height <= maxEdge) continue
        val scale = maxEdge.toFloat() / max(width, height)
        val newWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val newHeight = (height * scale).roundToInt().coerceAtLeast(1)
        val source = image.image
        val scaled = Bitmap.createScaledBitmap(source, newWidth, newHeight, true)
        resources.put(name, JPEGFactory.createFromImage(document, scaled, jpegQuality))
    }
}
