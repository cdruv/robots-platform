import Foundation

/// One telemetry line, matching `TelemetryEvent` in
/// `Walky/onboard-android/core/.../telemetry/Telemetry.kt` (NDJSON over TCP :7777).
nonisolated struct TelemetryEvent: Codable, Identifiable, Hashable, Sendable {
    let seq: Int64
    let tsWallMs: Int64
    let tsMonoNs: Int64
    let source: String
    let kind: String
    let payload: JSONValue

    var id: Int64 { seq }

    var wallDate: Date { Date(timeIntervalSince1970: Double(tsWallMs) / 1000) }
}

extension TelemetryEvent {
    nonisolated enum Level: Sendable {
        case debug, info, heard, warn, error

        /// Ordering used by the severity filter.
        var rank: Int {
            switch self {
            case .debug: 0
            case .info, .heard: 1
            case .warn: 2
            case .error: 3
            }
        }

        var label: String {
            switch self {
            case .debug: "DEBUG"
            case .info: "INFO"
            case .heard: "HEARD"
            case .warn: "WARN"
            case .error: "ERROR"
            }
        }
    }

    nonisolated var level: Level {
        switch kind {
        case "log.error": .error
        case "log.warn": .warn
        case "log.info": .info
        case "Heard": .heard
        case "Motion", "SystemStatus": .debug
        default: .info
        }
    }

    /// One-line presentation for the stream.
    nonisolated var message: String {
        if let msg = payload["msg"]?.stringValue { return msg }
        switch kind {
        case "Motion":
            let state = payload["state"]?.stringValue ?? "?"
            return "Motion  \(state)  pitch \(field("pitch"))  roll \(field("roll"))  mag \(field("mag"))"
        case "SystemStatus":
            return "SystemStatus  battery \(field("battery"))%  thermal \(field("thermal"))"
        case "Heard":
            return "HEARD  “\(payload["text"]?.stringValue ?? "")”"
        case "SpeechStarted", "SpeechFinished":
            return "\(kind) \(field("utteranceId"))"
        default:
            guard case .object(let object) = payload, !object.isEmpty else { return kind }
            let pairs = object.keys.sorted().map { "\($0) \(object[$0]?.displayString ?? "")" }
            return "\(kind)  \(pairs.joined(separator: "  "))"
        }
    }

    private nonisolated func field(_ key: String) -> String {
        payload[key]?.displayString ?? "?"
    }
}

/// Untyped JSON for event payloads.
nonisolated enum JSONValue: Codable, Hashable, Sendable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? container.decode(Double.self) {
            self = .number(value)
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode([JSONValue].self) {
            self = .array(value)
        } else {
            self = .object(try container.decode([String: JSONValue].self))
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .null: try container.encodeNil()
        case .bool(let value): try container.encode(value)
        case .number(let value): try container.encode(value)
        case .string(let value): try container.encode(value)
        case .array(let value): try container.encode(value)
        case .object(let value): try container.encode(value)
        }
    }

    subscript(key: String) -> JSONValue? {
        if case .object(let object) = self { return object[key] }
        return nil
    }

    var stringValue: String? {
        if case .string(let value) = self { return value }
        return nil
    }

    /// Compact form for stream lines: strings unquoted, whole numbers without a fraction.
    var displayString: String {
        switch self {
        case .null: "null"
        case .bool(let value): value ? "true" : "false"
        case .number(let value): Fmt.trimmed(value)
        case .string(let value): value
        case .array, .object: prettyPrinted(indent: 0).replacingOccurrences(of: "\n", with: " ")
        }
    }

    /// Two-space-indented JSON. `msg` sorts first so log payloads read naturally.
    func prettyPrinted(indent: Int = 0) -> String {
        let pad = String(repeating: "  ", count: indent)
        let inner = String(repeating: "  ", count: indent + 1)
        switch self {
        case .null: return "null"
        case .bool(let value): return value ? "true" : "false"
        case .number(let value): return Fmt.trimmed(value)
        case .string(let value): return Self.quoted(value)
        case .array(let values):
            if values.isEmpty { return "[]" }
            let lines = values.map { inner + $0.prettyPrinted(indent: indent + 1) }
            return "[\n" + lines.joined(separator: ",\n") + "\n" + pad + "]"
        case .object(let object):
            if object.isEmpty { return "{}" }
            let keys = object.keys.sorted { lhs, rhs in
                if (lhs == "msg") != (rhs == "msg") { return lhs == "msg" }
                return lhs < rhs
            }
            let lines = keys.map { key in
                inner + Self.quoted(key) + ": " + (object[key]?.prettyPrinted(indent: indent + 1) ?? "null")
            }
            return "{\n" + lines.joined(separator: ",\n") + "\n" + pad + "}"
        }
    }

    private static func quoted(_ string: String) -> String {
        let escaped = string
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
            .replacingOccurrences(of: "\n", with: "\\n")
        return "\"" + escaped + "\""
    }
}
