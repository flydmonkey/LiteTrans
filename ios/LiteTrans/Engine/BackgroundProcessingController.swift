import BackgroundTasks
import Foundation

@MainActor
final class BackgroundProcessingController {
    func register(handler: @escaping @MainActor (BGTask) -> Void) {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: backgroundProcessingTaskIdentifier,
            using: nil
        ) { task in
            Task { @MainActor in
                handler(task)
            }
        }
    }

    func sync(jobs: [Job], sceneActive: Bool) {
        if sceneActive || !shouldSubmitBackgroundProcessing(jobs) {
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: backgroundProcessingTaskIdentifier)
            return
        }
        let request = BGProcessingTaskRequest(identifier: backgroundProcessingTaskIdentifier)
        request.requiresNetworkConnectivity = false
        request.requiresExternalPower = false
        try? BGTaskScheduler.shared.submit(request)
    }
}

final class BackgroundTaskCompletion: @unchecked Sendable {
    private var done = false
    private let lock = NSLock()

    func finish(_ task: BGTask, success: Bool) {
        lock.lock()
        defer { lock.unlock() }
        guard !done else { return }
        done = true
        task.setTaskCompleted(success: success)
    }
}
