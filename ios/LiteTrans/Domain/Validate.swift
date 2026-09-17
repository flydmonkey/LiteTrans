private let containers = ["mp4", "webm", "mkv", "mov", "avi", "gif", "mp3", "m4a", "wav", "ogg", "flac", "amr"]
private let videoEncoders = ["h264", "h265", "vp9", "mpeg4", "gif", "copy"]
private let audioEncoders = ["aac", "opus", "mp3", "copy", "pcm_s16le", "flac", "amr_nb"]

public func validate(_ config: ResolvedConfig, media: MediaInfo) throws {
    if !containers.contains(config.container) {
        throw LiteTransError.unsupportedContainer(config.container)
    }
    if let encoder = config.videoEncoder, !videoEncoders.contains(encoder) {
        throw LiteTransError.unsupportedVideoEncoder(encoder)
    }
    if let encoder = config.audioEncoder, !audioEncoders.contains(encoder) {
        throw LiteTransError.unsupportedAudioEncoder(encoder)
    }

    if isAudioOnlyConfig(config) {
        if media.audioCodec == nil {
            throw LiteTransError.noAudioForExport
        }
        return
    }

    if config.container == "gif" || config.preset == "gif" {
        if media.videoCodec == nil {
            throw LiteTransError.noVideoForGif
        }
        return
    }

    if config.videoEncoder == "copy" {
        guard let codec = media.videoCodec else {
            throw LiteTransError.noVideoForCopy
        }
        if !containerAcceptsVideo(config.container, codec: codec) {
            throw LiteTransError.containerVideoCodec
        }
        if config.maxWidth != nil || config.maxHeight != nil || config.frameRate != nil {
            throw LiteTransError.copyCannotChangeVideo
        }
    }

    if config.audioEncoder == "copy" {
        if let codec = media.audioCodec {
            if !containerAcceptsAudio(config.container, codec: codec) && config.videoEncoder != "copy" {
                throw LiteTransError.containerAudioCodec
            }
        }
    }
}

public func containerAcceptsVideo(_ container: String, codec: String) -> Bool {
    let normalized = normalizeCodec(codec)
    switch container {
    case "mp4", "mov": return ["h264", "hevc", "mpeg4", "av1"].contains(normalized)
    case "webm": return ["vp8", "vp9", "av1"].contains(normalized)
    case "avi": return ["mpeg4", "h264", "mjpeg", "mpeg1video", "mpeg2video"].contains(normalized)
    case "gif", "mkv": return true
    default: return false
    }
}

public func containerAcceptsAudio(_ container: String, codec: String) -> Bool {
    let normalized = normalizeCodec(codec)
    switch container {
    case "mp4", "mov", "m4a": return ["aac", "mp3", "ac3", "alac"].contains(normalized)
    case "webm": return ["opus", "vorbis"].contains(normalized)
    case "avi": return ["mp3", "mp2", "ac3", "pcm_s16le"].contains(normalized)
    case "mkv", "wav", "flac": return true
    case "mp3": return normalized == "mp3"
    case "amr": return ["amr_nb", "amr_wb", "amrnb", "amrwb"].contains(normalized)
    case "ogg": return ["opus", "vorbis"].contains(normalized)
    default: return false
    }
}

private func normalizeCodec(_ codec: String) -> String {
    codec == "h265" ? "hevc" : codec
}
