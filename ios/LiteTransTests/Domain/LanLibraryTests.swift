import Foundation
import Testing
@testable import LiteTransDomain

struct LanLibraryTests {
    @Test func groupsByOutputKindNotHistorySegment() {
        let jobs = [
            lanTestJob(id: "v", status: .completed, outputPaths: ["/tmp/v.mp4"], displayName: "clip.mp4", preset: "mp4-h264"),
            lanTestJob(id: "a", status: .completed, outputPaths: ["/tmp/a.mp3"], displayName: "song.mp3", preset: "audio-mp3"),
            lanTestJob(id: "p", status: .completed, outputPaths: ["/tmp/p.png"], displayName: "shot.png", preset: "image-png"),
            lanTestJob(id: "d", status: .completed, outputPaths: ["/tmp/a.pdf"], displayName: "scan.pdf", preset: "pdf-split"),
            lanTestJob(id: "x", status: .completed, outputPaths: ["/tmp/x.docx"], displayName: "doc.docx", preset: "office-pdf"),
        ]
        let items = lanLibraryItems(jobs, fileExists: { _ in true })
        #expect(lanLibraryItemsFor(items, tab: .video).map(\.jobId) == ["v"])
        #expect(lanLibraryItemsFor(items, tab: .audio).map(\.jobId) == ["a"])
        #expect(lanLibraryItemsFor(items, tab: .image).map(\.jobId) == ["p"])
        #expect(lanLibraryItemsFor(items, tab: .document).map(\.jobId) == ["x", "d"])
        #expect(items.first { $0.jobId == "d" }?.kind == .pdf)
        #expect(items.first { $0.jobId == "x" }?.kind == .file)
        #expect(lanLibraryTabWireName(.image) == "image")
    }

    @Test func skipsIncompleteAndMissing() {
        let jobs = [
            lanTestJob(id: "q", status: .queued, outputPaths: ["/tmp/q.mp4"], displayName: "q.mp4"),
            lanTestJob(id: "f", status: .failed, outputPaths: ["/tmp/f.mp4"], displayName: "f.mp4"),
            lanTestJob(id: "g", status: .completed, outputPaths: ["/tmp/gone.mp4"], displayName: "gone.mp4"),
            lanTestJob(id: "ok", status: .completed, outputPaths: ["/tmp/ok.mp4"], displayName: "ok.mp4"),
        ]
        let items = lanLibraryItems(jobs, fileExists: { $0 == "/tmp/ok.mp4" })
        #expect(items.map(\.jobId) == ["ok"])
    }

    @Test func splitsMultiOutputAcrossTabsNewestJobFirst() {
        let jobs = [
            lanTestJob(id: "old", status: .completed, outputPaths: ["/tmp/old.mp4"], displayName: "old.mp4"),
            lanTestJob(id: "mix", status: .completed, outputPaths: ["/tmp/a.pdf", "/tmp/b.png"], displayName: "scan.pdf", preset: "pdf-split"),
        ]
        let items = lanLibraryItems(jobs, fileExists: { _ in true })
        #expect(lanLibraryItemsFor(items, tab: .video).map(\.jobId) == ["old"])
        let images = lanLibraryItemsFor(items, tab: .image)
        #expect(images.count == 1)
        #expect(images[0].jobId == "mix")
        #expect(images[0].index == 1)
        #expect(images[0].needsIndex)
        let docs = lanLibraryItemsFor(items, tab: .document)
        #expect(docs[0].index == 0)
        #expect(docs[0].needsIndex)
    }

    @Test func defaultTabSkipsEmpty() {
        let onlyAudio = lanLibraryItems(
            [lanTestJob(id: "a", status: .completed, outputPaths: ["/tmp/a.mp3"], displayName: "a.mp3", preset: "audio-mp3")],
            fileExists: { _ in true }
        )
        #expect(lanDefaultLibraryTab(onlyAudio) == .audio)
        #expect(lanDefaultLibraryTab([]) == .video)
        let imageThenDoc = lanLibraryItems(
            [
                lanTestJob(id: "p", status: .completed, outputPaths: ["/tmp/p.png"], displayName: "p.png", preset: "image-png"),
                lanTestJob(id: "d", status: .completed, outputPaths: ["/tmp/a.pdf"], displayName: "a.pdf", preset: "pdf-split"),
            ],
            fileExists: { _ in true }
        )
        #expect(lanDefaultLibraryTab(imageThenDoc) == .image)
    }

    @Test func tabAndEmptyLabels() {
        let copy = englishLanHistoryCopy()
        #expect(lanLibraryTabLabel(.image, copy: copy) == "Images")
        #expect(lanLibraryEmptyLabel(.image, copy: copy) == "No image history yet")
        #expect(lanLibraryTabLabel(.video, copy: copy) == "Video")
        #expect(lanLibraryTabLabel(.document, copy: copy) == "Documents")
        #expect(lanLibraryEmptyLabel(.audio, copy: copy) == "No audio history yet")
    }

    @Test func loneLaterIndexStillNeedsIndex() {
        let items = lanLibraryItems(
            [lanTestJob(id: "d", status: .completed, outputPaths: ["/tmp/gone.pdf", "/tmp/b.pdf"], displayName: "scan.pdf", preset: "pdf-split")],
            fileExists: { $0 == "/tmp/b.pdf" }
        )
        #expect(items.single?.index == 1)
        #expect(items.single?.needsIndex == true)
        #expect(items.single?.tab == .document)
    }
}

private extension Array {
    var single: Element? { count == 1 ? first : nil }
}
