import Foundation
import Testing
@testable import Robot_Relay

struct PhoneLineTests {
    private func decode(_ line: String) -> PhoneLine? {
        PhoneLine.decode(Data(line.utf8))
    }

    @Test func helloLine() {
        let line = #"{"link":"hello","protocol":1,"device":"Google Pixel 8","app":"1.0","session":"4f2c9a1e","port":7777}"#
        guard case .hello(let hello) = decode(line) else {
            Issue.record("not a hello")
            return
        }
        #expect(hello.protocol == 1)
        #expect(hello.device == "Google Pixel 8")
        #expect(hello.session == "4f2c9a1e")
        #expect(hello.port == 7777)
        #expect(hello.summary == "Google Pixel 8 · session 4f2c")
    }

    @Test func pongLine() {
        #expect(decode(#"{"link":"pong","ping":42}"#) == .pong(42))
        #expect(decode(#"{"link":"pong"}"#) == nil)
    }

    @Test func unknownLinkLineIsNotAnEvent() {
        #expect(decode(#"{"link":"bye"}"#) == .otherLink("bye"))
    }

    @Test func eventLine() {
        let line = #"{"seq":7,"tsWallMs":1700000000123,"tsMonoNs":99,"source":"body","kind":"log.info","payload":{"msg":"hi"}}"#
        guard case .event(let event) = decode(line) else {
            Issue.record("not an event")
            return
        }
        #expect(event.seq == 7)
        #expect(event.source == "body")
        #expect(event.message == "hi")
    }

    @Test func garbage() {
        #expect(decode("not json") == nil)
        #expect(decode("[1,2]") == nil)
        #expect(decode(#"{"seq":"x"}"#) == nil)
        #expect(decode(#"{"link":"pong","ping":"x"}"#) == nil)
        #expect(decode("") == nil)
    }

    @Test func tcpDropsAreReadFromTheDroppedEvent() {
        let line = #"{"seq":1,"tsWallMs":0,"tsMonoNs":0,"source":"telemetry","kind":"dropped","payload":{"queue":0,"sinks":{"tcp":12,"logcat":3}}}"#
        guard case .event(let event) = decode(line) else {
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

    @Test func newSessionStartsOver() {
        var dedupe = SeqDedupe()
        _ = dedupe.begin(session: "a")
        _ = dedupe.admit(500)
        let restarted = dedupe.begin(session: "b")
        let first = dedupe.admit(1)
        #expect(restarted)
        #expect(first)
    }

    @Test func unknownSessionAlwaysStartsOver() {
        var dedupe = SeqDedupe()
        _ = dedupe.begin(session: nil)
        _ = dedupe.admit(500)
        let restarted = dedupe.begin(session: nil)
        let first = dedupe.admit(1)
        #expect(restarted)
        #expect(first)
    }
}

struct AdbTests {
    @Test func picksOnlyOnlineDevices() {
        let output = """
        List of devices attached
        3A111FDJH000AB\tdevice
        emulator-5554\toffline
        R58M\tunauthorized

        """
        let serials = Adb.onlineSerials(fromDevicesOutput: output)
        #expect(serials == ["3A111FDJH000AB"])
        #expect(Adb.judgeSelection(serials) == .ok("3A111FDJH000AB"))
    }

    @Test func noneOrSeveralIsAFailureNamingThem() {
        #expect(Adb.onlineSerials(fromDevicesOutput: "List of devices attached\n\n").isEmpty)
        #expect(Adb.judgeSelection([]) == .failed("no online device · is USB debugging authorized?"))
        let several = Adb.onlineSerials(fromDevicesOutput: "List of devices attached\nA\tdevice\nB\tdevice\n")
        #expect(Adb.judgeSelection(several) == .failed("several devices: A, B"))
    }

    @Test func commandsDisplayAsTyped() {
        #expect(CommandRunner.displayCommand("adb", ["-s", "3A1", "forward", "tcp:7777", "tcp:7777"])
            == "adb -s 3A1 forward tcp:7777 tcp:7777")
        #expect(CommandRunner.displayCommand("mpremote", ["fs", "cp", "a b.py", ":"]) == "mpremote fs cp 'a b.py' :")
    }
}

struct PhoneAddressTests {
    @Test func parsesHostAndPort() {
        #expect(PhoneAddress("10.0.0.42:7777") == PhoneAddress(host: "10.0.0.42", port: 7777))
        #expect(PhoneAddress(" pixel.local ")?.port == 7777)
        #expect(PhoneAddress("localhost:9000")?.isLoopback == true)
        #expect(PhoneAddress("") == nil)
        #expect(PhoneAddress("host:") == nil)
        #expect(PhoneAddress("host:0") == nil)
        #expect(PhoneAddress("host:70000") == nil)
        #expect(PhoneAddress("a:b:c") == nil)
        #expect(PhoneAddress("has space:1") == nil)
    }

    @Test func transportFollowsTheAddress() {
        #expect(PhoneLink(address: "127.0.0.1:7777").transport == "USB · adb")
        #expect(PhoneLink(address: "10.0.0.42:7777").transport == "Wi‑Fi")
        #expect(PhoneLink().transport == "—")
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
