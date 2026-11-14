import Foundation

nonisolated struct CalibrationSnapshot: Codable, Equatable, Sendable {
    var version: Int
    var board: String
    var steps: [Int]?
    var stored: Bool?
    var error: String?
    var offsets: LegOffsets? { steps.flatMap(LegOffsets.init(steps:)) }
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
