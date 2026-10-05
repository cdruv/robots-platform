import Foundation
import Observation

@Observable
final class TelemetryStore {
    enum Filter {
        case all, warnPlus, error

        var minimumRank: Int {
            switch self {
            case .all: 0
            case .warnPlus: TelemetryEvent.Level.warn.rank
            case .error: TelemetryEvent.Level.error.rank
            }
        }
    }

    enum TimeMode {
        case relative, clock
    }

    /// Oldest events are discarded past this many.
    static let capacity = 5000
    /// Window used for "Around this event".
    static let contextWindow = 1.5

    private(set) var events: [TelemetryEvent] = []
    /// `events` after the severity filter.
    private(set) var visibleEvents: [TelemetryEvent] = []
    var filter: Filter = .all {
        didSet { visibleEvents = events.filter(passesFilter) }
    }
    var timeMode: TimeMode = .relative
    private(set) var selectedSeq: Int64?
    /// When false the stream stays where the user left it and counts what arrives.
    private(set) var followLive = true
    private(set) var behindCount = 0

    private let service: any TelemetryService
    private var task: Task<Void, Never>?
    private var originMonoNs: Int64?

    init(service: any TelemetryService) {
        self.service = service
    }

    func start() {
        guard task == nil else { return }
        task = Task { [weak self, service] in
            for await event in service.events() {
                self?.ingest(event)
            }
        }
    }

    var lastSeq: Int64? { events.last?.seq }

    var selectedEvent: TelemetryEvent? {
        guard let selectedSeq else { return nil }
        return events.first { $0.seq == selectedSeq }
    }

    func select(_ seq: Int64) {
        selectedSeq = seq
        setFollowLive(false)
    }

    func setFollowLive(_ follow: Bool) {
        guard follow != followLive else { return }
        followLive = follow
        behindCount = 0
    }

    func export() {
        Task { await service.export(visibleEvents) }
    }

    // MARK: Presentation

    /// Seconds since the first event of the session.
    func seconds(_ event: TelemetryEvent) -> Double {
        Double(event.tsMonoNs - (originMonoNs ?? event.tsMonoNs)) / 1e9
    }

    func timeLabel(for event: TelemetryEvent) -> String {
        switch timeMode {
        case .relative:
            let seconds = seconds(event)
            let format = seconds < 10 ? "+%.3f" : seconds < 100 ? "+%.2f" : "+%.1f"
            return String(format: format, seconds)
        case .clock:
            return Self.clockFormatter.string(from: event.wallDate)
        }
    }

    func clockLabel(for event: TelemetryEvent) -> String {
        Self.clockFormatter.string(from: event.wallDate)
    }

    /// Up to two info-or-higher neighbours each side of `event`, within ±`contextWindow` seconds.
    func context(around event: TelemetryEvent) -> [(offset: Double, event: TelemetryEvent)] {
        guard let index = events.firstIndex(where: { $0.seq == event.seq }) else { return [] }
        let center = seconds(event)
        func isNear(_ other: TelemetryEvent) -> Bool {
            other.level.rank >= TelemetryEvent.Level.info.rank && abs(seconds(other) - center) <= Self.contextWindow
        }
        let before = events[..<index].reversed().prefix { abs(seconds($0) - center) <= Self.contextWindow }
            .filter(isNear).prefix(2).reversed()
        let after = events[(index + 1)...].prefix { abs(seconds($0) - center) <= Self.contextWindow }
            .filter(isNear).prefix(2)
        return (Array(before) + [event] + Array(after)).map { (seconds($0) - center, $0) }
    }

    func json(for event: TelemetryEvent) -> String {
        JSONValue.object([
            "seq": .number(Double(event.seq)),
            "tsWallMs": .number(Double(event.tsWallMs)),
            "tsMonoNs": .number(Double(event.tsMonoNs)),
            "source": .string(event.source),
            "kind": .string(event.kind),
            "payload": event.payload,
        ]).prettyPrinted()
    }

    // MARK: Private

    private func ingest(_ event: TelemetryEvent) {
        if originMonoNs == nil { originMonoNs = event.tsMonoNs }
        events.append(event)
        if passesFilter(event) { visibleEvents.append(event) }
        if events.count > Self.capacity {
            events.removeFirst(events.count - Self.capacity)
            if let oldest = events.first?.seq {
                visibleEvents.removeAll { $0.seq < oldest }
            }
        }
        if !followLive { behindCount += 1 }
    }

    private func passesFilter(_ event: TelemetryEvent) -> Bool {
        event.level.rank >= filter.minimumRank
    }

    private static let clockFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss.SSS"
        return formatter
    }()
}
