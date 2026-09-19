# 桌面第 2–3 刀：音频、文档、视频合并 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 转换页加上视频 / 音频 / 文档分段，能抽六种音频、合并多段视频、以及图片 / PDF / Word 本机转换。

**Architecture:** 可测的模式、开始条件、合并目标、文档种类放在 `src-tauri/src/convert.rs` 与 `src/convert.ts`（表必须一致）。音频和合并走现有 FFmpeg 泵；文档走新的 `src-tauri/src/document.rs`。React 只做三份会话和模式过滤。

**Tech Stack:** Tauri 2、Rust 2021、React 19、现有 FFmpeg sidecar、`image` / `lopdf` / `pdf-extract` / `printpdf` / `zip`。Rust：`export PATH="$HOME/.rustup/toolchains/stable-aarch64-apple-darwin/bin:$PATH"` 后 `cargo test`。前端：`npx tsc --noEmit` 与 `node scripts/check-locales.mjs`。

## Global Constraints

- 规格：[2026-09-19-desktop-audio-document-concat-design.md](../specs/2026-09-19-desktop-audio-document-concat-design.md)；产品语义同时受 [desktop-mobile-parity](../specs/2026-09-19-desktop-mobile-parity-design.md) 约束
- 外观继续暖灰 `#ecece8` / 墨 `#1f2428` / 橙 `#c45a2a`
- 点开始后留在转换页；只清空当前模式源；历史角标；分段切到对应段
- 视频文案「开始转码」；音频 / 文档「开始转换」
- 视频模式不再放 MP3 / M4A
- 输出进用户选的文件夹；不按相册 / 文档分流
- 未知预设仍报「未知预设」
- 不做局域网；不改 `android/`、`ios/`
- Homebrew `rustc` 不可用；测试前必须 rustup toolchain 在 PATH 最前

## File map

```
src-tauri/src/convert.rs       # ConvertMode、can_start、concat、文档种类
src-tauri/src/concat.rs        # 合并滤镜与 ffmpeg 参数
src-tauri/src/document.rs      # 文档引擎
src-tauri/src/presets.rs       # 音频 + concat + 文档 resolve_config
src-tauri/src/args.rs          # 新容器 / 编码器白名单、音频校验
src-tauri/src/engine.rs        # probe 按扩展名分流；concat 转码
src-tauri/src/lib.rs           # enqueue 合并一条 Job；泵文档分支
src-tauri/src/settings.rs      # 三模式会话
src-tauri/src/queue.rs         # 需要时补字段拷贝
src-tauri/Cargo.toml
src/convert.ts                 # 与 convert.rs 同表
src/types.ts
src/api.ts                     # 选文件按模式过滤
src/ConvertPage.tsx
src/App.tsx                    # convertMode
src/HistoryPage.tsx            # 去转换带上模式
src/App.css
src/locales/*.json
README.md
```

`android/`、`ios/` 不改。

---

### Task 1: 模式 / 开始条件 / 文档种类 / 合并边界

**Files:**
- Create: `src-tauri/src/convert.rs`
- Create: `src/convert.ts`
- Modify: `src-tauri/src/lib.rs`（`mod convert;`）

**Interfaces:**
- Consumes: 无
- Produces:

```rust
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum ConvertMode { Video, Audio, Document }

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DocumentSourceKind { Image, Pdf, Word, Excel }

pub const VIDEO_CONCAT_PRESET: &str = "video-concat";
pub const VIDEO_CONCAT_MAX_SOURCES: usize = 20;

pub fn can_start(importable: usize, probing: bool, transcoding: bool, output_ready: bool, preset: &str, source_count: usize) -> bool;
pub fn is_video_concat_preset(preset: &str) -> bool;
pub fn allows_trim(preset: &str) -> bool; // false for mp4-copy and video-concat
pub fn document_source_kind(file_name: &str) -> Option<DocumentSourceKind>;
pub fn same_document_kind(existing: &[&str], incoming: &str) -> bool;
pub fn clamp_page_range(start: u32, end: u32, page_count: u32) -> (u32, u32);
pub fn default_document_preset(kind: DocumentSourceKind) -> &'static str;
pub fn document_extension(preset: &str, image_format: Option<&str>) -> &'static str;
pub fn document_output_file_name(stem: &str, index: usize, total: usize, ext: &str) -> String;
pub fn even_dimension(value: u32) -> u32;
pub fn concat_output_stem(display_name: &str) -> String; // "{stem}-merged"
```

`document_source_kind` 扩展名（小写）：

- Image: `jpg` `jpeg` `png` `webp` `bmp` `gif`
- Pdf: `pdf`
- Word: `docx`（仅此；`.doc` 不是 Word）
- Excel: `xlsx` `xls` — 探测后标红，**没有**可转预设
- 其它：`None`

`default_document_preset`：Image → `image-jpg`；Pdf → `pdf-image`；Word → `office-pdf`；Excel 不调用。

TS 用 camelCase：`canStart`、`documentSourceKind`、`sameDocumentKind`、`clampPageRange`、`defaultDocumentPreset`、`documentExtension`、`documentOutputFileName`、`evenDimension`、`concatOutputStem`、`allowsTrim`、`isVideoConcatPreset`。扩展名表和预设 id 必须与 Rust 一致。

- [ ] **Step 1: 写失败测试** `src-tauri/src/convert.rs`

```rust
#[test]
fn can_start_concat_needs_two_clean_clips() {
    assert!(!can_start(1, false, false, true, "video-concat", 1));
    assert!(can_start(2, false, false, true, "video-concat", 2));
    assert!(!can_start(2, false, false, true, "video-concat", 3)); // 有标红
    assert!(!can_start(21, false, false, true, "video-concat", 21));
    assert!(can_start(1, false, false, true, "mp4-h264", 1));
}

#[test]
fn document_kind_and_mix() {
    assert_eq!(document_source_kind("a.JPG"), Some(DocumentSourceKind::Image));
    assert_eq!(document_source_kind("a.docx"), Some(DocumentSourceKind::Word));
    assert_eq!(document_source_kind("a.doc"), Some(DocumentSourceKind::Excel)); // 旧 doc 走 Excel 桶：无预设、标红
    assert_eq!(document_source_kind("a.xlsx"), Some(DocumentSourceKind::Excel));
    assert!(same_document_kind(&["a.png"], "b.jpg"));
    assert!(!same_document_kind(&["a.png"], "b.pdf"));
    assert_eq!(clamp_page_range(0, 99, 5), (1, 5));
    assert_eq!(default_document_preset(DocumentSourceKind::Pdf), "pdf-image");
    assert_eq!(document_extension("pdf-split", None), "pdf");
    assert_eq!(document_output_file_name("scan", 2, 4, "jpg"), "scan-002.jpg");
    assert_eq!(even_dimension(1921), 1920);
    assert_eq!(concat_output_stem("clip.mp4"), "clip-merged");
    assert!(!allows_trim("video-concat"));
    assert!(!allows_trim("mp4-copy"));
    assert!(allows_trim("audio-mp3"));
}
```

旧 `.doc`：规格写「Excel、旧 `.doc` / `.xls` 无预设、行标红」。用 `Excel` 变体表示「不支持办公格式」，不要给 `office-pdf`。

- [ ] **Step 2: 跑测试确认失败**

Run:

```
export PATH="$HOME/.rustup/toolchains/stable-aarch64-apple-darwin/bin:$PATH"
cd src-tauri && cargo test convert:: -- --nocapture
```

Expected: FAIL（模块不存在）

- [ ] **Step 3: 实现 `convert.rs`，`lib.rs` 加 `mod convert;`。实现 `src/convert.ts` 与测试断言一致。**

`can_start`：

```rust
pub fn can_start(
    importable: usize,
    probing: bool,
    transcoding: bool,
    output_ready: bool,
    preset: &str,
    source_count: usize,
) -> bool {
    let concat = is_video_concat_preset(preset);
    let minimum = if concat { 2 } else { 1 };
    let maximum = if concat { VIDEO_CONCAT_MAX_SOURCES } else { usize::MAX };
    let no_rejects = !concat || importable == source_count;
    importable >= minimum
        && importable <= maximum
        && no_rejects
        && !probing
        && !transcoding
        && output_ready
}
```

- [ ] **Step 4: 再跑 `cargo test convert::` Expected: PASS。`npx tsc --noEmit` 若已引用 convert.ts 也要过。**

- [ ] **Step 5: Commit**

```bash
git add src-tauri/src/convert.rs src-tauri/src/lib.rs src/convert.ts
git commit -m "$(cat <<'EOF'
Add convert-mode helpers for concat limits and document kinds.
EOF
)"
```

---

### Task 2: 音频预设 + concat 占位进 resolve_config

**Files:**
- Modify: `src-tauri/src/presets.rs`
- Modify: `src-tauri/src/args.rs`

**Interfaces:**
- Consumes: Task 1 `VIDEO_CONCAT_PRESET`
- Produces: `resolve_config("audio-wav"|"audio-flac"|"audio-ogg"|"audio-amr"|"video-concat")` 成功；`list_presets` 含这些 id；WAV/FLAC 的 `audio_bitrate_kbps` 可为 `None`（无损）

映射：

| preset | container | audio_encoder | video |
| --- | --- | --- | --- |
| audio-wav | wav | pcm_s16le | none |
| audio-flac | flac | flac | none |
| audio-ogg | ogg | libvorbis | none |
| audio-amr | amr | `amr_nb` | none |
| video-concat | mp4 | aac | h264 |

`extension_for` 增加 `wav` `flac` `ogg` `amr`。

`args.rs`：`CONTAINERS` 加上这些；`AUDIO_ENCODERS` 加上 `pcm_s16le` `flac` `libvorbis` `amr_nb`（以及实现选用的 AMR 名）；`is_audio_only` 覆盖全部 `audio-*` 与这些容器。

- [ ] **Step 1: 失败测试** 加到 `presets.rs`：

```rust
#[test]
fn resolves_new_audio_and_concat() {
    for (id, ext) in [
        ("audio-wav", "wav"),
        ("audio-flac", "flac"),
        ("audio-ogg", "ogg"),
        ("audio-amr", "amr"),
        ("video-concat", "mp4"),
    ] {
        let resolved = resolve_config(&OutputConfig { preset: id.into(), ..Default::default() }).unwrap();
        assert_eq!(resolved.extension, ext);
        assert!(resolved.video_encoder.is_none() || id == "video-concat");
    }
    assert!(resolve_config(&OutputConfig { preset: "nope".into(), ..Default::default() }).is_err());
}
```

- [ ] **Step 2: `cargo test resolves_new_audio_and_concat` Expected: FAIL**

- [ ] **Step 3: 实现 match 臂。`list_presets` 追加五项（label 可拉丁文，界面用 locale）。**

- [ ] **Step 4: `cargo test presets:: args::` Expected: PASS**

- [ ] **Step 5: Commit** `Resolve extra audio presets and video-concat as MP4.`

---

### Task 3: 探测分流

**Files:**
- Modify: `src-tauri/src/engine.rs`（`probe_media`）
- Modify: `src-tauri/src/probe.rs` 如需 `probe_document` 入口
- Create tests in `probe.rs` 或 `convert.rs` 对扩展名路由的纯函数

**Interfaces:**
- Consumes: `document_source_kind`
- Produces: `probe_media`：图片 / pdf / docx / xlsx / xls / doc 不走 ffprobe

行为：

- Image：`importable=true`，`container`=扩展名，无音视频流也可。
- Pdf：`importable=true`，`page_count` 用 `lopdf` 读页数（本任务可先只写扩展名路由 + 页数 1 的 stub，Task 8 填真页数——**不要 stub**。本任务把 `lopdf` 加进 `Cargo.toml`，用最小 PDF fixture 测页数）。
- Word `docx`：`importable=true`
- Excel / `.doc`：`importable=false`，`error` 用稳定键 `error_unsupported_document`（前端 t()）
- 其它扩展名：现有 ffprobe

纯函数可先测：`probe_kind_for_path("a.png") == DocumentImage`。

- [ ] **Step 1: 测试** `probe_kind` + 一个最小 PDF 页数（可在测试里用 `lopdf` 写 2 页再读）。Excel 扩展名不可导入。

- [ ] **Step 2–4: TDD 实现。图片探测不要求解码成功才 importable；打不开则 `importable=false`。**

- [ ] **Step 5: Commit** `Probe images, PDFs, and Office files without FFprobe.`

---

### Task 4: 三模式 settings.json

**Files:**
- Modify: `src-tauri/src/settings.rs`
- Modify: `src/types.ts` `SessionSettings`
- Modify: `src-tauri/src/lib.rs` `save_session_settings` 合并语义

**Interfaces:**
- Produces:

```rust
#[derive(Default, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModeSettings {
    pub output_dir: Option<String>,
    pub preset: Option<String>,
    pub quality: Option<String>,
    pub max_width: Option<u32>,
    pub max_height: Option<u32>,
}

pub struct SessionSettings {
    pub output_dir: Option<String>, // 旧字段：当作 video.output_dir 回退
    pub preset: Option<String>,
    pub quality: Option<String>,
    pub max_width: Option<u32>,
    pub max_height: Option<u32>,
    pub language: Option<String>,
    #[serde(default)]
    pub video: ModeSettings,
    #[serde(default)]
    pub audio: ModeSettings,
    #[serde(default)]
    pub document: ModeSettings,
}
```

读档后：若 `video.preset` 空且顶层 `preset` 有值，把顶层拷进 `video`（兼容第 1 刀）。保存语言仍：仅当 `language.is_some()` 才覆盖。保存某一模式时只更新该模式对象 + 可选顶层 video 镜像，避免把另外两模式打成 null。

- [ ] **Step 1: 测试** `legacy_preset_fills_video_mode`、`saving_audio_does_not_clear_video`。

- [ ] **Step 2–4: 实现。**

- [ ] **Step 5: Commit** `Store convert presets per video, audio, and document mode.`

---

### Task 5: 转换页分段 + 三会话 + 音频 UI

**Files:**
- Modify: `src/types.ts`（`ConvertMode`、`MediaInfo.pageCount` 等）
- Modify: `src/ConvertPage.tsx`
- Modify: `src/App.tsx`（`convertMode` state；`onGoConvert` 同步模式）
- Modify: `src/api.ts`（`pickFiles(mode)` 扩展名）
- Modify: `src/App.css`
- Modify: `src/locales/*.json`

**Interfaces:**
- Consumes: `canStart` / `allowsTrim` / `isVideoConcatPreset` from `src/convert.ts`
- Produces: ConvertPage 顶上 chips：`segment_video` `segment_audio` `segment_document`。内部 `sessions: Record<ConvertMode, { sources, config, outputDir, showAll }>`。切换模式换会话。

视频 `PRIMARY_PRESET_IDS` = `mp4-h264` `mp4-copy` `mp4-h265` `mov-h264` `video-concat`。更多：MKV/WebM/AVI/GIF。**不要 MP3/M4A。**

音频：六张卡一次铺开，无「更多」。WAV/FLAC 隐藏音质。无分辨率。有时长则裁切。

选文件 / 拖放：视频用现有视频扩展名；音频 = 视频扩展名 + `mp3 m4a wav flac ogg amr aac`；文档 = `jpg jpeg png webp bmp gif pdf docx doc xls xlsx`。模式不对的文件 `onNotice(t(locale,"notice_wrong_mode"))`，不进列表。

文档混选：`sameDocumentKind` 为 false 则 `notice_document_mixed`，已选保留。

开始：用 `canStart(...)`；concat 不足两段时 dock 文案 `dock_concat_need_two`。音频/文档 dock 按钮 `start_convert`（新键），视频仍 `start_transcode`。入队成功只 `setSessions` 清空当前模式 sources。

`App`：`convertMode` 默认 `"video"`。`HistoryPage onGoConvert` → `setConvertMode(segment); setTab("convert")`。

Locale 至少加：`start_convert`、`preset_video_concat_*`、`preset_audio_wav/flac/ogg/amr_*`、`notice_wrong_mode`、`notice_document_mixed`、`dock_concat_need_two`、`error_unsupported_document`、文档预设键。五份 json 一起改，跑 `node scripts/check-locales.mjs`。

- [ ] **Step 1: 本任务无组件测试。实现中保持 `npx tsc --noEmit`。**

- [ ] **Step 2: 实现。**

- [ ] **Step 3: `npx tsc --noEmit` 与 `node scripts/check-locales.mjs` PASS。**

- [ ] **Step 4: Commit** `Add convert mode switcher, audio presets, and per-mode sessions.`

---

### Task 6: 合并入队 + FFmpeg 拼接

**Files:**
- Create: `src-tauri/src/concat.rs`
- Modify: `src-tauri/src/lib.rs` `enqueue_jobs` / `pump_queue`
- Modify: `src-tauri/src/engine.rs` 或 `concat.rs` 执行转码
- Modify: `src/ConvertPage.tsx` 文件列表拖排序（仅 `video-concat`）

**Interfaces:**
- Consumes: `concat_output_stem`、`even_dimension`、`ConcatTarget`
- Produces: `video-concat` **一条** Job：`source_path`=第一段，`concat_source_paths`=全部路径（含第一段，顺序即列表），`output_path`=`{stem}-merged.mp4`

`concat.rs`（对齐 Android `Concat.kt`）：

```rust
pub struct ConcatTarget { pub width: u32, pub height: u32, pub frame_rate: f64 }

pub fn concat_target(first: &MediaInfo) -> Result<ConcatTarget, String> {
    let width = first.width.filter(|w| *w > 0).ok_or("Each clip needs a video track")?;
    let height = first.height.filter(|h| *h > 0).ok_or("Each clip needs a video track")?;
    Ok(ConcatTarget {
        width: even_dimension(width),
        height: even_dimension(height),
        frame_rate: first.frame_rate.filter(|f| *f > 0.0).unwrap_or(30.0),
    })
}

pub fn concat_scale_pad_filter(target: &ConcatTarget) -> String {
    format!(
        "scale={w}:{h}:force_original_aspect_ratio=decrease,pad={w}:{h}:(ow-iw)/2:(oh-ih)/2,setsar=1,fps={fps}",
        w = target.width,
        h = target.height,
        fps = target.frame_rate
    )
}
```

引擎步骤：临时目录；对每段跑现有 `transcode` 风格 ffmpeg → 对齐的 temp mp4（无音轨则 `anullsrc` + map，同 Android）；写 concat demuxer 列表；`-f concat -safe 0 -i list -c copy` 到 `.partial` 再改名。失败删除临时文件。进度按段粗分。

`enqueue_jobs`：若 `is_video_concat_preset`，`accepted.len()` 必须 ≥2 且 skipped 空（否则返回错误或全部进 skipped、不建 Job）。不要对每段各建 Job。

- [ ] **Step 1: 测试** `concat_target_evens_odd_width`、`concat_scale_pad_filter_matches_android`、`enqueue` 单测若难抽 lib 命令则测 `jobs_from_concat_sources` 纯函数：输入 3 条 MediaInfo → 1 个 Job、paths 长度 3。

- [ ] **Step 2–4: TDD 纯函数后接 enqueue/pump。**

- [ ] **Step 5: Commit** `Enqueue video-concat as one job and join aligned clips.`

---

### Task 7: 文档 UI + 多输出入队

**Files:**
- Modify: `src/ConvertPage.tsx`
- Modify: `src-tauri/src/lib.rs` `enqueue_jobs`
- Modify: `src-tauri/src/presets.rs` 文档 preset 的 `resolve_config`（container/extension 来自 `document_extension`）

**Interfaces:**
- 文档右栏只渲染 `documentCardsFor(kind)`：
  - Image: image-jpg/png/webp/bmp/gif/image-compress
  - Pdf: pdf-image/pdf-txt/pdf-compress/pdf-split
  - Word: office-pdf
  - Excel: 无卡
- `pdf-image`：另选 `jpg|png|webp` 写入 `config.container`
- 压缩预设显示画质；PDF 显示页范围输入，写入 `media.pageStart/pageEnd`（1-based）
- 左栏：图片用 `<img src={localMediaUrl}>`；PDF 可只显示文件名+页数（完整页预览可用第一页抽图，没有则文件名）；Word 文件名；Excel 行 `className=bad`

入队：每个 importable 源一条 Job。`pdf-image` / `pdf-split` 的 `output_paths` 按页范围张数预分配路径（`document_output_file_name`），`output_path` 为第一份。其它文档一份输出。

`resolve_config("image-jpg")` 等必须成功，不要「未知预设」。

- [ ] **Step 1: 测试** `resolve_config` 对全部 `DOCUMENT_PRESETS`；`planned_output_count` 纯函数：page 1–4 的 pdf-split → 4。

- [ ] **Step 2–4: 实现。tsc + cargo test。**

- [ ] **Step 5: Commit** `Show document targets and enqueue one job per source.`

---

### Task 8: 文档引擎

**Files:**
- Create: `src-tauri/src/document.rs`
- Modify: `src-tauri/Cargo.toml`
- Modify: `src-tauri/src/lib.rs` `pump_queue`：`is_document_preset` 则 `document::run_job`，否则现有 `transcode_job`

**Interfaces:**
- Crates：`image`（jpeg/png/gif/webp/bmp）、`lopdf`、`pdf-extract`、`printpdf`、`zip`（解析 docx）
- `pub fn run_job(job: &Job, on_progress: impl Fn(f64), is_cancelled: impl Fn() -> bool) -> Result<Vec<String>, String>` 返回写出的绝对路径（已从 `.partial` 改名）

行为：

- `image-*` / `image-compress`：解码写出；压缩按 quality 调 JPEG/WebP 质量或缩小 PNG/BMP
- `pdf-txt`：`pdf-extract` 文本，页范围
- `pdf-split`：`lopdf` 每页一个 PDF
- `pdf-compress`：`lopdf` 重写（不要整页光栅化再装回）
- `pdf-image`：每页光栅化成 `container` 图片。加入 `pdfium-render`，动态库放 `src-tauri/binaries/`（`scripts/fetch-pdfium.mjs`，按 target triple 命名，与 ffmpeg 相同）。禁止用 FFmpeg 光栅化 PDF。测试：生成 1 页 PDF → JPG，输出文件非空。
- `office-pdf`：unzip docx，读 `word/document.xml` 段落，用 `printpdf` 写纯文本 PDF。失败「无法转换该文档」

取消：循环里查 `is_cancelled`，返回 `Err("cancelled")` 与现泵一致。

- [ ] **Step 1: 图片：生成 8×8 PNG 转 JPG 测试。PDF：两页 split 出两个文件。docx：最小 zip fixture 转 PDF 文件头 `%PDF`。**

- [ ] **Step 2–4: TDD。泵分支接上。**

- [ ] **Step 5: Commit** `Convert images, PDFs, and Word on a document engine.`

---

### Task 9: 历史衔接 + README + 回归

**Files:**
- Modify: `src/HistoryPage.tsx`（多输出副标题 `history_outputs_images` / `history_outputs_pdfs` 若 `outputPaths.length>1`）
- Modify: `src/App.tsx`（已在 Task 5 接模式则此处补缺）
- Modify: `README.md`：桌面可转音频 / 合并 / 文档一句；不写局域网
- Modify: locales

**Interfaces:**
- 历史「去转换」必须带上 `ConvertMode`
- 跑：

```
node scripts/check-locales.mjs
npx tsc --noEmit
export PATH="$HOME/.rustup/toolchains/stable-aarch64-apple-darwin/bin:$PATH"
cd src-tauri && cargo test
cd .. && npm run smoke
```

Expected: 全 PASS。

本机（写入报告，不要假装点过）：抽 MP3、两段合并、一张图转 JPG、PDF 拆分。

- [ ] **Step 1: README 两三句。**
- [ ] **Step 2: 全套门禁。**
- [ ] **Step 3: Commit** `Describe desktop audio and document conversion in the README.`

---

## 自检

| 规格 | 任务 |
| --- | --- |
| 顶上视频/音频/文档 + 三会话 | 5 |
| 视频无 MP3/M4A；合并卡 | 5–6 |
| 音频六种、裁切、无分辨率 | 2, 5 |
| 文档种类、混选、Excel 标红 | 1, 5, 7 |
| concat 一条 Job、对齐再拼接 | 6 |
| 文档引擎不走 FFmpeg（pdf-image 用 pdfium） | 8 |
| settings 三模式 | 4 |
| 开始后留页、历史分段 | 5, 9 |
| 不做局域网 | 全局 |

故意留给第 4 刀：局域网开关、HTTP、`lanShare` 界面。
