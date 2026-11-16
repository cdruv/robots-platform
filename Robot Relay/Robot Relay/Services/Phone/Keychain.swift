import Foundation
import os

/// One generic-password item in the login Keychain.
struct KeychainItem {
    let service: String
    var account = "walky"

    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service, kSecAttrAccount as String: account]
    }

    func save(_ data: Data) {
        precondition(!RobotServices.isTestHost)
        var status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var item = query
            item[kSecValueData as String] = data
            status = SecItemAdd(item as CFDictionary, nil)
        }
        if status != errSecSuccess {
            Logger.phone.error("\(service, privacy: .public) not stored in Keychain: OSStatus \(status, privacy: .public)")
        }
    }

    func load() -> Data? {
        precondition(!RobotServices.isTestHost)
        var request = query
        request[kSecReturnData as String] = true
        var value: CFTypeRef?
        guard SecItemCopyMatching(request as CFDictionary, &value) == errSecSuccess else { return nil }
        return value as? Data
    }

    func save<Value: Encodable>(json value: Value) {
        if let data = try? JSONEncoder().encode(value) { save(data) }
    }

    func load<Value: Decodable>(json: Value.Type) -> Value? {
        load().flatMap { try? JSONDecoder().decode(Value.self, from: $0) }
    }
}
