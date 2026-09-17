import Foundation

public func parseFfprobeJson(
    sourceUri: String,
    displayName: String,
    json: String,
    cannotParse: String = "Could not parse media information",
    noAvStream: String = "No convertible video or audio stream"
) -> MediaInfo {
    do {
        guard let data = json.data(using: .utf8) else {
            return unreadable(sourceUri: sourceUri, displayName: displayName, reason: cannotParse)
        }
        let object = try JSONSerialization.jsonObject(with: data)
        guard let probe = object as? [String: Any] else {
            return unreadable(sourceUri: sourceUri, displayName: displayName, reason: cannotParse)
        }
        return mapProbe(sourceUri: sourceUri, displayName: displayName, probe: probe, noAvStream: noAvStream)
    } catch {
        return unreadable(sourceUri: sourceUri, displayName: displayName, reason: cannotParse)
    }
}

public func unreadable(sourceUri: String, displayName: String, reason: String) -> MediaInfo {
    MediaInfo(
        sourceUri: sourceUri,
        displayName: displayName,
        importable: false,
        error: reason
    )
}

private func mapProbe(
    sourceUri: String,
    displayName: String,
    probe: [String: Any],
    noAvStream: String
) -> MediaInfo {
    let format = probe["format"] as? [String: Any]
    let streams = probe["streams"] as? [[String: Any]]
    var video: [String: Any]?
    var audio: [String: Any]?

    if let streams {
        for stream in streams {
            switch optionalString(stream, "codec_type") {
            case "video":
                if video == nil { video = stream }
            case "audio":
                if audio == nil { audio = stream }
            default:
                break
            }
        }
    }

    let hasAv = video != nil || audio != nil
    return MediaInfo(
        sourceUri: sourceUri,
        displayName: displayName,
        durationSecs: format.flatMap { optionalString($0, "duration") }.flatMap(Double.init),
        container: format.flatMap { optionalString($0, "format_name") },
        videoCodec: video.flatMap { optionalString($0, "codec_name") },
        width: video.flatMap { optionalInt($0, "width") },
        height: video.flatMap { optionalInt($0, "height") },
        frameRate: parseFrameRate(video.flatMap { optionalString($0, "r_frame_rate") }),
        audioCodec: audio.flatMap { optionalString($0, "codec_name") },
        channels: audio.flatMap { optionalInt($0, "channels") },
        importable: hasAv,
        error: hasAv ? nil : noAvStream
    )
}

private func parseFrameRate(_ value: String?) -> Double? {
    guard let value else { return nil }
    guard let separator = value.firstIndex(of: "/") else {
        return Double(value)
    }

    guard let numerator = Double(value[..<separator]),
          let denominator = Double(value[value.index(after: separator)...])
    else {
        return nil
    }
    return denominator == 0.0 ? nil : numerator / denominator
}

private func optionalString(_ object: [String: Any], _ name: String) -> String? {
    guard let value = object[name], !(value is NSNull) else { return nil }
    if let string = value as? String { return string }
    if let number = value as? NSNumber { return number.stringValue }
    return nil
}

private func optionalInt(_ object: [String: Any], _ name: String) -> Int? {
    guard let value = object[name], !(value is NSNull) else { return nil }
    if let int = value as? Int { return int }
    if let number = value as? NSNumber { return number.intValue }
    if let string = value as? String { return Int(string) }
    return nil
}
