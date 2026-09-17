package com.videoconverter.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.videoconverter.android.MainActivity
import com.videoconverter.android.R
import com.videoconverter.android.withAppLocales
import com.videoconverter.android.data.JobStore
import com.videoconverter.android.data.LanShareStore
import com.videoconverter.android.lan.LAN_SHARE_PORT_ATTEMPTS
import com.videoconverter.android.lan.LanHttpResponse
import com.videoconverter.android.lan.applyLanResponseRange
import com.videoconverter.android.lan.chooseLanPort
import com.videoconverter.android.lan.collectLanIfaces
import com.videoconverter.android.lan.copyLanRange
import com.videoconverter.android.lan.handleLanRequest
import com.videoconverter.android.lan.isLanContentLocation
import com.videoconverter.android.lan.lanFileIsRegular
import com.videoconverter.android.lan.lanHistoryCopy
import com.videoconverter.android.lan.openLanServerSocket
import com.videoconverter.android.lan.parseHttpRequestLine
import com.videoconverter.android.lan.parseLanHeaderLines
import com.videoconverter.android.lan.pickLanIpv4
import java.io.File
import java.io.IOException
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.LinkOption

class LanShareService : Service() {
    private lateinit var lanShareStore: LanShareStore
    private lateinit var jobStore: JobStore

    @Volatile
    private var running = false
    private var serverThread: Thread? = null
    @Volatile
    private var serverSocket: ServerSocket? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        lanShareStore = LanShareStore(this)
        jobStore = JobStore(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        if (intent?.action == ACTION_STOP) {
            persistDisabledAndStop()
            return START_NOT_STICKY
        }
        startServerIfNeeded()
        return START_STICKY
    }

    override fun onTimeout(startId: Int) {
        persistDisabledAndStop()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        persistDisabledAndStop()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        shutdownServer()
        super.onDestroy()
    }

    private fun startServerIfNeeded() {
        if (running) return
        running = true
        serverThread = Thread(::serveLoop, "lan-share").also { it.start() }
    }

    private fun serveLoop() {
        while (running) {
            val ipv4 = pickLanIpv4(currentLanIfaces())
            if (ipv4 == null) {
                setUnbound(error = null)
                sleepInterruptibly(RETRY_MS)
                continue
            }
            val server = bindLanServer(ipv4)
            if (server == null) {
                setUnbound(error = withAppLocales().getString(R.string.lan_ports_busy))
                sleepInterruptibly(RETRY_MS)
                continue
            }
            serverSocket = server
            boundPort = server.localPort
            boundIpv4 = ipv4
            boundError = null
            try {
                acceptLoop(server, ipv4)
            } finally {
                runCatching { server.close() }
                if (serverSocket === server) serverSocket = null
            }
        }
        setUnbound(error = null)
    }

    private fun acceptLoop(server: ServerSocket, boundIp: String) {
        server.soTimeout = RETRY_MS.toInt()
        while (running) {
            val currentIp = pickLanIpv4(currentLanIfaces())
            if (currentIp == null || currentIp != boundIp) {
                setUnbound(error = null)
                runCatching { server.close() }
                break
            }
            val socket = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: SocketException) {
                break
            } catch (_: IOException) {
                if (!running) break
                continue
            }
            socket.use { handleClient(it) }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = CLIENT_TIMEOUT_MS
            val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = input.readLine() ?: return
            val headerLines = mutableListOf<String>()
            while (true) {
                val header = input.readLine() ?: break
                if (header.isEmpty()) break
                headerLines += header
            }
            val request = parseHttpRequestLine(requestLine)?.copy(headers = parseLanHeaderLines(headerLines)) ?: return
            val token = lanShareStore.load().token
            val jobs = jobStore.load()
            val response = handleLanRequest(request, jobs, token, ::lanLocationExists, lanHistoryCopy(withAppLocales().resources))
            writeResponse(socket, response)
        } catch (_: Exception) {
            // Close the client socket without logging request contents (token lives in query).
        }
    }

    private fun writeResponse(socket: Socket, response: LanHttpResponse) {
        val location = response.filePath
        val total = if (location != null) lanLocationLength(location) else -1L
        val sized = when {
            location != null && total < 0 -> LanHttpResponse(
                status = 404,
                contentType = "text/plain; charset=utf-8",
                body = "Not Found".toByteArray(Charsets.UTF_8),
                sendBody = response.sendBody,
            )
            location != null -> applyLanResponseRange(response, total)
            else -> response
        }
        val out = socket.getOutputStream()
        val reason = when (sized.status) {
            200 -> "OK"
            206 -> "Partial Content"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            416 -> "Range Not Satisfiable"
            else -> "OK"
        }
        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = sized.contentType
        headers.putAll(sized.headers)
        if (!headers.containsKey("Content-Length")) {
            headers["Content-Length"] = sized.body.size.toString()
        }
        headers["Connection"] = "close"
        val headerBytes = buildString {
            append("HTTP/1.1 ${sized.status} $reason\r\n")
            for ((name, value) in headers) {
                append("$name: $value\r\n")
            }
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)
        out.write(headerBytes)
        if (sized.sendBody) {
            val path = sized.filePath
            if (path != null) {
                openLanStream(path)?.use { input ->
                    val length = sized.byteLength
                    if (length != null) {
                        copyLanRange(input, out, sized.byteStart, length)
                    } else {
                        input.copyTo(out)
                    }
                }
            } else {
                out.write(sized.body)
            }
        }
        out.flush()
    }

    private fun lanLocationExists(path: String): Boolean = lanLocationLength(path) >= 0

    private fun lanLocationLength(location: String): Long {
        if (isLanContentLocation(location)) {
            return try {
                contentResolver.openAssetFileDescriptor(android.net.Uri.parse(location), "r")?.use { it.length } ?: -1L
            } catch (_: Exception) {
                -1L
            }
        }
        if (!lanFileIsRegular(location)) return -1L
        return File(location).length()
    }

    private fun openLanStream(location: String): java.io.InputStream? {
        if (isLanContentLocation(location)) {
            return try {
                contentResolver.openInputStream(android.net.Uri.parse(location))
            } catch (_: Exception) {
                null
            }
        }
        if (!lanFileIsRegular(location)) return null
        return Files.newInputStream(java.io.File(location).toPath(), LinkOption.NOFOLLOW_LINKS)
    }

    private fun bindLanServer(ip: String): ServerSocket? {
        val occupied = mutableSetOf<Int>()
        repeat(LAN_SHARE_PORT_ATTEMPTS) {
            val port = chooseLanPort(occupied = occupied) ?: return null
            try {
                return openLanServerSocket(ip, port)
            } catch (_: IOException) {
                occupied += port
            }
        }
        return null
    }

    private fun currentLanIfaces() = try {
        collectLanIfaces(NetworkInterface.getNetworkInterfaces()?.toList().orEmpty())
    } catch (_: SocketException) {
        emptyList()
    }

    private fun startForegroundNotification() {
        val openApp = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).putExtra(EXTRA_OPEN_LAN_SHARE, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopShare = PendingIntent.getForegroundService(
            this,
            STOP_REQUEST_CODE,
            Intent(this, LanShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(withAppLocales().getString(R.string.app_name))
            .setContentText(withAppLocales().getString(R.string.lan_notify_on))
            .setContentIntent(openApp)
            .addAction(Notification.Action.Builder(null, withAppLocales().getString(R.string.lan_notify_stop), stopShare).build())
            .setOngoing(true)
            .build()
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            withAppLocales().getString(R.string.lan_notify_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun shutdownServer() {
        running = false
        val socket = serverSocket
        serverSocket = null
        runCatching { socket?.close() }
        serverThread?.interrupt()
        serverThread?.join(JOIN_TIMEOUT_MS)
        serverThread = null
        clearBound()
    }

    private fun persistDisabledAndStop() {
        try {
            persistEnabledFalse()
        } finally {
            shutdownServer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun persistEnabledFalse() {
        val current = lanShareStore.load()
        if (current.enabled) {
            lanShareStore.save(current.copy(enabled = false))
        }
    }

    private fun setUnbound(error: String?) {
        boundPort = null
        boundIpv4 = null
        boundError = error
    }

    private fun clearBound() {
        setUnbound(error = null)
    }

    private fun sleepInterruptibly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        const val ACTION_STOP = "com.videoconverter.android.service.action.LAN_SHARE_STOP"
        const val EXTRA_OPEN_LAN_SHARE = "openLanShare"
        private const val CHANNEL_ID = "lan-share"
        private const val NOTIFICATION_ID = 1002
        private const val STOP_REQUEST_CODE = 1003
        private const val RETRY_MS = 2_000L
        private const val CLIENT_TIMEOUT_MS = 15_000
        private const val JOIN_TIMEOUT_MS = 1_000L

        @Volatile
        var boundPort: Int? = null
            private set

        @Volatile
        var boundIpv4: String? = null
            private set

        @Volatile
        var boundError: String? = null
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, LanShareService::class.java))
        }

        fun stop(context: Context) {
            context.startForegroundService(
                Intent(context, LanShareService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
