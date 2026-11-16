import Foundation

/// The phone's protocol 4 stream as onboard-android's `TcpServerSink` writes it: magic, Header,
/// the `hello` Metadata, every Schema and Channel, then Messages. Records are encoded here
/// from the MCAP spec. Channel ids are not the phone's, as Relay must read them from the
/// Channel records.
enum PhoneStreamFixture {
    static let topics = ["/events", "/link/pong", "/camera/video", "/body/state", "/body/link"]

    static func preamble(session: String, device: String = "Pixel", _ extra: [String: String] = [:]) -> Data {
        var data = Data([0x89, 0x4D, 0x43, 0x41, 0x50, 0x30, 0x0D, 0x0A])
        data += record(0x01, string("") + string("walky"))
        let hello = ["protocol": "4", "device": device, "session": session].merging(extra) { $1 }
        data += record(0x0C, string("hello") + map(hello))
        for (i, topic) in topics.enumerated() {
            data += record(0x03, u16(id(i)) + string(topic) + string("jsonschema") + u32(2) + Data("{}".utf8))
        }
        for (i, topic) in topics.enumerated() {
            data += record(0x04, u16(id(i)) + u16(id(i)) + string(topic) + string("json") + map(["rate": "1"]))
        }
        return data
    }

    static func message(_ topic: String, _ payload: String) -> Data {
        message(topic, Data(payload.utf8))
    }

    static func message(_ topic: String, _ payload: Data) -> Data {
        record(0x05, u16(id(topics.firstIndex(of: topic)!)) + u32(1) + u64(1_000) + u64(2_000) + payload)
    }

    static func event(_ seq: Int64, tcpDropped: Int? = nil) -> Data {
        let payload = tcpDropped.map { #"{"sinks":{"tcp":\#($0)}}"# } ?? "{}"
        let kind = tcpDropped == nil ? #""source":"body","kind":"log.info""# : #""source":"telemetry","kind":"dropped""#
        return message("/events", #"{"seq":\#(seq),"tsWallMs":0,"tsMonoNs":0,\#(kind),"payload":\#(payload)}"#)
    }

    private static func id(_ index: Int) -> UInt16 { UInt16(10 + index) }

    static func record(_ opcode: UInt8, _ content: Data) -> Data {
        Data([opcode]) + u64(UInt64(content.count)) + content
    }

    private static func little<T: FixedWidthInteger>(_ value: T) -> Data {
        withUnsafeBytes(of: value.littleEndian) { Data($0) }
    }

    private static func u16(_ value: UInt16) -> Data { little(value) }
    private static func u32(_ value: UInt32) -> Data { little(value) }
    private static func u64(_ value: UInt64) -> Data { little(value) }
    private static func string(_ text: String) -> Data { u32(UInt32(text.utf8.count)) + Data(text.utf8) }

    private static func map(_ map: [String: String]) -> Data {
        let entries = map.sorted { $0.key < $1.key }.reduce(Data()) { $0 + string($1.key) + string($1.value) }
        return u32(UInt32(entries.count)) + entries
    }
}
