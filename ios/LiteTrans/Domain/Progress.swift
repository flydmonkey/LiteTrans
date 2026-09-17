import Foundation

public func parseProgressLine(_ line: String, durationSecs: Double) -> Double? {
    guard let separator = line.firstIndex(of: "=") else { return nil }

    let key = String(line[..<separator])
    if key != "out_time_ms" && key != "out_time_us" { return nil }
    if !durationSecs.isFinite || durationSecs <= 0.0 { return nil }

    let raw = String(line[line.index(after: separator)...]).trimmingCharacters(in: .whitespacesAndNewlines)
    guard let microseconds = Double(raw), microseconds.isFinite else { return nil }
    let seconds = microseconds / 1_000_000.0
    return min(100.0, max(0.0, (seconds / durationSecs) * 100.0))
}
