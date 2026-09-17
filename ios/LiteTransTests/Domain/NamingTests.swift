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
}
