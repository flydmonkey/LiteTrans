import SwiftUI
import UniformTypeIdentifiers

@MainActor
@Observable
final class AppModel {
    var tab: RootTab = .convert
    var convertPage: ConvertPage = .home
    var minePage: MinePage = .root
    var sources: [MediaInfo] = []
    var selectedUri: String?
    var preset: String = defaultPreset {
        didSet { persistSession() }
    }
    var quality: String = "standard" {
        didSet { persistSession() }
    }
    var size: String = "original" {
        didSet { persistSession() }
    }
    var output: OutputTarget = .init(kind: .downloads) {
        didSet { persistSession() }
    }
    var jobs: [Job] = []
    var transcoding: Bool = false
    var message: String?
    var language: AppLanguage = .system {
        didSet { persistSession() }
    }

    var importableCount: Int { sources.filter(\.importable).count }
    var probing: Bool { sources.contains(where: \.probing) }
    var startEnabled: Bool { canStart(importable: importableCount, probing: probing, transcoding: transcoding, output: output) }

    private let jobStore: JobStore
    private let sessionStore: SessionStore
    private let probeService: ProbeService
    private let pump: QueuePump
    private var hydrating = true
    private var outputAccessStop: (() -> Void)?

    init(
        jobStore: JobStore = JobStore(),
        sessionStore: SessionStore = SessionStore(),
        probeService: ProbeService = ProbeService(),
        pump: QueuePump = QueuePump()
    ) {
        self.jobStore = jobStore
        self.sessionStore = sessionStore
        self.probeService = probeService
        self.pump = pump
        jobs = markInterrupted(jobStore.load())
        jobStore.save(jobs)
        if let session = sessionStore.load() {
            preset = session.preset
            quality = session.quality
            size = session.size
            output = session.output
            language = session.language
        }
        hydrating = false
    }

    func startConversion() {
        start()
    }

    func start() {
        guard startEnabled else { return }
        do {
            let dir = try resolvedOutputDir()
            let bounds = shouldShowResolution(preset) ? resolutionBounds(size) : (nil, nil)
            let report = try enqueueJobs(
                sources: sources,
                config: OutputConfig(
                    preset: preset,
                    maxWidth: bounds.0,
                    maxHeight: bounds.1,
                    quality: quality
                ),
                outputDir: dir,
                nextId: { UUID().uuidString },
                exists: { FileManager.default.fileExists(atPath: $0) }
            )
            if report.jobs.isEmpty {
                message = skippedSourcesMessage(report.skipped)
                releaseOutputAccess()
                return
            }
            jobs.insert(contentsOf: report.jobs, at: 0)
            transcoding = true
            message = skippedSourcesMessage(report.skipped)
            sources = []
            selectedUri = nil
            convertPage = .home
            tab = .history
            persistJobs()
            persistSession()
            pump.start(model: self)
        } catch {
            message = error.localizedDescription
            releaseOutputAccess()
        }
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

    func replaceJob(_ job: Job) {
        guard let index = jobs.firstIndex(where: { $0.id == job.id }) else { return }
        jobs[index] = job
    }

    func updateProgress(id: String, progress: Double) {
        guard let index = jobs.firstIndex(where: { $0.id == id }) else { return }
        guard shouldApplyJobProgress(jobs[index].status) else { return }
        jobs[index].progress = min(max(progress, 0), 100)
    }

    func persistJobs() {
        jobStore.save(jobs)
    }

    var hasFinishedJobs: Bool {
        jobs.contains { $0.status != .queued && $0.status != .running }
    }

    func outputFileURL(for job: Job) -> URL? {
        guard let path = job.outputPath, FileManager.default.fileExists(atPath: path) else { return nil }
        return URL(fileURLWithPath: path)
    }

    func cancelJob(_ job: Job) {
        guard jobRowActions(job.status).contains(.cancel) else { return }
        if job.status == .queued {
            var next = job
            next.status = .cancelled
            replaceJob(next)
            persistJobs()
            return
        }
        pump.cancelCurrentExport()
    }

    func retryJob(_ job: Job) {
        guard jobRowActions(job.status).contains(.retry) else { return }
        if !transcoding {
            do {
                _ = try resolvedOutputDir()
            } catch {
                message = error.localizedDescription
                return
            }
        }
        var next = job
        next.status = .queued
        next.progress = 0
        next.error = nil
        replaceJob(next)
        persistJobs()
        transcoding = true
        pump.start(model: self)
    }

    func deleteJob(_ job: Job) {
        guard jobRowActions(job.status).contains(.delete) else { return }
        if let path = job.outputPath {
            try? FileManager.default.removeItem(atPath: path)
            try? FileManager.default.removeItem(atPath: partialOutputPath(path))
        }
        jobs.removeAll { $0.id == job.id }
        persistJobs()
    }

    func renameJob(_ job: Job, rawName: String) {
        guard canRenameJob(job.status), jobRowActions(job.status).contains(.rename) else { return }
        guard let stem = sanitizeRenameStem(rawName) else {
            message = localized("error_invalid_filename")
            return
        }
        guard let currentPath = job.outputPath else { return }
        let currentURL = URL(fileURLWithPath: currentPath)
        let ext = currentURL.pathExtension
        let newName = ext.isEmpty ? stem : "\(stem).\(ext)"
        let dest = currentURL.deletingLastPathComponent().appendingPathComponent(newName)
        do {
            if dest.path != currentURL.path {
                if FileManager.default.fileExists(atPath: dest.path) {
                    throw CocoaError(.fileWriteFileExists)
                }
                try FileManager.default.moveItem(at: currentURL, to: dest)
            }
            var next = job
            next.outputPath = dest.path
            next.displayName = newName
            replaceJob(next)
            persistJobs()
        } catch {
            message = localized("error_cannot_rename")
        }
    }

    func clearFinished() {
        jobs = remainingJobsAfterClearFinished(jobs)
        persistJobs()
    }

    func releaseOutputAccess() {
        outputAccessStop?()
        outputAccessStop = nil
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
            Task { await self.finishProbe(sourceUri: info.sourceUri, displayName: displayName) }
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
        let bookmark = try? url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
        output = OutputTarget(kind: .custom, bookmark: bookmark)
    }

    func resolvedOutputDir() throws -> String {
        releaseOutputAccess()
        switch output.kind {
        case .photos:
            return FileManager.default.temporaryDirectory.path
        case .downloads:
            let directory = documentsDownloadsDirectory()
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            return directory.path
        case .custom:
            guard let bookmark = output.bookmark else {
                throw VideoExportError.bookmarkUnresolved
            }
            var stale = false
            let url = try URL(
                resolvingBookmarkData: bookmark,
                options: [],
                relativeTo: nil,
                bookmarkDataIsStale: &stale
            )
            let accessed = url.startAccessingSecurityScopedResource()
            outputAccessStop = {
                if accessed {
                    url.stopAccessingSecurityScopedResource()
                }
            }
            return url.path
        }
    }

    private func finishProbe(sourceUri: String, displayName: String) async {
        guard let url = URL(string: sourceUri) else { return }
        let probed = await probeService.probe(url: url, displayName: displayName)
        replaceSource(probed)
    }

    private func localized(_ key: String.LocalizationValue) -> String {
        if let identifier = resolvedLocaleIdentifier(language) {
            return String(localized: key, locale: Locale(identifier: identifier))
        }
        return String(localized: key)
    }

    private func persistSession() {
        guard !hydrating else { return }
        sessionStore.save(
            SessionSnapshot(
                preset: preset,
                quality: quality,
                size: size,
                output: output,
                language: language
            )
        )
    }

    private func documentsDownloadsDirectory() -> URL {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return documents.appendingPathComponent("Downloads", isDirectory: true)
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
