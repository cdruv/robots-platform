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

/// Stands in for `mpremote` until it is wired up: no device, no file, no console output.
final class UnavailableFirmwareService: FirmwareService {
    let deviceInfo: FirmwareDeviceInfo? = nil
    let file: FirmwareFile? = nil
    let storedOffsets: LegOffsets? = nil

    private let stream = AsyncStream<ConsoleLine> { _ in }

    func console() -> AsyncStream<ConsoleLine> { stream }

    func upload(_ file: FirmwareFile) -> AsyncStream<Double> {
        AsyncStream { $0.finish() }
    }

    func backUpDeviceCopy() async {}
    func arm(mode: ArmMode, repeatEveryBoot: Bool) async {}
    func writeOffsets(_ offsets: LegOffsets) async {}
    func centerLegs() async {}
    func sweepLegs(degrees: Double) async {}
    func openREPL() async {}
}
