package com.videoconverter.android.domain

fun splitImportable(
    sources: List<MediaInfo>,
    cannotTranscode: String = "Could not convert this file",
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
                reason = source.error ?: cannotTranscode,
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
    cannotTranscode: String = "Could not convert this file",
    selectOutput: String = "Choose an output folder first",
    validateCopy: ValidateCopy = ValidateCopy(),
    unknownPreset: (String) -> String = { "Unknown preset: $it" },
    clock: () -> String = ::outputCollisionStamp,
    nowMs: () -> Long = { System.currentTimeMillis() },
    concatNeedsTwo: String = "Add at least two videos",
    concatTooMany: String = "You can merge up to 20 videos",
    concatMissingVideo: String = "Each clip needs a video track",
): Result<EnqueueReport> = runCatching {
    if (outputDir.isBlank()) {
        throw IllegalArgumentException(selectOutput)
    }

    val resolved = resolveConfig(config, unknownPreset).getOrThrow()
    if (isVideoConcatPreset(config.preset)) {
        return@runCatching enqueueConcatJobs(
            sources = sources,
            config = config,
            resolved = resolved,
            outputDir = outputDir,
            nextId = nextId,
            exists = exists,
            cannotTranscode = cannotTranscode,
            validateCopy = validateCopy,
            clock = clock,
            nowMs = nowMs,
            concatNeedsTwo = concatNeedsTwo,
            concatTooMany = concatTooMany,
            concatMissingVideo = concatMissingVideo,
        )
    }
    val (accepted, initialSkipped) = splitImportable(sources, cannotTranscode)
    val skipped = initialSkipped.toMutableList()
    val jobs = mutableListOf<Job>()
    val allocated = mutableSetOf<String>()

    for (media in accepted) {
        val validation = validate(resolved, media, validateCopy)
        if (validation.isFailure) {
            skipped += SkippedSource(
                sourceUri = media.sourceUri,
                displayName = media.displayName,
                reason = validation.exceptionOrNull()?.message ?: cannotTranscode,
            )
            continue
        }

        val outputPath = allocateOutputPath(
            outputDir = outputDir,
            stem = sourceStem(media.displayName),
            ext = resolved.extension,
            exists = { candidate ->
                val partial = partialOutputPath(candidate)
                exists(candidate) ||
                    exists(partial) ||
                    candidate in allocated ||
                    partial in allocated
            },
            clock = clock,
        )
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
            createdAtEpochMs = nowMs(),
        )
    }

    EnqueueReport(jobs = jobs, skipped = skipped)
}

private fun enqueueConcatJobs(
    sources: List<MediaInfo>,
    config: OutputConfig,
    resolved: ResolvedConfig,
    outputDir: String,
    nextId: () -> String,
    exists: (String) -> Boolean,
    cannotTranscode: String,
    validateCopy: ValidateCopy,
    clock: () -> String,
    nowMs: () -> Long,
    concatNeedsTwo: String,
    concatTooMany: String,
    concatMissingVideo: String,
): EnqueueReport {
    val (accepted, skipped) = splitImportable(sources, cannotTranscode)
    if (skipped.isNotEmpty()) {
        return EnqueueReport(jobs = emptyList(), skipped = skipped)
    }
    if (accepted.size < 2) {
        return EnqueueReport(
            jobs = emptyList(),
            skipped = accepted.map {
                SkippedSource(it.sourceUri, it.displayName, concatNeedsTwo)
            },
        )
    }
    if (accepted.size > VIDEO_CONCAT_MAX_SOURCES) {
        return EnqueueReport(
            jobs = emptyList(),
            skipped = accepted.map {
                SkippedSource(it.sourceUri, it.displayName, concatTooMany)
            },
        )
    }
    val invalid = mutableListOf<SkippedSource>()
    for (media in accepted) {
        try {
            concatTarget(media, concatMissingVideo)
            if (media.videoCodec == null) {
                throw IllegalArgumentException(concatMissingVideo)
            }
            validate(resolved, media, validateCopy).getOrThrow()
        } catch (error: Exception) {
            invalid += SkippedSource(
                sourceUri = media.sourceUri,
                displayName = media.displayName,
                reason = error.message ?: cannotTranscode,
            )
        }
    }
    if (invalid.isNotEmpty()) {
        return EnqueueReport(jobs = emptyList(), skipped = invalid)
    }

    val outputPath = allocateOutputPath(
        outputDir = outputDir,
        stem = concatOutputStem(accepted[0].displayName),
        ext = resolved.extension,
        exists = { candidate ->
            val partial = partialOutputPath(candidate)
            exists(candidate) || exists(partial)
        },
        clock = clock,
    )
    val first = accepted[0]
    val job = Job(
        id = nextId(),
        sourceUri = first.sourceUri,
        displayName = first.displayName,
        outputPath = outputPath,
        status = JobStatus.Queued,
        progress = 0.0,
        error = null,
        config = config.copy(concatSourceUris = accepted.map { it.sourceUri }),
        media = first,
        createdAtEpochMs = nowMs(),
        concatMedias = accepted,
    )
    return EnqueueReport(jobs = listOf(job), skipped = emptyList())
}

fun enqueueDocumentJobs(
    sources: List<MediaInfo>,
    config: OutputConfig,
    outputDir: String,
    nextId: () -> String,
    exists: (String) -> Boolean,
    cannotTranscode: String = "Could not convert this file",
    selectOutput: String = "Choose an output folder first",
    cannotReadPages: String = "Could not read the page count",
    clock: () -> String = ::outputCollisionStamp,
    nowMs: () -> Long = { System.currentTimeMillis() },
): Result<EnqueueReport> = runCatching {
    if (outputDir.isBlank()) {
        throw IllegalArgumentException(selectOutput)
    }

    val (accepted, initialSkipped) = splitImportable(sources, cannotTranscode)
    val skipped = initialSkipped.toMutableList()
    val jobs = mutableListOf<Job>()
    val allocated = mutableSetOf<String>()
    val extension = documentExtension(config.preset, config.container)

    for (media in accepted) {
        if (needsPdfPageCount(config.preset) && media.pageCount == null) {
            skipped += SkippedSource(
                sourceUri = media.sourceUri,
                displayName = media.displayName,
                reason = cannotReadPages,
            )
            continue
        }

        val outputPath = allocateOutputPath(
            outputDir = outputDir,
            stem = sourceStem(media.displayName),
            ext = extension,
            exists = { candidate ->
                val partial = partialOutputPath(candidate)
                exists(candidate) ||
                    exists(partial) ||
                    candidate in allocated ||
                    partial in allocated
            },
            clock = clock,
        )
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
            createdAtEpochMs = nowMs(),
        )
    }

    EnqueueReport(jobs = jobs, skipped = skipped)
}

private fun needsPdfPageCount(preset: String): Boolean =
    preset == "pdf-image" ||
        preset == "pdf-txt" ||
        preset == "pdf-compress" ||
        preset == "pdf-split"

fun markInterrupted(
    jobs: List<Job>,
    interrupted: String = "Conversion was interrupted",
): List<Job> = jobs.map { job ->
    if (job.status == JobStatus.Running) {
        job.copy(status = JobStatus.Failed, error = interrupted)
    } else {
        job
    }
}
