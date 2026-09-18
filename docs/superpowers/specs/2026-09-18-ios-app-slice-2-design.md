# 轻转码 iOS 第 2 刀

叠在 [iOS 主规格](2026-09-18-ios-app-design.md) 和第 1 刀已落地的 SwiftUI App 上。本刀覆盖主规格落地顺序第 2 项（音频、文档、历史三分段），并**提前捆绑 FFmpeg**（主规格原文写在第 2 刀之后；以本文为准）。

领域语义对齐：

- [Android 原生版](2026-09-16-android-native-transcoder-design.md)
- [音频转换](2026-09-17-android-audio-conversion-design.md)（预设以现网六张卡为准，含 FLAC / AMR）
- [文档转换](2026-09-17-android-document-conversion-design.md)（本刀不做 Office）

HIG：分段是页内切换，不是新的底栏 Tab。[Segmented controls](https://developer.apple.com/design/human-interface-guidelines/segmented-controls)、[Tab bars](https://developer.apple.com/design/human-interface-guidelines/tab-bars)、[Lists](https://developer.apple.com/design/human-interface-guidelines/lists-and-tables)、[Settings](https://developer.apple.com/design/human-interface-guidelines/settings)、[File management](https://developer.apple.com/design/human-interface-guidelines/file-management)、[Accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility)。

## 背景

第 1 刀：三 Tab、视频四张主预设（AVFoundation）、历史仅视频、我的（语言 / 协议 / 条款 / 关于）。转码首页没有视频 | 音频 | 文档分段。Domain 已有音频预设 ID，引擎未跑。没有 FFmpeg xcframework。

## 目标与约束

- 转码、历史都能切 **视频 | 音频 | 文档**。三份会话互不覆盖。
- 音频六张卡能入队并产出文件：`audio-mp3`、`audio-aac`、`audio-wav`、`audio-flac`、`audio-ogg`、`audio-amr`。
- 视频「更多格式」能入队：`webm-vp9`、`mkv-copy-friendly`、`mkv-h265`、`avi-mpeg4`、`gif`，以及视频页「只导出音频」的 `audio-mp3` / `audio-aac`。
- 文档：图片六项 + PDF 四项能入队并产出。docx / xlsx 不可导入。
- 四张视频主预设继续走 AVFoundation，不改成 FFmpeg。
- FFmpeg 以 xcframework **进程内**调用。禁止 `Process` / `fork` / 调外部二进制。
- 单任务队列不变。预设 ID、白名单、输出命名、`.partial` 再改名与 Android 相同。
- 最低 iOS 18，只打 iPhone arm64；模拟器需 arm64 slice。不上 App Store。
- 生产 UI 不用 hex；语义色 + Asset Catalog 轻转码橙；触控 ≥ 44pt；Dynamic Type；VoiceOver 不只靠颜色。

## 方案

`ConvertMode`（`video` / `audio` / `document`）是转码页内分段。`AppModel` 持有三份 `WizardSession`。`HistorySegment` 同三值。底栏仍三个 `RootTab`。

队列泵按预设分流：

| 预设 | 引擎 |
| --- | --- |
| `mp4-h264`、`mp4-copy`、`mp4-h265`、`mov-h264` | 现有 AVFoundation |
| 视频更多格式、六张音频（含视频页抽音轨） | 进程内 FFmpeg |
| `image-*`、`pdf-*` | ImageIO + PDFKit |
| `office-pdf` | 本刀不入队 |

不采用：五个底栏 Tab、音频默认「音乐」、把 FFmpeg 当 CLI spawn、用 FFmpeg 出预览片、本刀做 Office → PDF、Live Activity、局域网。

## 仓库布局

桌面与 `android/` 不动。在现有 `ios/` 上增加：

```
ios/
  scripts/fetch-ffmpeg.mjs    # 写死 URL + sha256；产物不入库
  Vendor/FFmpeg/              # gitignore；xcframework
  LiteTrans/
    Domain/                   # ConvertMode、文档预设、FFmpeg 参数、历史分流
    Engine/
      FFmpegRunner.swift      # argv + 进度日志 + cancel
      DocumentEngine.swift    # 图片 / PDF
    UI/                       # 转码分段、历史分段、按 mode 的入口
```

## 模块边界

| 单元 | 做什么 | 怎么用 | 依赖 |
| --- | --- | --- | --- |
| `Domain` | mode、会话默认、折叠预设、文档源类型、历史分段、FFmpeg argv、页范围夹紧、多输出命名 | 纯函数 | 无 |
| `FFmpegRunner` | 执行 argv、解析 `time=`、取消 | 泵在 FFmpeg 任务上调用 | xcframework |
| `DocumentEngine` | 图片编码、PDF 转图 / TXT / 拆分 / 压缩 | 泵在文档任务上调用 | ImageIO、PDFKit |
| `QueuePump` | 单任务；按 `engineKind(preset)` 分发 | 入队后跑 | 上三者 + 现有 AVFoundation |
| `UI` | 分段、入口、设置行随 mode/preset 变 | `AppModel` | Domain、Data |

`engineKind`：四主视频 → `avFoundation`；`isDocumentPreset` → `document`；其余音视频预设 → `ffmpeg`。`office-pdf` 以及 Word/Excel 源在入队前拒绝，泵不会拿到它们。DocumentEngine 若仍收到 `office-pdf`，失败「后续版本提供」。

## 导航与会话

转码大标题下：系统 `Picker` `.segmented`，三项等宽名词「视频」「音频」「文档」。切换立即换下面的文件列表、预览和设置行，不需要确定。不要做成底栏第四、第五个 Tab。

```
WizardSession(
  sources, preset, quality, size, output, showAllFormats
)
```

默认：

| mode | preset | output |
| --- | --- | --- |
| video | `mp4-h264` | 下载 |
| audio | `audio-mp3` | 下载 |
| document | `image-jpg`（尚无源或源为图片）；源为 PDF 时改为 `pdf-image` | 图片结果 → 照片；PDF/TXT → 文档 |

切分段不丢该份会话。点「开始转换」或导航栏「转换」且真正入队后：只 `reset` **当前** mode；切到历史 Tab；历史分段切到 `historySegmentAfterEnqueue(mode, preset)`（视频页抽出的 MP3/M4A 进音频）。

切到其它 Tab 时：我的详情收回根页；转码子页（格式/画质/分辨率/存放）保留。

## 添加与预览

| 分段 | 入口 |
| --- | --- |
| 视频 | 相册（仅视频）、文件（视频类型） |
| 音频 | 「音乐」= `fileImporter` 音频 UTType（不是 Apple Music 资料库）；相册（仅视频，抽音轨）；文件（音频+视频） |
| 文档 | 相册（仅图片）；文件（图片 + PDF；也可选到 docx/xlsx，但标红不可导入） |

空列表 footer：

- 视频：「添加要转码的视频」
- 音频：「添加音频，或从视频抽出音轨」
- 文档：「添加图片、PDF 或办公文档」

音频：没有音轨的源不可导入，文案「没有音频流，无法导出音频」。有音轨的视频可以导入。

文档：新文件必须与已选 `DocumentSourceKind` 相同，否则提示「请一次只加同一种文件」，已选保留、新文件不进入。`docx` / `xlsx` 行标红「后续版本提供」。`.doc` / `.xls` / WPS / 其它：标红「不支持此格式」。HEIC 只作图片输入，不作输出。

预览（有选中且可导入的源时，文件分组上方）：

- 音视频：系统能播用 `AVPlayer`；不能播只留时间轴。**不用** FFmpeg 出预览片。纯音频不留空视频窗。
- PDF：`PDFView` 当前页；页范围默认 1…N，从 1 起闭区间，夹紧到 `clampPageRange`。
- 图片：系统能解码则显示。

Reduce Motion：播放头和入队反馈改淡入淡出。

## 格式

设置仍是 push，返回按钮标签「转码」。

**视频** 格式页：`collapsedPresetCards(selectedId, showAll)` 与 Android 相同。折叠为四张主预设 + 「更多格式」。展开后该项改「收起」，列出 `mkv-copy-friendly`、`mkv-h265`、`webm-vp9`、`avi-mpeg4`、`gif`、`audio-mp3`、`audio-aac`。视频页不出现 WAV / OGG / FLAC / AMR。选中非常用项时折叠态第四张换成该项。`mp4-copy` 不出现画质和分辨率。

**音频** 格式页：一次六张，无「更多」。默认 `audio-mp3`。无分辨率行。画质：

| 预设 | 画质 |
| --- | --- |
| `audio-mp3` / `audio-aac` / `audio-ogg` | 原画 320 / 标准 192 / 节省体积 128 kbps |
| `audio-amr` | 原画 12 / 标准 8 / 节省体积 5 kbps |
| `audio-wav` / `audio-flac` | 不显示画质 |

**文档** 格式页由当前批 `DocumentSourceKind` 决定，无「更多」。无源时格式页仍按图片六项（默认 `image-jpg`），但主按钮因无可用源禁用。

| 源 | 预设 |
| --- | --- |
| 图片 | `image-jpg`、`image-png`、`image-webp`、`image-bmp`、`image-gif`（静图一帧）、`image-compress` |
| PDF | `pdf-image`、`pdf-txt`、`pdf-compress`、`pdf-split` |

`pdf-image` 另选输出图格式：JPG / PNG / WebP，默认 JPG。写在 `OutputConfig.container`（`jpg` / `png` / `webp`）。画质行只出现在 `image-compress` 和 `pdf-compress`（高 / 标准 / 更小）。文档互转不出现分辨率行。

源类型变化时：若当前 preset 不属于新类型的卡片，改成该类型默认（图片 `image-jpg`，PDF `pdf-image`），并把存放改成该结果类型的默认（图片 → 照片，PDF/TXT → 文档）。

## 存放

`OutputKind` 增加 `documents`。本刀**不**增加 `music`。

| 分段 / 结果 | 选项 | 默认 |
| --- | --- | --- |
| 视频（非只导出音频） | 照片、下载、自选 | 下载 |
| 视频且当前预设为 `audio-mp3` / `audio-aac` | 下载、自选 | 若当前是照片则改下载 |
| 音频 | 下载、自选 | 下载 |
| 文档且 `documentResultIsImage` | 照片、下载、自选 | 照片 |
| 文档且结果为 PDF / TXT | 文档、下载、自选 | 文档 |

「文档」= 应用 `Documents/` 目录，经「文件」App / 文件共享可见（现有 `UIFileSharingEnabled`）。「下载」= 第 1 刀已有沙盒下载目录。自选 = 目录选择 + 安全作用域书签；`bookmark == nil` 时 `outputReadyToStart` 为假，主按钮禁用。照片写入仍是 Add Only。

`usesPersistentSandboxOutput`：`photos`、`downloads`、`documents` 为真。

## 引擎

### FFmpeg xcframework

`ios/scripts/fetch-ffmpeg.mjs` 与 Android 脚本同一模式：写死 `ARCHIVE_URL` 与 `EXPECTED_SHA256`（实现计划第一项引擎任务选定具体制品并填进脚本，规格不留空 URL）。校验失败则退出非零。产物放到 `ios/Vendor/FFmpeg/`（gitignore）。构建前未拉取则 `xcodebuild` 失败，错误指向该脚本。

xcframework 必须含 `ios-arm64` 与 `ios-arm64-simulator`。配置串必须含：`--enable-libx264`、`--enable-libx265`、`--enable-libvpx`、`--enable-libmp3lame`、`--enable-libopus`。许可说明沿用根 README（独立库调用，发布前自行确认 GPL/LGPL）。找不到匹配预编译时，脚本用同一套 flag 打 xcframework，不得改成 spawn CLI。

`FFmpegRunner`：传入 argv 数组（不是一条 shell 字符串）、工作目录、进度回调、取消。进度解析日志 `time=`，用现有 `parseFfmpegTimeLine` + 源时长。退出码非 0 且未取消 → 失败。取消后不得把已取消任务的 `outputPath` 留给后续完成任务占用（第 1 刀规则）。

探测：先 AVFoundation；系统打不开或不完整时用捆绑 ffprobe。预览永远不跑 FFmpeg。

`buildFfmpegArgs` 从 Android `FfmpegArgs.kt` 按同表翻译。GIF / WebM / MKV / AVI / 六张音频走这里。四主视频**不**调用 `FFmpegRunner`。

### 文档

不经过 FFmpeg。

| 预设 | 行为 |
| --- | --- |
| 图片互转 | ImageIO 解码，按目标编码。GIF 输出静图。 |
| `image-compress` | 尽量保持源容器；JPG/WebP/GIF 调质量；PNG/BMP 缩小边长。 |
| `pdf-image` | PDFKit 按页光栅化。 |
| `pdf-txt` | PDFKit 抽文本；空结果失败。 |
| `pdf-split` | 范围内每页一个 PDF。 |
| `pdf-compress` | 缩小内嵌图后写回，不把整页拍成图再装回。无法缩则失败。 |

加密 PDF：失败，「不支持加密 PDF」。无密码框。扫描件转 TXT：失败，「没有可提取的文字」。压缩失败：「无法压缩此 PDF」。

进度：PDF 类按页；单张图片 0→100。

多输出仍是**一条** `Job`。`Job.outputPath` 为第一份；`Job.outputPaths` 为全部（仅一份时就是含 `outputPath` 的单元素列表）。文件名：`{stem}-001.{ext}` 三位序号，从页范围第一页起算。打开 / 分享第一份；重命名只改历史展示名；删除这条时删掉 `outputPaths` 里仍存在的全部文件。已取消任务未完成的输出不进入「占用路径」集合。

`MediaInfo` 增加 `pageCount`、`pageStart`、`pageEnd`（1-based，闭区间）。音视频任务这些字段为 `nil`。

## 历史

大标题下分段 **视频 | 音频 | 文档**，只渲染当前段。点按 / 左滑 / 长按与第 1 刀相同，作用于当前段的行。

- `isDocumentHistoryJob`：`isDocumentPreset(preset)`（集合含 `office-pdf`，本刀不会产生该类任务）。
- `isAudioHistoryJob`：预设为六张音频之一，或 `resolveConfig` 的 container 为 `mp3` / `m4a` / `wav` / `ogg` / `flac` / `amr`。
- 其余进视频。因此视频页抽出的 MP3 / M4A 出现在历史「音频」。

`remainingJobsAfterClearFinished(jobs, segment)`：其它段原样保留；当前段只留下排队 / 进行中。空态 `ContentUnavailableView`，按钮「去转码」切到转码对应 `ConvertMode`。有当前段已结束任务时才显示「清空」。

## 错误

首页提示条（可关掉），不弹空白 alert：

- 入队失败、自定义目录未授权、文档混选。

行内红色文字（同时可读）：无音轨、不支持的格式、Office 后续版本、探测失败。

历史失败可再试一次，沿用原 `OutputConfig`（含 trim / 页范围 / 图格式）。FFmpeg 被系统杀掉：该任务失败。

## 测试

- Domain：三份会话互不覆盖；`collapsedPresetCards`；音频六张无分辨率；`shouldShowQualityRow` 对 WAV/FLAC 为假、对 MP3 为真；存放默认（音频下载、图片照片、PDF 文档）；`isAudioHistoryJob` / `isDocumentHistoryJob` / `historySegmentAfterEnqueue`；清空只影响当前段；`sameDocumentKind`；`clampPageRange`；多输出 `原名-001`；`engineKind` 四主预设 / `webm-vp9` / `audio-mp3` / `image-jpg` / `pdf-split`。
- `buildFfmpegArgs`：WAV 无 `-b:a` 且 muxer `wav`；OGG `libopus`；AMR 码率 12/8/5；GIF 走 `gif`。
- UI：转码/历史分段文案；入队后切对应历史段；音频存放无「音乐」。
- 模拟器冒烟：音频转 MP3 进下载；相册图转 JPG 进照片；小 PDF 拆分进文件 App；`mp4-h264` 仍走 AVFoundation。

## 成功标准

模拟器或真机可切视频 / 音频 / 文档，设置仍是 push。音频六种、视频更多格式、图片六项、PDF 四项能入队并产出对应文件。Office 进不了队列。四张主视频仍走 AVFoundation。界面仍是系统分组列表 + 三 Tab。

## 明确不做

- Live Activity、`BGTaskScheduler`、局域网
- Office → PDF、`.doc` / `.xls` / WPS、PDF 密码、OCR
- HEIC/TIFF 作为输出；GIF 动画输出
- 音频「音乐」存放档、写入 Apple Music
- App Store、账号、联网转码
- 用 FFmpeg 生成预览片
- 改预设 ID、队列语义、输出命名规则
- 五个底栏 Tab、桌面暖灰、硬编码 hex
- 音频后台保活
