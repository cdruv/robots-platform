import SwiftUI
import UniformTypeIdentifiers

/// Firmware files, power-on mode and leg offsets on the left; the commands run on the right.
struct FirmwareView: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        let store = app.firmware
        let state = store.state
        VStack(spacing: 0) {
            ViewHeader("Firmware", subtitle: Self.subtitle(state))
            HStack(alignment: .top, spacing: 14) {
                VStack(spacing: 12) {
                    if !state.hasDevice { USBRequiredCard() }
                    FirmwareFilesCard(store: store)
                    PowerOnModeCard(store: store)
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
                Text(state.tool.map { "mpremote · \($0)" } ?? "mpremote not found")
                Spacer()
            }
        }
        .onAppear { store.refreshLocal() }
    }

    private static func subtitle(_ state: FirmwareState) -> String {
        guard let port = state.port else { return "no Pico on USB" }
        let board = [state.board, state.runtime.map { "MicroPython \($0)" }].compactMap(\.self)
        return (board + [port]).joined(separator: " · ")
    }
}

/// Shown instead of guessing: everything in this tab runs over USB.
private struct USBRequiredCard: View {
    var body: some View {
        Card {
            HStack(spacing: 8) {
                StatusDot(tone: .off)
                Text("Pico not connected over USB")
                    .font(.nocturne(14, .medium))
                    .foregroundStyle(Nocturne.accent300)
            }
            Text("""
                Connect the Pico 2 W with a USB data cable. Uploading, the power-on mode and \
                calibration all go over USB with mpremote; this tab updates as soon as it's plugged in.
                """)
                .font(.nocturne(11.5))
                .foregroundStyle(Nocturne.neutral500)
                .lineSpacing(2)
                .fixedSize(horizontal: false, vertical: true)
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

/// The local firmware folder against the board's files; Upload copies the changed ones.
struct FirmwareFilesCard: View {
    let store: FirmwareStore
    @State private var isChoosingFolder = false

    var body: some View {
        let state = store.state
        let changed = state.changedFiles.count
        Card {
            CardHeader(
                title: "Firmware source",
                note: "source folder",
                status: !state.hasDevice ? "needs USB" : !state.hasDeviceFiles ? "—" : changed > 0 ? "\(changed) changed" : "in sync",
                isStatusHighlighted: changed > 0
            )
            HStack(spacing: 8) {
                Text((state.folder as NSString).abbreviatingWithTildeInPath)
                    .font(.nocturneMono(11))
                    .foregroundStyle(state.hasLocalFolder ? Nocturne.neutral500 : Nocturne.accent300)
                    .lineLimit(1)
                    .truncationMode(.head)
                    .help(state.folder)
                Spacer(minLength: 0)
                Button("Change source folder…") { isChoosingFolder = true }
                    .buttonStyle(.nocturneSecondary)
            }
            .disabled(state.isBusy)
            if !state.hasLocalFolder {
                Text("Source folder not found. Select the folder containing main.py and its modules.")
                    .font(.nocturne(11.5))
                    .foregroundStyle(Nocturne.accent300)
            }
            Text("main.py and its modules")
                .font(.nocturne(11.5))
                .foregroundStyle(Nocturne.neutral500)
            Grid(alignment: .leading, horizontalSpacing: 14, verticalSpacing: 2) {
                ForEach(state.files) { file in
                    GridRow {
                        Text(file.name)
                            .foregroundStyle(Nocturne.neutral300)
                        Text(file.local.map { "\(Fmt.grouped(Int64($0.bytes))) B" } ?? "—")
                            .frame(maxWidth: .infinity, alignment: .leading)
                        Text(Self.status(file, isDeviceKnown: state.hasDeviceFiles))
                            .foregroundStyle(state.hasDeviceFiles && file.differs ? Nocturne.accent300 : Nocturne.neutral500)
                    }
                }
            }
            .font(.nocturneMono(11.5))
            .foregroundStyle(Nocturne.neutral500)

            Button(state.isBusy ? "Working…" : "Upload firmware") { store.upload() }
                .buttonStyle(.nocturnePrimary)
                .disabled(!store.canUpload)
            Text("Uploads changed Python files from this source folder. New firmware runs at the next power-on.")
                .font(.nocturne(11.5))
                .foregroundStyle(Nocturne.neutral500)
                .lineSpacing(2)
                .fixedSize(horizontal: false, vertical: true)
        }
        .fileImporter(isPresented: $isChoosingFolder, allowedContentTypes: [.folder]) { result in
            if case .success(let folder) = result { store.chooseFolder(folder) }
        }
        .fileDialogDefaultDirectory(URL(fileURLWithPath: state.folder))
    }

    private static func status(_ file: FirmwareFile, isDeviceKnown: Bool) -> String {
        guard isDeviceKnown else { return file.local == nil ? "—" : "local" }
        switch (file.local, file.device) {
        case (nil, _): return "board only"
        case (_, nil): return "not on board"
        default: return file.differs ? "differs" : "in sync"
        }
    }
}

/// What `mode.txt` asks power-on to run, chosen from the modes the board's firmware lists.
struct PowerOnModeCard: View {
    @Bindable var store: FirmwareStore

    var body: some View {
        let state = store.state
        Card {
            CardHeader(
                title: "Power-on mode",
                note: "mode.txt",
                status: !state.hasDevice ? "needs USB" : state.bootMode?.label ?? "—",
                isStatusHighlighted: state.bootMode?.isArmed == true
            )
            HStack(spacing: 10) {
                if !state.modes.isEmpty {
                    NocturneMenu(
                        selection: Binding(get: { store.selectedBoot }, set: { store.bootSelection = $0 }),
                        options: store.bootOptions
                    )
                }
                Button("Apply") { store.applyBoot() }
                    .buttonStyle(.nocturneSecondary)
                    .disabled(!store.canApplyBoot)
                Spacer(minLength: 0)
            }
            Text(Self.note(state))
                .font(.nocturne(11.5))
                .foregroundStyle(Nocturne.neutral500)
                .lineSpacing(2)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private static func note(_ state: FirmwareState) -> AttributedString {
        if !state.hasDevice {
            return AttributedString("Read from mode.txt on the board once the Pico is on USB.")
        }
        if state.hasDevice, state.bootMode != nil, state.modes.isEmpty {
            var text = AttributedString("The firmware on the board lists no modes. Upload the firmware files first.")
            text.foregroundColor = Nocturne.accent300
            return text
        }
        var text = AttributedString("""
            A mode runs on every power-on until changed, including plugging USB in; once: runs on \
            the next boot only. Nothing moves until then. 
            """)
        var warning = AttributedString("Keep battery off while USB is connected.")
        warning.foregroundColor = Nocturne.accent300
        text.append(warning)
        return text
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
                status: !store.hasDevice ? "needs USB" : store.storedOffsets == nil ? "not on the board yet" : unsaved > 0 ? "\(unsaved) unsaved" : "saved",
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
            .disabled(!store.canCalibrate)
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
            .disabled(!store.canCalibrate)
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
                            ConsoleRow(line: line)
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
                Text(store.hasDevice ? "commands run from this tab" : "no Pico on USB")
                    .font(.nocturneMono(11))
                    .foregroundStyle(Nocturne.neutral600)
                    .lineLimit(1)
                Spacer(minLength: 0)
                Button("Clear") { store.clearConsole() }
                    .buttonStyle(.nocturneGhost)
                    .disabled(store.consoleLines.isEmpty)
            }
            .padding(EdgeInsets(top: 8, leading: 12, bottom: 10, trailing: 12))
        }
    }
}

private struct ConsoleRow: View {
    let line: ConsoleLine

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Rectangle()
                .fill(line.kind == .error ? Nocturne.accent : Nocturne.neutral800)
                .frame(width: 3)
            Text(line.time, format: .dateTime.hour(.twoDigits(amPM: .omitted)).minute(.twoDigits).second(.twoDigits))
                .foregroundStyle(Nocturne.neutral600)
                .frame(width: 72, alignment: .leading)
                .padding(.vertical, 2.5)
            Text(line.text)
                .foregroundStyle(textColor)
                .lineLimit(4)
                .truncationMode(.middle)
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
        case .error: Nocturne.accent300
        }
    }
}
