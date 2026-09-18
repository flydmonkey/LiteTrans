import Foundation

public enum TrimDragTarget: Equatable, Sendable {
    case start
    case end
    case playhead
}

public func itemHasDuration(_ media: MediaInfo) -> Bool {
    media.importable && (media.durationSecs ?? 0) > 0.05
}

public func isTrimmed(_ media: MediaInfo) -> Bool {
    guard itemHasDuration(media), let duration = media.durationSecs else { return false }
    return (media.trimStartSecs ?? 0) > 0.2
        || (media.trimEndSecs != nil && duration - (media.trimEndSecs ?? duration) > 0.2)
}

public func timeAt(x: Double, width: Double, duration: Double) -> Double {
    guard width > 0, duration > 0 else { return 0 }
    return min(max(x / width, 0), 1) * duration
}

public func clampTrim(start: Double, end: Double, duration: Double) -> (start: Double, end: Double) {
    guard duration > 0 else { return (0, 0) }
    let minGap = max(min(0.2, duration / 20), 0.01)
    let startClamped = min(max(start, 0), duration)
    let endClamped = min(max(end, 0), duration)
    if endClamped - startClamped < minGap {
        let nextEnd = min(startClamped + minGap, duration)
        return (max(nextEnd - minGap, 0), nextEnd)
    }
    return (startClamped, endClamped)
}

public func trimDragTarget(
    x: Double,
    width: Double,
    duration: Double,
    start: Double,
    end: Double,
    hitSlop: Double
) -> TrimDragTarget {
    guard width > 0, duration > 0 else { return .playhead }
    let startX = (start / duration) * width
    let endX = (end / duration) * width
    if abs(x - startX) <= hitSlop { return .start }
    if abs(x - endX) <= hitSlop { return .end }
    return .playhead
}

public func mediaFileURL(from sourceUri: String) -> URL? {
    guard let url = URL(string: sourceUri), url.isFileURL else { return nil }
    return url
}
