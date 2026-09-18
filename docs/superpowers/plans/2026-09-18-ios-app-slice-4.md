# 轻转码 iOS 第 4 刀 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在「我的」加上局域网访问：前台用 Network.framework 提供与 Android 相同的电脑网页（口令、`/` `/d` `/m`、四 Tab 成品库）。

**Architecture:** Domain 纯函数移植 Android `LanShare` / `LanLibrary` / `LanMedia` / `renderLanHistoryHtml`，用 `swift test` 覆盖。App 用 `NWListener` 解析 HTTP/1.1、按 Domain 响应写回；`LanShareStore` 持久化开关与口令；`scenePhase != .active` 时停监听，不清 `enabled`。

**Tech Stack:** Swift 6、SwiftUI、iOS 18、Network.framework、Swift Testing、XcodeGen。无第三方 HTTP 库。

## Global Constraints

- Bundle ID `com.videoconverter.ios`，主屏幕名 `LiteTrans`，关于页写「轻转码」
- 最低 iOS 18，只打 iPhone arm64；不上 App Store
- 电脑网页协议与 Android 相同：端口首选 `17890`、尝试 10 次；口令查询 `k`；路由 `/` `/d/{jobId}[/{index}]` `/m/{jobId}[/{index}]`；GET/HEAD；Range bytes；四 Tab Video/Audio/Image/Document
- 仅前台监听。禁止音频后台、禁止 Live Activity（第 3 刀）、禁止伪装成播放器保活
- 生产 UI 不用 hex；语义色；触控 ≥ 44pt；网页 CSS 可沿用 Android 现页
- 不引入 FlyingFox / GCDWebServer
- 中文默认；Localizable 覆盖 en / zh-Hans / zh-Hant / ja / ko
- **不要提交 git，除非用户明确要求提交**

本计划覆盖 [2026-09-18-ios-app-slice-4-design.md](../specs/2026-09-18-ios-app-slice-4-design.md)。桌面 `src/`、`android/` 不改。

## File map

```
docs/superpowers/specs/2026-09-18-ios-app-slice-4-design.md
docs/superpowers/plans/2026-09-18-ios-app-slice-4.md
ios/LiteTrans/Domain/ConvertNavigation.swift   # MinePage.lan
ios/LiteTrans/Domain/LanShare.swift
ios/LiteTrans/Domain/LanMedia.swift
ios/LiteTrans/Domain/LanLibrary.swift
ios/LiteTrans/Domain/LanHistoryPage.swift
ios/LiteTrans/Data/LanShareStore.swift
ios/LiteTrans/Engine/LanShareServer.swift
ios/LiteTrans/UI/AppModel.swift
ios/LiteTrans/UI/MineView.swift
ios/LiteTrans/App/LiteTransApp.swift
ios/LiteTrans/Resources/Info.plist
ios/LiteTrans/Resources/Localizable.xcstrings
ios/project.yml
ios/LiteTransTests/Domain/LanShareTests.swift
ios/LiteTransTests/Domain/LanMediaTests.swift
ios/LiteTransTests/Domain/LanLibraryTests.swift
ios/LiteTransTests/Domain/LanHistoryHtmlTests.swift
ios/LiteTransTests/LanShareStoreTests.swift
```

---

### Task 1: 口令、路由、查询、地址、端口

**Files:**
- Create: `ios/LiteTrans/Domain/LanShare.swift`
- Modify: `ios/LiteTrans/Domain/ConvertNavigation.swift`（`MinePage` 增加 `.lan`）
- Test: `ios/LiteTransTests/Domain/LanShareTests.swift`
- Test: `ios/LiteTransTests/Domain/ConvertNavigationTests.swift`（`popMineBack(.lan) == .root`）

**Interfaces:**
- Consumes: `Job`、`JobStatus`、`OutputConfig`、`MediaInfo`
- Produces:
  - `public let lanSharePreferredPort = 17890`
  - `public let lanSharePortAttempts = 10`
  - `public struct LanShareSettings: Equatable, Sendable, Codable { public var enabled: Bool; public var token: String }`
  - `public func normalizeLanToken(_ raw: String) -> String`
  - `public func lanTokenAllows(storedToken: String, queryK: String?) -> Bool`
  - `public enum LanRoute: Equatable, Sendable { case home; case download(jobId: String, index: Int); case media(jobId: String, index: Int); case notFound }`
  - `public func parseLanRoute(_ path: String) -> LanRoute`
  - `public func parseLanQuery(_ rawQuery: String?) -> [String: String]`
  - `public func parseLanHeaderLines(_ lines: [String]) -> [String: String]`
  - `public func parseHttpRequestLine(_ line: String) -> LanHttpRequest?`
  - `public struct LanIface: Equatable, Sendable { public var name: String; public var hostAddress: String; public var loopback: Bool }`
  - `public func isLanWifiOrHotspotName(_ name: String) -> Bool`
  - `public func pickLanIpv4(_ ifaces: [LanIface]) -> String?`
  - `public func chooseLanPort(preferred: Int, attempts: Int, occupied: Set<Int>) -> Int?`
  - `public func lanPublicUrl(ip: String, port: Int, token: String) -> String`
  - `public func lanQueryEncode(_ raw: String) -> String`（与 Java `URLEncoder.encode(..., UTF-8)` 一致：空格为 `+`）

- [ ] **Step 1: Write the failing tests**

Create `ios/LiteTransTests/Domain/LanShareTests.swift`:

```swift
import Foundation
import Testing
@testable import LiteTransDomain

struct LanShareTests {
    @Test func defaultsAreOffAndEmptyToken() {
        let settings = LanShareSettings()
        #expect(settings.enabled == false)
        #expect(settings.token == "")
    }

    @Test func normalizeTrimsAndBlankBecomesEmpty() {
        #expect(normalizeLanToken("  secret  ") == "secret")
        #expect(normalizeLanToken("   ") == "")
        #expect(normalizeLanToken("") == "")
    }

    @Test func emptyTokenAllowsMissingOrAnyK() {
        #expect(lanTokenAllows(storedToken: "", queryK: nil))
        #expect(lanTokenAllows(storedToken: "", queryK: "whatever"))
    }

    @Test func setTokenRequiresExactMatch() {
        #expect(!lanTokenAllows(storedToken: "pw", queryK: nil))
        #expect(!lanTokenAllows(storedToken: "pw", queryK: ""))
        #expect(!lanTokenAllows(storedToken: "pw", queryK: "PW"))
        #expect(lanTokenAllows(storedToken: "pw", queryK: "pw"))
    }

    @Test func parsesHomeAndDownloadRoutes() {
        #expect(parseLanRoute("/") == .home)
        #expect(parseLanRoute("/d/a1") == .download(jobId: "a1", index: 0))
        #expect(parseLanRoute("/d/a1/2") == .download(jobId: "a1", index: 2))
        #expect(parseLanRoute("/d/") == .notFound)
        #expect(parseLanRoute("/d/a1/x") == .notFound)
        #expect(parseLanRoute("/d/../secret") == .notFound)
        #expect(parseLanRoute("/other") == .notFound)
        #expect(parseLanRoute("/m/a1") == .media(jobId: "a1", index: 0))
        #expect(parseLanRoute("/m/a1/2") == .media(jobId: "a1", index: 2))
        #expect(parseLanRoute("/m/") == .notFound)
        #expect(parseLanRoute("/m/../secret") == .notFound)
        #expect(parseLanRoute("/d/a1/../b") == .notFound)
    }

    @Test func prefersWifiIpv4AndSkipsLoopback() {
        #expect(pickLanIpv4([LanIface(name: "lo", hostAddress: "127.0.0.1", loopback: true)]) == nil)
        #expect(
            pickLanIpv4([
                LanIface(name: "rmnet0", hostAddress: "10.20.30.40", loopback: false),
                LanIface(name: "wlan0", hostAddress: "10.0.0.8", loopback: false),
            ]) == "10.0.0.8"
        )
        #expect(pickLanIpv4([LanIface(name: "wlan1", hostAddress: "192.168.43.1", loopback: false)]) == "192.168.43.1")
        #expect(pickLanIpv4([LanIface(name: "wlan0", hostAddress: "fe80::1", loopback: false)]) == nil)
        #expect(pickLanIpv4([LanIface(name: "ap0", hostAddress: "192.168.49.1", loopback: false)]) == "192.168.49.1")
        #expect(pickLanIpv4([LanIface(name: "softap0", hostAddress: "192.168.43.1", loopback: false)]) == "192.168.43.1")
        #expect(pickLanIpv4([LanIface(name: "swlan0", hostAddress: "192.168.50.1", loopback: false)]) == "192.168.50.1")
        #expect(pickLanIpv4([LanIface(name: "en0", hostAddress: "10.0.0.8", loopback: false)]) == "10.0.0.8")
        #expect(pickLanIpv4([LanIface(name: "bridge100", hostAddress: "172.20.10.1", loopback: false)]) == "172.20.10.1")
    }

    @Test func cellularOnlyIpv4IsIgnored() {
        #expect(pickLanIpv4([LanIface(name: "rmnet0", hostAddress: "10.20.30.40", loopback: false)]) == nil)
        #expect(pickLanIpv4([LanIface(name: "pdp_ip0", hostAddress: "10.20.30.40", loopback: false)]) == nil)
    }

    @Test func portWalksForwardWhenBusy() {
        #expect(chooseLanPort(preferred: 17890, attempts: 10, occupied: []) == 17890)
        #expect(chooseLanPort(preferred: 17890, attempts: 10, occupied: [17890, 17891]) == 17892)
        #expect(chooseLanPort(preferred: 17890, attempts: 10, occupied: Set(17890..<17900)) == nil)
    }

    @Test func publicUrlEncodesToken() {
        #expect(lanPublicUrl(ip: "10.0.0.8", port: 17890, token: "") == "http://10.0.0.8:17890/")
        let url = lanPublicUrl(ip: "10.0.0.8", port: 17890, token: "a b")
        #expect(url.hasPrefix("http://10.0.0.8:17890/?"))
        #expect(url.contains("k="))
        #expect(!url.contains("a b"))
    }

    @Test func parseQueryDecodesK() {
        #expect(parseLanQuery("k=a+b")["k"] == "a b")
    }

    @Test func parsesRangeHeaderLine() {
        #expect(parseLanHeaderLines(["Range: bytes=0-1"])["range"] == "bytes=0-1")
    }
}
```

Add to `mineBackGoesRoot` in `ConvertNavigationTests.swift`:

```swift
#expect(popMineBack(.lan) == .root)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd ios && swift test --filter LanShareTests`

Expected: FAIL — `LanShareSettings` not found.

- [ ] **Step 3: Write minimal implementation**

`isLanWifiOrHotspotName` 必须同时识别 Android 名（`wlan*` / `ap*` / `*wlan*` / `*swlan*` / `*softap*`）和 iOS 名（`en*`、`bridge*`）。跳过 `pdp_ip*`、`rmnet*`、loopback。

`parseLanRoute`：去掉 `?` 之后；`/` 或空为 Home；按 `/` 拆成 2…3 段；第一段只能是 `d` 或 `m`；`jobId` 空、含 `..` 或 `/` → NotFound；第三段必须是 ≥0 的整数。

`lanQueryEncode`：`addingPercentEncoding` 后把 `%20` 换成 `+`，与 Java `URLEncoder` 一致。

`LanShareSettings` 默认 `enabled: false, token: ""`。

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd ios && swift test --filter LanShareTests && swift test --filter ConvertNavigationTests`

Expected: PASS

- [ ] **Step 5: Commit**

跳过，除非用户要求提交。

---

### Task 2: 预览类型、Content-Type、Range

**Files:**
- Create: `ios/LiteTrans/Domain/LanMedia.swift`
- Test: `ios/LiteTransTests/Domain/LanMediaTests.swift`

**Interfaces:**
- Consumes: Task 1 无强制依赖；`Job`、`isDocumentPreset`、`documentExtension`、`resolveConfig`
- Produces:
  - `public enum LanPreviewKind: Equatable, Sendable { case video, audio, pdf, image, file }`
  - `public func lanPreviewKind(_ fileName: String) -> LanPreviewKind`
  - `public func lanContentType(_ fileName: String) -> String`
  - `public func lanContentDisposition(_ fileName: String, inline: Bool = false) -> String`
  - `public func isLanContentLocation(_ location: String) -> Bool`
  - `public enum LanByteRange: Equatable, Sendable { case whole; case partial(start: Int64, endInclusive: Int64); case unsatisfiable }`
  - `public func parseLanByteRange(_ header: String?, total: Int64) -> LanByteRange`
  - `public func lanContentRangeValue(start: Int64, endInclusive: Int64, total: Int64) -> String`
  - `public func lanUnsatisfiableContentRange(_ total: Int64) -> String`
  - `public func applyLanResponseRange(_ response: LanHttpResponse, total: Int64) -> LanHttpResponse`
  - `public func lanReadyFileResponse(_ sized: LanHttpResponse, opened: Bool) -> LanHttpResponse`
  - `public func copyLanRange(from: Data, start: Int64, length: Int64) -> Data`

- [ ] **Step 1: Write the failing tests**

Create `ios/LiteTransTests/Domain/LanMediaTests.swift`，断言与 Android `LanMediaTest` / `LanHttpWriteTest` 相同：

- `lanPreviewKind("a.mp4") == .video`，`a.MKV` video，`a.mp3` audio，`a.pdf` pdf，`a.png`/`a.gif` image，`a.docx`/`a.xlsx`/`a.bin` file
- `lanContentType("a.mp4") == "video/mp4"`，mov/mkv/webm/avi/pdf/bin 与 Android 表一致
- disposition：默认 `attachment;`，`inline: true` 为 `inline;`，文件名去掉 `\r` `\n`
- `isLanContentLocation("content://media/...")` true；本地路径与 `file://` false
- Range：`nil`/`""` → whole；`bytes=0-49` → partial 0...49；`Bytes=50-` → 50...99；`bytes=0-9999` 截到 99；`bytes=100-110`、`80-20`、多段 → unsatisfiable
- `lanContentRangeValue(0, 49, 100) == "bytes 0-49/100"`
- `applyLanResponseRange`：无 Range 保持 200 + Content-Length；`bytes=0-9` → 206；坏 Range → 416、`filePath == nil`、`sendBody == false`
- `lanReadyFileResponse(opened: false)` → 404 `"Not Found"`；`opened: true` 保持原响应；无 `filePath` 的内存响应不受影响
- `copyLanRange`：`Data([10,11,12,13,14])` start 1 length 3 → `[11,12,13]`

`LanHttpResponse` 在本任务或 Task 1 定义：

```swift
public struct LanHttpRequest: Equatable, Sendable {
    public var method: String
    public var path: String
    public var query: [String: String]
    public var headers: [String: String]
}

public struct LanHttpResponse: Equatable, Sendable {
    public var status: Int
    public var contentType: String
    public var body: Data
    public var headers: [String: String]
    public var filePath: String?
    public var rangeHeader: String?
    public var sendBody: Bool
    public var byteStart: Int64
    public var byteLength: Int64?
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd ios && swift test --filter LanMediaTests`

Expected: FAIL — types missing.

- [ ] **Step 3: Write minimal implementation**

Range 解析对齐 Android：不支持 suffix-range（`-500`）；`startText.toLongOrNull()` 失败即 Unsatisfiable；`end` 空则 `total-1`。`copyLanRange` 用 `Data` 切片即可，不需要 InputStream。

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd ios && swift test --filter LanMediaTests`

Expected: PASS

- [ ] **Step 5: Commit** — 跳过，除非用户要求。

---

### Task 3: 四 Tab 成品库 + HTML

**Files:**
- Create: `ios/LiteTrans/Domain/LanLibrary.swift`
- Create: `ios/LiteTrans/Domain/LanHistoryPage.swift`
- Modify: `ios/LiteTrans/Domain/LanShare.swift`（`jobOutputPaths`、`resolveLanDownload`、`lanFileIsRegular`、`LanHistoryCopy`、`escapeHtml`、`lanHistoryDownloadHref`）
- Test: `ios/LiteTransTests/Domain/LanLibraryTests.swift`
- Test: `ios/LiteTransTests/Domain/LanHistoryHtmlTests.swift`

**Interfaces:**
- Consumes: Task 1–2、`resolved` 输出路径语义、`resolveConfig`
- Produces:
  - `public enum LanLibraryTab: String, CaseIterable, Sendable { case video, audio, image, document }`
  - `public func lanLibraryTabWireName(_ tab: LanLibraryTab) -> String` → `"video"` / `"audio"` / `"image"` / `"document"`
  - `public struct LanLibraryItem`
  - `public func lanLibraryItems(_ jobs: [Job], fileExists: (String) -> Bool) -> [LanLibraryItem]`
  - `public func lanLibraryItemsFor(_ items: [LanLibraryItem], tab: LanLibraryTab) -> [LanLibraryItem]`
  - `public func lanDefaultLibraryTab(_ items: [LanLibraryItem]) -> LanLibraryTab`
  - `public func renderLanHistoryHtml(jobs: [Job], token: String, copy: LanHistoryCopy, fileExists: (String) -> Bool) -> String`
  - `public func englishLanHistoryCopy() -> LanHistoryCopy`（测试夹具可放测试文件）

- [ ] **Step 1: Write the failing tests**

`LanLibraryTests` 对齐 Android `LanLibraryTest`：按输出扩展名分 Tab（不是历史页三分段）；跳过未完成/缺失；多输出拆开且 `needsIndex`；默认 Tab 跳过空 Tab。

`LanHistoryHtmlTests` 对齐 Android `LanHistoryHtmlTest`：含 `LiteTrans`、四 `data-tab-btn`、`data-media="/m/v"`、token 时 `data-download="/d/v?k="`、隐藏 `content://secret` 与未完成任务、含 `#ecece8` / `showTab` / `prefers-reduced-motion`、无 `<script src` / `cdn.`、HTML 转义、空 Tab 文案、图片不进 document pane、默认第一个非空 Tab `class="on"`。

测试夹具 `englishLanHistoryCopy()`：

```swift
LanHistoryCopy(
    warning: "Anyone on this network who has the address can view history and download finished files.",
    video: "Video", audio: "Audio", document: "Documents", image: "Images",
    emptyVideo: "No video history yet", emptyAudio: "No audio history yet",
    emptyDocument: "No document history yet", emptyImage: "No image history yet",
    download: "Download", downloadNamed: "Download %1$@", downloadIndex: "Download #%1$d",
    statusQueued: "Queued", statusRunning: "Converting", statusCompleted: "Done",
    statusFailed: "Failed", statusCancelled: "Cancelled",
    needToken: "Password required",
    previewFailed: "Can't preview. Download the file instead.",
    downloadToOpen: "Download and open it on your computer."
)
```

HTML 页 CSS/JS **必须与** `android/app/src/main/java/com/videoconverter/android/lan/LanHistoryPage.kt` 中 `LAN_HISTORY_PAGE_CSS` / `LAN_HISTORY_PAGE_JS` 逐字相同。

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd ios && swift test --filter LanLibraryTests && swift test --filter LanHistoryHtmlTests`

Expected: FAIL — symbols missing.

- [ ] **Step 3: Write implementation**

`lanLibraryItems`：`jobs.reversed()`，只收 `.completed`，路径用 `jobOutputPaths`。`needsIndex = paths.count > 1 || index > 0`。`lanPreviewFileName`：content URI 或无扩展名时用预设 container（文档走 `documentExtension`）。

`lanFileIsRegular`：`FileManager` 查 `.isRegularFile` 且非 symlink。

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd ios && swift test --filter LiteTransDomainTests`

Expected: PASS（含既有 Domain 测试）

- [ ] **Step 5: Commit** — 跳过，除非用户要求。

---

### Task 4: `handleLanRequest`

**Files:**
- Modify: `ios/LiteTrans/Domain/LanShare.swift`
- Test: `ios/LiteTransTests/Domain/LanShareTests.swift`（追加 handler 用例）

**Interfaces:**
- Consumes: Tasks 1–3
- Produces: `public func handleLanRequest(_ request: LanHttpRequest, jobs: [Job], token: String, exists: (String) -> Bool, copy: LanHistoryCopy) -> LanHttpResponse`

- [ ] **Step 1: Write the failing tests**

对齐 Android `LanShareHandlerTest` + `LanShareDownloadTest` 的 handler/download 断言：

- POST → 405
- 设口令无 `k` → 401 body `"Password required"`，不含文件名
- 正确 `k` GET `/` → 200 `text/html`，含 `data-media="/m/v"`
- GET `/d/v` → 200、`filePath`、`Content-Disposition` 含 `attachment`
- 排队任务 / 未知路径 / 越界 index → 404
- GET `/m/v` → `inline;`、`Accept-Ranges: bytes`、`sendBody == true`
- HEAD `/m/v` → `sendBody == false` 仍有 `filePath`
- Range 头透传到 `rangeHeader`
- HEAD 401/404 的 `sendBody == false`
- 仅 completed + exists 可下载；content URI 音频下载名以 `.mp3` 结尾不是 `.mp4`

- [ ] **Step 2: Run to verify fail** — `swift test --filter LanShareTests`

- [ ] **Step 3: Implement `handleLanRequest`**

非 GET/HEAD → 405。口令失败 → 401 `copy.needToken`。Home 渲染 HTML。Download/Media 用 `resolveLanDownload`；Media 为 inline。`rangeHeader = request.headers["range"]`。

- [ ] **Step 4: Run** `cd ios && swift test` — Domain 全绿。

- [ ] **Step 5: Commit** — 跳过，除非用户要求。

---

### Task 5: `LanShareStore`

**Files:**
- Create: `ios/LiteTrans/Data/LanShareStore.swift`
- Test: `ios/LiteTransTests/LanShareStoreTests.swift`

**Interfaces:**
- Consumes: `LanShareSettings`、`normalizeLanToken`
- Produces: `struct LanShareStore { static let key = "liteTrans.lanShare"; func load() -> LanShareSettings; func save(_ settings: LanShareSettings) }`

- [ ] **Step 1: Write the failing XCTest**

对齐 Android `LanShareStoreTest` 语义（UserDefaults 代替文件）：

- 缺 key → `enabled == false, token == ""`
- save 再 load 往返 `enabled: true, token: "secret"`
- save `"  x  "` 后 token 为 `"x"`
- 损坏 JSON → 默认关闭

- [ ] **Step 2: Run** `cd ios && xcodebuild test -scheme LiteTrans -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:LiteTransTests/LanShareStoreTests`

若模拟器名不同，用 `xcrun simctl list devices available`。

Expected: FAIL — type missing.

- [ ] **Step 3: Implement** JSON encode/decode `LanShareSettings`；save 前 `token = normalizeLanToken`。

- [ ] **Step 4: Run store tests** — PASS

- [ ] **Step 5: Commit** — 跳过，除非用户要求。

---

### Task 6: `LanShareServer` + 前台生命周期

**Files:**
- Create: `ios/LiteTrans/Engine/LanShareServer.swift`
- Modify: `ios/LiteTrans/UI/AppModel.swift`
- Modify: `ios/LiteTrans/App/LiteTransApp.swift`

**Interfaces:**
- Consumes: Domain HTTP API、`Job` 列表、`LanShareSettings`
- Produces:
  - `@MainActor final class LanShareServer`：`public private(set) var boundURL: String?`、`public private(set) var lastError: String?`（`needWifi` / `portsBusy` 键或已本地化句子由 UI 决定）
  - `func apply(enabled: Bool, token: String, jobs: [Job], sceneActive: Bool, copy: LanHistoryCopy)`
  - 场景：`enabled && sceneActive` → `pickLanIpv4` + `chooseLanPort` + `NWListener`；否则 `cancel`
  - HTTP：读到空行后 `parseHttpRequestLine` + `parseLanHeaderLines` → `handleLanRequest` → 若有 `filePath` 则 `lanFileIsRegular` + `applyLanResponseRange` + 按 `byteStart`/`byteLength`/`sendBody` 写 body

收集网卡：`getifaddrs`，IPv4 `en*`/`bridge*`/`wlan*`/`ap*`/`softap*`，排除 loopback 与 `pdp_ip`。

打开开关时对 `http://<lan-ip>:<port>/` 发一次短 `NWConnection`（或绑定即触发）以弹出「本地网络」。被拒则 `enabled = false`。

- [ ] **Step 1: Write a Domain-level smoke already covered by Task 4.** Engine 用临时文件 + 本机 loopback **不作为产品绑定**；产品只绑 Wi‑Fi IP。可用 `swift test` 已覆盖的 `applyLanResponseRange` 作为写文件依据。若可在 XCTest 里对 `NWListener` 绑 `127.0.0.1` 做一次 GET，仅用于开发验证，不要让产品 `pickLanIpv4` 选出 loopback。

- [ ] **Step 2: Implement server**

`NWListener` on IPv4. 每个 `NWConnection` 累积 Data 直到 `\r\n\r\n`。响应：

```
HTTP/1.1 \(status)\r\n
Content-Type: ...\r\n
（response.headers）
Connection: close\r\n
\r\n
body if sendBody
```

状态行：200 OK / 206 Partial Content / 401 Unauthorized / 404 Not Found / 405 Method Not Allowed / 416 Range Not Satisfiable。

- [ ] **Step 3: Wire AppModel**

`var lanShare = LanShareSettings()`，`didSet` save。`LanShareServer` 成员。`syncLanShare(sceneActive:)` 在 load、jobs 变化、settings 变化、scenePhase 时调用。

`LiteTransApp`：

```swift
@Environment(\.scenePhase) private var scenePhase
.onChange(of: scenePhase) { _, phase in
    model.syncLanShare(sceneActive: phase == .active)
}
```

- [ ] **Step 4: Domain tests still PASS.** 真机安装后用电脑浏览器抽测（Task 7）。

- [ ] **Step 5: Commit** — 跳过，除非用户要求。

---

### Task 7: 「我的」局域网页、文案、权限说明

**Files:**
- Modify: `ios/LiteTrans/UI/MineView.swift`
- Modify: `ios/LiteTrans/Resources/Localizable.xcstrings`
- Modify: `ios/LiteTrans/Resources/Info.plist`
- Modify: `ios/project.yml`
- Test: `ios/LiteTransTests/LocalizationTests.swift`（`mine_lan` 五语）

**Interfaces:**
- Consumes: `MinePage.lan`、`AppModel.lanShare`、`LanShareServer.boundURL`

HIG：Settings 分组列表；权限被拒用 `UIApplication.openSettingsURLString`；开关默认关；44pt。

- [ ] **Step 1: Failing localization test**

```swift
func testMineLanSwitchesWithLocale() {
    XCTAssertEqual(localizedText("mine_lan", locale: Locale(identifier: "en")), "LAN access")
    XCTAssertEqual(localizedText("mine_lan", locale: Locale(identifier: "zh-Hans")), "局域网访问")
}
```

- [ ] **Step 2: Run to fail** — key missing.

- [ ] **Step 3: Strings + UI**

文案（en / zh-Hans / zh-Hant / ja / ko）对齐 Android `mine_lan`、`lan_token_*`、`lan_open_warning`、`lan_need_wifi`、`lan_ports_busy`，并新增：

- `lan_copy`：Copy / 复制 / 複製 / コピー / 복사
- `lan_open_settings`：Open Settings / 打开设置 / 打開設定 / 設定を開く / 설정 열기
- `lan_local_network_denied`：Local Network access is off. Turn it on in Settings. / 未允许本地网络。请在设置中打开。
- `history_empty_image`：No image history yet / 还没有图片记录 / …

`Info.plist` + `project.yml`：

```
NSLocalNetworkUsageDescription: LiteTrans serves finished files on your Wi‑Fi so you can open them in a computer browser.
```

`MineView` 第一组：

```swift
NavigationLink(value: MinePage.lan) {
    Text(text("mine_lan")).frame(minHeight: 44, alignment: .leading)
}
NavigationLink(value: MinePage.language) { ... }
```

`LanShareView`：`List` insetGrouped。Section 1：`Toggle` ≥44pt + footer `lan_open_warning`。Section 2：口令 `TextField`。Section 3：地址 `LabeledContent` + Copy 按钮（`UIPasteboard`）；无 Wi‑Fi / 端口忙 / 权限被拒时用 footer 说明；被拒时 Button 打开设置。

`MinePageDestination` `.lan` → `LanShareView()`，导航标题 `mine_lan`。

- [ ] **Step 4: Verify**

1. `cd ios && swift test`
2. `xcodegen` 若改了 `project.yml`
3. 真机安装：开开关 → 系统本地网络 → 电脑浏览器打开地址 → 四 Tab 能播/下；进后台地址不可达；回前台恢复；关开关停止。

- [ ] **Step 5: Commit** — 跳过，除非用户要求。

---

## Self-review

1. Spec coverage：开关/口令/地址/复制、本地网络权限、前台-only、Wi‑Fi IPv4、17890×10、四 Tab 协议、Range、Mine push — 均有任务。Live Activity 明确不做。
2. Placeholder scan：CSS/JS 以 Android 源文件为逐字源，实现时写入 `LanHistoryPage.swift`。
3. Types：`LanShareSettings`、`LanHttpRequest/Response`、`handleLanRequest` 在后续任务中名称一致。
