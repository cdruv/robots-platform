import Foundation
import Testing
@testable import Robot_Relay

struct PhoneStreamTests {
    private func read(_ chunks: Data...) throws -> [PhoneMessage] {
        var stream = PhoneStream()
        return try chunks.flatMap { try stream.append($0) }
    }

    @Test func helloComesFromTheMetadataRecord() throws {
        let messages = try read(PhoneStreamFixture.preamble(
            session: "4f2c9a1e", device: "Google Pixel 8", ["app": "1.0", "port": "7777", "cert": "75a342"]))
        guard case .hello(let hello)? = messages.first, messages.count == 1 else {
            Issue.record("not one hello: \(messages)")
            return
        }
        #expect(hello == PhoneHello(protocol: 4, device: "Google Pixel 8", app: "1.0", session: "4f2c9a1e", port: 7777, cert: "75a342"))
        #expect(hello.summary == "Google Pixel 8 · session 4f2c")
    }

    @Test func messagesAreRoutedByTheirChannelsTopic() throws {
        let messages = try read(
            PhoneStreamFixture.preamble(session: "a"),
            PhoneStreamFixture.message("/events", #"{"seq":7,"tsWallMs":1700000000123,"tsMonoNs":99,"source":"body","kind":"log.info","payload":{"msg":"hi"}}"#),
            PhoneStreamFixture.message("/link/pong", #"{"ping":42}"#),
            // Not shown yet: skipped, whatever its bytes.
            PhoneStreamFixture.message("/camera/video", Data([0x0A, 0x00, 0xFF])),
        )
        #expect(messages.count == 3)
        guard messages.count == 3, case .event(let event) = messages[1] else {
            Issue.record("no event: \(messages)")
            return
        }
        #expect(event.seq == 7 && event.source == "body" && event.message == "hi")
        #expect(messages[2] == .pong(42))
    }

    @Test func recordsSplitAcrossReadsAreJoined() throws {
        let bytes = PhoneStreamFixture.preamble(session: "a") + PhoneStreamFixture.message("/link/pong", #"{"ping":1}"#)
        var stream = PhoneStream()
        var messages: [PhoneMessage] = []
        for i in stride(from: 0, to: bytes.count, by: 7) {
            messages += try stream.append(bytes.subdata(in: i..<min(i + 7, bytes.count)))
        }
        #expect(messages.last == .pong(1))
    }

    @Test func undecodableMessagesAreSkippedAndTheStreamGoesOn() throws {
        let messages = try read(
            PhoneStreamFixture.preamble(session: "a"),
            PhoneStreamFixture.message("/link/pong", #"{"ping":"x"}"#),
            PhoneStreamFixture.message("/events", "not json"),
            PhoneStreamFixture.message("/link/pong", #"{"ping":2}"#),
        )
        #expect(Array(messages.dropFirst()) == [.pong(2)])
    }

    @Test func otherRecordsAreSkippedAndACorruptLengthIsAnError() throws {
        let preamble = PhoneStreamFixture.preamble(session: "a")
        let skipped = try read(preamble, PhoneStreamFixture.record(0x0F, Data(repeating: 0, count: 5)), PhoneStreamFixture.message("/link/pong", #"{"ping":3}"#))
        #expect(skipped.last == .pong(3))
        let huge = Data([0x05]) + withUnsafeBytes(of: UInt64(1 << 40).littleEndian) { Data($0) }
        #expect(throws: McapRecords.Failure.tooLarge(1 << 40)) { try read(preamble, huge) }
    }

    @Test func aRefusalIsAJsonLineInsteadOfTheStream() throws {
        #expect(try read(Data(#"{"link":"auth","ok":false,"reason":"unpaired"}"#.utf8) + Data("\n".utf8)) == [.auth(ok: false, reason: "unpaired")])
    }

    @Test func anythingElseIsNotTelemetry() {
        #expect(throws: (any Error).self) { try read(Data("hello, world, this is not MCAP\n".utf8)) }
    }

    @Test func tcpDropsAreReadFromTheDroppedEvent() throws {
        let messages = try read(PhoneStreamFixture.preamble(session: "a"), PhoneStreamFixture.event(1, tcpDropped: 12))
        guard case .event(let event)? = messages.last else {
            Issue.record("not an event")
            return
        }
        #expect(event.tcpDropped == 12)
    }
}

struct LineFramerTests {
    @Test func splitsAcrossChunksAndStripsCarriageReturns() {
        var framer = LineFramer()
        let first = framer.append(Data("{\"a\":1}\n{\"b\"".utf8))
        let second = framer.append(Data(":2}\r\n\n".utf8))
        #expect(first == [Data("{\"a\":1}".utf8)])
        #expect(second == [Data("{\"b\":2}".utf8)])
    }

    @Test func discardsOversizedLinesWhole() {
        var framer = LineFramer(maxLineBytes: 8)
        let first = framer.append(Data("0123456789".utf8))
        let second = framer.append(Data("tail\nok\n".utf8))
        #expect(first.isEmpty)
        #expect(second == [Data("ok".utf8)])
    }
}

struct SeqDedupeTests {
    @Test func reconnectWithinASessionSkipsTheReplay() {
        var dedupe = SeqDedupe()
        let started = dedupe.begin(session: "a")
        let admitted = (1...5).map { dedupe.admit($0) }
        // Reconnect: same session, the server replays 3...5 before 6.
        let restarted = dedupe.begin(session: "a")
        let replayed = [3, 4, 5].map { dedupe.admit($0) }
        let live = dedupe.admit(6)
        #expect(started)
        #expect(admitted.allSatisfy { $0 })
        #expect(!restarted)
        #expect(replayed.allSatisfy { !$0 })
        #expect(live)
    }

    /// A new session, or an unknown one, restarts seq.
    @Test(arguments: [("a", "b"), (nil, nil)] as [(String?, String?)])
    func anotherSessionStartsOver(first: String?, next: String?) {
        var dedupe = SeqDedupe()
        _ = dedupe.begin(session: first)
        _ = dedupe.admit(500)
        let restarted = dedupe.begin(session: next)
        let admitted = dedupe.admit(1)
        #expect(restarted)
        #expect(admitted)
    }
}

struct HelloLinesTests {
    private let pairing = PicoPairing(ssid: "walky-pico", password: "secret")
    private let key = Data(repeating: 7, count: 32)

    private func json(_ line: Data) -> [String: String]? {
        try? JSONDecoder().decode([String: String].self, from: line)
    }

    @Test func usbCarriesThePicoPairingAndTheKey() {
        let lines = PhoneLinkService.helloLines(route: .usb, pairing: pairing, key: key).compactMap(json)
        #expect(lines.count == 2)
        #expect(lines.first?["link"] == "pair")
        #expect(lines.first?["password"] == "secret")
        #expect(lines.last == ["link": "key", "key": key.base64EncodedString()])
    }

    @Test func wifiCarriesNoSecrets() {
        #expect(PhoneLinkService.helloLines(route: .wifi, pairing: pairing, key: key).isEmpty)
    }
}

struct AdbTests {
    @Test func explainsUSBSetupFailures() {
        for (error, hint) in [("error: device unauthorized", "allow USB debugging"),
                              ("error: no devices found", "Check the cable"),
                              ("error: more than one device", "Connect only the Walky phone")] {
            let result = CommandResult(status: 1, stdout: "", stderr: error)
            #expect(Adb.failureMessage(result).contains(hint))
        }
    }

    @Test func commandsDisplayAsTyped() {
        #expect(CommandRunner.displayCommand("adb", ["-s", "3A1", "forward", "tcp:7777", "tcp:7777"])
            == "adb -s 3A1 forward tcp:7777 tcp:7777")
        #expect(CommandRunner.displayCommand("mpremote", ["fs", "cp", "a b.py", ":"]) == "mpremote fs cp 'a b.py' :")
    }
}

struct LinkActivityTests {
    @Test func appendsAndKeepsTheNewestFour() {
        var entries: [LinkActivity] = []
        LinkActivity.append(LinkActivity(text: "nc 10.0.0.42 7777", state: .running), to: &entries, limit: 4)
        LinkActivity.append(LinkActivity(text: "nc 10.0.0.42 7777", state: .failed("refused · retry in 1 s")), to: &entries, limit: 4)
        #expect(entries.map(\.state) == [.running, .failed("refused · retry in 1 s")])

        for index in 0..<4 {
            LinkActivity.append(LinkActivity(text: "\(index)", state: .ok(nil)), to: &entries, limit: 4)
        }
        #expect(entries.map(\.text) == ["0", "1", "2", "3"])
    }
}

struct TestHostTests {
    /// The host app picks placeholder services from this; if Xcode stops setting it, live ones start.
    @Test func runsInsideTheTestHost() {
        #expect(RobotServices.isTestHost)
    }
}
