import SwiftUI

@main
struct LiteTransApp: App {
    @State private var model = AppModel()
    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
        }
    }
}
