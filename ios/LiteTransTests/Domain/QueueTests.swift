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

    @Test func copyRejectsResolutionChange() throws {
        let media = MediaInfo(sourceUri: "a", displayName: "a.mp4", videoCodec: "h264", importable: true)
        var resolved = try resolveConfig(OutputConfig(preset: "mp4-copy", maxWidth: 1280))
        resolved.maxWidth = 1280
        #expect(throws: LiteTransError.copyCannotChangeVideo) {
            try validate(resolved, media: media)
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
