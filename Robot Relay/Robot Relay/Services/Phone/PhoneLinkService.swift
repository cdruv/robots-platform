import Foundation
import os

/// Connects to onboard-android through a USB ADB forward.
/// The Pico over USB via mpremote (`PicoUSBLink`), or through the phone when the phone
/// reports its body link connected. The controller stays offline until its transport exists.
///
/// Pairing: the Pico's Wi-Fi name and password are read from the board over USB, kept in
/// the Keychain and sent to the phone on every hello.
final class PhoneLinkService: RobotLinkService {
    private var current = RobotLinks()
    private let stream: AsyncStream<RobotLinks>
    private let continuation: AsyncStream<RobotLinks>.Continuation
    private let connection: PhoneConnection
    private let runner: CommandRunner
    private let pico: PicoUSBLink
    private let keychain = PairingKeychain()
    private var pairing: PicoPairing?
    private var usb = PicoLink()
    private var body: PhoneConnection.Body?

    /// `pico` should share `runner`, so its mpremote commands reach the activity footer.
    init(connection: PhoneConnection, runner: CommandRunner, pico: PicoUSBLink) {
        self.connection = connection
        self.runner = runner
        self.pico = pico
        (stream, continuation) = AsyncStream.makeStream(of: RobotLinks.self, bufferingPolicy: .bufferingNewest(1))
        continuation.yield(current)
        pairing = keychain.load()
        connection.onStatus = { [weak self] status in self?.apply(status) }
        connection.onHello = { [weak self] in self?.sendPairing() }
        pico.onPairing = { [weak self] pairing in self?.store(pairing) }
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
        body = status.body
        let pico = picoLink()
        guard phone != current.phone || pico != current.pico else { return }
        current.phone = phone
        current.pico = pico
        continuation.yield(current)
    }

    private func apply(_ link: PicoLink) {
        usb = link
        let pico = picoLink()
        guard pico != current.pico else { return }
        current.pico = pico
        continuation.yield(current)
    }

    /// USB wins: plugging the Pico in stops its body link until the next power-on.
    private func picoLink() -> PicoLink {
        var link = usb
        if usb.route != .usb, let body, body.phase == "connected" {
            link.route = .viaPhone
            link.bodyMode = body.mode
        }
        return link
    }

    private func store(_ new: PicoPairing) {
        if new != pairing {
            pairing = new
            keychain.save(new)
            Logger.pico.notice("Pico pairing for \(new.ssid, privacy: .public) read over USB and stored")
            record(LinkActivity(text: "Pico pairing read over USB", state: .ok(new.ssid)))
        }
        // Not sent now: while the Pico is on USB its Wi-Fi is down, so the phone's search could
        // only fail. The next hello carries it, after the Pico is back on battery.
    }

    private func sendPairing() {
        guard let pairing, let line = BodyCommand.pair(pairing).line else { return }
        connection.send(line)
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
