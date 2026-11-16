import Foundation
import os

/// Connects to onboard-android through a USB ADB forward, or over Wi-Fi (TLS) to the phone
/// found by `PhoneDiscovery`. The Pico over USB via mpremote (`PicoUSBLink`), or through the
/// phone when the phone reports its body link connected. The controller stays offline until
/// its transport exists.
///
/// Secrets travel over USB only. Every USB hello pins the phone's certificate and carries the
/// Pico's Wi-Fi name and password (read from the board over USB, kept in the Keychain) and
/// Relay's key, which the phone then requires on Wi-Fi.
final class PhoneLinkService: RobotLinkService {
    private var current = RobotLinks()
    private let stream: AsyncStream<RobotLinks>
    private let continuation: AsyncStream<RobotLinks>.Continuation
    private let connection: PhoneConnection
    private let runner: CommandRunner
    private let pico: PicoUSBLink
    private let discovery = PhoneDiscovery()
    private let picoPairingItem = KeychainItem(service: "RobotRelay.PicoPairing")
    private let relayPairing = RelayPairing()
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
        pairing = picoPairingItem.load(json: PicoPairing.self)
        current.phone.isPairedForWifi = relayPairing.fingerprint != nil
        connection.onStatus = { [weak self] status in self?.apply(status) }
        connection.onHello = { [weak self] hello in self?.greet(hello) }
        discovery.onAppear = { [connection] in connection.routeAppeared() }
        discovery.onActivity = { [weak self] entry in self?.record(entry) }
        pico.onPairing = { [weak self] pairing in self?.store(pairing) }
        connection.onActivity = { [weak self] entry in self?.record(entry) }
        runner.report = { [weak self] entry in self?.record(entry) }
        pico.observe { [weak self] link in self?.apply(link) }
        pico.start()
    }

    func links() -> AsyncStream<RobotLinks> { stream }

    /// USB first, then the phone discovery found, if Relay has its certificate.
    func connectPhone() async {
        discovery.start()
        connection.connect { [runner, discovery, relayPairing] in
            let port = "tcp:\(PhoneConnection.usbPort)"
            let result = await runner.run("adb", ["-d", "forward", port, port]) { result in
                result.succeeded ? .ok("USB port forward ready") : .failed(Adb.failureMessage(result))
            }
            if result.succeeded { return .usb }
            guard let fingerprint = relayPairing.fingerprint, let endpoint = discovery.endpoint else { return nil }
            return .wifi(endpoint, key: relayPairing.key, fingerprint: fingerprint)
        }
    }

    func disconnectPhone() async {
        connection.disconnect()
        discovery.stop()
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
        phone.route = status.route
        phone.isPairedForWifi = relayPairing.fingerprint != nil
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
            picoPairingItem.save(json: new)
            Logger.pico.notice("Pico pairing for \(new.ssid, privacy: .public) read over USB and stored")
            record(LinkActivity(text: "Pico pairing read over USB", state: .ok(new.ssid)))
        }
        // Not sent now: while the Pico is on USB its Wi-Fi is down, so the phone's search could
        // only fail. The next hello carries it, after the Pico is back on battery.
    }

    private func greet(_ hello: PhoneHello) {
        guard connection.status.route == .usb else { return }
        if let cert = hello.cert, relayPairing.pin(cert) {
            Logger.phone.notice("phone: certificate \(cert.prefix(16), privacy: .public)… pinned for Wi-Fi")
            record(LinkActivity(text: "Walky’s Wi‑Fi certificate pinned", state: .ok("sha256 \(cert.prefix(16))…")))
            apply(connection.status)
        }
        let lines = Self.helloLines(route: .usb, pairing: pairing, key: hello.cert == nil ? nil : relayPairing.key)
        lines.forEach(connection.send)
        if hello.cert != nil {
            record(LinkActivity(text: "Relay’s key handed to Walky over USB", state: .ok(nil)))
        }
    }

    /// What Relay sends after a hello: secrets go over USB only, so nothing on Wi-Fi. `key`
    /// is nil when the phone has no Wi-Fi listener to use it.
    static func helloLines(route: PhoneLink.Route, pairing: PicoPairing?, key: Data?) -> [Data] {
        guard route == .usb else { return [] }
        return [pairing.flatMap { BodyCommand.pair($0).line }, key.map { PhoneConnection.keyLine("key", $0) }].compactMap { $0 }
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
