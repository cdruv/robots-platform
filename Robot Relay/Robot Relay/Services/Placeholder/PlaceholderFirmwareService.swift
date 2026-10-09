import Foundation

/// Echoes the `mpremote` commands a real implementation would run; nothing is executed.
final class PlaceholderFirmwareService: FirmwareService {
    private var current: FirmwareState
    private let states = AsyncStream.makeStream(of: FirmwareState.self, bufferingPolicy: .bufferingNewest(1))
    private let lines = AsyncStream.makeStream(of: ConsoleLine.self)

    init() {
        var state = FirmwareState(folder: "~/robots/Walky/firmware/pico")
        state.port = "/dev/cu.usbmodem14201"
        state.board = "Raspberry Pi Pico 2 W with RP2350"
        state.runtime = "1.28.0"
        state.tool = "~/.venvs/pico/bin"
        state.hasLocalFolder = true
        state.hasDeviceFiles = true
        state.files = [
            FirmwareFile(name: "body.py", local: .init(sha: "9955804", bytes: 240), device: .init(sha: "9955804", bytes: 240)),
            FirmwareFile(name: "main.py", local: .init(sha: "dcff7a0", bytes: 141), device: .init(sha: "dcff7a0", bytes: 141)),
            FirmwareFile(name: "modes.py", local: .init(sha: "34cc7e1", bytes: 1532), device: .init(sha: "d0f5127", bytes: 1488)),
            FirmwareFile(name: "servo_check.py", local: .init(sha: "c022684", bytes: 2691), device: nil),
        ]
        state.bootMode = .armed("body")
        state.modes = ["body", "center", "sweep", "once:center", "once:sweep"]
        state.storedOffsets = LegOffsets(left: -2.0, right: 1.5)
        current = state
        states.continuation.yield(state)
        emit("$ mpremote connect /dev/cu.usbmodem14201 exec …", .command)
        emit("boot: body", .output)
    }

    func state() -> AsyncStream<FirmwareState> { states.stream }

    func console() -> AsyncStream<ConsoleLine> { lines.stream }

    func refreshLocal() async {}

    func setFolder(_ folder: URL?) async {
        current.folder = folder?.path ?? "~/robots/Walky/firmware/pico"
        current.isDefaultFolder = folder == nil
        publish()
    }

    func upload() async {
        let names = current.changedFiles.map(\.name)
        emit("$ mpremote fs cp \(names.joined(separator: " ")) :", .command)
        current.files = current.files.map { file in
            var file = file
            if file.local != nil { file.device = file.local }
            return file
        }
        publish()
        emit("copied \(names.joined(separator: ", "))", .output)
    }

    func setBootMode(_ mode: String?) async {
        if let mode {
            emit("$ mpremote exec \"with open('mode.txt', 'w') as f: f.write('\(mode)')\"", .command)
        } else {
            emit("$ mpremote exec \"import os; os.remove('mode.txt')\"", .command)
        }
        current.bootMode = mode.map(PicoArm.armed) ?? .idle
        publish()
        emit("ok", .output)
    }

    func writeOffsets(_ offsets: LegOffsets) async {
        emit("$ mpremote exec \"open('leg_offsets.txt','w').write('\(offsets.left) \(offsets.right)')\"", .command)
        current.storedOffsets = offsets
        publish()
    }

    func centerLegs() async {
        emit("$ mpremote exec \"center()\"", .command)
        emit("L 1500µs  R 1500µs", .output)
    }

    func sweepLegs(degrees: Double) async {
        emit("$ mpremote exec \"sweep(\(Fmt.trimmed(degrees)))\"", .command)
        emit("sweep ±\(Fmt.trimmed(degrees))° done; servos released", .output)
    }

    private func publish() {
        states.continuation.yield(current)
    }

    private func emit(_ text: String, _ kind: ConsoleLine.Kind) {
        lines.continuation.yield(ConsoleLine(text: text, kind: kind))
    }
}
