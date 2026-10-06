import Foundation
import Observation

@Observable
final class ConnectionStore {
    private(set) var links = RobotLinks()
    /// The address field. Remembered across launches.
    var addressDraft: String {
        didSet {
            defaults.set(addressDraft, forKey: Self.addressKey)
            isAddressInvalid = false
        }
    }
    /// Set when Connect was pressed with something that isn't `host:port`.
    private(set) var isAddressInvalid = false

    private static let addressKey = "phoneAddress"
    private let service: any RobotLinkService
    private let defaults: UserDefaults
    private var task: Task<Void, Never>?

    init(service: any RobotLinkService, defaults: UserDefaults = .standard) {
        self.service = service
        self.defaults = defaults
        addressDraft = defaults.string(forKey: Self.addressKey) ?? PhoneAddress.defaultForward.description
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
        switch links.phone.state {
        case .connected:
            return Summary(tone: .on, label: "Connected", detail: links.phone.rttMs.map { "\($0) ms" } ?? "— ms")
        case .connecting:
            return Summary(tone: .idle, label: "Connecting", detail: links.phone.address)
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
        guard let address = PhoneAddress(addressDraft) else {
            isAddressInvalid = true
            return
        }
        Task { await service.connectPhone(address: address.description) }
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
