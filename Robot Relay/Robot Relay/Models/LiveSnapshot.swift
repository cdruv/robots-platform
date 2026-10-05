import Foundation

/// Everything the Live Robot view shows at one instant.
nonisolated struct LiveSnapshot: Equatable, Sendable {
    var camera = Camera()
    var mic = Microphone()
    var imu = IMU()
    var face = Face()
    var system = System()

    struct Camera: Equatable, Sendable {
        var fps = 24
        var width = 1280
        var height = 720
        var motion = 0.12
        var brightness = 0.41
        var frame: Int64 = 48_211
    }

    struct Microphone: Equatable, Sendable {
        var sampleRateKHz = 16
        var levelDb = -31.0
        /// Normalised 0…1 levels, oldest first.
        var bars: [Double] = [
            0.14, 0.28, 0.46, 0.70, 0.52, 0.36, 0.62, 0.84,
            0.58, 0.30, 0.18, 0.40, 0.24, 0.12, 0.08, 0.16,
        ]
        var transcript: Transcript? = Transcript()
    }

    struct Transcript: Equatable, Sendable {
        var text = "are you okay"
        var isFinal = true
        var confidence = 0.91
    }

    struct IMU: Equatable, Sendable {
        var rateHz = 100
        var pitch = 1.8
        var roll = -0.6
        /// Linear acceleration magnitude, m/s².
        var accel = 0.05
        var motionState = "Still"
    }

    struct Face: Equatable, Sendable {
        var expression = "idle"
        var speech = "quiet"
        var lastEvent = "alert 2 min ago"
    }

    struct System: Equatable, Sendable {
        var deviceName = "Pixel 8"
        var batteryPercent = 74
        var thermal = 0
        var linkMs = 38
        var dropped = 0
        var railVolts = 5.9
        var picoArmed = true
    }
}

/// The streams `⌘1–5` can focus.
nonisolated enum LiveStream: Int, CaseIterable, Sendable {
    case camera = 1, microphone, attitude, face, system
}
