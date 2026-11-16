import CryptoKit
import Foundation
import Network
import os

/// The link to onboard-android's telemetry server (`TcpServerSink`, NDJSON): over USB through
/// `adb forward` to its loopback listener, or over Wi-Fi to its TLS listener.
///
/// Each attempt asks `route` where to dial. Once a connection opens (the USB tunnel, or TLS
/// on Wi-Fi) failures don't count: the phone is there, and its app may stay paused for a
/// while, for example behind Android's Wi-Fi approval dialog that the pairing sent on hello
/// brings up, so Relay retries every 3 s. After `maxAttempts` attempts in a row that open
/// nothing, or when the phone refuses Relay's key or shows another certificate, it stops and
/// waits for `routeAppeared()` or another `connect`. Each connection starts with a hello line
/// and a replay of recent events; replayed events already seen in the same session are
/// skipped, so the event stream has no duplicates.
final class PhoneConnection {
    /// Where one attempt dials.
    enum Target {
        case usb
        /// The phone's Bonjour endpoint, with what Relay and the phone exchanged over USB.
        case wifi(NWEndpoint, key: Data, fingerprint: String)

        var route: PhoneLink.Route {
            switch self {
            case .usb: .usb
            case .wifi: .wifi
            }
        }
    }

    struct Status: Equatable {
        var state: PhoneLink.State = .disconnected
        /// The route of the current attempt or connection.
        var route: PhoneLink.Route?
        var hello: PhoneHello?
        var rttMs: Int?
        var eventsPerSecond = 0
        /// Telemetry lines the phone dropped for slow TCP clients this session.
        var dropped = 0
        /// The phone's link to the Pico, without the parts that change with every report.
        var body: Body?
    }

    struct Body: Equatable {
        var phase: String
        var mode: PicoBodyState.Mode?
    }

    static let usbPort: UInt16 = 7777
    static let pingInterval: Duration = .seconds(2)
    static let maxAttempts = 4
    /// The phone sends a body report at least every second; this long without a byte, the link is gone.
    static let silenceLimit: Duration = .seconds(6)
    static let pairHint = "Connect the phone over USB once to pair"

    var onStatus: ((Status) -> Void)?
    var onActivity: ((LinkActivity) -> Void)?
    /// Called for every hello, including the one after a reconnect in the same session.
    var onHello: ((PhoneHello) -> Void)?
    /// Every body report from the phone, and nil when the connection to the phone ends.
    var onBody: ((BodyLink?) -> Void)?

    private(set) var status = Status() {
        didSet { if status != oldValue { onStatus?(status) } }
    }

    private let events: AsyncStream<TelemetryEvent>
    private let eventSink: AsyncStream<TelemetryEvent>.Continuation
    private let retryInterval: Duration

    private var connection: NWConnection?
    /// Bumped for every new `NWConnection`, so late callbacks from an old one are ignored.
    private var generation = 0
    private var wantsConnection = false
    /// Attempts in a row that opened nothing.
    private var failures = 0
    private var retryTask: Task<Void, Never>?
    private var tickTask: Task<Void, Never>?
    private var route: (() async -> Target?)?
    private var setupTask: Task<Void, Never>?

    private var target: Target?
    /// The current attempt reached `.ready`: the USB tunnel or the TLS session is up.
    private var opened = false
    private var certificateRejected = false
    private var lastReceived = ContinuousClock.now
    private var framer = LineFramer()
    private var dedupe = SeqDedupe()
    private var sawHello = false
    private var sawEvent = false
    private var nextPing: Int64 = 1
    private var pingsInFlight: [Int64: ContinuousClock.Instant] = [:]
    private var eventsThisSecond = 0

    /// The footer's text for the current attempt, as it would be typed in a terminal.
    private var connectText = "Looking for Walky"

    init(retryInterval: Duration = .seconds(3)) {
        self.retryInterval = retryInterval
        (events, eventSink) = AsyncStream.makeStream(of: TelemetryEvent.self, bufferingPolicy: .bufferingNewest(5000))
    }

    /// Every event received, without duplicates. Single consumer.
    func telemetry() -> AsyncStream<TelemetryEvent> { events }

    /// Starts connecting, or tries again at once when waiting. `route` returns nil when there
    /// is nowhere to dial.
    func connect(route: @escaping () async -> Target?) {
        self.route = route
        if wantsConnection {
            if status.state == .waiting { restart() }
            return
        }
        wantsConnection = true
        restart()
    }

    /// The phone appeared on Wi-Fi: try at once if waiting.
    func routeAppeared() {
        guard wantsConnection, status.state == .waiting else { return }
        Logger.phone.notice("phone: appeared on Wi-Fi, connecting")
        restart()
    }

    private func restart() {
        failures = 0
        startAttempt()
    }

    private func startAttempt() {
        retryTask?.cancel()
        retryTask = nil
        status.state = .connecting
        status.route = nil
        status.hello = nil
        connectText = "Looking for Walky"
        report(connectText, .running)
        setupTask?.cancel()
        setupTask = Task {
            guard !Task.isCancelled, let route else { return }
            let target = await route()
            guard !Task.isCancelled, wantsConnection else { return }
            if let target {
                open(target)
            } else {
                opened = false
                lost("No phone on USB or Wi‑Fi")
            }
        }
    }

    func disconnect() {
        let wasActive = wantsConnection
        wantsConnection = false
        setupTask?.cancel()
        setupTask = nil
        route = nil
        retryTask?.cancel()
        retryTask = nil
        endAttempt()
        status.state = .disconnected
        status.route = nil
        if wasActive {
            Logger.phone.notice("phone: disconnected by user")
            report("Phone connection", .ok("disconnected by user"))
        }
    }

    private func open(_ target: Target) {
        precondition(!RobotServices.isTestHost, "unit tests must not open a phone connection")
        closeConnection()
        let current = generation
        let tcp = NWProtocolTCP.Options()
        // Leg targets are small lines that must not wait for earlier ones to be acknowledged.
        tcp.noDelay = true
        tcp.connectionTimeout = 5
        let connection: NWConnection
        switch target {
        case .usb:
            connectText = "nc 127.0.0.1 \(Self.usbPort)"
            connection = NWConnection(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: Self.usbPort)!,
                                      using: NWParameters(tls: nil, tcp: tcp))
        case .wifi(let endpoint, _, let fingerprint):
            connectText = Self.lookupCommand(endpoint)
            let tls = Self.tlsOptions(pinning: fingerprint) { [weak self] in
                MainActor.assumeIsolated {
                    guard let self, self.generation == current else { return }
                    self.certificateRejected = true
                }
            }
            connection = NWConnection(to: endpoint, using: NWParameters(tls: tls, tcp: tcp))
        }
        self.connection = connection
        self.target = target
        status.route = target.route
        opened = false
        certificateRejected = false
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
            opened = true
            lastReceived = .now
            if case .wifi(_, let key, _) = target {
                if let remote = connection?.currentPath?.remoteEndpoint {
                    connectText = "openssl s_client -connect \(Self.address(remote))"
                }
                Logger.phone.notice("phone: TLS open to \(self.connectText, privacy: .public), certificate pinned, sending key")
                report(connectText, .ok("TLS open, certificate pinned · waiting for Walky"))
                connection?.send(content: Self.keyLine("auth", key) + Data([0x0A]), completion: .contentProcessed { error in
                    if let error { Logger.phone.error("phone: auth send failed: \(error.localizedDescription, privacy: .public)") }
                })
            } else {
                Logger.phone.notice("phone: USB tunnel open (adb forward tcp:\(Self.usbPort, privacy: .public)), waiting for hello")
                report(connectText, .ok("USB tunnel open · waiting for Walky"))
            }
            receive()
            startTicking()
        case .waiting(let error), .failed(let error):
            if certificateRejected {
                refused("Walky’s certificate is not the one paired over USB")
            } else {
                // `.waiting` is how a refused connection shows up; retry on our own schedule.
                lost(Self.describe(error))
            }
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
                    self.lastReceived = .now
                    for line in self.framer.append(data) {
                        self.handle(line: line)
                        guard self.generation == current else { return }
                    }
                }
                if let error {
                    self.lost(Self.describe(error))
                } else if isComplete {
                    self.lost(self.sawHello || self.sawEvent ? "Telemetry closed" : "No telemetry received. Open Walky on the unlocked phone.")
                } else {
                    self.receive()
                }
            }
        }
    }

    /// Closes the connection and what depends on it; the state is the caller's to set.
    private func endAttempt() {
        closeConnection()
        tickTask?.cancel()
        tickTask = nil
        status.rttMs = nil
        status.eventsPerSecond = 0
        endBody()
    }

    /// The connection ended without the user asking: schedule a reconnect, or wait.
    private func lost(_ reason: String) {
        endAttempt()
        guard wantsConnection else { return }
        failures = opened ? 0 : failures + 1
        guard failures < Self.maxAttempts else {
            Logger.phone.error("phone: \(self.failures, privacy: .public) attempts in a row opened nothing: \(reason, privacy: .public), waiting")
            wait(report: "\(reason) · waiting for Walky on Wi‑Fi")
            return
        }
        let delay = retryInterval
        let seconds = Double(delay.components.seconds) + Double(delay.components.attoseconds) / 1e18
        Logger.phone.notice("phone: connection failed: \(reason, privacy: .public), retrying in \(seconds, privacy: .public) s")
        status.state = .retrying(Date.now.addingTimeInterval(seconds))
        report(connectText, .failed("\(reason) · retry in \(Int(seconds.rounded(.up))) s"))
        retryTask?.cancel()
        retryTask = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled, let self, self.wantsConnection else { return }
            self.startAttempt()
        }
    }

    /// The phone does not trust Relay or Relay does not trust the phone: retrying cannot help.
    private func refused(_ reason: String) {
        endAttempt()
        Logger.phone.error("phone: \(reason, privacy: .public)")
        guard wantsConnection else {
            report(connectText, .failed("\(reason) · \(Self.pairHint)"))
            return
        }
        wait(report: "\(reason) · \(Self.pairHint)")
    }

    private func wait(report detail: String) {
        retryTask?.cancel()
        retryTask = nil
        status.state = .waiting
        status.route = nil
        status.hello = nil
        report(connectText, .failed(detail))
    }

    private func endBody() {
        guard status.body != nil else { return }
        status.body = nil
        onBody?(nil)
    }

    /// Sends one line to the phone. Dropped unless the phone has said hello.
    func send(_ line: Data) {
        guard let connection, status.state == .connected else { return }
        connection.send(content: line + Data([0x0A]), completion: .contentProcessed { error in
            if let error { Logger.phone.error("phone: send failed: \(error.localizedDescription, privacy: .public)") }
        })
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
            status.state = .connected
            sawHello = true
            if dedupe.begin(session: hello.session) {
                status.dropped = 0
                Logger.phone.info("phone: hello from \(hello.summary, privacy: .public), new session, telemetry restarts")
            } else {
                Logger.phone.info("phone: hello from \(hello.summary, privacy: .public), same session, skipping replayed events")
            }
            status.hello = hello
            report("Walky connected · hello \(hello.summary)", .ok(nil))
            onHello?(hello)
        case .pong(let ping):
            guard let sent = pingsInFlight.removeValue(forKey: ping) else { return }
            let elapsed = ContinuousClock.now - sent
            status.rttMs = Int((Double(elapsed.components.attoseconds) / 1e15 + Double(elapsed.components.seconds) * 1000).rounded())
        case .event(let event):
            status.state = .connected
            if !sawEvent {
                sawEvent = true
                // A server without hello lines: a new connection may be a new session.
                if !sawHello, dedupe.begin(session: nil) { status.dropped = 0 }
            }
            guard dedupe.admit(event.seq) else { return }
            eventsThisSecond += 1
            if let dropped = event.tcpDropped { status.dropped += dropped }
            eventSink.yield(event)
        case .body(let link):
            status.body = Body(phase: link.phase, mode: link.pico?.mode)
            onBody?(link)
        case .auth(let ok, let reason):
            guard !ok else { return }
            refused(reason == "unpaired" ? "Walky has no key from Relay" : "Walky refused Relay’s key")
        case .otherLink:
            break
        case nil:
            Logger.phone.error("phone: dropped undecodable line (\(line.count, privacy: .public) bytes): \(String(decoding: line.prefix(200), as: UTF8.self))")
        }
    }

    /// Pings every 2 s and samples ev/s every second while open; ends a link gone silent.
    private func startTicking() {
        tickTask?.cancel()
        eventsThisSecond = 0
        tickTask = Task { [weak self] in
            var second = 0
            while !Task.isCancelled {
                if second % Int(Self.pingInterval.components.seconds) == 0 { self?.sendPing() }
                try? await Task.sleep(for: .seconds(1))
                guard !Task.isCancelled, let self else { return }
                if ContinuousClock.now - self.lastReceived > Self.silenceLimit {
                    self.lost("No data from Walky for \(Self.silenceLimit.components.seconds) s")
                    return
                }
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
        connection.send(content: Data("{\"ping\":\(ping)}\n".utf8), completion: .contentProcessed { error in
            if let error { Logger.phone.error("phone: ping send failed: \(error.localizedDescription, privacy: .public)") }
        })
    }

    private func report(_ text: String, _ state: LinkActivity.State) {
        onActivity?(LinkActivity(text: text, state: state))
    }

    /// `{"link":"auth","key":"<base64>"}` on Wi-Fi, `{"link":"key",…}` to hand the key over USB.
    nonisolated static func keyLine(_ link: String, _ key: Data) -> Data {
        Data(#"{"link":"\#(link)","key":"\#(key.base64EncodedString())"}"#.utf8)
    }

    /// TLS that accepts only the certificate whose SHA-256 is `fingerprint`; the phone's is
    /// self-signed, so pinning is the whole check. `rejected` runs on the main queue.
    nonisolated private static func tlsOptions(pinning fingerprint: String, rejected: @escaping @Sendable () -> Void) -> NWProtocolTLS.Options {
        let options = NWProtocolTLS.Options()
        let security = options.securityProtocolOptions
        sec_protocol_options_set_min_tls_protocol_version(security, .TLSv12)
        sec_protocol_options_set_verify_block(security, { _, trust, complete in
            let matches = certificateFingerprint(sec_trust_copy_ref(trust).takeRetainedValue()) == fingerprint
            if !matches { rejected() }
            complete(matches)
        }, .main)
        return options
    }

    nonisolated private static func certificateFingerprint(_ trust: SecTrust) -> String? {
        guard let leaf = (SecTrustCopyCertificateChain(trust) as? [SecCertificate])?.first else { return nil }
        return SHA256.hash(data: SecCertificateCopyData(leaf) as Data).map { String(format: "%02x", $0) }.joined()
    }

    /// `dns-sd -L Walky _walky._tcp` until the connection has resolved an address.
    nonisolated private static func lookupCommand(_ endpoint: NWEndpoint) -> String {
        if case .service(let name, let type, _, _) = endpoint { return "dns-sd -L \(name) \(type)" }
        return "openssl s_client -connect \(address(endpoint))"
    }

    nonisolated private static func address(_ endpoint: NWEndpoint) -> String {
        guard case .hostPort(let host, let port) = endpoint else { return "\(endpoint)" }
        if case .ipv6 = host { return "[\(host)]:\(port)" }
        return "\(host):\(port)"
    }

    private static func describe(_ error: NWError) -> String {
        switch error {
        case .posix(.ECONNREFUSED): "refused"
        case .posix(.ETIMEDOUT): "timed out"
        case .posix(.EHOSTUNREACH), .posix(.ENETUNREACH): "unreachable"
        case .posix(.ECONNRESET): "reset"
        case .dns: "unknown host"
        case .tls: "TLS failed"
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
    /// SHA-256 of the certificate on the phone's Wi-Fi listener, lowercase hex; sent on USB only.
    var cert: String?

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
    case body(BodyLink)
    /// The Wi-Fi listener's answer to Relay's key; it only ever sends refusals.
    case auth(ok: Bool, reason: String?)
    case otherLink(String)

    private struct Probe: Decodable {
        var link: String?
        var ping: Int64?
    }

    private struct Auth: Decodable {
        var ok: Bool
        var reason: String?
    }

    /// nil for anything that is neither a link line nor a telemetry event.
    static func decode(_ line: Data) -> PhoneLine? {
        let decoder = JSONDecoder()
        guard let probe = try? decoder.decode(Probe.self, from: line) else { return nil }
        switch probe.link {
        case "hello": return (try? decoder.decode(PhoneHello.self, from: line)).map(PhoneLine.hello)
        case "pong": return probe.ping.map(PhoneLine.pong)
        case "body": return (try? decoder.decode(BodyLink.self, from: line)).map(PhoneLine.body)
        case "auth": return (try? decoder.decode(Auth.self, from: line)).map { .auth(ok: $0.ok, reason: $0.reason) }
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
            let limit = maxLineBytes
            Logger.phone.error("phone: line over \(limit, privacy: .public) bytes, discarding until next newline")
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
