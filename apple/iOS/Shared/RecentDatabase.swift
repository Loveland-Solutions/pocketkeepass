import Foundation

/// The database (and key file) last unlocked, so the unlock screen can offer it again, and the
/// AutoFill extension knows which one to open. Stored in the App Group's UserDefaults as
/// bookmarks and file names: locations only, never a password or anything from inside the
/// database.
nonisolated struct RecentDatabase: Codable, Equatable, Sendable {
    /// Made fresh each time a database is picked. Quick unlock and its expiry record belong to
    /// this, not to the file's location, which a bookmark can follow as the file moves.
    var id = UUID().uuidString
    var bookmark: Data
    var name: String
    var keyFileBookmark: Data? = nil
    var keyFileName: String? = nil
    /// Where the file was when last picked or unlocked (`Backups.location`), the key its backups
    /// are kept under. Remembered so they can be found when the file is gone and its bookmark
    /// no longer resolves.
    var location: String? = nil

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
        location = try container.decodeIfPresent(String.self, forKey: .location)
    }

    static let key = "recent_database"

    static func load() -> RecentDatabase? {
        guard let data = AppGroup.defaults.data(forKey: key),
              let recent = try? JSONDecoder().decode(RecentDatabase.self, from: data)
        else { return nil }
        recent.save() // keeps an `id` given to an older record
        return recent
    }

    func save() {
        if let data = try? JSONEncoder().encode(self) {
            AppGroup.defaults.set(data, forKey: Self.key)
        }
    }
}
