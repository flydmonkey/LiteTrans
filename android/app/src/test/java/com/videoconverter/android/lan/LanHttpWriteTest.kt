package com.videoconverter.android.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanHttpWriteTest {
    private val base = LanHttpResponse(
        status = 200,
        contentType = "video/mp4",
        body = ByteArray(0),
        headers = mapOf("Content-Disposition" to "inline; filename=\"a.mp4\"", "Accept-Ranges" to "bytes"),
        filePath = "/tmp/a.mp4",
        rangeHeader = null,
        sendBody = true,
    )

    @Test
    fun wholeFileKeeps200() {
        val out = applyLanResponseRange(base, 100)
        assertEquals(200, out.status)
        assertEquals("100", out.headers["Content-Length"])
        assertEquals(0L, out.byteStart)
        assertEquals(null, out.byteLength)
    }

    @Test
    fun partialIs206() {
        val out = applyLanResponseRange(base.copy(rangeHeader = "bytes=0-9"), 100)
        assertEquals(206, out.status)
        assertEquals("bytes 0-9/100", out.headers["Content-Range"])
        assertEquals("10", out.headers["Content-Length"])
        assertEquals(0L, out.byteStart)
        assertEquals(10L, out.byteLength)
    }

    @Test
    fun badRangeIs416() {
        val out = applyLanResponseRange(base.copy(rangeHeader = "bytes=500-600"), 100)
        assertEquals(416, out.status)
        assertEquals("bytes */100", out.headers["Content-Range"])
        assertNull(out.filePath)
        assertEquals(false, out.sendBody)
    }

    @Test
    fun openFailureBecomes404BeforeHeaders() {
        val failed = lanReadyFileResponse(base, opened = false)
        assertEquals(404, failed.status)
        assertEquals("text/plain; charset=utf-8", failed.contentType)
        assertEquals("Not Found", String(failed.body, Charsets.UTF_8))
        assertNull(failed.filePath)
        assertEquals(true, failed.sendBody)

        val headFailed = lanReadyFileResponse(base.copy(sendBody = false), opened = false)
        assertEquals(404, headFailed.status)
        assertEquals(false, headFailed.sendBody)

        val kept = lanReadyFileResponse(base.copy(status = 206), opened = true)
        assertEquals(206, kept.status)
        assertEquals("/tmp/a.mp4", kept.filePath)

        val memory = lanReadyFileResponse(base.copy(filePath = null, body = "ok".toByteArray()), opened = false)
        assertEquals(200, memory.status)
        assertEquals("ok", String(memory.body, Charsets.UTF_8))
    }

    @Test
    fun parsesRangeHeaderLine() {
        val headers = parseLanHeaderLines(listOf("Range: bytes=0-1"))
        assertEquals("bytes=0-1", headers["range"])
    }
}
