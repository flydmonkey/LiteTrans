import Foundation
import Testing
@testable import LiteTransDomain

struct ModelsTests {
    @Test func probingDefaultsToFalse() {
        let media = MediaInfo(sourceUri: "u", displayName: "a.mp4")
        #expect(media.probing == false)
    }

    @Test func probingRoundTripsThroughCodable() throws {
        let media = MediaInfo(sourceUri: "u", displayName: "a.mp4", probing: true)
        let data = try JSONEncoder().encode(media)
        let decoded = try JSONDecoder().decode(MediaInfo.self, from: data)
        #expect(decoded.probing)
        #expect(decoded == media)
    }

    @Test func probingDecodesMissingKeyAsFalse() throws {
        let json = Data(#"{"sourceUri":"u","displayName":"a.mp4","importable":false}"#.utf8)
        let decoded = try JSONDecoder().decode(MediaInfo.self, from: json)
        #expect(decoded.probing == false)
    }

    @Test func pageFieldsDefaultAndDecodeMissingAsNil() throws {
        let media = MediaInfo(sourceUri: "u", displayName: "a.pdf")
        #expect(media.pageCount == nil)
        #expect(media.pageStart == nil)
        #expect(media.pageEnd == nil)
        let json = Data(#"{"sourceUri":"u","displayName":"a.pdf","importable":false}"#.utf8)
        let decoded = try JSONDecoder().decode(MediaInfo.self, from: json)
        #expect(decoded.pageCount == nil)
        #expect(decoded.pageStart == nil)
        #expect(decoded.pageEnd == nil)
    }

    @Test func jobDefaultsOutputKindToDownloads() {
        let job = Job(
            id: "1",
            sourceUri: "a",
            displayName: "a",
            outputPath: nil,
            status: .queued,
            progress: 0,
            error: nil,
            config: OutputConfig(),
            media: MediaInfo(sourceUri: "a", displayName: "a")
        )
        #expect(job.outputPaths.isEmpty)
        #expect(job.outputKind == .downloads)
        #expect(job.createdAtEpochMs == nil)
    }

    @Test func jobDecodesMissingOutputFields() throws {
        let json = Data(#"""
        {"id":"1","sourceUri":"a","displayName":"a","status":"queued","progress":0,"config":{"preset":"mp4-h264"},"media":{"sourceUri":"a","displayName":"a","importable":false}}
        """#.utf8)
        let decoded = try JSONDecoder().decode(Job.self, from: json)
        #expect(decoded.outputPaths.isEmpty)
        #expect(decoded.outputKind == .downloads)
        #expect(decoded.media.pageCount == nil)
        #expect(decoded.createdAtEpochMs == nil)
    }

    @Test func jobRoundTripsCreatedAt() throws {
        let job = Job(
            id: "1",
            sourceUri: "a",
            displayName: "a",
            outputPath: nil,
            status: .queued,
            progress: 0,
            error: nil,
            config: OutputConfig(),
            media: MediaInfo(sourceUri: "a", displayName: "a"),
            createdAtEpochMs: 1_779_160_980_000
        )
        let data = try JSONEncoder().encode(job)
        let decoded = try JSONDecoder().decode(Job.self, from: data)
        #expect(decoded.createdAtEpochMs == 1_779_160_980_000)
    }

    @Test func concatFieldsDecodeMissingAsEmpty() throws {
        let json = Data(#"""
        {"id":"1","sourceUri":"a","displayName":"a","status":"queued","progress":0,"config":{"preset":"mp4-h264"},"media":{"sourceUri":"a","displayName":"a","importable":false}}
        """#.utf8)
        let job = try JSONDecoder().decode(Job.self, from: json)
        #expect(job.config.concatSourceUris.isEmpty)
        #expect(job.concatMedias.isEmpty)
    }
}
