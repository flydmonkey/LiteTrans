package com.videoconverter.android.domain

import java.util.Locale

const val VIDEO_CONCAT_PRESET_ID = "video-concat"
const val VIDEO_CONCAT_MAX_SOURCES = 20

data class ConcatTarget(
    val width: Int,
    val height: Int,
    val frameRate: Double,
)

fun isVideoConcatPreset(preset: String): Boolean = preset == VIDEO_CONCAT_PRESET_ID

fun concatOutputStem(displayName: String): String = "${sourceStem(displayName)}-merged"

fun evenDimension(value: Int): Int = maxOf(2, value - (value % 2))

fun concatTarget(
    first: MediaInfo,
    missingVideo: String = "Each clip needs a video track",
): ConcatTarget {
    val width = first.width
    val height = first.height
    if (width == null || height == null || width <= 0 || height <= 0) {
        throw IllegalArgumentException(missingVideo)
    }
    return ConcatTarget(
        width = evenDimension(width),
        height = evenDimension(height),
        frameRate = first.frameRate ?: 30.0,
    )
}

fun concatScalePadFilter(target: ConcatTarget): String {
    val w = target.width
    val h = target.height
    val fps = concatSecs(target.frameRate)
    return "scale=$w:$h:force_original_aspect_ratio=decrease,pad=$w:$h:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=$fps"
}

fun buildConcatNormalizeArgs(
    input: String,
    outputPartial: String,
    media: MediaInfo,
    target: ConcatTarget,
    quality: String,
    preferHardware: Boolean = true,
): List<String> {
    val args = mutableListOf(
        "-hide_banner",
        "-nostats",
        "-progress",
        "pipe:1",
        "-y",
        "-i",
        ffmpegFileArg(input),
    )
    val duration = maxOf(media.durationSecs ?: 0.0, 0.05)
    val missingAudio = media.audioCodec == null
    if (missingAudio) {
        args += listOf(
            "-f",
            "lavfi",
            "-t",
            concatSecs(duration),
            "-i",
            "anullsrc=channel_layout=stereo:sample_rate=48000",
        )
    }
    args += listOf("-c:v", ffmpegVideoCodec("h264", preferHardware))
    args += if (preferHardware) {
        hardwareVideoQualityArgs(quality)
    } else {
        softwareVideoQualityArgs("h264", quality)
    }
    args += listOf(
        "-pix_fmt",
        "yuv420p",
        "-vf",
        concatScalePadFilter(target),
    )
    if (missingAudio) {
        args += listOf("-map", "0:v:0", "-map", "1:a:0", "-shortest")
    }
    args += listOf(
        "-c:a",
        "aac",
        "-ar",
        "48000",
        "-ac",
        "2",
        "-b:a",
        "192k",
        "-movflags",
        "+faststart",
        "-f",
        "mp4",
        ffmpegFileArg(outputPartial),
    )
    return args
}

fun buildConcatJoinArgs(listPath: String, outputPartial: String): List<String> = listOf(
    "-hide_banner",
    "-nostats",
    "-progress",
    "pipe:1",
    "-y",
    "-f",
    "concat",
    "-safe",
    "0",
    "-i",
    ffmpegFileArg(listPath),
    "-c",
    "copy",
    "-movflags",
    "+faststart",
    "-f",
    "mp4",
    ffmpegFileArg(outputPartial),
)

fun concatListFileContents(paths: List<String>): String =
    paths.joinToString("\n") { path ->
        val escaped = path.replace("'", "'\\''")
        "file '$escaped'"
    }

private fun concatSecs(value: Double): String = "%.3f".format(Locale.US, value)
