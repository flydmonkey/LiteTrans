import Foundation

struct SessionSnapshot: Equatable, Codable, Sendable {
    var language: AppLanguage
    var convertMode: ConvertMode
    var historySegment: HistorySegment
    var video: WizardSession
    var audio: WizardSession
    var document: WizardSession
    var schemaVersion: Int

    static let currentSchemaVersion = 2

    init(
        language: AppLanguage = .system,
        convertMode: ConvertMode = .video,
        historySegment: HistorySegment = .video,
        video: WizardSession = defaultSession(.video),
        audio: WizardSession = defaultSession(.audio),
        document: WizardSession = defaultSession(.document),
        schemaVersion: Int = SessionSnapshot.currentSchemaVersion
    ) {
        self.language = language
        self.convertMode = convertMode
        self.historySegment = historySegment
        self.video = Self.withoutSources(video)
        self.audio = Self.withoutSources(audio)
        self.document = Self.withoutSources(document)
        self.schemaVersion = schemaVersion
    }

    enum CodingKeys: String, CodingKey {
        case language, convertMode, historySegment, video, audio, document
        case preset, quality, size, output
        case schemaVersion
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        language = try container.decodeIfPresent(AppLanguage.self, forKey: .language) ?? .system
        schemaVersion = try container.decodeIfPresent(Int.self, forKey: .schemaVersion) ?? 1
        if container.contains(.video) {
            video = Self.withoutSources(try container.decode(WizardSession.self, forKey: .video))
            audio = Self.withoutSources(
                try container.decodeIfPresent(WizardSession.self, forKey: .audio) ?? defaultSession(.audio)
            )
            document = Self.withoutSources(
                try container.decodeIfPresent(WizardSession.self, forKey: .document) ?? defaultSession(.document)
            )
            convertMode = try container.decodeIfPresent(ConvertMode.self, forKey: .convertMode) ?? .video
            historySegment = try container.decodeIfPresent(HistorySegment.self, forKey: .historySegment) ?? .video
        } else {
            var migrated = defaultSession(.video)
            migrated.preset = try container.decodeIfPresent(String.self, forKey: .preset) ?? migrated.preset
            migrated.quality = try container.decodeIfPresent(String.self, forKey: .quality) ?? migrated.quality
            migrated.size = try container.decodeIfPresent(String.self, forKey: .size) ?? migrated.size
            migrated.output = try container.decodeIfPresent(OutputTarget.self, forKey: .output) ?? migrated.output
            video = migrated
            audio = defaultSession(.audio)
            document = defaultSession(.document)
            convertMode = .video
            historySegment = .video
        }
        if schemaVersion < 2 {
            if video.quality == "standard" {
                video.quality = "original"
            }
            schemaVersion = 2
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(language, forKey: .language)
        try container.encode(convertMode, forKey: .convertMode)
        try container.encode(historySegment, forKey: .historySegment)
        try container.encode(Self.withoutSources(video), forKey: .video)
        try container.encode(Self.withoutSources(audio), forKey: .audio)
        try container.encode(Self.withoutSources(document), forKey: .document)
        try container.encode(schemaVersion, forKey: .schemaVersion)
    }

    static func withoutSources(_ session: WizardSession) -> WizardSession {
        var next = session
        next.sources = []
        next.selectedUri = nil
        return next
    }
}

struct SessionStore {
    static let key = "liteTrans.session"
    let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load() -> SessionSnapshot? {
        guard let data = defaults.data(forKey: Self.key) else { return nil }
        return try? JSONDecoder().decode(SessionSnapshot.self, from: data)
    }

    func save(_ snapshot: SessionSnapshot) {
        let persistable = SessionSnapshot(
            language: snapshot.language,
            convertMode: snapshot.convertMode,
            historySegment: snapshot.historySegment,
            video: snapshot.video,
            audio: snapshot.audio,
            document: snapshot.document,
            schemaVersion: snapshot.schemaVersion
        )
        guard let data = try? JSONEncoder().encode(persistable) else { return }
        defaults.set(data, forKey: Self.key)
    }
}
