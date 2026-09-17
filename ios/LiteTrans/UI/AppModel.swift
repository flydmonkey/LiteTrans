import SwiftUI
import UniformTypeIdentifiers

@Observable
final class AppModel {
    var tab: RootTab = .convert
    var convertPage: ConvertPage = .home
    var minePage: MinePage = .root
    var sources: [MediaInfo] = []
    var selectedUri: String?
    var preset: String = defaultPreset
    var quality: String = "standard"
    var size: String = "original"
    var output: OutputTarget = .init(kind: .downloads)
    var jobs: [Job] = []
    var transcoding: Bool = false
    var message: String?
    var language: AppLanguage = .system

    var importableCount: Int { sources.filter(\.importable).count }
    var probing: Bool { sources.contains { $0.error == nil && !$0.importable && $0.videoCodec == nil && $0.durationSecs == nil } }
    var startEnabled: Bool { canStart(importable: importableCount, probing: probing, transcoding: transcoding, output: output) }

    func startConversion() {
        guard startEnabled else { return }
    }

    func canRemove(_ source: MediaInfo) -> Bool {
        !(transcoding && jobs.contains { $0.status == .running && $0.sourceUri == source.sourceUri })
    }

    func removeSource(_ source: MediaInfo) {
        guard canRemove(source) else { return }
        sources.removeAll { $0.sourceUri == source.sourceUri }
        if selectedUri == source.sourceUri {
            selectedUri = sources.first?.sourceUri
        }
    }

    func replaceSource(_ source: MediaInfo) {
        guard let index = sources.firstIndex(where: { $0.sourceUri == source.sourceUri }) else { return }
        sources[index] = source
    }

    func importPickedURLs(_ urls: [URL]) {
        for url in urls {
            let scoped = url.startAccessingSecurityScopedResource()
            defer {
                if scoped { url.stopAccessingSecurityScopedResource() }
            }
            addImportedURL(url, displayName: url.lastPathComponent)
        }
    }

    func addImportedURL(_ url: URL, displayName: String) {
        do {
            let dest = try copyIntoImports(url, preferredName: displayName)
            let info = MediaInfo(
                sourceUri: dest.absoluteString,
                displayName: displayName,
                probing: true
            )
            sources.append(info)
            if selectedUri == nil {
                selectedUri = info.sourceUri
            }
        } catch {
            sources.append(
                MediaInfo(
                    sourceUri: url.absoluteString,
                    displayName: displayName,
                    error: error.localizedDescription
                )
            )
            message = error.localizedDescription
        }
    }

    func setCustomOutputFolder(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer {
            if scoped { url.stopAccessingSecurityScopedResource() }
        }
        let bookmark = try? url.bookmarkData(options: .minimalBookmark, includingResourceValuesForKeys: nil, relativeTo: nil)
        output = OutputTarget(kind: .custom, bookmark: bookmark)
    }

    private func copyIntoImports(_ url: URL, preferredName: String) throws -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        let directory = base.appendingPathComponent("Imports", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let name = preferredName.isEmpty ? "video.mov" : preferredName
        var dest = directory.appendingPathComponent(name)
        if FileManager.default.fileExists(atPath: dest.path) {
            dest = directory.appendingPathComponent("\(UUID().uuidString)-\(name)")
        }
        try FileManager.default.copyItem(at: url, to: dest)
        return dest
    }
}
