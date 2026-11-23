import Foundation

/// The database (and key file) last unlocked, so the unlock screen can offer it again. Stored in
/// UserDefaults as bookmarks and file names: locations only, never a password or anything
/// from inside the database.
nonisolated struct RecentDatabase: Codable, Equatable, Sendable {
    /// Made fresh each time a database is picked. Quick unlock and its expiry record belong to
    /// this, not to the file's location, which a bookmark can follow as the file moves.
    var id = UUID().uuidString
    var bookmark: Data
    var name: String
    var keyFileBookmark: Data? = nil
    var keyFileName: String? = nil

    init(bookmark: Data, name: String) {
        self.bookmark = bookmark
        self.name = name
    }

    /// Records saved before `id` existed get one.
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decodeIfPresent(String.self, forKey: .id) ?? UUID().uuidString
        bookmark = try container.decode(Data.self, forKey: .bookmark)
        name = try container.decode(String.self, forKey: .name)
        keyFileBookmark = try container.decodeIfPresent(Data.self, forKey: .keyFileBookmark)
        keyFileName = try container.decodeIfPresent(String.self, forKey: .keyFileName)
    }

    private static let key = "recent_database"

    static func load() -> RecentDatabase? {
        guard let data = UserDefaults.standard.data(forKey: key),
              let recent = try? JSONDecoder().decode(RecentDatabase.self, from: data)
        else { return nil }
        recent.save() // keeps an `id` given to an older record
        return recent
    }

    func save() {
        if let data = try? JSONEncoder().encode(self) {
            UserDefaults.standard.set(data, forKey: Self.key)
        }
    }
}
