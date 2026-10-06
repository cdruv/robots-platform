import CryptoKit
import Foundation

/// Firmware over USB: compares the local firmware folder with the board's files, uploads the
/// changed ones and sets the power-on mode, all with mpremote through `PicoUSBLink`'s queue.
/// Its commands go to the Firmware console, not the popover's log. Never resets the board,
/// so uploaded code runs from the next power-on.
final class PicoFirmwareService: FirmwareService {
    /// `Yobot/firmware/pico` in the checkout this app was built from.
    nonisolated static let defaultFolder = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()  // Pico
        .deletingLastPathComponent()  // Services
        .deletingLastPathComponent()  // Robot Relay (target)
        .deletingLastPathComponent()  // Robot Relay (project)
        .deletingLastPathComponent()  // repository root
        .appendingPathComponent("Yobot/firmware/pico")

    /// The folder chosen in the Firmware tab. Unset follows `defaultFolder`. Debug and release
    /// builds share the bundle identifier, so they share this.
    static let folderKey = "firmwareFolder"

    private let pico: PicoUSBLink
    private let runner: CommandRunner
    private let defaults: UserDefaults
    private var folder: URL
    private var link = PicoLink()
    /// nil when the folder is missing.
    private var local: [PicoFile]?
    private var isBusy = false
    private var current: FirmwareState {
        didSet { if current != oldValue { states.continuation.yield(current) } }
    }

    private let states = AsyncStream.makeStream(of: FirmwareState.self, bufferingPolicy: .bufferingNewest(1))
    private let lines = AsyncStream.makeStream(of: ConsoleLine.self)

    init(pico: PicoUSBLink, defaults: UserDefaults = .standard, runner: CommandRunner = CommandRunner()) {
        self.pico = pico
        self.defaults = defaults
        self.runner = runner
        folder = defaults.string(forKey: Self.folderKey).map { URL(fileURLWithPath: $0) } ?? Self.defaultFolder
        current = FirmwareState(folder: folder.path)
        local = Self.localFiles(in: folder)
        states.continuation.yield(current)
        runner.report = { [weak self] entry in self?.log(entry) }
        pico.observe { [weak self] link in
            self?.link = link
            self?.update()
        }
    }

    func state() -> AsyncStream<FirmwareState> { states.stream }

    func console() -> AsyncStream<ConsoleLine> { lines.stream }

    func refreshLocal() async {
        local = Self.localFiles(in: folder)
        update()
    }

    func setFolder(_ folder: URL?) async {
        guard !isBusy else { return }
        let folder = folder?.standardizedFileURL ?? Self.defaultFolder
        if folder == Self.defaultFolder.standardizedFileURL {
            defaults.removeObject(forKey: Self.folderKey)
        } else {
            defaults.set(folder.path, forKey: Self.folderKey)
        }
        self.folder = folder
        await refreshLocal()
    }

    func upload() async {
        await refreshLocal()
        let names = current.changedFiles.map(\.name)
        guard !isBusy, !names.isEmpty else { return }
        let paths = names.map { folder.appendingPathComponent($0).path }
        await busy {
            await pico.withBoard(reportingTo: runner) { [runner] port in
                await runner.run("mpremote", ["connect", port, "fs", "cp"] + paths + [":"], timeout: .seconds(60)) { result in
                    result.succeeded ? .ok("copied \(names.joined(separator: ", "))") : .failed(result.failureReason)
                }
            }
        }
    }

    func setBootMode(_ mode: String?) async {
        if let mode, !link.modes.contains(mode) { return }
        guard !isBusy else { return }
        let script = mode.map(PicoMode.armScript) ?? PicoMode.disarmScript
        await busy {
            await pico.withBoard(reportingTo: runner) { [runner] port in
                await runner.run("mpremote", ["connect", port, "exec", script])
            }
        }
    }

    func writeOffsets(_ offsets: LegOffsets) async {}
    func centerLegs() async {}
    func sweepLegs(degrees: Double) async {}

    private func busy(_ work: () async -> Void) async {
        isBusy = true
        update()
        await work()
        isBusy = false
        update()
    }

    private func update() {
        var state = FirmwareState(folder: folder.path)
        state.port = link.port
        state.board = link.board
        state.runtime = link.runtime
        state.tool = CommandRunner.resolve("mpremote", environment: ProcessInfo.processInfo.environment)?
            .deletingLastPathComponent().path
        state.isDefaultFolder = folder.standardizedFileURL == Self.defaultFolder.standardizedFileURL
        state.hasLocalFolder = local != nil
        state.hasDeviceFiles = link.files != nil
        state.files = FirmwareFile.merge(local: local ?? [], device: link.files)
        state.bootMode = link.arm
        state.modes = link.modes
        state.isBusy = isBusy
        current = state
    }

    /// A command line, then its result as output; same `$ …` text as the popover's log.
    private func log(_ entry: LinkActivity) {
        switch entry.state {
        case .running:
            lines.continuation.yield(ConsoleLine(time: entry.date, text: entry.text, kind: .command))
        case .ok(let detail):
            lines.continuation.yield(ConsoleLine(time: entry.date, text: detail ?? "ok", kind: .output))
        case .failed(let reason):
            lines.continuation.yield(ConsoleLine(time: entry.date, text: reason, kind: .error))
        }
    }

    /// The folder's top-level `.py` files, sorted; nil when the folder doesn't exist.
    nonisolated static func localFiles(in folder: URL) -> [PicoFile]? {
        guard let names = try? FileManager.default.contentsOfDirectory(atPath: folder.path) else { return nil }
        return names.filter { $0.hasSuffix(".py") }.sorted().compactMap { name in
            guard let data = FileManager.default.contents(atPath: folder.appendingPathComponent(name).path) else {
                return nil
            }
            let sha = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
            return PicoFile(name: name, bytes: data.count, sha: sha)
        }
    }
}
