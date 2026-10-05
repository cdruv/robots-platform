import Foundation
import Observation

@Observable
final class ConnectionStore {
    private(set) var links = RobotLinks()
    var addressDraft = "10.0.0.42:7777"

    private let service: any RobotLinkService
    private var task: Task<Void, Never>?

    init(service: any RobotLinkService) {
        self.service = service
    }

    func start() {
        guard task == nil else { return }
        task = Task { [weak self, service] in
            for await links in service.links() {
                self?.links = links
            }
        }
    }

    /// What the Connection chip shows.
    struct Summary {
        var tone: StatusDot.Tone
        var label: String
        var detail: String
    }

    var summary: Summary {
        if links.phone.isConnected {
            return Summary(tone: .on, label: "Connected", detail: "\(links.phone.rttMs) ms")
        }
        if links.pico.route == .usb {
            return Summary(tone: .idle, label: "Pico over USB", detail: "phone offline")
        }
        return Summary(tone: .off, label: "Offline", detail: "no links")
    }

    func togglePhone() {
        Task {
            if links.phone.isConnected {
                await service.disconnectPhone()
            } else {
                await service.connectPhone(address: addressDraft)
            }
        }
    }

    func releasePico() {
        Task { await service.releasePico() }
    }

    func pairController() {
        Task { await service.pairController() }
    }

    func adbForward() {
        Task { await service.adbForward(address: addressDraft) }
    }

    func addRobot() {
        Task { await service.addRobot() }
    }
}
