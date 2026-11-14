import Observation

/// Root state: the selected section plus one store per feature.
@Observable
final class AppModel {
    var selection: AppSection = .liveRobot
    var isConnectionPopoverPresented = false

    let connection: ConnectionStore
    let live: LiveRobotStore
    let telemetry: TelemetryStore
    let firmware: FirmwareStore
    let body: BodyStore

    init(services: RobotServices) {
        connection = ConnectionStore(service: services.link)
        live = LiveRobotStore(service: services.live)
        telemetry = TelemetryStore(service: services.telemetry)
        firmware = FirmwareStore(service: services.firmware)
        body = BodyStore(service: services.body)
    }

    /// Starts consuming every service stream. Safe to call more than once.
    func start() {
        connection.start()
        live.start()
        telemetry.start()
        firmware.start()
        body.start()
    }
}
