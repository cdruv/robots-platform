import SwiftUI

/// Status chip at the top-right of every view; opens the links popover.
struct ConnectionChip: View {
    /// Compact drops the label and keeps the dot and detail.
    var isCompact = false

    @Environment(AppModel.self) private var app
    @State private var isHovering = false

    var body: some View {
        @Bindable var app = app
        let summary = app.connection.summary
        let shape = RoundedRectangle(cornerRadius: Nocturne.Radius.md)
        Button {
            app.isConnectionPopoverPresented.toggle()
        } label: {
            HStack(spacing: 8) {
                StatusDot(tone: summary.tone)
                if !isCompact {
                    Text(summary.label)
                        .font(.nocturne(12, .medium))
                        .foregroundStyle(Nocturne.neutral200)
                }
                // Ticks so "retry Ns" counts down.
                TimelineView(.periodic(from: .now, by: 1)) { _ in
                    Text(app.connection.summary.detail)
                        .font(.nocturneMono(11))
                        .foregroundStyle(Nocturne.neutral500)
                }
                if app.isConnectionPopoverPresented {
                    Text("▴")
                        .font(.nocturneMono(11))
                        .foregroundStyle(Nocturne.neutral600)
                }
            }
            .lineLimit(1)
            .fixedSize()
            .padding(EdgeInsets(top: 5, leading: 9, bottom: 5, trailing: 10))
            .background(
                isHovering || app.isConnectionPopoverPresented ? Nocturne.text.opacity(0.07) : .clear,
                in: shape
            )
            .overlay(shape.strokeBorder(Nocturne.divider, lineWidth: 1))
            .contentShape(shape)
        }
        .buttonStyle(.plain)
        .onHover { isHovering = $0 }
        .popover(isPresented: $app.isConnectionPopoverPresented, arrowEdge: .bottom) {
            ConnectionPopover()
                .environment(app)
        }
    }
}
