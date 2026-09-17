# Android 界面：Material 3 原生壳

把现网 Android 的 iOS 外壳（大标题、‹ 返回、分段控件、49dp 底栏、系统蓝）换成 Material Design 3。信息架构、转码引擎、队列、选文件和输出逻辑不变。本设计只替换界面视觉与 Compose 控件；[原生版](2026-09-16-android-native-transcoder-design.md)、[根 Tab](2026-09-17-android-root-tabs-design.md)、音视频文档转换、局域网、多语言等规格的功能章节继续有效。桌面暖灰令牌和「禁止动态取色」不再约束 Android 壳。

## 背景

现网 `ui/` 用 `IosChrome`（`IosLargeTitle`、`IosNavBar`、`IosSegmented`、`IosSection`、`IosRow`）和写死的 iOS 色（`#F2F2F7` / `#007AFF`），固定浅色，不走完整 `MaterialTheme.colorScheme`。用户选择按 Material 3 重做外壳，而不是继续桌面仿制或 iOS 仿制。

## 目标与约束

- 打开 App 能认成 Android 应用：`Scaffold` + `TopAppBar` + `NavigationBar`，颜色走主题角色，热区 ≥ 48dp。
- 三 Tab 仍是 **转换 / 历史记录 / 我的**；转换内视频 / 音频 / 文档；设置仍是子页（格式、画质、尺寸、存放）。
- Android 12+ 使用动态取色；不可用时回退到以 `#c45a2a` 为种子的 tonal palette。深浅色跟随系统。
- 功能与现网一致：相册 / 文件 / 音乐多选、探测、裁剪、预设 / 画质 / 分辨率、输出位置、单任务队列、取消 / 重试 / 打开 / 分享 / 重命名 / 删除、局域网、语言。
- 继续 Jetpack Compose，不引入 Navigation Compose，不引入 WebView。
- 只改 `ui/`（含 `theme/`）和窗口背景。`domain` / `engine` / `data` / `TranscodeService` / `LanShareService` 不为此改语义。
- 现有 string 资源继续用，不为换皮新造一套话。应用名、多语言规则不变。

## 方案

Material 3 主题 + 现有 `RootTab` / `ConvertPage` / `MinePage` 状态机。根布局 `Scaffold`。不采用：只换配色保留 iOS 控件、引入 `NavHost`、平板 `NavigationRail`、FAB 主操作、滑动删除。

## 主题

`LightTranscodeTheme` 换成完整 `MaterialTheme`（`colorScheme`、`typography`、`shapes`）。

- 种子色 `#c45a2a`，用 Material 色调生成亮色 / 暗色 scheme（含 `primary`、`primaryContainer`、`surface`、`surfaceVariant`、`error` 等角色）。不要再手抄 iOS 蓝或桌面暖灰表当运行时颜色。
- `dynamicColor = true`：API 31+ 用 `dynamicLightColorScheme` / `dynamicDarkColorScheme`；更低版本或动态取色失败时用种子 scheme。
- `darkTheme` 默认 `isSystemInDarkTheme()`。
- 字体：`FontFamily.Default`（系统中文）。不打包 Inter / Roboto。
- 形状用 Material 3 默认档：chip `extraSmall`/`small`，卡片 `medium`（12dp），sheet / 大容器 `large`/`extraLarge`。
- 所有界面颜色只读 `MaterialTheme.colorScheme` 与 `typography`。删除 `LightTokens` 硬编码用法。窗口状态栏 / 导航栏颜色跟当前 `surface` / `background`，图标对比跟随深浅色。
- 本轮不新增成功 / 警告扩展色。完成、进行中走 `primary`；失败走 `error`。

## 外壳

```
Scaffold
  TopAppBar（根页标题 / 子页标题+返回）
  内容
  转换首页：filled Button「开始转换」钉在底栏上方
  NavigationBar
SnackbarHost 预留；本轮错误不用 Snackbar
```

- 底栏：`NavigationBar` + 三个 `NavigationBarItem`，Material 图标 + 现有 Tab 文案。选中态走 `secondaryContainer`。历史有排队或进行中任务时，该 item 用 `Badge` 显示数量。
- 根页 `TopAppBar`：转换 / 历史记录 / 我的。不要 34sp 大标题。
- 子页 `TopAppBar`：`navigationIcon` 为返回箭头，`contentDescription` 为返回。不要 `‹ 返回文案`。
- 转换的视频 / 音频 / 文档用 `PrimaryTabRow`（切模式）。历史的视频 / 音频 / 文档用 `SecondaryTabRow`（筛列表）。不要 iOS segmented。
- 系统返回继续 `consumeRootBack`：我的详情回根页；转换子页回首页；Tab 根页交给系统。切走「我的」时详情收回根页。

## 转换

根页：

1. 有可预览源时：`Card` 包现有预览 / 裁切（`TrimPanel` / `DocumentSourcePreview`）。逻辑不变，颜色改主题色。
2. 文件 `Card`：`ListItem` 显示文件名、格式摘要、移除。不可导入行用 `errorContainer`。无文件时只留添加按钮和现有 hint。添加操作为 `FilledTonalButton`：相册、文件；音频模式再加「音乐」。
3. 设置 `Card`：`convertSettingsFor(preset)` 的每一项是 `ListItem`（标题 + 当前值 + chevron），点进对应 `ConvertPage`。
4. 底部 filled `Button` 用现有「开始转换」文案。无导入文件、探测中、已有进行中任务时禁用。成功入队后仍切到历史并重置向导，规则与现网相同。不用 FAB。

子页（格式 / 画质 / 尺寸 / 存放）：返回顶栏 + `ListItem`，末尾 `RadioButton` 表示当前项。视频「更多 / 收起」仍是列表最后一项，无 RadioButton。`pdf-image` 的容器选择仍跟在格式页。点自定义输出且尚未选目录时，仍打开 SAF。

## 历史

- 顶栏 action：有已结束任务时显示「清空已完成」`TextButton`，点了直接清，不另弹确认（与现网相同）。
- 分段 Tab 下：有进行中任务时显示 tonal 提示条（现有「正在转换 n 个」类文案），可点回转换 Tab。
- 列表：`jobs.asReversed()` 的 `Card`。左侧 48dp 圆形容器表示状态（完成勾、进行中进度数字、失败、取消、排队）。标题 + 摘要用 `titleMedium` / `bodyMedium`。进行中显示 `LinearProgressIndicator`。
- 操作按现有 `jobRowActions`，收进主按钮 + 溢出：

| 状态 | 主操作 | 更多（`ModalBottomSheet`） |
| --- | --- | --- |
| 排队 / 进行中 | 取消 | 无 |
| 失败 / 已取消 | 重试 | 删除 |
| 完成 | 打开 | 分享、重命名、删除 |

- 删除、重命名用 `AlertDialog`。破坏性确认按钮用 `error` 色。不做滑动删除。
- 空态：居中图标 + 现有空态标题 / `history_empty_hint` + `FilledTonalButton`，文案用现有 `action_convert_again`（切到转换 Tab）。

## 我的

根页：分组 `Card` + `ListItem`，分组与 `mineItemGroups()` 相同（局域网、语言；隐私、条款；关于）。下方 `bodySmall`：版本号、本地处理承诺。

子页：返回顶栏 + 正文。局域网、语言页只换壳，功能不变。

## 错误与空态

| 情况 | 呈现 |
| --- | --- |
| `state.message` | 内容区顶部可关闭提示条（Banner 样式的 `Surface`，不是 Snackbar），按钮用现有「知道了」。不自动消失 |
| 任务失败 | 卡片 `errorContainer`；主按钮重试 |
| 非法文件名、无法重命名等 | 仍写入 `state.message` |
| 探测中 | 该 `ListItem` 用小型 `CircularProgressIndicator` + 现有「正在读取格式」类文案；开始按钮禁用 |
| 无文件点开始 | 按钮禁用，不额外弹错 |

Snackbar 本轮不用来承载错误。

## 无障碍

- 所有图标按钮有 `contentDescription`（返回、更多、移除、取消）。
- 热区 ≥ 48dp。
- 跟随系统字体缩放；底栏用 `labelMedium`，不写死 10sp。
- 颜色只走主题角色，保证动态取色和深色对比度。

## 模块边界

| 单元 | 做什么 | 怎么用 | 依赖 |
| --- | --- | --- | --- |
| `ui/theme` | 动态取色 + 种子色回退、深浅色、形状与字体 | `AppTheme` 包住 `setContent` | Compose Material3 |
| Material 外壳组件 | 替换 `IosChrome`：顶栏、底栏、分组卡片、行、主按钮、Banner、对话框 | 各 Screen 组装 | theme |
| `ConvertScreen` / `HistoryScreen` / `MineScreen` / 局域网 / 语言 | 现有页面结构，换控件 | `AppScreen` | ViewModel |
| `AppScreen` | 现有 Tab / 子页 / 选择器 / 通知权限 | Activity `setContent` | ViewModel |
| `AppViewModel` | 现有状态与操作 | 不改领域语义 | 现有 |

删除 `IosChrome.kt`，调用点全部迁走。`TrimPanel` 保留行为，改主题色。底栏与状态图标改用 Material `Icons`，不再画 `AppGlyph` 底栏。

## 测试

- 现有 JVM 单测必须继续通过（`RootTabs`、`Wizard`、`AppViewModel`、文案资源、局域网等）。
- 主题回退可测：API < 31 或 `dynamicColor = false` 时 primary 来自种子色，不是 `#007AFF`。
- 不改仪器测试为截图对比。真机冒烟：浅色 / 深色各走完转换主路径；Android 12+ 上主色可随壁纸变；三 Tab 与子页返回正常；开始转换仍切到历史。

## 成功标准

侧载后不再出现 iOS 大标题、‹ 返回、分段胶囊和系统蓝主色。底栏是 `NavigationBar`。转换首页能选文件、改设置、开始转换。转码结果与改 UI 前一致。深色模式可开。

## 明确不做

- Navigation Compose / 平板 NavigationRail / 折叠屏两栏
- WebView / 共享 React
- 改转码参数、队列、输出命名
- 局域网 HTTP 网页改成 Material（电脑浏览器页，继续现有桌面气质）
- FAB 作为「开始转换」、滑动删除、账号登录
- iOS
