import Foundation

public let lanSharePreferredPort = 17890
public let lanSharePortAttempts = 10

public struct LanShareSettings: Equatable, Sendable, Codable {
    public var enabled: Bool
    public var token: String

    public init(enabled: Bool = false, token: String = "") {
        self.enabled = enabled
        self.token = token
    }
}

public func normalizeLanToken(_ raw: String) -> String {
    raw.trimmingCharacters(in: .whitespacesAndNewlines)
}

public func lanTokenAllows(storedToken: String, queryK: String?) -> Bool {
    if storedToken.isEmpty { return true }
    return queryK == storedToken
}

public enum LanRoute: Equatable, Sendable {
    case home
    case download(jobId: String, index: Int)
    case media(jobId: String, index: Int)
    case notFound
}

public func parseLanRoute(_ path: String) -> LanRoute {
    let trimmed = String(path.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false).first ?? "")
    if trimmed == "/" || trimmed.isEmpty { return .home }
    let stripped = trimmed.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    let parts = stripped.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
    if parts.count < 2 || parts.count > 3 { return .notFound }
    let kind = parts[0]
    guard kind == "d" || kind == "m" else { return .notFound }
    let jobId = parts[1]
    if jobId.isEmpty || jobId.contains("..") || jobId.contains("/") { return .notFound }
    let index: Int
    if parts.count == 2 {
        index = 0
    } else {
        guard let value = Int(parts[2]), value >= 0 else { return .notFound }
        index = value
    }
    return kind == "d" ? .download(jobId: jobId, index: index) : .media(jobId: jobId, index: index)
}

public func lanQueryEncode(_ raw: String) -> String {
    var allowed = CharacterSet.alphanumerics
    allowed.insert(charactersIn: "-._*")
    let encoded = raw.addingPercentEncoding(withAllowedCharacters: allowed) ?? raw
    return encoded.replacingOccurrences(of: "%20", with: "+")
}

public func lanQueryDecode(_ raw: String) -> String {
    let plusAsSpace = raw.replacingOccurrences(of: "+", with: " ")
    return plusAsSpace.removingPercentEncoding ?? plusAsSpace
}

public func parseLanQuery(_ rawQuery: String?) -> [String: String] {
    guard let rawQuery, !rawQuery.isEmpty else { return [:] }
    var result: [String: String] = [:]
    for part in rawQuery.split(separator: "&", omittingEmptySubsequences: false) {
        if part.isEmpty { continue }
        let piece = String(part)
        let eq = piece.firstIndex(of: "=")
        let rawKey = eq.map { String(piece[..<$0]) } ?? piece
        let rawVal = eq.map { String(piece[piece.index(after: $0)...]) } ?? ""
        let key = lanQueryDecode(rawKey)
        if key.isEmpty { continue }
        result[key] = lanQueryDecode(rawVal)
    }
    return result
}

public func parseLanHeaderLines(_ lines: [String]) -> [String: String] {
    var headers: [String: String] = [:]
    for line in lines {
        guard let colon = line.firstIndex(of: ":"), colon > line.startIndex else { continue }
        let name = line[..<colon].trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespacesAndNewlines)
        headers[name] = value
    }
    return headers
}

public struct LanHttpRequest: Equatable, Sendable {
    public var method: String
    public var path: String
    public var query: [String: String]
    public var headers: [String: String]

    public init(method: String, path: String, query: [String: String], headers: [String: String] = [:]) {
        self.method = method
        self.path = path
        self.query = query
        self.headers = headers
    }
}

public struct LanHttpResponse: Equatable, Sendable {
    public var status: Int
    public var contentType: String
    public var body: Data
    public var headers: [String: String]
    public var filePath: String?
    public var rangeHeader: String?
    public var sendBody: Bool
    public var byteStart: Int64
    public var byteLength: Int64?

    public init(
        status: Int,
        contentType: String,
        body: Data,
        headers: [String: String] = [:],
        filePath: String? = nil,
        rangeHeader: String? = nil,
        sendBody: Bool = true,
        byteStart: Int64 = 0,
        byteLength: Int64? = nil
    ) {
        self.status = status
        self.contentType = contentType
        self.body = body
        self.headers = headers
        self.filePath = filePath
        self.rangeHeader = rangeHeader
        self.sendBody = sendBody
        self.byteStart = byteStart
        self.byteLength = byteLength
    }
}

public func parseHttpRequestLine(_ line: String) -> LanHttpRequest? {
    let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
    guard let firstSpace = trimmed.firstIndex(of: " "), firstSpace > trimmed.startIndex else { return nil }
    let method = String(trimmed[..<firstSpace])
    let rest = trimmed[trimmed.index(after: firstSpace)...].trimmingCharacters(in: .whitespaces)
    if rest.isEmpty { return nil }
    let target: String
    if let targetEnd = rest.firstIndex(of: " ") {
        target = String(rest[..<targetEnd])
    } else {
        target = String(rest)
    }
    if target.isEmpty { return nil }
    if let queryStart = target.firstIndex(of: "?") {
        let path = String(target[..<queryStart])
        let query = parseLanQuery(String(target[target.index(after: queryStart)...]))
        return LanHttpRequest(method: method, path: path, query: query)
    }
    return LanHttpRequest(method: method, path: target, query: [:])
}

public struct LanIface: Equatable, Sendable {
    public var name: String
    public var hostAddress: String
    public var loopback: Bool

    public init(name: String, hostAddress: String, loopback: Bool) {
        self.name = name
        self.hostAddress = hostAddress
        self.loopback = loopback
    }
}

public func isLanWifiOrHotspotName(_ name: String) -> Bool {
    let n = name.lowercased()
    if n.hasPrefix("pdp_ip") || n.hasPrefix("rmnet") { return false }
    if n.hasPrefix("wlan") || n.hasPrefix("ap") || n.contains("wlan") || n.contains("swlan") || n.contains("softap") {
        return true
    }
    if n.hasPrefix("en") || n.hasPrefix("bridge") { return true }
    return false
}

public func pickLanIpv4(_ ifaces: [LanIface]) -> String? {
    let ipv4 = /^[0-9]{1,3}(?:\.[0-9]{1,3}){3}$/
    let usable = ifaces.filter { iface in
        !iface.loopback && iface.hostAddress.wholeMatch(of: ipv4) != nil
    }
    return usable.first { isLanWifiOrHotspotName($0.name) }?.hostAddress
}

public func chooseLanPort(preferred: Int = lanSharePreferredPort, attempts: Int = lanSharePortAttempts, occupied: Set<Int>) -> Int? {
    for offset in 0..<attempts {
        let port = preferred + offset
        if !occupied.contains(port) { return port }
    }
    return nil
}

public func lanPublicUrl(ip: String, port: Int, token: String) -> String {
    let base = "http://\(ip):\(port)/"
    if token.isEmpty { return base }
    return "\(base)?k=\(lanQueryEncode(token))"
}

public struct LanHistoryCopy: Equatable, Sendable {
    public var warning: String
    public var video: String
    public var audio: String
    public var document: String
    public var image: String
    public var emptyVideo: String
    public var emptyAudio: String
    public var emptyDocument: String
    public var emptyImage: String
    public var download: String
    public var downloadNamed: String
    public var downloadIndex: String
    public var statusQueued: String
    public var statusRunning: String
    public var statusCompleted: String
    public var statusFailed: String
    public var statusCancelled: String
    public var needToken: String
    public var previewFailed: String
    public var downloadToOpen: String

    public init(
        warning: String,
        video: String,
        audio: String,
        document: String,
        image: String,
        emptyVideo: String,
        emptyAudio: String,
        emptyDocument: String,
        emptyImage: String,
        download: String,
        downloadNamed: String,
        downloadIndex: String,
        statusQueued: String,
        statusRunning: String,
        statusCompleted: String,
        statusFailed: String,
        statusCancelled: String,
        needToken: String,
        previewFailed: String,
        downloadToOpen: String
    ) {
        self.warning = warning
        self.video = video
        self.audio = audio
        self.document = document
        self.image = image
        self.emptyVideo = emptyVideo
        self.emptyAudio = emptyAudio
        self.emptyDocument = emptyDocument
        self.emptyImage = emptyImage
        self.download = download
        self.downloadNamed = downloadNamed
        self.downloadIndex = downloadIndex
        self.statusQueued = statusQueued
        self.statusRunning = statusRunning
        self.statusCompleted = statusCompleted
        self.statusFailed = statusFailed
        self.statusCancelled = statusCancelled
        self.needToken = needToken
        self.previewFailed = previewFailed
        self.downloadToOpen = downloadToOpen
    }
}

public func jobOutputPaths(_ job: Job) -> [String] {
    let paths = job.outputPaths.filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    if !paths.isEmpty { return paths }
    if let outputPath = job.outputPath, !outputPath.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
        return [outputPath]
    }
    return []
}

public struct LanDownloadTarget: Equatable, Sendable {
    public var path: String
    public var downloadName: String
    public var contentType: String

    public init(path: String, downloadName: String, contentType: String) {
        self.path = path
        self.downloadName = downloadName
        self.contentType = contentType
    }
}

public func resolveLanDownload(
    jobs: [Job],
    jobId: String,
    index: Int,
    exists: (String) -> Bool
) -> LanDownloadTarget? {
    if jobId.contains("..") || jobId.contains("/") { return nil }
    guard let job = jobs.first(where: { $0.id == jobId }) else { return nil }
    guard job.status == .completed else { return nil }
    let paths = jobOutputPaths(job)
    guard index >= 0, index < paths.count else { return nil }
    let path = paths[index]
    guard exists(path) else { return nil }
    let downloadName = lanPreviewFileName(path, job: job)
    return LanDownloadTarget(path: path, downloadName: downloadName, contentType: lanContentType(downloadName))
}

public func lanFileIsRegular(_ path: String) -> Bool {
    if path.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return false }
    let url = URL(fileURLWithPath: path)
    let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
    return values?.isRegularFile == true && values?.isSymbolicLink != true
}

public func escapeHtml(_ raw: String) -> String {
    raw
        .replacingOccurrences(of: "&", with: "&amp;")
        .replacingOccurrences(of: "<", with: "&lt;")
        .replacingOccurrences(of: ">", with: "&gt;")
        .replacingOccurrences(of: "\"", with: "&quot;")
}

public func lanHistoryDownloadHref(
    jobId: String,
    index: Int,
    multi: Bool,
    token: String,
    kind: String = "d"
) -> String {
    let path = (index > 0 || multi) ? "/\(kind)/\(jobId)/\(index)" : "/\(kind)/\(jobId)"
    if token.isEmpty { return path }
    return "\(path)?k=\(lanQueryEncode(token))"
}

func lanPreviewFileName(_ path: String, job: Job) -> String {
    let base = path.split(separator: "/").last.map(String.init) ?? path
    let ext = lanFileExtension(base)
    let needsOutputExtension = isLanContentLocation(path) || ext.isEmpty
    if !needsOutputExtension { return base }
    if let outputExtension = previewExtensionFromOutput(job), !outputExtension.isEmpty {
        return "file.\(outputExtension)"
    }
    if job.displayName.contains(".") { return job.displayName }
    return base
}

private func previewExtensionFromOutput(_ job: Job) -> String? {
    if isDocumentPreset(job.config.preset) {
        return documentExtension(job.config.preset, imageFormat: job.config.container)
    }
    return try? resolveConfig(job.config).container
}

public func handleLanRequest(
    _ request: LanHttpRequest,
    jobs: [Job],
    token: String,
    exists: (String) -> Bool,
    copy: LanHistoryCopy
) -> LanHttpResponse {
    if request.method != "GET" && request.method != "HEAD" {
        return lanPlainText(405, "Method Not Allowed")
    }
    let sendBody = request.method != "HEAD"
    if !lanTokenAllows(storedToken: token, queryK: request.query["k"]) {
        return lanPlainText(401, copy.needToken, sendBody: sendBody)
    }
    switch parseLanRoute(request.path) {
    case .home:
        let html = renderLanHistoryHtml(jobs: jobs, token: token, copy: copy, fileExists: exists)
        return LanHttpResponse(
            status: 200,
            contentType: "text/html; charset=utf-8",
            body: Data(html.utf8),
            sendBody: sendBody
        )
    case let .download(jobId, index), let .media(jobId, index):
        let inline = if case .media = parseLanRoute(request.path) { true } else { false }
        guard let target = resolveLanDownload(jobs: jobs, jobId: jobId, index: index, exists: exists) else {
            return lanPlainText(404, "Not Found", sendBody: sendBody)
        }
        return LanHttpResponse(
            status: 200,
            contentType: target.contentType,
            body: Data(),
            headers: [
                "Content-Type": target.contentType,
                "Content-Disposition": lanContentDisposition(target.downloadName, inline: inline),
                "Accept-Ranges": "bytes",
            ],
            filePath: target.path,
            rangeHeader: request.headers["range"],
            sendBody: sendBody
        )
    case .notFound:
        return lanPlainText(404, "Not Found", sendBody: sendBody)
    }
}

private func lanPlainText(_ status: Int, _ body: String, sendBody: Bool = true) -> LanHttpResponse {
    LanHttpResponse(
        status: status,
        contentType: "text/plain; charset=utf-8",
        body: Data(body.utf8),
        sendBody: sendBody
    )
}
