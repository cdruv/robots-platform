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

    /// The phone link and its telemetry are real; the rest is still canned.
    static func live() -> RobotServices {
        let phone = PhoneConnection()
        return RobotServices(
            link: PhoneLinkService(connection: phone),
            telemetry: PhoneTelemetryService(connection: phone),
            live: PlaceholderLiveStreamService(),
            firmware: PlaceholderFirmwareService(),
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
    /// Releases the servos (stops driving the PWM lines).
    func releasePico() async
    func pairController() async
    /// Runs `adb forward` so the phone's telemetry port is reachable on localhost.
    func adbForward(address: String) async
    func addRobot() async
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
    var deviceInfo: FirmwareDeviceInfo { get }
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
