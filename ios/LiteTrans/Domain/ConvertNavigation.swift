import Foundation

public enum RootTab: String, CaseIterable, Sendable { case convert, history, mine }
public enum ConvertPage: String, Sendable, Hashable { case home, format, quality, size, output }
public enum ConvertSetting: String, Sendable, Hashable { case format, quality, size, output }
public enum MinePage: String, Sendable { case root, language, privacy, terms, about }
public enum AppLanguage: String, Sendable, Codable { case system, zhHans, zhHant, en, ja, ko }
public enum OutputKind: String, Sendable, Codable { case photos, downloads, custom }

public struct OutputTarget: Equatable, Sendable, Codable {
    public var kind: OutputKind
    public var bookmark: Data?
    public init(kind: OutputKind, bookmark: Data? = nil) {
        self.kind = kind
        self.bookmark = bookmark
    }
}

public struct PresetCard: Equatable, Sendable {
    public var id: String
    public var title: String
    public var hint: String
}

public let primaryPresetIDs = ["mp4-h264", "mp4-copy", "mp4-h265", "mov-h264"]

public func collapsedPrimaryPresets() -> [PresetCard] {
    [
        .init(id: "mp4-h264", title: "MP4 · H.264", hint: "Best compatibility"),
        .init(id: "mp4-copy", title: "MP4 · Remux", hint: "Change the wrapper only"),
        .init(id: "mp4-h265", title: "MP4 · H.265", hint: "Usually smaller"),
        .init(id: "mov-h264", title: "MOV · H.264", hint: "Apple devices and editors"),
    ]
}

public func isCopyPreset(_ preset: String) -> Bool { preset == "mp4-copy" }

public func shouldShowQualityRow(_ preset: String) -> Bool { !isCopyPreset(preset) && !preset.hasPrefix("audio-") }

public func shouldShowResolution(_ preset: String) -> Bool { !preset.hasPrefix("audio-") && preset != "mp4-copy" }

public func convertSettingsFor(preset: String) -> [ConvertSetting] {
    var rows: [ConvertSetting] = [.format]
    if shouldShowQualityRow(preset) { rows.append(.quality) }
    if shouldShowResolution(preset) { rows.append(.size) }
    rows.append(.output)
    return rows
}

public func outputReadyToStart(_ output: OutputTarget) -> Bool {
    output.kind != .custom || output.bookmark != nil
}

public func shouldSaveToPhotos(_ kind: OutputKind) -> Bool {
    kind == .photos
}

public func shouldApplyJobProgress(_ status: JobStatus) -> Bool {
    switch status {
    case .queued, .running: return true
    case .completed, .failed, .cancelled: return false
    }
}

public func canStart(importable: Int, probing: Bool, transcoding: Bool, output: OutputTarget) -> Bool {
    importable > 0 && !probing && !transcoding && outputReadyToStart(output)
}

public func popConvertBack(_ page: ConvertPage) -> ConvertPage? {
    page == .home ? nil : .home
}

public func popMineBack(_ page: MinePage) -> MinePage? {
    page == .root ? nil : .root
}

public func resolutionBounds(_ size: String) -> (Int?, Int?) {
    switch size {
    case "1080p": return (1920, 1080)
    case "720p": return (1280, 720)
    case "480p": return (854, 480)
    default: return (nil, nil)
    }
}
