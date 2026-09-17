public func ffmpegVideoCodec(_ encoder: String, preferHardware: Bool) -> String {
    switch (encoder, preferHardware) {
    case ("h264", true): return "h264_videotoolbox"
    case ("h265", true): return "hevc_videotoolbox"
    case ("h264", false): return "libx264"
    case ("h265", false): return "libx265"
    case ("vp9", _): return "libvpx-vp9"
    case ("mpeg4", _): return "mpeg4"
    case ("gif", _): return "gif"
    case ("copy", _): return "copy"
    default: return "libx264"
    }
}
