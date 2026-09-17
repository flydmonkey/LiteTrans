package com.videoconverter.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentTest {
    @Test
    fun classifiesExtensions() {
        assertEquals(DocumentSourceKind.Image, documentSourceKind("a.JPG"))
        assertEquals(DocumentSourceKind.Image, documentSourceKind("a.heic"))
        assertEquals(DocumentSourceKind.Pdf, documentSourceKind("scan.pdf"))
        assertEquals(DocumentSourceKind.Word, documentSourceKind("a.docx"))
        assertEquals(DocumentSourceKind.Excel, documentSourceKind("a.xlsx"))
        assertNull(documentSourceKind("a.doc"))
        assertNull(documentSourceKind("a.wps"))
        assertTrue(unsupportedDocumentReason("old.doc")!!.contains("not supported"))
    }

    @Test
    fun sameKindAllowsBatchRejectsMix() {
        assertTrue(sameDocumentKind(listOf("a.pdf", "b.pdf"), "c.pdf"))
        assertFalse(sameDocumentKind(listOf("a.pdf"), "note.docx"))
        assertTrue(sameDocumentKind(emptyList(), "x.png"))
    }

    @Test
    fun clampsPageRange() {
        assertEquals(1 to 3, clampPageRange(0, 99, 3))
        assertEquals(2 to 2, clampPageRange(2, 2, 5))
        assertEquals(1 to 1, clampPageRange(8, 1, 1))
    }

    @Test
    fun defaultsAndExtensions() {
        assertEquals("image-jpg", defaultDocumentPreset(DocumentSourceKind.Image))
        assertEquals("pdf-image", defaultDocumentPreset(DocumentSourceKind.Pdf))
        assertEquals("office-pdf", defaultDocumentPreset(DocumentSourceKind.Word))
        assertEquals("jpg", documentExtension("image-jpg", null))
        assertEquals("png", documentExtension("pdf-image", "png"))
        assertEquals("pdf", documentExtension("pdf-split", null))
        assertEquals("txt", documentExtension("pdf-txt", null))
        assertTrue(documentResultIsImage("pdf-image"))
        assertTrue(documentResultIsImage("image-compress"))
        assertFalse(documentResultIsImage("office-pdf"))
    }

    @Test
    fun numberedNamesOnlyWhenMultiple() {
        assertEquals("clip.jpg", documentOutputFileName("clip", 1, 1, "jpg"))
        assertEquals("clip-001.jpg", documentOutputFileName("clip", 1, 12, "jpg"))
        assertEquals("clip-012.jpg", documentOutputFileName("clip", 12, 12, "jpg"))
    }
}
