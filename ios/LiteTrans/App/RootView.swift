import SwiftUI

struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        TabView(selection: $model.tab) {
            NavigationStack(path: convertPath) {
                ConvertHomeView()
                    .navigationDestination(for: ConvertPage.self) { page in
                        ConvertSettingDestination(page: page)
                    }
            }
            .tabItem {
                Label(String(localized: "tab_convert"), systemImage: "arrow.triangle.2.circlepath")
            }
            .tag(RootTab.convert)

            NavigationStack {
                Text("历史")
                    .navigationTitle("历史")
                    .navigationBarTitleDisplayMode(.large)
            }
            .tabItem {
                Label(String(localized: "tab_history"), systemImage: "clock")
            }
            .tag(RootTab.history)

            NavigationStack {
                Text("我的")
                    .navigationTitle("我的")
                    .navigationBarTitleDisplayMode(.large)
            }
            .tabItem {
                Label(String(localized: "tab_mine"), systemImage: "person.crop.circle")
            }
            .tag(RootTab.mine)
        }
    }

    private var convertPath: Binding<[ConvertPage]> {
        Binding(
            get: { model.convertPage == .home ? [] : [model.convertPage] },
            set: { stack in
                if let page = stack.last {
                    model.convertPage = page
                } else {
                    model.convertPage = popConvertBack(model.convertPage) ?? .home
                }
            }
        )
    }
}
