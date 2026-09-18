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
    public var probing: Bool
    public var pageCount: Int?
    public var pageStart: Int?
    public var pageEnd: Int?

    enum CodingKeys: String, CodingKey {
        case sourceUri, displayName, durationSecs, container, videoCodec, width, height
        case frameRate, audioCodec, channels, importable, error, trimStartSecs, trimEndSecs, probing
        case pageCount, pageStart, pageEnd
    }

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
        trimEndSecs: Double? = nil,
        probing: Bool = false,
        pageCount: Int? = nil,
        pageStart: Int? = nil,
        pageEnd: Int? = nil
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
        self.probing = probing
        self.pageCount = pageCount
        self.pageStart = pageStart
        self.pageEnd = pageEnd
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        sourceUri = try container.decode(String.self, forKey: .sourceUri)
        displayName = try container.decode(String.self, forKey: .displayName)
        durationSecs = try container.decodeIfPresent(Double.self, forKey: .durationSecs)
        self.container = try container.decodeIfPresent(String.self, forKey: .container)
        videoCodec = try container.decodeIfPresent(String.self, forKey: .videoCodec)
        width = try container.decodeIfPresent(Int.self, forKey: .width)
        height = try container.decodeIfPresent(Int.self, forKey: .height)
        frameRate = try container.decodeIfPresent(Double.self, forKey: .frameRate)
        audioCodec = try container.decodeIfPresent(String.self, forKey: .audioCodec)
        channels = try container.decodeIfPresent(Int.self, forKey: .channels)
        importable = try container.decodeIfPresent(Bool.self, forKey: .importable) ?? false
        error = try container.decodeIfPresent(String.self, forKey: .error)
        trimStartSecs = try container.decodeIfPresent(Double.self, forKey: .trimStartSecs)
        trimEndSecs = try container.decodeIfPresent(Double.self, forKey: .trimEndSecs)
        probing = try container.decodeIfPresent(Bool.self, forKey: .probing) ?? false
        pageCount = try container.decodeIfPresent(Int.self, forKey: .pageCount)
        pageStart = try container.decodeIfPresent(Int.self, forKey: .pageStart)
        pageEnd = try container.decodeIfPresent(Int.self, forKey: .pageEnd)
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
    public var concatSourceUris: [String]

    enum CodingKeys: String, CodingKey {
        case preset, container, videoEncoder, maxWidth, maxHeight, videoBitrateKbps, frameRate
        case audioEncoder, audioBitrateKbps, keepAudio, quality, trimStartSecs, trimEndSecs
        case concatSourceUris
    }

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
        trimEndSecs: Double? = nil,
        concatSourceUris: [String] = []
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
        self.concatSourceUris = concatSourceUris
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        preset = try container.decode(String.self, forKey: .preset)
        self.container = try container.decodeIfPresent(String.self, forKey: .container)
        videoEncoder = try container.decodeIfPresent(String.self, forKey: .videoEncoder)
        maxWidth = try container.decodeIfPresent(Int.self, forKey: .maxWidth)
        maxHeight = try container.decodeIfPresent(Int.self, forKey: .maxHeight)
        videoBitrateKbps = try container.decodeIfPresent(Int.self, forKey: .videoBitrateKbps)
        frameRate = try container.decodeIfPresent(Double.self, forKey: .frameRate)
        audioEncoder = try container.decodeIfPresent(String.self, forKey: .audioEncoder)
        audioBitrateKbps = try container.decodeIfPresent(Int.self, forKey: .audioBitrateKbps)
        keepAudio = try container.decodeIfPresent(Bool.self, forKey: .keepAudio)
        quality = try container.decodeIfPresent(String.self, forKey: .quality)
        trimStartSecs = try container.decodeIfPresent(Double.self, forKey: .trimStartSecs)
        trimEndSecs = try container.decodeIfPresent(Double.self, forKey: .trimEndSecs)
        concatSourceUris = try container.decodeIfPresent([String].self, forKey: .concatSourceUris) ?? []
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
    public var outputPaths: [String]
    public var outputKind: OutputKind
    public var createdAtEpochMs: Int64?
    public var concatMedias: [MediaInfo]

    enum CodingKeys: String, CodingKey {
        case id, sourceUri, displayName, outputPath, status, progress, error, config, media
        case outputPaths, outputKind, createdAtEpochMs, concatMedias
    }

    public init(
        id: String,
        sourceUri: String,
        displayName: String,
        outputPath: String?,
        status: JobStatus,
        progress: Double,
        error: String?,
        config: OutputConfig,
        media: MediaInfo,
        outputPaths: [String] = [],
        outputKind: OutputKind = .downloads,
        createdAtEpochMs: Int64? = nil,
        concatMedias: [MediaInfo] = []
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
        self.outputPaths = outputPaths
        self.outputKind = outputKind
        self.createdAtEpochMs = createdAtEpochMs
        self.concatMedias = concatMedias
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        sourceUri = try container.decode(String.self, forKey: .sourceUri)
        displayName = try container.decode(String.self, forKey: .displayName)
        outputPath = try container.decodeIfPresent(String.self, forKey: .outputPath)
        status = try container.decode(JobStatus.self, forKey: .status)
        progress = try container.decode(Double.self, forKey: .progress)
        error = try container.decodeIfPresent(String.self, forKey: .error)
        config = try container.decode(OutputConfig.self, forKey: .config)
        media = try container.decode(MediaInfo.self, forKey: .media)
        outputPaths = try container.decodeIfPresent([String].self, forKey: .outputPaths) ?? []
        outputKind = try container.decodeIfPresent(OutputKind.self, forKey: .outputKind) ?? .downloads
        createdAtEpochMs = try container.decodeIfPresent(Int64.self, forKey: .createdAtEpochMs)
        concatMedias = try container.decodeIfPresent([MediaInfo].self, forKey: .concatMedias) ?? []
    }
}

public struct SkippedSource: Equatable, Sendable {
    public var sourceUri: String
    public var displayName: String
    public var reason: String

    public init(sourceUri: String, displayName: String, reason: String) {
        self.sourceUri = sourceUri
        self.displayName = displayName
        self.reason = reason
    }
}

public struct EnqueueReport: Equatable, Sendable {
    public var jobs: [Job]
    public var skipped: [SkippedSource]

    public init(jobs: [Job], skipped: [SkippedSource]) {
        self.jobs = jobs
        self.skipped = skipped
    }
}
