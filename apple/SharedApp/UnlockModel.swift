import Foundation
import LocalAuthentication
import Observation
import PocketKeePassCore

/// The unlock screen's state. The typed password is kept here, never in saved state (SwiftUI's
/// scene storage goes to disk), and cleared after every attempt.
@Observable
final class UnlockModel {
    private(set) var recent = RecentDatabase.load()
    var password = ""
    var showPassword = false
    private(set) var isUnlocking = false
    private(set) var error: String?
    /// A copy of the database to offer when its file is missing or won't open as a database.
    private(set) var backup: Backup?
    /// The key `backup` is filed under.
    private var backupLocation: String?
    /// Writing the backup back is running.
    private(set) var isRestoring = false
    /// The backup was just restored, into the database's file or a new one.
    private(set) var restored = false
    /// What Face ID or Touch ID can do on this device, read again whenever the screen shows.
    private(set) var biometry = QuickUnlock.biometry()
    /// The Face ID prompt opens by itself once per lock; this model lives as long as one locked
    /// spell of the unlock screen.
    private var autoPrompted = false

    /// A database was picked in the system's file picker.
    func pickedDatabase(_ url: URL, quickUnlock: QuickUnlock) {
        do {
            // Another database: its key file (if any) is the user's to add again. Even the same
            // file again: the user may be starting over with different credentials.
            var picked = RecentDatabase(bookmark: try FileAccess.bookmark(url), name: url.lastPathComponent)
            picked.location = Backups.location(url)
            picked.save()
            recent = picked
            quickUnlock.disable()
            error = nil
            clearBackup()
        } catch {
            self.error = error.userMessage
        }
    }

    func pickedKeyFile(_ url: URL, quickUnlock: QuickUnlock) {
        guard var recent else { return }
        do {
            recent.keyFileBookmark = try FileAccess.bookmark(url)
            recent.keyFileName = url.lastPathComponent
            recent.save()
            self.recent = recent
            // The stored hash was made without this key file; it would open the database
            // anyway, confusingly.
            quickUnlock.disable()
            error = nil
            clearBackup()
        } catch {
            self.error = String(localized: "PocketKeePass can’t open the key file anymore. It may have been moved, renamed, or deleted. Remove it and add it again.")
        }
    }

    func removeKeyFile(quickUnlock: QuickUnlock) {
        recent?.keyFileBookmark = nil
        recent?.keyFileName = nil
        recent?.save()
        quickUnlock.disable()
        clearBackup()
    }

    // MARK: Quick unlock

    /// Reads again what may have changed while the screen was away (Face ID set up, removed,
    /// or refused for the app in Settings).
    func refreshBiometry() {
        biometry = QuickUnlock.biometry()
    }

    /// Whether to show the "Unlock with Face ID" button.
    func canQuickUnlock(_ quickUnlock: QuickUnlock) -> Bool {
        guard let recent, quickUnlock.isEnabled(for: recent), !quickUnlock.isPasswordDue(recent) else { return false }
        if case .ready = biometry.state { return true }
        return biometry.state == .lockedOut // tapping explains
    }

    /// Whether the master password is needed this time although quick unlock is on.
    func isPasswordDue(_ quickUnlock: QuickUnlock) -> Bool {
        guard let recent else { return false }
        return quickUnlock.isEnabled(for: recent) && quickUnlock.isPasswordDue(recent)
    }

    /// Opens the Face ID prompt once after each lock, when the screen is showing and active.
    func offerQuickUnlock(into session: Session, quickUnlock: QuickUnlock) {
        guard !autoPrompted else { return }
        autoPrompted = true
        refreshBiometry()
        guard canQuickUnlock(quickUnlock) else {
            // Says why now, rather than when the user reaches for the button.
            if let recent, quickUnlock.check(recent, biometry: biometry) == .invalidated { invalidated() }
            return
        }
        self.quickUnlock(into: session, quickUnlock: quickUnlock)
    }

    /// Asks for Face ID, then opens the database with the stored key hash.
    func quickUnlock(into session: Session, quickUnlock: QuickUnlock) {
        guard let recent, !isUnlocking else { return }
        refreshBiometry()
        switch quickUnlock.check(recent, biometry: biometry) {
        case nil: break
        case .invalidated: invalidated(); return
        case .off, .passwordDue: return
        case .unavailable:
            if biometry.state == .lockedOut {
                #if os(macOS)
                error = String(localized: "\(biometry.kind.name) is locked after too many tries. Use your master password, or unlock the Mac with its password to turn \(biometry.kind.name) back on.")
                #else
                error = String(localized: "\(biometry.kind.name) is locked after too many tries. Use your master password, or unlock the iPhone with its passcode to turn \(biometry.kind.name) back on.")
                #endif
            }
            return
        }
        isUnlocking = true
        error = nil
        restored = false
        let biometry = self.biometry

        Task {
            let hash: Data
            do {
                hash = try await quickUnlock.keyHash(recent, biometry: biometry)
            } catch is QuickUnlock.Invalidated {
                isUnlocking = false
                invalidated()
                return
            } catch let failure as LAError where Self.quietErrors.contains(failure.code) {
                isUnlocking = false
                return
            } catch {
                isUnlocking = false
                self.error = error.localizedDescription
                return
            }

            let result = await Task.detached(priority: .userInitiated) { () -> Result<Unlocked, Error> in
                var keyHash = hash
                defer { keyHash.wipe() }
                do {
                    let url = try FileAccess.resolve(recent.bookmark).url
                    let file = try FileAccess.read(url)
                    return .success(try Unlocked(UnlockedDatabase.openWithKeyHash(file: file, keyHash: keyHash), url: url, file: file))
                } catch {
                    return .failure(error)
                }
            }.value

            isUnlocking = false
            switch result {
            case .success(let unlocked):
                opened(unlocked, recent, session)
            case .failure(KeePassError.WrongKey(_)):
                quickUnlock.outdated()
                error = String(localized: "Unlocking with \(biometry.kind.name) was turned off because this database’s master password or key file changed. Unlock with the new one, then turn it back on in settings.")
            case .failure(let failure):
                error = failure.userMessage
                await offerBackup(recent, failure)
            }
        }
    }

    private func invalidated() {
        #if os(macOS)
        error = String(localized: "Unlocking with \(biometry.kind.name) was turned off because the fingerprints or the password on this Mac changed. Unlock with your master password, then turn it back on in settings.")
        #else
        error = String(localized: "Unlocking with \(biometry.kind.name) was turned off because the faces, fingerprints, or passcode on this iPhone changed. Unlock with your master password, then turn it back on in settings.")
        #endif
    }

    /// The user chose the master password, cancelled, or iOS took the prompt away (another app
    /// came up). None of these needs a message.
    private static let quietErrors: Set<LAError.Code> = [.userCancel, .userFallback, .systemCancel, .appCancel]

    // MARK: The master password

    /// Reads the files and runs the key derivation off the main thread. It can't be abandoned
    /// halfway: if the screen goes away meanwhile, the session still gets the result (and
    /// auto-lock still applies to it).
    func unlock(into session: Session, quickUnlock: QuickUnlock) {
        guard let recent, !isUnlocking else { return }
        // An empty password with a key file means "key file only". An empty password alone is
        // a real (empty) password.
        let typed = password
        let usePassword = !(typed.isEmpty && recent.keyFileBookmark != nil)
        password = ""
        isUnlocking = true
        error = nil
        restored = false

        Task {
            let result = await Task.detached(priority: .userInitiated) { () -> Result<Unlocked, Error> in
                var passwordBytes: Data? = usePassword ? Secrets.utf8(typed) : nil
                var keyFile: Data?
                defer {
                    passwordBytes?.wipe()
                    keyFile?.wipe()
                }
                do {
                    let url = try FileAccess.resolve(recent.bookmark).url
                    let file = try FileAccess.read(url)
                    if let keyBookmark = recent.keyFileBookmark {
                        do {
                            keyFile = try FileAccess.read(FileAccess.resolve(keyBookmark).url)
                        } catch {
                            throw KeyFileUnreadable()
                        }
                    }
                    return .success(try Unlocked(UnlockedDatabase.open(file: file, password: passwordBytes, keyFile: keyFile), url: url, file: file))
                } catch {
                    return .failure(error)
                }
            }.value

            isUnlocking = false
            switch result {
            case .success(let unlocked):
                quickUnlock.passwordTyped(recent)
                opened(unlocked, recent, session)
                if quickUnlock.shouldOffer(recent) { quickUnlock.offerPending = true }
            case .failure(let failure as KeyFileUnreadable):
                _ = failure
                error = String(localized: "PocketKeePass can’t open the key file anymore. It may have been moved, renamed, or deleted. Remove it and add it again.")
            case .failure(let failure):
                error = failure.userMessage
                await offerBackup(recent, failure)
            }
        }
    }

    /// Hands the database to the session, remembering where its file is now, for its backups.
    private func opened(_ unlocked: Unlocked, _ recent: RecentDatabase, _ session: Session) {
        var recent = recent
        let location = Backups.location(unlocked.url)
        if recent.location != location {
            recent.location = location
            recent.save()
            if self.recent?.id == recent.id { self.recent = recent }
        }
        clearBackup()
        unlocked.start(session, recent)
    }

    // MARK: Backups

    /// Offers the database's backup if `failure` says its file is missing or isn't a database
    /// any more. Not for a wrong key, and not for a file in a newer format (another app upgraded
    /// it): restoring an older copy over that would throw its content away.
    private func offerBackup(_ recent: RecentDatabase, _ failure: Error) async {
        let damaged = switch failure {
        case KeePassError.NotKeePass, KeePassError.Corrupt: true
        case is KeePassError, is KeyFileUnreadable: false
        default: true // the file couldn't be read: gone, or out of reach
        }
        guard damaged else { return clearBackup() }
        let found = await Task.detached(priority: .userInitiated) { () -> (String, Backup)? in
            // Where the file is now if the bookmark still resolves, else where it last was.
            let resolved = (try? FileAccess.resolve(recent.bookmark).url).map(Backups.location)
            for location in [resolved, recent.location].compactMap({ $0 }) {
                if let backup = Backups.standard.find(location) { return (location, backup) }
            }
            return nil
        }.value
        guard self.recent?.id == recent.id else { return }
        backupLocation = found?.0
        backup = found?.1
    }

    private func clearBackup() {
        backup = nil
        backupLocation = nil
        restored = false
    }

    /// Writes the backup over the database's file, which is damaged. The user then unlocks it as
    /// usual. A file that's gone can't be written (the picker granted that file only): then the
    /// backup has to be saved as a new file.
    func restoreBackup() {
        guard let recent, let backup, let location = backupLocation, !isRestoring, !isUnlocking else { return }
        isRestoring = true
        error = nil
        Task {
            let restored = await Task.detached(priority: .userInitiated) { () -> Bool in
                do {
                    let url = try FileAccess.resolve(recent.bookmark).url
                    try FileAccess.write(url, try Backups.standard.read(location, backup))
                    // The file now holds the unfinished save; the previous version stays.
                    if backup.unfinished { Backups.standard.finished(location) }
                    return true
                } catch {
                    return false
                }
            }.value
            isRestoring = false
            guard self.recent?.id == recent.id else { return }
            if restored {
                clearBackup()
                self.restored = true
            } else {
                error = String(localized: "PocketKeePass couldn’t write the backup into the database file. Save it as a new file instead.")
            }
        }
    }

    /// The backup's bytes, for "Save as new file".
    func backupBytes() async -> Data? {
        guard let backup, let location = backupLocation else { return nil }
        return await Task.detached(priority: .userInitiated) { try? Backups.standard.read(location, backup) }.value
    }

    /// The backup was saved as a new file at `url`: that file is now the database, with the
    /// same key file, and the backups move with it. The old file is left alone.
    func savedBackup(as url: URL, quickUnlock: QuickUnlock) {
        guard let old = recent else { return }
        var new: RecentDatabase
        do {
            new = RecentDatabase(bookmark: try FileAccess.bookmark(url), name: url.lastPathComponent)
        } catch {
            clearBackup()
            self.error = String(localized: "The backup was saved as \(url.lastPathComponent), but PocketKeePass can’t open it from there. Choose it with Change.")
            return
        }
        new.keyFileBookmark = old.keyFileBookmark
        new.keyFileName = old.keyFileName
        new.location = Backups.location(url)
        if let from = backupLocation, let to = new.location {
            // If they can't move, the new file's first save makes copies of its own.
            try? Backups.standard.move(from, to: to)
        }
        new.save()
        recent = new
        // Quick unlock belongs to the database as picked.
        quickUnlock.disable()
        clearBackup()
        error = nil
        restored = true
    }

    func backupNotSaved() {
        error = String(localized: "PocketKeePass couldn’t save the backup into that file. Try another location.")
    }
}

private struct KeyFileUnreadable: Error {}

/// A database just unlocked, with what `Session` needs to know about its file.
private nonisolated struct Unlocked: Sendable {
    let database: UnlockedDatabase
    let url: URL
    let fingerprint: Data
    let needsUpgrade: Bool
    let file: Data

    init(_ database: UnlockedDatabase, url: URL, file: Data) throws {
        self.database = database
        self.url = url
        self.file = file
        fingerprint = FileAccess.fingerprint(file)
        needsUpgrade = try database.summary().needsUpgrade
    }

    @MainActor func start(_ session: Session, _ recent: RecentDatabase) {
        session.start(database, recent: recent, url: url, fingerprint: fingerprint, needsUpgrade: needsUpgrade, file: file)
    }
}
