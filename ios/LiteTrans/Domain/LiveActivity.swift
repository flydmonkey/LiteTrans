import Foundation

public struct LiveActivitySnapshot: Equatable, Sendable {
    public var jobId: String
    public var filename: String
    public var percent: Int

    public init(jobId: String, filename: String, percent: Int) {
        self.jobId = jobId
        self.filename = filename
        self.percent = percent
    }
}

public enum LiveActivityCommand: Equatable, Sendable {
    case idle
    case start(LiveActivitySnapshot)
    case update(LiveActivitySnapshot)
    case end(jobId: String)
}

public func liveActivityFilename(_ displayName: String) -> String {
    URL(fileURLWithPath: displayName).lastPathComponent
}

public func liveActivityPercent(_ progress: Double) -> Int {
    Int(min(max(progress, 0), 100))
}

public func liveActivitySnapshot(_ job: Job) -> LiveActivitySnapshot {
    LiveActivitySnapshot(
        jobId: job.id,
        filename: liveActivityFilename(job.displayName),
        percent: liveActivityPercent(job.progress)
    )
}

public func liveActivityCommand(displayedJobId: String?, jobs: [Job]) -> LiveActivityCommand {
    let running = jobs.first { $0.status == .running }
    if let running {
        let snapshot = liveActivitySnapshot(running)
        if displayedJobId == running.id {
            return .update(snapshot)
        }
        return .start(snapshot)
    }
    if let displayedJobId {
        return .end(jobId: displayedJobId)
    }
    return .idle
}
