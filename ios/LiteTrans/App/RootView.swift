import SwiftUI

struct RootView: View {
    @State private var tab: RootTab = .convert

    var body: some View {
        TabView(selection: $tab) {
            NavigationStack {
                Text("转码")
                    .navigationTitle("转码")
                    .navigationBarTitleDisplayMode(.large)
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
}
