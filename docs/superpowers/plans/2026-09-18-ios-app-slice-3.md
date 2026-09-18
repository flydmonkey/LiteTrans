# 轻转码 iOS 第 3 刀 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 入队后锁屏 / Dynamic Island 跟踪进行中那一条转码，结束后立刻撤掉；后台用 processing 任务尽量跑完当前作业。

**Architecture:** Domain 纯函数根据 `jobs` 产出 Live Activity 命令与是否提交 BG 请求。App 映射到 ActivityKit / `BGTaskScheduler`。Widget Extension 只渲染四种 HIG 展示。取消 Intent 打开 App 再停泵。

**Tech Stack:** Swift 6、SwiftUI、ActivityKit、WidgetKit、BGTaskScheduler、Swift Testing、XcodeGen。

## Global Constraints

- iOS 18，iPhone arm64，bundle `com.videoconverter.ios`
- Widget `com.videoconverter.ios.LiveActivity`
- BG identifier `com.videoconverter.ios.process`
- 不申请 audio 后台；百分比更新不 alert；锁屏只用文件名
- Domain 包继续在 macOS `swift test`，禁止把 ActivityKit 放进 Domain
- 单任务队列、预设 ID、局域网前台 HTTP 不变

---

### Task 1: Live Activity Domain 命令

**Files:**
- Create: `ios/LiteTrans/Domain/LiveActivity.swift`
- Test: `ios/LiteTransTests/Domain/LiveActivityTests.swift`

**Interfaces:**
- Produces:
  - `LiveActivitySnapshot(jobId: String, filename: String, percent: Int)`
  - `LiveActivityCommand` = `idle | start(LiveActivitySnapshot) | update(LiveActivitySnapshot) | end(jobId: String)`
  - `liveActivityFilename(_ displayName: String) -> String`
  - `liveActivityPercent(_ progress: Double) -> Int`
  - `liveActivitySnapshot(_ job: Job) -> LiveActivitySnapshot`
  - `liveActivityCommand(displayedJobId: String?, jobs: [Job]) -> LiveActivityCommand`

- [ ] **Step 1: Write the failing tests**

```swift
import Testing
@testable import LiteTransDomain

struct LiveActivityTests {
    @Test func filenameStripsPath() {
        #expect(liveActivityFilename("/tmp/假期.mp4") == "假期.mp4")
        #expect(liveActivityFilename("clip.mov") == "clip.mov")
    }

    @Test func percentClamps() {
        #expect(liveActivityPercent(-1) == 0)
        #expect(liveActivityPercent(42.9) == 42)
        #expect(liveActivityPercent(100) == 100)
        #expect(liveActivityPercent(140) == 100)
    }

    @Test func idleWhenNothingRunning() {
        let queued = lanTestJob(id: "a", status: .queued, outputPaths: ["/t/a.mp4"], displayName: "a.mp4")
        #expect(liveActivityCommand(displayedJobId: nil, jobs: [queued]) == .idle)
        #expect(liveActivityCommand(displayedJobId: "a", jobs: [queued]) == .end(jobId: "a"))
    }

    @Test func startsRunningJob() {
        var job = lanTestJob(id: "a", status: .running, outputPaths: ["/t/a.mp4"], displayName: "a.mp4")
        job.progress = 10
        let command = liveActivityCommand(displayedJobId: nil, jobs: [job])
        #expect(command == .start(LiveActivitySnapshot(jobId: "a", filename: "a.mp4", percent: 10)))
    }

    @Test func updatesSameRunningJob() {
        var job = lanTestJob(id: "a", status: .running, outputPaths: ["/t/a.mp4"], displayName: "a.mp4")
        job.progress = 40
        #expect(
            liveActivityCommand(displayedJobId: "a", jobs: [job])
                == .update(LiveActivitySnapshot(jobId: "a", filename: "a.mp4", percent: 40))
        )
    }

    @Test func switchesToNextRunningJob() {
        let done = lanTestJob(id: "a", status: .completed, outputPaths: ["/t/a.mp4"], displayName: "a.mp4")
        var next = lanTestJob(id: "b", status: .running, outputPaths: ["/t/b.mp4"], displayName: "b.mp4")
        next.progress = 1
        #expect(
            liveActivityCommand(displayedJobId: "a", jobs: [done, next])
                == .start(LiveActivitySnapshot(jobId: "b", filename: "b.mp4", percent: 1))
        )
    }
}
```

- [ ] **Step 2: Run** `cd ios && swift test --filter LiveActivityTests`
Expected: FAIL compile

- [ ] **Step 3: Implement** `LiveActivity.swift` so the first running job wins; `end` if displayed id is set and no running job; `start` if displayed id differs from running id.

- [ ] **Step 4: Re-run** Expected: PASS

- [ ] **Step 5: Commit** `Add Live Activity domain commands for the running job.`

---

### Task 2: Background processing Domain

**Files:**
- Create: `ios/LiteTrans/Domain/BackgroundProcessing.swift`
- Test: `ios/LiteTransTests/Domain/BackgroundProcessingTests.swift`

**Interfaces:**
- Produces:
  - `backgroundProcessingTaskIdentifier -> String` = `"com.videoconverter.ios.process"`
  - `shouldSubmitBackgroundProcessing(_ jobs: [Job]) -> Bool` 有 queued 或 running 为 true
  - `historyDeepLink(jobId: String) -> String` = `"litetrans://history?job=" + jobId`
  - `historyJobFromDeepLink(_ url: URL) -> String?`

- [ ] **Step 1: Failing tests** for identifier, submit true/false, deep link parse (ignore unknown hosts).

- [ ] **Step 2: Run** `swift test --filter BackgroundProcessingTests` Expected: FAIL

- [ ] **Step 3: Implement**

- [ ] **Step 4: Tests PASS**

- [ ] **Step 5: Commit** `Add background processing flags and history deep links.`

---

### Task 3: Widget 扩展与四种展示

**Files:**
- Create: `ios/LiteTrans/LiveActivity/ConvertActivityAttributes.swift`
- Create: `ios/LiteTransLiveActivity/LiteTransLiveActivityBundle.swift`
- Create: `ios/LiteTransLiveActivity/ConvertLiveActivityWidget.swift`
- Create: `ios/LiteTransLiveActivity/CancelConvertIntent.swift`
- Modify: `ios/project.yml` — widget target + app embed + Info keys
- Run: `cd ios && xcodegen generate`

**Interfaces:**
- `ConvertActivityAttributes: ActivityAttributes` with `jobId: String`, `ContentState { filename, percent }`
- Widget `ActivityConfiguration` 四种区域；Lock Screen / Expanded 有取消按钮
- Intent `CancelConvertIntent: LiveActivityIntent` 带 `jobId`，`openAppWhenRun = true`

- [ ] **Step 1: Add XcodeGen widget target** bundle `com.videoconverter.ios.LiveActivity`，`NSExtensionPointIdentifier` `com.apple.widgetkit-extension`。App Info：`NSSupportsLiveActivities` true、`NSSupportsLiveActivitiesFrequentUpdates` true、`BGTaskSchedulerPermittedIdentifiers`、`UIBackgroundModes` processing、URL scheme `litetrans`。

- [ ] **Step 2: Shared attributes file** included in App `LiteTrans/` 与 extension。

- [ ] **Step 3: Four presentations** Compact leading `arrow.triangle.2.circlepath` trailing percent；Minimal percent；Expanded + Lock Screen 文件名、ProgressView、取消。不用 alert。Semantic colors。取消 ≥ 44pt。

- [ ] **Step 4: `xcodegen generate` and `xcodebuild -scheme LiteTrans -destination 'generic/platform=iOS Simulator' build`** Expected: BUILD SUCCEEDED

- [ ] **Step 5: Commit** `Add Live Activity widget presentations and Info keys.`

---

### Task 4: App 接线：Activity、触感、通知、深度链接、BGTask

**Files:**
- Create: `ios/LiteTrans/Engine/LiveActivityController.swift`
- Create: `ios/LiteTrans/Engine/BackgroundProcessingController.swift`
- Modify: `ios/LiteTrans/UI/AppModel.swift`
- Modify: `ios/LiteTrans/App/LiteTransApp.swift`
- Modify: `ios/LiteTrans/App/RootView.swift` (open job from deep link)
- Modify: `ios/LiteTrans/Resources/Localizable.xcstrings` — `live_activity_cancel`, `live_activity_converting`
- Modify: `README.md` — 第 3 刀已提供；Office 尚未

**Interfaces:**
- `LiveActivityController.sync(jobs:)` 读 `displayedJobId`，执行 Domain 命令
- `BackgroundProcessingController.register()` 在 App 启动；`sync(jobs:sceneActive:)`
- `start()` 成功后 haptic + 首次通知请求
- `onOpenURL` 解析 job id，设 `tab = .history`、对应 `historySegment`、可选高亮

- [ ] **Step 1: Controller syncs from `replaceJob` / `updateProgress` / `cancelJob` / pump 状态变化**

- [ ] **Step 2: BGTask** `BGProcessingTaskRequest(identifier:)` `requiresExternalPower = false` `requiresNetworkConnectivity = false`；active 时不 submit；离开 active 且 `shouldSubmitBackgroundProcessing` 时 submit；队列空时 `cancel`。Handler 调 `pump.start` 若队列仍有作业，结束时 `setTaskCompleted`。

- [ ] **Step 3: URL** `onOpenURL`；Intent 把 jobId 放进 `liteTrans.pendingCancelJobId` UserDefaults，App `onAppear` / scene active 读到则 `cancelJob`。

- [ ] **Step 4: Domain tests still pass；simulator build**

- [ ] **Step 5: Commit** `Wire Live Activity, processing tasks, and history deep links.`

---

### Task 5: 真机冒烟说明

**Files:**
- Modify: `README.md` iOS 冒烟清单加 Live Activity 三项

- [ ] **Step 1: 清单** 入队后锁屏有百分比；完成消失；Expanded 取消能停。

- [ ] **Step 2: Commit** `Document iOS Live Activity smoke checks.`
