import Foundation

/// One line of the popover's activity log: a command the app ran, or the terminal
/// equivalent of what it did. Lines are only appended, so the log stays in time order.
nonisolated struct LinkActivity: Identifiable, Equatable, Sendable {
    enum State: Equatable, Sendable {
        case running
        case ok(String?)
        case failed(String)
    }

    var id = UUID()
    var date = Date.now
    var text: String
    var state: State

    /// Appends `entry`, keeping the newest `limit` entries.
    static func append(_ entry: LinkActivity, to entries: inout [LinkActivity], limit: Int = 500) {
        entries.append(entry)
        if entries.count > limit { entries.removeFirst(entries.count - limit) }
    }
}
