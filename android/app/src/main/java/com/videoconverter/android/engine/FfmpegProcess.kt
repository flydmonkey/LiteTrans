package com.videoconverter.android.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import com.videoconverter.android.data.ExportedOutput
import com.videoconverter.android.data.JobOutput
import com.videoconverter.android.data.OutputStore
import com.videoconverter.android.data.OutputTarget
import com.videoconverter.android.data.ResolvedInput
import com.videoconverter.android.data.SourceAccess
import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.MediaInfo
import com.videoconverter.android.domain.buildFfmpegArgs
import com.videoconverter.android.domain.ffmpegFileArg
import com.videoconverter.android.domain.outputDurationSecs
import com.videoconverter.android.domain.parseFfprobeJson
import com.videoconverter.android.domain.parseProgressLine
import com.videoconverter.android.domain.resolveConfig
import com.videoconverter.android.domain.sourceStem
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun shouldRetryWithoutHardware(stderr: String): Boolean {
    val normalized = stderr.lowercase()
    return "mediacodec" in normalized &&
        listOf("error", "fail", "not found", "cannot").any { it in normalized }
}

class FfmpegProcess(
    private val context: Context,
    private val sourceAccess: SourceAccess = SourceAccess(context),
    private val outputStore: OutputStore = OutputStore(context),
    private val configuredFfmpegPath: String? = null,
    private val configuredFfprobePath: String? = null,
) {
    private val activeProcess = ActiveProcessSlot()

    suspend fun transcode(
        job: Job,
        outputTarget: OutputTarget,
        onProgress: (Double) -> Unit = {},
    ): Job = withContext(Dispatchers.IO) {
        if (!activeProcess.claimOrConfirm(job.id)) {
            return@withContext job.copy(
                status = JobStatus.Failed,
                error = "已有转码任务正在运行",
            )
        }
        var output: JobOutput? = null

        try {
            if (activeProcess.wasCancelled(job.id)) {
                return@withContext job.copy(status = JobStatus.Cancelled, error = null)
            }
            val config = resolveConfig(job.config).getOrThrow()
            output = createStagingOutput(
                jobId = job.id,
                displayName = job.displayName,
                extension = config.extension,
                create = outputStore::createJobOutput,
            )
            val partial = output.partial
            val duration = outputDurationSecs(config, job.media)
            val uri = Uri.parse(job.sourceUri)
            val result = runWithInputFallback(
                cachedInput = sourceAccess.cachedInput(uri),
                openInput = { sourceAccess.resolveInput(uri) },
                inputPath = ResolvedInput::ffmpegPath,
                needsCacheFallback = { it.pfd != null },
                copyToCache = { sourceAccess.copyToCache(uri) },
                closeInput = ResolvedInput::close,
                run = { inputPath ->
                    runTranscodeAttempts(
                        job = job,
                        inputPath = inputPath,
                        partial = partial,
                        durationSecs = duration,
                        onProgress = onProgress,
                    )
                },
            )

            if (result.cancelled || activeProcess.wasCancelled(job.id)) {
                partial.delete()
                return@withContext job.copy(
                    status = JobStatus.Cancelled,
                    error = null,
                )
            }
            if (result.exitCode != 0) {
                partial.delete()
                Log.e(TAG, "FFmpeg failed (${result.exitCode}): ${result.stderr.takeLast(4_000)}")
                return@withContext job.copy(
                    status = JobStatus.Failed,
                    error = "FFmpeg 转码失败（退出码 ${result.exitCode}）",
                )
            }

            val final = outputStore.finalizeJobOutput(output)
            if (activeProcess.wasCancelled(job.id)) {
                final.delete()
                return@withContext job.copy(status = JobStatus.Cancelled, error = null)
            }
            val exported = outputStore.export(
                source = final,
                stem = sourceStem(job.displayName),
                ext = config.extension,
                mimeType = mimeType(config.container),
                target = outputTarget,
            )
            job.completed(exported)
        } catch (error: Exception) {
            if (activeProcess.wasCancelled(job.id)) {
                job.copy(status = JobStatus.Cancelled, error = null)
            } else {
                job.copy(
                    status = JobStatus.Failed,
                    error = error.message ?: "转码失败",
                )
            }
        } finally {
            output?.let(::deleteStagedOutput)
            activeProcess.release(job.id)
        }
    }

    suspend fun probe(uri: Uri, displayName: String): MediaInfo = withContext(Dispatchers.IO) {
        sourceAccess.cachedInput(uri)?.use {
            val cached = runProbe(it.ffmpegPath)
            return@withContext parseFfprobeJson(uri.toString(), displayName, cached.stdout)
        }
        val opened = sourceAccess.resolveInput(uri)
        val first = runProbe(opened.ffmpegPath)
        if (first.exitCode == 0) {
            opened.close()
            return@withContext parseFfprobeJson(uri.toString(), displayName, first.stdout)
        }

        val cached = if (opened.pfd != null) {
            sourceAccess.copyToCache(uri, opened)
        } else {
            opened.close()
            return@withContext parseFfprobeJson(uri.toString(), displayName, first.stdout)
        }
        cached.use {
            val second = runProbe(it.ffmpegPath)
            parseFfprobeJson(uri.toString(), displayName, second.stdout)
        }
    }

    fun cancel(jobId: String) {
        activeProcess.cancel(jobId)
    }

    fun interrupt(jobId: String) {
        activeProcess.interrupt(jobId)
    }

    fun reserve(jobId: String): Boolean = activeProcess.claim(jobId)

    internal fun wasCancelled(jobId: String): Boolean = activeProcess.wasCancelled(jobId)

    internal fun release(jobId: String) = activeProcess.release(jobId)

    private fun runTranscodeAttempts(
        job: Job,
        inputPath: String,
        partial: File,
        durationSecs: Double,
        onProgress: (Double) -> Unit,
    ): ProcessResult {
        val first = runAttempt(
            job = job,
            inputPath = inputPath,
            partial = partial,
            preferHardware = true,
            durationSecs = durationSecs,
            onProgress = onProgress,
        )
        if (
            first.exitCode == 0 ||
            first.cancelled ||
            !shouldRetryWithoutHardware(first.stderr)
        ) {
            return first
        }
        partial.delete()
        return runAttempt(
            job = job,
            inputPath = inputPath,
            partial = partial,
            preferHardware = false,
            durationSecs = durationSecs,
            onProgress = onProgress,
        )
    }

    private fun runAttempt(
        job: Job,
        inputPath: String,
        partial: File,
        preferHardware: Boolean,
        durationSecs: Double,
        onProgress: (Double) -> Unit,
    ): ProcessResult {
        val config = resolveConfig(job.config).getOrThrow()
        val args = buildFfmpegArgs(
            input = inputPath,
            outputPartial = partial.absolutePath,
            config = config,
            media = job.media,
            preferHardware = preferHardware,
        ).getOrThrow()
        partial.delete()
        val process = activeProcess.start(
            candidateJobId = job.id,
            start = { processBuilder(binaryPath(configuredFfmpegPath, "ffmpeg"), args).start() },
            destroy = { started ->
                started.destroy()
                if (started.isAlive) started.destroyForcibly()
                partial.delete()
            },
        ) ?: return ProcessResult(
            exitCode = -1,
            stderr = "",
            cancelled = true,
        )

        val stderr = StringBuilder()
        val stdoutReader = thread(name = "ffmpeg-progress-${job.id}") {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        parseProgressLine(line, durationSecs)?.let(onProgress)
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "读取 FFmpeg 进度失败", error)
            }
        }
        val stderrReader = thread(name = "ffmpeg-stderr-${job.id}") {
            process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> stderr.appendLine(line) }
            }
        }
        val exitCode = process.waitFor()
        stdoutReader.join()
        stderrReader.join()
        return ProcessResult(
            exitCode = exitCode,
            stderr = stderr.toString(),
            cancelled = activeProcess.wasCancelled(job.id),
        )
    }

    private fun runProbe(inputPath: String): ProbeResult {
        val args = listOf(
            "-v",
            "error",
            "-show_format",
            "-show_streams",
            "-print_format",
            "json",
            ffmpegFileArg(inputPath),
        )
        val process = processBuilder(binaryPath(configuredFfprobePath, "ffprobe"), args).start()
        val stderr = StringBuilder()
        val stderrReader = thread(name = "ffprobe-stderr") {
            process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> stderr.appendLine(line) }
            }
        }
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        stderrReader.join()
        return ProbeResult(exitCode, stdout, stderr.toString())
    }

    private fun processBuilder(executable: String, args: List<String>): ProcessBuilder =
        ProcessBuilder(listOf(executable) + args).apply {
            val nativeDir = File(context.applicationInfo.nativeLibraryDir).absolutePath
            environment()["PATH"] = "/system/bin:/vendor/bin"
            environment()["LD_LIBRARY_PATH"] = nativeDir
        }

    private fun binaryPath(configured: String?, name: String): String =
        configured ?: nativeBinary(
            File(context.applicationInfo.nativeLibraryDir),
            name,
        ).absolutePath

    private companion object {
        const val TAG = "FfmpegProcess"
    }
}

internal suspend fun <T> runWithInputFallback(
    cachedInput: T?,
    openInput: suspend () -> T,
    inputPath: (T) -> String,
    needsCacheFallback: (T) -> Boolean,
    copyToCache: suspend (T) -> T,
    closeInput: (T) -> Unit,
    run: (String) -> ProcessResult,
): ProcessResult {
    var input = cachedInput ?: openInput()
    var inputOpen = true
    try {
        var result = run(inputPath(input))
        if (
            result.exitCode != 0 &&
            !result.cancelled &&
            needsCacheFallback(input) &&
            shouldRetryWithCachedInput(result.stderr)
        ) {
            val descriptorInput = input
            closeInput(descriptorInput)
            inputOpen = false
            input = copyToCache(descriptorInput)
            inputOpen = true
            result = run(inputPath(input))
        }
        return result
    } finally {
        if (inputOpen) closeInput(input)
    }
}

internal fun shouldRetryWithCachedInput(stderr: String): Boolean {
    val normalized = stderr.lowercase()
    return "/proc/self/fd/" in normalized &&
        listOf(
            "no such file",
            "permission denied",
            "cannot open",
            "could not open",
            "error opening input",
        ).any { it in normalized }
}

internal fun createStagingOutput(
    jobId: String,
    displayName: String,
    extension: String,
    create: (String, String, String) -> JobOutput,
): JobOutput = create(jobId, sourceStem(displayName), extension)

internal fun deleteStagedOutput(output: JobOutput) {
    output.partial.parentFile?.deleteRecursively()
}

internal class ActiveProcessSlot {
    private val lock = Any()
    private var jobId: String? = null
    private var process: Process? = null
    private var destroyProcess: ((Process) -> Unit)? = null
    private var cancelled = false
    private var releasedCancelledJobId: String? = null

    fun claim(candidateJobId: String): Boolean = synchronized(lock) {
        if (jobId != null) return false
        jobId = candidateJobId
        process = null
        destroyProcess = null
        cancelled = false
        releasedCancelledJobId = null
        true
    }

    fun claimOrConfirm(candidateJobId: String): Boolean = synchronized(lock) {
        if (jobId == candidateJobId) return true
        if (jobId != null) return false
        jobId = candidateJobId
        process = null
        destroyProcess = null
        cancelled = false
        releasedCancelledJobId = null
        true
    }

    fun start(
        candidateJobId: String,
        start: () -> Process,
        destroy: (Process) -> Unit,
    ): Process? = synchronized(lock) {
        check(jobId == candidateJobId) { "转码任务未持有进程槽" }
        if (cancelled) return null
        val started = start()
        process = started
        destroyProcess = destroy
        started
    }

    fun cancel(candidateJobId: String): Boolean = synchronized(lock) {
        if (jobId != candidateJobId) return false
        cancelled = true
        process?.let { destroyProcess?.invoke(it) }
        true
    }

    fun interrupt(candidateJobId: String): Boolean = synchronized(lock) {
        if (jobId != candidateJobId) return false
        process?.let { destroyProcess?.invoke(it) }
        true
    }

    fun wasCancelled(candidateJobId: String): Boolean = synchronized(lock) {
        (jobId == candidateJobId && cancelled) ||
            releasedCancelledJobId == candidateJobId
    }

    fun activeJobId(): String? = synchronized(lock) { jobId }

    fun release(candidateJobId: String) = synchronized(lock) {
        if (jobId == candidateJobId) {
            releasedCancelledJobId = candidateJobId.takeIf { cancelled }
            jobId = null
            process = null
            destroyProcess = null
            cancelled = false
        }
    }
}

private fun ResolvedInput.close() {
    pfd?.close()
}

private inline fun <T> ResolvedInput.use(block: (ResolvedInput) -> T): T =
    try {
        block(this)
    } finally {
        close()
    }

private fun Job.completed(output: ExportedOutput): Job = copy(
    outputPath = output.location,
    status = JobStatus.Completed,
    progress = 100.0,
    error = null,
)

private fun mimeType(container: String): String = when (container) {
    "mp4", "m4a" -> if (container == "m4a") "audio/mp4" else "video/mp4"
    "mov" -> "video/quicktime"
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    "avi" -> "video/x-msvideo"
    "gif" -> "image/gif"
    "mp3" -> "audio/mpeg"
    "wav" -> "audio/wav"
    "ogg" -> "audio/ogg"
    "flac" -> "audio/flac"
    "amr" -> "audio/amr"
    else -> "application/octet-stream"
}

internal data class ProcessResult(
    val exitCode: Int,
    val stderr: String,
    val cancelled: Boolean,
)

private data class ProbeResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)
