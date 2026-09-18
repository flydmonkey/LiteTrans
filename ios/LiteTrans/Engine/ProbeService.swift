import AVFoundation
import CoreMedia
import Foundation
import ImageIO
import PDFKit
@preconcurrency import ffmpegkit

struct ProbeService {
    func probe(
        url: URL,
        displayName: String,
        mode: ConvertMode = .video,
        preset: String = defaultPreset,
        language: AppLanguage = .system
    ) async -> MediaInfo {
        let name = displayName.isEmpty ? url.lastPathComponent : displayName
        if let kind = documentSourceKind(name) {
            switch kind {
            case .word:
                return MediaInfo(
                    sourceUri: url.absoluteString,
                    displayName: name,
                    container: "docx",
                    importable: true,
                    probing: false
                )
            case .excel:
                return blocked(
                    url: url,
                    displayName: name,
                    container: url.pathExtension.lowercased(),
                    error: localized("error_office_later", language: language)
                )
            case .pdf:
                return probePDF(url: url, displayName: name, language: language)
            case .image:
                return probeImage(url: url, displayName: name, language: language)
            }
        }

        if mode == .document {
            return blocked(
                url: url,
                displayName: name,
                container: containerName(url),
                error: localized("error_unsupported_document", language: language)
            )
        }

        return await probeAV(
            url: url,
            displayName: name,
            mode: mode,
            preset: preset,
            language: language
        )
    }
}

private func probePDF(url: URL, displayName: String, language: AppLanguage) -> MediaInfo {
    guard let document = PDFDocument(url: url) else {
        return blocked(
            url: url,
            displayName: displayName,
            container: "pdf",
            error: localized("error_unsupported_document", language: language)
        )
    }
    if document.isEncrypted || document.isLocked {
        return blocked(
            url: url,
            displayName: displayName,
            container: "pdf",
            error: localized("error_encrypted_pdf", language: language)
        )
    }
    let pageCount = max(document.pageCount, 1)
    return MediaInfo(
        sourceUri: url.absoluteString,
        displayName: displayName,
        container: "pdf",
        importable: true,
        probing: false,
        pageCount: pageCount,
        pageStart: 1,
        pageEnd: pageCount
    )
}

private func probeImage(url: URL, displayName: String, language: AppLanguage) -> MediaInfo {
    guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
          CGImageSourceGetCount(source) > 0,
          let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]
    else {
        return blocked(
            url: url,
            displayName: displayName,
            container: containerName(url),
            error: localized("error_unsupported_document", language: language)
        )
    }
    let width = intProperty(props[kCGImagePropertyPixelWidth])
    let height = intProperty(props[kCGImagePropertyPixelHeight])
    return MediaInfo(
        sourceUri: url.absoluteString,
        displayName: displayName,
        container: containerName(url),
        width: width == 0 ? nil : width,
        height: height == 0 ? nil : height,
        importable: true,
        probing: false
    )
}

private func probeAV(
    url: URL,
    displayName: String,
    mode: ConvertMode,
    preset: String,
    language: AppLanguage
) async -> MediaInfo {
    let audioOnlyTarget = mode == .audio || preset.hasPrefix("audio-")
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
        if audioOnlyTarget && audioTracks.isEmpty && !videoTracks.isEmpty {
            return MediaInfo(
                sourceUri: url.absoluteString,
                displayName: displayName,
                durationSecs: seconds.isFinite && seconds > 0 ? seconds : nil,
                container: containerName(url),
                videoCodec: videoCodec,
                width: width == 0 ? nil : width,
                height: height == 0 ? nil : height,
                frameRate: frameRate,
                importable: false,
                error: localized("error_no_audio", language: language),
                probing: false
            )
        }

        let hasAV = videoTracks.isEmpty == false || audioTracks.isEmpty == false
        if hasAV {
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
                importable: true,
                probing: false
            )
        }
    } catch {
        let fallback = await probeFFprobe(
            url: url,
            displayName: displayName,
            audioOnlyTarget: audioOnlyTarget,
            language: language
        )
        if fallback.importable || fallback.error == localized("error_no_audio", language: language) {
            return fallback
        }
        return MediaInfo(
            sourceUri: url.absoluteString,
            displayName: displayName,
            importable: false,
            error: error.localizedDescription,
            probing: false
        )
    }

    return await probeFFprobe(
        url: url,
        displayName: displayName,
        audioOnlyTarget: audioOnlyTarget,
        language: language
    )
}

private func probeFFprobe(
    url: URL,
    displayName: String,
    audioOnlyTarget: Bool,
    language: AppLanguage
) async -> MediaInfo {
    let path = url.isFileURL ? url.path : url.absoluteString
    let json = await executeFFprobe([
        "-v", "error",
        "-show_format",
        "-show_streams",
        "-print_format", "json",
        ffmpegFileArg(path),
    ])
    guard let json, !json.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
        return MediaInfo(
            sourceUri: url.absoluteString,
            displayName: displayName,
            importable: false,
            error: "No convertible video or audio stream",
            probing: false
        )
    }
    let probed = parseFfprobeJson(sourceUri: url.absoluteString, displayName: displayName, json: json)
    if audioOnlyTarget && probed.audioCodec == nil {
        return MediaInfo(
            sourceUri: url.absoluteString,
            displayName: displayName,
            durationSecs: probed.durationSecs,
            container: probed.container,
            videoCodec: probed.videoCodec,
            width: probed.width,
            height: probed.height,
            frameRate: probed.frameRate,
            importable: false,
            error: localized("error_no_audio", language: language),
            probing: false
        )
    }
    return probed
}

private func executeFFprobe(_ arguments: [String]) async -> String? {
    await withCheckedContinuation { continuation in
        _ = FFprobeKit.execute(withArgumentsAsync: arguments, withCompleteCallback: { session in
            continuation.resume(returning: session?.getOutput() as String?)
        })
    }
}

private func blocked(url: URL, displayName: String, container: String?, error: String) -> MediaInfo {
    MediaInfo(
        sourceUri: url.absoluteString,
        displayName: displayName,
        container: container,
        importable: false,
        error: error,
        probing: false
    )
}

private func localized(_ key: String.LocalizationValue, language: AppLanguage) -> String {
    localizedText(key, language: language)
}

private func intProperty(_ value: Any?) -> Int? {
    switch value {
    case let number as NSNumber: return number.intValue
    case let int as Int: return int
    default: return nil
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
