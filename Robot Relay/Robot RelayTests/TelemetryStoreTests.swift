import Foundation
import Testing
@testable import Robot_Relay

/// Feeds events straight to `TelemetryStore.ingest`; the service is never started.
@MainActor
struct TelemetryStoreTests {
    private final class NoService: TelemetryService {
        func events() -> AsyncStream<TelemetryEvent> { AsyncStream { _ in } }
        func export(_ events: [TelemetryEvent]) async {}
    }

    private func event(_ seq: Int64, at seconds: Double = 0, kind: String = "log.info") -> TelemetryEvent {
        TelemetryEvent(seq: seq, tsWallMs: 0, tsMonoNs: Int64(seconds * 1e9), source: "body", kind: kind, payload: .object([:]))
    }

    private func store(_ events: [TelemetryEvent]) -> TelemetryStore {
        let store = TelemetryStore(service: NoService())
        events.forEach(store.ingest)
        return store
    }

    @Test func filterShowsOnlyEventsAtOrAboveTheLevel() {
        let store = store([event(1, kind: "Motion"), event(2), event(3, kind: "log.warn"), event(4, kind: "log.error")])
        #expect(store.visibleEvents.map(\.seq) == [1, 2, 3, 4])
        store.filter = .warnPlus
        #expect(store.visibleEvents.map(\.seq) == [3, 4])
        store.ingest(event(5))
        store.ingest(event(6, kind: "log.error"))
        #expect(store.visibleEvents.map(\.seq) == [3, 4, 6])
        store.filter = .error
        #expect(store.visibleEvents.map(\.seq) == [4, 6])
    }

    @Test func aLowerSeqStartsANewSession() {
        let store = store([event(1, at: 10), event(2, at: 11)])
        store.select(2)
        store.ingest(event(1, at: 50))
        #expect(store.events.map(\.seq) == [1])
        #expect(store.selectedSeq == nil)
        #expect(store.seconds(store.events[0]) == 0, "time counts from the new session's first event")
    }

    @Test func keepsTheNewestEventsUpToCapacity() {
        let store = store((1...Int64(TelemetryStore.capacity + 2)).map { event($0) })
        #expect(store.events.count == TelemetryStore.capacity)
        #expect(store.events.first?.seq == 3)
        #expect(store.visibleEvents.first?.seq == 3)
    }

    @Test func selectingPausesTheStreamAndCountsWhatArrives() {
        let store = store([event(1)])
        store.select(1)
        #expect(!store.followLive)
        store.ingest(event(2))
        store.ingest(event(3))
        #expect(store.behindCount == 2)
        store.setFollowLive(true)
        #expect(store.behindCount == 0)
    }

    @Test func relativeTimeShowsFewerDecimalsAsSecondsGrow() {
        let store = store([event(1), event(2, at: 1.5), event(3, at: 12.346), event(4, at: 123.46)])
        #expect(store.events.map(store.timeLabel(for:)) == ["+0.000", "+1.500", "+12.35", "+123.5"])
    }

    @Test func contextIsTwoInfoNeighboursEachSideWithinTheWindow() {
        let store = store([
            event(1, at: 0), event(2, at: 8.6), event(3, at: 9), event(4, at: 9.5, kind: "Motion"),
            event(5, at: 9.8), event(6, at: 10), event(7, at: 10.5), event(8, at: 11), event(9, at: 11.2),
        ])
        let context = store.context(around: store.events[5])
        #expect(context.map(\.event.seq) == [3, 5, 6, 7, 8], "skips debug and stops at two each side")
        let offsets: [Double] = [-1, -0.2, 0, 0.5, 1]
        #expect(zip(context.map(\.offset), offsets).allSatisfy { abs($0 - $1) < 1e-9 })
    }
}
