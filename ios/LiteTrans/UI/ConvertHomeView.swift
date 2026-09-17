import AVKit
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

struct ConvertHomeView: View {
    @Environment(AppModel.self) private var model
    @State private var photoItems: [PhotosPickerItem] = []
    @State private var showFileImporter = false

    private var selected: MediaInfo? {
        if let selectedUri = model.selectedUri {
            return model.sources.first { $0.sourceUri == selectedUri }
        }
        return model.sources.first
    }

    var body: some View {
        List {
            if let selected, selected.importable, selected.durationSecs != nil {
                Section {
                    ConvertTrimCard(source: selected)
                        .id(selected.sourceUri)
                }
            }

            Section {
                PhotosPicker(selection: $photoItems, matching: .videos) {
                    Label(String(localized: "wizard_source_gallery"), systemImage: "photo.on.rectangle")
                }
                .accessibilityHint(String(localized: "wizard_source_gallery_hint_video"))

                Button {
                    showFileImporter = true
                } label: {
                    Label(String(localized: "wizard_source_files"), systemImage: "folder")
                }
                .accessibilityHint(String(localized: "wizard_source_files_hint"))

                ForEach(model.sources, id: \.sourceUri) { source in
                    ConvertSourceRow(source: source, selected: model.selectedUri == source.sourceUri)
                        .contentShape(Rectangle())
                        .onTapGesture {
                            model.selectedUri = source.sourceUri
                        }
                        .swipeActions(edge: .trailing, allowsFullSwipe: model.canRemove(source)) {
                            if model.canRemove(source) {
                                Button(role: .destructive) {
                                    model.removeSource(source)
                                } label: {
                                    Text(String(localized: "action_remove"))
                                }
                            }
                        }
                }
            } header: {
                Text(String(localized: "section_files"))
            } footer: {
                if model.sources.isEmpty {
                    Text(String(localized: "wizard_add_video"))
                }
            }

            Section(String(localized: "section_settings")) {
                ForEach(convertSettingsFor(preset: model.preset), id: \.self) { setting in
                    NavigationLink(value: convertPage(for: setting)) {
                        LabeledContent(settingTitle(setting), value: settingValue(setting))
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(String(localized: "tab_convert"))
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button(String(localized: "action_convert")) {
                    model.startConversion()
                }
                .disabled(!model.startEnabled)
            }
        }
        .safeAreaInset(edge: .bottom) {
            Button {
                model.startConversion()
            } label: {
                Text(String(localized: "wizard_start_convert"))
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: 44)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(!model.startEnabled)
            .padding(.horizontal)
            .padding(.vertical, 8)
        }
        .fileImporter(
            isPresented: $showFileImporter,
            allowedContentTypes: [UTType.movie],
            allowsMultipleSelection: true
        ) { result in
            switch result {
            case .success(let urls):
                model.importPickedURLs(urls)
            case .failure(let error):
                model.message = error.localizedDescription
            }
        }
        .onChange(of: photoItems) { _, items in
            guard !items.isEmpty else { return }
            Task {
                await importPhotos(items)
            }
            photoItems = []
        }
    }

    private func importPhotos(_ items: [PhotosPickerItem]) async {
        for item in items {
            do {
                guard let movie = try await item.loadTransferable(type: ImportedMovie.self) else { continue }
                await MainActor.run {
                    model.addImportedURL(
                        movie.url,
                        displayName: photosImportDisplayName(
                            fileName: movie.sourceFileName,
                            pathExtension: movie.url.pathExtension,
                            itemIdentifier: item.itemIdentifier
                        )
                    )
                }
            } catch {
                await MainActor.run {
                    model.message = error.localizedDescription
                }
            }
        }
    }

    private func convertPage(for setting: ConvertSetting) -> ConvertPage {
        switch setting {
        case .format: .format
        case .quality: .quality
        case .size: .size
        case .output: .output
        }
    }

    private func settingTitle(_ setting: ConvertSetting) -> String {
        switch setting {
        case .format: String(localized: "wizard_step_format")
        case .quality: String(localized: "quality_video_title")
        case .size: String(localized: "resolution_title")
        case .output: String(localized: "wizard_step_output")
        }
    }

    private func settingValue(_ setting: ConvertSetting) -> String {
        switch setting {
        case .format:
            collapsedPrimaryPresets().first { $0.id == model.preset }?.title
                ?? listPresets().first { $0.id == model.preset }?.label
                ?? model.preset
        case .quality:
            switch model.quality {
            case "original", "high": String(localized: "quality_original")
            case "small": String(localized: "quality_small")
            default: String(localized: "quality_standard")
            }
        case .size:
            switch model.size {
            case "1080p": "1080p"
            case "720p": "720p"
            case "480p": "480p"
            default: String(localized: "size_original")
            }
        case .output:
            switch model.output.kind {
            case .photos: String(localized: "output_photos")
            case .downloads: String(localized: "output_downloads")
            case .custom: String(localized: "output_custom")
            }
        }
    }
}

private struct ConvertSourceRow: View {
    let source: MediaInfo
    let selected: Bool

    private var readingFormat: Bool {
        source.probing || (source.error == nil && !source.importable && source.videoCodec == nil && source.durationSecs == nil)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(source.displayName)
                    .font(.body)
                    .foregroundStyle(Color(uiColor: .label))
                if selected {
                    Spacer()
                    Image(systemName: "checkmark")
                        .foregroundStyle(Color.accentColor)
                        .accessibilityHidden(true)
                }
            }
            Text(subtitle)
                .font(.footnote)
                .foregroundStyle(source.error == nil ? Color(uiColor: .secondaryLabel) : Color(uiColor: .systemRed))
            if let error = source.error {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(Color(uiColor: .systemRed))
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private var subtitle: String {
        if readingFormat {
            return String(localized: "wizard_reading_format")
        }
        var parts: [String] = []
        if let container = source.container { parts.append(container) }
        if let codec = source.videoCodec { parts.append(codec) }
        if let width = source.width, let height = source.height {
            parts.append("\(width)×\(height)")
        }
        if let frameRate = source.frameRate {
            parts.append(String(format: "%.0f fps", frameRate))
        }
        if let duration = source.durationSecs {
            parts.append(formatDuration(duration))
        }
        if source.trimStartSecs != nil || source.trimEndSecs != nil {
            parts.append(String(localized: "wizard_trimmed"))
        }
        return parts.isEmpty ? String(localized: "wizard_kind_video") : parts.joined(separator: " · ")
    }

    private func formatDuration(_ seconds: Double) -> String {
        let total = Int(seconds.rounded())
        let hours = total / 3600
        let minutes = (total % 3600) / 60
        let secs = total % 60
        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, secs)
        }
        return String(format: "%d:%02d", minutes, secs)
    }
}

private struct ConvertTrimCard: View {
    @Environment(AppModel.self) private var model
    let source: MediaInfo
    @State private var playhead = 0.0
    @State private var player: AVPlayer?

    private var duration: Double { max(source.durationSecs ?? 0, 0.001) }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            preview
            Slider(value: $playhead, in: 0...duration)
                .frame(minHeight: 44)
                .accessibilityLabel(String(localized: "trim_title"))
                .accessibilityValue(formatClock(playhead))
            HStack {
                Button(String(localized: "trim_set_start")) { setStart() }
                    .frame(minHeight: 44)
                Button(String(localized: "trim_set_end")) { setEnd() }
                    .frame(minHeight: 44)
                Button(String(localized: "trim_reset")) { resetTrim() }
                    .frame(minHeight: 44)
            }
            .buttonStyle(.bordered)
            if source.trimStartSecs != nil || source.trimEndSecs != nil {
                Text(String(localized: "wizard_trimmed"))
                    .font(.footnote)
                    .foregroundStyle(Color(uiColor: .secondaryLabel))
            }
        }
        .onAppear { setupPlayer() }
        .onDisappear { player?.pause() }
    }

    @ViewBuilder
    private var preview: some View {
        if let player {
            VideoPlayer(player: player)
                .frame(minHeight: 180)
                .accessibilityLabel(String(localized: "wizard_preview_video"))
        } else {
            Text(String(localized: "trim_no_preview"))
                .font(.footnote)
                .foregroundStyle(Color(uiColor: .secondaryLabel))
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
        }
    }

    private func setupPlayer() {
        guard let url = URL(string: source.sourceUri), url.isFileURL,
              FileManager.default.fileExists(atPath: url.path)
        else { return }
        player = AVPlayer(url: url)
    }

    private func setStart() {
        var next = source
        next.trimStartSecs = playhead
        if let end = next.trimEndSecs, end < playhead {
            next.trimEndSecs = nil
        }
        model.replaceSource(next)
    }

    private func setEnd() {
        var next = source
        next.trimEndSecs = playhead
        if let start = next.trimStartSecs, start > playhead {
            next.trimStartSecs = nil
        }
        model.replaceSource(next)
    }

    private func resetTrim() {
        var next = source
        next.trimStartSecs = nil
        next.trimEndSecs = nil
        model.replaceSource(next)
    }

    private func formatClock(_ seconds: Double) -> String {
        let total = Int(seconds.rounded())
        return String(format: "%d:%02d", total / 60, total % 60)
    }
}

struct ImportedMovie: Transferable {
    let url: URL
    let sourceFileName: String

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { movie in
            SentTransferredFile(movie.url)
        } importing: { received in
            let dest = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString)
                .appendingPathExtension(received.file.pathExtension)
            if FileManager.default.fileExists(atPath: dest.path) {
                try FileManager.default.removeItem(at: dest)
            }
            try FileManager.default.copyItem(at: received.file, to: dest)
            return Self(url: dest, sourceFileName: received.file.lastPathComponent)
        }
    }
}
