# 轻转码 Android 原生版 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在仓库里新增独立的 Kotlin/Compose Android App，用捆绑的 ffmpeg/ffprobe 进程完成本机转码，功能和桌面「轻转码」对齐。

**Architecture:** `android/` 下单模块 `app`。纯 Kotlin `domain` 移植预设、校验、命名、探测解析、参数白名单和队列；`engine` 用 `ProcessBuilder` 调 native 库里的 ffmpeg/ffprobe；`data` 处理 SAF/MediaStore；`TranscodeService` 前台服务一次只跑一个任务；Compose 单 Activity 三步界面。

**Tech Stack:** Kotlin 2.0、Jetpack Compose、AGP 8.7、minSdk 29、targetSdk 35、arm64-v8a、DataStore、Media3、JUnit4 单元测试。FFmpeg 来自 `fazi-gondal/ffmpeg` 的 arm64 预编译包（含 libx264 与 MediaCodec），打成 `libffmpeg.so` / `libffprobe.so`。

## Global Constraints

- applicationId `com.videoconverter.android`，应用名「轻转码」
- minSdk 29，targetSdk 35，compileSdk 35，只打 arm64-v8a
- 不复用 Tauri/React/Rust，不上架，不申请 `MANAGE_EXTERNAL_STORAGE`
- 预设 ID 与桌面完全一致；输出命名 `{stem}.{ext}` / `{stem}-1.{ext}`；临时文件 `{stem}.partial.{ext}`
- H.264/H.265 优先 `h264_mediacodec` / `hevc_mediacodec`，失败静默回退 `libx264` / `libx265`
- 转码走前台服务；进程被杀后 `running` → `failed`（「转码被中断」），`queued` 不自动开泵
- 中文文案；不上传；第一版不做 FFmpeg 预览转码、不做 iOS、不做 32 位

## File map

```
android/
  settings.gradle.kts
  build.gradle.kts
  gradle.properties
  gradle/wrapper/gradle-wrapper.properties
  app/build.gradle.kts
  app/src/main/AndroidManifest.xml
  app/src/main/res/values/strings.xml
  app/src/main/java/com/videoconverter/android/
    MainActivity.kt
    domain/Models.kt
    domain/Presets.kt
    domain/Validate.kt
    domain/Naming.kt
    domain/ProbeParser.kt
    domain/Progress.kt
    domain/FfmpegArgs.kt
    domain/Queue.kt
    engine/BinaryLocator.kt
    engine/FfmpegProcess.kt
    data/SessionStore.kt
    data/SourceAccess.kt
    data/OutputStore.kt
    data/JobStore.kt
    service/TranscodeService.kt
    ui/AppViewModel.kt
    ui/AppScreen.kt
    ui/TrimScreen.kt
    ui/theme/Theme.kt
  app/src/test/java/com/videoconverter/android/domain/
  app/src/androidTest/java/com/videoconverter/android/
  scripts/fetch-ffmpeg.mjs
  app/src/main/jniLibs/arm64-v8a/.gitkeep
```

桌面 `src/`、`src-tauri/` 不改。根目录 `.gitignore` 与 `README.md` 只追加 Android 段。

---

### Task 1: Gradle 脚手架 + 领域模型 + 预设解析

**Files:**
- Create: `android/settings.gradle.kts`
- Create: `android/build.gradle.kts`
- Create: `android/gradle.properties`
- Create: `android/gradle/wrapper/gradle-wrapper.properties`
- Create: `android/app/build.gradle.kts`
- Create: `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/res/values/strings.xml`
- Create: `android/app/src/main/java/com/videoconverter/android/domain/Models.kt`
- Create: `android/app/src/main/java/com/videoconverter/android/domain/Presets.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/domain/PresetsTest.kt`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: 无
- Produces: `data class MediaInfo`, `data class OutputConfig`, `data class ResolvedConfig`, `fun resolveConfig(config: OutputConfig): Result<ResolvedConfig>`, `fun listPresets(): List<PresetInfo>`, `const val DEFAULT_PRESET = "mp4-h264"`

- [ ] **Step 1: 写失败测试 `PresetsTest.kt`**

```kotlin
package com.videoconverter.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetsTest {
    @Test
    fun defaultPresetIsMp4H264() {
        val resolved = resolveConfig(OutputConfig()).getOrThrow()
        assertEquals("mp4-h264", resolved.preset)
        assertEquals("mp4", resolved.container)
        assertEquals("h264", resolved.videoEncoder)
        assertEquals("aac", resolved.audioEncoder)
    }

    @Test
    fun listsRequiredPresets() {
        val ids = listPresets().map { it.id }
        listOf(
            "mp4-h264", "mp4-h265", "mp4-copy", "webm-vp9", "mkv-copy-friendly",
            "audio-mp3", "mov-h264", "avi-mpeg4", "gif", "audio-aac", "mkv-h265",
        ).forEach { assertTrue(ids.contains(it)) }
    }

    @Test
    fun qualityDefaultsToStandard() {
        assertEquals("standard", resolveConfig(OutputConfig()).getOrThrow().quality)
    }

    @Test
    fun mp4CopyDoesNotReencode() {
        val resolved = resolveConfig(OutputConfig(preset = "mp4-copy")).getOrThrow()
        assertEquals("copy", resolved.videoEncoder)
        assertEquals("copy", resolved.audioEncoder)
    }
}
```

- [ ] **Step 2: 写 Gradle 脚手架（测试还不能编译）**

`android/settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "qing-zhuama"
include(":app")
```

`android/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
```

`android/gradle.properties`:

```
org.gradle.jvmargs=-Xmx2048m
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

`android/gradle/wrapper/gradle-wrapper.properties`:

```
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.11.1-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

在 `android/` 生成 wrapper（需要本机已装 JDK 17）：

```bash
cd android && gradle wrapper --gradle-version 8.11.1
```

若没有全局 `gradle`，用 Homebrew：`brew install gradle` 后再跑上面命令。必须提交 `gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar`。

`android/app/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.videoconverter.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.videoconverter.android"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
```

`android/app/src/main/AndroidManifest.xml`（先最小，后续任务再加 service/权限）：

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:label="@string/app_name"
        android:extractNativeLibs="true"
        android:supportsRtl="true">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`strings.xml`：`<string name="app_name">轻转码</string>`

空的 `MainActivity.kt`：

```kotlin
package com.videoconverter.android
import android.os.Bundle
import androidx.activity.ComponentActivity
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }
}
```

`.gitignore` 追加：

```
android/.gradle
android/build
android/app/build
android/local.properties
android/app/src/main/jniLibs/arm64-v8a/*
!android/app/src/main/jniLibs/arm64-v8a/.gitkeep
```

- [ ] **Step 3: 实现 `Models.kt` 与 `Presets.kt`**

`Models.kt`:

```kotlin
package com.videoconverter.android.domain

data class MediaInfo(
    val sourceUri: String,
    val displayName: String,
    val durationSecs: Double? = null,
    val container: String? = null,
    val videoCodec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Double? = null,
    val audioCodec: String? = null,
    val channels: Int? = null,
    val importable: Boolean = false,
    val error: String? = null,
    val trimStartSecs: Double? = null,
    val trimEndSecs: Double? = null,
)

data class PresetInfo(val id: String, val label: String, val description: String)

data class OutputConfig(
    val preset: String = DEFAULT_PRESET,
    val container: String? = null,
    val videoEncoder: String? = null,
    val maxWidth: Int? = null,
    val maxHeight: Int? = null,
    val videoBitrateKbps: Int? = null,
    val frameRate: Double? = null,
    val audioEncoder: String? = null,
    val audioBitrateKbps: Int? = null,
    val keepAudio: Boolean? = null,
    val quality: String? = null,
    val trimStartSecs: Double? = null,
    val trimEndSecs: Double? = null,
)

data class ResolvedConfig(
    val preset: String,
    val container: String,
    val extension: String,
    val videoEncoder: String?,
    val audioEncoder: String?,
    val maxWidth: Int?,
    val maxHeight: Int?,
    val videoBitrateKbps: Int?,
    val frameRate: Double?,
    val audioBitrateKbps: Int?,
    val keepAudio: Boolean,
    val quality: String,
    val trimStartSecs: Double?,
    val trimEndSecs: Double?,
)

enum class JobStatus { Queued, Running, Completed, Failed, Cancelled }

data class Job(
    val id: String,
    val sourceUri: String,
    val displayName: String,
    val outputPath: String?,
    val status: JobStatus,
    val progress: Double,
    val error: String?,
    val config: OutputConfig,
    val media: MediaInfo,
)

data class SkippedSource(val sourceUri: String, val displayName: String, val reason: String)

data class EnqueueReport(val jobs: List<Job>, val skipped: List<SkippedSource>)
```

`Presets.kt` 按 `src-tauri/src/presets.rs` 逐条移植 `listPresets`、`resolveConfig`、`normalizeQuality`、`extensionFor`。未知预设返回 `Result.failure(IllegalArgumentException("未知预设：$other"))`。`mp4-copy` 时 `maxWidth`/`maxHeight`/`frameRate` 置 null。音频预设强制清掉视频字段。画质 `high` 归一成 `original`。码率：original=320k、standard=192k、small=128k。

- [ ] **Step 4: 跑单元测试**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests com.videoconverter.android.domain.PresetsTest
```

Expected: `BUILD SUCCESSFUL`，4 tests passed。

- [ ] **Step 5: Commit**

```bash
git add android .gitignore
git commit -m "$(cat <<'EOF'
feat(android): add Gradle app skeleton and preset resolver

EOF
)"
```

---

### Task 2: 白名单校验

**Files:**
- Create: `android/app/src/main/java/com/videoconverter/android/domain/Validate.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/domain/ValidateTest.kt`

**Interfaces:**
- Consumes: `ResolvedConfig`, `MediaInfo`, `resolveConfig`
- Produces: `fun validate(config: ResolvedConfig, media: MediaInfo): Result<Unit>`, `fun containerAcceptsVideo(container: String, codec: String): Boolean`, `fun containerAcceptsAudio(container: String, codec: String): Boolean`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.videoconverter.android.domain

import org.junit.Assert.assertTrue
import org.junit.Test

class ValidateTest {
    private fun h264() = MediaInfo(
        sourceUri = "content://a",
        displayName = "a.mkv",
        durationSecs = 5.0,
        container = "matroska",
        videoCodec = "h264",
        width = 1920,
        height = 1080,
        audioCodec = "aac",
        importable = true,
    )

    @Test
    fun audioPresetWithoutAudioFails() {
        val config = resolveConfig(OutputConfig(preset = "audio-mp3")).getOrThrow()
        val err = validate(config, h264().copy(audioCodec = null)).exceptionOrNull()!!.message!!
        assertTrue(err.contains("没有音频流"))
    }

    @Test
    fun copyVp9IntoMp4IsRejected() {
        val config = resolveConfig(
            OutputConfig(preset = "custom", container = "mp4", videoEncoder = "copy"),
        ).getOrThrow()
        val err = validate(config, h264().copy(videoCodec = "vp9")).exceptionOrNull()!!.message!!
        assertTrue(err.contains("请改为重新编码"))
    }

    @Test
    fun copyH264IntoMp4IsAllowed() {
        val config = resolveConfig(
            OutputConfig(preset = "custom", container = "mp4", videoEncoder = "copy", audioEncoder = "copy"),
        ).getOrThrow()
        assertTrue(validate(config, h264()).isSuccess)
    }
}
```

`Presets.kt` 必须已支持 `custom` → 默认 mp4/h264/aac（与 `presets.rs` 一致），否则第二、三个测试编不过。

- [ ] **Step 2: 跑测试确认失败**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests com.videoconverter.android.domain.ValidateTest
```

Expected: 编译失败（找不到 `validate`）或测试失败。

- [ ] **Step 3: 实现 `Validate.kt`**

逐字移植 `src-tauri/src/args.rs` 的 `validate` / `container_accepts_video` / `container_accepts_audio` / `normalize_codec`。错误文案必须与桌面相同：

- `不支持的容器：…`
- `不支持的视频编码器：…`
- `该文件没有音频流，无法导出音频`
- `该文件没有视频流，无法导出 GIF`
- `该文件没有视频流，无法复制视频`
- `目标容器不支持当前视频编码，请改为重新编码`
- `复制视频流时不能同时修改分辨率或帧率，请改为重新编码`
- `目标容器不支持当前音频编码，请改为重新编码`

`h265` 归一成 `hevc` 再匹配。音频-only 判定：container 为 `mp3`/`m4a` 或 preset 为 `audio-mp3`/`audio-aac`。

- [ ] **Step 4: 跑测试确认通过**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests com.videoconverter.android.domain.ValidateTest
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/videoconverter/android/domain/Validate.kt android/app/src/test/java/com/videoconverter/android/domain/ValidateTest.kt
git commit -m "$(cat <<'EOF'
feat(android): port FFmpeg argument whitelist validation

EOF
)"
```

---

### Task 3: 输出命名

**Files:**
- Create: `android/app/src/main/java/com/videoconverter/android/domain/Naming.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/domain/NamingTest.kt`

**Interfaces:**
- Consumes: 无
- Produces: `fun sourceStem(displayName: String): String`, `fun partialOutputPath(output: String): String`, `fun ffmpegFileArg(path: String): String`, `fun allocateOutputPath(outputDir: String, stem: String, ext: String, exists: (String) -> Boolean): String`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.videoconverter.android.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class NamingTest {
    @Test
    fun uniqueName() {
        assertEquals("/out/clip.mp4", allocateOutputPath("/out", "clip", "mp4") { false })
    }

    @Test
    fun collisionUsesNumericSuffix() {
        assertEquals("/out/clip-1.mp4", allocateOutputPath("/out", "clip", "mp4") { it == "/out/clip.mp4" })
    }

    @Test
    fun collisionSkipsTakenSuffixes() {
        val taken = setOf("/out/clip.mp4", "/out/clip-1.mp4")
        assertEquals("/out/clip-2.mp4", allocateOutputPath("/out", "clip", "mp4") { it in taken })
    }

    @Test
    fun partialKeepsRealExtension() {
        assertEquals(
            "/out/[4K高清]clip_mp4-h264.partial.mp4",
            partialOutputPath("/out/[4K高清]clip_mp4-h264.mp4"),
        )
    }

    @Test
    fun ffmpegFileArgPrefixesLocalPaths() {
        assertEquals("file:/tmp/[4K]clip.mp4", ffmpegFileArg("/tmp/[4K]clip.mp4"))
        assertEquals("pipe:1", ffmpegFileArg("pipe:1"))
        assertEquals("/proc/self/fd/7", ffmpegFileArg("/proc/self/fd/7"))
    }
}
```

`/proc/self/fd/` 与已是 `file:` / `pipe:` 前缀的路径不要再加 `file:`。

- [ ] **Step 2: 跑测试确认失败**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests com.videoconverter.android.domain.NamingTest
```

Expected: 找不到符号。

- [ ] **Step 3: 实现 `Naming.kt`**

移植 `src-tauri/src/naming.rs`。`sourceStem` 取 `displayName` 去掉目录和最后一个扩展名，空则 `"output"`。`allocateOutputPath` 用 `"$outputDir/$stem.$ext"`，冲突 `"$outputDir/$stem-$index.$ext"`。路径拼接用 `"$outputDir/${name}"`，`outputDir` 不要末尾斜杠依赖：实现里 `trimEnd('/')`。

- [ ] **Step 4: 跑测试确认通过**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests com.videoconverter.android.domain.NamingTest
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/videoconverter/android/domain/Naming.kt android/app/src/test/java/com/videoconverter/android/domain/NamingTest.kt
git commit -m "$(cat <<'EOF'
feat(android): port output naming and ffmpeg file: prefix

EOF
)"
```

---

### Task 4: ffprobe JSON 与进度解析

**Files:**
- Create: `android/app/src/main/java/com/videoconverter/android/domain/ProbeParser.kt`
- Create: `android/app/src/main/java/com/videoconverter/android/domain/Progress.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/domain/ProbeParserTest.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/domain/ProgressTest.kt`

**Interfaces:**
- Consumes: `MediaInfo`
- Produces: `fun parseFfprobeJson(sourceUri: String, displayName: String, json: String): MediaInfo`, `fun unreadable(sourceUri: String, displayName: String, reason: String): MediaInfo`, `fun parseProgressLine(line: String, durationSecs: Double): Double?`

- [ ] **Step 1: 写失败测试**

`ProbeParserTest.kt` 移植 `probe.rs` 的两条：可导入的 h264+aac 12.5s 30fps；空 streams → `importable=false` 且 error 含 `"没有可转码"`。JSON 用 `org.json.JSONObject`（Android/Robolectric 单元测试默认有，纯 JVM 用内置字符串解析，**不要**加 Gson。用最小手写解析：找 `format`/`streams` 字段。为了 JVM 单元测试不依赖 Android，解析器用纯 Kotlin/`org.json` 不可用时手写。

本仓库 JVM 测试没有 Android SDK 的 `org.json`。用纯 Kotlin 解析这几个字段：`JSONTokener` 不要。实现一个只覆盖 ffprobe 结构的轻量解析（正则或 `kotlinx` 不要新依赖）：用 `org.json` 通过 `testImplementation` 不稳。

**做法：** `ProbeParser.kt` 用 `org.json.JSONObject`，`app/build.gradle.kts` 的 `testOptions { unitTests.isReturnDefaultValues = true }` 不够。给 `testImplementation("org.json:json:20240303")`。

`ProgressTest.kt`:

```kotlin
@Test
fun progressFromOutTime() {
    val percent = parseProgressLine("out_time_ms=5000000", 10.0)!!
    assertEquals(50.0, percent, 0.01)
}
```

`out_time_ms` 与 `out_time_us` 都按微秒 / 1_000_000。结果 clamp 到 0–100。`durationSecs <= 0` 返回 null。

- [ ] **Step 2: 跑测试确认失败**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests com.videoconverter.android.domain.ProbeParserTest --tests com.videoconverter.android.domain.ProgressTest
```

- [ ] **Step 3: 实现解析器**

`parseFfprobeJson`：JSON 非法 → `unreadable(..., "无法解析媒体信息")`。无 video 且无 audio → error `"没有可转码的视频或音频流"`。`r_frame_rate` 支持 `30/1`。`sourceUri`/`displayName` 原样写入 `MediaInfo`。

- [ ] **Step 4: 跑测试确认通过**

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(android): parse ffprobe JSON and ffmpeg progress lines

EOF
)"
```

---

### Task 5: FFmpeg 参数拼装

**Files:**
- Create: `android/app/src/main/java/com/videoconverter/android/domain/FfmpegArgs.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/domain/FfmpegArgsTest.kt`

**Interfaces:**
- Consumes: `validate`, `ffmpegFileArg`, `ResolvedConfig`, `MediaInfo`
- Produces: `fun buildFfmpegArgs(input: String, outputPartial: String, config: ResolvedConfig, media: MediaInfo, preferHardware: Boolean = true): Result<List<String>>`, `fun outputDurationSecs(config: ResolvedConfig, media: MediaInfo): Double`, `fun ffmpegVideoCodec(encoder: String, preferHardware: Boolean): String`

- [ ] **Step 1: 写失败测试（对齐 `args.rs` 测试，编码器改 Android）**

```kotlin
private fun h264() = MediaInfo(
    sourceUri = "content://a", displayName = "a.mkv", durationSecs = 5.0,
    container = "matroska", videoCodec = "h264", width = 1920, height = 1080,
    audioCodec = "aac", importable = true,
)

@Test
fun hardwareH264UsesMediacodec() {
    val config = resolveConfig(OutputConfig()).getOrThrow()
    val args = buildFfmpegArgs("/tmp/a.mkv", "/tmp/a.partial.mp4", config, h264(), preferHardware = true).getOrThrow()
    val i = args.indexOf("-c:v")
    assertEquals("h264_mediacodec", args[i + 1])
}

@Test
fun softwareFallbackUsesLibx264() {
    val config = resolveConfig(OutputConfig()).getOrThrow()
    val args = buildFfmpegArgs("/tmp/a.mkv", "/tmp/a.partial.mp4", config, h264(), preferHardware = false).getOrThrow()
    val i = args.indexOf("-c:v")
    assertEquals("libx264", args[i + 1])
    assertEquals("16", args[args.indexOf("-crf") + 1]) // original quality not set; default standard is 23
}

@Test
fun standardQualitySoftwareCrfIs23() {
    val config = resolveConfig(OutputConfig()).getOrThrow()
    val args = buildFfmpegArgs("/in", "/out.partial.mp4", config, h264(), preferHardware = false).getOrThrow()
    assertEquals("23", args[args.indexOf("-crf") + 1])
}

@Test
fun gifPresetHasFpsFilter() { /* args contains gif; -vf starts with fps= */ }

@Test
fun argsAreArgvNotShellString() {
    val args = buildFfmpegArgs("/tmp/my file.mp4", "/tmp/out.partial", resolveConfig(OutputConfig()).getOrThrow(), h264()).getOrThrow()
    assertTrue(args.any { it == "file:/tmp/my file.mp4" })
    assertTrue(args.none { it.contains("ffmpeg ") })
}

@Test
fun outputUsesMuxerAndFileProtocol() { /* -i file:/tmp/[4K]a.mp4 ; -f mp4 ; file:/tmp/[4K]a.partial.mp4 */ }

@Test
fun trimAddsSeekAndDuration() { /* reencode: -ss after -i, 1.000 / 2.000 */ }

@Test
fun copyTrimSeeksBeforeInput() { /* mp4-copy */ }

@Test
fun longReencodeTrimUsesHybridSeek() { /* start 10, end 12, duration 30 → 8.500 then 1.500 */ }

@Test
fun vp9UsesFasterDeadline() { /* libvpx-vp9, -row-mt, good, -cpu-used */ }

@Test
fun copyIncompatibleAudioFallsBackToAac() { /* video copy, audio aac */ }

@Test
fun fullClipOmitsTrim() { /* no -ss -t */ }
```

`standardQualitySoftwareCrfIs23` 以桌面 `video_quality_args` 为准：h264 standard crf 23，original 16，small 28。上面 `softwareFallbackUsesLibx264` 不要断言 crf 16。

硬件路径质量：mediacodec 不支持 CRF。`preferHardware=true` 且 encoder 为 h264/h265 时用 `-b:v`：original `8000k`，standard `4000k`，small `1500k`（若 `videoBitrateKbps` 有值则用该值）。另加 `-pix_fmt yuv420p`；h265 + mp4/mov 加 `-tag:v hvc1`。

- [ ] **Step 2: 跑测试确认失败**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests com.videoconverter.android.domain.FfmpegArgsTest
```

- [ ] **Step 3: 实现 `FfmpegArgs.kt`**

移植 `build_ffmpeg_args`、`start_args`、`trim_window`（阈值 0.05 与 1.5s hybrid seek）、`push_output`、`ffmpeg_muxer`（mkv→matroska，m4a→ipod）、`effective_audio_encoder`、`gif_filter`、`scale_filter`（含 even 的 `trunc(iw/2)*2`）。开头固定：

```
-hide_banner -nostats -progress pipe:1 -y
```

`ffmpegVideoCodec`: h264 + preferHardware → `h264_mediacodec`；h265 + preferHardware → `hevc_mediacodec`；否则 libx264 / libx265 / libvpx-vp9 / mpeg4 / gif / copy。音频：aac / libopus / libmp3lame / copy。

先 `validate`，失败则 `Result.failure`。

- [ ] **Step 4: 跑测试确认通过**

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(android): build whitelisted ffmpeg argv with mediacodec fallback

EOF
)"
```

---

### Task 6: 入队与任务状态（纯逻辑）

**Files:**
- Create: `android/app/src/main/java/com/videoconverter/android/domain/Queue.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/domain/QueueTest.kt`

**Interfaces:**
- Consumes: `validate`, `resolveConfig`, `allocateOutputPath`, `partialOutputPath`, `sourceStem`, `MediaInfo`, `OutputConfig`
- Produces: `fun splitImportable(sources: List<MediaInfo>): Pair<List<MediaInfo>, List<SkippedSource>>`, `fun configForSource(config: OutputConfig, media: MediaInfo): OutputConfig`, `fun enqueueJobs(sources: List<MediaInfo>, config: OutputConfig, outputDir: String, nextId: () -> String, exists: (String) -> Boolean): Result<EnqueueReport>`, `fun markInterrupted(jobs: List<Job>): List<Job>`

- [ ] **Step 1: 写失败测试**

```kotlin
@Test
fun skipsNonImportableSources() {
    val (ok, skipped) = splitImportable(listOf(
        MediaInfo("content://a", "a.mp4", importable = true),
        MediaInfo("content://b", "b.bin", importable = false, error = "无法读取"),
    ))
    assertEquals(1, ok.size)
    assertEquals(1, skipped.size)
    assertEquals("无法读取", skipped[0].reason)
}

@Test
fun enqueueAllocatesUniqueNames() {
    val media = MediaInfo("content://a", "clip.mp4", durationSecs = 5.0, videoCodec = "h264", audioCodec = "aac", importable = true)
    val report = enqueueJobs(listOf(media, media.copy(sourceUri = "content://b")), OutputConfig(), "/out", { java.util.concurrent.atomic.AtomicInteger(1).let { "job-${it.getAndIncrement()}" } }, { false }).getOrThrow()
    // 上面 nextId 有误：两个 job 会共用同一个 AtomicInteger 才对
}

@Test
fun markInterruptedConvertsRunningToFailed() {
    val running = Job("job-1", "content://a", "a.mp4", "/out/a.mp4", JobStatus.Running, 40.0, null, OutputConfig(), MediaInfo("content://a", "a.mp4"))
    val queued = running.copy(id = "job-2", status = JobStatus.Queued, progress = 0.0)
    val next = markInterrupted(listOf(running, queued))
    assertEquals(JobStatus.Failed, next[0].status)
    assertEquals("转码被中断", next[0].error)
    assertEquals(JobStatus.Queued, next[1].status)
}
```

修正 `enqueueAllocatesUniqueNames`：同一个 `AtomicInteger` 闭包生成 `job-1`、`job-2`；两个同源 stem 的输出为 `/out/clip.mp4` 与 `/out/clip-1.mp4`。`exists` 必须把已经分配的路径和对应 partial 视为占用（`path_or_partial_exists`）。

空 `outputDir` → failure `"请先选择输出目录"`。校验失败进 `skipped`，不进 jobs。

- [ ] **Step 2: 跑测试确认失败**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests com.videoconverter.android.domain.QueueTest
```

- [ ] **Step 3: 实现 `Queue.kt`**

`enqueueJobs`：`resolveConfig` → `splitImportable` → 对每个 accepted `validate` → `configForSource`（媒体上的 trim 覆盖 config）→ `allocateOutputPath(outputDir, sourceStem(displayName), extension, exists)`。`exists` 由调用方提供；enqueue 内部在分配后要把新路径记入本地 set，后续 `exists` 为 `{ callerExists(p) || allocated.contains(p) }`。

`markInterrupted`：仅 `Running` 改为 `Failed` + `"转码被中断"`，其它状态不变。

- [ ] **Step 4: 跑测试确认通过**

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(android): port job enqueue, skip report, and interrupt recovery

EOF
)"
```

---

### Task 7: 拉取 Android FFmpeg 二进制

**Files:**
- Create: `android/scripts/fetch-ffmpeg.mjs`
- Create: `android/app/src/main/jniLibs/arm64-v8a/.gitkeep`
- Create: `android/app/src/main/java/com/videoconverter/android/engine/BinaryLocator.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/engine/BinaryLocatorTest.kt`
- Modify: `README.md`

**Interfaces:**
- Consumes: 无
- Produces: `fun nativeBinary(nativeLibraryDir: File, name: String): File`（`name` 为 `"ffmpeg"` 或 `"ffprobe"`，解析 `libffmpeg.so` / `libffprobe.so`）

- [ ] **Step 1: 下载预编译包并记下 sha256**

```bash
curl -L -o /tmp/ffmpeg-android-arm64-v8a.tar.gz \
  https://github.com/fazi-gondal/ffmpeg/releases/download/latest/ffmpeg-android-arm64-v8a.tar.gz
shasum -a 256 /tmp/ffmpeg-android-arm64-v8a.tar.gz
tar -tzf /tmp/ffmpeg-android-arm64-v8a.tar.gz | head
```

Expected: 得到 64 位 hex；归档内能看到名为 `ffmpeg` 与 `ffprobe` 的可执行文件（路径可能在 `bin/`）。把 hex 写进下一步脚本常量 `EXPECTED_SHA256`。若归档没有独立 CLI，停下来换源，不要改用 FFmpeg Kit Java API。

- [ ] **Step 2: 写 `fetch-ffmpeg.mjs`**

用 Node `https`/`fs`/`crypto`/`zlib`/`child_process`（`tar -xzf`）下载到 `android/.ffmpeg-cache/`，校验 sha256，解压后递归找文件名恰好为 `ffmpeg`、`ffprobe` 的文件，复制到 `android/app/src/main/jniLibs/arm64-v8a/libffmpeg.so` 与 `libffprobe.so`，`chmod 755`。校验失败则 `process.exit(1)`。

- [ ] **Step 3: 跑脚本**

```bash
node android/scripts/fetch-ffmpeg.mjs
ls -l android/app/src/main/jniLibs/arm64-v8a/
```

Expected: 两个 `.so` 文件存在且可执行。

- [ ] **Step 4: `BinaryLocator` + 测试**

```kotlin
fun nativeBinary(nativeLibraryDir: File, name: String): File {
    val file = File(nativeLibraryDir, "lib$name.so")
    if (!file.isFile) throw IllegalStateException("找不到打包的 $name。请先运行 node android/scripts/fetch-ffmpeg.mjs")
    return file
}
```

测试用临时目录放假文件 `libffmpeg.so`，断言返回该路径；缺文件则消息含 `"找不到打包的 ffmpeg"`。

- [ ] **Step 5: README 追加「Android」节**

写明：JDK 17、Android SDK 35、`node android/scripts/fetch-ffmpeg.mjs`、`cd android && ./gradlew assembleDebug`、产物 `app/build/outputs/apk/debug/app-debug.apk`、仅 arm64、侧载、FFmpeg GPL 与桌面相同需自行确认。根 README 安装包表里 Android 一行改为有 APK（debug 侧载）。

- [ ] **Step 6: Commit（不要提交 `.so` 和 cache）**

```bash
git add android/scripts/fetch-ffmpeg.mjs android/app/src/main/jniLibs/arm64-v8a/.gitkeep android/app/src/main/java/com/videoconverter/android/engine/BinaryLocator.kt android/app/src/test/java/com/videoconverter/android/engine/BinaryLocatorTest.kt README.md
git commit -m "$(cat <<'EOF'
feat(android): fetch bundled ffmpeg/ffprobe native binaries

EOF
)"
```

---

### Task 8: 源文件 fd/缓存 与输出目录

**Files:**
- Create: `android/app/src/main/java/com/videoconverter/android/data/SourceAccess.kt`
- Create: `android/app/src/main/java/com/videoconverter/android/data/OutputStore.kt`
- Create: `android/app/src/main/java/com/videoconverter/android/data/SessionStore.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/data/OutputStoreTest.kt`

**Interfaces:**
- Consumes: `partialOutputPath`, `allocateOutputPath`
- Produces:
  - `suspend fun resolveInput(uri: Uri): ResolvedInput` where `data class ResolvedInput(val ffmpegPath: String, val pfd: ParcelFileDescriptor?)`
  - `fun defaultRelativePath(): String` = `"Download/轻转码"`
  - `data class OutputTarget(val kind: Kind, val treeUri: String?)` `enum class Kind { Downloads, SafTree, AppExternal }`
  - `data class SessionSettings(val preset: String?, val quality: String?, val maxWidth: Int?, val maxHeight: Int?, val output: OutputTarget)`

- [ ] **Step 1: 写 `OutputStore` 纯逻辑测试**（不碰 ContentResolver）

把「最终文件从 app 目录拷到用户可见位置」拆成可测函数：

```kotlin
fun uniqueDisplayName(stem: String, ext: String, existing: Set<String>): String
```

与 `allocateOutputPath` 同一规则，existing 是已有文件名集合。测试 `clip.mp4` 冲突 → `clip-1.mp4`。

- [ ] **Step 2: 实现 `SourceAccess.resolveInput`**

顺序：`contentResolver.openFileDescriptor(uri, "r")` → ffmpegPath = `"/proc/self/fd/${pfd.fd}"`。调用方在 ffprobe 失败且 stderr/错误表明不可 seek 时，再 `copyToCache`：写到 `context.cacheDir/sources/<hash>-<displayName>`，ffmpegPath 为该绝对路径，并 `pfd.close()`。空间不足抛中文 `"缓存空间不足"`。

- [ ] **Step 3: 实现 `OutputStore`**

转码始终写到 `context.filesDir/jobs/{jobId}/{stem}.partial.{ext}`，成功改名为同目录 `{stem}.{ext}`。然后：

1. `OutputTarget.Kind.Downloads`：`MediaStore.Downloads`，`RELATIVE_PATH = "Download/轻转码/"`，`IS_PENDING` 1→0。
2. `Kind.SafTree`：`DocumentFile.fromTreeUri`，没有写权限 → 错误 `"无法写入输出目录，请重新选择"`。
3. Downloads 插入失败 → 写 `context.getExternalFilesDir(null)/轻转码/`，并把实际目录记到 session，供 UI 提示。

不删除源文件。

- [ ] **Step 4: `SessionStore`**

DataStore preferences：`preset`、`quality`、`maxWidth`、`maxHeight`、`outputKind`、`outputTreeUri`。默认 preset `mp4-h264`、quality `standard`、Downloads。

- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(android): resolve SAF inputs and MediaStore/SAF outputs

EOF
)"
```

---

### Task 9: FFmpeg 进程、前台服务、单任务泵

**Files:**
- Create: `android/app/src/main/java/com/videoconverter/android/engine/FfmpegProcess.kt`
- Create: `android/app/src/main/java/com/videoconverter/android/data/JobStore.kt`
- Create: `android/app/src/main/java/com/videoconverter/android/service/TranscodeService.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`
- Test: `android/app/src/test/java/com/videoconverter/android/engine/FfmpegProcessTest.kt`（进程封装可用假二进制时再跑仪器测试；本任务 JVM 测「mediacodec 错误应重试软件编码」的判定函数）

**Interfaces:**
- Consumes: `buildFfmpegArgs`, `parseProgressLine`, `outputDurationSecs`, `nativeBinary`, `resolveInput`, `OutputStore`, `enqueueJobs`, `markInterrupted`
- Produces: `fun shouldRetryWithoutHardware(stderr: String): Boolean`, `class TranscodeService : Service()` 的 action：`enqueue`、`startPump`、`cancel(jobId)`、`retry(jobId)`、`clearFinished`

- [ ] **Step 1: 写 `shouldRetryWithoutHardware` 测试**

```kotlin
@Test
fun mediacodecFailureRetries() {
    assertTrue(shouldRetryWithoutHardware("Error while opening encoder: h264_mediacodec"))
    assertFalse(shouldRetryWithoutHardware("Invalid data found when processing input"))
}
```

判定：stderr 忽略大小写包含 `mediacodec` 且包含 `error`/`fail`/`not found`/`cannot` 之一。

- [ ] **Step 2: 实现 `FfmpegProcess`**

`ProcessBuilder(ffmpegPath, *args)`，`environment()["PATH"]="/system/bin:/vendor/bin"`，stdout 读 `-progress`，stderr 收集。成功：rename partial→final，再交给 `OutputStore.export`。失败：删 partial。取消：`process.destroy()` 再 `destroyForcibly()`，删 partial，状态 `Cancelled`。

同一 job 若 `preferHardware=true` 失败且 `shouldRetryWithoutHardware`，用 `preferHardware=false` 再跑一次，不把第一次错误显示给用户。

ffprobe：`listOf("-v","error","-show_format","-show_streams","-print_format","json", ffmpegFileArg(input))`。

- [ ] **Step 3: `JobStore` + 服务泵**

`JobStore` 把 `List<Job>` 序列化到 `filesDir/jobs.json`（kotlinx 不要；用手写 JSON 或 `org.json`）。`MainActivity.onCreate` 读出后 `markInterrupted` 再写回，**不** startService。

`TranscodeService`：`START_STICKY` 不要（避免悄悄恢复）。`startForeground` 通知标题「轻转码」，内容 `「$displayName · ${progress.toInt()}%」`。API 35+ `FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING`，否则 `FOREGROUND_SERVICE_TYPE_DATA_SYNC`。一次只取第一个 `Queued` 跑。队列空则 `stopForeground` + `stopSelf`。

Manifest 增加：

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />

<service
    android:name=".service.TranscodeService"
    android:exported="false"
    android:foregroundServiceType="dataSync|mediaProcessing" />
```

不声明 `READ_MEDIA_VIDEO` / `MANAGE_EXTERNAL_STORAGE`。通知权限被拒仍继续转码。

- [ ] **Step 4: 用假 ffmpeg 做 JVM 测试（可选路径）**

若环境无 Android SDK 设备，本步至少保证 `shouldRetryWithoutHardware` 的 JVM 测试通过：

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests com.videoconverter.android.engine.FfmpegProcessTest
```

- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(android): run one ffmpeg job at a time in a foreground service

EOF
)"
```

---

### Task 10: Compose 三步界面

**Files:**
- Create: `android/app/src/main/java/com/videoconverter/android/ui/theme/Theme.kt`
- Create: `android/app/src/main/java/com/videoconverter/android/ui/AppViewModel.kt`
- Create: `android/app/src/main/java/com/videoconverter/android/ui/AppScreen.kt`
- Create: `android/app/src/main/java/com/videoconverter/android/ui/TrimScreen.kt`
- Modify: `android/app/src/main/java/com/videoconverter/android/MainActivity.kt`

**Interfaces:**
- Consumes: `listPresets`、`SessionStore`、`enqueueJobs`、`TranscodeService`、`MediaInfo`
- Produces: 可安装的 debug APK 主界面

- [ ] **Step 1: `Theme.kt`**

Material 3 `dynamicDark`/`dynamicLight`（API 31+），否则默认配色。中文不强制字体。

- [ ] **Step 2: `AppViewModel`**

状态：`sources: List<MediaInfo>`（探测中 `error==null && !importable` 且 duration 空时可另用 `probing`：给 `MediaInfo` 增加可选不必要，探测中用 `error=null, importable=false, container=null` 并在 UI 用本地 `Set<String>` 记 probing URI）。更干净：ViewModel 里 `data class SourceItem(val media: MediaInfo, val probing: Boolean)`。

动作：`addUris(List<Uri>)` 并发 4 个 ffprobe；`remove(uri)` 仅当没有该 uri 的 `Running` job；`updateTrim`；`setPreset/quality/size`；`pickOutputTree`；`start()`：若已有 queued 且 sources 未变可只 `startPump`；若当前 sources 要新入队则 `enqueueJobs` 再 startService。`cancel`/`retry`/`clearFinished`。完成项暴露打开/分享 Intent。

记住设置：preset、quality、maxWidth/Height、output target。

- [ ] **Step 3: `AppScreen.kt` 布局（对齐桌面三步）**

顶栏「轻转码」+ 副文案「不上传 · 不联网」。

1. 「添加视频」按钮：相册 `PickMultipleVisualMedia` + 文件 `OpenMultipleDocuments`（`video/*`）。卡片：`displayName` + `容器 · 编码 · 宽×高 · fps · 时长`；探测中「正在读取格式…」；失败显示 `error`。未在转码显示移除。点击进入 `TrimScreen`。
2. 预设：默认四张 `mp4-h264` `mp4-copy` `mp4-h265` `mov-h264`，按钮「更多」展开其余。画质三档、分辨率四档；`audio-*` 只显示音质；`mp4-copy` 隐藏分辨率。
3. 「输出到」一行（默认「下载/轻转码」或 SAF 显示名）。任务列表：状态中文「排队中/正在转码/已完成/出错了/已取消」，进度条，取消/重试，清除已结束，打开、分享。

底部固定 Button「开始转码」。无拖放。

- [ ] **Step 4: `TrimScreen.kt`**

系统能播（扩展名 mp4/m4v/mov/webm）用 Media3 `PlayerView` + 时间轴；否则仅时间轴。「设为起点」「设为终点」。第一版不调用 FFmpeg 做预览片。保存 trim 写回 `MediaInfo.trimStartSecs/trimEndSecs`。

- [ ] **Step 5: 接线 `MainActivity`**

`setContent { 轻转码Theme { AppScreen() } }`。启动时 `JobStore.load` + `markInterrupted`。请求 `POST_NOTIFICATIONS` 在第一次点「开始转码」时进行，拒绝不阻断。

- [ ] **Step 6: 组装 debug APK**

```bash
node android/scripts/fetch-ffmpeg.mjs
cd android && ./gradlew assembleDebug
```

Expected: `app/build/outputs/apk/debug/app-debug.apk`。安装到 arm64 设备或模拟器后能看到三步界面。

- [ ] **Step 7: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(android): add Compose import, preset, trim, and job UI

EOF
)"
```

---

### Task 11: 仪器测试与冒烟清单

**Files:**
- Create: `android/app/src/androidTest/java/com/videoconverter/android/EnqueueFlowTest.kt`
- Create: `android/app/src/androidTest/assets/tiny.mp4`（用仓库内测试夹或测试里动态生成）
- Modify: `README.md`（冒烟步骤）

**Interfaces:**
- Consumes: `enqueueJobs`、`FfmpegProcess`、应用 context
- Produces: 可在连接设备时运行的仪器测试

- [ ] **Step 1: 生成极小测试视频（构建时，不入库大文件）**

仪器测试 `setUp`：若 `jniLibs` 的 ffmpeg 在测试进程里可用，用 bundled ffmpeg 生成 1 秒 320x240 彩条 `tiny.mp4` 到 app filesDir。若仪器测试拿不到 nativeLibraryDir 里的二进制，改为把 `tiny.mp4` 作为 `androidTest/assets`——用桌面已有 ffmpeg 在开发机生成一次：

```bash
ffmpeg -f lavfi -i color=c=black:s=320x240:d=1 -c:v libx264 -pix_fmt yuv420p -t 1 android/app/src/androidTest/assets/tiny.mp4
```

该 mp4 很小，可提交。

- [ ] **Step 2: `EnqueueFlowTest`**

把 asset 拷到 cache，构造 `MediaInfo`（可先 ffprobe），`enqueueJobs` → 跑一个 job（直接调 `FfmpegProcess`，不必起 UI）→ 取消路径：启动后立刻 cancel，状态 `Cancelled` 且无残留 `.partial.`。重试失败任务：先用错误 outputDir 失败，再改有效目录 retry。断言默认导出名无冲突时为 `tiny.mp4`，再入队一次出现 `tiny-1.mp4`。

命令：

```bash
cd android && ./gradlew connectedDebugAndroidTest
```

无设备时不要假装通过：在 README 写明必须连接 arm64 设备/模拟器。本机只保证 JVM：

```bash
cd android && ./gradlew :app:testDebugUnitTest
```

Expected: 全部 JVM 测试 PASS。

- [ ] **Step 3: README 冒烟清单**

真机：MP4 H.264 全流程；`mp4-copy`（H.264/AAC 源）；抽音频；裁切。系统 PATH 无 ffmpeg。完成后原片仍在。

- [ ] **Step 4: Commit**

```bash
git commit -m "$(cat <<'EOF'
test(android): add enqueue/cancel/retry instrumentation and smoke notes

EOF
)"
```

---

## Self-review vs spec

| Spec 项 | Task |
| --- | --- |
| Kotlin 原生、不复用 Tauri | 1 |
| 预设 ID / resolve / custom | 1 |
| 白名单校验 | 2 |
| `{stem}.{ext}` / partial / `file:` | 3 |
| ffprobe JSON、进度微秒 | 4 |
| 全预设 argv、mediacodec 回退 | 5 |
| 入队跳过、中断恢复不自动开泵 | 6, 9 |
| 捆绑 ffmpeg 脚本、arm64 .so | 7 |
| fd→缓存、Downloads/SAF、不删原片 | 8 |
| 前台服务、一进程、取消删 partial | 9 |
| 三步 UI、裁剪不做预览转码、记住设置 | 10 |
| JVM + 仪器 + 冒烟 | 11 |
| 不上架、无 MANAGE_EXTERNAL_STORAGE、无 32 位、无 iOS | 全局 / Manifest |

无 TBD。FFmpeg 包的 sha256 在 Task 7 Step 1 下载后写入脚本，不在计划里写假哈希。
