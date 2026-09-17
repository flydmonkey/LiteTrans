package com.videoconverter.android.domain

data class MediaInfo(
    val sourceUri: String,
    val displayName: String,
    val durationSecs: Double? = null,
    val container: String? = null,
    val videoCodec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Double? = null,
    val audioCodec: String? = null,
    val channels: Int? = null,
    val importable: Boolean = false,
    val error: String? = null,
    val trimStartSecs: Double? = null,
    val trimEndSecs: Double? = null,
    val pageCount: Int? = null,
    val pageStart: Int? = null,
    val pageEnd: Int? = null,
)

data class PresetInfo(val id: String, val label: String, val description: String)

data class OutputConfig(
    val preset: String = DEFAULT_PRESET,
    val container: String? = null,
    val videoEncoder: String? = null,
    val maxWidth: Int? = null,
    val maxHeight: Int? = null,
    val videoBitrateKbps: Int? = null,
    val frameRate: Double? = null,
    val audioEncoder: String? = null,
    val audioBitrateKbps: Int? = null,
    val keepAudio: Boolean? = null,
    val quality: String? = null,
    val trimStartSecs: Double? = null,
    val trimEndSecs: Double? = null,
)

data class ResolvedConfig(
    val preset: String,
    val container: String,
    val extension: String,
    val videoEncoder: String?,
    val audioEncoder: String?,
    val maxWidth: Int?,
    val maxHeight: Int?,
    val videoBitrateKbps: Int?,
    val frameRate: Double?,
    val audioBitrateKbps: Int?,
    val keepAudio: Boolean,
    val quality: String,
    val trimStartSecs: Double?,
    val trimEndSecs: Double?,
)

enum class JobStatus { Queued, Running, Completed, Failed, Cancelled }

data class Job(
    val id: String,
    val sourceUri: String,
    val displayName: String,
    val outputPath: String?,
    val status: JobStatus,
    val progress: Double,
    val error: String?,
    val config: OutputConfig,
    val media: MediaInfo,
    val outputKind: String? = null,
    val outputTreeUri: String? = null,
    val outputPaths: List<String> = emptyList(),
    val createdAtEpochMs: Long? = null,
)

data class SkippedSource(val sourceUri: String, val displayName: String, val reason: String)

data class EnqueueReport(val jobs: List<Job>, val skipped: List<SkippedSource>)
