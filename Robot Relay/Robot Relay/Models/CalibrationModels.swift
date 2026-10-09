import Foundation

nonisolated struct CalibrationSnapshot: Codable, Equatable, Sendable {
    var version: Int
    var board: String
    var steps: [Int]?
    var stored: Bool?
    var error: String?
    var offsets: LegOffsets? { steps.flatMap(LegOffsets.init(steps:)) }
    var supported: Bool { version == 1 && !board.isEmpty && error == nil && offsets != nil }
}

extension LegOffsets {
    nonisolated init?(steps: [Int]) {
        guard steps.count == 2, steps.allSatisfy({ (-18...18).contains($0) }) else { return nil }
        self.init(left: Double(steps[0]) / 2, right: Double(steps[1]) / 2)
    }
    nonisolated var steps: [Int]? {
        let values = [left, right]
        guard values.allSatisfy({ $0.isFinite && Self.range.contains($0) && ($0 * 2).rounded() == $0 * 2 }) else { return nil }
        return values.map { Int($0 * 2) }
    }
}

nonisolated struct CalibrationTicket: Codable, Sendable {
    var version = 1
    var board: String
    var session: String
    var ssid: String
    var password: String

    init(board: String) {
        self.board = board
        session = UUID().uuidString.replacingOccurrences(of: "-", with: "")
        password = UUID().uuidString.replacingOccurrences(of: "-", with: "")
        ssid = "Walky-Cal-" + String(session.prefix(8))
    }
}

nonisolated struct CalibrationReply: Codable, Sendable {
    var version: Int
    var board: String
    var session: String
    var id: Int
    var saved: [Int]
    var preview: [Int]
    var stored: Bool
    var pulses: [Int]
    var finished: Bool

    func validate(ticket: CalibrationTicket, id: Int) throws {
        guard version == 1, board == ticket.board, session == ticket.session, self.id == id,
              LegOffsets(steps: saved) != nil, let offsets = LegOffsets(steps: preview),
              pulses == [offsets.left, offsets.right].map({ ServoMath.pulseMicros(offsetDegrees: $0) }) else {
            throw CalibrationFailure("Invalid calibration acknowledgement")
        }
    }
}

nonisolated struct CalibrationFailure: LocalizedError {
    var message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}
