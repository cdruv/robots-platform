import Foundation

/// Replays the session from the design, then keeps emitting routine events.
final class PlaceholderTelemetryService: TelemetryService {
    private struct Scripted {
        var time: Double
        var source: String
        var kind: String
        var payload: JSONValue

        static func log(_ time: Double, _ source: String, _ level: String, _ msg: String, error: String? = nil) -> Scripted {
            var fields: [String: JSONValue] = ["msg": .string(msg)]
            if let error { fields["error"] = .string(error) }
            return Scripted(time: time, source: source, kind: "log.\(level)", payload: .object(fields))
        }

        static func motion(_ time: Double, _ state: String, pitch: Double, roll: Double, mag: Double) -> Scripted {
            Scripted(time: time, source: "imu", kind: "Motion", payload: .object([
                "state": .string(state), "pitch": .number(pitch), "roll": .number(roll), "mag": .number(mag),
            ]))
        }

        static func status(_ time: Double) -> Scripted {
            Scripted(time: time, source: "system", kind: "SystemStatus", payload: .object([
                "battery": .number(74), "thermal": .number(0),
            ]))
        }
    }

    /// First seq is chosen so the watchdog error lands on seq 1036, as in the design.
    private static let firstSeq: Int64 = 1031

    private static let seed: [Scripted] = [
        .log(0.000, "telemetry", "info", "TCP client connected (sinks: tcp, recent)"),
        .log(4.130, "executive", "info", "Intent StandUp → skill body.stand (priority 2)"),
        .log(4.138, "body", "info", "pico ← L 1500µs  R 1500µs  hold 400ms"),
        .log(4.902, "body", "warn", "Link RTT 142 ms exceeds control budget (60 ms)"),
        .log(5.001, "telemetry", "warn", "Dropped events: queue=0, sinks={\"tcp\":12}"),
        .log(
            6.344118, "body", "error", "Pico watchdog reset (WDT_RESET); servos released",
            error: "BodyLinkException: no ack within 2000 ms"
        ),
        .log(6.350, "executive", "info", "Skill body.stand aborted; state Idle"),
        .motion(7.008, "Tilted", pitch: 12.4, roll: 3.1, mag: 0.31),
        .log(7.420, "body", "info", "pico link re-established, RTT 38 ms"),
        .status(8.002),
        Scripted(time: 9.611, source: "hearing", kind: "Heard", payload: .object([
            "text": .string("are you okay"), "confidence": .number(0.91),
        ])),
        .log(9.640, "executive", "info", "Intent Reassure → say \"I slipped. Resetting.\""),
        Scripted(time: 9.655, source: "speech", kind: "SpeechStarted", payload: .object(["utteranceId": .string("u-0419")])),
        Scripted(time: 11.20, source: "speech", kind: "SpeechFinished", payload: .object(["utteranceId": .string("u-0419")])),
        .motion(12.01, "Still", pitch: 2.0, roll: -0.3, mag: 0.05),
        .log(12.40, "body", "info", "pico ← L 1500µs  R 1500µs  hold 400ms"),
    ]

    private static let routine: [Scripted] = [
        .motion(0, "Still", pitch: 1.8, roll: -0.6, mag: 0.05),
        .status(0),
        .log(0, "body", "info", "pico ← L 1500µs  R 1500µs  hold 400ms"),
        .motion(0, "Still", pitch: 1.9, roll: -0.5, mag: 0.04),
        .log(0, "executive", "info", "State Idle; no pending intents"),
        .log(0, "body", "warn", "Link RTT 71 ms exceeds control budget (60 ms)"),
        .motion(0, "Still", pitch: 1.7, roll: -0.6, mag: 0.06),
        .log(0, "body", "info", "pico link RTT 38 ms"),
    ]

    func events() -> AsyncStream<TelemetryEvent> {
        let (stream, continuation) = AsyncStream.makeStream(of: TelemetryEvent.self)
        let task = Task {
            let lastSeedTime = Self.seed.last?.time ?? 0
            let sessionStart = Date.now.addingTimeInterval(-lastSeedTime)
            var seq = Self.firstSeq

            func event(_ scripted: Scripted, at time: Double) -> TelemetryEvent {
                defer { seq += 1 }
                return TelemetryEvent(
                    seq: seq,
                    tsWallMs: Int64((sessionStart.timeIntervalSince1970 + time) * 1000),
                    tsMonoNs: Int64((time * 1e9).rounded()),
                    source: scripted.source,
                    kind: scripted.kind,
                    payload: scripted.payload
                )
            }

            for scripted in Self.seed {
                continuation.yield(event(scripted, at: scripted.time))
            }
            var index = 0
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(900))
                let time = Date.now.timeIntervalSince(sessionStart)
                continuation.yield(event(Self.routine[index % Self.routine.count], at: time))
                index += 1
            }
        }
        continuation.onTermination = { _ in task.cancel() }
        return stream
    }

    func export(_ events: [TelemetryEvent]) async {}
}
