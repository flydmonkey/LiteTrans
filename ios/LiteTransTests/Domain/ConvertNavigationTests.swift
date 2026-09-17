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
