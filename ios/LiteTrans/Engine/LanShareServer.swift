import Darwin
import Foundation
import Network
import Observation

enum LanShareBindError: Equatable, Sendable {
    case needWifi
    case portsBusy
    case denied
}

@Observable
@MainActor
final class LanShareServer {
    private(set) var boundURL: String?
    private(set) var lastError: LanShareBindError?
    var onDenied: (() -> Void)?

    private var listener: NWListener?
    private var jobs: [Job] = []
    private var token = ""
    private var copy = englishPlaceholderCopy
    private var boundIP: String?
    private var boundPort: Int?

    func apply(enabled: Bool, token: String, jobs: [Job], sceneActive: Bool, copy: LanHistoryCopy) {
        self.jobs = jobs
        self.token = token
        self.copy = copy
        if let ip = boundIP, let port = boundPort {
            boundURL = lanPublicUrl(ip: ip, port: port, token: token)
        }
        if enabled && sceneActive {
            if listener == nil {
                start()
            } else if pickLanIpv4(currentLanIfaces()) == nil {
                stop()
                lastError = .needWifi
                boundURL = nil
            }
        } else {
            stop()
            if enabled && !sceneActive {
                lastError = nil
            }
        }
    }

    func stop() {
        listener?.cancel()
        listener = nil
        boundURL = nil
        boundIP = nil
        boundPort = nil
    }

    private func start() {
        stop()
        lastError = nil
        guard let ip = pickLanIpv4(currentLanIfaces()) else {
            lastError = .needWifi
            return
        }
        listen(ip: ip, port: lanSharePreferredPort, remaining: lanSharePortAttempts)
    }

    private func listen(ip: String, port: Int, remaining: Int) {
        guard remaining > 0, let nwPort = NWEndpoint.Port(rawValue: UInt16(port)) else {
            lastError = .portsBusy
            return
        }
        do {
            let listener = try NWListener(using: .tcp, on: nwPort)
            listener.stateUpdateHandler = { [weak self] state in
                Task { @MainActor in
                    guard let self else { return }
                    switch state {
                    case .ready:
                        self.listener = listener
                        self.boundIP = ip
                        self.boundPort = port
                        self.boundURL = lanPublicUrl(ip: ip, port: port, token: self.token)
                        self.lastError = nil
                    case .failed(let error):
                        listener.cancel()
                        if self.isLocalNetworkDenied(error) {
                            self.lastError = .denied
                            self.onDenied?()
                            return
                        }
                        self.listen(ip: ip, port: port + 1, remaining: remaining - 1)
                    default:
                        break
                    }
                }
            }
            listener.newConnectionHandler = { [weak self] connection in
                Task { @MainActor in
                    guard let self else { return }
                    serveLanConnection(connection, jobs: self.jobs, token: self.token, copy: self.copy)
                }
            }
            listener.start(queue: .global(qos: .userInitiated))
            self.listener = listener
        } catch {
            listen(ip: ip, port: port + 1, remaining: remaining - 1)
        }
    }

    private func isLocalNetworkDenied(_ error: NWError) -> Bool {
        String(describing: error).localizedCaseInsensitiveContains("denied")
    }
}

private let englishPlaceholderCopy = LanHistoryCopy(
    warning: "",
    video: "Video",
    audio: "Audio",
    document: "Documents",
    image: "Images",
    emptyVideo: "",
    emptyAudio: "",
    emptyDocument: "",
    emptyImage: "",
    download: "Download",
    downloadNamed: "",
    downloadIndex: "",
    statusQueued: "",
    statusRunning: "",
    statusCompleted: "",
    statusFailed: "",
    statusCancelled: "",
    needToken: "Password required",
    previewFailed: "",
    downloadToOpen: ""
)

func currentLanIfaces() -> [LanIface] {
    var ifaddr: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return [] }
    defer { freeifaddrs(first) }
    var result: [LanIface] = []
    var pointer: UnsafeMutablePointer<ifaddrs>? = first
    while let iface = pointer {
        let flags = Int32(iface.pointee.ifa_flags)
        let loopback = (flags & IFF_LOOPBACK) != 0
        if let addr = iface.pointee.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) {
            var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let named = getnameinfo(
                addr,
                socklen_t(addr.pointee.sa_len),
                &hostname,
                socklen_t(hostname.count),
                nil,
                0,
                NI_NUMERICHOST
            )
            if named == 0 {
                result.append(
                    LanIface(
                        name: String(cString: iface.pointee.ifa_name),
                        hostAddress: String(cString: hostname),
                        loopback: loopback
                    )
                )
            }
        }
        pointer = iface.pointee.ifa_next
    }
    return result
}

func lanHttpReason(_ status: Int) -> String {
    switch status {
    case 200: "OK"
    case 206: "Partial Content"
    case 401: "Unauthorized"
    case 404: "Not Found"
    case 405: "Method Not Allowed"
    case 416: "Range Not Satisfiable"
    default: "OK"
    }
}

private func serveLanConnection(_ connection: NWConnection, jobs: [Job], token: String, copy: LanHistoryCopy) {
    connection.start(queue: .global(qos: .userInitiated))
    receiveLanRequest(connection, buffer: Data(), jobs: jobs, token: token, copy: copy)
}

private func receiveLanRequest(
    _ connection: NWConnection,
    buffer: Data,
    jobs: [Job],
    token: String,
    copy: LanHistoryCopy
) {
    connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { data, _, isComplete, error in
        var next = buffer
        if let data { next.append(data) }
        if let range = next.range(of: Data("\r\n\r\n".utf8)) {
            let headerText = String(data: next[..<range.lowerBound], encoding: .utf8) ?? ""
            let lines = headerText.split(separator: "\r\n", omittingEmptySubsequences: false).map(String.init)
            guard let first = lines.first, var request = parseHttpRequestLine(first) else {
                connection.cancel()
                return
            }
            request.headers = parseLanHeaderLines(Array(lines.dropFirst()))
            let handled = handleLanRequest(request, jobs: jobs, token: token, exists: lanFileIsRegular, copy: copy)
            sendLanResponse(lanFileResponse(handled), on: connection)
            return
        }
        if isComplete || error != nil || next.count > 64 * 1024 {
            connection.cancel()
            return
        }
        receiveLanRequest(connection, buffer: next, jobs: jobs, token: token, copy: copy)
    }
}

private func lanFileResponse(_ response: LanHttpResponse) -> LanHttpResponse {
    guard response.filePath != nil else { return response }
    let opened = response.filePath.map(lanFileIsRegular) ?? false
    var result = lanReadyFileResponse(response, opened: opened)
    if let filePath = result.filePath {
        let size = (try? FileManager.default.attributesOfItem(atPath: filePath)[.size] as? Int64) ?? 0
        result = applyLanResponseRange(result, total: size)
    }
    return result
}

private func sendLanResponse(_ response: LanHttpResponse, on connection: NWConnection) {
    var header = "HTTP/1.1 \(response.status) \(lanHttpReason(response.status))\r\n"
    header += "Content-Type: \(response.contentType)\r\n"
    for (key, value) in response.headers where key.lowercased() != "content-type" {
        header += "\(key): \(value)\r\n"
    }
    header += "Connection: close\r\n\r\n"
    var payload = Data(header.utf8)
    if response.sendBody {
        if let path = response.filePath, let handle = FileHandle(forReadingAtPath: path) {
            defer { try? handle.close() }
            try? handle.seek(toOffset: UInt64(max(response.byteStart, 0)))
            if let length = response.byteLength {
                payload.append(handle.readData(ofLength: Int(length)))
            } else {
                payload.append(handle.readDataToEndOfFile())
            }
        } else {
            payload.append(response.body)
        }
    }
    connection.send(content: payload, isComplete: true, completion: .contentProcessed { _ in
        connection.cancel()
    })
}
