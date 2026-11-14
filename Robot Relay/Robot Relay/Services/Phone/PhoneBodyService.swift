import Foundation

/// The body link through the shared `PhoneConnection`: Mac → adb forward → phone → Pico.
final class PhoneBodyService: BodyService {
    private let connection: PhoneConnection
    private let stream = AsyncStream.makeStream(of: BodyLink?.self, bufferingPolicy: .bufferingNewest(1))

    init(connection: PhoneConnection) {
        self.connection = connection
        stream.continuation.yield(nil)
        connection.onBody = { [continuation = stream.continuation] link in continuation.yield(link) }
    }

    func states() -> AsyncStream<BodyLink?> { stream.stream }

    func drive(left: Double, right: Double) { send(.legs(left: left, right: right)) }

    func limp() { send(.limp) }

    func setTrim(_ steps: [Int]) { send(.trim(steps)) }

    func saveTrim() { send(.save) }

    private func send(_ command: BodyCommand) {
        if let line = command.line { connection.send(line) }
    }
}
