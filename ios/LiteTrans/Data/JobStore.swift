import Foundation

struct JobStore {
    static let key = "liteTrans.jobs"
    let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load() -> [Job] {
        guard let data = defaults.data(forKey: Self.key) else { return [] }
        return (try? JSONDecoder().decode([Job].self, from: data)) ?? []
    }

    func save(_ jobs: [Job]) {
        guard let data = try? JSONEncoder().encode(jobs) else { return }
        defaults.set(data, forKey: Self.key)
    }
}
