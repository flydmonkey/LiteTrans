import Testing
@testable import LiteTransDomain

struct ProgressTests {
    @Test func parsesOutTimeMs() {
        #expect(parseProgressLine("out_time_ms=5000000", durationSecs: 10) == 50)
    }

    @Test func probeMarksImportableWhenVideoPresent() {
        let json = """
        {"format":{"duration":"2.5","format_name":"mov,mp4,m4a,3gp,3g2,mj2"},"streams":[{"codec_type":"video","codec_name":"h264","width":1920,"height":1080,"r_frame_rate":"30/1"},{"codec_type":"audio","codec_name":"aac","channels":2}]}
        """
        let media = parseFfprobeJson(sourceUri: "u", displayName: "a.mp4", json: json)
        #expect(media.importable)
        #expect(media.videoCodec == "h264")
        #expect(media.width == 1920)
        #expect(media.durationSecs == 2.5)
        #expect(media.frameRate == 30)
    }

    @Test func hardwareCodecsUseVideoToolbox() {
        #expect(ffmpegVideoCodec("h264", preferHardware: true) == "h264_videotoolbox")
        #expect(ffmpegVideoCodec("h265", preferHardware: true) == "hevc_videotoolbox")
        #expect(ffmpegVideoCodec("h264", preferHardware: false) == "libx264")
        #expect(ffmpegVideoCodec("copy", preferHardware: true) == "copy")
    }
}
