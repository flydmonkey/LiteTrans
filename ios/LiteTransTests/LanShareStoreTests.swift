import XCTest
@testable import LiteTrans

final class LanShareStoreTests: XCTestCase {
    private var suiteName: String!
    private var defaults: UserDefaults!
    private var store: LanShareStore!

    override func setUp() {
        super.setUp()
        suiteName = "liteTrans.lanShare.tests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        store = LanShareStore(defaults: defaults)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        store = nil
        suiteName = nil
        super.tearDown()
    }

    func testMissingKeyLoadsDefaultOff() {
        let loaded = store.load()
        XCTAssertFalse(loaded.enabled)
        XCTAssertEqual(loaded.token, "")
    }

    func testSaveThenLoadRoundTripsEnabledAndToken() {
        store.save(LanShareSettings(enabled: true, token: "secret"))
        XCTAssertEqual(store.load(), LanShareSettings(enabled: true, token: "secret"))
    }

    func testSavePersistsNormalizedToken() {
        store.save(LanShareSettings(enabled: true, token: "  x  "))
        XCTAssertEqual(store.load(), LanShareSettings(enabled: true, token: "x"))
    }

    func testCorruptJsonLoadsAsDefaultOff() {
        defaults.set(Data("{not json".utf8), forKey: LanShareStore.key)
        XCTAssertEqual(store.load(), LanShareSettings())
    }
}
