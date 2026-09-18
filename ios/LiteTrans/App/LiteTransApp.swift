import SwiftUI

@main
struct LiteTransApp: App {
    @State private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase
    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .environment(\.locale, localizationLocale(for: model.language))
                .id(model.language)
                .onChange(of: scenePhase) { _, phase in
                    model.syncLanShare(sceneActive: phase == .active)
                }
                .onAppear {
                    model.syncLanShare(sceneActive: scenePhase == .active)
                }
        }
    }
}
