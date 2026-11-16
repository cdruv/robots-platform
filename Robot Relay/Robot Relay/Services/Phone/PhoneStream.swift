import Foundation
import os

/// What Relay reads from the phone's stream.
nonisolated enum PhoneMessage: Equatable, Sendable {
    case hello(PhoneHello)
    case pong(Int64)
    case event(TelemetryEvent)
    /// `/body/link` joined with the latest `/body/state` while connected.
    case body(BodyLink)
    /// The Wi-Fi listener's answer to Relay's key; it only ever sends refusals.
    case auth(ok: Bool, reason: String?)
}

/// Turns the phone's bytes into messages. After admission onboard-android's `TcpServerSink`
/// (protocol 4) sends an unindexed MCAP stream, whose topics are defined in its `Topics`;
/// a Wi-Fi refusal is a JSON line instead, so the first byte tells which it is. Topics Relay
/// does not show yet are skipped. One per connection.
///
/// The records are read by `McapRecords` rather than foxglove's Swift package, whose
/// repository needs Git LFS to resolve.
nonisolated struct PhoneStream {
    private enum Mode {
        case unknown
        case refusal(LineFramer)
        case mcap(McapRecords)
    }

    private var mode = Mode.unknown
    private var topics: [UInt16: String] = [:]
    private var link: LinkWire?
    private var pico: PicoBodyState?
    private let decoder = JSONDecoder()

    private struct LinkWire: Decodable {
        var phase: String
        var rttMs: Int?
    }

    private struct Pong: Decodable {
        var ping: Int64
    }

    private struct Auth: Decodable {
        var link: String
        var ok: Bool
        var reason: String?
    }

    /// Throws when the bytes are not a valid stream; the connection is unusable after that.
    mutating func append(_ data: Data) throws -> [PhoneMessage] {
        if case .unknown = mode, let first = data.first {
            mode = first == UInt8(ascii: "{") ? .refusal(LineFramer()) : .mcap(McapRecords())
        }
        switch mode {
        case .unknown:
            return []
        case .refusal(var framer):
            let lines = framer.append(data)
            mode = .refusal(framer)
            return lines.compactMap { line in
                (try? decoder.decode(Auth.self, from: line)).flatMap { $0.link == "auth" ? .auth(ok: $0.ok, reason: $0.reason) : nil }
            }
        case .mcap(var reader):
            let records = try reader.append(data)
            mode = .mcap(reader)
            return records.compactMap { handle($0) }
        }
    }

    private mutating func handle(_ record: McapRecords.Record) -> PhoneMessage? {
        switch record {
        case .metadata("hello", let metadata):
            return .hello(PhoneHello(metadata))
        case .metadata:
            return nil
        case .channel(let id, let topic):
            topics[id] = topic
            return nil
        case .message(let channel, let data):
            guard let topic = topics[channel] else { return nil }
            return decode(topic, data)
        }
    }

    private mutating func decode(_ topic: String, _ data: Data) -> PhoneMessage? {
        do {
            switch topic {
            case "/events":
                return .event(try decoder.decode(TelemetryEvent.self, from: data))
            case "/link/pong":
                return .pong(try decoder.decode(Pong.self, from: data).ping)
            case "/body/link":
                let link = try decoder.decode(LinkWire.self, from: data)
                self.link = link
                if link.phase != "connected" { pico = nil }
                return body()
            case "/body/state":
                pico = try decoder.decode(PicoBodyState.self, from: data)
                return link?.phase == "connected" ? body() : nil
            default:
                return nil
            }
        } catch {
            Logger.phone.error("phone: undecodable \(topic, privacy: .public) message (\(data.count, privacy: .public) bytes): \(String(decoding: data.prefix(200), as: UTF8.self))")
            return nil
        }
    }

    private func body() -> PhoneMessage? {
        guard let link else { return nil }
        return .body(BodyLink(phase: link.phase, pico: link.phase == "connected" ? pico : nil, rttMs: link.rttMs))
    }
}

/// Reads the records of an unindexed, unchunked MCAP stream (https://mcap.dev/spec): the magic,
/// then records of an opcode, a u64 length and the content, little-endian. Only the records Relay
/// uses are returned; the rest, including any chunk, are skipped.
nonisolated struct McapRecords: Sendable {
    enum Record: Equatable, Sendable {
        case metadata(name: String, [String: String])
        case channel(id: UInt16, topic: String)
        case message(channel: UInt16, data: Data)
    }

    enum Failure: Error, Equatable {
        case notMcap
        case malformed(opcode: UInt8)
        case tooLarge(UInt64)
    }

    static let magic = Data([0x89, 0x4D, 0x43, 0x41, 0x50, 0x30, 0x0D, 0x0A])
    /// Larger than any record the phone sends (a keyframe is well under 1 MB); anything bigger is a corrupt length.
    static let maxRecordBytes: UInt64 = 64 << 20

    private var buffer = Data()
    private var sawMagic = false

    /// The records completed by `data`. Throws when the bytes are not MCAP.
    mutating func append(_ data: Data) throws -> [Record] {
        buffer.append(data)
        var offset = buffer.startIndex
        if !sawMagic {
            let available = min(buffer.count, Self.magic.count)
            guard buffer.prefix(available).elementsEqual(Self.magic.prefix(available)) else { throw Failure.notMcap }
            guard available == Self.magic.count else { return [] }
            offset += Self.magic.count
            sawMagic = true
        }
        var records: [Record] = []
        while buffer.endIndex - offset >= 9 {
            let opcode = buffer[offset]
            var reader = Reader(data: buffer, offset: offset + 1)
            let length = reader.u64()!
            guard length <= Self.maxRecordBytes else { throw Failure.tooLarge(length) }
            let start = offset + 9
            guard buffer.endIndex - start >= Int(length) else { break }
            let end = start + Int(length)
            offset = end
            var content = Reader(data: buffer, offset: start, end: end)
            switch opcode {
            case 0x0C:
                guard let name = content.string(), let map = content.stringMap() else { throw Failure.malformed(opcode: opcode) }
                records.append(.metadata(name: name, map))
            case 0x04:
                guard let id = content.u16(), content.u16() != nil, let topic = content.string() else { throw Failure.malformed(opcode: opcode) }
                records.append(.channel(id: id, topic: topic))
            case 0x05:
                // channel u16, sequence u32, log time u64, publish time u64, then the data.
                guard let channel = content.u16(), content.skip(4 + 8 + 8) else { throw Failure.malformed(opcode: opcode) }
                records.append(.message(channel: channel, data: Data(buffer[content.offset..<end])))
            default:
                break
            }
        }
        buffer.removeSubrange(buffer.startIndex..<offset)
        return records
    }

    private struct Reader {
        let data: Data
        var offset: Int
        var end: Int

        init(data: Data, offset: Int, end: Int? = nil) {
            self.data = data
            self.offset = offset
            self.end = end ?? data.endIndex
        }

        mutating func skip(_ count: Int) -> Bool {
            guard end - offset >= count else { return false }
            offset += count
            return true
        }

        private mutating func uint(_ bytes: Int) -> UInt64? {
            guard end - offset >= bytes else { return nil }
            var value: UInt64 = 0
            for i in 0..<bytes { value |= UInt64(data[offset + i]) << (8 * i) }
            offset += bytes
            return value
        }

        mutating func u16() -> UInt16? { uint(2).map { UInt16($0) } }
        mutating func u32() -> UInt32? { uint(4).map { UInt32($0) } }
        mutating func u64() -> UInt64? { uint(8) }

        mutating func string() -> String? {
            guard let length = u32(), end - offset >= Int(length) else { return nil }
            let text = String(decoding: data[offset..<offset + Int(length)], as: UTF8.self)
            offset += Int(length)
            return text
        }

        mutating func stringMap() -> [String: String]? {
            guard let length = u32(), end - offset >= Int(length) else { return nil }
            var map = Reader(data: data, offset: offset, end: offset + Int(length))
            offset += Int(length)
            var result: [String: String] = [:]
            while map.offset < map.end {
                guard let key = map.string(), let value = map.string() else { return nil }
                result[key] = value
            }
            return result
        }
    }
}

/// The phone's `hello` Metadata record, first on every connection.
nonisolated struct PhoneHello: Equatable, Sendable {
    var `protocol`: Int?
    var device: String?
    var app: String?
    var session: String?
    var port: Int?
    /// SHA-256 of the certificate on the phone's Wi-Fi listener, lowercase hex; sent on USB only.
    var cert: String?

    init(protocol: Int? = nil, device: String? = nil, app: String? = nil, session: String? = nil, port: Int? = nil, cert: String? = nil) {
        self.protocol = `protocol`
        self.device = device
        self.app = app
        self.session = session
        self.port = port
        self.cert = cert
    }

    init(_ metadata: [String: String]) {
        self.init(protocol: metadata["protocol"].flatMap(Int.init), device: metadata["device"], app: metadata["app"],
                  session: metadata["session"], port: metadata["port"].flatMap(Int.init), cert: metadata["cert"])
    }

    /// `Google Pixel 8 · session 4f2c`
    var summary: String {
        [device, session.map { "session " + $0.prefix(4) }].compactMap { $0 }.joined(separator: " · ")
    }
}
