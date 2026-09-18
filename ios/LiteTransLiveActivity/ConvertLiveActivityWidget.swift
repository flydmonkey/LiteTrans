import ActivityKit
import AppIntents
import SwiftUI
import WidgetKit
import LiteTransActivityKit

struct ConvertLiveActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: ConvertActivityAttributes.self) { context in
            ConvertLiveActivityBanner(context: context)
                .widgetURL(historyURL(jobId: context.attributes.jobId))
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Image(systemName: "arrow.triangle.2.circlepath")
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text("\(context.state.percent)%")
                        .font(.headline)
                        .monospacedDigit()
                }
                DynamicIslandExpandedRegion(.bottom) {
                    ConvertLiveActivityExpanded(context: context)
                }
            } compactLeading: {
                Image(systemName: "arrow.triangle.2.circlepath")
            } compactTrailing: {
                Text("\(context.state.percent)%")
                    .font(.caption)
                    .monospacedDigit()
                    .minimumScaleFactor(0.6)
            } minimal: {
                Text("\(context.state.percent)%")
                    .font(.caption2)
                    .monospacedDigit()
                    .minimumScaleFactor(0.5)
            }
            .widgetURL(historyURL(jobId: context.attributes.jobId))
        }
    }
}

private struct ConvertLiveActivityBanner: View {
    let context: ActivityViewContext<ConvertActivityAttributes>

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(context.state.filename)
                .font(.headline)
                .foregroundStyle(Color(uiColor: .label))
                .lineLimit(1)
            ProgressView(value: Double(context.state.percent), total: 100)
            HStack {
                Text("\(context.state.percent)%")
                    .font(.subheadline)
                    .monospacedDigit()
                    .foregroundStyle(Color(uiColor: .secondaryLabel))
                Spacer()
                ConvertCancelButton(jobId: context.attributes.jobId)
            }
        }
        .padding(16)
        .activityBackgroundTint(Color(uiColor: .systemBackground))
    }
}

private struct ConvertLiveActivityExpanded: View {
    let context: ActivityViewContext<ConvertActivityAttributes>

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(context.state.filename)
                .font(.subheadline)
                .lineLimit(1)
            ProgressView(value: Double(context.state.percent), total: 100)
            ConvertCancelButton(jobId: context.attributes.jobId)
        }
    }
}

private struct ConvertCancelButton: View {
    let jobId: String

    var body: some View {
        Button(intent: CancelConvertIntent(jobId: jobId)) {
            Text("live_activity_cancel", bundle: .main)
                .font(.body)
                .frame(minWidth: 44, minHeight: 44)
        }
        .buttonStyle(.bordered)
    }
}

private func historyURL(jobId: String) -> URL? {
    URL(string: "litetrans://history?job=\(jobId)")
}
