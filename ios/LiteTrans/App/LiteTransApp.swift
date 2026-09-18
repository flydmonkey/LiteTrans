import SwiftUI

@main
struct LiteTransApp: App {
    @State private var model: AppModel
    @Environment(\.scenePhase) private var scenePhase

    init() {
        let model = AppModel()
        model.registerBackgroundProcessing()
        _model = State(initialValue: model)
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .environment(\.locale, localizationLocale(for: model.language))
                .id(model.language)
                .onChange(of: scenePhase) { _, phase in
                    model.syncScene(sceneActive: phase == .active)
                }
                .onAppear {
                    model.syncScene(sceneActive: scenePhase == .active)
                }
                .onOpenURL { url in
                    if let id = historyJobFromDeepLink(url) {
                        model.openHistoryJob(id: id)
                    }
                    model.consumePendingCancel()
                }
        }
    }
}
