import AVFoundation
import AVKit
import PDFKit
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

struct ConvertHomeView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.locale) private var locale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var photoItems: [PhotosPickerItem] = []
    @State private var showFileImporter = false
    @State private var importerTypes: [UTType] = [.movie]

    private var selected: MediaInfo? {
        if let selectedUri = model.selectedUri {
            return model.sources.first { $0.sourceUri == selectedUri }
        }
        return model.sources.first
    }

    var body: some View {
        @Bindable var model = model
        List {
            Section {
                Picker("", selection: $model.convertMode) {
                    Text(text("segment_video")).tag(ConvertMode.video)
                    Text(text("segment_audio")).tag(ConvertMode.audio)
                    Text(text("segment_document")).tag(ConvertMode.document)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .accessibilityLabel(text("segment_convert"))
                .frame(minHeight: 44)
                .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                .listRowBackground(Color.clear)
            }

            if let message = model.message {
                Section {
                    HStack(alignment: .top, spacing: 12) {
                        Text(message)
                            .font(.body)
                            .foregroundStyle(Color(uiColor: .label))
                            .frame(maxWidth: .infinity, alignment: .leading)
                        Button {
                            model.message = nil
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundStyle(Color(uiColor: .secondaryLabel))
                                .frame(minWidth: 44, minHeight: 44)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(text("action_dismiss"))
                    }
                    .accessibilityElement(children: .combine)
                }
            }

            if let selected, selected.importable {
                previewSection(selected)
            }

            Section {
                sourcePickers
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
                                    Text(text("action_remove"))
                                }
                            }
                        }
                }
            } header: {
                Text(text("section_files"))
            } footer: {
                if model.sources.isEmpty {
                    Text(footerText)
                }
            }

            Section(text("section_settings")) {
                ForEach(convertSettingsFor(preset: model.preset), id: \.self) { setting in
                    NavigationLink(value: convertPage(for: setting)) {
                        LabeledContent(settingTitle(setting), value: settingValue(setting))
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(text("tab_convert"))
        .navigationBarTitleDisplayMode(.large)
        .animation(reduceMotion ? .easeInOut(duration: 0.2) : .snappy, value: model.convertMode)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button(text("action_convert")) {
                    model.startConversion()
                }
                .disabled(!model.startEnabled)
            }
        }
        .safeAreaInset(edge: .bottom) {
            Button {
                model.startConversion()
            } label: {
                Text(text("wizard_start_convert"))
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
            allowedContentTypes: importerTypes,
            allowsMultipleSelection: true
        ) { result in
            importFiles(result)
        }
        .onChange(of: photoItems) { _, items in
            guard !items.isEmpty else { return }
            Task {
                await importPhotos(items)
            }
            photoItems = []
        }
    }

    @ViewBuilder
    private var sourcePickers: some View {
        PhotosPicker(selection: $photoItems, matching: photoMatching) {
            Label(text("wizard_source_gallery"), systemImage: "photo.on.rectangle")
                .frame(minHeight: 44, alignment: .leading)
        }
        .accessibilityHint(galleryHint)

        if model.convertMode == .audio {
            Button {
                importerTypes = musicContentTypes
                showFileImporter = true
            } label: {
                Label(text("wizard_source_music"), systemImage: "music.note")
                    .frame(minHeight: 44, alignment: .leading)
            }
            .accessibilityHint(text("wizard_source_files_hint"))
        }

        Button {
            importerTypes = fileContentTypes
            showFileImporter = true
        } label: {
            Label(text("wizard_source_files"), systemImage: "folder")
                .frame(minHeight: 44, alignment: .leading)
        }
        .accessibilityHint(text("wizard_source_files_hint"))
    }

    @ViewBuilder
    private func previewSection(_ selected: MediaInfo) -> some View {
        Section {
            switch model.convertMode {
            case .video:
                if selected.durationSecs != nil, allowsTrim(preset: model.preset) {
                    ConvertTrimCard(source: selected, showsVideo: true)
                        .id(selected.sourceUri)
                        .transition(.opacity)
                }
            case .audio:
                if selected.durationSecs != nil, allowsTrim(preset: model.preset) {
                    ConvertTrimCard(source: selected, showsVideo: hasVideoTrack(selected))
                        .id(selected.sourceUri)
                        .transition(.opacity)
                }
            case .document:
                switch documentSourceKind(selected.displayName) {
                case .pdf:
                    ConvertPDFCard(source: selected)
                        .id(selected.sourceUri)
                        .transition(.opacity)
                case .image:
                    ConvertImageCard(source: selected)
                        .id(selected.sourceUri)
                        .transition(.opacity)
                default:
                    EmptyView()
                }
            }
        }
    }

    private var photoMatching: PHPickerFilter {
        model.convertMode == .document ? .images : .videos
    }

    private var galleryHint: String {
        switch model.convertMode {
        case .audio: text("wizard_source_gallery_hint_audio")
        default: text("wizard_source_gallery_hint_video")
        }
    }

    private var footerText: String {
        switch model.convertMode {
        case .video: text("wizard_add_video")
        case .audio: text("footer_audio")
        case .document: text("footer_document")
        }
    }

    private var fileContentTypes: [UTType] {
        switch model.convertMode {
        case .video:
            return movieTypes
        case .audio:
            return musicContentTypes + movieTypes
        case .document:
            return documentTypes
        }
    }

    private var musicContentTypes: [UTType] {
        [.audio, .mp3, .mpeg4Audio, .wav, .aiff]
    }

    private var movieTypes: [UTType] {
        [.movie, .mpeg4Movie, .quickTimeMovie, .avi, .mpeg]
    }

    private var documentTypes: [UTType] {
        var types: [UTType] = [.image, .png, .jpeg, .gif, .webP, .bmp, .heic, .pdf]
        if let docx = UTType(filenameExtension: "docx") { types.append(docx) }
        if let xlsx = UTType(filenameExtension: "xlsx") { types.append(xlsx) }
        return types
    }

    private func importFiles(_ result: Result<[URL], Error>) {
        switch result {
        case .success(let urls):
            model.importPickedURLs(urls)
        case .failure(let error):
            model.message = error.localizedDescription
        }
    }

    private func importPhotos(_ items: [PhotosPickerItem]) async {
        for item in items {
            do {
                if model.convertMode == .document {
                    guard let image = try await item.loadTransferable(type: ImportedImage.self) else { continue }
                    await MainActor.run {
                        model.addImportedURL(
                            image.url,
                            displayName: photosImportDisplayName(
                                fileName: image.sourceFileName,
                                pathExtension: image.url.pathExtension,
                                itemIdentifier: item.itemIdentifier
                            )
                        )
                    }
                } else {
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
        case .format: text("wizard_step_format")
        case .quality: text("quality_video_title")
        case .size: text("resolution_title")
        case .output: text("wizard_step_output")
        }
    }

    private func settingValue(_ setting: ConvertSetting) -> String {
        switch setting {
        case .format:
            formatTitle(for: model.preset)
        case .quality:
            switch model.quality {
            case "original", "high": text("quality_original")
            case "small": text("quality_small")
            default: text("quality_standard")
            }
        case .size:
            switch model.size {
            case "1080p": "1080p"
            case "720p": "720p"
            case "480p": "480p"
            default: text("size_original")
            }
        case .output:
            switch model.output.kind {
            case .photos: text("output_photos")
            case .downloads: text("output_downloads")
            case .custom: text("output_custom")
            case .documents: text("output_documents")
            }
        }
    }

    private func formatTitle(for preset: String) -> String {
        let cards = collapsedPresetCards(selectedId: preset, showAll: true)
            + audioPresetCards()
            + documentCards(for: model.documentKind)
            + documentCards(for: .image)
            + documentCards(for: .pdf)
        return cards.first { $0.id == preset }?.title
            ?? listPresets().first { $0.id == preset }?.label
            ?? preset
    }

    private func text(_ key: String.LocalizationValue) -> String {
        String(localized: key, locale: locale)
    }
}

private func hasVideoTrack(_ source: MediaInfo) -> Bool {
    source.videoCodec != nil || (source.width != nil && source.height != nil)
}

private struct ConvertSourceRow: View {
    @Environment(AppModel.self) private var model
    @Environment(\.locale) private var locale
    let source: MediaInfo
    let selected: Bool

    private var readingFormat: Bool {
        source.probing || (source.error == nil && !source.importable && source.videoCodec == nil && source.durationSecs == nil && source.pageCount == nil && source.width == nil)
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
        .frame(minHeight: 44, alignment: .leading)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private var subtitle: String {
        if readingFormat {
            return text("wizard_reading_format")
        }
        var parts: [String] = []
        if let container = source.container { parts.append(container) }
        if let codec = source.videoCodec { parts.append(codec) }
        if let width = source.width, let height = source.height {
            parts.append("\(width)×\(height)")
        }
        if let pageCount = source.pageCount {
            parts.append("\(pageCount)")
        }
        if let frameRate = source.frameRate {
            parts.append(String(format: "%.0f fps", locale: locale, frameRate))
        }
        if let duration = source.durationSecs {
            parts.append(formatDuration(duration))
        }
        if allowsTrim(preset: model.preset), source.trimStartSecs != nil || source.trimEndSecs != nil {
            parts.append(text("wizard_trimmed"))
        }
        return parts.isEmpty ? text("wizard_kind_video") : parts.joined(separator: " · ")
    }

    private func text(_ key: String.LocalizationValue) -> String {
        String(localized: key, locale: locale)
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
    @Environment(\.locale) private var locale
    let source: MediaInfo
    var showsVideo: Bool
    @State private var playhead = 0.0
    @State private var player: AVPlayer?
    @State private var isSeeking = false
    @State private var timeObserver: Any?

    private var duration: Double { max(source.durationSecs ?? 0, 0.001) }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if showsVideo {
                preview
            }
            Slider(
                value: Binding(
                    get: { playhead },
                    set: { newValue in
                        playhead = newValue
                        seekPlayer(to: newValue)
                    }
                ),
                in: 0...duration,
                onEditingChanged: { editing in
                    isSeeking = editing
                    if !editing {
                        seekPlayer(to: playhead)
                    }
                }
            )
                .frame(minHeight: 44)
                .accessibilityLabel(text("trim_title"))
                .accessibilityValue(formatClock(playhead))
            HStack {
                Button(text("trim_set_start")) { setStart() }
                    .frame(minHeight: 44)
                Button(text("trim_set_end")) { setEnd() }
                    .frame(minHeight: 44)
                Button(text("trim_reset")) { resetTrim() }
                    .frame(minHeight: 44)
            }
            .buttonStyle(.bordered)
            if source.trimStartSecs != nil || source.trimEndSecs != nil {
                Text(text("wizard_trimmed"))
                    .font(.footnote)
                    .foregroundStyle(Color(uiColor: .secondaryLabel))
            }
        }
        .onAppear { setupPlayer() }
        .onDisappear { teardownPlayer() }
    }

    @ViewBuilder
    private var preview: some View {
        if let player {
            VideoPlayer(player: player)
                .frame(minHeight: 180)
                .accessibilityLabel(text("wizard_preview_video"))
        } else {
            Text(text("trim_no_preview"))
                .font(.footnote)
                .foregroundStyle(Color(uiColor: .secondaryLabel))
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
        }
    }

    private func text(_ key: String.LocalizationValue) -> String {
        String(localized: key, locale: locale)
    }

    private func setupPlayer() {
        teardownPlayer()
        guard let url = URL(string: source.sourceUri), url.isFileURL,
              FileManager.default.fileExists(atPath: url.path)
        else { return }
        let player = AVPlayer(url: url)
        let interval = CMTime(seconds: 0.1, preferredTimescale: 600)
        timeObserver = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { time in
            guard !isSeeking else { return }
            let seconds = CMTimeGetSeconds(time)
            guard seconds.isFinite else { return }
            playhead = min(max(seconds, 0), duration)
        }
        self.player = player
    }

    private func teardownPlayer() {
        if let player, let timeObserver {
            player.removeTimeObserver(timeObserver)
        }
        timeObserver = nil
        player?.pause()
        player = nil
    }

    private func seekPlayer(to seconds: Double) {
        guard let player else { return }
        let clamped = min(max(seconds, 0), duration)
        let time = CMTime(seconds: clamped, preferredTimescale: 600)
        player.seek(to: time, toleranceBefore: .zero, toleranceAfter: .zero)
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

private struct ConvertPDFCard: View {
    @Environment(AppModel.self) private var model
    @Environment(\.locale) private var locale
    let source: MediaInfo

    private var pageCount: Int { max(source.pageCount ?? 1, 1) }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let url = fileURL {
                PDFPagePreview(url: url)
                    .frame(minHeight: 180)
                    .accessibilityLabel(text("wizard_preview_pdf"))
            }
            Stepper(value: startBinding, in: 1...pageCount) {
                LabeledContent(text("pdf_page_start"), value: "\(source.pageStart ?? 1)")
            }
            .frame(minHeight: 44)
            Stepper(value: endBinding, in: 1...pageCount) {
                LabeledContent(text("pdf_page_end"), value: "\(source.pageEnd ?? pageCount)")
            }
            .frame(minHeight: 44)
        }
    }

    private var fileURL: URL? {
        guard let url = URL(string: source.sourceUri), url.isFileURL else { return nil }
        return url
    }

    private var startBinding: Binding<Int> {
        Binding(
            get: { source.pageStart ?? 1 },
            set: { updateRange(start: $0, end: source.pageEnd ?? pageCount) }
        )
    }

    private var endBinding: Binding<Int> {
        Binding(
            get: { source.pageEnd ?? pageCount },
            set: { updateRange(start: source.pageStart ?? 1, end: $0) }
        )
    }

    private func updateRange(start: Int, end: Int) {
        let clamped = clampPageRange(start: start, end: end, pageCount: pageCount)
        var next = source
        next.pageStart = clamped.0
        next.pageEnd = clamped.1
        model.replaceSource(next)
    }

    private func text(_ key: String.LocalizationValue) -> String {
        String(localized: key, locale: locale)
    }
}

private struct PDFPagePreview: UIViewRepresentable {
    let url: URL

    func makeUIView(context: Context) -> PDFView {
        let view = PDFView()
        view.autoScales = true
        view.displayMode = .singlePageContinuous
        view.displayDirection = .vertical
        view.backgroundColor = .secondarySystemBackground
        view.document = PDFDocument(url: url)
        return view
    }

    func updateUIView(_ uiView: PDFView, context: Context) {
        if uiView.document?.documentURL != url {
            uiView.document = PDFDocument(url: url)
        }
    }
}

private struct ConvertImageCard: View {
    @Environment(\.locale) private var locale
    let source: MediaInfo

    var body: some View {
        if let url = URL(string: source.sourceUri), url.isFileURL,
           let image = UIImage(contentsOfFile: url.path) {
            Image(uiImage: image)
                .resizable()
                .scaledToFit()
                .frame(maxHeight: 240)
                .accessibilityLabel(text("wizard_preview_image"))
        }
    }

    private func text(_ key: String.LocalizationValue) -> String {
        String(localized: key, locale: locale)
    }
}

struct ImportedMovie: Transferable {
    let url: URL
    let sourceFileName: String

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { movie in
            SentTransferredFile(movie.url)
        } importing: { received in
            try importReceived(received)
        }
    }
}

struct ImportedImage: Transferable {
    let url: URL
    let sourceFileName: String

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .image) { image in
            SentTransferredFile(image.url)
        } importing: { received in
            try importReceived(received)
        }
    }
}

private func copyTransferred(_ received: ReceivedTransferredFile) throws -> URL {
    let dest = FileManager.default.temporaryDirectory
        .appendingPathComponent(UUID().uuidString)
        .appendingPathExtension(received.file.pathExtension)
    if FileManager.default.fileExists(atPath: dest.path) {
        try FileManager.default.removeItem(at: dest)
    }
    try FileManager.default.copyItem(at: received.file, to: dest)
    return dest
}

private func importReceived(_ received: ReceivedTransferredFile) throws -> ImportedMovie {
    let dest = try copyTransferred(received)
    return ImportedMovie(url: dest, sourceFileName: received.file.lastPathComponent)
}

private func importReceived(_ received: ReceivedTransferredFile) throws -> ImportedImage {
    let dest = try copyTransferred(received)
    return ImportedImage(url: dest, sourceFileName: received.file.lastPathComponent)
}
