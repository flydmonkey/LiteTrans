import Foundation

public enum RootTab: String, CaseIterable, Sendable { case convert, history, mine }
public enum ConvertPage: String, Sendable, Hashable { case home, format, quality, size, output }
public enum ConvertSetting: String, Sendable, Hashable { case format, quality, size, output }
public enum MinePage: String, Sendable, Hashable { case root, language, privacy, terms, about }
public enum AppLanguage: String, Sendable, Codable, Hashable { case system, zhHans, zhHant, en, ja, ko }
public enum ConvertMode: String, CaseIterable, Sendable, Codable { case video, audio, document }
public enum HistorySegment: String, CaseIterable, Sendable, Codable { case video, audio, document }
public enum EngineKind: String, Sendable { case avFoundation, ffmpeg, document }
public enum OutputKind: String, Sendable, Codable, Hashable { case photos, downloads, custom, documents }

public struct WizardSession: Equatable, Sendable, Codable {
    public var sources: [MediaInfo]
    public var selectedUri: String?
    public var preset: String
    public var quality: String
    public var size: String
    public var output: OutputTarget
    public var showAllFormats: Bool
    public var imageFormat: String

    public init(
        sources: [MediaInfo],
        selectedUri: String?,
        preset: String,
        quality: String,
        size: String,
        output: OutputTarget,
        showAllFormats: Bool,
        imageFormat: String
    ) {
        self.sources = sources
        self.selectedUri = selectedUri
        self.preset = preset
        self.quality = quality
        self.size = size
        self.output = output
        self.showAllFormats = showAllFormats
        self.imageFormat = imageFormat
    }
}

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

public func allowsTrim(preset: String) -> Bool { !isCopyPreset(preset) }

public func usesPersistentSandboxOutput(_ kind: OutputKind) -> Bool {
    kind == .photos || kind == .downloads || kind == .documents
}

public func isLosslessAudioPreset(_ preset: String) -> Bool {
    preset == "audio-wav" || preset == "audio-flac"
}

public func shouldShowQualityRow(_ preset: String) -> Bool {
    if isCopyPreset(preset) || isLosslessAudioPreset(preset) { return false }
    if isDocumentPreset(preset) { return preset == "image-compress" || preset == "pdf-compress" }
    return true
}

public func shouldShowResolution(_ preset: String) -> Bool {
    !preset.hasPrefix("audio-") && preset != "mp4-copy" && !isDocumentPreset(preset)
}

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

public enum JobRowAction: Equatable { case cancel, retry, open, share, rename, delete }

public func jobRowActions(_ status: JobStatus) -> [JobRowAction] {
    switch status {
    case .queued, .running: return [.cancel]
    case .failed, .cancelled: return [.retry, .delete]
    case .completed: return [.open, .share, .rename, .delete]
    }
}

public func remainingJobsAfterClearFinished(_ jobs: [Job]) -> [Job] {
    jobs.filter { $0.status == .queued || $0.status == .running }
}

public func remainingJobsAfterClearFinished(_ jobs: [Job], segment: HistorySegment) -> [Job] {
    jobs.filter { job in
        historySegmentFor(job) != segment || job.status == .queued || job.status == .running
    }
}

public func resolvedLocaleIdentifier(_ language: AppLanguage) -> String? {
    switch language {
    case .system: return nil
    case .zhHans: return "zh-Hans"
    case .en, .zhHant, .ja, .ko: return "en"
    }
}

public enum HistoryOutputAccess: Equatable, Sendable {
    case none
    case reuseExisting
    case startThenStop
}

public func historyOutputAccess(kind: OutputKind, alreadyAccessing: Bool) -> HistoryOutputAccess {
    guard kind == .custom else { return .none }
    return alreadyAccessing ? .reuseExisting : .startThenStop
}

public func resolutionBounds(_ size: String) -> (Int?, Int?) {
    switch size {
    case "1080p": return (1920, 1080)
    case "720p": return (1280, 720)
    case "480p": return (854, 480)
    default: return (nil, nil)
    }
}

private let audioPresetIDs = ["audio-mp3", "audio-aac", "audio-wav", "audio-flac", "audio-ogg", "audio-amr"]
private let audioHistoryContainers: Set<String> = ["mp3", "m4a", "wav", "ogg", "flac", "amr"]

public func engineKind(_ preset: String) -> EngineKind {
    if primaryPresetIDs.contains(preset) { return .avFoundation }
    if isDocumentPreset(preset) { return .document }
    return .ffmpeg
}

public func videoMorePresetCards() -> [PresetCard] {
    [
        .init(id: "mkv-copy-friendly", title: "MKV · H.264", hint: "Good for keeping a container"),
        .init(id: "mkv-h265", title: "MKV · H.265", hint: "Good for long-term archives"),
        .init(id: "webm-vp9", title: "WebM · VP9", hint: "Common on the web; a bit slower than MP4"),
        .init(id: "avi-mpeg4", title: "AVI · MPEG-4", hint: "Older PCs and projectors"),
        .init(id: "gif", title: "GIF", hint: "Turn a short clip into a GIF"),
        .init(id: "audio-mp3", title: "MP3", hint: "Export audio only"),
        .init(id: "audio-aac", title: "M4A · AAC", hint: "Export audio only"),
    ]
}

public func collapsedPresetCards(selectedId: String, showAll: Bool) -> [PresetCard] {
    let all = collapsedPrimaryPresets() + videoMorePresetCards()
    if showAll { return all }
    let primary = collapsedPrimaryPresets()
    if primaryPresetIDs.contains(selectedId) { return primary }
    guard let selected = all.first(where: { $0.id == selectedId }) else { return primary }
    return Array(primary.prefix(3)) + [selected]
}

public func audioPresetCards() -> [PresetCard] {
    [
        .init(id: "audio-mp3", title: "MP3", hint: "Best compatibility"),
        .init(id: "audio-aac", title: "M4A · AAC", hint: "Common on Apple devices and in the gallery"),
        .init(id: "audio-wav", title: "WAV", hint: "Lossless, larger files"),
        .init(id: "audio-flac", title: "FLAC", hint: "Lossless, smaller than WAV"),
        .init(id: "audio-ogg", title: "OGG · Opus", hint: "Smaller files"),
        .init(id: "audio-amr", title: "AMR", hint: "Common for call recordings"),
    ]
}

public func documentCards(for kind: DocumentSourceKind?) -> [PresetCard] {
    switch kind {
    case .pdf:
        [
            .init(id: "pdf-image", title: "To images", hint: "One image per page; still pick JPG / PNG / WebP"),
            .init(id: "pdf-txt", title: "To TXT", hint: "Extract text; scanned pages will fail"),
            .init(id: "pdf-compress", title: "Compress", hint: "Shrink embedded images without rasterizing whole pages"),
            .init(id: "pdf-split", title: "Split", hint: "One PDF per page in the range"),
        ]
    case .word, .excel:
        [
            .init(id: "office-pdf", title: "To PDF", hint: "Simple text and tables are fine; complex layouts may not line up"),
        ]
    case .image, nil:
        [
            .init(id: "image-jpg", title: "JPG", hint: "Best compatibility"),
            .init(id: "image-png", title: "PNG", hint: "Lossless, larger files"),
            .init(id: "image-webp", title: "WebP", hint: "Same clarity, smaller files"),
            .init(id: "image-bmp", title: "BMP", hint: "Uncompressed"),
            .init(id: "image-gif", title: "GIF", hint: "Still image, first frame only"),
            .init(id: "image-compress", title: "Compress", hint: "Keep the format when possible and shrink the file"),
        ]
    }
}

public func isAudioHistoryJob(_ job: Job) -> Bool {
    if audioPresetIDs.contains(job.config.preset) { return true }
    guard let container = try? resolveConfig(job.config).container else { return false }
    return audioHistoryContainers.contains(container)
}

public func isDocumentHistoryJob(_ job: Job) -> Bool {
    isDocumentPreset(job.config.preset)
}

public func historySegmentFor(_ job: Job) -> HistorySegment {
    if isDocumentHistoryJob(job) { return .document }
    if isAudioHistoryJob(job) { return .audio }
    return .video
}

public func historyJobs(_ jobs: [Job], segment: HistorySegment) -> [Job] {
    jobs.filter { historySegmentFor($0) == segment }
}

public func historySegmentAfterEnqueue(mode: ConvertMode, preset: String) -> HistorySegment {
    if mode == .document || isDocumentPreset(preset) { return .document }
    if mode == .audio || audioPresetIDs.contains(preset) { return .audio }
    return .video
}

public func outputChoices(mode: ConvertMode, preset: String) -> [OutputKind] {
    switch mode {
    case .audio:
        return [.downloads, .custom]
    case .video:
        if preset == "audio-mp3" || preset == "audio-aac" { return [.downloads, .custom] }
        if ["webm-vp9", "mkv-copy-friendly", "mkv-h265", "avi-mpeg4"].contains(preset) {
            return [.downloads, .custom]
        }
        return [.photos, .downloads, .custom]
    case .document:
        if documentResultIsImage(preset) { return [.photos, .downloads, .custom] }
        return [.documents, .downloads, .custom]
    }
}

public func defaultOutputKind(mode: ConvertMode, preset: String) -> OutputKind {
    switch mode {
    case .video, .audio:
        return .downloads
    case .document:
        return documentResultIsImage(preset) ? .photos : .documents
    }
}

public func defaultSession(_ mode: ConvertMode) -> WizardSession {
    switch mode {
    case .video:
        WizardSession(
            sources: [],
            selectedUri: nil,
            preset: "mp4-h264",
            quality: "standard",
            size: "original",
            output: OutputTarget(kind: .downloads),
            showAllFormats: false,
            imageFormat: "jpg"
        )
    case .audio:
        WizardSession(
            sources: [],
            selectedUri: nil,
            preset: "audio-mp3",
            quality: "standard",
            size: "original",
            output: OutputTarget(kind: .downloads),
            showAllFormats: false,
            imageFormat: "jpg"
        )
    case .document:
        WizardSession(
            sources: [],
            selectedUri: nil,
            preset: "image-jpg",
            quality: "standard",
            size: "original",
            output: OutputTarget(kind: .photos),
            showAllFormats: false,
            imageFormat: "jpg"
        )
    }
}

public func coerceOutput(_ output: OutputTarget, mode: ConvertMode, preset: String) -> OutputTarget {
    let choices = outputChoices(mode: mode, preset: preset)
    if choices.contains(output.kind) { return output }
    return OutputTarget(kind: defaultOutputKind(mode: mode, preset: preset))
}
