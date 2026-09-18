import Foundation

@MainActor
final class QueuePump {
    private let exporter: VideoExporter
    private let ffmpeg: FFmpegRunner
    private let documents: DocumentEngine
    private var task: Task<Void, Never>?
    private var exportTask: Task<Void, Error>?

    init(
        exporter: VideoExporter = VideoExporter(),
        ffmpeg: FFmpegRunner = FFmpegRunner(),
        documents: DocumentEngine = DocumentEngine()
    ) {
        self.exporter = exporter
        self.ffmpeg = ffmpeg
        self.documents = documents
    }

    func start(model: AppModel) {
        guard task == nil else { return }
        task = Task { await run(model: model) }
    }

    func cancelCurrentExport() {
        ffmpeg.cancel()
        exportTask?.cancel()
    }

    private func run(model: AppModel) async {
        defer {
            task = nil
            exportTask = nil
            model.transcoding = false
            model.releaseOutputAccess()
        }
        while !Task.isCancelled {
            guard let job = model.jobs.first(where: { $0.status == .queued }) else {
                return
            }
            var current = job
            current.status = .running
            model.replaceJob(current)
            model.persistJobs()
            do {
                guard let path = current.outputPath else {
                    throw VideoExportError.missingOutput
                }
                let outputURL = URL(fileURLWithPath: path)
                let jobID = current.id
                let export = Task {
                    switch engineKind(current.config.preset) {
                    case .avFoundation:
                        try await exporter.export(
                            job: current,
                            outputURL: outputURL,
                            saveToPhotos: shouldSaveToPhotos(current.outputKind)
                        ) { progress in
                            Task { @MainActor in
                                model.updateProgress(id: jobID, progress: progress)
                            }
                        }
                    case .ffmpeg:
                        try await ffmpeg.run(job: current) { progress in
                            Task { @MainActor in
                                model.updateProgress(id: jobID, progress: progress)
                            }
                        }
                        if shouldSaveToPhotos(current.outputKind), current.config.preset == "gif" {
                            try await saveImageToPhotos(outputURL)
                        }
                    case .document:
                        try await documents.run(job: current) { progress in
                            Task { @MainActor in
                                model.updateProgress(id: jobID, progress: progress)
                            }
                        }
                    }
                }
                exportTask = export
                try await export.value
                current.status = .completed
                current.progress = 100
                current.error = nil
            } catch is CancellationError {
                current.status = .cancelled
            } catch {
                current.status = .failed
                current.error = error.localizedDescription
            }
            exportTask = nil
            model.replaceJob(current)
            model.persistJobs()
        }
    }
}
