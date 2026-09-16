package com.videoconverter.android.domain

import org.json.JSONObject

fun parseFfprobeJson(sourceUri: String, displayName: String, json: String): MediaInfo =
    try {
        mapProbe(sourceUri, displayName, JSONObject(json))
    } catch (_: Exception) {
        unreadable(sourceUri, displayName, "无法解析媒体信息")
    }

fun unreadable(sourceUri: String, displayName: String, reason: String): MediaInfo =
    MediaInfo(
        sourceUri = sourceUri,
        displayName = displayName,
        importable = false,
        error = reason,
    )

private fun mapProbe(sourceUri: String, displayName: String, probe: JSONObject): MediaInfo {
    val format = probe.optJSONObject("format")
    val streams = probe.optJSONArray("streams")
    var video: JSONObject? = null
    var audio: JSONObject? = null

    if (streams != null) {
        for (index in 0 until streams.length()) {
            val stream = streams.optJSONObject(index) ?: continue
            when (stream.optionalString("codec_type")) {
                "video" -> if (video == null) video = stream
                "audio" -> if (audio == null) audio = stream
            }
        }
    }

    val hasAv = video != null || audio != null
    return MediaInfo(
        sourceUri = sourceUri,
        displayName = displayName,
        durationSecs = format?.optionalString("duration")?.toDoubleOrNull(),
        container = format?.optionalString("format_name"),
        videoCodec = video?.optionalString("codec_name"),
        width = video?.optionalInt("width"),
        height = video?.optionalInt("height"),
        frameRate = parseFrameRate(video?.optionalString("r_frame_rate")),
        audioCodec = audio?.optionalString("codec_name"),
        channels = audio?.optionalInt("channels"),
        importable = hasAv,
        error = if (hasAv) null else "没有可转码的视频或音频流",
    )
}

private fun parseFrameRate(value: String?): Double? {
    value ?: return null
    val separator = value.indexOf('/')
    if (separator < 0) return value.toDoubleOrNull()

    val numerator = value.substring(0, separator).toDoubleOrNull() ?: return null
    val denominator = value.substring(separator + 1).toDoubleOrNull() ?: return null
    return if (denominator == 0.0) null else numerator / denominator
}

private fun JSONObject.optionalString(name: String): String? =
    if (has(name) && !isNull(name)) optString(name) else null

private fun JSONObject.optionalInt(name: String): Int? =
    if (has(name) && !isNull(name)) optInt(name) else null
