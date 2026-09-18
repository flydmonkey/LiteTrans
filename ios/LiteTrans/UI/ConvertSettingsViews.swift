import SwiftUI
import UniformTypeIdentifiers

struct ConvertSettingDestination: View {
    let page: ConvertPage

    var body: some View {
        switch page {
        case .home:
            EmptyView()
        case .format:
            FormatSettingsView()
                .toolbar(.visible, for: .navigationBar)
        case .quality:
            QualitySettingsView()
                .toolbar(.visible, for: .navigationBar)
        case .size:
            SizeSettingsView()
                .toolbar(.visible, for: .navigationBar)
        case .output:
            OutputSettingsView()
                .toolbar(.visible, for: .navigationBar)
        }
    }
}

struct FormatSettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.locale) private var locale

    var body: some View {
        List {
            ForEach(formatCards, id: \.id) { card in
                Button {
                    model.preset = card.id
                } label: {
                    settingChoice(
                        title: localizedPresetCardTitle(card, locale: locale),
                        hint: text(String.LocalizationValue(stringLiteral: card.hintKey)),
                        selected: model.preset == card.id
                    )
                }
            }
            if model.convertMode == .video {
                Button {
                    model.showAllFormats.toggle()
                } label: {
                    Text(model.showAllFormats ? text("format_less") : text("format_more"))
                        .font(.body)
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                }
                .accessibilityLabel(model.showAllFormats ? text("format_less") : text("format_more"))
            }
            if model.convertMode == .document && model.preset == "pdf-image" {
                ForEach(imageFormats, id: \.id) { format in
                    Button {
                        model.imageFormat = format.id
                    } label: {
                        settingChoice(
                            title: format.title,
                            hint: format.hint,
                            selected: model.imageFormat == format.id
                        )
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(text("wizard_step_format"))
        .navigationBarTitleDisplayMode(.inline)
    }

    private var formatCards: [PresetCard] {
        switch model.convertMode {
        case .video:
            collapsedPresetCards(selectedId: model.preset, showAll: model.showAllFormats)
        case .audio:
            audioPresetCards()
        case .document:
            documentCards(for: model.documentKind)
        }
    }

    private var imageFormats: [(id: String, title: String, hint: String)] {
        [
            ("jpg", "JPG", "JPEG"),
            ("png", "PNG", "PNG"),
            ("webp", "WebP", "WebP"),
        ]
    }

    private func text(_ key: String.LocalizationValue) -> String {
        localizedText(key, locale: locale)
    }
}

struct QualitySettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.locale) private var locale

    private let options: [(id: String, title: String.LocalizationValue, hint: String.LocalizationValue)] = [
        ("original", "quality_original", "quality_original_hint"),
        ("standard", "quality_standard", "quality_standard_hint"),
        ("small", "quality_small", "quality_small_hint"),
    ]

    var body: some View {
        List {
            ForEach(options, id: \.id) { option in
                Button {
                    model.quality = option.id
                } label: {
                    settingChoice(
                        title: text(option.title),
                        hint: text(option.hint),
                        selected: model.quality == option.id
                    )
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(text("quality_video_title"))
        .navigationBarTitleDisplayMode(.inline)
    }

    private func text(_ key: String.LocalizationValue) -> String {
        localizedText(key, locale: locale)
    }
}

struct SizeSettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.locale) private var locale

    private let options: [(id: String, title: String.LocalizationValue, hint: String.LocalizationValue)] = [
        ("original", "size_original", "size_original_hint"),
        ("1080p", "size_1080p", "size_1080p_hint"),
        ("720p", "size_720p", "size_720p_hint"),
        ("480p", "size_480p", "size_480p_hint"),
    ]

    var body: some View {
        List {
            ForEach(options, id: \.id) { option in
                Button {
                    model.size = option.id
                } label: {
                    settingChoice(
                        title: text(option.title),
                        hint: text(option.hint),
                        selected: model.size == option.id
                    )
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(text("resolution_title"))
        .navigationBarTitleDisplayMode(.inline)
    }

    private func text(_ key: String.LocalizationValue) -> String {
        localizedText(key, locale: locale)
    }
}

struct OutputSettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.locale) private var locale
    @State private var pickingFolder = false

    var body: some View {
        List {
            ForEach(outputChoices(mode: model.convertMode, preset: model.preset), id: \.self) { kind in
                switch kind {
                case .photos:
                    outputRow(kind: .photos, title: text("output_photos"), hint: text("output_gallery_hint"))
                case .downloads:
                    outputRow(kind: .downloads, title: text("output_downloads"), hint: text("output_downloads_hint"))
                case .documents:
                    outputRow(kind: .documents, title: text("output_documents"), hint: text("output_documents_hint"))
                case .custom:
                    Button {
                        pickingFolder = true
                    } label: {
                        settingChoice(
                            title: text("output_custom"),
                            hint: text("output_custom_hint"),
                            selected: model.output.kind == .custom
                        )
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(text("wizard_step_output"))
        .navigationBarTitleDisplayMode(.inline)
        .fileImporter(
            isPresented: $pickingFolder,
            allowedContentTypes: [UTType.folder],
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case .success(let urls):
                if let url = urls.first {
                    model.setCustomOutputFolder(url)
                }
            case .failure(let error):
                model.message = error.localizedDescription
            }
        }
    }

    private func outputRow(kind: OutputKind, title: String, hint: String) -> some View {
        Button {
            model.output = OutputTarget(kind: kind)
        } label: {
            settingChoice(title: title, hint: hint, selected: model.output.kind == kind)
        }
    }

    private func text(_ key: String.LocalizationValue) -> String {
        localizedText(key, locale: locale)
    }
}

@ViewBuilder
private func settingChoice(title: String, hint: String, selected: Bool) -> some View {
    HStack(alignment: .firstTextBaseline) {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.body)
                .foregroundStyle(Color(uiColor: .label))
            Text(hint)
                .font(.footnote)
                .foregroundStyle(Color(uiColor: .secondaryLabel))
        }
        Spacer()
        if selected {
            Image(systemName: "checkmark")
                .foregroundStyle(Color.primary)
                .accessibilityHidden(true)
        }
    }
    .frame(minHeight: 44, alignment: .leading)
    .accessibilityElement(children: .combine)
    .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
}
