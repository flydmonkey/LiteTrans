package com.videoconverter.android.lan

import android.content.res.Resources
import com.videoconverter.android.R
import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.ui.HistorySegment
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.LinkOption

data class LanHistoryCopy(
    val warning: String,
    val video: String,
    val audio: String,
    val document: String,
    val image: String,
    val emptyVideo: String,
    val emptyAudio: String,
    val emptyDocument: String,
    val emptyImage: String,
    val download: String,
    val downloadNamed: String,
    val downloadIndex: String,
    val statusQueued: String,
    val statusRunning: String,
    val statusCompleted: String,
    val statusFailed: String,
    val statusCancelled: String,
    val needToken: String,
    val previewFailed: String,
    val downloadToOpen: String,
)

fun lanHistoryCopy(resources: Resources) = LanHistoryCopy(
    warning = resources.getString(R.string.lan_open_warning),
    video = resources.getString(R.string.lan_segment_video),
    audio = resources.getString(R.string.lan_segment_audio),
    document = resources.getString(R.string.lan_segment_document),
    image = resources.getString(R.string.lan_segment_image),
    emptyVideo = resources.getString(R.string.history_empty_video),
    emptyAudio = resources.getString(R.string.history_empty_audio),
    emptyDocument = resources.getString(R.string.history_empty_document),
    emptyImage = resources.getString(R.string.history_empty_image),
    download = resources.getString(R.string.lan_download),
    downloadNamed = resources.getString(R.string.lan_download_named),
    downloadIndex = resources.getString(R.string.lan_download_index),
    statusQueued = resources.getString(R.string.status_queued),
    statusRunning = resources.getString(R.string.status_running),
    statusCompleted = resources.getString(R.string.status_completed),
    statusFailed = resources.getString(R.string.status_failed),
    statusCancelled = resources.getString(R.string.status_cancelled),
    needToken = resources.getString(R.string.lan_need_token),
    previewFailed = resources.getString(R.string.lan_preview_failed),
    downloadToOpen = resources.getString(R.string.lan_download_to_open),
)

fun lanLibraryTabLabel(tab: LanLibraryTab, copy: LanHistoryCopy): String = when (tab) {
    LanLibraryTab.Video -> copy.video
    LanLibraryTab.Audio -> copy.audio
    LanLibraryTab.Image -> copy.image
    LanLibraryTab.Document -> copy.document
}

fun lanLibraryEmptyLabel(tab: LanLibraryTab, copy: LanHistoryCopy): String = when (tab) {
    LanLibraryTab.Video -> copy.emptyVideo
    LanLibraryTab.Audio -> copy.emptyAudio
    LanLibraryTab.Image -> copy.emptyImage
    LanLibraryTab.Document -> copy.emptyDocument
}

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
    data class Media(val jobId: String, val index: Int) : LanRoute()
    data object NotFound : LanRoute()
}

fun parseLanRoute(path: String): LanRoute {
    val trimmed = path.substringBefore('?')
    if (trimmed == "/" || trimmed.isEmpty()) return LanRoute.Home
    val parts = trimmed.trim('/').split('/')
    if (parts.size !in 2..3) return LanRoute.NotFound
    val kind = parts[0]
    if (kind != "d" && kind != "m") return LanRoute.NotFound
    val jobId = parts[1]
    if (jobId.isEmpty() || jobId.contains("..") || '/' in jobId) return LanRoute.NotFound
    val index = if (parts.size == 2) 0 else parts[2].toIntOrNull() ?: return LanRoute.NotFound
    if (index < 0) return LanRoute.NotFound
    return if (kind == "d") LanRoute.Download(jobId, index) else LanRoute.Media(jobId, index)
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
    val downloadName = lanPreviewFileName(path, job)
    return LanDownloadTarget(path, downloadName, lanContentType(downloadName))
}

fun lanFileIsRegular(path: String): Boolean {
    if (path.isBlank()) return false
    return try {
        Files.isRegularFile(java.io.File(path).toPath(), LinkOption.NOFOLLOW_LINKS)
    } catch (_: Exception) {
        false
    }
}

const val LAN_SHARE_PREFERRED_PORT = 17890
const val LAN_SHARE_PORT_ATTEMPTS = 10

data class LanIface(val name: String, val hostAddress: String, val loopback: Boolean)

fun collectLanIfaces(ifaces: Iterable<NetworkInterface>): List<LanIface> =
    ifaces.flatMap { ni ->
        ni.inetAddresses.toList().mapNotNull { addr ->
            val host = addr.hostAddress ?: return@mapNotNull null
            LanIface(
                name = ni.name,
                hostAddress = host.substringBefore('%'),
                loopback = ni.isLoopback || addr.isLoopbackAddress,
            )
        }
    }

fun isLanWifiOrHotspotName(name: String): Boolean {
    val n = name.lowercase()
    return n.startsWith("wlan") ||
        n.startsWith("ap") ||
        n.contains("wlan") ||
        n.contains("swlan") ||
        n.contains("softap")
}

fun pickLanIpv4(ifaces: List<LanIface>): String? {
    val usable = ifaces.filter { iface ->
        !iface.loopback && iface.hostAddress.matches(Regex("""\d{1,3}(?:\.\d{1,3}){3}"""))
    }
    return usable.firstOrNull { iface -> isLanWifiOrHotspotName(iface.name) }?.hostAddress
}

fun openLanServerSocket(ip: String, port: Int): ServerSocket =
    ServerSocket(port, 0, InetAddress.getByName(ip))

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
    val encoded = java.net.URLEncoder.encode(token, "UTF-8")
    return "${base}?k=$encoded"
}

internal fun lanHistoryEmptyLabel(segment: HistorySegment, copy: LanHistoryCopy): String = when (segment) {
    HistorySegment.Video -> copy.emptyVideo
    HistorySegment.Audio -> copy.emptyAudio
    HistorySegment.Document -> copy.emptyDocument
}

internal fun lanStatusLabel(status: JobStatus, copy: LanHistoryCopy): String = when (status) {
    JobStatus.Queued -> copy.statusQueued
    JobStatus.Running -> copy.statusRunning
    JobStatus.Completed -> copy.statusCompleted
    JobStatus.Failed -> copy.statusFailed
    JobStatus.Cancelled -> copy.statusCancelled
}

fun escapeHtml(raw: String): String = raw
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

internal fun lanHistoryDownloadHref(
    jobId: String,
    index: Int,
    multi: Boolean,
    token: String,
    kind: String = "d",
): String {
    val path = if (index > 0 || multi) "/$kind/$jobId/$index" else "/$kind/$jobId"
    if (token.isEmpty()) return path
    val encoded = java.net.URLEncoder.encode(token, "UTF-8")
    return "$path?k=$encoded"
}

data class LanHttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String> = emptyMap(),
)

data class LanHttpResponse(
    val status: Int,
    val contentType: String,
    val body: ByteArray,
    val headers: Map<String, String> = emptyMap(),
    val filePath: String? = null,
    val rangeHeader: String? = null,
    val sendBody: Boolean = true,
    val byteStart: Long = 0,
    val byteLength: Long? = null,
)

fun parseLanHeaderLines(lines: List<String>): Map<String, String> {
    val headers = LinkedHashMap<String, String>()
    for (line in lines) {
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
    }
    return headers
}

fun parseHttpRequestLine(line: String): LanHttpRequest? {
    val trimmed = line.trim()
    val firstSpace = trimmed.indexOf(' ')
    if (firstSpace <= 0) return null
    val method = trimmed.substring(0, firstSpace)
    val rest = trimmed.substring(firstSpace + 1).trimStart()
    if (rest.isEmpty()) return null
    val targetEnd = rest.indexOf(' ')
    val target = if (targetEnd < 0) rest else rest.substring(0, targetEnd)
    if (target.isEmpty()) return null
    val queryStart = target.indexOf('?')
    val path = if (queryStart < 0) target else target.substring(0, queryStart)
    val query = if (queryStart < 0) emptyMap() else parseLanQuery(target.substring(queryStart + 1))
    return LanHttpRequest(method, path, query)
}

fun parseLanQuery(rawQuery: String?): Map<String, String> {
    if (rawQuery.isNullOrEmpty()) return emptyMap()
    return rawQuery.split('&').mapNotNull { part ->
        if (part.isEmpty()) return@mapNotNull null
        val eq = part.indexOf('=')
        val rawKey = if (eq < 0) part else part.substring(0, eq)
        val rawVal = if (eq < 0) "" else part.substring(eq + 1)
        val key = java.net.URLDecoder.decode(rawKey, "UTF-8")
        val value = java.net.URLDecoder.decode(rawVal, "UTF-8")
        if (key.isEmpty()) null else key to value
    }.toMap()
}

fun handleLanRequest(
    request: LanHttpRequest,
    jobs: List<Job>,
    token: String,
    exists: (String) -> Boolean,
    copy: LanHistoryCopy,
): LanHttpResponse {
    if (request.method != "GET" && request.method != "HEAD") {
        return lanPlainText(405, "Method Not Allowed")
    }
    val sendBody = request.method != "HEAD"
    if (!lanTokenAllows(token, request.query["k"])) {
        return lanPlainText(401, copy.needToken, sendBody)
    }
    return when (val route = parseLanRoute(request.path)) {
        is LanRoute.Home -> {
            val html = renderLanHistoryHtml(jobs, token, copy, fileExists = exists)
            LanHttpResponse(
                status = 200,
                contentType = "text/html; charset=utf-8",
                body = html.toByteArray(Charsets.UTF_8),
                sendBody = sendBody,
            )
        }
        is LanRoute.Download, is LanRoute.Media -> {
            val (jobId, index) = when (route) {
                is LanRoute.Download -> route.jobId to route.index
                is LanRoute.Media -> route.jobId to route.index
                else -> error("unreachable")
            }
            val target = resolveLanDownload(jobs, jobId, index, exists)
                ?: return lanPlainText(404, "Not Found", sendBody)
            val inline = route is LanRoute.Media
            LanHttpResponse(
                status = 200,
                contentType = target.contentType,
                body = ByteArray(0),
                headers = mapOf(
                    "Content-Type" to target.contentType,
                    "Content-Disposition" to lanContentDisposition(target.downloadName, inline),
                    "Accept-Ranges" to "bytes",
                ),
                filePath = target.path,
                rangeHeader = request.headers["range"],
                sendBody = sendBody,
            )
        }
        is LanRoute.NotFound -> lanPlainText(404, "Not Found", sendBody)
    }
}

private fun lanPlainText(status: Int, body: String, sendBody: Boolean = true): LanHttpResponse = LanHttpResponse(
    status = status,
    contentType = "text/plain; charset=utf-8",
    body = body.toByteArray(Charsets.UTF_8),
    sendBody = sendBody,
)
