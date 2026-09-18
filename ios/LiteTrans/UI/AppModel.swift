import SwiftUI
import UniformTypeIdentifiers
import UIKit
import UserNotifications
import BackgroundTasks
import LiteTransActivityKit

@MainActor
@Observable
final class AppModel {
    var tab: RootTab = .convert
    var convertPage: ConvertPage = .home
    var minePage: MinePage = .root
    var convertMode: ConvertMode = .video {
        didSet {
            if convertMode != oldValue {
                var session = session(for: convertMode)
                session.output = coerceOutput(session.output, mode: convertMode, preset: session.preset)
                write(session, to: convertMode, persist: false)
            }
            persistSession()
        }
    }
    var historySegment: HistorySegment = .video {
        didSet { persistSession() }
    }
    var video: WizardSession = defaultSession(.video)
    var audio: WizardSession = defaultSession(.audio)
    var document: WizardSession = defaultSession(.document)
    var sources: [MediaInfo] {
        get { current.sources }
        set { mutateCurrent { $0.sources = newValue } }
    }
    var selectedUri: String? {
        get { current.selectedUri }
        set { mutateCurrent { $0.selectedUri = newValue } }
    }
    var preset: String {
        get { current.preset }
        set {
            mutateCurrent { session in
                session.preset = newValue
                session.output = coerceOutput(session.output, mode: convertMode, preset: newValue)
            }
        }
    }
    var quality: String {
        get { current.quality }
        set { mutateCurrent { $0.quality = newValue } }
    }
    var size: String {
        get { current.size }
        set { mutateCurrent { $0.size = newValue } }
    }
    var output: OutputTarget {
        get { current.output }
        set { mutateCurrent { $0.output = newValue } }
    }
    var showAllFormats: Bool {
        get { current.showAllFormats }
        set { mutateCurrent { $0.showAllFormats = newValue } }
    }
    var imageFormat: String {
        get { current.imageFormat }
        set { mutateCurrent { $0.imageFormat = newValue } }
    }
    var jobs: [Job] = []
    var transcoding: Bool = false
    var message: String?
    var language: AppLanguage = .system {
        didSet {
            persistSession()
            syncLanShare()
        }
    }
    var lanShare = LanShareSettings() {
        didSet {
            persistLanShare()
            syncLanShare()
        }
    }
    var lanSceneActive = true
    let lanServer = LanShareServer()
    var documentKind: DocumentSourceKind? {
        sources.lazy.compactMap { documentSourceKind($0.displayName) }.first
    }

    var importableCount: Int { sources.filter(\.importable).count }
    var probing: Bool { sources.contains(where: \.probing) }
    var startEnabled: Bool {
        canStart(
            importable: importableCount,
            probing: probing,
            transcoding: transcoding,
            output: output,
            preset: preset,
            sourceCount: sources.count
        )
    }

    func moveSources(from offsets: IndexSet, to destination: Int) {
        sources.move(fromOffsets: offsets, toOffset: destination)
    }

    private let jobStore: JobStore
    private let sessionStore: SessionStore
    private let lanShareStore: LanShareStore
    private let probeService: ProbeService
    private let pump: QueuePump
    private let liveActivity = LiveActivityController()
    private let backgroundProcessing = BackgroundProcessingController()
    private var hydrating = true
    private var outputAccessStop: (() -> Void)?
    private static let didAskNotificationsKey = "liteTrans.didAskNotifications"

    init(
        jobStore: JobStore = JobStore(),
        sessionStore: SessionStore = SessionStore(),
        lanShareStore: LanShareStore = LanShareStore(),
        probeService: ProbeService = ProbeService(),
        pump: QueuePump = QueuePump()
    ) {
        self.jobStore = jobStore
        self.sessionStore = sessionStore
        self.lanShareStore = lanShareStore
        self.probeService = probeService
        self.pump = pump
        jobs = markInterrupted(jobStore.load()).map {
            relocateJobSandboxPaths(
                $0,
                documentsDir: sandboxDocumentsDirectory().path,
                applicationSupportDir: sandboxApplicationSupportDirectory().path
            )
        }
        jobStore.save(jobs)
        lanShare = lanShareStore.load()
        lanServer.onDenied = { [weak self] in
            self?.lanShare.enabled = false
        }
        if let session = sessionStore.load() {
            language = session.language
            video = session.video
            audio = session.audio
            document = session.document
            historySegment = session.historySegment
            convertMode = session.convertMode
        }
        hydrating = false
        persistSession()
        syncLiveActivity()
    }

    func registerBackgroundProcessing() {
        backgroundProcessing.register { [weak self] task in
            self?.handleBackgroundProcessing(task)
        }
    }

    func syncScene(sceneActive: Bool) {
        lanSceneActive = sceneActive
        syncLanShare()
        backgroundProcessing.sync(jobs: jobs, sceneActive: sceneActive)
        if sceneActive {
            consumePendingCancel()
        }
    }

    func consumePendingCancel() {
        let defaults = UserDefaults.standard
        guard let id = defaults.string(forKey: pendingCancelJobDefaultsKey), !id.isEmpty else { return }
        defaults.removeObject(forKey: pendingCancelJobDefaultsKey)
        if let job = jobs.first(where: { $0.id == id }) {
            cancelJob(job)
        }
    }

    func openHistoryJob(id: String) {
        guard let job = jobs.first(where: { $0.id == id }) else { return }
        tab = .history
        historySegment = historySegmentFor(job)
    }

    private func syncLiveActivity() {
        guard !hydrating else { return }
        liveActivity.sync(jobs: jobs)
    }

    private func requestNotificationsIfNeeded() {
        let defaults = UserDefaults.standard
        guard !defaults.bool(forKey: Self.didAskNotificationsKey) else { return }
        defaults.set(true, forKey: Self.didAskNotificationsKey)
        UNUserNotificationCenter.current().requestAuthorization(options: [.badge, .sound, .alert]) { _, _ in }
    }

    private func handleBackgroundProcessing(_ task: BGTask) {
        pump.start(model: self)
        let box = BackgroundTaskCompletion()
        task.expirationHandler = { [weak self] in
            Task { @MainActor in
                self?.pump.cancelCurrentExport()
                box.finish(task, success: false)
            }
        }
        Task { @MainActor in
            while shouldSubmitBackgroundProcessing(self.jobs) {
                try? await Task.sleep(for: .milliseconds(400))
            }
            box.finish(task, success: true)
        }
    }

    private var current: WizardSession { session(for: convertMode) }

    private func session(for mode: ConvertMode) -> WizardSession {
        switch mode {
        case .video: video
        case .audio: audio
        case .document: document
        }
    }

    private func write(_ session: WizardSession, to mode: ConvertMode, persist: Bool = true) {
        switch mode {
        case .video: video = session
        case .audio: audio = session
        case .document: document = session
        }
        if persist { persistSession() }
    }

    private func mutateCurrent(_ update: (inout WizardSession) -> Void) {
        var session = current
        update(&session)
        write(session, to: convertMode)
    }

    private func syncDocumentSessionToSources() {
        guard convertMode == .document else { return }
        let cards = documentCards(for: documentKind)
        guard !cards.contains(where: { $0.id == preset }) else { return }
        let nextPreset = documentKind.map(defaultDocumentPreset) ?? defaultSession(.document).preset
        preset = nextPreset
        output = OutputTarget(kind: defaultOutputKind(mode: .document, preset: nextPreset))
    }

    func startConversion() {
        start()
    }

    func start() {
        guard startEnabled else { return }
        let mode = convertMode
        let session = current
        do {
            let dir = try resolvedOutputDir()
            let bounds = shouldShowResolution(session.preset) ? resolutionBounds(session.size) : (nil, nil)
            var config = OutputConfig(
                preset: session.preset,
                maxWidth: bounds.0,
                maxHeight: bounds.1,
                quality: session.quality
            )
            if session.preset == "pdf-image" {
                config.container = session.imageFormat
            }
            let report = try enqueueJobs(
                sources: session.sources,
                config: config,
                outputDir: dir,
                nextId: { UUID().uuidString },
                exists: { FileManager.default.fileExists(atPath: $0) },
                existingJobs: jobs,
                outputKind: session.output.kind
            )
            if report.jobs.isEmpty {
                message = skippedSourcesMessage(report.skipped)
                releaseOutputAccess()
                return
            }
            jobs.insert(contentsOf: report.jobs, at: 0)
            transcoding = true
            message = skippedSourcesMessage(report.skipped)
            write(defaultSession(mode), to: mode, persist: false)
            convertPage = .home
            tab = .history
            historySegment = historySegmentAfterEnqueue(mode: mode, preset: session.preset)
            persistJobs()
            persistSession()
            pump.start(model: self)
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            requestNotificationsIfNeeded()
            syncLiveActivity()
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
        syncDocumentSessionToSources()
        deleteOrphanedImport(sourceUri: source.sourceUri)
    }

    func replaceSource(_ source: MediaInfo) {
        guard let index = sources.firstIndex(where: { $0.sourceUri == source.sourceUri }) else { return }
        sources[index] = source
    }

    func replaceJob(_ job: Job) {
        guard let index = jobs.firstIndex(where: { $0.id == job.id }) else { return }
        jobs[index] = job
        syncLiveActivity()
    }

    func updateProgress(id: String, progress: Double) {
        guard let index = jobs.firstIndex(where: { $0.id == id }) else { return }
        guard shouldApplyJobProgress(jobs[index].status) else { return }
        jobs[index].progress = min(max(progress, 0), 100)
        syncLiveActivity()
    }

    func persistJobs() {
        jobStore.save(jobs)
        syncLanShare()
        syncLiveActivity()
        backgroundProcessing.sync(jobs: jobs, sceneActive: lanSceneActive)
    }

    func syncLanShare(sceneActive: Bool? = nil) {
        if let sceneActive {
            lanSceneActive = sceneActive
        }
        guard !hydrating else { return }
        lanServer.apply(
            enabled: lanShare.enabled,
            token: lanShare.token,
            jobs: jobs,
            sceneActive: lanSceneActive,
            copy: lanHistoryCopy(language: language)
        )
    }

    var hasFinishedJobs: Bool {
        historyJobs(jobs, segment: historySegment).contains { $0.status != .queued && $0.status != .running }
    }

    func outputFileURL(for job: Job) -> URL? {
        let started = beginHistoryOutputAccess()
        let relocated = relocateJobSandboxPaths(
            job,
            documentsDir: sandboxDocumentsDirectory().path,
            applicationSupportDir: sandboxApplicationSupportDirectory().path
        )
        guard let path = relocated.outputPath, FileManager.default.fileExists(atPath: path) else {
            endHistoryOutputAccess(started)
            return nil
        }
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
        next.createdAtEpochMs = currentEpochMs()
        replaceJob(next)
        persistJobs()
        transcoding = true
        pump.start(model: self)
    }

    func deleteJob(_ job: Job) {
        guard jobRowActions(job.status).contains(.delete) else { return }
        let started = beginHistoryOutputAccess()
        defer { endHistoryOutputAccess(started) }
        let remaining = jobs.filter { $0.id != job.id }
        for path in outputFileDeletionPaths(job: job, remainingJobs: remaining) {
            try? FileManager.default.removeItem(atPath: path)
        }
        jobs.removeAll { $0.id == job.id }
        persistJobs()
        for sourceUri in jobSourceURIs(job) {
            deleteOrphanedImport(sourceUri: sourceUri)
        }
    }

    func renameJob(_ job: Job, rawName: String) {
        guard canRenameJob(job.status), jobRowActions(job.status).contains(.rename) else { return }
        guard let stem = sanitizeRenameStem(rawName) else {
            message = localized("error_invalid_filename")
            return
        }
        let ext: String
        if let currentPath = job.outputPath {
            ext = URL(fileURLWithPath: currentPath).pathExtension
        } else {
            ext = URL(fileURLWithPath: job.displayName).pathExtension
        }
        let newName = ext.isEmpty ? stem : "\(stem).\(ext)"
        var next = job
        next.displayName = newName
        replaceJob(next)
        persistJobs()
    }

    func clearFinished() {
        let segment = historySegment
        let remaining = remainingJobsAfterClearFinished(jobs, segment: segment)
        let remainingIDs = Set(remaining.map(\.id))
        let removed = jobs.filter { !remainingIDs.contains($0.id) }
        jobs = remaining
        persistJobs()
        for job in removed {
            for sourceUri in jobSourceURIs(job) {
                deleteOrphanedImport(sourceUri: sourceUri)
            }
        }
    }

    func releaseOutputAccess() {
        outputAccessStop?()
        outputAccessStop = nil
    }

    func releaseHistoryOutputAccess() {
        guard !transcoding else { return }
        releaseOutputAccess()
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
        if convertMode == .document,
           !sameDocumentKind(existing: sources.map(\.displayName), incoming: displayName) {
            message = localized("error_mixed_documents")
            return
        }
        do {
            let dest = try copyIntoImports(url, preferredName: displayName)
            let info = MediaInfo(
                sourceUri: dest.absoluteString,
                displayName: displayName,
                probing: true
            )
            sources.append(info)
            selectedUri = info.sourceUri
            syncDocumentSessionToSources()
            let mode = convertMode
            let currentPreset = preset
            Task { await self.finishProbe(sourceUri: info.sourceUri, displayName: displayName, mode: mode, preset: currentPreset) }
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
        if output.kind == .documents {
            let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            let directory = documents.appendingPathComponent("LiteTrans", isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            return directory.path
        }
        if usesPersistentSandboxOutput(output.kind) {
            let directory = documentsDownloadsDirectory()
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            return directory.path
        }
        let url = try resolveCustomOutputURL()
        beginAccessing(url)
        return url.path
    }

    @discardableResult
    private func beginHistoryOutputAccess() -> Bool {
        switch historyOutputAccess(kind: output.kind, alreadyAccessing: outputAccessStop != nil) {
        case .none, .reuseExisting:
            return false
        case .startThenStop:
            do {
                beginAccessing(try resolveCustomOutputURL())
                return true
            } catch {
                return false
            }
        }
    }

    private func endHistoryOutputAccess(_ started: Bool) {
        guard started else { return }
        releaseOutputAccess()
    }

    private func resolveCustomOutputURL() throws -> URL {
        guard let bookmark = output.bookmark else {
            throw VideoExportError.bookmarkUnresolved
        }
        var stale = false
        return try URL(
            resolvingBookmarkData: bookmark,
            options: [],
            relativeTo: nil,
            bookmarkDataIsStale: &stale
        )
    }

    private func beginAccessing(_ url: URL) {
        let accessed = url.startAccessingSecurityScopedResource()
        outputAccessStop = {
            if accessed {
                url.stopAccessingSecurityScopedResource()
            }
        }
    }

    private func finishProbe(sourceUri: String, displayName: String, mode: ConvertMode, preset: String) async {
        guard let url = URL(string: sourceUri) else { return }
        let probed = await probeService.probe(
            url: url,
            displayName: displayName,
            mode: mode,
            preset: preset,
            language: language
        )
        replaceSource(probed)
        syncDocumentSessionToSources()
    }

    private func localized(_ key: String.LocalizationValue) -> String {
        localizedText(key, language: language)
    }

    private func persistLanShare() {
        guard !hydrating else { return }
        lanShareStore.save(lanShare)
    }

    private func persistSession() {
        guard !hydrating else { return }
        sessionStore.save(
            SessionSnapshot(
                language: language,
                convertMode: convertMode,
                historySegment: historySegment,
                video: video,
                audio: audio,
                document: document
            )
        )
    }

    private func documentsDownloadsDirectory() -> URL {
        sandboxDocumentsDirectory().appendingPathComponent("Downloads", isDirectory: true)
    }

    private func sandboxDocumentsDirectory() -> URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
    }

    private func sandboxApplicationSupportDirectory() -> URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
    }

    private func copyIntoImports(_ url: URL, preferredName: String) throws -> URL {
        let directory = importsDirectory()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let name = preferredName.isEmpty ? "video.mov" : preferredName
        var dest = directory.appendingPathComponent(name)
        if FileManager.default.fileExists(atPath: dest.path) {
            dest = directory.appendingPathComponent("\(UUID().uuidString)-\(name)")
        }
        try FileManager.default.copyItem(at: url, to: dest)
        return dest.resolvingSymlinksInPath()
    }

    private func deleteOrphanedImport(sourceUri: String) {
        guard shouldDeleteImportedSource(sourceUri: sourceUri, remainingJobs: jobs, sessionSources: sources) else {
            return
        }
        guard let url = URL(string: sourceUri), url.isFileURL else { return }
        let importsPath = importsDirectory().standardizedFileURL.path
        let filePath = url.standardizedFileURL.path
        guard filePath == importsPath || filePath.hasPrefix(importsPath + "/") else { return }
        try? FileManager.default.removeItem(at: url)
    }

    private func importsDirectory() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return base.appendingPathComponent("Imports", isDirectory: true)
    }
}
