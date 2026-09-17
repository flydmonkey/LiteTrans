package com.videoconverter.android.domain

private val CONTAINERS = listOf("mp4", "webm", "mkv", "mov", "avi", "gif", "mp3", "m4a", "wav", "ogg", "flac", "amr")
private val VIDEO_ENCODERS = listOf("h264", "h265", "vp9", "mpeg4", "gif", "copy")
private val AUDIO_ENCODERS = listOf("aac", "opus", "mp3", "copy", "pcm_s16le", "flac", "amr_nb")

fun validate(config: ResolvedConfig, media: MediaInfo): Result<Unit> = runCatching {
    if (config.container !in CONTAINERS) {
        throw IllegalArgumentException("不支持的容器：${config.container}")
    }
    config.videoEncoder?.let { encoder ->
        if (encoder !in VIDEO_ENCODERS) {
            throw IllegalArgumentException("不支持的视频编码器：$encoder")
        }
    }
    config.audioEncoder?.let { encoder ->
        if (encoder !in AUDIO_ENCODERS) {
            throw IllegalArgumentException("不支持的音频编码器：$encoder")
        }
    }

    if (isAudioOnlyConfig(config)) {
        if (media.audioCodec == null) {
            throw IllegalArgumentException("该文件没有音频流，无法导出音频")
        }
        return@runCatching
    }

    if (config.container == "gif" || config.preset == "gif") {
        if (media.videoCodec == null) {
            throw IllegalArgumentException("该文件没有视频流，无法导出 GIF")
        }
        return@runCatching
    }

    if (config.videoEncoder == "copy") {
        val codec = media.videoCodec
            ?: throw IllegalArgumentException("该文件没有视频流，无法复制视频")
        if (!containerAcceptsVideo(config.container, codec)) {
            throw IllegalArgumentException("目标容器不支持当前视频编码，请改为重新编码")
        }
        if (config.maxWidth != null || config.maxHeight != null || config.frameRate != null) {
            throw IllegalArgumentException("复制视频流时不能同时修改分辨率或帧率，请改为重新编码")
        }
    }

    if (config.audioEncoder == "copy") {
        media.audioCodec?.let { codec ->
            if (!containerAcceptsAudio(config.container, codec) && config.videoEncoder != "copy") {
                throw IllegalArgumentException("目标容器不支持当前音频编码，请改为重新编码")
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
