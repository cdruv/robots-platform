import Foundation
import os

nonisolated extension Logger {
    private static let subsystem = Bundle.main.bundleIdentifier ?? "Robot-Relay"

    static let app = Logger(subsystem: subsystem, category: "app")
    static let command = Logger(subsystem: subsystem, category: "command")
    static let pico = Logger(subsystem: subsystem, category: "pico")
    static let firmware = Logger(subsystem: subsystem, category: "firmware")
    static let phone = Logger(subsystem: subsystem, category: "phone")
    static let calibration = Logger(subsystem: subsystem, category: "calibration")
    static let telemetry = Logger(subsystem: subsystem, category: "telemetry")
}
