import Foundation

/// The sidebar destinations.
enum AppSection: String, CaseIterable, Identifiable {
    case liveRobot
    case logs
    case firmware

    var id: String { rawValue }

    var title: String {
        switch self {
        case .liveRobot: "Live Robot"
        case .logs: "Logs"
        case .firmware: "Firmware"
        }
    }
}
