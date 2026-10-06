import Foundation

/// Number formatting shared by the views. The design uses a true minus sign and
/// space-grouped thousands.
nonisolated enum Fmt {
    /// `+1.8`, `−0.6`.
    static func signed(_ value: Double, decimals: Int = 1) -> String {
        let magnitude = String(format: "%.\(decimals)f", abs(value))
        return (value < 0 ? "−" : "+") + magnitude
    }

    /// `48 211`.
    static func grouped(_ value: Int64) -> String {
        let digits = String(abs(value))
        var out = ""
        for (index, character) in digits.enumerated() {
            if index > 0 && (digits.count - index) % 3 == 0 { out.append(" ") }
            out.append(character)
        }
        return (value < 0 ? "−" : "") + out
    }

    /// `74` for whole numbers, `12.4` otherwise.
    static func trimmed(_ value: Double) -> String {
        value == value.rounded() && abs(value) < 1e15
            ? String(Int64(value))
            : String(format: "%g", value)
    }

    /// The value, or an em dash when there is no data.
    static func dash<Value: CustomStringConvertible>(_ value: Value?) -> String {
        value.map { "\($0)" } ?? "—"
    }

    /// `2 min ago`.
    static func ago(_ date: Date, now: Date = .now) -> String {
        let seconds = max(0, Int(now.timeIntervalSince(date)))
        switch seconds {
        case ..<60: return "just now"
        case ..<3600: return "\(seconds / 60) min ago"
        default: return "\(seconds / 3600) h ago"
        }
    }
}
