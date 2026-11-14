import Foundation

/// The Pico's state line, forwarded unchanged by the phone. The fields are defined in the
/// module docstring of `Walky/firmware/pico/src/body.py`; only the ones Relay shows are read.
nonisolated struct PicoBodyState: Decodable, Equatable, Sendable {
    enum Mode: String, Decodable, Sendable {
        case limp, active
    }

    struct Failure: Decodable, Equatable, Sendable {
        var msg: String
    }

    var board: String
    var mode: Mode
    /// Degrees from horizontal, left then right.
    var target: [Double]
    /// Microseconds on the servo pins; nil while limp.
    var pulses: [Int]?
    /// Half-degree steps, left then right: the trim in use and the one in flash.
    var trim: [Int]
    var saved: [Int]
    /// False until a trim has been saved on this board.
    var stored: Bool
    var error: Failure?
}

/// Leg targets in degrees from horizontal. The same sign is the same physical direction on
/// both legs.
nonisolated struct LegAngles: Equatable, Sendable {
    var left = 0.0
    var right = 0.0

    subscript(side: LegSide) -> Double {
        get { side == .left ? left : right }
        set {
            if side == .left { left = newValue } else { right = newValue }
        }
    }
}

/// The phone's report on its link to the Pico (`BodyRelay` in onboard-android).
nonisolated struct BodyLink: Decodable, Equatable, Sendable {
    /// "connected", or what the phone is waiting for: "unpaired", "declined", "searching", "connecting".
    var phase: String
    /// nil unless connected.
    var pico: PicoBodyState?
    /// Phone to Pico and back.
    var rttMs: Int?
}

/// The Pico's own Wi-Fi network, which the phone joins (`link.credentials()` in the firmware).
nonisolated struct PicoPairing: Codable, Equatable, Sendable {
    var ssid: String
    var password: String
}

/// One line from Relay to the phone; the phone side is `BodyRelay` in onboard-android.
nonisolated enum BodyCommand: Equatable, Sendable {
    /// Degrees from horizontal. The phone forwards each line once, so targets are sent as a stream.
    case legs(left: Double, right: Double)
    case limp
    /// Half-degree steps, left then right.
    case trim([Int])
    case save
    case pair(PicoPairing)

    private struct Wire: Encodable {
        var link = "body"
        var legs: [Double]?
        var limp: Bool?
        var trim: [Int]?
        var save: Bool?
        var ssid: String?
        var password: String?
    }

    /// The line without its newline; nil for a target that is not a finite number.
    var line: Data? {
        var wire = Wire()
        switch self {
        case .legs(let left, let right): wire.legs = [left, right]
        case .limp: wire.limp = true
        case .trim(let steps): wire.trim = steps
        case .save: wire.save = true
        case .pair(let pairing):
            wire.link = "pair"
            wire.ssid = pairing.ssid
            wire.password = pairing.password
        }
        let encoder = JSONEncoder()
        encoder.outputFormatting = .sortedKeys
        return try? encoder.encode(wire)
    }
}
