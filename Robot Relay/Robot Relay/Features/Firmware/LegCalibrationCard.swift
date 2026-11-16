import SwiftUI

/// Trims the legs through the body link, and shows the stored trim from whichever side can
/// see it: the body link while the robot runs on battery, or the board's files over USB.
/// The two rarely meet, since plugging the Pico into the Mac stops its link to the phone.
struct LegCalibrationCard: View {
    let legs: BodyStore
    let firmware: FirmwareStore

    var body: some View {
        let store = legs
        let calibrating = store.mode == .calibrate
        let pico = store.pico
        Card {
            CardHeader(title: "Leg calibration", note: "° · ±9", status: status, isStatusHighlighted: calibrating)
            stored
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
                Spacer(minLength: 0)
                StopButton(store: store)
            }
            Text(store.canEngage ? Self.instructions : Self.needsLink)
                .lineSpacing(2)
                .fixedSize(horizontal: false, vertical: true)
        }
        .font(.nocturne(11.5))
        .foregroundStyle(Nocturne.neutral500)
    }

    @ViewBuilder
    private var stored: some View {
        if let pico = legs.pico {
            Text("Stored on the Pico: \(BodyLinkCard.trim(pico.saved))" + (pico.stored ? "" : " · not calibrated"))
                .font(.nocturneMono(11))
        } else if firmware.hasDevice {
            let snapshot = firmware.state.calibration
            if let error = snapshot?.error {
                Text("Stored calibration ignored: " + error).foregroundStyle(Nocturne.Log.error)
            }
            if let offsets = snapshot?.offsets {
                Text("Stored on the board (USB): L \(Fmt.signed(offsets.left))° · R \(Fmt.signed(offsets.right))°"
                     + (snapshot?.stored == true ? "" : " · not calibrated"))
                    .font(.nocturneMono(11))
            } else {
                Text("Upload firmware to read the stored trim.")
            }
        }
    }

    private var status: String {
        let store = legs
        if store.mode == .calibrate {
            return switch store.save {
            case .idle: "adjusting"
            case .saving: "saving"
            case .saved: "saved"
            case .failed: "save failed"
            }
        }
        if store.mode == .drive { return "legs driving" }
        if let pico = store.pico { return pico.stored ? "calibrated" : "not calibrated" }
        if firmware.hasDevice, let snapshot = firmware.state.calibration {
            return snapshot.stored == true ? "calibrated" : "not calibrated"
        }
        return "needs the body link"
    }

    private static let instructions = """
        Lay Walky face down with the legs clear. Calibrating holds both legs at horizontal. \
        Look from the side, along the servo axis, and trim until the near leg hides the far one. \
        Wiggle steps one leg toward + and back: if Left moves the robot’s own right leg, power \
        off and swap the servo plugs between sockets 0 and 1.
        """

    private static let needsLink = """
        Calibrating goes through the phone’s link to the Pico: run the robot on battery with the \
        Pico’s USB unplugged. See Body link in Live Robot.
        """

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

