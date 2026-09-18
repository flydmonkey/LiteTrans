# iOS 视频合并 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 iOS 转码视频页增加「合并」预设，把多段视频按顺序重编码后拼成一个 MP4。

**Architecture:** Domain 纯函数决定预设、开始条件、入队成 **一条** Job、以及每段 normalize / concat 的 FFmpeg argv。`FFmpegRunner` 按 Job 跑多段再 concat demuxer。UI 只加主预设卡、拖动排序和文案。整条功能只在分支 `ios-video-merge`。

**Tech Stack:** Swift 6、SwiftUI、iOS 18、Swift Testing、进程内 FFmpegKit。

## Global Constraints

- 只改 iOS；不改 `android/`、`src/`、桌面
- 分支 `ios-video-merge`；不要了就删分支，不进 `master`
- 不要提交 `android/app/src/test/java/com/videoconverter/android/ui/RootTabsTest.kt`
- Bundle ID `io.github.flydmonkey.litetrans`；不改启动图
- 预设 id `video-concat`；`engineKind == .ffmpeg`；输出一个 H.264 AAC MP4
- 对齐列表 **第一条** 的偶数宽高和帧率（缺省 30）；contain + 黑边
- ≥ 2 段、≤ 20 段；无裁切；无音轨补静音
- Domain 继续 `cd ios && swift test`；不测真实跑片
- 文案 en / zh-Hans / zh-Hant / ja / ko；触控 ≥ 44pt

本计划覆盖 [2026-09-18-ios-video-merge-design.md](../specs/2026-09-18-ios-video-merge-design.md)。

## File map

```
ios/LiteTrans/Domain/ConvertNavigation.swift   # 第 5 张主卡、trim/分辨率/canStart
ios/LiteTrans/Domain/Concat.swift              # 新建：对齐目标、argv、list、stem
ios/LiteTrans/Domain/Presets.swift             # resolveConfig video-concat；错误文案
ios/LiteTrans/Domain/Models.swift              # concatSourceUris、concatMedias
ios/LiteTrans/Domain/Queue.swift               # 一条 Job；删除源文件要认 concat URI
ios/LiteTrans/Engine/FFmpegRunner.swift        # 多段 normalize 再 concat
ios/LiteTrans/Engine/QueuePump.swift           # 合并进照片走 saveVideoToPhotos
ios/LiteTrans/UI/AppModel.swift                # canStart(preset)；moveSources
ios/LiteTrans/UI/ConvertHomeView.swift         # onMove、至少两段 footer
ios/LiteTrans/Resources/Localizable.xcstrings
ios/LiteTransTests/Domain/ConvertNavigationTests.swift
ios/LiteTransTests/Domain/ConcatTests.swift    # 新建
ios/LiteTransTests/Domain/QueueTests.swift
ios/LiteTransTests/Domain/ModelsTests.swift
ios/LiteTransTests/LocalizationTests.swift
README.md                                      # 真机冒烟第 12 条
```

---

### Task 1: 主预设卡与开始条件

**Files:**
- Modify: `ios/LiteTrans/Domain/ConvertNavigation.swift`
- Test: `ios/LiteTransTests/Domain/ConvertNavigationTests.swift`

**Interfaces:**
- Produces:
  - `public let videoConcatPresetID = "video-concat"`
  - `public let videoConcatMaxSources = 20`
  - `public func isVideoConcatPreset(_ preset: String) -> Bool`
  - `primaryPresetIDs == ["mp4-h264", "mp4-copy", "mp4-h265", "mov-h264", "video-concat"]`
  - `engineKind("video-concat") == .ffmpeg`（四张 AVFoundation 卡不变）
  - `allowsTrim("video-concat") == false`
  - `shouldShowResolution("video-concat") == false`
  - `shouldShowQualityRow("video-concat") == true`
  - `convertSettingsFor("video-concat") == [.format, .quality, .output]`
  - `outputChoices(mode: .video, preset: "video-concat") == [.photos, .downloads, .custom]`
  - `canStart(importable:probing:transcoding:output:)` 保持原签名，行为等于 `preset: defaultPreset`
  - `canStart(..., preset:sourceCount:)`：合并要求 `2...20` 且 `importable == sourceCount`

- [ ] **Step 1: Write the failing tests**

在 `ConvertNavigationTests` 追加，并改 `primaryPresetsAreFour`：

```swift
@Test func primaryPresetsIncludeConcat() {
    #expect(primaryPresetIDs == ["mp4-h264", "mp4-copy", "mp4-h265", "mov-h264", "video-concat"])
    #expect(collapsedPrimaryPresets().map(\.id) == primaryPresetIDs)
    #expect(collapsedPrimaryPresets().first { $0.id == "video-concat" }?.titleKey == "preset_video_concat_title")
    #expect(collapsedPrimaryPresets().first { $0.id == "video-concat" }?.hintKey == "preset_video_concat_desc")
}

@Test func concatIsFfmpegWithoutTrimOrResolution() {
    #expect(engineKind("video-concat") == .ffmpeg)
    #expect(engineKind("mp4-h264") == .avFoundation)
    #expect(!allowsTrim(preset: "video-concat"))
    #expect(!shouldShowResolution("video-concat"))
    #expect(shouldShowQualityRow("video-concat"))
    #expect(convertSettingsFor(preset: "video-concat") == [.format, .quality, .output])
    #expect(outputChoices(mode: .video, preset: "video-concat") == [.photos, .downloads, .custom])
    #expect(historySegmentAfterEnqueue(mode: .video, preset: "video-concat") == .video)
}

@Test func concatStartNeedsTwoReadyClips() {
    let ready = OutputTarget(kind: .downloads)
    #expect(!canStart(importable: 1, probing: false, transcoding: false, output: ready, preset: "video-concat", sourceCount: 1))
    #expect(canStart(importable: 2, probing: false, transcoding: false, output: ready, preset: "video-concat", sourceCount: 2))
    #expect(!canStart(importable: 2, probing: false, transcoding: false, output: ready, preset: "video-concat", sourceCount: 3))
    #expect(!canStart(importable: 21, probing: false, transcoding: false, output: ready, preset: "video-concat", sourceCount: 21))
    #expect(canStart(importable: 1, probing: false, transcoding: false, output: ready))
}
```

把原来的 `@Test func primaryPresetsAreFour` 删掉（被 `primaryPresetsIncludeConcat` 取代）。

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd ios && swift test --filter ConvertNavigationTests`

Expected: FAIL，`video-concat` 不在 `primaryPresetIDs`，`canStart` 无 `preset` 参数。

- [ ] **Step 3: Write minimal implementation**

在 `ConvertNavigation.swift`：

```swift
public let videoConcatPresetID = "video-concat"
public let videoConcatMaxSources = 20
public let primaryPresetIDs = ["mp4-h264", "mp4-copy", "mp4-h265", "mov-h264", videoConcatPresetID]
private let avFoundationPresetIDs = ["mp4-h264", "mp4-copy", "mp4-h265", "mov-h264"]

public func isVideoConcatPreset(_ preset: String) -> Bool { preset == videoConcatPresetID }

public func collapsedPrimaryPresets() -> [PresetCard] {
    [
        .init(id: "mp4-h264", title: "MP4 · H.264", hintKey: "preset_mp4_h264_desc"),
        .init(id: "mp4-copy", title: "MP4 · Remux", hintKey: "preset_mp4_copy_desc"),
        .init(id: "mp4-h265", title: "MP4 · H.265", hintKey: "preset_mp4_h265_desc"),
        .init(id: "mov-h264", title: "MOV · H.264", hintKey: "preset_mov_h264_desc"),
        .init(id: videoConcatPresetID, title: "Merge", hintKey: "preset_video_concat_desc", titleKey: "preset_video_concat_title"),
    ]
}

public func allowsTrim(preset: String) -> Bool {
    !isCopyPreset(preset) && !isVideoConcatPreset(preset)
}

public func shouldShowResolution(_ preset: String) -> Bool {
    !preset.hasPrefix("audio-") && preset != "mp4-copy" && !isDocumentPreset(preset) && !isVideoConcatPreset(preset)
}

public func engineKind(_ preset: String) -> EngineKind {
    if avFoundationPresetIDs.contains(preset) { return .avFoundation }
    if isDocumentPreset(preset) { return .document }
    return .ffmpeg
}

public func canStart(importable: Int, probing: Bool, transcoding: Bool, output: OutputTarget) -> Bool {
    canStart(importable: importable, probing: probing, transcoding: transcoding, output: output, preset: defaultPreset, sourceCount: importable)
}

public func canStart(
    importable: Int,
    probing: Bool,
    transcoding: Bool,
    output: OutputTarget,
    preset: String,
    sourceCount: Int
) -> Bool {
    let minimum = isVideoConcatPreset(preset) ? 2 : 1
    let maximum = isVideoConcatPreset(preset) ? videoConcatMaxSources : Int.max
    let noRejects = !isVideoConcatPreset(preset) || importable == sourceCount
    return importable >= minimum
        && importable <= maximum
        && noRejects
        && !probing
        && !transcoding
        && outputReadyToStart(output)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd ios && swift test --filter ConvertNavigationTests`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add ios/LiteTrans/Domain/ConvertNavigation.swift ios/LiteTransTests/Domain/ConvertNavigationTests.swift
git commit -m "$(cat <<'EOF'
Add a fifth video preset card for concat.

Keep H.264 remux H.265 and MOV on AVFoundation; concat is FFmpeg-only and needs two ready clips.
EOF
)"
```

---

### Task 2: Concat argv 与命名

**Files:**
- Create: `ios/LiteTrans/Domain/Concat.swift`
- Modify: `ios/LiteTrans/Domain/Presets.swift`（`video-concat` 与 `mp4-h264` 相同默认；错误枚举）
- Modify: `ios/LiteTrans/Domain/FfmpegArgs.swift`（把画质 argv 从 `private` 改成模块内可见，供 Concat 复用）
- Test: `ios/LiteTransTests/Domain/ConcatTests.swift`

**Interfaces:**
- Consumes: `evenDimension` 目标来自第一条 `MediaInfo`；`softwareVideoQualityArgs` / `hardwareVideoQualityArgs`（去掉 `private`）
- Produces:
  - `concatOutputStem(_ displayName: String) -> String` → `"clip-merged"`
  - `evenDimension(_ value: Int) -> Int` → `max(2, value - value % 2)`
  - `ConcatTarget(width: Int, height: Int, frameRate: Double)`
  - `concatTarget(from: MediaInfo) throws -> ConcatTarget`
  - `concatScalePadFilter(target: ConcatTarget) -> String`
  - `buildConcatNormalizeArgs(input:outputPartial:media:target:quality:preferHardware:) -> [String]`
  - `buildConcatJoinArgs(listPath:outputPartial:) -> [String]`
  - `concatListFileContents(paths: [String]) -> String`
  - `LiteTransError.concatNeedsTwo` / `.concatTooMany` / `.concatMissingVideo`

- [ ] **Step 1: Write the failing tests**

```swift
import Testing
@testable import LiteTransDomain

struct ConcatTests {
    private func first() -> MediaInfo {
        MediaInfo(
            sourceUri: "file:///a.mp4",
            displayName: "holiday.MOV",
            durationSecs: 3,
            videoCodec: "h264",
            width: 1920,
            height: 1080,
            frameRate: 30,
            audioCodec: "aac",
            importable: true
        )
    }

    private func silentPortrait() -> MediaInfo {
        MediaInfo(
            sourceUri: "file:///b.mp4",
            displayName: "b.mp4",
            durationSecs: 2,
            videoCodec: "hevc",
            width: 1080,
            height: 1920,
            importable: true
        )
    }

    @Test func mergedStemUsesFirstName() {
        #expect(concatOutputStem("holiday.MOV") == "holiday-merged")
    }

    @Test func targetEvenizesAndDefaultsFps() throws {
        var odd = first()
        odd.width = 1281
        odd.height = 721
        odd.frameRate = nil
        let target = try concatTarget(from: odd)
        #expect(target.width == 1280)
        #expect(target.height == 720)
        #expect(target.frameRate == 30)
    }

    @Test func normalizeScalesToFirstAndPads() throws {
        let target = try concatTarget(from: first())
        let args = buildConcatNormalizeArgs(
            input: "/in/b.mp4",
            outputPartial: "/tmp/clip-001.partial.mp4",
            media: silentPortrait(),
            target: target,
            quality: "standard",
            preferHardware: false
        )
        #expect(args.contains("file:/in/b.mp4"))
        #expect(args.contains("-vf"))
        let filter = args[args.firstIndex(of: "-vf")! + 1]
        #expect(filter.contains("1920:1080"))
        #expect(filter.contains("force_original_aspect_ratio=decrease"))
        #expect(filter.contains("pad=1920:1080"))
        #expect(args.contains("libx264"))
        #expect(args.contains("yuv420p"))
        #expect(args.contains("anullsrc=channel_layout=stereo:sample_rate=48000"))
        #expect(args.contains("aac"))
        #expect(args.contains("48000"))
    }

    @Test func normalizeKeepsExistingAudio() throws {
        let args = buildConcatNormalizeArgs(
            input: "/in/a.mp4",
            outputPartial: "/tmp/a.partial.mp4",
            media: first(),
            target: try concatTarget(from: first()),
            quality: "original",
            preferHardware: false
        )
        #expect(!args.contains { $0.contains("anullsrc") })
        #expect(args.contains("aac"))
    }

    @Test func joinUsesConcatDemuxerCopy() {
        let args = buildConcatJoinArgs(listPath: "/tmp/list.txt", outputPartial: "/out/a-merged.partial.mp4")
        #expect(args.contains("-f"))
        #expect(args.contains("concat"))
        #expect(args.contains("-c"))
        #expect(args.contains("copy"))
        #expect(args.contains("file:/tmp/list.txt"))
        #expect(args.contains("file:/out/a-merged.partial.mp4"))
    }

    @Test func listFileEscapesQuotes() {
        let text = concatListFileContents(paths: ["/tmp/a.mp4", "/tmp/o'reilly.mp4"])
        #expect(text.contains("file '/tmp/a.mp4'"))
        #expect(text.contains("file '/tmp/o'\\''reilly.mp4'"))
    }

    @Test func resolveConcatPresetIsH264Mp4() throws {
        let resolved = try resolveConfig(OutputConfig(preset: "video-concat", quality: "standard"))
        #expect(resolved.container == "mp4")
        #expect(resolved.videoEncoder == "h264")
        #expect(resolved.audioEncoder == "aac")
        #expect(resolved.extension == "mp4")
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd ios && swift test --filter ConcatTests`

Expected: FAIL，`Concat.swift` 不存在。

- [ ] **Step 3: Write minimal implementation**

`Presets.swift` 的 `switch preset` 增加：

```swift
case "video-concat": defaults = .init(container: "mp4", videoEncoder: "h264", audioEncoder: "aac", keepAudio: true)
```

`LiteTransError` 增加：

```swift
case concatNeedsTwo
case concatTooMany
case concatMissingVideo
```

`errorDescription`：

```swift
case .concatNeedsTwo: return "Add at least two videos"
case .concatTooMany: return "You can merge up to 20 videos"
case .concatMissingVideo: return "Each clip needs a video track"
```

`FfmpegArgs.swift`：去掉 `hardwareVideoQualityArgs`、`softwareVideoQualityArgs` 的 `private`。

新建 `Concat.swift`：

```swift
import Foundation

public struct ConcatTarget: Equatable, Sendable {
    public var width: Int
    public var height: Int
    public var frameRate: Double
    public init(width: Int, height: Int, frameRate: Double) {
        self.width = width
        self.height = height
        self.frameRate = frameRate
    }
}

public func concatOutputStem(_ displayName: String) -> String {
    "\(sourceStem(displayName))-merged"
}

public func evenDimension(_ value: Int) -> Int {
    max(2, value - (value % 2))
}

public func concatTarget(from first: MediaInfo) throws -> ConcatTarget {
    guard let width = first.width, let height = first.height, width > 0, height > 0 else {
        throw LiteTransError.concatMissingVideo
    }
    return ConcatTarget(
        width: evenDimension(width),
        height: evenDimension(height),
        frameRate: first.frameRate ?? 30
    )
}

public func concatScalePadFilter(target: ConcatTarget) -> String {
    let w = target.width
    let h = target.height
    let fps = String(format: "%.3f", locale: Locale(identifier: "en_US_POSIX"), target.frameRate)
    return "scale=\(w):\(h):force_original_aspect_ratio=decrease,pad=\(w):\(h):(ow-iw)/2:(oh-ih)/2,setsar=1,fps=\(fps)"
}

public func buildConcatNormalizeArgs(
    input: String,
    outputPartial: String,
    media: MediaInfo,
    target: ConcatTarget,
    quality: String,
    preferHardware: Bool = true
) -> [String] {
    var args = [
        "-hide_banner", "-nostats", "-progress", "pipe:1", "-y",
        "-i", ffmpegFileArg(input),
    ]
    let duration = max(media.durationSecs ?? 0, 0.05)
    let missingAudio = media.audioCodec == nil
    if missingAudio {
        args += [
            "-f", "lavfi",
            "-t", String(format: "%.3f", locale: Locale(identifier: "en_US_POSIX"), duration),
            "-i", "anullsrc=channel_layout=stereo:sample_rate=48000",
        ]
    }
    args += ["-c:v", ffmpegVideoCodec("h264", preferHardware: preferHardware)]
    if preferHardware {
        args += hardwareVideoQualityArgs(quality)
    } else {
        args += softwareVideoQualityArgs(encoder: "h264", quality: quality)
    }
    args += [
        "-pix_fmt", "yuv420p",
        "-vf", concatScalePadFilter(target: target),
        "-c:a", "aac", "-ar", "48000", "-ac", "2", "-b:a", "192k",
        "-movflags", "+faststart",
        "-f", "mp4",
        ffmpegFileArg(outputPartial),
    ]
    if missingAudio {
        args += ["-map", "0:v:0", "-map", "1:a:0", "-shortest"]
    }
    return args
}

public func buildConcatJoinArgs(listPath: String, outputPartial: String) -> [String] {
    [
        "-hide_banner", "-nostats", "-progress", "pipe:1", "-y",
        "-f", "concat", "-safe", "0",
        "-i", ffmpegFileArg(listPath),
        "-c", "copy",
        "-movflags", "+faststart",
        "-f", "mp4",
        ffmpegFileArg(outputPartial),
    ]
}

public func concatListFileContents(paths: [String]) -> String {
    paths.map { path in
        let escaped = path.replacingOccurrences(of: "'", with: "'\\''")
        return "file '\(escaped)'"
    }.joined(separator: "\n")
}
```

注意：`-map` 必须出现在输出文件 **之前**。实现时把 map/shortest 插在 `-f mp4` 之前，不要照抄上面若测试失败再把 map 挪到 `ffmpegFileArg(outputPartial)` 前。正确顺序：

```swift
args += ["-c:v", ...]
args += quality
args += ["-pix_fmt", "yuv420p", "-vf", concatScalePadFilter(target: target)]
if missingAudio {
    args += ["-map", "0:v:0", "-map", "1:a:0", "-shortest"]
}
args += ["-c:a", "aac", "-ar", "48000", "-ac", "2", "-b:a", "192k", "-movflags", "+faststart", "-f", "mp4", ffmpegFileArg(outputPartial)]
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd ios && swift test --filter ConcatTests`

Expected: PASS。若 `-map` 顺序导致断言失败，按上一段调整后再跑。

- [ ] **Step 5: Commit**

```bash
git add ios/LiteTrans/Domain/Concat.swift ios/LiteTrans/Domain/Presets.swift ios/LiteTrans/Domain/FfmpegArgs.swift ios/LiteTransTests/Domain/ConcatTests.swift
git commit -m "$(cat <<'EOF'
Build FFmpeg argv that re-encode clips to the first frame size.

Pad letterbox and invent silent AAC so concat demuxer can copy the intermediates.
EOF
)"
```

---

### Task 3: 入队一条合并 Job

**Files:**
- Modify: `ios/LiteTrans/Domain/Models.swift`
- Modify: `ios/LiteTrans/Domain/Queue.swift`
- Test: `ios/LiteTransTests/Domain/QueueTests.swift`
- Test: `ios/LiteTransTests/Domain/ModelsTests.swift`

**Interfaces:**
- Consumes: `concatOutputStem`, `isVideoConcatPreset`, `concatTarget`, `videoConcatMaxSources`
- Produces:
  - `OutputConfig.concatSourceUris: [String]` 默认 `[]`；旧 JSON 缺省解码为 `[]`
  - `Job.concatMedias: [MediaInfo]` 默认 `[]`；旧 JSON 缺省 `[]`
  - `enqueueJobs` 在 `video-concat` 时：不可导入 / <2 / >20 / 缺画面 → **零 Job**；成功则 **一个** Job，`sourceUri` 为第一段，`outputPath` 以 `-merged.mp4` 结尾
  - `jobSourceURIs(_ job: Job) -> [String]`
  - `shouldDeleteImportedSource` 认 concat URI，不只 `job.sourceUri`

- [ ] **Step 1: Write the failing tests**

`ModelsTests`：

```swift
@Test func concatFieldsDecodeMissingAsEmpty() throws {
    let json = Data(#"""
    {"id":"1","sourceUri":"a","displayName":"a","status":"queued","progress":0,"config":{"preset":"mp4-h264"},"media":{"sourceUri":"a","displayName":"a","importable":false}}
    """#.utf8)
    let job = try JSONDecoder().decode(Job.self, from: json)
    #expect(job.config.concatSourceUris.isEmpty)
    #expect(job.concatMedias.isEmpty)
}
```

`QueueTests`：

```swift
private func clip(_ uri: String, name: String, importable: Bool = true) -> MediaInfo {
    MediaInfo(
        sourceUri: uri,
        displayName: name,
        durationSecs: 2,
        videoCodec: "h264",
        width: 1280,
        height: 720,
        audioCodec: "aac",
        importable: importable
    )
}

@Test func concatEnqueuesOneMergedJob() throws {
    let a = clip("file:///a.mp4", name: "a.mp4")
    let b = clip("file:///b.mp4", name: "b.mp4")
    let report = try enqueueJobs(
        sources: [a, b],
        config: OutputConfig(preset: "video-concat"),
        outputDir: "/tmp",
        nextId: { "job-1" },
        exists: { _ in false }
    )
    #expect(report.jobs.count == 1)
    #expect(report.skipped.isEmpty)
    #expect(report.jobs[0].sourceUri == "file:///a.mp4")
    #expect(report.jobs[0].config.concatSourceUris == ["file:///a.mp4", "file:///b.mp4"])
    #expect(report.jobs[0].concatMedias.map(\.sourceUri) == ["file:///a.mp4", "file:///b.mp4"])
    #expect(report.jobs[0].outputPath == "/tmp/a-merged.mp4")
}

@Test func concatRejectsMixedUnimportable() throws {
    let report = try enqueueJobs(
        sources: [clip("file:///a.mp4", name: "a.mp4"), clip("file:///bad.mp4", name: "bad.mp4", importable: false)],
        config: OutputConfig(preset: "video-concat"),
        outputDir: "/tmp",
        nextId: { "1" },
        exists: { _ in false }
    )
    #expect(report.jobs.isEmpty)
    #expect(report.skipped.contains { $0.sourceUri == "file:///bad.mp4" })
}

@Test func concatRejectsOneClip() throws {
    let report = try enqueueJobs(
        sources: [clip("file:///a.mp4", name: "a.mp4")],
        config: OutputConfig(preset: "video-concat"),
        outputDir: "/tmp",
        nextId: { "1" },
        exists: { _ in false }
    )
    #expect(report.jobs.isEmpty)
    #expect(report.skipped[0].reason == LiteTransError.concatNeedsTwo.localizedDescription)
}

@Test func concatKeepsImportedSourcesUntilJobGone() {
    let a = clip("file:///imports/a.mp4", name: "a.mp4")
    let b = clip("file:///imports/b.mp4", name: "b.mp4")
    var config = OutputConfig(preset: "video-concat")
    config.concatSourceUris = [a.sourceUri, b.sourceUri]
    let job = Job(
        id: "1",
        sourceUri: a.sourceUri,
        displayName: a.displayName,
        outputPath: "/tmp/a-merged.mp4",
        status: .queued,
        progress: 0,
        error: nil,
        config: config,
        media: a,
        concatMedias: [a, b]
    )
    #expect(!shouldDeleteImportedSource(sourceUri: b.sourceUri, remainingJobs: [job], sessionSources: []))
    #expect(shouldDeleteImportedSource(sourceUri: b.sourceUri, remainingJobs: [], sessionSources: []))
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd ios && swift test --filter QueueTests --filter ModelsTests`

Expected: FAIL，`concatSourceUris` 不存在。

- [ ] **Step 3: Write minimal implementation**

`OutputConfig` 增加 `concatSourceUris: [String] = []`，自定义 `Codable`：`decodeIfPresent([String].self, forKey: .concatSourceUris) ?? []`。init 增加参数，默认 `[]`。

`Job`：`concatMedias: [MediaInfo] = []`；`CodingKeys` 加 `concatMedias`；decode `decodeIfPresent ?? []`；init 增加参数。

`Queue.swift`：

```swift
public func jobSourceURIs(_ job: Job) -> [String] {
    if !job.config.concatSourceUris.isEmpty { return job.config.concatSourceUris }
    return [job.sourceUri]
}

public func shouldDeleteImportedSource(
    sourceUri: String,
    remainingJobs: [Job],
    sessionSources: [MediaInfo]
) -> Bool {
    remainingJobs.allSatisfy { !jobSourceURIs($0).contains(sourceUri) }
        && sessionSources.allSatisfy { $0.sourceUri != sourceUri }
}
```

`enqueueJobs` 在 `resolveConfig` 之后：

```swift
if isVideoConcatPreset(config.preset) {
    return try enqueueConcatJobs(
        sources: sources,
        config: config,
        resolved: resolved,
        outputDir: outputDir,
        nextId: nextId,
        exists: exists,
        existingJobs: existingJobs,
        outputKind: outputKind,
        nowMs: nowMs
    )
}
```

`enqueueConcatJobs`：

1. `splitImportable`；若 `skipped` 非空 → `EnqueueReport(jobs: [], skipped: skipped)`
2. `accepted.count < 2` → 全部标 `concatNeedsTwo`，零 Job
3. `accepted.count > videoConcatMaxSources` → `concatTooMany`
4. 对每段 `concatTarget`/`validate`；缺宽高或无视频 → `concatMissingVideo`，零 Job
5. `stem = concatOutputStem(accepted[0].displayName)`，`allocateOutputPath`
6. `var next = config; next.concatSourceUris = accepted.map(\.sourceUri)`
7. 一个 `Job`：`sourceUri/displayName/media` 用第一段，`concatMedias: accepted`

`markInterrupted` 重建 `Job` 时带上 `concatMedias: job.concatMedias`。

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd ios && swift test --filter QueueTests --filter ModelsTests --filter ConcatTests --filter ConvertNavigationTests`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add ios/LiteTrans/Domain/Models.swift ios/LiteTrans/Domain/Queue.swift ios/LiteTransTests/Domain/QueueTests.swift ios/LiteTransTests/Domain/ModelsTests.swift
git commit -m "$(cat <<'EOF'
Enqueue concat as one job with ordered source URIs.

Keep imported clips until every concat URI is gone so later segments are not deleted early.
EOF
)"
```

---

### Task 4: FFmpegRunner 多段再拼接

**Files:**
- Modify: `ios/LiteTrans/Engine/FFmpegRunner.swift`
- Modify: `ios/LiteTrans/Engine/QueuePump.swift`

**Interfaces:**
- Consumes: `job.config.concatSourceUris`、`job.concatMedias`、Task 2 的 argv 函数
- Produces: `FFmpegRunner.run` 在 concat 时：工作目录 `concat-{job.id}/`；每段 `clip-XXX.mp4`；`list.txt`；成功把最终 `.partial` 改名为 `outputPath`；`defer` 删除工作目录；失败不改源文件
- `QueuePump`：`shouldSaveToPhotos && preset == video-concat` 时 `saveVideoToPhotos`

- [ ] **Step 1: No Domain test for the runner**

引擎测不到真 FFmpeg。用现有 Domain 测试当契约，本任务只接线。

- [ ] **Step 2: Implement runner concat path**

`FFmpegRunner.run`：

```swift
func run(job: Job, onProgress: @escaping @Sendable (Double) -> Void) async throws {
    if isVideoConcatPreset(job.config.preset) {
        try await runConcat(job: job, onProgress: onProgress)
        return
    }
    // 现有单文件路径不变
}
```

`runConcat`：

1. `guard let outputPath = job.outputPath`
2. `let medias = job.concatMedias`
3. `guard medias.count >= 2, medias.count == job.config.concatSourceUris.count`
4. `let target = try concatTarget(from: medias[0])`
5. `let work = URL(fileURLWithPath: outputPath).deletingLastPathComponent().appendingPathComponent("concat-\(job.id)")`
6. `try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)`
7. `defer { try? FileManager.default.removeItem(at: work) }`
8. 循环 `medias.enumerated()`：
   - `input = URL(string: media.sourceUri)?.path ?? media.sourceUri`
   - `partial = work.appendingPathComponent(String(format: "clip-%03d.partial.mp4", index)).path`
   - `finalClip = work.appendingPathComponent(String(format: "clip-%03d.mp4", index)).path`
   - `args = buildConcatNormalizeArgs(..., quality: job.config.quality ?? "standard")`
   - `try await execute(arguments: args, duration: media.durationSecs ?? 0)`，进度映射到 `index / count * 90`
   - `moveItem(partial → finalClip)`
9. 写 `list.txt`：`concatListFileContents(paths: clipPaths)`
10. `buildConcatJoinArgs` → `outputPartial = partialOutputPath(outputPath)`
11. `execute` 时长为各段 `durationSecs` 之和；进度 90...100
12. 成功则替换 `outputPath`（与现有 `moveItem` 相同）；失败删 `.partial`

进度可用：

```swift
let base = Double(index) / Double(medias.count) * 90
onProgress(min(90, base + clipLocal / 100.0 * (90.0 / Double(medias.count))))
```

- [ ] **Step 3: Photos**

`QueuePump` ffmpeg 分支改成：

```swift
if shouldSaveToPhotos(current.outputKind) {
    if current.config.preset == "gif" {
        try await saveImageToPhotos(outputURL)
    } else if isVideoConcatPreset(current.config.preset) {
        try await saveVideoToPhotos(outputURL)
    }
}
```

- [ ] **Step 4: Compile the app target**

Run:

```bash
cd ios && xcodegen generate && xcodebuild -scheme LiteTrans -destination 'generic/platform=iOS Simulator' build
```

Expected: BUILD SUCCEEDED

- [ ] **Step 5: Commit**

```bash
git add ios/LiteTrans/Engine/FFmpegRunner.swift ios/LiteTrans/Engine/QueuePump.swift
git commit -m "$(cat <<'EOF'
Run concat as per-clip re-encode then demuxer copy.

Clean the temp directory after success or failure and save merged MP4 to Photos when asked.
EOF
)"
```

---

### Task 5: 首页拖动与五语文案

**Files:**
- Modify: `ios/LiteTrans/UI/AppModel.swift`
- Modify: `ios/LiteTrans/UI/ConvertHomeView.swift`
- Modify: `ios/LiteTrans/Resources/Localizable.xcstrings`
- Test: `ios/LiteTransTests/LocalizationTests.swift`

**Interfaces:**
- Consumes: `canStart(..., preset:sourceCount:)`、`isVideoConcatPreset`、`AppModel.moveSources`
- Produces: 合并模式下文件列表 `.onMove`；不足两段 footer；开始按钮用新 `canStart`

- [ ] **Step 1: Write the failing localization test**

```swift
func testConcatPresetSwitchesWithLocale() {
    XCTAssertEqual(localizedText("preset_video_concat_title", locale: Locale(identifier: "zh-Hans")), "合并")
    XCTAssertEqual(localizedText("preset_video_concat_title", locale: Locale(identifier: "en")), "Merge")
    XCTAssertEqual(localizedText("preset_video_concat_title", locale: Locale(identifier: "zh-Hant")), "合併")
    XCTAssertEqual(localizedText("preset_video_concat_title", locale: Locale(identifier: "ja")), "結合")
    XCTAssertEqual(localizedText("preset_video_concat_title", locale: Locale(identifier: "ko")), "병합")
    XCTAssertEqual(localizedText("preset_video_concat_desc", locale: Locale(identifier: "zh-Hans")), "按顺序拼成一个视频")
    XCTAssertEqual(localizedText("concat_need_two", locale: Locale(identifier: "zh-Hans")), "至少添加两段视频")
    XCTAssertEqual(localizedText("concat_need_two", locale: Locale(identifier: "en")), "Add at least two videos")
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd ios && xcodebuild -scheme LiteTrans -destination 'platform=iOS Simulator,name=iPhone 16' test -only-testing:LiteTransTests/LocalizationTests/testConcatPresetSwitchesWithLocale`

若模拟器名不同，用 `xcrun simctl list devices available` 里已有的 iPhone。

Expected: FAIL，缺 key。

- [ ] **Step 3: Strings + UI**

`Localizable.xcstrings` 增加三个 key，每种语言 `state: translated`：

| key | en | zh-Hans | zh-Hant | ja | ko |
| --- | --- | --- | --- | --- | --- |
| `preset_video_concat_title` | Merge | 合并 | 合併 | 結合 | 병합 |
| `preset_video_concat_desc` | Combine clips in order into one video | 按顺序拼成一个视频 | 依序拼成一個影片 | 順番に1本の動画へつなぎます | 순서대로 하나의 동영상으로 이어 붙입니다 |
| `concat_need_two` | Add at least two videos | 至少添加两段视频 | 至少加入兩段影片 | 動画を2本以上追加してください | 동영상을 두 개 이상 추가하세요 |

`AppModel`：

```swift
var startEnabled: Bool {
    canStart(
        importable: importableCount,
        probing: probing,
        transcoding: transcoding,
        output: output,
        preset: preset,
        sourceCount: sources.count
    )
}

func moveSources(from offsets: IndexSet, to destination: Int) {
    sources.move(fromOffsets: offsets, toOffset: destination)
}
```

`ConvertHomeView` 文件 `Section` 的 `ForEach` 后加：

```swift
.onMove { offsets, destination in
    guard isVideoConcatPreset(model.preset) else { return }
    model.moveSources(from: offsets, to: destination)
}
```

该 `Section`：

```swift
.environment(
    \.editMode,
    isVideoConcatPreset(model.preset) && model.sources.count > 1
        ? .constant(.active)
        : .constant(.inactive)
)
```

只加在文件 Section 上，不要加到整个 List（否则设置行也会出现排序把手）。

footer：

```swift
} footer: {
    if model.sources.isEmpty {
        Text(footerText)
    } else if isVideoConcatPreset(model.preset) && model.importableCount < 2 {
        Text(text("concat_need_two"))
    }
}
```

- [ ] **Step 4: Run localization + domain tests**

Run:

```bash
cd ios && swift test
```

Expected: Domain PASS。再用 Step 2 的 `xcodebuild test -only-testing:...LocalizationTests` 确认文案。

- [ ] **Step 5: Commit**

```bash
git add ios/LiteTrans/UI/AppModel.swift ios/LiteTrans/UI/ConvertHomeView.swift ios/LiteTrans/Resources/Localizable.xcstrings ios/LiteTransTests/LocalizationTests.swift
git commit -m "$(cat <<'EOF'
Let concat clips be reordered and name the preset in five languages.

Show a two-clip hint instead of starting a one-file merge.
EOF
)"
```

---

### Task 6: 冒烟说明与全量 Domain 测试

**Files:**
- Modify: `README.md`（iOS 真机冒烟清单）

- [ ] **Step 1: Add smoke item 12**

在「iOS 真机冒烟清单」追加：

```
12. 视频选「合并」，加两段相册视频，拖动对调后开始；历史一条记录，成片时长相加且顺序为对调后；竖屏拼横屏有黑边
```

- [ ] **Step 2: Run all Domain tests**

Run: `cd ios && swift test`

Expected: PASS，0 failed。

- [ ] **Step 3: Commit spec + plan + README if not already committed**

```bash
git add README.md docs/superpowers/specs/2026-09-18-ios-video-merge-design.md docs/superpowers/plans/2026-09-18-ios-video-merge.md
git commit -m "$(cat <<'EOF'
Document iOS video merge smoke coverage.

Keep the feature on ios-video-merge so it can be dropped without touching master.
EOF
)"
```

不要 `git add` Android 脏文件。

---

## Spec coverage

| Spec | Task |
| --- | --- |
| 视频 Tab 第 5 张「合并」`video-concat` | 1、5 |
| ≥2 ≤20、canStart、少两段文案 | 1、5 |
| 无裁切、无分辨率行、有画质 | 1 |
| 拖动顺序 = `sources` | 5 |
| 一条 Job、`concatSourceUris`、`*-merged.mp4` | 3 |
| 整单不入队（不可导入 / 一段 / 超 20） | 3 |
| 跟第一条对齐、scale+pad、静音、先重编码再 concat | 2、4 |
| `engineKind` ffmpeg、画质同 H.264 | 1、2 |
| 失败清临时、源不动 | 4 |
| 历史 video、局域网单文件 | 3（现有打开/分享/LAN 不改） |
| 五语文案 | 5 |
| 真机冒烟 | 6 |
| 不要 Android / 可丢分支 | Global |

## Type consistency

- Preset id 一律 `video-concat` / `videoConcatPresetID`
- `concatSourceUris` 在 `OutputConfig`；`concatMedias` 在 `Job`
- `canStart` 四参数 overload 保留；UI 走六参数
- `engineKind` 用 `avFoundationPresetIDs`，合并不进 AVFoundation
