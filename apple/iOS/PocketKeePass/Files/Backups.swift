import CryptoKit
import Foundation

/// A database's backup.
nonisolated struct Backup: Equatable, Sendable {
    /// When it was taken.
    var savedAt: Date
    /// Whether it's a save that was being written when it was cut short (it has the latest
    /// changes), rather than the file as it was before a save.
    var unfinished: Bool
}

/// Copies of each database, kept in the app's own storage, to repair a file a save damaged.
///
/// The file picker gives access to one file, not its folder, so saving can't write a temporary
/// file beside the database and swap it in: it has to overwrite the file in place. If the
/// iPhone dies or the storage app crashes halfway through, the file is damaged. So every save
/// keeps two copies here first:
///
/// - the **previous** version: the file as it was before the save. It stays until the next save
///   replaces it;
/// - the **unfinished** save: the new bytes being written. Once the write is confirmed it
///   becomes the saved copy (`saved`), so it only survives a save that was cut short or held
///   back (offline), and then it holds the changes that save was writing.
///
/// A third copy, the **saved** one, is what this iPhone last wrote to the file. It's how our
/// changes survive the file being overwritten elsewhere without them: a storage app that
/// uploads whichever copy arrives last, or another app that doesn't merge. Each unlock merges
/// it (or an unfinished save, which is newer) into the file as read (`ours`).
///
/// All are the database file itself, encrypted with the master key, so they hold nothing the
/// file doesn't. They live in Application Support, excluded from iCloud and computer backups,
/// named by a hash of the database's location. Writing one is atomic: a temporary file, synced,
/// then renamed into place, so a crash here leaves the older copy whole.
///
/// Copies of databases the user switched away from stay, in case the switch was to get away from
/// a damaged file, but only for the `maxDatabases` most recently saved databases.
///
/// Same rules as Android's `Backups.kt`. Blocking file work: call it off the main thread.
nonisolated struct Backups: Sendable {
    static let maxDatabases = 3
    private static let previous = ".kdbx"
    private static let unfinished = ".unfinished.kdbx"
    private static let saved = ".saved.kdbx"
    /// A SHA-256 hash in hex, which starts every copy's file name.
    private static let hashLength = 64

    let directory: URL

    static let standard = Backups(
        directory: URL.applicationSupportDirectory.appending(path: "Backups", directoryHint: .isDirectory)
    )

    /// The key the copies of the database at `url` are filed under.
    static func location(_ url: URL) -> String {
        url.standardizedFileURL.path(percentEncoded: false)
    }

    /// Keeps `bytes` as the previous version of the database at `location`, replacing the one before.
    func keep(_ location: String, _ bytes: Data) throws {
        try store(file(location, Self.previous), bytes)
    }

    /// Keeps `bytes`, a save about to be written to the database at `location`, until it's confirmed.
    func keepUnfinished(_ location: String, _ bytes: Data) throws {
        try store(file(location, Self.unfinished), bytes)
    }

    /// Whether a save to the database at `location` was kept here but isn't confirmed in its file.
    func hasUnfinished(_ location: String) -> Bool {
        FileManager.default.fileExists(atPath: file(location, Self.unfinished).path(percentEncoded: false))
    }

    /// The save to the database at `location` was written and confirmed: the unfinished copy is
    /// now the saved one, what this iPhone last wrote.
    func saved(_ location: String) {
        let unfinished = file(location, Self.unfinished)
        guard FileManager.default.fileExists(atPath: unfinished.path(percentEncoded: false)) else { return }
        if rename(unfinished.path(percentEncoded: false), file(location, Self.saved).path(percentEncoded: false)) != 0 {
            try? FileManager.default.removeItem(at: unfinished)
        }
    }

    /// The file opened and our copy was merged into it, with nothing left to save: the
    /// unfinished copy isn't needed.
    func finished(_ location: String) {
        try? FileManager.default.removeItem(at: file(location, Self.unfinished))
    }

    /// What this iPhone last kept of the database at `location`, to merge into the file as
    /// read: an unfinished save if there is one (it's newer), else what was last written. Nil
    /// if there's neither, or it can't be read.
    func ours(_ location: String) -> Data? {
        for kind in [Self.unfinished, Self.saved] {
            if let data = try? Data(contentsOf: file(location, kind)) { return data }
        }
        return nil
    }

    /// The best copy of the database at `location`: the unfinished save if any, else the
    /// previous version.
    func find(_ location: String) -> Backup? {
        for (kind, unfinished) in [(Self.unfinished, true), (Self.previous, false)] {
            let values = try? file(location, kind).resourceValues(forKeys: [.contentModificationDateKey])
            if let date = values?.contentModificationDate { return Backup(savedAt: date, unfinished: unfinished) }
        }
        return nil
    }

    /// The bytes of `backup`, found for `location`.
    func read(_ location: String, _ backup: Backup) throws -> Data {
        try Data(contentsOf: file(location, backup.unfinished ? Self.unfinished : Self.previous))
    }

    /// Files the copies of the database at `from` under `to`, where its backup was just saved as
    /// a new file. Copies already filed under `to` are replaced.
    func move(_ from: String, to: String) throws {
        for kind in [Self.previous, Self.unfinished, Self.saved] {
            let source = file(from, kind)
            guard FileManager.default.fileExists(atPath: source.path(percentEncoded: false)) else { continue }
            guard rename(source.path(percentEncoded: false), file(to, kind).path(percentEncoded: false)) == 0 else {
                throw CocoaError(.fileWriteUnknown)
            }
        }
    }

    /// Deletes the copies of the database at `location`, if any.
    func delete(_ location: String) {
        for kind in [Self.previous, Self.unfinished, Self.saved] {
            try? FileManager.default.removeItem(at: file(location, kind))
        }
    }

    private func store(_ target: URL, _ bytes: Data) throws {
        let manager = FileManager.default
        if !manager.fileExists(atPath: directory.path(percentEncoded: false)) {
            try manager.createDirectory(at: directory, withIntermediateDirectories: true)
            var folder = directory
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try folder.setResourceValues(values)
        }
        let temporary = directory.appending(path: target.lastPathComponent + ".tmp")
        guard manager.createFile(atPath: temporary.path(percentEncoded: false), contents: nil) else {
            throw CocoaError(.fileWriteUnknown)
        }
        do {
            let handle = try FileHandle(forWritingTo: temporary)
            defer { try? handle.close() }
            try handle.write(contentsOf: bytes)
            try handle.synchronize()
        } catch {
            try? manager.removeItem(at: temporary)
            throw error
        }
        guard rename(temporary.path(percentEncoded: false), target.path(percentEncoded: false)) == 0 else {
            try? manager.removeItem(at: temporary)
            throw CocoaError(.fileWriteUnknown)
        }
        prune(keeping: String(target.lastPathComponent.prefix(Self.hashLength)))
    }

    /// Deletes the copies of all but the `maxDatabases` most recently saved databases, always
    /// keeping `keeping`'s.
    private func prune(keeping: String) {
        let files = (try? FileManager.default.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: [.contentModificationDateKey]
        )) ?? []
        let byDatabase = Dictionary(grouping: files.filter { $0.lastPathComponent.hasSuffix(Self.previous) }) {
            String($0.lastPathComponent.prefix(Self.hashLength))
        }
        func newest(_ files: [URL]) -> Date {
            files.compactMap { try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate }
                .max() ?? .distantPast
        }
        byDatabase
            .filter { $0.key != keeping }
            .sorted { newest($0.value) > newest($1.value) }
            .dropFirst(Self.maxDatabases - 1)
            .flatMap(\.value)
            .forEach { try? FileManager.default.removeItem(at: $0) }
    }

    private func file(_ location: String, _ kind: String) -> URL {
        let hash = SHA256.hash(data: Data(location.utf8)).map { String(format: "%02x", $0) }.joined()
        return directory.appending(path: hash + kind)
    }
}
