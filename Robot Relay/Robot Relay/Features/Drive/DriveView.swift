import SwiftUI

/// The legs through the phone's link to the Pico: drive them directly, or trim them.
struct DriveView: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        let store = app.body
        VStack(spacing: 0) {
            ViewHeader("Drive", subtitle: Self.subtitle(store.link))
            HStack(alignment: .top, spacing: 14) {
                VStack(spacing: 12) {
                    BodyLinkCard(link: store.link)
                    LegsCard(store: store)
                }
                .frame(maxWidth: .infinity)
                TrimCard(store: store)
                    .frame(maxWidth: .infinity)
            }
            .padding(EdgeInsets(top: 6, leading: 20, bottom: 10, trailing: 20))
            .frame(maxHeight: .infinity, alignment: .top)
            .dimmedBehindPopover()
            ViewFooter(hasTopRule: true) {
                Text(Self.footer(store.mode))
                Spacer()
                Text("legs go limp 0.5 s after targets stop · esc stops")
            }
        }
        .modifier(DriveKeys(store: store))
    }

    private static func subtitle(_ link: BodyLink?) -> String {
        guard let link else { return "phone not connected" }
        guard let pico = link.pico else { return "phone connected · \(BodyLinkCard.phaseLabel(link.phase))" }
        return (["Pico \(pico.board)", "legs \(pico.mode.rawValue)"] + (link.rttMs.map { ["\($0) ms"] } ?? [])).joined(separator: " · ")
    }

    private static func footer(_ mode: BodyStore.Mode) -> String {
        switch mode {
        case .off: "legs off"
        case .drive: "driving · targets 50 Hz"
        case .calibrate: "calibrating · legs held at horizontal"
        }
    }
}

private struct BodyLinkCard: View {
    let link: BodyLink?

    var body: some View {
        Card {
            HStack(spacing: 8) {
                StatusDot(tone: link?.pico != nil ? .on : link != nil ? .idle : .off)
                Text("Body link")
                    .font(.nocturne(14, .medium))
                    .foregroundStyle(Nocturne.text)
                Spacer(minLength: 0)
                Text(link.map { $0.pico != nil ? $0.phase : Self.phaseLabel($0.phase) } ?? "no phone")
                    .font(.nocturneMono(11))
                    .foregroundStyle(link?.pico != nil ? Nocturne.accent300 : Nocturne.neutral600)
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

private struct LegsCard: View {
    let store: BodyStore

    var body: some View {
        @Bindable var store = store
        let driving = store.mode == .drive
        let reachable = store.canEngage
        Card {
            CardHeader(title: "Legs", note: "° from horizontal · ±90",
                       status: driving ? "driving" : reachable ? "off" : "needs the body link", isStatusHighlighted: driving)
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
            Text(store.movesTogether
                 ? "Keys: ← → or A D move both legs. Tap for 1°, hold to keep moving."
                 : "Keys: A D move the left leg, ← → the right. Tap for 1°, hold to keep moving.")
                .foregroundStyle(driving ? Nocturne.neutral300 : Nocturne.neutral600)
            Text(reachable
                 ? "Engaging swings both legs to horizontal at full servo speed. Keep the legs clear."
                 : "Engage needs the phone’s link to the Pico; see Body link.")
                .lineSpacing(2)
                .fixedSize(horizontal: false, vertical: true)
        }
        .font(.nocturne(11.5))
        .foregroundStyle(Nocturne.neutral500)
    }
}

/// Keyboard driving while this tab is on screen: A/D for the left leg under the left hand,
/// arrows for the right leg under the right. Physical key codes, so the layout doesn't matter.
/// Keys are taken only while driving, so arrows elsewhere still work.
private struct DriveKeys: ViewModifier {
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

/// Always sends limp, even when Relay believes the legs are off. Disabled only while the phone
/// has no link to the Pico: then nothing can carry it, and the Pico's own timeout has the legs limp.
private struct StopButton: View {
    let store: BodyStore

    var body: some View {
        Button("STOP") { store.stop() }
            .buttonStyle(NocturneButtonStyle(kind: .primary, fontSize: 13))
            .keyboardShortcut(.cancelAction)
            .disabled(store.pico == nil)
    }
}

private struct TrimCard: View {
    let store: BodyStore

    var body: some View {
        let calibrating = store.mode == .calibrate
        let pico = store.pico
        Card {
            CardHeader(title: "Leg calibration", note: "° · ±9", status: Self.status(store), isStatusHighlighted: calibrating)
            if let pico {
                Text("Stored on the Pico: \(BodyLinkCard.trim(pico.saved))" + (pico.stored ? "" : " · not calibrated"))
                    .font(.nocturneMono(11))
            }
            if calibrating {
                Grid(alignment: .leading, horizontalSpacing: 10, verticalSpacing: 6) {
                    ForEach(LegSide.allCases, id: \.self) { side in
                        GridRow {
                            Text(side.label + " · " + side.pin)
                            OffsetStepper(
                                value: Binding(get: { store.trim[side] }, set: { store.setTrim(side, $0) }),
                                step: LegOffsets.step, range: LegOffsets.range
                            )
                            Text(pico?.pulses?[safe: side == .left ? 0 : 1].map { "\($0) µs" } ?? "—")
                                .font(.nocturneMono(11.5))
                            Button("Wiggle") { store.wiggle(side) }
                                .buttonStyle(.nocturneGhost)
                        }
                    }
                }
                Text(Self.progress(store))
                    .foregroundStyle(Self.isFailure(store.save) ? Nocturne.Log.error : Nocturne.neutral300)
                    .fixedSize(horizontal: false, vertical: true)
            }
            HStack(spacing: 10) {
                if calibrating {
                    Button("Save") { store.saveTrim() }
                        .buttonStyle(.nocturnePrimary)
                        .disabled(store.save == .saving || store.trim.steps == pico?.saved && pico?.stored == true)
                    Button("Done") { store.stop() }
                        .buttonStyle(.nocturneSecondary)
                } else {
                    Button("Calibrate") { store.engage(.calibrate) }
                        .buttonStyle(.nocturnePrimary)
                        .disabled(!store.canEngage)
                }
            }
            Text("""
                Lay Walky face down with the legs clear. Calibrating holds both legs at horizontal. \
                Look from the side, along the servo axis, and trim until the near leg hides the far one. \
                Wiggle steps one leg toward + and back: if Left moves the robot’s own right leg, power \
                off and swap the servo plugs between sockets 0 and 1.
                """)
                .lineSpacing(2)
                .fixedSize(horizontal: false, vertical: true)
        }
        .font(.nocturne(11.5))
        .foregroundStyle(Nocturne.neutral500)
    }

    private static func status(_ store: BodyStore) -> String {
        guard store.mode == .calibrate else { return store.pico == nil ? "needs the body link" : "off" }
        return switch store.save {
        case .idle: "adjusting"
        case .saving: "saving"
        case .saved: "saved"
        case .failed: "save failed"
        }
    }

    private static func progress(_ store: BodyStore) -> String {
        switch store.save {
        case .saving: "Saving on the Pico…"
        case .saved: "Saved. The Pico uses this trim from now on, including after power-off."
        case .failed(let reason): reason
        case .idle:
            store.pico?.trim == store.trim.steps
                ? "The legs show this trim. Save stores it on the Pico; Done without saving drops it."
                : "Waiting for the Pico to apply the trim…"
        }
    }

    private static func isFailure(_ save: BodyStore.SaveState) -> Bool {
        if case .failed = save { true } else { false }
    }
}

private extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}

#Preview {
    let app = AppModel(services: .placeholder())
    let _ = app.start()
    DriveView()
        .environment(app)
        .frame(width: 1100, height: 640)
        .foregroundStyle(Nocturne.text)
        .background(Nocturne.bg)
        .tint(Nocturne.accent)
        .preferredColorScheme(.dark)
}

#Preview("Pico not reached") {
    let app = AppModel(services: .placeholder())
    let _ = app.body.apply(BodyLink(phase: "searching", pico: nil, rttMs: nil))
    DriveView()
        .environment(app)
        .frame(width: 1100, height: 640)
        .foregroundStyle(Nocturne.text)
        .background(Nocturne.bg)
        .tint(Nocturne.accent)
        .preferredColorScheme(.dark)
}
