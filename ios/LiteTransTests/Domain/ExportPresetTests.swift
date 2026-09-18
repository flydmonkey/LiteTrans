import Foundation
import Testing
@testable import LiteTransDomain

struct ExportPresetTests {
    @Test func copyAlwaysUsesPassthrough() {
        #expect(avExportPresetName(preset: "mp4-copy", quality: "original", size: "1080p") == "AVAssetExportPresetPassthrough")
        #expect(avExportFileType(preset: "mp4-copy") == "mp4")
    }

    @Test func qualityMapsWhenSizeIsOriginal() {
        #expect(avExportPresetName(preset: "mp4-h264", quality: "original", size: "original") == "AVAssetExportPresetHighestQuality")
        #expect(avExportPresetName(preset: "mp4-h264", quality: "standard", size: "original") == "AVAssetExportPreset1920x1080")
        #expect(avExportPresetName(preset: "mp4-h264", quality: "small", size: "original") == "AVAssetExportPreset1280x720")
    }

    @Test func sizeWinsOverQuality() {
        #expect(avExportPresetName(preset: "mp4-h264", quality: "original", size: "480p") == "AVAssetExportPreset640x480")
        #expect(avExportPresetName(preset: "mp4-h264", quality: "small", size: "1080p") == "AVAssetExportPreset1920x1080")
        #expect(avExportPresetName(preset: "mov-h264", quality: "standard", size: "720p") == "AVAssetExportPreset1280x720")
        #expect(avExportFileType(preset: "mov-h264") == "mov")
    }

    @Test func hevcUsesHighestOr1080Presets() {
        #expect(avExportPresetName(preset: "mp4-h265", quality: "original", size: "original") == "AVAssetExportPresetHEVCHighestQuality")
        #expect(avExportPresetName(preset: "mp4-h265", quality: "standard", size: "original") == "AVAssetExportPresetHEVC1920x1080")
        #expect(avExportPresetName(preset: "mp4-h265", quality: "small", size: "720p") == "AVAssetExportPresetHEVC1920x1080")
        #expect(avExportFileType(preset: "mp4-h265") == "mp4")
    }

    @Test func skippedMessageJoinsReasons() {
        #expect(skippedSourcesMessage([]) == nil)
        let skipped = [
            SkippedSource(sourceUri: "a", displayName: "a.mp4", reason: "bad"),
            SkippedSource(sourceUri: "b", displayName: "b.mp4", reason: "worse"),
        ]
        #expect(skippedSourcesMessage(skipped) == "bad\nworse")
    }

    @Test func jobArrayRoundTripsForStore() throws {
        let job = Job(
            id: "1",
            sourceUri: "file://a",
            displayName: "a.mp4",
            outputPath: "/tmp/a.mp4",
            status: .running,
            progress: 12,
            error: nil,
            config: OutputConfig(preset: "mp4-h264", quality: "standard"),
            media: MediaInfo(sourceUri: "file://a", displayName: "a.mp4", importable: true)
        )
        let data = try JSONEncoder().encode([job])
        let decoded = try JSONDecoder().decode([Job].self, from: data)
        #expect(decoded == [job])
    }
}
