package com.videoconverter.android.domain

import android.content.res.Resources
import com.videoconverter.android.R

private val CONTAINERS = listOf("mp4", "webm", "mkv", "mov", "avi", "gif", "mp3", "m4a", "wav", "ogg", "flac", "amr")
private val VIDEO_ENCODERS = listOf("h264", "h265", "vp9", "mpeg4", "gif", "copy")
private val AUDIO_ENCODERS = listOf("aac", "opus", "mp3", "copy", "pcm_s16le", "flac", "amr_nb")

data class ValidateCopy(
    val unsupportedContainer: (String) -> String = { "Unsupported container: $it" },
    val unsupportedVideoEncoder: (String) -> String = { "Unsupported video encoder: $it" },
    val unsupportedAudioEncoder: (String) -> String = { "Unsupported audio encoder: $it" },
    val noAudioForExport: String = "This file has no audio stream, so audio cannot be exported",
    val noVideoForGif: String = "This file has no video stream, so a GIF cannot be exported",
    val noVideoForCopy: String = "This file has no video stream to copy",
    val containerVideoCodec: String = "The target container does not support this video codec. Please re-encode.",
    val copyCannotChangeVideo: String = "You cannot change resolution or frame rate while copying the video stream. Please re-encode.",
    val containerAudioCodec: String = "The target container does not support this audio codec. Please re-encode.",
)

fun validateCopy(resources: Resources) = ValidateCopy(
    unsupportedContainer = { resources.getString(R.string.error_unsupported_container, it) },
    unsupportedVideoEncoder = { resources.getString(R.string.error_unsupported_video_encoder, it) },
    unsupportedAudioEncoder = { resources.getString(R.string.error_unsupported_audio_encoder, it) },
    noAudioForExport = resources.getString(R.string.error_no_audio_for_export),
    noVideoForGif = resources.getString(R.string.error_no_video_for_gif),
    noVideoForCopy = resources.getString(R.string.error_no_video_for_copy),
    containerVideoCodec = resources.getString(R.string.error_container_video_codec),
    copyCannotChangeVideo = resources.getString(R.string.error_copy_cannot_change_video),
    containerAudioCodec = resources.getString(R.string.error_container_audio_codec),
)

fun validate(
    config: ResolvedConfig,
    media: MediaInfo,
    copy: ValidateCopy = ValidateCopy(),
): Result<Unit> = runCatching {
    if (config.container !in CONTAINERS) {
        throw IllegalArgumentException(copy.unsupportedContainer(config.container))
    }
    config.videoEncoder?.let { encoder ->
        if (encoder !in VIDEO_ENCODERS) {
            throw IllegalArgumentException(copy.unsupportedVideoEncoder(encoder))
        }
    }
    config.audioEncoder?.let { encoder ->
        if (encoder !in AUDIO_ENCODERS) {
            throw IllegalArgumentException(copy.unsupportedAudioEncoder(encoder))
        }
    }

    if (isAudioOnlyConfig(config)) {
        if (media.audioCodec == null) {
            throw IllegalArgumentException(copy.noAudioForExport)
        }
        return@runCatching
    }

    if (config.container == "gif" || config.preset == "gif") {
        if (media.videoCodec == null) {
            throw IllegalArgumentException(copy.noVideoForGif)
        }
        return@runCatching
    }

    if (config.videoEncoder == "copy") {
        val codec = media.videoCodec
            ?: throw IllegalArgumentException(copy.noVideoForCopy)
        if (!containerAcceptsVideo(config.container, codec)) {
            throw IllegalArgumentException(copy.containerVideoCodec)
        }
        if (config.maxWidth != null || config.maxHeight != null || config.frameRate != null) {
            throw IllegalArgumentException(copy.copyCannotChangeVideo)
        }
    }

    if (config.audioEncoder == "copy") {
        media.audioCodec?.let { codec ->
            if (!containerAcceptsAudio(config.container, codec) && config.videoEncoder != "copy") {
                throw IllegalArgumentException(copy.containerAudioCodec)
            }
        }
    }
}

fun containerAcceptsVideo(container: String, codec: String): Boolean {
    val normalized = normalizeCodec(codec)
    return when (container) {
        "mp4", "mov" -> normalized in listOf("h264", "hevc", "mpeg4", "av1")
        "webm" -> normalized in listOf("vp8", "vp9", "av1")
        "avi" -> normalized in listOf("mpeg4", "h264", "mjpeg", "mpeg1video", "mpeg2video")
        "gif" -> true
        "mkv" -> true
        else -> false
    }
}

fun containerAcceptsAudio(container: String, codec: String): Boolean {
    val normalized = normalizeCodec(codec)
    return when (container) {
        "mp4", "mov", "m4a" -> normalized in listOf("aac", "mp3", "ac3", "alac")
        "webm" -> normalized in listOf("opus", "vorbis")
        "avi" -> normalized in listOf("mp3", "mp2", "ac3", "pcm_s16le")
        "mkv" -> true
        "mp3" -> normalized == "mp3"
        "wav" -> true
        "flac" -> true
        "amr" -> normalized in listOf("amr_nb", "amr_wb", "amrnb", "amrwb")
        "ogg" -> normalized in listOf("opus", "vorbis")
        else -> false
    }
}

private fun normalizeCodec(codec: String): String = when (codec) {
    "h265" -> "hevc"
    else -> codec
}
