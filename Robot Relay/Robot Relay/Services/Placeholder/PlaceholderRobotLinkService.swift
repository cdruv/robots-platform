import Foundation

/// Pretends the phone is reachable over Wi‑Fi. Disconnecting it falls back to
/// "Pico over USB", the state the Firmware design shows.
final class PlaceholderRobotLinkService: RobotLinkService {
    private var current = PlaceholderRobotLinkService.connected(address: "10.0.0.42:7777")
    private let stream: AsyncStream<RobotLinks>
    private let continuation: AsyncStream<RobotLinks>.Continuation

    init() {
        (stream, continuation) = AsyncStream.makeStream(of: RobotLinks.self)
        continuation.yield(current)
    }

    func links() -> AsyncStream<RobotLinks> { stream }

    func connectPhone(address: String) async {
        let controller = current.controller
        current = Self.connected(address: address)
        current.controller = controller
        publish()
    }

    func disconnectPhone() async {
        current.phone.isConnected = false
        current.phone.rttMs = 0
        current.phone.eventsPerSecond = 0
        current.pico = PicoLink(route: .usb)
        publish()
    }

    func releasePico() async {
        current.pico.isArmed = false
        publish()
    }

    func pairController() async {
        current.controller.isPaired.toggle()
        publish()
    }

    func adbForward(address: String) async {
        current.phone.address = address
        publish()
    }

    func addRobot() async {}

    private func publish() {
        continuation.yield(current)
    }

    private static func connected(address: String) -> RobotLinks {
        RobotLinks(
            phone: PhoneLink(isConnected: true, address: address, rttMs: 38, eventsPerSecond: 18, dropped: 0),
            pico: PicoLink(
                route: .viaPhone,
                isArmed: true,
                railVolts: 5.9,
                lastWatchdogReset: Date.now.addingTimeInterval(-120)
            )
        )
    }
}
