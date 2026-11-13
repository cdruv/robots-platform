import Foundation

/// The robot's links as shown by the Connection chip and popover.
nonisolated struct RobotLinks: Equatable, Sendable {
    var robotName = "Walky"
    var phone = PhoneLink()
    var pico = PicoLink()
    var controller = ControllerLink()
    /// What the app last did on the robot's behalf, newest last (at most 4).
    var activity: [LinkActivity] = []

    var linkCount: Int { 3 }
}

/// The onboard Android app (the robot's brain), reached over TCP.
nonisolated struct PhoneLink: Equatable, Sendable {
    enum State: Equatable, Sendable {
        case disconnected, connecting, connected
        /// Waiting to reconnect at the given time.
        case retrying(Date)
    }

    var state: State = .disconnected
    /// From the phone's hello line.
    var deviceName = "—"
    var rttMs: Int?
    var eventsPerSecond = 0
    var dropped = 0

    var isConnected: Bool { state == .connected }
}

/// The Pico 2 W body controller, reached through the phone or directly over USB.
nonisolated struct PicoLink: Equatable, Sendable {
    enum Route: Sendable {
        case viaPhone, usb, offline
    }

    /// nil: nothing reports on the Pico yet.
    var route: Route?
    /// The serial port on the USB route, for example `/dev/cu.usbmodem1101`.
    var port: String?
    /// nil: not read yet, or the port was busy.
    var arm: PicoArm?
    /// The requests the firmware on the board accepts (`modes.MODES`), in its order.
    /// Empty when the board doesn't say.
    var modes: [String] = []
    /// From `os.uname()`: machine ("Raspberry Pi Pico 2 W with RP2350") and MicroPython release.
    var board: String?
    var runtime: String?
    /// The board's `.py` files; nil until read.
    var files: [PicoFile]?
    var calibration: CalibrationSnapshot?
    /// The firmware's `boot.log`, oldest first, from the last board read.
    var bootLog: [String] = []
    var railVolts: Double?
    var lastWatchdogReset: Date?
}

/// A file as stored on the board, or in the local firmware folder.
nonisolated struct PicoFile: Equatable, Sendable {
    var name: String
    var bytes: Int
    /// SHA-256, lowercase hex.
    var sha: String
}

/// What `mode.txt` asks power-on to run.
nonisolated enum PicoArm: Equatable, Sendable {
    /// No `mode.txt`.
    case idle
    /// The file's contents: one of the firmware's modes (`body`, `center`, `once:sweep`, …),
    /// or anything else, shown as-is.
    case armed(String)

    var isArmed: Bool { self != .idle }

    /// "idle", "boot: body" (every power-on), "once: sweep" (next boot only).
    var label: String {
        switch self {
        case .idle: "idle"
        case .armed(let mode): mode.hasPrefix("once:") ? Self.menuLabel(mode) : "boot: " + mode
        }
    }

    /// A mode as the Arm menu shows it: "body", "once: sweep".
    static func menuLabel(_ mode: String) -> String {
        mode.replacingOccurrences(of: ":", with: ": ")
    }
}

/// The game controller. All nil until something reports on it.
nonisolated struct ControllerLink: Equatable, Sendable {
    var name: String?
    var transport: String?
    var isPaired: Bool?
}
