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
