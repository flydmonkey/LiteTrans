package com.videoconverter.android.domain

const val DEFAULT_PRESET = "mp4-h264"

fun listPresets(): List<PresetInfo> = listOf(
    PresetInfo("mp4-h264", "MP4 / H.264", "兼容性最好，适合分享和上传"),
    PresetInfo("mp4-h265", "MP4 / H.265", "同样是 MP4，编码更新"),
    PresetInfo("mp4-copy", "MP4 / 不重编码", "只换外壳，不重新压缩，速度最快"),
    PresetInfo("webm-vp9", "WebM / VP9", "适合网页播放"),
    PresetInfo("mkv-copy-friendly", "MKV / H.264", "通用容器，重编码以保证可播放"),
    PresetInfo("mov-h264", "MOV / H.264", "苹果设备和剪辑软件常用"),
    PresetInfo("mkv-h265", "MKV / H.265", "适合封装保存"),
    PresetInfo("avi-mpeg4", "AVI / MPEG-4", "旧电脑和投影常用"),
    PresetInfo("gif", "GIF", "短视频转成动图"),
    PresetInfo("audio-mp3", "仅音频 / MP3", "提取音频为 MP3"),
    PresetInfo("audio-aac", "仅音频 / M4A", "提取音频为 AAC"),
    PresetInfo("audio-wav", "仅音频 / WAV", "无损 PCM"),
    PresetInfo("audio-flac", "仅音频 / FLAC", "无损压缩，比 WAV 小"),
    PresetInfo("audio-ogg", "仅音频 / OGG", "Opus，体积更小"),
    PresetInfo("audio-amr", "仅音频 / AMR", "通话录音常用"),
)

fun resolveConfig(
    config: OutputConfig,
    unknownPreset: (String) -> String = { "Unknown preset: $it" },
): Result<ResolvedConfig> = runCatching {
    val preset = config.preset.ifBlank { DEFAULT_PRESET }
    val defaults = when (preset) {
        "mp4-h264" -> PresetDefaults("mp4", "h264", "aac", true)
        "mp4-h265" -> PresetDefaults("mp4", "h265", "aac", true)
        "mp4-copy" -> PresetDefaults("mp4", "copy", "copy", true)
        "mov-h264" -> PresetDefaults("mov", "h264", "aac", true)
        "webm-vp9" -> PresetDefaults("webm", "vp9", "opus", true)
        "mkv-copy-friendly" -> PresetDefaults("mkv", "h264", "aac", true)
        "mkv-h265" -> PresetDefaults("mkv", "h265", "aac", true)
        "avi-mpeg4" -> PresetDefaults("avi", "mpeg4", "mp3", true)
        "gif" -> PresetDefaults("gif", "gif", null, false)
        "audio-mp3" -> PresetDefaults("mp3", null, "mp3", true)
        "audio-aac" -> PresetDefaults("m4a", null, "aac", true)
        "audio-wav" -> PresetDefaults("wav", null, "pcm_s16le", true)
        "audio-flac" -> PresetDefaults("flac", null, "flac", true)
        "audio-ogg" -> PresetDefaults("ogg", null, "opus", true)
        "audio-amr" -> PresetDefaults("amr", null, "amr_nb", true)
        "custom" -> PresetDefaults("mp4", "h264", "aac", true)
        else -> throw IllegalArgumentException(unknownPreset(preset))
    }

    val quality = normalizeQuality(config.quality)
    val audioBitrateKbps = config.audioBitrateKbps ?: audioBitrateForQuality(quality)

    when (preset) {
        "audio-mp3", "audio-aac", "audio-wav", "audio-flac", "audio-ogg", "audio-amr" -> {
            val container = when (preset) {
                "audio-aac" -> "m4a"
                "audio-wav" -> "wav"
                "audio-flac" -> "flac"
                "audio-ogg" -> "ogg"
                "audio-amr" -> "amr"
                else -> "mp3"
            }
            val audioEncoder = when (preset) {
                "audio-aac" -> "aac"
                "audio-wav" -> "pcm_s16le"
                "audio-flac" -> "flac"
                "audio-ogg" -> "opus"
                "audio-amr" -> "amr_nb"
                else -> "mp3"
            }
            val bitrate = when (preset) {
                "audio-wav", "audio-flac" -> null
                "audio-amr" -> amrBitrateForQuality(quality)
                else -> audioBitrateKbps
            }
            return@runCatching ResolvedConfig(
                preset = preset,
                container = container,
                extension = extensionFor(container).getOrThrow(),
                videoEncoder = null,
                audioEncoder = audioEncoder,
                maxWidth = null,
                maxHeight = null,
                videoBitrateKbps = null,
                frameRate = null,
                audioBitrateKbps = bitrate,
                keepAudio = true,
                quality = quality,
                trimStartSecs = config.trimStartSecs,
                trimEndSecs = config.trimEndSecs,
            )
        }
    }

    val container = config.container?.takeIf { it.isNotEmpty() } ?: defaults.container
    val videoEncoder = config.videoEncoder ?: defaults.videoEncoder
    val copyVideo = videoEncoder == "copy"
    ResolvedConfig(
        preset = preset,
        container = container,
        extension = extensionFor(container).getOrThrow(),
        videoEncoder = videoEncoder,
        audioEncoder = config.audioEncoder ?: defaults.audioEncoder,
        maxWidth = if (copyVideo) null else config.maxWidth,
        maxHeight = if (copyVideo) null else config.maxHeight,
        videoBitrateKbps = config.videoBitrateKbps,
        frameRate = if (copyVideo) null else config.frameRate,
        audioBitrateKbps = audioBitrateKbps,
        keepAudio = config.keepAudio ?: defaults.keepAudio,
        quality = quality,
        trimStartSecs = config.trimStartSecs,
        trimEndSecs = config.trimEndSecs,
    )
}

fun normalizeQuality(value: String?): String = when (value) {
    "original", "high" -> "original"
    "small" -> "small"
    "standard" -> "standard"
    else -> "original"
}

fun extensionFor(container: String): Result<String> = when (container) {
    "mp4", "webm", "mkv", "mov", "avi", "gif", "mp3", "m4a", "wav", "ogg", "flac", "amr" -> Result.success(container)
    else -> Result.failure(IllegalArgumentException("Unsupported container: $container"))
}

fun isAudioOnlyConfig(config: ResolvedConfig): Boolean =
    config.container in listOf("mp3", "m4a", "wav", "ogg", "flac", "amr") ||
        config.preset in listOf(
            "audio-mp3", "audio-aac", "audio-wav", "audio-flac", "audio-ogg", "audio-amr",
        )

private fun audioBitrateForQuality(quality: String): Int = when (quality) {
    "original", "high" -> 320
    "small" -> 128
    else -> 192
}

private fun amrBitrateForQuality(quality: String): Int = when (quality) {
    "original", "high" -> 12
    "small" -> 5
    else -> 8
}

private data class PresetDefaults(
    val container: String,
    val videoEncoder: String?,
    val audioEncoder: String?,
    val keepAudio: Boolean,
)
