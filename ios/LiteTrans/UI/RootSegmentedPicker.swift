import SwiftUI
import UIKit

/// In-screen mode switch (video / audio / document). System segmented control,
/// sized to the HIG 44pt control floor with body Dynamic Type instead of the
/// default 32pt / caption control that reads too small under a large title.
struct RootSegmentedPicker<Value: Hashable>: View {
    @Binding var selection: Value
    let accessibilityLabel: String
    let options: [(value: Value, title: String)]

    var body: some View {
        Representable(
            selection: $selection,
            options: options,
            accessibilityLabel: accessibilityLabel
        )
        .frame(maxWidth: .infinity)
        .frame(height: Theme.rootSegmentHeight)
        .accessibilityLabel(accessibilityLabel)
    }
}

private struct Representable<Value: Hashable>: UIViewRepresentable {
    @Binding var selection: Value
    let options: [(value: Value, title: String)]
    let accessibilityLabel: String
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    func makeUIView(context: Context) -> BodySegmentedControl {
        let control = BodySegmentedControl(items: options.map(\.title))
        control.selectedSegmentIndex = index(of: selection)
        control.apportionsSegmentWidthsByContent = false
        control.accessibilityLabel = accessibilityLabel
        control.addTarget(context.coordinator, action: #selector(Coordinator.changed(_:)), for: .valueChanged)
        applyChrome(control)
        control.setContentHuggingPriority(.required, for: .vertical)
        control.setContentCompressionResistancePriority(.required, for: .vertical)
        return control
    }

    func updateUIView(_ control: BodySegmentedControl, context: Context) {
        context.coordinator.parent = self
        syncTitles(control)
        let next = index(of: selection)
        if control.selectedSegmentIndex != next {
            control.selectedSegmentIndex = next
        }
        control.accessibilityLabel = accessibilityLabel
        applyChrome(control)
        control.setContentHuggingPriority(.required, for: .vertical)
        control.setContentCompressionResistancePriority(.required, for: .vertical)
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: BodySegmentedControl, context: Context) -> CGSize? {
        CGSize(width: proposal.width ?? uiView.intrinsicContentSize.width, height: Theme.rootSegmentHeight)
    }

    private func index(of value: Value) -> Int {
        options.firstIndex(where: { $0.value == value }) ?? 0
    }

    private func syncTitles(_ control: UISegmentedControl) {
        if control.numberOfSegments != options.count {
            control.removeAllSegments()
            for (index, option) in options.enumerated() {
                control.insertSegment(withTitle: option.title, at: index, animated: false)
            }
            return
        }
        for (index, option) in options.enumerated() where control.titleForSegment(at: index) != option.title {
            control.setTitle(option.title, forSegmentAt: index)
        }
    }

    private func applyChrome(_ control: UISegmentedControl) {
        let body = UIFont.preferredFont(forTextStyle: .body)
        let selected = UIFont.systemFont(ofSize: body.pointSize, weight: .semibold)
        control.setTitleTextAttributes([
            .font: body,
            .foregroundColor: UIColor.label
        ], for: .normal)
        control.setTitleTextAttributes([
            .font: selected,
            .foregroundColor: UIColor.label
        ], for: .selected)
    }

    @MainActor
    final class Coordinator: NSObject {
        var parent: Representable
        private let haptic = UISelectionFeedbackGenerator()

        init(_ parent: Representable) {
            self.parent = parent
        }

        @objc func changed(_ sender: UISegmentedControl) {
            let index = sender.selectedSegmentIndex
            guard parent.options.indices.contains(index) else { return }
            haptic.prepare()
            haptic.selectionChanged()
            parent.selection = parent.options[index].value
        }
    }
}

private final class BodySegmentedControl: UISegmentedControl {
    override var intrinsicContentSize: CGSize {
        var size = super.intrinsicContentSize
        size.height = Theme.rootSegmentHeight
        return size
    }

    override func sizeThatFits(_ size: CGSize) -> CGSize {
        CGSize(width: size.width.isFinite && size.width > 0 ? size.width : super.sizeThatFits(size).width, height: Theme.rootSegmentHeight)
    }
}

extension View {
    func blankAreaTabSwipe(onSwipeLeft: @escaping () -> Void, onSwipeRight: @escaping () -> Void) -> some View {
        background {
            BlankAreaSwipeCatcher(onSwipeLeft: onSwipeLeft, onSwipeRight: onSwipeRight)
        }
    }
}

private struct BlankAreaSwipeCatcher: UIViewRepresentable {
    var onSwipeLeft: () -> Void
    var onSwipeRight: () -> Void

    func makeUIView(context: Context) -> CatcherView {
        let view = CatcherView()
        view.isUserInteractionEnabled = false
        view.backgroundColor = .clear
        view.onSwipeLeft = onSwipeLeft
        view.onSwipeRight = onSwipeRight
        return view
    }

    func updateUIView(_ uiView: CatcherView, context: Context) {
        uiView.onSwipeLeft = onSwipeLeft
        uiView.onSwipeRight = onSwipeRight
    }

    final class CatcherView: UIView, UIGestureRecognizerDelegate {
        var onSwipeLeft: (() -> Void)?
        var onSwipeRight: (() -> Void)?
        private weak var host: UIView?
        private var leftRecognizer: UISwipeGestureRecognizer?
        private var rightRecognizer: UISwipeGestureRecognizer?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            if window == nil {
                uninstall()
            } else {
                install(on: pageHost(startingFrom: superview))
            }
        }

        private func pageHost(startingFrom start: UIView?) -> UIView? {
            var current = start
            var candidate = start
            while let node = current {
                if node is UIWindow { break }
                if node.next is UITabBarController { break }
                if node is UITabBar { break }
                candidate = node
                current = node.superview
            }
            return candidate
        }

        private func install(on host: UIView?) {
            guard let host else { return }
            if host === self.host, leftRecognizer != nil { return }
            uninstall()
            self.host = host
            let left = UISwipeGestureRecognizer(target: self, action: #selector(handleLeft))
            left.direction = .left
            left.delegate = self
            let right = UISwipeGestureRecognizer(target: self, action: #selector(handleRight))
            right.direction = .right
            right.delegate = self
            host.addGestureRecognizer(left)
            host.addGestureRecognizer(right)
            leftRecognizer = left
            rightRecognizer = right
        }

        private func uninstall() {
            if let leftRecognizer {
                host?.removeGestureRecognizer(leftRecognizer)
            }
            if let rightRecognizer {
                host?.removeGestureRecognizer(rightRecognizer)
            }
            leftRecognizer = nil
            rightRecognizer = nil
            host = nil
        }

        @objc private func handleLeft() {
            onSwipeLeft?()
        }

        @objc private func handleRight() {
            onSwipeRight?()
        }

        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
            !touchHitsOccupiedControl(touch.view)
        }

        func gestureRecognizer(
            _ gestureRecognizer: UIGestureRecognizer,
            shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer
        ) -> Bool {
            true
        }
    }
}

private func touchHitsOccupiedControl(_ view: UIView?) -> Bool {
    var current = view
    while let node = current {
        if node is UICollectionViewCell || node is UITableViewCell || node is UISegmentedControl {
            return true
        }
        current = node.superview
    }
    return false
}
