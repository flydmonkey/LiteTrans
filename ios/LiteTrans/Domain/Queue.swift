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
        guard let path = job.outputPath else { continue }
        paths.insert(path)
        paths.insert(partialOutputPath(path))
    }
    return paths
}

public func outputFileDeletionPaths(job: Job, remainingJobs: [Job]) -> [String] {
    guard let path = job.outputPath else { return [] }
    if remainingJobs.contains(where: { $0.outputPath == path }) {
        return []
    }
    switch job.status {
    case .completed:
        return [path, partialOutputPath(path)]
    case .queued, .running, .failed, .cancelled:
        return []
    }
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
    existingJobs: [Job] = []
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
        do {
            try validate(resolved, media: media)
        } catch {
            skipped.append(.init(sourceUri: media.sourceUri, displayName: media.displayName, reason: error.localizedDescription))
            continue
        }
        let outputPath = allocateOutputPath(outputDir: outputDir, stem: sourceStem(media.displayName), ext: resolved.extension) { candidate in
            let partial = partialOutputPath(candidate)
            return exists(candidate) || exists(partial) || allocated.contains(candidate) || allocated.contains(partial)
        }
        allocated.insert(outputPath)
        allocated.insert(partialOutputPath(outputPath))
        jobs.append(Job(
            id: nextId(),
            sourceUri: media.sourceUri,
            displayName: media.displayName,
            outputPath: outputPath,
            status: .queued,
            progress: 0,
            error: nil,
            config: configForSource(config, media: media),
            media: media
        ))
    }
    return EnqueueReport(jobs: jobs, skipped: skipped)
}

public func markInterrupted(_ jobs: [Job], interrupted: String = "Conversion was interrupted") -> [Job] {
    jobs.map { job in
        job.status == .running ? Job(id: job.id, sourceUri: job.sourceUri, displayName: job.displayName, outputPath: job.outputPath, status: .failed, progress: job.progress, error: interrupted, config: job.config, media: job.media) : job
    }
}
