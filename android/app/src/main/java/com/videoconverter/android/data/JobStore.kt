package com.videoconverter.android.data

import android.content.Context
import com.videoconverter.android.R
import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.MediaInfo
import com.videoconverter.android.domain.OutputConfig
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

class JobStore(context: Context) {
    private val file = File(context.filesDir, "jobs.json")
    private val temporary = File(context.filesDir, "jobs.json.tmp")
    private val corrupt = File(context.filesDir, "jobs.json.bad")
    private val saveError = context.getString(R.string.error_cannot_save_jobs)

    fun load(): List<Job> = synchronized(STORE_LOCK) {
        loadJobsOrEmpty(file, corrupt)
    }

    fun save(jobs: List<Job>) = synchronized(STORE_LOCK) {
        saveLocked(jobs)
    }

    fun update(transform: (List<Job>) -> List<Job>): List<Job> = synchronized(STORE_LOCK) {
        val updated = transform(load())
        saveLocked(updated)
        updated
    }

    private fun saveLocked(jobs: List<Job>) {
        try {
            temporary.writeText(jobsToJson(jobs))
            replaceFile(temporary, file)
        } catch (error: Exception) {
            temporary.delete()
            if (error is IOException) throw error
            throw IOException(saveError, error)
        }
    }

    private companion object {
        val STORE_LOCK = Any()
    }
}

internal fun loadJobsOrEmpty(file: File, corrupt: File): List<Job> {
    if (!file.isFile) return emptyList()
    return try {
        jobsFromJson(file.readText())
    } catch (_: Exception) {
        runCatching {
            corrupt.delete()
            if (!file.renameTo(corrupt)) file.delete()
        }
        emptyList()
    }
}

internal fun replaceFile(temporary: File, live: File) {
    Files.move(
        temporary.toPath(),
        live.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
    )
}

internal fun jobsToJson(jobs: List<Job>): String =
    JSONArray().apply { jobs.forEach { put(it.toJson()) } }.toString()

internal fun jobsFromJson(json: String): List<Job> {
    val array = JSONArray(json)
    return buildList {
        for (index in 0 until array.length()) {
            add(array.getJSONObject(index).toJob())
        }
    }
}

private fun Job.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("sourceUri", sourceUri)
    .put("displayName", displayName)
    .putNullable("outputPath", outputPath)
    .put("status", status.name)
    .put("progress", progress)
    .putNullable("error", error)
    .put("config", config.toJson())
    .put("media", media.toJson())
    .putNullable("outputKind", outputKind)
    .putNullable("outputTreeUri", outputTreeUri)
    .put("outputPaths", JSONArray(outputPaths))

private fun JSONObject.toJob(): Job = Job(
    id = getString("id"),
    sourceUri = getString("sourceUri"),
    displayName = getString("displayName"),
    outputPath = nullableString("outputPath"),
    status = JobStatus.valueOf(getString("status")),
    progress = getDouble("progress"),
    error = nullableString("error"),
    config = getJSONObject("config").toOutputConfig(),
    media = getJSONObject("media").toMediaInfo(),
    outputKind = nullableString("outputKind"),
    outputTreeUri = nullableString("outputTreeUri"),
    outputPaths = stringList("outputPaths"),
)

private fun OutputConfig.toJson(): JSONObject = JSONObject()
    .put("preset", preset)
    .putNullable("container", container)
    .putNullable("videoEncoder", videoEncoder)
    .putNullable("maxWidth", maxWidth)
    .putNullable("maxHeight", maxHeight)
    .putNullable("videoBitrateKbps", videoBitrateKbps)
    .putNullable("frameRate", frameRate)
    .putNullable("audioEncoder", audioEncoder)
    .putNullable("audioBitrateKbps", audioBitrateKbps)
    .putNullable("keepAudio", keepAudio)
    .putNullable("quality", quality)
    .putNullable("trimStartSecs", trimStartSecs)
    .putNullable("trimEndSecs", trimEndSecs)

private fun JSONObject.toOutputConfig(): OutputConfig = OutputConfig(
    preset = getString("preset"),
    container = nullableString("container"),
    videoEncoder = nullableString("videoEncoder"),
    maxWidth = nullableInt("maxWidth"),
    maxHeight = nullableInt("maxHeight"),
    videoBitrateKbps = nullableInt("videoBitrateKbps"),
    frameRate = nullableDouble("frameRate"),
    audioEncoder = nullableString("audioEncoder"),
    audioBitrateKbps = nullableInt("audioBitrateKbps"),
    keepAudio = nullableBoolean("keepAudio"),
    quality = nullableString("quality"),
    trimStartSecs = nullableDouble("trimStartSecs"),
    trimEndSecs = nullableDouble("trimEndSecs"),
)

private fun MediaInfo.toJson(): JSONObject = JSONObject()
    .put("sourceUri", sourceUri)
    .put("displayName", displayName)
    .putNullable("durationSecs", durationSecs)
    .putNullable("container", container)
    .putNullable("videoCodec", videoCodec)
    .putNullable("width", width)
    .putNullable("height", height)
    .putNullable("frameRate", frameRate)
    .putNullable("audioCodec", audioCodec)
    .putNullable("channels", channels)
    .put("importable", importable)
    .putNullable("error", error)
    .putNullable("trimStartSecs", trimStartSecs)
    .putNullable("trimEndSecs", trimEndSecs)
    .putNullable("pageCount", pageCount)
    .putNullable("pageStart", pageStart)
    .putNullable("pageEnd", pageEnd)

private fun JSONObject.toMediaInfo(): MediaInfo = MediaInfo(
    sourceUri = getString("sourceUri"),
    displayName = getString("displayName"),
    durationSecs = nullableDouble("durationSecs"),
    container = nullableString("container"),
    videoCodec = nullableString("videoCodec"),
    width = nullableInt("width"),
    height = nullableInt("height"),
    frameRate = nullableDouble("frameRate"),
    audioCodec = nullableString("audioCodec"),
    channels = nullableInt("channels"),
    importable = getBoolean("importable"),
    error = nullableString("error"),
    trimStartSecs = nullableDouble("trimStartSecs"),
    trimEndSecs = nullableDouble("trimEndSecs"),
    pageCount = nullableInt("pageCount"),
    pageStart = nullableInt("pageStart"),
    pageEnd = nullableInt("pageEnd"),
)

private fun JSONObject.putNullable(name: String, value: Any?): JSONObject =
    put(name, value ?: JSONObject.NULL)

private fun JSONObject.nullableString(name: String): String? =
    if (isNull(name)) null else getString(name)

private fun JSONObject.nullableInt(name: String): Int? =
    if (isNull(name)) null else getInt(name)

private fun JSONObject.nullableDouble(name: String): Double? =
    if (isNull(name)) null else getDouble(name)

private fun JSONObject.nullableBoolean(name: String): Boolean? =
    if (isNull(name)) null else getBoolean(name)

private fun JSONObject.stringList(name: String): List<String> {
    if (isNull(name)) return emptyList()
    val array = getJSONArray(name)
    return buildList {
        for (index in 0 until array.length()) {
            add(array.getString(index))
        }
    }
}
