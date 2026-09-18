import SwiftUI

struct MineView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.locale) private var locale

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            RootLargeTitle(title: text("tab_mine"))
                .padding(.horizontal, 16)
                .padding(.top, Theme.rootTitleTop)
                .padding(.bottom, Theme.rootHeaderSpacing)
            List {
                Section {
                    NavigationLink(value: MinePage.lan) {
                        LabeledContent(text("mine_lan"), value: lanStatusValue)
                            .frame(minHeight: 44, alignment: .leading)
                    }
                    NavigationLink(value: MinePage.language) {
                        LabeledContent(text("mine_language"), value: languageValue)
                            .frame(minHeight: 44, alignment: .leading)
                    }
                }
                Section {
                    NavigationLink(value: MinePage.privacy) {
                        Text(text("mine_privacy"))
                            .frame(minHeight: 44, alignment: .leading)
                    }
                    NavigationLink(value: MinePage.terms) {
                        Text(text("mine_terms"))
                            .frame(minHeight: 44, alignment: .leading)
                    }
                    NavigationLink(value: MinePage.about) {
                        Text(text("mine_about"))
                            .frame(minHeight: 44, alignment: .leading)
                    }
                }
            }
            .listStyle(.insetGrouped)
            .contentMargins(.top, 0, for: .scrollContent)
            .scrollContentBackground(.hidden)
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle(text("tab_mine"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .navigationBar)
    }

    private var lanStatusValue: String {
        text(model.lanShare.enabled ? "lan_status_on" : "lan_status_off")
    }

    private var languageValue: String {
        switch model.language {
        case .system: text("language_follow_system")
        case .zhHans: text("language_zh_hans")
        case .zhHant: text("language_zh_hant")
        case .en: text("language_en")
        case .ja: text("language_ja")
        case .ko: text("language_ko")
        }
    }

    private func text(_ key: String.LocalizationValue) -> String {
        localizedText(key, locale: locale)
    }
}

struct MinePageDestination: View {
    let page: MinePage

    var body: some View {
        switch page {
        case .root:
            EmptyView()
        case .lan:
            LanShareView()
                .toolbar(.visible, for: .navigationBar)
        case .language:
            LanguageSettingsView()
                .toolbar(.visible, for: .navigationBar)
        case .privacy:
            LegalTextView(titleKey: "mine_privacy", bodyKey: "privacy_body")
                .toolbar(.visible, for: .navigationBar)
        case .terms:
            LegalTextView(titleKey: "mine_terms", bodyKey: "terms_body")
                .toolbar(.visible, for: .navigationBar)
        case .about:
            AboutView()
                .toolbar(.visible, for: .navigationBar)
        }
    }
}

private struct LanguageSettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.locale) private var locale

    private let options: [(AppLanguage, String.LocalizationValue)] = [
        (.system, "language_follow_system"),
        (.zhHans, "language_zh_hans"),
        (.zhHant, "language_zh_hant"),
        (.en, "language_en"),
        (.ja, "language_ja"),
        (.ko, "language_ko"),
    ]

    var body: some View {
        List {
            ForEach(options, id: \.0) { language, title in
                Button {
                    model.language = language
                } label: {
                    HStack {
                        Text(localizedText(title, locale: locale))
                            .font(.body)
                            .foregroundStyle(Color(uiColor: .label))
                        Spacer()
                        if model.language == language {
                            Image(systemName: "checkmark")
                                .foregroundStyle(Color.primary)
                                .accessibilityHidden(true)
                        }
                    }
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
                }
                .accessibilityAddTraits(model.language == language ? [.isButton, .isSelected] : .isButton)
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(localizedText("mine_language", locale: locale))
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct LegalTextView: View {
    @Environment(\.locale) private var locale
    let titleKey: String.LocalizationValue
    let bodyKey: String.LocalizationValue

    var body: some View {
        ScrollView {
            Text(localizedText(bodyKey, locale: locale))
                .font(.body)
                .foregroundStyle(Color(uiColor: .label))
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
        }
        .background(Color(uiColor: .systemBackground))
        .navigationTitle(localizedText(titleKey, locale: locale))
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct AboutView: View {
    @Environment(\.locale) private var locale

    private var version: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(localizedText("about_name", locale: locale))
                    .font(.title)
                    .foregroundStyle(Color(uiColor: .label))
                if !version.isEmpty {
                    Text(version)
                        .font(.headline)
                        .foregroundStyle(Color(uiColor: .secondaryLabel))
                }
                Text(localizedText("about_no_upload", locale: locale))
                    .font(.subheadline)
                    .foregroundStyle(Color(uiColor: .secondaryLabel))
                Text(String(format: localizedText("about_body", locale: locale), locale: locale, version))
                    .font(.body)
                    .foregroundStyle(Color(uiColor: .label))
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
        }
        .background(Color(uiColor: .systemBackground))
        .navigationTitle(localizedText("mine_about", locale: locale))
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct LanShareView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.locale) private var locale
    @State private var tokenDraft = ""

    var body: some View {
        @Bindable var model = model
        List {
            Section {
                Toggle(localizedText("mine_lan", locale: locale), isOn: lanEnabled)
                    .frame(minHeight: 44)
            } footer: {
                Text(localizedText("lan_open_warning", locale: locale))
            }
            Section {
                TextField(localizedText("lan_token_placeholder", locale: locale), text: $tokenDraft)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .frame(minHeight: 44)
                    .onSubmit(persistToken)
            } header: {
                Text(localizedText("lan_token_label", locale: locale))
            } footer: {
                Text(localizedText("lan_token_empty_hint", locale: locale))
            }
            Section {
                if let url = model.lanServer.boundURL {
                    Button(action: copyAddress) {
                        LabeledContent(localizedText("lan_address", locale: locale), value: url)
                            .frame(minHeight: 44, alignment: .leading)
                    }
                    .accessibilityLabel(localizedText("lan_copy", locale: locale))
                } else {
                    Text(statusText)
                        .foregroundStyle(Color(uiColor: .secondaryLabel))
                        .frame(minHeight: 44, alignment: .leading)
                }
                if model.lanServer.lastError == .denied {
                    Button(localizedText("lan_open_settings", locale: locale)) {
                        if let url = URL(string: UIApplication.openSettingsURLString) {
                            UIApplication.shared.open(url)
                        }
                    }
                    .frame(minHeight: 44)
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(localizedText("mine_lan", locale: locale))
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { tokenDraft = model.lanShare.token }
        .onDisappear(perform: persistToken)
    }

    private var lanEnabled: Binding<Bool> {
        Binding(
            get: { model.lanShare.enabled },
            set: { model.lanShare.enabled = $0 }
        )
    }

    private var statusText: String {
        switch model.lanServer.lastError {
        case .needWifi: localizedText("lan_need_wifi", locale: locale)
        case .portsBusy: localizedText("lan_ports_busy", locale: locale)
        case .denied: localizedText("lan_local_network_denied", locale: locale)
        case nil:
            if model.lanShare.enabled {
                localizedText("lan_need_wifi", locale: locale)
            } else {
                localizedText("lan_open_warning", locale: locale)
            }
        }
    }

    private func persistToken() {
        model.lanShare.token = normalizeLanToken(tokenDraft)
        tokenDraft = model.lanShare.token
    }

    private func copyAddress() {
        guard let url = model.lanServer.boundURL else { return }
        UIPasteboard.general.string = url
    }
}
