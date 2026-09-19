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
    case favicon
    case notFound
}

public func parseLanRoute(_ path: String) -> LanRoute {
    let trimmed = String(path.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false).first ?? "")
    if trimmed == "/" || trimmed.isEmpty { return .home }
    if trimmed == "/favicon.png" || trimmed == "/favicon.ico" { return .favicon }
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
    let route = parseLanRoute(request.path)
    if case .favicon = route {
        return LanHttpResponse(
            status: 200,
            contentType: "image/png",
            body: lanFaviconPng,
            sendBody: sendBody
        )
    }
    if !lanTokenAllows(storedToken: token, queryK: request.query["k"]) {
        return lanPlainText(401, copy.needToken, sendBody: sendBody)
    }
    switch route {
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
    case .notFound, .favicon:
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

private let lanFaviconPng = Data(base64Encoded: "iVBORw0KGgoAAAANSUhEUgAAAIAAAACACAYAAADDPmHLAAAV4ElEQVR42u1dCXCUVRLuTAZyEy65" +
    "FWSxFhS5BLY4IseiyA0uCh67bq14oLLKIbp4rYLHYgKisIiAVSoIURSCyI0IeJSAIIcGVgygECBy" +
    "hdwhx/bXf94wjnMkJPPPP5P3qv7KJDOT/+jv9ev+ul+3vU6dOqWkR7Uddv0INAD00ADQQwNADw0A" +
    "PTQA9NAA0EMDQA8NAD00APTQANBDA0APDQA9NAD00ADQQwNADw0APTQA9NAACK4RFhbmONTv7kZp" +
    "aanjpzo0AIJU2DabTQRYXFxMFy9elKOoqJhKSorLhCuiVt8iYML4XjjZ7eFUo0YN/mmn8PBw+XtJ" +
    "SUlIgsIeKkKHwDEg6Pz8fCosLOS/2yg2Nobq169PDRs24KOhvK5duzbFxMSIkNV3cnJy6Pz583T6" +
    "9Gk6deoUHxl05swZysy8wEIvoZo1a1JkZKTjOwoQGgABHBA6DghbCQoC7tChA3Xu3Ik6duxIrVv/" +
    "kZo2bUqc/UwREVEy272PUiooyKNz587R8ePH6cCBg7R7927auXMXpaamCkAUsAAKAAFH0E6eYEwL" +
    "h1rG7MOsLSgooMaNG1NCQk+65ZZbqEeP7tSyZQv+TETZp0tY9UP9F/lU487LB9S/3Y7ZbmiW4uIC" +
    "Sks7TF9++TWtXbuWtm37gk6cOMGgihBtgu9hudEAMEHwmO02Wxh17dqF7rhjNA0aNJCaN29ZZsAV" +
    "yRIAYTgbf54MP496wMkQxIFzYwkICzOU5tGjafTpp6tpyZKltH37DgZXKcXH1wo6IAQFADAj8WAz" +
    "MzNFEP3730wPPvgA/fnPfXlNjuQHXihCx8QGMJQ9UNXDUPelYjBGRUXyeWqy/ZBPmzZ9Rm++OY/W" +
    "rVsvwo+PjxfQBMPSYHkAQBXn5uaKgG+6qR9Nnvw49enTV9byvLxseeDKUjdzKA8D546KihXbYfPm" +
    "z2j69Fdpw4aNoi2io6Nl6dEAuMxZj3H27Fk25FrTc889Q6NG3Y6FgAGR5VDL5RVWefz78vIFrkMt" +
    "N9HRcWJzJCcn0/PPT2UD8gDVrVvXoT00ACow6/Py8sQ9g6p/7rln2YqvVyHBK+scgsH/g/tms9Uo" +
    "p6pXvEGRnE95GxUBwrlzZxgEL8jSgHNHRUVZUhtYDgAQFlywq666it54YxYNHDhY1llY+3ivPELH" +
    "5yIjox0uH4AD3z49PZ0t95Pi32dlZfH/LJT3IyJqUlxcnLiQjRo1pCZNmghnYMxow5PIz88TAZYH" +
    "DPgcvAPYJ2vWrKJHHnmUfv75Z3FFrQYCywBAuV/wswcMuIUWLJjPgmhG2dmZPtf4SzMvRty2/Pwc" +
    "2r//e/r666/FQof/np5+QryHwsIC+bw7JhDngeBq1aolIGjTprV4Gt27d6PrrruOQRUjYMjNzfGp" +
    "iZSNEBsbz6A7Rvfeex+DYa2AzEokkiUAoNZdrPfjxj1Cr702Qx4QjD9vsx4PGKAxjLAS2rVrF61Y" +
    "sUKs8dTUAwyebHkfhA0OCEx5FJ6EBuHg/4JcwoHfoR1gh8D7GDFiGBNMnQRoMELxvjcgYMbDGMQ5" +
    "H3tsAmu12WIXONsieC9QgAg4AJQwMDtfemkqW/lPygxWFrYnVY8BFV1UVECrVn1KCxe+TVu3bqML" +
    "F7LERcOaq3iDigZ3XPkDXAtskry8fNYOcXTjjTfSmDH/EP7Bbo+QJcbZcHUHVINHiGEv4RWaMuUZ" +
    "4QzUudTSUu0AoISP9XjWrJk0duzDzO5d8DpL8bBiY+NEbaekrKCkpJmi6vH52NhY0Rj+oGfV2o/z" +
    "Q7MATFgaJk6cQEOHDhMNhL970lhKu8TE1KK5c+fQo4+OF80CYNWrV5dBlBcQLRAwAKjZdf58JqvF" +
    "10T43tZ7ZdFD3e/fv5eefvpZZuI+lc/jQaqZZhYjqYCLcw4ePJimTXue7YR2siwoz8GbXTBv3lz2" +
    "cB6iv//9HnZvb6ORI0eJ/WE2CAIGADxEWOOvvPKSqH0I39PsUesoHuqMGTPpxRdfZlV/QaJ6gWTc" +
    "lKY6d+68qPSnn55C48c/5lgyPN0Prjc6OlY02M033yRA6tDhBolt4DtmgiAgAMBN/vrrr/TPf45j" +
    "1f+6V7VvqPx4OnnyON1//1j65JNPRPDwra3iUuF+wBsgnDx06FB666257EY28QpqAwRx7Irmyuu+" +
    "ffvRd9/tkcCSmYC2BcrPh6sHax8Gny/h79z5DRtefST40qDBFY612CpDGXG4tlWrVsm1fvvtdrl2" +
    "T9eJzwP4AA6WtWuuuUZem01p28xWmVCNIHng56s10ZvwQaT07z+Qjh07JsbSxYtFlkzEwDXh2urV" +
    "q0e//PILq/YBHDZe7RUEzl5OmzZt+FmUhDYAqCz7BgwfSB74+e5cPSX85cs/EuMIv0M1Wj2woq4d" +
    "14r7HDnydlq5coXciycDVQn82mvb8LJmNx3cNjNVP4gecPugdz2tj84z/+67/yZrPY5girHjWkE8" +
    "4bpvvXUkL12fiPvn7h4MABRTq1atxJsxG+Q2s1Q/ZruK6oHb9zbzd+z4hoV/T1lWjj1oU66gBTp0" +
    "aM/arjGzinkeNQAYx2bNmnJmUyPT7QCbWT4/4vkQfp069SWw43qThlUcLdb+XXf9VWwFlXNHQZik" +
    "CqE2atSIFi9+j6njznwvUXJ/UPHO6WmKCYyPr82pbC3le2aygjYz/H3QvEjmQDwftKk71a+CQXD1" +
    "fvopTVi9YMyxUwYh7htcxbBht9Lo0aPo7bcX0P/+96OQPdBycAFxvyptHaKAhgQYzNQAdnMeho3J" +
    "nkmSzOHOyFGqPykpUfx8uFOwqCsLvEBG3SBECBbey6FDh+iDD5ZxJLAetWt3PfXu3ZuzmnrL67i4" +
    "2o7vAABGCDtEiCAIAVTvwIEDhPXKzc3+nXozVH8M07v7qVevPvK72tRRmQGtg9RtzLhAeg/OexZw" +
    "HeD8oeaxHLRq9QfJYgYY+vXrJzkDCQm9fOY9BA0AcOOgOVNSlnPK9kAhPlyNP0WLjhgxQqJ6lUma" +
    "wP8GyQQ6FgbVyy//R+hmMIfY7YOdQVbZtQTVD7sIEUYksjZr1oxdwWslpI2/m2UH2Pwp/OzsHOrS" +
    "pYtk7yJI4ip8PASshStXpojwIajKzFYVuoWqnTBhEufwb6WHH35ItMmZM2fl/fLmEfprOQTgVaoZ" +
    "Qtb169cV0MNF3rRpkxjIIWEEGjt2CujOO0eXpW4Xu/0M4vmJiTMcsfuqcb8MEF199dWSgLF162b2" +
    "LO6Uhwu+HucNJBCctR+0Ep4NOAPwACHBBOImkG+HHTtImkCSpbvZDw4cMx/xfNx8Vbl86hlClYJw" +
    "uv76dvTuu+/R+vVr5XqwLMFCVxlCVvEcAuHy2vznBxfwDGwhIDA2aoa5YcBKJJPHX6hXGcHI4YP9" +
    "0b17T0kZW7lyOfXs2UPsBZV2ZhUghAQALpE/KgGz1C3ps2fPHknj8rfPr1Q+QABPBMGlDRvW0fvv" +
    "L6K2bdtKIiq0BYBgtgoOSQBAwDBwsLN2z569ouqx/qqES4P4CKelS5NFHZvl9iiVDyDgem6/fbTY" +
    "B/Pmvcl7C5tLjgKurToBwa8aoLi4iCZNmsyq9jRTnXXF34+JiZPX69ev4Qf/luyjM5vxU2lnsA8w" +
    "7rvvfvrqq2306qvTJZwLIOCazPTHAzXCeab+219GDfbHHTlyRAy9iAgjgyct7SfOmJlHTz45RX6v" +
    "yhQoFXTq27cPJ2X0EjvE29qu3sOmD1wrSJjRo2+TrOJ9+/YLEPB3cAjYFBqKw+8pYZhtEAoIDzBz" +
    "EDrWW8z8qmD8yE2q2bRpL9BTTz3jNSXLU8KmIfAIBuqPnLE0i957b7Ej/1AROHoJoIrFxmEPIJsH" +
    "vi6MPxA1VS38qvIYYAMAOC1atKDXXzc4hLvvvstyHEJQhYOV8XeJCSu2bH0dBQRnDuGdd94VDmHw" +
    "4EGW5BCCKiUsmOoPuXIIy5evkBQvxSHk5AQ/h6ABUCEOIYs5hAHCISxZspjDucHPIWgAXCaHcNtt" +
    "o2jLls3s0QQ3h6ABUEkOYcwYg0NITAxODkEDoBIuJwaAAC9n4sTHOaj1JW8Pe0qSUAAEDYBqAgTM" +
    "eAABru7UqdO4sOROJrqeCIqlQAPAb5k/l8LSulRsCA8wm0hfR8Dr1Kl0mjNnrsQ4MjIyJNNHAyBk" +
    "BV8sMQJkM2dmnuXMozks/P/S4cOHhea+4oorgmIrmwYAVZzahjsYG1tLtnZjkytiBt9//71kNUHw" +
    "+EwwCF8DoIKCx9qOPX5Icfvww2TJZdyxY4fEN4JN8BoAVP44BuIWEDzGunVrOG8gkT7/fIsEt1TZ" +
    "t2ATvAZAOSuNIl0N2Uvw8VEHePXqNfI+DDy1z083jAihofICjJpENTgxZA9vWUuiZcs+FgoYBl5V" +
    "l4Q39ivYJOlEFZgyK0tYA8BNQghcusOHD9HMmbNo0aLFUqYeCSFg/Ko6IUT1I0J00UhGscuGGmQl" +
    "AYT+TkDRAHDry/+X5s9fKPWFIXhw/PiMP4SvAkj/+tcT0vEEINi3bx8Xz3qDDh48KJ6FP0Fg1768" +
    "sy8/W4gc5cvDwIPg/bHOq5kP4S9dulhqChm7gkt5O92feK/kcBoyZDgXm/rWr5XDbNXVpYPKhy+P" +
    "sWDBW7xLN0EymBHfh0uHqJ8/DTyjbkImPfDA/SJ8ADAnJ0sSUPAahTQQYQwP92/dIFt1E7wq14qI" +
    "HXz5Xr36clGKBzl7+agIXtUf9HfKGs4B9Y7iEagRhPOqnANcGzbTtm/fTrqeoVqKv7KO7NXVl58+" +
    "PZETOuDL1zTdl1ezH9XH27dvL7PeVcBqG3lkpH/Lx9pD3bI3qo8oX/4LEbwVfHmc9957/yFK2FXA" +
    "+B1aAPUGDx8+4tcawvZA5Nj5uwWr+td4cCjOBF8+MTGJPvrIf758RYtmdOvWTbKMPdVNAAeByqjo" +
    "TaiM0aAHAISOm4dgcCBeDvKjqi1cFFwk6e13VKJ0/vblL6eIxaRJE2QDCgDpCgD8jlJ677+/lAEc" +
    "4VdCyGbeOlwqvXlQLQQdM0B8nD59VgwclYvvrU9AeQGG5s+nT59hEieJU7kTaPbsOfIefHlF9lAA" +
    "s4ewuQQzHz0GkGXsqW4CehEi0ITdVP4EgKnVwqHGtm3bIrWCN27cyH32PucyLl9JFS0UTwIZEx0d" +
    "VVYytXINmq1SJMrV7wfIkU3ctu11smXOfdGsWC4vN1xsldq140ODCALhgophIDbat+8oadU4srLO" +
    "0969exkMWwQQYMEwg1E4SXXrvhytULduHUtF6XD/GRlnOa7wKgu/ndt9ixA0PJXNmzfyTqT10oMg" +
    "hKhgo/4dmikaM/SsPADM+h49bpRjypQn6ccfD7FW+JIfwAaun/8dF086d1nlU620iRP3ieVuyJAh" +
    "XLxqvBh+noplghOAp2JW5XDTloBLvm9/LgaZ4vB9lUegum85N2g+ePAHrukzVPLroAmsup/Q132j" +
    "EwjKwGGjaYMGDdyWgVPFMpOTl0ipXLioZoDYZiYZg9melpYmhpCqC+Dcrh2vsS6isDJSq/EgTp48" +
    "Kd8LRuEbldIKxfNAzeBGjZq6XfeV349CGmg5i0lg1v3azHQBMYsh0OPHj3kVKnx3NHpECxV8h4J0" +
    "T6EKJC1a9I4EeDzVKzDKxEU6+g0jDGxWxTCb2WsheABY/Z7qBquNmIMGDaGPP14m6z9mUTDtyTf8" +
    "eKP/8KJF73J7HN/9EVavXiV9huEim2m42swvoFxEP/yQ+puu3u4eIB7Y0KHDORPnA9ECqqMWBcFO" +
    "IXWty5Ylc1j3L15nPmZ7evox7pj6aEC0nc38Muo2BwC8WetGZkym1BhGQakrr7xS6v6CI7Dilitc" +
    "E64NJWlh8K1bt9rrzFcl5fG9MWPuk0LRsBXMLhZpOgCAcrh6cIXw2kizLvEKghtu6CoWNBo0ZmT8" +
    "6kiksNKsxzXh2lCJFNfaufOfvAofn0crWfQTRlPpQHUWNx0AsHaBdvT4xUNAJXEwX75AgIBISsrH" +
    "3DgyUQws8AOBLtOiikeA1sZrkDyoQgpXz1uBKkX4oI8wspD8GewJWJk4bw8Na2RCQk8hPFAurnHj" +
    "hhwd6yHl2lRJdU8WdUJCb9YEA9iTOM4M4j75G9wmM4tOKeChVzBiGYMGDeJqYu8wfTuCf8+Va3Jn" +
    "tDq3jkX/4EmTnnCEpKtV+3ijB3CUlEjHz4o3j44V5YUy80lJM7hAQ2CaRyOkO3HieOHtwXJmZ2dV" +
    "uHm0N2M4pHsHq3Vc3Xxl2scjbr5gAdrHbzWpfXyCJHMgqlfZ9vGBJrgC2j1c3bx60NAI48Y9Ii1l" +
    "8Z6q5E0+NmoifIrK47t37+JKXilsga8XQgWaBe+DdMKhVLcn7aJmKf4vuAccandQmzatJYVr+PDh" +
    "1KlTJ9FAMGQVhU1eIqBw9XBOGHxY8+Hr+zspxvIA8NRbB1m56Cu8cOF8tg2aeW0p77pxE7WIIRho" +
    "EOzWxdKwffsOSk09wL52umgZlI81soLJqTmTUdAB50ECBmYnev2hnWvXrl1E1SN8ixkMoCGOodw4" +
    "XxtNsN7Dz4erB2tf5R9ahdq2DABcm0sjZ2D27FniSyM7Bpkzvlw/tfbjc5GRUQ4nByoamzwAghMn" +
    "TgqfAO2AphYkqWM1ZT1G0gh6DTVp0oS7fzeUJaZMnAyqXEeTaF+eBz4Hbwf0Lhg+kDzwfALl6gUV" +
    "ABQIsOaCSh079gFuOPksJ0bUE0H6mnmuYFDZRuAckGdXPvvEoHFVenh5hP5bTRTHID7D3P4LQu/i" +
    "3LBJrLiR1JIAcDaoYBeolrOjRo2SWV0RIDgbWr6MQWfDz7mxc0UEjyUiOTnZEdjBeu9suGoAXIY2" +
    "gDGIGDq6j06e/Dj32esr6zaMMGVhm00PqzUe5zaM0FLO5PlMtpBv2LBRuAkYf1bfPm55AChtAAEj" +
    "oQQPHNY4upAjwRTrbElJobhomNhIJfMXO2gsK6ViMELA4eE1xT5BAidUPbwPgAJp54FqAhWSAHBm" +
    "4PBgYc1D0LDQ77hjtPDvzZu3LJuZRj8CpZZd1XpFZrjz4ZqtdPRomvAPS5YsFU8DwID3EKj9BtUC" +
    "AK5AAKUM7wCdyUAtY3t1jx7duAv31fyZCDVvWQ1f/E3Hbl82gMpQsttrODyJ4uICzmY6wq7lV+LO" +
    "bdv2hWzagLWP3bvBJvigBoArPQvCBkUVSktLxM+G/965cydu295RNlc2bdpUXLCIiKiy5FSvc59B" +
    "lSeuKOINaHy1e/du2rlzF/MJqcJThIXZJOVctbcPxhb3IQEAdw2a4b5hCTB6FRqCgn/fsGED8e0B" +
    "EOwQwqxVCRj4DrQJchUhYHAGp05lCF+ggAVhqzR15w2npHsGWbFMa5gjOqhKtxl+PbaHF5ctA79n" +
    "Ao3vhUsOPwSNZUB5GL6WD9JFosgyO4JdLXAIEzPYl3/vji8ItSZR1bI+QCjOXA0APTQA9NAA0EMD" +
    "QA8NAD00APTQANBDA0APDQA9NAD00ADQQwNADw0APTQA9NAA0EMDQA8NAD00APT4/fg/FAiiawD/" +
    "YY4AAAAASUVORK5CYII=")!
