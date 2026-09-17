import Testing
@testable import LiteTransDomain

struct NamingTests {
    @Test func stemStripsExtension() {
        #expect(sourceStem("clip.MOV") == "clip")
        #expect(sourceStem("a/b/c.mp4") == "c")
    }

    @Test func partialSitsBeforeExtension() {
        #expect(partialOutputPath("/out/clip.mp4") == "/out/clip.partial.mp4")
        #expect(partialOutputPath("clip.mp4") == "clip.partial.mp4")
    }

    @Test func allocateSkipsExistingAndPartial() {
        let taken: Set<String> = ["/tmp/a.mp4", "/tmp/a.partial.mp4", "/tmp/a-1.mp4"]
        #expect(allocateOutputPath(outputDir: "/tmp", stem: "a", ext: "mp4", exists: { taken.contains($0) }) == "/tmp/a-2.mp4")
    }

    @Test func photosImportPrefersSourceFileName() {
        #expect(
            photosImportDisplayName(
                fileName: "IMG_1234.MOV",
                pathExtension: "mov",
                itemIdentifier: "A1B2C3D4-E5F6-7890-ABCD-EF1234567890/L0/001"
            ) == "IMG_1234.MOV"
        )
    }

    @Test func photosImportRejectsUUIDAndFallsBackToExtension() {
        let uuid = "A1B2C3D4-E5F6-7890-ABCD-EF1234567890"
        #expect(photosImportDisplayName(fileName: uuid, pathExtension: "mp4") == "video.mp4")
        #expect(photosImportDisplayName(fileName: "\(uuid).mov", pathExtension: "mov") == "video.mov")
        #expect(
            photosImportDisplayName(
                fileName: uuid,
                pathExtension: "mp4",
                itemIdentifier: "\(uuid)/L0/001"
            ) == "video.mp4"
        )
        #expect(photosImportDisplayName(fileName: "", pathExtension: "") == "video.mov")
        #expect(photosImportDisplayName(fileName: "", pathExtension: "m4v") == "video.m4v")
    }
}
