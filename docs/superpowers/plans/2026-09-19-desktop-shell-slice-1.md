# 桌面第 1 刀：壳 + 历史持久化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把现有桌面视频转码放进侧栏壳（转换 / 历史 / 我的），任务写入 `jobs.json`，界面五种语言，历史可打开、重试、删除、重命名。

**Architecture:** 可测的历史 / 语言 / 存盘逻辑放在 `src-tauri` 纯函数里。React 只负责壳和现有转换页。第 1 刀不做音频模式、文档引擎、视频合并、局域网 HTTP。转换页仍可抽 MP3 / M4A（第 2 刀再挪到音频模式）；这类任务在历史里进「音频」段。

**Tech Stack:** Tauri 2、Rust 2021、React 19、现有 `src/App.css` 令牌。Rust 用 `cargo test`；前端用 `npx tsc --noEmit`。

## Global Constraints

- 规格：[2026-09-19-desktop-mobile-parity-design.md](../specs/2026-09-19-desktop-mobile-parity-design.md) 落地顺序第 1 项
- 外观继续暖灰 `#ecece8` / 墨 `#1f2428` / 橙 `#c45a2a`；不跟系统深色
- 窗口默认 `1180×780`，最小 `880×640`
- 不与手机同步设置或历史；不上传
- 第 1 刀转换页不出现「视频 | 音频 | 文档」分段
- 第 1 刀「我的」不出现局域网访问
- 点「开始转码」后留在转换页；侧栏历史出角标
- 转换页 dock 不伸进侧栏；历史和「我的」没有 dock
- 清空已完成不删磁盘；删除才删全部 `outputPaths`
- 预设 ID 与现网一致；未知预设仍报「未知预设」
- 第 2–4 刀（音频+合并、文档、局域网）另开计划

## File map

```
src-tauri/src/history.rs      # 分段、清空、操作、中断
src-tauri/src/job_store.rs    # jobs.json 原子读写
src-tauri/src/locale.rs       # 语言解析
src-tauri/src/queue.rs        # Job 新字段
src-tauri/src/probe.rs        # MediaInfo 页字段（默认空，给后续刀）
src-tauri/src/settings.rs     # language
src-tauri/src/lib.rs          # 启动加载、命令、persist
src-tauri/src/naming.rs       # rename 用 stem（已有 source_stem）
src-tauri/tauri.conf.json
src/types.ts
src/api.ts
src/i18n.ts
src/locales/en.json
src/locales/zh-Hans.json
src/locales/zh-Hant.json
src/locales/ja.json
src/locales/ko.json
src/App.tsx                   # 侧栏壳
src/ConvertPage.tsx           # 从 App.tsx 搬出现有转换 UI
src/HistoryPage.tsx
src/MinePage.tsx
src/App.css
README.md                     # 补一句：历史在侧栏，关窗口记录还在
```

`android/`、`ios/` 不改。

---

### Task 1: Job 字段 + 历史纯函数

**Files:**
- Create: `src-tauri/src/history.rs`
- Modify: `src-tauri/src/queue.rs`（`Job` 加字段）
- Modify: `src-tauri/src/probe.rs`（`MediaInfo` 加默认页字段，避免后续刀再改测试夹具）
- Modify: `src-tauri/src/lib.rs`（`mod history;`）
- Modify: `src-tauri/src/queue.rs` 测试夹具，以及所有构造 `MediaInfo` / `Job` 的地方补默认字段

**Interfaces:**
- Consumes: 现有 `Job`、`JobStatus`、`OutputConfig`
- Produces:

```rust
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum HistorySegment { Video, Audio, Document }

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum JobRowAction { Cancel, Retry, Open, Reveal, Rename, Delete }

pub fn is_audio_preset(preset: &str) -> bool;
pub fn is_document_preset(preset: &str) -> bool;
pub fn history_segment_for(job: &Job) -> HistorySegment;
pub fn history_jobs<'a>(jobs: &'a [Job], segment: HistorySegment) -> Vec<&'a Job>;
pub fn remaining_jobs_after_clear_finished(jobs: &[Job], segment: HistorySegment) -> Vec<Job>;
pub fn history_active_count(jobs: &[Job]) -> usize;
pub fn mark_interrupted(jobs: &mut [Job]); // queued/running → failed, error = "error_interrupted"
pub fn job_row_actions(status: &JobStatus) -> Vec<JobRowAction>;
pub fn history_segment_after_enqueue(preset: &str) -> HistorySegment;
```

`Job` 新增（`serde default`，缺省兼容旧 JSON）：

```rust
#[serde(default)]
pub display_name: String,
#[serde(default)]
pub output_paths: Vec<String>,
#[serde(default)]
pub created_at_epoch_ms: Option<i64>,
#[serde(default)]
pub concat_source_paths: Vec<String>,
```

`MediaInfo` 新增：`page_count` / `page_start` / `page_end: Option<u32>`，全部 `#[serde(default)]`。

文档预设集合（第 1 刀只用于分段，不跑引擎）：`image-jpg` `image-png` `image-webp` `image-bmp` `image-gif` `image-compress` `pdf-image` `pdf-txt` `pdf-compress` `pdf-split` `office-pdf`。

音频预设：`audio-mp3` `audio-aac` `audio-wav` `audio-flac` `audio-ogg` `audio-amr`。

- [ ] **Step 1: 写失败测试**

在 `history.rs` 底部：

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use crate::presets::OutputConfig;
    use crate::probe::MediaInfo;
    use crate::queue::{Job, JobStatus};

    fn job(id: &str, preset: &str, status: JobStatus) -> Job {
        Job {
            id: id.into(),
            source_path: "/a.mp4".into(),
            output_path: Some("/out/a.mp4".into()),
            status,
            progress: 0.0,
            error: None,
            config: OutputConfig { preset: preset.into(), ..Default::default() },
            media: MediaInfo {
                path: "/a.mp4".into(),
                duration_secs: Some(1.0),
                container: Some("mp4".into()),
                video_codec: Some("h264".into()),
                width: Some(64),
                height: Some(64),
                frame_rate: Some(25.0),
                audio_codec: Some("aac".into()),
                channels: Some(2),
                importable: true,
                error: None,
                trim_start_secs: None,
                trim_end_secs: None,
                page_count: None,
                page_start: None,
                page_end: None,
            },
            display_name: "a.mp4".into(),
            output_paths: vec!["/out/a.mp4".into()],
            created_at_epoch_ms: Some(1),
            concat_source_paths: vec![],
        }
    }

    #[test]
    fn segments_audio_and_document_presets() {
        assert_eq!(history_segment_for(&job("1", "audio-mp3", JobStatus::Completed)), HistorySegment::Audio);
        assert_eq!(history_segment_for(&job("2", "pdf-split", JobStatus::Completed)), HistorySegment::Document);
        assert_eq!(history_segment_for(&job("3", "mp4-h264", JobStatus::Completed)), HistorySegment::Video);
    }

    #[test]
    fn clear_finished_only_touches_current_segment() {
        let jobs = vec![
            job("v", "mp4-h264", JobStatus::Completed),
            job("a", "audio-mp3", JobStatus::Completed),
            job("r", "mp4-h264", JobStatus::Running),
        ];
        let next = remaining_jobs_after_clear_finished(&jobs, HistorySegment::Video);
        let ids: Vec<_> = next.iter().map(|j| j.id.as_str()).collect();
        assert_eq!(ids, vec!["a", "r"]);
    }

    #[test]
    fn mark_interrupted_fails_active_jobs() {
        let mut jobs = vec![
            job("q", "mp4-h264", JobStatus::Queued),
            job("c", "mp4-h264", JobStatus::Completed),
        ];
        mark_interrupted(&mut jobs);
        assert_eq!(jobs[0].status, JobStatus::Failed);
        assert_eq!(jobs[0].error.as_deref(), Some("error_interrupted"));
        assert_eq!(jobs[1].status, JobStatus::Completed);
    }

    #[test]
    fn actions_match_status() {
        assert_eq!(job_row_actions(&JobStatus::Running), vec![JobRowAction::Cancel]);
        assert_eq!(job_row_actions(&JobStatus::Failed), vec![JobRowAction::Retry, JobRowAction::Delete]);
        assert_eq!(
            job_row_actions(&JobStatus::Completed),
            vec![JobRowAction::Open, JobRowAction::Reveal, JobRowAction::Rename, JobRowAction::Delete]
        );
    }

    #[test]
    fn enqueue_segment_follows_preset() {
        assert_eq!(history_segment_after_enqueue("audio-mp3"), HistorySegment::Audio);
        assert_eq!(history_segment_after_enqueue("mp4-h264"), HistorySegment::Video);
    }
}
```

把 `queue.rs` / `probe.rs` 测试夹具补上新字段，否则现有测试编译失败。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd src-tauri && cargo test history:: -- --nocapture`

Expected: FAIL，`history` 模块不存在或函数未定义。

- [ ] **Step 3: 最小实现**

`history.rs` 实现上述函数。`is_audio_preset` 看预设 id 集合。`is_document_preset` 看文档 id 集合。其余进视频。`history_jobs` 过滤，**不**在这里反转顺序（UI 再 `asReversed` / `slice().reverse()`）。`remaining_jobs_after_clear_finished`：其它段原样留下；当前段只留 `Queued` / `Running`。`history_active_count` 计全部 queued+running。`job_row_actions`：Queued/Running → Cancel；Failed/Cancelled → Retry, Delete；Completed → Open, Reveal, Rename, Delete。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd src-tauri && cargo test`

Expected: PASS（含原有 queue / presets / args / settings 测试）。

- [ ] **Step 5: Commit**

```bash
git add src-tauri/src/history.rs src-tauri/src/queue.rs src-tauri/src/probe.rs src-tauri/src/lib.rs
git commit -m "$(cat <<'EOF'
Add desktop history helpers and extra job fields.
EOF
)"
```

---

### Task 2: `jobs.json` 原子读写

**Files:**
- Create: `src-tauri/src/job_store.rs`
- Modify: `src-tauri/src/lib.rs`（`mod job_store;`）

**Interfaces:**
- Consumes: `Job`、`mark_interrupted`
- Produces:

```rust
pub fn jobs_file(config_dir: &Path) -> PathBuf; // config_dir.join("jobs.json")
pub fn load_jobs(path: &Path) -> Vec<Job>;      // 缺文件或坏 JSON → []
pub fn save_jobs(path: &Path, jobs: &[Job]) -> Result<(), String>;
// 写 path.with_extension("json.tmp")（实际用 jobs.json.tmp），成功后再 rename
```

坏 JSON：把原文件挪到 `jobs.json.bad`（能挪就挪），然后返回 `[]`。

- [ ] **Step 1: 写失败测试**

```rust
#[test]
fn roundtrip_jobs() {
    let dir = std::env::temp_dir().join(format!("lt-jobs-{}", std::process::id()));
    let _ = std::fs::create_dir_all(&dir);
    let path = jobs_file(&dir);
    let jobs = vec![/* 一条 Completed Job，id=job-1 */];
    save_jobs(&path, &jobs).unwrap();
    let loaded = load_jobs(&path);
    assert_eq!(loaded[0].id, "job-1");
    assert_eq!(loaded[0].display_name, "a.mp4");
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn missing_file_is_empty() {
    let path = std::env::temp_dir().join("lt-jobs-missing-nope.json");
    assert!(load_jobs(&path).is_empty());
}

#[test]
fn corrupt_json_is_empty() {
    let dir = std::env::temp_dir().join(format!("lt-jobs-bad-{}", std::process::id()));
    let _ = std::fs::create_dir_all(&dir);
    let path = jobs_file(&dir);
    std::fs::write(&path, "{nope").unwrap();
    assert!(load_jobs(&path).is_empty());
    assert!(dir.join("jobs.json.bad").exists() || !path.exists());
    let _ = std::fs::remove_dir_all(&dir);
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd src-tauri && cargo test job_store:: -- --nocapture`

Expected: FAIL，模块不存在。

- [ ] **Step 3: 最小实现**

`save_jobs`：`create_dir_all` 父目录 → 写 `jobs.json.tmp` → `std::fs::rename` 到 `jobs.json`。Windows 上若目标存在，先 `remove_file` 再 rename。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd src-tauri && cargo test job_store::`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src-tauri/src/job_store.rs src-tauri/src/lib.rs
git commit -m "$(cat <<'EOF'
Store desktop jobs in an atomic JSON file.
EOF
)"
```

---

### Task 3: 启动加载、持久化、删除 / 重命名 / 按段清空

**Files:**
- Modify: `src-tauri/src/lib.rs`
- Modify: `src-tauri/src/naming.rs`（若需要 `rename_output_file`；否则把重命名逻辑写在 `lib.rs` 旁的纯函数并在 `naming.rs` 测）

**Interfaces:**
- Consumes: `load_jobs` / `save_jobs` / `jobs_file` / `remaining_jobs_after_clear_finished` / `job_row_actions`
- Produces: 命令

```rust
fn list_jobs(...) -> Result<Vec<Job>, String>; // 已有，启动后应是磁盘内容
fn clear_finished_jobs(app, state, segment: String) -> Result<(), String>;
fn delete_job(app, state, id: String) -> Result<(), String>;
fn rename_job(app, state, id: String, raw_name: String) -> Result<(), String>;
fn retry_job(...); // Failed 或 Cancelled
fn app_version() -> String; // tauri.conf version，关于页用
```

`segment` 解析：`"video"` / `"audio"` / `"document"`，其它 → `"video"`。

`delete_job`：找不到 → 错误「找不到该任务」。Queued/Running → 错误「进行中的任务请先取消」。其它状态：删掉 `output_paths`（空则 `output_path`）里存在的文件，忽略单个文件删除失败，然后从列表移除。

`rename_job`：仅 Completed。`raw_name` trim，去掉路径分隔符和结尾扩展名，空则错误「文件名不能为空」。第一份输出所在目录 + 新 stem + 原扩展名；目标已存在则走现有 `allocate_output_path` 规则。改磁盘名成功后写回 `output_path` / `output_paths[0]` / `display_name`（带扩展名）。多份输出不批量改名。

`enqueue_jobs` 创建 Job 时填：

- `display_name` = 输出文件名
- `output_paths` = `vec![output_path]`
- `created_at_epoch_ms` = unix ms
- `concat_source_paths` = `[]`

每次 `emit_jobs` 和 `mark_job` 之后 `save_jobs`。启动 `.setup`：加载 → `mark_interrupted` → 保存 → 放进 `AppState.jobs`。

`AppState` 可加 `config_dir: Mutex<PathBuf>`，setup 时写入；测试不依赖它。

- [ ] **Step 1: 写失败测试（纯函数，不启 Tauri）**

在 `naming.rs` 或新建 `src-tauri/src/history.rs` 旁测：

```rust
pub fn sanitized_rename_stem(raw: &str) -> Option<String>;
// "  clip.mp4 " → Some("clip")
// "a/b" / ".." / "" / "   " → None
// "foo.bar.mp4" → Some("foo.bar")  // 只剥最后一个扩展名；调用方传入已是 stem 也可以
```

计划约定：调用方把用户输入当「文件名可含扩展名」，函数剥最后一个 `.xxx`（xxx 长 1–8 字母数字）。

再测 `parse_history_segment("audio") == Audio`。

把 `parse_history_segment` 放 `history.rs`。

`retry_job` 测试：改现有逻辑后，用文档说明 Cancelled 可重试；若没有独立纯函数，在 Step 4 用 `cargo test` 保证编译，手动看 `if job.status != Failed && job.status != Cancelled`。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd src-tauri && cargo test sanitized_rename_stem -- --nocapture`

Expected: FAIL，函数不存在。

- [ ] **Step 3: 实现命令并接到 handler**

`clear_finished_jobs` 签名变更：前端旧调用会断，Task 6 一起改 `api.ts`。`generate_handler!` 加上 `delete_job` `rename_job` `app_version`。

`mark_job` / `emit_jobs` 里取 `app.path().app_config_dir()`，失败就只 emit 不写盘（不要 unwrap 崩掉泵）。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd src-tauri && cargo test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src-tauri/src/lib.rs src-tauri/src/history.rs src-tauri/src/naming.rs
git commit -m "$(cat <<'EOF'
Persist jobs on launch and add history mutations.
EOF
)"
```

---

### Task 4: 语言解析 + `settings.json`

**Files:**
- Create: `src-tauri/src/locale.rs`
- Modify: `src-tauri/src/settings.rs`
- Modify: `src-tauri/src/lib.rs`（`mod locale;`，`save_session_settings` 写入 `language`）

**Interfaces:**
- Consumes: 现有 `SessionSettings`
- Produces:

```rust
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub enum AppLanguage {
    #[default]
    System,
    #[serde(rename = "zh-Hans")]
    ZhHans,
    #[serde(rename = "zh-Hant")]
    ZhHant,
    En,
    Ja,
    Ko,
}

pub fn resolve_locale_tag(language: &AppLanguage, system_tag: &str) -> &'static str;
// 返回 "en" | "zh-Hans" | "zh-Hant" | "ja" | "ko"
```

`SessionSettings` 增加 `#[serde(default)] pub language: Option<String>`，存 `system` / `zh-Hans` / `zh-Hant` / `en` / `ja` / `ko`。

解析规则（系统标签小写）：

- `zh-cn` `zh-sg` `zh-hans` → zh-Hans
- `zh-tw` `zh-hk` `zh-mo` `zh-hant` → zh-Hant
- `en` 前缀 → en
- `ja` 前缀 → ja
- `ko` 前缀 → ko
- 其它、空 → en
- `AppLanguage::System` 用上面规则；显式枚举忽略 system_tag

- [ ] **Step 1: 写失败测试**

```rust
#[test]
fn system_zh_cn_is_simplified() {
    assert_eq!(resolve_locale_tag(&AppLanguage::System, "zh-CN"), "zh-Hans");
}
#[test]
fn system_fr_falls_back_to_en() {
    assert_eq!(resolve_locale_tag(&AppLanguage::System, "fr-FR"), "en");
}
#[test]
fn explicit_ja_ignores_system() {
    assert_eq!(resolve_locale_tag(&AppLanguage::Ja, "en-US"), "ja");
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd src-tauri && cargo test locale:: -- --nocapture`

Expected: FAIL

- [ ] **Step 3: 最小实现 + settings 读写 language**

`save_session_settings`：若 `settings.language` 是 `Some`，写入；`None` 表示不改。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd src-tauri && cargo test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src-tauri/src/locale.rs src-tauri/src/settings.rs src-tauri/src/lib.rs
git commit -m "$(cat <<'EOF'
Resolve app language the same way mobile does.
EOF
)"
```

---

### Task 5: 前端类型、API、文案表

**Files:**
- Modify: `src/types.ts`
- Modify: `src/api.ts`
- Create: `src/i18n.ts`
- Create: `src/locales/en.json`、`zh-Hans.json`、`zh-Hant.json`、`ja.json`、`ko.json`

**Interfaces:**
- Consumes: 新命令名与 camelCase JSON
- Produces:

```ts
export type HistorySegment = "video" | "audio" | "document";
export type AppLanguage = "system" | "zh-Hans" | "zh-Hant" | "en" | "ja" | "ko";
export type Job = { /* 旧字段 + */
  displayName?: string;
  outputPaths?: string[];
  createdAtEpochMs?: number | null;
  concatSourcePaths?: string[];
};
export type SessionSettings = { /* 旧字段 + */ language?: AppLanguage | null };

export function clearFinishedJobs(segment: HistorySegment);
export function deleteJob(id: string);
export function renameJob(id: string, rawName: string);
export function appVersion();
export function t(locale: string, key: string, vars?: Record<string, string | number>): string;
export function resolveLocaleTag(language: AppLanguage, systemTag: string): string;
```

`resolveLocaleTag` 与 Rust 规则相同（前端即时切换不必再 invoke）。

`t`：从对应 JSON 取键，缺键回退 `en.json`，再缺回退 key 本身。`{{name}}` / `{{count}}` 替换。`error_interrupted` 等 Rust 错误键也走 `t`。

文案至少包含：`tab_convert` `tab_history` `tab_mine` `segment_video` `segment_audio` `segment_document` `action_*`（cancel/open/retry/delete/rename/got_it/clear_finished/convert_again/more/reveal）`status_*` `history_empty_*` `history_clear_title` `history_clear_message` `history_delete_title` `history_delete_message` `history_rename_title` `history_rename_hint` `history_running_banner` `history_go_convert` `mine_language` `mine_privacy` `mine_terms` `mine_about` `language_*` `privacy_body` `terms_body` `about_name` `about_no_upload` `about_body` `error_interrupted` `untitled` `privacy_capsule`（不上传 · 不联网）以及现有转换页所有用户可见中文（dropzone、预设 hint、dock、画质、分辨率等）。

关于正文写桌面事实：「本机转码工具……当前版本是桌面应用」，不要写 Android 侧载。隐私 / 条款从手机文案改「电脑 / 你指定的文件夹」，局域网那句第 1 刀可保留（功能第 4 刀才开）。

en 为默认完整表。zh-Hans 用现有中文。zh-Hant / ja / ko 对齐 Android 对应 strings，缺的转换页句子按意思补，语气靠近现有桌面。

- [ ] **Step 1: 写失败测试**

在 `src-tauri/src/locale.rs` 已覆盖解析。前端 `t` 用一段内联断言不方便；改为在 `src/i18n.ts` 导出 `resolveLocaleTag`，并在 `src-tauri` 不测 TS。用 Node 跑：

Create `src/i18n.check.mjs`（临时，Task 结束可删，或保留为 `node src/i18n.check.mjs`）：

更干净：把 `resolveLocaleTag` 只放 Rust，TS 复制同一张表。第 1 刀用 `tsc` 保证编译。**本任务 Step 1 写一个 Rust 测试已有；前端 Step 4 跑 `npx tsc --noEmit`。**

补一个 JSON 完整性测试脚本 `scripts/check-locales.mjs`：五个文件键集合与 `en.json` 相同。

```js
import { readFileSync } from "node:fs";
const en = JSON.parse(readFileSync("src/locales/en.json", "utf8"));
for (const id of ["zh-Hans", "zh-Hant", "ja", "ko"]) {
  const other = JSON.parse(readFileSync(`src/locales/${id}.json`, "utf8"));
  const missing = Object.keys(en).filter((k) => !(k in other));
  if (missing.length) {
    console.error(id, missing);
    process.exit(1);
  }
}
console.log("locales ok");
```

先提交空/不齐的 json 时脚本应失败。

- [ ] **Step 2: 跑脚本确认失败**

Run: `node scripts/check-locales.mjs`

Expected: FAIL，文件不存在。

- [ ] **Step 3: 写满五个 JSON + `i18n.ts` + 更新 `types.ts` / `api.ts`**

`clearFinishedJobs` 必须传 `segment`。

- [ ] **Step 4: 确认通过**

Run: `node scripts/check-locales.mjs && npx tsc --noEmit`

Expected: 脚本打印 `locales ok`；tsc 可能因 App.tsx 仍用旧 `clearFinishedJobs()` 失败——若失败，本任务先让 `api.ts` 新签名存在，App.tsx 暂改成 `clearFinishedJobs("video")` 以免整仓红，Task 6 再删转换页清空按钮。

- [ ] **Step 5: Commit**

```bash
git add src/types.ts src/api.ts src/i18n.ts src/locales scripts/check-locales.mjs src/App.tsx
git commit -m "$(cat <<'EOF'
Add desktop locale tables and history API bindings.
EOF
)"
```

---

### Task 6: 侧栏壳 + 两栏转换页 + 挪走队列

**Files:**
- Create: `src/ConvertPage.tsx`（现 `App.tsx` 的转换内容，含 `TrimBar`）
- Modify: `src/App.tsx`（壳）
- Modify: `src/App.css`
- Modify: `src-tauri/tauri.conf.json`（宽高与 min）

**Interfaces:**
- Consumes: `t`、`jobs`、`history_active_count` 语义（前端自己 `jobs.filter(queued|running).length`）
- Produces: `App` 状态 `tab: "convert" | "history" | "mine"`；`ConvertPage` props：sources/config/jobs/notice 等现有状态可仍放 `App` 以免大搬家，或整段状态进 `ConvertPage`。**推荐：转换状态留在 `ConvertPage`，`App` 只持 `tab`、`jobs`（监听事件）、`locale`、`notice`。** 入队成功后 `ConvertPage` 清空当前源，不切 `tab`。

窗口：

```json
"width": 1180,
"height": 780,
"minWidth": 880,
"minHeight": 640
```

壳布局：

```tsx
<div className={dragging ? "shell dragging" : "shell"}>
  <nav className="sidebar">
    <p className="sidebar-brand">{t(locale, "about_name")}</p>
    <button className={tab==="convert" ? "on" : ""} onClick={() => setTab("convert")}>{t(locale, "tab_convert")}</button>
    <button className={tab==="history" ? "on" : ""} onClick={() => setTab("history")}>
      {t(locale, "tab_history")}
      {activeCount > 0 ? <em>{activeCount}</em> : null}
    </button>
    <button className={tab==="mine" ? "on" : ""} onClick={() => setTab("mine")}>{t(locale, "tab_mine")}</button>
  </nav>
  <div className="content">
    {notice ? <div className="notice">...</div> : null}
    {tab === "convert" ? <ConvertPage ... /> : null}
    {tab === "history" ? <HistoryPage ... /> : null}
    {tab === "mine" ? <MinePage ... /> : null}
  </div>
</div>
```

CSS：`.shell` 横向 flex，全高。`.sidebar` 宽 176px，`min-width: 148px; max-width: 240px;`，底 `#e2e2dc`。选中项墨底浅字。`.content` `flex:1; min-width:0; position:relative;`。转换 dock 改 `position:absolute; left: 16px; right: 16px; bottom: 16px;`（相对 `.content`，不是 `viewport`）。

`ConvertPage`：删除「第 3 步 · 转码队列」整段。左右栏：`.convert-grid { display:grid; grid-template-columns: 1fr 1fr; gap: 16px; }`，`@media (max-width: 960px) { 1fr }`。左：源 + 裁切；右：格式 + 画质 + 输出。顶上不要模式分段。

拖放监听仍在有窗口时挂到 webview；`dragging` 由 `App` 或 `ConvertPage` 设。仅转换 tab 时显示 drop 高亮即可。

预设卡：第 1 刀仍含 MP3 / M4A。

- [ ] **Step 1: 先改 CSS/壳，转换页仍可滚动出现旧队列——不要。本任务直接删队列。**

没有组件测试。用 `npx tsc --noEmit` 当编译门。手工对照：侧栏三钮、转换两栏、无队列。

- [ ] **Step 2: `tsc` 在 HistoryPage/MinePage 未创建时会失败。本任务先在 `App.tsx` 用占位：**

```tsx
{tab === "history" ? <p>{t(locale, "tab_history")}</p> : null}
{tab === "mine" ? <p>{t(locale, "tab_mine")}</p> : null}
```

Task 7 / 8 替换占位。

- [ ] **Step 3: 搬家 + 布局**

把 `TrimBar` 和转换 UI 移到 `ConvertPage.tsx`。`App` 监听 `jobs-changed` / `job-progress`，把 `jobs` 传给转换（dock 进行中禁用）和历史。

启动：`loadSessionSettings` 读 language，用 `navigator.language` 调 `resolveLocaleTag`。

- [ ] **Step 4: 确认通过**

Run: `npx tsc --noEmit`

Expected: PASS

手工：`npm run tauri dev` — 窗口约横屏；侧栏可切；转换页无队列；开始后仍停在转换。

- [ ] **Step 5: Commit**

```bash
git add src/App.tsx src/ConvertPage.tsx src/App.css src-tauri/tauri.conf.json
git commit -m "$(cat <<'EOF'
Move desktop convert into a sidebar shell.
EOF
)"
```

---

### Task 7: 历史页

**Files:**
- Create: `src/HistoryPage.tsx`
- Modify: `src/App.tsx`（换掉占位；持有 `historySegment`）
- Modify: `src/App.css`

**Interfaces:**
- Consumes: `jobs`、`t`、`cancelJob` `retryJob` `deleteJob` `renameJob` `clearFinishedJobs`、`openPath` `revealItemInDir`
- Produces: 入队后由 `ConvertPage` 回调 `onEnqueued(preset)` → `setHistorySegment(historySegmentAfterEnqueue(preset))`，**不**改 `tab`

前端复制分段函数（与 Rust 同一套 id）：

```ts
export function historySegmentFor(job: Job): HistorySegment { /* audio presets → audio; document ids → document; else video */ }
export function historySegmentAfterEnqueue(preset: string): HistorySegment
```

放 `src/history.ts`，避免 HistoryPage 和 ConvertPage 各写一份。

列表：`jobs.filter(seg).slice().reverse()`。行：标题 `job.displayName || fileName(outputPath||sourcePath)`；副标题状态 + `formatHistoryDate(createdAtEpochMs)`（`yyyy-MM-dd HH:mm` 本地时区）；失败用 `t(locale, job.error) !== job.error ? t(...) : job.error`。进行中进度条 + 现有 ETA。

主按钮 / 更多按 Task 1 的 `job_row_actions` 语义。右键 `contextmenu` 打开更多。双击完成行打开。键盘：行 `tabIndex=0`，Enter 打开或重试，Delete 走删除确认。

确认框用现有卡片样式的遮罩，不要 `window.confirm`。清空文案：`history_clear_message`（文件仍留在磁盘）。删除文案：会删掉转出的文件。

空态 + 「去转换」→ `onGoConvert(segment)` 只切 `tab=convert`（第 1 刀没有模式可切）。

进行中条：`history_running_banner` 带 `{{count}}`，可点回转换。

- [ ] **Step 1: 写 `src/history.ts` 的 Rust 对齐测试不可用。把分段函数再测一遍？** 在 `src-tauri` 已测。前端 `history.ts` 写完后，用 `scripts/check-locales.mjs` 仍通过即可。

补 `src/history.ts` 注释：id 列表必须与 `history.rs` 同步。

- [ ] **Step 2: tsc 在 HistoryPage 引用前会红。实现页面。**

- [ ] **Step 3: 实现 HistoryPage，替换占位**

`App`：

```ts
const [historySegment, setHistorySegment] = useState<HistorySegment>("video");
```

`ConvertPage` `onEnqueued={(preset) => setHistorySegment(historySegmentAfterEnqueue(preset))}`。

- [ ] **Step 4: 确认通过**

Run: `npx tsc --noEmit && cd src-tauri && cargo test`

Expected: PASS

手工：入队一条，历史角标 ≥1；打开历史能看到进度；完成后打开 / 在文件夹中看；清空弹出确认且文件还在；删除后文件消失。

- [ ] **Step 5: Commit**

```bash
git add src/HistoryPage.tsx src/history.ts src/App.tsx src/App.css src/ConvertPage.tsx
git commit -m "$(cat <<'EOF'
Show desktop jobs on a history pane.
EOF
)"
```

---

### Task 8: 我的（语言 / 隐私 / 条款 / 关于）

**Files:**
- Create: `src/MinePage.tsx`
- Modify: `src/App.tsx`
- Modify: `src/App.css`

**Interfaces:**
- Consumes: `language`、`setLanguage`、`appVersion()`、`t`、`openUrl`（`@tauri-apps/plugin-opener` 的 `openUrl`）
- Produces: 左栏 `MinePageId = "language" | "privacy" | "terms" | "about"`，默认 `"language"`（第 1 刀无局域网，不能默认 LAN）。切走「我的」再回来保留 `minePage`。

法律 URL：

- privacy: `https://flydmonkey.github.io/LiteTrans/docs/privacy.html`
- terms: `https://flydmonkey.github.io/LiteTrans/docs/terms.html`

页脚：应用名、`version`、`privacy_capsule`。

语言列表点选立即 `saveSessionSettings({ language })` 并 `setLanguage`。显示名：`language_zh_hans` 等键本身已是目标语言写法。

- [ ] **Step 1: 占位已在 Task 6。本任务直接实现。**

- [ ] **Step 2: `npx tsc --noEmit` 在实现中应保持可编译。**

- [ ] **Step 3: 实现 MinePage**

两组：语言；隐私 / 条款 / 关于。不要局域网。

- [ ] **Step 4: 确认通过**

Run: `npx tsc --noEmit`

Expected: PASS

手工：切 English，侧栏变成 History / Convert / Me；关于页有版本号；隐私可离线读完。

- [ ] **Step 5: Commit**

```bash
git add src/MinePage.tsx src/App.tsx src/App.css
git commit -m "$(cat <<'EOF'
Add desktop settings for language and legal pages.
EOF
)"
```

---

### Task 9: README + 回归

**Files:**
- Modify: `README.md`（安装/能做什么附近加：侧栏有历史；关窗口记录还在；语言在「我的」）

**Interfaces:**
- Consumes: 已落地行为
- Produces: 用户能按 README 找到历史

- [ ] **Step 1: 改 README 两三句，不写第 2 刀功能。**

- [ ] **Step 2: 跑全套**

Run:

```
node scripts/check-locales.mjs
npx tsc --noEmit
cd src-tauri && cargo test
cd .. && npm run smoke
```

Expected: 全部 PASS。smoke 仍只测捆绑 FFmpeg，与 UI 无关。

- [ ] **Step 3: 本机点一遍**

- 拖视频 → 开始转码 → 仍在转换页 → 历史有进度
- 完成后打开、在文件夹中看
- 重启应用，历史还在；若上次杀进程时有进行中，该条为失败，可重试
- 切换繁体 / English
- 清空已完成：列表没了，磁盘文件还在
- 删除：列表和文件都没了

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "$(cat <<'EOF'
Describe the desktop history sidebar in the README.
EOF
)"
```

---

## 自检

| 规格第 1 刀 | 任务 |
| --- | --- |
| 侧栏转换 / 历史 / 我的 | 6 |
| 转换两栏、无队列、dock 在内容区 | 6 |
| 开始后不跳历史、角标 | 6–7 |
| 历史分段、操作、清空 vs 删除 | 1, 3, 7 |
| 任务持久化、启动中断 | 2–3 |
| 语言五种 + 文案表 | 4–5, 8 |
| 关于 / 隐私 / 条款 | 8 |
| 窗口尺寸 | 6 |
| 现有视频转码仍跑 | 6，引擎不改 |
| 不做音频模式 / 合并 / 文档 / LAN | 全局约束 |

故意留给后刀：转换顶部分段、合并卡、文档、局域网行、Rust 错误全量 i18n（本刀只保证 `error_interrupted` 与界面字符串）。
