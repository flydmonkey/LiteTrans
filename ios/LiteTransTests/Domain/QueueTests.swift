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
        let running = Job(id: "1", sourceUri: "a", displayName: "a", outputPath: nil, status: .running, progress: 10, error: nil, config: OutputConfig(), media: MediaInfo(sourceUri: "a", displayName: "a"), createdAtEpochMs: 99)
        let queued = Job(id: "2", sourceUri: "b", displayName: "b", outputPath: nil, status: .queued, progress: 0, error: nil, config: OutputConfig(), media: MediaInfo(sourceUri: "b", displayName: "b"))
        let out = markInterrupted([running, queued], interrupted: "interrupted")
        #expect(out[0].status == .failed)
        #expect(out[0].error == "interrupted")
        #expect(out[0].createdAtEpochMs == 99)
        #expect(out[1].status == .queued)
    }

    @Test func enqueueStampsCreatedAt() throws {
        let media = MediaInfo(
            sourceUri: "b",
            displayName: "b.mp4",
            videoCodec: "h264",
            audioCodec: "aac",
            importable: true
        )
        let report = try enqueueJobs(
            sources: [media],
            config: OutputConfig(),
            outputDir: "/tmp",
            nextId: { "1" },
            exists: { _ in false },
            nowMs: { 1_779_160_980_000 }
        )
        #expect(report.jobs[0].createdAtEpochMs == 1_779_160_980_000)
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

    @Test func completedDeleteRemovesAllOutputPaths() {
        let completed = Job(
            id: "old",
            sourceUri: "file:///a.pdf",
            displayName: "a.pdf",
            outputPath: "/out/a-001.pdf",
            status: .completed,
            progress: 100,
            error: nil,
            config: OutputConfig(preset: "pdf-split"),
            media: MediaInfo(sourceUri: "file:///a.pdf", displayName: "a.pdf"),
            outputPaths: ["/out/a-001.pdf", "/out/a-002.pdf"]
        )
        let deletion = outputFileDeletionPaths(job: completed, remainingJobs: [])
        #expect(deletion.contains("/out/a-001.pdf"))
        #expect(deletion.contains("/out/a-002.pdf"))
        #expect(deletion.contains("/out/a-001.partial.pdf"))
        #expect(deletion.contains("/out/a-002.partial.pdf"))
    }

    @Test func cancelledJobDoesNotDeleteOccupiedOutputPaths() {
        let cancelled = Job(
            id: "old",
            sourceUri: "file:///a.pdf",
            displayName: "a.pdf",
            outputPath: "/out/a-001.pdf",
            status: .cancelled,
            progress: 10,
            error: nil,
            config: OutputConfig(preset: "pdf-split"),
            media: MediaInfo(sourceUri: "file:///a.pdf", displayName: "a.pdf"),
            outputPaths: ["/out/a-001.pdf", "/out/a-002.pdf"]
        )
        let later = Job(
            id: "new",
            sourceUri: "file:///b.pdf",
            displayName: "b.pdf",
            outputPath: "/out/a-001.pdf",
            status: .queued,
            progress: 0,
            error: nil,
            config: OutputConfig(preset: "pdf-split"),
            media: MediaInfo(sourceUri: "file:///b.pdf", displayName: "b.pdf"),
            outputPaths: ["/out/a-001.pdf", "/out/a-002.pdf"]
        )
        #expect(occupiedOutputPaths([cancelled]).contains("/out/a-001.pdf"))
        #expect(occupiedOutputPaths([cancelled]).contains("/out/a-002.pdf"))
        #expect(occupiedOutputPaths([cancelled]).contains("/out/a-001.partial.pdf"))
        #expect(outputFileDeletionPaths(job: cancelled, remainingJobs: [later]).isEmpty)
    }

    @Test func markInterruptedCopiesOutputPathsAndKind() {
        let running = Job(
            id: "1",
            sourceUri: "a",
            displayName: "a",
            outputPath: "/out/a-001.jpg",
            status: .running,
            progress: 10,
            error: nil,
            config: OutputConfig(),
            media: MediaInfo(sourceUri: "a", displayName: "a"),
            outputPaths: ["/out/a-001.jpg", "/out/a-002.jpg"],
            outputKind: .photos
        )
        let out = markInterrupted([running], interrupted: "interrupted")
        #expect(out[0].status == .failed)
        #expect(out[0].outputPaths == ["/out/a-001.jpg", "/out/a-002.jpg"])
        #expect(out[0].outputKind == .photos)
    }

    @Test func enqueuePdfSplitAllocatesNumberedPaths() throws {
        let media = MediaInfo(
            sourceUri: "p",
            displayName: "scan.pdf",
            importable: true,
            pageCount: 2,
            pageStart: 1,
            pageEnd: 2
        )
        let report = try enqueueJobs(
            sources: [media],
            config: OutputConfig(preset: "pdf-split"),
            outputDir: "/tmp",
            nextId: { "1" },
            exists: { _ in false }
        )
        #expect(report.jobs[0].outputPaths == ["/tmp/scan-001.pdf", "/tmp/scan-002.pdf"])
        #expect(report.jobs[0].outputPath == "/tmp/scan-001.pdf")
    }

    @Test func enqueueImageCompressKeepsSourceExtension() throws {
        let media = MediaInfo(sourceUri: "p", displayName: "photo.png", importable: true)
        let report = try enqueueJobs(
            sources: [media],
            config: OutputConfig(preset: "image-compress", container: nil),
            outputDir: "/tmp",
            nextId: { "1" },
            exists: { _ in false }
        )
        #expect(report.jobs[0].outputPath == "/tmp/photo.png")
        #expect(report.jobs[0].outputPaths == ["/tmp/photo.png"])
    }

    @Test func enqueueWordOfficePdf() throws {
        let media = MediaInfo(sourceUri: "w", displayName: "a.docx", importable: true)
        let report = try enqueueJobs(
            sources: [media],
            config: OutputConfig(preset: "office-pdf"),
            outputDir: "/tmp",
            nextId: { "1" },
            exists: { _ in false }
        )
        #expect(report.jobs.map(\.id) == ["1"])
        #expect(report.jobs[0].outputPath == "/tmp/a.pdf")
        #expect(report.skipped.isEmpty)
    }

    @Test func enqueueRejectsExcelOffice() throws {
        let media = MediaInfo(sourceUri: "x", displayName: "a.xlsx", importable: true)
        let report = try enqueueJobs(
            sources: [media],
            config: OutputConfig(preset: "office-pdf"),
            outputDir: "/tmp",
            nextId: { "1" },
            exists: { _ in false }
        )
        #expect(report.jobs.isEmpty)
        #expect(report.skipped[0].reason != String(describing: LiteTransError.officeNotAvailable))
    }

    private func clip(_ uri: String, name: String, importable: Bool = true) -> MediaInfo {
        MediaInfo(
            sourceUri: uri,
            displayName: name,
            durationSecs: 2,
            videoCodec: "h264",
            width: 1280,
            height: 720,
            audioCodec: "aac",
            importable: importable
        )
    }

    @Test func concatEnqueuesOneMergedJob() throws {
        let a = clip("file:///a.mp4", name: "a.mp4")
        let b = clip("file:///b.mp4", name: "b.mp4")
        let report = try enqueueJobs(
            sources: [a, b],
            config: OutputConfig(preset: "video-concat"),
            outputDir: "/tmp",
            nextId: { "job-1" },
            exists: { _ in false }
        )
        #expect(report.jobs.count == 1)
        #expect(report.skipped.isEmpty)
        #expect(report.jobs[0].sourceUri == "file:///a.mp4")
        #expect(report.jobs[0].config.concatSourceUris == ["file:///a.mp4", "file:///b.mp4"])
        #expect(report.jobs[0].concatMedias.map(\.sourceUri) == ["file:///a.mp4", "file:///b.mp4"])
        #expect(report.jobs[0].outputPath == "/tmp/a-merged.mp4")
    }

    @Test func concatRejectsMixedUnimportable() throws {
        let report = try enqueueJobs(
            sources: [clip("file:///a.mp4", name: "a.mp4"), clip("file:///bad.mp4", name: "bad.mp4", importable: false)],
            config: OutputConfig(preset: "video-concat"),
            outputDir: "/tmp",
            nextId: { "1" },
            exists: { _ in false }
        )
        #expect(report.jobs.isEmpty)
        #expect(report.skipped.contains { $0.sourceUri == "file:///bad.mp4" })
    }

    @Test func concatRejectsOneClip() throws {
        let report = try enqueueJobs(
            sources: [clip("file:///a.mp4", name: "a.mp4")],
            config: OutputConfig(preset: "video-concat"),
            outputDir: "/tmp",
            nextId: { "1" },
            exists: { _ in false }
        )
        #expect(report.jobs.isEmpty)
        #expect(report.skipped[0].reason == LiteTransError.concatNeedsTwo.localizedDescription)
    }

    @Test func concatKeepsImportedSourcesUntilJobGone() {
        let a = clip("file:///imports/a.mp4", name: "a.mp4")
        let b = clip("file:///imports/b.mp4", name: "b.mp4")
        var config = OutputConfig(preset: "video-concat")
        config.concatSourceUris = [a.sourceUri, b.sourceUri]
        let job = Job(
            id: "1",
            sourceUri: a.sourceUri,
            displayName: a.displayName,
            outputPath: "/tmp/a-merged.mp4",
            status: .queued,
            progress: 0,
            error: nil,
            config: config,
            media: a,
            concatMedias: [a, b]
        )
        #expect(!shouldDeleteImportedSource(sourceUri: b.sourceUri, remainingJobs: [job], sessionSources: []))
        #expect(shouldDeleteImportedSource(sourceUri: b.sourceUri, remainingJobs: [], sessionSources: []))
    }
}
