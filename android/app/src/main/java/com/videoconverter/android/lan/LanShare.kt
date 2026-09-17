package com.videoconverter.android.lan

import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.JobStatus

data class LanShareSettings(
    val enabled: Boolean = false,
    val token: String = "",
)

fun normalizeLanToken(raw: String): String = raw.trim()

fun lanTokenAllows(storedToken: String, queryK: String?): Boolean {
    if (storedToken.isEmpty()) return true
    return queryK == storedToken
}

sealed class LanRoute {
    data object Home : LanRoute()
    data class Download(val jobId: String, val index: Int) : LanRoute()
    data object NotFound : LanRoute()
}

fun parseLanRoute(path: String): LanRoute {
    val trimmed = path.substringBefore('?')
    if (trimmed == "/" || trimmed.isEmpty()) return LanRoute.Home
    val parts = trimmed.trim('/').split('/')
    if (parts.size !in 2..3 || parts[0] != "d") return LanRoute.NotFound
    val jobId = parts[1]
    if (jobId.isEmpty() || jobId.contains("..") || '/' in jobId) return LanRoute.NotFound
    val index = if (parts.size == 2) 0 else parts[2].toIntOrNull() ?: return LanRoute.NotFound
    if (index < 0) return LanRoute.NotFound
    return LanRoute.Download(jobId, index)
}

fun jobOutputPaths(job: Job): List<String> =
    job.outputPaths.filter { it.isNotBlank() }.ifEmpty { listOfNotNull(job.outputPath?.takeIf { it.isNotBlank() }) }

data class LanDownloadTarget(
    val path: String,
    val downloadName: String,
    val contentType: String,
)

fun resolveLanDownload(
    jobs: List<Job>,
    jobId: String,
    index: Int,
    exists: (String) -> Boolean,
): LanDownloadTarget? {
    if (jobId.contains("..") || '/' in jobId) return null
    val job = jobs.firstOrNull { it.id == jobId } ?: return null
    if (job.status != JobStatus.Completed) return null
    val paths = jobOutputPaths(job)
    val path = paths.getOrNull(index) ?: return null
    if (!exists(path)) return null
    val base = java.io.File(path).name.ifBlank { job.displayName }
    val downloadName = if (base.contains('.')) base else job.displayName
    return LanDownloadTarget(path, downloadName, lanContentType(downloadName))
}

fun lanContentType(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
    "mp4" -> "video/mp4"
    "mp3" -> "audio/mpeg"
    "m4a" -> "audio/mp4"
    "wav" -> "audio/wav"
    "ogg" -> "audio/ogg"
    "flac" -> "audio/flac"
    "amr" -> "audio/amr"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "bmp" -> "image/bmp"
    "pdf" -> "application/pdf"
    "txt" -> "text/plain; charset=utf-8"
    else -> "application/octet-stream"
}

fun lanContentDisposition(fileName: String): String {
    val safe = fileName.replace(Regex("[\r\n\"]"), "_")
    val encoded = java.net.URLEncoder.encode(safe, Charsets.UTF_8).replace("+", "%20")
    return "attachment; filename=\"$safe\"; filename*=UTF-8''$encoded"
}

const val LAN_SHARE_PREFERRED_PORT = 17890
const val LAN_SHARE_PORT_ATTEMPTS = 10

data class LanIface(val name: String, val hostAddress: String, val loopback: Boolean)

fun pickLanIpv4(ifaces: List<LanIface>): String? {
    val usable = ifaces.filter { iface ->
        !iface.loopback && iface.hostAddress.matches(Regex("""\d{1,3}(?:\.\d{1,3}){3}"""))
    }
    val preferred = usable.firstOrNull { iface ->
        val n = iface.name.lowercase()
        n.startsWith("wlan") || n.startsWith("ap") || n.contains("wlan") || n.contains("swlan")
    }
    return (preferred ?: usable.firstOrNull())?.hostAddress
}

fun chooseLanPort(
    preferred: Int = LAN_SHARE_PREFERRED_PORT,
    attempts: Int = LAN_SHARE_PORT_ATTEMPTS,
    occupied: Set<Int>,
): Int? {
    repeat(attempts) { offset ->
        val port = preferred + offset
        if (port !in occupied) return port
    }
    return null
}

fun lanPublicUrl(ip: String, port: Int, token: String): String {
    val base = "http://$ip:$port/"
    if (token.isEmpty()) return base
    val encoded = java.net.URLEncoder.encode(token, Charsets.UTF_8)
    return "${base}?k=$encoded"
}
