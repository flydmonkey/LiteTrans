public enum DocumentSourceKind: String, Sendable, Codable {
    case image, pdf, word, excel
}

private let documentPresetIDs: Set<String> = [
    "image-jpg", "image-png", "image-webp", "image-bmp", "image-gif", "image-compress",
    "pdf-image", "pdf-txt", "pdf-compress", "pdf-split", "office-pdf",
]
private let imageResultPresets: Set<String> = [
    "image-jpg", "image-png", "image-webp", "image-bmp", "image-gif", "image-compress", "pdf-image",
]

public func documentSourceKind(_ fileName: String) -> DocumentSourceKind? {
    switch (fileName.split(separator: "/").last.map(String.init) ?? fileName)
        .split(separator: ".").last.map({ String($0).lowercased() }) ?? "" {
    case "jpg", "jpeg", "png", "webp", "bmp", "gif", "heic": return .image
    case "pdf": return .pdf
    case "docx": return .word
    case "xlsx": return .excel
    default: return nil
    }
}

public func sameDocumentKind(existing: [String], incoming: String) -> Bool {
    if existing.isEmpty { return true }
    guard let incomingKind = documentSourceKind(incoming) else { return false }
    return existing.allSatisfy { documentSourceKind($0) == incomingKind }
}

public func clampPageRange(start: Int, end: Int, pageCount: Int) -> (Int, Int) {
    let pages = max(pageCount, 1)
    let lo = min(max(start, 1), pages)
    let hi = min(max(end, lo), pages)
    return (lo, hi)
}

public func defaultDocumentPreset(_ kind: DocumentSourceKind) -> String {
    switch kind {
    case .image: return "image-jpg"
    case .pdf: return "pdf-image"
    case .word, .excel: return "office-pdf"
    }
}

public func isDocumentPreset(_ preset: String) -> Bool { documentPresetIDs.contains(preset) }
public func documentResultIsImage(_ preset: String) -> Bool { imageResultPresets.contains(preset) }

public func keptImageExtension(_ fileName: String) -> String {
    let name = fileName.split(separator: "/").last.map(String.init) ?? fileName
    let ext = name.split(separator: ".").last.map { String($0).lowercased() } ?? ""
    switch ext {
    case "jpg", "jpeg": return "jpg"
    case "png", "webp", "bmp", "gif": return ext
    default: return "jpg"
    }
}

public func documentExtension(_ preset: String, imageFormat: String?) -> String {
    switch preset {
    case "image-jpg": return "jpg"
    case "image-png": return "png"
    case "image-webp": return "webp"
    case "image-bmp": return "bmp"
    case "image-gif": return "gif"
    case "image-compress": return imageFormat?.isEmpty == false ? imageFormat! : "jpg"
    case "pdf-image": return imageFormat?.isEmpty == false ? imageFormat! : "jpg"
    case "pdf-txt": return "txt"
    case "pdf-compress", "pdf-split", "office-pdf": return "pdf"
    default: return imageFormat?.isEmpty == false ? imageFormat! : "bin"
    }
}

public func outputCount(preset: String, media: MediaInfo) -> Int {
    if preset == "pdf-split" || preset == "pdf-image" {
        let (lo, hi) = clampPageRange(
            start: media.pageStart ?? 1,
            end: media.pageEnd ?? media.pageCount ?? 1,
            pageCount: media.pageCount ?? 1
        )
        return max(hi - lo + 1, 1)
    }
    return 1
}
