import Foundation

public struct ConcatTarget: Equatable, Sendable {
    public var width: Int
    public var height: Int
    public var frameRate: Double
    public init(width: Int, height: Int, frameRate: Double) {
        self.width = width
        self.height = height
        self.frameRate = frameRate
    }
}

public func concatOutputStem(_ displayName: String) -> String {
    "\(sourceStem(displayName))-merged"
}

public func evenDimension(_ value: Int) -> Int {
    max(2, value - (value % 2))
}

public func concatTarget(from first: MediaInfo) throws -> ConcatTarget {
    guard let width = first.width, let height = first.height, width > 0, height > 0 else {
        throw LiteTransError.concatMissingVideo
    }
    return ConcatTarget(
        width: evenDimension(width),
        height: evenDimension(height),
        frameRate: first.frameRate ?? 30
    )
}

public func concatScalePadFilter(target: ConcatTarget) -> String {
    let w = target.width
    let h = target.height
    let fps = String(format: "%.3f", locale: Locale(identifier: "en_US_POSIX"), target.frameRate)
    return "scale=\(w):\(h):force_original_aspect_ratio=decrease,pad=\(w):\(h):(ow-iw)/2:(oh-ih)/2,setsar=1,fps=\(fps)"
}

public func buildConcatNormalizeArgs(
    input: String,
    outputPartial: String,
    media: MediaInfo,
    target: ConcatTarget,
    quality: String,
    preferHardware: Bool = true
) -> [String] {
    var args = [
        "-hide_banner", "-nostats", "-progress", "pipe:1", "-y",
        "-i", ffmpegFileArg(input),
    ]
    let duration = max(media.durationSecs ?? 0, 0.05)
    let missingAudio = media.audioCodec == nil
    if missingAudio {
        args += [
            "-f", "lavfi",
            "-t", String(format: "%.3f", locale: Locale(identifier: "en_US_POSIX"), duration),
            "-i", "anullsrc=channel_layout=stereo:sample_rate=48000",
        ]
    }
    args += ["-c:v", ffmpegVideoCodec("h264", preferHardware: preferHardware)]
    if preferHardware {
        args += hardwareVideoQualityArgs(quality)
    } else {
        args += softwareVideoQualityArgs(encoder: "h264", quality: quality)
    }
    args += [
        "-pix_fmt", "yuv420p",
        "-vf", concatScalePadFilter(target: target),
    ]
    if missingAudio {
        args += ["-map", "0:v:0", "-map", "1:a:0", "-shortest"]
    }
    args += [
        "-c:a", "aac", "-ar", "48000", "-ac", "2", "-b:a", "192k",
        "-movflags", "+faststart",
        "-f", "mp4",
        ffmpegFileArg(outputPartial),
    ]
    return args
}

public func buildConcatJoinArgs(listPath: String, outputPartial: String) -> [String] {
    [
        "-hide_banner", "-nostats", "-progress", "pipe:1", "-y",
        "-f", "concat", "-safe", "0",
        "-i", ffmpegFileArg(listPath),
        "-c", "copy",
        "-movflags", "+faststart",
        "-f", "mp4",
        ffmpegFileArg(outputPartial),
    ]
}

public func concatListFileContents(paths: [String]) -> String {
    paths.map { path in
        let escaped = path.replacingOccurrences(of: "'", with: "'\\''")
        return "file '\(escaped)'"
    }.joined(separator: "\n")
}
