public struct MediaInfo: Equatable, Sendable, Codable {
    public var sourceUri: String
    public var displayName: String
    public var durationSecs: Double?
    public var container: String?
    public var videoCodec: String?
    public var width: Int?
    public var height: Int?
    public var frameRate: Double?
    public var audioCodec: String?
    public var channels: Int?
    public var importable: Bool
    public var error: String?
    public var trimStartSecs: Double?
    public var trimEndSecs: Double?

    public init(
        sourceUri: String,
        displayName: String,
        durationSecs: Double? = nil,
        container: String? = nil,
        videoCodec: String? = nil,
        width: Int? = nil,
        height: Int? = nil,
        frameRate: Double? = nil,
        audioCodec: String? = nil,
        channels: Int? = nil,
        importable: Bool = false,
        error: String? = nil,
        trimStartSecs: Double? = nil,
        trimEndSecs: Double? = nil
    ) {
        self.sourceUri = sourceUri
        self.displayName = displayName
        self.durationSecs = durationSecs
        self.container = container
        self.videoCodec = videoCodec
        self.width = width
        self.height = height
        self.frameRate = frameRate
        self.audioCodec = audioCodec
        self.channels = channels
        self.importable = importable
        self.error = error
        self.trimStartSecs = trimStartSecs
        self.trimEndSecs = trimEndSecs
    }
}

public struct PresetInfo: Equatable, Sendable {
    public let id: String
    public let label: String
    public let description: String
    public init(id: String, label: String, description: String) {
        self.id = id
        self.label = label
        self.description = description
    }
}

public struct OutputConfig: Equatable, Sendable, Codable {
    public var preset: String
    public var container: String?
    public var videoEncoder: String?
    public var maxWidth: Int?
    public var maxHeight: Int?
    public var videoBitrateKbps: Int?
    public var frameRate: Double?
    public var audioEncoder: String?
    public var audioBitrateKbps: Int?
    public var keepAudio: Bool?
    public var quality: String?
    public var trimStartSecs: Double?
    public var trimEndSecs: Double?

    public init(
        preset: String = defaultPreset,
        container: String? = nil,
        videoEncoder: String? = nil,
        maxWidth: Int? = nil,
        maxHeight: Int? = nil,
        videoBitrateKbps: Int? = nil,
        frameRate: Double? = nil,
        audioEncoder: String? = nil,
        audioBitrateKbps: Int? = nil,
        keepAudio: Bool? = nil,
        quality: String? = nil,
        trimStartSecs: Double? = nil,
        trimEndSecs: Double? = nil
    ) {
        self.preset = preset
        self.container = container
        self.videoEncoder = videoEncoder
        self.maxWidth = maxWidth
        self.maxHeight = maxHeight
        self.videoBitrateKbps = videoBitrateKbps
        self.frameRate = frameRate
        self.audioEncoder = audioEncoder
        self.audioBitrateKbps = audioBitrateKbps
        self.keepAudio = keepAudio
        self.quality = quality
        self.trimStartSecs = trimStartSecs
        self.trimEndSecs = trimEndSecs
    }
}

public struct ResolvedConfig: Equatable, Sendable {
    public var preset: String
    public var container: String
    public var `extension`: String
    public var videoEncoder: String?
    public var audioEncoder: String?
    public var maxWidth: Int?
    public var maxHeight: Int?
    public var videoBitrateKbps: Int?
    public var frameRate: Double?
    public var audioBitrateKbps: Int?
    public var keepAudio: Bool
    public var quality: String
    public var trimStartSecs: Double?
    public var trimEndSecs: Double?

    public init(
        preset: String,
        container: String,
        `extension`: String,
        videoEncoder: String?,
        audioEncoder: String?,
        maxWidth: Int?,
        maxHeight: Int?,
        videoBitrateKbps: Int?,
        frameRate: Double?,
        audioBitrateKbps: Int?,
        keepAudio: Bool,
        quality: String,
        trimStartSecs: Double?,
        trimEndSecs: Double?
    ) {
        self.preset = preset
        self.container = container
        self.extension = `extension`
        self.videoEncoder = videoEncoder
        self.audioEncoder = audioEncoder
        self.maxWidth = maxWidth
        self.maxHeight = maxHeight
        self.videoBitrateKbps = videoBitrateKbps
        self.frameRate = frameRate
        self.audioBitrateKbps = audioBitrateKbps
        self.keepAudio = keepAudio
        self.quality = quality
        self.trimStartSecs = trimStartSecs
        self.trimEndSecs = trimEndSecs
    }
}

public enum JobStatus: String, Equatable, Sendable, Codable {
    case queued, running, completed, failed, cancelled
}

public struct Job: Equatable, Sendable, Identifiable, Codable {
    public var id: String
    public var sourceUri: String
    public var displayName: String
    public var outputPath: String?
    public var status: JobStatus
    public var progress: Double
    public var error: String?
    public var config: OutputConfig
    public var media: MediaInfo

    public init(
        id: String,
        sourceUri: String,
        displayName: String,
        outputPath: String?,
        status: JobStatus,
        progress: Double,
        error: String?,
        config: OutputConfig,
        media: MediaInfo
    ) {
        self.id = id
        self.sourceUri = sourceUri
        self.displayName = displayName
        self.outputPath = outputPath
        self.status = status
        self.progress = progress
        self.error = error
        self.config = config
        self.media = media
    }
}

public struct SkippedSource: Equatable, Sendable {
    public var sourceUri: String
    public var displayName: String
    public var reason: String
}

public struct EnqueueReport: Equatable, Sendable {
    public var jobs: [Job]
    public var skipped: [SkippedSource]
}
