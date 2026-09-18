import Foundation
import Testing
@testable import LiteTransDomain

struct LegalTests {
    @Test func privacyAndTermsUsePublicHttpsPages() {
        #expect(LegalDocument.privacy.url.absoluteString == "https://flydmonkey.github.io/LiteTrans/docs/privacy.html")
        #expect(LegalDocument.terms.url.absoluteString == "https://flydmonkey.github.io/LiteTrans/docs/terms.html")
        #expect(LegalDocument.privacy.url != LegalDocument.terms.url)
    }
}
