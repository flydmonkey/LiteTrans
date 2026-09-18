import Foundation
import Testing
@testable import LiteTransDomain

struct BackgroundProcessingTests {
    @Test func processingTaskIdentifierIsStable() {
        #expect(backgroundProcessingTaskIdentifier == "io.github.flydmonkey.litetrans.process")
    }

    @Test func submitsWhenQueuedOrRunning() {
        let queued = lanTestJob(id: "a", status: .queued, outputPaths: ["/t/a.mp4"], displayName: "a.mp4")
        let running = lanTestJob(id: "b", status: .running, outputPaths: ["/t/b.mp4"], displayName: "b.mp4")
        let done = lanTestJob(id: "c", status: .completed, outputPaths: ["/t/c.mp4"], displayName: "c.mp4")
        #expect(shouldSubmitBackgroundProcessing([queued]))
        #expect(shouldSubmitBackgroundProcessing([running]))
        #expect(!shouldSubmitBackgroundProcessing([done]))
        #expect(!shouldSubmitBackgroundProcessing([]))
    }

    @Test func historyDeepLinkRoundTripsJobId() {
        #expect(historyDeepLink(jobId: "abc") == "litetrans://history?job=abc")
        #expect(historyJobFromDeepLink(URL(string: "litetrans://history?job=abc")!) == "abc")
        #expect(historyJobFromDeepLink(URL(string: "litetrans://history")!) == nil)
        #expect(historyJobFromDeepLink(URL(string: "https://example.com")!) == nil)
    }
}
