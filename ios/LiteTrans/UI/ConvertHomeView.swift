import AVFoundation
import PDFKit
import PhotosUI
import SwiftUI
import UIKit
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
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: Theme.rootHeaderSpacing) {
                RootLargeTitle(title: text("tab_convert"))
                RootSegmentedPicker(
                    selection: $model.convertMode,
                    accessibilityLabel: text("segment_convert"),
                    options: [
                        (ConvertMode.video, text("segment_video")),
                        (ConvertMode.audio, text("segment_audio")),
                        (ConvertMode.document, text("segment_document")),
                    ]
                )
            }
            .padding(.horizontal, 16)
            .padding(.top, Theme.rootTitleTop)
            .padding(.bottom, Theme.rootHeaderBottom)

            List {
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
                Section {
                    previewSection(selected)
                        .listRowSeparator(.hidden)
                        .buttonStyle(.plain)
                }
            }

            Section {
                sourcePickers
            }

            Section {
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
                                .tint(Color(uiColor: .systemRed))
                            }
                        }
                }
                .onMove { offsets, destination in
                    guard isVideoConcatPreset(model.preset) else { return }
                    model.moveSources(from: offsets, to: destination)
                }
            } header: {
                Text(text("section_files"))
            } footer: {
                if model.sources.isEmpty {
                    Text(footerText)
                } else if isVideoConcatPreset(model.preset) && model.importableCount < 2 {
                    Text(text("concat_need_two"))
                }
            }
            .environment(
                \.editMode,
                isVideoConcatPreset(model.preset) && model.sources.count > 1
                    ? .constant(.active)
                    : .constant(.inactive)
            )

            Section(text("section_settings")) {
                ForEach(convertSettingsFor(preset: model.preset), id: \.self) { setting in
                    NavigationLink(value: convertPage(for: setting)) {
                        LabeledContent(settingTitle(setting), value: settingValue(setting))
                    }
                }
            }
            }
            .listStyle(.insetGrouped)
            .contentMargins(.top, 0, for: .scrollContent)
            .scrollContentBackground(.hidden)
            .background(EnclosingScrollTouchTuner())
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .blankAreaTabSwipe(
            onSwipeLeft: { moveConvertTab(step: 1) },
            onSwipeRight: { moveConvertTab(step: -1) }
        )
        .navigationTitle(text("tab_convert"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .navigationBar)
        .animation(reduceMotion ? .easeInOut(duration: 0.2) : .snappy, value: model.convertMode)
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
        switch model.convertMode {
        case .video:
            if itemHasDuration(selected), allowsTrim(preset: model.preset) {
                ConvertTrimCard(source: selected, showsVideo: true)
                    .id(selected.sourceUri)
                    .transition(.opacity)
            }
        case .audio:
            if itemHasDuration(selected), allowsTrim(preset: model.preset) {
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

    private var photoMatching: PHPickerFilter {
        model.convertMode == .document ? .images : .videos
    }

    private var galleryHint: String {
        switch model.convertMode {
        case .audio: text("wizard_source_gallery_hint_audio")
        case .document: text("wizard_source_gallery_hint_document")
        case .video: text("wizard_source_gallery_hint_video")
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
            + documentCards(for: .word)
        if let card = cards.first(where: { $0.id == preset }) {
            return localizedPresetCardTitle(card, locale: locale)
        }
        return listPresets().first { $0.id == preset }?.label ?? preset
    }

    private func moveConvertTab(step: Int) {
        guard let next = adjacentCase(model.convertMode, step: step) else { return }
        model.convertMode = next
        UISelectionFeedbackGenerator().selectionChanged()
    }

    private func text(_ key: String.LocalizationValue) -> String {
        localizedText(key, locale: locale)
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
                        .foregroundStyle(Color.primary)
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
        if allowsTrim(preset: model.preset), isTrimmed(source) {
            parts.append(text("wizard_trimmed"))
        }
        return parts.isEmpty ? text("wizard_kind_video") : parts.joined(separator: " · ")
    }

    private func text(_ key: String.LocalizationValue) -> String {
        localizedText(key, locale: locale)
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
    @State private var isPlaying = false
    @State private var timeObserver: Any?

    private var duration: Double { max(source.durationSecs ?? 0, 0.001) }
    private var trimStart: Double { min(max(source.trimStartSecs ?? 0, 0), duration) }
    private var trimEnd: Double { min(max(source.trimEndSecs ?? duration, 0), duration) }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 8) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(text("trim_title"))
                        .font(.headline)
                    Text(text("trim_hint"))
                        .font(.footnote)
                        .foregroundStyle(Color(uiColor: .secondaryLabel))
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Button(text("trim_reset")) {
                    resetTrim()
                }
                .frame(minHeight: 44)
            }

            if showsVideo {
                preview
            }

            HStack(spacing: 8) {
                Button(text("trim_set_start")) { setStart() }
                    .frame(maxWidth: .infinity, minHeight: 44)
                Button(text("trim_set_end")) { setEnd() }
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.bordered)

            TrimTrack(
                duration: duration,
                start: trimStart,
                end: trimEnd,
                playhead: playhead,
                onStart: { applyTrim(start: $0, end: trimEnd) },
                onEnd: { applyTrim(start: trimStart, end: $0) },
                onPlayhead: { seekPlayhead($0) }
            )
            .accessibilityLabel(text("trim_title"))
            .accessibilityValue(formatClock(playhead))

            HStack {
                Text(formatClock(trimStart))
                Spacer()
                Text(formatClock(playhead))
                Spacer()
                Text(formatClock(trimEnd))
            }
            .font(.footnote)
            .foregroundStyle(Color(uiColor: .secondaryLabel))

            if isTrimmed(source) {
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
        ZStack {
            if let player {
                TrimPlayerView(player: player)
                    .accessibilityLabel(text("wizard_preview_video"))
            } else {
                Text(text("trim_no_preview"))
                    .font(.footnote)
                    .foregroundStyle(Color(uiColor: .secondaryLabel))
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                    .padding(.horizontal, 12)
            }
            Button(action: togglePlayback) {
                Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                    .font(.title2)
                    .frame(width: 56, height: 56)
                    .background(.ultraThinMaterial, in: Circle())
            }
            .buttonStyle(.plain)
            .disabled(player == nil)
            .accessibilityLabel(text(isPlaying ? "trim_pause" : "trim_play_selection"))
        }
        .frame(maxWidth: .infinity, minHeight: 180)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .background(Color(uiColor: .tertiarySystemFill), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private func text(_ key: String.LocalizationValue) -> String {
        localizedText(key, locale: locale)
    }

    private func setupPlayer() {
        teardownPlayer()
        guard let url = mediaFileURL(from: source.sourceUri),
              FileManager.default.fileExists(atPath: url.path)
        else { return }
        let player = AVPlayer(url: url)
        let interval = CMTime(seconds: 0.1, preferredTimescale: 600)
        timeObserver = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { time in
            guard !isSeeking else { return }
            let seconds = CMTimeGetSeconds(time)
            guard seconds.isFinite else { return }
            playhead = min(max(seconds, 0), duration)
            if player.timeControlStatus == .playing, playhead >= trimEnd - 0.04 {
                player.pause()
                seekPlayer(to: trimStart)
                playhead = trimStart
                isPlaying = false
            } else {
                isPlaying = player.timeControlStatus == .playing
            }
        }
        self.player = player
        seekPlayer(to: trimStart)
        playhead = trimStart
    }

    private func teardownPlayer() {
        if let player, let timeObserver {
            player.removeTimeObserver(timeObserver)
        }
        timeObserver = nil
        player?.pause()
        player = nil
        isPlaying = false
    }

    private func seekPlayer(to seconds: Double) {
        guard let player else { return }
        let clamped = min(max(seconds, 0), duration)
        let time = CMTime(seconds: clamped, preferredTimescale: 600)
        player.seek(to: time, toleranceBefore: .zero, toleranceAfter: .zero)
    }

    private func seekPlayhead(_ seconds: Double) {
        isSeeking = true
        playhead = min(max(seconds, 0), duration)
        seekPlayer(to: playhead)
        isSeeking = false
    }

    private func togglePlayback() {
        guard let player else { return }
        if player.timeControlStatus == .playing {
            player.pause()
            isPlaying = false
            return
        }
        if playhead < trimStart || playhead >= trimEnd - 0.04 {
            seekPlayhead(trimStart)
        }
        player.play()
        isPlaying = true
    }

    private func applyTrim(start: Double, end: Double) {
        let clamped = clampTrim(start: start, end: end, duration: duration)
        var next = source
        next.trimStartSecs = clamped.start
        next.trimEndSecs = clamped.end
        model.replaceSource(next)
    }

    private func setStart() {
        applyTrim(start: playhead, end: trimEnd)
    }

    private func setEnd() {
        applyTrim(start: trimStart, end: playhead)
    }

    private func resetTrim() {
        var next = source
        next.trimStartSecs = nil
        next.trimEndSecs = nil
        model.replaceSource(next)
        seekPlayhead(0)
    }

    private func formatClock(_ seconds: Double) -> String {
        let total = Int(seconds.rounded())
        return String(format: "%d:%02d", total / 60, total % 60)
    }
}

private struct TrimTrack: View {
    let duration: Double
    let start: Double
    let end: Double
    let playhead: Double
    let onStart: (Double) -> Void
    let onEnd: (Double) -> Void
    let onPlayhead: (Double) -> Void

    var body: some View {
        GeometryReader { geo in
            let inset: CGFloat = 11
            let width = max(geo.size.width - inset * 2, 1)
            let startX = (start / duration) * width
            let endX = (end / duration) * width
            let playX = (playhead / duration) * width
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(Color(uiColor: .tertiarySystemFill))
                    .frame(height: 8)
                Capsule()
                    .fill(Color(uiColor: .label).opacity(0.35))
                    .frame(width: max(endX - startX, 2), height: 8)
                    .offset(x: startX)
                Capsule()
                    .fill(Color.primary)
                    .frame(width: 2, height: 28)
                    .offset(x: playX - 1)
                handle.offset(x: startX - 11)
                handle.offset(x: endX - 11)
            }
            .padding(.horizontal, inset)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .contentShape(Rectangle())
            .overlay {
                TrimTrackDragOverlay(
                    duration: duration,
                    start: start,
                    end: end,
                    inset: inset,
                    onStart: onStart,
                    onEnd: onEnd,
                    onPlayhead: onPlayhead
                )
            }
        }
        .frame(minHeight: 44)
        .accessibilityAdjustableAction { direction in
            let step = duration / 50
            switch direction {
            case .increment: onPlayhead(min(playhead + step, duration))
            case .decrement: onPlayhead(max(playhead - step, 0))
            default: break
            }
        }
    }

    private var handle: some View {
        Circle()
            .fill(Color.primary)
            .overlay(Circle().stroke(Color(uiColor: .systemBackground), lineWidth: 2))
            .frame(width: 22, height: 22)
    }
}

private struct TrimTrackDragOverlay: UIViewRepresentable {
    var duration: Double
    var start: Double
    var end: Double
    var inset: CGFloat
    var onStart: (Double) -> Void
    var onEnd: (Double) -> Void
    var onPlayhead: (Double) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeUIView(context: Context) -> TrimTrackHitView {
        let view = TrimTrackHitView()
        context.coordinator.bind(view: view, representable: self)
        return view
    }

    func updateUIView(_ uiView: TrimTrackHitView, context: Context) {
        context.coordinator.bind(view: uiView, representable: self)
    }

    final class Coordinator {
        var duration = 0.001
        var start = 0.0
        var end = 1.0
        var inset: CGFloat = 11
        var onStart: (Double) -> Void = { _ in }
        var onEnd: (Double) -> Void = { _ in }
        var onPlayhead: (Double) -> Void = { _ in }
        var dragging: TrimDragTarget?

        func bind(view: TrimTrackHitView, representable: TrimTrackDragOverlay) {
            duration = representable.duration
            start = representable.start
            end = representable.end
            inset = representable.inset
            onStart = representable.onStart
            onEnd = representable.onEnd
            onPlayhead = representable.onPlayhead
            view.onDown = { [weak self] point in
                self?.handle(point, began: true, width: view.bounds.width)
            }
            view.onMove = { [weak self] point in
                self?.handle(point, began: false, width: view.bounds.width)
            }
            view.onUp = { [weak self] in
                self?.dragging = nil
            }
        }

        func handle(_ point: CGPoint, began: Bool, width: CGFloat) {
            let trackWidth = max(Double(width - inset * 2), 1)
            let x = Double(point.x - inset)
            if began || dragging == nil {
                dragging = trimDragTarget(
                    x: x,
                    width: trackWidth,
                    duration: duration,
                    start: start,
                    end: end,
                    hitSlop: 22
                )
            }
            let time = timeAt(x: x, width: trackWidth, duration: duration)
            switch dragging {
            case .start: onStart(time)
            case .end: onEnd(time)
            case .playhead, .none: onPlayhead(time)
            }
        }
    }
}

private final class TrimTrackHitView: UIView {
    var onDown: ((CGPoint) -> Void)?
    var onMove: ((CGPoint) -> Void)?
    var onUp: (() -> Void)?
    private weak var scrollView: UIScrollView?
    private var savedScrollEnabled = true

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isMultipleTouchEnabled = false
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        scrollView = enclosingScrollView()
        scrollView?.delaysContentTouches = false
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        savedScrollEnabled = scrollView?.isScrollEnabled ?? true
        scrollView?.isScrollEnabled = false
        if let point = touches.first?.location(in: self) {
            onDown?(point)
        }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        if let point = touches.first?.location(in: self) {
            onMove?(point)
        }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        finishTouch()
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        finishTouch()
    }

    private func finishTouch() {
        onUp?()
        scrollView?.isScrollEnabled = savedScrollEnabled
    }

    private func enclosingScrollView() -> UIScrollView? {
        var current: UIView? = superview
        while let node = current {
            if let scroll = node as? UIScrollView {
                return scroll
            }
            current = node.superview
        }
        return nil
    }
}

private struct EnclosingScrollTouchTuner: UIViewRepresentable {
    func makeUIView(context: Context) -> UIView {
        let view = TunerView()
        view.isUserInteractionEnabled = false
        view.backgroundColor = .clear
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {}

    private final class TunerView: UIView {
        override func didMoveToWindow() {
            super.didMoveToWindow()
            var current: UIView? = self
            while let node = current {
                if let scroll = node as? UIScrollView {
                    scroll.delaysContentTouches = false
                    return
                }
                current = node.superview
            }
        }
    }
}

private struct TrimPlayerView: UIViewRepresentable {
    let player: AVPlayer

    func makeUIView(context: Context) -> TrimPlayerLayerView {
        let view = TrimPlayerLayerView()
        view.backgroundColor = .tertiarySystemFill
        view.player = player
        return view
    }

    func updateUIView(_ uiView: TrimPlayerLayerView, context: Context) {
        uiView.player = player
    }
}

private final class TrimPlayerLayerView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }

    var player: AVPlayer? {
        get { (layer as? AVPlayerLayer)?.player }
        set {
            (layer as? AVPlayerLayer)?.player = newValue
            (layer as? AVPlayerLayer)?.videoGravity = .resizeAspect
        }
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
        mediaFileURL(from: source.sourceUri)
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
        localizedText(key, locale: locale)
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
        if let url = mediaFileURL(from: source.sourceUri),
           let image = UIImage(contentsOfFile: url.path) {
            Image(uiImage: image)
                .resizable()
                .scaledToFit()
                .frame(maxHeight: 240)
                .frame(maxWidth: .infinity)
                .accessibilityLabel(text("wizard_preview_image"))
        }
    }

    private func text(_ key: String.LocalizationValue) -> String {
        localizedText(key, locale: locale)
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
