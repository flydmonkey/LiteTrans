import Testing
@testable import LiteTransDomain

struct TrimTests {
    @Test func durationAndTrimFlagsMatchAndroid() {
        let plain = MediaInfo(
            sourceUri: "a",
            displayName: "a.mp4",
            durationSecs: 10,
            importable: true
        )
        #expect(itemHasDuration(plain))
        #expect(!isTrimmed(plain))
        #expect(
            isTrimmed(
                MediaInfo(
                    sourceUri: "a",
                    displayName: "a.mp4",
                    durationSecs: 10,
                    importable: true,
                    trimStartSecs: 1
                )
            )
        )
        #expect(
            !itemHasDuration(
                MediaInfo(sourceUri: "a", displayName: "a.mp4", durationSecs: 0.04, importable: true)
            )
        )
    }

    @Test func timeAtMapsTrackPositionIntoSeconds() {
        #expect(timeAt(x: 50, width: 100, duration: 10) == 5)
        #expect(timeAt(x: -10, width: 100, duration: 10) == 0)
        #expect(timeAt(x: 200, width: 100, duration: 10) == 10)
        #expect(timeAt(x: 50, width: 0, duration: 10) == 0)
    }

    @Test func clampTrimKeepsMinimumGap() {
        let clamped = clampTrim(start: 9.9, end: 10.0, duration: 10.0)
        #expect(clamped.end - clamped.start >= 0.2 - 1e-6)
        #expect(clamped.end == 10.0)
        let wide = clampTrim(start: 1, end: 8, duration: 10)
        #expect(wide.start == 1)
        #expect(wide.end == 8)
        let empty = clampTrim(start: 1, end: 2, duration: 0)
        #expect(empty.start == 0)
        #expect(empty.end == 0)
    }

    @Test func dragTargetPrefersNearbyHandles() {
        #expect(
            trimDragTarget(x: 10, width: 100, duration: 10, start: 1, end: 8, hitSlop: 12) == .start
        )
        #expect(
            trimDragTarget(x: 80, width: 100, duration: 10, start: 1, end: 8, hitSlop: 12) == .end
        )
        #expect(
            trimDragTarget(x: 40, width: 100, duration: 10, start: 1, end: 8, hitSlop: 12) == .playhead
        )
    }
}
