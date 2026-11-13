import CoreWLAN
import CoreLocation
import Foundation
import os
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
        var robots: Set<String> = []
        for attempt in 0..<12 {
            try Task.checkCancellation()
            let networks = try interface.scanForNetworks(withName: nil)
            let named = networks.compactMap(\.ssid)
            robots.formUnion(named.filter { $0.hasPrefix("Walky-Cal-") })
            Logger.calibration.info("scan \(attempt + 1, privacy: .public) of 12 for \"\(ticket.ssid, privacy: .public)\": \(networks.count, privacy: .public) networks, \(networks.count - named.count, privacy: .public) without names, robot networks seen: [\(robots.sorted().joined(separator: ", "), privacy: .public)]")
            if let network = networks.first(where: { $0.ssid == ticket.ssid }) {
                try interface.associate(to: network, password: ticket.password)
                Logger.calibration.info("joined calibration Wi-Fi \"\(ticket.ssid, privacy: .public)\", will restore \(self.previous.map { "\"\($0)\"" } ?? "no previous network", privacy: .public)")
                try await waitForRobotAddress(interface.interfaceName ?? "en0", ssid: ticket.ssid)
                return
            }
            if attempt < 11 { try await Task.sleep(for: .seconds(2)) }
        }
        Logger.calibration.error("calibration Wi-Fi \"\(ticket.ssid, privacy: .public)\" not found after 12 scans, other robot networks seen: [\(robots.sorted().joined(separator: ", "), privacy: .public)]")
        let others = robots.subtracting([ticket.ssid])
        throw CalibrationFailure(others.isEmpty
            ? "\(ticket.ssid) not found. The Pico's LED should blink for 3 s after battery power-on, then stay on. If it never blinked, power it off and on again, then re-prepare."
            : "\(ticket.ssid) not found, but \(others.sorted().joined(separator: ", ")) is up. The Pico is running an older calibration request; power it off and re-prepare.")
    }

    private func waitForRobotAddress(_ name: String, ssid: String) async throws {
        var addresses: [String] = []
        for _ in 0..<20 {
            addresses = InterfaceAddresses.ipv4(name)
            if let address = addresses.first(where: { $0.hasPrefix("192.168.4.") }) {
                Logger.calibration.info("\(name, privacy: .public) got \(address, privacy: .public) from the robot")
                return
            }
            try await Task.sleep(for: .milliseconds(500))
        }
        let found = addresses.isEmpty ? "none" : addresses.joined(separator: ", ")
        Logger.calibration.error("no 192.168.4.x address on \(name, privacy: .public) after 10 s, IPv4: \(found, privacy: .public)")
        throw CalibrationFailure("Joined \(ssid), but the robot gave this Mac no address within 10 s (\(name) IPv4: \(found)).")
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
            Logger.calibration.info("restored Mac Wi-Fi to \"\(previous, privacy: .public)\"")
            return nil
        } catch {
            Logger.calibration.error("Wi-Fi restore to \"\(previous, privacy: .public)\" failed: \(error.localizedDescription, privacy: .public)")
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
            let added = SecItemAdd(item as CFDictionary, nil)
            guard added == errSecSuccess else {
                Logger.calibration.error("Keychain add failed: OSStatus \(added, privacy: .public)")
                throw CalibrationFailure("Could not store calibration credentials in Keychain")
            }
        } else if status != errSecSuccess {
            Logger.calibration.error("Keychain update failed: OSStatus \(status, privacy: .public)")
            throw CalibrationFailure("Could not update calibration credentials in Keychain")
        }
    }
    func load() -> CalibrationTicket? {
        precondition(!RobotServices.isTestHost)
        var request = query
        request[kSecReturnData as String] = true
        var value: CFTypeRef?
        guard SecItemCopyMatching(request as CFDictionary, &value) == errSecSuccess, let data = value as? Data else { return nil }
        do {
            return try JSONDecoder().decode(CalibrationTicket.self, from: data)
        } catch {
            Logger.calibration.error("stored calibration ticket undecodable: \(error.localizedDescription, privacy: .public)")
            return nil
        }
    }
    func clear() {
        precondition(!RobotServices.isTestHost)
        SecItemDelete(query as CFDictionary)
    }
}

nonisolated enum InterfaceAddresses {
    static func ipv4(_ name: String) -> [String] {
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return [] }
        defer { freeifaddrs(head) }
        var result: [String] = []
        for pointer in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let entry = pointer.pointee
            guard String(cString: entry.ifa_name) == name, let address = entry.ifa_addr,
                  address.pointee.sa_family == UInt8(AF_INET) else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(address, socklen_t(address.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                result.append(String(cString: host))
            }
        }
        return result
    }
}

nonisolated enum CalibrationWiFiPolicy {
    static func shouldRestore(current: String?, temporary: String) -> Bool {
        current == nil || current == temporary
    }
}
