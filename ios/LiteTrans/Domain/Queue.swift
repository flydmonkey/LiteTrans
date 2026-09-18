import Foundation

public func splitImportable(_ sources: [MediaInfo], cannotTranscode: String = "Could not convert this file") -> ([MediaInfo], [SkippedSource]) {
    var accepted: [MediaInfo] = []
    var skipped: [SkippedSource] = []
    for source in sources {
        if source.importable {
            accepted.append(source)
        } else {
            skipped.append(.init(sourceUri: source.sourceUri, displayName: source.displayName, reason: source.error ?? cannotTranscode))
        }
    }
    return (accepted, skipped)
}

public func configForSource(_ config: OutputConfig, media: MediaInfo) -> OutputConfig {
    var next = config
    if allowsTrim(preset: config.preset), media.trimStartSecs != nil || media.trimEndSecs != nil {
        next.trimStartSecs = media.trimStartSecs
        next.trimEndSecs = media.trimEndSecs
    }
    return next
}

public func occupiedOutputPaths(_ jobs: [Job]) -> Set<String> {
    var paths = Set<String>()
    for job in jobs {
        for path in resolvedOutputPaths(job) {
            paths.insert(path)
            paths.insert(partialOutputPath(path))
        }
    }
    return paths
}

public func outputFileDeletionPaths(job: Job, remainingJobs: [Job]) -> [String] {
    guard job.status == .completed else { return [] }
    let occupied = occupiedOutputPaths(remainingJobs)
    var deletion: [String] = []
    for path in resolvedOutputPaths(job) where !occupied.contains(path) {
        deletion.append(path)
        deletion.append(partialOutputPath(path))
    }
    return deletion
}

private func resolvedOutputPaths(_ job: Job) -> [String] {
    job.outputPaths.isEmpty ? [job.outputPath].compactMap { $0 } : job.outputPaths
}

public func shouldDeleteImportedSource(
    sourceUri: String,
    remainingJobs: [Job],
    sessionSources: [MediaInfo]
) -> Bool {
    remainingJobs.allSatisfy { $0.sourceUri != sourceUri }
        && sessionSources.allSatisfy { $0.sourceUri != sourceUri }
}

public func enqueueJobs(
    sources: [MediaInfo],
    config: OutputConfig,
    outputDir: String,
    nextId: () -> String,
    exists: (String) -> Bool,
    existingJobs: [Job] = [],
    outputKind: OutputKind = .downloads,
    nowMs: () -> Int64 = { currentEpochMs() }
) throws -> EnqueueReport {
    if outputDir.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
        throw LiteTransError.blankOutputDir
    }
    let resolved = try resolveConfig(config)
    let (accepted, initialSkipped) = splitImportable(sources)
    var skipped = initialSkipped
    var jobs: [Job] = []
    var allocated = occupiedOutputPaths(existingJobs)
    for media in accepted {
        if documentSourceKind(media.displayName) == .word || documentSourceKind(media.displayName) == .excel {
            skipped.append(
                .init(
                    sourceUri: media.sourceUri,
                    displayName: media.displayName,
                    reason: LiteTransError.officeNotAvailable.localizedDescription
                )
            )
            continue
        }
        do {
            try validate(resolved, media: media)
        } catch {
            skipped.append(.init(sourceUri: media.sourceUri, displayName: media.displayName, reason: error.localizedDescription))
            continue
        }
        let isTaken: (String) -> Bool = { candidate in
            let partial = partialOutputPath(candidate)
            return exists(candidate) || exists(partial) || allocated.contains(candidate) || allocated.contains(partial)
        }
        let stem = sourceStem(media.displayName)
        let ext = resolved.preset == "image-compress"
            ? keptImageExtension(media.displayName)
            : resolved.extension
        let count = outputCount(preset: resolved.preset, media: media)
        var outputPaths: [String] = []
        if count > 1 {
            for index in 1...count {
                let numbered = numberedOutputName(stem: stem, index: index, ext: ext)
                let path = allocateOutputPath(
                    outputDir: outputDir,
                    stem: sourceStem(numbered),
                    ext: ext,
                    exists: isTaken
                )
                outputPaths.append(path)
                allocated.insert(path)
                allocated.insert(partialOutputPath(path))
            }
        } else {
            let path = allocateOutputPath(outputDir: outputDir, stem: stem, ext: ext, exists: isTaken)
            outputPaths.append(path)
            allocated.insert(path)
            allocated.insert(partialOutputPath(path))
        }
        jobs.append(Job(
            id: nextId(),
            sourceUri: media.sourceUri,
            displayName: media.displayName,
            outputPath: outputPaths[0],
            status: .queued,
            progress: 0,
            error: nil,
            config: configForSource(config, media: media),
            media: media,
            outputPaths: outputPaths,
            outputKind: outputKind,
            createdAtEpochMs: nowMs()
        ))
    }
    return EnqueueReport(jobs: jobs, skipped: skipped)
}

public func markInterrupted(_ jobs: [Job], interrupted: String = "Conversion was interrupted") -> [Job] {
    jobs.map { job in
        job.status == .running
            ? Job(
                id: job.id,
                sourceUri: job.sourceUri,
                displayName: job.displayName,
                outputPath: job.outputPath,
                status: .failed,
                progress: job.progress,
                error: interrupted,
                config: job.config,
                media: job.media,
                outputPaths: job.outputPaths,
                outputKind: job.outputKind,
                createdAtEpochMs: job.createdAtEpochMs
            )
            : job
    }
}
