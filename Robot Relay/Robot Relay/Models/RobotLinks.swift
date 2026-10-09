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
    /// Empty until the first connect.
    var address = ""
    var rttMs: Int?
    var eventsPerSecond = 0
    var dropped = 0

    var isConnected: Bool { state == .connected }

    /// Localhost means an `adb forward` over USB.
    var transport: String {
        guard let address = PhoneAddress(address) else { return "—" }
        return address.isLoopback ? "USB · adb" : "Wi‑Fi"
    }
}

/// `host:port` of the phone's telemetry server. The port defaults to 7777.
nonisolated struct PhoneAddress: Hashable, Sendable, CustomStringConvertible {
    static let defaultPort: UInt16 = 7777
    static let defaultForward = PhoneAddress(host: "127.0.0.1", port: defaultPort)

    var host: String
    var port: UInt16

    init(host: String, port: UInt16) {
        self.host = host
        self.port = port
    }

    /// Accepts `host` or `host:port`; nil for anything else.
    init?(_ string: String) {
        let trimmed = string.trimmingCharacters(in: .whitespaces)
        let parts = trimmed.split(separator: ":", omittingEmptySubsequences: false)
        guard (1...2).contains(parts.count) else { return nil }
        let host = String(parts[0])
        guard !host.isEmpty, host.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "." || $0 == "-" }) else {
            return nil
        }
        var port = Self.defaultPort
        if parts.count == 2 {
            guard let value = UInt16(parts[1]), value > 0 else { return nil }
            port = value
        }
        self.init(host: host, port: port)
    }

    var isLoopback: Bool { host == "localhost" || host == "127.0.0.1" }

    var description: String { "\(host):\(port)" }
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
