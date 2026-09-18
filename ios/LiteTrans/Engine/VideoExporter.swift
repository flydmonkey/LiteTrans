import AVFoundation
import Foundation
import Photos

enum VideoExportError: Error, LocalizedError {
    case unsupportedPreset(String)
    case sessionUnavailable
    case missingOutput
    case bookmarkUnresolved
    case photosDenied
    case photosSaveFailed

    var errorDescription: String? {
        switch self {
        case .unsupportedPreset(let preset):
            return "Unsupported preset: \(preset)"
        case .sessionUnavailable:
            return "Could not start export"
        case .missingOutput:
            return "Missing output path"
        case .bookmarkUnresolved:
            return "Could not open the chosen folder"
        case .photosDenied:
            return "Photos access was denied"
        case .photosSaveFailed:
            return "Could not save the video to Photos"
        }
    }
}

struct VideoExporter {
    func export(
        job: Job,
        outputURL: URL,
        saveToPhotos: Bool,
        onProgress: @escaping @Sendable (Double) -> Void
    ) async throws {
        guard avFoundationPresetIDs.contains(job.config.preset) else {
            throw VideoExportError.unsupportedPreset(job.config.preset)
        }
        guard let source = URL(string: job.sourceUri) else {
            throw VideoExportError.sessionUnavailable
        }

        let quality = job.config.quality ?? "standard"
        let size = exportSizeID(maxWidth: job.config.maxWidth, maxHeight: job.config.maxHeight)
        let presetName = avExportPresetName(preset: job.config.preset, quality: quality, size: size)
        let fileType: AVFileType = avExportFileType(preset: job.config.preset) == "mov" ? .mov : .mp4

        let parent = outputURL.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: parent, withIntermediateDirectories: true)
        let partialURL = URL(fileURLWithPath: partialOutputPath(outputURL.path))
        try? FileManager.default.removeItem(at: partialURL)

        let asset = AVURLAsset(url: source)
        guard let session = AVAssetExportSession(asset: asset, presetName: presetName) else {
            throw VideoExportError.sessionUnavailable
        }
        if let range = try await trimRange(for: job, asset: asset) {
            session.timeRange = range
        }

        let box = ExportSessionBox(session)
        let progressTask = Task {
            while !Task.isCancelled {
                onProgress(Double(box.session.progress) * 100)
                try? await Task.sleep(nanoseconds: 200_000_000)
            }
        }

        do {
            try await withTaskCancellationHandler {
                try await box.session.export(to: partialURL, as: fileType)
            } onCancel: {
                box.session.cancelExport()
            }
            progressTask.cancel()
            onProgress(100)
            try replaceOutput(original: outputURL, with: partialURL)
            if saveToPhotos {
                try await saveVideoToPhotos(outputURL)
            }
        } catch {
            progressTask.cancel()
            try? FileManager.default.removeItem(at: partialURL)
            if Task.isCancelled || box.session.status == .cancelled {
                throw CancellationError()
            }
            throw error
        }
    }
}

private final class ExportSessionBox: @unchecked Sendable {
    let session: AVAssetExportSession
    init(_ session: AVAssetExportSession) {
        self.session = session
    }
}

private final class PhotosRequestFlag: @unchecked Sendable {
    var value = false
}

private func trimRange(for job: Job, asset: AVAsset) async throws -> CMTimeRange? {
    let start = job.config.trimStartSecs ?? job.media.trimStartSecs
    let end = job.config.trimEndSecs ?? job.media.trimEndSecs
    guard start != nil || end != nil else { return nil }
    let duration = try await asset.load(.duration)
    let total = CMTimeGetSeconds(duration)
    guard total.isFinite, total > 0 else { return nil }
    let startSecs = max(start ?? 0, 0)
    let endSecs = min(end ?? total, total)
    guard endSecs > startSecs else { return nil }
    let scale = duration.timescale == 0 ? 600 : duration.timescale
    return CMTimeRange(
        start: CMTime(seconds: startSecs, preferredTimescale: scale),
        end: CMTime(seconds: endSecs, preferredTimescale: scale)
    )
}

private func replaceOutput(original: URL, with partial: URL) throws {
    if FileManager.default.fileExists(atPath: original.path) {
        _ = try FileManager.default.replaceItemAt(original, withItemAt: partial)
    } else {
        try FileManager.default.moveItem(at: partial, to: original)
    }
}

func saveVideoToPhotos(_ url: URL) async throws {
    let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
    guard status == .authorized || status == .limited else {
        throw VideoExportError.photosDenied
    }
    let created = PhotosRequestFlag()
    do {
        try await PHPhotoLibrary.shared().performChanges {
            created.value = PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: url) != nil
        }
    } catch {
        throw VideoExportError.photosSaveFailed
    }
    guard created.value else {
        throw VideoExportError.photosSaveFailed
    }
}
