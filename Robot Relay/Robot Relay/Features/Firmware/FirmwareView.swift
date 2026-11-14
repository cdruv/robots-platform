import SwiftUI
import UniformTypeIdentifiers
import AppKit
import os

/// Firmware files, power-on mode and the stored leg trim on the left; the commands run on the right.
struct FirmwareView: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        let store = app.firmware
        let state = store.state
        VStack(spacing: 0) {
            ViewHeader("Firmware", subtitle: Self.subtitle(state))
            HStack(alignment: .top, spacing: 14) {
                ScrollView {
                  VStack(spacing: 12) {
                    if !state.hasDevice { USBRequiredCard() }
                    FirmwareFilesCard(store: store)
                    PowerOnModeCard(store: store)
                    LegOffsetsCard(store: store)
                  }
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
                Connect the Pico 2 W with a USB data cable to upload firmware or change the power-on \
                mode. Plugging it in stops its link to the phone until the next power-on.
                """)
                .font(.nocturne(11.5))
                .foregroundStyle(Nocturne.neutral500)
                .lineSpacing(2)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

struct CardHeader: View {
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

/// Compares the local firmware folder with the files on the board.
struct FirmwareFilesCard: View {
    let store: FirmwareStore
    @State private var isChoosingFolder = false

    var body: some View {
        let state = store.state
        let changed = state.changedFiles.count
        let status: String
        if !state.hasDevice {
            status = "needs USB"
        } else if !state.hasLocalFolder {
            status = "source unavailable"
        } else if !state.hasDeviceFiles {
            status = "board files unknown"
        } else if state.filesToUpload.isEmpty {
            status = "no source files"
        } else {
            status = changed > 0 ? "out of date (\(changed) changed)" : "up to date"
        }
        return Card {
            CardHeader(
                title: "Firmware source",
                note: "source folder",
                status: status,
                isStatusHighlighted: changed > 0
            )
            HStack(spacing: 8) {
                Text((state.folder as NSString).abbreviatingWithTildeInPath)
                    .font(.nocturneMono(11))
                    .foregroundStyle(state.hasLocalFolder ? Nocturne.neutral500 : Nocturne.accent300)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .help(state.folder)
                Button("Change source folder…") { isChoosingFolder = true }
                    .buttonStyle(.nocturneSecondary)
                    .fixedSize()
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
            Text("Uploads changed Python files. If firmware is up to date, uploads all source files again. Firmware runs at the next power-on.")
                .font(.nocturne(11.5))
                .foregroundStyle(Nocturne.neutral500)
                .lineSpacing(2)
                .fixedSize(horizontal: false, vertical: true)
        }
        .fileImporter(isPresented: $isChoosingFolder, allowedContentTypes: [.folder]) { result in
            switch result {
            case .success(let folder): store.chooseFolder(folder)
            case .failure(let error): Logger.firmware.error("folder picker failed: \(error.localizedDescription, privacy: .public)")
            }
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
                        selection: $store.selectedBoot,
                        options: store.bootOptions
                    )
                    Toggle("Run once", isOn: $store.runOnce)
                        .toggleStyle(.checkbox)
                        .disabled(!store.canChooseFrequency)
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
            Select Run once for the next power-on only. Otherwise the mode runs on every \
            power-on, including plugging USB in.
            """)
        var warning = AttributedString(" Keep battery off while USB is connected.")
        warning.foregroundColor = Nocturne.accent300
        text.append(warning)
        return text
    }
}

/// The trim stored on the board, as read over USB. It is changed in the Drive tab.
struct LegOffsetsCard: View {
    let store: FirmwareStore

    var body: some View {
        let snapshot = store.state.calibration
        Card {
            CardHeader(
                title: "Leg calibration", note: "° · ±9",
                status: !store.hasDevice ? "needs USB" : snapshot?.stored == true ? "calibrated" : "not calibrated"
            )
            if let error = snapshot?.error {
                Text("Stored calibration ignored: " + error).foregroundStyle(Nocturne.Log.error)
            }
            if store.hasDevice, let stored = snapshot?.offsets {
                Text("L \(Fmt.signed(stored.left))° · R \(Fmt.signed(stored.right))°")
                    .font(.nocturneMono(11.5))
                    .foregroundStyle(Nocturne.neutral200)
            } else if store.hasDevice {
                Text("Upload firmware to read the stored trim.")
            }
            Text("Read from the board over USB. Calibrate in the Drive tab, with the robot on battery.")
                .fixedSize(horizontal: false, vertical: true)
        }
        .font(.nocturne(11.5))
        .foregroundStyle(Nocturne.neutral500)
    }
}

struct FirmwareConsole: View {
    let store: FirmwareStore

    var body: some View {
        Tile(caption: "Console", value: "USB") {
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
                .fill(line.kind == .error ? Nocturne.Log.error : Nocturne.neutral800)
                .frame(width: 3)
            Text(line.time, format: .dateTime.hour(.twoDigits(amPM: .omitted)).minute(.twoDigits).second(.twoDigits))
                .foregroundStyle(Nocturne.Log.timestamp)
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
        case .output: Nocturne.Log.output
        case .error: Nocturne.Log.error
        }
    }
}
