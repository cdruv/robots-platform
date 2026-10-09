import Foundation

protocol CalibrationBoard: AnyObject {
    var isUSBConnected: Bool { get }
    var report: ((LinkActivity) -> Void)? { get set }
    func prepare(_ ticket: CalibrationTicket) async throws
    func cancelPrepared(_ ticket: CalibrationTicket) async -> Bool
    func reserve() async
    func release()
}

extension CalibrationBoard {
    var report: ((LinkActivity) -> Void)? {
        get { nil }
        set {}
    }
}

final class PicoCalibrationBoard: CalibrationBoard {
    private let pico: PicoUSBLink
    private let runner = CommandRunner()
    init(pico: PicoUSBLink) { self.pico = pico }
    var isUSBConnected: Bool { pico.current.port != nil }
    var report: ((LinkActivity) -> Void)? {
        get { runner.report }
        set { runner.report = newValue }
    }

    func reserve() async { _ = await pico.reserveForCalibration() }
    func release() { pico.releaseCalibration() }

    func prepare(_ ticket: CalibrationTicket) async throws {
        guard let port = await pico.reserveForCalibration(), pico.current.calibration?.board == ticket.board else {
            throw CalibrationFailure("Reconnect the intended Pico over USB before preparing calibration")
        }
        let data = try JSONEncoder().encode(ticket)
        let encoded = data.base64EncodedString()
        // UUID-based values and board IDs are validated by firmware. A local script keeps
        // the secret out of argv/console while remaining a standard mpremote operation.
        let script = "import calibration, json, binascii\ncalibration.arm(json.loads(binascii.a2b_base64('" + encoded + "')))\nprint('calibration-armed')\n"
        let result = try await runScript(script, port: port, secrets: [encoded, ticket.password, ticket.session])
        guard result.succeeded else {
            throw CalibrationFailure("Calibration arming failed: " + CalibrationCommandDiagnostics.failure(result, secrets: [encoded, ticket.password, ticket.session]))
        }
        guard result.stdout.contains("calibration-armed"), pico.current.port == port else {
            throw CalibrationFailure("Could not confirm calibration arming. Reconnect over USB before retrying.")
        }
    }

    func cancelPrepared(_ ticket: CalibrationTicket) async -> Bool {
        guard let port = pico.current.port, let encoded = try? JSONEncoder().encode(ticket).base64EncodedString() else { return false }
        let script = "import calibration, os, json, binascii\nt = json.loads(binascii.a2b_base64('" + encoded + "'))\nassert calibration.board_id() == t['board']\np = calibration.REQUEST_FILE\nif p in os.listdir():\n r = json.load(open(p))\n assert r['session'] == t['session']\n os.remove(p)\n os.sync()\nprint('calibration-disarmed')\n"
        guard let result = try? await runScript(script, port: port, secrets: [encoded, ticket.password, ticket.session]) else { return false }
        return result.succeeded && result.stdout.contains("calibration-disarmed")
    }

    private func runScript(_ script: String, port: String, secrets: [String]) async throws -> CommandResult {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("relay-calibration-\(UUID().uuidString).py")
        guard FileManager.default.createFile(atPath: url.path, contents: Data(script.utf8), attributes: [.posixPermissions: 0o600]) else {
            throw CalibrationFailure("Could not prepare calibration script")
        }
        defer { try? FileManager.default.removeItem(at: url) }
        return await runner.run("mpremote", ["connect", port, "run", url.path]) { result in
            result.succeeded ? .ok(nil) : .failed(CalibrationCommandDiagnostics.failure(result, secrets: secrets))
        }
    }
}

/// Keep the exception and traceback, but never print the temporary Wi-Fi credentials.
nonisolated enum CalibrationCommandDiagnostics {
    static func failure(_ result: CommandResult, secrets: [String]) -> String {
        var text = [result.stderr, result.stdout].filter { !$0.isEmpty }.joined(separator: "\n")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return "exit \(result.status)" }
        for secret in secrets.filter({ !$0.isEmpty }).sorted(by: { $0.count > $1.count }) {
            text = text.replacingOccurrences(of: secret, with: "[redacted]")
        }
        return String(text.suffix(4096))
    }
}
