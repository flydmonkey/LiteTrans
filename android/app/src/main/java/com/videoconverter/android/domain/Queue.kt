package com.videoconverter.android.domain

fun splitImportable(
    sources: List<MediaInfo>,
): Pair<List<MediaInfo>, List<SkippedSource>> {
    val accepted = mutableListOf<MediaInfo>()
    val skipped = mutableListOf<SkippedSource>()
    for (source in sources) {
        if (source.importable) {
            accepted += source
        } else {
            skipped += SkippedSource(
                sourceUri = source.sourceUri,
                displayName = source.displayName,
                reason = source.error ?: "无法转码该文件",
            )
        }
    }
    return accepted to skipped
}

fun configForSource(config: OutputConfig, media: MediaInfo): OutputConfig =
    if (media.trimStartSecs != null || media.trimEndSecs != null) {
        config.copy(
            trimStartSecs = media.trimStartSecs,
            trimEndSecs = media.trimEndSecs,
        )
    } else {
        config
    }

fun enqueueJobs(
    sources: List<MediaInfo>,
    config: OutputConfig,
    outputDir: String,
    nextId: () -> String,
    exists: (String) -> Boolean,
): Result<EnqueueReport> = runCatching {
    if (outputDir.isBlank()) {
        throw IllegalArgumentException("请先选择输出目录")
    }

    val resolved = resolveConfig(config).getOrThrow()
    val (accepted, initialSkipped) = splitImportable(sources)
    val skipped = initialSkipped.toMutableList()
    val jobs = mutableListOf<Job>()
    val allocated = mutableSetOf<String>()

    for (media in accepted) {
        val validation = validate(resolved, media)
        if (validation.isFailure) {
            skipped += SkippedSource(
                sourceUri = media.sourceUri,
                displayName = media.displayName,
                reason = validation.exceptionOrNull()?.message ?: "无法转码该文件",
            )
            continue
        }

        val outputPath = allocateOutputPath(
            outputDir = outputDir,
            stem = sourceStem(media.displayName),
            ext = resolved.extension,
        ) { candidate ->
            val partial = partialOutputPath(candidate)
            exists(candidate) ||
                exists(partial) ||
                candidate in allocated ||
                partial in allocated
        }
        allocated += outputPath
        allocated += partialOutputPath(outputPath)

        jobs += Job(
            id = nextId(),
            sourceUri = media.sourceUri,
            displayName = media.displayName,
            outputPath = outputPath,
            status = JobStatus.Queued,
            progress = 0.0,
            error = null,
            config = configForSource(config, media),
            media = media,
        )
    }

    EnqueueReport(jobs = jobs, skipped = skipped)
}

fun markInterrupted(jobs: List<Job>): List<Job> = jobs.map { job ->
    if (job.status == JobStatus.Running) {
        job.copy(status = JobStatus.Failed, error = "转码被中断")
    } else {
        job
    }
}
