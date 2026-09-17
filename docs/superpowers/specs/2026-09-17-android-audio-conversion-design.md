# Android 音频转换 Tab

在现有底栏上再加一条与「视频转码」平行的「音频转换」。叠在 [底部三 Tab](2026-09-17-android-root-tabs-design.md) 和 [引导式三步](2026-09-17-android-desktop-ui-parity-design.md) 上。队列、前台服务、FFmpeg 泵仍是一套；本设计改 `ui/`，并给 `domain` / `data` 补音频预设、容器和「音乐」输出。

## 目标

- 底栏四个 Tab，从左到右：**视频转码**、**音频转换**、**历史记录**、**我的**。
- 音频页复用同一套三步：添加（预览 + 裁切）→ 格式 → 存放。
- 源可以是音频文件，也可以是带音轨的视频（抽音轨）。
- 目标格式：MP3、M4A（AAC）、WAV、OGG（Opus）。
- 存放：音乐 / 下载 / 自定义。
- 历史记录一个 Tab，页内 **视频 | 音频** 两个列表。
- 视频页第二步的「只导出音频」MP3 / M4A 保留。
- 视频会话和音频会话互相独立：切 Tab 不丢已选文件、步骤、裁切和存放。

## 方案

Compose 顶层 `RootTab` 增加 `Audio`。不上 Navigation。`AppViewModel` 持有两份 `WizardSession`，`start()` 用当前 Tab 对应的那份。`JobStore` 不拆表。

```
内容区（当前 Tab）
下一步 / 开始转码或开始转换（仅视频或音频 Tab）
视频转码 | 音频转换 | 历史记录 | 我的
```

不采用：再写一套互不相干的音频界面、给音频单独开队列、历史再占一个底栏 Tab。

## 会话状态

```
enum class ConvertMode { Video, Audio }

data class WizardSession(
    sources,
    preset,          // 视频默认 mp4-h264；音频默认 audio-mp3
    quality,         // original / standard / small
    size,            // 仅视频
    output,          // 视频：相册/影库/下载/自定义；音频：音乐/下载/自定义
)
```

`AppScreen` 为两个 mode 各保留 `step` / `showAll` / `selectedUri`。切走再回来，两套都还在。

点「开始转码」或「开始转换」且真正入队后：

1. 只 `reset` **当前** mode 的会话（清源、回到第 1 步）。
2. 切到「历史记录」。
3. 历史分段切到该 mode：视频任务打开「视频」，音频任务打开「音频」。

入队失败则留在当前页，照旧出提示条。

## 历史分流

历史标题下是两段控件：**视频 | 音频**。只渲染当前分段。新任务在上。打开、分享、重命名、删除、取消、再试一次、清空已完成都复用现有 `JobRow`；**清空已完成只清当前分段里已结束的任务**。

`isAudioHistoryJob(job)` 为真当且仅当：

- 预设为 `audio-mp3`、`audio-aac`、`audio-wav`、`audio-ogg`，或
- 容器为 `mp3`、`m4a`、`wav`、`ogg`

其余进视频列表。因此从**视频页**抽出的 MP3 / M4A 出现在历史「音频」。

空态：

- 视频：「还没有视频记录」
- 音频：「还没有音频记录」

分段状态：若刚从某转换 Tab 开始任务，打开对应分段；否则记住用户上次在历史里选的分段。

## 音频三步

### 1. 添加、预览、裁切

标题「添加文件」，副标题「预览并裁切要保留的片段」。空态三个入口，居中、与视频页添加区同一套视觉：

| 按钮 | 行为 |
| --- | --- |
| 音乐 | `OpenMultipleDocuments`，MIME `audio/*` |
| 相册 | `PickMultipleVisualMedia`，仅视频（抽音轨） |
| 文件 | `OpenMultipleDocuments`，MIME `audio/*` 与 `video/*` |

探测后：音频会话里 **没有音轨的文件不可导入**（红底行 +「没有音频流，无法导出音频」），不能靠它进入第 2 步。有音轨的视频可以导入。

预览：有画面的视频源用现有 Media3 预览 + 裁切轨；纯音频只显示播放条和裁切轨，不留空视频窗。裁切控件与视频页相同（设为开始 / 设为结束 / 恢复整段）。

### 2. 格式

标题「选择格式」。四张主卡片，无「更多」：

| 预设 | 标题 | 说明 |
| --- | --- | --- |
| `audio-mp3` | MP3 | 兼容性最好 |
| `audio-aac` | M4A · AAC | 苹果设备和相册常用 |
| `audio-wav` | WAV | 无损，文件更大 |
| `audio-ogg` | OGG · Opus | 体积更小 |

默认 `audio-mp3`。MP3 / M4A / OGG 显示音质三档（原画 / 标准 / 节省体积），码率沿用现逻辑：320 / 192 / 128 kbps。WAV 不显示音质，固定说明「原始采样，不压缩，文件更大」。

视频页格式列表不因本功能改常用四卡；展开后仍有「只导出音频」的 MP3 / M4A。视频页不出现 WAV / OGG。

### 3. 存放

三张卡片：

| 选项 | 路径 / 行为 |
| --- | --- |
| 音乐 | MediaStore 音频，相对路径 `Music/轻转码` |
| 下载 | `Download/轻转码` |
| 自定义 | SAF 目录；未选好时「开始转换」不可用 |

音频会话默认「音乐」。不提供相册、影库。打开 / 分享使用音频 MIME：`audio/mpeg`、`audio/mp4`、`audio/wav`、`audio/ogg`。

Dock：第 1 / 2 步「下一步」；第 3 步「开始转换」。摘要用「文件 / 音质」，不提分辨率。

## 引擎与输出

在现有 `audio-mp3` / `audio-aac` 旁增加：

- `audio-wav`：容器 `wav`，无视频，编码器 PCM 16-bit（`pcm_s16le`），**不写** `-b:a`。
- `audio-ogg`：容器 `ogg`，无视频，编码器 `libopus`，按音质写 `-b:a`。

`isAudioOnly`、容器白名单、`ffmpegMuxer`、`extensionFor`、`outputMimeType`、`containerAcceptsAudio` 都要认 `wav` / `ogg`。音频-only 参数一律 `-vn`。WAV 的 `audioBitrateKbps` 为 `null`，参数里不出现 `-b:a`。

`OutputTarget.Kind` 增加 `Music`。`mediaStoreRelativePath(Music) = "Music/轻转码"`，写入 `MediaStore.Audio`。

队列仍单任务泵：视频、音频任务按提交顺序同一条服务执行。

## 返回

| 当前 | 系统返回 |
| --- | --- |
| 我的 · 详情 | 我的根页 |
| 视频转码 · 第 2/3 步 | 上一步 |
| 音频转换 · 第 2/3 步 | 上一步 |
| 其它 Tab 根页 | 交给系统 |

切到其它 Tab 时，「我的」详情仍收回根页。音频步骤只在音频 Tab 上后退，不改视频会话的 `step`。

## 错误与空态

- 探测失败：该行留在列表并显示原因，其它已选文件不受影响。
- 无音轨：音频会话标红，不计入 `importableCount`。
- 自定义目录未授权或不可写：提示条，不入队。
- 转码失败：历史对应分段里显示错误，「再试一次」用原 `OutputConfig`（含 trim）。
- 第 1 步无可导入文件：「下一步」禁用，摘要「先添加音频或带声音的视频」。

## 测试

- `rootTabLabel` 四个文案与顺序。
- `isAudioHistoryJob`：`audio-*`、WAV/OGG、以及视频页 `audio-mp3` 进音频；`mp4-h264` 进视频。
- `consumeRootBack`：音频第 2 步回第 1 步；历史根页不消费返回。
- `resolveConfig` / `buildFfmpegArgs`：WAV 无 `-b:a` 且 muxer 为 `wav`；OGG 为 `libopus` + `ogg`；无音轨文件校验失败。
- 现有视频向导、队列、输出测试继续通过。
- 真机冒烟：音乐选曲转 MP3；相册选视频抽 M4A；文件选 WAV 源转 OGG。完成后历史「音频」可见，打开能播。

## 成功标准

侧载后底栏能进「音频转换」，三步能走完。视频页抽音频仍可用。历史能分开看。转出的 MP3 / M4A / WAV / OGG 能被系统播放器打开。

## 明确不做

- 桌面端音频 Tab
- iOS
- FLAC、变调 / 变速、歌词、铃声专用页
- 音频单独队列或单独前台服务
- 从视频页格式列表加入 WAV / OGG
- 账号、联网
