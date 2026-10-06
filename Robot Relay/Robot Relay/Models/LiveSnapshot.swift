import Foundation

/// Everything the Live Robot view shows at one instant. A nil stream has delivered no data,
/// and its tile shows dashes rather than made-up values.
nonisolated struct LiveSnapshot: Equatable, Sendable {
    var camera: Camera?
    var mic: Microphone?
    var imu: IMU?
    var face: Face?
    /// Phone battery and thermal state. Link, drops, servo rail and Pico state come from `RobotLinks`.
    var system: System?

    struct Camera: Equatable, Sendable {
        var fps: Int
        var width: Int
        var height: Int
        var motion: Double
        var brightness: Double
        var frame: Int64
    }

    struct Microphone: Equatable, Sendable {
        var sampleRateKHz: Int
        var levelDb: Double
        /// Normalised 0…1 levels, oldest first.
        var bars: [Double]
        var transcript: Transcript?
    }

    struct Transcript: Equatable, Sendable {
        var text: String
        var isFinal: Bool
        var confidence: Double
    }

    struct IMU: Equatable, Sendable {
        var rateHz: Int
        var pitch: Double
        var roll: Double
        /// Linear acceleration magnitude, m/s².
        var accel: Double
        var motionState: String
    }

    struct Face: Equatable, Sendable {
        var expression: String
        var speech: String
        var lastEvent: String
    }

    struct System: Equatable, Sendable {
        var batteryPercent: Int
        var thermal: Int
    }
}

/// The streams `⌘1–5` can focus.
nonisolated enum LiveStream: Int, CaseIterable, Sendable {
    case camera = 1, microphone, attitude, face, system
}
