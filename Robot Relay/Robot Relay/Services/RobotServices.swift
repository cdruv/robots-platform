import Foundation

/// Every backend the app talks to. Stores depend on these protocols only, so the
/// placeholder implementations can be swapped for real transports one at a time.
struct RobotServices {
    var link: any RobotLinkService
    var telemetry: any TelemetryService
    var live: any LiveStreamService
    var firmware: any FirmwareService
    var drive: any DriveService

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

    /// The phone link, its telemetry and the Pico over USB are real. Everything else reports
    /// no data, so the UI shows dashes instead of canned values.
    static func live() -> RobotServices {
        let phone = PhoneConnection()
        let runner = CommandRunner()
        return RobotServices(
            link: PhoneLinkService(connection: phone, runner: runner, pico: PicoUSBLink(runner: runner)),
            telemetry: PhoneTelemetryService(connection: phone),
            live: UnavailableLiveStreamService(),
            firmware: UnavailableFirmwareService(),
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
    /// Over USB this disarms: deletes `bringup_mode.txt` so the next battery boot does nothing.
    /// No remote stop exists; removing power is the immediate stop.
    func releasePico() async
    /// Over USB, writes `bringup_mode.txt` so the next battery boot runs `mode`, the same
    /// command as the firmware README. Nothing moves until that boot.
    func armPico(mode: ArmMode, repeatEveryBoot: Bool) async
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

/// Pico firmware management over `mpremote`: upload, arming, calibration.
protocol FirmwareService: AnyObject {
    /// nil when no Pico is reachable; the Firmware view then shows no data and disables its actions.
    var deviceInfo: FirmwareDeviceInfo? { get }
    /// The firmware file, with the device copy and the local copy.
    var file: FirmwareFile? { get }
    /// Offsets currently stored on the device.
    var storedOffsets: LegOffsets? { get }
    /// Console output. A line re-sent with the same `id` replaces the earlier one. Single consumer.
    func console() -> AsyncStream<ConsoleLine>
    /// Copies the local file to the device, yielding progress 0…1.
    func upload(_ file: FirmwareFile) -> AsyncStream<Double>
    func backUpDeviceCopy() async
    /// Writes `bringup_mode.txt` so the next battery boot runs `mode`.
    func arm(mode: ArmMode, repeatEveryBoot: Bool) async
    func writeOffsets(_ offsets: LegOffsets) async
    func centerLegs() async
    func sweepLegs(degrees: Double) async
    func openREPL() async
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
