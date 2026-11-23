import Foundation
import Observation
import PocketKeePassCore
import UIKit

/// Whether the open database's changes are in its file.
enum SaveState: Equatable {
    /// Everything is saved (or nothing was changed).
    case saved
    /// A save is running.
    case saving
    /// The changes are saved on this iPhone (`Backups`) but not in the file: the iPhone is
    /// offline, and a storage app might upload the file later over whatever it became meanwhile.
    /// They go into the file, merged with what it holds by then, once the iPhone is back online,
    /// and survive a lock meanwhile.
    case waiting
    /// There are changes that aren't in the file, and why. They're lost if the database locks.
    case unsaved(SaveProblem)
}

enum SaveProblem: Equatable {
    /// Another app or device changed the file since it was opened, and it couldn't be merged
    /// with ours (see `SyncProblem`). Saving would replace that.
    case conflict
    /// The file can't be written: it's gone, or the storage app only allows reading it.
    case cantWrite
    /// The file didn't read back the same after writing.
    case writeCheckFailed
    /// The engine refused to save, and its message.
    case engine(String)
}

/// Why the file as another app or device left it couldn't be taken in.
enum SyncProblem: Equatable {
    /// The file can't be read right now (the storage app may be offline), or is damaged.
    case cantRead
    /// Our master key no longer opens it: the master password or key file was changed elsewhere.
    case keyChanged
    /// It holds a different database now.
    case otherDatabase
    /// Its key derivation settings changed. They can't be checked before they're run, so a file
    /// could ask for any amount of work: that only happens when the user unlocks it.
    case settingsChanged
}

/// Changes that came in from another app or device.
struct Incoming: Equatable {
    let at: Date
    let report: SyncReport
}

/// How the open database's file stands with other devices. Nothing secret.
struct SyncStatus: Equatable {
    /// The storage app holding the file, when iOS says (only iCloud Drive).
    var storage: String?
    /// When our changes last went into the file.
    var savedAt: Date?
    /// When the file was last read to look for changes made elsewhere.
    var checkedAt: Date?
    /// The last changes that came in from elsewhere.
    var incoming: Incoming?
    /// Why the last check couldn't take the file in, or nil if it could.
    var problem: SyncProblem?
    /// The user asked for a check (pull to refresh, "Check now"), and it's running.
    var checking = false
}

/// The one open database, app-wide. Screens read it from here and never keep their own
/// reference, so locking really drops it everywhere.
///
/// Every change to the database is saved straight away (`changed`). Saves can't be cancelled
/// halfway (a file half-written because the user left a screen is the worst outcome there is),
/// and ask iOS for time to finish if the app leaves the screen. Before each one, the file as it
/// was and the new bytes are copied to `Backups`, in case the write itself is cut short.
///
/// The file may be shared with other devices through iCloud Drive or a storage app, so while the
/// app is on screen it's read every `checkInterval` (and whenever iOS says it changed) to take
/// in changes made elsewhere. The engine always merges the two versions, so a file that went
/// back to an older version (a device that was offline, someone putting an old copy back) can't
/// undo what was saved here; if ours adds nothing, the result is just the file's version.
/// Otherwise the result is saved. A save that finds the file changed merges the same way instead
/// of overwriting it.
///
/// Two more guards keep a storage app from losing changes. Some (Google Drive's Android app, at
/// least) take a save made offline into their own copy and upload it later over whatever the
/// file became meanwhile, without checking:
///
/// - offline, a save to a file outside the app's own storage stays on this iPhone (`.waiting`)
///   and goes into the file once the iPhone has been back online for `reconnectDelay`, merged
///   with what the file holds by then. So we never overwrite another device's change unseen;
/// - each unlock merges what this iPhone last saved (`Backups.ours`) into the file as read, so
///   if another device or app overwrote our save without it, it comes back. While the database
///   stays open, the checks above do the same from memory.
///
/// The same design as Android's `Session.kt`.
@Observable
final class Session {
    /// An unlocked database and its file.
    final class Open {
        let database: UnlockedDatabase
        let recent: RecentDatabase
        let url: URL
        /// The key `Backups` files this database's copies under.
        let location: String
        /// The fingerprint of the file as we last read or wrote it.
        var fingerprint: Data
        /// The engine's `changeCount` when the database in memory last matched the file.
        /// Anything changed since then is ours alone, to be merged rather than replaced.
        var cleanCount: UInt64
        /// KDBX 3: has to be upgraded, with the user's consent, before its first change.
        var needsUpgrade: Bool

        init(database: UnlockedDatabase, recent: RecentDatabase, url: URL, fingerprint: Data, needsUpgrade: Bool) {
            self.database = database
            self.recent = recent
            self.url = url
            location = Backups.location(url)
            self.fingerprint = fingerprint
            cleanCount = database.changeCount()
            self.needsUpgrade = needsUpgrade
        }
    }

    static let checkInterval: Duration = .seconds(30)
    /// How long the iPhone must be back online before a save that waited for it is written, so
    /// the storage app has had a moment to fetch what other devices saved meanwhile.
    static let reconnectDelay: Duration = .seconds(10)
    /// How many times one save merges in another device's changes before giving up.
    private static let maxMerges = 3

    private(set) var open: Open?
    /// Bumped on every change to the open database, so screens know to read it again.
    private(set) var revision = 0
    private(set) var saveState = SaveState.saved
    private(set) var sync = SyncStatus()
    /// Set each time changes made elsewhere come in, for saying so.
    private(set) var lastIncoming: Incoming?

    /// The unlocked database, or nil while locked.
    var database: UnlockedDatabase? { open?.database }
    /// The file's name, for the title bar.
    var fileName: String { open?.recent.name ?? "" }
    var isUnlocked: Bool { open != nil }
    var needsUpgrade: Bool { open?.needsUpgrade ?? false }

    @ObservationIgnored private let backups: Backups
    @ObservationIgnored private let connection: Connection
    @ObservationIgnored private let fileLock = FileLock()
    @ObservationIgnored private var saveTask: Task<Void, Never>?
    /// Another change arrived while a save was running: save again when it's done. Possibly to
    /// another database than the running save's, if it was locked and another unlocked meanwhile.
    @ObservationIgnored private var saveAgain = false
    @ObservationIgnored private var restoreTask: Task<Void, Never>?
    @ObservationIgnored private var checkTask: Task<Void, Never>?
    @ObservationIgnored private var watchTask: Task<Void, Never>?
    @ObservationIgnored private var reconnectTask: Task<Void, Never>?
    @ObservationIgnored private var watcher: FileWatcher?
    /// Whether the app is on screen, so the file is worth watching.
    @ObservationIgnored private var visible = false

    init(backups: Backups = .standard, connection: Connection = Connection()) {
        self.backups = backups
        self.connection = connection
        connection.onReconnect = { [weak self] in self?.reconnected() }
    }

    /// A database was just unlocked from `file`. Any other one is locked first.
    func start(_ database: UnlockedDatabase, recent: RecentDatabase, url: URL, fingerprint: Data, needsUpgrade: Bool) {
        lock()
        let open = Open(database: database, recent: recent, url: url, fingerprint: fingerprint, needsUpgrade: needsUpgrade)
        self.open = open
        saveState = .saved
        sync = SyncStatus(storage: FileAccess.storageName(url), checkedAt: .now)
        lastIncoming = nil
        revision += 1
        restoreTask = Task { await restoreOurs(open) }
        startWatching()
    }

    /// Wipes the decrypted database at once (not when it's garbage collected) and forgets it.
    /// Unsaved changes are lost.
    ///
    /// The wipe runs off the main thread: it waits for any engine call still running (a save's
    /// or a check's key derivation), and the screen mustn't freeze meanwhile. A save that already
    /// has its encrypted bytes still writes them, so the change reaches the file, but a slow
    /// storage app can't keep the database decrypted in memory.
    func lock() {
        guard let open else { return }
        stopWatching()
        checkTask?.cancel()
        reconnectTask?.cancel()
        self.open = nil
        saveState = .saved
        sync = SyncStatus()
        lastIncoming = nil
        revision += 1
        Clipboard.shared.databaseLocked()
        let database = open.database
        Task.detached(priority: .userInitiated) { database.lock() }
    }

    // MARK: Changing

    /// Runs a change to the open database off the main thread, then saves it. Upgrades a KDBX 3
    /// database first: ask the user before calling this for one (`needsUpgrade`).
    @discardableResult
    func change<T: Sendable>(_ edit: @escaping @Sendable (UnlockedDatabase) throws -> T) async throws -> T {
        guard let open else { throw KeePassError.Locked(message: "") }
        let upgrade = open.needsUpgrade
        let database = open.database
        let result = try await Task.detached(priority: .userInitiated) {
            if upgrade { try database.upgradeToKdbx4() }
            return try edit(database)
        }.value
        if upgrade { open.needsUpgrade = false }
        if self.open === open { changed() }
        return result
    }

    /// Converts a KDBX 3 database to KDBX 4 in memory, once the user agreed, so the edit they
    /// asked for can follow. The file changes with the first save.
    func upgrade() async throws {
        guard let open else { throw KeePassError.Locked(message: "") }
        guard open.needsUpgrade else { return }
        let database = open.database
        try await Task.detached(priority: .userInitiated) { try database.upgradeToKdbx4() }.value
        open.needsUpgrade = false
    }

    /// Like `change`, for an edit that reports whether it changed anything (`update_entry`,
    /// `move_entry`, …). One that didn't needs no save, unless the database was upgraded on the
    /// way. Returns what the edit returned.
    @discardableResult
    func changeIfNeeded(_ edit: @escaping @Sendable (UnlockedDatabase) throws -> Bool) async throws -> Bool {
        guard let open else { throw KeePassError.Locked(message: "") }
        let upgrade = open.needsUpgrade
        let database = open.database
        let changed = try await Task.detached(priority: .userInitiated) {
            if upgrade { try database.upgradeToKdbx4() }
            return try edit(database)
        }.value
        if upgrade { open.needsUpgrade = false }
        if self.open === open, changed || upgrade { self.changed() }
        return changed
    }

    /// The open database was just changed in memory. Screens re-read it, and it's saved.
    func changed() {
        revision += 1
        save()
    }

    /// Saves the open database to its file. Unless `overwrite` is true, a file another app or
    /// device changed in a way that can't be merged isn't replaced: the state becomes
    /// `.unsaved(.conflict)` instead, for the user to decide.
    func save(overwrite: Bool = false) {
        guard let open else { return }
        saveState = .saving
        if saveTask != nil {
            saveAgain = true
            return
        }
        saveTask = Task {
            let background = UIApplication.shared.beginBackgroundTask(withName: "Save")
            defer { UIApplication.shared.endBackgroundTask(background) }
            // What this iPhone saved before must be in the database before anything is written.
            await restoreTask?.value
            let written = await fileLock.run { await write(open, overwrite: overwrite) }
            saveTask = nil
            let again = saveAgain
            saveAgain = false
            guard self.open === open else {
                // Locked meanwhile. A change to a database unlocked since is still to be saved.
                if again { save() }
                return
            }
            if again, !written.isFailure {
                // Straight on to the next save: "Saved" in between would be untrue.
                save()
                return
            }
            switch written {
            case .done: saveState = .saved
            case .held: saveState = .waiting
            case .failed(let problem): saveState = .unsaved(problem)
            }
        }
    }

    /// The open database as the bytes of a `.kdbx` file, for "Save a copy". The database's own
    /// file is left alone.
    func copyBytes() async throws -> Data {
        guard let database else { throw KeePassError.Locked(message: "") }
        return try await Task.detached(priority: .userInitiated) { try database.save() }.value
    }

    /// Throws away the changes and opens the file as it is now, with the same master key. Used
    /// after a conflict. Returns false if the master key no longer opens it (it was changed
    /// elsewhere); the database is locked then, for the user to unlock with the new one.
    func reload() async -> Bool {
        guard let open else { return false }
        let database = open.database
        let url = open.url
        let reopened = await Task.detached(priority: .userInitiated) { () -> (UnlockedDatabase, Data, Bool)? in
            guard var keyHash = try? database.keyHash() else { return nil }
            defer { keyHash.wipe() }
            guard let file = try? FileAccess.read(url),
                  let fresh = try? UnlockedDatabase.openWithKeyHash(file: file, keyHash: keyHash)
            else { return nil }
            let needsUpgrade = (try? fresh.summary().needsUpgrade) ?? false
            return (fresh, FileAccess.fingerprint(file), needsUpgrade)
        }.value
        guard self.open === open else {
            if let reopened { Task.detached { reopened.0.lock() } }
            return false
        }
        guard let (fresh, fingerprint, needsUpgrade) = reopened else {
            lock()
            return false
        }
        start(fresh, recent: open.recent, url: url, fingerprint: fingerprint, needsUpgrade: needsUpgrade)
        return true
    }

    // MARK: Watching the file

    /// The app came on screen, or left it. Changes elsewhere are watched for meanwhile.
    func setVisible(_ visible: Bool) {
        self.visible = visible
        if visible {
            startWatching()
            check()
        } else {
            stopWatching()
        }
    }

    /// Reads the file now, for changes made elsewhere (pull to refresh). Returns when done.
    func checkNow() async {
        guard let open else { return }
        sync.checking = true
        check()
        await checkTask?.value
        if self.open === open { sync.checking = false }
    }

    private func startWatching() {
        guard let open, visible, watchTask == nil else { return }
        watcher = FileWatcher(open.url) { [weak self] in
            Task { @MainActor in self?.check() }
        }
        watchTask = Task {
            while !Task.isCancelled {
                try? await Task.sleep(for: Self.checkInterval)
                guard !Task.isCancelled else { return }
                // In case the reconnect callback was missed (the app was suspended meanwhile).
                if saveState == .waiting, let online = connection.onlineFor, online >= Self.reconnectDelay {
                    save()
                } else {
                    check()
                }
            }
        }
    }

    private func stopWatching() {
        watchTask?.cancel()
        watchTask = nil
        watcher?.stop()
        watcher = nil
    }

    /// The iPhone is back online: write a save that was waiting for it, after `reconnectDelay`.
    private func reconnected() {
        guard saveState == .waiting else { return }
        reconnectTask?.cancel()
        reconnectTask = Task {
            try? await Task.sleep(for: Self.reconnectDelay)
            guard !Task.isCancelled, saveState == .waiting, connection.isOnline else { return }
            save()
        }
    }

    /// Reads the file and takes in what changed elsewhere, if anything did.
    private func check() {
        guard let open, checkTask == nil else { return }
        checkTask = Task {
            let outcome: Taken? = await fileLock.run {
                guard self.open === open else { return nil }
                let url = open.url
                let presenter = watcher
                guard let current = try? await Self.io({ try FileAccess.read(url, presenter: presenter) }) else {
                    return .failed(.cantRead)
                }
                if FileAccess.fingerprint(current) == open.fingerprint { return .same }
                return await take(open, current)
            }
            checkTask = nil
            guard self.open === open, let outcome else { return }
            switch outcome {
            // take() records what it took in; these two never reached it.
            case .same:
                sync.checkedAt = .now
                sync.problem = nil
            case .failed(let problem):
                sync.checkedAt = .now
                sync.problem = problem
            case .changes(let report):
                if report.merged { save() }
            }
        }
    }

    private enum Taken {
        case same
        case changes(SyncReport)
        case failed(SyncProblem)
    }

    /// Takes in `current`, the file as another app or device left it, merged with ours. If ours
    /// adds nothing to it, the database now matches the file; otherwise (`merged`) the result
    /// needs saving. Records the outcome in `sync`. Runs key derivation, so it's slow.
    private func take(_ open: Open, _ current: Data) async -> Taken {
        let database = open.database
        let cleanCount = open.cleanCount
        let taken: Taken
        do {
            let (report, needsUpgrade) = try await Task.detached(priority: .utility) {
                let report = try database.syncWithFile(file: current, unchangedSince: cleanCount)
                return (report, (try? database.summary().needsUpgrade) ?? true)
            }.value
            open.fingerprint = FileAccess.fingerprint(current)
            // The engine's count from the moment it took the file in, so a change made just
            // after isn't counted as already in the file.
            if !report.merged { open.cleanCount = report.changeCount }
            // A KDBX 3 file replaced by its KDBX 4 version from another device.
            open.needsUpgrade = needsUpgrade
            taken = .changes(report)
        } catch KeePassError.WrongKey {
            taken = .failed(.keyChanged)
        } catch KeePassError.Invalid {
            taken = .failed(.otherDatabase)
        } catch KeePassError.SettingsChanged {
            taken = .failed(.settingsChanged)
        } catch KeePassError.Locked {
            taken = .same
        } catch {
            taken = .failed(.cantRead) // damaged, or still arriving
        }
        guard self.open === open else { return taken }
        sync.checkedAt = .now
        switch taken {
        case .same: sync.problem = nil
        case .failed(let problem): sync.problem = problem
        case .changes(let report):
            sync.problem = nil
            if report.hasChanges {
                let incoming = Incoming(at: .now, report: report)
                sync.incoming = incoming
                lastIncoming = incoming
            }
            revision += 1
        }
        return taken
    }

    /// Merges what this iPhone last saved to the file (or a save that was held back or cut
    /// short) into the database as just read from it, and saves if that brought anything back:
    /// another device or app overwrote the file without our change. Usually the file is exactly
    /// what we saved, and nothing runs.
    private func restoreOurs(_ open: Open) async {
        let backups = backups
        let location = open.location
        let database = open.database
        let fingerprint = open.fingerprint
        let restored = await fileLock.run {
            guard self.open === open else { return false }
            return await Task.detached(priority: .utility) { () -> Bool in
                guard let ours = backups.ours(location) else { return false }
                if FileAccess.fingerprint(ours) == fingerprint {
                    backups.finished(location)
                    return false
                }
                // Another master key since (changed elsewhere), another database, or a copy that
                // won't open: the copy stays until the next save replaces it.
                guard let report = try? database.mergeSavedCopy(file: ours) else { return false }
                if !report.merged { backups.finished(location) }
                return report.merged
            }.value
        }
        restoreTask = nil
        if restored, self.open === open { changed() }
    }

    // MARK: Writing

    private enum Written: Equatable {
        /// In the file.
        case done
        /// Kept on this iPhone until it's back online (`.waiting`).
        case held
        case failed(SaveProblem)

        var isFailure: Bool {
            if case .failed = self { return true }
            return false
        }
    }

    /// Saves to the database's own file. A file changed elsewhere is merged with ours first
    /// (unless `overwrite`), and the result saved. Offline, a file outside the app's storage
    /// isn't touched: the save is kept on this iPhone instead (`.held`). Hold `fileLock`.
    private func write(_ open: Open, overwrite: Bool) async -> Written {
        let database = open.database
        let url = open.url
        let location = open.location
        let backups = backups
        let presenter = watcher
        // Each round merges what another device saved meanwhile; it would take a device saving
        // every few seconds to need more than a couple.
        for _ in 0...Self.maxMerges {
            let count: UInt64
            let bytes: Data
            do {
                (count, bytes) = try await Task.detached(priority: .userInitiated) {
                    let count = database.changeCount()
                    return (count, try database.save())
                }.value
            } catch KeePassError.Locked {
                return .done // locked before the save started; nothing to do
            } catch {
                return .failed(.engine(error.userMessage))
            }

            if !FileAccess.isOnThisDevice(url) && !connection.isOnline {
                // A storage app could upload this later over whatever the file is by then.
                do {
                    try await Self.io { try backups.keepUnfinished(location, bytes) }
                    return .held
                } catch {
                    return .failed(.cantWrite) // nowhere to keep it: in memory only
                }
            }

            let current: Data?
            do {
                current = try await Self.io { try FileAccess.read(url, presenter: presenter) }
            } catch {
                // Replacing it anyway: the write itself will say if the file is really out of reach.
                guard overwrite else { return .failed(.cantWrite) }
                current = nil
            }
            if let current {
                let known = FileAccess.fingerprint(current) == open.fingerprint
                if !overwrite && !known {
                    switch await take(open, current) {
                    case .changes(let report):
                        if report.merged { continue }
                        return .done
                    default:
                        return .failed(.conflict)
                    }
                }
                // Replacing another app's version: keep it only if it's a database our key
                // opens, so a damaged or foreign file never pushes out a backup that works.
                let keepIt = known ? true : await Self.opens(current, with: database)
                if keepIt {
                    try? await Self.io { try backups.keep(location, current) }
                }
            }
            // If a copy can't be kept (storage full), the save goes ahead: older copies are still
            // there, and refusing would leave the changes to be lost at the next lock.
            try? await Self.io { try backups.keepUnfinished(location, bytes) }
            do {
                try await Self.io { try FileAccess.write(url, bytes, presenter: presenter) }
            } catch is WriteCheckFailed {
                return .failed(.writeCheckFailed)
            } catch {
                // Whatever the storage app throws: none of it may lose the changes.
                return .failed(.cantWrite)
            }
            open.fingerprint = FileAccess.fingerprint(bytes)
            open.cleanCount = count
            await Self.io { backups.saved(location) }
            if self.open === open { sync.savedAt = .now }
            return .done
        }
        return .failed(.conflict)
    }

    /// Whether `file` opens with the open database's master key. Runs key derivation, so it's slow.
    private static func opens(_ file: Data, with database: UnlockedDatabase) async -> Bool {
        await Task.detached(priority: .utility) {
            guard var keyHash = try? database.keyHash() else { return false }
            defer { keyHash.wipe() }
            guard let other = try? UnlockedDatabase.openWithKeyHash(file: file, keyHash: keyHash) else { return false }
            other.lock()
            return true
        }.value
    }

    /// Runs blocking file work off the main thread.
    private static func io<T: Sendable>(_ work: @escaping @Sendable () throws -> T) async throws -> T {
        try await Task.detached(priority: .userInitiated, operation: work).value
    }

    private static func io<T: Sendable>(_ work: @escaping @Sendable () -> T) async -> T {
        await Task.detached(priority: .userInitiated, operation: work).value
    }
}

/// Saving and checking for changes take turns with the file.
private final class FileLock {
    private var busy = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func run<T>(_ body: () async -> T) async -> T {
        if busy {
            await withCheckedContinuation { waiters.append($0) }
        } else {
            busy = true
        }
        defer {
            if waiters.isEmpty { busy = false } else { waiters.removeFirst().resume() }
        }
        return await body()
    }
}

extension SyncReport {
    /// Whether anything at all came in.
    var hasChanges: Bool {
        entriesAdded + entriesChanged + entriesRemoved + groupsChanged > 0
    }
}
