import SwiftUI

/// The phone's link to the Pico, which carries everything sent to the legs.
struct BodyLinkCard: View {
    let link: BodyLink?

    var body: some View {
        Card {
            HStack(spacing: 8) {
                StatusDot(tone: link?.pico != nil ? .on : link != nil ? .idle : .off)
                Text("Body link")
                    .font(.nocturne(14, .medium))
                    .foregroundStyle(Nocturne.text)
                Spacer(minLength: 0)
                Text(Self.status(link))
                    .font(.nocturneMono(11))
                    .foregroundStyle(link?.pico != nil ? Nocturne.accent300 : Nocturne.neutral600)
                    .lineLimit(1)
            }
            if let pico = link?.pico {
                Grid(alignment: .leading, horizontalSpacing: 18, verticalSpacing: 3) {
                    GridRow {
                        LabeledValue(label: "legs", value: pico.mode.rawValue)
                        LabeledValue(label: "pulses", value: pico.pulses.map { "L \($0[safe: 0] ?? 0) · R \($0[safe: 1] ?? 0)" } ?? "off", unit: pico.pulses == nil ? nil : "µs")
                    }
                    GridRow {
                        LabeledValue(label: "target", value: "L \(Self.degrees(pico.target[safe: 0])) · R \(Self.degrees(pico.target[safe: 1]))")
                        LabeledValue(label: "trim", value: Self.trim(pico.trim) + (pico.stored ? "" : " · not calibrated"))
                    }
                }
                .font(.nocturneMono(11.5))
                if let error = pico.error {
                    Text("Pico: " + error.msg)
                        .foregroundStyle(Nocturne.Log.error)
                        .fixedSize(horizontal: false, vertical: true)
                }
            } else {
                Text(Self.hint(link?.phase))
                    .lineSpacing(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .font(.nocturne(11.5))
        .foregroundStyle(Nocturne.neutral500)
    }

    private static func status(_ link: BodyLink?) -> String {
        guard let link else { return "no phone" }
        guard link.pico != nil else { return phaseLabel(link.phase) }
        return ([link.phase] + (link.rttMs.map { ["\($0) ms"] } ?? [])).joined(separator: " · ")
    }

    /// What the phone is waiting for, while it has no link to the Pico.
    static func phaseLabel(_ phase: String) -> String {
        switch phase {
        case "unpaired": "Pico Wi‑Fi unknown"
        case "declined": "Wi‑Fi not approved"
        case "searching": "Pico not found"
        default: "connecting to the Pico"
        }
    }

    static func degrees(_ value: Double?) -> String {
        value.map { $0 == 0 ? "0°" : Fmt.signed($0, decimals: 0) + "°" } ?? "—"
    }

    static func trim(_ steps: [Int]) -> String {
        guard let offsets = LegOffsets(steps: steps) else { return "—" }
        return "L \(Fmt.signed(offsets.left))° · R \(Fmt.signed(offsets.right))°"
    }

    private static func hint(_ phase: String?) -> String {
        switch phase {
        case nil:
            "Open Walky on the phone and connect it over USB, or over Wi‑Fi once it has been paired over USB. Relay reaches the Pico through the phone."
        case "unpaired":
            "The phone does not know the Pico’s Wi-Fi yet. Plug the Pico into this Mac once so Relay can read it; the phone gets it the next time it connects over USB."
        case "declined":
            "The phone’s request to join the Pico’s Wi-Fi was not approved. Reconnect the phone here over USB to ask again, then approve it on the phone."
        case "searching":
            "The phone can’t find the Pico’s Wi-Fi: the Pico is off or out of range. Power the robot from its battery with the Pico’s USB unplugged; plugging the Pico into the Mac stops its link until the next power-on."
        default:
            "The phone has joined the Pico’s Wi-Fi and is connecting."
        }
    }
}

struct LegsCard: View {
    let store: BodyStore

    var body: some View {
        @Bindable var store = store
        let driving = store.mode == .drive
        let reachable = store.canEngage
        Card {
            CardHeader(title: "Legs", note: "° · ±90", status: Self.status(store), isStatusHighlighted: driving)
            ForEach(LegSide.allCases, id: \.self) { side in
                HStack(spacing: 10) {
                    Text(side.label + " · " + side.pin)
                        .frame(width: 86, alignment: .leading)
                    Slider(
                        value: Binding(get: { store.targets[side] }, set: { store.setTarget(side, $0.rounded()) }),
                        in: BodyStore.range
                    )
                    Text(reachable ? BodyLinkCard.degrees(store.targets[side]) : "—")
                        .font(.nocturneMono(11.5))
                        .foregroundStyle(Nocturne.neutral200)
                        .frame(width: 44, alignment: .trailing)
                }
            }
            .disabled(!driving)
            .opacity(driving ? 1 : 0.45)
            HStack(spacing: 10) {
                if driving {
                    Button("Centre") { store.centre() }
                        .buttonStyle(.nocturneSecondary)
                } else {
                    Button("Engage") { store.engage(.drive) }
                        .buttonStyle(.nocturnePrimary)
                        .disabled(!store.canEngage)
                }
                Toggle("Move together", isOn: $store.movesTogether)
                    .toggleStyle(.checkbox)
                    .disabled(!reachable)
                Spacer(minLength: 0)
                StopButton(store: store)
            }
            Text(Self.hint(store))
                .foregroundStyle(driving ? Nocturne.neutral300 : Nocturne.neutral500)
                .lineSpacing(2)
                .fixedSize(horizontal: false, vertical: true)
        }
        .font(.nocturne(11.5))
        .foregroundStyle(Nocturne.neutral500)
    }

    private static func status(_ store: BodyStore) -> String {
        switch store.mode {
        case .drive: "driving"
        case .calibrate: "calibrating in Firmware"
        case .off: store.canEngage ? "off" : "needs the body link"
        }
    }

    private static func hint(_ store: BodyStore) -> String {
        guard store.canEngage else { return "Engage needs the phone’s link to the Pico; see Body link." }
        guard store.mode == .drive else {
            return "Engaging swings both legs to horizontal at full servo speed. Keep the legs clear."
        }
        return store.movesTogether
            ? "Keys: ← → or A D move both legs. Tap for 1°, hold to keep moving."
            : "Keys: A D move the left leg, ← → the right. Tap for 1°, hold to keep moving."
    }
}

/// Keyboard driving while the view it is attached to is on screen: A/D for the left leg under
/// the left hand, arrows for the right leg under the right. Physical key codes, so the layout
/// doesn't matter. Keys are taken only while driving, so arrows elsewhere still work.
struct DriveKeys: ViewModifier {
    let store: BodyStore
    @State private var monitor: Any?

    private static let nudges: [UInt16: BodyStore.Nudge] = [
        0: .init(side: .left, sign: -1),     // A
        2: .init(side: .left, sign: 1),      // D
        123: .init(side: .right, sign: -1),  // ←
        124: .init(side: .right, sign: 1),   // →
    ]

    func body(content: Content) -> some View {
        content
            .onAppear {
                monitor = NSEvent.addLocalMonitorForEvents(matching: [.keyDown, .keyUp]) { [store] event in
                    guard let nudge = Self.nudges[event.keyCode] else { return event }
                    if event.type == .keyUp {
                        // Always let a key go, even after driving stopped.
                        store.release(nudge)
                        return store.mode == .drive ? nil : event
                    }
                    guard store.mode == .drive,
                          event.modifierFlags.isDisjoint(with: [.command, .control, .option]) else { return event }
                    store.press(nudge)
                    return nil
                }
            }
            .onDisappear {
                if let monitor { NSEvent.removeMonitor(monitor) }
                monitor = nil
                store.releaseKeys()
            }
            // Key-ups don't arrive while another app is active.
            .onReceive(NotificationCenter.default.publisher(for: NSApplication.didResignActiveNotification)) { _ in
                store.releaseKeys()
            }
    }
}

struct StopButton: View {
    let store: BodyStore

    var body: some View {
        Button("STOP") { store.stop() }
            .buttonStyle(NocturneButtonStyle(kind: .primary, fontSize: 13))
            .keyboardShortcut(.cancelAction)
            .disabled(!store.canStop)
    }
}

extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}
