import Foundation

struct SessionSnapshot: Equatable, Codable, Sendable {
    var preset: String
    var quality: String
    var size: String
    var output: OutputTarget
    var language: AppLanguage
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
        guard let data = try? JSONEncoder().encode(snapshot) else { return }
        defaults.set(data, forKey: Self.key)
    }
}
