import Foundation
import Testing
@testable import LiteTransDomain

struct ConvertNavigationTests {
    @Test func settingsHideQualityAndSizeForCopy() {
        #expect(convertSettingsFor(preset: "mp4-copy") == [.format, .output])
        #expect(convertSettingsFor(preset: "mp4-h264") == [.format, .quality, .size, .output])
    }

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
        #expect(popMineBack(.lan) == .root)
        #expect(popMineBack(.root) == nil)
    }

    @Test func photosKindIsTheOnlySaveToPhotosDestination() {
        #expect(shouldSaveToPhotos(.photos))
        #expect(!shouldSaveToPhotos(.downloads))
        #expect(!shouldSaveToPhotos(.custom))
    }

    @Test func terminalJobsIgnoreProgressUpdates() {
        #expect(shouldApplyJobProgress(.queued))
        #expect(shouldApplyJobProgress(.running))
        #expect(!shouldApplyJobProgress(.completed))
        #expect(!shouldApplyJobProgress(.failed))
        #expect(!shouldApplyJobProgress(.cancelled))
    }

    @Test func appLanguageHasRequiredCases() {
        #expect(AppLanguage.system.rawValue == "system")
        #expect(AppLanguage.zhHans.rawValue == "zhHans")
        #expect(AppLanguage.zhHant.rawValue == "zhHant")
        #expect(AppLanguage.en.rawValue == "en")
        #expect(AppLanguage.ja.rawValue == "ja")
        #expect(AppLanguage.ko.rawValue == "ko")
    }

    @Test func runningJobRowOnlyCancels() {
        #expect(jobRowActions(.running) == [.cancel])
        #expect(jobRowActions(.queued) == [.cancel])
    }

    @Test func completedJobRowCanOpenShareRenameAndDelete() {
        #expect(jobRowActions(.completed) == [.open, .share, .rename, .delete])
        #expect(historyContextMenuActions(.completed) == [.share, .rename])
        #expect(historyContextMenuActions(.failed) == [.retry])
        #expect(historyContextMenuActions(.cancelled) == [.retry])
        #expect(historyContextMenuActions(.queued) == [])
        #expect(historyContextMenuActions(.running) == [])
    }

    @Test func failedAndCancelledJobRowsRetryOrDelete() {
        #expect(jobRowActions(.failed) == [.retry, .delete])
        #expect(jobRowActions(.cancelled) == [.retry, .delete])
    }

    @Test func clearFinishedKeepsOnlyQueuedAndRunning() {
        let jobs = [
            sampleJob(id: "q", status: .queued),
            sampleJob(id: "r", status: .running),
            sampleJob(id: "c", status: .completed),
            sampleJob(id: "f", status: .failed),
            sampleJob(id: "x", status: .cancelled),
        ]
        #expect(remainingJobsAfterClearFinished(jobs).map(\.id) == ["q", "r"])
    }

    @Test func resolvedLocaleIdentifierMapsAllLanguages() {
        #expect(resolvedLocaleIdentifier(.system) == nil)
        #expect(resolvedLocaleIdentifier(.zhHans) == "zh-Hans")
        #expect(resolvedLocaleIdentifier(.zhHant) == "zh-Hant")
        #expect(resolvedLocaleIdentifier(.en) == "en")
        #expect(resolvedLocaleIdentifier(.ja) == "ja")
        #expect(resolvedLocaleIdentifier(.ko) == "ko")
    }

    @Test func historyFileOpsSkipScopedAccessForPhotosAndDownloads() {
        #expect(historyOutputAccess(kind: .photos, alreadyAccessing: false) == .none)
        #expect(historyOutputAccess(kind: .photos, alreadyAccessing: true) == .none)
        #expect(historyOutputAccess(kind: .downloads, alreadyAccessing: false) == .none)
        #expect(historyOutputAccess(kind: .downloads, alreadyAccessing: true) == .none)
    }

    @Test func historyFileOpsReaccessCustomFolderUnlessAlreadyOpen() {
        #expect(historyOutputAccess(kind: .custom, alreadyAccessing: false) == .startThenStop)
        #expect(historyOutputAccess(kind: .custom, alreadyAccessing: true) == .reuseExisting)
    }

    @Test func historyDateStaysOnItsOwnLabel() {
        var components = DateComponents()
        components.calendar = Calendar(identifier: .gregorian)
        components.timeZone = TimeZone(secondsFromGMT: 0)
        components.year = 2026
        components.month = 9
        components.day = 18
        components.hour = 3
        components.minute = 43
        let date = components.date!
        let epochMs = Int64((date.timeIntervalSince1970 * 1000).rounded())
        let utc = TimeZone(secondsFromGMT: 0)!
        #expect(formatHistoryDate(epochMs, timeZone: utc) == "2026-09-18 03:43")
        #expect(historyDateLabel(nil) == nil)
        #expect(historyDateLabel(epochMs, timeZone: utc) == "2026-09-18 03:43")
    }

    @Test func copyPresetDisallowsTrim() {
        #expect(!allowsTrim(preset: "mp4-copy"))
        #expect(allowsTrim(preset: "mp4-h264"))
        #expect(allowsTrim(preset: "mp4-h265"))
        #expect(allowsTrim(preset: "mov-h264"))
    }

    @Test func photosDestinationUsesPersistentSandboxOutput() {
        #expect(usesPersistentSandboxOutput(.photos))
        #expect(usesPersistentSandboxOutput(.downloads))
        #expect(!usesPersistentSandboxOutput(.custom))
    }

    @Test func engineKindSplitsThreeWays() {
        #expect(engineKind("mp4-h264") == .avFoundation)
        #expect(engineKind("mp4-copy") == .avFoundation)
        #expect(engineKind("mp4-h265") == .avFoundation)
        #expect(engineKind("mov-h264") == .avFoundation)
        #expect(engineKind("webm-vp9") == .ffmpeg)
        #expect(engineKind("audio-mp3") == .ffmpeg)
        #expect(engineKind("image-jpg") == .document)
        #expect(engineKind("pdf-split") == .document)
        #expect(engineKind("office-pdf") == .document)
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

    @Test func videoQualityDefaultsToOriginal() {
        #expect(defaultSession(.video).quality == "original")
        #expect(defaultSession(.audio).quality == "standard")
        #expect(defaultSession(.document).quality == "standard")
    }

    @Test func formatCardsUseLocalizationKeysForHints() {
        #expect(collapsedPrimaryPresets().first { $0.id == "mp4-h264" }?.hintKey == "preset_mp4_h264_desc")
        #expect(audioPresetCards().first { $0.id == "audio-mp3" }?.hintKey == "preset_audio_mp3_audio_desc")
        #expect(videoMorePresetCards().first { $0.id == "gif" }?.hintKey == "preset_gif_desc")
    }

    @Test func documentCardsUseTitleKeys() {
        #expect(documentCards(for: .pdf).first { $0.id == "pdf-image" }?.titleKey == "preset_pdf_image_title")
        #expect(documentCards(for: .pdf).first { $0.id == "pdf-txt" }?.titleKey == "preset_pdf_txt_title")
        #expect(documentCards(for: .pdf).first { $0.id == "pdf-compress" }?.titleKey == "preset_pdf_compress_title")
        #expect(documentCards(for: .pdf).first { $0.id == "pdf-split" }?.titleKey == "preset_pdf_split_title")
        #expect(documentCards(for: .word).first { $0.id == "office-pdf" }?.titleKey == "preset_office_pdf_title")
        #expect(documentCards(for: .image).first { $0.id == "image-compress" }?.titleKey == "preset_image_compress_title")
        #expect(documentCards(for: .image).first { $0.id == "image-jpg" }?.titleKey == nil)
    }

    @Test func audioDefaultsToDownloadsWithoutMusic() {
        #expect(defaultSession(.audio).preset == "audio-mp3")
        #expect(defaultSession(.audio).output.kind == .downloads)
        #expect(outputChoices(mode: .audio, preset: "audio-mp3") == [.downloads, .custom])
        #expect(outputChoices(mode: .video, preset: "mp4-h264") == [.photos, .downloads, .custom])
        #expect(outputChoices(mode: .video, preset: "audio-mp3") == [.downloads, .custom])
        #expect(!outputChoices(mode: .video, preset: "webm-vp9").contains(.photos))
        #expect(outputChoices(mode: .video, preset: "webm-vp9") == [.downloads, .custom])
        #expect(outputChoices(mode: .video, preset: "mkv-copy-friendly") == [.downloads, .custom])
        #expect(outputChoices(mode: .video, preset: "mkv-h265") == [.downloads, .custom])
        #expect(outputChoices(mode: .video, preset: "avi-mpeg4") == [.downloads, .custom])
        #expect(outputChoices(mode: .video, preset: "gif").contains(.photos))
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

    @Test func swipeMovesConvertAndHistoryTabsWithoutWrapping() {
        #expect(nextCase(ConvertMode.video) == .audio)
        #expect(nextCase(ConvertMode.audio) == .document)
        #expect(nextCase(ConvertMode.document) == nil)
        #expect(previousCase(ConvertMode.video) == nil)
        #expect(previousCase(ConvertMode.audio) == .video)
        #expect(previousCase(ConvertMode.document) == .audio)
        #expect(nextCase(HistorySegment.video) == .audio)
        #expect(nextCase(HistorySegment.audio) == .document)
        #expect(nextCase(HistorySegment.document) == nil)
        #expect(previousCase(HistorySegment.video) == nil)
        #expect(previousCase(HistorySegment.document) == .audio)
    }

    @Test func relocatesSandboxOutputAfterContainerUUIDChange() {
        let oldApp = "/var/mobile/Containers/Data/Application/AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"
        let newApp = "/var/mobile/Containers/Data/Application/BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB"
        let documents = "\(newApp)/Documents"
        let support = "\(newApp)/Library/Application Support"
        let stored = "\(oldApp)/Documents/Downloads/clip.mp4"
        #expect(relocatedSandboxPath(stored, documentsDir: documents, applicationSupportDir: support) == "\(documents)/Downloads/clip.mp4")
        #expect(
            relocatedSandboxPath(
                "\(oldApp)/Library/Application Support/Imports/in.mov",
                documentsDir: documents,
                applicationSupportDir: support
            ) == "\(support)/Imports/in.mov"
        )
        let icloud = "/private/var/mobile/Library/Mobile Documents/com~apple~CloudDocs/out/a.mp4"
        #expect(relocatedSandboxPath(icloud, documentsDir: documents, applicationSupportDir: support) == icloud)
        var job = sampleJob(id: "v", status: .completed)
        job.outputPath = stored
        job.outputPaths = [stored, "\(oldApp)/Documents/Downloads/clip-002.mp4"]
        let relocated = relocateJobSandboxPaths(job, documentsDir: documents, applicationSupportDir: support)
        #expect(relocated.outputPath == "\(documents)/Downloads/clip.mp4")
        #expect(relocated.outputPaths == ["\(documents)/Downloads/clip.mp4", "\(documents)/Downloads/clip-002.mp4"])
    }

    @Test func coercePhotosAwayWhenExtractingAudio() {
        let photos = OutputTarget(kind: .photos)
        #expect(coerceOutput(photos, mode: .video, preset: "audio-mp3").kind == .downloads)
        #expect(coerceOutput(photos, mode: .video, preset: "mp4-h264").kind == .photos)
        #expect(coerceOutput(photos, mode: .video, preset: "webm-vp9").kind == .downloads)
    }
}

private func sampleJob(id: String, status: JobStatus) -> Job {
    Job(
        id: id,
        sourceUri: "file:///a.mp4",
        displayName: "a.mp4",
        outputPath: "/tmp/\(id).mp4",
        status: status,
        progress: 0,
        error: nil,
        config: OutputConfig(),
        media: MediaInfo(sourceUri: "file:///a.mp4", displayName: "a.mp4")
    )
}
