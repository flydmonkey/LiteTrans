import Foundation

public func parseProgressLine(_ line: String, durationSecs: Double) -> Double? {
    if !durationSecs.isFinite || durationSecs <= 0.0 { return nil }
    let payload = progressPayload(line)
    if let percent = parseOutTimeProgress(payload, durationSecs: durationSecs) {
        return percent
    }
    if let seconds = parseFfmpegTimeLine(payload) {
        return min(100.0, max(0.0, (seconds / durationSecs) * 100.0))
    }
    return nil
}

public func parseFfmpegTimeLine(_ line: String) -> Double? {
    guard let marker = line.range(of: "time=") else { return nil }
    let raw = String(line[marker.upperBound...])
        .split(whereSeparator: { $0.isWhitespace || $0 == "," })
        .first
        .map(String.init) ?? ""
    return parseFfmpegClock(raw)
}

private func progressPayload(_ line: String) -> String {
    let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
    for marker in ["out_time_ms=", "out_time_us=", "time="] {
        if let range = trimmed.range(of: marker) {
            return String(trimmed[range.lowerBound...])
        }
    }
    return trimmed
}

private func parseOutTimeProgress(_ line: String, durationSecs: Double) -> Double? {
    guard let separator = line.firstIndex(of: "=") else { return nil }
    let key = String(line[..<separator])
    if key != "out_time_ms" && key != "out_time_us" { return nil }

    let raw = String(line[line.index(after: separator)...]).trimmingCharacters(in: .whitespacesAndNewlines)
    guard let microseconds = Double(raw), microseconds.isFinite else { return nil }
    let seconds = microseconds / 1_000_000.0
    return min(100.0, max(0.0, (seconds / durationSecs) * 100.0))
}

private func parseFfmpegClock(_ raw: String) -> Double? {
    let parts = raw.split(separator: ":")
    guard parts.count == 3,
          let hours = Double(parts[0]),
          let minutes = Double(parts[1]),
          let seconds = Double(parts[2]),
          hours.isFinite, minutes.isFinite, seconds.isFinite
    else {
        return nil
    }
    return hours * 3600 + minutes * 60 + seconds
}
