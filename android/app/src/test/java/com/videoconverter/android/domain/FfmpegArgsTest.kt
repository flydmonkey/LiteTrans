package com.videoconverter.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegArgsTest {
    private fun h264(
        durationSecs: Double = 5.0,
        audioCodec: String? = "aac",
    ) = MediaInfo(
        sourceUri = "content://a",
        displayName = "a.mkv",
        durationSecs = durationSecs,
        container = "matroska",
        videoCodec = "h264",
        width = 1920,
        height = 1080,
        frameRate = 30.0,
        audioCodec = audioCodec,
        channels = 2,
        importable = true,
    )

    @Test
    fun hardwareH264UsesMediacodec() {
        val config = resolveConfig(OutputConfig()).getOrThrow()

        val args = buildFfmpegArgs(
            "/tmp/a.mkv",
            "/tmp/a.partial.mp4",
            config,
            h264(),
            preferHardware = true,
        ).getOrThrow()

        assertEquals("h264_mediacodec", valueAfter(args, "-c:v"))
    }

    @Test
    fun softwareFallbackUsesLibx264() {
        val config = resolveConfig(OutputConfig()).getOrThrow()

        val args = buildFfmpegArgs(
            "/tmp/a.mkv",
            "/tmp/a.partial.mp4",
            config,
            h264(),
            preferHardware = false,
        ).getOrThrow()

        assertEquals("libx264", valueAfter(args, "-c:v"))
    }

    @Test
    fun standardQualitySoftwareCrfIs23() {
        val config = resolveConfig(OutputConfig()).getOrThrow()

        val args = buildFfmpegArgs(
            "/in",
            "/out.partial.mp4",
            config,
            h264(),
            preferHardware = false,
        ).getOrThrow()

        assertEquals("23", valueAfter(args, "-crf"))
    }

    @Test
    fun hardwareQualityUsesExpectedBitrates() {
        val cases = listOf(
            "original" to "8000k",
            "standard" to "4000k",
            "small" to "1500k",
        )

        cases.forEach { (quality, expected) ->
            val config = resolveConfig(OutputConfig(quality = quality)).getOrThrow()
            val args = buildFfmpegArgs("/in", "/out", config, h264()).getOrThrow()
            assertEquals(expected, valueAfter(args, "-b:v"))
        }
    }

    @Test
    fun explicitBitrateOverridesHardwareQuality() {
        val config = resolveConfig(
            OutputConfig(quality = "small", videoBitrateKbps = 2345),
        ).getOrThrow()

        val args = buildFfmpegArgs("/in", "/out", config, h264()).getOrThrow()

        assertEquals("2345k", valueAfter(args, "-b:v"))
    }

    @Test
    fun hardwareH265UsesMediacodecAndHvc1TagForMp4() {
        val config = resolveConfig(OutputConfig(preset = "mp4-h265")).getOrThrow()

        val args = buildFfmpegArgs("/in", "/out", config, h264()).getOrThrow()

        assertEquals("hevc_mediacodec", valueAfter(args, "-c:v"))
        assertEquals("hvc1", valueAfter(args, "-tag:v"))
        assertEquals("yuv420p", valueAfter(args, "-pix_fmt"))
    }

    @Test
    fun gifPresetHasFpsFilter() {
        val config = resolveConfig(OutputConfig(preset = "gif")).getOrThrow()

        val args = buildFfmpegArgs("/in", "/out", config, h264()).getOrThrow()

        assertTrue(args.contains("gif"))
        assertTrue(valueAfter(args, "-vf").startsWith("fps="))
    }

    @Test
    fun argsAreArgvNotShellString() {
        val config = resolveConfig(OutputConfig()).getOrThrow()

        val args = buildFfmpegArgs(
            "/tmp/my file.mp4",
            "/tmp/out.partial",
            config,
            h264(),
        ).getOrThrow()

        assertTrue(args.any { it == "file:/tmp/my file.mp4" })
        assertTrue(args.none { it.contains("ffmpeg ") })
    }

    @Test
    fun outputUsesMuxerAndFileProtocol() {
        val config = resolveConfig(OutputConfig()).getOrThrow()

        val args = buildFfmpegArgs(
            "/tmp/[4K]a.mp4",
            "/tmp/[4K]a.partial.mp4",
            config,
            h264(),
        ).getOrThrow()

        assertEquals("file:/tmp/[4K]a.mp4", valueAfter(args, "-i"))
        val formatAt = args.lastIndexOf("-f")
        assertEquals("mp4", args[formatAt + 1])
        assertEquals("file:/tmp/[4K]a.partial.mp4", args[formatAt + 2])
    }

    @Test
    fun trimAddsSeekAndDuration() {
        val config = resolveConfig(
            OutputConfig(trimStartSecs = 1.0, trimEndSecs = 3.0),
        ).getOrThrow()

        val args = buildFfmpegArgs("/in", "/out", config, h264()).getOrThrow()

        assertTrue(args.indexOf("-ss") > args.indexOf("-i"))
        assertEquals("1.000", valueAfter(args, "-ss"))
        assertEquals("2.000", valueAfter(args, "-t"))
        assertEquals(2.0, outputDurationSecs(config, h264()), 0.01)
    }

    @Test
    fun copyTrimSeeksBeforeInput() {
        val config = resolveConfig(
            OutputConfig(
                preset = "mp4-copy",
                trimStartSecs = 1.0,
                trimEndSecs = 3.0,
            ),
        ).getOrThrow()

        val args = buildFfmpegArgs("/in", "/out", config, h264()).getOrThrow()

        assertTrue(args.indexOf("-ss") < args.indexOf("-i"))
        assertEquals("1.000", valueAfter(args, "-ss"))
    }

    @Test
    fun longReencodeTrimUsesHybridSeek() {
        val config = resolveConfig(
            OutputConfig(trimStartSecs = 10.0, trimEndSecs = 12.0),
        ).getOrThrow()

        val args = buildFfmpegArgs("/in", "/out", config, h264(30.0)).getOrThrow()

        val inputAt = args.indexOf("-i")
        val firstSeekAt = args.indexOf("-ss")
        val secondSeekAt = args.lastIndexOf("-ss")
        assertTrue(firstSeekAt < inputAt)
        assertEquals("8.500", args[firstSeekAt + 1])
        assertTrue(secondSeekAt > inputAt)
        assertEquals("1.500", args[secondSeekAt + 1])
    }

    @Test
    fun vp9UsesFasterDeadline() {
        val config = resolveConfig(OutputConfig(preset = "webm-vp9")).getOrThrow()

        val args = buildFfmpegArgs(
            "/in",
            "/out.partial.webm",
            config,
            h264(),
        ).getOrThrow()

        assertEquals("libvpx-vp9", valueAfter(args, "-c:v"))
        assertTrue(args.contains("-row-mt"))
        assertTrue(args.contains("good"))
        assertTrue(args.contains("-cpu-used"))
    }

    @Test
    fun copyIncompatibleAudioFallsBackToAac() {
        val config = resolveConfig(OutputConfig(preset = "mp4-copy")).getOrThrow()

        val args = buildFfmpegArgs(
            "/in",
            "/out",
            config,
            h264(audioCodec = "flac"),
        ).getOrThrow()

        assertEquals("copy", valueAfter(args, "-c:v"))
        assertEquals("aac", valueAfter(args, "-c:a"))
    }

    @Test
    fun fullClipOmitsTrim() {
        val config = resolveConfig(OutputConfig()).getOrThrow()

        val args = buildFfmpegArgs("/in", "/out", config, h264()).getOrThrow()

        assertFalse(args.contains("-ss"))
        assertFalse(args.contains("-t"))
        assertEquals(5.0, outputDurationSecs(config, h264()), 0.01)
    }

    @Test
    fun scaleFilterUsesEvenDimensionsForVideo() {
        val config = resolveConfig(
            OutputConfig(maxWidth = 1280, maxHeight = 720),
        ).getOrThrow()

        val args = buildFfmpegArgs("/in", "/out", config, h264()).getOrThrow()

        assertTrue(valueAfter(args, "-vf").contains("scale=trunc(iw/2)*2:trunc(ih/2)*2"))
    }

    @Test
    fun validatesBeforeBuildingArgs() {
        val config = resolveConfig(OutputConfig()).getOrThrow()
            .copy(videoEncoder = "mpeg2")

        val result = buildFfmpegArgs("/in", "/out", config, h264())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("不支持的视频编码器"))
    }

    @Test
    fun wavOmitsBitrateAndUsesPcmMuxer() {
        val config = resolveConfig(OutputConfig(preset = "audio-wav")).getOrThrow()
        val args = buildFfmpegArgs("/in", "/out.partial.wav", config, h264()).getOrThrow()
        assertTrue(args.contains("-vn"))
        assertEquals("pcm_s16le", valueAfter(args, "-c:a"))
        assertEquals("wav", valueAfter(args, "-f"))
        assertFalse(args.contains("-b:a"))
    }

    @Test
    fun oggUsesLibopusAndBitrate() {
        val config = resolveConfig(OutputConfig(preset = "audio-ogg", quality = "standard")).getOrThrow()
        val args = buildFfmpegArgs("/in", "/out.partial.ogg", config, h264()).getOrThrow()
        assertTrue(args.contains("-vn"))
        assertEquals("libopus", valueAfter(args, "-c:a"))
        assertEquals("ogg", valueAfter(args, "-f"))
        assertEquals("192k", valueAfter(args, "-b:a"))
    }

    @Test
    fun mapsSupportedVideoCodecs() {
        assertEquals("h264_mediacodec", ffmpegVideoCodec("h264", true))
        assertEquals("libx264", ffmpegVideoCodec("h264", false))
        assertEquals("hevc_mediacodec", ffmpegVideoCodec("h265", true))
        assertEquals("libx265", ffmpegVideoCodec("h265", false))
        assertEquals("libvpx-vp9", ffmpegVideoCodec("vp9", true))
        assertEquals("mpeg4", ffmpegVideoCodec("mpeg4", true))
        assertEquals("gif", ffmpegVideoCodec("gif", true))
        assertEquals("copy", ffmpegVideoCodec("copy", true))
    }

    private fun valueAfter(args: List<String>, option: String): String {
        val index = args.indexOf(option)
        assertTrue("missing $option in $args", index >= 0)
        return args[index + 1]
    }
}
