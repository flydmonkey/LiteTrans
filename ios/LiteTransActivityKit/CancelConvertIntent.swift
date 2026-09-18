import ActivityKit
import AppIntents
import Foundation

public struct CancelConvertIntent: LiveActivityIntent {
    public static let title: LocalizedStringResource = "Cancel conversion"
    public static let openAppWhenRun = true

    @Parameter(title: "Job")
    public var jobId: String

    public init() {
        jobId = ""
    }

    public init(jobId: String) {
        self.jobId = jobId
    }

    public func perform() async throws -> some IntentResult {
        UserDefaults.standard.set(jobId, forKey: pendingCancelJobDefaultsKey)
        return .result()
    }
}
