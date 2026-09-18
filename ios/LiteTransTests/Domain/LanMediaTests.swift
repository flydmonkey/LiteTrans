import Foundation
import Testing
@testable import LiteTransDomain

struct LanMediaTests {
    @Test func previewKindByExtension() {
        #expect(lanPreviewKind("a.mp4") == .video)
        #expect(lanPreviewKind("a.MKV") == .video)
        #expect(lanPreviewKind("a.mp3") == .audio)
        #expect(lanPreviewKind("a.pdf") == .pdf)
        #expect(lanPreviewKind("a.png") == .image)
        #expect(lanPreviewKind("a.gif") == .image)
        #expect(lanPreviewKind("a.docx") == .file)
        #expect(lanPreviewKind("a.xlsx") == .file)
        #expect(lanPreviewKind("a.bin") == .file)
    }

    @Test func contentTypeCoversPlayableContainers() {
        #expect(lanContentType("a.mp4") == "video/mp4")
        #expect(lanContentType("a.mov") == "video/quicktime")
        #expect(lanContentType("a.mkv") == "video/x-matroska")
        #expect(lanContentType("a.webm") == "video/webm")
        #expect(lanContentType("a.avi") == "video/x-msvideo")
        #expect(lanContentType("a.pdf") == "application/pdf")
        #expect(lanContentType("a.bin") == "application/octet-stream")
    }

    @Test func dispositionInlineVsAttachment() {
        #expect(lanContentDisposition("a.mp4").hasPrefix("attachment;"))
        #expect(lanContentDisposition("a.mp4", inline: true).hasPrefix("inline;"))
        #expect(!lanContentDisposition("a\r\nb.mp4").contains("\r"))
        #expect(!lanContentDisposition("a\r\nb.mp4").contains("\n"))
    }

    @Test func contentLocationDetection() {
        #expect(isLanContentLocation("content://media/external/video/123"))
        #expect(!isLanContentLocation("/storage/emulated/0/Download/a.mp4"))
        #expect(!isLanContentLocation("file:///tmp/a.mp4"))
    }

    @Test func parseByteRange() {
        #expect(parseLanByteRange(nil, total: 100) == .whole)
        #expect(parseLanByteRange("", total: 100) == .whole)
        #expect(parseLanByteRange("bytes=0-49", total: 100) == .partial(start: 0, endInclusive: 49))
        #expect(parseLanByteRange("Bytes=50-", total: 100) == .partial(start: 50, endInclusive: 99))
        #expect(parseLanByteRange("bytes=0-9999", total: 100) == .partial(start: 0, endInclusive: 99))
        #expect(parseLanByteRange("bytes=100-110", total: 100) == .unsatisfiable)
        #expect(parseLanByteRange("bytes=80-20", total: 100) == .unsatisfiable)
        #expect(parseLanByteRange("bytes=0-10,11-20", total: 100) == .unsatisfiable)
        #expect(lanContentRangeValue(start: 0, endInclusive: 49, total: 100) == "bytes 0-49/100")
        #expect(lanUnsatisfiableContentRange(100) == "bytes */100")
    }

    @Test func copyLanRangeSkipsAndLimits() {
        let sliced = copyLanRange(from: Data([10, 11, 12, 13, 14]), start: 1, length: 3)
        #expect(sliced == Data([11, 12, 13]))
    }

    @Test func wholeFileKeeps200() {
        let out = applyLanResponseRange(baseResponse(), total: 100)
        #expect(out.status == 200)
        #expect(out.headers["Content-Length"] == "100")
        #expect(out.byteStart == 0)
        #expect(out.byteLength == nil)
    }

    @Test func partialIs206() {
        var base = baseResponse()
        base.rangeHeader = "bytes=0-9"
        let out = applyLanResponseRange(base, total: 100)
        #expect(out.status == 206)
        #expect(out.headers["Content-Range"] == "bytes 0-9/100")
        #expect(out.headers["Content-Length"] == "10")
        #expect(out.byteStart == 0)
        #expect(out.byteLength == 10)
    }

    @Test func badRangeIs416() {
        var base = baseResponse()
        base.rangeHeader = "bytes=500-600"
        let out = applyLanResponseRange(base, total: 100)
        #expect(out.status == 416)
        #expect(out.headers["Content-Range"] == "bytes */100")
        #expect(out.filePath == nil)
        #expect(out.sendBody == false)
    }

    @Test func openFailureBecomes404BeforeHeaders() {
        let failed = lanReadyFileResponse(baseResponse(), opened: false)
        #expect(failed.status == 404)
        #expect(failed.contentType == "text/plain; charset=utf-8")
        #expect(String(data: failed.body, encoding: .utf8) == "Not Found")
        #expect(failed.filePath == nil)
        #expect(failed.sendBody == true)

        var head = baseResponse()
        head.sendBody = false
        let headFailed = lanReadyFileResponse(head, opened: false)
        #expect(headFailed.status == 404)
        #expect(headFailed.sendBody == false)

        var partial = baseResponse()
        partial.status = 206
        let kept = lanReadyFileResponse(partial, opened: true)
        #expect(kept.status == 206)
        #expect(kept.filePath == "/tmp/a.mp4")

        let memory = lanReadyFileResponse(
            LanHttpResponse(status: 200, contentType: "text/plain", body: Data("ok".utf8), filePath: nil),
            opened: false
        )
        #expect(memory.status == 200)
        #expect(String(data: memory.body, encoding: .utf8) == "ok")
    }

    private func baseResponse() -> LanHttpResponse {
        LanHttpResponse(
            status: 200,
            contentType: "video/mp4",
            body: Data(),
            headers: [
                "Content-Disposition": "inline; filename=\"a.mp4\"",
                "Accept-Ranges": "bytes",
            ],
            filePath: "/tmp/a.mp4",
            rangeHeader: nil,
            sendBody: true
        )
    }
}
