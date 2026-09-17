package com.videoconverter.android.domain

fun buildFfmpegArgs(
    input: String,
    outputPartial: String,
    config: ResolvedConfig,
    media: MediaInfo,
    preferHardware: Boolean = true,
    validateCopy: ValidateCopy = ValidateCopy(),
    ffmpegValidate: String = "FFmpeg argument check failed",
): Result<List<String>> {
    val validation = validate(config, media, validateCopy)
    if (validation.isFailure) {
        return Result.failure(
            validation.exceptionOrNull()
                ?: IllegalArgumentException(ffmpegValidate),
        )
    }

    return runCatching {
        val args = startArgs(input, config, media).toMutableList()

        if (isAudioOnlyConfig(config)) {
            val encoder = config.audioEncoder ?: "mp3"
            args += listOf("-vn", "-c:a", ffmpegAudioCodec(encoder))
            if (encoder == "amr_nb") {
                args += listOf(
                    "-ar",
                    "8000",
                    "-ac",
                    "1",
                    "-b:a",
                    amrBitrateArg(config.quality),
                )
            } else if (encoder !in listOf("pcm_s16le", "flac") && config.audioBitrateKbps != null) {
                args += listOf("-b:a", "${config.audioBitrateKbps}k")
            }
            pushOutput(args, config.container, outputPartial)
            return@runCatching args
        }

        if (config.container == "gif" || config.preset == "gif") {
            args += listOf("-an", "-c:v", "gif")
            gifFilter(config, media)?.let { filter ->
                args += listOf("-vf", filter)
            }
            pushOutput(args, "gif", outputPartial)
            return@runCatching args
        }

        val videoEncoder = config.videoEncoder ?: "h264"
        val hardwareVideo = preferHardware && videoEncoder in listOf("h264", "h265")
        args += listOf("-c:v", ffmpegVideoCodec(videoEncoder, preferHardware))

        if (videoEncoder != "copy") {
            if (config.videoBitrateKbps != null) {
                args += listOf("-b:v", "${config.videoBitrateKbps}k")
            } else if (hardwareVideo) {
                args += hardwareVideoQualityArgs(config.quality)
            } else {
                args += softwareVideoQualityArgs(videoEncoder, config.quality)
            }

            if (videoEncoder in listOf("h264", "h265", "mpeg4")) {
                args += listOf("-pix_fmt", "yuv420p")
            }
            if (videoEncoder == "h265" && config.container in listOf("mp4", "mov")) {
                args += listOf("-tag:v", "hvc1")
            }
            if (videoEncoder == "mpeg4" && config.container == "avi") {
                args += listOf("-vtag", "xvid")
            }

            config.frameRate?.let { fps ->
                args += listOf("-r", fps.toString())
            }
            scaleFilter(config, media, even = true)?.let { filter ->
                args += listOf("-vf", filter)
            }
        }

        if (!config.keepAudio || media.audioCodec == null) {
            args += "-an"
        } else {
            val audioEncoder = effectiveAudioEncoder(config, media)
            args += listOf("-c:a", ffmpegAudioCodec(audioEncoder))
            if (audioEncoder != "copy") {
                args += listOf("-b:a", "${config.audioBitrateKbps ?: 192}k")
            }
        }

        if (config.container in listOf("mp4", "mov")) {
            args += listOf("-movflags", "+faststart")
        }

        pushOutput(args, config.container, outputPartial)
        args
    }
}

fun outputDurationSecs(config: ResolvedConfig, media: MediaInfo): Double =
    trimWindow(config, media)?.second ?: (media.durationSecs ?: 0.0)

fun ffmpegVideoCodec(encoder: String, preferHardware: Boolean): String = when {
    encoder == "h264" && preferHardware -> "h264_mediacodec"
    encoder == "h265" && preferHardware -> "hevc_mediacodec"
    encoder == "h264" -> "libx264"
    encoder == "h265" -> "libx265"
    encoder == "vp9" -> "libvpx-vp9"
    encoder == "mpeg4" -> "mpeg4"
    encoder == "gif" -> "gif"
    encoder == "copy" -> "copy"
    else -> "libx264"
}

private fun startArgs(
    input: String,
    config: ResolvedConfig,
    media: MediaInfo,
): List<String> {
    val args = mutableListOf(
        "-hide_banner",
        "-nostats",
        "-progress",
        "pipe:1",
        "-y",
    )
    val trim = trimWindow(config, media)
    if (trim == null) {
        args += listOf("-i", ffmpegFileArg(input))
        return args
    }

    val (start, duration) = trim
    when {
        config.videoEncoder == "copy" -> args += listOf(
            "-ss",
            formatSecs(start),
            "-i",
            ffmpegFileArg(input),
            "-t",
            formatSecs(duration),
        )

        start > HYBRID_SEEK_SECS -> args += listOf(
            "-ss",
            formatSecs(start - HYBRID_SEEK_SECS),
            "-i",
            ffmpegFileArg(input),
            "-ss",
            formatSecs(HYBRID_SEEK_SECS),
            "-t",
            formatSecs(duration),
        )

        else -> args += listOf(
            "-i",
            ffmpegFileArg(input),
            "-ss",
            formatSecs(start),
            "-t",
            formatSecs(duration),
        )
    }
    return args
}

private fun trimWindow(config: ResolvedConfig, media: MediaInfo): Pair<Double, Double>? {
    val total = media.durationSecs?.takeIf { it > TRIM_THRESHOLD_SECS } ?: return null
    val start = (config.trimStartSecs ?: 0.0).coerceIn(0.0, total)
    val end = (config.trimEndSecs ?: total).coerceIn(0.0, total)
    if (end - start < TRIM_THRESHOLD_SECS) return null
    if (start <= TRIM_THRESHOLD_SECS && total - end <= TRIM_THRESHOLD_SECS) return null
    return start to (end - start)
}

private fun formatSecs(value: Double): String = "%.3f".format(java.util.Locale.ROOT, value)

private fun pushOutput(args: MutableList<String>, container: String, outputPartial: String) {
    args += listOf("-f", ffmpegMuxer(container), ffmpegFileArg(outputPartial))
}

private fun ffmpegMuxer(container: String): String = when (container) {
    "mkv" -> "matroska"
    "webm" -> "webm"
    "mov" -> "mov"
    "avi" -> "avi"
    "gif" -> "gif"
    "mp3" -> "mp3"
    "m4a" -> "ipod"
    "wav" -> "wav"
    "ogg" -> "ogg"
    "flac" -> "flac"
    "amr" -> "amr"
    else -> "mp4"
}

private fun ffmpegAudioCodec(encoder: String): String = when (encoder) {
    "aac" -> "aac"
    "opus" -> "libopus"
    "mp3" -> "libmp3lame"
    "pcm_s16le" -> "pcm_s16le"
    "flac" -> "flac"
    "amr_nb" -> "libopencore_amrnb"
    "copy" -> "copy"
    else -> "aac"
}

private fun effectiveAudioEncoder(config: ResolvedConfig, media: MediaInfo): String {
    val encoder = config.audioEncoder ?: "aac"
    if (
        encoder == "copy" &&
        media.audioCodec != null &&
        !containerAcceptsAudio(config.container, media.audioCodec)
    ) {
        return fallbackAudioEncoder(config.container)
    }
    return encoder
}

private fun fallbackAudioEncoder(container: String): String = when (container) {
    "webm" -> "opus"
    "mp3", "avi" -> "mp3"
    else -> "aac"
}

private fun hardwareVideoQualityArgs(quality: String): List<String> {
    val bitrate = when (quality) {
        "original", "high" -> "8000k"
        "small" -> "1500k"
        else -> "4000k"
    }
    return listOf("-b:v", bitrate)
}

private fun softwareVideoQualityArgs(encoder: String, quality: String): List<String> =
    when (encoder) {
        "h264" -> listOf(
            "-preset",
            "medium",
            "-crf",
            when (quality) {
                "original", "high" -> "16"
                "small" -> "28"
                else -> "23"
            },
        )

        "h265" -> listOf(
            "-preset",
            "medium",
            "-crf",
            when (quality) {
                "original", "high" -> "18"
                "small" -> "32"
                else -> "28"
            },
        )

        "vp9" -> listOf(
            "-row-mt",
            "1",
            "-deadline",
            "good",
            "-cpu-used",
            when (quality) {
                "original", "high" -> "2"
                "small" -> "6"
                else -> "5"
            },
            "-b:v",
            "0",
            "-crf",
            when (quality) {
                "original", "high" -> "20"
                "small" -> "40"
                else -> "32"
            },
        )

        "mpeg4" -> listOf(
            "-q:v",
            when (quality) {
                "original", "high" -> "2"
                "small" -> "10"
                else -> "6"
            },
        )

        else -> emptyList()
    }

private fun gifFilter(config: ResolvedConfig, media: MediaInfo): String? {
    val fps = when (config.quality) {
        "original", "high" -> "15"
        "small" -> "8"
        else -> "12"
    }
    return scaleFilter(config, media, even = false)
        ?.let { scale -> "fps=$fps,$scale" }
        ?: "fps=$fps"
}

private fun scaleFilter(
    config: ResolvedConfig,
    media: MediaInfo,
    even: Boolean,
): String? {
    if (config.maxWidth == null && config.maxHeight == null) return null

    val maxWidth = config.maxWidth?.toLong() ?: UINT_MAX
    val maxHeight = config.maxHeight?.toLong() ?: UINT_MAX
    if (
        media.width != null &&
        media.height != null &&
        media.width.toLong() <= maxWidth &&
        media.height.toLong() <= maxHeight
    ) {
        return null
    }

    val scale =
        "scale='min(iw,$maxWidth):min(ih,$maxHeight):force_original_aspect_ratio=decrease'"
    return if (even) {
        "$scale,scale=trunc(iw/2)*2:trunc(ih/2)*2"
    } else {
        scale
    }
}

private fun amrBitrateArg(quality: String): String = when (quality) {
    "original", "high" -> "12200"
    "small" -> "4750"
    else -> "7950"
}

private const val TRIM_THRESHOLD_SECS = 0.05
private const val HYBRID_SEEK_SECS = 1.5
private const val UINT_MAX = 4_294_967_295L
