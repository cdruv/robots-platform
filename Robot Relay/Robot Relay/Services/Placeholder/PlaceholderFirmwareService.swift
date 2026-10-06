import Foundation

/// Echoes the `mpremote` commands a real implementation would run; nothing is executed.
final class PlaceholderFirmwareService: FirmwareService {
    let deviceInfo: FirmwareDeviceInfo? = FirmwareDeviceInfo(
        board: "Pico 2 W",
        runtime: "MicroPython 1.28",
        port: "/dev/tty.usbmodem14201",
        tool: "mpremote 1.25",
        toolEnvironment: "~/.venvs/pico",
        watchdog: "watchdog 2 s · release on idle",
        lastUpload: "2026‑09‑28 21:14"
    )
    let file: FirmwareFile? = FirmwareFile(
        name: "servo_bringup / main.py",
        device: .init(sha: "0fe53", bytes: 3402, date: "2026‑09‑28"),
        local: .init(sha: "34cc7", bytes: 3614, date: "2026‑10‑04")
    )
    let storedOffsets: LegOffsets? = LegOffsets(left: -2.0, right: 1.5)

    private let stream: AsyncStream<ConsoleLine>
    private let continuation: AsyncStream<ConsoleLine>.Continuation

    init() {
        (stream, continuation) = AsyncStream.makeStream(of: ConsoleLine.self)
        let start = Date.now.addingTimeInterval(-8)
        emit("$ mpremote fs ls", .command, at: start)
        emit("ls :\n   3402 main.py", .output, at: start)
        emit("$ mpremote fs cp :main.py main-backup.py", .command, at: start.addingTimeInterval(3))
        emit("cp :main.py main-backup.py", .output, at: start.addingTimeInterval(3))
    }

    func console() -> AsyncStream<ConsoleLine> { stream }

    func upload(_ file: FirmwareFile) -> AsyncStream<Double> {
        let (progress, progressContinuation) = AsyncStream.makeStream(of: Double.self)
        let task = Task {
            emit("$ mpremote fs cp main.py :main.py", .command)
            var line = ConsoleLine(text: "", kind: .progress)
            let steps = 40
            for step in 0...steps {
                if Task.isCancelled { break }
                let fraction = Double(step) / Double(steps)
                let filled = Int((fraction * 20).rounded())
                let bar = String(repeating: "▮", count: filled) + String(repeating: "▯", count: 20 - filled)
                line.text = "cp main.py :main.py  \(bar) \(Int(fraction * 100))%"
                continuation.yield(line)
                progressContinuation.yield(fraction)
                try? await Task.sleep(for: .milliseconds(90))
            }
            progressContinuation.finish()
        }
        progressContinuation.onTermination = { _ in task.cancel() }
        return progress
    }

    func backUpDeviceCopy() async {
        emit("$ mpremote fs cp :main.py main-backup.py", .command)
        emit("cp :main.py main-backup.py", .output)
    }

    func arm(mode: ArmMode, repeatEveryBoot: Bool) async {
        let value = repeatEveryBoot ? "\(mode.rawValue) repeat" : mode.rawValue
        emit("$ mpremote exec \"open('bringup_mode.txt','w').write('\(value)')\"", .command)
    }

    func writeOffsets(_ offsets: LegOffsets) async {
        emit("$ mpremote exec \"open('leg_offsets.txt','w').write('\(offsets.left) \(offsets.right)')\"", .command)
    }

    func centerLegs() async {
        emit("$ mpremote exec \"center()\"", .command)
        emit("L 1500µs  R 1500µs", .output)
    }

    func sweepLegs(degrees: Double) async {
        emit("$ mpremote exec \"sweep(\(Fmt.trimmed(degrees)))\"", .command)
        emit("sweep ±\(Fmt.trimmed(degrees))° done; servos released", .output)
    }

    func openREPL() async {
        emit("$ mpremote repl", .command)
        emit("REPL is not available in this build", .output)
    }

    private func emit(_ text: String, _ kind: ConsoleLine.Kind, at time: Date = .now) {
        continuation.yield(ConsoleLine(time: time, text: text, kind: kind))
    }
}
