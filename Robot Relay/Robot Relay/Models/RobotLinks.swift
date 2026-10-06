import Foundation

/// The robot's links as shown by the Connection chip and popover.
nonisolated struct RobotLinks: Equatable, Sendable {
    var robotName = "Yobot"
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
    var address = PhoneAddress.defaultForward.description
    var rttMs: Int?
    var eventsPerSecond = 0
    var dropped = 0

    var isConnected: Bool { state == .connected }

    /// Localhost means an `adb forward` over USB.
    var transport: String {
        PhoneAddress(address)?.isLoopback == true ? "USB · adb" : "Wi‑Fi"
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

    var route: Route = .offline
    var isArmed = false
    var railVolts: Double?
    var lastWatchdogReset: Date?
}

nonisolated struct ControllerLink: Equatable, Sendable {
    var name = "Xbox"
    var transport = "Bluetooth"
    var isPaired = false
}
