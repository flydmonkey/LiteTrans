import Testing
@testable import LiteTransDomain

struct DocumentTests {
    @Test func kindFromExtension() {
        #expect(documentSourceKind("a.HEIC") == .image)
        #expect(documentSourceKind("a.pdf") == .pdf)
        #expect(documentSourceKind("a.docx") == .word)
        #expect(documentSourceKind("a.xlsx") == .excel)
        #expect(documentSourceKind("a.doc") == nil)
    }

    @Test func sameKindRejectsMix() {
        #expect(sameDocumentKind(existing: ["a.pdf"], incoming: "b.pdf"))
        #expect(!sameDocumentKind(existing: ["a.pdf"], incoming: "b.jpg"))
        #expect(sameDocumentKind(existing: [], incoming: "b.jpg"))
    }

    @Test func clampPages() {
        #expect(clampPageRange(start: 0, end: 99, pageCount: 12) == (1, 12))
        #expect(clampPageRange(start: 5, end: 3, pageCount: 10) == (5, 5))
    }

    @Test func defaultsAndExtensions() {
        #expect(defaultDocumentPreset(.image) == "image-jpg")
        #expect(defaultDocumentPreset(.pdf) == "pdf-image")
        #expect(documentExtension("pdf-image", imageFormat: nil) == "jpg")
        #expect(documentExtension("pdf-image", imageFormat: "png") == "png")
        #expect(documentExtension("pdf-txt", imageFormat: nil) == "txt")
        #expect(documentExtension("pdf-split", imageFormat: nil) == "pdf")
        #expect(documentResultIsImage("image-compress"))
        #expect(documentResultIsImage("pdf-image"))
        #expect(!documentResultIsImage("pdf-split"))
        #expect(isDocumentPreset("office-pdf"))
        #expect(!isDocumentPreset("mp4-h264"))
    }

    @Test func outputCountUsesPageRange() {
        let pdf = MediaInfo(
            sourceUri: "p",
            displayName: "a.pdf",
            importable: true,
            pageCount: 12,
            pageStart: 2,
            pageEnd: 5
        )
        #expect(outputCount(preset: "pdf-split", media: pdf) == 4)
        #expect(outputCount(preset: "image-jpg", media: pdf) == 1)
    }

    @Test func resolveConfigUsesDocumentExtension() throws {
        let split = try resolveConfig(OutputConfig(preset: "pdf-split"))
        #expect(split.container == "pdf")
        #expect(split.extension == "pdf")

        let defaultImage = try resolveConfig(OutputConfig(preset: "pdf-image"))
        #expect(defaultImage.container == "jpg")
        #expect(defaultImage.extension == "jpg")

        let png = try resolveConfig(OutputConfig(preset: "pdf-image", container: "png"))
        #expect(png.container == "png")
        #expect(png.extension == "png")
    }

    @Test func validateRejectsOfficeAndAllowsOtherDocuments() throws {
        let office = try resolveConfig(OutputConfig(preset: "office-pdf"))
        #expect(throws: LiteTransError.officeNotAvailable) {
            try validate(office, media: MediaInfo(sourceUri: "a", displayName: "a.docx", importable: true))
        }

        let split = try resolveConfig(OutputConfig(preset: "pdf-split"))
        try validate(split, media: MediaInfo(sourceUri: "a", displayName: "a.pdf", importable: true, pageCount: 3))
    }

    @Test func officeErrorUsesHumanDescription() {
        #expect(LiteTransError.officeNotAvailable.localizedDescription != String(describing: LiteTransError.officeNotAvailable))
        #expect(!LiteTransError.officeNotAvailable.localizedDescription.contains("officeNotAvailable"))
    }
}
