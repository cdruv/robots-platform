import Foundation

/// Stands in for live streams that have no transport yet: delivers no data, so every tile
/// shows dashes rather than made-up values.
final class UnavailableLiveStreamService: LiveStreamService {
    func snapshots() -> AsyncStream<LiveSnapshot> {
        let (stream, continuation) = AsyncStream.makeStream(of: LiveSnapshot.self)
        continuation.yield(LiveSnapshot())
        return stream
    }

    func takeSnapshot() async {}
    func setRecording(_ isRecording: Bool) async {}
    func setMuted(_ isMuted: Bool) async {}
}
