import SwiftUI

struct JobRow: View {
    @Environment(\.locale) private var locale
    let job: Job
    var outputURL: URL?
    var onCancel: () -> Void
    var onRetry: () -> Void
    var onOpen: () -> Void
    var onRename: () -> Void
    var onDelete: () -> Void

    private var actions: [JobRowAction] { jobRowActions(job.status) }

    var body: some View {
        Button(action: handleTap) {
            VStack(alignment: .leading, spacing: 8) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(Color(uiColor: .label))
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(subtitle)
                    .font(.footnote)
                    .foregroundStyle(subtitleColor)
                if job.status == .running {
                    ProgressView(value: min(max(job.progress, 0), 100), total: 100)
                        .tint(Color.accentColor)
                        .accessibilityLabel(text("status_running"))
                        .accessibilityValue("\(Int(job.progress.rounded()))%")
                }
            }
            .frame(minHeight: 44, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
            if actions.contains(.cancel) {
                Button(text("action_cancel"), role: .destructive, action: onCancel)
            }
            if actions.contains(.delete) {
                Button(text("action_delete"), role: .destructive, action: onDelete)
            }
        }
        .contextMenu {
            if actions.contains(.retry) {
                Button(action: onRetry) {
                    Label(text("action_retry"), systemImage: "arrow.clockwise")
                }
            }
            if actions.contains(.share), let outputURL {
                ShareLink(item: outputURL) {
                    Label(text("action_share"), systemImage: "square.and.arrow.up")
                }
            }
            if actions.contains(.rename) {
                Button(action: onRename) {
                    Label(text("action_rename"), systemImage: "pencil")
                }
            }
        }
    }

    private var title: String {
        job.displayName.isEmpty ? text("untitled") : job.displayName
    }

    private var subtitle: String {
        let parts: [String]
        if job.status == .failed, let error = job.error, !error.isEmpty {
            parts = [error, multiOutputText].compactMap { $0 }
        } else if job.status == .running {
            parts = ["\(Int(job.progress.rounded()))% · \(statusText)", multiOutputText].compactMap { $0 }
        } else {
            parts = [statusText, multiOutputText].compactMap { $0 }
        }
        return parts.joined(separator: " · ")
    }

    private var multiOutputText: String? {
        guard job.outputPaths.count > 1 else { return nil }
        let count = job.outputPaths.count
        if documentResultIsImage(job.config.preset) {
            return String(localized: "history_output_images \(count)", locale: locale)
        }
        return String(localized: "history_output_pdfs \(count)", locale: locale)
    }

    private var subtitleColor: Color {
        job.status == .failed ? Color(uiColor: .systemRed) : Color(uiColor: .secondaryLabel)
    }

    private var statusText: String {
        switch job.status {
        case .queued: text("status_queued")
        case .running: text("status_running")
        case .completed: text("status_completed")
        case .failed: text("status_failed")
        case .cancelled: text("status_cancelled")
        }
    }

    private func text(_ key: String.LocalizationValue) -> String {
        String(localized: key, locale: locale)
    }

    private func handleTap() {
        if actions.contains(.open) {
            onOpen()
        } else if actions.contains(.retry) {
            onRetry()
        }
    }
}
