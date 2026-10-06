import Foundation

/// Live telemetry from the shared `PhoneConnection`. Terminal equivalent:
/// `scripts/relay-phone.sh tail --pretty`.
final class PhoneTelemetryService: TelemetryService {
    private let connection: PhoneConnection

    init(connection: PhoneConnection) {
        self.connection = connection
    }

    func events() -> AsyncStream<TelemetryEvent> { connection.telemetry() }

    func export(_ events: [TelemetryEvent]) async {}
}
