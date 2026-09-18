import Foundation
import LiteTransActivityKit
@preconcurrency import ActivityKit

@MainActor
final class LiveActivityController {
    private var displayedJobId: String?

    func sync(jobs: [Job]) {
        switch liveActivityCommand(displayedJobId: displayedJobId, jobs: jobs) {
        case .idle:
            break
        case .start(let snapshot):
            displayedJobId = snapshot.jobId
            Task { await start(snapshot) }
        case .update(let snapshot):
            Task { await update(snapshot) }
        case .end(let jobId):
            displayedJobId = nil
            Task { await end(jobId) }
        }
    }

    private func start(_ snapshot: LiveActivitySnapshot) async {
        await endAll()
        let attributes = ConvertActivityAttributes(jobId: snapshot.jobId)
        let state = ConvertActivityAttributes.ContentState(filename: snapshot.filename, percent: snapshot.percent)
        let content = ActivityContent(state: state, staleDate: nil)
        do {
            _ = try Activity.request(attributes: attributes, content: content, pushType: nil)
        } catch {
            displayedJobId = nil
        }
    }

    private func update(_ snapshot: LiveActivitySnapshot) async {
        let state = ConvertActivityAttributes.ContentState(filename: snapshot.filename, percent: snapshot.percent)
        let content = ActivityContent(state: state, staleDate: nil)
        let matches = Activity<ConvertActivityAttributes>.activities.filter { $0.attributes.jobId == snapshot.jobId }
        if matches.isEmpty {
            await start(snapshot)
            return
        }
        for activity in matches {
            await activity.update(content)
        }
    }

    private func end(_ jobId: String) async {
        let content: ActivityContent<ConvertActivityAttributes.ContentState>? = nil
        for activity in Activity<ConvertActivityAttributes>.activities where activity.attributes.jobId == jobId {
            await activity.end(content, dismissalPolicy: .immediate)
        }
        if Activity<ConvertActivityAttributes>.activities.isEmpty == false {
            await endAll()
        }
    }

    private func endAll() async {
        let content: ActivityContent<ConvertActivityAttributes.ContentState>? = nil
        for activity in Activity<ConvertActivityAttributes>.activities {
            await activity.end(content, dismissalPolicy: .immediate)
        }
    }
}
