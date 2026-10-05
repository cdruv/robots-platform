import Foundation

/// Emits the design's snapshot with a little jitter so the tiles look alive.
final class PlaceholderLiveStreamService: LiveStreamService {
    func snapshots() -> AsyncStream<LiveSnapshot> {
        let (stream, continuation) = AsyncStream.makeStream(of: LiveSnapshot.self, bufferingPolicy: .bufferingNewest(1))
        let task = Task {
            var snapshot = LiveSnapshot()
            var tick = 0.0
            while !Task.isCancelled {
                continuation.yield(snapshot)
                try? await Task.sleep(for: .milliseconds(250))
                tick += 1
                snapshot.camera.frame += Int64(snapshot.camera.fps / 4)
                snapshot.camera.motion = 0.12 + 0.02 * sin(tick / 5)
                snapshot.imu.pitch = 1.8 + 0.3 * sin(tick / 7)
                snapshot.imu.roll = -0.6 + 0.4 * sin(tick / 11)
                snapshot.mic.bars.removeFirst()
                snapshot.mic.bars.append(0.08 + 0.8 * abs(sin(tick / 3) * sin(tick / 1.7)))
                snapshot.mic.levelDb = -31 + 3 * sin(tick / 4)
            }
        }
        continuation.onTermination = { _ in task.cancel() }
        return stream
    }

    func takeSnapshot() async {}
    func setRecording(_ isRecording: Bool) async {}
    func setMuted(_ isMuted: Bool) async {}
}
