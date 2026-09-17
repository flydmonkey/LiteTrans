package com.videoconverter.android.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.videoconverter.android.data.JobStore
import com.videoconverter.android.data.OutputStore
import com.videoconverter.android.data.OutputTarget
import com.videoconverter.android.data.SessionSettings
import com.videoconverter.android.data.SessionStore
import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.MediaInfo
import com.videoconverter.android.domain.OutputConfig
import com.videoconverter.android.domain.canRenameJob
import com.videoconverter.android.domain.enqueueJobs
import com.videoconverter.android.domain.renamedFileName
import com.videoconverter.android.domain.resolveConfig
import com.videoconverter.android.domain.sanitizeRenameStem
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

data class SourceItem(val media: MediaInfo, val probing: Boolean)

data class AppUiState(
    val sources: List<SourceItem> = emptyList(),
    val jobs: List<Job> = emptyList(),
    val preset: String = SessionStore.DEFAULT_PRESET,
    val quality: String = SessionStore.DEFAULT_QUALITY,
    val size: String = "original",
    val output: OutputTarget = OutputTarget(OutputTarget.Kind.Downloads),
    val message: String? = null,
)

enum class StartAction { StartPump, Enqueue }

fun chooseStartAction(hasQueuedJobs: Boolean, sourcesChanged: Boolean): StartAction =
    if (hasQueuedJobs && !sourcesChanged) StartAction.StartPump else StartAction.Enqueue

suspend fun persistOutputBeforeStart(
    output: OutputTarget,
    persist: suspend (OutputTarget) -> Unit,
    start: () -> Unit,
) {
    persist(output)
    start()
}

fun outputMimeType(config: OutputConfig): String =
    resolveConfig(config).fold(
        onSuccess = {
            when (it.container) {
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "gif" -> "image/gif"
                "mp4" -> "video/mp4"
                "mov" -> "video/quicktime"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "avi" -> "video/x-msvideo"
                else -> "video/*"
            }
        },
        onFailure = { "video/*" },
    )

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
    !preset.startsWith("audio-") && preset != "mp4-copy"

fun effectiveResolution(preset: String, size: String): Pair<Int?, Int?> =
    if (shouldShowResolution(preset)) resolutionBounds(size) else null to null

fun statusLabel(status: JobStatus): String = when (status) {
    JobStatus.Queued -> "排队中"
    JobStatus.Running -> "正在转码"
    JobStatus.Completed -> "已完成"
    JobStatus.Failed -> "出错了"
    JobStatus.Cancelled -> "已取消"
}

fun supportsSystemPreview(media: MediaInfo): Boolean {
    val extension = media.displayName.substringAfterLast('.', "").lowercase()
    return extension in setOf("mp4", "m4v", "mov", "webm")
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.applicationContext
    private val sessionStore = SessionStore(app)
    private val jobStore = JobStore(app)
    private val outputStore = OutputStore(app)
    private val ffmpeg = FfmpegProcess(app)
    private val probeSlots = Semaphore(4)
    private val mutableState = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()
    private var sourcesChanged = false

    init {
        viewModelScope.launch {
            val settings = sessionStore.load()
            mutableState.value = mutableState.value.copy(
                jobs = withContext(Dispatchers.IO) { jobStore.load() },
                preset = settings.preset ?: SessionStore.DEFAULT_PRESET,
                quality = settings.quality ?: SessionStore.DEFAULT_QUALITY,
                size = sizeFor(settings.maxWidth, settings.maxHeight),
                output = settings.output,
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

    fun addUris(uris: List<Uri>) {
        val existing = mutableState.value.sources.map { it.media.sourceUri }.toSet()
        uris.distinctBy(Uri::toString).filterNot { it.toString() in existing }.forEach { uri ->
            takeReadPermission(uri)
            val placeholder = MediaInfo(
                sourceUri = uri.toString(),
                displayName = displayName(uri),
            )
            mutableState.value = mutableState.value.copy(
                sources = mutableState.value.sources + SourceItem(placeholder, probing = true),
                message = null,
            )
            sourcesChanged = true
            viewModelScope.launch {
                val result = probeSlots.withPermit {
                    runCatching { ffmpeg.probe(uri, placeholder.displayName) }
                        .getOrElse {
                            placeholder.copy(error = it.message ?: "无法读取媒体信息")
                        }
                }
                mutableState.value = mutableState.value.copy(
                    sources = mutableState.value.sources.map {
                        if (it.media.sourceUri == uri.toString()) SourceItem(result, false) else it
                    },
                )
            }
        }
    }

    fun remove(uri: String) {
        val running = mutableState.value.jobs.any {
            it.sourceUri == uri && it.status == JobStatus.Running
        }
        if (running) return
        mutableState.value = mutableState.value.copy(
            sources = mutableState.value.sources.filterNot { it.media.sourceUri == uri },
        )
        sourcesChanged = true
    }

    fun clearSources() {
        mutableState.value = mutableState.value.copy(sources = emptyList())
        sourcesChanged = true
    }

    fun updateTrim(updated: MediaInfo) {
        mutableState.value = mutableState.value.copy(
            sources = mutableState.value.sources.map {
                if (it.media.sourceUri == updated.sourceUri) it.copy(media = updated) else it
            },
        )
        sourcesChanged = true
    }

    fun setPreset(preset: String) = updateSettings(preset = preset)

    fun setQuality(quality: String) = updateSettings(quality = quality)

    fun setSize(size: String) {
        val (width, height) = resolutionBounds(size)
        mutableState.value = mutableState.value.copy(size = size)
        persistSettings(maxWidth = width, maxHeight = height)
        sourcesChanged = true
    }

    fun pickOutputTree(uri: Uri) {
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val output = OutputTarget(OutputTarget.Kind.SafTree, uri.toString())
        mutableState.value = mutableState.value.copy(output = output)
        viewModelScope.launch { sessionStore.saveOutputTarget(output) }
    }

    fun setOutputChoice(id: String) {
        val kind = outputKindForChoice(id) ?: return
        val output = OutputTarget(kind)
        mutableState.value = mutableState.value.copy(output = output)
        viewModelScope.launch { sessionStore.saveOutputTarget(output) }
    }

    fun start(): Boolean {
        val snapshot = mutableState.value
        val queued = snapshot.jobs.any { it.status == JobStatus.Queued }
        if (chooseStartAction(queued, sourcesChanged) == StartAction.StartPump) {
            viewModelScope.launch {
                persistOutputBeforeStart(
                    output = snapshot.output,
                    persist = sessionStore::saveOutputTarget,
                    start = { TranscodeService.startPump(app) },
                )
            }
            return true
        }
        if (snapshot.sources.any { it.probing }) {
            mutableState.value = snapshot.copy(message = "请等待格式读取完成")
            return false
        }
        val bounds = effectiveResolution(snapshot.preset, snapshot.size)
        val config = OutputConfig(
            preset = snapshot.preset,
            quality = snapshot.quality,
            maxWidth = bounds.first,
            maxHeight = bounds.second,
        )
        val report = enqueueJobs(
            sources = snapshot.sources.map { it.media },
            config = config,
            outputDir = File(app.filesDir, "planned").absolutePath,
            nextId = { UUID.randomUUID().toString() },
            exists = { File(it).exists() },
        ).getOrElse {
            mutableState.value = snapshot.copy(message = it.message ?: "无法创建转码任务")
            return false
        }
        if (report.jobs.isEmpty()) {
            val reason = report.skipped.firstOrNull()?.reason ?: "请先添加可转码的视频"
            mutableState.value = snapshot.copy(message = reason)
            return false
        }
        mutableState.value = snapshot.copy(
            jobs = snapshot.jobs + report.jobs,
            message = report.skipped.firstOrNull()?.reason,
        )
        sourcesChanged = false
        viewModelScope.launch {
            persistOutputBeforeStart(
                output = snapshot.output,
                persist = sessionStore::saveOutputTarget,
                start = { TranscodeService.enqueue(app, report.jobs) },
            )
        }
        return true
    }

    fun cancel(jobId: String) = TranscodeService.cancel(app, jobId)

    fun retry(jobId: String) = TranscodeService.retry(app, jobId)

    fun clearFinished() = TranscodeService.clearFinished(app)

    fun delete(jobId: String) {
        val job = mutableState.value.jobs.find { it.id == jobId } ?: return
        if (job.status == JobStatus.Queued || job.status == JobStatus.Running) {
            TranscodeService.cancel(app, jobId)
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { outputStore.deleteExported(job.outputPath) }
            jobStore.update { jobs -> jobs.filterNot { it.id == jobId } }
        }
    }

    fun rename(jobId: String, rawName: String) {
        val stem = sanitizeRenameStem(rawName)
        if (stem == null) {
            mutableState.value = mutableState.value.copy(message = "请输入可用的文件名")
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
                mutableState.value = mutableState.value.copy(message = it.message ?: "无法重命名")
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

    private fun updateSettings(preset: String? = null, quality: String? = null) {
        mutableState.value = mutableState.value.copy(
            preset = preset ?: mutableState.value.preset,
            quality = quality ?: mutableState.value.quality,
        )
        persistSettings()
        sourcesChanged = true
    }

    private fun persistSettings(maxWidth: Int? = resolutionBounds(state.value.size).first,
                                maxHeight: Int? = resolutionBounds(state.value.size).second) {
        val snapshot = mutableState.value
        viewModelScope.launch {
            sessionStore.save(
                SessionSettings(
                    preset = snapshot.preset,
                    quality = snapshot.quality,
                    maxWidth = maxWidth,
                    maxHeight = maxHeight,
                    output = snapshot.output,
                ),
            )
        }
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
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "视频"

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
