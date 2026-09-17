# 轻转码 iOS

在同一仓库新增独立的 SwiftUI iPhone App，功能和现网 Android「轻转码」对齐：本机转换、不上传。界面走 Apple Human Interface Guidelines，不把桌面暖灰长页或 Android 自定义 `IosChrome` 像素搬过去。

HIG：[Designing for iOS](https://developer.apple.com/design/human-interface-guidelines/designing-for-ios)、[Tab bars](https://developer.apple.com/design/human-interface-guidelines/tab-bars)、[Color](https://developer.apple.com/design/human-interface-guidelines/color)、[Settings](https://developer.apple.com/design/human-interface-guidelines/settings)、[Live Activities](https://developer.apple.com/design/human-interface-guidelines/live-activities)、[Accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility)、[File management](https://developer.apple.com/design/human-interface-guidelines/file-management)。

本设计叠在现 Android 产品语义上，领域与队列以这些规格为准，不另写一套预设表：

- [Android 原生版](2026-09-16-android-native-transcoder-design.md)
- [音频转换](2026-09-17-android-audio-conversion-design.md)
- [文档转换](2026-09-17-android-document-conversion-design.md)
- [底部三 Tab](2026-09-17-android-root-tabs-design.md)
- [多语言](2026-09-17-android-i18n-design.md)
- [局域网媒体库](2026-09-17-android-lan-media-library-design.md)

## 背景

桌面是 Tauri + FFmpeg sidecar。Android 是 Kotlin + 捆绑 FFmpeg，界面已改成大标题 / 分组列表 / 底栏三 Tab，但仍是 Compose 自绘、浅色 hex、不跟系统深色。仓库里还没有 iOS 包。

## 目标与约束

- 现网 Android 能转的视频 / 音频 / 文档，iPhone 上也能入队并产出对应文件。
- 文件留在本机，不上传、不联网转码。局域网分享默认关闭，行为对齐 Android。
- 第一版：Xcode 真机与 TestFlight。不上 App Store。
- 最低 iOS 18，只打 iPhone arm64。iPad 用同一套 Tab，不做分栏。
- 主屏幕名 `LiteTrans`（与 Android 多语言规格一致）。关于页写「轻转码」。
- Bundle ID：`com.videoconverter.ios`。
- 不与桌面 / Android 共享代码，不与它们同步设置。
- SwiftUI。不引入 WebView，不套 React。

## 方案

`TabView`（三个平行目的地）+ 每 Tab 一个 `NavigationStack`。转码首页是分组列表：文件、设置披露行、主按钮。格式 / 画质 / 分辨率 / 存放用 push，不用 sheet。

引擎：探测与转码对齐 Android 预设 ID、白名单、单任务队列、`.partial` 再改名。H.264 / H.265 优先 VideoToolbox，其余走捆绑 FFmpeg。文档走独立引擎（图片 / PDF / Office），不经过 FFmpeg。

不采用：像素对齐桌面或 Android `IosChrome`、五个底栏 Tab、汉堡菜单、自定义 dock 三步条、用音乐后台伪装保活。

## 仓库布局

桌面与 `android/` 不动。新增：

```
ios/
  LiteTrans/
    App/           # @main、TabView
    Domain/        # MediaInfo、OutputConfig、Job、预设、校验
    Engine/        # ffprobe/ffmpeg、VideoToolbox、文档、队列泵
    Data/          # 设置、书签、输出位置
    UI/            # 转码 / 历史 / 我的
  LiteTransTests/
  LiteTransUITests/
  scripts/fetch-ffmpeg.sh   # 拉取固定版本 xcframework，不入库
```

FFmpeg 二进制不提交 git。许可说明沿用根目录 README。

## 模块边界

| 单元 | 做什么 | 怎么用 | 依赖 |
| --- | --- | --- | --- |
| `Domain` | 预设表、配置解析、白名单、命名 | 纯 Swift | 无 |
| `Engine` | 探测、拼参、进程、进度、文档转换、队列泵 | 入队后跑 | Domain、本机二进制 |
| `Data` | 记住预设 / 画质 / 输出；安全作用域书签 | UI 与引擎之间 | Photos / Files |
| `UI` | 三 Tab、向导状态、权限请求 | `WindowGroup` | Domain、Data、Engine |
| Live Activity | 进行中任务的锁屏 / Dynamic Island | 入队后开始，结束即撤 | ActivityKit |

## 导航

底栏三个 Tab，切走再回来不丢该 Tab 的栈、已选文件、裁切和设置。

| Tab | 文案 | SF Symbol（未选 / 选中） |
| --- | --- | --- |
| 转码 | 转码 | `arrow.triangle.2.circlepath` / `.fill` |
| 历史 | 历史 | `clock` / `clock.fill` |
| 我的 | 我的 | `person.crop.circle` / `.fill` |

视频 / 音频 / 文档是「转码」里的分段控件，三份 `WizardSession` 互相独立。不要做成五个底栏 Tab。

```
┌─────────────────────────┐
│ 转码              转换  │
│ [ 视频 | 音频 | 文档 ]  │
│ 预览 / 裁切（有源时）    │
│ 文件                    │
│ 设置                    │
│ [ 开始转换 ]            │
│  转码     历史     我的  │
└─────────────────────────┘
```

- 仅转码首页：导航栏右侧「转换」和列表底部「开始转换」是同一操作、同一启用条件。子页不放这个按钮。无可用源、探测未完成、输出未就绪、或当前已有任务在跑时禁用（与现 Android `startEnabled` 相同，不在进行中再入队）。
- 格式 / 画质 / 分辨率 / 存放：push。返回按钮标签是「转码」，不写「返回」。
- 系统侧滑返回保留，不覆盖。
- 选照片、选文件、选输出目录：系统 picker（modal）。
- 切到其它 Tab 时，「我的」详情收回根页；转码子页保留。

系统返回：

| 当前 | 行为 |
| --- | --- |
| 我的 · 详情 | 回到「我的」根页 |
| 转码 · 设置子页 | 回到转码首页 |
| 各 Tab 根页 | 交给系统 |

## 视觉

生产界面不用 hex。浅色、深色、增强对比都走语义色。

| 角色 | 用法 |
| --- | --- |
| 页面底 | `systemGroupedBackground` |
| 分组 | `secondarySystemGroupedBackground` |
| 主字 / 次字 | `label` / `secondaryLabel` |
| 分隔线 | `separator` |
| 主操作 / Tab 选中 | Asset Catalog 强调色「轻转码橙」，含 any / dark / 高对比变体（浅色参考桌面 `#c45a2a`） |
| 危险 | `systemRed`（删除、不可导入） |

字体只用文本样式：`largeTitle`、`headline`、`body`、`subheadline`、`footnote`。禁止为正文写死点数。触控 ≥ 44pt。内容避开 Dynamic Island 和 Home Indicator；只有背景可以铺满。

图标一律 SF Symbols。不要自绘 Tab 图标。

Reduce Motion 打开时，裁切播放头和入队反馈改淡入淡出，不用弹簧位移。

## 转码

大标题「转码」，其下分段 **视频 | 音频 | 文档**。

### 文件

| 分段 | 添加行 |
| --- | --- |
| 视频 | 相册、文件 |
| 音频 | 音乐、相册（抽音轨）、文件 |
| 文档 | 相册（图片）、文件 |

相册用 `PhotosPicker` / `PHPicker`，不申请「所有照片」。文件用 `fileImporter`。音频「音乐」走文件选择器，限定音频类型。

已选文件是列表行：文件名 + 容器 · 编码 · 宽高 · 帧率 · 时长。探测中写「正在读取格式…」。不可导入：错误文案用 `systemRed`，并保留说明文字，不只靠颜色。左滑「移除」。正在转码的那条没有移除。点一行即选中。

空列表时分组 footer：

- 视频：「添加要转码的视频」
- 音频：「添加音频，或从视频抽出音轨」
- 文档：「添加图片、PDF 或办公文档」

同一批文档必须同类型。点开始时若混选或源不合法，留在首页出提示，已选文件不清掉。

### 预览与裁切

有选中且可导入的源时，文件分组上方出现预览卡。

- 音视频：系统能播的用 `AVPlayer`；不能播的只留时间轴，**不用** FFmpeg 出预览片。
- 时间轴：浅轨、选区、手柄、播放头。「设为开始」「设为结束」「恢复整段」。已裁剪的文件旁标明「已裁剪」。
- 文档：页范围（从 1 起，闭区间），语义对等于裁切起止。

### 设置

分组披露行，右侧是当前值。点进去是勾选列表。

| 行 | 何时出现 | 值例 |
| --- | --- | --- |
| 格式 | 始终 | `MP4 · H.264` |
| 画质 | copy / 无损音频 / 部分文档预设不出现 | `标准` |
| 分辨率 | 仅需重编码的视频 | `原尺寸` |
| 存放 | 始终 | `下载` |

**格式：** 视频默认四项（`mp4-h264`、`mp4-copy`、`mp4-h265`、`mov-h264`）加「更多格式」；展开后该项改「收起」。选中非常用项时第四张换成该项，规则与 Android `collapsedPresetCards` 相同。音频、文档只列出当前源能转的目标。`mp4-copy` 只留说明，不出现画质和分辨率。

**画质 / 分辨率：** 每行标题 + 一句 hint。选项与 Android 相同（原画 / 标准 / 节省体积；原尺寸 / 1080p / 720p / 480p）。

**存放：**

| 分段 | 选项 | 默认 |
| --- | --- | --- |
| 视频 | 照片、下载、自选 | 下载 |
| 音频 | 音乐、下载、自选 | 音乐 |
| 文档（图片结果） | 照片、下载、自选 | 照片 |
| 文档（PDF / TXT 等） | 文档、下载、自选 | 文档 |

自选走文件 App 目录选择，用安全作用域书签记住。存到照片用 Add Only，不申请完整图库。

### 开始转换

点「开始转换」或导航栏「转换」，且真正入队之后：

1. 只 reset **当前**分段的会话（清源、回到首页）。
2. 切到「历史」，并把历史分段切到对应类型（视频页抽出的 MP3 / M4A 进历史「音频」）。
3. 开始 Live Activity。成功触感 `.success`。

入队失败留在本页，用首页提示条说明原因，不弹空白 alert。第一次入队再请求通知权限；被拒也继续转。

## 历史

大标题「历史」。分段 **视频 | 音频 | 文档**。只渲染当前段。新任务在上。

行：文件名、源 → 目标、状态或百分比。进行中用 `ProgressView`。

| 状态 | 点按 | 左滑 | 长按菜单 |
| --- | --- | --- | --- |
| 排队 / 进行中 | 无 | 取消 | 无 |
| 失败 / 已取消 | 再试一次 | 删除 | 再试一次 |
| 完成 | Quick Look 预览；系统不能预览的（如 docx）改为分享面板 | 删除 | 分享、重命名 |

有已结束任务时，导航栏 trailing「清空」。清空和删除都要确认；清空只清**当前分段**里已结束的任务。

空态用 `ContentUnavailableView`，按钮「去转码」切到转码 Tab 对应分段。

分享：`ShareLink`。重命名：带文本框的 alert，规则与 Android 输出命名一致。

## Live Activity

仅进行中的那一个任务。锁屏与 Dynamic Island 四种展示都要有：文件名、百分比、取消。点按打开历史对应条。任务结束立即撤掉。锁屏上不出现完整本地路径。

后台：用 `BGTaskScheduler` processing 任务尽量把当前 FFmpeg 跑完。系统仍可能在后台挂起或杀掉进程；被杀后该任务记为失败，可在历史重试。不申请音频后台、不伪装成播放器保活。

## 我的

`Form` 分组列表，不要 sheet。

1. 局域网访问、语言
2. 隐私协议、使用条款
3. 关于

关于：应用名「轻转码」、版本号、`不上传 · 不联网`。协议和使用条款是应用内长文，不联网；正文事实与 Android 相同（本地处理、不收集账号；需对源文件有权；可在历史重试）。

语言页对齐 [Android 多语言](2026-09-17-android-i18n-design.md)：跟随系统、简体、繁体、English、日本語、한국어。默认跟随系统；对不上五种时用英文。改完当前界面立刻换文案。

局域网：开关、口令、地址、绑定 Wi‑Fi 的行为对齐现 Android 规格，不改电脑网页协议。系统「本地网络」权限在用户打开开关时再要；被拒则开关关回去，并给一条可跳系统设置的说明。

权限被拒（照片写入、通知、本地网络）时，在对应功能处说明，并提供「打开设置」，不要在启动时连环弹窗。

## 会话与领域

三份会话，切分段不丢：

```
WizardSession(
  sources, preset, quality, size, output, container
)
```

默认：视频 `mp4-h264` + 下载；音频 `audio-mp3` + 音乐；文档随源类型变（图片 `image-jpg` + 照片，其余见 Android 文档规格）。

预设 ID、校验、输出命名、`Job` 状态机与 Android `domain` 同语义。文档任务仍进同一张队：同一时间只跑一个任务。

## 错误与空态

探测失败、不可导入、输出目录不可写、混选文档、无音轨走音频预设：映射为现有中文（及当前语言）提示，出现在转码首页提示条，用户可关掉。

无可用源时主按钮禁用，不把用户推进空的格式页。

## 测试

- Domain 纯函数：步骤能否开始、折叠预设、`mp4-copy` 隐藏分辨率、各分段存放默认、历史分段分流、清空只影响当前段。
- UI：三 Tab 文案与 SF Symbol；转码分段；「转换」启用条件；入队后切到历史对应段。
- VoiceOver：图标按钮有 label；不可导入行能读到错误原因。
- 真机冒烟：浅色 / 深色；Dynamic Type 最大档正文不被裁切；三步能走完（加文件 → 改格式 → 开始）；预览在首页；Live Activity 随任务起停。
- 现有 Android / 桌面测试不在本设计范围内。

## 成功标准

侧载或 TestFlight 安装后，是系统分组列表 + 底栏三 Tab，跟浅色 / 深色。打开先到转码首页，分段切换视频 / 音频 / 文档，设置用 push。点开始后在历史看进度。转码结果与 Android 同预设、同命名规则。界面不像桌面暖灰页，也不像自绘安卓仿 iOS。

HIG 自检目标 9/10：Safe Area、44pt、语义色、Dynamic Type、VoiceOver、Tab + Stack、SF Symbols、触感。扣 1 分：第一版 iPad 不做 sidebar。

## 落地顺序

一份设计，实现按切面写计划，不一次铺开：

1. `ios/` 工程、Domain、视频转码首页 / 设置 push、历史（仅视频）、我的（协议 / 条款 / 关于 / 语言）
2. 音频、文档、历史三分段
3. Live Activity 与后台任务
4. 局域网访问（对齐现网页协议）

## 明确不做

- App Store、账号、登录、联网转码或拉文案
- WebView / 共享 React / 与 Android 共界面代码
- 桌面暖灰、硬编码浅色 hex、自定义 dock、五个底栏 Tab
- 用 FFmpeg 生成裁剪预览片
- 改预设参数、队列语义、输出命名
- 小组件、App Clip、Siri、Action Button、Apple Watch
- iPad 分栏、macOS 菜单栏版（桌面仍是现有 Tauri）
- 音频后台保活
