import CryptoKit
import Foundation

/// What Relay and the phone exchange over USB so they can trust each other over Wi-Fi:
/// Relay's key, which the phone requires from Wi-Fi clients, and the phone's certificate
/// fingerprint, the only certificate Relay accepts there. Both live in the Keychain.
final class RelayPairing {
    private let keyItem = KeychainItem(service: "RobotRelay.PhoneKey")
    private let certificateItem = KeychainItem(service: "RobotRelay.PhoneCertificate")
    private var cachedKey: Data?

    /// SHA-256 of the phone's TLS certificate, lowercase hex; nil until a USB hello carried it.
    private(set) var fingerprint: String?

    init() {
        fingerprint = certificateItem.load().map { String(decoding: $0, as: UTF8.self) }
    }

    /// 32 random bytes, created on first use.
    var key: Data {
        if let cachedKey { return cachedKey }
        if let stored = keyItem.load(), stored.count == 32 {
            cachedKey = stored
            return stored
        }
        let key = SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) }
        keyItem.save(key)
        cachedKey = key
        return key
    }

    /// Returns true when `fingerprint` is new.
    func pin(_ fingerprint: String) -> Bool {
        let fingerprint = fingerprint.lowercased()
        guard fingerprint != self.fingerprint else { return false }
        self.fingerprint = fingerprint
        certificateItem.save(Data(fingerprint.utf8))
        return true
    }
}
