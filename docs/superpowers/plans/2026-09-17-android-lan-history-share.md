# Android 局域网访问历史 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在「我的」里提供默关的局域网 HTTP，供同网浏览器查看转码历史并下载已完成文件。

**Architecture:** 纯函数处理口令、路由、下载解析和 HTML；`LanShareStore` 持久化开关和口令；独立前台服务 `LanShareService` 用 `ServerSocket` 提供 GET。不引入 Ktor，不并入 `TranscodeService`。

**Tech Stack:** Kotlin、Jetpack Compose、JUnit4、`ServerSocket`、现有 `JobStore` / `historySegmentFor`。不改桌面 `src/`。

## Global Constraints

- 规格：`docs/superpowers/specs/2026-09-17-android-lan-history-share-design.md`
- 开关默认关；口令可留空；保存时 trim，空则无口令；不自动换口令
- GET `/`、`/d/{jobId}`、`/d/{jobId}/{index}`；口令走查询参数 `k=`
- 只读，不下源文件路径到 HTML；只下载 Completed 且仍在的任务输出
- 端口优先 `17890`，占用则顺延最多 10 个；只展示非回环 IPv4
- 独立 FGS + 通知「局域网访问已开启」；无开机广播
- 中文文案；JDK 17：`JAVA_HOME=/Users/wuyu/.sdkman/candidates/java/17.0.11-amzn`，`ANDROID_HOME=/Users/wuyu/Library/Android/sdk`
- 现有 JVM 单元测试必须继续通过
- 不上 Navigation、不上 Ktor、不 HTTPS

## File map

```
android/app/src/main/java/com/videoconverter/android/lan/
  LanShare.kt          # 设置、口令、路由、下载、HTML、URL、端口、IPv4
android/app/src/main/java/com/videoconverter/android/data/
  LanShareStore.kt     # 持久化 enabled + token
android/app/src/main/java/com/videoconverter/android/service/
  LanShareService.kt   # ServerSocket + 前台通知
android/app/src/main/java/com/videoconverter/android/ui/
  RootTabs.kt          # MinePage.LanShare、列表顺序、隐私文案
  RootScreens.kt / LanShareScreen.kt / AppScreen.kt / AppViewModel.kt
android/app/src/main/AndroidManifest.xml
android/app/src/main/java/com/videoconverter/android/MainActivity.kt
```

---

### Task 1: 口令与默认设置

**Files:**
- Create: `android/app/src/main/java/com/videoconverter/android/lan/LanShare.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/lan/LanShareAuthTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `data class LanShareSettings(val enabled: Boolean = false, val token: String = "")`
  - `fun normalizeLanToken(raw: String): String`
  - `fun lanTokenAllows(storedToken: String, queryK: String?): Boolean`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.videoconverter.android.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanShareAuthTest {
    @Test
    fun defaultsAreOffAndEmptyToken() {
        val settings = LanShareSettings()
        assertFalse(settings.enabled)
        assertEquals("", settings.token)
    }

    @Test
    fun normalizeTrimsAndBlankBecomesEmpty() {
        assertEquals("secret", normalizeLanToken("  secret  "))
        assertEquals("", normalizeLanToken("   "))
        assertEquals("", normalizeLanToken(""))
    }

    @Test
    fun emptyTokenAllowsMissingOrAnyK() {
        assertTrue(lanTokenAllows("", null))
        assertTrue(lanTokenAllows("", "whatever"))
    }

    @Test
    fun setTokenRequiresExactMatch() {
        assertFalse(lanTokenAllows("pw", null))
        assertFalse(lanTokenAllows("pw", ""))
        assertFalse(lanTokenAllows("pw", "PW"))
        assertTrue(lanTokenAllows("pw", "pw"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
export JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/17.0.11-amzn}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
cd android && ./gradlew :app:testDebugUnitTest --tests com.videoconverter.android.lan.LanShareAuthTest
```

Expected: FAIL（`LanShareSettings` / 函数未定义）

- [ ] **Step 3: Write minimal implementation**

在 `LanShare.kt`：

```kotlin
package com.videoconverter.android.lan

data class LanShareSettings(
    val enabled: Boolean = false,
    val token: String = "",
)

fun normalizeLanToken(raw: String): String = raw.trim()

fun lanTokenAllows(storedToken: String, queryK: String?): Boolean {
    if (storedToken.isEmpty()) return true
    return queryK == storedToken
}
```

- [ ] **Step 4: Run tests and make sure they pass**

同一条 gradle 命令。Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/videoconverter/android/lan/LanShare.kt \
  android/app/src/test/java/com/videoconverter/android/lan/LanShareAuthTest.kt
git commit -m "$(cat <<'EOF'
feat(android): 局域网访问口令可留空并按原文校验

EOF
)"
```

---

### Task 2: 路由与下载解析

**Files:**
- Modify: `android/app/src/main/java/com/videoconverter/android/lan/LanShare.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/lan/LanShareDownloadTest.kt`

**Interfaces:**
- Consumes: `Job`、`JobStatus`；Task 1
- Produces:
  - `sealed class LanRoute { data object Home; data class Download(val jobId: String, val index: Int); data object NotFound }`
  - `fun parseLanRoute(path: String): LanRoute`
  - `fun jobOutputPaths(job: Job): List<String>`
  - `data class LanDownloadTarget(val path: String, val downloadName: String, val contentType: String)`
  - `fun resolveLanDownload(jobs: List<Job>, jobId: String, index: Int, exists: (String) -> Boolean): LanDownloadTarget?`
  - `fun lanContentType(fileName: String): String`
  - `fun lanContentDisposition(fileName: String): String`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.videoconverter.android.lan

import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.MediaInfo
import com.videoconverter.android.domain.OutputConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanShareDownloadTest {
    private val done = job(
        id = "a1",
        status = JobStatus.Completed,
        outputPath = "/tmp/out.mp4",
        outputPaths = listOf("/tmp/out.mp4"),
        displayName = "clip.mp4",
    )

    @Test
    fun parsesHomeAndDownloadRoutes() {
        assertEquals(LanRoute.Home, parseLanRoute("/"))
        assertEquals(LanRoute.Download("a1", 0), parseLanRoute("/d/a1"))
        assertEquals(LanRoute.Download("a1", 2), parseLanRoute("/d/a1/2"))
        assertEquals(LanRoute.NotFound, parseLanRoute("/d/"))
        assertEquals(LanRoute.NotFound, parseLanRoute("/d/a1/x"))
        assertEquals(LanRoute.NotFound, parseLanRoute("/d/../secret"))
        assertEquals(LanRoute.NotFound, parseLanRoute("/other"))
    }

    @Test
    fun onlyCompletedExistingIndexedOutputsDownload() {
        val exists = { path: String -> path == "/tmp/out.mp4" }
        val ok = resolveLanDownload(listOf(done), "a1", 0, exists)!!
        assertEquals("/tmp/out.mp4", ok.path)
        assertTrue(ok.downloadName.contains("clip") || ok.downloadName.endsWith(".mp4"))

        assertNull(resolveLanDownload(listOf(done), "missing", 0, exists))
        assertNull(resolveLanDownload(listOf(done.copy(status = JobStatus.Running)), "a1", 0, exists))
        assertNull(resolveLanDownload(listOf(done), "a1", 0) { false })
        assertNull(resolveLanDownload(listOf(done), "a1", 1, exists))
    }

    @Test
    fun fallsBackToOutputPathAndRejectsTraversalJobId() {
        val single = done.copy(outputPaths = emptyList(), outputPath = "/tmp/out.mp4")
        assertEquals("/tmp/out.mp4", jobOutputPaths(single).single())
        assertEquals(LanRoute.NotFound, parseLanRoute("/d/a1/../b"))
        assertTrue(lanContentDisposition("a\r\nb.mp4").none { it == '\r' || it == '\n' })
        assertEquals("video/mp4", lanContentType("clip.mp4"))
        assertEquals("application/octet-stream", lanContentType("clip.bin"))
    }
}

internal fun job(
    id: String,
    status: JobStatus,
    outputPath: String?,
    outputPaths: List<String>,
    displayName: String = "clip.mp4",
    preset: String = "mp4-h264",
) = Job(
    id = id,
    sourceUri = "content://secret/$id",
    displayName = displayName,
    outputPath = outputPath,
    status = status,
    progress = 1.0,
    error = null,
    config = OutputConfig(preset = preset),
    media = MediaInfo(sourceUri = "content://secret/$id", displayName = displayName, importable = true),
    outputPaths = outputPaths,
)
```

把 `job()` 放进测试文件即可；不要放到 production。

- [ ] **Step 2: Run test to verify it fails**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests com.videoconverter.android.lan.LanShareDownloadTest
```

Expected: FAIL

- [ ] **Step 3: Write minimal implementation**

追加到 `LanShare.kt`（`java.net.URLEncoder` 仅给 Content-Disposition 的 filename* 用）：

```kotlin
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
```

需要 `import com.videoconverter.android.domain.Job` 和 `JobStatus`。

- [ ] **Step 4: Run tests**

`LanShareAuthTest` 与 `LanShareDownloadTest`。Expected: PASS

- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(android): 解析局域网下载路由并限制输出路径

EOF
)"
```

---

### Task 3: 公网展示 URL、端口、IPv4

**Files:**
- Modify: `android/app/src/main/java/com/videoconverter/android/lan/LanShare.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/lan/LanShareAddressTest.kt`

**Interfaces:**
- Consumes: Task 1 的 token
- Produces:
  - `const val LAN_SHARE_PREFERRED_PORT = 17890`
  - `const val LAN_SHARE_PORT_ATTEMPTS = 10`
  - `data class LanIface(val name: String, val hostAddress: String, val loopback: Boolean)`
  - `fun pickLanIpv4(ifaces: List<LanIface>): String?`
  - `fun chooseLanPort(preferred: Int = LAN_SHARE_PREFERRED_PORT, attempts: Int = LAN_SHARE_PORT_ATTEMPTS, occupied: Set<Int>): Int?`
  - `fun lanPublicUrl(ip: String, port: Int, token: String): String`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.videoconverter.android.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanShareAddressTest {
    @Test
    fun prefersWifiIpv4AndSkipsLoopback() {
        assertNull(pickLanIpv4(listOf(LanIface("lo", "127.0.0.1", true))))
        assertEquals(
            "10.0.0.8",
            pickLanIpv4(
                listOf(
                    LanIface("rmnet0", "10.20.30.40", false),
                    LanIface("wlan0", "10.0.0.8", false),
                ),
            ),
        )
        assertEquals("192.168.43.1", pickLanIpv4(listOf(LanIface("wlan1", "192.168.43.1", false))))
        assertNull(pickLanIpv4(listOf(LanIface("wlan0", "fe80::1", false))))
    }

    @Test
    fun portWalksForwardWhenBusy() {
        assertEquals(17890, chooseLanPort(occupied = emptySet()))
        assertEquals(17892, chooseLanPort(occupied = setOf(17890, 17891)))
        assertNull(chooseLanPort(occupied = (17890 until 17900).toSet()))
    }

    @Test
    fun publicUrlEncodesToken() {
        assertEquals("http://10.0.0.8:17890/", lanPublicUrl("10.0.0.8", 17890, ""))
        val url = lanPublicUrl("10.0.0.8", 17890, "a b")
        assertTrue(url.startsWith("http://10.0.0.8:17890/?"))
        assertTrue(url.contains("k="))
        assertTrue(!url.contains("a b"))
    }
}
```

- [ ] **Step 2: Run to verify fail**

`--tests com.videoconverter.android.lan.LanShareAddressTest` Expected: FAIL

- [ ] **Step 3: Implement**

```kotlin
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
```

- [ ] **Step 4: Tests pass** — Auth + Download + Address

- [ ] **Step 5: Commit** `feat(android): 选择局域网 IPv4 与展示用访问地址`

---

### Task 4: 历史 HTML

**Files:**
- Modify: `android/app/src/main/java/com/videoconverter/android/lan/LanShare.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/lan/LanHistoryHtmlTest.kt`

**Interfaces:**
- Consumes: `historyJobs`、`historyEmptyLabel`、`historySegmentFor`、`statusLabel`（`AppViewModel.kt` 已有）、`resolveConfig`、`jobOutputPaths`、`lanTokenAllows` 不在 HTML 内做鉴权
- Produces: `fun renderLanHistoryHtml(jobs: List<Job>, token: String, fileExists: (String) -> Boolean): String`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.videoconverter.android.lan

import com.videoconverter.android.domain.JobStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanHistoryHtmlTest {
    @Test
    fun groupsAndHidesSourceUri() {
        val jobs = listOf(
            job("v", JobStatus.Completed, "/tmp/v.mp4", listOf("/tmp/v.mp4"), "假期.mp4", "mp4-h264"),
            job("a", JobStatus.Queued, null, emptyList(), "song.mp3", "audio-mp3"),
            job("d", JobStatus.Completed, "/tmp/a.pdf", listOf("/tmp/a.pdf", "/tmp/b.pdf"), "scan.pdf", "pdf-split"),
        )
        val html = renderLanHistoryHtml(jobs, "pw") { it.startsWith("/tmp/") }
        assertTrue(html.contains("视频"))
        assertTrue(html.contains("音频"))
        assertTrue(html.contains("文档"))
        assertTrue(html.contains("假期.mp4"))
        assertTrue(html.contains("/d/v?k="))
        assertTrue(html.contains("/d/d/1?k="))
        assertFalse(html.contains("content://secret"))
        assertTrue(html.contains("排队中"))
        assertFalse(html.contains("/d/a"))
    }

    @Test
    fun emptyLabelsAndEscapesHtml() {
        val html = renderLanHistoryHtml(
            listOf(job("x", JobStatus.Completed, "/t/a.mp4", listOf("/t/a.mp4"), "<img>", "mp4-h264")),
            "",
        ) { true }
        assertTrue(html.contains("还没有音频记录"))
        assertTrue(html.contains("还没有文档记录"))
        assertFalse(html.contains("<img>"))
        assertTrue(html.contains("&lt;img&gt;"))
        assertTrue(html.contains("href=\"/d/x\""))
    }

    @Test
    fun missingFileHasNoDownload() {
        val html = renderLanHistoryHtml(
            listOf(job("v", JobStatus.Completed, "/tmp/gone.mp4", listOf("/tmp/gone.mp4"), "gone.mp4")),
            "",
        ) { false }
        assertFalse(html.contains("href=\"/d/v\""))
    }
}
```

- [ ] **Step 2: Fail** — `--tests com.videoconverter.android.lan.LanHistoryHtmlTest`

- [ ] **Step 3: Implement `renderLanHistoryHtml`**

要求：

- 无外链 `<script src>`；内联 CSS 用 `#ecece8` `#1f2428` `#c45a2a`
- 三段标题「视频」「音频」「文档」，顺序固定；每段用 `historyJobs(jobs, segment)`，空则 `historyEmptyLabel`
- 行内：escape 后的 `displayName`、格式（`resolveConfig(job.config).getOrNull()?.container ?: job.config.preset`）、`statusLabel(job.status)`
- 可下载：Completed 且 `jobOutputPaths` 里 `fileExists` 为真的每一项，链接 `/d/{id}` 或 `/d/{id}/{index}`（index>0 或多文件时都带 index；单文件 index 0 用 `/d/{id}`）
- 有 token 时所有下载链接加 `?k=` + `URLEncoder`
- 页面顶部一句：无口令时「同一网络中知道此地址的设备可以查看记录并下载已完成文件。」
- `escapeHtml`: `& < > "` → 实体

把 `LanHistoryHtmlTest` 需要的 `job()` 复用 Task 2 测试文件里的同构辅助函数（可复制，不要跨测试源集依赖）。

- [ ] **Step 4: Tests pass**

- [ ] **Step 5: Commit** `feat(android): 渲染局域网历史页并转义文件名`

---

### Task 5: HTTP 处理器

**Files:**
- Modify: `android/app/src/main/java/com/videoconverter/android/lan/LanShare.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/lan/LanShareHandlerTest.kt`

**Interfaces:**
- Consumes: Tasks 1–4
- Produces:
  - `data class LanHttpRequest(val method: String, val path: String, val query: Map<String, String>)`
  - `data class LanHttpResponse(val status: Int, val contentType: String, val body: ByteArray, val headers: Map<String, String> = emptyMap(), val filePath: String? = null)`
  - `fun parseLanQuery(rawQuery: String?): Map<String, String>`
  - `fun handleLanRequest(request: LanHttpRequest, jobs: List<Job>, token: String, exists: (String) -> Boolean): LanHttpResponse`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.videoconverter.android.lan

import com.videoconverter.android.domain.JobStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanShareHandlerTest {
    private val jobs = listOf(
        job("v", JobStatus.Completed, "/tmp/v.mp4", listOf("/tmp/v.mp4"), "假期.mp4"),
        job("q", JobStatus.Queued, null, emptyList(), "wait.mp4"),
    )
    private val exists = { path: String -> path == "/tmp/v.mp4" }

    @Test
    fun postIsMethodNotAllowed() {
        val res = handleLanRequest(LanHttpRequest("POST", "/", emptyMap()), jobs, "", exists)
        assertEquals(405, res.status)
    }

    @Test
    fun tokenRequiredWhenSet() {
        val denied = handleLanRequest(LanHttpRequest("GET", "/", emptyMap()), jobs, "pw", exists)
        assertEquals(401, denied.status)
        assertFalse(String(denied.body, Charsets.UTF_8).contains("假期.mp4"))
        val ok = handleLanRequest(LanHttpRequest("GET", "/", mapOf("k" to "pw")), jobs, "pw", exists)
        assertEquals(200, ok.status)
        assertTrue(ok.contentType.startsWith("text/html"))
        assertTrue(String(ok.body, Charsets.UTF_8).contains("假期.mp4"))
    }

    @Test
    fun downloadAndUnknown() {
        val file = handleLanRequest(LanHttpRequest("GET", "/d/v", mapOf("k" to "pw")), jobs, "pw", exists)
        assertEquals(200, file.status)
        assertEquals("/tmp/v.mp4", file.filePath)
        assertTrue(file.headers["Content-Disposition"]!!.contains("attachment"))
        assertEquals(404, handleLanRequest(LanHttpRequest("GET", "/d/q", emptyMap()), jobs, "", exists).status)
        assertEquals(404, handleLanRequest(LanHttpRequest("GET", "/nope", emptyMap()), jobs, "", exists).status)
        assertEquals(404, handleLanRequest(LanHttpRequest("GET", "/d/v/9", emptyMap()), jobs, "", exists).status)
    }

    @Test
    fun parseQueryDecodesK() {
        assertEquals("a b", parseLanQuery("k=a+b")["k"])
    }
}
```

- [ ] **Step 2: Fail**

- [ ] **Step 3: Implement**

401 正文：`需要正确口令`，`text/plain; charset=utf-8`。405/404 纯文本。HTML `200` 的 body 为 `renderLanHistoryHtml`。下载成功：`status=200`，`filePath` 为解析路径，`body` 可空数组，`Content-Type` 与 `Content-Disposition` 放入 headers。`parseLanQuery` 用 `URLDecoder`。path 不要含 query（调用方拆开）。

- [ ] **Step 4: Tests pass**（本任务 + 既有 lan 测试）

- [ ] **Step 5: Commit** `feat(android): 局域网 HTTP 只读 GET 并校验口令`

---

### Task 6: 设置持久化

**Files:**
- Create: `android/app/src/main/java/com/videoconverter/android/data/LanShareStore.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/data/LanShareStoreTest.kt`

**Interfaces:**
- Consumes: `LanShareSettings`、`normalizeLanToken`
- Produces:
  - `class LanShareStore(file: File)` 或 `(context: Context)` 内部 `filesDir/lan-share.json`
  - `fun load(): LanShareSettings`
  - `fun save(settings: LanShareSettings)`
  - 测试可走内部 `loadLanShare` / `saveLanShare` 文件函数以免 Robolectric

推荐与 `JobStore` 相同的 JSON 文件 + tmp 替换：

```kotlin
internal fun lanShareFromJson(json: String): LanShareSettings
internal fun lanShareToJson(settings: LanShareSettings): String
internal fun loadLanShareOrDefault(file: File): LanShareSettings
internal fun saveLanShare(file: File, temporary: File, settings: LanShareSettings)
```

`save` 时 `token = normalizeLanToken(settings.token)`。

- [ ] **Step 1: Failing tests** — 缺文件默认 `enabled=false, token=""`；写入再读回；`"  x  "` 存成 `"x"`；坏 JSON 当默认关。

- [ ] **Step 2: Fail** `--tests com.videoconverter.android.data.LanShareStoreTest`

- [ ] **Step 3: Implement** `{"enabled":false,"token":""}`

- [ ] **Step 4: Pass**

- [ ] **Step 5: Commit** `feat(android): 持久化局域网访问开关和口令`

---

### Task 7: 前台服务与清单

**Files:**
- Create: `android/app/src/main/java/com/videoconverter/android/service/LanShareService.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`
- Modify: `android/app/src/main/java/com/videoconverter/android/MainActivity.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/lan/LanShareBindTest.kt`（纯函数：从 `NetworkInterface` 模型已在 Task 3；本任务测 `collectLanIfaces` 若提取；否则测 `parseRequestLine`）

**Interfaces:**
- Consumes: `handleLanRequest`、`chooseLanPort`、`pickLanIpv4`、`LanShareStore`、`JobStore`
- Produces:
  - `LanShareService.start(context)` / `stop(context)` / `ACTION_STOP`
  - `fun parseHttpRequestLine(line: String): LanHttpRequest?`
  - 通知 id 与转码 `1001` 不同（用 `1002`），channel `lan-share`，文案「局域网访问已开启」
  - `companion object val boundPort: Int?`、`val boundIpv4: String?` 供 UI 读（`@Volatile`）

- [ ] **Step 1: Failing test for request line**

```kotlin
@Test
fun parseRequestLineSplitsPathAndQuery() {
    val req = parseHttpRequestLine("GET /d/v?k=pw HTTP/1.1")!!
    assertEquals("GET", req.method)
    assertEquals("/d/v", req.path)
    assertEquals("pw", req.query["k"])
    assertNull(parseHttpRequestLine("GET"))
}
```

放在 `LanShareHandlerTest` 或 `LanShareBindTest`。

- [ ] **Step 2: Fail**

- [ ] **Step 3: Implement service**

行为：

- Manifest：`INTERNET`、`ACCESS_WIFI_STATE`、`ACCESS_NETWORK_STATE`；`<service android:name=".service.LanShareService" android:exported="false" android:foregroundServiceType="dataSync" android:stopWithTask="false" />`
- `onStartCommand`：`startForeground`；若 action 为 STOP：写 `enabled=false`、`stopSelf`，**不要**在普通 `onDestroy` 里把 enabled 写成 false（避免进程被杀后开关被清掉）
- 后台线程：`chooseLanPort` 用尝试 `ServerSocket(port)`；成功则记录 `boundPort`/`boundIpv4`（`pickLanIpv4` 来自 `NetworkInterface.getNetworkInterfaces()` 映射成 `LanIface`）
- 无 IPv4：不 listen，清 `boundIpv4`，保持 FGS；可短间隔重试
- accept 循环：读请求行 + 忽略 headers 直到空行；`handleLanRequest`；下载用 `Files.copy` 流式写；写完关 socket
- `START_STICKY`
- 通知 PendingIntent 打开 `MainActivity`，extra `openLanShare=true`
- `stop`：关闭 ServerSocket、`stopForeground`、`stopSelf`

`MainActivity.onCreate`：若 `LanShareStore.load().enabled` 则 `LanShareService.start(this)`。若 intent extra `openLanShare`，不在 Activity 里直接改 tab（下一任务 UI 读 extra）。

- [ ] **Step 4:** 单测 request line PASS；`./gradlew :app:compileDebugKotlin` PASS

- [ ] **Step 5: Commit** `feat(android): 用前台服务提供局域网历史 HTTP`

---

### Task 8: 「我的」页、隐私文案、开关

**Files:**
- Modify: `android/app/src/main/java/com/videoconverter/android/ui/RootTabs.kt`
- Modify: `android/app/src/test/java/com/videoconverter/android/ui/RootTabsTest.kt`
- Create: `android/app/src/main/java/com/videoconverter/android/ui/LanShareScreen.kt`
- Modify: `android/app/src/main/java/com/videoconverter/android/ui/RootScreens.kt` 仅当把入口放这里；优先独立 `LanShareScreen.kt`
- Modify: `android/app/src/main/java/com/videoconverter/android/ui/AppScreen.kt`
- Modify: `android/app/src/main/java/com/videoconverter/android/MainActivity.kt`（把 `openLanShare` 交给界面：可用 `intent.getBooleanExtra` 在 `AppScreen` 的 `LaunchedEffect`）

**Interfaces:**
- Consumes: `LanShareStore`、`LanShareService`、`lanPublicUrl`、`MinePage`
- Produces: `MinePage.LanShare`；`mineItems()` 第一项为局域网访问

- [ ] **Step 1: Update RootTabsTest**

`mineEntriesArePrivacyTermsAndAbout` 改为：

```kotlin
assertEquals(
    listOf(MinePage.LanShare, MinePage.Privacy, MinePage.Terms, MinePage.About),
    mineItems().map { it.page },
)
assertEquals("局域网访问", minePageTitle(MinePage.LanShare))
```

`legalCopyStaysLocalAndOffline` 增加：隐私文案包含「局域网访问」，且仍包含「不会上传」「不要求联网才能转码」。

`leavingMineResetsDetail` / `consumeRootBack` 对 `MinePage.LanShare` 返回 Root（现有 `minePage != Root` 已覆盖，补一条 assert）。

- [ ] **Step 2: Run RootTabsTest — FAIL**（缺 enum / 文案）

- [ ] **Step 3: Implement UI**

- `enum class MinePage { Root, LanShare, Privacy, Terms, About }`
- `mineItems` 最前 `MineItem(MinePage.LanShare, "局域网访问")`
- `PRIVACY_BODY` 增补：用户主动打开局域网访问后，同一网络中持有地址（以及口令，若已设置）的设备可以查看历史并下载已完成文件；转码本身仍不要求联网。
- `LanShareScreen`：`Switch`（开关语义）、「口令」`OutlinedTextField` placeholder「可留空」、失焦/`onFocusChanged` 或 IME Done 时 `save(token)`；开且有 `boundIpv4`+`boundPort` 显示可复制地址；无 IPv4 显示「先连上 Wi‑Fi 或热点」；口令空时说明同网即可访问
- 开关开：`save(enabled=true)` + `LanShareService.start`；关：`save(enabled=false)` + `LanShareService.stop`
- 口令保存后若服务已在跑，不必重启（服务每次请求读 store）
- `AppScreen`：`MinePage.LanShare` 走 `LanShareScreen` 而不是法律长文；`onOpen` 仍设置 `minePage`
- 打开通知：`AppScreen` 若 `openLanShare` extra 为 true，则 `tab = Mine`、`minePage = LanShare`

Material3 `Switch`、`OutlinedTextField` 已在工程里可用。复制用 `ClipboardManager`。

轮询或每秒读 `LanShareService.boundIpv4/port` 以更新地址（`LaunchedEffect` + delay）即可，不必事件总线。

- [ ] **Step 4:**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests com.videoconverter.android.lan.LanShareAuthTest \
  --tests com.videoconverter.android.lan.LanShareDownloadTest \
  --tests com.videoconverter.android.lan.LanShareAddressTest \
  --tests com.videoconverter.android.lan.LanHistoryHtmlTest \
  --tests com.videoconverter.android.lan.LanShareHandlerTest \
  --tests com.videoconverter.android.data.LanShareStoreTest \
  --tests com.videoconverter.android.ui.RootTabsTest
cd android && ./gradlew :app:assembleDebug
```

Expected: 全部 PASS，APK 产出。

手动：安装后「我的 → 局域网访问」默认关；打开后同一 Wi‑Fi 电脑打开链接能看历史；设口令后无 `k=` 为 401；关掉开关浏览器连不上。

- [ ] **Step 5: Commit** `feat(android): 在我的里开关局域网访问历史`

---

## Spec coverage

| Spec | Task |
| --- | --- |
| 默认关、口令可空/自设、trim | 1, 6 |
| 路由 GET / 与下载、401/404/405 | 2, 5 |
| 只 Completed 真实输出、无路径穿越、无 sourceUri | 2, 4 |
| HTML 三段与空态、下载带 k | 4 |
| IPv4、端口 17890+、展示 URL | 3 |
| FGS、通知、STOP 写 enabled=false、重启后 App 再拉起 | 7 |
| 我的入口、开关、复制、无 Wi‑Fi 提示 | 8 |
| 隐私补句 | 8 |
| 无 Ktor、无开机广播、无网页改记录 | 全局 / 不实现 |

## Placeholder scan

无 TBD。后续任务使用的类型名与 Task 1–5 的 `LanShareSettings`、`LanRoute`、`handleLanRequest` 一致。
