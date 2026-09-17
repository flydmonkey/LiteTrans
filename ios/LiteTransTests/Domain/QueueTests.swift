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

    @Test func cancelledJobOccupiesPathSoReenqueueGetsAnother() throws {
        let media = MediaInfo(
            sourceUri: "file:///a.mp4",
            displayName: "a.mp4",
            videoCodec: "h264",
            importable: true
        )
        var ids = ["old", "new"].makeIterator()
        let first = try enqueueJobs(
            sources: [media],
            config: OutputConfig(),
            outputDir: "/tmp",
            nextId: { ids.next()! },
            exists: { _ in false }
        )
        #expect(first.jobs[0].outputPath == "/tmp/a.mp4")

        var cancelled = first.jobs[0]
        cancelled.status = .cancelled
        let occupied = occupiedOutputPaths([cancelled])
        #expect(occupied.contains("/tmp/a.mp4"))
        #expect(occupied.contains("/tmp/a.partial.mp4"))

        let second = try enqueueJobs(
            sources: [media],
            config: OutputConfig(),
            outputDir: "/tmp",
            nextId: { ids.next()! },
            exists: { _ in false },
            existingJobs: [cancelled]
        )
        #expect(second.jobs[0].outputPath == "/tmp/a-1.mp4")
        #expect(second.jobs[0].outputPath != cancelled.outputPath)

        let deletion = outputFileDeletionPaths(job: cancelled, remainingJobs: second.jobs)
        #expect(!deletion.contains("/tmp/a-1.mp4"))
        #expect(!deletion.contains("/tmp/a.mp4"))
    }

    @Test func completedDeleteRemovesUnreferencedPathOnly() {
        let completed = Job(
            id: "old",
            sourceUri: "file:///a.mp4",
            displayName: "a.mp4",
            outputPath: "/out/a.mp4",
            status: .completed,
            progress: 100,
            error: nil,
            config: OutputConfig(),
            media: MediaInfo(sourceUri: "file:///a.mp4", displayName: "a.mp4")
        )
        let other = Job(
            id: "new",
            sourceUri: "file:///a.mp4",
            displayName: "a.mp4",
            outputPath: "/out/a.mp4",
            status: .completed,
            progress: 100,
            error: nil,
            config: OutputConfig(),
            media: MediaInfo(sourceUri: "file:///a.mp4", displayName: "a.mp4")
        )
        #expect(outputFileDeletionPaths(job: completed, remainingJobs: [other]).isEmpty)
        #expect(outputFileDeletionPaths(job: completed, remainingJobs: []).contains("/out/a.mp4"))
    }

    @Test func enqueueSkipUsesLocalizedErrorDescription() throws {
        let media = MediaInfo(sourceUri: "a", displayName: "a.mp4", importable: true)
        let report = try enqueueJobs(
            sources: [media],
            config: OutputConfig(preset: "mp4-copy"),
            outputDir: "/tmp",
            nextId: { "1" },
            exists: { _ in false }
        )
        #expect(report.jobs.isEmpty)
        #expect(report.skipped.count == 1)
        #expect(report.skipped[0].reason == LiteTransError.noVideoForCopy.localizedDescription)
        #expect(report.skipped[0].reason != String(describing: LiteTransError.noVideoForCopy))
    }

    @Test func deleteImportedSourceOnlyWhenUnreferenced() {
        let source = MediaInfo(sourceUri: "file:///imports/a.mp4", displayName: "a.mp4")
        let job = Job(
            id: "1",
            sourceUri: source.sourceUri,
            displayName: source.displayName,
            outputPath: "/out/a.mp4",
            status: .completed,
            progress: 100,
            error: nil,
            config: OutputConfig(),
            media: source
        )
        #expect(!shouldDeleteImportedSource(sourceUri: source.sourceUri, remainingJobs: [job], sessionSources: []))
        #expect(!shouldDeleteImportedSource(sourceUri: source.sourceUri, remainingJobs: [], sessionSources: [source]))
        #expect(shouldDeleteImportedSource(sourceUri: source.sourceUri, remainingJobs: [], sessionSources: []))
    }

    @Test func copyPresetDoesNotKeepExistingTrim() {
        let media = MediaInfo(
            sourceUri: "a",
            displayName: "a.mp4",
            videoCodec: "h264",
            importable: true,
            trimStartSecs: 1,
            trimEndSecs: 4
        )
        let copied = configForSource(OutputConfig(preset: "mp4-copy"), media: media)
        #expect(copied.trimStartSecs == nil)
        #expect(copied.trimEndSecs == nil)
        let encoded = configForSource(OutputConfig(preset: "mp4-h264"), media: media)
        #expect(encoded.trimStartSecs == 1)
        #expect(encoded.trimEndSecs == 4)
    }
}
