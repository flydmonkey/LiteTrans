import Testing
@testable import LiteTransDomain

struct LiveActivityTests {
    @Test func filenameStripsPath() {
        #expect(liveActivityFilename("/tmp/假期.mp4") == "假期.mp4")
        #expect(liveActivityFilename("clip.mov") == "clip.mov")
    }

    @Test func percentClamps() {
        #expect(liveActivityPercent(-1) == 0)
        #expect(liveActivityPercent(42.9) == 42)
        #expect(liveActivityPercent(100) == 100)
        #expect(liveActivityPercent(140) == 100)
    }

    @Test func idleWhenNothingRunning() {
        let queued = lanTestJob(id: "a", status: .queued, outputPaths: ["/t/a.mp4"], displayName: "a.mp4")
        #expect(liveActivityCommand(displayedJobId: nil, jobs: [queued]) == .idle)
        #expect(liveActivityCommand(displayedJobId: "a", jobs: [queued]) == .end(jobId: "a"))
    }

    @Test func startsRunningJob() {
        var job = lanTestJob(id: "a", status: .running, outputPaths: ["/t/a.mp4"], displayName: "a.mp4")
        job.progress = 10
        let command = liveActivityCommand(displayedJobId: nil, jobs: [job])
        #expect(command == .start(LiveActivitySnapshot(jobId: "a", filename: "a.mp4", percent: 10)))
    }

    @Test func updatesSameRunningJob() {
        var job = lanTestJob(id: "a", status: .running, outputPaths: ["/t/a.mp4"], displayName: "a.mp4")
        job.progress = 40
        #expect(
            liveActivityCommand(displayedJobId: "a", jobs: [job])
                == .update(LiveActivitySnapshot(jobId: "a", filename: "a.mp4", percent: 40))
        )
    }

    @Test func switchesToNextRunningJob() {
        let done = lanTestJob(id: "a", status: .completed, outputPaths: ["/t/a.mp4"], displayName: "a.mp4")
        var next = lanTestJob(id: "b", status: .running, outputPaths: ["/t/b.mp4"], displayName: "b.mp4")
        next.progress = 1
        #expect(
            liveActivityCommand(displayedJobId: "a", jobs: [done, next])
                == .start(LiveActivitySnapshot(jobId: "b", filename: "b.mp4", percent: 1))
        )
    }
}
