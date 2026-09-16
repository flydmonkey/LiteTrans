# 轻转码 Android 原生版

在同一仓库新增独立的 Kotlin 原生 App，功能和桌面「轻转码」对齐：本机转码、不上传。第一版只做 Android 侧载 APK。iOS 不在本设计范围内。

## 背景

现有桌面应用是 Tauri 2 + React + 捆绑 FFmpeg sidecar。预设、参数白名单、探测、单任务队列、`.partial` 再改名，都已经在 Rust 里跑通。Android 不复用 Tauri / React / Rust，用 Kotlin 按同一套语义重写。

## 目标与约束

- 桌面有的预设都能在 Android 上入队并产出对应文件。
- 文件始终留在本机，不上传、不联网转码。
- 第一版只发可侧载的 APK，不上 Google Play、不上国内应用商店。
- 最低系统 Android 10（API 29），只打 arm64-v8a。
- 应用名「轻转码」，`applicationId` 为 `com.videoconverter.android`。
- 不与桌面共享代码，不与桌面同步设置。

## 方案

Jetpack Compose 单 Activity 应用 + 捆绑 ffmpeg/ffprobe（打成 native 库）+ `ProcessBuilder` 调用。H.264 / H.265 优先硬件编码，其余走软件编码。转码在前台服务中进行。

不采用：Tauri Android、FFmpeg Kit Java API、纯 MediaCodec（无法对齐 WebM / GIF / AVI / 不重编码）。

## 仓库布局

桌面工程保持不动。新增：

```
android/
  app/
    src/main/java/com/videoconverter/android/
      ui/          # Compose 界面
      domain/      # MediaInfo、OutputConfig、Job、预设解析、校验
      engine/      # 探测、参数拼装、进程、进度解析、队列泵
      data/        # 设置、输出目录、SAF
    src/main/jniLibs/arm64-v8a/   # 由脚本拉取，不入库
    src/test/                      # 单元测试
    src/androidTest/               # 仪器测试
  scripts/fetch-ffmpeg.mjs
```

FFmpeg 二进制不提交 git（与桌面 `src-tauri/binaries/` 相同）。`android/scripts/fetch-ffmpeg.mjs` 在脚本内写死下载 URL 与 sha256，构建前必须先跑该脚本；放到 `jniLibs/arm64-v8a/`，文件名为 `libffmpeg.so` 与 `libffprobe.so`，以便安装时被系统抽成可执行 native 库。构建必须包含：libx264、libx265、libvpx-vp9、libmp3lame、libopus、aac、mpeg4、gif、mediacodec。许可说明沿用根目录 README：独立进程调用，发布前自行确认 GPL/LGPL。

## 模块边界

| 单元 | 做什么 | 怎么用 | 依赖 |
| --- | --- | --- | --- |
| `domain` | 预设表、配置解析、白名单校验、命名 | 纯 Kotlin，无 Android API | 无 |
| `engine` | 调 ffprobe/ffmpeg、解析进度、杀进程 | 队列泵调用 | domain、本机二进制 |
| `data` | DataStore 记住预设/画质/输出树 URI；把用户选的文件变成引擎能读的路径或 fd | UI 与引擎之间 | Android 存储 API |
| `TranscodeService` | 前台服务，同一时间只跑一个 FFmpeg | 入队后启动 | engine、data |
| `ui` | 三步界面与任务列表 | 观察队列状态 | domain、data、service |

## 领域模型（对齐桌面）

类型与桌面 `types.ts` / `presets.rs` / `queue.rs` 同语义：

- `MediaInfo`：`sourceUri`（用户选中的 content URI，作为稳定身份）、展示名、时长、容器、视频/音频编码、宽高、帧率、声道、`importable`、错误、可选裁切起止。重试和队列只认 `sourceUri`，运行时再解析成 fd 或缓存路径。
- `OutputConfig` / `ResolvedConfig`：预设、容器、编码器、画质、分辨率上限、码率、裁切。
- `Job`：`queued` / `running` / `completed` / `failed` / `cancelled`，进度 0–100，输出位置或错误文案。
- 预设 ID 必须与桌面一致：`mp4-h264`（默认）、`mp4-h265`、`mp4-copy`、`mov-h264`、`mkv-copy-friendly`、`mkv-h265`、`webm-vp9`、`avi-mpeg4`、`gif`、`audio-mp3`、`audio-aac`。

校验规则与 `args.rs` 的 `validate` 相同，包括：

- 无音频流不能走音频预设。
- 无视频流不能导出 GIF 或复制视频。
- `copy` 时目标容器必须接得住源编码；复制时不能同时改分辨率或帧率。

输出命名跟桌面**代码**（不是 README 旧描述）：`{stem}.{ext}`，冲突则 `{stem}-1.{ext}`、`{stem}-2.{ext}`。临时文件必须是 `{stem}.partial.{ext}`，不能是 `{stem}.{ext}.partial`。路径含 `[` `]` 时用 `file:` 前缀传给 FFmpeg。

## 转码引擎

参数只从白名单拼装，禁止把用户字符串拼进 shell。进度读 FFmpeg `-progress` 的 `out_time_ms`（按微秒解析，与桌面一致）。

编码器映射：

| 逻辑编码器 | Android 优先 | 回退 |
| --- | --- | --- |
| h264 | `h264_mediacodec` | `libx264` |
| h265 | `hevc_mediacodec` | `libx265` |
| vp9 | `libvpx-vp9` | 无 |
| mpeg4 | `mpeg4` | 无 |
| gif | `gif` | 无 |
| copy | `copy` | 无 |
| aac / mp3 / opus / copy | 与桌面相同 | 与桌面相同 |

硬件编码器探测失败或该次进程失败且错误指向 mediacodec 时，同一任务自动改软件编码重试一次。界面不报这次回退。其它 FFmpeg 失败则任务标失败，展示可读中文原因。

队列：一次一个 FFmpeg 进程。入队时跳过不可转的源并返回原因。成功则把 partial 改名为最终文件名；取消或失败杀进程并删除 partial。探测并发上限 4，与桌面相同。

转码必须在前台服务中运行。`targetSdk` 35：API 35+ 使用 `foregroundServiceType=mediaProcessing`，更低版本使用 `dataSync`。通知渠道用于进度和保活，文案含当前文件名和百分比。

## 文件流

不申请 `MANAGE_EXTERNAL_STORAGE`。输入用系统相册选择器和文档选择器（可多选），只访问用户选中的 URI。

FFmpeg 读输入的顺序：

1. 用 `contentResolver.openFileDescriptor` 拿到 fd，传 `/proc/self/fd/{fd}`。
2. 若该 URI 不能 seek（ffprobe/ffmpeg 立刻失败），再把内容拷到应用缓存后用本地路径。

输出：

- 先在应用专属目录写成 `{stem}.partial.{ext}`，成功后改名为 `{stem}.{ext}`。
- 再写入用户目录。默认位置是 MediaStore 相对路径 `Download/轻转码/`。用户也可通过 SAF 选文件夹；该树 URI 持久化授权，下次沿用。
- 若默认下载目录写入失败（厂商差异），回退到应用外部专属目录，并在界面提示实际位置。

不删除原文件。不在用户没选的情况下扫描整机媒体库。

## 界面

Material 3，中文，竖屏单栏，跟随系统深色模式。顶栏：「轻转码」+「不上传 · 不联网」。底部固定「开始转码」。没有拖放。

1. **源视频**：添加（相册 / 文件）。卡片显示文件名与「容器 · 编码 · 宽高 · 帧率 · 时长」。探测中显示「正在读取格式…」。不可导入显示桌面同款错误。未在转码的条目可移除。点卡片进入裁剪页：系统能播的用 Media3 预览；不能播的只保留时间轴和「设为起点/终点」。第一版**不做**桌面那种先用 FFmpeg 转出预览片的流程。
2. **格式**：默认展开 `mp4-h264`、`mp4-copy`、`mp4-h265`、`mov-h264`，「更多」展开其余。画质：原画 / 标准 / 节省体积。分辨率：原尺寸 / 1080p / 720p / 480p。音频预设只显示音质；`mp4-copy` 隐藏分辨率。
3. **输出和任务**：输出目录一行。任务可取消当前、重试失败、清除已结束。完成后提供打开文件、分享、在文件夹中查看。

设置用 DataStore 记住：预设、画质、分辨率、输出目录（SAF URI 或默认下载目录标记）。

## 错误处理与进程生命周期

对用户展示中文原因，不展示未包装的进程 stderr。覆盖：

- 文件无法探测或不可转码
- 校验失败（无音视频流、copy 与容器不兼容、copy 时改分辨率/帧率）
- 输出目录不可写或 SAF 授权丢失（提示重新选择）
- 缓存空间不足
- FFmpeg 找不到或非零退出
- 读媒体/通知权限被拒（通知被拒时仍转码，但可能看不到通知）

取消：杀当前进程、删 partial、状态 `cancelled`。

应用进程被划掉或服务被系统杀掉后再打开：原先 `running` 的任务改为 `failed`，错误为「转码被中断」；`queued` 的任务保持排队，**不**自动启动前台服务。用户再点「开始转码」（只启动已有队列，不再重复入队）或对失败任务点「重试」后才继续，避免后台偷跑耗电。

## 测试

- **单元测试**（JVM）：预设解析、白名单校验、命名冲突、`partial` 路径、进度行解析。意图对齐桌面 Rust 测试。
- **仪器测试**：选文件 → 探测 → 入队 → 取消 / 重试；默认输出进「下载/轻转码」；重名出现 `-1`。
- **冒烟**（真机或 arm64 模拟器，真实视频）：MP4 H.264 全流程；另测 `mp4-copy`、抽音频、裁切。设备 PATH 里没有 ffmpeg 也必须成功。

## 成功标准

侧载 APK 安装后，桌面列出的每个预设都能对一条**通过该预设校验**的源视频入队，并在默认或自选目录得到对应扩展名的成品（例如 `mp4-copy` 用已是 H.264/AAC 的 MP4 源）；原文件仍在；转码过程不发起网络请求。

## 明确不做（本设计）

- iOS
- Google Play / 国内商店上架、签名与商店合规
- 32 位（armeabi-v7a）包
- 与桌面设置云同步或本地同步
- 用 FFmpeg 生成裁剪预览片
- 自定义编码器/容器高级面板（桌面主路径也是预设 + 画质/分辨率/裁切）
- 同时跑多个 FFmpeg 进程
