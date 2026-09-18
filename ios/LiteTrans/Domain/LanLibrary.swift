import Foundation

public enum LanLibraryTab: String, CaseIterable, Sendable {
    case video, audio, image, document
}

public func lanLibraryTabWireName(_ tab: LanLibraryTab) -> String {
    tab.rawValue
}

public func lanLibraryTabFor(_ kind: LanPreviewKind) -> LanLibraryTab {
    switch kind {
    case .video: return .video
    case .audio: return .audio
    case .image: return .image
    case .pdf, .file: return .document
    }
}

public struct LanLibraryItem: Equatable, Sendable {
    public var jobId: String
    public var index: Int
    public var path: String
    public var kind: LanPreviewKind
    public var tab: LanLibraryTab
    public var label: String
    public var format: String
    public var needsIndex: Bool

    public init(
        jobId: String,
        index: Int,
        path: String,
        kind: LanPreviewKind,
        tab: LanLibraryTab,
        label: String,
        format: String,
        needsIndex: Bool
    ) {
        self.jobId = jobId
        self.index = index
        self.path = path
        self.kind = kind
        self.tab = tab
        self.label = label
        self.format = format
        self.needsIndex = needsIndex
    }
}

public func lanLibraryItems(_ jobs: [Job], fileExists: (String) -> Bool) -> [LanLibraryItem] {
    var out: [LanLibraryItem] = []
    for job in jobs.reversed() {
        guard job.status == .completed else { continue }
        let paths = jobOutputPaths(job)
        for (index, path) in paths.enumerated() {
            if path.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !fileExists(path) { continue }
            let label = lanPreviewFileName(path, job: job)
            let kind = lanPreviewKind(label)
            let format = (try? resolveConfig(job.config).container) ?? lanFileExtension(label).nilIfEmpty ?? job.config.preset
            out.append(
                LanLibraryItem(
                    jobId: job.id,
                    index: index,
                    path: path,
                    kind: kind,
                    tab: lanLibraryTabFor(kind),
                    label: label,
                    format: format,
                    needsIndex: paths.count > 1 || index > 0
                )
            )
        }
    }
    return out
}

public func lanLibraryItemsFor(_ items: [LanLibraryItem], tab: LanLibraryTab) -> [LanLibraryItem] {
    items.filter { $0.tab == tab }
}

public func lanDefaultLibraryTab(_ items: [LanLibraryItem]) -> LanLibraryTab {
    let order: [LanLibraryTab] = [.video, .audio, .image, .document]
    return order.first { tab in items.contains { $0.tab == tab } } ?? .video
}

public func lanLibraryTabLabel(_ tab: LanLibraryTab, copy: LanHistoryCopy) -> String {
    switch tab {
    case .video: return copy.video
    case .audio: return copy.audio
    case .image: return copy.image
    case .document: return copy.document
    }
}

public func lanLibraryEmptyLabel(_ tab: LanLibraryTab, copy: LanHistoryCopy) -> String {
    switch tab {
    case .video: return copy.emptyVideo
    case .audio: return copy.emptyAudio
    case .image: return copy.emptyImage
    case .document: return copy.emptyDocument
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
