import Foundation

/// The Pico 2 W over USB: found by `PicoUSBMonitor` and read with `mpremote exec`, the same
/// commands the firmware README uses. Never runs `mpremote reset`: with the carrier off, a
/// reset would consume a once: request.
///
/// Plug and unplug come from IOKit as they happen, so nothing polls. The board itself is read
/// on connect, on Refresh and after a firmware change, because every mpremote call interrupts
/// it and takes the serial port. A failed read (port busy) is retried 3 times, 3 s apart.
/// All mpremote calls, including the Firmware tab's, go through one queue so they never
/// fight over the port.
final class PicoUSBLink {
    private(set) var current = PicoLink() {
        didSet { if current != oldValue { observers.forEach { $0(current) } } }
    }

    private let monitor: PicoUSBMonitor
    /// Reports the connect checks to the popover's activity log.
    private let runner: CommandRunner
    private var observers: [(PicoLink) -> Void] = []
    /// Bumped on every plug and unplug, so a slow mpremote result can't land on a different board.
    private var generation = 0
    private(set) var calibrationReserved = false

    /// Reserve before waiting: queued reads and retries become no-ops.
    func reserveForCalibration() async -> String? {
        guard !calibrationReserved else { return nil }
        calibrationReserved = true
        observers.forEach { $0(current) }
        generation += 1
        await tail?.value
        return current.port
    }

    func releaseCalibration() {
        calibrationReserved = false
        observers.forEach { $0(current) }
        // Do not interrupt a still-powered board; next USB attachment refreshes it.
    }
    /// mpremote calls run one at a time; two would fight over the port.
    private var tail: Task<Void, Never>?
    /// Waits before re-reading after a failed read, then gives up until the next plug or Refresh.
    private static let retryDelays: [Duration] = [.seconds(3), .seconds(3), .seconds(3)]

    init(runner: CommandRunner, monitor: PicoUSBMonitor = PicoUSBMonitor()) {
        self.runner = runner
        self.monitor = monitor
    }

    /// Calls `handler` with the current state now and after every change.
    func observe(_ handler: @escaping (PicoLink) -> Void) {
        observers.append(handler)
        handler(current)
    }

    func start() {
        monitor.onChange = { [weak self] port in self?.portChanged(port) }
        monitor.start()
        if monitor.port == nil { portChanged(nil) }
    }

    /// Reads the board again, with the same retries as on connect. Does nothing without a Pico on USB.
    func refresh() {
        guard !calibrationReserved, current.port != nil else { return }
        generation += 1  // Drops retries still pending from an earlier read.
        readOnConnect(attempt: 0)
    }

    /// Runs `work` with the port once no other mpremote call is running, then reads the board
    /// again, reporting that read to `reporter`. Does nothing without a Pico on USB.
    func withBoard(reportingTo reporter: CommandRunner, _ work: @escaping (String) async -> Void) async {
        await enqueue { [weak self] in
            guard let self, !self.calibrationReserved, let port = current.port else { return }
            let generation = generation
            await work(port)
            guard generation == self.generation else { return }
            await read(using: reporter)
        }.value
    }

    private func portChanged(_ port: String?) {
        generation += 1
        if port != nil || current.port != nil {
            runner.report?(LinkActivity(text: "$ mpremote connect list", state: .ok(port ?? "Pico unplugged")))
        }
        var link = PicoLink(route: port == nil ? .offline : .usb)
        link.port = port
        current = link
        guard port != nil else { return }
        readOnConnect(attempt: 0)
    }

    private func readOnConnect(attempt: Int) {
        enqueue { [weak self] in
            guard let self, !self.calibrationReserved else { return }
            let generation = generation
            guard await !read(using: runner), attempt < Self.retryDelays.count else { return }
            let delay = Self.retryDelays[attempt]
            Task { [weak self] in
                try? await Task.sleep(for: delay)
                guard let self, generation == self.generation, current.arm == nil else { return }
                readOnConnect(attempt: attempt + 1)
            }
        }
    }

    /// Reads mode, modes, board and files in one call. Keeps what the output had even when the
    /// call failed partway (a broken `modes.py` still shows its files, so it can be re-uploaded).
    /// False when the mode couldn't be read.
    @discardableResult
    private func read(using reporter: CommandRunner) async -> Bool {
        guard !calibrationReserved, let port = current.port else { return false }
        let generation = generation
        var output = ""
        await reporter.run("mpremote", ["connect", port, "exec", PicoMode.readScript]) { result in
            output = result.stdout
            let arm = PicoMode.parse(result.stdout)
            guard result.succeeded else { return .failed(result.failureReason) }
            return arm.map { .ok($0.label) } ?? .failed("no mode= line in the output")
        }
        guard generation == self.generation else { return false }
        var link = current
        link.arm = PicoMode.parse(output)
        link.modes = PicoMode.parseModes(output)
        (link.board, link.runtime) = PicoMode.parseBoard(output) ?? (nil, nil)
        link.files = PicoMode.parseFiles(output)
        link.calibration = PicoMode.parseCalibration(output)
        current = link
        return link.arm != nil
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

/// The scripts run on the board and the parsing of their output, shared with tests.
nonisolated enum PicoMode {
    static let file = "mode.txt"

    /// The firmware module that declares `MODES`, every request the file accepts.
    static let module = "modes"

    /// Prints, one per line: `board=machine|release`, `file=name,bytes,sha256` for each `.py`
    /// file, `mode=` and the file's contents (nothing when there is no file), then `modes=` and
    /// the firmware's `MODES`, comma-separated (nothing when the board has no such module).
    /// `modes` goes last: importing a broken module fails the call, after the rest is printed.
    /// Importing it touches no hardware.
    static let readScript =
        "import os, hashlib, binascii; f = os.listdir(); u = os.uname(); "
        + "print('board=' + u.machine + '|' + u.release); "
        + "[print('file=%s,%d,%s' % (n, os.stat(n)[6], binascii.hexlify(hashlib.sha256(open(n, 'rb').read()).digest()).decode())) for n in f if n.endswith('.py')]; "
        + "print('mode=' + (open('\(file)').read().strip() if '\(file)' in f else '')); "
        + "print('calibration=' + (__import__('json').dumps(__import__('calibration').snapshot()) if 'calibration.py' in f else 'null')); "
        + "print('modes=' + (','.join(__import__('\(module)').MODES) if '\(module).py' in f else ''))"

    static func parseCalibration(_ output: String) -> CalibrationSnapshot? {
        guard let raw = value(of: "calibration", in: output), let data = raw.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(CalibrationSnapshot.self, from: data)
    }

    /// Deletes the file if it exists.
    static let disarmScript = "import os; '\(file)' in os.listdir() and os.remove('\(file)')"

    /// The firmware README's arm command.
    static func armScript(_ value: String) -> String {
        "with open('\(file)', 'w') as f: f.write('\(value)')"
    }

    /// The `mode=` line of `readScript`'s output: empty is idle; nil when there is no such line.
    static func parse(_ output: String) -> PicoArm? {
        value(of: "mode", in: output).map { $0.isEmpty ? .idle : .armed($0) }
    }

    /// The `modes=` line of `readScript`'s output, in the firmware's order. Names that could
    /// not be written back safely (anything but letters, digits, `:`, `_`, `-`) are dropped.
    static func parseModes(_ output: String) -> [String] {
        (value(of: "modes", in: output) ?? "").split(separator: ",").compactMap { item in
            let mode = item.trimmingCharacters(in: .whitespaces)
            let isSafe = mode.unicodeScalars.allSatisfy {
                $0.isASCII && (CharacterSet.alphanumerics.contains($0) || ":_-".unicodeScalars.contains($0))
            }
            return mode.isEmpty || !isSafe ? nil : mode
        }
    }

    /// The `board=` line: machine and MicroPython release.
    static func parseBoard(_ output: String) -> (board: String, runtime: String)? {
        guard let value = value(of: "board", in: output) else { return nil }
        let parts = value.split(separator: "|", maxSplits: 1).map(String.init)
        guard parts.count == 2 else { return nil }
        return (parts[0], parts[1])
    }

    /// The `file=` lines, sorted by name; nil when the output has no `board=` line (not read).
    static func parseFiles(_ output: String) -> [PicoFile]? {
        guard value(of: "board", in: output) != nil else { return nil }
        return lines(output).compactMap { line -> PicoFile? in
            guard line.hasPrefix("file=") else { return nil }
            let fields = line.dropFirst("file=".count).split(separator: ",").map(String.init)
            guard fields.count == 3, let bytes = Int(fields[1]) else { return nil }
            return PicoFile(name: fields[0], bytes: bytes, sha: fields[2])
        }
        .sorted { $0.name < $1.name }
    }

    private static func value(of key: String, in output: String) -> String? {
        lines(output).first { $0.hasPrefix(key + "=") }
            .map { $0.dropFirst(key.count + 1).trimmingCharacters(in: .whitespaces) }
    }

    private static func lines(_ output: String) -> [String] {
        output.split(whereSeparator: \.isNewline).map { $0.trimmingCharacters(in: .whitespaces) }
    }
}
