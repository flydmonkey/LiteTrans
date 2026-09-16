package com.videoconverter.android.engine

import android.content.Context
import android.net.Uri
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
    private val ffmpegPath: String = nativeBinary(
        File(context.applicationInfo.nativeLibraryDir),
        "ffmpeg",
    ).absolutePath,
    private val ffprobePath: String = nativeBinary(
        File(context.applicationInfo.nativeLibraryDir),
        "ffprobe",
    ).absolutePath,
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
            val input = sourceAccess.resolveInput(Uri.parse(job.sourceUri))
            input.use {
                val first = runAttempt(
                    job = job,
                    inputPath = it.ffmpegPath,
                    partial = partial,
                    preferHardware = true,
                    durationSecs = duration,
                    onProgress = onProgress,
                )
                val result = if (
                    first.exitCode != 0 &&
                    !first.cancelled &&
                    shouldRetryWithoutHardware(first.stderr)
                ) {
                    partial.delete()
                    runAttempt(
                        job = job,
                        inputPath = it.ffmpegPath,
                        partial = partial,
                        preferHardware = false,
                        durationSecs = duration,
                        onProgress = onProgress,
                    )
                } else {
                    first
                }

                if (result.cancelled || activeProcess.wasCancelled(job.id)) {
                    partial.delete()
                    return@withContext job.copy(
                        status = JobStatus.Cancelled,
                        error = null,
                    )
                }
                if (result.exitCode != 0) {
                    partial.delete()
                    return@withContext job.copy(
                        status = JobStatus.Failed,
                        error = "FFmpeg 转码失败（退出码 ${result.exitCode}）",
                    )
                }
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

    fun reserve(jobId: String): Boolean = activeProcess.claim(jobId)

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
        val process = processBuilder(ffmpegPath, args).start()
        activeProcess.attach(job.id) {
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
            partial.delete()
        }

        val stderr = StringBuilder()
        val stdoutReader = thread(name = "ffmpeg-progress-${job.id}") {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    parseProgressLine(line, durationSecs)?.let(onProgress)
                }
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
        val process = processBuilder(ffprobePath, args).start()
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
            environment()["PATH"] = "/system/bin:/vendor/bin"
        }
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
    private var destroyProcess: (() -> Unit)? = null
    private var cancelled = false

    fun claim(candidateJobId: String): Boolean = synchronized(lock) {
        if (jobId != null) return false
        jobId = candidateJobId
        destroyProcess = null
        cancelled = false
        true
    }

    fun claimOrConfirm(candidateJobId: String): Boolean = synchronized(lock) {
        if (jobId == candidateJobId) return true
        if (jobId != null) return false
        jobId = candidateJobId
        destroyProcess = null
        cancelled = false
        true
    }

    fun attach(candidateJobId: String, destroy: () -> Unit) = synchronized(lock) {
        check(jobId == candidateJobId) { "转码任务未持有进程槽" }
        destroyProcess = destroy
        if (cancelled) destroy()
    }

    fun cancel(candidateJobId: String): Boolean = synchronized(lock) {
        if (jobId != candidateJobId) return false
        cancelled = true
        destroyProcess?.invoke()
        true
    }

    fun wasCancelled(candidateJobId: String): Boolean = synchronized(lock) {
        jobId == candidateJobId && cancelled
    }

    fun activeJobId(): String? = synchronized(lock) { jobId }

    fun release(candidateJobId: String) = synchronized(lock) {
        if (jobId == candidateJobId) {
            jobId = null
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
    else -> "application/octet-stream"
}

private data class ProcessResult(
    val exitCode: Int,
    val stderr: String,
    val cancelled: Boolean,
)

private data class ProbeResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)
