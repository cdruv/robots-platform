import Foundation

/// The robot's links as shown by the Connection chip and popover.
nonisolated struct RobotLinks: Equatable, Sendable {
    var robotName = "Yobot"
    var phone = PhoneLink()
    var pico = PicoLink()
    var controller = ControllerLink()

    var linkCount: Int { 3 }
}

/// The onboard Android app (the robot's brain), reached over TCP.
nonisolated struct PhoneLink: Equatable, Sendable {
    var isConnected = false
    var deviceName = "Pixel 8"
    var transport = "Wi‑Fi"
    var address = "10.0.0.42:7777"
    var rttMs = 0
    var eventsPerSecond = 0
    var dropped = 0
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
