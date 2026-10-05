import SwiftUI

/// Event stream with an inspector for the selected event.
struct TelemetryView: View {
    var body: some View {
        HStack(spacing: 0) {
            TelemetryStreamView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            Rectangle()
                .fill(Nocturne.neutral900)
                .frame(width: 1)
            TelemetryInspector()
                .frame(width: 300)
                .dimmedBehindPopover()
        }
    }
}

/// Scroll position of the stream, shared with the minimap.
struct StreamScrollMetrics: Equatable {
    var offset: CGFloat = 0
    var contentHeight: CGFloat = 0
    var containerHeight: CGFloat = 0

    var isAtBottom: Bool {
        offset + containerHeight >= contentHeight - 24
    }

    /// Visible window as fractions of the content, for the minimap box.
    var visibleRange: ClosedRange<CGFloat> {
        guard contentHeight > containerHeight, contentHeight > 0 else { return 0...1 }
        let start = min(max(offset / contentHeight, 0), 1)
        let end = min(max((offset + containerHeight) / contentHeight, start), 1)
        return start...end
    }
}

struct TelemetryStreamView: View {
    @Environment(AppModel.self) private var app
    @State private var metrics = StreamScrollMetrics()
    @State private var isUserScrolling = false

    var body: some View {
        @Bindable var store = app.telemetry
        ScrollViewReader { proxy in
            VStack(spacing: 0) {
                ViewHeader("Telemetry", compactChip: true) {
                    NocturneSegmented(
                        selection: $store.filter,
                        options: [(.all, "All"), (.warnPlus, "Warn+"), (.error, "Error")]
                    )
                } trailing: {
                    if !store.followLive {
                        Button("Paused · \(store.behindCount) new") { jumpToLive(proxy) }
                            .buttonStyle(NocturneButtonStyle(kind: .secondary, fontSize: 13, foreground: Nocturne.accent300))
                    }
                }

                HStack(spacing: 0) {
                    list(store: store, proxy: proxy)
                    TelemetryMinimap(events: store.visibleEvents, visibleRange: metrics.visibleRange) { event in
                        store.select(event.seq)
                        withAnimation(.easeOut(duration: 0.2)) {
                            proxy.scrollTo(event.seq, anchor: .center)
                        }
                    }
                    .frame(width: 10)
                    .padding(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 4))
                }
                .dimmedBehindPopover()

                ViewFooter {
                    Text("seq \(store.lastSeq.map(String.init) ?? "—")")
                    if !store.followLive {
                        Text("\(store.behindCount) behind")
                            .foregroundStyle(Nocturne.accent300)
                    }
                    Spacer()
                    NocturneSegmented(
                        selection: $store.timeMode,
                        options: [(.relative, "T+"), (.clock, "Clock")],
                        fontSize: 11,
                        horizontalPadding: 8,
                        verticalPadding: 2
                    )
                    .foregroundStyle(Nocturne.text)
                    Button("Export") { store.export() }
                        .buttonStyle(.plain)
                    Button("⌘↓ jump to live") { jumpToLive(proxy) }
                        .buttonStyle(.plain)
                        .keyboardShortcut(.downArrow, modifiers: .command)
                }
            }
        }
    }

    private func list(store: TelemetryStore, proxy: ScrollViewProxy) -> some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(store.visibleEvents) { event in
                    TelemetryRow(
                        event: event,
                        time: store.timeLabel(for: event),
                        timeWidth: store.timeMode == .relative ? 64 : 92,
                        isSelected: event.seq == store.selectedSeq
                    )
                    .contentShape(Rectangle())
                    .onTapGesture { store.select(event.seq) }
                }
            }
            .padding(.bottom, 8)
        }
        .defaultScrollAnchor(.bottom, for: .initialOffset)
        .onScrollGeometryChange(for: StreamScrollMetrics.self) { geometry in
            StreamScrollMetrics(
                offset: geometry.contentOffset.y,
                contentHeight: geometry.contentSize.height,
                containerHeight: geometry.containerSize.height
            )
        } action: { _, new in
            metrics = new
            // Scrolling up pauses auto-follow; scrolling back to the end resumes it.
            if isUserScrolling { store.setFollowLive(new.isAtBottom) }
        }
        .onScrollPhaseChange { _, phase in
            isUserScrolling = phase == .tracking || phase == .interacting || phase == .decelerating
        }
        .onChange(of: store.visibleEvents.last?.seq) {
            if store.followLive { scrollToEnd(proxy) }
        }
        .onChange(of: store.filter) {
            if store.followLive { scrollToEnd(proxy) }
        }
    }

    private func jumpToLive(_ proxy: ScrollViewProxy) {
        app.telemetry.setFollowLive(true)
        scrollToEnd(proxy)
    }

    private func scrollToEnd(_ proxy: ScrollViewProxy) {
        if let last = app.telemetry.visibleEvents.last?.seq {
            proxy.scrollTo(last, anchor: .bottom)
        }
    }
}

struct TelemetryRow: View {
    let event: TelemetryEvent
    let time: String
    let timeWidth: CGFloat
    let isSelected: Bool

    var body: some View {
        let level = event.level
        HStack(alignment: .top, spacing: 14) {
            Rectangle()
                .fill(Self.barColor(level))
                .frame(width: 3)
                .shadow(color: level == .error ? Nocturne.accent : .clear, radius: 5)
            Text(time)
                .foregroundStyle(isSelected ? Nocturne.accent300 : Nocturne.neutral600)
                .frame(width: timeWidth, alignment: .leading)
                .padding(.vertical, 3)
            Text("\(Text(event.source).foregroundStyle(Nocturne.neutral500)) \(event.message)")
                .foregroundStyle(Self.messageColor(level))
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 3)
        }
        .font(.nocturneMono(12))
        .fixedSize(horizontal: false, vertical: true)
        .padding(.horizontal, 20)
        .background(isSelected ? Nocturne.accent.opacity(0.09) : .clear)
        .overlay(alignment: .leading) {
            if isSelected {
                Rectangle().fill(Nocturne.accent).frame(width: 2)
            }
        }
    }

    static func barColor(_ level: TelemetryEvent.Level) -> Color {
        switch level {
        case .debug: Nocturne.accent700
        case .info: Nocturne.neutral800
        case .warn: Nocturne.accent300
        case .error: Nocturne.accent100
        case .heard: Nocturne.accent
        }
    }

    static func messageColor(_ level: TelemetryEvent.Level) -> Color {
        switch level {
        case .debug: Nocturne.neutral400
        case .info: Nocturne.text
        case .warn: Nocturne.accent300
        case .error: Nocturne.accent100
        case .heard: Nocturne.accent200
        }
    }
}

/// Right-edge overview: warn/error marks plus a box for the visible window.
struct TelemetryMinimap: View {
    let events: [TelemetryEvent]
    let visibleRange: ClosedRange<CGFloat>
    let onSelect: (TelemetryEvent) -> Void

    var body: some View {
        GeometryReader { geometry in
            Canvas { context, size in
                let count = CGFloat(max(events.count, 1))
                for (index, event) in events.enumerated() {
                    let level = event.level
                    guard level == .warn || level == .error else { continue }
                    let y = size.height * CGFloat(index) / count
                    let mark = CGRect(x: 2, y: y, width: size.width - 4, height: level == .error ? 3 : 2)
                    context.fill(Path(mark), with: .color(level == .error ? Nocturne.accent100 : Nocturne.accent300))
                }
                let window = CGRect(
                    x: 0.5,
                    y: size.height * visibleRange.lowerBound + 0.5,
                    width: size.width - 1,
                    height: max(6, size.height * (visibleRange.upperBound - visibleRange.lowerBound) - 1)
                )
                context.stroke(Path(roundedRect: window, cornerRadius: 2), with: .color(Nocturne.neutral600), lineWidth: 1)
            }
            .background(Nocturne.neutral900, in: RoundedRectangle(cornerRadius: 2))
            .contentShape(Rectangle())
            .gesture(
                SpatialTapGesture().onEnded { tap in
                    if let event = event(at: tap.location.y / max(geometry.size.height, 1)) {
                        onSelect(event)
                    }
                }
            )
        }
    }

    /// The warn/error mark nearest the tap, falling back to the event at that position.
    private func event(at fraction: CGFloat) -> TelemetryEvent? {
        guard !events.isEmpty else { return nil }
        let index = min(max(Int(fraction * CGFloat(events.count)), 0), events.count - 1)
        let reach = max(1, events.count / 30)
        let nearby = (max(0, index - reach)...min(events.count - 1, index + reach))
            .filter { events[$0].level.rank >= TelemetryEvent.Level.warn.rank }
            .min { abs($0 - index) < abs($1 - index) }
        return events[nearby ?? index]
    }
}
