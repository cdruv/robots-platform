import Foundation

/// The phone row for real: TCP to onboard-android, optionally through `adb forward`.
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
        pico.onChange = { [weak self] link in self?.apply(link) }
        pico.start()
    }

    func links() -> AsyncStream<RobotLinks> { stream }

    func connectPhone(address: String) async {
        guard let address = PhoneAddress(address) else {
            record(LinkActivity(text: address, state: .failed("expected host:port")))
            return
        }
        connection.connect(to: address)
    }

    func disconnectPhone() async {
        connection.disconnect()
    }

    func releasePico() async {
        await pico.disarm()
    }

    func armPico(mode: ArmMode, repeatEveryBoot: Bool) async {
        await pico.arm(PicoMode.value(mode: mode, repeatEveryBoot: repeatEveryBoot))
    }

    func pairController() async {}

    /// Runs `adb devices`, picks the one online phone, forwards its port to
    /// localhost, then connects to `127.0.0.1:port`.
    func adbForward(address: String) async {
        let port = PhoneAddress(address)?.port ?? PhoneAddress.defaultPort
        var serials: [String] = []
        let devices = await runner.run("adb", ["devices"]) { result in
            guard result.succeeded else { return .failed(result.failureReason) }
            serials = Adb.onlineSerials(fromDevicesOutput: result.stdout)
            return Adb.judgeSelection(serials)
        }
        guard devices.succeeded, serials.count == 1, let serial = serials.first else { return }

        let forward = await runner.run("adb", ["-s", serial, "forward", "tcp:\(port)", "tcp:\(port)"])
        guard forward.succeeded else { return }
        connection.connect(to: PhoneAddress(host: "127.0.0.1", port: port))
    }

    private func apply(_ status: PhoneConnection.Status) {
        var phone = current.phone
        phone.state = status.state
        if let address = status.address { phone.address = address.description }
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
        LinkActivity.upsert(entry, into: &current.activity)
        continuation.yield(current)
    }
}

/// Parsing for `adb` output, shared with tests.
nonisolated enum Adb {
    /// Serials in the `device` state from `adb devices` (skips offline/unauthorized ones).
    static func onlineSerials(fromDevicesOutput output: String) -> [String] {
        output.split(whereSeparator: \.isNewline).dropFirst().compactMap { line in
            let fields = line.split(whereSeparator: \.isWhitespace)
            guard fields.count >= 2, fields[1] == "device" else { return nil }
            return String(fields[0])
        }
    }

    /// The footer state for `adb devices`: ok naming the one device, else why none was picked.
    static func judgeSelection(_ serials: [String]) -> LinkActivity.State {
        switch serials.count {
        case 1: .ok(serials[0])
        case 0: .failed("no online device · is USB debugging authorized?")
        default: .failed("several devices: \(serials.joined(separator: ", "))")
        }
    }
}
