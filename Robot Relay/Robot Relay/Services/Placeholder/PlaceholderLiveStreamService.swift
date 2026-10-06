import Foundation

/// Emits the design's snapshot with a little jitter so the tiles look alive.
final class PlaceholderLiveStreamService: LiveStreamService {
    func snapshots() -> AsyncStream<LiveSnapshot> {
        let (stream, continuation) = AsyncStream.makeStream(of: LiveSnapshot.self, bufferingPolicy: .bufferingNewest(1))
        let task = Task {
            var snapshot = LiveSnapshot.design
            var tick = 0.0
            while !Task.isCancelled {
                continuation.yield(snapshot)
                try? await Task.sleep(for: .milliseconds(250))
                tick += 1
                snapshot.camera?.frame += 6
                snapshot.camera?.motion = 0.12 + 0.02 * sin(tick / 5)
                snapshot.imu?.pitch = 1.8 + 0.3 * sin(tick / 7)
                snapshot.imu?.roll = -0.6 + 0.4 * sin(tick / 11)
                snapshot.mic?.bars.removeFirst()
                snapshot.mic?.bars.append(0.08 + 0.8 * abs(sin(tick / 3) * sin(tick / 1.7)))
                snapshot.mic?.levelDb = -31 + 3 * sin(tick / 4)
            }
        }
        continuation.onTermination = { _ in task.cancel() }
        return stream
    }

    func takeSnapshot() async {}
    func setRecording(_ isRecording: Bool) async {}
    func setMuted(_ isMuted: Bool) async {}
}

extension LiveSnapshot {
    /// The values from the design, for previews.
    static let design = LiveSnapshot(
        camera: Camera(fps: 24, width: 1280, height: 720, motion: 0.12, brightness: 0.41, frame: 48_211),
        mic: Microphone(
            sampleRateKHz: 16,
            levelDb: -31,
            bars: [
                0.14, 0.28, 0.46, 0.70, 0.52, 0.36, 0.62, 0.84,
                0.58, 0.30, 0.18, 0.40, 0.24, 0.12, 0.08, 0.16,
            ],
            transcript: Transcript(text: "are you okay", isFinal: true, confidence: 0.91)
        ),
        imu: IMU(rateHz: 100, pitch: 1.8, roll: -0.6, accel: 0.05, motionState: "Still"),
        face: Face(expression: "idle", speech: "quiet", lastEvent: "alert 2 min ago"),
        system: System(batteryPercent: 74, thermal: 0)
    )
}
