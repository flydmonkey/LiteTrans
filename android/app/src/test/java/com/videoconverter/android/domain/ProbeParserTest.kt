package com.videoconverter.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeParserTest {
    @Test
    fun parsesVideoAndAudio() {
        val json = """
            {
              "format": {
                "format_name": "mov,mp4,m4a,3gp,3g2,mj2",
                "duration": "12.5"
              },
              "streams": [
                {
                  "codec_type": "video",
                  "codec_name": "h264",
                  "width": 1920,
                  "height": 1080,
                  "r_frame_rate": "30/1"
                },
                {
                  "codec_type": "audio",
                  "codec_name": "aac",
                  "channels": 2
                }
              ]
            }
        """.trimIndent()

        val info = parseFfprobeJson(
            sourceUri = "content://media/external/video/42",
            displayName = "a.mp4",
            json = json,
        )

        assertEquals("content://media/external/video/42", info.sourceUri)
        assertEquals("a.mp4", info.displayName)
        assertTrue(info.importable)
        assertEquals("mov,mp4,m4a,3gp,3g2,mj2", info.container)
        assertEquals("h264", info.videoCodec)
        assertEquals(1920, info.width)
        assertEquals(1080, info.height)
        assertEquals(30.0, info.frameRate!!, 0.001)
        assertEquals("aac", info.audioCodec)
        assertEquals(2, info.channels)
        assertEquals(12.5, info.durationSecs!!, 0.001)
        assertNull(info.error)
    }

    @Test
    fun rejectsMissingStreams() {
        val info = parseFfprobeJson(
            sourceUri = "content://media/external/file/7",
            displayName = "a.bin",
            json = """{"format":{"format_name":"data"},"streams":[]}""",
        )

        assertFalse(info.importable)
        assertTrue(info.error!!.contains("没有可转码"))
    }

    @Test
    fun invalidJsonIsUnreadable() {
        val info = parseFfprobeJson("content://broken", "broken.mp4", "{")

        assertEquals("content://broken", info.sourceUri)
        assertEquals("broken.mp4", info.displayName)
        assertFalse(info.importable)
        assertEquals("无法解析媒体信息", info.error)
    }

    @Test
    fun unreadableKeepsSourceIdentityAndReason() {
        val info = unreadable("content://missing", "missing.mov", "无法读取")

        assertEquals("content://missing", info.sourceUri)
        assertEquals("missing.mov", info.displayName)
        assertFalse(info.importable)
        assertEquals("无法读取", info.error)
    }
}
