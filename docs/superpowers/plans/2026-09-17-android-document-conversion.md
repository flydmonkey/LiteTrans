# Android 文档转换 Tab Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Android「轻转码」底栏增加「文档」，用与音视频相同的三步引导，在本机把图片、PDF、docx/xlsx 转成约定目标。

**Architecture:** 一套 `JobStore` 与前台泵不变。文档不走 FFmpeg：图片用系统解码，PDF 预览/转图用 `PdfRenderer`，拆分/TXT/压缩用 PdfBox-Android，Office 用 POI + `PdfDocument`。`AppViewModel` 增加第三份 `WizardSession`。历史用 `historySegmentFor` 分成视频 / 音频 / 文档。

**Tech Stack:** Kotlin、Jetpack Compose、JUnit4、Robolectric、`com.tom-roush:pdfbox-android:2.0.27.0`、`org.apache.poi:poi-ooxml:5.2.5`。不改桌面 `src/`。

## Global Constraints

- 底栏顺序：**视频转码 | 音频转换 | 文档 | 历史记录 | 我的**
- 全程本机、不上传；不 OCR、不 PDF→Excel/PPT、不 `.doc`/`.xls`/WPS、不 PDF 密码框
- 同一批必须同类型；每个源文件一条任务；多页结果是多个文件，`outputPath` 为第一份
- 图片目标：JPG / PNG / WebP / BMP / GIF（静图）；PDF：转图片、TXT、压缩、拆分；Office：只 docx/xlsx → PDF
- 图片结果默认相册 `Pictures/轻转码`；PDF/TXT 默认 `Documents/轻转码`
- JDK 17：`JAVA_HOME=/Users/wuyu/.sdkman/candidates/java/17.0.11-amzn`，`ANDROID_HOME=/Users/wuyu/Library/Android/sdk`
- 中文文案；不上 Navigation；不给文档单独队列或 Service
- 现有 JVM 单元测试必须继续通过
- 规格：`docs/superpowers/specs/2026-09-17-android-document-conversion-design.md`

## File map

```
android/app/build.gradle.kts          # PdfBox + POI
android/app/src/main/java/com/videoconverter/android/domain/
  Document.kt                         # 新建：类型、预设、页范围、命名、同类型
  Models.kt                           # pageCount/pageStart/pageEnd、Job.outputPaths
  Queue.kt                            # enqueueDocumentJobs
android/app/src/main/java/com/videoconverter/android/data/
  JobStore.kt                         # JSON 新字段
  OutputStore.kt                      # Kind.Documents + 多文件导出/删除
android/app/src/main/java/com/videoconverter/android/document/
  ImageConvert.kt                     # 图片互转/压缩、BMP/GIF 编码
  PdfOps.kt                           # 转图、拆分、抽 TXT、压缩
  OfficePdf.kt                        # docx/xlsx → 简单 PDF
  DocumentEngine.kt                   # 统一入口，按预设分发
android/app/src/main/java/com/videoconverter/android/service/
  TranscodeService.kt                 # 文档任务走 DocumentEngine
android/app/src/main/java/com/videoconverter/android/ui/
  Convert.kt                          # ConvertMode.Document、三会话
  RootTabs.kt                         # Tab、历史三段
  Wizard.kt                           # 文档卡片、存放选项
  AppViewModel.kt / AppScreen.kt / RootScreens.kt
```

---

### Task 1: 文档领域纯函数与历史三段

**Files:**
- Create: `android/app/src/main/java/com/videoconverter/android/domain/Document.kt`
- Modify: `android/app/src/main/java/com/videoconverter/android/ui/RootTabs.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/domain/DocumentTest.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/ui/RootTabsTest.kt`

**Interfaces:**
- Consumes: 现有 `Job`、`OutputConfig`、`MediaInfo`、`ConvertMode`、`HistorySegment`（本任务把 `HistorySegment` 加上 `Document`）
- Produces:
  - `enum class DocumentSourceKind { Image, Pdf, Word, Excel }`
  - 预设 id：`image-jpg` `image-png` `image-webp` `image-bmp` `image-gif` `image-compress` `pdf-image` `pdf-txt` `pdf-compress` `pdf-split` `office-pdf`
  - `fun documentSourceKind(fileName: String): DocumentSourceKind?`
  - `fun unsupportedDocumentReason(fileName: String): String?`
  - `fun sameDocumentKind(existing: List<String>, incoming: String): Boolean`
  - `fun clampPageRange(start: Int, end: Int, pageCount: Int): Pair<Int, Int>`
  - `fun defaultDocumentPreset(kind: DocumentSourceKind): String`
  - `fun documentPresetIds(): Set<String>`
  - `fun isDocumentPreset(preset: String): Boolean`
  - `fun documentResultIsImage(preset: String): Boolean`
  - `fun documentExtension(preset: String, imageFormat: String?): String`
  - `fun documentOutputFileName(stem: String, index: Int, total: Int, ext: String): String`
  - `fun historySegmentFor(job: Job): HistorySegment`
  - `fun historyJobs` / `remainingJobsAfterClearFinished` 改为按 `historySegmentFor`

- [ ] **Step 1: Write the failing tests**

创建 `DocumentTest.kt`：

```kotlin
package com.videoconverter.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentTest {
    @Test
    fun classifiesExtensions() {
        assertEquals(DocumentSourceKind.Image, documentSourceKind("a.JPG"))
        assertEquals(DocumentSourceKind.Image, documentSourceKind("a.heic"))
        assertEquals(DocumentSourceKind.Pdf, documentSourceKind("scan.pdf"))
        assertEquals(DocumentSourceKind.Word, documentSourceKind("a.docx"))
        assertEquals(DocumentSourceKind.Excel, documentSourceKind("a.xlsx"))
        assertNull(documentSourceKind("a.doc"))
        assertNull(documentSourceKind("a.wps"))
        assertTrue(unsupportedDocumentReason("old.doc")!!.contains("不支持此格式"))
    }

    @Test
    fun sameKindAllowsBatchRejectsMix() {
        assertTrue(sameDocumentKind(listOf("a.pdf", "b.pdf"), "c.pdf"))
        assertFalse(sameDocumentKind(listOf("a.pdf"), "note.docx"))
        assertTrue(sameDocumentKind(emptyList(), "x.png"))
    }

    @Test
    fun clampPageRange() {
        assertEquals(1 to 3, clampPageRange(0, 99, 3))
        assertEquals(2 to 2, clampPageRange(2, 2, 5))
        assertEquals(1 to 1, clampPageRange(8, 1, 1))
    }

    @Test
    fun defaultsAndExtensions() {
        assertEquals("image-jpg", defaultDocumentPreset(DocumentSourceKind.Image))
        assertEquals("pdf-image", defaultDocumentPreset(DocumentSourceKind.Pdf))
        assertEquals("office-pdf", defaultDocumentPreset(DocumentSourceKind.Word))
        assertEquals("jpg", documentExtension("image-jpg", null))
        assertEquals("png", documentExtension("pdf-image", "png"))
        assertEquals("pdf", documentExtension("pdf-split", null))
        assertEquals("txt", documentExtension("pdf-txt", null))
        assertTrue(documentResultIsImage("pdf-image"))
        assertTrue(documentResultIsImage("image-compress"))
        assertFalse(documentResultIsImage("office-pdf"))
    }

    @Test
    fun numberedNamesOnlyWhenMultiple() {
        assertEquals("clip.jpg", documentOutputFileName("clip", 1, 1, "jpg"))
        assertEquals("clip-001.jpg", documentOutputFileName("clip", 1, 12, "jpg"))
        assertEquals("clip-012.jpg", documentOutputFileName("clip", 12, 12, "jpg"))
    }
}
```

在 `RootTabsTest.kt` 的 `historySplitsVideoAndAudioJobs` 追加一份 `OutputConfig(preset = "pdf-split")` 的 job，并断言：

- `historySegmentFor(pdf) == HistorySegment.Document`
- `historyJobs(..., HistorySegment.Document)` 只含这份
- `historyJobs(..., HistorySegment.Video)` 不含它
- `historyEmptyLabel(HistorySegment.Document) == "还没有文档记录"`

把 `clearFinishedOnlyDropsCurrentSegment` 再测一次 `HistorySegment.Document`：完成的文档被清掉，视频留下。

把 `tabLabelsMatchProductCopy` **先不要**改成五个文案（Tab 在 Task 8 才加）。本任务只加 `HistorySegment.Document`。

- [ ] **Step 2: Run tests to verify they fail**

```bash
JAVA_HOME=/Users/wuyu/.sdkman/candidates/java/17.0.11-amzn \
ANDROID_HOME=/Users/wuyu/Library/Android/sdk \
./gradlew -p android :app:testDebugUnitTest --tests com.videoconverter.android.domain.DocumentTest --tests com.videoconverter.android.ui.RootTabsTest
```

Expected: FAIL（`Document.kt` 不存在 / `HistorySegment.Document` 未定义）

- [ ] **Step 3: Write minimal implementation**

`Document.kt` 按扩展名（`substringAfterLast('.').lowercase()`）分类。`doc`/`xls`/`wps`/`ppt` 等返回 `unsupportedDocumentReason = "不支持此格式"`。`sameDocumentKind` 对 `existing` 与 `incoming` 都跑 `documentSourceKind`，任一侧 `null` 则 false（空 existing 为 true）。

`clampPageRange`：`pageCount < 1` 时当作 1；`lo = start.coerceIn(1, pageCount)`，`hi = end.coerceIn(lo, pageCount)`。

`documentExtension`：`image-compress` 若 `imageFormat` 为空则用 `jpg`；`pdf-image` 必须用传入的 `imageFormat`（默认 jpg）。

`documentOutputFileName`：`total <= 1` 时 `$stem.$ext`，否则 `"$stem-${index.toString().padStart(3,'0')}.$ext"`。

`RootTabs.kt`：

```kotlin
enum class HistorySegment { Video, Audio, Document }

fun isDocumentHistoryJob(job: Job): Boolean = isDocumentPreset(job.config.preset)

fun historySegmentFor(job: Job): HistorySegment = when {
    isDocumentHistoryJob(job) -> HistorySegment.Document
    isAudioHistoryJob(job) -> HistorySegment.Audio
    else -> HistorySegment.Video
}

fun historyJobs(jobs: List<Job>, segment: HistorySegment): List<Job> =
    jobs.filter { historySegmentFor(it) == segment }

fun historyEmptyLabel(segment: HistorySegment): String = when (segment) {
    HistorySegment.Video -> "还没有视频记录"
    HistorySegment.Audio -> "还没有音频记录"
    HistorySegment.Document -> "还没有文档记录"
}

fun remainingJobsAfterClearFinished(jobs: List<Job>, segment: HistorySegment): List<Job> =
    jobs.filter { job ->
        if (historySegmentFor(job) != segment) true
        else job.status == JobStatus.Queued || job.status == JobStatus.Running
    }

fun historySegmentAfterEnqueue(mode: ConvertMode, preset: String): HistorySegment = when {
    mode == ConvertMode.Document || isDocumentPreset(preset) -> HistorySegment.Document
    mode == ConvertMode.Audio || isAudioPreset(preset) -> HistorySegment.Audio
    else -> HistorySegment.Video
}
```

本任务 **不要** 给 `ConvertMode` 加 `Document`。`historySegmentAfterEnqueue` 先只靠 `isDocumentPreset(preset)` 识别文档（`mode == ConvertMode.Document` 等 Task 8 再补上，否则现在编不过）。写成：

```kotlin
fun historySegmentAfterEnqueue(mode: ConvertMode, preset: String): HistorySegment = when {
    isDocumentPreset(preset) -> HistorySegment.Document
    mode == ConvertMode.Audio || isAudioPreset(preset) -> HistorySegment.Audio
    else -> HistorySegment.Video
}
```

- [ ] **Step 4: Run tests to verify they pass**

同一条 gradle 命令。Expected: PASS。再跑一遍全量 `:app:testDebugUnitTest`，确认旧的 `historyJobs` 布尔逻辑改掉后音视频用例仍过。

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/videoconverter/android/domain/Document.kt \
  android/app/src/main/java/com/videoconverter/android/ui/RootTabs.kt \
  android/app/src/test/java/com/videoconverter/android/domain/DocumentTest.kt \
  android/app/src/test/java/com/videoconverter/android/ui/RootTabsTest.kt
git commit -m "$(cat <<'EOF'
feat(android): 文档预设与历史第三段纯函数

EOF
)"
```

---

### Task 2: MediaInfo 页码与 Job.outputPaths 持久化

**Files:**
- Modify: `android/app/src/main/java/com/videoconverter/android/domain/Models.kt`
- Modify: `android/app/src/main/java/com/videoconverter/android/data/JobStore.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/data/JobStoreTest.kt`

**Interfaces:**
- Consumes: 现有 `Job.toJson` / `jobsFromJson`
- Produces: `MediaInfo.pageCount: Int?`、`pageStart: Int?`、`pageEnd: Int?`；`Job.outputPaths: List<String> = emptyList()`；旧 JSON 缺字段时页码为 null、`outputPaths` 为空

- [ ] **Step 1: Write the failing test**

在 `JobStoreTest.kt` 追加：

```kotlin
@Test
fun jobJsonRoundTripKeepsPagesAndOutputPaths() {
    val job = sampleJob().copy(
        outputPath = "content://first",
        outputPaths = listOf("content://first", "content://second"),
        media = sampleJob().media.copy(pageCount = 12, pageStart = 2, pageEnd = 5),
        config = OutputConfig(preset = "pdf-image", container = "jpg"),
    )
    val parsed = jobsFromJson(jobsToJson(listOf(job))).single()
    assertEquals(listOf("content://first", "content://second"), parsed.outputPaths)
    assertEquals(12, parsed.media.pageCount)
    assertEquals(2, parsed.media.pageStart)
    assertEquals(5, parsed.media.pageEnd)
}

@Test
fun missingPageAndOutputPathsLoadAsEmpty() {
    val oldJson = """
        [{"id":"j1","sourceUri":"content://a","displayName":"a.mp4","outputPath":null,
          "status":"Queued","progress":0,"error":null,
          "config":{"preset":"mp4-h264"},
          "media":{"sourceUri":"content://a","displayName":"a.mp4","importable":true}}]
    """.trimIndent()
    val parsed = jobsFromJson(oldJson).single()
    assertEquals(emptyList<String>(), parsed.outputPaths)
    assertNull(parsed.media.pageCount)
}
```

给 `sampleJob()` 的 `Job(...)` 补默认参数即可（`outputPaths` 有默认空列表则旧调用不用改）。

- [ ] **Step 2: Run test to verify it fails**

```bash
JAVA_HOME=/Users/wuyu/.sdkman/candidates/java/17.0.11-amzn \
ANDROID_HOME=/Users/wuyu/Library/Android/sdk \
./gradlew -p android :app:testDebugUnitTest --tests com.videoconverter.android.data.JobStoreTest
```

Expected: FAIL（`outputPaths` / `pageCount` unresolved）

- [ ] **Step 3: Write minimal implementation**

`MediaInfo` 增加 `pageCount: Int? = null`、`pageStart: Int? = null`、`pageEnd: Int? = null`。`Job` 增加 `outputPaths: List<String> = emptyList()`。

JSON：`outputPaths` 用 `JSONArray`；缺键或 NULL 时 `emptyList()`。页码用现有 `nullableInt`。

- [ ] **Step 4: Run test to verify it passes**

同一命令。Expected: PASS。全量 unit test 也要过（所有 `Job(` 调用因默认参数仍编译）。

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/videoconverter/android/domain/Models.kt \
  android/app/src/main/java/com/videoconverter/android/data/JobStore.kt \
  android/app/src/test/java/com/videoconverter/android/data/JobStoreTest.kt
git commit -m "$(cat <<'EOF'
feat(android): 任务记录页范围与多输出路径

EOF
)"
```

---

### Task 3: Documents 目录与多文件导出/删除

**Files:**
- Modify: `android/app/src/main/java/com/videoconverter/android/data/OutputStore.kt`
- Modify: `android/app/src/test/java/com/videoconverter/android/data/OutputStoreTest.kt`
- Modify: `android/app/src/main/java/com/videoconverter/android/ui/AppViewModel.kt`（删除时遍历 `outputPaths`）

**Interfaces:**
- Consumes: 现有 `export` / `deleteExported` / `mediaStoreRelativePath`
- Produces:
  - `OutputTarget.Kind.Documents`
  - `mediaStoreRelativePath(Documents) = "Documents/轻转码"`
  - `mediaStoreCollection(Documents, _)` → `MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)`
  - `fun exportedLocations(job: Job): List<String>`：`outputPaths` 非空用它，否则 `listOfNotNull(outputPath)`
  - 删除：对 `exportedLocations` 逐个 `deleteExported`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun documentsRelativePath() {
    assertEquals("Documents/轻转码", mediaStoreRelativePath(OutputTarget.Kind.Documents))
}

@Test
fun outputTargetForJobAcceptsDocuments() {
    val fallback = OutputTarget(OutputTarget.Kind.Downloads)
    assertEquals(
        OutputTarget(OutputTarget.Kind.Documents),
        outputTargetForJob("Documents", null, fallback),
    )
}
```

在 `AppViewModel.kt` 旁无法轻松测删除，把 `exportedLocations` 放进 `OutputStore.kt`（顶层函数）并测：

```kotlin
@Test
fun exportedLocationsPrefersOutputPaths() {
    val job = Job(
        id = "1", sourceUri = "u", displayName = "a.pdf",
        outputPath = "first", status = JobStatus.Completed, progress = 100.0,
        error = null, config = OutputConfig(preset = "pdf-split"),
        media = MediaInfo("u", "a.pdf", importable = true),
        outputPaths = listOf("first", "second"),
    )
    assertEquals(listOf("first", "second"), exportedLocations(job))
    assertEquals(listOf("only"), exportedLocations(job.copy(outputPath = "only", outputPaths = emptyList())))
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
JAVA_HOME=/Users/wuyu/.sdkman/candidates/java/17.0.11-amzn \
ANDROID_HOME=/Users/wuyu/Library/Android/sdk \
./gradlew -p android :app:testDebugUnitTest --tests com.videoconverter.android.data.OutputStoreTest
```

Expected: FAIL（`Kind.Documents` 不存在）

- [ ] **Step 3: Write minimal implementation**

`enum class Kind` 增加 `Documents`。所有 `when (kind)` 必须补分支：`Gallery`/`Movies`/`Music` 行为不变；`Documents` 走 Files collection，`RELATIVE_PATH = Documents/轻转码`。

`Wizard.kt` 的 `outputChoiceId` / `outputKindForChoice` 若因 when 不穷尽而编不过，先把 `Documents` 映射到 `"documents"`（UI 文案 Task 8 再接到卡片）。`WizardComponents.kt` 的 `outputKindLabel` 同样加「文档」。

`AppViewModel.delete`：

```kotlin
exportedLocations(job).forEach { path ->
    runCatching { outputStore.deleteExported(path) }
}
```

- [ ] **Step 4: Run tests to verify they pass**

同一命令 + 全量 unit test。Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/videoconverter/android/data/OutputStore.kt \
  android/app/src/test/java/com/videoconverter/android/data/OutputStoreTest.kt \
  android/app/src/main/java/com/videoconverter/android/ui/AppViewModel.kt \
  android/app/src/main/java/com/videoconverter/android/ui/Wizard.kt \
  android/app/src/main/java/com/videoconverter/android/ui/WizardComponents.kt
git commit -m "$(cat <<'EOF'
feat(android): 文档输出到系统 Documents 并支持多文件删除

EOF
)"
```

---

### Task 4: 图片互转与压缩

**Files:**
- Create: `android/app/src/main/java/com/videoconverter/android/document/BmpEncoder.kt`
- Create: `android/app/src/main/java/com/videoconverter/android/document/GifEncoder.kt`
- Create: `android/app/src/main/java/com/videoconverter/android/document/ImageConvert.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/document/ImageConvertTest.kt`

**Interfaces:**
- Consumes: `documentExtension`、质量档 `high` / `standard` / `small`
- Produces:
  - `fun imageCompressFormat(preset: String, sourceExt: String): Pair<String /*ext*/, Int /*jpegQuality 0-100*/>`
  - `fun scaleForQuality(quality: String, preset: String): Float`（仅 `image-compress` 的 PNG/BMP：`small=0.7f` 其余 `1f`）
  - `fun encodeBitmap(bitmap: android.graphics.Bitmap, ext: String, quality: Int, out: java.io.OutputStream)`
  - JPEG/PNG/WebP 用 `Bitmap.compress`；BMP/GIF 用自写编码器。GIF 只一帧。

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.videoconverter.android.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ImageConvertTest {
    @Test
    fun compressKeepsJpegQualitySteps() {
        assertEquals("jpg" to 92, imageCompressFormat("image-compress", "jpg"))
        assertEquals("jpg" to 75, imageCompressFormat("image-jpg", "png"))
        assertEquals("webp" to 80, imageCompressFormat("image-webp", "png"))
        assertEquals("png" to 100, imageCompressFormat("image-png", "jpg"))
        assertEquals(0.7f, scaleForQuality("small", "image-compress"), 0.001f)
        assertEquals(1f, scaleForQuality("small", "image-jpg"), 0.001f)
    }

    @Test
    fun bmpAndGifEncodersWriteSignatures() {
        val bmp = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        val gifOut = ByteArrayOutputStream()
        encodeBitmap(bmp, "gif", 80, gifOut)
        assertEquals('G'.code.toByte(), gifOut.toByteArray()[0])
        assertEquals('I'.code.toByte(), gifOut.toByteArray()[1])
        val bmpOut = ByteArrayOutputStream()
        encodeBitmap(bmp, "bmp", 100, bmpOut)
        val bytes = bmpOut.toByteArray()
        assertEquals('B'.code.toByte(), bytes[0])
        assertEquals('M'.code.toByte(), bytes[1])
        assertTrue(bytes.size > 14)
    }
}
```

测试类加 `@org.robolectric.annotation.Config(sdk = [29])` 与 `@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)`（与仓库里其它 Robolectric 测试一致，若现有测试没用 runner，就只对 Bitmap 测试加）。

- [ ] **Step 2: Run test to verify it fails**

```bash
JAVA_HOME=/Users/wuyu/.sdkman/candidates/java/17.0.11-amzn \
ANDROID_HOME=/Users/wuyu/Library/Android/sdk \
./gradlew -p android :app:testDebugUnitTest --tests com.videoconverter.android.document.ImageConvertTest
```

Expected: FAIL

- [ ] **Step 3: Write minimal implementation**

`image-compress`：源扩展是 png/bmp → 仍输出同扩展并在 `small` 时缩放；jpg/jpeg/webp/其它 → jpg，质量 high=92、standard=75、small=60。

`encodeBitmap`：`jpg`/`jpeg` → `JPEG`；`png` → `PNG`；`webp` → `WEBP`（API 29 用 `Bitmap.CompressFormat.WEBP`）；`bmp`/`gif` 走编码器。

BMP：24-bit、行对齐 4 字节、像素从下到上、文件头 `BM`。GIF89a：逻辑屏幕=位图尺寸、全局色表 256、单帧无动画扩展。

- [ ] **Step 4: Run test to verify it passes**

同一命令。Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/videoconverter/android/document \
  android/app/src/test/java/com/videoconverter/android/document/ImageConvertTest.kt
git commit -m "$(cat <<'EOF'
feat(android): 本机图片互转与静图 GIF/BMP 编码

EOF
)"
```

---

### Task 5: PDF 转图、抽 TXT、拆分、压缩

**Files:**
- Modify: `android/app/build.gradle.kts`（加上 `implementation("com.tom-roush:pdfbox-android:2.0.27.0")`）
- Create: `android/app/src/main/java/com/videoconverter/android/document/PdfOps.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/document/PdfOpsTest.kt`

**Interfaces:**
- Consumes: `clampPageRange`、`documentOutputFileName`、`com.tom_roush.pdfbox.pdmodel.PDDocument`
- Produces:
  - `fun pdfPageCount(file: java.io.File): Int`
  - `fun assertPdfReadable(file: java.io.File)`：加密则 `error("不支持加密 PDF")`
  - `fun extractPdfText(file: java.io.File, start: Int, end: Int): String`；空白则 `error("没有可提取的文字")`
  - `fun splitPdf(file: java.io.File, start: Int, end: Int, destDir: java.io.File, stem: String): List<java.io.File>`
  - `fun compressPdf(file: java.io.File, quality: String, dest: java.io.File)`
  - `fun pdfImageMaxEdge(quality: String): Int` → high=1600、standard=1200、small=800（给转图与压缩共用）

转图渲染放在 `PdfOps.kt` 的 `fun renderPdfPage(file, pageIndex0, maxEdge): android.graphics.Bitmap`，内部优先 `android.graphics.pdf.PdfRenderer`（Robolectric 上若不可用，测试只覆盖 PdfBox 的拆分/文字/压缩）。

- [ ] **Step 1: Write the failing tests**

用 PdfBox 在测试里生成 2 页文字 PDF（`PDPageContentStream` 写 `Hello` / `World`），不要用真实夹具文件。

```kotlin
@Test
fun splitKeepsTextAndNamesPages() {
    val src = writtenTwoPagePdf()
    val out = kotlin.io.path.createTempDirectory("split").toFile()
    val files = splitPdf(src, 1, 2, out, "clip")
    assertEquals(2, files.size)
    assertTrue(files[0].name.contains("001"))
    assertTrue(extractPdfText(files[0], 1, 1).contains("Hello"))
    assertTrue(extractPdfText(files[1], 1, 1).contains("World"))
}

@Test
fun emptyTextFails() {
    val blank = writtenBlankPdf()
    try {
        extractPdfText(blank, 1, 1)
        org.junit.Assert.fail("expected")
    } catch (e: IllegalStateException) {
        assertTrue(e.message!!.contains("没有可提取的文字"))
    }
}

@Test
fun compressStillHasExtractableText() {
    val src = writtenTwoPagePdf()
    val dest = kotlin.io.path.createTempFile("c", ".pdf").toFile()
    compressPdf(src, "small", dest)
    assertTrue(extractPdfText(dest, 1, 2).contains("Hello"))
}
```

`writtenTwoPagePdf` 放在测试文件私有函数里，用 `PDDocument` + 两页 `PDPage`。

- [ ] **Step 2: Run tests to verify they fail**

```bash
JAVA_HOME=/Users/wuyu/.sdkman/candidates/java/17.0.11-amzn \
ANDROID_HOME=/Users/wuyu/Library/Android/sdk \
./gradlew -p android :app:testDebugUnitTest --tests com.videoconverter.android.document.PdfOpsTest
```

Expected: FAIL

- [ ] **Step 3: Write minimal implementation**

`assertPdfReadable`：`PDDocument.load` 后 `if (document.isEncrypted)` 抛 `IllegalStateException("不支持加密 PDF")`。

拆分：对 `[start, end]` 每页 `PDDocument()` + `importPage` + `save`。**禁止**渲染成位图再 `PdfDocument`。

抽字：`PDFTextStripper` 的 `startPage`/`endPage`（PdfBox 2.x 从 1 起）。`trim().isEmpty()` 则失败。

压缩：遍历页面 XObject 图，若宽或高大于 `pdfImageMaxEdge(quality)`，缩到该边长、换成 JPEG（high=0.85、standard=0.7、small=0.5），写回。没有图的文字页保持矢量。不要整页栅格化。

在 `android/app/src/main/java/...` 调 PdfBox 前调用 `com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)`——`init` 放 `DocumentEngine`（Task 7）。本任务的纯文件 API 在 JVM 测试里不 init 也应能 load/save。

- [ ] **Step 4: Run tests to verify they pass**

同一命令。Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add android/app/build.gradle.kts \
  android/app/src/main/java/com/videoconverter/android/document/PdfOps.kt \
  android/app/src/test/java/com/videoconverter/android/document/PdfOpsTest.kt
git commit -m "$(cat <<'EOF'
feat(android): 本机 PDF 拆分、抽字和压缩

EOF
)"
```

---

### Task 6: docx / xlsx 转简单 PDF

**Files:**
- Modify: `android/app/build.gradle.kts`（`implementation("org.apache.poi:poi-ooxml:5.2.5")`，并 `packaging.resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/NOTICE")`）
- Create: `android/app/src/main/java/com/videoconverter/android/document/OfficePdf.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/document/OfficePdfTest.kt`

**Interfaces:**
- Consumes: POI `XWPFDocument` / `XSSFWorkbook`；Android `android.graphics.pdf.PdfDocument`
- Produces:
  - `fun officeBlocksFromDocx(bytes: ByteArray): List<OfficeBlock>`
  - `fun officeBlocksFromXlsx(bytes: ByteArray): List<OfficeBlock>`
  - `fun writeOfficePdf(blocks: List<OfficeBlock>, out: java.io.File)`
  - `sealed class OfficeBlock { data class Paragraph(val text: String); data class Table(val rows: List<List<String>>) }`
  - 空 blocks → `error("无法转换此文档")`

- [ ] **Step 1: Write the failing tests**

用 POI 在测试里写一个最小 docx（一个段落 `标题` + 一行两列表格）和一个 xlsx（A1=`姓名`, B1=`数量`），再：

```kotlin
@Test
fun docxBlocksIncludeParagraphAndTable() {
    val blocks = officeBlocksFromDocx(sampleDocx())
    assertTrue(blocks.any { it is OfficeBlock.Paragraph && it.text.contains("标题") })
    assertTrue(blocks.any { it is OfficeBlock.Table })
}

@Test
fun writesNonEmptyPdf() {
    val dest = kotlin.io.path.createTempFile("o", ".pdf").toFile()
    writeOfficePdf(officeBlocksFromDocx(sampleDocx()), dest)
    assertTrue(dest.length() > 100)
}
```

`writeOfficePdf` 用 Robolectric `PdfDocument`。Runner 与 Task 4 相同。

- [ ] **Step 2: Run test to verify it fails**

```bash
JAVA_HOME=/Users/wuyu/.sdkman/candidates/java/17.0.11-amzn \
ANDROID_HOME=/Users/wuyu/Library/Android/sdk \
./gradlew -p android :app:testDebugUnitTest --tests com.videoconverter.android.document.OfficePdfTest
```

Expected: FAIL

- [ ] **Step 3: Write minimal implementation**

只读 OOXML。段落：`document.paragraphs.map { it.text }`。表格：每个 `XWPFTable` 的行/单元格文字。xlsx：第一张 sheet 已用区域，每行一个 `Table` 或整表一个 `Table`。

`writeOfficePdf`：A4、11pt、左 48px，按块 `drawText`，超高换页。不处理页眉、图片、合并单元格。POI 失败或 blocks 全空 → `IllegalStateException("无法转换此文档")`。

- [ ] **Step 4: Run test to verify it passes**

同一命令。Expected: PASS。若 POI 在 Android 单测里因 `javax.xml.stream` 炸掉，加上：

```kotlin
implementation("com.fasterxml:aalto-xml:1.3.3")
```

并在 `OfficePdf` 文件顶注释写明用途。不要换商业 SDK。

- [ ] **Step 5: Commit**

```bash
git add android/app/build.gradle.kts \
  android/app/src/main/java/com/videoconverter/android/document/OfficePdf.kt \
  android/app/src/test/java/com/videoconverter/android/document/OfficePdfTest.kt
git commit -m "$(cat <<'EOF'
feat(android): 本机把简单 docx/xlsx 画成 PDF

EOF
)"
```

---

### Task 7: 入队、DocumentEngine、泵分发

**Files:**
- Modify: `android/app/src/main/java/com/videoconverter/android/domain/Queue.kt`
- Create: `android/app/src/main/java/com/videoconverter/android/document/DocumentEngine.kt`
- Modify: `android/app/src/main/java/com/videoconverter/android/service/TranscodeService.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/domain/QueueTest.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/document/DocumentEngineTest.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/service/TranscodeServiceStateTest.kt`（若有泵选择逻辑可单测，没有则加 `fun shouldRunDocumentEngine(preset: String)`）

**Interfaces:**
- Consumes: Task 1–6 API、`OutputStore.createJobOutput` / `export`、`SourceAccess`
- Produces:
  - `fun enqueueDocumentJobs(sources, config, outputDir, nextId, exists): Result<EnqueueReport>`——**不**调用 `resolveConfig`/`validate`
  - `fun shouldRunDocumentEngine(preset: String) = isDocumentPreset(preset)`
  - `class DocumentEngine(context)`：`suspend fun convert(job, target, onProgress): Job`，返回完成/失败/取消的 `Job`，填 `outputPath` + `outputPaths`
  - `fun cancel(jobId: String)` / `fun wasCancelled(jobId: String)`
  - 泵：`if (shouldRunDocumentEngine(running.config.preset)) documentEngine.convert else ffmpeg.transcode`
  - 取消：文档跑着就 `documentEngine.cancel`，否则 `ffmpeg.cancel`

- [ ] **Step 1: Write the failing tests**

`QueueTest.kt`：

```kotlin
@Test
fun enqueueDocumentJobsDoesNotUseVideoPresets() {
    val media = MediaInfo(
        sourceUri = "content://doc",
        displayName = "a.pdf",
        importable = true,
        pageCount = 2,
        pageStart = 1,
        pageEnd = 2,
    )
    val report = enqueueDocumentJobs(
        sources = listOf(media),
        config = OutputConfig(preset = "pdf-split"),
        outputDir = "/tmp/planned",
        nextId = { "id1" },
        exists = { false },
    ).getOrThrow()
    assertEquals(1, report.jobs.size)
    assertEquals("pdf-split", report.jobs[0].config.preset)
    assertEquals("a.pdf", report.jobs[0].displayName)
}

@Test
fun enqueueDocumentJobsSkipsUnsupported() {
    val bad = MediaInfo("u", "a.doc", importable = false, error = "不支持此格式")
    val report = enqueueDocumentJobs(
        listOf(bad), OutputConfig(preset = "office-pdf"), "/tmp", { "x" }, { false },
    ).getOrThrow()
    assertTrue(report.jobs.isEmpty())
    assertEquals(1, report.skipped.size)
}
```

`DocumentEngineTest.kt`：对一份 Task 5 的两页 PDF 跑 `convert` 到 `OutputTarget.Kind.AppExternal`（用临时目录）。若 engine 强依赖 `Context`/`OutputStore`，把「按页写出文件列表」抽成：

```kotlin
fun planDocumentOutputs(job: Job): List<Pair<String /*stem*/, String /*ext*/>>
```

并测：`pdf-split` + 页 1–2 → 两个 `pdf`；`pdf-image` + container jpg → 两个 `jpg`；`image-jpg` → 一个 `jpg`。

```kotlin
@Test
fun shouldRunDocumentEngine() {
    assertTrue(shouldRunDocumentEngine("pdf-txt"))
    assertFalse(shouldRunDocumentEngine("mp4-h264"))
    assertFalse(shouldRunDocumentEngine("audio-mp3"))
}
```

把 `shouldRunDocumentEngine` 放 `DocumentEngine.kt` 文件顶层。

- [ ] **Step 2: Run tests to verify they fail**

```bash
JAVA_HOME=/Users/wuyu/.sdkman/candidates/java/17.0.11-amzn \
ANDROID_HOME=/Users/wuyu/Library/Android/sdk \
./gradlew -p android :app:testDebugUnitTest --tests com.videoconverter.android.domain.QueueTest --tests com.videoconverter.android.document.DocumentEngineTest
```

Expected: FAIL

- [ ] **Step 3: Write minimal implementation**

`enqueueDocumentJobs` 复制 `enqueueJobs` 的跳过/占名结构，但扩展名用 `documentExtension(config.preset, config.container)`，`allocateOutputPath` 只占**第一份**路径（实际多文件由 engine 在 job 目录生成）。校验：`!importable` 跳过；PDF 预设需要 `pageCount != null` 否则跳过「无法读取页数」。

`planDocumentOutputs`：PDF 类用 `clampPageRange(media.pageStart ?: 1, media.pageEnd ?: pageCount, pageCount)` 得到页数 `n`；`pdf-txt`/`pdf-compress`/`office-pdf`/`image-*` 的 `n=1`。

`DocumentEngine.convert` 流程：

1. 若已 cancel → Cancelled
2. `SourceAccess` 把源拷到可读 `File`
3. 按预设调用 ImageConvert / PdfOps / OfficePdf，进度 `index/n`
4. 每个产出 `export(...)`，收集 locations
5. `job.copy(status=Completed, progress=100, outputPath=locations.first(), outputPaths=locations)`

失败映射：`IllegalStateException` 的 message 原样进 `Job.error`。

`TranscodeService`：`onCreate` 里 `documentEngine = DocumentEngine(this)`。`onDestroy` 对 running 文档 job 也 cancel。`claimNextQueued` 的 `reserve` 对文档预设改成 `documentEngine.reserve`（一个 `AtomicBoolean` 槽，语义与 ffmpeg 相同：同时只能跑一个）。最简单：ffmpeg 与 document **共用**现有 `ffmpeg.reserve` 槽——文档任务也 `reserve` 一下占坑，但 `transcode` 不调用 FFmpeg。这样不用改 `claimNextQueuedPersisted`。**采用这一做法。** 文档 `convert` 开头不必再 reserve；取消时 `ffmpeg.cancel` 不会停文档，所以 `cancelJob` 里：

```kotlin
if (runningJobId == jobId) {
    if (isDocumentPreset(jobStore.load().first { it.id == jobId }.config.preset)) {
        documentEngine.cancel(jobId)
    } else {
        ffmpeg.cancel(jobId)
    }
}
```

`documentEngine.cancel` 设 flag；循环里每页检查。`recoverInterruptedPump` 文案对文档也可仍是「转码被中断」（YAGNI，不改文案）。

- [ ] **Step 4: Run tests to verify they pass**

同一命令 + `:app:testDebugUnitTest`。Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/videoconverter/android/domain/Queue.kt \
  android/app/src/main/java/com/videoconverter/android/document/DocumentEngine.kt \
  android/app/src/main/java/com/videoconverter/android/service/TranscodeService.kt \
  android/app/src/test/java/com/videoconverter/android/domain/QueueTest.kt \
  android/app/src/test/java/com/videoconverter/android/document/DocumentEngineTest.kt \
  android/app/src/test/java/com/videoconverter/android/service/TranscodeServiceStateTest.kt
git commit -m "$(cat <<'EOF'
feat(android): 文档任务入队并由同一泵执行

EOF
)"
```

---

### Task 8: 第五 Tab、三会话、三步界面

**Files:**
- Modify: `android/app/src/main/java/com/videoconverter/android/ui/Convert.kt`
- Modify: `android/app/src/main/java/com/videoconverter/android/ui/RootTabs.kt`
- Modify: `android/app/src/main/java/com/videoconverter/android/ui/Wizard.kt`
- Modify: `android/app/src/main/java/com/videoconverter/android/ui/WizardComponents.kt`
- Modify: `android/app/src/main/java/com/videoconverter/android/ui/AppViewModel.kt`
- Modify: `android/app/src/main/java/com/videoconverter/android/ui/AppScreen.kt`
- Modify: `android/app/src/main/java/com/videoconverter/android/ui/RootScreens.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/ui/ConvertTest.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/ui/RootTabsTest.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/ui/WizardTest.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/ui/AppViewModelTest.kt`

**Interfaces:**
- Consumes: Task 1–7
- Produces: 可走完的文档向导；开始后 reset 文档会话并打开历史「文档」

- [ ] **Step 1: Write the failing tests**

`ConvertTest.kt`：`ConvertMode.Document`；`defaultDocumentSession().preset == "image-jpg"` 且 `output.kind == Gallery`；`sessionFor`/`replaceSession` 改成三个会话（用 `data class WizardSessions(val video, val audio, val document)` 或三参数），文档替换不影响视频。

`RootTabsTest.kt`：

```kotlin
assertEquals(
    listOf("视频转码", "音频转换", "文档", "历史记录", "我的"),
    RootTab.entries.map(::rootTabLabel),
)
assertEquals(
    RootBack(RootTab.Document, MinePage.Root, WizardStep.Sources),
    consumeRootBack(RootTab.Document, MinePage.Root, WizardStep.Format),
)
assertEquals(
    HistorySegment.Document,
    historySegmentAfterEnqueue(ConvertMode.Document, "image-jpg"),
)
```

`WizardTest.kt`：`documentCardsFor(DocumentSourceKind.Pdf).map { it.id } == listOf("pdf-image","pdf-txt","pdf-compress","pdf-split")`；图片六张卡；Word 只有 `office-pdf`。`outputChoicesForDocument("pdf-split")` 含 documents/downloads/custom，不含 gallery。`outputChoicesForDocument("image-jpg")` 含 gallery，不含 documents。

`AppViewModelTest.kt`：`presetTitle("pdf-txt")`；`shouldShowResolution` 对文档预设为 false。

- [ ] **Step 2: Run tests to verify they fail**

```bash
JAVA_HOME=/Users/wuyu/.sdkman/candidates/java/17.0.11-amzn \
ANDROID_HOME=/Users/wuyu/Library/Android/sdk \
./gradlew -p android :app:testDebugUnitTest --tests com.videoconverter.android.ui.ConvertTest --tests com.videoconverter.android.ui.RootTabsTest --tests com.videoconverter.android.ui.WizardTest --tests com.videoconverter.android.ui.AppViewModelTest
```

Expected: FAIL

- [ ] **Step 3: Write minimal implementation**

`enum class RootTab { Transcode, Audio, Document, History, Mine }`  
`enum class ConvertMode { Video, Audio, Document }`

`defaultDocumentSession()`：`preset=image-jpg`，`output=Gallery`。

`AppUiState` 增加 `document: WizardSession`。所有 `sessionFor`/`updateSession`/`start`/`persistOutput` 走当前 mode。

探测文档源：不要调 ffprobe。按文件名 `documentSourceKind`；PDF 用 `PdfRenderer` 填 `pageCount`（打不开则 `importable=false`）。HEIC 等图：`importable=true`，解码失败留到 engine。混选：`sameDocumentKind` 为 false 的新文件不进列表，`message = "请一次只加同一种文件"`。

源类型变化时：`preset = defaultDocumentPreset(kind)`，若当前 output kind 不在该预设允许集合里，改成 `Gallery` 或 `Documents`。

`start(ConvertMode.Document)` 用 `enqueueDocumentJobs`，并把会话里的页范围写进每个 `MediaInfo.pageStart/pageEnd`。成功后只 `defaultDocumentSession()` 的 sources/step 重置（preset/output 可保留），`tab=History`，`historySegment=Document`。

`AppScreen`：文档 Tab 复用 `TranscodePane`，空态两按钮「相册」「文件」；第 1 步 PDF 显示当前页预览 + 起始/结束页数字。Office 只显示文件名。Format 用 `documentCardsFor`。`pdf-image` 额外三档 JPG/PNG/WebP 写入 `OutputConfig.container`。压缩三档写入 `quality`。说明文案：Office「简单文字和表格可以，复杂排版会对不齐」；TXT「扫描件抽不出字」；AMR 那种卡片下方样式即可。

`RootScreens.kt`：`HistorySegmentTabs` 三段；`TabGlyph` 给 Document 画一个简单折页矩形。Job 行副标题：`outputPaths.size > 1` 时「N 张图片」或「N 个 PDF」（按 `documentResultIsImage`）。

`consumeRootBack`：`tab == Document` 与 Audio 一样后退 step。

相册 MIME 仅图片；文件 MIME：`image/*`、`application/pdf`、Word/Excel OOXML。

- [ ] **Step 4: Run tests to verify they pass**

上面的测试命令 + `:app:testDebugUnitTest` + `:app:assembleDebug`。Expected: BUILD SUCCESSFUL。

真机冒烟（有设备才做，没有就在报告里写未跑）：相册图转 WebP；PDF 转 JPG；PDF 拆分；docx 转 PDF。历史「文档」可见。

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/videoconverter/android/ui \
  android/app/src/test/java/com/videoconverter/android/ui
git commit -m "$(cat <<'EOF'
feat(android): 文档三步向导与第五个底栏 Tab

EOF
)"
```

---

## Self-review

1. **Spec coverage:** 五 Tab、先选文件、同类型、页范围、格式表、存放分流、历史三段、单泵、多输出一条任务、错误文案、明确不做的项均有任务。PdfRenderer 转图在 Task 5/7；预览在 Task 8。
2. **Placeholders:** 无 TBD。GIF/BMP 编码器、PdfBox 坐标、POI 排除项都写死。
3. **Types:** `DocumentSourceKind`、预设 id、`Kind.Documents`、`outputPaths`、`WizardSessions`/`sessionFor` 三会话在后续任务中名称一致。`historySegmentAfterEnqueue` 在 Task 1 先靠 preset，Task 8 补 `ConvertMode.Document`。
