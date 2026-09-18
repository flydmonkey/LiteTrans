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
