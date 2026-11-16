import Foundation
import Testing
@testable import Robot_Relay

/// Feeds the phone’s stream straight to `PhoneConnection`; nothing opens a socket.
@MainActor
struct PhoneConnectionTests {
    private func hello(_ session: String) -> Data { PhoneStreamFixture.preamble(session: session) }
    private func event(_ seq: Int64, tcpDropped: Int? = nil) -> Data { PhoneStreamFixture.event(seq, tcpDropped: tcpDropped) }

    @Test func aReplayAfterReconnectIsDeliveredOnce() async {
        let connection = PhoneConnection()
        var activity: [String] = []
        connection.onActivity = { activity.append($0.text) }
        connection.handle(data: hello("abcd1234"))
        (1...3).forEach { connection.handle(data: event($0)) }
        // Reconnect in the same session: the phone replays 2...3 before 4.
        connection.startStream()
        connection.handle(data: hello("abcd1234"))
        (2...4).forEach { connection.handle(data: event($0)) }

        var events = connection.telemetry().makeAsyncIterator()
        var seqs: [Int64] = []
        for _ in 0..<4 { if let event = await events.next() { seqs.append(event.seq) } }
        #expect(seqs == [1, 2, 3, 4])
        #expect(connection.status.hello?.session == "abcd1234")
        #expect(activity == ["Walky connected · hello Pixel · session abcd", "Walky connected · hello Pixel · session abcd"])
    }

    @Test func droppedCountAddsUpAndResetsWithANewSession() {
        let connection = PhoneConnection()
        connection.handle(data: hello("a"))
        connection.handle(data: event(1, tcpDropped: 12))
        connection.handle(data: event(2, tcpDropped: 3))
        #expect(connection.status.dropped == 15)
        connection.startStream()
        connection.handle(data: hello("b"))
        #expect(connection.status.dropped == 0)
        connection.handle(data: event(1, tcpDropped: 4))
        #expect(connection.status.dropped == 4, "seq 1 is new in session b")
    }

    @Test func aKeyRefusalNeverConnectsAndAsksForUSBPairing() {
        let connection = PhoneConnection()
        var activity: [LinkActivity] = []
        connection.onActivity = { activity.append($0) }
        connection.handle(data: Data(#"{"link":"auth","ok":false,"reason":"denied"}"#.utf8) + Data("\n".utf8))
        #expect(connection.status.state != .connected)
        #expect(connection.status.hello == nil)
        guard case .failed(let detail)? = activity.last?.state else {
            Issue.record("no failure reported")
            return
        }
        #expect(detail.contains(PhoneConnection.pairHint))
    }

    @Test func withNoRouteItWaitsThenTriesAgainWhenThePhoneAppears() async {
        let connection = PhoneConnection(retryInterval: .milliseconds(1))
        var asked = 0
        connection.connect { asked += 1; return nil }
        await until { connection.status.state == .waiting }
        #expect(connection.status.state == .waiting)
        #expect(asked == PhoneConnection.maxAttempts)

        connection.routeAppeared()
        await until { asked == 2 * PhoneConnection.maxAttempts && connection.status.state == .waiting }
        #expect(asked == 2 * PhoneConnection.maxAttempts)
        connection.disconnect()
        #expect(connection.status.state == .disconnected)
    }

    @Test func routeAppearingIsIgnoredUnlessWaiting() {
        let connection = PhoneConnection()
        connection.routeAppeared()
        #expect(connection.status.state == .disconnected)
    }

    private func until(_ condition: () -> Bool) async {
        for _ in 0..<200 where !condition() {
            try? await Task.sleep(for: .milliseconds(10))
        }
    }

    @Test func ignoresPongsForUnsentPings() {
        let connection = PhoneConnection()
        connection.handle(data: hello("a"))
        let connected = connection.status
        connection.handle(data: PhoneStreamFixture.message("/link/pong", #"{"ping":1}"#))
        #expect(connection.status == connected)
        #expect(connected.rttMs == nil)
    }
}
