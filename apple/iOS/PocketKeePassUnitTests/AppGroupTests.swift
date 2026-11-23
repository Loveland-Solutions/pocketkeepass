import Foundation
import Security
import Testing
@testable import PocketKeePass

/// The App Group the AutoFill extension shares with the app: without its entitlement, the
/// extension would find no database and no quick unlock.
struct AppGroupTests {
    @Test func theSharedContainerAndSettingsExist() {
        let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: AppGroup.id)
        #expect(container != nil)
        #expect(AppGroup.defaults !== UserDefaults.standard)
        // The backups are where the extension can read them.
        #expect(Backups.standard.directory.path(percentEncoded: false).hasPrefix(container?.path(percentEncoded: false) ?? "-"))
    }

    /// Quick unlock's item goes into the App Group's Keychain access group.
    @Test func theKeychainTakesTheAppGroup() {
        let service = "mn.lls.pocketkeepass.app-group-test"
        let item: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: "test",
            kSecAttrAccessGroup: AppGroup.id,
            kSecValueData: Data([1, 2, 3]),
        ]
        let query: [CFString: Any] = [kSecClass: kSecClassGenericPassword, kSecAttrService: service]
        SecItemDelete(query as CFDictionary)
        #expect(SecItemAdd(item as CFDictionary, nil) == errSecSuccess)
        #expect(SecItemDelete(query as CFDictionary) == errSecSuccess)
    }
}
