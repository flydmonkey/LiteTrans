import Foundation

public let backgroundProcessingTaskIdentifier = "io.github.flydmonkey.litetrans.process"

public func shouldSubmitBackgroundProcessing(_ jobs: [Job]) -> Bool {
    jobs.contains { $0.status == .queued || $0.status == .running }
}

public func historyDeepLink(jobId: String) -> String {
    "litetrans://history?job=\(jobId)"
}

public func historyJobFromDeepLink(_ url: URL) -> String? {
    guard url.scheme == "litetrans", url.host == "history" else { return nil }
    guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
    let job = components.queryItems?.first { $0.name == "job" }?.value
    return job?.isEmpty == false ? job : nil
}
