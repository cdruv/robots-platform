import Foundation
import Testing
@testable import Robot_Relay

/// Feeds lines straight to `PhoneConnection`; nothing opens a socket.
@MainActor
struct PhoneConnectionTests {
    private func hello(_ session: String) -> Data {
        Data(#"{"link":"hello","device":"Pixel","session":"\#(session)"}"#.utf8)
    }

    private func event(_ seq: Int64, tcpDropped: Int? = nil) -> Data {
        let payload = tcpDropped.map { #"{"sinks":{"tcp":\#($0)}}"# } ?? "{}"
        let kind = tcpDropped == nil ? #""source":"body","kind":"log.info""# : #""source":"telemetry","kind":"dropped""#
        return Data(#"{"seq":\#(seq),"tsWallMs":0,"tsMonoNs":0,\#(kind),"payload":\#(payload)}"#.utf8)
    }

    @Test func aReplayAfterReconnectIsDeliveredOnce() async {
        let connection = PhoneConnection()
        var activity: [String] = []
        connection.onActivity = { activity.append($0.text) }
        connection.handle(line: hello("abcd1234"))
        (1...3).forEach { connection.handle(line: event($0)) }
        // Reconnect in the same session: the phone replays 2...3 before 4.
        connection.handle(line: hello("abcd1234"))
        (2...4).forEach { connection.handle(line: event($0)) }

        var events = connection.telemetry().makeAsyncIterator()
        var seqs: [Int64] = []
        for _ in 0..<4 { if let event = await events.next() { seqs.append(event.seq) } }
        #expect(seqs == [1, 2, 3, 4])
        #expect(connection.status.hello?.session == "abcd1234")
        #expect(activity == ["← hello Pixel · session abcd", "← hello Pixel · session abcd"])
    }

    @Test func droppedCountAddsUpAndResetsWithANewSession() {
        let connection = PhoneConnection()
        connection.handle(line: hello("a"))
        connection.handle(line: event(1, tcpDropped: 12))
        connection.handle(line: event(2, tcpDropped: 3))
        #expect(connection.status.dropped == 15)
        connection.handle(line: hello("b"))
        #expect(connection.status.dropped == 0)
        connection.handle(line: event(1, tcpDropped: 4))
        #expect(connection.status.dropped == 4, "seq 1 is new in session b")
    }

    @Test func ignoresPongsForUnsentPingsAndGarbage() {
        let connection = PhoneConnection()
        connection.handle(line: Data(#"{"link":"pong","ping":1}"#.utf8))
        connection.handle(line: Data("not json".utf8))
        #expect(connection.status == PhoneConnection.Status())
    }

    @Test func retriesEvery3SecondsThenGivesUpAfter4Attempts() {
        #expect((1..<PhoneConnection.maxAttempts).map(PhoneConnection.retryDelay(afterFailures:)) == [.seconds(3), .seconds(3), .seconds(3)])
        #expect(PhoneConnection.retryDelay(afterFailures: PhoneConnection.maxAttempts) == nil)
    }
}
