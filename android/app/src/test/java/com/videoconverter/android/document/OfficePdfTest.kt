package com.videoconverter.android.document

import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLegacyCanvas
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** Robolectric 4.14 has no PdfDocument natives; startPage returns 0 without this. */
/** Robolectric 4.14 has no PdfDocument natives; startPage returns 0 without this. */
@Implements(PdfDocument::class)
class ShadowPdfDocument {
    @Implementation
    protected fun nativeCreateDocument(): Long = 1L

    @Implementation
    protected fun nativeClose(nativeDocument: Long) {
    }

    @Implementation
    protected fun nativeFinishPage(nativeDocument: Long) {
    }

    @Implementation
    protected fun nativeWriteTo(nativeDocument: Long, out: OutputStream, chunk: ByteArray) {
        out.write(MINIMAL_PDF)
    }

    companion object {
        private val MINIMAL_PDF = (
            "%PDF-1.4\n" +
                "1 0 obj<< /Type /Catalog /Pages 2 0 R >>endobj\n" +
                "2 0 obj<< /Type /Pages /Kids [3 0 R] /Count 1 >>endobj\n" +
                "3 0 obj<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] >>endobj\n" +
                "trailer<< /Root 1 0 R >>\n%%EOF\n"
            ).toByteArray()

        @JvmStatic
        @Implementation
        fun nativeStartPage(
            nativeDocument: Long,
            pageWidth: Int,
            pageHeight: Int,
            contentLeft: Int,
            contentTop: Int,
            contentRight: Int,
            contentBottom: Int,
        ): Long {
            val initRaster = ShadowLegacyCanvas::class.java.getDeclaredMethod(
                "nInitRaster",
                Bitmap::class.java,
            )
            initRaster.isAccessible = true
            return initRaster.invoke(null, null) as Long
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, shadows = [ShadowPdfDocument::class])
class OfficePdfTest {
    @Test
    fun docxBlocksIncludeParagraphAndTable() {
        val blocks = officeBlocksFromDocx(sampleDocx())
        assertTrue(blocks.any { it is OfficeBlock.Paragraph && it.text.contains("标题") })
        assertTrue(blocks.any { it is OfficeBlock.Table })
    }

    @Test
    fun writesNonEmptyPdf() {
        val dest = kotlin.io.path.createTempFile("o", ".pdf").toFile()
        writeOfficePdf(officeBlocksFromDocx(sampleDocx()), dest)
        assertTrue(dest.length() > 100)
    }

    @Test
    fun xlsxBlocksIncludeHeaderCells() {
        val blocks = officeBlocksFromXlsx(sampleXlsx())
        assertTrue(
            blocks.any { block ->
                block is OfficeBlock.Table &&
                    block.rows.any { row -> row.contains("姓名") && row.contains("数量") }
            },
        )
    }

    @Test
    fun emptyBlocksFail() {
        val dest = kotlin.io.path.createTempFile("e", ".pdf").toFile()
        try {
            writeOfficePdf(emptyList(), dest)
            org.junit.Assert.fail("expected")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Could not convert this document"))
        }
    }

    private fun sampleDocx(): ByteArray {
        val document = XWPFDocument()
        document.createParagraph().createRun().setText("标题")
        val table = document.createTable(1, 2)
        table.getRow(0).getCell(0).setText("左")
        table.getRow(0).getCell(1).setText("右")
        return ByteArrayOutputStream().use { out ->
            document.write(out)
            document.close()
            out.toByteArray()
        }
    }

    private fun sampleXlsx(): ByteArray {
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet()
        val row = sheet.createRow(0)
        row.createCell(0).setCellValue("姓名")
        row.createCell(1).setCellValue("数量")
        return ByteArrayOutputStream().use { out ->
            workbook.write(out)
            workbook.close()
            out.toByteArray()
        }
    }
}
