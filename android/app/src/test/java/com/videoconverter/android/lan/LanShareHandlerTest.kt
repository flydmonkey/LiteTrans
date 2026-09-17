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

    @Test
    fun postIsMethodNotAllowed() {
        val res = handleLanRequest(LanHttpRequest("POST", "/", emptyMap()), jobs, "", exists)
        assertEquals(405, res.status)
    }

    @Test
    fun tokenRequiredWhenSet() {
        val denied = handleLanRequest(LanHttpRequest("GET", "/", emptyMap()), jobs, "pw", exists)
        assertEquals(401, denied.status)
        assertFalse(String(denied.body, Charsets.UTF_8).contains("假期.mp4"))
        val ok = handleLanRequest(LanHttpRequest("GET", "/", mapOf("k" to "pw")), jobs, "pw", exists)
        assertEquals(200, ok.status)
        assertTrue(ok.contentType.startsWith("text/html"))
        assertTrue(String(ok.body, Charsets.UTF_8).contains("假期.mp4"))
    }

    @Test
    fun downloadAndUnknown() {
        val file = handleLanRequest(LanHttpRequest("GET", "/d/v", mapOf("k" to "pw")), jobs, "pw", exists)
        assertEquals(200, file.status)
        assertEquals("/tmp/v.mp4", file.filePath)
        assertTrue(file.headers["Content-Disposition"]!!.contains("attachment"))
        assertEquals(404, handleLanRequest(LanHttpRequest("GET", "/d/q", emptyMap()), jobs, "", exists).status)
        assertEquals(404, handleLanRequest(LanHttpRequest("GET", "/nope", emptyMap()), jobs, "", exists).status)
        assertEquals(404, handleLanRequest(LanHttpRequest("GET", "/d/v/9", emptyMap()), jobs, "", exists).status)
    }

    @Test
    fun parseQueryDecodesK() {
        assertEquals("a b", parseLanQuery("k=a+b")["k"])
    }
}
