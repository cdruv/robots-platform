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

    /// Follows the links and connects to the USB phone.
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
            return Summary(tone: .idle, label: "Connecting", detail: "phone over USB")
        case .retrying(let date):
            return Summary(tone: .idle, label: "Retrying", detail: "retry \(Self.secondsUntil(date))s")
        case .disconnected:
            break
        }
        if links.pico.route == .usb {
            return Summary(tone: .idle, label: "Pico over USB", detail: "phone offline")
        }
        return Summary(tone: .off, label: "Offline", detail: "no links")
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
