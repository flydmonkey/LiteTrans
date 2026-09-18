import Foundation

private let trimThresholdSecs = 0.05
private let hybridSeekSecs = 1.5
private let uintMax: Int64 = 4_294_967_295

public func buildFfmpegArgs(
    input: String,
    outputPartial: String,
    config: ResolvedConfig,
    media: MediaInfo,
    preferHardware: Bool = true
) throws -> [String] {
    try validate(config, media: media)

    var args = startArgs(input: input, config: config, media: media)

    if isAudioOnlyConfig(config) {
        let encoder = config.audioEncoder ?? "mp3"
        args += ["-vn", "-c:a", ffmpegAudioCodec(encoder)]
        if encoder == "amr_nb" {
            args += ["-ar", "8000", "-ac", "1", "-b:a", amrBitrateArg(config.quality)]
        } else if encoder != "pcm_s16le" && encoder != "flac", let bitrate = config.audioBitrateKbps {
            args += ["-b:a", "\(bitrate)k"]
        }
        pushOutput(&args, container: config.container, outputPartial: outputPartial)
        return args
    }

    if config.container == "gif" || config.preset == "gif" {
        args += ["-an", "-c:v", "gif"]
        if let filter = gifFilter(config: config, media: media) {
            args += ["-vf", filter]
        }
        pushOutput(&args, container: "gif", outputPartial: outputPartial)
        return args
    }

    let videoEncoder = config.videoEncoder ?? "h264"
    let hardwareVideo = preferHardware && ["h264", "h265"].contains(videoEncoder)
    args += ["-c:v", ffmpegVideoCodec(videoEncoder, preferHardware: preferHardware)]

    if videoEncoder != "copy" {
        if let bitrate = config.videoBitrateKbps {
            args += ["-b:v", "\(bitrate)k"]
        } else if hardwareVideo {
            args += hardwareVideoQualityArgs(config.quality)
        } else {
            args += softwareVideoQualityArgs(encoder: videoEncoder, quality: config.quality)
        }

        if ["h264", "h265", "mpeg4"].contains(videoEncoder) {
            args += ["-pix_fmt", "yuv420p"]
        }
        if videoEncoder == "h265" && ["mp4", "mov"].contains(config.container) {
            args += ["-tag:v", "hvc1"]
        }
        if videoEncoder == "mpeg4" && config.container == "avi" {
            args += ["-vtag", "xvid"]
        }

        if let fps = config.frameRate {
            args += ["-r", String(fps)]
        }
        if let filter = scaleFilter(config: config, media: media, even: true) {
            args += ["-vf", filter]
        }
    }

    if !config.keepAudio || media.audioCodec == nil {
        args.append("-an")
    } else {
        let audioEncoder = effectiveAudioEncoder(config: config, media: media)
        args += ["-c:a", ffmpegAudioCodec(audioEncoder)]
        if audioEncoder != "copy" {
            args += ["-b:a", "\(config.audioBitrateKbps ?? 192)k"]
        }
    }

    if ["mp4", "mov"].contains(config.container) {
        args += ["-movflags", "+faststart"]
    }

    pushOutput(&args, container: config.container, outputPartial: outputPartial)
    return args
}

public func outputDurationSecs(config: ResolvedConfig, media: MediaInfo) -> Double {
    if let trim = trimWindow(config: config, media: media) {
        return trim.duration
    }
    return media.durationSecs ?? 0.0
}

private func startArgs(input: String, config: ResolvedConfig, media: MediaInfo) -> [String] {
    var args = [
        "-hide_banner",
        "-nostats",
        "-progress",
        "pipe:1",
        "-y",
    ]
    guard let trim = trimWindow(config: config, media: media) else {
        args += ["-i", ffmpegFileArg(input)]
        return args
    }

    let start = trim.start
    let duration = trim.duration
    if config.videoEncoder == "copy" {
        args += [
            "-ss",
            formatSecs(start),
            "-i",
            ffmpegFileArg(input),
            "-t",
            formatSecs(duration),
        ]
    } else if start > hybridSeekSecs {
        args += [
            "-ss",
            formatSecs(start - hybridSeekSecs),
            "-i",
            ffmpegFileArg(input),
            "-ss",
            formatSecs(hybridSeekSecs),
            "-t",
            formatSecs(duration),
        ]
    } else {
        args += [
            "-i",
            ffmpegFileArg(input),
            "-ss",
            formatSecs(start),
            "-t",
            formatSecs(duration),
        ]
    }
    return args
}

private func trimWindow(config: ResolvedConfig, media: MediaInfo) -> (start: Double, duration: Double)? {
    guard let total = media.durationSecs, total > trimThresholdSecs else { return nil }
    let start = min(max(config.trimStartSecs ?? 0.0, 0.0), total)
    let end = min(max(config.trimEndSecs ?? total, 0.0), total)
    if end - start < trimThresholdSecs { return nil }
    if start <= trimThresholdSecs && total - end <= trimThresholdSecs { return nil }
    return (start, end - start)
}

private func formatSecs(_ value: Double) -> String {
    String(format: "%.3f", locale: Locale(identifier: "en_US_POSIX"), value)
}

private func pushOutput(_ args: inout [String], container: String, outputPartial: String) {
    args += ["-f", ffmpegMuxer(container), ffmpegFileArg(outputPartial)]
}

private func ffmpegMuxer(_ container: String) -> String {
    switch container {
    case "mkv": return "matroska"
    case "webm": return "webm"
    case "mov": return "mov"
    case "avi": return "avi"
    case "gif": return "gif"
    case "mp3": return "mp3"
    case "m4a": return "ipod"
    case "wav": return "wav"
    case "ogg": return "ogg"
    case "flac": return "flac"
    case "amr": return "amr"
    default: return "mp4"
    }
}

private func ffmpegAudioCodec(_ encoder: String) -> String {
    switch encoder {
    case "aac": return "aac"
    case "opus": return "libopus"
    case "mp3": return "libmp3lame"
    case "pcm_s16le": return "pcm_s16le"
    case "flac": return "flac"
    case "amr_nb": return "libopencore_amrnb"
    case "copy": return "copy"
    default: return "aac"
    }
}

private func effectiveAudioEncoder(config: ResolvedConfig, media: MediaInfo) -> String {
    let encoder = config.audioEncoder ?? "aac"
    if encoder == "copy",
       let audioCodec = media.audioCodec,
       !containerAcceptsAudio(config.container, codec: audioCodec)
    {
        return fallbackAudioEncoder(config.container)
    }
    return encoder
}

private func fallbackAudioEncoder(_ container: String) -> String {
    switch container {
    case "webm": return "opus"
    case "mp3", "avi": return "mp3"
    default: return "aac"
    }
}

private func hardwareVideoQualityArgs(_ quality: String) -> [String] {
    let bitrate: String
    switch quality {
    case "original", "high": bitrate = "8000k"
    case "small": bitrate = "1500k"
    default: bitrate = "4000k"
    }
    return ["-b:v", bitrate]
}

private func softwareVideoQualityArgs(encoder: String, quality: String) -> [String] {
    switch encoder {
    case "h264":
        let crf: String
        switch quality {
        case "original", "high": crf = "16"
        case "small": crf = "28"
        default: crf = "23"
        }
        return ["-preset", "medium", "-crf", crf]
    case "h265":
        let crf: String
        switch quality {
        case "original", "high": crf = "18"
        case "small": crf = "32"
        default: crf = "28"
        }
        return ["-preset", "medium", "-crf", crf]
    case "vp9":
        let cpuUsed: String
        let crf: String
        switch quality {
        case "original", "high":
            cpuUsed = "2"
            crf = "20"
        case "small":
            cpuUsed = "6"
            crf = "40"
        default:
            cpuUsed = "5"
            crf = "32"
        }
        return ["-row-mt", "1", "-deadline", "good", "-cpu-used", cpuUsed, "-b:v", "0", "-crf", crf]
    case "mpeg4":
        let qv: String
        switch quality {
        case "original", "high": qv = "2"
        case "small": qv = "10"
        default: qv = "6"
        }
        return ["-q:v", qv]
    default:
        return []
    }
}

private func gifFilter(config: ResolvedConfig, media: MediaInfo) -> String? {
    let fps: String
    switch config.quality {
    case "original", "high": fps = "15"
    case "small": fps = "8"
    default: fps = "12"
    }
    if let scale = scaleFilter(config: config, media: media, even: false) {
        return "fps=\(fps),\(scale)"
    }
    return "fps=\(fps)"
}

private func scaleFilter(config: ResolvedConfig, media: MediaInfo, even: Bool) -> String? {
    if config.maxWidth == nil && config.maxHeight == nil { return nil }

    let maxWidth = config.maxWidth.map { Int64($0) } ?? uintMax
    let maxHeight = config.maxHeight.map { Int64($0) } ?? uintMax
    if let width = media.width, let height = media.height,
       Int64(width) <= maxWidth, Int64(height) <= maxHeight
    {
        return nil
    }

    let scale = "scale='min(iw,\(maxWidth)):min(ih,\(maxHeight)):force_original_aspect_ratio=decrease'"
    return even ? "\(scale),scale=trunc(iw/2)*2:trunc(ih/2)*2" : scale
}

private func amrBitrateArg(_ quality: String) -> String {
    switch quality {
    case "original", "high": return "12200"
    case "small": return "4750"
    default: return "7950"
    }
}
