# Android 音频转换 Tab Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Android「轻转码」底栏增加「音频转换」，用与视频转码相同的三步引导，把音频文件或视频音轨转成 MP3 / M4A / WAV / OGG。

**Architecture:** 一套队列和 FFmpeg 泵不变。`AppViewModel` 持有视频、音频两份 `WizardSession`。引擎补 WAV/OGG 预设；输出补 `Music` 目录。历史仍读同一个 `JobStore`，用纯函数按预设/容器分成视频、音频两个列表。Compose 复用现有向导组件，音频页换预设卡、添加入口和存放卡。

**Tech Stack:** Kotlin、Jetpack Compose、现有 JUnit4 / Robolectric。不新增依赖。不改桌面 `src/`。

## Global Constraints

- 底栏顺序：**视频转码 | 音频转换 | 历史记录 | 我的**
- 音频三步：添加（音乐/相册/文件 + 预览裁切）→ 格式（MP3、M4A、WAV、OGG）→ 存放（音乐/下载/自定义）
- 音频默认预设 `audio-mp3`，默认存放 `Music/轻转码`
- 视频页「只导出音频」MP3/M4A 保留；视频页不出现 WAV/OGG
- 两套会话独立；开始成功后只重置当前会话，并打开历史对应分段
- 清空已完成只清当前历史分段
- JDK 17：`JAVA_HOME=/Users/wuyu/.sdkman/candidates/java/17.0.11-amzn`，`ANDROID_HOME=/Users/wuyu/Library/Android/sdk`
- 中文文案；不上 Navigation；不给音频单独队列
- 现有 JVM 单元测试必须继续通过

## File map

```
android/app/src/main/java/com/videoconverter/android/domain/
  Presets.kt              # WAV/OGG 预设、isAudioOnlyConfig
  Validate.kt             # wav/ogg 容器与 pcm 编码器
  FfmpegArgs.kt           # -vn；WAV 无 -b:a；OGG libopus
android/app/src/main/java/com/videoconverter/android/data/
  OutputStore.kt          # Kind.Music → Music/轻转码 + Audio collection
android/app/src/main/java/com/videoconverter/android/ui/
  Convert.kt              # 新建：ConvertMode、WizardSession、探测修正
  RootTabs.kt             # RootTab.Audio、历史分段
  Wizard.kt               # 音频预设卡、存放卡、dock 文案
  AppViewModel.kt         # 双会话
  AppScreen.kt            # 四 Tab + 音频向导
  RootScreens.kt          # 音频图标、历史分段条
  WizardComponents.kt     # Dropzone 三入口、OutputChoiceGrid 收 cards
  TrimPanel.kt            # 纯音频不显示画面、仍可播放
android/app/src/test/...  # 对应 *Test.kt
```

---

### Task 1: WAV / OGG 预设与校验

**Files:**
- Modify: `android/app/src/main/java/com/videoconverter/android/domain/Presets.kt`
- Modify: `android/app/src/main/java/com/videoconverter/android/domain/Validate.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/domain/PresetsTest.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/domain/ValidateTest.kt`

**Interfaces:**
- Consumes: 现有 `OutputConfig` / `ResolvedConfig` / `listPresets` / `resolveConfig` / `validate`
- Produces:
  - `fun isAudioOnlyConfig(config: ResolvedConfig): Boolean`
  - 预设 `audio-wav`（容器 `wav`，`videoEncoder=null`，`audioEncoder=pcm_s16le`，`audioBitrateKbps=null`）
  - 预设 `audio-ogg`（容器 `ogg`，`audioEncoder=opus`，有码率）
  - `extensionFor` 接受 `wav`、`ogg`
  - `AUDIO_ENCODERS` 含 `pcm_s16le`；`CONTAINERS` 含 `wav`、`ogg`

- [ ] **Step 1: Write the failing tests**

在 `PresetsTest.kt` 追加：

```kotlin
@Test
fun listsAudioWavAndOggPresets() {
    val ids = listPresets().map { it.id }
    assertTrue(ids.contains("audio-wav"))
    assertTrue(ids.contains("audio-ogg"))
}

@Test
fun wavPresetHasPcmAndNoBitrate() {
    val resolved = resolveConfig(OutputConfig(preset = "audio-wav")).getOrThrow()
    assertEquals("wav", resolved.container)
    assertEquals("wav", resolved.extension)
    assertEquals(null, resolved.videoEncoder)
    assertEquals("pcm_s16le", resolved.audioEncoder)
    assertEquals(null, resolved.audioBitrateKbps)
    assertTrue(isAudioOnlyConfig(resolved))
}

@Test
fun oggPresetUsesOpusBitrate() {
    val resolved = resolveConfig(OutputConfig(preset = "audio-ogg", quality = "small")).getOrThrow()
    assertEquals("ogg", resolved.container)
    assertEquals("opus", resolved.audioEncoder)
    assertEquals(128, resolved.audioBitrateKbps)
    assertTrue(isAudioOnlyConfig(resolved))
}
```

在 `ValidateTest.kt` 追加：

```kotlin
@Test
fun wavAndOggWithoutAudioFail() {
    listOf("audio-wav", "audio-ogg").forEach { preset ->
        val config = resolveConfig(OutputConfig(preset = preset)).getOrThrow()
        val err = validate(config, h264().copy(audioCodec = null)).exceptionOrNull()!!.message!!
        assertTrue(err.contains("没有音频流"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
JAVA_HOME=/Users/wuyu/.sdkman/candidates/java/17.0.11-amzn \
ANDROID_HOME=/Users/wuyu/Library/Android/sdk \
./gradlew -p android :app:testDebugUnitTest --tests com.videoconverter.android.domain.PresetsTest --tests com.videoconverter.android.domain.ValidateTest
```

Expected: FAIL（未知预设 / `isAudioOnlyConfig` 未定义）

- [ ] **Step 3: Implement presets and validation**

`Presets.kt`：

- `listPresets()` 增加：
  - `PresetInfo("audio-wav", "仅音频 / WAV", "无损 PCM")`
  - `PresetInfo("audio-ogg", "仅音频 / OGG", "Opus，体积更小")`
- `resolveConfig` 里把现有 `audio-mp3` / `audio-aac` 分支扩成：

```kotlin
fun isAudioOnlyConfig(config: ResolvedConfig): Boolean =
    config.container in listOf("mp3", "m4a", "wav", "ogg") ||
        config.preset in listOf("audio-mp3", "audio-aac", "audio-wav", "audio-ogg")

// inside resolveConfig, before generic video path:
when (preset) {
    "audio-mp3", "audio-aac", "audio-wav", "audio-ogg" -> {
        val container = when (preset) {
            "audio-aac" -> "m4a"
            "audio-wav" -> "wav"
            "audio-ogg" -> "ogg"
            else -> "mp3"
        }
        val audioEncoder = when (preset) {
            "audio-aac" -> "aac"
            "audio-wav" -> "pcm_s16le"
            "audio-ogg" -> "opus"
            else -> "mp3"
        }
        val bitrate = if (preset == "audio-wav") null else audioBitrateKbps
        return@runCatching ResolvedConfig(
            preset = preset,
            container = container,
            extension = extensionFor(container).getOrThrow(),
            videoEncoder = null,
            audioEncoder = audioEncoder,
            maxWidth = null,
            maxHeight = null,
            videoBitrateKbps = null,
            frameRate = null,
            audioBitrateKbps = bitrate,
            keepAudio = true,
            quality = quality,
            trimStartSecs = config.trimStartSecs,
            trimEndSecs = config.trimEndSecs,
        )
    }
}
```

- `extensionFor` 增加 `"wav"`, `"ogg"`。

`Validate.kt`：

- `CONTAINERS` 加 `"wav"`, `"ogg"`
- `AUDIO_ENCODERS` 加 `"pcm_s16le"`
- 删除文件内 private `isAudioOnly`，改用 `isAudioOnlyConfig(config)`
- `containerAcceptsAudio`：`"wav" -> true`；`"ogg" -> normalized in listOf("opus", "vorbis")`

- [ ] **Step 4: Re-run tests**

同一 gradle 命令。Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/videoconverter/android/domain/Presets.kt \
  android/app/src/main/java/com/videoconverter/android/domain/Validate.kt \
  android/app/src/test/java/com/videoconverter/android/domain/PresetsTest.kt \
  android/app/src/test/java/com/videoconverter/android/domain/ValidateTest.kt
git commit -m "feat(android): 增加 WAV 与 OGG 音频预设"
```

---

### Task 2: FFmpeg 音频参数

**Files:**
- Modify: `android/app/src/main/java/com/videoconverter/android/domain/FfmpegArgs.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/domain/FfmpegArgsTest.kt`

**Interfaces:**
- Consumes: `isAudioOnlyConfig`、`resolveConfig`
- Produces: WAV 参数含 `-vn`、`-c:a pcm_s16le`、`-f wav`，不含 `-b:a`；OGG 含 `-c:a libopus`、`-f ogg`、`-b:a`

- [ ] **Step 1: Write the failing tests**

在 `FfmpegArgsTest.kt` 追加（复用现有 `h264()` 与 `valueAfter`）：

```kotlin
@Test
fun wavOmitsBitrateAndUsesPcmMuxer() {
    val config = resolveConfig(OutputConfig(preset = "audio-wav")).getOrThrow()
    val args = buildFfmpegArgs("/in", "/out.partial.wav", config, h264()).getOrThrow()
    assertTrue(args.contains("-vn"))
    assertEquals("pcm_s16le", valueAfter(args, "-c:a"))
    assertEquals("wav", valueAfter(args, "-f"))
    assertFalse(args.contains("-b:a"))
}

@Test
fun oggUsesLibopusAndBitrate() {
    val config = resolveConfig(OutputConfig(preset = "audio-ogg", quality = "standard")).getOrThrow()
    val args = buildFfmpegArgs("/in", "/out.partial.ogg", config, h264()).getOrThrow()
    assertTrue(args.contains("-vn"))
    assertEquals("libopus", valueAfter(args, "-c:a"))
    assertEquals("ogg", valueAfter(args, "-f"))
    assertEquals("192k", valueAfter(args, "-b:a"))
}
```

- [ ] **Step 2: Run to verify fail**

```bash
JAVA_HOME=/Users/wuyu/.sdkman/candidates/java/17.0.11-amzn \
ANDROID_HOME=/Users/wuyu/Library/Android/sdk \
./gradlew -p android :app:testDebugUnitTest --tests com.videoconverter.android.domain.FfmpegArgsTest
```

Expected: FAIL（muxer 落到 mp4 / 仍写 `-b:a`）

- [ ] **Step 3: Implement**

`FfmpegArgs.kt`：

- 删除 private `isAudioOnly`，改调用 `isAudioOnlyConfig`
- 音频-only 分支：

```kotlin
if (isAudioOnlyConfig(config)) {
    val encoder = config.audioEncoder ?: "mp3"
    args += listOf("-vn", "-c:a", ffmpegAudioCodec(encoder))
    if (encoder != "pcm_s16le" && config.audioBitrateKbps != null) {
        args += listOf("-b:a", "${config.audioBitrateKbps}k")
    }
    pushOutput(args, config.container, outputPartial)
    return@runCatching args
}
```

- `ffmpegMuxer`：`"wav" -> "wav"`，`"ogg" -> "ogg"`
- `ffmpegAudioCodec`：`"pcm_s16le" -> "pcm_s16le"`（已有 opus → libopus）

- [ ] **Step 4: Re-run FfmpegArgsTest** — Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/videoconverter/android/domain/FfmpegArgs.kt \
  android/app/src/test/java/com/videoconverter/android/domain/FfmpegArgsTest.kt
git commit -m "feat(android): WAV 不写码率，OGG 使用 libopus"
```

---

### Task 3: 音乐库输出与音频 MIME

**Files:**
- Modify: `android/app/src/main/java/com/videoconverter/android/data/OutputStore.kt`
- Modify: `android/app/src/main/java/com/videoconverter/android/ui/AppViewModel.kt`（仅 `outputMimeType`）
- Test: `android/app/src/test/java/com/videoconverter/android/data/OutputStoreTest.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/ui/AppViewModelTest.kt`

**Interfaces:**
- Produces: `OutputTarget.Kind.Music`；`mediaStoreRelativePath(Music) = "Music/轻转码"`；`mediaStoreCollection(Music, *)` 用 `MediaStore.Audio`；`outputMimeType` 对 wav/ogg 返回 `audio/wav` / `audio/ogg`

- [ ] **Step 1: Write failing tests**

`OutputStoreTest.kt`：

```kotlin
@Test
fun musicRelativePathIsMusicFolder() {
    assertEquals("Music/轻转码", mediaStoreRelativePath(OutputTarget.Kind.Music))
}
```

`AppViewModelTest.kt` 的 `outputMimeMatchesResolvedContainer` 增加：

```kotlin
assertEquals("audio/wav", outputMimeType(OutputConfig(preset = "audio-wav")))
assertEquals("audio/ogg", outputMimeType(OutputConfig(preset = "audio-ogg")))
```

- [ ] **Step 2: Run tests — Expected: compile/assert FAIL**

- [ ] **Step 3: Implement**

`OutputStore.kt`：

```kotlin
enum class Kind { Downloads, SafTree, AppExternal, Gallery, Movies, Music }

fun mediaStoreRelativePath(kind: OutputTarget.Kind): String = when (kind) {
    OutputTarget.Kind.Gallery -> "DCIM/轻转码"
    OutputTarget.Kind.Movies -> "Movies/轻转码"
    OutputTarget.Kind.Downloads -> "Download/轻转码"
    OutputTarget.Kind.Music -> "Music/轻转码"
    OutputTarget.Kind.SafTree, OutputTarget.Kind.AppExternal ->
        throw IllegalArgumentException("not a MediaStore target")
}
```

`mediaStoreCollection` 增加：

```kotlin
OutputTarget.Kind.Music -> MediaStore.Audio.Media.getContentUri(volume)
```

`outputMimeType` 增加 `"wav" -> "audio/wav"`、`"ogg" -> "audio/ogg"`。

`outputFolderLabel`（Task 5 会用到，本任务若编译需要 exhaustive when，先在 `WizardComponents.kt` 给 `Music -> "音乐"`）。

- [ ] **Step 4: Re-run those tests — PASS**

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/videoconverter/android/data/OutputStore.kt \
  android/app/src/main/java/com/videoconverter/android/ui/AppViewModel.kt \
  android/app/src/main/java/com/videoconverter/android/ui/WizardComponents.kt \
  android/app/src/test/java/com/videoconverter/android/data/OutputStoreTest.kt \
  android/app/src/test/java/com/videoconverter/android/ui/AppViewModelTest.kt
git commit -m "feat(android): 音频可输出到系统音乐库"
```

---

### Task 4: 四 Tab 与历史视频/音频分流

**Files:**
- Modify: `android/app/src/main/java/com/videoconverter/android/ui/RootTabs.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/ui/RootTabsTest.kt`

**Interfaces:**
- Produces:
  - `enum class RootTab { Transcode, Audio, History, Mine }`（插入顺序即底栏顺序）
  - `enum class HistorySegment { Video, Audio }`
  - `fun rootTabLabel(RootTab.Audio) = "音频转换"`
  - `fun isAudioHistoryJob(job: Job): Boolean`
  - `fun historyJobs(jobs: List<Job>, segment: HistorySegment): List<Job>`
  - `fun historyEmptyLabel(segment: HistorySegment): String`
  - `fun remainingJobsAfterClearFinished(jobs: List<Job>, segment: HistorySegment): List<Job>`
  - `fun historySegmentAfterStart(tab: RootTab): HistorySegment?`
  - `consumeRootBack`：`RootTab.Audio` 第 2/3 步后退，与视频相同

- [ ] **Step 1: Write failing tests**

替换/扩展 `RootTabsTest.kt`：

```kotlin
@Test
fun tabLabelsMatchProductCopy() {
    assertEquals(
        listOf("视频转码", "音频转换", "历史记录", "我的"),
        RootTab.entries.map(::rootTabLabel),
    )
}

@Test
fun historySplitsVideoAndAudioJobs() {
    val video = job("v", OutputConfig(preset = "mp4-h264"))
    val fromVideoExtract = job("a1", OutputConfig(preset = "audio-mp3"))
    val wav = job("a2", OutputConfig(preset = "audio-wav"))
    val jobs = listOf(video, fromVideoExtract, wav)
    assertFalse(isAudioHistoryJob(video))
    assertTrue(isAudioHistoryJob(fromVideoExtract))
    assertTrue(isAudioHistoryJob(wav))
    assertEquals(listOf(video), historyJobs(jobs, HistorySegment.Video))
    assertEquals(listOf(fromVideoExtract, wav), historyJobs(jobs, HistorySegment.Audio))
    assertEquals("还没有视频记录", historyEmptyLabel(HistorySegment.Video))
    assertEquals("还没有音频记录", historyEmptyLabel(HistorySegment.Audio))
}

@Test
fun clearFinishedOnlyDropsCurrentSegment() {
    val doneVideo = job("v", OutputConfig(preset = "mp4-h264"), JobStatus.Completed)
    val doneAudio = job("a", OutputConfig(preset = "audio-mp3"), JobStatus.Completed)
    val runningAudio = job("r", OutputConfig(preset = "audio-ogg"), JobStatus.Running)
    val kept = remainingJobsAfterClearFinished(
        listOf(doneVideo, doneAudio, runningAudio),
        HistorySegment.Audio,
    )
    assertEquals(setOf("v", "r"), kept.map { it.id }.toSet())
}

@Test
fun backConsumesAudioWizardSteps() {
    assertEquals(
        RootBack(RootTab.Audio, MinePage.Root, WizardStep.Sources),
        consumeRootBack(RootTab.Audio, MinePage.Root, WizardStep.Format),
    )
    assertNull(consumeRootBack(RootTab.Audio, MinePage.Root, WizardStep.Sources))
    assertEquals(HistorySegment.Audio, historySegmentAfterStart(RootTab.Audio))
    assertEquals(HistorySegment.Video, historySegmentAfterStart(RootTab.Transcode))
    assertNull(historySegmentAfterStart(RootTab.History))
}

private fun job(id: String, config: OutputConfig, status: JobStatus = JobStatus.Completed) = Job(
    id = id,
    sourceUri = "content://$id",
    displayName = "$id.mp4",
    outputPath = null,
    status = status,
    progress = 1.0,
    error = null,
    config = config,
    media = MediaInfo(sourceUri = "content://$id", displayName = "$id.mp4", importable = true),
)
```

删除对无参 `historyEmptyLabel()` 的断言，或改成默认视频文案的 overload。**选定：删除无参函数，一律带 `HistorySegment`。**

- [ ] **Step 2: Run RootTabsTest — FAIL**

- [ ] **Step 3: Implement in RootTabs.kt**

```kotlin
enum class RootTab { Transcode, Audio, History, Mine }
enum class HistorySegment { Video, Audio }

fun rootTabLabel(tab: RootTab): String = when (tab) {
    RootTab.Transcode -> "视频转码"
    RootTab.Audio -> "音频转换"
    RootTab.History -> "历史记录"
    RootTab.Mine -> "我的"
}

fun isAudioHistoryJob(job: Job): Boolean {
    val preset = job.config.preset
    if (preset in listOf("audio-mp3", "audio-aac", "audio-wav", "audio-ogg")) return true
    val container = resolveConfig(job.config).getOrNull()?.container
    return container in listOf("mp3", "m4a", "wav", "ogg")
}

fun historyJobs(jobs: List<Job>, segment: HistorySegment): List<Job> =
    jobs.filter { isAudioHistoryJob(it) == (segment == HistorySegment.Audio) }

fun historyEmptyLabel(segment: HistorySegment): String = when (segment) {
    HistorySegment.Video -> "还没有视频记录"
    HistorySegment.Audio -> "还没有音频记录"
}

fun remainingJobsAfterClearFinished(jobs: List<Job>, segment: HistorySegment): List<Job> =
    jobs.filter { job ->
        val inSegment = isAudioHistoryJob(job) == (segment == HistorySegment.Audio)
        if (!inSegment) true
        else job.status == JobStatus.Queued || job.status == JobStatus.Running
    }

fun historySegmentAfterStart(tab: RootTab): HistorySegment? = when (tab) {
    RootTab.Transcode -> HistorySegment.Video
    RootTab.Audio -> HistorySegment.Audio
    else -> null
}

fun consumeRootBack(...): RootBack? = when {
    tab == RootTab.Mine && minePage != MinePage.Root ->
        RootBack(tab, MinePage.Root, wizardStep)
    tab == RootTab.Transcode || tab == RootTab.Audio ->
        retreatStep(wizardStep)?.let { RootBack(tab, minePage, it) }
    else -> null
}
```

`TabGlyph` 此时会缺 `Audio` 分支，**本任务在 `RootScreens.kt` 给 `RootTab.Audio` 画音符**（圆 + 两条小弧），否则无法编译。

- [ ] **Step 4: Run RootTabsTest — PASS**

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/videoconverter/android/ui/RootTabs.kt \
  android/app/src/main/java/com/videoconverter/android/ui/RootScreens.kt \
  android/app/src/test/java/com/videoconverter/android/ui/RootTabsTest.kt
git commit -m "feat(android): 底栏增加音频 Tab 并拆分历史列表"
```

---

### Task 5: 音频向导纯函数与双会话模型

**Files:**
- Create: `android/app/src/main/java/com/videoconverter/android/ui/Convert.kt`
- Create: `android/app/src/test/java/com/videoconverter/android/ui/ConvertTest.kt`
- Modify: `android/app/src/main/java/com/videoconverter/android/ui/Wizard.kt`
- Modify: `android/app/src/test/java/com/videoconverter/android/ui/WizardTest.kt`

**Interfaces:**
- Produces:
  - `enum class ConvertMode { Video, Audio }`
  - `data class WizardSession(val sources: List<SourceItem>, val preset: String, val quality: String, val size: String, val output: OutputTarget)`
  - `fun defaultVideoSession(): WizardSession`
  - `fun defaultAudioSession(): WizardSession`
  - `fun sessionFor(video: WizardSession, audio: WizardSession, mode: ConvertMode): WizardSession`
  - `fun replaceSession(video: WizardSession, audio: WizardSession, mode: ConvertMode, session: WizardSession): Pair<WizardSession, WizardSession>`
  - `fun restrictAudioSource(media: MediaInfo): MediaInfo` — 无音轨则 `importable=false`、error「没有音频流，无法导出音频」
  - `val AUDIO_PRESET_CARDS` 四张卡
  - `val AUDIO_OUTPUT_CHOICE_CARDS` 音乐/下载/自定义
  - `const val OUTPUT_CHOICE_MUSIC = "music"`
  - `fun outputKindForChoice` 认 music → `Kind.Music`；`outputChoiceId(Music) = music`
  - `fun isAudioPreset` 含 wav/ogg
  - `fun isLosslessAudioPreset(preset: String) = preset == "audio-wav"`
  - `fun dockActionLabel(..., startLabel: String = "开始转码")`；音频传 `"开始转换"`
  - `fun dockSummary` 增加 `audioMode: Boolean = false`：空态「先添加音频或带声音的视频」；有文件时不拼分辨率

把 `SourceItem` 从 `AppViewModel.kt` 挪到 `Convert.kt`（同包，调用方不改 import）。

- [ ] **Step 1: Write ConvertTest + WizardTest 增量**

```kotlin
// ConvertTest.kt
@Test
fun audioSessionDefaultsToMp3AndMusic() {
    val audio = defaultAudioSession()
    assertEquals("audio-mp3", audio.preset)
    assertEquals(OutputTarget.Kind.Music, audio.output.kind)
    assertTrue(defaultVideoSession().sources.isEmpty())
}

@Test
fun replaceSessionIsIndependent() {
    val video = defaultVideoSession().copy(preset = "mp4-copy")
    val audio = defaultAudioSession()
    val (v, a) = replaceSession(video, audio, ConvertMode.Audio, audio.copy(preset = "audio-wav"))
    assertEquals("mp4-copy", v.preset)
    assertEquals("audio-wav", a.preset)
}

@Test
fun silentVideoRejectedInAudioSession() {
    val media = MediaInfo("u", "silent.mp4", videoCodec = "h264", audioCodec = null, importable = true)
    val marked = restrictAudioSource(media)
    assertFalse(marked.importable)
    assertTrue(marked.error!!.contains("没有音频流"))
}
```

`WizardTest` 增加音频存放卡、`isLosslessAudioPreset("audio-wav")`、`outputChoiceId(OutputTarget(Music)) == "music"`、`dockActionLabel(Output, false, false, "开始转换") == "开始转换"`。

- [ ] **Step 2: Run ConvertTest + WizardTest — FAIL**

- [ ] **Step 3: Implement Convert.kt and Wizard.kt helpers**

`dockSummary` 在 `audioMode` 为真且 `importableCount==0` 时三步都用「先添加音频或带声音的视频」；有文件时 Output 步与现 `audioOnly` 分支相同（文件 + 音质 + 裁切 + 存放）。

WAV 的 Output 摘要：若 `isLosslessAudioPreset`，不要拼 `· $qualityLabel`。给 `dockSummary` 增加 `losslessAudio: Boolean = false`。

- [ ] **Step 4: Tests PASS**

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/videoconverter/android/ui/Convert.kt \
  android/app/src/main/java/com/videoconverter/android/ui/Wizard.kt \
  android/app/src/main/java/com/videoconverter/android/ui/AppViewModel.kt \
  android/app/src/test/java/com/videoconverter/android/ui/ConvertTest.kt \
  android/app/src/test/java/com/videoconverter/android/ui/WizardTest.kt
git commit -m "feat(android): 抽出视频与音频两套向导会话"
```

---

### Task 6: ViewModel 按 mode 读写会话

**Files:**
- Modify: `android/app/src/main/java/com/videoconverter/android/ui/AppViewModel.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/ui/AppViewModelTest.kt`（纯函数即可；不要硬拉 AndroidViewModel）

**Interfaces:**
- `AppUiState` 改为：
  ```kotlin
  data class AppUiState(
      val video: WizardSession = defaultVideoSession(),
      val audio: WizardSession = defaultAudioSession(),
      val jobs: List<Job> = emptyList(),
      val message: String? = null,
  )
  ```
- `fun addUris(uris, mode: ConvertMode)`：音频 mode 探测后走 `restrictAudioSource`
- `fun remove/clearSources/updateTrim/setPreset/setQuality/setSize/setOutputChoice/pickOutputTree/start` 都带 `mode: ConvertMode`（视频 Tab 传 `Video`）
- `start(mode)` 用该 session 的 sources/preset/quality/size/output 入队；成功不在 VM 里清源（仍由 UI `clearSources(mode)`）
- `fun clearFinished(segment: HistorySegment)`：`jobStore.update { remainingJobsAfterClearFinished(it, segment) }`，不再无差别调 `TranscodeService.clearFinished`
- `init` 只把 SessionStore 填进 **video** 会话；音频每次冷启动用 `defaultAudioSession()`
- `sourcesChanged` 改成按 mode 两套布尔，供 `chooseStartAction` 使用

现有 `state.sources` 调用点会编译失败，本任务把 VM 改完，Task 7 改 UI。若 Task 6 单独提交时 `AppScreen` 编不过，**允许同一提交包含 AppScreen 的最小编译修复**（把 `state.sources` 临时改成 `state.video.sources`），完整音频 UI 仍在 Task 7。

- [ ] **Step 1: 给 `outputMimeType` 已在 Task 3 测过。本任务追加：**

```kotlin
@Test
fun shouldShowResolutionHidesAllAudioPresets() {
    assertFalse(shouldShowResolution("audio-wav"))
    assertFalse(shouldShowResolution("audio-ogg"))
}
```

- [ ] **Step 2: 改 AppUiState 后编译 AppScreen 会红。先改 VM，再最小修补 AppScreen 用 `state.video`。**

`updateSession(mode) { session -> ... }` 内部 helper，避免复制粘贴。

音频 `addUris`：

```kotlin
val probed = runCatching { ffmpeg.probe(...) }.getOrElse { placeholder.copy(error = ...) }
val result = if (mode == ConvertMode.Audio) restrictAudioSource(probed) else probed
```

`start(mode)` 空源提示：音频用「请先添加可转码的音频」，视频保持「请先添加可转码的视频」。

- [ ] **Step 3: Run AppViewModelTest + compile**

```bash
JAVA_HOME=/Users/wuyu/.sdkman/candidates/java/17.0.11-amzn \
ANDROID_HOME=/Users/wuyu/Library/Android/sdk \
./gradlew -p android :app:compileDebugKotlin :app:testDebugUnitTest --tests com.videoconverter.android.ui.AppViewModelTest
```

Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/videoconverter/android/ui/AppViewModel.kt \
  android/app/src/main/java/com/videoconverter/android/ui/AppScreen.kt \
  android/app/src/test/java/com/videoconverter/android/ui/AppViewModelTest.kt
git commit -m "feat(android): ViewModel 按视频和音频会话入队"
```

---

### Task 7: 音频三步界面、历史分段、预览

**Files:**
- Modify: `android/app/src/main/java/com/videoconverter/android/ui/AppScreen.kt`
- Modify: `android/app/src/main/java/com/videoconverter/android/ui/RootScreens.kt`
- Modify: `android/app/src/main/java/com/videoconverter/android/ui/WizardComponents.kt`
- Modify: `android/app/src/main/java/com/videoconverter/android/ui/TrimPanel.kt`

**Interfaces:**
- Consumes: Task 4–6 全部函数
- Produces: 可操作的四 Tab UI

- [ ] **Step 1: 没有新的 JVM 测试（界面）。先确认 Task 4–6 测试仍绿，再改 UI。**

- [ ] **Step 2: UI 行为（按项改，不要另起一套页面文件）**

`AppScreen`：

- `var audioStep / audioShowAll / audioSelectedUri` 与视频那套并列。
- `var historySegment`；`goToHistoryAndResetWizard(mode)`：reset 对应 step 状态、`clearSources(mode)`、`historySegmentAfterStart(tab)?.let { historySegment = it }`、`tab = History`。
- `when (tab)` 增加 `RootTab.Audio -> TranscodePane(...)`，`mode = Audio`。
- `TranscodePane` 增加参数：`mode: ConvertMode`、`onMusic: (() -> Unit)?`。
- 音频格式步：`PresetGrid(cards = AUDIO_PRESET_CARDS, ...)`，**不要** More 卡。给 `PresetGrid` 加 `showMore: Boolean = true`；`false` 时不拼 `null` 占位。
- 音频存放：`OutputChoiceGrid(cards = AUDIO_OUTPUT_CHOICE_CARDS, ...)`。
- `FormatDetailPanel`：音频 + WAV 显示「原始采样，不压缩，文件更大」，不显示音质 chip；其它音频预设只显示音质（标题「音质」）。
- Dock 仅 `tab == Transcode || tab == Audio`。音频 `dockActionLabel(..., "开始转换")`，`audioMode = true`。
- `BackHandler` 传入当前 tab 的 step（音频用 `audioStep`）。
- Pickers：
  - 音乐：`filePicker.launch(arrayOf("audio/*"))` 后 `addUris(uris, Audio)`
  - 音频相册：现有 gallery VideoOnly + Audio mode
  - 音频文件：`arrayOf("audio/*", "video/*")`
  - ViewModel `addUris` 需要记住 mode：用 `var pendingMode` 或两个 launcher 回调。推荐：

```kotlin
var pickerMode by remember { mutableStateOf(ConvertMode.Video) }
val filePicker = rememberLauncherForActivityResult(OpenMultipleDocuments()) { uris ->
    appViewModel.addUris(uris, pickerMode)
}
```

自定义目录未选：`actionEnabled` 在 Output 步若 `output.kind == SafTree && treeUri == null` 则为 false。点自定义只打开 picker，不把 kind 设成 SafTree 直到选中 URI（保持现 `pickOutputTree` 行为）。音频默认 Music，不是 SafTree。

`Dropzone`：

```kotlin
fun Dropzone(
    onGallery: () -> Unit,
    onFiles: () -> Unit,
    onMusic: (() -> Unit)? = null,
    centered: Boolean = false,
    audioMode: Boolean = false,
)
```

`audioMode` 标题「添加要转换的音频」，说明「从音乐库选曲子，从相册抽视频音轨，或从文件夹选文件。」三个 `SourceChoiceCard`：音乐 / 相册 / 文件。非居中列表顶上的 Dropzone 同样三按钮（横排，小屏可 `Arrangement.spacedBy(8.dp)`，三张 `weight(1f)`）。

`OutputChoiceGrid(cards: List<OutputChoiceCard> = OUTPUT_CHOICE_CARDS, ...)`。

`HistoryScreen`：

- 参数 `segment: HistorySegment`、`onSegment: (HistorySegment) -> Unit`、`jobs` 已是过滤后的列表、`emptyLabel: String`。
- `PageHeader` 的 `below` 放两段控件「视频 | 音频」（选中墨底白字，未选浅底，与 StepTabs 同一套色）。
- 空态用 `historyEmptyLabel(segment)`。

`TrimPanel`：

- `fun canPlayPreview(media)`：现有视频扩展名 **或** `mp3/m4a/aac/wav/ogg/flac/opus`。
- `fun showVideoSurface(media) = media.videoCodec != null && supportsSystemPreview(media)`（mkv 仍无画面，音频无画面）。
- Player 在 `canPlayPreview` 时创建；`AndroidView(PlayerView)` 仅 `showVideoSurface` 时显示。音频仍用时间轴播放/暂停。
- 更新 `supportsSystemPreview` 测试：`clip.mp3` 对 `canPlayPreview` 为 true，对 `showVideoSurface` 为 false。把新函数放 `AppViewModel.kt` 旁现有 `supportsSystemPreview`，测试加在 `AppViewModelTest`。

`TabGlyph` Audio：空心圆（音符头）+ 竖线 + 小旗，或圆 + 两条弧。选中用 Accent。

- [ ] **Step 3: Run unit tests + compile**

```bash
JAVA_HOME=/Users/wuyu/.sdkman/candidates/java/17.0.11-amzn \
ANDROID_HOME=/Users/wuyu/Library/Android/sdk \
./gradlew -p android :app:testDebugUnitTest :app:assembleDebug
```

Expected: 全绿；`android/app/build/outputs/apk/debug/app-debug.apk` 生成。

- [ ] **Step 4: 若有设备，安装冒烟**

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

手测：底栏四项；音频三步；历史分段；视频页仍能抽 MP3。无设备则跳过并在报告写明。

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/videoconverter/android/ui/AppScreen.kt \
  android/app/src/main/java/com/videoconverter/android/ui/RootScreens.kt \
  android/app/src/main/java/com/videoconverter/android/ui/WizardComponents.kt \
  android/app/src/main/java/com/videoconverter/android/ui/TrimPanel.kt \
  android/app/src/main/java/com/videoconverter/android/ui/AppViewModel.kt \
  android/app/src/test/java/com/videoconverter/android/ui/AppViewModelTest.kt
git commit -m "feat(android): 音频转换三步界面与历史分段"
```

---

## Spec coverage

| Spec 条目 | Task |
| --- | --- |
| 四 Tab 顺序与文案 | 4, 7 |
| 双 WizardSession、切 Tab 保留 | 5, 6, 7 |
| 开始后重置当前会话并打开对应历史 | 4, 6, 7 |
| 历史视频/音频分流、抽音频进音频列表 | 4, 7 |
| 清空只清当前分段 | 4, 6 |
| 音乐/相册/文件、无音轨标红 | 5, 6, 7 |
| 预览+裁切，纯音频无画面 | 7 |
| 四格式卡、WAV 无音质 | 5, 7 |
| 视频页保留抽音频、无 WAV/OGG | 7（不改 WIZARD_PRESET_CARDS 主列表以外的抽音频卡） |
| 音乐/下载/自定义 | 3, 5, 7 |
| WAV/OGG 引擎参数 | 1, 2 |
| MIME 打开/分享 | 3 |
| consumeRootBack 音频 | 4, 7 |
| 不做桌面/iOS/FLAC/单独队列 | 全局 |

## 明确不要做

- 改 `fetch-ffmpeg.mjs` / `FfmpegProcess.kt` 的打包路径（与本功能无关的未提交改动保持不动）
- 桌面端音频 Tab
- SessionStore 持久化音频会话（冷启动音频回到默认即可）
