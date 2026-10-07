import Foundation
import Network

/// The TCP link to onboard-android's telemetry server (`TcpServerSink`, NDJSON on :7777).
///
/// Reconnects every 3 s while the user wants to be connected, and gives up after
/// `maxAttempts` attempts in a row (the first plus 3 retries) end without a hello. Each
/// connection starts with a hello line and a replay of recent events; replayed events already
/// seen in the same session are skipped, so the event stream has no duplicates.
final class PhoneConnection {
    struct Status: Equatable {
        var state: PhoneLink.State = .disconnected
        var address: PhoneAddress?
        var hello: PhoneHello?
        var rttMs: Int?
        var eventsPerSecond = 0
        /// Telemetry lines the phone dropped for slow TCP clients this session.
        var dropped = 0
    }

    static let pingInterval: Duration = .seconds(2)
    static let backoff: [Duration] = [.seconds(3)]
    static let maxAttempts = 4

    var onStatus: ((Status) -> Void)?
    var onActivity: ((LinkActivity) -> Void)?

    private(set) var status = Status() {
        didSet { if status != oldValue { onStatus?(status) } }
    }

    private let events: AsyncStream<TelemetryEvent>
    private let eventSink: AsyncStream<TelemetryEvent>.Continuation

    private var connection: NWConnection?
    /// Bumped for every new `NWConnection`, so late callbacks from an old one are ignored.
    private var generation = 0
    private var wantsConnection = false
    private var failures = 0
    private var retryTask: Task<Void, Never>?
    private var tickTask: Task<Void, Never>?

    private var framer = LineFramer()
    private var dedupe = SeqDedupe()
    private var sawHello = false
    private var sawEvent = false
    private var nextPing: Int64 = 1
    private var pingsInFlight: [Int64: ContinuousClock.Instant] = [:]
    private var eventsThisSecond = 0

    /// The footer's text for the current connection, as `nc` would be typed.
    private var connectText = ""

    init() {
        (events, eventSink) = AsyncStream.makeStream(of: TelemetryEvent.self, bufferingPolicy: .bufferingNewest(5000))
    }

    /// Every event received, without duplicates. Single consumer.
    func telemetry() -> AsyncStream<TelemetryEvent> { events }

    func connect(to address: PhoneAddress) {
        disconnect(reporting: false)
        wantsConnection = true
        failures = 0
        status.address = address
        connectText = "nc \(address.host) \(address.port)"
        open()
    }

    func disconnect() {
        disconnect(reporting: true)
    }

    // MARK: Connection lifecycle

    private func disconnect(reporting: Bool) {
        let wasActive = wantsConnection
        wantsConnection = false
        retryTask?.cancel()
        retryTask = nil
        tickTask?.cancel()
        tickTask = nil
        closeConnection()
        status.state = .disconnected
        status.rttMs = nil
        status.eventsPerSecond = 0
        if reporting && wasActive { report(connectText, .ok("closed")) }
    }

    private func open() {
        precondition(!RobotServices.isTestHost, "unit tests must not open a phone connection")
        guard let address = status.address, let port = NWEndpoint.Port(rawValue: address.port) else { return }
        generation += 1
        let current = generation
        let connection = NWConnection(host: NWEndpoint.Host(address.host), port: port, using: .tcp)
        self.connection = connection
        framer = LineFramer()
        sawHello = false
        sawEvent = false
        pingsInFlight = [:]
        status.state = .connecting
        report(connectText, .running)

        connection.stateUpdateHandler = { [weak self] state in
            MainActor.assumeIsolated {
                guard let self, self.generation == current else { return }
                self.handle(state)
            }
        }
        connection.start(queue: .main)
    }

    private func handle(_ state: NWConnection.State) {
        switch state {
        case .ready:
            status.state = .connected
            report(connectText, .ok("connected"))
            receive()
            startTicking()
        case .waiting(let error), .failed(let error):
            // `.waiting` is how a refused connection shows up; retry on our own schedule.
            lost(Self.describe(error))
        default:
            break
        }
    }

    private func receive() {
        let current = generation
        connection?.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, isComplete, error in
            MainActor.assumeIsolated {
                guard let self, self.generation == current else { return }
                if let data, !data.isEmpty {
                    for line in self.framer.append(data) { self.handle(line: line) }
                }
                if let error {
                    self.lost(Self.describe(error))
                } else if isComplete {
                    self.lost("closed")
                } else {
                    self.receive()
                }
            }
        }
    }

    /// The connection ended without the user asking: schedule a reconnect, or give up.
    private func lost(_ reason: String) {
        closeConnection()
        tickTask?.cancel()
        tickTask = nil
        status.rttMs = nil
        status.eventsPerSecond = 0
        guard wantsConnection else { return }
        failures += 1
        guard let delay = Self.retryDelay(afterFailures: failures) else {
            wantsConnection = false
            status.state = .disconnected
            report(connectText, .failed("\(reason) · gave up after \(Self.maxAttempts) attempts"))
            return
        }
        status.state = .retrying(Date.now.addingTimeInterval(Double(delay.components.seconds)))
        report(connectText, .failed("\(reason) · retry in \(delay.components.seconds) s"))
        retryTask?.cancel()
        retryTask = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled, let self, self.wantsConnection else { return }
            self.open()
        }
    }

    /// The wait before the next attempt after `failures` attempts in a row, or nil to give up.
    static func retryDelay(afterFailures failures: Int) -> Duration? {
        guard failures < maxAttempts else { return nil }
        return backoff[min(failures - 1, backoff.count - 1)]
    }

    private func closeConnection() {
        generation += 1
        connection?.stateUpdateHandler = nil
        connection?.cancel()
        connection = nil
    }

    // MARK: Lines

    /// Handles one line from the phone. Internal so tests can feed lines without a socket.
    func handle(line: Data) {
        switch PhoneLine.decode(line) {
        case .hello(let hello):
            sawHello = true
            failures = 0
            if dedupe.begin(session: hello.session) { status.dropped = 0 }
            status.hello = hello
            report("← hello \(hello.summary)", .ok(nil))
        case .pong(let ping):
            guard let sent = pingsInFlight.removeValue(forKey: ping) else { return }
            let elapsed = ContinuousClock.now - sent
            status.rttMs = Int((Double(elapsed.components.attoseconds) / 1e15 + Double(elapsed.components.seconds) * 1000).rounded())
        case .event(let event):
            if !sawEvent {
                sawEvent = true
                // A server without hello lines: a new connection may be a new session.
                if !sawHello, dedupe.begin(session: nil) { status.dropped = 0 }
            }
            guard dedupe.admit(event.seq) else { return }
            eventsThisSecond += 1
            if let dropped = event.tcpDropped { status.dropped += dropped }
            eventSink.yield(event)
        case .otherLink, nil:
            break
        }
    }

    /// Pings every 2 s and samples ev/s every second while connected.
    private func startTicking() {
        tickTask?.cancel()
        eventsThisSecond = 0
        tickTask = Task { [weak self] in
            var second = 0
            while !Task.isCancelled {
                if second % Int(Self.pingInterval.components.seconds) == 0 { self?.sendPing() }
                try? await Task.sleep(for: .seconds(1))
                guard !Task.isCancelled, let self else { return }
                self.status.eventsPerSecond = self.eventsThisSecond
                self.eventsThisSecond = 0
                second += 1
            }
        }
    }

    private func sendPing() {
        guard let connection else { return }
        let ping = nextPing
        nextPing += 1
        pingsInFlight[ping] = .now
        // Pongs queue behind telemetry; forget pings that never came back.
        if pingsInFlight.count > 16, let oldest = pingsInFlight.keys.min() { pingsInFlight[oldest] = nil }
        connection.send(content: Data("{\"ping\":\(ping)}\n".utf8), completion: .contentProcessed { _ in })
    }

    private func report(_ text: String, _ state: LinkActivity.State) {
        onActivity?(LinkActivity(text: text, state: state))
    }

    private static func describe(_ error: NWError) -> String {
        switch error {
        case .posix(.ECONNREFUSED): "refused"
        case .posix(.ETIMEDOUT): "timed out"
        case .posix(.EHOSTUNREACH), .posix(.ENETUNREACH): "unreachable"
        case .posix(.ECONNRESET): "reset"
        case .dns: "unknown host"
        default: error.localizedDescription
        }
    }
}

/// The phone's first line on every connection.
nonisolated struct PhoneHello: Decodable, Equatable, Sendable {
    var `protocol`: Int?
    var device: String?
    var app: String?
    var session: String?
    var port: Int?

    /// `Google Pixel 8 · session 4f2c`
    var summary: String {
        [device, session.map { "session " + $0.prefix(4) }].compactMap { $0 }.joined(separator: " · ")
    }
}

/// One NDJSON line from the phone. Lines with a `link` key concern this client only.
nonisolated enum PhoneLine: Equatable, Sendable {
    case hello(PhoneHello)
    case pong(Int64)
    case event(TelemetryEvent)
    case otherLink(String)

    private struct Probe: Decodable {
        var link: String?
        var ping: Int64?
    }

    /// nil for anything that is neither a link line nor a telemetry event.
    static func decode(_ line: Data) -> PhoneLine? {
        let decoder = JSONDecoder()
        guard let probe = try? decoder.decode(Probe.self, from: line) else { return nil }
        switch probe.link {
        case "hello": return (try? decoder.decode(PhoneHello.self, from: line)).map(PhoneLine.hello)
        case "pong": return probe.ping.map(PhoneLine.pong)
        case let link?: return .otherLink(link)
        case nil: return (try? decoder.decode(TelemetryEvent.self, from: line)).map(PhoneLine.event)
        }
    }
}

/// Splits a byte stream on `\n`. A line longer than `maxLineBytes` is discarded whole.
nonisolated struct LineFramer: Sendable {
    var maxLineBytes = 1 << 20
    private var buffer = Data()
    private var discarding = false

    mutating func append(_ data: Data) -> [Data] {
        buffer.append(data)
        var lines: [Data] = []
        var start = buffer.startIndex
        while let newline = buffer[start...].firstIndex(of: 0x0A) {
            if discarding {
                discarding = false
            } else {
                var end = newline
                if end > start, buffer[buffer.index(before: end)] == 0x0D { end = buffer.index(before: end) }
                if end > start { lines.append(Data(buffer[start..<end])) }
            }
            start = buffer.index(after: newline)
        }
        buffer.removeSubrange(buffer.startIndex..<start)
        if buffer.count > maxLineBytes {
            buffer.removeAll()
            discarding = true
        }
        return lines
    }
}

/// Skips events already delivered: a reconnect replays the server's recent events.
nonisolated struct SeqDedupe: Sendable {
    private(set) var session: String?
    private(set) var lastSeq: Int64?

    /// Starts tracking `session`; returns true when that resets the state. An unknown (nil)
    /// session always resets, since seq restarts with every phone session.
    mutating func begin(session: String?) -> Bool {
        guard session == nil || session != self.session else { return false }
        self.session = session
        lastSeq = nil
        return true
    }

    /// True the first time a seq at or past the last one is seen.
    mutating func admit(_ seq: Int64) -> Bool {
        if let lastSeq, seq <= lastSeq { return false }
        lastSeq = seq
        return true
    }
}

extension TelemetryEvent {
    /// Lines the phone's TCP sink dropped, from a `telemetry/dropped` event.
    nonisolated var tcpDropped: Int? {
        guard source == "telemetry", kind == "dropped",
              case .number(let count)? = payload["sinks"]?["tcp"] else { return nil }
        return Int(count)
    }
}
