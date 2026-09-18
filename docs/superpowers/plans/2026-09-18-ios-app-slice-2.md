# 轻转码 iOS 第 2 刀 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 转码和历史加上视频 | 音频 | 文档分段；音频六张卡与视频更多格式走进程内 FFmpeg；图片六项和 PDF 四项走 ImageIO + PDFKit。

**Architecture:** Domain 纯函数继续只靠 `swift test`。App 的 `QueuePump` 按 `engineKind(preset)` 分发到现有 `VideoExporter`、新建 `FFmpegRunner`（xcframework，argv 数组）或 `DocumentEngine`。`AppModel` 持有三份 `WizardSession`。FFmpeg 产物不入库，构建前跑 `ios/scripts/fetch-ffmpeg.mjs`。

**Tech Stack:** Swift 6、SwiftUI、iOS 18、arm64、Swift Testing、XcodeGen、AVFoundation、ImageIO、PDFKit、FFmpeg xcframework（进程内，禁止 `Process`）。

## Global Constraints

- Bundle ID `com.videoconverter.ios`，主屏幕名 `LiteTrans`，关于页写「轻转码」
- 最低 iOS 18，只打 iPhone arm64；模拟器需 arm64 slice；不上 App Store
- 四张主预设 `mp4-h264` / `mp4-copy` / `mp4-h265` / `mov-h264` 继续走 AVFoundation，不改成 FFmpeg
- FFmpeg 以 xcframework **进程内**调用。禁止 `Process` / `fork` / 调外部二进制
- 预设 ID、白名单、输出命名、`.partial` 再改名与 Android 相同
- 音频六张：`audio-mp3`、`audio-aac`、`audio-wav`、`audio-flac`、`audio-ogg`、`audio-amr`；默认 `audio-mp3` + 下载；无「音乐」存放档
- 视频更多格式：`webm-vp9`、`mkv-copy-friendly`、`mkv-h265`、`avi-mpeg4`、`gif`，以及视频页 `audio-mp3` / `audio-aac`；视频页不出现 WAV / OGG / FLAC / AMR
- 文档：图片六项 + PDF 四项；docx / xlsx 不可导入；不做 Office → PDF、Live Activity、局域网
- 「文档」存放 = 应用 `Documents/`；照片仍是 Add Only
- 生产 UI 不用 hex；语义色 + 轻转码橙；触控 ≥ 44pt；不用 FFmpeg 出预览片
- 中文默认；Localizable 先做 en + zh-Hans

本计划覆盖 [2026-09-18-ios-app-slice-2-design.md](../specs/2026-09-18-ios-app-slice-2-design.md)。桌面 `src/`、`android/` 不改。

## File map

```
ios/
  scripts/fetch-ffmpeg.mjs          # 新建
  Vendor/FFmpeg/                    # gitignore
  Package.swift                     # 不变（仍只测 Domain）
  project.yml                       # 链 xcframework
  LiteTrans/Domain/
    Models.swift                    # page*、outputPaths、outputKind
    Document.swift                  # 新建
    Naming.swift                    # 001 序号、ffmpegFileArg
    Queue.swift                     # 多输出占用/删除
    ConvertNavigation.swift         # mode、折叠卡、历史分段、存放
    FfmpegArgs.swift                # 新建
    Validate.swift                  # 文档预设早退
    Presets.swift                   # 若需文档 resolveConfig
  LiteTrans/Engine/
    FFmpegRunner.swift              # 新建
    DocumentEngine.swift            # 新建
    QueuePump.swift                 # 三分发
    ProbeService.swift              # 音频/PDF/图片
  LiteTrans/Data/SessionStore.swift # 三会话
  LiteTrans/UI/
    AppModel.swift
    ConvertHomeView.swift
    ConvertSettingsViews.swift
    HistoryView.swift
  LiteTrans/Resources/Localizable.xcstrings
  LiteTransTests/Domain/
    DocumentTests.swift
    FfmpegArgsTests.swift
    ConvertNavigationTests.swift
    NamingTests.swift
    QueueTests.swift
.gitignore
README.md
```

---

### Task 1: 文档领域 + 多输出路径

**Files:**
- Create: `ios/LiteTrans/Domain/Document.swift`
- Modify: `ios/LiteTrans/Domain/Models.swift`（`MediaInfo` 加 `pageCount` / `pageStart` / `pageEnd`；`Job` 加 `outputPaths` / `outputKind`，默认 `[]` 与 `.downloads`）
- Modify: `ios/LiteTrans/Domain/Naming.swift`
- Modify: `ios/LiteTrans/Domain/Queue.swift`
- Modify: `ios/LiteTrans/Domain/Validate.swift`
- Modify: `ios/LiteTrans/Domain/Presets.swift`（`resolveConfig` 识别文档预设；`LiteTransError` 增加 `officeNotAvailable`）
- Test: `ios/LiteTransTests/Domain/DocumentTests.swift`
- Test: `ios/LiteTransTests/Domain/NamingTests.swift`
- Test: `ios/LiteTransTests/Domain/QueueTests.swift`

**Interfaces:**
- Consumes: 现有 `MediaInfo`、`Job`、`allocateOutputPath`、`occupiedOutputPaths`
- Produces: `enum DocumentSourceKind: String, Sendable { case image, pdf, word, excel }`；`func documentSourceKind(_ fileName: String) -> DocumentSourceKind?`；`func isDocumentPreset(_ preset: String) -> Bool`；`func documentResultIsImage(_ preset: String) -> Bool`；`func defaultDocumentPreset(_ kind: DocumentSourceKind) -> String`；`func documentExtension(_ preset: String, imageFormat: String?) -> String`；`func sameDocumentKind(existing: [String], incoming: String) -> Bool`；`func clampPageRange(start: Int, end: Int, pageCount: Int) -> (Int, Int)`；`func numberedOutputName(stem: String, index: Int, ext: String) -> String`；`func outputCount(preset: String, media: MediaInfo) -> Int`；`func ffmpegFileArg(_ path: String) -> String`；`Job.outputPaths: [String]`；`Job.outputKind: OutputKind`

- [ ] **Step 1: 写失败测试**

`ios/LiteTransTests/Domain/DocumentTests.swift`:

```swift
import Testing
@testable import LiteTransDomain

struct DocumentTests {
    @Test func kindFromExtension() {
        #expect(documentSourceKind("a.HEIC") == .image)
        #expect(documentSourceKind("a.pdf") == .pdf)
        #expect(documentSourceKind("a.docx") == .word)
        #expect(documentSourceKind("a.xlsx") == .excel)
        #expect(documentSourceKind("a.doc") == nil)
    }

    @Test func sameKindRejectsMix() {
        #expect(sameDocumentKind(existing: ["a.pdf"], incoming: "b.pdf"))
        #expect(!sameDocumentKind(existing: ["a.pdf"], incoming: "b.jpg"))
        #expect(sameDocumentKind(existing: [], incoming: "b.jpg"))
    }

    @Test func clampPages() {
        #expect(clampPageRange(start: 0, end: 99, pageCount: 12) == (1, 12))
        #expect(clampPageRange(start: 5, end: 3, pageCount: 10) == (5, 5))
    }

    @Test func defaultsAndExtensions() {
        #expect(defaultDocumentPreset(.image) == "image-jpg")
        #expect(defaultDocumentPreset(.pdf) == "pdf-image")
        #expect(documentExtension("pdf-image", imageFormat: nil) == "jpg")
        #expect(documentExtension("pdf-image", imageFormat: "png") == "png")
        #expect(documentExtension("pdf-txt", imageFormat: nil) == "txt")
        #expect(documentExtension("pdf-split", imageFormat: nil) == "pdf")
        #expect(documentResultIsImage("image-compress"))
        #expect(documentResultIsImage("pdf-image"))
        #expect(!documentResultIsImage("pdf-split"))
        #expect(isDocumentPreset("office-pdf"))
        #expect(!isDocumentPreset("mp4-h264"))
    }

    @Test func outputCountUsesPageRange() {
        let pdf = MediaInfo(
            sourceUri: "p",
            displayName: "a.pdf",
            importable: true,
            pageCount: 12,
            pageStart: 2,
            pageEnd: 5
        )
        #expect(outputCount(preset: "pdf-split", media: pdf) == 4)
        #expect(outputCount(preset: "image-jpg", media: pdf) == 1)
    }
}
```

`NamingTests.swift` 追加：

```swift
    @Test func numberedNameIsThreeDigits() {
        #expect(numberedOutputName(stem: "scan", index: 1, ext: "jpg") == "scan-001.jpg")
        #expect(numberedOutputName(stem: "scan", index: 12, ext: "pdf") == "scan-012.pdf")
    }

    @Test func ffmpegFileArgPrefixesLocalPaths() {
        #expect(ffmpegFileArg("/tmp/a.mp4") == "file:/tmp/a.mp4")
        #expect(ffmpegFileArg("file:/tmp/a.mp4") == "file:/tmp/a.mp4")
        #expect(ffmpegFileArg("pipe:1") == "pipe:1")
    }
```

`QueueTests.swift` 追加：完成任务删除全部 `outputPaths`；已取消任务若路径被后来任务占用则不删。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd ios && DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter DocumentTests`
Expected: FAIL（`documentSourceKind` 未定义）

- [ ] **Step 3: 最小实现**

`Document.swift`：

```swift
public enum DocumentSourceKind: String, Sendable, Codable {
    case image, pdf, word, excel
}

private let documentPresetIDs: Set<String> = [
    "image-jpg", "image-png", "image-webp", "image-bmp", "image-gif", "image-compress",
    "pdf-image", "pdf-txt", "pdf-compress", "pdf-split", "office-pdf",
]
private let imageResultPresets: Set<String> = [
    "image-jpg", "image-png", "image-webp", "image-bmp", "image-gif", "image-compress", "pdf-image",
]

public func documentSourceKind(_ fileName: String) -> DocumentSourceKind? {
    switch (fileName.split(separator: "/").last.map(String.init) ?? fileName)
        .split(separator: ".").last.map({ String($0).lowercased() }) ?? "" {
    case "jpg", "jpeg", "png", "webp", "bmp", "gif", "heic": return .image
    case "pdf": return .pdf
    case "docx": return .word
    case "xlsx": return .excel
    default: return nil
    }
}

public func sameDocumentKind(existing: [String], incoming: String) -> Bool {
    if existing.isEmpty { return true }
    guard let incomingKind = documentSourceKind(incoming) else { return false }
    return existing.allSatisfy { documentSourceKind($0) == incomingKind }
}

public func clampPageRange(start: Int, end: Int, pageCount: Int) -> (Int, Int) {
    let pages = max(pageCount, 1)
    let lo = min(max(start, 1), pages)
    let hi = min(max(end, lo), pages)
    return (lo, hi)
}

public func defaultDocumentPreset(_ kind: DocumentSourceKind) -> String {
    switch kind {
    case .image: return "image-jpg"
    case .pdf: return "pdf-image"
    case .word, .excel: return "office-pdf"
    }
}

public func isDocumentPreset(_ preset: String) -> Bool { documentPresetIDs.contains(preset) }
public func documentResultIsImage(_ preset: String) -> Bool { imageResultPresets.contains(preset) }

public func documentExtension(_ preset: String, imageFormat: String?) -> String {
    switch preset {
    case "image-jpg": return "jpg"
    case "image-png": return "png"
    case "image-webp": return "webp"
    case "image-bmp": return "bmp"
    case "image-gif": return "gif"
    case "image-compress": return imageFormat?.isEmpty == false ? imageFormat! : "jpg"
    case "pdf-image": return imageFormat?.isEmpty == false ? imageFormat! : "jpg"
    case "pdf-txt": return "txt"
    case "pdf-compress", "pdf-split", "office-pdf": return "pdf"
    default: return imageFormat?.isEmpty == false ? imageFormat! : "bin"
    }
}

public func outputCount(preset: String, media: MediaInfo) -> Int {
    if preset == "pdf-split" || preset == "pdf-image" {
        let (lo, hi) = clampPageRange(
            start: media.pageStart ?? 1,
            end: media.pageEnd ?? media.pageCount ?? 1,
            pageCount: media.pageCount ?? 1
        )
        return max(hi - lo + 1, 1)
    }
    return 1
}
```

`Naming.swift` 追加 `numberedOutputName` 与 `ffmpegFileArg`（Android `Naming.kt` 同语义：已是 `file:` / `pipe:` 则原样，否则加 `file:` 前缀）。

`Models.swift`：`MediaInfo` 三个可选页字段（解码缺省 `nil`）；`Job` 增加 `outputPaths: [String] = []`、`outputKind: OutputKind = .downloads`。所有现有 `Job(...)` 调用靠默认参数继续编译。`markInterrupted` 必须拷贝这两个新字段。

`Queue.swift`：`occupiedOutputPaths` 把 `outputPaths`（空则回退 `[outputPath].compactMap`）及其 `.partial` 都算占用。`outputFileDeletionPaths`：仅 `.completed` 且路径不被其它剩余任务占用时，返回全部路径 + partial。

`Validate.swift`：若 `isDocumentPreset(config.preset)`：`office-pdf` throw `LiteTransError.officeNotAvailable`，否则 `return`。

`Presets.swift`：`resolveConfig` 对文档预设：container/extension 来自 `documentExtension`；`pdf-image` 的 container 用 `config.container ?? "jpg"`。`LiteTransError.officeNotAvailable` 的 `errorDescription` 用用户可读中英，不要 dump case 名。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd ios && DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter DocumentTests --filter NamingTests --filter QueueTests`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add ios/LiteTrans/Domain ios/LiteTransTests/Domain
git commit -m "$(cat <<'EOF'
Add iOS document domain types and numbered multi-output paths.

EOF
)"
```

---

### Task 2: ConvertMode、折叠格式、存放与历史分流

**Files:**
- Modify: `ios/LiteTrans/Domain/ConvertNavigation.swift`
- Test: `ios/LiteTransTests/Domain/ConvertNavigationTests.swift`

**Interfaces:**
- Consumes: Task 1 的 `isDocumentPreset`、`documentResultIsImage`、`defaultDocumentPreset`
- Produces: `enum ConvertMode: String, CaseIterable, Sendable, Codable { case video, audio, document }`；`enum HistorySegment: String, CaseIterable, Sendable, Codable { case video, audio, document }`；`enum EngineKind: String, Sendable { case avFoundation, ffmpeg, document }`；`struct WizardSession`（`sources`、`selectedUri`、`preset`、`quality`、`size`、`output`、`showAllFormats`、`imageFormat`）；`OutputKind.documents`；`func engineKind(_ preset: String) -> EngineKind`；`func collapsedPresetCards(selectedId: String, showAll: Bool) -> [PresetCard]`；`func videoMorePresetCards() -> [PresetCard]`；`func audioPresetCards() -> [PresetCard]`；`func documentCards(for kind: DocumentSourceKind?) -> [PresetCard]`；`func isLosslessAudioPreset(_ preset: String) -> Bool`；`func isAudioHistoryJob(_ job: Job) -> Bool`；`func isDocumentHistoryJob(_ job: Job) -> Bool`；`func historySegmentFor(_ job: Job) -> HistorySegment`；`func historyJobs(_ jobs: [Job], segment: HistorySegment) -> [Job]`；`func historySegmentAfterEnqueue(mode: ConvertMode, preset: String) -> HistorySegment`；`func remainingJobsAfterClearFinished(_ jobs: [Job], segment: HistorySegment) -> [Job]`；`func outputChoices(mode: ConvertMode, preset: String) -> [OutputKind]`；`func defaultOutputKind(mode: ConvertMode, preset: String) -> OutputKind`；`func defaultSession(_ mode: ConvertMode) -> WizardSession`；`func coerceOutput(_ output: OutputTarget, mode: ConvertMode, preset: String) -> OutputTarget`

- [ ] **Step 1: 写失败测试**

追加到 `ConvertNavigationTests.swift`（保留现有测试）：

```swift
    @Test func engineKindSplitsThreeWays() {
        #expect(engineKind("mp4-h264") == .avFoundation)
        #expect(engineKind("mp4-copy") == .avFoundation)
        #expect(engineKind("mp4-h265") == .avFoundation)
        #expect(engineKind("mov-h264") == .avFoundation)
        #expect(engineKind("webm-vp9") == .ffmpeg)
        #expect(engineKind("audio-mp3") == .ffmpeg)
        #expect(engineKind("image-jpg") == .document)
        #expect(engineKind("pdf-split") == .document)
    }

    @Test func collapsedSwapsFourthWhenRareSelected() {
        let folded = collapsedPresetCards(selectedId: "webm-vp9", showAll: false).map(\.id)
        #expect(folded == ["mp4-h264", "mp4-copy", "mp4-h265", "webm-vp9"])
        #expect(collapsedPresetCards(selectedId: "mp4-h264", showAll: false).map(\.id) == primaryPresetIDs)
        #expect(collapsedPresetCards(selectedId: "gif", showAll: true).map(\.id).contains("gif"))
        #expect(collapsedPresetCards(selectedId: "gif", showAll: true).map(\.id).contains("audio-aac"))
        #expect(!collapsedPresetCards(selectedId: "gif", showAll: true).map(\.id).contains("audio-wav"))
    }

    @Test func qualityRowHidesLosslessAudioShowsMp3() {
        #expect(!shouldShowQualityRow("mp4-copy"))
        #expect(!shouldShowQualityRow("audio-wav"))
        #expect(!shouldShowQualityRow("audio-flac"))
        #expect(shouldShowQualityRow("audio-mp3"))
        #expect(shouldShowQualityRow("image-compress"))
        #expect(!shouldShowQualityRow("image-jpg"))
        #expect(!shouldShowResolution("audio-mp3"))
        #expect(!shouldShowResolution("pdf-image"))
    }

    @Test func audioDefaultsToDownloadsWithoutMusic() {
        #expect(defaultSession(.audio).preset == "audio-mp3")
        #expect(defaultSession(.audio).output.kind == .downloads)
        #expect(outputChoices(mode: .audio, preset: "audio-mp3") == [.downloads, .custom])
        #expect(outputChoices(mode: .video, preset: "mp4-h264") == [.photos, .downloads, .custom])
        #expect(outputChoices(mode: .video, preset: "audio-mp3") == [.downloads, .custom])
        #expect(outputChoices(mode: .document, preset: "image-jpg") == [.photos, .downloads, .custom])
        #expect(outputChoices(mode: .document, preset: "pdf-split") == [.documents, .downloads, .custom])
        #expect(usesPersistentSandboxOutput(.documents))
        #expect(!shouldSaveToPhotos(.documents))
    }

    @Test func historySplitsAudioExtractFromVideo() throws {
        let video = sampleJob(id: "v", status: .completed)
        var extract = sampleJob(id: "a", status: .completed)
        extract.config = OutputConfig(preset: "audio-mp3")
        var pdf = sampleJob(id: "d", status: .completed)
        pdf.config = OutputConfig(preset: "pdf-split")
        #expect(historySegmentFor(extract) == .audio)
        #expect(historySegmentFor(pdf) == .document)
        #expect(historySegmentFor(video) == .video)
        #expect(historySegmentAfterEnqueue(mode: .video, preset: "audio-mp3") == .audio)
        let mixed = [video, extract, pdf, sampleJob(id: "q", status: .queued)]
        #expect(remainingJobsAfterClearFinished(mixed, segment: .audio).map(\.id).contains("v"))
        #expect(!remainingJobsAfterClearFinished(mixed, segment: .audio).map(\.id).contains("a"))
    }

    @Test func coercePhotosAwayWhenExtractingAudio() {
        let photos = OutputTarget(kind: .photos)
        #expect(coerceOutput(photos, mode: .video, preset: "audio-mp3").kind == .downloads)
        #expect(coerceOutput(photos, mode: .video, preset: "mp4-h264").kind == .photos)
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd ios && DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter ConvertNavigationTests`
Expected: FAIL（`engineKind` 未定义）

- [ ] **Step 3: 最小实现**

`OutputKind` 增加 `documents`。

`shouldShowQualityRow`：`mp4-copy`、无损音频为假；文档仅 `image-compress` / `pdf-compress` 为真；其余（含 `audio-mp3` 与视频更多格式）为真。

`shouldShowResolution`：音频前缀、`mp4-copy`、文档预设为假。

`convertSettingsFor` 继续用这两函数。

`collapsedPresetCards`：与 Android `Wizard.kt` 相同。主四张 + 更多列表（顺序）：`mkv-copy-friendly`、`mkv-h265`、`webm-vp9`、`avi-mpeg4`、`gif`、`audio-mp3`、`audio-aac`。`showAll == false` 且 selected 不在主四张时，返回主四张的前三张 + 选中项。

音频六张卡标题：`MP3`、`M4A · AAC`、`WAV`、`FLAC`、`OGG · Opus`、`AMR`（英文 hint 可先写 Android 英译；UI 任务再接 xcstrings）。

`engineKind`：`primaryPresetIDs.contains` → `avFoundation`；`isDocumentPreset` → `document`；否则 `ffmpeg`。

`isAudioHistoryJob`：预设属于六张音频，或 `resolveConfig` 成功且 container ∈ `mp3,m4a,wav,ogg,flac,amr`。

`isDocumentHistoryJob`：`isDocumentPreset(job.config.preset)`。

`historySegmentFor`：文档优先，然后音频，否则视频。

`remainingJobsAfterClearFinished(jobs, segment)`：其它段全部保留；当前段只留 queued/running。保留无 segment 的旧函数（清全部已结束），供现有测试。

`outputChoices` / `defaultOutputKind` / `coerceOutput` 按规格表。若当前 kind 不在 choices 内，`coerceOutput` 改成 `defaultOutputKind`。

`defaultSession(.video)` preset `mp4-h264` output downloads；audio 如上；document preset `image-jpg` output photos。

`WizardSession` 全部属性 `public`，带 memberwise init。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd ios && DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter ConvertNavigationTests`
Expected: PASS（含第 1 刀旧用例）

- [ ] **Step 5: Commit**

```bash
git add ios/LiteTrans/Domain/ConvertNavigation.swift ios/LiteTransTests/Domain/ConvertNavigationTests.swift
git commit -m "$(cat <<'EOF'
Add convert modes, collapsed video formats, and history segments.

EOF
)"
```

---

### Task 3: `buildFfmpegArgs`

**Files:**
- Create: `ios/LiteTrans/Domain/FfmpegArgs.swift`
- Test: `ios/LiteTransTests/Domain/FfmpegArgsTests.swift`

**Interfaces:**
- Consumes: `validate`、`ffmpegVideoCodec`（已有，VideoToolbox 名）、`ffmpegFileArg`、`isAudioOnlyConfig`、`partialOutputPath`
- Produces: `func buildFfmpegArgs(input: String, outputPartial: String, config: ResolvedConfig, media: MediaInfo, preferHardware: Bool = true) throws -> [String]`；`func outputDurationSecs(config: ResolvedConfig, media: MediaInfo) -> Double`

硬件视频编码器必须用现有 `ffmpegVideoCodec(_:preferHardware:)`（`h264_videotoolbox` / `hevc_videotoolbox`），不要 Android 的 `mediacodec`。

- [ ] **Step 1: 写失败测试**

```swift
import Testing
@testable import LiteTransDomain

struct FfmpegArgsTests {
    private func audioMedia() -> MediaInfo {
        MediaInfo(sourceUri: "a", displayName: "a.m4a", durationSecs: 10, audioCodec: "aac", importable: true)
    }
    private func videoMedia() -> MediaInfo {
        MediaInfo(sourceUri: "v", displayName: "v.mp4", durationSecs: 10, videoCodec: "h264", audioCodec: "aac", importable: true)
    }

    @Test func wavHasNoAudioBitrate() throws {
        let config = try resolveConfig(OutputConfig(preset: "audio-wav"))
        let args = try buildFfmpegArgs(input: "/in.m4a", outputPartial: "/out.partial.wav", config: config, media: audioMedia())
        #expect(args.contains("-vn"))
        #expect(args.contains("pcm_s16le"))
        #expect(args.contains("wav"))
        #expect(!args.contains("-b:a"))
        #expect(args.contains("file:/in.m4a"))
    }

    @Test func oggUsesLibopus() throws {
        let config = try resolveConfig(OutputConfig(preset: "audio-ogg", quality: "standard"))
        let args = try buildFfmpegArgs(input: "/in.m4a", outputPartial: "/out.partial.ogg", config: config, media: audioMedia())
        #expect(args.contains("libopus"))
        #expect(args.contains("ogg"))
        #expect(args.contains("192k"))
    }

    @Test func amrBitrates() throws {
        for (quality, rate) in [("original", "12200"), ("standard", "7950"), ("small", "4750")] {
            let config = try resolveConfig(OutputConfig(preset: "audio-amr", quality: quality))
            let args = try buildFfmpegArgs(input: "/in.m4a", outputPartial: "/out.partial.amr", config: config, media: audioMedia())
            #expect(args.contains("libopencore_amrnb"))
            #expect(args.contains(rate))
            #expect(args.contains("8000"))
        }
    }

    @Test func gifUsesGifMuxer() throws {
        let config = try resolveConfig(OutputConfig(preset: "gif"))
        let args = try buildFfmpegArgs(input: "/in.mp4", outputPartial: "/out.partial.gif", config: config, media: videoMedia())
        #expect(args.contains("-an"))
        #expect(args.contains("gif"))
        #expect(engineKind("gif") == .ffmpeg)
    }

    @Test func webmVp9Software() throws {
        let config = try resolveConfig(OutputConfig(preset: "webm-vp9"))
        let args = try buildFfmpegArgs(
            input: "/in.mp4",
            outputPartial: "/out.partial.webm",
            config: config,
            media: videoMedia(),
            preferHardware: true
        )
        #expect(args.contains("libvpx-vp9"))
        #expect(args.contains("webm"))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd ios && DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter FfmpegArgsTests`
Expected: FAIL

- [ ] **Step 3: 把 Android `FfmpegArgs.kt` 译成 Swift**

对照 `android/app/src/main/java/com/videoconverter/android/domain/FfmpegArgs.kt` 全文件翻译到 `ios/LiteTrans/Domain/FfmpegArgs.swift`。必须包含：

- `startArgs`：`-hide_banner -nostats -progress pipe:1 -y`，以及 copy / hybrid seek / 普通 trim（阈值 0.05s，hybrid 1.5s）
- 音频-only：`-vn -c:a`；`amr_nb` 加 `-ar 8000 -ac 1 -b:a` 12200/7950/4750；`pcm_s16le`/`flac` 不写 `-b:a`
- gif：`-an -c:v gif` + fps/scale filter
- 视频：`ffmpegVideoCodec`；软件 crf/preset 与 Android 同表；h264/h265/mpeg4 加 `yuv420p`；h265+mp4/mov 加 `-tag:v hvc1`；mpeg4+avi 加 `-vtag xvid`
- `ffmpegMuxer`：mkv→matroska，m4a→ipod，其余同 container
- `ffmpegAudioCodec`：mp3→`libmp3lame`，opus→`libopus`，amr_nb→`libopencore_amrnb`
- `pushOutput` 最后两项为 muxer 与 `ffmpegFileArg(outputPartial)`
- `buildFfmpegArgs` 先 `validate`，失败则 throw

不要把四主视频接到 QueuePump；本任务只生产 argv。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd ios && DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter FfmpegArgsTests`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add ios/LiteTrans/Domain/FfmpegArgs.swift ios/LiteTransTests/Domain/FfmpegArgsTests.swift
git commit -m "$(cat <<'EOF'
Port FFmpeg argv builder for iOS extra video and audio presets.

EOF
)"
```

---

### Task 4: 拉取 xcframework + `FFmpegRunner` + 泵分流

**Files:**
- Create: `ios/scripts/fetch-ffmpeg.mjs`
- Create: `ios/LiteTrans/Engine/FFmpegRunner.swift`
- Modify: `ios/LiteTrans/Engine/QueuePump.swift`
- Modify: `ios/project.yml`
- Modify: `.gitignore`
- Modify: `ios/LiteTrans/Engine/VideoExporter.swift` 仅当需要共用错误类型；不要改四主预设导出逻辑

**Interfaces:**
- Consumes: `buildFfmpegArgs`、`engineKind`、`parseProgressLine`、`outputDurationSecs`、`Job.outputKind`
- Produces: `struct FFmpegRunner` / `func run(job: Job, onProgress: @escaping @Sendable (Double) -> Void) async throws`；泵在 `engineKind == .ffmpeg` 时走它；`engineKind == .avFoundation` 仍走 `VideoExporter`

- [ ] **Step 1: 写死下载脚本并拉制品**

创建 `ios/scripts/fetch-ffmpeg.mjs`，结构照抄 `android/scripts/fetch-ffmpeg.mjs`（https 下载、sha256、解压、失败非零）。

固定：

```javascript
const ARCHIVE_URL =
  "https://github.com/NooruddinLakhani/ffmpeg-kit-ios-full-gpl/archive/refs/tags/latest.zip";
```

先下载一次，把 `shasum -a 256` 写入 `EXPECTED_SHA256`。解压后把 `ffmpegkit.xcframework` 与 `libav*.xcframework` / `libsw*.xcframework` 复制到 `ios/Vendor/FFmpeg/`。用 `strings` 或 FFmpegKit 配置检查必须出现：`--enable-libx264`、`--enable-libx265`、`--enable-libvpx`、`--enable-libmp3lame`、`--enable-libopus`。缺则退出非零。

若该 zip 结构不同，只许改脚本里的解压路径，不许改成 `Process` spawn CLI。若制品不含 opencore-amr，AMR 任务允许运行时失败（历史文案来自 FFmpeg 输出）；不要因此改规格去掉 AMR。

`.gitignore` 追加：

```
ios/Vendor/FFmpeg/
ios/.ffmpeg-cache/
```

保留 `ios/Vendor/FFmpeg/.gitkeep`。

- [ ] **Step 2: XcodeGen 链接**

`project.yml` 的 `LiteTrans` target：

```yaml
    dependencies:
      - framework: Vendor/FFmpeg/ffmpegkit.xcframework
        embed: false
      - framework: Vendor/FFmpeg/libavcodec.xcframework
        embed: false
      - framework: Vendor/FFmpeg/libavformat.xcframework
        embed: false
      - framework: Vendor/FFmpeg/libavutil.xcframework
        embed: false
      - framework: Vendor/FFmpeg/libavfilter.xcframework
        embed: false
      - framework: Vendor/FFmpeg/libavdevice.xcframework
        embed: false
      - framework: Vendor/FFmpeg/libswscale.xcframework
        embed: false
      - framework: Vendor/FFmpeg/libswresample.xcframework
        embed: false
```

实际文件名以解压结果为准，缺哪个加哪个。`xcodegen generate`。静态 xcframework 一般 `embed: false`。

- [ ] **Step 3: `FFmpegRunner`**

禁止 `Process`。用 FFmpegKit 的 argv API（名称以头文件为准，常见为 `FFmpegKit.execute(withArguments:)` / `FFmpegSessionCompleteCallback`）。

伪实现必须满足：

```swift
struct FFmpegRunner: Sendable {
    func run(job: Job, onProgress: @escaping @Sendable (Double) -> Void) async throws {
        guard let outputPath = job.outputPath else { throw VideoExportError.missingOutput }
        let resolved = try resolveConfig(job.config)
        let partial = partialOutputPath(outputPath)
        let args = try buildFfmpegArgs(
            input: URL(string: job.sourceUri)?.path ?? job.sourceUri,
            outputPartial: partial,
            config: resolved,
            media: job.media
        )
        let duration = outputDurationSecs(config: resolved, media: job.media)
        try await execute(arguments: args, duration: duration, onProgress: onProgress)
        try FileManager.default.moveItem(atPath: partial, toPath: outputPath)
    }
}
```

进度：解析 `-progress pipe:1` 的 `out_time_ms` / `out_time_us`，调用现有 `parseProgressLine`。取消：调用 FFmpegKit cancel，throw `CancellationError`。非 0 退出：throw `LocalizedError`，描述不要是 case dump。

- [ ] **Step 4: `QueuePump` 按 job 分流**

`start(model:)` **不要**再把「当前 UI 的 output.kind」冻成整次运行的 `saveToPhotos`。每个 job：

```swift
switch engineKind(job.config.preset) {
case .avFoundation:
    try await exporter.export(job: current, outputURL: outputURL, saveToPhotos: shouldSaveToPhotos(current.outputKind), onProgress: ...)
case .ffmpeg:
    try await ffmpeg.run(job: current, onProgress: ...)
    if shouldSaveToPhotos(current.outputKind) { /* 与 VideoExporter 相同的 Photos 保存，完成后保留沙盒副本 */ }
case .document:
    break // Task 5 接上
}
```

取消：ffmpeg session cancel + 现有 `exportTask.cancel()`。已取消 job 不得把 `outputPath` 留给后续完成文件（沿用 `occupiedOutputPaths`）。

本任务 Domain 测试：无需 `swift test` 新用例（`engineKind` 已在 Task 2）。构建：

Run: `cd ios && node scripts/fetch-ffmpeg.mjs && xcodegen generate && xcodebuild -scheme LiteTrans -destination 'platform=iOS Simulator,name=iPhone 17' -configuration Debug build`
Expected: BUILD SUCCEEDED

- [ ] **Step 5: Commit**（不要 add `Vendor/FFmpeg/*.xcframework`）

```bash
git add ios/scripts/fetch-ffmpeg.mjs ios/Vendor/FFmpeg/.gitkeep ios/project.yml ios/LiteTrans/Engine .gitignore ios/LiteTrans.xcodeproj
git commit -m "$(cat <<'EOF'
Bundle in-process FFmpeg and route extra presets through the queue pump.

EOF
)"
```

---

### Task 5: DocumentEngine 与多输出入队

**Files:**
- Create: `ios/LiteTrans/Engine/DocumentEngine.swift`
- Modify: `ios/LiteTrans/Domain/Queue.swift`（`enqueueJobs` 对文档多输出调用 `numberedOutputName`）
- Modify: `ios/LiteTrans/Engine/QueuePump.swift`（`case .document`）
- Test: `ios/LiteTransTests/Domain/QueueTests.swift`（多输出命名与占用）

**Interfaces:**
- Consumes: Task 1 文档函数、`Job.outputPaths`
- Produces: `struct DocumentEngine` / `func run(job: Job, onProgress: @escaping @Sendable (Double) -> Void) async throws`；完成后 `job.outputPath` 为第一份，`outputPaths` 为全部

- [ ] **Step 1: 写失败测试**

```swift
    @Test func enqueuePdfSplitAllocatesNumberedPaths() throws {
        let media = MediaInfo(
            sourceUri: "p",
            displayName: "scan.pdf",
            importable: true,
            pageCount: 2,
            pageStart: 1,
            pageEnd: 2
        )
        let report = try enqueueJobs(
            sources: [media],
            config: OutputConfig(preset: "pdf-split"),
            outputDir: "/tmp",
            nextId: { "1" },
            exists: { _ in false }
        )
        #expect(report.jobs[0].outputPaths == ["/tmp/scan-001.pdf", "/tmp/scan-002.pdf"])
        #expect(report.jobs[0].outputPath == "/tmp/scan-001.pdf")
    }

    @Test func enqueueRejectsOffice() throws {
        let media = MediaInfo(sourceUri: "w", displayName: "a.docx", importable: true)
        let report = try enqueueJobs(
            sources: [media],
            config: OutputConfig(preset: "office-pdf"),
            outputDir: "/tmp",
            nextId: { "1" },
            exists: { _ in false }
        )
        #expect(report.jobs.isEmpty)
        #expect(report.skipped[0].reason != String(describing: LiteTransError.officeNotAvailable))
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd ios && DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter enqueuePdfSplitAllocatesNumberedPaths`
Expected: FAIL

- [ ] **Step 3: 实现入队 + DocumentEngine**

`enqueueJobs`：`office-pdf` / Word / Excel 源进 skipped。若 `outputCount > 1`，为每一页 `allocateOutputPath` 使用 `numberedOutputName(stem:index:ext:)` 的文件名（index 从页范围第一页对应 1 起）。`outputPath = outputPaths[0]`。单输出仍用 `stem.ext`（不要强行 `-001`）。`Job.outputKind` 暂由调用方传入：给 `enqueueJobs` 增加参数 `outputKind: OutputKind = .downloads`。

`DocumentEngine`（ImageIO + PDFKit，不 import FFmpeg）：

| preset | 行为 |
| --- | --- |
| `image-jpg/png/webp/bmp/gif` | 解码写入目标格式；GIF 静图 |
| `image-compress` | 尽量保持源 UTI；JPG/WebP/GIF 调质量；PNG/BMP 缩小边长 |
| `pdf-image` | `PDFDocument` 按页渲染 |
| `pdf-txt` | 抽文本；空 → 失败「没有可提取的文字」 |
| `pdf-split` | 每页一个 PDF |
| `pdf-compress` | 缩小内嵌图写回；做不到 → 「无法压缩此 PDF」 |
| `office-pdf` | 「后续版本提供」 |
| 加密 PDF | 「不支持加密 PDF」 |

进度：PDF 按页；单图 0→100。取消：循环中检查 `Task.isCancelled`。写入 `.partial` 再 rename。图片结果且 `outputKind == .photos` 时按第 1 刀规则存相册并保留沙盒副本。

泵 `case .document: try await documents.run(...)`。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd ios && DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter QueueTests`
Expected: PASS

再：`xcodebuild -scheme LiteTrans -destination 'platform=iOS Simulator,name=iPhone 17' build`
Expected: BUILD SUCCEEDED

- [ ] **Step 5: Commit**

```bash
git add ios/LiteTrans/Engine/DocumentEngine.swift ios/LiteTrans/Engine/QueuePump.swift ios/LiteTrans/Domain/Queue.swift ios/LiteTransTests/Domain/QueueTests.swift
git commit -m "$(cat <<'EOF'
Add PDFKit/ImageIO document engine and numbered enqueue outputs.

EOF
)"
```

---

### Task 6: 转码 UI 三分段与三会话

**Files:**
- Modify: `ios/LiteTrans/UI/AppModel.swift`
- Modify: `ios/LiteTrans/Data/SessionStore.swift`
- Modify: `ios/LiteTrans/UI/ConvertHomeView.swift`
- Modify: `ios/LiteTrans/UI/ConvertSettingsViews.swift`
- Modify: `ios/LiteTrans/Engine/ProbeService.swift`（PDF 页数、音频无轨、图片/HEIC、Office 标红）
- Modify: `ios/LiteTrans/Resources/Localizable.xcstrings`
- Test: `ios/LiteTransTests/Domain/ConvertNavigationTests.swift` 已覆盖纯函数；本任务以构建 + 手动分段为准，不写 UIKit 快照

**Interfaces:**
- Consumes: `WizardSession`、`ConvertMode`、`outputChoices`、`coerceOutput`、`collapsedPresetCards`、`audioPresetCards`、`documentCards`
- Produces: `AppModel.convertMode`、`AppModel.historySegment`、`AppModel.video` / `.audio` / `.document`；当前会话通过计算属性读写 `sources`/`preset`/`output` 等，避免改遍所有调用点

- [ ] **Step 1: SessionStore 三会话**

```swift
struct SessionSnapshot: Equatable, Codable, Sendable {
    var language: AppLanguage
    var convertMode: ConvertMode
    var historySegment: HistorySegment
    var video: WizardSession
    var audio: WizardSession
    var document: WizardSession
}
```

旧快照只有 `preset/quality/size/output/language` 时：填进 `video`，`audio`/`document` 用 `defaultSession`，`convertMode = .video`。`WizardSession.sources` 不持久化（下次启动空列表），只存 preset/quality/size/output/showAllFormats。

- [ ] **Step 2: AppModel**

持有三份 `WizardSession`。`convertMode` didSet 时 `coerceOutput` 当前会话。改 `preset` 时若视频抽音频，照片改下载。`start()`：用当前会话入队；`enqueueJobs(..., outputKind: session.output.kind)`；成功后只 `reset` 当前 mode 为 `defaultSession(mode)`（清 sources，preset/output 回到该 mode 默认）；`tab = .history`；`historySegment = historySegmentAfterEnqueue(mode, preset)`。

`add` 文档源前：`sameDocumentKind` 失败则 `message =`「请一次只加同一种文件」，不加入。源类型变化：preset 不在 `documentCards(for:)` 则改 `defaultDocumentPreset`，output 改该结果默认。

`startEnabled` 仍用当前会话的 importable/output。

- [ ] **Step 3: ConvertHomeView**

大标题下：

```swift
Picker("", selection: $model.convertMode) {
    Text(text("segment_video")).tag(ConvertMode.video)
    Text(text("segment_audio")).tag(ConvertMode.audio)
    Text(text("segment_document")).tag(ConvertMode.document)
}
.pickerStyle(.segmented)
.accessibilityLabel(text("segment_convert"))
```

入口：

- video：相册 `.videos` + 文件视频 UTType
- audio：按钮「音乐」`fileImporter` 音频 UTType；相册 `.videos`；文件音频+视频
- document：相册 `.images`；文件 image + pdf + docx/xlsx（后两者探测后标红「后续版本提供」）

footer 三句按 mode。预览：音视频现有 trim 卡；`documentSourceKind == .pdf` 用 `PDFKit.PDFView` + 页范围（夹紧后写回 `pageStart`/`pageEnd`）；图片用 `Image`；纯音频不留空视频窗。Reduce Motion：动画改 opacity。

设置披露行继续 `convertSettingsFor(preset)`。

- [ ] **Step 4: Format / Output 页**

`FormatSettingsView`：

- video：`collapsedPresetCards(selectedId:preset, showAll: showAllFormats)` + 最后一行「更多格式」/「收起」切换 `showAllFormats`
- audio：`audioPresetCards()`，无更多
- document：`documentCards(for: documentKindOf(sources))`；`pdf-image` 再列出 JPG/PNG/WebP，写入 `OutputConfig.container`（在 session 上加 `imageFormat: String = "jpg"`，入队时放进 `OutputConfig.container`）

`OutputSettingsView`：只渲染 `outputChoices(mode:preset)`。有 `.documents` 时标题「文档」，hint「文件 App 中的轻转码」。无「音乐」。

xcstrings 至少增加：`segment_video/audio/document`、`segment_convert`、`wizard_source_music`、`wizard_source_gallery_hint_audio`、`footer_audio`、`footer_document`、`format_more`、`format_less`、`output_documents`、`output_documents_hint`、`error_mixed_documents`、`error_office_later`、`error_unsupported_document`、`error_no_audio`、`error_encrypted_pdf`、`error_no_text`、`error_cannot_compress_pdf`。en + zh-Hans。

`ProbeService`：无音轨且当前/目标为音频-only → `importable=false`，error 用 `error_no_audio`。PDF 填 `pageCount` 并默认 1...N。docx/xlsx `importable=false`，`error_office_later`。`.doc`/其它 `error_unsupported_document`。

- [ ] **Step 5: 构建**

Run: `cd ios && xcodegen generate && xcodebuild -scheme LiteTrans -destination 'platform=iOS Simulator,name=iPhone 17' build`
Expected: BUILD SUCCEEDED

```bash
git add ios/LiteTrans ios/LiteTrans.xcodeproj
git commit -m "$(cat <<'EOF'
Add convert-mode sessions and segmented source pickers.

EOF
)"
```

---

### Task 7: 历史三分段与文档目录

**Files:**
- Modify: `ios/LiteTrans/UI/HistoryView.swift`
- Modify: `ios/LiteTrans/UI/AppModel.swift`（`resolvedOutputDir` 支持 `.documents`；删除用 `outputFileDeletionPaths` 全路径；清空走 segmented 函数）
- Modify: `ios/LiteTrans/Resources/Localizable.xcstrings`

**Interfaces:**
- Consumes: `historyJobs`、`remainingJobsAfterClearFinished(_:segment:)`、`HistorySegment`
- Produces: 历史页分段；「去转码」设置 `convertMode` 与对应 `historySegment`；`Documents/` 目录

- [ ] **Step 1: HistoryView**

空态与列表都按 `historyJobs(model.jobs, segment: model.historySegment)` 判断。大标题下同样三段 Picker。清空确认只清当前段。空态文案：`history_empty_video` / `_audio` / `_document`。按钮把 `tab = .convert` 且 `convertMode` 对齐当前历史段。

多输出副标题：`outputPaths.count > 1` 时显示「N 张图片」或「N 个 PDF」（pdf-split）。打开/分享第一份 `outputPath`。删除调用现有删除并传入全 `outputPaths`。

- [ ] **Step 2: Documents 目录**

`resolvedOutputDir()`：`.documents` → `FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]`（可再加子目录 `LiteTrans`，须 `createDirectory`）。`.downloads` 保持第 1 刀。文件共享已开。

- [ ] **Step 3: 构建**

Run: `cd ios && xcodebuild -scheme LiteTrans -destination 'platform=iOS Simulator,name=iPhone 17' build`
Expected: BUILD SUCCEEDED

```bash
git add ios/LiteTrans/UI/HistoryView.swift ios/LiteTrans/UI/AppModel.swift ios/LiteTrans/Resources/Localizable.xcstrings
git commit -m "$(cat <<'EOF'
Split history into video, audio, and document segments.

EOF
)"
```

---

### Task 8: README 与冒烟

**Files:**
- Modify: `README.md` iOS 段
- Modify: `ios/LiteTrans/Resources/Localizable.xcstrings`（补齐 Task 6/7 漏键）

**Interfaces:**
- Consumes: 全部
- Produces: 构建步骤含 `node ios/scripts/fetch-ffmpeg.mjs`

- [ ] **Step 1: README**

把「第 1 刀只支持四张视频主预设；音频 / 文档 / 局域网尚未提供」改成：第 2 刀支持音频六种、视频更多格式、图片与 PDF；Office / 局域网 / Live Activity 尚未提供。

构建：

```bash
cd ios && node scripts/fetch-ffmpeg.mjs && xcodegen generate && xcodebuild -scheme LiteTrans -destination 'generic/platform=iOS Simulator' build
```

冒烟清单追加：音频文件转 MP3 进下载；相册图转 JPG 进照片；小 PDF 拆分在「文件」里可见；四张主视频仍成功；Office 行标红不能开始。

- [ ] **Step 2: 回归 Domain 测试 + 模拟器构建**

Run: `cd ios && DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test`
Expected: PASS

Run: `cd ios && xcodebuild -scheme LiteTrans -destination 'platform=iOS Simulator,name=iPhone 17' build`
Expected: BUILD SUCCEEDED

可选：`xcrun simctl install` 已启动的 iPhone 17，`xcrun simctl launch booted com.videoconverter.ios`。

- [ ] **Step 3: Commit**

```bash
git add README.md ios/LiteTrans/Resources/Localizable.xcstrings
git commit -m "$(cat <<'EOF'
Document iOS slice-2 FFmpeg fetch and conversion smoke steps.

EOF
)"
```

---

## Spec coverage（自审）

| 规格项 | 任务 |
| --- | --- |
| 转码/历史分段、三会话、入队后切对应历史 | 2, 6, 7 |
| 音频六张、无音乐存放、默认下载 | 2, 6 |
| 视频更多格式 + 折叠第四张 | 2, 6 |
| 视频页抽 MP3/M4A、存放去掉照片 | 2, 6 |
| 图片六项 + PDF 四项、Office 标红 | 1, 5, 6 |
| ImageIO + PDFKit、多输出 001、一条 Job | 1, 5, 7 |
| 文档存放 Files Documents | 2, 7 |
| FFmpeg xcframework 进程内、禁止 Process | 4 |
| `buildFfmpegArgs` WAV/OGG/AMR/GIF | 3 |
| 四主预设仍 AVFoundation | 4（分流） |
| fetch 脚本不入库 | 4, 8 |
| 加密 PDF / 无文字 / 无法压缩 | 5 |
| 不用 FFmpeg 预览 | 6 |
| Live Activity / 局域网 / Office 转换 | 明确不做 |
| README 冒烟 | 8 |

## Placeholder scan

无 TBD。FFmpeg zip URL 已写死；sha256 在 Task 4 Step 1 第一次下载后写入脚本。xcframework 文件名以解压结果为准，但目录固定 `ios/Vendor/FFmpeg/`。

## Type consistency

`ConvertMode` / `HistorySegment` / `EngineKind` / `WizardSession` / `OutputKind.documents` / `Job.outputPaths` / `Job.outputKind` / `enqueueJobs(..., outputKind:)` 在后续任务中名称一致。`buildFfmpegArgs` 用 iOS `ffmpegVideoCodec`（VideoToolbox），不用 mediacodec。
