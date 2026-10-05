import Foundation

/// The sidebar destinations.
enum AppSection: String, CaseIterable, Identifiable {
    case liveRobot
    case telemetry
    case firmware
    case drive

    var id: String { rawValue }

    var title: String {
        switch self {
        case .liveRobot: "Live Robot"
        case .telemetry: "Telemetry"
        case .firmware: "Firmware"
        case .drive: "Drive"
        }
    }
}
