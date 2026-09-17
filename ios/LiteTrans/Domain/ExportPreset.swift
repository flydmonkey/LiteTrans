import Foundation

public func resolvedExportSize(quality: String, size: String) -> String {
    if size != "original" && !size.isEmpty {
        return size
    }
    switch normalizeQuality(quality) {
    case "original": return "original"
    case "small": return "720p"
    default: return "1080p"
    }
}

public func avExportPresetName(preset: String, quality: String, size: String) -> String {
    if preset == "mp4-copy" {
        return "AVAssetExportPresetPassthrough"
    }
    let resolved = resolvedExportSize(quality: quality, size: size)
    if preset == "mp4-h265" {
        return resolved == "original"
            ? "AVAssetExportPresetHEVCHighestQuality"
            : "AVAssetExportPresetHEVC1920x1080"
    }
    switch resolved {
    case "original": return "AVAssetExportPresetHighestQuality"
    case "1080p": return "AVAssetExportPreset1920x1080"
    case "720p": return "AVAssetExportPreset1280x720"
    case "480p": return "AVAssetExportPreset640x480"
    default: return "AVAssetExportPreset1920x1080"
    }
}

public func avExportFileType(preset: String) -> String {
    preset == "mov-h264" ? "mov" : "mp4"
}

public func skippedSourcesMessage(_ skipped: [SkippedSource]) -> String? {
    if skipped.isEmpty { return nil }
    return skipped.map(\.reason).joined(separator: "\n")
}

public func exportSizeID(maxWidth: Int?, maxHeight: Int?) -> String {
    switch (maxWidth, maxHeight) {
    case (1920, _), (_, 1080): return "1080p"
    case (1280, _), (_, 720): return "720p"
    case (854, _), (640, _), (_, 480): return "480p"
    default: return "original"
    }
}
