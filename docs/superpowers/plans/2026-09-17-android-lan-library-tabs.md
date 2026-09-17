# Android 局域网媒体库四 Tab 成品页 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 电脑打开局域网地址后，顶栏四个 Tab（视频 / 音频 / 图片 / 文档）只列出已完成且仍在的文件；四种都是左选右预览 + 下载，观感是成品媒体库。

**Architecture:** 继续单页 `GET /`。新增纯 JVM 分组 `LanLibrary.kt`，按输出扩展名的 `lanPreviewKind` 拆成四组。`renderLanHistoryHtml` 一次吐出四组 DOM，JS 显隐 Tab。不改 `/m` `/d`、口令、绑定。

**Tech Stack:** Kotlin、JUnit4、Robolectric、现有内嵌 HTML/CSS/JS。不引入 Ktor、不拉 CDN。

## Global Constraints

- 规格：`docs/superpowers/specs/2026-09-17-android-lan-library-tabs-design.md`；HTTP/打开输出仍以 `2026-09-17-android-lan-media-library-design.md` 为准
- **禁止** Ktor、NanoHTTPD、第三方 CDN、外链脚本、外链字体
- **禁止** `values-zh/`；新文案六套对齐（en / zh-rCN / zh-rTW / zh-rHK / ja / ko）
- 只列出 `JobStatus.Completed` 且 `fileExists` 为真的输出；排队/失败/缺失不进任何 Tab
- 分组按输出文件 `lanPreviewKind`，不按 App `HistorySegment`
- 不改手机设置页、不改 App 历史三段、不改桌面 `src/`
- 现有 `/m` `/d`、Range、口令、content URI 单测必须继续通过
- minSdk 29；`JAVA_HOME=/Users/wuyu/.sdkman/candidates/java/17.0.11-amzn`，`ANDROID_HOME=/Users/wuyu/Library/Android/sdk`
- 在分支 `android-lan-library-tabs` 上实现，不要直接改 master
- 不要暂存 leftover：`android/scripts/fetch-ffmpeg.mjs`、`values-v31/`、无关 untracked spec；本计划的 spec/plan 可以提交

## File map

```
android/app/src/main/java/com/videoconverter/android/lan/LanLibrary.kt       # 四 Tab 分组（新建）
android/app/src/main/java/com/videoconverter/android/lan/LanShare.kt         # LanHistoryCopy 加图片字段
android/app/src/main/java/com/videoconverter/android/lan/LanHistoryPage.kt   # 四 Tab HTML/CSS/JS
android/app/src/main/res/values*/strings.xml                                 # lan_segment_image / history_empty_image
android/app/src/test/java/com/videoconverter/android/lan/LanLibraryTest.kt
android/app/src/test/java/com/videoconverter/android/lan/LanHistoryHtmlTest.kt
android/app/src/test/java/com/videoconverter/android/lan/LanHistoryCopyFixtures.kt
android/app/src/test/java/com/videoconverter/android/ui/StringsResourceTest.kt
```

---

### Task 1: 四 Tab 分组

**Files:**
- Create: `android/app/src/main/java/com/videoconverter/android/lan/LanLibrary.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/lan/LanLibraryTest.kt`

**Interfaces:**
- Consumes: `lanPreviewKind`、`lanPreviewFileName`、`jobOutputPaths`、`Job` / `JobStatus` / `resolveConfig`
- Produces:
  - `enum class LanLibraryTab { Video, Audio, Image, Document }`
  - `fun LanLibraryTab.wireName(): String` → `"video"` / `"audio"` / `"image"` / `"document"`
  - `fun lanLibraryTabFor(kind: LanPreviewKind): LanLibraryTab`
  - `data class LanLibraryItem(val jobId: String, val index: Int, val path: String, val kind: LanPreviewKind, val tab: LanLibraryTab, val label: String, val format: String, val needsIndex: Boolean)`
  - `fun lanLibraryItems(jobs: List<Job>, fileExists: (String) -> Boolean): List<LanLibraryItem>`
  - `fun lanLibraryItemsFor(items: List<LanLibraryItem>, tab: LanLibraryTab): List<LanLibraryItem>`
  - `fun lanDefaultLibraryTab(items: List<LanLibraryItem>): LanLibraryTab`

规则（必须按此实现）：

- 只收 `Completed` 且 `fileExists(path)` 的输出。
- 新任务在上：`jobs.asReversed()`，同一任务内按原始 `index` 升序。
- `needsIndex = jobOutputPaths(job).size > 1 || index > 0`（只有 index 1 还在时链接必须是 `/m/{id}/1`）。
- `label = lanPreviewFileName(path, job)`。
- `format = resolveConfig(job.config).getOrNull()?.container ?: label.substringAfterLast('.', job.config.preset)`。
- `lanDefaultLibraryTab`：按 Video → Audio → Image → Document 找第一个非空；全空则 Video。

- [ ] **Step 1: Write the failing test**

```kotlin
package com.videoconverter.android.lan

import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.MediaInfo
import com.videoconverter.android.domain.OutputConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanLibraryTest {
    @Test
    fun groupsByOutputKindNotHistorySegment() {
        val jobs = listOf(
            job("v", JobStatus.Completed, listOf("/tmp/v.mp4"), "clip.mp4", "mp4-h264"),
            job("a", JobStatus.Completed, listOf("/tmp/a.mp3"), "song.mp3", "audio-mp3"),
            job("p", JobStatus.Completed, listOf("/tmp/p.png"), "shot.png", "image-png"),
            job("d", JobStatus.Completed, listOf("/tmp/a.pdf"), "scan.pdf", "pdf-split"),
            job("x", JobStatus.Completed, listOf("/tmp/x.docx"), "doc.docx", "office-pdf"),
        )
        val items = lanLibraryItems(jobs) { true }
        assertEquals(listOf("v"), lanLibraryItemsFor(items, LanLibraryTab.Video).map { it.jobId })
        assertEquals(listOf("a"), lanLibraryItemsFor(items, LanLibraryTab.Audio).map { it.jobId })
        assertEquals(listOf("p"), lanLibraryItemsFor(items, LanLibraryTab.Image).map { it.jobId })
        assertEquals(listOf("d", "x"), lanLibraryItemsFor(items, LanLibraryTab.Document).map { it.jobId })
        assertEquals(LanPreviewKind.Pdf, items.first { it.jobId == "d" }.kind)
        assertEquals(LanPreviewKind.File, items.first { it.jobId == "x" }.kind)
        assertEquals("image", LanLibraryTab.Image.wireName())
    }

    @Test
    fun skipsIncompleteAndMissing() {
        val jobs = listOf(
            job("q", JobStatus.Queued, listOf("/tmp/q.mp4"), "q.mp4"),
            job("f", JobStatus.Failed, listOf("/tmp/f.mp4"), "f.mp4"),
            job("g", JobStatus.Completed, listOf("/tmp/gone.mp4"), "gone.mp4"),
            job("ok", JobStatus.Completed, listOf("/tmp/ok.mp4"), "ok.mp4"),
        )
        val items = lanLibraryItems(jobs) { it == "/tmp/ok.mp4" }
        assertEquals(listOf("ok"), items.map { it.jobId })
    }

    @Test
    fun splitsMultiOutputAcrossTabsNewestJobFirst() {
        val jobs = listOf(
            job("old", JobStatus.Completed, listOf("/tmp/old.mp4"), "old.mp4"),
            job("mix", JobStatus.Completed, listOf("/tmp/a.pdf", "/tmp/b.png"), "scan.pdf", "pdf-split"),
        )
        val items = lanLibraryItems(jobs) { true }
        assertEquals(listOf("old"), lanLibraryItemsFor(items, LanLibraryTab.Video).map { it.jobId })
        val images = lanLibraryItemsFor(items, LanLibraryTab.Image)
        assertEquals(1, images.size)
        assertEquals("mix", images[0].jobId)
        assertEquals(1, images[0].index)
        assertTrue(images[0].needsIndex)
        val docs = lanLibraryItemsFor(items, LanLibraryTab.Document)
        assertEquals(0, docs[0].index)
        assertTrue(docs[0].needsIndex)
    }

    @Test
    fun defaultTabSkipsEmpty() {
        val onlyAudio = lanLibraryItems(
            listOf(job("a", JobStatus.Completed, listOf("/tmp/a.mp3"), "a.mp3", "audio-mp3")),
        ) { true }
        assertEquals(LanLibraryTab.Audio, lanDefaultLibraryTab(onlyAudio))
        assertEquals(LanLibraryTab.Video, lanDefaultLibraryTab(emptyList()))
        val imageThenDoc = lanLibraryItems(
            listOf(
                job("p", JobStatus.Completed, listOf("/tmp/p.png"), "p.png", "image-png"),
                job("d", JobStatus.Completed, listOf("/tmp/a.pdf"), "a.pdf", "pdf-split"),
            ),
        ) { true }
        assertEquals(LanLibraryTab.Image, lanDefaultLibraryTab(imageThenDoc))
    }

    @Test
    fun loneLaterIndexStillNeedsIndex() {
        val items = lanLibraryItems(
            listOf(job("d", JobStatus.Completed, listOf("/tmp/gone.pdf", "/tmp/b.pdf"), "scan.pdf", "pdf-split")),
        ) { it == "/tmp/b.pdf" }
        assertEquals(1, items.single().index)
        assertTrue(items.single().needsIndex)
        assertEquals(LanLibraryTab.Document, items.single().tab)
    }

    private fun job(
        id: String,
        status: JobStatus,
        outputPaths: List<String>,
        displayName: String,
        preset: String = "mp4-h264",
    ) = Job(
        id = id,
        sourceUri = "content://secret/$id",
        displayName = displayName,
        outputPath = outputPaths.firstOrNull(),
        status = status,
        progress = 1.0,
        error = null,
        config = OutputConfig(preset = preset),
        media = MediaInfo(sourceUri = "content://secret/$id", displayName = displayName, importable = true),
        outputPaths = outputPaths,
    )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
cd /Users/wuyu/Projects/video-converter/android && \
  JAVA_HOME=/Users/wuyu/.sdkman/candidates/java/17.0.11-amzn \
  ANDROID_HOME=/Users/wuyu/Library/Android/sdk \
  ./gradlew :app:testDebugUnitTest --tests com.videoconverter.android.lan.LanLibraryTest --console=plain
```

Expected: FAIL（`LanLibraryTab` / `lanLibraryItems` unresolved）

- [ ] **Step 3: Write minimal implementation**

Create `android/app/src/main/java/com/videoconverter/android/lan/LanLibrary.kt`:

```kotlin
package com.videoconverter.android.lan

import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.resolveConfig

enum class LanLibraryTab {
    Video, Audio, Image, Document
}

fun LanLibraryTab.wireName(): String = when (this) {
    LanLibraryTab.Video -> "video"
    LanLibraryTab.Audio -> "audio"
    LanLibraryTab.Image -> "image"
    LanLibraryTab.Document -> "document"
}

fun lanLibraryTabFor(kind: LanPreviewKind): LanLibraryTab = when (kind) {
    LanPreviewKind.Video -> LanLibraryTab.Video
    LanPreviewKind.Audio -> LanLibraryTab.Audio
    LanPreviewKind.Image -> LanLibraryTab.Image
    LanPreviewKind.Pdf, LanPreviewKind.File -> LanLibraryTab.Document
}

data class LanLibraryItem(
    val jobId: String,
    val index: Int,
    val path: String,
    val kind: LanPreviewKind,
    val tab: LanLibraryTab,
    val label: String,
    val format: String,
    val needsIndex: Boolean,
)

fun lanLibraryItems(jobs: List<Job>, fileExists: (String) -> Boolean): List<LanLibraryItem> {
    val out = ArrayList<LanLibraryItem>()
    for (job in jobs.asReversed()) {
        if (job.status != JobStatus.Completed) continue
        val paths = jobOutputPaths(job)
        for ((index, path) in paths.withIndex()) {
            if (path.isBlank() || !fileExists(path)) continue
            val label = lanPreviewFileName(path, job)
            val kind = lanPreviewKind(label)
            val format = resolveConfig(job.config).getOrNull()?.container
                ?: label.substringAfterLast('.', job.config.preset)
            out += LanLibraryItem(
                jobId = job.id,
                index = index,
                path = path,
                kind = kind,
                tab = lanLibraryTabFor(kind),
                label = label,
                format = format,
                needsIndex = paths.size > 1 || index > 0,
            )
        }
    }
    return out
}

fun lanLibraryItemsFor(items: List<LanLibraryItem>, tab: LanLibraryTab): List<LanLibraryItem> =
    items.filter { it.tab == tab }

fun lanDefaultLibraryTab(items: List<LanLibraryItem>): LanLibraryTab {
    val order = listOf(
        LanLibraryTab.Video,
        LanLibraryTab.Audio,
        LanLibraryTab.Image,
        LanLibraryTab.Document,
    )
    return order.firstOrNull { tab -> items.any { it.tab == tab } } ?: LanLibraryTab.Video
}
```

- [ ] **Step 4: Run tests and make sure they pass**

同一条 `LanLibraryTest` 命令。Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/videoconverter/android/lan/LanLibrary.kt \
  android/app/src/test/java/com/videoconverter/android/lan/LanLibraryTest.kt
git commit -m "$(cat <<'EOF'
feat(android): 局域网网页按输出类型分成四个库 Tab

EOF
)"
```

---

### Task 2: 图片 Tab 文案与 Copy

**Files:**
- Modify: `android/app/src/main/java/com/videoconverter/android/lan/LanShare.kt` — `LanHistoryCopy` 增加 `image`、`emptyImage`；`lanHistoryCopy()` 接线；新增 `lanLibraryTabLabel` / `lanLibraryEmptyLabel`
- Modify: `android/app/src/test/java/com/videoconverter/android/lan/LanHistoryCopyFixtures.kt`
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/main/res/values-zh-rCN/strings.xml`
- Modify: `android/app/src/main/res/values-zh-rTW/strings.xml`
- Modify: `android/app/src/main/res/values-zh-rHK/strings.xml`
- Modify: `android/app/src/main/res/values-ja/strings.xml`
- Modify: `android/app/src/main/res/values-ko/strings.xml`
- Modify: `android/app/src/test/java/com/videoconverter/android/ui/StringsResourceTest.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/lan/LanLibraryTest.kt` — 加 copy 标签测试（可放本文件或继续用 `LanLibraryTest`）

**Interfaces:**
- Consumes: Task 1 `LanLibraryTab`
- Produces:
  - `LanHistoryCopy` 新字段 `image: String`、`emptyImage: String`（插在 `document` / `emptyDocument` 之后）
  - `fun lanLibraryTabLabel(tab: LanLibraryTab, copy: LanHistoryCopy): String`
  - `fun lanLibraryEmptyLabel(tab: LanLibraryTab, copy: LanHistoryCopy): String`
  - 资源：`lan_segment_image`、`history_empty_image`

文案（必须用这些值）：

| key | en | zh-rCN | zh-rTW / zh-rHK | ja | ko |
| --- | --- | --- | --- | --- | --- |
| `lan_segment_image` | Images | 图片 | 圖片 | 画像 | 이미지 |
| `history_empty_image` | No image history yet | 还没有图片记录 | 還沒有圖片記錄 | 画像の履歴はまだありません | 아직 이미지 기록이 없습니다 |

- [ ] **Step 1: Write the failing test**

在 `LanLibraryTest` 增加：

```kotlin
@Test
fun tabAndEmptyLabels() {
    val copy = englishLanHistoryCopy()
    assertEquals("Images", lanLibraryTabLabel(LanLibraryTab.Image, copy))
    assertEquals("No image history yet", lanLibraryEmptyLabel(LanLibraryTab.Image, copy))
    assertEquals("Video", lanLibraryTabLabel(LanLibraryTab.Video, copy))
    assertEquals("Documents", lanLibraryTabLabel(LanLibraryTab.Document, copy))
    assertEquals("No audio history yet", lanLibraryEmptyLabel(LanLibraryTab.Audio, copy))
}
```

在 `StringsResourceTest.requiredCatalogMatchesBrief` 的 `expected` 列表、`lan_segment_document` 后插入：

```kotlin
Triple(R.string.lan_segment_image, "Images", "图片"),
Triple(R.string.history_empty_image, "No image history yet", "还没有图片记录"),
```

在 `userFacingKeysResolveInBothLocales` 的 `ids` 里加入 `R.string.lan_segment_image`、`R.string.history_empty_image`。

在 `traditionalJapaneseAndKoreanLocales` 末尾加入：

```kotlin
assertEquals("圖片", tw.getString(R.string.lan_segment_image))
assertEquals(tw.getString(R.string.lan_segment_image), hk.getString(R.string.lan_segment_image))
assertEquals("画像", ja.getString(R.string.lan_segment_image))
assertTrue(ko.getString(R.string.lan_segment_image).any {
    Character.UnicodeBlock.of(it) == Character.UnicodeBlock.HANGUL_SYLLABLES
})
assertEquals("還沒有圖片記錄", tw.getString(R.string.history_empty_image))
```

同步改 `englishLanHistoryCopy()`（否则 data class 加字段后夹具编不过）：

```kotlin
internal fun englishLanHistoryCopy() = LanHistoryCopy(
    warning = "Anyone on this network who has the address can view history and download finished files.",
    video = "Video",
    audio = "Audio",
    document = "Documents",
    image = "Images",
    emptyVideo = "No video history yet",
    emptyAudio = "No audio history yet",
    emptyDocument = "No document history yet",
    emptyImage = "No image history yet",
    download = "Download",
    downloadNamed = "Download %1\$s",
    downloadIndex = "Download #%1\$d",
    statusQueued = "Queued",
    statusRunning = "Converting",
    statusCompleted = "Done",
    statusFailed = "Failed",
    statusCancelled = "Cancelled",
    needToken = "Password required",
    previewFailed = "Can't preview. Download the file instead.",
    downloadToOpen = "Download and open it on your computer.",
)
```

字段顺序必须与 `LanHistoryCopy` 构造函数一致。

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd /Users/wuyu/Projects/video-converter/android && \
  JAVA_HOME=/Users/wuyu/.sdkman/candidates/java/17.0.11-amzn \
  ANDROID_HOME=/Users/wuyu/Library/Android/sdk \
  ./gradlew :app:testDebugUnitTest --tests com.videoconverter.android.lan.LanLibraryTest --tests com.videoconverter.android.ui.StringsResourceTest --console=plain
```

Expected: FAIL（缺少 `image` 字段或 `R.string.lan_segment_image`）

- [ ] **Step 3: Write minimal implementation**

`LanHistoryCopy` 在 `document` 后加 `image`，在 `emptyDocument` 后加 `emptyImage`：

```kotlin
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
```

六个 `strings.xml` 在 `lan_segment_document` 后插入 `lan_segment_image`，在 `history_empty_document` 后插入 `history_empty_image`，值用上表。TW 与 HK 必须相同。

- [ ] **Step 4: Run tests and make sure they pass**

同一条 Gradle 命令。Expected: PASS。再跑：

```bash
./gradlew :app:testDebugUnitTest --tests com.videoconverter.android.lan.LanShareHandlerTest --console=plain
```

Expected: PASS（handler 用 `englishLanHistoryCopy()`）

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/videoconverter/android/lan/LanShare.kt \
  android/app/src/main/java/com/videoconverter/android/lan/LanLibrary.kt \
  android/app/src/test/java/com/videoconverter/android/lan/LanLibraryTest.kt \
  android/app/src/test/java/com/videoconverter/android/lan/LanHistoryCopyFixtures.kt \
  android/app/src/test/java/com/videoconverter/android/ui/StringsResourceTest.kt \
  android/app/src/main/res/values/strings.xml \
  android/app/src/main/res/values-zh-rCN/strings.xml \
  android/app/src/main/res/values-zh-rTW/strings.xml \
  android/app/src/main/res/values-zh-rHK/strings.xml \
  android/app/src/main/res/values-ja/strings.xml \
  android/app/src/main/res/values-ko/strings.xml
git commit -m "$(cat <<'EOF'
feat(android): 局域网网页补图片 Tab 文案

EOF
)"
```

---

### Task 3: 四 Tab 成品页 HTML/CSS/JS

**Files:**
- Modify: `android/app/src/main/java/com/videoconverter/android/lan/LanHistoryPage.kt`（整页重写结构：顶栏 Tab、四 pane、浅壳深播放器、JS 切 Tab）
- Modify: `android/app/src/test/java/com/videoconverter/android/lan/LanHistoryHtmlTest.kt`

**Interfaces:**
- Consumes: Task 1 `lanLibraryItems` / `lanDefaultLibraryTab` / `LanLibraryItem`；Task 2 `lanLibraryTabLabel` / `lanLibraryEmptyLabel` / `copy.image`
- Produces: `renderLanHistoryHtml` 仍是 `(jobs, token, copy, fileExists) -> String`，但 DOM 契约改为：
  - `data-tab-btn="video|audio|image|document"`，文案旁条数
  - `data-pane="..."`，默认 Tab 的 pane 可见，其它 `hidden`
  - 可打开项：`data-media` `data-download` `data-kind` `data-id` `data-index` `data-tab`
  - 图片左栏是 `<img class="thumb-src">`，`src` 为 `/m/...`（有口令则带 `k=`）
  - 无排队/失败文案；`sourceUri` 不出现
  - 脚本含 `showTab`、切 Tab 时 `hideAll`（清 media `src` + `load()`），`fromHash` 后 `showTab(data-tab)`
  - CSS：`#ecece8` `#1f2428` `#5c6460` `#d5d2cc` `#c45a2a` 右栏 `#111`；`prefers-reduced-motion`
  - 无 `<script src=`、无 `http` CDN

- [ ] **Step 1: Rewrite failing HTML tests**

替换 `LanHistoryHtmlTest` 为：

```kotlin
package com.videoconverter.android.lan

import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.MediaInfo
import com.videoconverter.android.domain.OutputConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanHistoryHtmlTest {
    @Test
    fun tabsGroupReadyFilesAndHideSourceUri() {
        val jobs = listOf(
            job("v", JobStatus.Completed, listOf("/tmp/v.mp4"), "假期.mp4", "mp4-h264"),
            job("a", JobStatus.Queued, listOf("/tmp/a.mp3"), "song.mp3", "audio-mp3"),
            job("img", JobStatus.Completed, listOf("/tmp/p.png"), "shot.png", "image-png"),
            job("d", JobStatus.Completed, listOf("/tmp/a.pdf", "/tmp/b.pdf"), "scan.pdf", "pdf-split"),
        )
        val html = renderLanHistoryHtml(jobs, "pw", englishLanHistoryCopy()) { it.startsWith("/tmp/") }
        assertTrue(html.contains("LiteTrans"))
        assertTrue(html.contains("data-tab-btn=\"video\""))
        assertTrue(html.contains("data-tab-btn=\"audio\""))
        assertTrue(html.contains("data-tab-btn=\"image\""))
        assertTrue(html.contains("data-tab-btn=\"document\""))
        assertTrue(html.contains("Images"))
        assertTrue(html.contains("class=\"player\""))
        assertTrue(html.contains("<video"))
        assertTrue(html.contains("data-media=\"/m/v\""))
        assertTrue(html.contains("data-download=\"/d/v?k="))
        assertTrue(html.contains("data-tab=\"video\""))
        assertTrue(html.contains("data-media=\"/m/d/0\""))
        assertTrue(html.contains("data-media=\"/m/d/1\""))
        assertTrue(html.contains("data-tab=\"image\""))
        assertTrue(html.contains("class=\"thumb-src\""))
        assertTrue(html.contains("src=\"/m/img?k="))
        assertFalse(html.contains("content://secret"))
        assertFalse(html.contains("song.mp3"))
        assertFalse(html.contains("Queued"))
        assertFalse(html.contains("/d/a"))
        assertFalse(html.contains("/m/a"))
        assertTrue(html.contains("#ecece8"))
        assertTrue(html.contains("#111"))
        assertTrue(html.contains("showTab"))
        assertTrue(html.contains("prefers-reduced-motion"))
        assertFalse(html.contains("<script src"))
        assertFalse(html.contains("cdn."))
    }

    @Test
    fun emptyLabelsAndEscapesHtml() {
        val html = renderLanHistoryHtml(
            listOf(job("x", JobStatus.Completed, listOf("/t/a.mp4"), "<img>", "mp4-h264")),
            "",
            englishLanHistoryCopy(),
        ) { true }
        assertTrue(html.contains("No audio history yet"))
        assertTrue(html.contains("No document history yet"))
        assertTrue(html.contains("No image history yet"))
        assertTrue(html.contains("&lt;img&gt;") || html.contains("a.mp4"))
        assertFalse(html.contains("displayName=\"<img>\""))
        assertTrue(html.contains("data-download=\"/d/x\""))
        assertFalse(html.contains("content://secret"))
    }

    @Test
    fun singleExistingOfManyKeepsIndexWhenNotZero() {
        val html = renderLanHistoryHtml(
            listOf(job("d", JobStatus.Completed, listOf("/tmp/a.pdf", "/tmp/gone.pdf"), "scan.pdf", "pdf-split")),
            "",
            englishLanHistoryCopy(),
        ) { it == "/tmp/a.pdf" }
        assertTrue(html.contains("data-media=\"/m/d/0\"") || html.contains("data-media=\"/m/d\""))
        assertFalse(html.contains("/m/d/1"))
        assertTrue(html.contains(">Download</a>"))
    }

    @Test
    fun missingFileHasNoDownload() {
        val html = renderLanHistoryHtml(
            listOf(job("v", JobStatus.Completed, listOf("/tmp/gone.mp4"), "gone.mp4")),
            "",
            englishLanHistoryCopy(),
        ) { false }
        assertFalse(html.contains("href=\"/d/v\""))
        assertFalse(html.contains("data-download=\"/d/v\""))
        assertFalse(html.contains("data-media=\"/m/v\""))
        assertTrue(html.contains("data-tab-btn=\"video\""))
    }

    @Test
    fun contentUriOutputUsesDisplayNameForVideoKind() {
        val location = "content://media/external/video/media/42"
        val html = renderLanHistoryHtml(
            listOf(job("v", JobStatus.Completed, listOf(location), "假期.mp4")),
            "",
            englishLanHistoryCopy(),
        ) { true }
        assertTrue(html.contains("data-kind=\"video\""))
        assertTrue(html.contains("data-media=\"/m/v\""))
        assertFalse(html.contains(location))
        assertFalse(html.contains("content://secret"))
    }

    @Test
    fun videoPaneListsNewestFirstAndSelectsNewer() {
        val jobs = listOf(
            job("old", JobStatus.Completed, listOf("/tmp/old.mp4"), "old-clip.mp4"),
            job("new", JobStatus.Completed, listOf("/tmp/new.mp4"), "new-clip.mp4"),
        )
        val html = renderLanHistoryHtml(jobs, "", englishLanHistoryCopy()) { true }
        val videoPane = html.substringAfter("data-pane=\"video\"").substringBefore("data-pane=\"audio\"")
        assertTrue(videoPane.indexOf("new-clip.mp4") < videoPane.indexOf("old-clip.mp4"))
        val selectedAttrs = videoPane.substringAfter("item selected").substringBefore('>')
        assertTrue(selectedAttrs.contains("data-id=\"new\""))
        assertTrue(html.contains("data-tab-btn=\"video\" class=\"on\"") || html.contains("aria-selected=\"true\""))
    }

    @Test
    fun defaultTabIsFirstNonEmpty() {
        val html = renderLanHistoryHtml(
            listOf(job("a", JobStatus.Completed, listOf("/tmp/a.mp3"), "song.mp3", "audio-mp3")),
            "",
            englishLanHistoryCopy(),
        ) { true }
        val audioBtn = html.substringAfter("data-tab-btn=\"audio\"").substringBefore("</button>")
        assertTrue(audioBtn.contains("on") || html.contains("data-pane=\"audio\"") && !html.substringAfter("data-pane=\"audio\"").substringBefore("data-pane=\"image\"").contains("hidden"))
        assertTrue(html.contains("data-tab=\"audio\""))
        val videoPane = html.substringAfter("data-pane=\"video\"").substringBefore("data-pane=\"audio\"")
        assertTrue(videoPane.contains("hidden"))
    }

    @Test
    fun contentUriAudioOutputUsesPresetContainerNotSourceDisplayName() {
        val location = "content://media/external/audio/media/99"
        val html = renderLanHistoryHtml(
            listOf(job("a", JobStatus.Completed, listOf(location), "假期.mp4", "audio-mp3")),
            "",
            englishLanHistoryCopy(),
        ) { true }
        assertTrue(html.contains("data-kind=\"audio\""))
        assertTrue(html.contains("data-media=\"/m/a\""))
        assertFalse(html.contains(location))
        assertFalse(html.contains("content://secret"))
    }

    @Test
    fun pngDoesNotAppearInDocumentPane() {
        val html = renderLanHistoryHtml(
            listOf(job("p", JobStatus.Completed, listOf("/tmp/p.png"), "shot.png", "image-png")),
            "",
            englishLanHistoryCopy(),
        ) { true }
        val docPane = html.substringAfter("data-pane=\"document\"")
        assertFalse(docPane.contains("data-id=\"p\""))
        val imagePane = html.substringAfter("data-pane=\"image\"").substringBefore("data-pane=\"document\"")
        assertTrue(imagePane.contains("data-id=\"p\""))
    }

    private fun job(
        id: String,
        status: JobStatus,
        outputPaths: List<String>,
        displayName: String = "clip.mp4",
        preset: String = "mp4-h264",
    ) = Job(
        id = id,
        sourceUri = "content://secret/$id",
        displayName = displayName,
        outputPath = outputPaths.firstOrNull(),
        status = status,
        progress = 1.0,
        error = null,
        config = OutputConfig(preset = preset),
        media = MediaInfo(sourceUri = "content://secret/$id", displayName = displayName, importable = true),
        outputPaths = outputPaths,
    )
}
```

`defaultTabIsFirstNonEmpty` 的断言写成明确、可维护的形式：默认音频 Tab 的 button 带 `class="on"`（或等价 `aria-selected="true"`），视频 pane 带 `hidden`，音频 pane 不带 `hidden`。不要用难读的 `&&` 长表达式。

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd /Users/wuyu/Projects/video-converter/android && \
  JAVA_HOME=/Users/wuyu/.sdkman/candidates/java/17.0.11-amzn \
  ANDROID_HOME=/Users/wuyu/Library/Android/sdk \
  ./gradlew :app:testDebugUnitTest --tests com.videoconverter.android.lan.LanHistoryHtmlTest --console=plain
```

Expected: FAIL（现页没有 `data-tab-btn` / `Images` / 仍含 `Queued`）

- [ ] **Step 3: Write the page**

重写 `LanHistoryPage.kt`。保留 `escapeHtml` / `jsString` / `LAN_HISTORY_PAGE_JS` 的包级位置。结构必须是：

1. `items = lanLibraryItems(jobs, fileExists)`，`defaultTab = lanDefaultLibraryTab(items)`。
2. `<header>`：`LiteTrans`；`token.isEmpty()` 时输出 `copy.warning`。
3. `<nav class="tabs">`：四个 `LanLibraryTab.entries` 顺序 Video, Audio, Image, Document。每个 `<button type="button" data-tab-btn="{wireName}"`；若 `tab==defaultTab` 则 `class="on"` 且 `aria-selected="true"`，否则 `aria-selected="false"`。按钮文本：`lanLibraryTabLabel` + 空格 + `lanLibraryItemsFor(items, tab).size`。
4. `<main>`：左 `<div class="rail">` 内四个 `data-pane`；右 `.stage` 含 `.player`（video/audio/img/iframe）和 `.meta`（`#hint`、`#download`，download 默认 `style="display:none"` 直到 JS select）。
5. pane：`tab!=defaultTab` 时写 `hidden`。空则 `<p class="empty">` + `lanLibraryEmptyLabel`。非空则逐条 `appendLibraryItem`；该 pane 且 `tab==defaultTab` 的**第一条**加 `selected`。
6. 条目：`class="item"`，图片再加 `thumb`。属性：`data-media`（token 空的 `/m` href）、`data-download`（带 token 的 `/d`）、`data-kind`（`LanPreviewKind.wireName()`）、`data-id`、`data-index`、`data-tab`。图片条目内部：`<img class="thumb-src" alt="" src="{带 token 的 /m href}">`。文档条目：label + `<span class="fmt">` format。其它：只输出 `label`（已 escape）。不要父任务占位行。
7. `lanHistoryDownloadHref(jobId, index, needsIndex, token, kind)`。
8. CSS 按规格：页面 `#ecece8`，字 `#1f2428`，次要 `#5c6460`，线 `#d5d2cc`，强调 `#c45a2a`，播放区 `#111`。Tab 当前项下划线 2px `#c45a2a`。左栏约 320px 白底。图片 pane `.thumbs` 两列网格。`@media (prefers-reduced-motion: reduce)` 关掉 transition。系统字体：`font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Hiragino Sans GB","Noto Sans SC",sans-serif`。窄屏 `main` 改 column。
9. JS（替换 `LAN_HISTORY_PAGE_JS`）必须包含：

```javascript
function withToken(url){
  if(!token) return url;
  return url+(url.indexOf('?')>=0?'&':'?')+'k='+encodeURIComponent(token);
}
var video=document.querySelector('video');
var audio=document.querySelector('audio');
var img=document.querySelector('.player img');
var iframe=document.querySelector('iframe');
var hint=document.getElementById('hint');
var download=document.getElementById('download');
function hideAll(){
  video.removeAttribute('src');video.load();video.style.display='none';
  audio.removeAttribute('src');audio.load();audio.style.display='none';
  img.removeAttribute('src');img.style.display='none';
  iframe.removeAttribute('src');iframe.style.display='none';
  hint.textContent='';
  download.style.display='none';
}
function showError(){hideAll();hint.textContent=previewFailed;download.style.display='inline';}
video.onerror=showError;audio.onerror=showError;img.onerror=showError;iframe.onerror=showError;
function showTab(name){
  document.querySelectorAll('[data-tab-btn]').forEach(function(btn){
    var on=btn.getAttribute('data-tab-btn')===name;
    btn.classList.toggle('on', on);
    btn.setAttribute('aria-selected', on?'true':'false');
  });
  document.querySelectorAll('[data-pane]').forEach(function(pane){
    if(pane.getAttribute('data-pane')===name) pane.removeAttribute('hidden');
    else pane.setAttribute('hidden','');
  });
  hideAll();
}
function select(el){
  document.querySelectorAll('.item.selected').forEach(function(n){n.classList.remove('selected');});
  el.classList.add('selected');
  var kind=el.getAttribute('data-kind');
  var media=el.getAttribute('data-media');
  var dl=el.getAttribute('data-download');
  hideAll();
  if(dl){download.setAttribute('href',dl);download.style.display='inline';}
  if(kind==='video'){video.style.display='block';video.src=withToken(media);}
  else if(kind==='audio'){audio.style.display='block';audio.src=withToken(media);}
  else if(kind==='image'){img.style.display='block';img.src=withToken(media);}
  else if(kind==='pdf'){iframe.style.display='block';iframe.src=withToken(media);}
  else {hint.textContent=downloadToOpen;download.style.display='inline';}
}
document.querySelectorAll('[data-tab-btn]').forEach(function(btn){
  btn.addEventListener('click',function(){
    var name=btn.getAttribute('data-tab-btn');
    showTab(name);
    var first=document.querySelector('[data-pane="'+name+'"] [data-media]');
    if(first) select(first);
  });
});
document.querySelectorAll('[data-media]').forEach(function(el){
  el.addEventListener('click',function(){select(el);});
});
function fromHash(){
  var m=location.hash.match(/^#m\/([^/]+)(?:\/(\d+))?$/);
  if(!m) return null;
  var id=m[1], index=m[2];
  var nodes=document.querySelectorAll('[data-media][data-id="'+id+'"]');
  if(index!=null){
    for(var i=0;i<nodes.length;i++){
      if(nodes[i].getAttribute('data-index')===index) return nodes[i];
    }
  }
  return nodes[0]||null;
}
var hashed=fromHash();
if(hashed){
  showTab(hashed.getAttribute('data-tab'));
  select(hashed);
}else{
  var initial=document.querySelector('.item.selected[data-media]');
  if(initial) select(initial);
}
```

切 Tab **不要** `history.pushState` / 改 hash。`LanPreviewKind.wireName()` 保持 Video→`video` 等。

`emptyLabelsAndEscapesHtml`：label 来自 `lanPreviewFileName("/t/a.mp4")` → `a.mp4`，displayName `<img>` 可以不出现在可见文本。测试已允许 `a.mp4` 或 escaped displayName。实现用 `label`（文件名）即可，不要把未 escape 的 `<img>` 写进属性。

视觉按 frontend-design：完成度用间距、下划线 Tab、左栏白底圆角、右深色播放器；不要卡片阴影套件、不要全大写 eyebrow、不要外链字体。

- [ ] **Step 4: Run tests and make sure they pass**

```bash
cd /Users/wuyu/Projects/video-converter/android && \
  JAVA_HOME=/Users/wuyu/.sdkman/candidates/java/17.0.11-amzn \
  ANDROID_HOME=/Users/wuyu/Library/Android/sdk \
  ./gradlew :app:testDebugUnitTest --tests com.videoconverter.android.lan.LanHistoryHtmlTest --tests com.videoconverter.android.lan.LanLibraryTest --tests com.videoconverter.android.lan.LanShareHandlerTest --tests com.videoconverter.android.lan.LanShareDownloadTest --tests com.videoconverter.android.lan.LanMediaTest --tests com.videoconverter.android.ui.StringsResourceTest --console=plain
```

Expected: BUILD SUCCESSFUL，上述测试 PASS。

若 `defaultTabIsFirstNonEmpty` 因 button 标记写法失败，只改测试去匹配你用的 `class="on"` / `aria-selected`，不要削弱「视频 pane hidden、音频 pane 可见」的断言。

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/videoconverter/android/lan/LanHistoryPage.kt \
  android/app/src/test/java/com/videoconverter/android/lan/LanHistoryHtmlTest.kt
git commit -m "$(cat <<'EOF'
feat(android): 局域网媒体库改为四 Tab 成品页

EOF
)"
```

---

## Self-review

- 四 Tab、按 `lanPreviewKind` 分组、只收完成且存在：Task 1 + 3
- 图片从文档拆出、多输出拆条、`needsIndex`：Task 1
- 默认 Tab 顺序、hash 切 Tab、停后台声音：Task 3
- 浅壳深播放器、条数、空态、无 CDN、reduced-motion、i18n：Task 2 + 3
- HTTP `/m` `/d` 不改：无新路由任务；Task 3 回归现有 handler/download/media 测试
- 未覆盖且规格声明范围外的：灯箱、封面、Office 预览、改 App 历史为四段
