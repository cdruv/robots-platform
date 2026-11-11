import Foundation

/// Pretends the phone is reachable over USB. Disconnecting it falls back to
/// "Pico over USB", the state the Firmware design shows.
final class PlaceholderRobotLinkService: RobotLinkService {
    private var current = PlaceholderRobotLinkService.connected()
    private let stream: AsyncStream<RobotLinks>
    private let continuation: AsyncStream<RobotLinks>.Continuation

    init() {
        (stream, continuation) = AsyncStream.makeStream(of: RobotLinks.self)
        continuation.yield(current)
    }

    func links() -> AsyncStream<RobotLinks> { stream }

    func connectPhone() async {
        let controller = current.controller
        current = Self.connected()
        current.controller = controller
        publish()
    }

    func disconnectPhone() async {
        current.phone.state = .disconnected
        current.phone.rttMs = nil
        current.phone.eventsPerSecond = 0
        current.pico = PicoLink(route: .usb, arm: .idle, modes: Self.modes)
        publish()
    }

    func refreshPico() async {}

    func pairController() async {
        current.controller.isPaired = !(current.controller.isPaired ?? false)
        publish()
    }

    func clearActivity() async {
        current.activity.removeAll()
        publish()
    }

    private func publish() {
        continuation.yield(current)
    }

    private static let modes = ["body", "center", "sweep", "once:center", "once:sweep"]

    private static func connected() -> RobotLinks {
        RobotLinks(
            phone: PhoneLink(state: .connected, deviceName: "Pixel 8", rttMs: 38, eventsPerSecond: 18, dropped: 0),
            pico: PicoLink(
                route: .viaPhone,
                arm: .armed("body"),
                modes: modes,
                railVolts: 5.9,
                lastWatchdogReset: Date.now.addingTimeInterval(-120)
            ),
            controller: ControllerLink(name: "Xbox", transport: "Bluetooth", isPaired: false)
        )
    }
}
