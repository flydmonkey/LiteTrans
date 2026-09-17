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
}
