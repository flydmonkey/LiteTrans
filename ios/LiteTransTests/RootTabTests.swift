import XCTest
@testable import LiteTrans

final class RootTabTests: XCTestCase {
    func testConvertRawValue() {
        XCTAssertEqual(RootTab.convert.rawValue, "convert")
    }
}
