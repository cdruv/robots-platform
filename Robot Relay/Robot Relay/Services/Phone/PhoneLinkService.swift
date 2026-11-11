import Foundation
import os

/// Connects to onboard-android through a USB ADB forward.
/// The Pico over USB via mpremote (`PicoUSBLink`). The controller stays offline until its
/// transport exists.
final class PhoneLinkService: RobotLinkService {
    private var current = RobotLinks()
    private let stream: AsyncStream<RobotLinks>
    private let continuation: AsyncStream<RobotLinks>.Continuation
    private let connection: PhoneConnection
    private let runner: CommandRunner
    private let pico: PicoUSBLink

    /// `pico` should share `runner`, so its mpremote commands reach the activity footer.
    init(connection: PhoneConnection, runner: CommandRunner, pico: PicoUSBLink) {
        self.connection = connection
        self.runner = runner
        self.pico = pico
        (stream, continuation) = AsyncStream.makeStream(of: RobotLinks.self, bufferingPolicy: .bufferingNewest(1))
        continuation.yield(current)
        connection.onStatus = { [weak self] status in self?.apply(status) }
        connection.onActivity = { [weak self] entry in self?.record(entry) }
        runner.report = { [weak self] entry in self?.record(entry) }
        pico.observe { [weak self] link in self?.apply(link) }
        pico.start()
    }

    func links() -> AsyncStream<RobotLinks> { stream }

    func connectPhone() async {
        connection.connect { [runner] in
            let port = "tcp:\(PhoneConnection.port)"
            let result = await runner.run("adb", ["-d", "forward", port, port]) { result in
                result.succeeded ? .ok("USB port forward ready") : .failed(Adb.failureMessage(result))
            }
            return result.succeeded
        }
    }

    func disconnectPhone() async {
        connection.disconnect()
    }

    func refreshPico() async {
        pico.refresh()
    }

    func pairController() async {}

    func clearActivity() async {
        current.activity.removeAll()
        continuation.yield(current)
    }

    private func apply(_ status: PhoneConnection.Status) {
        var phone = current.phone
        phone.state = status.state
        phone.deviceName = status.hello?.device ?? "—"
        phone.rttMs = status.rttMs
        phone.eventsPerSecond = status.eventsPerSecond
        phone.dropped = status.dropped
        guard phone != current.phone else { return }
        current.phone = phone
        continuation.yield(current)
    }

    private func apply(_ link: PicoLink) {
        guard link != current.pico else { return }
        current.pico = link
        continuation.yield(current)
    }

    private func record(_ entry: LinkActivity) {
        LinkActivity.append(entry, to: &current.activity)
        continuation.yield(current)
    }
}

nonisolated enum Adb {
    static func failureMessage(_ result: CommandResult) -> String {
        let reason = result.stderr.split(whereSeparator: \.isNewline)
            .first(where: { $0.contains("error:") }).map(String.init) ?? result.failureReason
        if result.status == 127 { return "ADB missing. Install Android SDK Platform-Tools." }
        if reason.contains("unauthorized") { return "Unlock the phone and allow USB debugging." }
        if reason.contains("more than one") { return "Multiple USB devices found. Connect only the Walky phone." }
        if reason.contains("no device") {
            return "No USB phone found. Check the cable and enable USB debugging."
        }
        if reason.contains("offline") { return "USB phone offline. Reconnect it and unlock the screen." }
        return reason
    }
}
