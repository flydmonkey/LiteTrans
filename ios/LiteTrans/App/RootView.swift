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
                HistoryView()
            }
            .tabItem {
                Label(String(localized: "tab_history"), systemImage: "clock")
            }
            .tag(RootTab.history)

            NavigationStack(path: minePath) {
                MineView()
                    .navigationDestination(for: MinePage.self) { page in
                        MinePageDestination(page: page)
                    }
            }
            .tabItem {
                Label(String(localized: "tab_mine"), systemImage: "person.crop.circle")
            }
            .tag(RootTab.mine)
        }
        .modifier(ResolvedLocaleModifier(language: model.language))
        .onChange(of: model.tab) { _, tab in
            if tab != .mine {
                model.minePage = .root
            }
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

    private var minePath: Binding<[MinePage]> {
        Binding(
            get: { model.minePage == .root ? [] : [model.minePage] },
            set: { stack in
                if let page = stack.last {
                    model.minePage = page
                } else {
                    model.minePage = popMineBack(model.minePage) ?? .root
                }
            }
        )
    }
}

private struct ResolvedLocaleModifier: ViewModifier {
    let language: AppLanguage

    func body(content: Content) -> some View {
        if let identifier = resolvedLocaleIdentifier(language) {
            content.environment(\.locale, Locale(identifier: identifier))
        } else {
            content
        }
    }
}
