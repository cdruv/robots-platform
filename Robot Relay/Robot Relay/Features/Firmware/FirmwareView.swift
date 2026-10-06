import SwiftUI

/// Upload, arm next boot and leg offsets on the left; `mpremote` console on the right.
struct FirmwareView: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        let store = app.firmware
        let info = store.deviceInfo
        VStack(spacing: 0) {
            ViewHeader("Firmware", subtitle: info.map { "\($0.board) · \($0.runtime) · \($0.port)" } ?? "no device data")
            HStack(alignment: .top, spacing: 14) {
                VStack(spacing: 12) {
                    FirmwareUploadCard(store: store)
                    ArmNextBootCard(store: store)
                    LegOffsetsCard(store: store)
                    Spacer(minLength: 0)
                }
                .frame(maxWidth: .infinity)
                FirmwareConsole(store: store)
                    .frame(maxWidth: .infinity)
            }
            .padding(EdgeInsets(top: 6, leading: 20, bottom: 10, trailing: 20))
            .dimmedBehindPopover()
            ViewFooter(hasTopRule: true) {
                Text(info.map { "\($0.tool) · \($0.toolEnvironment)" } ?? "mpremote —")
                Text(info?.watchdog ?? "watchdog —")
                Spacer()
                Text("last upload \(info?.lastUpload ?? "—")")
                    .foregroundStyle(Nocturne.neutral500)
            }
        }
    }
}

private struct CardHeader: View {
    let title: String
    var note: String?
    let status: String
    var isStatusHighlighted = false

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(title)
                .font(.nocturne(14, .medium))
            if let note {
                Text(note)
                    .font(.nocturneMono(11))
                    .foregroundStyle(Nocturne.neutral600)
            }
            Spacer(minLength: 0)
            Text(status)
                .font(.nocturneMono(11))
                .foregroundStyle(isStatusHighlighted ? Nocturne.accent300 : Nocturne.neutral600)
        }
        .lineLimit(1)
    }
}

struct FirmwareUploadCard: View {
    let store: FirmwareStore

    var body: some View {
        let file = store.file
        let differs = file?.differs ?? false
        Card {
            CardHeader(
                title: file?.name ?? "Firmware file",
                status: file == nil ? "—" : differs ? "differs" : "in sync",
                isStatusHighlighted: differs
            )
            Grid(alignment: .leading, horizontalSpacing: 14, verticalSpacing: 2) {
                versionRow("device", file?.device, isKnown: file != nil)
                versionRow("local", file?.local, isKnown: file != nil)
            }
            .font(.nocturneMono(11.5))
            .foregroundStyle(Nocturne.neutral500)

            GeometryReader { geometry in
                Capsule()
                    .fill(Nocturne.accent)
                    .shadow(color: Nocturne.accent, radius: 4)
                    .frame(width: geometry.size.width * (store.uploadProgress ?? 0))
            }
            .frame(height: 3)
            .background(Nocturne.neutral900, in: Capsule())

            HStack(spacing: 8) {
                Button(store.isUploading ? "Uploading…" : "Upload") { store.upload() }
                    .buttonStyle(.nocturnePrimary)
                    .disabled(store.isUploading || !differs || !store.hasDevice)
                Button("Back up device copy") { store.backUpDeviceCopy() }
                    .buttonStyle(.nocturneGhost)
                    .disabled(!store.hasDevice)
                Spacer(minLength: 0)
                if let progress = store.uploadProgress {
                    Text("\(Int(progress * 100))%")
                        .font(.nocturneMono(11))
                        .foregroundStyle(Nocturne.neutral500)
                }
            }
        }
    }

    /// An unknown file shows dashes; a known file without a device copy shows "missing".
    private func versionRow(_ label: String, _ version: FirmwareFile.Version?, isKnown: Bool) -> some View {
        GridRow {
            Text(label)
            Text(version.map { "sha \($0.sha) · \(Fmt.grouped(Int64($0.bytes))) B" } ?? (isKnown ? "missing" : "—"))
                .foregroundStyle(Nocturne.neutral300)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text(version?.date ?? "—")
        }
    }
}

struct ArmNextBootCard: View {
    @Bindable var store: FirmwareStore

    var body: some View {
        Card {
            CardHeader(
                title: "Arm next boot",
                note: "bringup_mode.txt",
                status: store.isArmed.map { $0 ? "armed · \(store.armMode.rawValue)" : "not armed" } ?? "—",
                isStatusHighlighted: store.isArmed == true
            )
            HStack(spacing: 10) {
                NocturneSegmented(
                    selection: $store.armMode,
                    options: ArmMode.allCases.map { ($0, $0.rawValue) },
                    horizontalPadding: 10,
                    verticalPadding: 3
                )
                Toggle("repeat every power‑on", isOn: $store.repeatEveryBoot)
                    .toggleStyle(NocturneCheckboxStyle())
                    .font(.nocturne(12))
                Spacer(minLength: 0)
                Button("Arm") { store.arm() }
                    .buttonStyle(.nocturneSecondary)
                    .disabled(!store.hasDevice)
            }
            Text("Unplug USB, then switch battery on. 5 s countdown, finite action, release. \(Text("Keep battery off while USB is connected.").foregroundStyle(Nocturne.accent300))")
                .font(.nocturne(11.5))
                .foregroundStyle(Nocturne.neutral500)
                .lineSpacing(2)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

struct LegOffsetsCard: View {
    @Bindable var store: FirmwareStore

    var body: some View {
        let unsaved = store.unsavedOffsetCount
        Card {
            CardHeader(
                title: "Leg offsets",
                note: "° from neutral 90 · ±\(Int(LegOffsets.range.upperBound))",
                status: store.storedOffsets == nil ? "—" : unsaved > 0 ? "\(unsaved) unsaved" : "saved",
                isStatusHighlighted: unsaved > 0
            )
            Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 6) {
                ForEach(LegSide.allCases, id: \.self) { side in
                    GridRow {
                        HStack(alignment: .firstTextBaseline, spacing: 6) {
                            Text(side.label)
                                .font(.nocturne(12))
                            Text(side.pin)
                                .font(.nocturneMono(10.5))
                                .foregroundStyle(Nocturne.neutral600)
                        }
                        OffsetStepper(value: $store.offsets[side], step: LegOffsets.step, range: LegOffsets.range)
                        pulseLabel(side)
                            .font(.nocturneMono(11))
                            .foregroundStyle(Nocturne.neutral500)
                            .lineLimit(1)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
            .disabled(!store.hasDevice)
            HStack(spacing: 8) {
                Button("Center") { store.centerLegs() }
                    .buttonStyle(.nocturneSecondary)
                Button("Sweep ±10°") { store.sweepLegs() }
                    .buttonStyle(.nocturneSecondary)
                Spacer(minLength: 0)
                Button("Write offsets") { store.writeOffsets() }
                    .buttonStyle(.nocturnePrimary)
                    .disabled(unsaved == 0)
            }
            .disabled(!store.hasDevice)
        }
    }

    private func pulseLabel(_ side: LegSide) -> Text {
        let pulse = "\(ServoMath.pulseMicros(offsetDegrees: store.offsets[side])) µs · "
        guard let stored = store.storedOffsets else { return Text(pulse + "stored —") }
        if stored[side] != store.offsets[side] {
            let label = "stored \(Fmt.signed(stored[side]))"
            return Text("\(pulse)\(Text(label).foregroundStyle(Nocturne.accent300))")
        }
        return Text(pulse + "saved")
    }
}

struct FirmwareConsole: View {
    let store: FirmwareStore

    var body: some View {
        Tile(caption: "Console", value: "mpremote") {
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        if store.consoleLines.isEmpty {
                            Text("No output")
                                .font(.nocturneMono(11.5))
                                .foregroundStyle(Nocturne.neutral600)
                                .padding(.vertical, 2.5)
                        }
                        ForEach(store.consoleLines) { line in
                            ConsoleRow(line: line, isRunning: line.kind == .progress && store.isUploading)
                                .id(line.id)
                        }
                    }
                    .padding(EdgeInsets(top: 8, leading: 12, bottom: 0, trailing: 12))
                }
                .defaultScrollAnchor(.bottom, for: .initialOffset)
                .onChange(of: store.consoleLines.last?.id) {
                    if let last = store.consoleLines.last?.id {
                        proxy.scrollTo(last, anchor: .bottom)
                    }
                }
            }
            HStack(spacing: 8) {
                Text(store.hasDevice ? "next: mpremote reset · verify idle boot" : "no device")
                    .font(.nocturneMono(11))
                    .foregroundStyle(Nocturne.neutral600)
                    .lineLimit(1)
                Spacer(minLength: 0)
                Button("Open REPL") { store.openREPL() }
                    .buttonStyle(.nocturneGhost)
                    .disabled(!store.hasDevice)
            }
            .padding(EdgeInsets(top: 8, leading: 12, bottom: 10, trailing: 12))
        }
    }
}

private struct ConsoleRow: View {
    let line: ConsoleLine
    let isRunning: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Rectangle()
                .fill(isRunning ? Nocturne.accent : Nocturne.neutral800)
                .frame(width: 3)
            Text(line.time, format: .dateTime.hour(.twoDigits(amPM: .omitted)).minute(.twoDigits).second(.twoDigits))
                .foregroundStyle(Nocturne.neutral600)
                .frame(width: 72, alignment: .leading)
                .padding(.vertical, 2.5)
            Text(line.text)
                .foregroundStyle(textColor)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 2.5)
        }
        .font(.nocturneMono(11.5))
        .fixedSize(horizontal: false, vertical: true)
    }

    private var textColor: Color {
        switch line.kind {
        case .command: Nocturne.neutral200
        case .output: Nocturne.neutral600
        case .progress: Nocturne.accent200
        }
    }
}
