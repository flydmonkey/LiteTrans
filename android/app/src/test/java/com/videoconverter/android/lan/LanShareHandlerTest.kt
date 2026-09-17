package com.videoconverter.android.lan

import com.videoconverter.android.domain.JobStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanShareHandlerTest {
    private val jobs = listOf(
        job("v", JobStatus.Completed, "/tmp/v.mp4", listOf("/tmp/v.mp4"), "假期.mp4"),
        job("q", JobStatus.Queued, null, emptyList(), "wait.mp4"),
    )
    private val exists = { path: String -> path == "/tmp/v.mp4" }
    private val copy = englishLanHistoryCopy()

    @Test
    fun postIsMethodNotAllowed() {
        val res = handleLanRequest(LanHttpRequest("POST", "/", emptyMap()), jobs, "", exists, copy)
        assertEquals(405, res.status)
    }

    @Test
    fun tokenRequiredWhenSet() {
        val denied = handleLanRequest(LanHttpRequest("GET", "/", emptyMap()), jobs, "pw", exists, copy)
        assertEquals(401, denied.status)
        assertEquals("Password required", String(denied.body, Charsets.UTF_8))
        assertFalse(String(denied.body, Charsets.UTF_8).contains("假期.mp4"))
        val ok = handleLanRequest(LanHttpRequest("GET", "/", mapOf("k" to "pw")), jobs, "pw", exists, copy)
        assertEquals(200, ok.status)
        assertTrue(ok.contentType.startsWith("text/html"))
        assertTrue(String(ok.body, Charsets.UTF_8).contains("假期.mp4"))
    }

    @Test
    fun downloadAndUnknown() {
        val file = handleLanRequest(LanHttpRequest("GET", "/d/v", mapOf("k" to "pw")), jobs, "pw", exists, copy)
        assertEquals(200, file.status)
        assertEquals("/tmp/v.mp4", file.filePath)
        assertTrue(file.headers["Content-Disposition"]!!.contains("attachment"))
        assertEquals(404, handleLanRequest(LanHttpRequest("GET", "/d/q", emptyMap()), jobs, "", exists, copy).status)
        assertEquals(404, handleLanRequest(LanHttpRequest("GET", "/nope", emptyMap()), jobs, "", exists, copy).status)
        assertEquals(404, handleLanRequest(LanHttpRequest("GET", "/d/v/9", emptyMap()), jobs, "", exists, copy).status)
    }

    @Test
    fun mediaIsInlineAndHeadOmitsBodyFlag() {
        val get = handleLanRequest(LanHttpRequest("GET", "/m/v", mapOf("k" to "pw")), jobs, "pw", exists, copy)
        assertEquals(200, get.status)
        assertEquals("/tmp/v.mp4", get.filePath)
        assertTrue(get.headers["Content-Disposition"]!!.startsWith("inline;"))
        assertEquals("bytes", get.headers["Accept-Ranges"])
        assertTrue(get.sendBody)

        val head = handleLanRequest(LanHttpRequest("HEAD", "/m/v", mapOf("k" to "pw")), jobs, "pw", exists, copy)
        assertEquals(200, head.status)
        assertEquals("/tmp/v.mp4", head.filePath)
        assertFalse(head.sendBody)

        val ranged = handleLanRequest(
            LanHttpRequest("GET", "/m/v", mapOf("k" to "pw"), headers = mapOf("range" to "bytes=0-1")),
            jobs, "pw", exists, copy,
        )
        assertEquals("bytes=0-1", ranged.rangeHeader)
    }

    @Test
    fun parseQueryDecodesK() {
        assertEquals("a b", parseLanQuery("k=a+b")["k"])
    }
}
