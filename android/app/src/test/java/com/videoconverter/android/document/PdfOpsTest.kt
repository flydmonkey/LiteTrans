package com.videoconverter.android.document

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PdfOpsTest {
    @Test
    fun splitKeepsTextAndNamesPages() {
        val src = writtenTwoPagePdf()
        val out = kotlin.io.path.createTempDirectory("split").toFile()
        val files = splitPdf(src, 1, 2, out, "clip")
        assertEquals(2, files.size)
        assertTrue(files[0].name.contains("001"))
        assertTrue(extractPdfText(files[0], 1, 1).contains("Hello"))
        assertTrue(extractPdfText(files[1], 1, 1).contains("World"))
    }

    @Test
    fun emptyTextFails() {
        val blank = writtenBlankPdf()
        try {
            extractPdfText(blank, 1, 1)
            org.junit.Assert.fail("expected")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("没有可提取的文字"))
        }
    }

    @Test
    fun compressStillHasExtractableText() {
        val src = writtenTwoPagePdf()
        val dest = kotlin.io.path.createTempFile("c", ".pdf").toFile()
        compressPdf(src, "small", dest)
        assertTrue(extractPdfText(dest, 1, 2).contains("Hello"))
    }

    @Test
    fun pdfImageMaxEdgeMatchesQuality() {
        assertEquals(1600, pdfImageMaxEdge("high"))
        assertEquals(1200, pdfImageMaxEdge("standard"))
        assertEquals(800, pdfImageMaxEdge("small"))
    }

    @Test
    fun pdfPageCountReadsTwoPages() {
        assertEquals(2, pdfPageCount(writtenTwoPagePdf()))
    }

    @Test
    fun encryptedPdfIsRejected() {
        val encrypted = writtenEncryptedPdf()
        try {
            assertPdfReadable(encrypted)
            org.junit.Assert.fail("expected")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("不支持加密 PDF"))
        }
    }

    private fun writtenTwoPagePdf(): File {
        val file = kotlin.io.path.createTempFile("two-page", ".pdf").toFile()
        PDDocument().use { document ->
            addTextPage(document, "Hello")
            addTextPage(document, "World")
            document.save(file)
        }
        return file
    }

    private fun writtenBlankPdf(): File {
        val file = kotlin.io.path.createTempFile("blank", ".pdf").toFile()
        PDDocument().use { document ->
            document.addPage(PDPage())
            document.save(file)
        }
        return file
    }

    private fun writtenEncryptedPdf(): File {
        val file = kotlin.io.path.createTempFile("encrypted", ".pdf").toFile()
        PDDocument().use { document ->
            addTextPage(document, "Secret")
            val policy = StandardProtectionPolicy("owner", "", AccessPermission())
            policy.encryptionKeyLength = 128
            document.protect(policy)
            document.save(file)
        }
        return file
    }

    private fun addTextPage(document: PDDocument, text: String) {
        val page = PDPage()
        document.addPage(page)
        PDPageContentStream(document, page).use { stream ->
            stream.beginText()
            stream.setFont(PDType1Font.HELVETICA, 12f)
            stream.newLineAtOffset(72f, 700f)
            stream.showText(text)
            stream.endText()
        }
    }
}
