import Foundation
import Observation

@Observable
final class ConnectionStore {
    private(set) var links = RobotLinks()
    private let service: any RobotLinkService
    private var task: Task<Void, Never>?

    init(service: any RobotLinkService) {
        self.service = service
    }

    /// Follows the links and connects to the phone, over USB or Wi‑Fi.
    func start() {
        guard task == nil else { return }
        task = Task { [weak self, service] in
            for await links in service.links() {
                self?.links = links
            }
        }
        Task { await service.connectPhone() }
    }

    /// What the Connection chip shows.
    struct Summary {
        var tone: StatusDot.Tone
        var label: String
        var detail: String
    }

    var summary: Summary {
        switch links.phone.state {
        case .connected:
            return Summary(tone: .on, label: "Connected", detail: links.phone.rttMs.map { "\($0) ms" } ?? "— ms")
        case .connecting:
            return Summary(tone: .idle, label: "Connecting", detail: Self.routeName(links.phone.route).map { "phone over \($0)" } ?? "looking for phone")
        case .retrying(let date):
            return Summary(tone: .idle, label: "Retrying", detail: "retry \(Self.secondsUntil(date))s")
        case .waiting:
            return Summary(tone: .idle, label: "Waiting", detail: links.phone.isPairedForWifi ? "phone on Wi‑Fi" : "pair over USB")
        case .disconnected:
            break
        }
        if links.pico.route == .usb {
            return Summary(tone: .idle, label: "Pico over USB", detail: "phone offline")
        }
        return Summary(tone: .off, label: "Offline", detail: "no links")
    }

    static func routeName(_ route: PhoneLink.Route?) -> String? {
        switch route {
        case .usb: "USB"
        case .wifi: "Wi‑Fi"
        case nil: nil
        }
    }

    /// Whole seconds until `date`, at least 1.
    static func secondsUntil(_ date: Date) -> Int {
        max(1, Int(date.timeIntervalSinceNow.rounded(.up)))
    }

    /// Connect when idle; otherwise (connected, connecting or retrying) disconnect.
    func togglePhone() {
        if links.phone.state != .disconnected {
            Task { await service.disconnectPhone() }
            return
        }
        Task { await service.connectPhone() }
    }

    /// Tries now: USB first, then Wi‑Fi.
    func retryPhone() {
        Task { await service.connectPhone() }
    }

    func refreshPico() {
        Task { await service.refreshPico() }
    }

    func pairController() {
        Task { await service.pairController() }
    }

    func clearActivity() {
        Task { await service.clearActivity() }
    }
}
