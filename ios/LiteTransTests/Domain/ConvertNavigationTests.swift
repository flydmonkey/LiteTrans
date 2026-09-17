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
}
