import Foundation
@testable import LiteTransDomain

func lanTestJob(
    id: String,
    status: JobStatus,
    outputPaths: [String],
    displayName: String,
    preset: String = "mp4-h264",
    outputPath: String? = nil
) -> Job {
    Job(
        id: id,
        sourceUri: "content://secret/\(id)",
        displayName: displayName,
        outputPath: outputPath ?? outputPaths.first,
        status: status,
        progress: 1.0,
        error: nil,
        config: OutputConfig(preset: preset),
        media: MediaInfo(sourceUri: "content://secret/\(id)", displayName: displayName, importable: true),
        outputPaths: outputPaths
    )
}

func englishLanHistoryCopy() -> LanHistoryCopy {
    LanHistoryCopy(
        warning: "Anyone on this network who has the address can view history and download finished files.",
        video: "Video",
        audio: "Audio",
        document: "Documents",
        image: "Images",
        emptyVideo: "No video history yet",
        emptyAudio: "No audio history yet",
        emptyDocument: "No document history yet",
        emptyImage: "No image history yet",
        download: "Download",
        downloadNamed: "Download %1$@",
        downloadIndex: "Download #%1$d",
        statusQueued: "Queued",
        statusRunning: "Converting",
        statusCompleted: "Done",
        statusFailed: "Failed",
        statusCancelled: "Cancelled",
        needToken: "Password required",
        previewFailed: "Can't preview. Download the file instead.",
        downloadToOpen: "Download and open it on your computer."
    )
}
