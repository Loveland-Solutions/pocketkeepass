import Foundation

/// The database (and key file) last unlocked, so the unlock screen can offer it again. Stored in
/// UserDefaults as bookmarks and file names: locations only, never a password or anything
/// from inside the database.
nonisolated struct RecentDatabase: Codable, Equatable, Sendable {
    var bookmark: Data
    var name: String
    var keyFileBookmark: Data? = nil
    var keyFileName: String? = nil

    private static let key = "recent_database"

    static func load() -> RecentDatabase? {
        guard let data = UserDefaults.standard.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(RecentDatabase.self, from: data)
    }

    func save() {
        if let data = try? JSONEncoder().encode(self) {
            UserDefaults.standard.set(data, forKey: Self.key)
        }
    }
}
