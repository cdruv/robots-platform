import AppKit
import SwiftUI

struct TelemetryInspector: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        let store = app.telemetry
        Group {
            if let event = store.selectedEvent {
                detail(event, store: store)
            } else {
                VStack(alignment: .leading, spacing: 14) {
                    Kicker("Event")
                    Text("Select an event to inspect its payload.")
                        .font(.nocturne(12))
                        .foregroundStyle(Nocturne.neutral600)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .padding(.horizontal, 18)
        .padding(.vertical, 16)
    }

    private func detail(_ event: TelemetryEvent, store: TelemetryStore) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Kicker("Event · seq \(event.seq)")
            Grid(alignment: .leading, horizontalSpacing: 14, verticalSpacing: 4) {
                field("kind", event.kind)
                field("source", event.source)
                field("wall", store.clockLabel(for: event))
                field("mono", "\(Fmt.grouped(event.tsMonoNs / 1000)) µs")
            }
            .font(.nocturne(12))

            Kicker("Payload")
            ScrollView {
                Text(event.payload.prettyPrinted())
                    .font(.nocturneMono(11.5))
                    .lineSpacing(4)
                    .foregroundStyle(Nocturne.neutral300)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxHeight: 180)
            .fixedSize(horizontal: false, vertical: true)

            Kicker("Around this event")
            VStack(alignment: .leading, spacing: 4) {
                ForEach(store.context(around: event), id: \.event.seq) { entry in
                    let isCurrent = entry.event.seq == event.seq
                    HStack(alignment: .firstTextBaseline, spacing: 10) {
                        Text(isCurrent ? "0.00" : "\(Fmt.signed(entry.offset, decimals: 2)) s")
                            .frame(width: 56, alignment: .trailing)
                        Text(entry.event.level.label)
                            .frame(width: 38, alignment: .leading)
                        Text(entry.event.message)
                            .truncationMode(.tail)
                    }
                    .lineLimit(1)
                    .foregroundStyle(isCurrent ? Nocturne.neutral300 : Nocturne.neutral500)
                }
            }
            .font(.nocturneMono(11))

            Spacer(minLength: 0)
            HStack(spacing: 8) {
                Button("Copy JSON") {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(store.json(for: event), forType: .string)
                }
                .buttonStyle(.nocturneSecondary)
                Button("Open in Firmware") { app.selection = .firmware }
                    .buttonStyle(.nocturneGhost)
            }
        }
    }

    private func field(_ label: String, _ value: String) -> some View {
        GridRow {
            Text(label)
                .foregroundStyle(Nocturne.neutral600)
            Text(value)
                .font(.nocturneMono(12))
                .textSelection(.enabled)
        }
    }
}
