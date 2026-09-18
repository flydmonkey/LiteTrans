import XCTest
@testable import LiteTrans

final class SessionStoreTests: XCTestCase {
    private var suiteName: String!
    private var defaults: UserDefaults!
    private var store: SessionStore!

    override func setUp() {
        super.setUp()
        suiteName = "liteTrans.session.tests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        store = SessionStore(defaults: defaults)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        store = nil
        suiteName = nil
        super.tearDown()
    }

    func testLegacySnapshotMigratesIntoVideoSession() throws {
        let legacy = Data(
            """
            {"preset":"mov-h264","quality":"small","size":"720p","output":{"kind":"photos"},"language":"zhHans"}
            """.utf8
        )
        defaults.set(legacy, forKey: SessionStore.key)

        let snapshot = try XCTUnwrap(store.load())
        XCTAssertEqual(snapshot.convertMode, .video)
        XCTAssertEqual(snapshot.historySegment, .video)
        XCTAssertEqual(snapshot.language, .zhHans)
        XCTAssertEqual(snapshot.video.preset, "mov-h264")
        XCTAssertEqual(snapshot.video.quality, "small")
        XCTAssertEqual(snapshot.video.size, "720p")
        XCTAssertEqual(snapshot.video.output.kind, .photos)
        XCTAssertEqual(snapshot.audio, defaultSession(.audio))
        XCTAssertEqual(snapshot.document, defaultSession(.document))
        XCTAssertTrue(snapshot.video.sources.isEmpty)
    }

    func testSaveOmitsSourcesOnNextLaunch() throws {
        var video = defaultSession(.video)
        video.sources = [
            MediaInfo(sourceUri: "file:///tmp/a.mp4", displayName: "a.mp4", importable: true)
        ]
        video.selectedUri = "file:///tmp/a.mp4"
        video.showAllFormats = true
        video.imageFormat = "png"
        store.save(
            SessionSnapshot(
                language: .en,
                convertMode: .audio,
                historySegment: .document,
                video: video,
                audio: defaultSession(.audio),
                document: defaultSession(.document)
            )
        )

        let snapshot = try XCTUnwrap(store.load())
        XCTAssertEqual(snapshot.convertMode, .audio)
        XCTAssertEqual(snapshot.historySegment, .document)
        XCTAssertEqual(snapshot.language, .en)
        XCTAssertTrue(snapshot.video.sources.isEmpty)
        XCTAssertNil(snapshot.video.selectedUri)
        XCTAssertTrue(snapshot.video.showAllFormats)
        XCTAssertEqual(snapshot.video.imageFormat, "png")
        XCTAssertEqual(snapshot.video.preset, defaultSession(.video).preset)
    }

    func testOldDefaultVideoQualityMigratesToOriginal() throws {
        var video = defaultSession(.video)
        video.quality = "standard"
        let snapshot = SessionSnapshot(
            language: .system,
            convertMode: .video,
            historySegment: .video,
            video: video,
            audio: defaultSession(.audio),
            document: defaultSession(.document),
            schemaVersion: 1
        )
        defaults.set(try JSONEncoder().encode(snapshot), forKey: SessionStore.key)

        let loaded = try XCTUnwrap(store.load())
        XCTAssertEqual(loaded.video.quality, "original")
        XCTAssertEqual(loaded.schemaVersion, 2)
    }

    func testMissingSchemaVersionMigratesPersistedStandardVideoQuality() throws {
        var video = defaultSession(.video)
        video.quality = "standard"
        let snapshot = SessionSnapshot(
            language: .system,
            convertMode: .video,
            historySegment: .video,
            video: video,
            audio: defaultSession(.audio),
            document: defaultSession(.document)
        )
        var object = try XCTUnwrap(
            JSONSerialization.jsonObject(with: try JSONEncoder().encode(snapshot)) as? [String: Any]
        )
        object.removeValue(forKey: "schemaVersion")
        defaults.set(try JSONSerialization.data(withJSONObject: object), forKey: SessionStore.key)

        let loaded = try XCTUnwrap(store.load())
        XCTAssertEqual(loaded.video.quality, "original")
        XCTAssertEqual(loaded.schemaVersion, 2)
    }

    func testExplicitStandardVideoQualityIsKeptAfterMigration() throws {
        var video = defaultSession(.video)
        video.quality = "standard"
        store.save(
            SessionSnapshot(
                language: .system,
                convertMode: .video,
                historySegment: .video,
                video: video,
                audio: defaultSession(.audio),
                document: defaultSession(.document),
                schemaVersion: 2
            )
        )

        let loaded = try XCTUnwrap(store.load())
        XCTAssertEqual(loaded.video.quality, "standard")
        XCTAssertEqual(loaded.schemaVersion, 2)
    }
}
