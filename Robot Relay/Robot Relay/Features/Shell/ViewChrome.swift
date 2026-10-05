import SwiftUI

/// Per-view header: title, optional mono subtitle, optional controls, and the Connection chip.
struct ViewHeader<Leading: View, Trailing: View>: View {
    let title: String
    var subtitle: String?
    var compactChip = false
    @ViewBuilder var leading: Leading
    @ViewBuilder var trailing: Trailing

    init(
        _ title: String,
        subtitle: String? = nil,
        compactChip: Bool = false,
        @ViewBuilder leading: () -> Leading = { EmptyView() },
        @ViewBuilder trailing: () -> Trailing = { EmptyView() }
    ) {
        self.title = title
        self.subtitle = subtitle
        self.compactChip = compactChip
        self.leading = leading()
        self.trailing = trailing()
    }

    var body: some View {
        HStack(spacing: 12) {
            Text(title)
                .font(.nocturne(17, .medium))
                .fixedSize()
            if let subtitle {
                Text(subtitle)
                    .font(.nocturneMono(11))
                    .foregroundStyle(Nocturne.neutral500)
                    .lineLimit(1)
            }
            leading
            Spacer(minLength: 0)
            HStack(spacing: 8) {
                trailing
                ConnectionChip(isCompact: compactChip)
            }
        }
        .padding(EdgeInsets(top: 14, leading: 20, bottom: 10, trailing: 20))
    }
}

/// Per-view footer: mono hints in a single row.
struct ViewFooter<Content: View>: View {
    var hasTopRule = false
    @ViewBuilder var content: Content

    var body: some View {
        HStack(spacing: 18) {
            content
        }
        .font(.nocturneMono(11))
        .foregroundStyle(Nocturne.neutral600)
        .lineLimit(1)
        .padding(.horizontal, 20)
        .padding(.vertical, 8)
        .overlay(alignment: .top) {
            if hasTopRule {
                Rectangle().fill(Nocturne.neutral900).frame(height: 1)
            }
        }
    }
}

extension View {
    /// Dims view content while the Connection popover is open.
    func dimmedBehindPopover() -> some View {
        modifier(PopoverDimming())
    }
}

private struct PopoverDimming: ViewModifier {
    @Environment(AppModel.self) private var app

    func body(content: Content) -> some View {
        content
            .opacity(app.isConnectionPopoverPresented ? 0.45 : 1)
            .saturation(app.isConnectionPopoverPresented ? 0.7 : 1)
            .animation(.easeOut(duration: 0.15), value: app.isConnectionPopoverPresented)
    }
}
