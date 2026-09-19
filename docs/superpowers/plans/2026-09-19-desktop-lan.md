# 桌面第 4 刀：局域网访问 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 「我的」加上局域网访问：默认关，打开后在局域网 IPv4 上提供与手机相同的只读媒体库网页。

**Architecture:** 可测的口令 / 路由 / 选地址 / 组响应放在 `src-tauri/src/lan.rs`。`std::net::TcpListener` 绑到选中的 IPv4，每次请求只读当前 `jobs`。React 只做开关、口令、地址、复制、打开浏览器。

**Tech Stack:** Tauri 2、Rust 2021、React 19、标准库 TCP（不引入 HTTP crate）。Rust：`export PATH="$HOME/.rustup/toolchains/stable-aarch64-apple-darwin/bin:$PATH"` 后 `cargo test`。前端：`npx tsc --noEmit` 与 `node scripts/check-locales.mjs`。

## Global Constraints

- 规格：[2026-09-19-desktop-lan-design.md](../specs/2026-09-19-desktop-lan-design.md)；网页协议同时受 [局域网媒体库](../specs/2026-09-17-android-lan-media-library-design.md) 约束；产品壳受 [desktop-mobile-parity](../specs/2026-09-19-desktop-mobile-parity-design.md) 约束
- 外观继续暖灰 `#ecece8` / 墨 `#1f2428` / 橙 `#c45a2a`
- 绑定局域网 IPv4，不听 `0.0.0.0`，不把 `127.0.0.1` 展示给用户
- 有线或 Wi‑Fi 均可；不要 Android 的 `wlan*` 过滤
- 端口优先 `17890`，连试 10 个
- 口令去空白；有口令则 `k=` 恒等比较（先 URL 解码）
- 关掉开关或退出应用：服务停；下次启动开关仍开则再拉起
- 不做开机自启、通知、HTTPS、mDNS、上传
- 不改 `android/`、`ios/`
- Homebrew `rustc` 不可用；测试前 rustup toolchain 在 PATH 最前

## File map

```
src-tauri/src/lan.rs           # 口令、路由、地址、HTTP 解析、handle_lan_request
src-tauri/src/lan_page.rs      # render HTML；CSS/JS 从 Android LanHistoryPage 拷入
src-tauri/src/lan_server.rs    # TcpListener 线程、Range 发文件
src-tauri/src/settings.rs      # lanShare
src-tauri/src/lib.rs           # 命令、生命周期
src-tauri/src/history.rs       # 如需把 history_segment 给网页 Tab
src/types.ts
src/api.ts
src/MinePage.tsx
src/App.tsx                    # minePage 默认 lan；运行中显示「已开启」
src/App.css
src/locales/*.json
README.md
```

`android/`、`ios/` 不改。

---

### Task 1: 口令、路由、query、端口、选 IPv4

**Files:**
- Create: `src-tauri/src/lan.rs`
- Modify: `src-tauri/src/lib.rs`（`mod lan;`）

**Interfaces:**
- Consumes: 无
- Produces:

```rust
pub const LAN_SHARE_PREFERRED_PORT: u16 = 17890;
pub const LAN_SHARE_PORT_ATTEMPTS: u16 = 10;

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LanShareSettings { pub enabled: bool, pub token: String }

pub fn normalize_lan_token(raw: &str) -> String { raw.trim().to_string() }
pub fn lan_token_allows(stored: &str, query_k: Option<&str>) -> bool;
pub enum LanRoute { Home, Download { job_id: String, index: usize }, Media { job_id: String, index: usize }, Favicon, NotFound }
pub fn parse_lan_route(path: &str) -> LanRoute;
pub fn parse_lan_query(raw: Option<&str>) -> HashMap<String, String>;
pub fn parse_http_request_line(line: &str) -> Option<LanHttpRequest>;
pub struct LanIface { pub name: String, pub host_address: String, pub loopback: bool }
pub fn pick_lan_ipv4(ifaces: &[LanIface]) -> Option<String>;
pub fn choose_lan_port(preferred: u16, attempts: u16, occupied: &HashSet<u16>) -> Option<u16>;
pub fn lan_public_url(ip: &str, port: u16, token: &str) -> String; // 空格编码为 +，与 Java URLEncoder 一致
pub fn lan_query_encode(raw: &str) -> String;
```

`pick_lan_ipv4`（桌面，不要 `wlan*`）：跳过 `loopback`、非点分 IPv4、`lo*` / `utun*` / `awdl*` / `llw*`。其余里优先 RFC1918（`10.` / `172.16–31.` / `192.168.`），否则第一张可用网卡。`en0` / `eth0` 必须能选中。

`parse_lan_route`：与 Android 相同。`/` → Home；`/favicon.png` `/favicon.ico` → Favicon；`/d|m/{id}` index 默认 0；`/d|m/{id}/{index}`；`..` 或 `/` 在 id 里 → NotFound。

- [ ] **Step 1: 写失败测试** 放在 `lan.rs`：

```rust
#[test]
fn token_and_routes() {
    assert_eq!(normalize_lan_token("  ab  "), "ab");
    assert!(lan_token_allows("", None));
    assert!(!lan_token_allows("secret", None));
    assert!(lan_token_allows("secret", Some("secret")));
    assert!(matches!(parse_lan_route("/"), LanRoute::Home));
    assert!(matches!(parse_lan_route("/d/job1"), LanRoute::Download { index: 0, .. }));
    assert!(matches!(parse_lan_route("/m/job1/2"), LanRoute::Media { index: 2, .. }));
    assert!(matches!(parse_lan_route("/favicon.png"), LanRoute::Favicon));
    assert!(matches!(parse_lan_route("/d/../x"), LanRoute::NotFound));
}

#[test]
fn pick_ipv4_allows_ethernet_skips_loopback() {
    assert_eq!(pick_lan_ipv4(&[LanIface { name: "lo0".into(), host_address: "127.0.0.1".into(), loopback: true }]), None);
    assert_eq!(
        pick_lan_ipv4(&[
            LanIface { name: "utun0".into(), host_address: "10.8.0.2".into(), loopback: false },
            LanIface { name: "en0".into(), host_address: "192.168.1.20".into(), loopback: false },
        ]),
        Some("192.168.1.20".into())
    );
    assert_eq!(choose_lan_port(17890, 10, &HashSet::from([17890, 17891])), Some(17892));
    assert_eq!(lan_public_url("10.0.0.8", 17890, "a b"), "http://10.0.0.8:17890/?k=a+b");
}
```

- [ ] **Step 2:** `export PATH="$HOME/.rustup/toolchains/stable-aarch64-apple-darwin/bin:$PATH"` 后 `cd src-tauri && cargo test lan::` Expected: FAIL（模块不存在）

- [ ] **Step 3: 实现 `lan.rs`，`lib.rs` 加 `mod lan;`。** `lan_token_allows`：stored 空则 true，否则 `query_k == Some(stored)`。`parse_lan_query` 用 `urlencoding` 或手写 `%xx` / `+` → 空格，与 `std` 即可；不要为这一点加 HTTP 框架。若手写 encoder 麻烦，可加 `form_urlencoded`（标准库 `application/x-www-form-urlencoded` 语义）。`lan_query_encode` 必须把空格编成 `+`。

- [ ] **Step 4:** `cargo test lan::` Expected: PASS

- [ ] **Step 5: Commit** `Add LAN token, route, and address helpers.`

---

### Task 2: 下载目标、Content-Type、handle 纯函数

**Files:**
- Modify: `src-tauri/src/lan.rs`
- Create: `src-tauri/src/lan_page.rs`（先可只放 `LanHistoryCopy` + 空 `render_lan_history_html` 返回含 `<title>LiteTrans</title>` 的最小 HTML；CSS 下一任务补齐）

**Interfaces:**
- Consumes: `queue::Job`、`JobStatus::Completed`、`job.output_paths` 空则 `output_path`
- Produces:

```rust
pub fn job_output_paths(job: &Job) -> Vec<String>;
pub fn resolve_lan_download(jobs: &[Job], job_id: &str, index: usize, exists: impl Fn(&str) -> bool) -> Option<LanDownloadTarget>;
pub fn lan_content_type(file_name: &str) -> &'static str; // 与 Android LanMedia.kt 同表
pub struct LanHttpRequest { pub method: String, pub path: String, pub query: HashMap<String, String>, pub headers: HashMap<String, String> }
pub struct LanHttpResponse { pub status: u16, pub content_type: String, pub body: Vec<u8>, pub headers: Vec<(String, String)>, pub file_path: Option<String>, pub send_body: bool }
pub fn handle_lan_request(req: &LanHttpRequest, jobs: &[Job], token: &str, exists: impl Fn(&str) -> bool, copy: &LanHistoryCopy) -> LanHttpResponse;
```

`handle_lan_request`：非 GET/HEAD → 405；口令失败 → 401 纯文本 `lan_need_token`；Home → 200 HTML（本任务可用最小 HTML）；Download/Media 找不到 → 404；HEAD 时 `send_body=false`。Favicon 本任务可 200 一个最小 PNG 常量（8 字节以上的合法 PNG 头 `%PNG`）。

- [ ] **Step 1: 测试**

```rust
#[test]
fn download_and_auth() {
    let job = completed_job("j1", "/tmp/a.mp4");
    let exists = |p: &str| p == "/tmp/a.mp4";
    let copy = LanHistoryCopy::english();
    let mut req = get("/");
    req.query.insert("k".into(), "nope".into());
    assert_eq!(handle_lan_request(&req, &[job.clone()], "secret", exists, &copy).status, 401);
    let ok = handle_lan_request(&get("/d/j1"), &[job.clone()], "", exists, &copy);
    assert_eq!(ok.status, 200);
    assert_eq!(ok.file_path.as_deref(), Some("/tmp/a.mp4"));
    let head = handle_lan_request(&head("/d/j1"), &[job], "", exists, &copy);
    assert!(!head.send_body);
}
```

`completed_job` 测试辅助：`status=Completed`，`output_path` 与 `output_paths`。`get`/`head` 设 `method`。

- [ ] **Step 2–4: TDD。** `lan_content_type("a.mp4") == "video/mp4"`。

- [ ] **Step 5: Commit** `Authorize LAN routes and resolve completed download paths.`

---

### Task 3: 网页 HTML（四 Tab）

**Files:**
- Modify: `src-tauri/src/lan_page.rs`
- Create: `src-tauri/src/lan_history.css`、`src-tauri/src/lan_history.js`

**Interfaces:**
- 把 `android/app/src/main/java/com/videoconverter/android/lan/LanHistoryPage.kt` 里的 `LAN_HISTORY_PAGE_CSS` / `LAN_HISTORY_PAGE_JS` **原文**拷进上述 css/js（去掉 Kotlin 字符串拼接）。`include_str!` 进 `render_lan_history_html`。
- Tab：Video / Audio / Image / Document，分组用现有 `history::history_segment`；图片从文档段里再按扩展名分到 Image（与 Android `LanLibrary` 相同：jpg/png/webp/gif/bmp 为 Image）。若 `history.rs` 没有 Image 段，在 `lan_page.rs` 做 `lan_library_tab(job, path)`，不要改手机。
- 无口令时 HTML 含 warning 文案。有口令时所有 `/m` `/d` 链接带 `?k=`。
- 完成且 `exists` 的项可点；默认选中第一条可打开项。

- [ ] **Step 1: 测试** `render_lan_history_html` 含 `LiteTrans`、四个 `data-tab-btn`、无口令时 warning、有 token 时 `k=`。

- [ ] **Step 2–4: 实现。** 对照 Android `lanLibraryItems` / `renderLanHistoryHtml` 移植，不要 CDN。

- [ ] **Step 5: Commit** `Render the LAN media-library page from current jobs.`

---

### Task 4: settings.json 的 lanShare

**Files:**
- Modify: `src-tauri/src/settings.rs`
- Modify: `src/types.ts` `SessionSettings`
- Modify: `src-tauri/src/lib.rs` `save_session_settings` 合并

**Interfaces:**

```rust
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LanShareSettings {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default)]
    pub token: String,
}

// SessionSettings 增加：
#[serde(default)]
pub lan_share: LanShareSettings,
```

保存：`incoming` 若带 `lanShare`（enabled 或 token 字段出现）则写入；token 先 `normalize_lan_token`。不要清掉 video/audio/document。语言规则不变。

- [ ] **Step 1: 测试** `saving_lan_does_not_clear_video`、`legacy_json_without_lan_share_defaults_off`。

- [ ] **Step 2–4: 实现。**

- [ ] **Step 5: Commit** `Persist LAN share enabled flag and token.`

---

### Task 5: TcpListener 服务 + 泵生命周期

**Files:**
- Create: `src-tauri/src/lan_server.rs`
- Modify: `src-tauri/src/lib.rs` `AppState`、`setup`、`save_session_settings`

**Interfaces:**
- `AppState` 增加 `lan: Mutex<LanRuntime>`，`LanRuntime { settings, bound: Option<(String, u16)>, stop: Option<mpsc::Sender<()>> }`
- `sync_lan_server(app) -> Result<LanStatus, String>`：
  - enabled=false → 停线程、bound=None
  - enabled=true：`pick_lan_ipv4(collect_ifaces())`，无地址 → bound=None，返回错误键 `lan_need_address`（不要听 127.0.0.1）
  - `choose_lan_port` 后 `TcpListener::bind((ip, port))`（只绑该 IP）。10 个都失败 → `lan_ports_busy`
  - 接受循环：读 request line + headers；`handle_lan_request`；若 `file_path` 有则按 Range 发文件（`NOFOLLOW_LINKS` 常规文件）；否则发 `body`
- 命令 `lan_status() -> LanStatus { enabled, token, url: Option<String>, error: Option<String> }`
- 命令 `set_lan_share(LanShareSettings)`：写入 settings 后 `sync_lan_server`
- `setup` 读档后若 enabled 则 sync；窗口退出 `RunEvent::Exit` 停服务
- Range：`Range: bytes=start-end`；合法 206；非法 416。与 Android `LanShareHandler` 对齐即可，单段 range。

- [ ] **Step 1: 测试** 纯函数 `parse_byte_range(header, total) -> Result<(u64,u64), ()>`：`"bytes=0-1"` + total 10 → `(0,1)`；越界 Err。服务集成：可在测试里 bind `127.0.0.1` **仅作为 handle 发文件测试**，不要把 loopback 当 `pick_lan_ipv4` 成功。另测 `sync` 在 enabled+无 iface 时不 bind。

- [ ] **Step 2–4: TDD 后接 setup。** 不要加 `tiny_http` / `hyper` 依赖。

- [ ] **Step 5: Commit** `Serve the LAN library on a bound IPv4 listener.`

---

### Task 6: 「我的」UI + 文案 + README

**Files:**
- Modify: `src/MinePage.tsx`、`src/App.tsx`、`src/api.ts`、`src/types.ts`、`src/App.css`、`src/locales/*.json`、`README.md`

**Interfaces:**
- `MinePageId` 加 `"lan"`。`NAV_GROUPS`：`[["lan","language"],["privacy","terms"],["about"]]`。`useState<MinePageId>("lan")`。
- 右栏：开关、口令 input、地址（只在 `url` 有值时）、复制、在浏览器打开。
- 复制：`navigator.clipboard.writeText(url)`；失败 `onNotice`。打开：现有 `openUrl(url)`。
- 局域网这一行：`url` 有值时按钮旁或副文案 `lan_status_on`（「已开启」）；侧栏「我的」仍不打数字角标。
- 开关打开失败：`onNotice(t(locale, errorKey))`，`lan_need_address` / `lan_ports_busy`。
- 五份 locale 至少：`mine_lan`、`lan_status_on`、`lan_token_label`、`lan_token_placeholder`、`lan_token_empty_hint`、`lan_open_warning`、`lan_address`、`lan_copy`、`lan_open_browser`、`lan_need_address`、`lan_ports_busy`、`lan_need_token`，以及 HTML 用的 `lan_segment_*` / `lan_download*` / `lan_preview_failed` / `lan_download_to_open` / 空态键（服务端 `LanHistoryCopy` 从当前 locale 填）。简体「局域网访问」「没有可用的局域网地址」。
- README 加一句：可在「我的」打开局域网，让同一网络的浏览器查看并下载已完成文件；默认关。不要写成云同步。

- [ ] **Step 1: 无组件测试。** `npx tsc --noEmit` 与 `node scripts/check-locales.mjs`。

- [ ] **Step 2: 实现。** `setLanShare` 后立刻 `lanStatus` 刷新。口令 `onBlur` 保存。

- [ ] **Step 3: 门禁**

```
node scripts/check-locales.mjs
npx tsc --noEmit
export PATH="$HOME/.rustup/toolchains/stable-aarch64-apple-darwin/bin:$PATH"
cd src-tauri && cargo test
cd .. && npm run smoke
```

Expected: 全 PASS。本机浏览器打开局域网：写入报告，不要假装点过。

- [ ] **Step 4: Commit** `Add the LAN access pane and start the share server from settings.`

---

## 自检

| 规格 | 任务 |
| --- | --- |
| 我的默认局域网；分组 局域网+语言 | 6 |
| 开关默认关；口令；地址；复制；打开浏览器 | 4–6 |
| 绑局域网 IPv4；有线可用；不听 0.0.0.0 | 1, 5 |
| 17890 × 10 | 1, 5 |
| `/` `/m` `/d` Range HEAD 401 | 2, 5 |
| 四 Tab 网页、桌面色 | 3 |
| lanShare 持久化、退出停、再开拉起 | 4, 5 |
| 不改 android/ios | 全局 |
