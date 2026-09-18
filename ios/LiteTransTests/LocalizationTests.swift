import XCTest
@testable import LiteTrans

final class LocalizationTests: XCTestCase {
    func testConvertTabSwitchesWithLocale() {
        XCTAssertEqual(localizedText("tab_convert", locale: Locale(identifier: "en")), "Convert")
        XCTAssertEqual(localizedText("tab_convert", locale: Locale(identifier: "zh-Hans")), "转码")
        XCTAssertEqual(localizedText("tab_convert", locale: Locale(identifier: "zh-Hant")), "轉碼")
        XCTAssertEqual(localizedText("tab_convert", locale: Locale(identifier: "ja")), "変換")
        XCTAssertEqual(localizedText("tab_convert", locale: Locale(identifier: "ko")), "변환")
    }

    func testLanguageMappingUsesRealLocaleIdentifiers() {
        XCTAssertEqual(resolvedLocaleIdentifier(.zhHant), "zh-Hant")
        XCTAssertEqual(resolvedLocaleIdentifier(.ja), "ja")
        XCTAssertEqual(resolvedLocaleIdentifier(.ko), "ko")
        XCTAssertEqual(localizationLocale(for: .en).identifier, "en")
    }

    func testMineLanSwitchesWithLocale() {
        XCTAssertEqual(localizedText("mine_lan", locale: Locale(identifier: "en")), "LAN access")
        XCTAssertEqual(localizedText("mine_lan", locale: Locale(identifier: "zh-Hans")), "局域网访问")
        XCTAssertEqual(localizedText("mine_lan", locale: Locale(identifier: "zh-Hant")), "區域網路存取")
        XCTAssertEqual(localizedText("mine_lan", locale: Locale(identifier: "ja")), "LANアクセス")
        XCTAssertEqual(localizedText("mine_lan", locale: Locale(identifier: "ko")), "LAN 액세스")
        XCTAssertEqual(localizedText("lan_status_on", locale: Locale(identifier: "zh-Hans")), "开启")
        XCTAssertEqual(localizedText("lan_status_off", locale: Locale(identifier: "zh-Hans")), "关闭")
        XCTAssertEqual(localizedText("lan_status_on", locale: Locale(identifier: "en")), "On")
        XCTAssertEqual(localizedText("lan_status_off", locale: Locale(identifier: "en")), "Off")
    }

    func testFormatHintsSwitchWithLocale() {
        XCTAssertEqual(localizedText("preset_mp4_h264_desc", locale: Locale(identifier: "en")), "Opens on almost every device")
        XCTAssertEqual(localizedText("preset_mp4_h264_desc", locale: Locale(identifier: "zh-Hans")), "几乎所有设备都能打开")
        XCTAssertEqual(localizedText("preset_audio_mp3_audio_desc", locale: Locale(identifier: "zh-Hans")), "兼容性最好")
        XCTAssertEqual(localizedText("preset_audio_mp3_audio_desc", locale: Locale(identifier: "ja")), "互換性が最も高い")
    }

    func testDocumentFormatTitlesSwitchWithLocale() {
        XCTAssertEqual(localizedText("preset_pdf_image_title", locale: Locale(identifier: "en")), "To images")
        XCTAssertEqual(localizedText("preset_pdf_image_title", locale: Locale(identifier: "zh-Hans")), "转图片")
        XCTAssertEqual(localizedText("preset_pdf_txt_title", locale: Locale(identifier: "zh-Hans")), "转 TXT")
        XCTAssertEqual(localizedText("preset_pdf_compress_title", locale: Locale(identifier: "zh-Hans")), "压缩")
        XCTAssertEqual(localizedText("preset_pdf_split_title", locale: Locale(identifier: "zh-Hans")), "拆分")
        XCTAssertEqual(localizedText("preset_image_compress_title", locale: Locale(identifier: "zh-Hans")), "压缩")
        XCTAssertEqual(localizedText("preset_office_pdf_title", locale: Locale(identifier: "zh-Hans")), "转 PDF")
        XCTAssertEqual(localizedText("preset_office_pdf_title", locale: Locale(identifier: "en")), "To PDF")
        XCTAssertEqual(localizedText("error_cannot_convert_document", locale: Locale(identifier: "zh-Hans")), "无法转换此文档")
    }
}
