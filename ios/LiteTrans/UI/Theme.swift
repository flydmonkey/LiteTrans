import SwiftUI

/// Accent is primary (black in light mode, white in dark mode).
enum Theme {
    static let accent = Color.primary
    /// Space below the status bar for a root large title. HIG uses an 8pt grid;
    /// 2pt crowds the Dynamic Island, a compact nav-bar row (~44pt) sits too far down.
    static let rootTitleTop: CGFloat = 8
    /// Space between the large title and the next header control.
    static let rootHeaderSpacing: CGFloat = 16
    /// Space between the header block and the first grouped list or form.
    /// 8pt is intra-group; HIG separates chrome from content on the 16pt step.
    static let rootHeaderBottom: CGFloat = 16
    /// Shared height for the video / audio / document segmented control.
    static let rootSegmentHeight: CGFloat = 44
}
