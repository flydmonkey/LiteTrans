import Foundation

struct LanShareStore {
    static let key = "liteTrans.lanShare"
    let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load() -> LanShareSettings {
        guard let data = defaults.data(forKey: Self.key) else { return LanShareSettings() }
        return (try? JSONDecoder().decode(LanShareSettings.self, from: data)) ?? LanShareSettings()
    }

    func save(_ settings: LanShareSettings) {
        var next = settings
        next.token = normalizeLanToken(settings.token)
        guard let data = try? JSONEncoder().encode(next) else { return }
        defaults.set(data, forKey: Self.key)
    }
}
