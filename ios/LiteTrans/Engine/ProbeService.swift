import AVFoundation
import CoreMedia
import Foundation

struct ProbeService {
    func probe(url: URL, displayName: String) async -> MediaInfo {
        let asset = AVURLAsset(url: url)
        do {
            async let durationLoad = asset.load(.duration)
            async let videoLoad = asset.loadTracks(withMediaType: .video)
            async let audioLoad = asset.loadTracks(withMediaType: .audio)
            let duration = try await durationLoad
            let videoTracks = try await videoLoad
            let audioTracks = try await audioLoad

            var width: Int?
            var height: Int?
            var frameRate: Double?
            var videoCodec: String?
            if let track = videoTracks.first {
                let size = try await track.load(.naturalSize)
                let transform = try await track.load(.preferredTransform)
                let rendered = size.applying(transform)
                width = Int(abs(rendered.width).rounded())
                height = Int(abs(rendered.height).rounded())
                let rate = try await track.load(.nominalFrameRate)
                if rate > 0 {
                    frameRate = Double(rate)
                }
                if let format = try await track.load(.formatDescriptions).first {
                    videoCodec = videoCodecName(format)
                }
            }

            var audioCodec: String?
            var channels: Int?
            if let track = audioTracks.first {
                if let format = try await track.load(.formatDescriptions).first {
                    audioCodec = audioCodecName(format)
                    channels = channelCount(format)
                }
            }

            let seconds = CMTimeGetSeconds(duration)
            let hasAV = videoTracks.isEmpty == false || audioTracks.isEmpty == false
            return MediaInfo(
                sourceUri: url.absoluteString,
                displayName: displayName,
                durationSecs: seconds.isFinite && seconds > 0 ? seconds : nil,
                container: containerName(url),
                videoCodec: videoCodec,
                width: width == 0 ? nil : width,
                height: height == 0 ? nil : height,
                frameRate: frameRate,
                audioCodec: audioCodec,
                channels: channels,
                importable: hasAV,
                error: hasAV ? nil : "No convertible video or audio stream",
                probing: false
            )
        } catch {
            return MediaInfo(
                sourceUri: url.absoluteString,
                displayName: displayName,
                importable: false,
                error: error.localizedDescription,
                probing: false
            )
        }
    }
}

private func containerName(_ url: URL) -> String? {
    let ext = url.pathExtension.lowercased()
    return ext.isEmpty ? nil : ext
}

private func fourCC(_ value: FourCharCode) -> String {
    let bytes: [UInt8] = [
        UInt8((value >> 24) & 0xff),
        UInt8((value >> 16) & 0xff),
        UInt8((value >> 8) & 0xff),
        UInt8(value & 0xff),
    ]
    return String(bytes: bytes, encoding: .ascii)?
        .trimmingCharacters(in: .whitespaces) ?? String(value)
}

private func videoCodecName(_ format: CMFormatDescription) -> String {
    switch CMFormatDescriptionGetMediaSubType(format) {
    case kCMVideoCodecType_H264: return "h264"
    case kCMVideoCodecType_HEVC, kCMVideoCodecType_HEVCWithAlpha: return "hevc"
    case kCMVideoCodecType_MPEG4Video: return "mpeg4"
    case kCMVideoCodecType_MPEG2Video: return "mpeg2video"
    case kCMVideoCodecType_MPEG1Video: return "mpeg1video"
    case kCMVideoCodecType_JPEG: return "mjpeg"
    case kCMVideoCodecType_AppleProRes422, kCMVideoCodecType_AppleProRes4444: return "prores"
    case FourCharCode(0x6176_3031): return "av1" // av01
    case FourCharCode(0x7670_3039): return "vp9" // vp09
    default: return fourCC(CMFormatDescriptionGetMediaSubType(format))
    }
}

private func audioCodecName(_ format: CMFormatDescription) -> String {
    switch CMFormatDescriptionGetMediaSubType(format) {
    case kAudioFormatMPEG4AAC, kAudioFormatMPEG4AAC_HE, kAudioFormatMPEG4AAC_LD,
         kAudioFormatMPEG4AAC_ELD, kAudioFormatMPEG4AAC_HE_V2:
        return "aac"
    case kAudioFormatMPEGLayer3: return "mp3"
    case kAudioFormatAppleLossless: return "alac"
    case kAudioFormatFLAC: return "flac"
    case kAudioFormatAMR: return "amr_nb"
    case kAudioFormatAMR_WB: return "amr_wb"
    case kAudioFormatOpus: return "opus"
    case kAudioFormatAC3, kAudioFormatEnhancedAC3: return "ac3"
    case kAudioFormatLinearPCM: return "pcm_s16le"
    default: return fourCC(CMFormatDescriptionGetMediaSubType(format))
    }
}

private func channelCount(_ format: CMFormatDescription) -> Int? {
    guard let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(format)?.pointee else {
        return nil
    }
    let channels = Int(asbd.mChannelsPerFrame)
    return channels > 0 ? channels : nil
}
