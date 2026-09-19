import Foundation
import Testing
@testable import LiteTransDomain

struct LanShareTests {
    @Test func defaultsAreOffAndEmptyToken() {
        let settings = LanShareSettings()
        #expect(settings.enabled == false)
        #expect(settings.token == "")
    }

    @Test func normalizeTrimsAndBlankBecomesEmpty() {
        #expect(normalizeLanToken("  secret  ") == "secret")
        #expect(normalizeLanToken("   ") == "")
        #expect(normalizeLanToken("") == "")
    }

    @Test func emptyTokenAllowsMissingOrAnyK() {
        #expect(lanTokenAllows(storedToken: "", queryK: nil))
        #expect(lanTokenAllows(storedToken: "", queryK: "whatever"))
    }

    @Test func setTokenRequiresExactMatch() {
        #expect(!lanTokenAllows(storedToken: "pw", queryK: nil))
        #expect(!lanTokenAllows(storedToken: "pw", queryK: ""))
        #expect(!lanTokenAllows(storedToken: "pw", queryK: "PW"))
        #expect(lanTokenAllows(storedToken: "pw", queryK: "pw"))
    }

    @Test func parsesHomeAndDownloadRoutes() {
        #expect(parseLanRoute("/") == .home)
        #expect(parseLanRoute("/d/a1") == .download(jobId: "a1", index: 0))
        #expect(parseLanRoute("/d/a1/2") == .download(jobId: "a1", index: 2))
        #expect(parseLanRoute("/d/") == .notFound)
        #expect(parseLanRoute("/d/a1/x") == .notFound)
        #expect(parseLanRoute("/d/../secret") == .notFound)
        #expect(parseLanRoute("/other") == .notFound)
        #expect(parseLanRoute("/m/a1") == .media(jobId: "a1", index: 0))
        #expect(parseLanRoute("/m/a1/2") == .media(jobId: "a1", index: 2))
        #expect(parseLanRoute("/m/") == .notFound)
        #expect(parseLanRoute("/m/../secret") == .notFound)
        #expect(parseLanRoute("/d/a1/../b") == .notFound)
        #expect(parseLanRoute("/favicon.png") == .favicon)
        #expect(parseLanRoute("/favicon.ico") == .favicon)
        #expect(parseLanRoute("/favicon.png?k=pw") == .favicon)
    }

    @Test func prefersWifiIpv4AndSkipsLoopback() {
        #expect(pickLanIpv4([LanIface(name: "lo", hostAddress: "127.0.0.1", loopback: true)]) == nil)
        #expect(
            pickLanIpv4([
                LanIface(name: "rmnet0", hostAddress: "10.20.30.40", loopback: false),
                LanIface(name: "wlan0", hostAddress: "10.0.0.8", loopback: false),
            ]) == "10.0.0.8"
        )
        #expect(pickLanIpv4([LanIface(name: "wlan1", hostAddress: "192.168.43.1", loopback: false)]) == "192.168.43.1")
        #expect(pickLanIpv4([LanIface(name: "wlan0", hostAddress: "fe80::1", loopback: false)]) == nil)
        #expect(pickLanIpv4([LanIface(name: "ap0", hostAddress: "192.168.49.1", loopback: false)]) == "192.168.49.1")
        #expect(pickLanIpv4([LanIface(name: "softap0", hostAddress: "192.168.43.1", loopback: false)]) == "192.168.43.1")
        #expect(pickLanIpv4([LanIface(name: "swlan0", hostAddress: "192.168.50.1", loopback: false)]) == "192.168.50.1")
        #expect(pickLanIpv4([LanIface(name: "en0", hostAddress: "10.0.0.8", loopback: false)]) == "10.0.0.8")
        #expect(pickLanIpv4([LanIface(name: "bridge100", hostAddress: "172.20.10.1", loopback: false)]) == "172.20.10.1")
    }

    @Test func cellularOnlyIpv4IsIgnored() {
        #expect(pickLanIpv4([LanIface(name: "rmnet0", hostAddress: "10.20.30.40", loopback: false)]) == nil)
        #expect(pickLanIpv4([LanIface(name: "pdp_ip0", hostAddress: "10.20.30.40", loopback: false)]) == nil)
    }

    @Test func portWalksForwardWhenBusy() {
        #expect(chooseLanPort(preferred: 17890, attempts: 10, occupied: []) == 17890)
        #expect(chooseLanPort(preferred: 17890, attempts: 10, occupied: [17890, 17891]) == 17892)
        #expect(chooseLanPort(preferred: 17890, attempts: 10, occupied: Set(17890..<17900)) == nil)
    }

    @Test func publicUrlEncodesToken() {
        #expect(lanPublicUrl(ip: "10.0.0.8", port: 17890, token: "") == "http://10.0.0.8:17890/")
        let url = lanPublicUrl(ip: "10.0.0.8", port: 17890, token: "a b")
        #expect(url.hasPrefix("http://10.0.0.8:17890/?"))
        #expect(url.contains("k="))
        #expect(!url.contains("a b"))
    }

    @Test func parseQueryDecodesK() {
        #expect(parseLanQuery("k=a+b")["k"] == "a b")
    }

    @Test func parsesRangeHeaderLine() {
        #expect(parseLanHeaderLines(["Range: bytes=0-1"])["range"] == "bytes=0-1")
    }

    @Test func postIsMethodNotAllowed() {
        let res = handleLanRequest(LanHttpRequest(method: "POST", path: "/", query: [:]), jobs: handlerJobs, token: "", exists: handlerExists, copy: englishLanHistoryCopy())
        #expect(res.status == 405)
    }

    @Test func tokenRequiredWhenSet() {
        let denied = handleLanRequest(LanHttpRequest(method: "GET", path: "/", query: [:]), jobs: handlerJobs, token: "pw", exists: handlerExists, copy: englishLanHistoryCopy())
        #expect(denied.status == 401)
        #expect(String(data: denied.body, encoding: .utf8) == "Password required")
        #expect(!(String(data: denied.body, encoding: .utf8) ?? "").contains("假期.mp4"))
        let ok = handleLanRequest(LanHttpRequest(method: "GET", path: "/", query: ["k": "pw"]), jobs: handlerJobs, token: "pw", exists: handlerExists, copy: englishLanHistoryCopy())
        #expect(ok.status == 200)
        #expect(ok.contentType.hasPrefix("text/html"))
        #expect(String(data: ok.body, encoding: .utf8)?.contains("data-media=\"/m/v\"") == true)
    }

    @Test func downloadAndUnknown() {
        let file = handleLanRequest(LanHttpRequest(method: "GET", path: "/d/v", query: ["k": "pw"]), jobs: handlerJobs, token: "pw", exists: handlerExists, copy: englishLanHistoryCopy())
        #expect(file.status == 200)
        #expect(file.filePath == "/tmp/v.mp4")
        #expect(file.headers["Content-Disposition"]?.contains("attachment") == true)
        #expect(handleLanRequest(LanHttpRequest(method: "GET", path: "/d/q", query: [:]), jobs: handlerJobs, token: "", exists: handlerExists, copy: englishLanHistoryCopy()).status == 404)
        #expect(handleLanRequest(LanHttpRequest(method: "GET", path: "/nope", query: [:]), jobs: handlerJobs, token: "", exists: handlerExists, copy: englishLanHistoryCopy()).status == 404)
        #expect(handleLanRequest(LanHttpRequest(method: "GET", path: "/d/v/9", query: [:]), jobs: handlerJobs, token: "", exists: handlerExists, copy: englishLanHistoryCopy()).status == 404)
    }

    @Test func mediaIsInlineAndHeadOmitsBodyFlag() {
        let get = handleLanRequest(LanHttpRequest(method: "GET", path: "/m/v", query: ["k": "pw"]), jobs: handlerJobs, token: "pw", exists: handlerExists, copy: englishLanHistoryCopy())
        #expect(get.status == 200)
        #expect(get.filePath == "/tmp/v.mp4")
        #expect(get.headers["Content-Disposition"]?.hasPrefix("inline;") == true)
        #expect(get.headers["Accept-Ranges"] == "bytes")
        #expect(get.sendBody)

        let head = handleLanRequest(LanHttpRequest(method: "HEAD", path: "/m/v", query: ["k": "pw"]), jobs: handlerJobs, token: "pw", exists: handlerExists, copy: englishLanHistoryCopy())
        #expect(head.status == 200)
        #expect(head.filePath == "/tmp/v.mp4")
        #expect(!head.sendBody)

        let ranged = handleLanRequest(
            LanHttpRequest(method: "GET", path: "/m/v", query: ["k": "pw"], headers: ["range": "bytes=0-1"]),
            jobs: handlerJobs,
            token: "pw",
            exists: handlerExists,
            copy: englishLanHistoryCopy()
        )
        #expect(ranged.rangeHeader == "bytes=0-1")
    }

    @Test func faviconServesAppIconWithoutToken() {
        let png = handleLanRequest(LanHttpRequest(method: "GET", path: "/favicon.png", query: [:]), jobs: handlerJobs, token: "pw", exists: handlerExists, copy: englishLanHistoryCopy())
        #expect(png.status == 200)
        #expect(png.contentType == "image/png")
        #expect(png.sendBody)
        #expect(png.body.count > 32)
        #expect(png.body.starts(with: [0x89, 0x50, 0x4E, 0x47]))

        let ico = handleLanRequest(LanHttpRequest(method: "GET", path: "/favicon.ico", query: [:]), jobs: handlerJobs, token: "pw", exists: handlerExists, copy: englishLanHistoryCopy())
        #expect(ico.status == 200)
        #expect(ico.contentType == "image/png")
        #expect(ico.body == png.body)

        let head = handleLanRequest(LanHttpRequest(method: "HEAD", path: "/favicon.png", query: [:]), jobs: handlerJobs, token: "pw", exists: handlerExists, copy: englishLanHistoryCopy())
        #expect(head.status == 200)
        #expect(!head.sendBody)
    }

    @Test func headErrorsOmitBody() {
        let denied = handleLanRequest(LanHttpRequest(method: "HEAD", path: "/", query: [:]), jobs: handlerJobs, token: "pw", exists: handlerExists, copy: englishLanHistoryCopy())
        #expect(denied.status == 401)
        #expect(!denied.sendBody)
        let missing = handleLanRequest(LanHttpRequest(method: "HEAD", path: "/nope", query: [:]), jobs: handlerJobs, token: "", exists: handlerExists, copy: englishLanHistoryCopy())
        #expect(missing.status == 404)
        #expect(!missing.sendBody)
    }

    @Test func onlyCompletedExistingIndexedOutputsDownload() {
        let done = lanTestJob(id: "a1", status: .completed, outputPaths: ["/tmp/out.mp4"], displayName: "clip.mp4")
        let exists: (String) -> Bool = { $0 == "/tmp/out.mp4" }
        let ok = resolveLanDownload(jobs: [done], jobId: "a1", index: 0, exists: exists)
        #expect(ok?.path == "/tmp/out.mp4")
        #expect(ok?.downloadName.contains("clip") == true || ok?.downloadName.hasSuffix(".mp4") == true)
        #expect(resolveLanDownload(jobs: [done], jobId: "missing", index: 0, exists: exists) == nil)
        var running = done
        running.status = .running
        #expect(resolveLanDownload(jobs: [running], jobId: "a1", index: 0, exists: exists) == nil)
        #expect(resolveLanDownload(jobs: [done], jobId: "a1", index: 0, exists: { _ in false }) == nil)
        #expect(resolveLanDownload(jobs: [done], jobId: "a1", index: 1, exists: exists) == nil)
    }

    @Test func contentUriDownloadUsesOutputExtensionNotSourceName() {
        let location = "content://media/external/audio/media/99"
        let extracted = lanTestJob(
            id: "a1",
            status: .completed,
            outputPaths: [location],
            displayName: "假期.mp4",
            preset: "audio-mp3"
        )
        let target = resolveLanDownload(jobs: [extracted], jobId: "a1", index: 0, exists: { _ in true })
        #expect(target?.contentType == "audio/mpeg")
        #expect(target?.downloadName.hasSuffix(".mp3") == true)
        #expect(target?.downloadName.hasSuffix(".mp4") == false)
    }
    private var handlerJobs: [Job] {
        [
            lanTestJob(id: "v", status: .completed, outputPaths: ["/tmp/v.mp4"], displayName: "假期.mp4"),
            lanTestJob(id: "q", status: .queued, outputPaths: [], displayName: "wait.mp4", outputPath: nil),
        ]
    }

    private func handlerExists(_ path: String) -> Bool {
        path == "/tmp/v.mp4"
    }
}
