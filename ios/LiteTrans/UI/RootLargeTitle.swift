import SwiftUI

struct RootLargeTitle: View {
    let title: String

    var body: some View {
        Text(title)
            .font(.largeTitle.bold())
            .foregroundStyle(Color(uiColor: .label))
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityAddTraits(.isHeader)
    }
}
