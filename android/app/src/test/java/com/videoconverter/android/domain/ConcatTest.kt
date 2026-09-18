package com.videoconverter.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcatTest {
    private fun first() = MediaInfo(
        sourceUri = "file:///a.mp4",
        displayName = "holiday.MOV",
        durationSecs = 3.0,
        videoCodec = "h264",
        width = 1920,
        height = 1080,
        frameRate = 30.0,
        audioCodec = "aac",
        importable = true,
    )

    private fun silentPortrait() = MediaInfo(
        sourceUri = "file:///b.mp4",
        displayName = "b.mp4",
        durationSecs = 2.0,
        videoCodec = "hevc",
        width = 1080,
        height = 1920,
        importable = true,
    )

    @Test
    fun mergedStemUsesFirstName() {
        assertEquals("holiday-merged", concatOutputStem("holiday.MOV"))
    }

    @Test
    fun targetEvenizesAndDefaultsFps() {
        val target = concatTarget(
            first().copy(width = 1281, height = 721, frameRate = null),
        )
        assertEquals(1280, target.width)
        assertEquals(720, target.height)
        assertEquals(30.0, target.frameRate, 0.0)
    }

    @Test
    fun normalizeScalesToFirstAndPads() {
        val args = buildConcatNormalizeArgs(
            input = "/in/b.mp4",
            outputPartial = "/tmp/clip-001.partial.mp4",
            media = silentPortrait(),
            target = concatTarget(first()),
            quality = "standard",
            preferHardware = false,
        )
        assertTrue(args.contains("file:/in/b.mp4"))
        assertTrue(args.contains("-vf"))
        val filter = args[args.indexOf("-vf") + 1]
        assertTrue(filter.contains("1920:1080"))
        assertTrue(filter.contains("force_original_aspect_ratio=decrease"))
        assertTrue(filter.contains("pad=1920:1080"))
        assertTrue(args.contains("libx264"))
        assertTrue(args.contains("yuv420p"))
        assertTrue(args.contains("anullsrc=channel_layout=stereo:sample_rate=48000"))
        assertTrue(args.contains("aac"))
        assertTrue(args.contains("48000"))
    }

    @Test
    fun normalizeKeepsExistingAudio() {
        val args = buildConcatNormalizeArgs(
            input = "/in/a.mp4",
            outputPartial = "/tmp/a.partial.mp4",
            media = first(),
            target = concatTarget(first()),
            quality = "original",
            preferHardware = false,
        )
        assertFalse(args.any { it.contains("anullsrc") })
        assertTrue(args.contains("aac"))
    }

    @Test
    fun joinUsesConcatDemuxerCopy() {
        val args = buildConcatJoinArgs(
            listPath = "/tmp/list.txt",
            outputPartial = "/out/a-merged.partial.mp4",
        )
        assertTrue(args.contains("-f"))
        assertTrue(args.contains("concat"))
        assertTrue(args.contains("-c"))
        assertTrue(args.contains("copy"))
        assertTrue(args.contains("file:/tmp/list.txt"))
        assertTrue(args.contains("file:/out/a-merged.partial.mp4"))
    }

    @Test
    fun listFileEscapesQuotes() {
        val text = concatListFileContents(listOf("/tmp/a.mp4", "/tmp/o'reilly.mp4"))
        assertTrue(text.contains("file '/tmp/a.mp4'"))
        assertTrue(text.contains("file '/tmp/o'\\''reilly.mp4'"))
    }

    @Test
    fun resolveConcatPresetIsH264Mp4() {
        val resolved = resolveConfig(OutputConfig(preset = "video-concat", quality = "standard")).getOrThrow()
        assertEquals("mp4", resolved.container)
        assertEquals("h264", resolved.videoEncoder)
        assertEquals("aac", resolved.audioEncoder)
        assertEquals("mp4", resolved.extension)
    }
}
