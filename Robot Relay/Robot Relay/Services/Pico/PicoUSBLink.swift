import Foundation

/// The Pico 2 W over USB: found by `PicoUSBMonitor`, read and disarmed with `mpremote exec`,
/// the same commands the firmware README uses. Never runs `mpremote reset`: with the carrier
/// off, a reset would consume a one-shot test.
///
/// The mode is read only on connect and after Disarm. There is no polling, because every
/// mpremote call interrupts the board and takes the serial port.
final class PicoUSBLink {
    var onChange: ((PicoLink) -> Void)?
    private(set) var current = PicoLink()

    private let monitor: PicoUSBMonitor
    private let runner: CommandRunner
    /// Bumped on every plug and unplug, so a slow mpremote result can't land on a different board.
    private var generation = 0
    /// mpremote calls run one at a time; two would fight over the port.
    private var tail: Task<Void, Never>?

    init(runner: CommandRunner, monitor: PicoUSBMonitor = PicoUSBMonitor()) {
        self.runner = runner
        self.monitor = monitor
    }

    func start() {
        monitor.onChange = { [weak self] port in self?.portChanged(port) }
        monitor.start()
        if monitor.port == nil { portChanged(nil) }
    }

    /// Deletes `bringup_mode.txt`, then reads the mode back so the row shows the board's state.
    func disarm() async {
    }

    /// Writes `value` (from `PicoMode.value`) to `bringup_mode.txt`, then reads the mode back.
    func arm(_ value: String) async {
        await execThenRead(PicoMode.armScript(value))
    }

    private func execThenRead(_ script: String) async {
        await enqueue { [weak self] in
            guard let self, let port = current.port else { return }
            let generation = generation
            let result = await runner.run("mpremote", ["connect", port, "exec", script])
            guard result.succeeded, generation == self.generation else { return }
            await readMode()
        }.value
    }

    private func portChanged(_ port: String?) {
        generation += 1
        if port != nil || current.port != nil {
            runner.report?(LinkActivity(text: "$ mpremote connect list", state: .ok(port ?? "Pico unplugged")))
        }
        current.port = port
        current.arm = nil
        current.route = port == nil ? .offline : .usb
        onChange?(current)
        guard port != nil else { return }
        enqueue { [weak self] in await self?.readMode() }
    }

    private func readMode() async {
        guard let port = current.port else { return }
        let generation = generation
        var arm: PicoArm?
        await runner.run("mpremote", ["connect", port, "exec", PicoMode.readScript]) { result in
            guard result.succeeded else { return .failed(result.failureReason) }
            arm = PicoMode.parse(result.stdout)
            return arm.map { .ok($0.label) } ?? .failed("no mode= line in the output")
        }
        guard generation == self.generation, arm != current.arm else { return }
        current.arm = arm
        onChange?(current)
    }

    @discardableResult
    private func enqueue(_ work: @escaping () async -> Void) -> Task<Void, Never> {
        let previous = tail
        let task = Task {
            await previous?.value
            await work()
        }
        tail = task
        return task
    }
}

/// The `bringup_mode.txt` scripts, shared with tests.
nonisolated enum PicoMode {
    static let file = "bringup_mode.txt"

    /// Prints `mode=` followed by the file's contents, or nothing after `=` when there is no file.
    static let readScript =
        "import os; print('mode=' + (open('\(file)').read().strip() if '\(file)' in os.listdir() else ''))"

    /// Deletes the file if it exists.
    static let disarmScript = "import os; '\(file)' in os.listdir() and os.remove('\(file)')"

    /// The `mode=` line of `readScript`'s output: empty is idle; nil when there is no such line.
    static func parse(_ output: String) -> PicoArm? {
        for line in output.split(whereSeparator: \.isNewline) {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            guard trimmed.hasPrefix("mode=") else { continue }
            let mode = trimmed.dropFirst("mode=".count).trimmingCharacters(in: .whitespaces)
            return mode.isEmpty ? .idle : .armed(mode)
        }
        return nil
    }
}
