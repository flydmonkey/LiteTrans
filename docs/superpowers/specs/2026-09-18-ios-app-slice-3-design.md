# 轻转码 iOS 第 3 刀：Live Activity 与后台任务

叠在 [iOS 主规格](2026-09-18-ios-app-design.md) 已落地的三 Tab App（含第 2 刀转码与第 4 刀局域网）上，覆盖落地顺序第 3 项。锁屏 / Dynamic Island 跟 [HIG Live Activities](https://developer.apple.com/design/human-interface-guidelines/live-activities) 与 [Dynamic Island](https://developer.apple.com/design/human-interface-guidelines/designing-for-ios)。

本刀**不做** Office、音频后台、推送更新 Live Activity、Home Screen 小组件。

## 目标与约束

- 入队成功后：成功触感 `.success`；第一次入队再请求通知权限，被拒也继续转。
- 仅**进行中那一条**任务有 Live Activity。排队中的下一条要等当前条变成 running 再切过去。
- 四种展示都要有：Compact、Minimal、Expanded、Lock Screen。内容：文件名、百分比、取消。点按打开历史对应条。任务结束立刻撤掉。
- 锁屏不出现完整本地路径，只用 `Job.displayName` 的文件名。
- 百分比更新走静默 `Activity.update`，**不**用 alert（不点亮屏幕、不发声）。
- 后台用 `BGTaskScheduler` processing 尽量把当前 FFmpeg / AVFoundation / 文档任务跑完。系统仍可能挂起或杀掉；被杀后该任务记失败，可在历史重试。
- 不申请 `audio` 后台、不伪装播放器。最低 iOS 18，只打 iPhone。不上 App Store。
- 生产 UI 不用 hex；语义色；取消按钮 ≥ 44pt（Lock Screen / Expanded）。Compact / Minimal 不放取消，避免误触。

## 方案

Domain 纯函数根据 `jobs` 算出当前应展示的快照，以及相对「已展示 jobId」的命令：`start` / `update` / `end`。App 把命令映射到 ActivityKit。Widget Extension 只渲染四种布局。取消用 `LiveActivityIntent`，`openAppWhenRun = true`，打开 App 后取消对应任务（进程内泵才能停 FFmpeg）。

后台：`BGProcessingTaskRequest` 标识 `com.videoconverter.ios.process`。有 running/queued 且进入非 active 时提交请求；队列空时取消未执行的请求。任务到期或系统回调时，若仍有 running 则继续泵；若进程已被杀，启动后 `markInterrupted` 已有逻辑把 running 标失败。

不采用：推送 token 更新进度、App Group 跨进程取消、`beginBackgroundTask` 作为主策略、锁屏展示输出路径。

## 模块边界

| 单元 | 做什么 | 怎么用 | 依赖 |
| --- | --- | --- | --- |
| `Domain/LiveActivity.swift` | 快照、文件名、百分比、命令 | 纯函数 | Job |
| `Domain/BackgroundProcessing.swift` | 任务标识、何时提交/取消 BG 请求 | 纯函数 | JobStatus |
| `LiveActivity/ConvertActivityAttributes.swift` | ActivityKit 属性与 ContentState | App + Widget 共用文件 | ActivityKit |
| `LiteTransLiveActivity` 扩展 | 四种 SwiftUI 展示 + 取消按钮 | WidgetKit | 上者 |
| `LiveActivityController` | start/update/end Activity | AppModel 在 jobs 变化时调用 | ActivityKit |
| `BackgroundProcessingController` | 注册 identifier、提交 request | `scenePhase` + 队列状态 | BGTaskScheduler |
| `CancelConvertIntent` | 打开 App 并取消 job | Live Activity 按钮 | AppModel |

## 展示

- Compact leading：箭头转码 SF Symbol。trailing：`42%`。
- Minimal：百分比数字或同一 SF Symbol。
- Expanded / Lock Screen：文件名、`ProgressView`、百分比、取消。
- 点按：`litetrans://history?job={id}` → 切到历史 Tab，并把分段切到该任务所属段。
- Dynamic Island 背景保持系统黑；Lock Screen 可用 `systemBackground` 语义，不自定义品牌色块。

## 生命周期

- 入队且泵把一条标为 `running`：`start`（若已有别的 Activity 先 `end`）。
- `updateProgress`：`update` 同一 `jobId`。
- 当前 running 结束（完成 / 失败 / 取消）：若下一条已是 `running` 则 `start` 新快照，否则 `end`。
- 进程被杀：系统撤 Live Activity；下次启动不恢复已失败任务的 Activity。

## 权限与 Info

- App：`NSSupportsLiveActivities`、`NSSupportsLiveActivitiesFrequentUpdates`。
- `BGTaskSchedulerPermittedIdentifiers` = `com.videoconverter.ios.process`。
- `UIBackgroundModes` = `processing`。
- URL scheme `litetrans`。
- 通知：第一次成功入队 `UNUserNotificationCenter.requestAuthorization`；`NSUserNotificationsUsageDescription` 不需要独立 key，被拒不阻断转码。
- Widget bundle `com.videoconverter.ios.LiveActivity`，嵌入主 App。

## 测试

- Domain：无 running → idle；一条 running → start；同 id 进度变 → update；running 变 completed 且无下一条 → end；A 结束 B running → start B；文件名剥路径；百分比夹紧 0…100。
- Domain：有 running/queued 且进入后台 → 应提交 BG；队列空 → 取消。
- 真机：入队后锁屏 / Island 有进度；完成立刻消失；取消从 Expanded / Lock Screen 能停任务；杀进程后历史为失败可重试。

## 明确不做

- Office、音频后台、推送、Home Screen Widget、Watch、CarPlay
- Live Activity 里预览画面或输出路径
- 百分比更新用 alert
