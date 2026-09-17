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
        case .quality:
            QualitySettingsView()
        case .size:
            SizeSettingsView()
        case .output:
            OutputSettingsView()
        }
    }
}

struct FormatSettingsView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        List {
            ForEach(collapsedPrimaryPresets(), id: \.id) { card in
                Button {
                    model.preset = card.id
                } label: {
                    settingChoice(
                        title: card.title,
                        hint: card.hint,
                        selected: model.preset == card.id
                    )
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(String(localized: "wizard_step_format"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct QualitySettingsView: View {
    @Environment(AppModel.self) private var model

    private let options: [(id: String, title: LocalizedStringResource, hint: LocalizedStringResource)] = [
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
                        title: String(localized: option.title),
                        hint: String(localized: option.hint),
                        selected: model.quality == option.id
                    )
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(String(localized: "quality_video_title"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct SizeSettingsView: View {
    @Environment(AppModel.self) private var model

    private let options: [(id: String, title: LocalizedStringResource, hint: LocalizedStringResource)] = [
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
                        title: String(localized: option.title),
                        hint: String(localized: option.hint),
                        selected: model.size == option.id
                    )
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(String(localized: "resolution_title"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct OutputSettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var pickingFolder = false

    var body: some View {
        List {
            outputRow(kind: .photos, title: String(localized: "output_photos"), hint: String(localized: "output_gallery_hint"))
            outputRow(kind: .downloads, title: String(localized: "output_downloads"), hint: String(localized: "output_downloads_hint"))
            Button {
                pickingFolder = true
            } label: {
                settingChoice(
                    title: String(localized: "output_custom"),
                    hint: String(localized: "output_custom_hint"),
                    selected: model.output.kind == .custom
                )
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(String(localized: "wizard_step_output"))
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
                .foregroundStyle(Color.accentColor)
                .accessibilityHidden(true)
        }
    }
    .frame(minHeight: 44, alignment: .leading)
    .accessibilityElement(children: .combine)
    .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
}
