# Android 局域网媒体库 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 电脑浏览器打开局域网地址后，能按左列表右播放器预览/播放已完成文件，并能下载；`content://` 成品与磁盘文件同等可用。

**Architecture:** 继续 `LanShareService` + `ServerSocket`，不引入 Ktor。新增 `/m` 内联 + Range，保留 `/d` 附件。打开输出时 `content://` 走 `ContentResolver`，磁盘路径仍 `NOFOLLOW_LINKS`。HTML 改为浅壳深播放区的单页媒体库。

**Tech Stack:** Kotlin、JUnit4、Robolectric、现有 AppCompat / Compose。不改桌面 `src/`。

## Global Constraints

- 规格：`docs/superpowers/specs/2026-09-17-android-lan-media-library-design.md`
- 设置/口令/Wi‑Fi 绑定/端口 17890 起 10 个：保持现状
- **禁止** Ktor、NanoHTTPD、第三方 CDN、外链脚本
- **禁止** `values-zh/`；新文案六套资源 key 对齐（en / zh-rCN / zh-rTW / zh-rHK / ja / ko）
- 不改桌面端；JVM 现有单测必须继续通过
- minSdk 29；`JAVA_HOME=/Users/wuyu/.sdkman/candidates/java/17.0.11-amzn`，`ANDROID_HOME=/Users/wuyu/Library/Android/sdk`
- 在分支 `android-lan-media-library` 上实现，不要直接改 master
- 不要暂存 leftover：`fetch-ffmpeg.mjs`、未修之前的无关 spec（本计划的 spec/plan 可以提交）

## File map

```
android/app/src/main/java/com/videoconverter/android/lan/LanMedia.kt          # 预览类型、MIME、Range、content 判定
android/app/src/main/java/com/videoconverter/android/lan/LanShare.kt          # 路由 /m、请求头、handleLanRequest
android/app/src/main/java/com/videoconverter/android/lan/LanHistoryPage.kt    # 媒体库 HTML
android/app/src/main/java/com/videoconverter/android/service/LanShareService.kt
android/app/src/main/res/values*/strings.xml                                 # 新文案 + 隐私句
android/app/src/test/java/com/videoconverter/android/lan/LanMediaTest.kt
android/app/src/test/java/com/videoconverter/android/lan/LanShareHandlerTest.kt
android/app/src/test/java/com/videoconverter/android/lan/LanShareDownloadTest.kt
android/app/src/test/java/com/videoconverter/android/lan/LanHistoryHtmlTest.kt
android/app/src/test/java/com/videoconverter/android/lan/LanHistoryCopyFixtures.kt
```

---

### Task 1: 预览类型与 MIME

**Files:**
- Create: `android/app/src/main/java/com/videoconverter/android/lan/LanMedia.kt`
- Modify: `android/app/src/main/java/com/videoconverter/android/lan/LanShare.kt` — 把 `lanContentType` / `lanContentDisposition` 迁到 `LanMedia.kt`（`LanShare.kt` 删掉同名函数，调用方 import 新文件即可；Kotlin 同包无需改 import）
- Test: `android/app/src/test/java/com/videoconverter/android/lan/LanMediaTest.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/lan/LanShareDownloadTest.kt` — 补视频 MIME 断言

**Interfaces:**
- Consumes: 无
- Produces:
  - `enum class LanPreviewKind { Video, Audio, Pdf, Image, File }`
  - `fun lanPreviewKind(fileName: String): LanPreviewKind`
  - `fun lanContentType(fileName: String): String`（从 `LanShare.kt` 迁来并扩展）
  - `fun lanContentDisposition(fileName: String, inline: Boolean = false): String`
  - `fun isLanContentLocation(location: String): Boolean` — `location` 以 `content:` 开头（忽略大小写）则为 true

- [ ] **Step 1: Write the failing test**

```kotlin
package com.videoconverter.android.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanMediaTest {
    @Test
    fun previewKindByExtension() {
        assertEquals(LanPreviewKind.Video, lanPreviewKind("a.mp4"))
        assertEquals(LanPreviewKind.Video, lanPreviewKind("a.MKV"))
        assertEquals(LanPreviewKind.Audio, lanPreviewKind("a.mp3"))
        assertEquals(LanPreviewKind.Pdf, lanPreviewKind("a.pdf"))
        assertEquals(LanPreviewKind.Image, lanPreviewKind("a.png"))
        assertEquals(LanPreviewKind.Image, lanPreviewKind("a.gif"))
        assertEquals(LanPreviewKind.File, lanPreviewKind("a.docx"))
        assertEquals(LanPreviewKind.File, lanPreviewKind("a.xlsx"))
        assertEquals(LanPreviewKind.File, lanPreviewKind("a.bin"))
    }

    @Test
    fun contentTypeCoversPlayableContainers() {
        assertEquals("video/mp4", lanContentType("a.mp4"))
        assertEquals("video/quicktime", lanContentType("a.mov"))
        assertEquals("video/x-matroska", lanContentType("a.mkv"))
        assertEquals("video/webm", lanContentType("a.webm"))
        assertEquals("video/x-msvideo", lanContentType("a.avi"))
        assertEquals("application/pdf", lanContentType("a.pdf"))
        assertEquals("application/octet-stream", lanContentType("a.bin"))
    }

    @Test
    fun dispositionInlineVsAttachment() {
        assertTrue(lanContentDisposition("a.mp4").startsWith("attachment;"))
        assertTrue(lanContentDisposition("a.mp4", inline = true).startsWith("inline;"))
        assertTrue(lanContentDisposition("a\r\nb.mp4").none { it == '\r' || it == '\n' })
    }

    @Test
    fun contentLocationDetection() {
        assertTrue(isLanContentLocation("content://media/external/video/123"))
        assertFalse(isLanContentLocation("/storage/emulated/0/Download/a.mp4"))
        assertFalse(isLanContentLocation("file:///tmp/a.mp4"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
cd /Users/wuyu/Projects/video-converter/android && \
  JAVA_HOME=/Users/wuyu/.sdkman/candidates/java/17.0.11-amzn \
  ANDROID_HOME=/Users/wuyu/Library/Android/sdk \
  ./gradlew :app:testDebugUnitTest --tests com.videoconverter.android.lan.LanMediaTest --console=plain
```

Expected: FAIL（`LanPreviewKind` / `lanPreviewKind` unresolved，或 `lanContentDisposition` 还没有 `inline` 参数）

- [ ] **Step 3: Write minimal implementation**

在 `LanMedia.kt`：

```kotlin
package com.videoconverter.android.lan

enum class LanPreviewKind { Video, Audio, Pdf, Image, File }

fun lanPreviewKind(fileName: String): LanPreviewKind = when (fileName.substringAfterLast('.', "").lowercase()) {
    "mp4", "mov", "mkv", "webm", "avi" -> LanPreviewKind.Video
    "mp3", "m4a", "wav", "ogg", "flac", "amr" -> LanPreviewKind.Audio
    "pdf" -> LanPreviewKind.Pdf
    "jpg", "jpeg", "png", "webp", "gif", "bmp" -> LanPreviewKind.Image
    else -> LanPreviewKind.File
}

fun lanContentType(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
    "mp4" -> "video/mp4"
    "mov" -> "video/quicktime"
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    "avi" -> "video/x-msvideo"
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

fun lanContentDisposition(fileName: String, inline: Boolean = false): String {
    val safe = fileName.replace(Regex("[\r\n\"]"), "_")
    val encoded = java.net.URLEncoder.encode(safe, "UTF-8").replace("+", "%20")
    val kind = if (inline) "inline" else "attachment"
    return "$kind; filename=\"$safe\"; filename*=UTF-8''$encoded"
}

fun isLanContentLocation(location: String): Boolean =
    location.startsWith("content:", ignoreCase = true)
```

从 `LanShare.kt` **删除** 旧的 `lanContentType` / `lanContentDisposition`（同包函数，测试无需改 import）。`LanShareDownloadTest` 里 `lanContentType("clip.mp4")` 继续通过。

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
cd /Users/wuyu/Projects/video-converter/android && \
  JAVA_HOME=/Users/wuyu/.sdkman/candidates/java/17.0.11-amzn \
  ANDROID_HOME=/Users/wuyu/Library/Android/sdk \
  ./gradlew :app:testDebugUnitTest --tests com.videoconverter.android.lan.LanMediaTest --tests com.videoconverter.android.lan.LanShareDownloadTest --console=plain
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/videoconverter/android/lan/LanMedia.kt \
  android/app/src/main/java/com/videoconverter/android/lan/LanShare.kt \
  android/app/src/test/java/com/videoconverter/android/lan/LanMediaTest.kt
git commit -m "$(cat <<'EOF'
feat(android): 区分局域网预览类型并补齐媒体 MIME

EOF
)"
```

---

### Task 2: Range 解析

**Files:**
- Modify: `android/app/src/main/java/com/videoconverter/android/lan/LanMedia.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/lan/LanMediaTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `LanMedia.kt`
- Produces:
  - `sealed class LanByteRange { data object Whole; data class Partial(val start: Long, val endInclusive: Long); data object Unsatisfiable }`
  - `fun parseLanByteRange(header: String?, total: Long): LanByteRange`
  - `fun lanContentRangeValue(start: Long, endInclusive: Long, total: Long): String` → `bytes start-end/total`
  - `fun lanUnsatisfiableContentRange(total: Long): String` → `bytes */total`
  - `fun copyLanRange(input: java.io.InputStream, output: java.io.OutputStream, start: Long, length: Long)` — 跳过 `start` 字节，再拷 `length` 字节

规则（`total` 为文件字节数，必须 `>= 0`）：

- `header` 空 / blank → `Whole`
- 只接受单个 `bytes=start-end` 或 `bytes=start-`（大小写忽略 `bytes`）
- `end` 缺省则为 `total - 1`；`end` 超过末尾则夹到 `total - 1`
- `start > end`、`start >= total`、多段（含 `,`）、非 bytes 单位、`total==0` 还带 Range → `Unsatisfiable`
- 无 Range 且 `total==0` → `Whole`（空文件 200）

- [ ] **Step 1: Write the failing test**

把下列测试追加进 `LanMediaTest`：

```kotlin
    @Test
    fun parseByteRange() {
        assertEquals(LanByteRange.Whole, parseLanByteRange(null, 100))
        assertEquals(LanByteRange.Whole, parseLanByteRange("", 100))
        assertEquals(LanByteRange.Partial(0, 49), parseLanByteRange("bytes=0-49", 100))
        assertEquals(LanByteRange.Partial(50, 99), parseLanByteRange("Bytes=50-", 100))
        assertEquals(LanByteRange.Partial(0, 99), parseLanByteRange("bytes=0-9999", 100))
        assertEquals(LanByteRange.Unsatisfiable, parseLanByteRange("bytes=100-110", 100))
        assertEquals(LanByteRange.Unsatisfiable, parseLanByteRange("bytes=80-20", 100))
        assertEquals(LanByteRange.Unsatisfiable, parseLanByteRange("bytes=0-10,11-20", 100))
        assertEquals("bytes 0-49/100", lanContentRangeValue(0, 49, 100))
        assertEquals("bytes */100", lanUnsatisfiableContentRange(100))
    }

    @Test
    fun copyLanRangeSkipsAndLimits() {
        val input = java.io.ByteArrayInputStream(byteArrayOf(10, 11, 12, 13, 14))
        val output = java.io.ByteArrayOutputStream()
        copyLanRange(input, output, 1, 3)
        assertTrue(output.toByteArray().contentEquals(byteArrayOf(11, 12, 13)))
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: 同上 `LanMediaTest`

Expected: FAIL unresolved `parseLanByteRange`

- [ ] **Step 3: Write minimal implementation**

```kotlin
sealed class LanByteRange {
    data object Whole : LanByteRange()
    data class Partial(val start: Long, val endInclusive: Long) : LanByteRange()
    data object Unsatisfiable : LanByteRange()
}

fun parseLanByteRange(header: String?, total: Long): LanByteRange {
    val raw = header?.trim().orEmpty()
    if (raw.isEmpty()) return LanByteRange.Whole
    if (total <= 0L) return LanByteRange.Unsatisfiable
    if (',' in raw) return LanByteRange.Unsatisfiable
    val prefix = "bytes="
    if (!raw.startsWith(prefix, ignoreCase = true)) return LanByteRange.Unsatisfiable
    val spec = raw.substring(prefix.length)
    val dash = spec.indexOf('-')
    if (dash < 0) return LanByteRange.Unsatisfiable
    val startText = spec.substring(0, dash)
    val endText = spec.substring(dash + 1)
    val start = startText.toLongOrNull() ?: return LanByteRange.Unsatisfiable
    if (start < 0L || start >= total) return LanByteRange.Unsatisfiable
    val end = if (endText.isEmpty()) total - 1 else endText.toLongOrNull() ?: return LanByteRange.Unsatisfiable
    if (end < start) return LanByteRange.Unsatisfiable
    return LanByteRange.Partial(start, minOf(end, total - 1))
}

fun lanContentRangeValue(start: Long, endInclusive: Long, total: Long): String =
    "bytes $start-$endInclusive/$total"

fun lanUnsatisfiableContentRange(total: Long): String = "bytes */$total"

fun copyLanRange(input: java.io.InputStream, output: java.io.OutputStream, start: Long, length: Long) {
    var skipped = 0L
    while (skipped < start) {
        val n = input.skip(start - skipped)
        if (n <= 0L) {
            if (input.read() < 0) return
            skipped += 1
        } else {
            skipped += n
        }
    }
    var remaining = length
    val buffer = ByteArray(8192)
    while (remaining > 0) {
        val want = minOf(buffer.size.toLong(), remaining).toInt()
        val read = input.read(buffer, 0, want)
        if (read < 0) return
        output.write(buffer, 0, read)
        remaining -= read
    }
}
```

- [ ] **Step 4: Run tests**

Run: `LanMediaTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/videoconverter/android/lan/LanMedia.kt \
  android/app/src/test/java/com/videoconverter/android/lan/LanMediaTest.kt
git commit -m "$(cat <<'EOF'
feat(android): 解析局域网媒体 Range 请求

EOF
)"
```

---

### Task 3: `/m` 路由、HEAD、请求头

**Files:**
- Modify: `android/app/src/main/java/com/videoconverter/android/lan/LanShare.kt`
- Modify: `android/app/src/test/java/com/videoconverter/android/lan/LanShareDownloadTest.kt`
- Modify: `android/app/src/test/java/com/videoconverter/android/lan/LanShareHandlerTest.kt`

**Interfaces:**
- Consumes: `lanContentDisposition(fileName, inline)`、`resolveLanDownload`
- Produces:
  - `sealed class LanRoute { Home; data class Download(jobId, index); data class Media(jobId, index); NotFound }`
  - `data class LanHttpRequest(method, path, query, headers: Map<String, String> = emptyMap())`
  - `data class LanHttpResponse(..., filePath: String? = null, rangeHeader: String? = null, sendBody: Boolean = true)`
  - `parseLanRoute` 识别 `/m/{id}` `/m/{id}/{index}`，规则与 `/d` 相同
  - `handleLanRequest`：`GET`/`HEAD` 允许；`HEAD` 时 `sendBody=false`；`/m` 为 `inline` + `Accept-Ranges: bytes`，并把 `headers["range"]` 放进 `rangeHeader`；`/d` 仍为 `attachment`

`headers` 的 key **一律小写**（调用方负责）。`handleLanRequest` 读 `request.headers["range"]`。

- [ ] **Step 1: Write the failing test**

`LanShareDownloadTest.parsesHomeAndDownloadRoutes` 追加：

```kotlin
        assertEquals(LanRoute.Media("a1", 0), parseLanRoute("/m/a1"))
        assertEquals(LanRoute.Media("a1", 2), parseLanRoute("/m/a1/2"))
        assertEquals(LanRoute.NotFound, parseLanRoute("/m/"))
        assertEquals(LanRoute.NotFound, parseLanRoute("/m/../secret"))
```

`LanShareHandlerTest.downloadAndUnknown` 追加（或新 `@Test fun mediaIsInlineAndHeadHasNoBody`）：

```kotlin
    @Test
    fun mediaIsInlineAndHeadOmitsBodyFlag() {
        val get = handleLanRequest(LanHttpRequest("GET", "/m/v", mapOf("k" to "pw")), jobs, "pw", exists, copy)
        assertEquals(200, get.status)
        assertEquals("/tmp/v.mp4", get.filePath)
        assertTrue(get.headers["Content-Disposition"]!!.startsWith("inline;"))
        assertEquals("bytes", get.headers["Accept-Ranges"])
        assertTrue(get.sendBody)

        val head = handleLanRequest(LanHttpRequest("HEAD", "/m/v", mapOf("k" to "pw")), jobs, "pw", exists, copy)
        assertEquals(200, head.status)
        assertEquals("/tmp/v.mp4", head.filePath)
        assertFalse(head.sendBody)

        val ranged = handleLanRequest(
            LanHttpRequest("GET", "/m/v", mapOf("k" to "pw"), headers = mapOf("range" to "bytes=0-1")),
            jobs, "pw", exists, copy,
        )
        assertEquals("bytes=0-1", ranged.rangeHeader)
    }
```

现有 `POST` 仍 405；`GET /d/v` 仍 `attachment`。

- [ ] **Step 2: Run tests to verify they fail**

Run: `LanShareDownloadTest` `LanShareHandlerTest`

Expected: FAIL（`LanRoute.Media` unresolved 或 `headers`/`sendBody` 不存在）

- [ ] **Step 3: Write minimal implementation**

`LanRoute` 增加 `data class Media(val jobId: String, val index: Int)`。

`parseLanRoute`：`parts[0]` 为 `d` → `Download`，为 `m` → `Media`，否则 `NotFound`。id/index 校验与现在 `/d` 相同。

`LanHttpRequest` 增加 `val headers: Map<String, String> = emptyMap()`。

`LanHttpResponse` 增加 `val rangeHeader: String? = null, val sendBody: Boolean = true`。

`handleLanRequest`：

- `method != "GET" && method != "HEAD"` → 405
- token 失败仍 401
- 所有成功路由：`sendBody = request.method != "HEAD"`（含 `/`）
- `Download` / `Media`：共用 `resolveLanDownload`；失败 404
- 成功时：

```kotlin
val inline = route is LanRoute.Media
LanHttpResponse(
    status = 200,
    contentType = target.contentType,
    body = ByteArray(0),
    headers = mapOf(
        "Content-Type" to target.contentType,
        "Content-Disposition" to lanContentDisposition(target.downloadName, inline),
    ) + if (inline) mapOf("Accept-Ranges" to "bytes") else emptyMap(),
    filePath = target.path,
    rangeHeader = request.headers["range"],
    sendBody = request.method != "HEAD",
)
```

`/d` 也可以带 `Accept-Ranges`（规格允许 Range）；为满足「/m 必有 Accept-Ranges」，inline 时必须写。`/d` 建议同样写 `Accept-Ranges: bytes`，Range 由 Task 5 应用。

- [ ] **Step 4: Run tests**

Run: `LanShareDownloadTest` `LanShareHandlerTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/videoconverter/android/lan/LanShare.kt \
  android/app/src/test/java/com/videoconverter/android/lan/LanShareDownloadTest.kt \
  android/app/src/test/java/com/videoconverter/android/lan/LanShareHandlerTest.kt
git commit -m "$(cat <<'EOF'
feat(android): 增加局域网内联播放路由与 HEAD

EOF
)"
```

---

### Task 4: 媒体库 HTML

**Files:**
- Create: `android/app/src/main/java/com/videoconverter/android/lan/LanHistoryPage.kt` — 把 `renderLanHistoryHtml` 从 `LanShare.kt` 移入并重写（`LanShare.kt` 删除旧函数，保留 `lanHistoryCopy` / escape / label 辅助；页面渲染只放 Page 文件）
- Modify: `android/app/src/main/java/com/videoconverter/android/lan/LanShare.kt` — `LanHistoryCopy` 增加 `previewFailed`、`downloadToOpen`；`lanHistoryCopy` 先用英文占位也行，Task 6 接资源
- Modify: `android/app/src/test/java/com/videoconverter/android/lan/LanHistoryCopyFixtures.kt`
- Modify: `android/app/src/test/java/com/videoconverter/android/lan/LanHistoryHtmlTest.kt`

**Interfaces:**
- Consumes: `lanPreviewKind`、`jobOutputPaths`、`historyJobs`、`lanHistoryDownloadHref`（若仍 private，改为 `internal` 并留在 `LanShare.kt`）
- Produces: `fun renderLanHistoryHtml(jobs, token, copy, fileExists): String` 输出左列表右详情页

HTML 必须：

- `<title>LiteTrans</title>`
- 外壳背景 `#ecece8`，文字 `#1f2428`，强调 `#c45a2a`；`.player` 背景 `#111`
- 三段列表 `data-segment`
- 可播放项 `data-media`、`data-download`、`data-kind`（`video|audio|pdf|image|file`）、`data-id`、`data-index`
- 右侧 `#stage`：`<video controls>` `<audio controls>` `<img>` `<iframe>`（PDF）以及 `#hint` `#download`
- 内嵌 JS：点击列表项切换 `src`（带 token 的 media/download URL），按 `data-kind` 显示对应控件；`video`/`audio`/`iframe`/`img` 的 `onerror` 显示 `copy.previewFailed`；读 `location.hash` `#m/{id}` 或 `#m/{id}/{index}`
- **禁止** 出现 `content://secret` / 源 URI
- 完成且 `fileExists` 的项才有 media/download；多文件用原始 index
- 默认给第一段里第一个可打开项加 `selected`

`LanHistoryCopy` 新字段：

```kotlin
val previewFailed: String,
val downloadToOpen: String,
```

英文 fixture：`previewFailed = "Can't preview. Download the file instead."`，`downloadToOpen = "Download and open it on your computer."`

`lanHistoryCopy` 暂时写死这两句英文也可以，Task 6 改为 `getString`。

- [ ] **Step 1: Write the failing test**

更新 `englishLanHistoryCopy()` 补两个新参数（此时未加字段会编译失败，这就是 RED）。

重写 `LanHistoryHtmlTest.groupsAndHidesSourceUri` 关键断言：

```kotlin
        assertTrue(html.contains("LiteTrans"))
        assertTrue(html.contains("class=\"player\""))
        assertTrue(html.contains("<video"))
        assertTrue(html.contains("<audio"))
        assertTrue(html.contains("data-media=\"/m/v\""))
        assertTrue(html.contains("data-download=\"/d/v?k="))
        assertTrue(html.contains("data-media=\"/m/d/0\""))
        assertTrue(html.contains("data-media=\"/m/d/1\""))
        assertFalse(html.contains("content://secret"))
        assertTrue(html.contains("#ecece8"))
        assertTrue(html.contains("#111"))
```

`singleExistingOfManyKeepsPlainDownloadLabel`：仅 `/tmp/a.pdf` 存在时，应有 `data-media=\"/m/d\"` 或 `data-index=\"0\"`，**没有** `/m/d/1`。

删除 `koreanNamedDownloadPutsFilenameFirst` 与 `koreanIndexFormatWhenFileNameBlank`：页面主下载按钮只用 `copy.download`，多文件在左侧子项显示 basename（`groupsAndHidesSourceUri` 断言含 `a.pdf` / `b.pdf`）。`lanHistoryDownloadLabel` 可留着不调用，或一并删掉以免死代码。

- [ ] **Step 2: Run test to verify it fails**

Run: `LanHistoryHtmlTest`

Expected: FAIL（缺 `class="player"` 或 `LanHistoryCopy` 参数不匹配）

- [ ] **Step 3: Write the page**

`renderLanHistoryHtml` 结构（内嵌 CSS/JS，无外链）：

```html
<!DOCTYPE html><html><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>LiteTrans</title>
<style>
body{margin:0;background:#ecece8;color:#1f2428;font-family:-apple-system,sans-serif;display:flex;flex-direction:column;min-height:100vh}
header{padding:16px 20px;border-bottom:1px solid #d5d2cc}
main{display:flex;flex:1;min-height:0}
nav{width:320px;overflow:auto;padding:12px 16px;box-sizing:border-box}
.item{padding:10px 12px;border-radius:8px;cursor:pointer}
.item.selected{background:#fff;box-shadow:inset 3px 0 0 #c45a2a}
.child{padding-left:20px;font-size:13px}
.stage{flex:1;display:flex;flex-direction:column;background:#ecece8;padding:16px}
.player{flex:1;background:#111;border-radius:12px;display:flex;align-items:center;justify-content:center;min-height:240px;overflow:hidden}
.player video,.player audio,.player img,.player iframe{max-width:100%;max-height:100%}
.meta{padding:12px 4px}
a{color:#c45a2a}
</style>
</head><body>
header>LiteTrans + warning（无口令时）
main> nav 三段列表 + .stage>.player+#hint+#download
<script>
/* click [data-media] → show video|audio|img|iframe or hint for file/office */
/* hash #m/id or #m/id/index */
</script>
</body></html>
```

可打开项的 `href`/`data-media` 用 `/m/$id` 或 `/m/$id/$index`（多文件或 index>0 时带 index），`data-download` 对应 `/d/...`，有 token 则 `?k=`。

`LanShare.kt` 的 `handleLanRequest` Home 分支继续调用 `renderLanHistoryHtml`。

- [ ] **Step 4: Run tests**

Run: `LanHistoryHtmlTest` `LanShareHandlerTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/videoconverter/android/lan/LanHistoryPage.kt \
  android/app/src/main/java/com/videoconverter/android/lan/LanShare.kt \
  android/app/src/test/java/com/videoconverter/android/lan/LanHistoryHtmlTest.kt \
  android/app/src/test/java/com/videoconverter/android/lan/LanHistoryCopyFixtures.kt
git commit -m "$(cat <<'EOF'
feat(android): 局域网网页改为列表加播放器

EOF
)"
```

---

### Task 5: 服务端打开 content URI 并应用 Range

**Files:**
- Modify: `android/app/src/main/java/com/videoconverter/android/service/LanShareService.kt`
- Create: `android/app/src/test/java/com/videoconverter/android/lan/LanHttpWriteTest.kt` — 只测纯函数：把 `applyLanResponseRange` 放在 `LanMedia.kt`（避免 Robolectric 打真 socket）

**Interfaces:**
- Consumes: `parseLanByteRange`、`copyLanRange`、`isLanContentLocation`、`LanHttpResponse.rangeHeader` / `sendBody` / `filePath`
- Produces:
  - `fun applyLanResponseRange(response: LanHttpResponse, total: Long): LanHttpResponse`  
    若 `filePath==null` 原样返回；否则按 `rangeHeader`：  
    - `Whole` → status 200，headers 含 `Content-Length=total`  
    - `Partial` → status 206，`Content-Range`、`Content-Length=end-start+1`，并把 start/length 写进新字段  
    - `Unsatisfiable` → status 416，`Content-Range: bytes */total`，`filePath=null`，`sendBody=false`，body 空
  - `LanHttpResponse` 增加 `byteStart: Long = 0, byteLength: Long? = null`（null 表示整段 body 或整文件）
  - `LanShareService.handleClient`：读全套 header（key 小写）填进 `LanHttpRequest.headers`
  - `exists`：`content:` 用 `contentResolver.openAssetFileDescriptor(uri,"r")` 能打开且 `length>=0`；否则 `lanFileIsRegular`
  - `writeResponse`：`416` 短语 `Range Not Satisfiable`；`206` 为 `Partial Content`；若 `filePath` 为 content URI 则 `openInputStream`；磁盘仍 `NOFOLLOW_LINKS`；`sendBody==false` 不写正文；`byteLength!=null` 时 `copyLanRange`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.videoconverter.android.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanHttpWriteTest {
    private val base = LanHttpResponse(
        status = 200,
        contentType = "video/mp4",
        body = ByteArray(0),
        headers = mapOf("Content-Disposition" to "inline; filename=\"a.mp4\"", "Accept-Ranges" to "bytes"),
        filePath = "/tmp/a.mp4",
        rangeHeader = null,
        sendBody = true,
    )

    @Test
    fun wholeFileKeeps200() {
        val out = applyLanResponseRange(base, 100)
        assertEquals(200, out.status)
        assertEquals("100", out.headers["Content-Length"])
        assertEquals(0L, out.byteStart)
        assertEquals(null, out.byteLength)
    }

    @Test
    fun partialIs206() {
        val out = applyLanResponseRange(base.copy(rangeHeader = "bytes=0-9"), 100)
        assertEquals(206, out.status)
        assertEquals("bytes 0-9/100", out.headers["Content-Range"])
        assertEquals("10", out.headers["Content-Length"])
        assertEquals(0L, out.byteStart)
        assertEquals(10L, out.byteLength)
    }

    @Test
    fun badRangeIs416() {
        val out = applyLanResponseRange(base.copy(rangeHeader = "bytes=500-600"), 100)
        assertEquals(416, out.status)
        assertEquals("bytes */100", out.headers["Content-Range"])
        assertNull(out.filePath)
        assertEquals(false, out.sendBody)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `LanHttpWriteTest`

Expected: FAIL unresolved `applyLanResponseRange`

- [ ] **Step 3: Implement apply + service I/O**

`applyLanResponseRange` 实现如上。`writeResponse` 先 `val sized = if (location != null) applyLanResponseRange(response, total) else response`。

测长度：content URI → AFD `length`；文件 → `File.length()`。打不开当 404：覆盖为 `lanPlainText` 等价（status 404，无 filePath）。可在 service 里若 `total < 0` 则写 404。

解析 header：

```kotlin
fun parseLanHeaderLines(lines: List<String>): Map<String, String> {
    val headers = LinkedHashMap<String, String>()
    for (line in lines) {
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
    }
    return headers
}
```

可放 `LanShare.kt` 并加一个小测试：`Range: bytes=0-1` → `headers["range"]=="bytes=0-1"`。

`handleClient`：request-line 之后收集 header 直到空行，`parseHttpRequestLine(line)?.copy(headers = parseLanHeaderLines(headerLines))`。

打开流：

```kotlin
private fun openLanStream(location: String): java.io.InputStream? {
    if (isLanContentLocation(location)) {
        return try { contentResolver.openInputStream(android.net.Uri.parse(location)) } catch (_: Exception) { null }
    }
    if (!lanFileIsRegular(location)) return null
    return Files.newInputStream(java.io.File(location).toPath(), LinkOption.NOFOLLOW_LINKS)
}
```

- [ ] **Step 4: Run tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.videoconverter.android.lan.LanHttpWriteTest --tests com.videoconverter.android.lan.LanShareHandlerTest --tests com.videoconverter.android.lan.LanMediaTest --console=plain
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/videoconverter/android/lan/LanMedia.kt \
  android/app/src/main/java/com/videoconverter/android/lan/LanShare.kt \
  android/app/src/main/java/com/videoconverter/android/service/LanShareService.kt \
  android/app/src/test/java/com/videoconverter/android/lan/LanHttpWriteTest.kt
git commit -m "$(cat <<'EOF'
feat(android): 局域网可打开相册文件并支持拖动进度

EOF
)"
```

---

### Task 6: 文案、隐私、资源接线

**Files:**
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/main/res/values-zh-rCN/strings.xml`
- Modify: `android/app/src/main/res/values-zh-rTW/strings.xml`
- Modify: `android/app/src/main/res/values-zh-rHK/strings.xml`（与 TW 相同）
- Modify: `android/app/src/main/res/values-ja/strings.xml`
- Modify: `android/app/src/main/res/values-ko/strings.xml`
- Modify: `android/app/src/main/java/com/videoconverter/android/lan/LanShare.kt` — `lanHistoryCopy` 读新 key
- Modify: `android/app/src/test/java/com/videoconverter/android/ui/StringsResourceTest.kt` — `userFacingKeysResolveInBothLocales` 数组加上新 id；隐私英文断言含 `play` 或 `preview`

**Interfaces:**
- Consumes: Task 4 的 `previewFailed` / `downloadToOpen`
- Produces: 资源 key（六套都有，顺序与 en 一致）：
  - `lan_preview_failed` en: `Can't preview. Download the file instead.`
  - `lan_download_to_open` en: `Download and open it on your computer.`
  - 更新 `lan_open_warning`：英文改为 `Anyone on this network who has the address can view, play or preview, and download finished files.`
  - 更新 `privacy_body` 最后一句：`can view, play or preview, and download finished files`
  - 简体警告：`同一网络中知道此地址的设备可以查看、播放或预览并下载已完成文件。`
  - 简体隐私对应句：`可以查看、播放或预览并下载已完成文件`

- [ ] **Step 1: Write the failing test**

在 `StringsResourceTest.defaultEnglishAndZhCn`（或隐私那段）追加：

```kotlin
        assertEquals("Can't preview. Download the file instead.", app.getString(R.string.lan_preview_failed))
        assertTrue(app.getString(R.string.privacy_body).contains("play or preview"))
        assertTrue(zhCn().getString(R.string.privacy_body).contains("播放或预览"))
```

`userFacingKeysResolveInBothLocales` 的 `ids` 加上 `R.string.lan_preview_failed`、`R.string.lan_download_to_open`。

- [ ] **Step 2: Run test to verify it fails**

Run: `StringsResourceTest`

Expected: FAIL missing `lan_preview_failed`

- [ ] **Step 3: Add strings in all six folders and wire `lanHistoryCopy`**

```kotlin
    previewFailed = resources.getString(R.string.lan_preview_failed),
    downloadToOpen = resources.getString(R.string.lan_download_to_open),
```

日文/韩文/繁体按规格「意思对即可」，警告与隐私最后一句都要提到播放/预览+下载。HK 与 TW 文件保持相同。

- [ ] **Step 4: Run tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.videoconverter.android.ui.StringsResourceTest --tests com.videoconverter.android.lan.LanHistoryHtmlTest --console=plain
```

然后全量：

```bash
./gradlew testDebugUnitTest --console=plain
```

Expected: BUILD SUCCESSFUL，既有局域网测试全部通过

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/res/values/strings.xml \
  android/app/src/main/res/values-zh-rCN/strings.xml \
  android/app/src/main/res/values-zh-rTW/strings.xml \
  android/app/src/main/res/values-zh-rHK/strings.xml \
  android/app/src/main/res/values-ja/strings.xml \
  android/app/src/main/res/values-ko/strings.xml \
  android/app/src/main/java/com/videoconverter/android/lan/LanShare.kt \
  android/app/src/test/java/com/videoconverter/android/ui/StringsResourceTest.kt
git commit -m "$(cat <<'EOF'
feat(android): 局域网文案改为可播放预览并下载

EOF
)"
```
