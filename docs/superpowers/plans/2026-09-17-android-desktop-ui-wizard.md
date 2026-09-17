# Android 桌面风格引导式界面 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 Android「轻转码」界面改成桌面同款浅色外观，并做成添加 → 格式 → 存放 的三步引导。

**Architecture:** 转码引擎、ViewModel 业务 API 不动。新增无 Android 依赖的 `Wizard.kt` 管步骤可否前进、预设折叠和 dock 文案；`theme` 关掉动态取色并锁死桌面色值；Compose 按三步组装，裁剪内联在第一步，删除独立 `TrimScreen`。

**Tech Stack:** Kotlin、Jetpack Compose、现有 JUnit4 / Robolectric。不新增依赖。

## Global Constraints

- 只改 `android/app/src/main/java/com/videoconverter/android/ui/`（含 `theme/`）和为窗口颜色服务的 Activity/Theme；不改 `domain` / `engine` / `data` / `TranscodeService` 语义
- 固定浅色：页面底 `#ecece8`，墨色 `#1f2428`，次要字 `#5c6460`，卡片 `#f7f7f4`，边框 `#e4e4de`，主色 `#c45a2a`，控件底 `#e2e2dc`；禁止 `dynamicLightColorScheme` / `dynamicDarkColorScheme`，不跟随系统深色模式
- 三步：1 添加文件（预览+裁切）→ 2 目标格式 → 3 存放位置；无导入文件不能进第 2 / 3 步；「开始转码」只在第 3 步
- 中文文案以桌面 `src/App.tsx` 为准；相册 / 文件 / 分享 / 通知权限保持现 Android 行为
- 不用 WebView，不用 FFmpeg 生成预览片
- 现有 JVM 单元测试必须继续通过
- JDK 17：`JAVA_HOME=/Users/wuyu/.sdkman/candidates/java/17.0.11-amzn`，`ANDROID_HOME=/Users/wuyu/Library/Android/sdk`

## File map

```
android/app/src/main/java/com/videoconverter/android/ui/
  Wizard.kt              # 新建：步骤、预设折叠、文案、裁剪数值
  TrimPanel.kt           # 新建：第一步预览 + 时间轴（原 TrimScreen 能力）
  AppScreen.kt           # 重写：三步壳 + 组装
  WizardComponents.kt    # 新建：顶栏、步骤块、dock、文件行、预设卡、chip、任务行
  theme/Tokens.kt        # 新建：色值常量
  theme/Theme.kt         # 修改：固定浅色，去掉动态取色
  TrimScreen.kt          # 删除
android/app/src/test/java/com/videoconverter/android/ui/
  WizardTest.kt          # 新建
  TokensTest.kt          # 新建
  AppViewModelTest.kt    # 不改（现有断言仍有效）
```

桌面 `src/` 不改。

---

### Task 1: 三步引导纯函数

**Files:**
- Create: `android/app/src/main/java/com/videoconverter/android/ui/Wizard.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/ui/WizardTest.kt`

**Interfaces:**
- Consumes: `com.videoconverter.android.domain.MediaInfo`、`JobStatus`
- Produces:
  - `enum class WizardStep { Sources, Format, Output }`
  - `data class WizardPresetCard(val id: String, val title: String, val hint: String, val badge: String? = null)`
  - `val WIZARD_PRESET_CARDS: List<WizardPresetCard>`
  - `val PRIMARY_PRESET_IDS: List<String>`
  - `fun canEnterStep(step: WizardStep, importableCount: Int): Boolean`
  - `fun advanceStep(current: WizardStep, importableCount: Int): WizardStep?`
  - `fun retreatStep(current: WizardStep): WizardStep?`
  - `fun collapsedPresetCards(selectedId: String, showAll: Boolean): List<WizardPresetCard>`
  - `fun dockActionLabel(step: WizardStep, busy: Boolean, transcoding: Boolean): String`
  - `fun dockSummary(step: WizardStep, importableCount: Int, presetTitle: String, qualityLabel: String, sizeLabel: String, audioOnly: Boolean, copyOnly: Boolean, trimLabel: String, outputLabel: String): String`
  - `fun conversionPreview(items: List<MediaInfo>, target: String): String`
  - `fun presetTitle(id: String): String`
  - `fun qualityLabel(id: String): String`
  - `fun sizeLabel(id: String): String`
  - `fun isAudioPreset(preset: String): Boolean`
  - `fun isCopyPreset(preset: String): Boolean`
  - `fun itemHasDuration(media: MediaInfo): Boolean`
  - `fun isTrimmed(media: MediaInfo): Boolean`
  - `fun timeAt(x: Float, width: Float, duration: Double): Double`
  - `fun clampTrim(start: Double, end: Double, duration: Double): Pair<Double, Double>`
  - `fun outputFileName(outputPath: String?): String`

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/videoconverter/android/ui/WizardTest.kt`:

```kotlin
package com.videoconverter.android.ui

import com.videoconverter.android.domain.MediaInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WizardTest {
    @Test
    fun cannotLeaveSourcesWithoutImportableFile() {
        assertFalse(canEnterStep(WizardStep.Format, 0))
        assertFalse(canEnterStep(WizardStep.Output, 0))
        assertTrue(canEnterStep(WizardStep.Sources, 0))
        assertNull(advanceStep(WizardStep.Sources, 0))
        assertEquals(WizardStep.Format, advanceStep(WizardStep.Sources, 1))
        assertEquals(WizardStep.Output, advanceStep(WizardStep.Format, 1))
        assertNull(advanceStep(WizardStep.Output, 1))
        assertEquals(WizardStep.Sources, retreatStep(WizardStep.Format))
        assertEquals(WizardStep.Format, retreatStep(WizardStep.Output))
        assertNull(retreatStep(WizardStep.Sources))
    }

    @Test
    fun collapsedPresetsKeepPrimaryAndSwapFourthWhenNeeded() {
        val primary = collapsedPresetCards("mp4-h264", showAll = false).map { it.id }
        assertEquals(listOf("mp4-h264", "mp4-copy", "mp4-h265", "mov-h264"), primary)
        val withGif = collapsedPresetCards("gif", showAll = false).map { it.id }
        assertEquals(listOf("mp4-h264", "mp4-copy", "mp4-h265", "gif"), withGif)
        assertTrue(collapsedPresetCards("mp4-h264", showAll = true).size >= 11)
    }

    @Test
    fun dockLabelsFollowStepAndBusyState() {
        assertEquals("下一步", dockActionLabel(WizardStep.Sources, busy = false, transcoding = false))
        assertEquals("下一步", dockActionLabel(WizardStep.Format, busy = false, transcoding = false))
        assertEquals("开始转码", dockActionLabel(WizardStep.Output, busy = false, transcoding = false))
        assertEquals("正在加入队列…", dockActionLabel(WizardStep.Output, busy = true, transcoding = false))
        assertEquals("正在转码…", dockActionLabel(WizardStep.Output, busy = false, transcoding = true))
    }

    @Test
    fun dockSummaryAndConversionPreviewMatchDesktopSentences() {
        assertEquals("先添加源视频", dockSummary(WizardStep.Sources, 0, "MP4 · H.264", "标准", "原尺寸", false, false, "", "下载/轻转码"))
        assertEquals("已选 2 个文件", dockSummary(WizardStep.Sources, 2, "MP4 · H.264", "标准", "原尺寸", false, false, "", "下载/轻转码"))
        val mp4 = media("a.mp4", "H.264", importable = true)
        assertEquals("MP4 · H.264  →  MP4 · H.265", conversionPreview(listOf(mp4), "MP4 · H.265"))
        assertEquals(
            "将 2 个视频转为 MP4 · H.264 · 标准 · 1080p · 存到下载/轻转码",
            dockSummary(WizardStep.Output, 2, "MP4 · H.264", "标准", "1080p", false, false, "", "下载/轻转码"),
        )
        assertEquals(
            "将 1 个视频转为 MP4 · 不重编码 · 存到下载/轻转码",
            dockSummary(WizardStep.Output, 1, "MP4 · 不重编码", "标准", "原尺寸", false, true, "", "下载/轻转码"),
        )
        assertEquals(
            "将 1 个文件转为 MP3 · 原画 · 存到下载/轻转码",
            dockSummary(WizardStep.Output, 1, "MP3", "原画", "原尺寸", true, false, "", "下载/轻转码"),
        )
    }

    @Test
    fun copyPresetHidesResolutionViaExistingHelper() {
        assertTrue(isCopyPreset("mp4-copy"))
        assertTrue(isAudioPreset("audio-mp3"))
        assertFalse(shouldShowResolution("mp4-copy"))
        assertEquals("MP4 · 不重编码", presetTitle("mp4-copy"))
        assertEquals("原画", qualityLabel("original"))
        assertEquals("原尺寸", sizeLabel("original"))
    }

    @Test
    fun trimMathClampsToDuration() {
        assertTrue(itemHasDuration(media("a.mp4", "H.264", duration = 10.0, importable = true)))
        assertFalse(isTrimmed(media("a.mp4", "H.264", duration = 10.0, importable = true)))
        assertTrue(isTrimmed(media("a.mp4", "H.264", duration = 10.0, importable = true, trimStart = 1.0)))
        assertEquals(5.0, timeAt(50f, 100f, 10.0), 0.001)
        val clamped = clampTrim(9.9, 10.0, 10.0)
        assertTrue(clamped.second - clamped.first >= 0.2 - 1e-6)
        assertEquals("out.mp4", outputFileName("content://x/out.mp4"))
        assertEquals("未命名", outputFileName(null))
    }

    private fun media(
        name: String,
        codecLabel: String,
        duration: Double? = 8.0,
        importable: Boolean = true,
        trimStart: Double? = null,
    ) = MediaInfo(
        sourceUri = "content://$name",
        displayName = name,
        durationSecs = duration,
        container = "mov,mp4,m4a,3gp,3g2,mj2",
        videoCodec = if (codecLabel == "H.264") "h264" else "hevc",
        importable = importable,
        trimStartSecs = trimStart,
    )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
export JAVA_HOME=/Users/wuyu/.sdkman/candidates/java/17.0.11-amzn
export ANDROID_HOME=/Users/wuyu/Library/Android/sdk
cd /Users/wuyu/Projects/video-converter/android
./gradlew :app:testDebugUnitTest --tests com.videoconverter.android.ui.WizardTest
```

Expected: FAIL，找不到 `WizardStep` / `canEnterStep` 等符号。

- [ ] **Step 3: Write minimal implementation**

Create `android/app/src/main/java/com/videoconverter/android/ui/Wizard.kt`:

```kotlin
package com.videoconverter.android.ui

import com.videoconverter.android.domain.MediaInfo

enum class WizardStep { Sources, Format, Output }

data class WizardPresetCard(
    val id: String,
    val title: String,
    val hint: String,
    val badge: String? = null,
)

val PRIMARY_PRESET_IDS = listOf("mp4-h264", "mp4-copy", "mp4-h265", "mov-h264")

val WIZARD_PRESET_CARDS = listOf(
    WizardPresetCard("mp4-h264", "MP4 · H.264", "几乎所有设备都能打开", "常用"),
    WizardPresetCard("mp4-copy", "MP4 · 不重编码", "只换外壳，速度最快", "最快"),
    WizardPresetCard("mp4-h265", "MP4 · H.265", "同样是 MP4，编码更新"),
    WizardPresetCard("mov-h264", "MOV · H.264", "苹果设备、剪辑软件"),
    WizardPresetCard("mkv-copy-friendly", "MKV · H.264", "适合封装保存"),
    WizardPresetCard("mkv-h265", "MKV · H.265", "适合长期存档"),
    WizardPresetCard("webm-vp9", "WebM · VP9", "网页常用，会比 MP4 慢一点"),
    WizardPresetCard("avi-mpeg4", "AVI · MPEG-4", "旧电脑和投影"),
    WizardPresetCard("gif", "GIF", "短视频转成动图"),
    WizardPresetCard("audio-mp3", "MP3", "只导出音频"),
    WizardPresetCard("audio-aac", "M4A · AAC", "只导出音频"),
)

private val CODEC_LABELS = mapOf(
    "h264" to "H.264",
    "hevc" to "H.265",
    "h265" to "H.265",
    "vp9" to "VP9",
    "vp8" to "VP8",
    "av1" to "AV1",
    "mpeg4" to "MPEG-4",
    "aac" to "AAC",
    "opus" to "Opus",
    "mp3" to "MP3",
)

fun canEnterStep(step: WizardStep, importableCount: Int): Boolean =
    step == WizardStep.Sources || importableCount > 0

fun advanceStep(current: WizardStep, importableCount: Int): WizardStep? = when (current) {
    WizardStep.Sources -> WizardStep.Format.takeIf { importableCount > 0 }
    WizardStep.Format -> WizardStep.Output.takeIf { importableCount > 0 }
    WizardStep.Output -> null
}

fun retreatStep(current: WizardStep): WizardStep? = when (current) {
    WizardStep.Sources -> null
    WizardStep.Format -> WizardStep.Sources
    WizardStep.Output -> WizardStep.Format
}

fun collapsedPresetCards(selectedId: String, showAll: Boolean): List<WizardPresetCard> {
    if (showAll) return WIZARD_PRESET_CARDS
    val primary = PRIMARY_PRESET_IDS.map { id -> WIZARD_PRESET_CARDS.first { it.id == id } }
    if (selectedId in PRIMARY_PRESET_IDS) return primary
    val selected = WIZARD_PRESET_CARDS.find { it.id == selectedId } ?: return primary
    return primary.take(3) + selected
}

fun dockActionLabel(step: WizardStep, busy: Boolean, transcoding: Boolean): String = when {
    step != WizardStep.Output -> "下一步"
    busy -> "正在加入队列…"
    transcoding -> "正在转码…"
    else -> "开始转码"
}

fun dockSummary(
    step: WizardStep,
    importableCount: Int,
    presetTitle: String,
    qualityLabel: String,
    sizeLabel: String,
    audioOnly: Boolean,
    copyOnly: Boolean,
    trimLabel: String,
    outputLabel: String,
    formatPreview: String = "",
): String = when (step) {
    WizardStep.Sources -> if (importableCount == 0) "先添加源视频" else "已选 ${importableCount} 个文件"
    WizardStep.Format -> formatPreview.ifBlank { "先添加源视频，再选要转成的格式" }
    WizardStep.Output -> {
        val body = when {
            importableCount == 0 -> "先添加源视频，再开始转码"
            audioOnly -> "将 $importableCount 个文件转为 $presetTitle · $qualityLabel$trimLabel"
            copyOnly -> "将 $importableCount 个视频转为 $presetTitle$trimLabel"
            else -> "将 $importableCount 个视频转为 $presetTitle · $qualityLabel · $sizeLabel$trimLabel"
        }
        if (importableCount == 0) body else "$body · 存到$outputLabel"
    }
}

fun conversionPreview(items: List<MediaInfo>, target: String): String {
    val importable = items.filter { it.importable }
    if (importable.isEmpty()) return "先添加源视频，再选要转成的格式"
    val labels = importable.map(::sourceFromLabel).toSet()
    val from = if (labels.size == 1) labels.first() else "${labels.size} 种源格式"
    return "$from  →  $target"
}

fun presetTitle(id: String): String = WIZARD_PRESET_CARDS.find { it.id == id }?.title ?: id

fun qualityLabel(id: String): String = when (id) {
    "original" -> "原画"
    "small" -> "节省体积"
    else -> "标准"
}

fun sizeLabel(id: String): String = when (id) {
    "1080p" -> "1080p"
    "720p" -> "720p"
    "480p" -> "480p"
    else -> "原尺寸"
}

fun isAudioPreset(preset: String): Boolean = preset == "audio-mp3" || preset == "audio-aac"

fun isCopyPreset(preset: String): Boolean = preset == "mp4-copy"

fun itemHasDuration(media: MediaInfo): Boolean =
    media.importable && (media.durationSecs ?: 0.0) > 0.05

fun isTrimmed(media: MediaInfo): Boolean {
    if (!itemHasDuration(media)) return false
    val duration = media.durationSecs ?: 0.0
    return (media.trimStartSecs ?: 0.0) > 0.2 ||
        (media.trimEndSecs != null && duration - media.trimEndSecs!! > 0.2)
}

fun timeAt(x: Float, width: Float, duration: Double): Double {
    if (width <= 0f || duration <= 0.0) return 0.0
    return ((x / width).toDouble().coerceIn(0.0, 1.0)) * duration
}

fun clampTrim(start: Double, end: Double, duration: Double): Pair<Double, Double> {
    if (duration <= 0.0) return 0.0 to 0.0
    val minGap = minOf(0.2, duration / 20.0).coerceAtLeast(0.01)
    val s = start.coerceIn(0.0, duration)
    val e = end.coerceIn(0.0, duration)
    return if (e - s < minGap) {
        val nextEnd = (s + minGap).coerceAtMost(duration)
        (nextEnd - minGap).coerceAtLeast(0.0) to nextEnd
    } else {
        s to e
    }
}

fun outputFileName(outputPath: String?): String {
    val raw = outputPath?.substringAfterLast('/')?.substringAfterLast('\\')?.substringBefore('?')
    return raw?.takeIf { it.isNotBlank() } ?: "未命名"
}

fun sourceFromLabel(media: MediaInfo): String {
    val container = friendlyContainer(media.container, media.displayName)
    val codec = friendlyCodec(media.videoCodec).ifBlank { friendlyCodec(media.audioCodec) }
    return listOf(container, codec).filter { it.isNotBlank() }.joinToString(" · ")
}

fun sourceFormatLine(media: MediaInfo, probing: Boolean): String {
    if (probing) return "正在读取格式…"
    if (!media.importable) return media.error ?: "这个文件打不开"
    return listOf(
        friendlyContainer(media.container, media.displayName),
        friendlyCodec(media.videoCodec).ifBlank { friendlyCodec(media.audioCodec) },
        if (media.width != null && media.height != null) "${media.width}×${media.height}" else "",
        media.frameRate?.let { "${kotlin.math.round(it).toInt()} fps" } ?: "",
        formatDuration(media.durationSecs),
        if (isTrimmed(media)) "已裁剪" else "",
    ).filter { it.isNotBlank() }.joinToString(" · ")
}

private fun friendlyCodec(codec: String?): String {
    if (codec.isNullOrBlank()) return ""
    return CODEC_LABELS[codec.lowercase()] ?: codec.uppercase()
}

private fun friendlyContainer(container: String?, name: String): String {
    val ext = name.substringAfterLast('.', "").uppercase()
    val value = (container ?: "").lowercase()
    return when {
        "matroska" in value || ext == "MKV" -> "MKV"
        "webm" in value || ext == "WEBM" -> "WebM"
        "mp3" in value || ext == "MP3" -> "MP3"
        "avi" in value || ext == "AVI" -> "AVI"
        ext == "MOV" -> "MOV"
        "mp4" in value || "mov" in value || ext == "MP4" || ext == "M4V" -> "MP4"
        ext.isNotBlank() -> ext
        else -> "视频"
    }
}

private fun formatDuration(seconds: Double?): String {
    if (seconds == null || seconds.isNaN()) return ""
    val total = kotlin.math.round(seconds).toInt()
    if (total < 60) return "$total 秒"
    val mins = total / 60
    val secs = total % 60
    return if (mins < 60) {
        if (secs == 0) "$mins 分钟" else "$mins 分 $secs 秒"
    } else {
        val hours = mins / 60
        val rest = mins % 60
        "$hours 小时 $rest 分"
    }
}
```

- [ ] **Step 4: Run the tests and make sure they pass**

Run the same Gradle command as Step 2.

Expected: `WizardTest` 全部 PASS。

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/videoconverter/android/ui/Wizard.kt \
  android/app/src/test/java/com/videoconverter/android/ui/WizardTest.kt
git commit -m "$(cat <<'EOF'
feat(android): 抽出三步引导与桌面文案纯函数

EOF
)"
```

---

### Task 2: 固定桌面色板主题

**Files:**
- Create: `android/app/src/main/java/com/videoconverter/android/ui/theme/Tokens.kt`
- Modify: `android/app/src/main/java/com/videoconverter/android/ui/theme/Theme.kt`
- Test: `android/app/src/test/java/com/videoconverter/android/ui/TokensTest.kt`

**Interfaces:**
- Consumes: 无
- Produces: `object LightTokens` 含 `Canvas`、`Ink`、`Muted`、`Card`、`Border`、`Accent`、`Chip`、`Notice`、`Bad`、`BadBorder`、`OnDark`、`OnDarkMuted` 的 `const val` ARGB；`LightTranscodeTheme` 只用这套浅色，不再读系统深色或动态取色。

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/videoconverter/android/ui/TokensTest.kt`:

```kotlin
package com.videoconverter.android.ui

import com.videoconverter.android.ui.theme.LightTokens
import org.junit.Assert.assertEquals
import org.junit.Test

class TokensTest {
    @Test
    fun desktopPaletteIsPinned() {
        assertEquals(0xFFECECE8.toInt(), LightTokens.Canvas)
        assertEquals(0xFF1F2428.toInt(), LightTokens.Ink)
        assertEquals(0xFF5C6460.toInt(), LightTokens.Muted)
        assertEquals(0xFFF7F7F4.toInt(), LightTokens.Card)
        assertEquals(0xFFE4E4DE.toInt(), LightTokens.Border)
        assertEquals(0xFFC45A2A.toInt(), LightTokens.Accent)
        assertEquals(0xFFE2E2DC.toInt(), LightTokens.Chip)
        assertEquals(0xFFFFF1D8.toInt(), LightTokens.Notice)
        assertEquals(0xFFFDECE6.toInt(), LightTokens.Bad)
        assertEquals(0xFFF0C9BC.toInt(), LightTokens.BadBorder)
        assertEquals(0xFFF7F7F4.toInt(), LightTokens.OnDark)
        assertEquals(0xFFC9CFCB.toInt(), LightTokens.OnDarkMuted)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME=/Users/wuyu/.sdkman/candidates/java/17.0.11-amzn
export ANDROID_HOME=/Users/wuyu/Library/Android/sdk
cd /Users/wuyu/Projects/video-converter/android
./gradlew :app:testDebugUnitTest --tests com.videoconverter.android.ui.TokensTest
```

Expected: FAIL，找不到 `LightTokens`。

- [ ] **Step 3: Write minimal implementation**

Create `android/app/src/main/java/com/videoconverter/android/ui/theme/Tokens.kt`:

```kotlin
package com.videoconverter.android.ui.theme

object LightTokens {
    const val Canvas = 0xFFECECE8.toInt()
    const val Ink = 0xFF1F2428.toInt()
    const val Muted = 0xFF5C6460.toInt()
    const val Card = 0xFFF7F7F4.toInt()
    const val Border = 0xFFE4E4DE.toInt()
    const val Accent = 0xFFC45A2A.toInt()
    const val Chip = 0xFFE2E2DC.toInt()
    const val Notice = 0xFFFFF1D8.toInt()
    const val Bad = 0xFFFDECE6.toInt()
    const val BadBorder = 0xFFF0C9BC.toInt()
    const val OnDark = 0xFFF7F7F4.toInt()
    const val OnDarkMuted = 0xFFC9CFCB.toInt()
}
```

Replace `Theme.kt` 全部内容为：

```kotlin
package com.videoconverter.android.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
fun LightTranscodeTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    val background = Color(LightTokens.Canvas)
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = background.toArgb()
            window.navigationBarColor = background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
        }
    }
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(LightTokens.Accent),
            onPrimary = Color(LightTokens.OnDark),
            background = background,
            surface = Color(LightTokens.Card),
            onBackground = Color(LightTokens.Ink),
            onSurface = Color(LightTokens.Ink),
            secondary = Color(LightTokens.Ink),
            onSecondary = Color(LightTokens.OnDark),
            error = Color(LightTokens.Accent),
        ),
        content = content,
    )
}
```

禁止再 `import` `isSystemInDarkTheme`、`dynamicDarkColorScheme`、`dynamicLightColorScheme`、`darkColorScheme`。

- [ ] **Step 4: Run the tests and make sure they pass**

```bash
export JAVA_HOME=/Users/wuyu/.sdkman/candidates/java/17.0.11-amzn
export ANDROID_HOME=/Users/wuyu/Library/Android/sdk
cd /Users/wuyu/Projects/video-converter/android
./gradlew :app:testDebugUnitTest --tests com.videoconverter.android.ui.TokensTest --tests com.videoconverter.android.ui.WizardTest --tests com.videoconverter.android.ui.AppViewModelTest
```

Expected: 全部 PASS。

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/videoconverter/android/ui/theme/Tokens.kt \
  android/app/src/main/java/com/videoconverter/android/ui/theme/Theme.kt \
  android/app/src/test/java/com/videoconverter/android/ui/TokensTest.kt
git commit -m "$(cat <<'EOF'
feat(android): 锁定桌面浅色令牌并关闭动态取色

EOF
)"
```

---

### Task 3: 三步 Compose 界面

**Files:**
- Create: `android/app/src/main/java/com/videoconverter/android/ui/WizardComponents.kt`
- Create: `android/app/src/main/java/com/videoconverter/android/ui/TrimPanel.kt`
- Modify: `android/app/src/main/java/com/videoconverter/android/ui/AppScreen.kt`（整文件重写）
- Delete: `android/app/src/main/java/com/videoconverter/android/ui/TrimScreen.kt`

**Interfaces:**
- Consumes: Task 1 全部函数；Task 2 `LightTokens`；现有 `AppViewModel`（`addUris` / `remove` / `updateTrim` / `setPreset` / `setQuality` / `setSize` / `pickOutputTree` / `start` / `cancel` / `retry` / `clearFinished` / `outputIntent` / `clearMessage` / `state`）；`shouldShowResolution`；`statusLabel`；`supportsSystemPreview`；`outputLabel` 逻辑（从旧 `AppScreen` 挪到 `WizardComponents.kt` 的 `fun outputFolderLabel(context: Context, output: OutputTarget): String`）
- Produces: `AppScreen` 三步引导；无独立裁剪路由；第 3 步才调用 `start()`

- [ ] **Step 1: Write the failing test**

在 `WizardTest.kt` 追加（覆盖界面会用到的「第 3 步才是开始」）：

```kotlin
@Test
fun onlyOutputStepStartsTranscode() {
    assertEquals("下一步", dockActionLabel(WizardStep.Sources, false, false))
    assertEquals("下一步", dockActionLabel(WizardStep.Format, false, false))
    assertEquals("开始转码", dockActionLabel(WizardStep.Output, false, false))
}
```

此测试在 Task 1 已有同类断言。本步**不要**为 Compose 写仪器截图测试。改为：确认 `TrimScreen.kt` 仍存在、`AppScreen` 仍是 Material `TopAppBar`——实现后这些必须消失。实现前无需新失败测试；用现有 `WizardTest` 作为契约。

若坚持 TDD：把「AppScreen 不得再引用 TrimScreen」做成编译期删除：先写 `TrimPanel.kt` 空壳 `fun TrimPanel(...)` 使下一步能编译，再删 `TrimScreen`。

- [ ] **Step 2: 实现 `WizardComponents.kt`**

文件必须包含（用 `LightTokens` + `Color()`，不要 `FilterChip` / `TopAppBar` / 动态色）：

- `fun WizardHeader()`：左「轻转码」26sp +「从一种格式转到另一种。文件只留在这台手机上。」；右胶囊「不上传 · 不联网」
- `fun StepTabs(step, importableCount, onSelect)`：三个块文案精确为 `1 添加` `2 格式` `3 存放`。当前步墨色底浅字；未激活浅底。点击时若 `canEnterStep(target, importableCount)` 才切换
- `fun NoticeBar(message, onDismiss)`：底 `Notice`，右「知道了」橙色字
- `fun Dropzone(onGallery, onFiles)`：虚线边框 `Border`，标题「添加要转码的视频」，说明「点这里选择，一次能选好几个」，两枚墨色按钮「相册」「文件」
- `fun FileRow(...)`：可导入白/卡片底，选中黑边，不可导入 `Bad` 底；「移除」橙色字
- `fun PresetGrid(selected, showAll, onSelect, onToggleMore)`：两列；卡片未选 `Card`+边框，选中 `Ink` 底；虚线「更多」/「收起」卡
- `fun OptionChips(title, description, options: List<Pair<String,String,String>> /* id, title, hint */, selected, onSelect)`
- `fun OutputBar(label, onChange)`：「输出到」+ 路径 +「换个位置」
- `fun JobRow(...)`：源名 → `outputFileName`；`statusLabel`；进度条 `Accent`；取消 / 再试一次 / 打开 / 分享 / 清空已完成
- `fun WizardDock(step, summary, action, actionEnabled, onBack, onAction)`：深色圆角 14dp。`step != Sources` 时左「上一步」。右橙色主按钮

圆角：卡片/添加区/dock 12–14.dp；步骤块 8.dp、28.dp 方形；主按钮 10.dp。

`outputFolderLabel` 从旧 `AppScreen.outputLabel` 原样搬过来（`下载/轻转码` 等）。

- [ ] **Step 3: 实现 `TrimPanel.kt`**

把旧 `TrimScreen` 的 Media3 预览搬过来，不再使用 `Scaffold`/`TopAppBar`。签名：

```kotlin
@Composable
fun TrimPanel(
    media: MediaInfo,
    onChange: (MediaInfo) -> Unit,
)
```

- `supportsSystemPreview(media)` 为真时用 `ExoPlayer` + `PlayerView`，否则文案「该格式使用时间轴裁剪，不提供视频预览」
- 时间轴：高 28.dp，底 `Chip`，选区 `Ink`，播放头宽 2.dp 色 `Accent`，手柄 18.dp 圆 `Accent` 白边。拖动手柄时用 `timeAt` + `clampTrim` 更新 `trimStartSecs` / `trimEndSecs`
- 按钮：「设为开始」「设为结束」「恢复整段」。恢复时 `trimStartSecs = null`、`trimEndSecs = null`（或 0 与 duration，但 `isTrimmed` 必须为 false）
- 「设为开始/结束」以当前播放头时间为准；无预览时播放头可点轨道设置

删除 `TrimScreen.kt` 整文件。

- [ ] **Step 4: 重写 `AppScreen.kt`**

结构（不要再 `return` 到独立裁剪页）：

```kotlin
@Composable
fun AppScreen(appViewModel: AppViewModel = viewModel()) {
    val state by appViewModel.state.collectAsState()
    var step by remember { mutableStateOf(WizardStep.Sources) }
    var showAll by remember { mutableStateOf(false) }
    var selectedUri by remember { mutableStateOf<String?>(null) }
    val importable = state.sources.count { it.media.importable }
    val transcoding = state.jobs.any { it.status == JobStatus.Queued || it.status == JobStatus.Running }

    BackHandler(enabled = step != WizardStep.Sources) {
        retreatStep(step)?.let { step = it }
    }

    // pickers 与 notificationPermission 保持现逻辑
    // startWithNotificationPermission 仅在 step == Output 且按下 dock 主按钮时调用

    Column(Modifier.fillMaxSize().background(Color(LightTokens.Canvas)).statusBarsPadding()) {
        WizardHeader()
        StepTabs(step, importable) { target ->
            if (canEnterStep(target, importable)) step = target
        }
        state.message?.let { NoticeBar(it, appViewModel::clearMessage) }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp), verticalArrangement = spacedBy(16.dp)) {
            when (step) {
                WizardStep.Sources -> {
                    item {
                        Text("添加文件" /* 17sp Ink */)
                        Text("预览并裁切要保留的片段" /* Muted */)
                    }
                    item { Dropzone(onGallery, onFiles) }
                    items(state.sources) { source ->
                        FileRow(
                            selected = source.media.sourceUri == selectedUri,
                            canRemove = state.jobs.none { it.sourceUri == source.media.sourceUri && it.status == JobStatus.Running },
                            line = sourceFormatLine(source.media, source.probing),
                            onOpen = {
                                if (itemHasDuration(source.media)) selectedUri = source.media.sourceUri
                            },
                            onRemove = { appViewModel.remove(source.media.sourceUri) },
                        )
                    }
                    val preview = state.sources.firstOrNull { it.media.sourceUri == selectedUri }?.media
                        ?: state.sources.firstOrNull { itemHasDuration(it.media) }?.media
                    if (preview != null) {
                        item { TrimPanel(preview, appViewModel::updateTrim) }
                    }
                }
                WizardStep.Format -> {
                    item {
                        Text("转成")
                        Text(conversionPreview(state.sources.map { it.media }, presetTitle(state.preset)))
                    }
                    item {
                        PresetGrid(
                            cards = collapsedPresetCards(state.preset, showAll),
                            selected = state.preset,
                            showAll = showAll,
                            onSelect = appViewModel::setPreset,
                            onToggleMore = { showAll = !showAll },
                        )
                    }
                    if (isCopyPreset(state.preset)) {
                        item { Text("不重编码只换文件外壳，画质和分辨率都保持原样。源视频编码必须能放进 MP4，不行的文件会提示改用普通转码。") }
                    } else {
                        item {
                            OptionChips("画质" or 音质 if isAudioPreset, … QUALITY original/standard/small …)
                        }
                        if (shouldShowResolution(state.preset)) {
                            item {
                                OptionChips("分辨率", … original/1080p/720p/480p …)
                            }
                        }
                    }
                }
                WizardStep.Output -> {
                    item { OutputBar(outputFolderLabel(context, state.output), { outputPicker.launch(null) }) }
                    if (state.jobs.any { it.status !in setOf(JobStatus.Queued, JobStatus.Running) }) {
                        item { TextButton-equivalent 「清空已完成」 -> clearFinished }
                    }
                    items(state.jobs) { job ->
                        JobRow(job, onCancel, onRetry, onOpen, onShare)
                    }
                }
            }
        }
        WizardDock(
            step = step,
            summary = dockSummary(/* 见 Task 1 */),
            action = dockActionLabel(step, busy = false, transcoding = transcoding),
            actionEnabled = when (step) {
                WizardStep.Sources, WizardStep.Format -> importable > 0 && state.sources.none { it.probing }
                WizardStep.Output -> importable > 0 && !transcoding && state.sources.none { it.probing }
            },
            onBack = { retreatStep(step)?.let { step = it } },
            onAction = {
                when (step) {
                    WizardStep.Sources, WizardStep.Format ->
                        advanceStep(step, importable)?.let { step = it }
                    WizardStep.Output -> startWithNotificationPermission()
                }
            },
        )
    }
}
```

探测未完成时第 1 / 2 步「下一步」可禁用，摘要仍显示已选数量；点开始时若仍 probing，保留 ViewModel 现有「请等待格式读取完成」。

`launchOutput` 保持旧实现。

质量/分辨率 chip 的 hint：

- 原画 / 尽量保留细节；标准 / 一般观看够用；节省体积 / 文件更小，会糊一点
- 原尺寸 / 不缩小画面；1080p / 全高清；720p / 高清；480p / 更小画面

画质区说明：「画质管「压得紧不紧」，分辨率管「画面有多大」。可以原画 + 1080p：画面缩小，细节尽量留着。」音频则「声音保留多少，和画面大小无关」。

Dock 底部加 `navigationBarsPadding()`。

- [ ] **Step 5: Run tests**

```bash
export JAVA_HOME=/Users/wuyu/.sdkman/candidates/java/17.0.11-amzn
export ANDROID_HOME=/Users/wuyu/Library/Android/sdk
cd /Users/wuyu/Projects/video-converter/android
./gradlew :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`，既有测试 + `WizardTest` + `TokensTest` 全过。`TrimScreen.kt` 必须已不存在，工程能编译。

再：

```bash
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`，APK 在 `android/app/build/outputs/apk/debug/app-debug.apk`。

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/videoconverter/android/ui/AppScreen.kt \
  android/app/src/main/java/com/videoconverter/android/ui/WizardComponents.kt \
  android/app/src/main/java/com/videoconverter/android/ui/TrimPanel.kt
git rm android/app/src/main/java/com/videoconverter/android/ui/TrimScreen.kt
git commit -m "$(cat <<'EOF'
feat(android): 用桌面风格三步引导替换 Material 主界面

EOF
)"
```

---

### Task 4: 真机确认

**Files:** 无新代码（若 Step 5 编译失败则只修 UI 文件）

**Interfaces:**
- Consumes: Task 3 的 APK
- Produces: 装到已连接设备的可操作包

- [ ] **Step 1: 安装**

```bash
export ANDROID_HOME=/Users/wuyu/Library/Android/sdk
adb install -r /Users/wuyu/Projects/video-converter/android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.videoconverter.android/.MainActivity
```

Expected: `Success`，应用打开。

- [ ] **Step 2: 按规范冒烟（人工）**

- 暖灰底、橙色主按钮、无壁纸染色顶栏
- 先看到添加/预览/裁切；无文件时「下一步」不可用
- 有文件后第 2 步只有格式；第 3 步才有存放位置和「开始转码」
- 系统返回键在第 2 / 3 步回到上一步

- [ ] **Step 3: Commit**

无代码则跳过 commit。

---

## Self-review

1. **Spec coverage:** 视觉令牌 → Task 2；引导壳/dock/步骤块 → Task 3；第一步预览裁切 → `TrimPanel`；第二步网格与 copy 说明 → Task 3 Format；第三步目录与队列与开始 → Task 3 Output；禁止动态取色 → Theme.kt；删除独立裁剪页 → 删 `TrimScreen`；纯函数测试 → Task 1；现有 JVM 测试 → Task 3 Step 5。
2. **Placeholder scan:** 无 TBD。Task 3 AppScreen 给出完整结构与必须文案。
3. **Type consistency:** `WizardStep` / `dockSummary(..., formatPreview)` / `TrimPanel(media, onChange)` / `LightTokens` 在后续任务中名称一致。
