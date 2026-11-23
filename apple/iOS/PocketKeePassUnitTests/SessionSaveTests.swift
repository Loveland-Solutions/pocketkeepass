import Foundation
import PocketKeePassCore
import Testing
@testable import PocketKeePass

/// Saving through `Session` into a real file: the write, the backups, merging a file another
/// device changed, bringing back a save that was overwritten elsewhere, and the KDBX 3 upgrade.
/// The files are copies of the engine's fixtures in a temporary folder inside the app's own
/// storage, so saves are never held back for being offline.
@MainActor
struct SessionSaveTests {
    private let folder: URL
    private let backups: Backups

    init() throws {
        folder = URL.temporaryDirectory.appending(path: "SessionSaveTests-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        backups = Backups(directory: folder.appending(path: "backups", directoryHint: .isDirectory))
    }

    private static let fixtures = URL(filePath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .appending(path: "core/tests/fixtures")

    /// A copy of a fixture to save into.
    private func copy(_ fixture: String) throws -> URL {
        let url = folder.appending(path: fixture)
        try FileManager.default.copyItem(at: Self.fixtures.appending(path: fixture), to: url)
        return url
    }

    private static func open(_ url: URL) throws -> UnlockedDatabase {
        try UnlockedDatabase.open(file: Data(contentsOf: url), password: Data("test-password".utf8), keyFile: nil)
    }

    /// A session with the database at `url` unlocked, as the unlock screen leaves it.
    private func unlocked(_ url: URL) throws -> Session {
        let session = Session(backups: backups)
        let database = try Self.open(url)
        session.start(
            database,
            recent: RecentDatabase(bookmark: Data(), name: url.lastPathComponent),
            url: url,
            fingerprint: FileAccess.fingerprint(try Data(contentsOf: url)),
            needsUpgrade: try database.summary().needsUpgrade
        )
        return session
    }

    private func addEntry(_ title: String, to session: Session) async throws {
        try await session.change { database in
            try database.createEntry(
                groupId: database.listGroup(id: nil).group.id,
                fields: [FieldEdit(key: "Title", value: Data(title.utf8), previousKey: nil, isProtected: false)],
                tags: []
            )
        }
    }

    /// Another device's change, written straight into the file.
    private func addEntryElsewhere(_ title: String, in url: URL) throws {
        let other = try Self.open(url)
        defer { other.lock() }
        _ = try other.createEntry(
            groupId: other.listGroup(id: nil).group.id,
            fields: [FieldEdit(key: "Title", value: Data(title.utf8), previousKey: nil, isProtected: false)],
            tags: []
        )
        try other.save().write(to: url)
    }

    private func titles(in url: URL) throws -> Set<String> {
        let database = try Self.open(url)
        defer { database.lock() }
        return Set(try database.search(query: "test entry").map(\.entry.title))
    }

    private func waitUntilSaved(_ session: Session) async throws {
        for _ in 0..<600 {
            if session.saveState == .saved { return }
            try await Task.sleep(for: .milliseconds(50))
        }
        Issue.record("still \(session.saveState)")
    }

    @Test func aNewEntryIsSavedAndBackedUp() async throws {
        let url = try copy("kdbx4-argon2id-chacha20.kdbx")
        let original = try Data(contentsOf: url)
        let session = try unlocked(url)

        try await addEntry("test entry one", to: session)
        try await waitUntilSaved(session)

        #expect(try titles(in: url) == ["test entry one"])
        let location = Backups.location(url)
        // The file as it was, and what was written (confirmed, so no unfinished save is left).
        #expect(try backups.read(location, #require(backups.find(location))) == original)
        #expect(!backups.hasUnfinished(location))
        #expect(backups.ours(location) == (try Data(contentsOf: url)))
        session.lock()
    }

    /// What the Mac's AutoFill extension does with a new passkey when it can't reach the file:
    /// keeps the new bytes as an unfinished save. The next unlock writes them into the file.
    @Test func anUnfinishedSaveLeftByAutoFillReachesTheFileAtTheNextUnlock() async throws {
        let url = try copy("kdbx4-argon2id-chacha20.kdbx")
        let extensionCopy = try Self.open(url)
        _ = try extensionCopy.createEntry(
            groupId: extensionCopy.listGroup(id: nil).group.id,
            fields: [FieldEdit(key: "Title", value: Data("test entry from autofill".utf8), previousKey: nil, isProtected: false)],
            tags: []
        )
        try backups.keepUnfinished(Backups.location(url), extensionCopy.save())
        extensionCopy.lock()

        let session = try unlocked(url)
        for _ in 0..<600 where (try titles(in: url)).isEmpty {
            try await Task.sleep(for: .milliseconds(50))
        }
        #expect(try titles(in: url) == ["test entry from autofill"])
        session.lock()
    }

    /// The extension's bytes, as `anUnfinishedSaveLeftByAutoFill…` makes them.
    private func leaveUnfinishedSave(_ title: String, for url: URL) throws {
        let extensionCopy = try Self.open(url)
        defer { extensionCopy.lock() }
        _ = try extensionCopy.createEntry(
            groupId: extensionCopy.listGroup(id: nil).group.id,
            fields: [FieldEdit(key: "Title", value: Data(title.utf8), previousKey: nil, isProtected: false)],
            tags: []
        )
        try backups.keepUnfinished(Backups.location(url), extensionCopy.save())
    }

    /// The same while the app is open: the next check takes it in and writes it.
    @Test func anUnfinishedSaveLeftByAutoFillWhileOpenReachesTheFileAtTheNextCheck() async throws {
        let url = try copy("kdbx4-argon2id-chacha20.kdbx")
        let session = try unlocked(url)
        await session.checkNow() // after the unlock's own restore, which would take it in otherwise
        try leaveUnfinishedSave("test entry from autofill", for: url)

        await session.checkNow()
        try await waitUntilSaved(session)
        #expect(try titles(in: url) == ["test entry from autofill"])
        #expect(session.lastIncoming?.report.entriesAdded == 1)
        session.lock()
    }

    /// A change made in the app before the next check doesn't replace the extension's save.
    @Test func aSaveInTheAppKeepsAnUnfinishedSaveLeftByAutoFill() async throws {
        let url = try copy("kdbx4-argon2id-chacha20.kdbx")
        let session = try unlocked(url)
        await session.checkNow() // after the unlock's own restore, which would take it in otherwise
        try leaveUnfinishedSave("test entry from autofill", for: url)

        try await addEntry("test entry here", to: session)
        try await waitUntilSaved(session)
        #expect(try titles(in: url) == ["test entry from autofill", "test entry here"])
        #expect(!backups.hasUnfinished(Backups.location(url)))
        session.lock()
    }

    #if os(macOS)
    /// The Mac keeps the file as last read or written for its AutoFill extension, which can't
    /// open the file itself.
    @Test func theMacKeepsTheLatestFileForAutoFill() async throws {
        let url = try copy("kdbx4-argon2id-chacha20.kdbx")
        let location = Backups.location(url)
        let session = Session(backups: backups)
        let database = try Self.open(url)
        session.start(
            database, recent: RecentDatabase(bookmark: Data(), name: url.lastPathComponent), url: url,
            fingerprint: FileAccess.fingerprint(try Data(contentsOf: url)), needsUpgrade: false,
            file: try Data(contentsOf: url)
        )
        for _ in 0..<100 where backups.latest(location) == nil { try await Task.sleep(for: .milliseconds(50)) }
        #expect(backups.latest(location) == (try Data(contentsOf: url)))

        try await addEntry("test entry one", to: session)
        try await waitUntilSaved(session)
        let saved = try Data(contentsOf: url)
        for _ in 0..<100 where backups.latest(location) != saved { try await Task.sleep(for: .milliseconds(50)) }
        #expect(backups.latest(location) == saved)
        session.lock()
    }
    #endif

    @Test func aFileChangedElsewhereIsMergedNotReplaced() async throws {
        let url = try copy("kdbx4-argon2id-chacha20.kdbx")
        let session = try unlocked(url)
        try addEntryElsewhere("test entry from elsewhere", in: url)

        try await addEntry("test entry here", to: session)
        try await waitUntilSaved(session)

        #expect(try titles(in: url) == ["test entry from elsewhere", "test entry here"])
        #expect(session.sync.problem == nil)
        session.lock()
    }

    @Test func aSaveOverwrittenElsewhereComesBackAtTheNextUnlock() async throws {
        let url = try copy("kdbx4-argon2id-chacha20.kdbx")
        let original = try Data(contentsOf: url)
        let first = try unlocked(url)
        try await addEntry("test entry kept", to: first)
        try await waitUntilSaved(first)
        first.lock()

        // Another app puts the old file back, without our entry.
        try original.write(to: url)
        let second = try unlocked(url)
        // The restore merges our saved copy in and saves.
        for _ in 0..<600 where (try titles(in: url)).isEmpty {
            try await Task.sleep(for: .milliseconds(50))
        }
        try await waitUntilSaved(second)
        #expect(try titles(in: url) == ["test entry kept"])
        second.lock()
    }

    @Test func aKdbx3DatabaseIsUpgradedByItsFirstChange() async throws {
        let url = try copy("browse.kdbx")
        let session = try unlocked(url)
        #expect(session.needsUpgrade)

        try await addEntry("test entry upgraded", to: session)
        try await waitUntilSaved(session)

        #expect(!session.needsUpgrade)
        let reopened = try Self.open(url)
        defer { reopened.lock() }
        #expect(try reopened.summary().formatVersion.hasPrefix("4"))
        #expect(try titles(in: url) == ["test entry upgraded"])
        session.lock()
    }

    @Test func aFileThatCantBeWrittenLeavesTheChangesUnsaved() async throws {
        let url = try copy("kdbx4-argon2id-chacha20.kdbx")
        let session = try unlocked(url)
        try FileManager.default.removeItem(at: url)

        try await addEntry("test entry lost", to: session)
        for _ in 0..<600 where session.saveState == .saving {
            try await Task.sleep(for: .milliseconds(50))
        }
        #expect(session.saveState == .unsaved(.cantWrite))
        session.lock()
    }
}

/// The copies `Backups` keeps, on their own.
struct BackupsTests {
    private let backups = Backups(
        directory: URL.temporaryDirectory.appending(path: "BackupsTests-\(UUID().uuidString)", directoryHint: .isDirectory)
    )

    @Test func anUnfinishedSaveBecomesTheSavedCopy() throws {
        try backups.keep("a", Data([1]))
        try backups.keepUnfinished("a", Data([2]))
        #expect(backups.find("a")?.unfinished == true)
        #expect(backups.ours("a") == Data([2]))

        backups.saved("a")
        #expect(!backups.hasUnfinished("a"))
        #expect(backups.find("a")?.unfinished == false)
        #expect(try backups.read("a", #require(backups.find("a"))) == Data([1]))
        #expect(backups.ours("a") == Data([2]))
    }

    @Test func copiesMoveWithABackupSavedAsANewFile() throws {
        try backups.keep("old", Data([1]))
        try backups.keepUnfinished("old", Data([2]))
        backups.saved("old")
        try backups.keepUnfinished("old", Data([3]))

        try backups.move("old", to: "new")
        #expect(backups.find("old") == nil)
        #expect(backups.ours("old") == nil)
        #expect(backups.find("new")?.unfinished == true)
        #expect(backups.ours("new") == Data([3]))
        backups.finished("new")
        #expect(try backups.read("new", #require(backups.find("new"))) == Data([1]))
        #expect(backups.ours("new") == Data([2]))
    }

    @Test func finishedDropsOnlyTheUnfinishedSave() throws {
        try backups.keepUnfinished("a", Data([2]))
        backups.saved("a")
        try backups.keepUnfinished("a", Data([3]))
        backups.finished("a")
        #expect(backups.ours("a") == Data([2]))
    }

    @Test func onlyTheMostRecentDatabasesKeepCopies() throws {
        for name in ["a", "b", "c", "d"] {
            try backups.keep(name, Data(name.utf8))
            Thread.sleep(forTimeInterval: 0.01) // distinct modification times
        }
        #expect(backups.find("a") == nil)
        #expect(backups.find("b") != nil)
        #expect(backups.find("d") != nil)
    }

    @Test func theFolderIsLeftOutOfBackups() throws {
        try backups.keep("a", Data([1]))
        #expect(try backups.directory.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup == true)
    }
}
