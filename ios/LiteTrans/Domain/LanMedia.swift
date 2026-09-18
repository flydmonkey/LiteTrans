import Foundation

public enum LanPreviewKind: Equatable, Sendable {
    case video, audio, pdf, image, file
}

public func lanPreviewKind(_ fileName: String) -> LanPreviewKind {
    switch lanFileExtension(fileName) {
    case "mp4", "mov", "mkv", "webm", "avi": return .video
    case "mp3", "m4a", "wav", "ogg", "flac", "amr": return .audio
    case "pdf": return .pdf
    case "jpg", "jpeg", "png", "webp", "gif", "bmp": return .image
    default: return .file
    }
}

public func lanContentType(_ fileName: String) -> String {
    switch lanFileExtension(fileName) {
    case "mp4": return "video/mp4"
    case "mov": return "video/quicktime"
    case "mkv": return "video/x-matroska"
    case "webm": return "video/webm"
    case "avi": return "video/x-msvideo"
    case "mp3": return "audio/mpeg"
    case "m4a": return "audio/mp4"
    case "wav": return "audio/wav"
    case "ogg": return "audio/ogg"
    case "flac": return "audio/flac"
    case "amr": return "audio/amr"
    case "jpg", "jpeg": return "image/jpeg"
    case "png": return "image/png"
    case "webp": return "image/webp"
    case "gif": return "image/gif"
    case "bmp": return "image/bmp"
    case "pdf": return "application/pdf"
    case "txt": return "text/plain; charset=utf-8"
    default: return "application/octet-stream"
    }
}

public func lanContentDisposition(_ fileName: String, inline: Bool = false) -> String {
    let safe = fileName.replacingOccurrences(of: "[\r\n\"]", with: "_", options: .regularExpression)
    let encoded = lanQueryEncode(safe).replacingOccurrences(of: "+", with: "%20")
    let kind = inline ? "inline" : "attachment"
    return "\(kind); filename=\"\(safe)\"; filename*=UTF-8''\(encoded)"
}

public func isLanContentLocation(_ location: String) -> Bool {
    location.lowercased().hasPrefix("content:")
}

func lanFileExtension(_ fileName: String) -> String {
    guard let dot = fileName.lastIndex(of: "."), dot != fileName.endIndex else { return "" }
    return String(fileName[fileName.index(after: dot)...]).lowercased()
}

public enum LanByteRange: Equatable, Sendable {
    case whole
    case partial(start: Int64, endInclusive: Int64)
    case unsatisfiable
}

public func parseLanByteRange(_ header: String?, total: Int64) -> LanByteRange {
    let raw = header?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    if raw.isEmpty { return .whole }
    if total <= 0 { return .unsatisfiable }
    if raw.contains(",") { return .unsatisfiable }
    let prefix = "bytes="
    guard raw.lowercased().hasPrefix(prefix) else { return .unsatisfiable }
    let spec = String(raw.dropFirst(prefix.count))
    guard let dash = spec.firstIndex(of: "-") else { return .unsatisfiable }
    let startText = String(spec[..<dash])
    let endText = String(spec[spec.index(after: dash)...])
    guard let start = Int64(startText) else { return .unsatisfiable }
    if start < 0 || start >= total { return .unsatisfiable }
    let end: Int64
    if endText.isEmpty {
        end = total - 1
    } else if let parsed = Int64(endText) {
        end = parsed
    } else {
        return .unsatisfiable
    }
    if end < start { return .unsatisfiable }
    return .partial(start: start, endInclusive: min(end, total - 1))
}

public func lanContentRangeValue(start: Int64, endInclusive: Int64, total: Int64) -> String {
    "bytes \(start)-\(endInclusive)/\(total)"
}

public func lanUnsatisfiableContentRange(_ total: Int64) -> String {
    "bytes */\(total)"
}

public func lanReadyFileResponse(_ sized: LanHttpResponse, opened: Bool) -> LanHttpResponse {
    if sized.filePath == nil || opened { return sized }
    return LanHttpResponse(
        status: 404,
        contentType: "text/plain; charset=utf-8",
        body: Data("Not Found".utf8),
        sendBody: sized.sendBody
    )
}

public func applyLanResponseRange(_ response: LanHttpResponse, total: Int64) -> LanHttpResponse {
    guard response.filePath != nil else { return response }
    var next = response
    switch parseLanByteRange(response.rangeHeader, total: total) {
    case .whole:
        next.status = 200
        next.headers["Content-Length"] = String(total)
        next.byteStart = 0
        next.byteLength = nil
        return next
    case let .partial(start, endInclusive):
        let length = endInclusive - start + 1
        next.status = 206
        next.headers["Content-Range"] = lanContentRangeValue(start: start, endInclusive: endInclusive, total: total)
        next.headers["Content-Length"] = String(length)
        next.byteStart = start
        next.byteLength = length
        return next
    case .unsatisfiable:
        next.status = 416
        next.headers["Content-Range"] = lanUnsatisfiableContentRange(total)
        next.body = Data()
        next.filePath = nil
        next.sendBody = false
        return next
    }
}

public func copyLanRange(from data: Data, start: Int64, length: Int64) -> Data {
    let begin = Int(start)
    let end = min(data.count, begin + Int(length))
    guard begin >= 0, begin < data.count, end > begin else { return Data() }
    return data.subdata(in: begin..<end)
}
