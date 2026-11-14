import Foundation

/// A connected, limp body that ignores commands.
final class PlaceholderBodyService: BodyService {
    func states() -> AsyncStream<BodyLink?> {
        let (stream, continuation) = AsyncStream.makeStream(of: BodyLink?.self)
        let pico = PicoBodyState(board: "e66118604b", mode: .limp, target: [0, 0], pulses: nil,
                                 trim: [-4, 3], saved: [-4, 3], stored: true, error: nil)
        continuation.yield(BodyLink(phase: "connected", pico: pico, rttMs: 12))
        return stream
    }

    func drive(left: Double, right: Double) {}
    func limp() {}
    func setTrim(_ steps: [Int]) {}
    func saveTrim() {}
}
