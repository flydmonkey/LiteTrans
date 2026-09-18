# iOS 视频合并

在转码「视频」里增加可选预设：把多个视频按顺序拼成一个 MP4。本功能只做 iOS，不改 Android / 桌面。整条功能放在分支 `ios-video-merge`；不要了就丢掉该分支，不进 `master`。

相关：[iOS App 设计](2026-09-18-ios-app-design.md)。

## 背景

现在多选视频会各自入队，各出一个文件。相册里常有分辨率、编码不一致的片段，用户希望合成一条片子。

## 目标

- 视频 Tab 能选「合并」，把 **至少 2 段** 可导入视频拼成 **一个** MP4（H.264 + AAC）。
- 顺序默认添加顺序，列表可拖动调整。
- 画幅、帧率对齐 **列表第一条**；每段先重编码再拼接，混拍也能合。
- 本机处理，不上传。历史里一条记录、一个输出文件。
- 不要了：删除分支 `ios-video-merge`，不合并进 `master`。

## 非目标

- Android、桌面、TestFlight 必发。
- 单段裁切、转场、配乐、画中画。
- 不重编码的 concat demuxer（参数不一致会失败）。
- AVFoundation `AVMutableComposition`（冷门编码覆盖差）。
- 新的底栏 Tab 或「视频 / 音频 / 文档 / 合并」第四段。

## 方案

### 入口

`PresetCard` id：`video-concat`。放进视频主预设（与 H.264 / 不重编码 / H.265 / MOV 并列，第 5 张）。

选中后：

- 设置行：格式、画质、存放。不显示分辨率（画幅跟第一条走）。
- `allowsTrim("video-concat") == false`，首页不提供裁切。
- `canStart`：可导入视频 ≥ 2，且未在探测、未在转码、输出目录就绪。
- 少于 2 个时开始按钮不可用；文案说明至少两段。

### 顺序

`WizardSession.sources` 顺序即拼接顺序。文件列表支持拖动重排（系统 List 编辑 / `.onMove`），44pt 行高。删除某一段不改变其余相对顺序。

### 入队

现有 `enqueueJobs` 是「一段一个 Job」。`video-concat` 改为 **一个 Job**：

- `sourceUri`：第一段。
- `OutputConfig` 增加有序 `concatSourceUris: [String]`（含第一段，与 sources 顺序一致）。缺省 `[]` 表示不是合并。
- 输出一个路径，命名：`{第一条 stem}-merged.mp4`，冲突走现有 `-001` 规则。
- 输出位置与其他视频相同：照片 / 下载 / 自选。
- 任一段 `importable == false`：整单不入队，提示该段不能转。

上限 20 段；超过则不能开始。

### 引擎

`engineKind("video-concat") == .ffmpeg`。

1. 以第一段的 `width`/`height`（偶数化）和 `frameRate`（缺省 30）为对齐目标。
2. 每一段 FFmpeg 转到临时 MP4：H.264、yuv420p、AAC 48 kHz 立体声；视频 `scale+pad` 到目标画幅（contain，黑边）；无音轨则 `anullsrc` 补静音，时长等于该段视频。
3. concat demuxer 拼临时文件，输出最终 `.partial` 再改名。
4. 画质映射现有 `quality`（原画 / 标准 / 节省体积）到 H.264 码率或 CRF，与 `mp4-h264` 同一套。
5. 失败：临时文件删掉；Job 失败；源文件不动。

不在一个 `filter_complex` 里塞全部输入，避免 20 段滤镜图过大。

### 历史与局域网

- `historySegmentFor`：`video`。
- 打开 / 分享 / 重命名 / 删除：与单输出视频 Job 相同。
- 局域网：一个输出文件，现有 `/d` `/m` 即可。

### 多语言

预设标题 / 说明走 `Localizable.xcstrings`（en、zh-Hans、zh-Hant、ja、ko）。简体标题「合并」，说明「按顺序拼成一个视频」。

## 测试

Domain 先红后绿：

- `video-concat` 在主预设里；`allowsTrim` false；设置无分辨率。
- `canStart`：1 个不可、2 个可。
- 入队：2 段 → 1 个 Job，`concatSourceUris` 顺序与拖动后一致；输出 stem 带 `merged`。
- 超过 20 段或混入不可导入 → 不入队。
- FFmpeg 参数：每段 scale/pad 到第一段偶数宽高；无音频走静音；最后 concat。

不测真实 FFmpeg 跑片（CI 无片源）；真机冒烟：两段相册视频，拖动对调，合并后时长相加、顺序正确。

## 风险

- 长视频多次重编码耗电、耗时；Live Activity 仍跟这一条 Job。
- 竖屏 + 横屏拼接会有黑边，这是「跟第一条对齐」的预期，不是 bug。
