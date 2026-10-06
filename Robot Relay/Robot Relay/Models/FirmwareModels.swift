import Foundation

nonisolated struct FirmwareFile: Equatable, Sendable {
    struct Version: Equatable, Sendable {
        var sha: String
        var bytes: Int
        var date: String
    }

    var name: String
    var device: Version?
    var local: Version

    var differs: Bool { device?.sha != local.sha }
}

/// The Pico and the toolchain used to reach it.
nonisolated struct FirmwareDeviceInfo: Equatable, Sendable {
    var board: String
    var runtime: String
    var port: String
    var tool: String
    var toolEnvironment: String
    var watchdog: String
    var lastUpload: String
}

/// Action `bringup_mode.txt` arms for the next battery boot.
nonisolated enum ArmMode: String, CaseIterable, Sendable {
    case center, test
}

nonisolated enum LegSide: CaseIterable, Sendable {
    case left, right

    var label: String { self == .left ? "Left" : "Right" }
    var pin: String { self == .left ? "GP0" : "GP1" }
}

/// Per-leg trim in degrees from neutral 90.
nonisolated struct LegOffsets: Equatable, Sendable {
    static let range: ClosedRange<Double> = -15...15
    static let step = 0.5

    var left: Double
    var right: Double

    subscript(side: LegSide) -> Double {
        get { side == .left ? left : right }
        set {
            if side == .left { left = newValue } else { right = newValue }
        }
    }
}

nonisolated enum ServoMath {
    static let centerMicros = 1500.0
    static let microsPerDegree = 11.0

    static func pulseMicros(offsetDegrees: Double) -> Int {
        Int((centerMicros + offsetDegrees * microsPerDegree).rounded())
    }
}

nonisolated struct ConsoleLine: Identifiable, Equatable, Sendable {
    enum Kind: Sendable {
        case command, output, progress
    }

    var id = UUID()
    var time = Date.now
    var text: String
    var kind: Kind
}
