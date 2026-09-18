package com.videoconverter.android.ui

import android.app.Application
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.videoconverter.android.R
import com.videoconverter.android.data.JobStore
import com.videoconverter.android.data.OutputStore
import com.videoconverter.android.data.OutputTarget
import com.videoconverter.android.data.SessionSettings
import com.videoconverter.android.data.SessionStore
import com.videoconverter.android.data.exportedLocations
import com.videoconverter.android.data.stampJobOutputTarget
import com.videoconverter.android.domain.DocumentSourceKind
import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.MediaInfo
import com.videoconverter.android.domain.OutputConfig
import com.videoconverter.android.domain.canRenameJob
import com.videoconverter.android.domain.clampPageRange
import com.videoconverter.android.domain.defaultDocumentPreset
import com.videoconverter.android.domain.documentExtension
import com.videoconverter.android.domain.documentSourceKind
import com.videoconverter.android.domain.enqueueDocumentJobs
import com.videoconverter.android.domain.enqueueJobs
import com.videoconverter.android.domain.isDocumentPreset
import com.videoconverter.android.domain.isVideoConcatPreset
import com.videoconverter.android.domain.renamedFileName
import com.videoconverter.android.domain.resolveConfig
import com.videoconverter.android.domain.sameDocumentKind
import com.videoconverter.android.domain.sanitizeRenameStem
import com.videoconverter.android.domain.unsupportedDocumentReason
import com.videoconverter.android.domain.validateCopy
import com.videoconverter.android.engine.FfmpegProcess
import com.videoconverter.android.service.TranscodeService
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class AppUiState(
    val video: WizardSession = defaultVideoSession(),
    val audio: WizardSession = defaultAudioSession(),
    val document: WizardSession = defaultDocumentSession(),
    val jobs: List<Job> = emptyList(),
    val message: String? = null,
)

fun AppUiState.sessions(): WizardSessions = WizardSessions(video, audio, document)

enum class StartAction { StartPump, Enqueue }

fun chooseStartAction(hasQueuedJobs: Boolean, sourcesChanged: Boolean): StartAction =
    if (hasQueuedJobs && !sourcesChanged) StartAction.StartPump else StartAction.Enqueue

fun sourcesChangedFor(
    videoChanged: Boolean,
    audioChanged: Boolean,
    mode: ConvertMode,
    documentChanged: Boolean = false,
): Boolean =
    when (mode) {
        ConvertMode.Video -> videoChanged
        ConvertMode.Audio -> audioChanged
        ConvertMode.Document -> documentChanged
    }

fun emptyStartReasonRes(mode: ConvertMode): Int = when (mode) {
    ConvertMode.Audio -> R.string.error_add_audio_first
    ConvertMode.Document -> R.string.error_add_document_first
    ConvertMode.Video -> R.string.error_add_video_first
}

fun applyProbedSource(media: MediaInfo, mode: ConvertMode, noAudioError: String): MediaInfo =
    if (mode == ConvertMode.Audio) restrictAudioSource(media, noAudioError) else media

fun videoSessionFromSettings(settings: SessionSettings): WizardSession = defaultVideoSession().copy(
    preset = coerceVideoPreset(settings.preset),
    quality = settings.quality ?: SessionStore.DEFAULT_QUALITY,
    size = sizeFor(settings.maxWidth, settings.maxHeight),
    output = coerceVideoOutput(settings.output),
)

fun shouldPersistOutputForMode(mode: ConvertMode): Boolean = mode == ConvertMode.Video

suspend fun persistOutputBeforeStart(
    output: OutputTarget,
    persist: suspend (OutputTarget) -> Unit,
    start: () -> Unit,
) {
    persist(output)
    start()
}

fun sourceDisplayNameOrUntitled(queryName: String?, lastPathSegment: String?, untitled: String): String {
    val queried = queryName?.takeIf { it.isNotBlank() }
    if (queried != null) return queried
    val segment = lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    return segment ?: untitled
}

fun outputMimeType(config: OutputConfig): String {
    if (isDocumentPreset(config.preset)) {
        return when (documentExtension(config.preset, config.container)) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "gif" -> "image/gif"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            else -> "*/*"
        }
    }
    return resolveConfig(config).fold(
        onSuccess = {
            when (it.container) {
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "wav" -> "audio/wav"
                "ogg" -> "audio/ogg"
                "flac" -> "audio/flac"
                "amr" -> "audio/amr"
                "gif" -> "image/gif"
                "mp4" -> "video/mp4"
                "mov" -> "video/quicktime"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "avi" -> "video/x-msvideo"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "bmp" -> "image/bmp"
                "pdf" -> "application/pdf"
                "txt" -> "text/plain"
                else -> "video/*"
            }
        },
        onFailure = { "video/*" },
    )
}

internal fun configureViewIntent(intent: Intent, uri: Uri, mimeType: String): Intent =
    intent.apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

fun resolutionBounds(size: String): Pair<Int?, Int?> = when (size) {
    "1080p" -> 1920 to 1080
    "720p" -> 1280 to 720
    "480p" -> 854 to 480
    else -> null to null
}

fun shouldShowResolution(preset: String): Boolean =
    !preset.startsWith("audio-") &&
        preset != "mp4-copy" &&
        !isDocumentPreset(preset) &&
        !isVideoConcatPreset(preset)

fun effectiveResolution(preset: String, size: String): Pair<Int?, Int?> =
    if (shouldShowResolution(preset)) resolutionBounds(size) else null to null

fun statusLabelRes(status: JobStatus): Int = when (status) {
    JobStatus.Queued -> R.string.status_queued
    JobStatus.Running -> R.string.status_running
    JobStatus.Completed -> R.string.status_completed
    JobStatus.Failed -> R.string.status_failed
    JobStatus.Cancelled -> R.string.status_cancelled
}

fun statusLabel(context: android.content.Context, status: JobStatus): String =
    context.getString(statusLabelRes(status))

fun supportsSystemPreview(media: MediaInfo): Boolean {
    val extension = media.displayName.substringAfterLast('.', "").lowercase()
    return extension in setOf("mp4", "m4v", "mov", "webm")
}

fun canPlayPreview(media: MediaInfo): Boolean {
    val extension = media.displayName.substringAfterLast('.', "").lowercase()
    return supportsSystemPreview(media) ||
        extension in setOf("mp3", "m4a", "aac", "wav", "ogg", "flac", "opus", "amr")
}

fun showVideoSurface(media: MediaInfo): Boolean =
    media.videoCodec != null && supportsSystemPreview(media)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.applicationContext
    private val sessionStore = SessionStore(app)
    private val jobStore = JobStore(app)
    private val outputStore = OutputStore(app)
    private val ffmpeg = FfmpegProcess(app)
    private val probeSlots = Semaphore(4)
    private val mutableState = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()
    private var videoSourcesChanged = false
    private var audioSourcesChanged = false
    private var documentSourcesChanged = false

    init {
        viewModelScope.launch {
            val settings = sessionStore.load()
            mutableState.value = mutableState.value.copy(
                jobs = withContext(Dispatchers.IO) { jobStore.load() },
                video = videoSessionFromSettings(settings),
            )
            while (true) {
                delay(500)
                val jobs = withContext(Dispatchers.IO) { jobStore.load() }
                if (jobs != mutableState.value.jobs) {
                    mutableState.value = mutableState.value.copy(jobs = jobs)
                }
            }
        }
    }

    fun addUris(uris: List<Uri>, mode: ConvertMode) {
        if (mode == ConvertMode.Document) {
            addDocumentUris(uris)
            return
        }
        val existing = currentSession(mode).sources.map { it.media.sourceUri }.toSet()
        uris.distinctBy(Uri::toString).filterNot { it.toString() in existing }.forEach { uri ->
            takeReadPermission(uri)
            val placeholder = MediaInfo(
                sourceUri = uri.toString(),
                displayName = displayName(uri),
            )
            updateSession(mode) { session ->
                session.copy(sources = session.sources + SourceItem(placeholder, probing = true))
            }
            mutableState.value = mutableState.value.copy(message = null)
            markSourcesChanged(mode, true)
            viewModelScope.launch {
                val probed = probeSlots.withPermit {
                    runCatching { ffmpeg.probe(uri, placeholder.displayName) }
                        .getOrElse {
                            placeholder.copy(error = it.message ?: app.getString(R.string.error_cannot_read_media))
                        }
                }
                val result = applyProbedSource(probed, mode, app.getString(R.string.error_no_audio_stream))
                updateSession(mode) { session ->
                    session.copy(
                        sources = session.sources.map {
                            if (it.media.sourceUri == uri.toString()) SourceItem(result, false) else it
                        },
                    )
                }
            }
        }
    }

    fun remove(uri: String, mode: ConvertMode) {
        val running = mutableState.value.jobs.any {
            it.sourceUri == uri && it.status == JobStatus.Running
        }
        if (running) return
        updateSession(mode) { session ->
            session.copy(sources = session.sources.filterNot { it.media.sourceUri == uri })
        }
        markSourcesChanged(mode, true)
    }

    fun moveSource(from: Int, to: Int, mode: ConvertMode) {
        updateSession(mode) { session ->
            session.copy(sources = movedItems(session.sources, from, to))
        }
        markSourcesChanged(mode, true)
    }

    fun clearSources(mode: ConvertMode) {
        updateSession(mode) { it.copy(sources = emptyList()) }
        markSourcesChanged(mode, true)
    }

    fun updateTrim(updated: MediaInfo, mode: ConvertMode) {
        updateSession(mode) { session ->
            session.copy(
                sources = session.sources.map {
                    if (it.media.sourceUri == updated.sourceUri) it.copy(media = updated) else it
                },
            )
        }
        markSourcesChanged(mode, true)
    }

    fun setContainer(container: String, mode: ConvertMode) {
        updateSession(mode) { it.copy(container = container) }
        markSourcesChanged(mode, true)
    }

    fun setPreset(preset: String, mode: ConvertMode) = updateSettings(mode, preset = preset)

    fun setQuality(quality: String, mode: ConvertMode) = updateSettings(mode, quality = quality)

    fun setSize(size: String, mode: ConvertMode) {
        updateSession(mode) { it.copy(size = size) }
        persistSettings(mode)
        markSourcesChanged(mode, true)
    }

    fun pickOutputTree(uri: Uri, mode: ConvertMode) {
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val output = OutputTarget(OutputTarget.Kind.SafTree, uri.toString())
        updateSession(mode) { it.copy(output = output) }
        persistOutput(mode, output)
    }

    fun setOutputChoice(id: String, mode: ConvertMode) {
        val kind = outputKindForChoice(id) ?: return
        val output = OutputTarget(kind)
        updateSession(mode) { it.copy(output = output) }
        persistOutput(mode, output)
    }

    fun start(mode: ConvertMode): Boolean {
        val snapshot = mutableState.value
        val session = sessionFor(snapshot.sessions(), mode)
        val queued = snapshot.jobs.any { it.status == JobStatus.Queued }
        if (chooseStartAction(queued, sourcesChangedFor(mode)) == StartAction.StartPump) {
            viewModelScope.launch {
                persistOutputBeforeStart(
                    output = session.output,
                    persist = { if (shouldPersistOutputForMode(mode)) persistOutputTarget(it) },
                    start = { TranscodeService.startPump(app) },
                )
            }
            return true
        }
        if (session.sources.any { it.probing }) {
            mutableState.value = snapshot.copy(message = app.getString(R.string.error_wait_probe))
            return false
        }
        val bounds = effectiveResolution(session.preset, session.size)
        val report = if (mode == ConvertMode.Document) {
            enqueueDocumentJobs(
                sources = session.sources.map { item -> documentMediaForEnqueue(item.media) },
                config = OutputConfig(
                    preset = session.preset,
                    quality = session.quality,
                    container = session.container,
                ),
                outputDir = File(app.filesDir, "planned").absolutePath,
                nextId = { UUID.randomUUID().toString() },
                exists = { File(it).exists() },
                cannotTranscode = app.getString(R.string.error_cannot_transcode),
                selectOutput = app.getString(R.string.error_select_output),
                cannotReadPages = app.getString(R.string.error_cannot_read_pages),
            )
        } else {
            enqueueJobs(
                sources = session.sources.map { it.media },
                config = OutputConfig(
                    preset = session.preset,
                    quality = session.quality,
                    maxWidth = bounds.first,
                    maxHeight = bounds.second,
                ),
                outputDir = File(app.filesDir, "planned").absolutePath,
                nextId = { UUID.randomUUID().toString() },
                exists = { File(it).exists() },
                cannotTranscode = app.getString(R.string.error_cannot_transcode),
                selectOutput = app.getString(R.string.error_select_output),
                validateCopy = validateCopy(app.resources),
                unknownPreset = { app.getString(R.string.error_unknown_preset, it) },
                concatNeedsTwo = app.getString(R.string.concat_need_two),
                concatTooMany = app.getString(R.string.concat_too_many),
                concatMissingVideo = app.getString(R.string.concat_missing_video),
            )
        }.getOrElse {
            mutableState.value = snapshot.copy(message = it.message ?: app.getString(R.string.error_cannot_create_job))
            return false
        }
        if (report.jobs.isEmpty()) {
            val reason = report.skipped.firstOrNull()?.reason ?: app.getString(emptyStartReasonRes(mode))
            mutableState.value = snapshot.copy(message = reason)
            return false
        }
        val stamped = report.jobs.map { stampJobOutputTarget(it, session.output) }
        mutableState.value = snapshot.copy(
            jobs = snapshot.jobs + stamped,
            message = report.skipped.firstOrNull()?.reason,
        )
        markSourcesChanged(mode, false)
        viewModelScope.launch {
            persistOutputBeforeStart(
                output = session.output,
                persist = { if (shouldPersistOutputForMode(mode)) persistOutputTarget(it) },
                start = { TranscodeService.enqueue(app, stamped) },
            )
        }
        return true
    }

    fun cancel(jobId: String) = TranscodeService.cancel(app, jobId)

    fun retry(jobId: String) = TranscodeService.retry(app, jobId)

    fun clearFinished(segment: HistorySegment) {
        viewModelScope.launch(Dispatchers.IO) {
            jobStore.update { remainingJobsAfterClearFinished(it, segment) }
        }
    }

    fun delete(jobId: String) {
        val job = mutableState.value.jobs.find { it.id == jobId } ?: return
        if (job.status == JobStatus.Queued || job.status == JobStatus.Running) {
            TranscodeService.cancel(app, jobId)
        }
        viewModelScope.launch(Dispatchers.IO) {
            exportedLocations(job).forEach { path ->
                runCatching { outputStore.deleteExported(path) }
            }
            jobStore.update { jobs -> jobs.filterNot { it.id == jobId } }
        }
    }

    fun rename(jobId: String, rawName: String) {
        val stem = sanitizeRenameStem(rawName)
        if (stem == null) {
            mutableState.value = mutableState.value.copy(message = app.getString(R.string.error_invalid_filename))
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                jobStore.update { jobs ->
                    jobs.map { job ->
                        if (job.id != jobId || !canRenameJob(job.status)) job
                        else {
                            val newName = renamedFileName(job.displayName, job.outputPath, stem)
                            val newPath = job.outputPath?.let { outputStore.renameExported(it, newName) }
                            job.copy(displayName = newName, outputPath = newPath ?: job.outputPath)
                        }
                    }
                }
            }.onFailure {
                mutableState.value = mutableState.value.copy(message = it.message ?: app.getString(R.string.error_cannot_rename))
            }
        }
    }

    fun outputIntent(job: Job, share: Boolean): Intent? {
        val location = job.outputPath ?: return null
        val parsed = Uri.parse(location)
        val uri = if (parsed.scheme == "content") {
            parsed
        } else {
            runCatching {
                FileProvider.getUriForFile(app, "${app.packageName}.files", File(location))
            }.getOrNull() ?: return null
        }
        return if (share) {
            Intent(Intent.ACTION_SEND).apply {
                type = outputMimeType(job.config)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            configureViewIntent(
                Intent(Intent.ACTION_VIEW),
                uri,
                outputMimeType(job.config),
            )
        }
    }

    fun clearMessage() {
        mutableState.value = mutableState.value.copy(message = null)
    }

    private fun addDocumentUris(uris: List<Uri>) {
        val session = currentSession(ConvertMode.Document)
        val existingUris = session.sources.map { it.media.sourceUri }.toSet()
        val acceptedNames = session.sources.map { it.media.displayName }.toMutableList()
        val accepted = mutableListOf<Uri>()
        var mixed = false
        uris.distinctBy(Uri::toString).filterNot { it.toString() in existingUris }.forEach { uri ->
            val name = displayName(uri)
            if (!sameDocumentKind(acceptedNames, name)) {
                mixed = true
            } else {
                accepted += uri
                acceptedNames += name
            }
        }
        if (mixed) {
            mutableState.value = mutableState.value.copy(message = app.getString(R.string.error_mixed_document_types))
        }
        if (accepted.isEmpty()) return
        val previousKind = documentKindOf(session.sources)
        accepted.forEach { uri ->
            takeReadPermission(uri)
            val placeholder = MediaInfo(
                sourceUri = uri.toString(),
                displayName = displayName(uri),
            )
            updateSession(ConvertMode.Document) { current ->
                alignDocumentSession(
                    current.copy(sources = current.sources + SourceItem(placeholder, probing = true)),
                    previousKind,
                )
            }
            if (!mixed) {
                mutableState.value = mutableState.value.copy(message = null)
            }
            markSourcesChanged(ConvertMode.Document, true)
            viewModelScope.launch {
                val probed = probeSlots.withPermit { probeDocument(uri, placeholder.displayName) }
                updateSession(ConvertMode.Document) { current ->
                    current.copy(
                        sources = current.sources.map {
                            if (it.media.sourceUri == uri.toString()) SourceItem(probed, false) else it
                        },
                    )
                }
            }
        }
    }

    private fun probeDocument(uri: Uri, displayName: String): MediaInfo {
        val kind = documentSourceKind(displayName)
        val unsupported = unsupportedDocumentReason(
            displayName,
            app.getString(R.string.error_unsupported_format),
        )
        if (kind == null || unsupported != null) {
            return MediaInfo(
                sourceUri = uri.toString(),
                displayName = displayName,
                importable = false,
                error = unsupported ?: app.getString(R.string.error_unsupported_format),
            )
        }
        return when (kind) {
            DocumentSourceKind.Pdf -> probePdf(uri, displayName)
            DocumentSourceKind.Image, DocumentSourceKind.Word ->
                MediaInfo(
                    sourceUri = uri.toString(),
                    displayName = displayName,
                    importable = true,
                )
        }
    }

    private fun probePdf(uri: Uri, displayName: String): MediaInfo =
        runCatching {
            app.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val count = renderer.pageCount
                    require(count >= 1)
                    MediaInfo(
                        sourceUri = uri.toString(),
                        displayName = displayName,
                        importable = true,
                        pageCount = count,
                        pageStart = 1,
                        pageEnd = count,
                    )
                }
            } ?: error(app.getString(R.string.error_cannot_read_pages))
        }.getOrElse {
            MediaInfo(
                sourceUri = uri.toString(),
                displayName = displayName,
                importable = false,
                error = app.getString(R.string.error_cannot_read_pages),
            )
        }

    private fun updateSession(mode: ConvertMode, transform: (WizardSession) -> WizardSession) {
        val snapshot = mutableState.value
        val current = sessionFor(snapshot.sessions(), mode)
        val next = replaceSession(snapshot.sessions(), mode, transform(current))
        mutableState.value = snapshot.copy(video = next.video, audio = next.audio, document = next.document)
    }

    private fun currentSession(mode: ConvertMode): WizardSession {
        val snapshot = mutableState.value
        return sessionFor(snapshot.sessions(), mode)
    }

    private fun markSourcesChanged(mode: ConvertMode, changed: Boolean) {
        when (mode) {
            ConvertMode.Video -> videoSourcesChanged = changed
            ConvertMode.Audio -> audioSourcesChanged = changed
            ConvertMode.Document -> documentSourcesChanged = changed
        }
    }

    private fun sourcesChangedFor(mode: ConvertMode): Boolean =
        sourcesChangedFor(videoSourcesChanged, audioSourcesChanged, mode, documentSourcesChanged)

    private fun updateSettings(mode: ConvertMode, preset: String? = null, quality: String? = null) {
        updateSession(mode) { session ->
            val nextPreset = preset ?: session.preset
            var next = session.copy(
                preset = nextPreset,
                quality = quality ?: session.quality,
            )
            if (mode == ConvertMode.Document) {
                val container = when (nextPreset) {
                    "pdf-image" -> session.container?.takeIf { it in IMAGE_OUTPUT_CONTAINERS } ?: "jpg"
                    else -> session.container
                }
                val output = if (next.output.kind in allowedDocumentOutputKinds(nextPreset)) {
                    next.output
                } else {
                    defaultDocumentOutput(nextPreset)
                }
                next = next.copy(container = container, output = output)
            }
            next
        }
        persistSettings(mode)
        markSourcesChanged(mode, true)
    }

    private fun persistSettings(mode: ConvertMode) {
        if (mode != ConvertMode.Video) return
        val session = mutableState.value.video
        val (maxWidth, maxHeight) = resolutionBounds(session.size)
        viewModelScope.launch {
            sessionStore.save(
                SessionSettings(
                    preset = session.preset,
                    quality = session.quality,
                    maxWidth = maxWidth,
                    maxHeight = maxHeight,
                    output = session.output,
                ),
            )
        }
    }

    private fun persistOutput(mode: ConvertMode, output: OutputTarget) {
        if (mode != ConvertMode.Video) return
        viewModelScope.launch { persistOutputTarget(output) }
    }

    private suspend fun persistOutputTarget(output: OutputTarget) {
        sessionStore.saveOutputTarget(output)
    }

    private fun displayName(uri: Uri): String =
        app.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }.let { sourceDisplayNameOrUntitled(it, uri.lastPathSegment, app.getString(R.string.untitled)) }

    private fun takeReadPermission(uri: Uri) {
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }
}

private fun sizeFor(width: Int?, height: Int?): String = when (width to height) {
    1920 to 1080 -> "1080p"
    1280 to 720 -> "720p"
    854 to 480 -> "480p"
    else -> "original"
}

private val IMAGE_OUTPUT_CONTAINERS = setOf("jpg", "png", "webp")

private fun alignDocumentSession(
    session: WizardSession,
    previousKind: DocumentSourceKind?,
): WizardSession {
    val kind = documentKindOf(session.sources) ?: return session
    if (kind == previousKind) return session
    val preset = defaultDocumentPreset(kind)
    val output = if (session.output.kind in allowedDocumentOutputKinds(preset)) {
        session.output
    } else {
        defaultDocumentOutput(preset)
    }
    val container = when (preset) {
        "pdf-image", "image-jpg" -> session.container?.takeIf { it in IMAGE_OUTPUT_CONTAINERS } ?: "jpg"
        else -> session.container
    }
    return session.copy(preset = preset, output = output, container = container)
}

private fun documentMediaForEnqueue(media: MediaInfo): MediaInfo {
    val pages = media.pageCount ?: return media
    val (start, end) = clampPageRange(media.pageStart ?: 1, media.pageEnd ?: pages, pages)
    return media.copy(pageStart = start, pageEnd = end)
}
