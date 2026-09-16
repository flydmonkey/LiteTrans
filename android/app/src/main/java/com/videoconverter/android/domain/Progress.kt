package com.videoconverter.android.domain

fun parseProgressLine(line: String, durationSecs: Double): Double? {
    val separator = line.indexOf('=')
    if (separator < 0) return null

    val key = line.substring(0, separator)
    if (key != "out_time_ms" && key != "out_time_us") return null
    if (!durationSecs.isFinite() || durationSecs <= 0.0) return null

    val microseconds = line.substring(separator + 1).trim().toDoubleOrNull() ?: return null
    if (!microseconds.isFinite()) return null
    val seconds = microseconds / 1_000_000.0
    return ((seconds / durationSecs) * 100.0).coerceIn(0.0, 100.0)
}
