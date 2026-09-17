# Android Material 3 原生壳 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 Android「轻转码」的 iOS 外壳换成 Material 3：动态取色 + 种子色回退、深浅色跟随系统、`Scaffold` / `TopAppBar` / `NavigationBar`，功能与队列语义不变。

**Architecture:** 引擎和 `AppViewModel` 不动。`theme` 提供 `colorSchemeChoice` 与种子 `ColorScheme`；新建 `Chrome.kt` 放顶栏、底栏、Banner、分组卡片；各 Screen 换成 Material 控件并删掉 `IosChrome.kt`。继续用现有 `RootTab` / `ConvertPage` / `MinePage` 状态，不引入 Navigation Compose。

**Tech Stack:** Kotlin、Jetpack Compose Material 3（已有 BOM `2024.10.01`）、现有 JUnit4 / Robolectric。不新增导航库。图标用 `androidx.compose.material:material-icons-extended`（若编译缺 `Icons.AutoMirrored` / `History` 才加这一行，其它依赖不动）。

## Global Constraints

- 只改 `android/app/src/main/java/com/videoconverter/android/ui/`（含 `theme/`）、`MainActivity.kt` 的 theme 包装，以及为窗口颜色服务的代码；不改 `domain` / `engine` / `data` / `TranscodeService` / `LanShareService` 语义
- Android 12+（API 31）且 `dynamicColor=true` 时用动态取色；否则用种子 `#c45a2a` 的亮/暗 scheme。深浅色跟随系统
- 三 Tab 仍是转换 / 历史记录 / 我的；转换内视频 / 音频 / 文档；设置仍是子页。现有 string 资源继续用
- 不用 Navigation Compose、不用 FAB 作「开始转换」、不做滑动删除、不改局域网 HTTP 网页
- 热区 ≥ 48dp；图标按钮有 `contentDescription`（用已有 `action_back` / `action_more` / `action_remove` / `action_cancel`）
- 现有 JVM 单元测试必须继续通过
- JDK 17：`JAVA_HOME=/Users/wuyu/.sdkman/candidates/java/17.0.11-amzn`，`ANDROID_HOME=/Users/wuyu/Library/Android/sdk`

## File map

```
android/app/src/main/java/com/videoconverter/android/ui/theme/
  Tokens.kt              # 重写：种子 ARGB、colorSchemeChoice、seedLight/Dark ColorScheme
  Theme.kt               # AppTheme：动态取色 + 窗口栏颜色
android/app/src/main/java/com/videoconverter/android/ui/
  RootTabs.kt            # 改 jobRowPrimaryAction + 新增 jobRowOverflowActions
  Chrome.kt              # 新建：TopAppBar、NavigationBar、TabRow、Banner、Card 行
  AppScreen.kt           # Scaffold + 底栏 + 统一 Banner
  ConvertScreen.kt       # Material 转换页
  RootScreens.kt         # 删自制底栏；历史 / 我的 / 对话框
  WizardComponents.kt    # NoticeBar、JobRow 走主题色
  TrimPanel.kt           # 主题色
  DocumentPanel.kt       # 主题色
  LanguageScreen.kt      # TopAppBar + Radio ListItem
  LanShareScreen.kt      # TopAppBar + 主题色
  Glyphs.kt              # 主题色（底栏不再用 TabGlyph）
  IosChrome.kt           # 删除
android/app/src/main/java/com/videoconverter/android/MainActivity.kt
android/app/src/test/java/com/videoconverter/android/ui/
  TokensTest.kt
  RootTabsTest.kt
```

---

### Task 1: 主题选择纯函数与种子色

**Files:**
- Modify: `android/app/src/main/java/com/videoconverter/android/ui/theme/Tokens.kt`
- Modify: `android/app/src/test/java/com/videoconverter/android/ui/TokensTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `const val SEED_ARGB = 0xFFC45A2A.toInt()`
  - `const val IOS_BLUE_ARGB = 0xFF007AFF.toInt()`
  - `enum class ColorSchemeChoice { DynamicLight, DynamicDark, SeedLight, SeedDark }`
  - `fun colorSchemeChoice(darkTheme: Boolean, dynamicColor: Boolean, sdkInt: Int): ColorSchemeChoice`
  - `object SeedColors`（亮/暗 primary 等 ARGB，供 Theme 组 `ColorScheme`）
  - `object ShapeTokens` 改为 Material 默认档：4 / 8 / 12 / 16 / 28 dp

- [ ] **Step 1: Write the failing test**

Replace `android/app/src/test/java/com/videoconverter/android/ui/TokensTest.kt` with:

```kotlin
package com.videoconverter.android.ui

import com.videoconverter.android.ui.theme.ColorSchemeChoice
import com.videoconverter.android.ui.theme.IOS_BLUE_ARGB
import com.videoconverter.android.ui.theme.SEED_ARGB
import com.videoconverter.android.ui.theme.SeedColors
import com.videoconverter.android.ui.theme.ShapeTokens
import com.videoconverter.android.ui.theme.colorSchemeChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TokensTest {
    @Test
    fun seedIsCaramelNotIosBlue() {
        assertEquals(0xFFC45A2A.toInt(), SEED_ARGB)
        assertEquals(0xFF007AFF.toInt(), IOS_BLUE_ARGB)
        assertNotEquals(IOS_BLUE_ARGB, SeedColors.LightPrimary)
        assertNotEquals(IOS_BLUE_ARGB, SeedColors.DarkPrimary)
        assertEquals(0xFF9B4418.toInt(), SeedColors.LightPrimary)
        assertEquals(0xFFFFB595.toInt(), SeedColors.DarkPrimary)
    }

    @Test
    fun dynamicColorOnlyOnApi31WhenEnabled() {
        assertEquals(
            ColorSchemeChoice.DynamicLight,
            colorSchemeChoice(darkTheme = false, dynamicColor = true, sdkInt = 31),
        )
        assertEquals(
            ColorSchemeChoice.DynamicDark,
            colorSchemeChoice(darkTheme = true, dynamicColor = true, sdkInt = 31),
        )
        assertEquals(
            ColorSchemeChoice.SeedLight,
            colorSchemeChoice(darkTheme = false, dynamicColor = true, sdkInt = 30),
        )
        assertEquals(
            ColorSchemeChoice.SeedDark,
            colorSchemeChoice(darkTheme = true, dynamicColor = false, sdkInt = 34),
        )
    }

    @Test
    fun shapesMatchMaterialDefaults() {
        assertEquals(4f, ShapeTokens.ExtraSmall.value)
        assertEquals(8f, ShapeTokens.Small.value)
        assertEquals(12f, ShapeTokens.Medium.value)
        assertEquals(16f, ShapeTokens.Large.value)
        assertEquals(28f, ShapeTokens.ExtraLarge.value)
        assertEquals(12f, ShapeTokens.Panel.value)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
export JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/17.0.11-amzn}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
cd /Users/wuyu/Projects/video-converter/android
./gradlew testDebugUnitTest --tests com.videoconverter.android.ui.TokensTest --console=plain
```

Expected: FAIL compiling (`ColorSchemeChoice` / `SEED_ARGB` / `SeedColors` unresolved, or `LightTokens` assertions fail).

- [ ] **Step 3: Write minimal implementation**

Replace `android/app/src/main/java/com/videoconverter/android/ui/theme/Tokens.kt` with:

```kotlin
package com.videoconverter.android.ui.theme

import androidx.compose.ui.unit.dp

const val SEED_ARGB = 0xFFC45A2A.toInt()
const val IOS_BLUE_ARGB = 0xFF007AFF.toInt()
const val DYNAMIC_COLOR_MIN_SDK = 31

enum class ColorSchemeChoice { DynamicLight, DynamicDark, SeedLight, SeedDark }

fun colorSchemeChoice(
    darkTheme: Boolean,
    dynamicColor: Boolean,
    sdkInt: Int,
): ColorSchemeChoice = when {
    dynamicColor && sdkInt >= DYNAMIC_COLOR_MIN_SDK && darkTheme -> ColorSchemeChoice.DynamicDark
    dynamicColor && sdkInt >= DYNAMIC_COLOR_MIN_SDK -> ColorSchemeChoice.DynamicLight
    darkTheme -> ColorSchemeChoice.SeedDark
    else -> ColorSchemeChoice.SeedLight
}

object SeedColors {
    const val LightPrimary = 0xFF9B4418.toInt()
    const val LightOnPrimary = 0xFFFFFFFF.toInt()
    const val LightPrimaryContainer = 0xFFFFDBCD.toInt()
    const val LightOnPrimaryContainer = 0xFF370E00.toInt()
    const val LightSecondary = 0xFF77574C.toInt()
    const val LightOnSecondary = 0xFFFFFFFF.toInt()
    const val LightSecondaryContainer = 0xFFFFDBD0.toInt()
    const val LightOnSecondaryContainer = 0xFF2C160E.toInt()
    const val LightTertiary = 0xFF6B5E2F.toInt()
    const val LightOnTertiary = 0xFFFFFFFF.toInt()
    const val LightTertiaryContainer = 0xFFF4E2A7.toInt()
    const val LightOnTertiaryContainer = 0xFF231B00.toInt()
    const val LightError = 0xFFBA1A1A.toInt()
    const val LightOnError = 0xFFFFFFFF.toInt()
    const val LightErrorContainer = 0xFFFFDAD6.toInt()
    const val LightOnErrorContainer = 0xFF410002.toInt()
    const val LightBackground = 0xFFFFF8F6.toInt()
    const val LightOnBackground = 0xFF201A18.toInt()
    const val LightSurface = 0xFFFFF8F6.toInt()
    const val LightOnSurface = 0xFF201A18.toInt()
    const val LightSurfaceVariant = 0xFFF5DED6.toInt()
    const val LightOnSurfaceVariant = 0xFF53433E.toInt()
    const val LightOutline = 0xFF85736D.toInt()
    const val LightOutlineVariant = 0xFFD8C2BB.toInt()

    const val DarkPrimary = 0xFFFFB595.toInt()
    const val DarkOnPrimary = 0xFF5A1C00.toInt()
    const val DarkPrimaryContainer = 0xFF7C2E05.toInt()
    const val DarkOnPrimaryContainer = 0xFFFFDBCD.toInt()
    const val DarkSecondary = 0xFFE7BDB0.toInt()
    const val DarkOnSecondary = 0xFF442A22.toInt()
    const val DarkSecondaryContainer = 0xFF5D4035.toInt()
    const val DarkOnSecondaryContainer = 0xFFFFDBD0.toInt()
    const val DarkTertiary = 0xFFD7C68D.toInt()
    const val DarkOnTertiary = 0xFF3A2F05.toInt()
    const val DarkTertiaryContainer = 0xFF524619.toInt()
    const val DarkOnTertiaryContainer = 0xFFF4E2A7.toInt()
    const val DarkError = 0xFFFFB4AB.toInt()
    const val DarkOnError = 0xFF690005.toInt()
    const val DarkErrorContainer = 0xFF93000A.toInt()
    const val DarkOnErrorContainer = 0xFFFFDAD6.toInt()
    const val DarkBackground = 0xFF181210.toInt()
    const val DarkOnBackground = 0xFFECE0DC.toInt()
    const val DarkSurface = 0xFF181210.toInt()
    const val DarkOnSurface = 0xFFECE0DC.toInt()
    const val DarkSurfaceVariant = 0xFF53433E.toInt()
    const val DarkOnSurfaceVariant = 0xFFD8C2BB.toInt()
    const val DarkOutline = 0xFFA08D86.toInt()
    const val DarkOutlineVariant = 0xFF53433E.toInt()
}

object ShapeTokens {
    val ExtraSmall = 4.dp
    val Small = 8.dp
    val Medium = 12.dp
    val Large = 16.dp
    val ExtraLarge = 28.dp
    val Panel = Medium
    val Chip = Small
    val Stamp = Medium
    val Glyph = Small
    val Dialog = ExtraLarge
}
```

Do not leave `LightTokens`. Call sites still compiling against it will fail until later tasks; that is expected. If the module cannot compile for later tests in this task, keep a deprecated typealias only if `./gradlew testDebugUnitTest --tests com.videoconverter.android.ui.TokensTest` cannot run otherwise. Prefer compiling Tokens in isolation by running that test class — if Gradle compiles the whole app and fails on `LightTokens`, add this temporary shim at the bottom of `Tokens.kt` and delete it in Task 8:

```kotlin
@Deprecated("Use MaterialTheme.colorScheme")
object LightTokens {
    const val Canvas = SeedColors.LightBackground
    const val Ink = SeedColors.LightOnSurface
    const val Muted = SeedColors.LightOnSurfaceVariant
    const val Card = SeedColors.LightSurface
    const val Border = SeedColors.LightOutline
    const val Accent = SeedColors.LightPrimary
    const val Chip = SeedColors.LightSurfaceVariant
    const val Notice = SeedColors.LightPrimaryContainer
    const val Bad = SeedColors.LightErrorContainer
    const val Danger = SeedColors.LightError
    const val BadBorder = SeedColors.LightError
    const val OnDark = SeedColors.LightOnPrimary
    const val OnDarkMuted = SeedColors.LightOnSurfaceVariant
}
```

- [ ] **Step 4: Run test to verify it passes**

Run the same `TokensTest` command as Step 2.

Expected: `BUILD SUCCESSFUL`, `TokensTest` tests PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/videoconverter/android/ui/theme/Tokens.kt \
  android/app/src/test/java/com/videoconverter/android/ui/TokensTest.kt
git commit -m "$(cat <<'EOF'
Add Material 3 seed palette and dynamic-color selection.

EOF
)"
```

---

### Task 2: AppTheme 接上动态取色

**Files:**
- Modify: `android/app/src/main/java/com/videoconverter/android/ui/theme/Theme.kt`
- Modify: `android/app/src/main/java/com/videoconverter/android/MainActivity.kt`

**Interfaces:**
- Consumes: `colorSchemeChoice`, `SeedColors`, `ColorSchemeChoice`, `ShapeTokens`
- Produces: `@Composable fun AppTheme(darkTheme: Boolean = isSystemInDarkTheme(), dynamicColor: Boolean = true, content: @Composable () -> Unit)`
- `LightTranscodeTheme` 改为调用 `AppTheme` 的别名，避免 MainActivity 之外残留调用立刻炸掉

- [ ] **Step 1: Write the failing test**

This task has no new JVM assertion beyond Task 1. Verify current theme still compiles by running `TokensTest` plus `AppCompatThemeTest` after implementation. Skip a new test file.

- [ ] **Step 2: Implement Theme.kt**

Replace `android/app/src/main/java/com/videoconverter/android/ui/theme/Theme.kt` with:

```kotlin
package com.videoconverter.android.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.view.WindowCompat

private fun seedLightScheme() = lightColorScheme(
    primary = Color(SeedColors.LightPrimary),
    onPrimary = Color(SeedColors.LightOnPrimary),
    primaryContainer = Color(SeedColors.LightPrimaryContainer),
    onPrimaryContainer = Color(SeedColors.LightOnPrimaryContainer),
    secondary = Color(SeedColors.LightSecondary),
    onSecondary = Color(SeedColors.LightOnSecondary),
    secondaryContainer = Color(SeedColors.LightSecondaryContainer),
    onSecondaryContainer = Color(SeedColors.LightOnSecondaryContainer),
    tertiary = Color(SeedColors.LightTertiary),
    onTertiary = Color(SeedColors.LightOnTertiary),
    tertiaryContainer = Color(SeedColors.LightTertiaryContainer),
    onTertiaryContainer = Color(SeedColors.LightOnTertiaryContainer),
    error = Color(SeedColors.LightError),
    onError = Color(SeedColors.LightOnError),
    errorContainer = Color(SeedColors.LightErrorContainer),
    onErrorContainer = Color(SeedColors.LightOnErrorContainer),
    background = Color(SeedColors.LightBackground),
    onBackground = Color(SeedColors.LightOnBackground),
    surface = Color(SeedColors.LightSurface),
    onSurface = Color(SeedColors.LightOnSurface),
    surfaceVariant = Color(SeedColors.LightSurfaceVariant),
    onSurfaceVariant = Color(SeedColors.LightOnSurfaceVariant),
    outline = Color(SeedColors.LightOutline),
    outlineVariant = Color(SeedColors.LightOutlineVariant),
)

private fun seedDarkScheme() = darkColorScheme(
    primary = Color(SeedColors.DarkPrimary),
    onPrimary = Color(SeedColors.DarkOnPrimary),
    primaryContainer = Color(SeedColors.DarkPrimaryContainer),
    onPrimaryContainer = Color(SeedColors.DarkOnPrimaryContainer),
    secondary = Color(SeedColors.DarkSecondary),
    onSecondary = Color(SeedColors.DarkOnSecondary),
    secondaryContainer = Color(SeedColors.DarkSecondaryContainer),
    onSecondaryContainer = Color(SeedColors.DarkOnSecondaryContainer),
    tertiary = Color(SeedColors.DarkTertiary),
    onTertiary = Color(SeedColors.DarkOnTertiary),
    tertiaryContainer = Color(SeedColors.DarkTertiaryContainer),
    onTertiaryContainer = Color(SeedColors.DarkOnTertiaryContainer),
    error = Color(SeedColors.DarkError),
    onError = Color(SeedColors.DarkOnError),
    errorContainer = Color(SeedColors.DarkErrorContainer),
    onErrorContainer = Color(SeedColors.DarkOnErrorContainer),
    background = Color(SeedColors.DarkBackground),
    onBackground = Color(SeedColors.DarkOnBackground),
    surface = Color(SeedColors.DarkSurface),
    onSurface = Color(SeedColors.DarkOnSurface),
    surfaceVariant = Color(SeedColors.DarkSurfaceVariant),
    onSurfaceVariant = Color(SeedColors.DarkOnSurfaceVariant),
    outline = Color(SeedColors.DarkOutline),
    outlineVariant = Color(SeedColors.DarkOutlineVariant),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(ShapeTokens.ExtraSmall),
    small = RoundedCornerShape(ShapeTokens.Small),
    medium = RoundedCornerShape(ShapeTokens.Medium),
    large = RoundedCornerShape(ShapeTokens.Large),
    extraLarge = RoundedCornerShape(ShapeTokens.ExtraLarge),
)

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when (
        colorSchemeChoice(darkTheme, dynamicColor, Build.VERSION.SDK_INT)
    ) {
        ColorSchemeChoice.DynamicDark -> dynamicDarkColorScheme(context)
        ColorSchemeChoice.DynamicLight -> dynamicLightColorScheme(context)
        ColorSchemeChoice.SeedDark -> seedDarkScheme()
        ColorSchemeChoice.SeedLight -> seedLightScheme()
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        shapes = AppShapes,
        content = content,
    )
}

@Composable
fun LightTranscodeTheme(content: @Composable () -> Unit) {
    AppTheme(content = content)
}
```

In `MainActivity.kt`, change the import and call to `AppTheme`:

```kotlin
import com.videoconverter.android.ui.theme.AppTheme
```

```kotlin
        setContent {
            AppTheme {
                AppScreen(
                    openLanShare = openLanShareState.value,
                    onOpenLanShareConsumed = ::clearOpenLanShare,
                )
            }
        }
```

- [ ] **Step 3: Run tests**

```bash
export JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/17.0.11-amzn}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
cd /Users/wuyu/Projects/video-converter/android
./gradlew testDebugUnitTest --tests com.videoconverter.android.ui.TokensTest --tests com.videoconverter.android.AppCompatThemeTest --console=plain
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/videoconverter/android/ui/theme/Theme.kt \
  android/app/src/main/java/com/videoconverter/android/MainActivity.kt
git commit -m "$(cat <<'EOF'
Wire AppTheme with dynamic color and seed fallback.

EOF
)"
```

---

### Task 3: 历史行主操作 / 溢出

**Files:**
- Modify: `android/app/src/main/java/com/videoconverter/android/ui/RootTabs.kt`
- Modify: `android/app/src/test/java/com/videoconverter/android/ui/RootTabsTest.kt`

**Interfaces:**
- Consumes: `JobRowAction`, `JobStatus`, existing `jobRowActions`
- Produces:
  - `fun jobRowPrimaryAction(status: JobStatus): JobRowAction?`（排队/进行中=`Cancel`，失败/取消=`Retry`，完成=`Open`）
  - `fun jobRowOverflowActions(status: JobStatus): List<JobRowAction>`（去掉主操作后的剩余项）

- [ ] **Step 1: Write the failing test**

In `RootTabsTest.jobRowActionsHideDeleteWhileRunningAndLeadWithOpen`, replace the primary-action assertions with:

```kotlin
        assertEquals(JobRowAction.Cancel, jobRowPrimaryAction(JobStatus.Queued))
        assertEquals(JobRowAction.Cancel, jobRowPrimaryAction(JobStatus.Running))
        assertEquals(JobRowAction.Retry, jobRowPrimaryAction(JobStatus.Failed))
        assertEquals(JobRowAction.Retry, jobRowPrimaryAction(JobStatus.Cancelled))
        assertEquals(JobRowAction.Open, jobRowPrimaryAction(JobStatus.Completed))
        assertEquals(emptyList<JobRowAction>(), jobRowOverflowActions(JobStatus.Running))
        assertEquals(listOf(JobRowAction.Delete), jobRowOverflowActions(JobStatus.Failed))
        assertEquals(
            listOf(JobRowAction.Share, JobRowAction.Rename, JobRowAction.Delete),
            jobRowOverflowActions(JobStatus.Completed),
        )
```

Keep the existing `jobRowActions(...)` assertions unchanged.

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/17.0.11-amzn}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
cd /Users/wuyu/Projects/video-converter/android
./gradlew testDebugUnitTest --tests com.videoconverter.android.ui.RootTabsTest --console=plain
```

Expected: FAIL (`jobRowOverflowActions` unresolved, or `jobRowPrimaryAction(Running)` still null).

- [ ] **Step 3: Write minimal implementation**

Replace in `RootTabs.kt`:

```kotlin
fun jobRowPrimaryAction(status: JobStatus): JobRowAction? = when (status) {
    JobStatus.Queued, JobStatus.Running -> JobRowAction.Cancel
    JobStatus.Failed, JobStatus.Cancelled -> JobRowAction.Retry
    JobStatus.Completed -> JobRowAction.Open
}

fun jobRowOverflowActions(status: JobStatus): List<JobRowAction> {
    val primary = jobRowPrimaryAction(status)
    return jobRowActions(status).filter { it != primary }
}
```

- [ ] **Step 4: Run test to verify it passes**

Same command as Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/videoconverter/android/ui/RootTabs.kt \
  android/app/src/test/java/com/videoconverter/android/ui/RootTabsTest.kt
git commit -m "$(cat <<'EOF'
Map history row primary and overflow actions.

EOF
)"
```

---

### Task 4: Material 外壳组件

**Files:**
- Create: `android/app/src/main/java/com/videoconverter/android/ui/Chrome.kt`
- Modify: `android/app/build.gradle.kts` only if `Icons.AutoMirrored` / `Icons.Filled.Movie` 编译失败，那时加：

```kotlin
    implementation("androidx.compose.material:material-icons-extended")
```

**Interfaces:**
- Consumes: `RootTab`, `ConvertMode`, `HistorySegment`, `rootTabLabelRes`, `convertModeLabelRes`, `historyActiveCount`
- Produces composables: `AppTopBar`, `RootNavigationBar`, `ConvertModeTabs`, `HistorySegmentTabs`, `MessageBanner`, `AppCard`

- [ ] **Step 1: Create Chrome.kt**

```kotlin
package com.videoconverter.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.videoconverter.android.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
        ),
    )
}

@Composable
fun RootNavigationBar(
    selected: RootTab,
    activeHistoryCount: Int,
    onSelect: (RootTab) -> Unit,
) {
    NavigationBar {
        RootTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = {
                    val icon = rootTabIcon(tab)
                    if (tab == RootTab.History && activeHistoryCount > 0) {
                        BadgedBox(badge = { Badge { Text("$activeHistoryCount") } }) {
                            Icon(icon, contentDescription = stringResource(rootTabLabelRes(tab)))
                        }
                    } else {
                        Icon(icon, contentDescription = stringResource(rootTabLabelRes(tab)))
                    }
                },
                label = {
                    Text(
                        stringResource(rootTabLabelRes(tab)),
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
            )
        }
    }
}

fun rootTabIcon(tab: RootTab): ImageVector = when (tab) {
    RootTab.Convert -> Icons.Filled.PlayArrow
    RootTab.History -> Icons.Filled.List
    RootTab.Mine -> Icons.Filled.Person
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvertModeTabs(
    selected: ConvertMode,
    onSelect: (ConvertMode) -> Unit,
) {
    val modes = ConvertMode.entries
    PrimaryTabRow(selectedTabIndex = modes.indexOf(selected)) {
        modes.forEach { mode ->
            Tab(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                text = { Text(stringResource(convertModeLabelRes(mode))) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySegmentTabs(
    selected: HistorySegment,
    onSelect: (HistorySegment) -> Unit,
) {
    val segments = listOf(HistorySegment.Video, HistorySegment.Audio, HistorySegment.Document)
    SecondaryTabRow(selectedTabIndex = segments.indexOf(selected)) {
        segments.forEach { segment ->
            Tab(
                selected = segment == selected,
                onClick = { onSelect(segment) },
                text = {
                    Text(
                        stringResource(
                            when (segment) {
                                HistorySegment.Video -> R.string.lan_segment_video
                                HistorySegment.Audio -> R.string.lan_segment_audio
                                HistorySegment.Document -> R.string.lan_segment_document
                            },
                        ),
                    )
                },
            )
        }
    }
}

@Composable
fun MessageBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_got_it))
            }
        }
    }
}

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = MaterialTheme.shapes.medium,
        content = { Column(modifier = Modifier.padding(vertical = 4.dp), content = { content() }) },
    )
}
```

This BOM includes `PrimaryTabRow` / `SecondaryTabRow`. Do not substitute `TabRow` unless compile fails with unresolved reference; only then switch both call sites to `TabRow`.

- [ ] **Step 2: Compile**

```bash
export JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/17.0.11-amzn}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
cd /Users/wuyu/Projects/video-converter/android
./gradlew :app:compileDebugKotlin --console=plain
```

Expected: SUCCESS, or a missing-icon error then add `material-icons-extended` and retry.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/videoconverter/android/ui/Chrome.kt android/app/build.gradle.kts
git commit -m "$(cat <<'EOF'
Add Material 3 chrome composables for bars and banners.

EOF
)"
```

---

### Task 5: AppScreen Scaffold 与底栏

**Files:**
- Modify: `android/app/src/main/java/com/videoconverter/android/ui/AppScreen.kt`
- Modify: `android/app/src/main/java/com/videoconverter/android/ui/RootScreens.kt`（删除 `RootTabBar` / `TabGlyph`，暂留 History/Mine）

**Interfaces:**
- Consumes: `AppTheme`（已包在 Activity）、`RootNavigationBar`, `MessageBanner`, `historyActiveCount`
- Produces: 根 `Scaffold`；内容区不再自己 `statusBarsPadding` + 自制 49dp tab

- [ ] **Step 1: Replace AppScreen shell**

In `AppScreen.kt`:

1. Imports: add `Scaffold`, `SnackbarHost`, `SnackbarHostState`, `remember`, remove `LightTokens` and `statusBarsPadding` if unused.
2. Replace the outer `Column` that starts around `Column(modifier = Modifier.fillMaxSize().background(...).statusBarsPadding())` with:

```kotlin
    val snackbarHostState = remember { SnackbarHostState() }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            RootNavigationBar(
                selected = tab,
                activeHistoryCount = historyActiveCount(state.jobs),
                onSelect = { next ->
                    minePage = minePageAfterLeavingTab(next, minePage)
                    tab = next
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            state.message?.let { MessageBanner(it, appViewModel::clearMessage) }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    RootTab.Convert -> ConvertScreen(/* existing args unchanged */)
                    RootTab.History -> HistoryScreen(/* existing args unchanged */)
                    RootTab.Mine -> /* existing Mine / Lan / Language branching unchanged */
                }
            }
        }
    }
```

3. Delete the duplicate `if (tab != RootTab.Convert) { state.message?.let { NoticeBar(...) } }` block.
4. Delete `RootTabBar(...)` at the bottom.
5. Remove `ConvertScreen`'s own `NoticeBar` in Task 6 so messages are not doubled; until then temporarily do **not** show the AppScreen banner when `tab == RootTab.Convert` if ConvertScreen still draws `NoticeBar`. Preferred: remove ConvertScreen `NoticeBar` in this same task:

In `ConvertScreen.kt` delete:

```kotlin
        state.message?.let {
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                NoticeBar(it, appViewModel::clearMessage)
            }
        }
```

- [ ] **Step 2: Delete RootTabBar and TabGlyph**

Remove `RootTabBar` and `TabGlyph` from `RootScreens.kt` (the Canvas tab icons). Leave `HistoryScreen` / `MineScreen` for Task 6–7.

- [ ] **Step 3: Run unit tests**

```bash
export JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/17.0.11-amzn}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
cd /Users/wuyu/Projects/video-converter/android
./gradlew testDebugUnitTest --tests com.videoconverter.android.ui.RootTabsTest --tests com.videoconverter.android.ui.WizardTest --tests com.videoconverter.android.ui.AppViewModelTest --console=plain
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/videoconverter/android/ui/AppScreen.kt \
  android/app/src/main/java/com/videoconverter/android/ui/RootScreens.kt \
  android/app/src/main/java/com/videoconverter/android/ui/ConvertScreen.kt
git commit -m "$(cat <<'EOF'
Replace iOS tab bar with Scaffold NavigationBar.

EOF
)"
```

---

### Task 6: 转换页 Material 化

**Files:**
- Modify: `android/app/src/main/java/com/videoconverter/android/ui/ConvertScreen.kt`

**Interfaces:**
- Consumes: `AppTopBar`, `ConvertModeTabs`, `AppCard`, existing `sessionFor` / `convertSettingsFor` / picker callbacks
- Produces: 转换首页与子页不再调用 `IosLargeTitle` / `IosNavBar` / `IosSegmented` / `IosSection` / `IosRow` / `IosPrimaryButton`

- [ ] **Step 1: Replace chrome in ConvertScreen**

Keep the function signature of `ConvertScreen` unchanged. Change the body structure to:

```kotlin
    Column(modifier = Modifier.fillMaxSize()) {
        if (page == ConvertPage.Home) {
            AppTopBar(title = stringResource(R.string.tab_convert))
            ConvertModeTabs(selected = mode, onSelect = onMode)
        } else {
            AppTopBar(
                title = stringResource(
                    when (page) {
                        ConvertPage.Quality -> convertSettingTitleRes(ConvertSetting.Quality, displayedPreset)
                        else -> convertPageTitleRes(page)
                    },
                ),
                onBack = { onPage(ConvertPage.Home) },
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        ) {
            // existing when(page) branches, with controls swapped as below
        }
        if (page == ConvertPage.Home) {
            Button(
                onClick = onStart,
                enabled = startEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.wizard_start_convert))
            }
        }
    }
```

Needed extra imports: `androidx.compose.material3.Button`, `ListItem`, `RadioButton`, `HorizontalDivider`, `Icons.AutoMirrored.Filled.KeyboardArrowRight`, `Icons.Filled.Close`, `FilledTonalButton`, `CircularProgressIndicator`, `MaterialTheme`.

Home `when` content:

- Preview item: wrap `TrimPanel` / `DocumentSourcePreview` in `AppCard` + `Modifier.padding(12.dp)` instead of `IosSection`.
- Files: replace `FilesSection` internals — each source is `ListItem` (`headlineContent` = name, `supportingContent` = format line or `wizard_reading_format` with a small `CircularProgressIndicator` when `probing`). Trailing `IconButton` with `Icons.Filled.Close` and `contentDescription = stringResource(R.string.action_remove)` unless `runningUris` contains the uri. Non-importable `ListItem` uses `colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.errorContainer)`. Below the list, a `Row` of `FilledTonalButton` for 相册 / 文件 /（音频）音乐. Empty list still shows the existing add hint `Text` using `MaterialTheme.colorScheme.onSurfaceVariant`.
- Settings: `AppCard` of `ListItem` per `convertSettingsFor(displayedPreset)`: `headlineContent` title, `supportingContent` current `settingValue`, trailing chevron icon, `onClick = { onPage(convertPageFor(setting)) }`.
- Delete the `item("start")` `IosPrimaryButton` (button is now pinned below `LazyColumn`).

Format / Quality / Size / Output pages: each option is `ListItem` + trailing `RadioButton(selected = ..., onClick = null)` inside `AppCard`. The video more/collapse row is a `ListItem` without `RadioButton`. `pdf-image` container chips stay a second `AppCard` of radio rows. Format hint `Text` uses `MaterialTheme.colorScheme.onSurfaceVariant`.

Replace `Color(LightTokens.Muted)` / `Color(LightTokens.Danger)` leftovers in this file with `MaterialTheme.colorScheme.onSurfaceVariant` / `error`.

- [ ] **Step 2: Compile and unit tests**

```bash
export JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/17.0.11-amzn}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
cd /Users/wuyu/Projects/video-converter/android
./gradlew testDebugUnitTest --tests com.videoconverter.android.ui.ConvertTest --tests com.videoconverter.android.ui.WizardTest --console=plain
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/videoconverter/android/ui/ConvertScreen.kt
git commit -m "$(cat <<'EOF'
Restyle convert flow with Material bars and list items.

EOF
)"
```

---

### Task 7: 历史与我的

**Files:**
- Modify: `android/app/src/main/java/com/videoconverter/android/ui/RootScreens.kt`
- Modify: `android/app/src/main/java/com/videoconverter/android/ui/WizardComponents.kt`（`JobRow`）
- Modify: `android/app/src/main/java/com/videoconverter/android/ui/LanguageScreen.kt`
- Modify: `android/app/src/main/java/com/videoconverter/android/ui/LanShareScreen.kt`

**Interfaces:**
- Consumes: `AppTopBar`, `HistorySegmentTabs`, `AppCard`, `jobRowPrimaryAction`, `jobRowOverflowActions`, `MessageBanner`（已在 AppScreen）
- Produces: 历史卡片 + BottomSheet 溢出；我的分组 `ListItem`；语言 / 局域网子页 `AppTopBar`

- [ ] **Step 1: HistoryScreen**

Replace `IosLargeTitle` / `IosSegmented` with:

```kotlin
        AppTopBar(
            title = stringResource(R.string.tab_history),
            actions = {
                if (hasFinishedJobs(jobs)) {
                    TextButton(onClick = onClearFinished) {
                        Text(stringResource(R.string.action_clear_finished))
                    }
                }
            },
        )
        HistorySegmentTabs(selected = segment, onSelect = onSegment)
```

Active jobs hint: `Surface(tonalElevation = 1.dp)` + `Text(stringResource(R.string.history_running_banner, historyActiveCount(jobs)))`, clickable `onConvertAgain`.

Empty: centered `Icon(Icons.Filled.List, contentDescription = null)`, `Text(emptyLabel, style = titleLarge)`, `Text(history_empty_hint, color = onSurfaceVariant)`, `FilledTonalButton(onClick = onConvertAgain) { Text(stringResource(R.string.action_convert_again)) }`.

List: keep `items(jobs.asReversed())`.

Replace `RenameDialog` / `ConfirmDialog` custom scrims with:

```kotlin
    renaming?.let { job ->
        var value by remember { mutableStateOf(sourceStem(historyTitle(job, stringResource(R.string.untitled)))) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.history_rename_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.history_rename_hint))
                    OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = { onRename(job, value); renaming = null }) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    deleting?.let { job ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.history_delete_title)) },
            text = { Text(stringResource(R.string.history_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = { onDelete(job.id); deleting = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
```

- [ ] **Step 2: JobRow**

In `WizardComponents.kt` `JobRow`:

- Card container: `Card` / `surface`, not `LightTokens.Card`.
- Status circle: `Completed`/`Running` → `primary`; `Failed` → `error`; else → `surfaceVariant`. Foreground `onPrimary` / `onError` / `onSurface`.
- Completed icon: `Icons.Filled.Check` instead of `AppGlyph.Check`.
- Progress: `LinearProgressIndicator(progress = { progress })` when active.
- Actions: primary `Button` or `TextButton` for `jobRowPrimaryAction(job.status)`; if `jobRowOverflowActions` is not empty, `IconButton` `Icons.Filled.MoreVert` with `contentDescription = stringResource(R.string.action_more)` that sets a local `var sheet by remember { mutableStateOf(false) }`.
- `ModalBottomSheet` lists overflow actions as `ListItem`. Delete uses `MaterialTheme.colorScheme.error`. Call the existing `onCancel` / `onRetry` / `onOpen` / `onShare` / `onRename` / `onDelete` lambdas.

- [ ] **Step 3: MineScreen**

```kotlin
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(title = stringResource(R.string.tab_mine))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                mineItemGroups().forEach { group ->
                    AppCard {
                        group.forEachIndexed { index, item ->
                            ListItem(
                                headlineContent = { Text(stringResource(item.titleRes)) },
                                trailingContent = {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                                },
                                modifier = Modifier.clickable { onOpen(item.page) },
                            )
                            if (index < group.lastIndex) HorizontalDivider()
                        }
                    }
                }
                Text(
                    stringResource(R.string.mine_version, versionName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Text(
                    stringResource(R.string.mine_local_promise),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
```

Detail pages (privacy / terms / about): `AppTopBar(title, onBack = onBack)` + body `Text` with `MaterialTheme.typography.bodyLarge` and `onSurface`.

- [ ] **Step 4: LanguageScreen**

Replace `IosNavBar` + `IosSection`/`IosRow` with `AppTopBar` + `AppCard` of `ListItem` + trailing `RadioButton(selected = language == selected, onClick = null)`.

- [ ] **Step 5: LanShareScreen**

Replace `IosNavBar` with `AppTopBar` (back still calls `persistToken(); onBack()`). Replace `Color(LightTokens.*)` with `MaterialTheme.colorScheme.onSurface` / `onSurfaceVariant` / `primary`. Keep `Switch` / `OutlinedTextField` / copy behavior.

- [ ] **Step 6: Tests**

```bash
export JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/17.0.11-amzn}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
cd /Users/wuyu/Projects/video-converter/android
./gradlew testDebugUnitTest --tests com.videoconverter.android.ui.RootTabsTest --tests com.videoconverter.android.ui.AppLanguageTest --tests com.videoconverter.android.ui.StringsResourceTest --console=plain
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/videoconverter/android/ui/RootScreens.kt \
  android/app/src/main/java/com/videoconverter/android/ui/WizardComponents.kt \
  android/app/src/main/java/com/videoconverter/android/ui/LanguageScreen.kt \
  android/app/src/main/java/com/videoconverter/android/ui/LanShareScreen.kt
git commit -m "$(cat <<'EOF'
Restyle history and mine screens with Material 3.

EOF
)"
```

---

### Task 8: 清掉 iOS 色与 IosChrome

**Files:**
- Modify: `TrimPanel.kt`, `DocumentPanel.kt`, `WizardComponents.kt`, `Glyphs.kt`
- Delete: `android/app/src/main/java/com/videoconverter/android/ui/IosChrome.kt`
- Modify: `Tokens.kt`（若 Task 1 留了 `LightTokens` shim，这里删掉）

**Interfaces:**
- Consumes: `MaterialTheme.colorScheme` / `shapes`
- Produces: 仓库内 `LightTokens` / `IosLargeTitle` / `IosNavBar` / `IosSegmented` / `IosSection` / `IosRow` / `IosPrimaryButton` 引用为零

Color mapping for leftover `LightTokens` usages:

| LightTokens | MaterialTheme.colorScheme |
| --- | --- |
| Canvas | background |
| Ink | onSurface |
| Muted | onSurfaceVariant |
| Card | surface |
| Border | outline |
| Accent | primary |
| Chip | surfaceVariant |
| Notice | primaryContainer |
| Bad | errorContainer |
| Danger / BadBorder | error |
| OnDark | onPrimary |
| OnDarkMuted | onSurfaceVariant |

`ShapeTokens.Panel` 继续当卡片圆角即可（已等于 Medium 12dp）。

- [ ] **Step 1: TrimPanel and DocumentPanel**

In both files, add `import androidx.compose.material3.MaterialTheme`. Replace every `Color(LightTokens.X)` / `androidx.compose.ui.graphics.Color(LightTokens.X)` using the table. Keep trim/page-range behavior and callbacks unchanged.

- [ ] **Step 2: WizardComponents leftovers**

Replace remaining `LightTokens` in `NoticeBar` (can delete `NoticeBar` if unused after Task 5), `Dropzone`, `SourceRow`, chips, `WizardDock` (if nothing calls `WizardDock`, delete that composable in this task). Grep before deleting:

```bash
rg "fun NoticeBar|NoticeBar\\(|WizardDock\\(|IosPrimaryButton|LightTokens" android/app/src/main/java
```

`Glyphs.kt`: `GlyphBadge` emphasized color = `primary` / `onPrimary`; otherwise `surfaceVariant` / `onSurface`. Pass `Color` from callers that already have theme, or read `MaterialTheme.colorScheme` inside.

- [ ] **Step 3: Delete IosChrome.kt and LightTokens shim**

Delete the file `IosChrome.kt`. Remove `LightTokens` object if still present.

- [ ] **Step 4: Verify no references**

```bash
rg "IosLargeTitle|IosNavBar|IosSegmented|IosSection|IosRow|IosPrimaryButton|IosSecondaryButton|LightTokens" android
```

Expected: no matches.

- [ ] **Step 5: Full unit tests**

```bash
export JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/17.0.11-amzn}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
cd /Users/wuyu/Projects/video-converter/android
./gradlew testDebugUnitTest --console=plain
```

Expected: `BUILD SUCCESSFUL`, all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add -A android/app/src/main/java/com/videoconverter/android/ui \
  android/app/src/main/java/com/videoconverter/android/ui/theme
git commit -m "$(cat <<'EOF'
Remove iOS chrome and leftover hardcoded palette.

EOF
)"
```

---

## Spec coverage

| Spec section | Task |
| --- | --- |
| 种子色 `#c45a2a`、禁止 iOS 蓝、动态取色 API 31+、深浅色跟随系统 | 1, 2 |
| `MaterialTheme` 完整 scheme / 默认字体 / 默认形状 | 1, 2 |
| `Scaffold` + `NavigationBar` + Badge | 4, 5 |
| `TopAppBar` 根页/子页返回 | 4, 6, 7 |
| `PrimaryTabRow` / `SecondaryTabRow` | 4, 6, 7 |
| 转换首页卡片、ListItem、钉住的 filled Button、无 FAB | 6 |
| 子页 RadioButton ListItem、更多/收起无 radio | 6 |
| 历史卡片、LinearProgress、主操作+溢出 sheet、AlertDialog、空态 `action_convert_again`、清空不确认 | 3, 7 |
| 我的分组 Card、局域网/语言只换壳 | 7 |
| Banner 不自动消失、探测中 indicator、禁用开始按钮 | 5, 6 |
| contentDescription、48dp | 4, 6, 7 |
| 删除 `IosChrome`、TrimPanel 改色、底栏 Material 图标 | 5, 8 |
| 不改 domain/engine/队列、不引入 NavHost | 全部 |
| JVM 单测继续过 | 每任务末 |

真机冒烟（浅色/深色走完转换、Android 12+ 动态取色、三 Tab 返回）不写进 JVM 任务，实施完成后由人在设备上做。
