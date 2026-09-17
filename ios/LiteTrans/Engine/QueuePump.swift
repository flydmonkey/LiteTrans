import Foundation

@MainActor
final class QueuePump {
    private let exporter: VideoExporter
    private var task: Task<Void, Never>?

    init(exporter: VideoExporter = VideoExporter()) {
        self.exporter = exporter
    }

    func start(model: AppModel) {
        guard !model.transcoding else { return }
        task = Task { await run(model: model) }
    }

    private func run(model: AppModel) async {
        while !Task.isCancelled {
            guard let job = model.jobs.first(where: { $0.status == .queued }) else {
                model.transcoding = false
                model.releaseOutputAccess()
                return
            }
            model.transcoding = true
            var current = job
            current.status = .running
            model.replaceJob(current)
            model.persistJobs()
            do {
                guard let path = current.outputPath else {
                    throw VideoExportError.missingOutput
                }
                let outputURL = URL(fileURLWithPath: path)
                let saveToPhotos = model.output.kind == .photos
                let jobID = current.id
                try await exporter.export(
                    job: current,
                    outputURL: outputURL,
                    saveToPhotos: saveToPhotos
                ) { progress in
                    Task { @MainActor in
                        model.updateProgress(id: jobID, progress: progress)
                    }
                }
                current.status = .completed
                current.progress = 100
                current.error = nil
            } catch is CancellationError {
                current.status = .cancelled
            } catch {
                current.status = .failed
                current.error = error.localizedDescription
            }
            model.replaceJob(current)
            model.persistJobs()
        }
        model.transcoding = false
        model.releaseOutputAccess()
    }
}
