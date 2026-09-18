import Foundation
import Testing
@testable import LiteTransDomain

struct LanHistoryHtmlTests {
    @Test func tabsGroupReadyFilesAndHideSourceUri() {
        let jobs = [
            lanTestJob(id: "v", status: .completed, outputPaths: ["/tmp/v.mp4"], displayName: "假期.mp4", preset: "mp4-h264"),
            lanTestJob(id: "a", status: .queued, outputPaths: ["/tmp/a.mp3"], displayName: "song.mp3", preset: "audio-mp3"),
            lanTestJob(id: "img", status: .completed, outputPaths: ["/tmp/p.png"], displayName: "shot.png", preset: "image-png"),
            lanTestJob(id: "d", status: .completed, outputPaths: ["/tmp/a.pdf", "/tmp/b.pdf"], displayName: "scan.pdf", preset: "pdf-split"),
        ]
        let html = renderLanHistoryHtml(jobs: jobs, token: "pw", copy: englishLanHistoryCopy(), fileExists: { $0.hasPrefix("/tmp/") })
        #expect(html.contains("LiteTrans"))
        #expect(html.contains("data-tab-btn=\"video\""))
        #expect(html.contains("data-tab-btn=\"audio\""))
        #expect(html.contains("data-tab-btn=\"image\""))
        #expect(html.contains("data-tab-btn=\"document\""))
        #expect(html.contains("Images"))
        #expect(html.contains("class=\"player\""))
        #expect(html.contains("<video"))
        #expect(html.contains("data-media=\"/m/v\""))
        #expect(html.contains("data-download=\"/d/v?k="))
        #expect(html.contains("data-tab=\"video\""))
        #expect(html.contains("data-media=\"/m/d/0\""))
        #expect(html.contains("data-media=\"/m/d/1\""))
        #expect(html.contains("data-tab=\"image\""))
        #expect(html.contains("class=\"thumb-src\""))
        #expect(html.contains("src=\"/m/img?k="))
        #expect(!html.contains("content://secret"))
        #expect(!html.contains("song.mp3"))
        #expect(!html.contains("Queued"))
        #expect(!html.contains("/d/a"))
        #expect(!html.contains("/m/a"))
        #expect(html.contains("#ecece8"))
        #expect(html.contains("#111"))
        #expect(html.contains("showTab"))
        #expect(html.contains("prefers-reduced-motion"))
        #expect(!html.contains("<script src"))
        #expect(!html.contains("cdn."))
    }

    @Test func emptyLabelsAndEscapesHtml() {
        let html = renderLanHistoryHtml(
            jobs: [lanTestJob(id: "x", status: .completed, outputPaths: ["/t/a.mp4"], displayName: "<img>")],
            token: "",
            copy: englishLanHistoryCopy(),
            fileExists: { _ in true }
        )
        #expect(html.contains("No audio history yet"))
        #expect(html.contains("No document history yet"))
        #expect(html.contains("No image history yet"))
        #expect(html.contains("&lt;img&gt;") || html.contains("a.mp4"))
        #expect(!html.contains("displayName=\"<img>\""))
        #expect(html.contains("data-download=\"/d/x\""))
        #expect(!html.contains("content://secret"))
    }

    @Test func singleExistingOfManyKeepsIndexWhenNotZero() {
        let html = renderLanHistoryHtml(
            jobs: [lanTestJob(id: "d", status: .completed, outputPaths: ["/tmp/a.pdf", "/tmp/gone.pdf"], displayName: "scan.pdf", preset: "pdf-split")],
            token: "",
            copy: englishLanHistoryCopy(),
            fileExists: { $0 == "/tmp/a.pdf" }
        )
        #expect(html.contains("data-media=\"/m/d/0\"") || html.contains("data-media=\"/m/d\""))
        #expect(!html.contains("/m/d/1"))
        #expect(html.contains(">Download</a>"))
    }

    @Test func missingFileHasNoDownload() {
        let html = renderLanHistoryHtml(
            jobs: [lanTestJob(id: "v", status: .completed, outputPaths: ["/tmp/gone.mp4"], displayName: "gone.mp4")],
            token: "",
            copy: englishLanHistoryCopy(),
            fileExists: { _ in false }
        )
        #expect(!html.contains("href=\"/d/v\""))
        #expect(!html.contains("data-download=\"/d/v\""))
        #expect(!html.contains("data-media=\"/m/v\""))
        #expect(html.contains("data-tab-btn=\"video\""))
    }

    @Test func contentUriOutputUsesDisplayNameForVideoKind() {
        let location = "content://media/external/video/media/42"
        let html = renderLanHistoryHtml(
            jobs: [lanTestJob(id: "v", status: .completed, outputPaths: [location], displayName: "假期.mp4")],
            token: "",
            copy: englishLanHistoryCopy(),
            fileExists: { _ in true }
        )
        #expect(html.contains("data-kind=\"video\""))
        #expect(html.contains("data-media=\"/m/v\""))
        #expect(!html.contains(location))
        #expect(!html.contains("content://secret"))
    }

    @Test func videoPaneListsNewestFirstAndSelectsNewer() {
        let jobs = [
            lanTestJob(id: "old", status: .completed, outputPaths: ["/tmp/old.mp4"], displayName: "old-clip.mp4"),
            lanTestJob(id: "new", status: .completed, outputPaths: ["/tmp/new.mp4"], displayName: "new-clip.mp4"),
        ]
        let html = renderLanHistoryHtml(jobs: jobs, token: "", copy: englishLanHistoryCopy(), fileExists: { _ in true })
        let videoPane = html.slice(after: "data-pane=\"video\"", before: "data-pane=\"audio\"")
        #expect(videoPane.range(of: "data-id=\"new\"")!.lowerBound < videoPane.range(of: "data-id=\"old\"")!.lowerBound)
        #expect(videoPane.range(of: "new.mp4")!.lowerBound < videoPane.range(of: "old.mp4")!.lowerBound)
        let selectedAttrs = videoPane.slice(after: "item selected", before: ">")
        #expect(selectedAttrs.contains("data-id=\"new\""))
        #expect(html.contains("data-tab-btn=\"video\" class=\"on\"") || html.contains("aria-selected=\"true\""))
    }

    @Test func defaultTabIsFirstNonEmpty() {
        let html = renderLanHistoryHtml(
            jobs: [lanTestJob(id: "a", status: .completed, outputPaths: ["/tmp/a.mp3"], displayName: "song.mp3", preset: "audio-mp3")],
            token: "",
            copy: englishLanHistoryCopy(),
            fileExists: { _ in true }
        )
        let audioBtn = html.slice(after: "data-tab-btn=\"audio\"", before: "</button>")
        #expect(audioBtn.contains("class=\"on\"") || audioBtn.contains("aria-selected=\"true\""))
        #expect(html.contains("data-tab=\"audio\""))
        let videoPane = html.slice(after: "data-pane=\"video\"", before: "data-pane=\"audio\"")
        #expect(videoPane.contains("hidden"))
        let audioPane = html.slice(after: "data-pane=\"audio\"", before: "data-pane=\"image\"")
        #expect(!audioPane.contains("hidden"))
    }

    @Test func contentUriAudioOutputUsesPresetContainerNotSourceDisplayName() {
        let location = "content://media/external/audio/media/99"
        let html = renderLanHistoryHtml(
            jobs: [lanTestJob(id: "a", status: .completed, outputPaths: [location], displayName: "假期.mp4", preset: "audio-mp3")],
            token: "",
            copy: englishLanHistoryCopy(),
            fileExists: { _ in true }
        )
        #expect(html.contains("data-kind=\"audio\""))
        #expect(html.contains("data-media=\"/m/a\""))
        #expect(!html.contains(location))
        #expect(!html.contains("content://secret"))
    }

    @Test func pngDoesNotAppearInDocumentPane() {
        let html = renderLanHistoryHtml(
            jobs: [lanTestJob(id: "p", status: .completed, outputPaths: ["/tmp/p.png"], displayName: "shot.png", preset: "image-png")],
            token: "",
            copy: englishLanHistoryCopy(),
            fileExists: { _ in true }
        )
        let docPane = String(html[html.range(of: "data-pane=\"document\"")!.lowerBound...])
        #expect(!docPane.contains("data-id=\"p\""))
        let imagePane = html.slice(after: "data-pane=\"image\"", before: "data-pane=\"document\"")
        #expect(imagePane.contains("data-id=\"p\""))
    }
}

private extension String {
    func slice(after: String, before: String) -> String {
        let tail = String(self[range(of: after)!.upperBound...])
        if let end = tail.range(of: before) {
            return String(tail[..<end.lowerBound])
        }
        return tail
    }
}
