package com.videoconverter.android.document

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream

sealed class OfficeBlock {
    data class Paragraph(val text: String) : OfficeBlock()
    data class Table(val rows: List<List<String>>) : OfficeBlock()
}

fun officeBlocksFromDocx(bytes: ByteArray): List<OfficeBlock> = officeOrFail {
    XWPFDocument(ByteArrayInputStream(bytes)).use { document ->
        val blocks = mutableListOf<OfficeBlock>()
        document.paragraphs.map { it.text }.forEach { blocks.add(OfficeBlock.Paragraph(it)) }
        for (table in document.tables) {
            blocks.add(
                OfficeBlock.Table(
                    table.rows.map { row -> row.tableCells.map { cell -> cell.text.orEmpty() } },
                ),
            )
        }
        blocks.also { if (it.isEmpty()) error("无法转换此文档") }
    }
}

fun officeBlocksFromXlsx(bytes: ByteArray): List<OfficeBlock> = officeOrFail {
    XSSFWorkbook(ByteArrayInputStream(bytes)).use { workbook ->
        val sheet = workbook.getSheetAt(0)
        val formatter = DataFormatter()
        var lastCell = 0
        for (r in sheet.firstRowNum..sheet.lastRowNum) {
            val row = sheet.getRow(r) ?: continue
            lastCell = maxOf(lastCell, row.lastCellNum.toInt().coerceAtLeast(0))
        }
        val rows = (sheet.firstRowNum..sheet.lastRowNum).map { r ->
            val row = sheet.getRow(r)
            (0 until lastCell).map { c ->
                row?.getCell(c)?.let { formatter.formatCellValue(it) }.orEmpty()
            }
        }
        listOf(OfficeBlock.Table(rows)).also { if (sheet.physicalNumberOfRows == 0) error("无法转换此文档") }
    }
}

fun writeOfficePdf(blocks: List<OfficeBlock>, out: File) {
    if (blocks.isEmpty()) error("无法转换此文档")
    val pdf = PdfDocument()
    try {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = FONT_SIZE }
        val lineHeight = paint.fontSpacing.coerceAtLeast(14f)
        var pageNumber = 1
        var page = pdf.startPage(pageInfo(pageNumber))
        var y = MARGIN + paint.textSize

        fun newPage() {
            pdf.finishPage(page)
            pageNumber++
            page = pdf.startPage(pageInfo(pageNumber))
            y = MARGIN + paint.textSize
        }

        fun drawLine(text: String) {
            if (y > PAGE_HEIGHT - MARGIN) newPage()
            page.canvas.drawText(text, MARGIN, y, paint)
            y += lineHeight
        }

        for (block in blocks) {
            when (block) {
                is OfficeBlock.Paragraph -> drawLine(block.text)
                is OfficeBlock.Table -> block.rows.forEach { drawLine(it.joinToString("  ")) }
            }
            y += lineHeight / 2f
        }
        pdf.finishPage(page)
        FileOutputStream(out).use { pdf.writeTo(it) }
    } finally {
        pdf.close()
    }
}

private const val PAGE_WIDTH = 595
private const val PAGE_HEIGHT = 842
private const val MARGIN = 48f
private const val FONT_SIZE = 11f

private fun pageInfo(number: Int): PdfDocument.PageInfo =
    PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, number).create()

private inline fun <T> officeOrFail(block: () -> T): T = try {
    block()
} catch (e: IllegalStateException) {
    throw e
} catch (e: Exception) {
    throw IllegalStateException("无法转换此文档", e)
}
