import SwiftUI

struct MineView: View {
    @Environment(\.locale) private var locale

    var body: some View {
        Form {
            Section {
                NavigationLink(value: MinePage.language) {
                    Text(text("mine_language"))
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
            }
            Section {
                NavigationLink(value: MinePage.about) {
                    Text(text("mine_about"))
                        .frame(minHeight: 44, alignment: .leading)
                }
            }
        }
        .navigationTitle(text("tab_mine"))
        .navigationBarTitleDisplayMode(.large)
    }

    private func text(_ key: String.LocalizationValue) -> String {
        String(localized: key, locale: locale)
    }
}

struct MinePageDestination: View {
    let page: MinePage

    var body: some View {
        switch page {
        case .root:
            EmptyView()
        case .language:
            LanguageSettingsView()
        case .privacy:
            LegalTextView(titleKey: "mine_privacy", bodyKey: "privacy_body")
        case .terms:
            LegalTextView(titleKey: "mine_terms", bodyKey: "terms_body")
        case .about:
            AboutView()
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
                        Text(String(localized: title, locale: locale))
                            .font(.body)
                            .foregroundStyle(Color(uiColor: .label))
                        Spacer()
                        if model.language == language {
                            Image(systemName: "checkmark")
                                .foregroundStyle(Color.accentColor)
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
        .navigationTitle(String(localized: "mine_language", locale: locale))
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct LegalTextView: View {
    @Environment(\.locale) private var locale
    let titleKey: String.LocalizationValue
    let bodyKey: String.LocalizationValue

    var body: some View {
        ScrollView {
            Text(String(localized: bodyKey, locale: locale))
                .font(.body)
                .foregroundStyle(Color(uiColor: .label))
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
        }
        .background(Color(uiColor: .systemBackground))
        .navigationTitle(String(localized: titleKey, locale: locale))
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
                Text(String(localized: "about_name", locale: locale))
                    .font(.title)
                    .foregroundStyle(Color(uiColor: .label))
                if !version.isEmpty {
                    Text(version)
                        .font(.headline)
                        .foregroundStyle(Color(uiColor: .secondaryLabel))
                }
                Text(String(localized: "about_no_upload", locale: locale))
                    .font(.subheadline)
                    .foregroundStyle(Color(uiColor: .secondaryLabel))
                Text(String(format: String(localized: "about_body", locale: locale), locale: locale, version))
                    .font(.body)
                    .foregroundStyle(Color(uiColor: .label))
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
        }
        .background(Color(uiColor: .systemBackground))
        .navigationTitle(String(localized: "mine_about", locale: locale))
        .navigationBarTitleDisplayMode(.inline)
    }
}
