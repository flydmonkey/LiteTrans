import Foundation

public enum LegalDocument: String, Sendable {
    case privacy
    case terms

    public var url: URL {
        URL(string: "https://flydmonkey.github.io/LiteTrans/docs/\(rawValue).html")!
    }
}
