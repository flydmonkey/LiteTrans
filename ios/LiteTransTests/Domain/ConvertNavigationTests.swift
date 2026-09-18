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

    @Test func unsupportedLanguagesFallBackToEnglishLocale() {
        #expect(resolvedLocaleIdentifier(.system) == nil)
        #expect(resolvedLocaleIdentifier(.zhHans) == "zh-Hans")
        #expect(resolvedLocaleIdentifier(.en) == "en")
        #expect(resolvedLocaleIdentifier(.zhHant) == "en")
        #expect(resolvedLocaleIdentifier(.ja) == "en")
        #expect(resolvedLocaleIdentifier(.ko) == "en")
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
