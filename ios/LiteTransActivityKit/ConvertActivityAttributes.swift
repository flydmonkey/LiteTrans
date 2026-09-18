import ActivityKit
import Foundation

public struct ConvertActivityAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable, Sendable {
        public var filename: String
        public var percent: Int

        public init(filename: String, percent: Int) {
            self.filename = filename
            self.percent = percent
        }
    }

    public var jobId: String

    public init(jobId: String) {
        self.jobId = jobId
    }
}

public let pendingCancelJobDefaultsKey = "liteTrans.pendingCancelJobId"
