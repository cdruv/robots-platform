import Foundation

/// Every backend the app talks to. Stores depend on these protocols only, so the
/// placeholder implementations can be swapped for real transports one at a time.
struct RobotServices {
    var link: any RobotLinkService
    var telemetry: any TelemetryService
    var live: any LiveStreamService
    var firmware: any FirmwareService
    var drive: any DriveService

    /// True in the app that hosts the unit tests. The host gets placeholder services, and the
    /// network, USB and process entry points refuse to run, so no test reaches real hardware.
    nonisolated static let isTestHost = ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil

    /// Canned data from the design; nothing touches the network, USB or Bluetooth.
    static func placeholder() -> RobotServices {
        RobotServices(
            link: PlaceholderRobotLinkService(),
            telemetry: PlaceholderTelemetryService(),
            live: PlaceholderLiveStreamService(),
            firmware: PlaceholderFirmwareService(),
            drive: PlaceholderDriveService()
        )
    }

    /// The phone link, its telemetry and the Pico over USB (status and firmware) are real.
    /// Everything else reports no data, so the UI shows dashes instead of canned values.
    static func live() -> RobotServices {
        let phone = PhoneConnection()
        let runner = CommandRunner()
        let pico = PicoUSBLink(runner: runner)
        return RobotServices(
            link: PhoneLinkService(connection: phone, runner: runner, pico: pico),
            telemetry: PhoneTelemetryService(connection: phone),
            live: UnavailableLiveStreamService(),
            firmware: PicoFirmwareService(pico: pico),
            drive: PlaceholderDriveService()
        )
    }
}

/// Owns the links to the phone app, the Pico and the game controller.
protocol RobotLinkService: AnyObject {
    /// Current link state followed by every change. Single consumer.
    func links() -> AsyncStream<RobotLinks>
    func connectPhone(address: String) async
    func disconnectPhone() async
    /// Reads the Pico over USB again, retrying like it does on plug-in.
    func refreshPico() async
    func pairController() async
    /// Runs `adb forward` so the phone's telemetry port is reachable on localhost.
    func adbForward(address: String) async
    /// Empties the popover's activity log.
    func clearActivity() async
}

/// Telemetry events from onboard-android (NDJSON over TCP :7777).
protocol TelemetryService: AnyObject {
    /// Buffered events followed by live ones, in `seq` order.
    func events() -> AsyncStream<TelemetryEvent>
    func export(_ events: [TelemetryEvent]) async
}

/// Camera, microphone, IMU, face and system state streamed from the robot.
protocol LiveStreamService: AnyObject {
    func snapshots() -> AsyncStream<LiveSnapshot>
    func takeSnapshot() async
    func setRecording(_ isRecording: Bool) async
    func setMuted(_ isMuted: Bool) async
}

/// Pico firmware and board configuration over `mpremote`: upload, power-on mode, calibration.
protocol FirmwareService: AnyObject {
    /// Current state followed by every change. Single consumer.
    func state() -> AsyncStream<FirmwareState>
    /// The commands the service runs, each followed by its result. Single consumer.
    func console() -> AsyncStream<ConsoleLine>
    /// Re-reads the local firmware folder. Doesn't touch the board.
    func refreshLocal() async
    /// Uploads from `folder` from now on, remembered across launches; nil returns to the
    /// repository's firmware folder.
    func setFolder(_ folder: URL?) async
    /// Copies the local files that differ from the board's, then reads the board again.
    /// Never resets the board: the new code runs from the next power-on.
    func upload() async
    /// Writes `mode` (one of `FirmwareState.modes`) to `mode.txt`; nil deletes the file, so
    /// power-on runs nothing. Nothing moves until the next power-on.
    func setBootMode(_ mode: String?) async
    func writeOffsets(_ offsets: LegOffsets) async
    func centerLegs() async
    func sweepLegs(degrees: Double) async
}

/// Manual driving from a game controller. Not designed yet.
protocol DriveService: AnyObject {
    func send(_ command: DriveCommand) async
}

nonisolated struct DriveCommand: Equatable, Sendable {
    /// Forward speed, −1…1.
    var linear: Double
    /// Turn rate, −1…1.
    var angular: Double
}
