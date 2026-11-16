import Foundation
import Network
import os

/// Browses Bonjour for the phone's `_walky._tcp` beacon, which it advertises only while
/// Walky is in the foreground. Event-driven: nothing polls while the phone is away.
final class PhoneDiscovery {
    static let serviceType = "_walky._tcp"

    /// The phone's TLS listener, while it is advertised.
    private(set) var endpoint: NWEndpoint?
    /// The phone appeared, or its advertisement changed.
    var onAppear: (() -> Void)?
    var onActivity: ((LinkActivity) -> Void)?

    private var browser: NWBrowser?
    private let command = "dns-sd -B \(PhoneDiscovery.serviceType)"

    func start() {
        precondition(!RobotServices.isTestHost, "unit tests must not browse the network")
        guard browser == nil else { return }
        let browser = NWBrowser(for: .bonjour(type: Self.serviceType, domain: nil), using: NWParameters())
        self.browser = browser
        browser.stateUpdateHandler = { [weak self] state in
            MainActor.assumeIsolated {
                guard let self, self.browser === browser else { return }
                self.handle(state)
            }
        }
        browser.browseResultsChangedHandler = { [weak self] results, changes in
            MainActor.assumeIsolated {
                guard let self, self.browser === browser else { return }
                self.update(results, changes)
            }
        }
        browser.start(queue: .main)
    }

    func stop() {
        browser?.cancel()
        browser = nil
        endpoint = nil
    }

    private func handle(_ state: NWBrowser.State) {
        switch state {
        case .waiting(let error), .failed(let error):
            if case .dns(DNSServiceErrorType(kDNSServiceErr_PolicyDenied)) = error {
                Logger.phone.error("phone: Bonjour browsing blocked by Local Network privacy")
                report(.failed("blocked: allow Robot Relay in System Settings › Privacy & Security › Local Network"))
            } else {
                Logger.phone.error("phone: Bonjour browsing failed: \(error.localizedDescription, privacy: .public)")
                report(.failed(error.localizedDescription))
            }
        default:
            break
        }
    }

    private func update(_ results: Set<NWBrowser.Result>, _ changes: Set<NWBrowser.Result.Change>) {
        let found = results.first?.endpoint
        let appeared = changes.contains { change in
            switch change {
            case .added, .changed: true
            default: false
            }
        }
        let previous = endpoint
        endpoint = found
        if let found {
            if found != previous {
                Logger.phone.notice("phone: found \(String(describing: found), privacy: .public) on Wi-Fi")
                report(.ok("found \(Self.name(found))"))
            }
            if appeared { onAppear?() }
        } else if previous != nil {
            Logger.phone.notice("phone: Bonjour advertisement gone")
            report(.ok("gone"))
        }
    }

    private func report(_ state: LinkActivity.State) {
        onActivity?(LinkActivity(text: command, state: state))
    }

    private static func name(_ endpoint: NWEndpoint) -> String {
        if case .service(let name, _, _, _) = endpoint { return name }
        return String(describing: endpoint)
    }
}
