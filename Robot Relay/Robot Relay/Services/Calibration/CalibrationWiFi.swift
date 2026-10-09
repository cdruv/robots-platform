import CoreWLAN
import CoreLocation
import Foundation
import Security

nonisolated protocol CalibrationWiFi: Sendable {
    func join(_ ticket: CalibrationTicket) async throws
    /// Returns a manual-recovery explanation when restoration could not be verified.
    func restore() async -> String?
}

/// Blocking CoreWLAN calls run on this actor, never the UI executor.
actor MacCalibrationWiFi: CalibrationWiFi {
    private var previous: String?
    private var temporary: String?
    private var interface: CWInterface?

    func join(_ ticket: CalibrationTicket) async throws {
        precondition(!RobotServices.isTestHost)
        guard let interface = CWWiFiClient.shared().interface(), interface.powerOn() else {
            throw CalibrationFailure("Turn on Mac Wi-Fi, then try again.")
        }
        self.interface = interface
        let current = interface.ssid()
        previous = current == ticket.ssid ? UserDefaults.standard.string(forKey: "calibrationPreviousSSID") : current
        temporary = ticket.ssid
        // Persist only names for recovery following app termination, never passwords.
        UserDefaults.standard.set(previous, forKey: "calibrationPreviousSSID")
        UserDefaults.standard.set(temporary, forKey: "calibrationTemporarySSID")
        for attempt in 0..<12 {
            try Task.checkCancellation()
            let networks = try interface.scanForNetworks(withName: ticket.ssid)
            if let network = networks.first(where: { $0.ssid == ticket.ssid }) {
                try interface.associate(to: network, password: ticket.password)
                return
            }
            if attempt < 11 { try await Task.sleep(for: .seconds(2)) }
        }
        throw CalibrationFailure("Robot Wi-Fi not found. Check battery power and the one-time calibration boot; re-arm over USB if it timed out.")
    }

    func restore() async -> String? {
        precondition(!RobotServices.isTestHost)
        let defaults = UserDefaults.standard
        let temporary = temporary ?? defaults.string(forKey: "calibrationTemporarySSID")
        let previous = previous ?? defaults.string(forKey: "calibrationPreviousSSID")
        defer {
            self.temporary = nil
            self.previous = nil
            defaults.removeObject(forKey: "calibrationTemporarySSID")
            defaults.removeObject(forKey: "calibrationPreviousSSID")
        }
        guard let temporary else { return nil }
        guard let interface = interface ?? CWWiFiClient.shared().interface() else {
            return "Open Wi-Fi Settings to leave \(temporary) and restore your network."
        }
        let current = interface.ssid()
        // A different nonempty SSID belongs to the user (or system auto-join).
        guard CalibrationWiFiPolicy.shouldRestore(current: current, temporary: temporary) else { return nil }
        if current == nil && interface.interfaceMode() != .none {
            return "Cannot identify the current Wi-Fi network. Open Wi-Fi Settings to verify disconnection and restore your network."
        }
        if current == temporary { interface.disassociate() }
        guard let previous else { return current == nil ? "Verify the calibration network is disconnected in Wi-Fi Settings." : nil }
        do {
            guard let network = try interface.scanForNetworks(withName: previous).first(where: { $0.ssid == previous }) else {
                throw CalibrationFailure("Previous network unavailable")
            }
            // Check again after the scan; don't replace a network chosen during cleanup.
            let selected = interface.ssid()
            if let selected, selected != temporary { return nil }
            if selected == nil && interface.interfaceMode() != .none {
                throw CalibrationFailure("Current network cannot be identified")
            }
            var password: NSString?
            if let ssid = previous.data(using: .utf8) {
                if CWKeychainFindWiFiPassword(.user, ssid, &password) != errSecSuccess {
                    _ = CWKeychainFindWiFiPassword(.system, ssid, &password)
                }
            }
            try interface.associate(to: network, password: password as String?)
            guard interface.ssid() == previous else { throw CalibrationFailure("Network restoration not confirmed") }
            return nil
        } catch {
            return "Could not restore \(previous). Select your network in Wi-Fi Settings. \(error.localizedDescription)"
        }
    }
}

/// Modern macOS gates Wi-Fi names/scanning behind Location Services.
final class CalibrationLocationPermission: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var continuation: CheckedContinuation<Void, Error>?

    override init() {
        super.init()
        manager.delegate = self
    }

    func request() async throws {
        precondition(!RobotServices.isTestHost)
        if manager.authorizationStatus == .notDetermined {
            try await withTaskCancellationHandler {
                try Task.checkCancellation()
                try await withCheckedThrowingContinuation { continuation in
                    self.continuation = continuation
                    manager.requestWhenInUseAuthorization()
                }
            } onCancel: {
                Task { @MainActor [weak self] in
                    self?.continuation?.resume(throwing: CancellationError())
                    self?.continuation = nil
                }
            }
        }
        guard manager.authorizationStatus == .authorizedAlways else {
            throw CalibrationFailure("Allow Robot Relay in System Settings → Privacy & Security → Location Services to switch calibration Wi-Fi.")
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        Task { @MainActor [weak self] in
            guard let self, self.manager.authorizationStatus != .notDetermined else { return }
            continuation?.resume()
            continuation = nil
        }
    }
}

protocol CalibrationTicketStorage {
    func save(_ ticket: CalibrationTicket) throws
    func load() -> CalibrationTicket?
    func clear()
}

struct CalibrationKeychain: CalibrationTicketStorage {
    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: "RobotRelay.Calibration", kSecAttrAccount as String: "pending"]
    }
    func save(_ ticket: CalibrationTicket) throws {
        precondition(!RobotServices.isTestHost)
        var item = query
        item[kSecValueData as String] = try JSONEncoder().encode(ticket)
        let status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: item[kSecValueData as String]!] as CFDictionary)
        if status == errSecItemNotFound {
            guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else { throw CalibrationFailure("Could not store calibration credentials in Keychain") }
        } else if status != errSecSuccess { throw CalibrationFailure("Could not update calibration credentials in Keychain") }
    }
    func load() -> CalibrationTicket? {
        precondition(!RobotServices.isTestHost)
        var request = query
        request[kSecReturnData as String] = true
        var value: CFTypeRef?
        guard SecItemCopyMatching(request as CFDictionary, &value) == errSecSuccess, let data = value as? Data else { return nil }
        return try? JSONDecoder().decode(CalibrationTicket.self, from: data)
    }
    func clear() {
        precondition(!RobotServices.isTestHost)
        SecItemDelete(query as CFDictionary)
    }
}

nonisolated enum CalibrationWiFiPolicy {
    static func shouldRestore(current: String?, temporary: String) -> Bool {
        current == nil || current == temporary
    }
}
