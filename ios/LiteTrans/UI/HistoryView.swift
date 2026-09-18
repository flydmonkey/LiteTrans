import QuickLook
import SwiftUI

struct HistoryView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.locale) private var locale
    @State private var previewJob: Job?
    @State private var pendingDelete: Job?
    @State private var renameJob: Job?
    @State private var renameText = ""
    @State private var showClearConfirm = false

    private var visibleJobs: [Job] {
        historyJobs(model.jobs, segment: model.historySegment)
    }

    var body: some View {
        Group {
            if visibleJobs.isEmpty {
                VStack(spacing: 8) {
                    historySegmentPicker
                        .padding(.horizontal, 16)
                        .padding(.top, 8)
                    ContentUnavailableView {
                        Label(text(emptyTitleKey), systemImage: "clock")
                    } description: {
                        Text(text("history_empty_hint"))
                    } actions: {
                        Button(text("history_go_convert")) {
                            goConvert()
                        }
                        .frame(minHeight: 44)
                    }
                }
            } else {
                List {
                    Section {
                        historySegmentPicker
                    }
                    .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                    .listRowBackground(Color.clear)
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
                    ForEach(visibleJobs) { job in
                        JobRow(
                            job: job,
                            outputURL: model.outputFileURL(for: job),
                            onCancel: { model.cancelJob(job) },
                            onRetry: { model.retryJob(job) },
                            onOpen: { previewJob = job },
                            onRename: { beginRename(job) },
                            onDelete: { pendingDelete = job }
                        )
                    }
                }
                .listStyle(.insetGrouped)
            }
        }
        .navigationTitle(text("tab_history"))
        .navigationBarTitleDisplayMode(.large)
        .onDisappear {
            model.releaseHistoryOutputAccess()
        }
        .toolbar {
            if model.hasFinishedJobs {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(text("action_clear")) {
                        showClearConfirm = true
                    }
                    .frame(minHeight: 44)
                }
            }
        }
        .sheet(item: $previewJob) { job in
            if let url = model.outputFileURL(for: job) {
                NavigationStack {
                    QuickLookPreview(url: url)
                        .ignoresSafeArea()
                        .navigationTitle(URL(fileURLWithPath: url.path).lastPathComponent)
                        .navigationBarTitleDisplayMode(.inline)
                        .toolbar {
                            ToolbarItem(placement: .cancellationAction) {
                                Button(text("action_done")) {
                                    previewJob = nil
                                }
                                .frame(minHeight: 44)
                            }
                        }
                }
            }
        }
        .alert(text("history_clear_title"), isPresented: $showClearConfirm) {
            Button(text("action_clear"), role: .destructive) {
                model.clearFinished()
            }
            Button(text("action_cancel"), role: .cancel) {}
        } message: {
            Text(text("history_clear_message"))
        }
        .alert(text("history_delete_title"), isPresented: deleteConfirm) {
            Button(text("action_delete"), role: .destructive) {
                if let job = pendingDelete {
                    model.deleteJob(job)
                }
                pendingDelete = nil
            }
            Button(text("action_cancel"), role: .cancel) {
                pendingDelete = nil
            }
        } message: {
            Text(text("history_delete_message"))
        }
        .alert(text("history_rename_title"), isPresented: renameConfirm) {
            TextField(text("history_rename_hint"), text: $renameText)
            Button(text("action_rename")) {
                if let job = renameJob {
                    model.renameJob(job, rawName: renameText)
                }
                renameJob = nil
            }
            Button(text("action_cancel"), role: .cancel) {
                renameJob = nil
            }
        } message: {
            Text(text("history_rename_hint"))
        }
    }

    private var emptyTitleKey: String.LocalizationValue {
        switch model.historySegment {
        case .video: "history_empty_video"
        case .audio: "history_empty_audio"
        case .document: "history_empty_document"
        }
    }

    private var deleteConfirm: Binding<Bool> {
        Binding(
            get: { pendingDelete != nil },
            set: { if !$0 { pendingDelete = nil } }
        )
    }

    private var renameConfirm: Binding<Bool> {
        Binding(
            get: { renameJob != nil },
            set: { if !$0 { renameJob = nil } }
        )
    }

    private var historySegmentPicker: some View {
        Picker("", selection: historySegmentBinding) {
            Text(text("segment_video")).tag(HistorySegment.video)
            Text(text("segment_audio")).tag(HistorySegment.audio)
            Text(text("segment_document")).tag(HistorySegment.document)
        }
        .pickerStyle(.segmented)
        .labelsHidden()
        .accessibilityLabel(text("segment_history"))
        .frame(minHeight: 44)
    }

    private var historySegmentBinding: Binding<HistorySegment> {
        Binding(
            get: { model.historySegment },
            set: { model.historySegment = $0 }
        )
    }

    private func goConvert() {
        switch model.historySegment {
        case .video: model.convertMode = .video
        case .audio: model.convertMode = .audio
        case .document: model.convertMode = .document
        }
        model.tab = .convert
    }

    private func text(_ key: String.LocalizationValue) -> String {
        String(localized: key, locale: locale)
    }

    private func beginRename(_ job: Job) {
        renameText = sourceStem(job.displayName.isEmpty ? (job.outputPath.map { URL(fileURLWithPath: $0).lastPathComponent } ?? "") : job.displayName)
        renameJob = job
    }
}

private struct QuickLookPreview: UIViewControllerRepresentable {
    let url: URL

    func makeCoordinator() -> Coordinator {
        Coordinator(url: url)
    }

    func makeUIViewController(context: Context) -> QLPreviewController {
        let controller = QLPreviewController()
        controller.dataSource = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: QLPreviewController, context: Context) {
        context.coordinator.url = url
        controller.reloadData()
    }

    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        var url: URL

        init(url: URL) {
            self.url = url
        }

        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }

        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> any QLPreviewItem {
            url as NSURL
        }
    }
}
