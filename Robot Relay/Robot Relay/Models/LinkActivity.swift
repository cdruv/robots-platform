import Foundation

/// One line of the popover's activity footer: a command the app ran, or the terminal
/// equivalent of what it did. Re-sending an entry with the same `id` updates it in place.
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

    /// Keeps the newest `limit` entries, replacing any entry that shares `id`.
    static func upsert(_ entry: LinkActivity, into entries: inout [LinkActivity], limit: Int = 4) {
        if let index = entries.firstIndex(where: { $0.id == entry.id }) {
            entries[index] = entry
        } else {
            entries.append(entry)
        }
        if entries.count > limit { entries.removeFirst(entries.count - limit) }
    }
}
