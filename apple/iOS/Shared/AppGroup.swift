import Foundation

/// What the app and its AutoFill extension share. They're separate processes with separate
/// storage, so anything both need lives in the App Group: which database to open (a bookmark,
/// never anything from inside it), quick unlock's settings and Keychain item, and the backups.
/// Nothing in it is secret beyond what the database file itself holds, encrypted.
nonisolated enum AppGroup {
    static let id = "group.mn.lls.pocketkeepass"

    /// The shared settings. Falls back to the app's own when the App Group is missing (a build
    /// without its entitlement), so the app still works on its own.
    /// `UserDefaults` is thread-safe; Swift just can't tell.
    nonisolated(unsafe) static let defaults: UserDefaults = UserDefaults(suiteName: id) ?? .standard

    /// The shared folder, or the app's own Application Support without the App Group.
    static var container: URL {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: id)?
            .appending(path: "Library/Application Support", directoryHint: .isDirectory)
            ?? URL.applicationSupportDirectory
    }

    /// Moves what earlier builds kept in the app's own storage into the App Group, once. Run by
    /// the app at launch, before anything reads it.
    @MainActor static func migrate() {
        let shared = defaults
        guard shared !== UserDefaults.standard, !shared.bool(forKey: migratedKey) else { return }
        let keys = [RecentDatabase.key, Preferences.autoLockKey] + QuickUnlock.Keys.all
        for key in keys {
            if let value = UserDefaults.standard.object(forKey: key), shared.object(forKey: key) == nil {
                shared.set(value, forKey: key)
            }
            UserDefaults.standard.removeObject(forKey: key)
        }
        let old = URL.applicationSupportDirectory.appending(path: "Backups", directoryHint: .isDirectory)
        let new = Backups.standard.directory
        if old != new, FileManager.default.fileExists(atPath: old.path(percentEncoded: false)),
           !FileManager.default.fileExists(atPath: new.path(percentEncoded: false)) {
            try? FileManager.default.createDirectory(at: new.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? FileManager.default.moveItem(at: old, to: new)
        }
        shared.set(true, forKey: migratedKey)
    }

    private static let migratedKey = "app_group_migrated"
}
