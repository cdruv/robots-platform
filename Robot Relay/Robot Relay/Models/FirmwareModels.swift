import Foundation

/// One firmware file: the local copy and the board's copy, either of which may be missing.
nonisolated struct FirmwareFile: Identifiable, Equatable, Sendable {
    struct Version: Equatable, Sendable {
        var sha: String
        var bytes: Int
    }

    var name: String
    var local: Version?
    var device: Version?

    var id: String { name }

    /// Local and board copies differ (including one of them missing).
    var differs: Bool { local != device }

    /// Pairs local and board files by name, sorted. `device` nil means the board wasn't read,
    /// so board copies stay unknown.
    static func merge(local: [PicoFile], device: [PicoFile]?) -> [FirmwareFile] {
        let names = Set(local.map(\.name)).union((device ?? []).map(\.name)).sorted()
        return names.map { name in
            FirmwareFile(
                name: name,
                local: local.first { $0.name == name }.map { Version(sha: $0.sha, bytes: $0.bytes) },
                device: device?.first { $0.name == name }.map { Version(sha: $0.sha, bytes: $0.bytes) }
            )
        }
    }
}

/// Everything the Firmware view shows about the Pico on USB and the local firmware folder.
nonisolated struct FirmwareState: Equatable, Sendable {
    /// nil when no Pico is on USB.
    var port: String?
    /// From `os.uname()`; nil until read.
    var board: String?
    var runtime: String?
    /// Where the local firmware lives, and where `mpremote` was found (nil: not found).
    var folder: String
    var tool: String?
    /// The folder is `Walky/firmware/pico` in the checkout the app was built from.
    var isDefaultFolder = true
    /// False when the folder is missing; `files` then only lists the board's.
    var hasLocalFolder = false
    /// False until the board's files are read.
    var hasDeviceFiles = false
    var files: [FirmwareFile] = []
    /// What `mode.txt` asks power-on to run; nil until read.
    var bootMode: PicoArm?
    /// What the board's firmware accepts (`modes.MODES`).
    var modes: [String] = []
    /// An upload or mode change is running.
    var isBusy = false
    /// nil: leg calibration isn't on the board yet.
    var storedOffsets: LegOffsets?

    init(folder: String = "") {
        self.folder = folder
    }

    var hasDevice: Bool { port != nil }

    /// Local files the board lacks or holds a different version of.
    var changedFiles: [FirmwareFile] {
        guard hasDeviceFiles else { return [] }
        return files.filter { $0.local != nil && $0.differs }
    }
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
        case command, output, error
    }

    var id = UUID()
    var time = Date.now
    var text: String
    var kind: Kind
}
