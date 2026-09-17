# 轻转码 iOS 第 1 刀 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在仓库新增独立 SwiftUI iPhone App，能本机把视频转成 H.264 / H.265 / MOV / 不重编码，并带转码首页、历史（仅视频）、我的（语言 / 协议 / 条款 / 关于）。

**Architecture:** `ios/` 下 Swift 包跑纯 Domain 测试；Xcode 工程编 App。转码首页是分组列表 + 设置 push。第 1 刀四张主预设用 AVFoundation（VideoToolbox / Passthrough），不在本计划捆绑 FFmpeg。音频、文档、Live Activity、局域网不在本计划。

**Tech Stack:** Swift 6、SwiftUI、iOS 18、arm64、Swift Testing（`swift test`）、XCTest/`xcodebuild`、XcodeGen、AVFoundation、Photos（Add Only）、UserDefaults。

## Global Constraints

- Bundle ID `com.videoconverter.ios`，主屏幕名 `LiteTrans`，关于页写「轻转码」
- 最低 iOS 18，只打 iPhone arm64；iPad 用同一套 Tab，不做分栏
- 不复用 Tauri / React / Android 代码，不上 App Store，不申请完整相册
- 预设 ID 与桌面 / Android 一致；输出命名 `{stem}.{ext}` / `{stem}-1.{ext}`；临时文件 `{stem}.partial.{ext}`
- H.264 / H.265 走 AVFoundation（系统 VideoToolbox）；`mp4-copy` 走 Passthrough
- 生产 UI 不用 hex；语义色 + Asset Catalog「轻转码橙」；Dynamic Type 文本样式
- 第 1 刀格式页只列出 `mp4-h264`、`mp4-copy`、`mp4-h265`、`mov-h264`，不展示「更多」
- 第 1 刀转码 Tab 不出现音频 / 文档分段；历史只有视频列表
- 中文默认文案；语言：跟随系统 / 简体 / 繁体 / English / 日本語 / 한국어，本刀 Localizable 先做 en + zh-Hans，其余回退英文
- 不上传；不用 FFmpeg 出预览片；不申请音频后台

本计划覆盖规格 [2026-09-18-ios-app-design.md](../specs/2026-09-18-ios-app-design.md) 的落地顺序第 1 项。第 2–4 项（音频/文档、Live Activity、局域网）另开计划。

## File map

```
ios/
  Package.swift
  project.yml
  LiteTrans/
    Domain/
      Models.swift
      Presets.swift
      Naming.swift
      Validate.swift
      Queue.swift
      Progress.swift
      ProbeParser.swift
      FfmpegCodec.swift
    App/
      LiteTransApp.swift
      RootView.swift
    Data/
      SessionStore.swift
      JobStore.swift
    Engine/
      ProbeService.swift
      VideoExporter.swift
      QueuePump.swift
    UI/
      ConvertNavigation.swift
      Theme.swift
      ConvertHomeView.swift
      ConvertSettingsViews.swift
      HistoryView.swift
      MineView.swift
      JobRow.swift
      AppModel.swift
    Resources/
      Localizable.xcstrings
      Assets.xcassets/AccentColor.colorset/Contents.json
      Info.plist
  LiteTransTests/
    Domain/
      PresetsTests.swift
      NamingTests.swift
      QueueTests.swift
      ProgressTests.swift
      ConvertNavigationTests.swift
  scripts/.gitkeep
.gitignore                         # 追加 ios 段
README.md                          # 追加 iOS 段
```

桌面 `src/`、`src-tauri/`、`android/` 不改。

---

### Task 1: Swift 包 + 领域模型 + 预设解析

**Files:**
- Create: `ios/Package.swift`
- Create: `ios/LiteTrans/Domain/Models.swift`
- Create: `ios/LiteTrans/Domain/Presets.swift`
- Test: `ios/LiteTransTests/Domain/PresetsTests.swift`

**Interfaces:**
- Consumes: 无
- Produces: `struct MediaInfo`, `struct OutputConfig`, `struct ResolvedConfig`, `enum JobStatus`, `struct Job`, `func resolveConfig(_ config: OutputConfig) throws -> ResolvedConfig`, `func listPresets() -> [PresetInfo]`, `let defaultPreset = "mp4-h264"`

- [ ] **Step 1: 写失败测试**

`ios/LiteTransTests/Domain/PresetsTests.swift`:

```swift
import Testing
@testable import LiteTransDomain

struct PresetsTests {
    @Test func defaultPresetIsMp4H264() throws {
        let resolved = try resolveConfig(OutputConfig())
        #expect(resolved.preset == "mp4-h264")
        #expect(resolved.container == "mp4")
        #expect(resolved.videoEncoder == "h264")
        #expect(resolved.audioEncoder == "aac")
    }

    @Test func listsRequiredPresets() {
        let ids = listPresets().map(\.id)
        for id in [
            "mp4-h264", "mp4-h265", "mp4-copy", "webm-vp9", "mkv-copy-friendly",
            "audio-mp3", "mov-h264", "avi-mpeg4", "gif", "audio-aac", "mkv-h265",
        ] {
            #expect(ids.contains(id))
        }
    }

    @Test func qualityDefaultsToStandard() throws {
        #expect(try resolveConfig(OutputConfig()).quality == "standard")
    }

    @Test func mp4CopyDoesNotReencode() throws {
        let resolved = try resolveConfig(OutputConfig(preset: "mp4-copy"))
        #expect(resolved.videoEncoder == "copy")
        #expect(resolved.audioEncoder == "copy")
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd ios && swift test --filter PresetsTests`
Expected: FAIL，`LiteTransDomain` 不存在或 `resolveConfig` 未定义

- [ ] **Step 3: 最小实现**

`ios/Package.swift`:

```swift
// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "LiteTrans",
    platforms: [.iOS(.v18), .macOS(.v14)],
    products: [
        .library(name: "LiteTransDomain", targets: ["LiteTransDomain"]),
    ],
    targets: [
        .target(
            name: "LiteTransDomain",
            path: "LiteTrans/Domain"
        ),
        .testTarget(
            name: "LiteTransDomainTests",
            dependencies: ["LiteTransDomain"],
            path: "LiteTransTests/Domain"
        ),
    ]
)
```

`ios/LiteTrans/Domain/Models.swift`:

```swift
public struct MediaInfo: Equatable, Sendable, Codable {
    public var sourceUri: String
    public var displayName: String
    public var durationSecs: Double?
    public var container: String?
    public var videoCodec: String?
    public var width: Int?
    public var height: Int?
    public var frameRate: Double?
    public var audioCodec: String?
    public var channels: Int?
    public var importable: Bool
    public var error: String?
    public var trimStartSecs: Double?
    public var trimEndSecs: Double?

    public init(
        sourceUri: String,
        displayName: String,
        durationSecs: Double? = nil,
        container: String? = nil,
        videoCodec: String? = nil,
        width: Int? = nil,
        height: Int? = nil,
        frameRate: Double? = nil,
        audioCodec: String? = nil,
        channels: Int? = nil,
        importable: Bool = false,
        error: String? = nil,
        trimStartSecs: Double? = nil,
        trimEndSecs: Double? = nil
    ) {
        self.sourceUri = sourceUri
        self.displayName = displayName
        self.durationSecs = durationSecs
        self.container = container
        self.videoCodec = videoCodec
        self.width = width
        self.height = height
        self.frameRate = frameRate
        self.audioCodec = audioCodec
        self.channels = channels
        self.importable = importable
        self.error = error
        self.trimStartSecs = trimStartSecs
        self.trimEndSecs = trimEndSecs
    }
}

public struct PresetInfo: Equatable, Sendable {
    public let id: String
    public let label: String
    public let description: String
    public init(id: String, label: String, description: String) {
        self.id = id
        self.label = label
        self.description = description
    }
}

public struct OutputConfig: Equatable, Sendable, Codable {
    public var preset: String
    public var container: String?
    public var videoEncoder: String?
    public var maxWidth: Int?
    public var maxHeight: Int?
    public var videoBitrateKbps: Int?
    public var frameRate: Double?
    public var audioEncoder: String?
    public var audioBitrateKbps: Int?
    public var keepAudio: Bool?
    public var quality: String?
    public var trimStartSecs: Double?
    public var trimEndSecs: Double?

    public init(
        preset: String = defaultPreset,
        container: String? = nil,
        videoEncoder: String? = nil,
        maxWidth: Int? = nil,
        maxHeight: Int? = nil,
        videoBitrateKbps: Int? = nil,
        frameRate: Double? = nil,
        audioEncoder: String? = nil,
        audioBitrateKbps: Int? = nil,
        keepAudio: Bool? = nil,
        quality: String? = nil,
        trimStartSecs: Double? = nil,
        trimEndSecs: Double? = nil
    ) {
        self.preset = preset
        self.container = container
        self.videoEncoder = videoEncoder
        self.maxWidth = maxWidth
        self.maxHeight = maxHeight
        self.videoBitrateKbps = videoBitrateKbps
        self.frameRate = frameRate
        self.audioEncoder = audioEncoder
        self.audioBitrateKbps = audioBitrateKbps
        self.keepAudio = keepAudio
        self.quality = quality
        self.trimStartSecs = trimStartSecs
        self.trimEndSecs = trimEndSecs
    }
}

public struct ResolvedConfig: Equatable, Sendable {
    public var preset: String
    public var container: String
    public var `extension`: String
    public var videoEncoder: String?
    public var audioEncoder: String?
    public var maxWidth: Int?
    public var maxHeight: Int?
    public var videoBitrateKbps: Int?
    public var frameRate: Double?
    public var audioBitrateKbps: Int?
    public var keepAudio: Bool
    public var quality: String
    public var trimStartSecs: Double?
    public var trimEndSecs: Double?

    public init(
        preset: String,
        container: String,
        extension: String,
        videoEncoder: String?,
        audioEncoder: String?,
        maxWidth: Int?,
        maxHeight: Int?,
        videoBitrateKbps: Int?,
        frameRate: Double?,
        audioBitrateKbps: Int?,
        keepAudio: Bool,
        quality: String,
        trimStartSecs: Double?,
        trimEndSecs: Double?
    ) {
        self.preset = preset
        self.container = container
        self.extension = extension
        self.videoEncoder = videoEncoder
        self.audioEncoder = audioEncoder
        self.maxWidth = maxWidth
        self.maxHeight = maxHeight
        self.videoBitrateKbps = videoBitrateKbps
        self.frameRate = frameRate
        self.audioBitrateKbps = audioBitrateKbps
        self.keepAudio = keepAudio
        self.quality = quality
        self.trimStartSecs = trimStartSecs
        self.trimEndSecs = trimEndSecs
    }
}

public enum JobStatus: String, Equatable, Sendable, Codable {
    case queued, running, completed, failed, cancelled
}

public struct Job: Equatable, Sendable, Identifiable, Codable {
    public var id: String
    public var sourceUri: String
    public var displayName: String
    public var outputPath: String?
    public var status: JobStatus
    public var progress: Double
    public var error: String?
    public var config: OutputConfig
    public var media: MediaInfo

    public init(
        id: String,
        sourceUri: String,
        displayName: String,
        outputPath: String?,
        status: JobStatus,
        progress: Double,
        error: String?,
        config: OutputConfig,
        media: MediaInfo
    ) {
        self.id = id
        self.sourceUri = sourceUri
        self.displayName = displayName
        self.outputPath = outputPath
        self.status = status
        self.progress = progress
        self.error = error
        self.config = config
        self.media = media
    }
}

public struct SkippedSource: Equatable, Sendable {
    public var sourceUri: String
    public var displayName: String
    public var reason: String
}

public struct EnqueueReport: Equatable, Sendable {
    public var jobs: [Job]
    public var skipped: [SkippedSource]
}
```

`ios/LiteTrans/Domain/Presets.swift`：

```swift
public let defaultPreset = "mp4-h264"

public enum LiteTransError: Error, Equatable {
    case unknownPreset(String)
    case unsupportedContainer(String)
    case unsupportedVideoEncoder(String)
    case unsupportedAudioEncoder(String)
    case noAudioForExport
    case noVideoForGif
    case noVideoForCopy
    case containerVideoCodec
    case copyCannotChangeVideo
    case containerAudioCodec
    case blankOutputDir
    case cannotTranscode
}

public func listPresets() -> [PresetInfo] {
    [
        .init(id: "mp4-h264", label: "MP4 / H.264", description: "Best compatibility"),
        .init(id: "mp4-h265", label: "MP4 / H.265", description: "Same MP4, newer codec"),
        .init(id: "mp4-copy", label: "MP4 / Remux", description: "Change the wrapper only"),
        .init(id: "webm-vp9", label: "WebM / VP9", description: "For the web"),
        .init(id: "mkv-copy-friendly", label: "MKV / H.264", description: "Re-encode for playback"),
        .init(id: "mov-h264", label: "MOV / H.264", description: "Apple devices and editors"),
        .init(id: "mkv-h265", label: "MKV / H.265", description: "Archival container"),
        .init(id: "avi-mpeg4", label: "AVI / MPEG-4", description: "Older computers"),
        .init(id: "gif", label: "GIF", description: "Short clips to GIF"),
        .init(id: "audio-mp3", label: "Audio / MP3", description: "Extract MP3"),
        .init(id: "audio-aac", label: "Audio / M4A", description: "Extract AAC"),
        .init(id: "audio-wav", label: "Audio / WAV", description: "PCM"),
        .init(id: "audio-flac", label: "Audio / FLAC", description: "Lossless, smaller than WAV"),
        .init(id: "audio-ogg", label: "Audio / OGG", description: "Opus"),
        .init(id: "audio-amr", label: "Audio / AMR", description: "Voice notes"),
    ]
}

private struct PresetDefaults {
    var container: String
    var videoEncoder: String?
    var audioEncoder: String?
    var keepAudio: Bool
}

public func resolveConfig(_ config: OutputConfig) throws -> ResolvedConfig {
    let preset = config.preset.isEmpty ? defaultPreset : config.preset
    let defaults: PresetDefaults
    switch preset {
    case "mp4-h264": defaults = .init(container: "mp4", videoEncoder: "h264", audioEncoder: "aac", keepAudio: true)
    case "mp4-h265": defaults = .init(container: "mp4", videoEncoder: "h265", audioEncoder: "aac", keepAudio: true)
    case "mp4-copy": defaults = .init(container: "mp4", videoEncoder: "copy", audioEncoder: "copy", keepAudio: true)
    case "mov-h264": defaults = .init(container: "mov", videoEncoder: "h264", audioEncoder: "aac", keepAudio: true)
    case "webm-vp9": defaults = .init(container: "webm", videoEncoder: "vp9", audioEncoder: "opus", keepAudio: true)
    case "mkv-copy-friendly": defaults = .init(container: "mkv", videoEncoder: "h264", audioEncoder: "aac", keepAudio: true)
    case "mkv-h265": defaults = .init(container: "mkv", videoEncoder: "h265", audioEncoder: "aac", keepAudio: true)
    case "avi-mpeg4": defaults = .init(container: "avi", videoEncoder: "mpeg4", audioEncoder: "mp3", keepAudio: true)
    case "gif": defaults = .init(container: "gif", videoEncoder: "gif", audioEncoder: nil, keepAudio: false)
    case "audio-mp3": defaults = .init(container: "mp3", videoEncoder: nil, audioEncoder: "mp3", keepAudio: true)
    case "audio-aac": defaults = .init(container: "m4a", videoEncoder: nil, audioEncoder: "aac", keepAudio: true)
    case "audio-wav": defaults = .init(container: "wav", videoEncoder: nil, audioEncoder: "pcm_s16le", keepAudio: true)
    case "audio-flac": defaults = .init(container: "flac", videoEncoder: nil, audioEncoder: "flac", keepAudio: true)
    case "audio-ogg": defaults = .init(container: "ogg", videoEncoder: nil, audioEncoder: "opus", keepAudio: true)
    case "audio-amr": defaults = .init(container: "amr", videoEncoder: nil, audioEncoder: "amr_nb", keepAudio: true)
    case "custom": defaults = .init(container: "mp4", videoEncoder: "h264", audioEncoder: "aac", keepAudio: true)
    default: throw LiteTransError.unknownPreset(preset)
    }

    let quality = normalizeQuality(config.quality)
    if ["audio-mp3", "audio-aac", "audio-wav", "audio-flac", "audio-ogg", "audio-amr"].contains(preset) {
        let bitrate: Int? = ["audio-wav", "audio-flac"].contains(preset) ? nil : (config.audioBitrateKbps ?? audioBitrateForQuality(quality))
        let ext = try extensionFor(defaults.container)
        return ResolvedConfig(
            preset: preset, container: defaults.container, extension: ext,
            videoEncoder: nil, audioEncoder: defaults.audioEncoder,
            maxWidth: nil, maxHeight: nil, videoBitrateKbps: nil, frameRate: nil,
            audioBitrateKbps: bitrate, keepAudio: true, quality: quality,
            trimStartSecs: config.trimStartSecs, trimEndSecs: config.trimEndSecs
        )
    }

    let copyVideo = (config.videoEncoder ?? defaults.videoEncoder) == "copy"
    let container = (config.container?.isEmpty == false ? config.container! : defaults.container)
    return ResolvedConfig(
        preset: preset,
        container: container,
        extension: try extensionFor(container),
        videoEncoder: config.videoEncoder ?? defaults.videoEncoder,
        audioEncoder: config.audioEncoder ?? defaults.audioEncoder,
        maxWidth: copyVideo ? nil : config.maxWidth,
        maxHeight: copyVideo ? nil : config.maxHeight,
        videoBitrateKbps: config.videoBitrateKbps,
        frameRate: copyVideo ? nil : config.frameRate,
        audioBitrateKbps: config.audioBitrateKbps ?? audioBitrateForQuality(quality),
        keepAudio: config.keepAudio ?? defaults.keepAudio,
        quality: quality,
        trimStartSecs: config.trimStartSecs,
        trimEndSecs: config.trimEndSecs
    )
}

public func normalizeQuality(_ value: String?) -> String {
    switch value {
    case "original", "high": return "original"
    case "small": return "small"
    default: return "standard"
    }
}

public func extensionFor(_ container: String) throws -> String {
    let allowed = ["mp4", "webm", "mkv", "mov", "avi", "gif", "mp3", "m4a", "wav", "ogg", "flac", "amr"]
    guard allowed.contains(container) else { throw LiteTransError.unsupportedContainer(container) }
    return container
}

public func isAudioOnlyConfig(_ config: ResolvedConfig) -> Bool {
    ["mp3", "m4a", "wav", "ogg", "flac", "amr"].contains(config.container)
        || ["audio-mp3", "audio-aac", "audio-wav", "audio-flac", "audio-ogg", "audio-amr"].contains(config.preset)
}

private func audioBitrateForQuality(_ quality: String) -> Int {
    switch quality {
    case "original", "high": return 320
    case "small": return 128
    default: return 192
    }
}
```

`ResolvedConfig` 需要在 `Models.swift` 提供 memberwise `init`。音频预设进表是因为测试要求 `listPresets` 含这些 ID；本刀引擎不导它们。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd ios && swift test --filter PresetsTests`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add ios/Package.swift ios/LiteTrans/Domain/Models.swift ios/LiteTrans/Domain/Presets.swift ios/LiteTransTests/Domain/PresetsTests.swift
git commit -m "$(cat <<'EOF'
feat(ios): add domain models and preset resolver

EOF
)"
```

---

### Task 2: 命名、校验、入队

**Files:**
- Create: `ios/LiteTrans/Domain/Naming.swift`
- Create: `ios/LiteTrans/Domain/Validate.swift`
- Create: `ios/LiteTrans/Domain/Queue.swift`
- Test: `ios/LiteTransTests/Domain/NamingTests.swift`
- Test: `ios/LiteTransTests/Domain/QueueTests.swift`

**Interfaces:**
- Consumes: `MediaInfo`, `OutputConfig`, `ResolvedConfig`, `resolveConfig`
- Produces: `sourceStem(_:)`, `partialOutputPath(_:)`, `allocateOutputPath(outputDir:stem:ext:exists:)`, `validate(_:media:)`, `enqueueJobs(sources:config:outputDir:nextId:exists:)`

- [ ] **Step 1: 写失败测试**

`NamingTests.swift`:

```swift
import Testing
@testable import LiteTransDomain

struct NamingTests {
    @Test func stemStripsExtension() {
        #expect(sourceStem("clip.MOV") == "clip")
        #expect(sourceStem("a/b/c.mp4") == "c")
    }

    @Test func partialSitsBeforeExtension() {
        #expect(partialOutputPath("/out/clip.mp4") == "/out/clip.partial.mp4")
        #expect(partialOutputPath("clip.mp4") == "clip.partial.mp4")
    }

    @Test func allocateSkipsExistingAndPartial() {
        let taken: Set<String> = ["/tmp/a.mp4", "/tmp/a.partial.mp4", "/tmp/a-1.mp4"]
        #expect(allocateOutputPath(outputDir: "/tmp", stem: "a", ext: "mp4", exists: { taken.contains($0) }) == "/tmp/a-2.mp4")
    }
}
```

上面 `exists` 写成清晰闭包：对 `"/tmp/a.mp4"`、`"/tmp/a.partial.mp4"`、`"/tmp/a-1.mp4"` 返回 true。

`QueueTests.swift`:

```swift
import Testing
@testable import LiteTransDomain

struct QueueTests {
    @Test func skipsUnimportable() throws {
        let bad = MediaInfo(sourceUri: "a", displayName: "a.mp4", importable: false, error: "bad")
        let good = MediaInfo(
            sourceUri: "b",
            displayName: "b.mp4",
            videoCodec: "h264",
            audioCodec: "aac",
            importable: true
        )
        let report = try enqueueJobs(
            sources: [bad, good],
            config: OutputConfig(),
            outputDir: "/tmp",
            nextId: { "1" },
            exists: { _ in false }
        )
        #expect(report.jobs.count == 1)
        #expect(report.jobs[0].displayName == "b.mp4")
        #expect(report.jobs[0].status == .queued)
        #expect(report.skipped.count == 1)
        #expect(report.skipped[0].reason == "bad")
    }

    @Test func copyRejectsResolutionChange() {
        let media = MediaInfo(sourceUri: "a", displayName: "a.mp4", videoCodec: "h264", importable: true)
        #expect(throws: LiteTransError.copyCannotChangeVideo) {
            try validate(
                try resolveConfig(OutputConfig(preset: "mp4-copy", maxWidth: 1280)),
                media: media
            )
        }
    }

    @Test func markInterruptedFailsRunningOnly() {
        let running = Job(id: "1", sourceUri: "a", displayName: "a", outputPath: nil, status: .running, progress: 10, error: nil, config: OutputConfig(), media: MediaInfo(sourceUri: "a", displayName: "a"))
        let queued = Job(id: "2", sourceUri: "b", displayName: "b", outputPath: nil, status: .queued, progress: 0, error: nil, config: OutputConfig(), media: MediaInfo(sourceUri: "b", displayName: "b"))
        let out = markInterrupted([running, queued], interrupted: "interrupted")
        #expect(out[0].status == .failed)
        #expect(out[0].error == "interrupted")
        #expect(out[1].status == .queued)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd ios && swift test --filter NamingTests --filter QueueTests`
Expected: FAIL，符号未定义

- [ ] **Step 3: 最小实现**

`Naming.swift`:

```swift
public func sourceStem(_ displayName: String) -> String {
    let name = displayName.split(separator: "/").last.map(String.init) ?? displayName
    if let dot = name.lastIndex(of: "."), dot != name.startIndex {
        let stem = String(name[..<dot])
        return stem.isEmpty ? "output" : stem
    }
    return name.isEmpty ? "output" : name
}

public func partialOutputPath(_ output: String) -> String {
    let lastSlash = output.lastIndex(of: "/")
    let parent = lastSlash.map { String(output[..<$0]) } ?? ""
    let fileName = lastSlash.map { String(output[output.index(after: $0)...]) } ?? output
    let ext: String
    let stem: String
    if let dot = fileName.lastIndex(of: ".") {
        ext = String(fileName[fileName.index(after: dot)...])
        stem = String(fileName[..<dot])
    } else {
        ext = "bin"
        stem = fileName
    }
    let actualStem = stem.isEmpty ? "output" : stem
    let partialName = "\(actualStem).partial.\(ext)"
    return parent.isEmpty ? partialName : "\(parent)/\(partialName)"
}

public func allocateOutputPath(outputDir: String, stem: String, ext: String, exists: (String) -> Bool) -> String {
    let dir = outputDir.hasSuffix("/") ? String(outputDir.dropLast()) : outputDir
    let candidate = "\(dir)/\(stem).\(ext)"
    if !exists(candidate) { return candidate }
    var index = 1
    while true {
        let numbered = "\(dir)/\(stem)-\(index).\(ext)"
        if !exists(numbered) { return numbered }
        index += 1
    }
}

public func sanitizeRenameStem(_ raw: String) -> String? {
    let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    if trimmed.isEmpty || trimmed.contains("/") || trimmed.contains("\\") { return nil }
    var cleaned = trimmed
    for ch in [":", "*", "?", "\"", "<", ">", "|"] {
        cleaned = cleaned.replacingOccurrences(of: ch, with: "")
    }
    cleaned = cleaned.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
        .trimmingCharacters(in: .whitespacesAndNewlines)
        .trimmingCharacters(in: CharacterSet(charactersIn: "."))
    let stem = cleaned.split(separator: ".").dropLast().joined(separator: ".")
    let resolved = cleaned.contains(".") ? stem : cleaned
    if resolved.isEmpty || resolved == "." || resolved == ".." { return nil }
    return String(resolved.prefix(80))
}

public func canRenameJob(_ status: JobStatus) -> Bool { status == .completed }
```

`sourceStem("clip.MOV")` 用 `lastIndex(of: ".")`，不要按每个点 split。

`Validate.swift` 的 `validate` / `containerAcceptsVideo` / `containerAcceptsAudio` 按下面集合实现（与 Android 相同）：

- 容器：`mp4,webm,mkv,mov,avi,gif,mp3,m4a,wav,ogg,flac,amr`
- 视频编码器：`h264,h265,vp9,mpeg4,gif,copy`
- 音频编码器：`aac,opus,mp3,copy,pcm_s16le,flac,amr_nb`
- `h265` 正规化为 `hevc` 再比容器
- mp4/mov 视频接受 `h264,hevc,mpeg4,av1`

`Queue.swift`：

```swift
public func splitImportable(_ sources: [MediaInfo], cannotTranscode: String = "Could not convert this file") -> ([MediaInfo], [SkippedSource]) {
    var accepted: [MediaInfo] = []
    var skipped: [SkippedSource] = []
    for source in sources {
        if source.importable {
            accepted.append(source)
        } else {
            skipped.append(.init(sourceUri: source.sourceUri, displayName: source.displayName, reason: source.error ?? cannotTranscode))
        }
    }
    return (accepted, skipped)
}

public func configForSource(_ config: OutputConfig, media: MediaInfo) -> OutputConfig {
    var next = config
    if media.trimStartSecs != nil || media.trimEndSecs != nil {
        next.trimStartSecs = media.trimStartSecs
        next.trimEndSecs = media.trimEndSecs
    }
    return next
}

public func enqueueJobs(
    sources: [MediaInfo],
    config: OutputConfig,
    outputDir: String,
    nextId: () -> String,
    exists: (String) -> Bool
) throws -> EnqueueReport {
    if outputDir.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
        throw LiteTransError.blankOutputDir
    }
    let resolved = try resolveConfig(config)
    let (accepted, initialSkipped) = splitImportable(sources)
    var skipped = initialSkipped
    var jobs: [Job] = []
    var allocated: Set<String> = []
    for media in accepted {
        do {
            try validate(resolved, media: media)
        } catch {
            skipped.append(.init(sourceUri: media.sourceUri, displayName: media.displayName, reason: String(describing: error)))
            continue
        }
        let outputPath = allocateOutputPath(outputDir: outputDir, stem: sourceStem(media.displayName), ext: resolved.extension) { candidate in
            let partial = partialOutputPath(candidate)
            return exists(candidate) || exists(partial) || allocated.contains(candidate) || allocated.contains(partial)
        }
        allocated.insert(outputPath)
        allocated.insert(partialOutputPath(outputPath))
        jobs.append(Job(
            id: nextId(),
            sourceUri: media.sourceUri,
            displayName: media.displayName,
            outputPath: outputPath,
            status: .queued,
            progress: 0,
            error: nil,
            config: configForSource(config, media),
            media: media
        ))
    }
    return EnqueueReport(jobs: jobs, skipped: skipped)
}

public func markInterrupted(_ jobs: [Job], interrupted: String = "Conversion was interrupted") -> [Job] {
    jobs.map { job in
        job.status == .running ? Job(id: job.id, sourceUri: job.sourceUri, displayName: job.displayName, outputPath: job.outputPath, status: .failed, progress: job.progress, error: interrupted, config: job.config, media: job.media) : job
    }
}
```

`Job` 需要 public memberwise init。`validate` 的失败原因测试用 `LiteTransError`，`QueueTests.copyRejectsResolutionChange` 调 `validate` 而不是看 skip 字符串。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd ios && swift test --filter NamingTests --filter QueueTests --filter PresetsTests`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add ios/LiteTrans/Domain/Naming.swift ios/LiteTrans/Domain/Validate.swift ios/LiteTrans/Domain/Queue.swift ios/LiteTransTests/Domain/NamingTests.swift ios/LiteTransTests/Domain/QueueTests.swift
git commit -m "$(cat <<'EOF'
feat(ios): add naming, validation, and enqueue

EOF
)"
```

---

### Task 3: 进度解析、探测 JSON、VideoToolbox 编码器名

**Files:**
- Create: `ios/LiteTrans/Domain/Progress.swift`
- Create: `ios/LiteTrans/Domain/ProbeParser.swift`
- Create: `ios/LiteTrans/Domain/FfmpegCodec.swift`
- Test: `ios/LiteTransTests/Domain/ProgressTests.swift`

**Interfaces:**
- Consumes: `MediaInfo`
- Produces: `parseProgressLine(_:durationSecs:) -> Double?`, `parseFfprobeJson(sourceUri:displayName:json:) -> MediaInfo`, `ffmpegVideoCodec(_:preferHardware:) -> String`

- [ ] **Step 1: 写失败测试**

```swift
import Testing
@testable import LiteTransDomain

struct ProgressTests {
    @Test func parsesOutTimeMs() {
        #expect(parseProgressLine("out_time_ms=5000000", durationSecs: 10) == 50)
    }

    @Test func probeMarksImportableWhenVideoPresent() {
        let json = """
        {"format":{"duration":"2.5","format_name":"mov,mp4,m4a,3gp,3g2,mj2"},"streams":[{"codec_type":"video","codec_name":"h264","width":1920,"height":1080,"r_frame_rate":"30/1"},{"codec_type":"audio","codec_name":"aac","channels":2}]}
        """
        let media = parseFfprobeJson(sourceUri: "u", displayName: "a.mp4", json: json)
        #expect(media.importable)
        #expect(media.videoCodec == "h264")
        #expect(media.width == 1920)
        #expect(media.durationSecs == 2.5)
        #expect(media.frameRate == 30)
    }

    @Test func hardwareCodecsUseVideoToolbox() {
        #expect(ffmpegVideoCodec("h264", preferHardware: true) == "h264_videotoolbox")
        #expect(ffmpegVideoCodec("h265", preferHardware: true) == "hevc_videotoolbox")
        #expect(ffmpegVideoCodec("h264", preferHardware: false) == "libx264")
        #expect(ffmpegVideoCodec("copy", preferHardware: true) == "copy")
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd ios && swift test --filter ProgressTests`
Expected: FAIL

- [ ] **Step 3: 最小实现**

`Progress.swift`：与 `Progress.kt` 相同，`out_time_ms` / `out_time_us` 都当微秒。

`ProbeParser.swift`：用 `JSONDecoder` 或 `JSONSerialization` 解析 ffprobe JSON，逻辑同 `ProbeParser.kt`。坏 JSON → `importable = false`。

`FfmpegCodec.swift`：

```swift
public func ffmpegVideoCodec(_ encoder: String, preferHardware: Bool) -> String {
    switch (encoder, preferHardware) {
    case ("h264", true): return "h264_videotoolbox"
    case ("h265", true): return "hevc_videotoolbox"
    case ("h264", false): return "libx264"
    case ("h265", false): return "libx265"
    case ("vp9", _): return "libvpx-vp9"
    case ("mpeg4", _): return "mpeg4"
    case ("gif", _): return "gif"
    case ("copy", _): return "copy"
    default: return "libx264"
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd ios && swift test --filter ProgressTests`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add ios/LiteTrans/Domain/Progress.swift ios/LiteTrans/Domain/ProbeParser.swift ios/LiteTrans/Domain/FfmpegCodec.swift ios/LiteTransTests/Domain/ProgressTests.swift
git commit -m "$(cat <<'EOF'
feat(ios): add probe, progress, and VideoToolbox codec names

EOF
)"
```

---

### Task 4: 转码导航纯函数

**Files:**
- Create: `ios/LiteTrans/UI/ConvertNavigation.swift`（此文件本刀放 Domain 测试能编过：改放到 `ios/LiteTrans/Domain/ConvertNavigation.swift`，避免 UI 依赖）
- Test: `ios/LiteTransTests/Domain/ConvertNavigationTests.swift`

**Interfaces:**
- Consumes: 预设 ID
- Produces: `enum RootTab`, `enum ConvertPage`, `enum ConvertSetting`, `enum OutputKind`, `struct OutputTarget`, `convertSettingsFor(preset:)`, `collapsedPrimaryPresets()`, `canStart(importable:probing:transcoding:output:)`, `popConvertBack(page:)`, `popMineBack(page:)`

把 `ConvertNavigation.swift` 放在 `LiteTrans/Domain/`，以便 `swift test` 覆盖。

- [ ] **Step 1: 写失败测试**

```swift
import Testing
@testable import LiteTransDomain

struct ConvertNavigationTests {
    @Test func settingsHideQualityAndSizeForCopy() {
        #expect(convertSettingsFor(preset: "mp4-copy") == [.format, .output])
        #expect(convertSettingsFor(preset: "mp4-h264") == [.format, .quality, .size, .output])
    }

    @Test func primaryPresetsAreFour() {
        #expect(collapsedPrimaryPresets().map(\.id) == ["mp4-h264", "mp4-copy", "mp4-h265", "mov-h264"])
    }

    @Test func startRequiresImportableReadyOutputAndIdle() {
        let ready = OutputTarget(kind: .downloads)
        #expect(!canStart(importable: 0, probing: false, transcoding: false, output: ready))
        #expect(!canStart(importable: 1, probing: true, transcoding: false, output: ready))
        #expect(!canStart(importable: 1, probing: false, transcoding: true, output: ready))
        #expect(!canStart(importable: 1, probing: false, transcoding: false, output: OutputTarget(kind: .custom, bookmark: nil)))
        #expect(canStart(importable: 1, probing: false, transcoding: false, output: ready))
    }

    @Test func convertBackGoesHome() {
        #expect(popConvertBack(.format) == .home)
        #expect(popConvertBack(.home) == nil)
    }

    @Test func mineBackGoesRoot() {
        #expect(popMineBack(.privacy) == .root)
        #expect(popMineBack(.root) == nil)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd ios && swift test --filter ConvertNavigationTests`
Expected: FAIL

- [ ] **Step 3: 最小实现**

```swift
public enum RootTab: String, CaseIterable, Sendable { case convert, history, mine }
public enum ConvertPage: String, Sendable { case home, format, quality, size, output }
public enum ConvertSetting: String, Sendable { case format, quality, size, output }
public enum MinePage: String, Sendable { case root, language, privacy, terms, about }
public enum OutputKind: String, Sendable, Codable { case photos, downloads, custom }

public struct OutputTarget: Equatable, Sendable, Codable {
    public var kind: OutputKind
    public var bookmark: Data?
    public init(kind: OutputKind, bookmark: Data? = nil) {
        self.kind = kind
        self.bookmark = bookmark
    }
}

public struct PresetCard: Equatable, Sendable {
    public var id: String
    public var title: String
    public var hint: String
}

public let primaryPresetIDs = ["mp4-h264", "mp4-copy", "mp4-h265", "mov-h264"]

public func collapsedPrimaryPresets() -> [PresetCard] {
    [
        .init(id: "mp4-h264", title: "MP4 · H.264", hint: "Best compatibility"),
        .init(id: "mp4-copy", title: "MP4 · Remux", hint: "Change the wrapper only"),
        .init(id: "mp4-h265", title: "MP4 · H.265", hint: "Usually smaller"),
        .init(id: "mov-h264", title: "MOV · H.264", hint: "Apple devices and editors"),
    ]
}

public func isCopyPreset(_ preset: String) -> Bool { preset == "mp4-copy" }

public func shouldShowQualityRow(_ preset: String) -> Bool { !isCopyPreset(preset) && !preset.hasPrefix("audio-") }

public func shouldShowResolution(_ preset: String) -> Bool { !preset.hasPrefix("audio-") && preset != "mp4-copy" }

public func convertSettingsFor(preset: String) -> [ConvertSetting] {
    var rows: [ConvertSetting] = [.format]
    if shouldShowQualityRow(preset) { rows.append(.quality) }
    if shouldShowResolution(preset) { rows.append(.size) }
    rows.append(.output)
    return rows
}

public func outputReadyToStart(_ output: OutputTarget) -> Bool {
    output.kind != .custom || output.bookmark != nil
}

public func canStart(importable: Int, probing: Bool, transcoding: Bool, output: OutputTarget) -> Bool {
    importable > 0 && !probing && !transcoding && outputReadyToStart(output)
}

public func popConvertBack(_ page: ConvertPage) -> ConvertPage? {
    page == .home ? nil : .home
}

public func popMineBack(_ page: MinePage) -> MinePage? {
    page == .root ? nil : .root
}

public func resolutionBounds(_ size: String) -> (Int?, Int?) {
    switch size {
    case "1080p": return (1920, 1080)
    case "720p": return (1280, 720)
    case "480p": return (854, 480)
    default: return (nil, nil)
    }
}
```

`Package.swift` 的 Domain target path 仍是 `LiteTrans/Domain`，新文件自动编进。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd ios && swift test`
Expected: PASS（全部 Domain 测试）

- [ ] **Step 5: Commit**

```bash
git add ios/LiteTrans/Domain/ConvertNavigation.swift ios/LiteTransTests/Domain/ConvertNavigationTests.swift
git commit -m "$(cat <<'EOF'
feat(ios): add convert navigation helpers

EOF
)"
```

---

### Task 5: Xcode 工程、主题、三 Tab 壳、文案

**Files:**
- Create: `ios/project.yml`
- Create: `ios/LiteTrans/App/LiteTransApp.swift`
- Create: `ios/LiteTrans/App/RootView.swift`
- Create: `ios/LiteTrans/UI/Theme.swift`
- Create: `ios/LiteTrans/Resources/Info.plist`
- Create: `ios/LiteTrans/Resources/Assets.xcassets/AccentColor.colorset/Contents.json`
- Create: `ios/LiteTrans/Resources/Localizable.xcstrings`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: `RootTab`
- Produces: 可 `xcodebuild` 的 `LiteTrans` scheme；`TabView` 三个 destination；强调色 Asset

- [ ] **Step 1: 写 `project.yml` 和壳代码**

`ios/project.yml`:

```yaml
name: LiteTrans
options:
  bundleIdPrefix: com.videoconverter
  deploymentTarget:
    iOS: "18.0"
settings:
  base:
    TARGETED_DEVICE_FAMILY: "1"
    IPHONEOS_DEPLOYMENT_TARGET: "18.0"
targets:
  LiteTrans:
    type: application
    platform: iOS
    sources:
      - LiteTrans
    resources:
      - LiteTrans/Resources/Assets.xcassets
      - LiteTrans/Resources/Localizable.xcstrings
    info:
      path: LiteTrans/Resources/Info.plist
      properties:
        CFBundleDisplayName: LiteTrans
        CFBundleShortVersionString: "0.1.0"
        CFBundleVersion: "1"
        UILaunchScreen: {}
        UISupportedInterfaceOrientations:
          - UIInterfaceOrientationPortrait
        NSPhotoLibraryAddUsageDescription: LiteTrans saves converted videos to Photos when you choose Photos as the output.
        UIFileSharingEnabled: true
        LSSupportsOpeningDocumentsInPlace: true
        ITSAppUsesNonExemptEncryption: false
    settings:
      base:
        PRODUCT_BUNDLE_IDENTIFIER: com.videoconverter.ios
        ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME: AccentColor
        SWIFT_VERSION: "6.0"
        TARGETED_DEVICE_FAMILY: "1"
  LiteTransTests:
    type: bundle.unit-test
    platform: iOS
    sources:
      - LiteTransTests
    dependencies:
      - target: LiteTrans
```

`LiteTransApp.swift`:

```swift
import SwiftUI

@main
struct LiteTransApp: App {
    @State private var model = AppModel()
    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
        }
    }
}
```

本步若 `AppModel` 尚未存在，先放一个最小 `@Observable class AppModel {}` 在 `UI/AppModel.swift`。

`RootView.swift`：`TabView` 三 Tab，标签「转码 / 历史 / 我的」，符号 `arrow.triangle.2.circlepath`、`clock`、`person.crop.circle`。每个 Tab 包 `NavigationStack`。转码 / 历史 / 我的先放 `Text` 占位，大标题用 `.navigationTitle` + `.navigationBarTitleDisplayMode(.large)`。

`Theme.swift`：不要 hex 字面量。注释写明强调色只来自 Asset Catalog。

`AccentColor.colorset`：any `#C45A2A`，dark 略提亮（例如 `#E07A4A`），高对比 extra 各一份。

`Localizable.xcstrings` 至少含：`tab_convert` 转码 / Convert；`tab_history` 历史 / History；`tab_mine` 我的 / Me；`action_convert` 转换 / Convert。中文进 `zh-Hans`。

`.gitignore` 追加：

```
ios/.build/
ios/*.xcodeproj/xcuserdata/
ios/*.xcworkspace/xcuserdata/
ios/DerivedData/
```

`project.yml` 生成的 `LiteTrans.xcodeproj` **要入库**（没有 XcodeGen 的机器也能打开），或文档写明必须 `brew install xcodegen && cd ios && xcodegen generate`。本计划选择：**生成后把 `LiteTrans.xcodeproj` 提交**。

- [ ] **Step 2: 生成工程并编译**

Run:

```bash
cd ios
command -v xcodegen >/dev/null || brew install xcodegen
xcodegen generate
xcodebuild -scheme LiteTrans -destination 'generic/platform=iOS Simulator' -quiet build
```

Expected: BUILD SUCCEEDED

- [ ] **Step 3: Commit**

```bash
git add ios/project.yml ios/LiteTrans.xcodeproj ios/LiteTrans/App ios/LiteTrans/UI/Theme.swift ios/LiteTrans/UI/AppModel.swift ios/LiteTrans/Resources .gitignore
git commit -m "$(cat <<'EOF'
feat(ios): add Xcode app shell with three tabs

EOF
)"
```

---

### Task 6: 转码首页与设置 push

**Files:**
- Create: `ios/LiteTrans/UI/ConvertHomeView.swift`
- Create: `ios/LiteTrans/UI/ConvertSettingsViews.swift`
- Modify: `ios/LiteTrans/App/RootView.swift`
- Modify: `ios/LiteTrans/UI/AppModel.swift`
- Modify: `ios/LiteTrans/Resources/Localizable.xcstrings`

**Interfaces:**
- Consumes: `ConvertPage`, `convertSettingsFor`, `collapsedPrimaryPresets`, `canStart`, `OutputTarget`
- Produces: 相册 / 文件添加、文件列表、设置披露行、格式 / 画质 / 分辨率 / 存放列表、导航栏「转换」+ 底部「开始转换」

- [ ] **Step 1: `AppModel` 会话状态**

```swift
import SwiftUI
import UniformTypeIdentifiers

@Observable
final class AppModel {
    var tab: RootTab = .convert
    var convertPage: ConvertPage = .home
    var minePage: MinePage = .root
    var sources: [MediaInfo] = []
    var selectedUri: String?
    var preset: String = defaultPreset
    var quality: String = "standard"
    var size: String = "original"
    var output: OutputTarget = .init(kind: .downloads)
    var jobs: [Job] = []
    var transcoding: Bool = false
    var message: String?
    var language: AppLanguage = .system

    var importableCount: Int { sources.filter(\.importable).count }
    var probing: Bool { sources.contains { $0.error == nil && !$0.importable && $0.videoCodec == nil && $0.durationSecs == nil } }
    var startEnabled: Bool { canStart(importable: importableCount, probing: probing, transcoding: transcoding, output: output) }
}
```

探测中判定不要含糊：给 `MediaInfo` 加 `var probing: Bool = false`（改 Models + 更新测试编译）。**本步先改 `MediaInfo.probing`**，默认 `false`。

`AppLanguage`: `enum AppLanguage: String { case system, zhHans, zhHant, en, ja, ko }` 放 Domain。

- [ ] **Step 2: Convert 界面**

`ConvertHomeView`：

- `navigationTitle`「转码」，`toolbar` trailing `Button` 文案 `String(localized: "action_convert")`，`disabled` 当 `!model.startEnabled`
- 无音频 / 文档分段
- 若 `selected` 可导入且有时长：`VideoPlayer`（系统能播）或只显示时间轴占位（本刀裁切控件可先做「设为开始 / 结束 / 恢复整段」三个按钮 + Slider，不用 FFmpeg 预览）
- 分组「文件」：行「相册」「文件」；已选文件 `swipeActions` 移除（`transcoding && sourceUri` 匹配 running job 则不能移除）
- 分组「设置」：`convertSettingsFor(preset:)` 披露行，`navigationDestination`
- 底部 `Button` `.borderedProminent`「开始转换」，同一 `startEnabled`

相册：`PhotosPicker` `matching: .videos`，不申请完整图库。文件：`.fileImporter` `UTType.movie`，`allowsMultipleSelection: true`。

`ConvertSettingsViews`：

- Format：`collapsedPrimaryPresets()` 勾选列表，无「更多」
- Quality：原画 / 标准 / 节省体积
- Size：原尺寸 / 1080p / 720p / 480p；`mp4-copy` 不进入此页
- Output：照片 / 下载 / 自选。自选 `fileImporter` `UTType.folder`，安全作用域书签写入 `output.bookmark`

本步「开始转换」只 `guard model.startEnabled else { return }`，真正入队在 Task 7。

返回：系统返回与自定义都走 `popConvertBack`。

- [ ] **Step 3: 编译**

Run: `cd ios && xcodebuild -scheme LiteTrans -destination 'generic/platform=iOS Simulator' -quiet build`
Expected: BUILD SUCCEEDED

- [ ] **Step 4: Commit**

```bash
git add ios/LiteTrans/UI/ConvertHomeView.swift ios/LiteTrans/UI/ConvertSettingsViews.swift ios/LiteTrans/UI/AppModel.swift ios/LiteTrans/App/RootView.swift ios/LiteTrans/Domain/Models.swift ios/LiteTrans/Resources/Localizable.xcstrings
git commit -m "$(cat <<'EOF'
feat(ios): add convert home and settings push pages

EOF
)"
```

---

### Task 7: 探测、AVFoundation 导出、队列泵、入队切历史

**Files:**
- Create: `ios/LiteTrans/Engine/ProbeService.swift`
- Create: `ios/LiteTrans/Engine/VideoExporter.swift`
- Create: `ios/LiteTrans/Engine/QueuePump.swift`
- Create: `ios/LiteTrans/Data/JobStore.swift`
- Create: `ios/LiteTrans/Data/SessionStore.swift`
- Modify: `ios/LiteTrans/UI/AppModel.swift`

**Interfaces:**
- Consumes: `enqueueJobs`, `parseFfprobeJson`（若走 ffprobe；本刀探测用 `AVAsset` 填 `MediaInfo`，不调用 ffprobe 二进制）, `canStart`
- Produces: `ProbeService.probe(url:displayName:) async -> MediaInfo`；`VideoExporter.export(job:outputURL:) async`；泵一次只跑一个 `queued` job

- [ ] **Step 1: ProbeService**

用 `AVURLAsset` 读时长、轨道 codec、宽高、帧率、是否有音视频。无视频且无音频 → `importable = false`。不要 FFmpeg。

```swift
struct ProbeService {
    func probe(url: URL, displayName: String) async -> MediaInfo { /* AVAsset tracks */ }
}
```

选中相册/文件后立刻 `Task { source = await probe(...) }`。

- [ ] **Step 2: VideoExporter**

只接受本刀四预设：

| preset | AVAssetExportSession |
| --- | --- |
| `mp4-copy` | `AVAssetExportPresetPassthrough`，输出 `.mp4` |
| `mp4-h264` | `AVAssetExportPresetHighestQuality` / `1920x1080` / `1280x720` / `640x480` 按 `size` 映射，文件类型 `.mp4` |
| `mp4-h265` | HEVC 预设（`AVAssetExportPresetHEVCHighestQuality` 或 1920x1080 HEVC），`.mp4` |
| `mov-h264` | 同 H.264，文件类型 `.mov` |

`quality`：`original` 用 Highest；`standard` 用 1920x1080（若 size 为 original）；`small` 用 1280x720。若用户同时选了 `size`，`size` 优先。

裁切：`timeRange` 用 `trimStartSecs` / `trimEndSecs`。

写出 `{stem}.partial.{ext}`，成功后再 `replace` 成 `allocateOutputPath` 的最终路径。失败删除 partial。

照片输出：导出到临时文件后 `PHAssetChangeRequest.creationRequestForAssetFromVideo`。下载：App Documents 下 `Downloads/`。自选：解析 bookmark 得目录。

进度：`exportSession.progress` 0...1 映射到 job `progress` 0...100。

- [ ] **Step 3: QueuePump + JobStore**

`JobStore`：`UserDefaults` JSON 编解码 `[Job]`。启动时 `markInterrupted`。

`QueuePump`：若 `transcoding` 则 return。取第一个 `queued`，标 `running`，`export`，成功 `completed`，取消 `cancelled`，错误 `failed`。`AppModel.start()`：

1. `guard startEnabled`
2. `let dir = resolvedOutputDir()`（下载目录或 bookmark 路径；照片用 tmp）
3. `let report = try enqueueJobs(...)`
4. 把 `report.jobs` 插到 `jobs` 列表头部
5. `report.skipped` 拼进 `message`
6. 清当前会话 `sources`，`convertPage = .home`
7. `tab = .history`
8. 启动泵

进行中禁止再 `start`（`transcoding`）。

- [ ] **Step 4: 编译**

Run: `cd ios && xcodebuild -scheme LiteTrans -destination 'generic/platform=iOS Simulator' -quiet build`
Expected: BUILD SUCCEEDED

- [ ] **Step 5: Domain 测试仍通过**

Run: `cd ios && swift test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add ios/LiteTrans/Engine ios/LiteTrans/Data ios/LiteTrans/UI/AppModel.swift
git commit -m "$(cat <<'EOF'
feat(ios): export videos with AVFoundation and a single-job pump

EOF
)"
```

---

### Task 8: 历史（视频）与我的

**Files:**
- Create: `ios/LiteTrans/UI/HistoryView.swift`
- Create: `ios/LiteTrans/UI/JobRow.swift`
- Create: `ios/LiteTrans/UI/MineView.swift`
- Modify: `ios/LiteTrans/App/RootView.swift`
- Modify: `ios/LiteTrans/Resources/Localizable.xcstrings`
- Test: 给 `jobRowActions` 纯函数加测试（放 Domain）

**Interfaces:**
- Consumes: `[Job]`, `JobStatus`, `MinePage`, `AppLanguage`
- Produces: 历史列表（无分段控件）；我的分组：语言、隐私、条款、关于。无局域网行。

- [ ] **Step 1: 写 `jobRowActions` 测试**

`ConvertNavigation.swift` 增加：

```swift
public enum JobRowAction: Equatable { case cancel, retry, open, share, rename, delete }

public func jobRowActions(_ status: JobStatus) -> [JobRowAction] {
    switch status {
    case .queued, .running: return [.cancel]
    case .failed, .cancelled: return [.retry, .delete]
    case .completed: return [.open, .share, .rename, .delete]
    }
}

public func remainingJobsAfterClearFinished(_ jobs: [Job]) -> [Job] {
    jobs.filter { $0.status == .queued || $0.status == .running }
}
```

测试：running 只有 cancel；completed 含 open/share/rename/delete；clear 只留 queued/running。

- [ ] **Step 2: HistoryView**

大标题「历史」。无视频/音频/文档分段。`List` 新任务在上。`ProgressView` 当 running。

| 状态 | 点按 | 左滑 | 长按 |
| --- | --- | --- | --- |
| queued/running | 无 | 取消 | 无 |
| failed/cancelled | 再试一次 | 删除 | 再试一次 |
| completed | Quick Look | 删除 | 分享、重命名 |

清空：trailing，确认后 `jobs = remainingJobsAfterClearFinished(jobs)`。空态 `ContentUnavailableView`，按钮切 `tab = .convert`。

Quick Look：`QLPreviewController` 包装。分享：`ShareLink`。重命名：alert 文本框 + `sanitizeRenameStem`。

- [ ] **Step 3: MineView**

`Form`：

1. 语言
2. 隐私协议、使用条款
3. 关于

语言页：跟随系统、简体中文、繁體中文、English、日本語、한국어。写入 `AppLanguage`，用 `environment(\.locale)` 覆盖。ja / zh-Hant / ko 本刀回退英文。

隐私 / 条款 / 关于：应用内长文。关于含版本 `Bundle.main.infoDictionary["CFBundleShortVersionString"]`。关于正文写「当前版本仅提供 Xcode 真机与 TestFlight」，不要写 Android。隐私文案可从 Android zh-CN / en 改「相册和文件访问只用于读取你选中的视频」，删掉局域网段或改成「局域网访问将在后续版本提供」（因为本刀没有开关，**不要承诺已存在的功能**）。本刀隐私正文**不提局域网**。

切走我的 Tab 时 `minePage = .root`。

- [ ] **Step 4: 跑 Domain 测试 + 编译**

Run:

```bash
cd ios && swift test
xcodebuild -scheme LiteTrans -destination 'generic/platform=iOS Simulator' -quiet build
```

Expected: PASS + BUILD SUCCEEDED

- [ ] **Step 5: Commit**

```bash
git add ios/LiteTrans/UI/HistoryView.swift ios/LiteTrans/UI/JobRow.swift ios/LiteTrans/UI/MineView.swift ios/LiteTrans/Domain/ConvertNavigation.swift ios/LiteTransTests/Domain/ConvertNavigationTests.swift ios/LiteTrans/Resources/Localizable.xcstrings ios/LiteTrans/App/RootView.swift
git commit -m "$(cat <<'EOF'
feat(ios): add video history and settings pages

EOF
)"
```

---

### Task 9: README 与冒烟清单

**Files:**
- Modify: `README.md`
- Create: `ios/scripts/.gitkeep`

**Interfaces:**
- Consumes: 无
- Produces: README iOS 段

- [ ] **Step 1: README 追加**

在安装包表增加：iOS arm64，Xcode 真机 / TestFlight，无 App Store。构建：

```bash
cd ios && xcodegen generate && xcodebuild -scheme LiteTrans -destination 'generic/platform=iOS Simulator' build
```

说明：第 1 刀只支持四张视频主预设；音频 / 文档 / 局域网尚未提供。许可仍按根 README 的本机处理说明。

真机冒烟：

1. 浅色 / 深色外观都像系统设置页
2. 相册加一条视频，改格式为 MOV，开始转换
3. 自动跳历史，完成后 Quick Look
4. 我的 → 关于含「轻转码」和版本；隐私含「不上传」

- [ ] **Step 2: Commit**

```bash
git add README.md ios/scripts/.gitkeep
git commit -m "$(cat <<'EOF'
docs: add iOS slice-1 build notes

EOF
)"
```

---

## Spec coverage（自审）

| 规格项 | 本计划 |
| --- | --- |
| 三 Tab 转码/历史/我的 | Task 5 |
| 分段视频/音频/文档 | 第 2 刀，本刀隐藏 |
| 设置 push、四主预设、无更多 | Task 4–6 |
| 相册 PHPicker、文件、Add Only 照片 | Task 6–7 |
| 开始后切历史、单任务泵 | Task 7 |
| 历史点按/左滑/清空 | Task 8 |
| 我的语言/协议/条款/关于 | Task 8 |
| 语义色 + 轻转码橙 | Task 5 |
| Live Activity / 后台 BGTask | 第 3 刀 |
| 局域网 | 第 4 刀 |
| FFmpeg 捆绑、WebM/GIF/AVI | 第 2 刀之后 |
| Domain 预设/命名/校验/入队 | Task 1–2 |
| VideoToolbox 编码器名 | Task 3（给后续 FFmpeg 用）；本刀导出走 AVFoundation |
