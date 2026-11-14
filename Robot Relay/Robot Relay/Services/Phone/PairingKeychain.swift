import Foundation
import os

/// Keeps the Pico's Wi-Fi name and password, read from the board over USB, so they can be
/// handed to the phone on later launches without the Pico plugged in.
struct PairingKeychain {
    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: "RobotRelay.PicoPairing", kSecAttrAccount as String: "walky"]
    }

    func save(_ pairing: PicoPairing) {
        precondition(!RobotServices.isTestHost)
        guard let data = try? JSONEncoder().encode(pairing) else { return }
        var status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var item = query
            item[kSecValueData as String] = data
            status = SecItemAdd(item as CFDictionary, nil)
        }
        if status != errSecSuccess {
            Logger.pico.error("Pico pairing not stored in Keychain: OSStatus \(status, privacy: .public)")
        }
    }

    func load() -> PicoPairing? {
        precondition(!RobotServices.isTestHost)
        var request = query
        request[kSecReturnData as String] = true
        var value: CFTypeRef?
        guard SecItemCopyMatching(request as CFDictionary, &value) == errSecSuccess, let data = value as? Data else { return nil }
        return try? JSONDecoder().decode(PicoPairing.self, from: data)
    }
}
