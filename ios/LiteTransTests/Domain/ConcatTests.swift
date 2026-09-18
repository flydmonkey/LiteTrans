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
