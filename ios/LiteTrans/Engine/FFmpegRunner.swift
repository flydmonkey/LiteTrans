import Foundation
@preconcurrency import ffmpegkit

struct FFmpegRunError: Error, LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

struct FFmpegRunner: Sendable {
    func run(job: Job, onProgress: @escaping @Sendable (Double) -> Void) async throws {
        if isVideoConcatPreset(job.config.preset) {
            try await runConcat(job: job, onProgress: onProgress)
            return
        }
        guard let outputPath = job.outputPath else { throw VideoExportError.missingOutput }
        let resolved = try resolveConfig(job.config)
        let partial = partialOutputPath(outputPath)
        let parent = URL(fileURLWithPath: partial).deletingLastPathComponent()
        try FileManager.default.createDirectory(at: parent, withIntermediateDirectories: true)
        try? FileManager.default.removeItem(atPath: partial)
        let args = try buildFfmpegArgs(
            input: URL(string: job.sourceUri)?.path ?? job.sourceUri,
            outputPartial: partial,
            config: resolved,
            media: job.media
        )
        let duration = outputDurationSecs(config: resolved, media: job.media)
        do {
            try await execute(arguments: args, duration: duration, onProgress: onProgress)
            if FileManager.default.fileExists(atPath: outputPath) {
                try FileManager.default.removeItem(atPath: outputPath)
            }
            try FileManager.default.moveItem(atPath: partial, toPath: outputPath)
        } catch {
            try? FileManager.default.removeItem(atPath: partial)
            throw error
        }
    }

    func cancel() {
        FFmpegKit.cancel()
    }

    private func runConcat(job: Job, onProgress: @escaping @Sendable (Double) -> Void) async throws {
        guard let outputPath = job.outputPath else { throw VideoExportError.missingOutput }
        let medias = job.concatMedias
        guard medias.count >= 2, medias.count == job.config.concatSourceUris.count else {
            throw LiteTransError.concatNeedsTwo
        }
        let target = try concatTarget(from: medias[0])
        let work = URL(fileURLWithPath: outputPath)
            .deletingLastPathComponent()
            .appendingPathComponent("concat-\(job.id)")
        try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: work) }

        var clipPaths: [String] = []
        clipPaths.reserveCapacity(medias.count)
        let quality = job.config.quality ?? "standard"
        for (index, media) in medias.enumerated() {
            let input = URL(string: media.sourceUri)?.path ?? media.sourceUri
            let partial = work.appendingPathComponent(String(format: "clip-%03d.partial.mp4", index)).path
            let finalClip = work.appendingPathComponent(String(format: "clip-%03d.mp4", index)).path
            let args = buildConcatNormalizeArgs(
                input: input,
                outputPartial: partial,
                media: media,
                target: target,
                quality: quality
            )
            let base = Double(index) / Double(medias.count) * 90
            try await execute(arguments: args, duration: media.durationSecs ?? 0) { clipLocal in
                onProgress(min(90, base + clipLocal / 100.0 * (90.0 / Double(medias.count))))
            }
            try FileManager.default.moveItem(atPath: partial, toPath: finalClip)
            clipPaths.append(finalClip)
        }

        let listPath = work.appendingPathComponent("list.txt").path
        try concatListFileContents(paths: clipPaths).write(toFile: listPath, atomically: true, encoding: .utf8)

        let outputPartial = partialOutputPath(outputPath)
        try? FileManager.default.removeItem(atPath: outputPartial)
        let joinArgs = buildConcatJoinArgs(listPath: listPath, outputPartial: outputPartial)
        let joinDuration = medias.reduce(0.0) { $0 + ($1.durationSecs ?? 0) }
        do {
            try await execute(arguments: joinArgs, duration: joinDuration) { joinLocal in
                onProgress(min(100, 90 + joinLocal / 100.0 * 10))
            }
            if FileManager.default.fileExists(atPath: outputPath) {
                try FileManager.default.removeItem(atPath: outputPath)
            }
            try FileManager.default.moveItem(atPath: outputPartial, toPath: outputPath)
        } catch {
            try? FileManager.default.removeItem(atPath: outputPartial)
            throw error
        }
    }

    private func execute(
        arguments: [String],
        duration: Double,
        onProgress: @escaping @Sendable (Double) -> Void
    ) async throws {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                let settled = ResumeOnce()
                _ = FFmpegKit.execute(
                    withArgumentsAsync: arguments,
                    withCompleteCallback: { session in
                        guard let session else {
                            settled.resume(continuation, throwing: FFmpegRunError(message: "FFmpeg conversion failed"))
                            return
                        }
                        if Task.isCancelled || ReturnCode.isCancel(session.getReturnCode()) {
                            settled.resume(continuation, throwing: CancellationError())
                            return
                        }
                        if ReturnCode.isSuccess(session.getReturnCode()) {
                            onProgress(100)
                            settled.resume(continuation)
                            return
                        }
                        settled.resume(continuation, throwing: FFmpegRunError(message: ffmpegFailureMessage(session)))
                    },
                    withLogCallback: { log in
                        guard let message = log?.getMessage() else { return }
                        for line in message.split(whereSeparator: \.isNewline) {
                            if let progress = parseProgressLine(String(line), durationSecs: duration) {
                                onProgress(progress)
                            }
                        }
                    },
                    withStatisticsCallback: { stats in
                        guard let stats else { return }
                        let millis = Double(stats.getTime())
                        guard duration.isFinite, duration > 0, millis.isFinite, millis >= 0 else { return }
                        onProgress(min(100.0, max(0.0, (millis / 1000.0 / duration) * 100.0)))
                    }
                )
            }
        } onCancel: {
            FFmpegKit.cancel()
        }
    }
}

private final class ResumeOnce: @unchecked Sendable {
    private let lock = NSLock()
    private var finished = false

    func resume(_ continuation: CheckedContinuation<Void, Error>) {
        lock.lock()
        defer { lock.unlock() }
        guard !finished else { return }
        finished = true
        continuation.resume()
    }

    func resume(_ continuation: CheckedContinuation<Void, Error>, throwing error: Error) {
        lock.lock()
        defer { lock.unlock() }
        guard !finished else { return }
        finished = true
        continuation.resume(throwing: error)
    }
}

private func ffmpegFailureMessage(_ session: FFmpegSession) -> String {
    let raw = [session.getFailStackTrace(), session.getOutput(), session.getLogsAsString()]
        .compactMap { $0 }
        .first { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        ?? ""
    let lines = raw
        .split(whereSeparator: \.isNewline)
        .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        .filter { !$0.isEmpty }
    if let errorLine = lines.last(where: { line in
        line.localizedCaseInsensitiveContains("error") || line.localizedCaseInsensitiveContains("failed")
    }) {
        return errorLine
    }
    if let last = lines.last {
        return last
    }
    return "FFmpeg conversion failed"
}
