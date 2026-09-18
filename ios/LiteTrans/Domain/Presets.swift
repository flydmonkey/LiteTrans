import Foundation

public let defaultPreset = "mp4-h264"

public enum LiteTransError: Error, Equatable, LocalizedError {
    case unknownPreset(String)
    case unsupportedContainer(String)
    case unsupportedVideoEncoder(String)
    case unsupportedAudioEncoder(String)
    case noAudioForExport
    case noVideoForGif
    case noVideoForCopy
    case containerVideoCodec
    case copyCannotChangeVideo
    case containerAudioCodec
    case blankOutputDir
    case cannotTranscode
    case officeNotAvailable
    case concatNeedsTwo
    case concatTooMany
    case concatMissingVideo

    public var errorDescription: String? {
        switch self {
        case .unknownPreset(let preset):
            return "Unknown format: \(preset)"
        case .unsupportedContainer(let container):
            return "Unsupported container: \(container)"
        case .unsupportedVideoEncoder(let encoder):
            return "Unsupported video encoder: \(encoder)"
        case .unsupportedAudioEncoder(let encoder):
            return "Unsupported audio encoder: \(encoder)"
        case .noAudioForExport:
            return "This file has no audio to export"
        case .noVideoForGif:
            return "This file has no video for GIF"
        case .noVideoForCopy:
            return "This file has no video to remux"
        case .containerVideoCodec:
            return "This video codec cannot stay in that container"
        case .copyCannotChangeVideo:
            return "Remux cannot change resolution or frame rate"
        case .containerAudioCodec:
            return "This audio codec cannot stay in that container"
        case .blankOutputDir:
            return "Choose an output folder"
        case .cannotTranscode:
            return "Could not convert this file"
        case .officeNotAvailable:
            return "Office conversion is not available yet / Office 转换将在后续版本提供"
        case .concatNeedsTwo:
            return "Add at least two videos"
        case .concatTooMany:
            return "You can merge up to 20 videos"
        case .concatMissingVideo:
            return "Each clip needs a video track"
        }
    }
}

public func listPresets() -> [PresetInfo] {
    [
        .init(id: "mp4-h264", label: "MP4 / H.264", description: "Best compatibility"),
        .init(id: "mp4-h265", label: "MP4 / H.265", description: "Same MP4, newer codec"),
        .init(id: "mp4-copy", label: "MP4 / Remux", description: "Change the wrapper only"),
        .init(id: "webm-vp9", label: "WebM / VP9", description: "For the web"),
        .init(id: "mkv-copy-friendly", label: "MKV / H.264", description: "Re-encode for playback"),
        .init(id: "mov-h264", label: "MOV / H.264", description: "Apple devices and editors"),
        .init(id: "mkv-h265", label: "MKV / H.265", description: "Archival container"),
        .init(id: "avi-mpeg4", label: "AVI / MPEG-4", description: "Older computers"),
        .init(id: "gif", label: "GIF", description: "Short clips to GIF"),
        .init(id: "audio-mp3", label: "Audio / MP3", description: "Extract MP3"),
        .init(id: "audio-aac", label: "Audio / M4A", description: "Extract AAC"),
        .init(id: "audio-wav", label: "Audio / WAV", description: "PCM"),
        .init(id: "audio-flac", label: "Audio / FLAC", description: "Lossless, smaller than WAV"),
        .init(id: "audio-ogg", label: "Audio / OGG", description: "Opus"),
        .init(id: "audio-amr", label: "Audio / AMR", description: "Voice notes"),
    ]
}

private struct PresetDefaults {
    var container: String
    var videoEncoder: String?
    var audioEncoder: String?
    var keepAudio: Bool
}

public func resolveConfig(_ config: OutputConfig) throws -> ResolvedConfig {
    let preset = config.preset.isEmpty ? defaultPreset : config.preset
    if isDocumentPreset(preset) {
        let ext = documentExtension(preset, imageFormat: config.container)
        let container = preset == "pdf-image" ? (config.container ?? "jpg") : ext
        return ResolvedConfig(
            preset: preset,
            container: container,
            extension: ext,
            videoEncoder: nil,
            audioEncoder: nil,
            maxWidth: config.maxWidth,
            maxHeight: config.maxHeight,
            videoBitrateKbps: nil,
            frameRate: nil,
            audioBitrateKbps: nil,
            keepAudio: false,
            quality: normalizeQuality(config.quality),
            trimStartSecs: config.trimStartSecs,
            trimEndSecs: config.trimEndSecs
        )
    }
    let defaults: PresetDefaults
    switch preset {
    case "mp4-h264": defaults = .init(container: "mp4", videoEncoder: "h264", audioEncoder: "aac", keepAudio: true)
    case "video-concat": defaults = .init(container: "mp4", videoEncoder: "h264", audioEncoder: "aac", keepAudio: true)
    case "mp4-h265": defaults = .init(container: "mp4", videoEncoder: "h265", audioEncoder: "aac", keepAudio: true)
    case "mp4-copy": defaults = .init(container: "mp4", videoEncoder: "copy", audioEncoder: "copy", keepAudio: true)
    case "mov-h264": defaults = .init(container: "mov", videoEncoder: "h264", audioEncoder: "aac", keepAudio: true)
    case "webm-vp9": defaults = .init(container: "webm", videoEncoder: "vp9", audioEncoder: "opus", keepAudio: true)
    case "mkv-copy-friendly": defaults = .init(container: "mkv", videoEncoder: "h264", audioEncoder: "aac", keepAudio: true)
    case "mkv-h265": defaults = .init(container: "mkv", videoEncoder: "h265", audioEncoder: "aac", keepAudio: true)
    case "avi-mpeg4": defaults = .init(container: "avi", videoEncoder: "mpeg4", audioEncoder: "mp3", keepAudio: true)
    case "gif": defaults = .init(container: "gif", videoEncoder: "gif", audioEncoder: nil, keepAudio: false)
    case "audio-mp3": defaults = .init(container: "mp3", videoEncoder: nil, audioEncoder: "mp3", keepAudio: true)
    case "audio-aac": defaults = .init(container: "m4a", videoEncoder: nil, audioEncoder: "aac", keepAudio: true)
    case "audio-wav": defaults = .init(container: "wav", videoEncoder: nil, audioEncoder: "pcm_s16le", keepAudio: true)
    case "audio-flac": defaults = .init(container: "flac", videoEncoder: nil, audioEncoder: "flac", keepAudio: true)
    case "audio-ogg": defaults = .init(container: "ogg", videoEncoder: nil, audioEncoder: "opus", keepAudio: true)
    case "audio-amr": defaults = .init(container: "amr", videoEncoder: nil, audioEncoder: "amr_nb", keepAudio: true)
    case "custom": defaults = .init(container: "mp4", videoEncoder: "h264", audioEncoder: "aac", keepAudio: true)
    default: throw LiteTransError.unknownPreset(preset)
    }

    let quality = normalizeQuality(config.quality)
    if ["audio-mp3", "audio-aac", "audio-wav", "audio-flac", "audio-ogg", "audio-amr"].contains(preset) {
        let bitrate: Int? = ["audio-wav", "audio-flac"].contains(preset) ? nil : (config.audioBitrateKbps ?? audioBitrateForQuality(quality))
        let ext = try extensionFor(defaults.container)
        return ResolvedConfig(
            preset: preset, container: defaults.container, extension: ext,
            videoEncoder: nil, audioEncoder: defaults.audioEncoder,
            maxWidth: nil, maxHeight: nil, videoBitrateKbps: nil, frameRate: nil,
            audioBitrateKbps: bitrate, keepAudio: true, quality: quality,
            trimStartSecs: config.trimStartSecs, trimEndSecs: config.trimEndSecs
        )
    }

    let copyVideo = (config.videoEncoder ?? defaults.videoEncoder) == "copy"
    let container = (config.container?.isEmpty == false ? config.container! : defaults.container)
    return ResolvedConfig(
        preset: preset,
        container: container,
        extension: try extensionFor(container),
        videoEncoder: config.videoEncoder ?? defaults.videoEncoder,
        audioEncoder: config.audioEncoder ?? defaults.audioEncoder,
        maxWidth: copyVideo ? nil : config.maxWidth,
        maxHeight: copyVideo ? nil : config.maxHeight,
        videoBitrateKbps: config.videoBitrateKbps,
        frameRate: copyVideo ? nil : config.frameRate,
        audioBitrateKbps: config.audioBitrateKbps ?? audioBitrateForQuality(quality),
        keepAudio: config.keepAudio ?? defaults.keepAudio,
        quality: quality,
        trimStartSecs: config.trimStartSecs,
        trimEndSecs: config.trimEndSecs
    )
}

public func normalizeQuality(_ value: String?) -> String {
    switch value {
    case "original", "high": return "original"
    case "small": return "small"
    default: return "standard"
    }
}

public func extensionFor(_ container: String) throws -> String {
    let allowed = ["mp4", "webm", "mkv", "mov", "avi", "gif", "mp3", "m4a", "wav", "ogg", "flac", "amr"]
    guard allowed.contains(container) else { throw LiteTransError.unsupportedContainer(container) }
    return container
}

public func isAudioOnlyConfig(_ config: ResolvedConfig) -> Bool {
    ["mp3", "m4a", "wav", "ogg", "flac", "amr"].contains(config.container)
        || ["audio-mp3", "audio-aac", "audio-wav", "audio-flac", "audio-ogg", "audio-amr"].contains(config.preset)
}

private func audioBitrateForQuality(_ quality: String) -> Int {
    switch quality {
    case "original", "high": return 320
    case "small": return 128
    default: return 192
    }
}
