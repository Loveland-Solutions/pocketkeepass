import Foundation
import Observation
import PocketKeePassCore
import SwiftUI

/// Making a new database: a name and a master password, then the system's save dialog for
/// where the file goes (on this device, iCloud Drive, or any storage app). The engine makes the
/// file (KDBX 4, AES-256, Argon2id timed for about a second here); once it's written and read
/// back, it opens like any other database.
///
/// The typed password lives here as a `String` (SecureField's limit, as on the unlock screen)
/// until the database is made, then it's cleared.
@Observable
final class CreateDatabaseModel {
    var name = String(localized: "Passwords")
    var password = ""
    var confirmation = ""
    var showPassword = false
    private(set) var isCreating = false
    private(set) var error: String?
    /// Made and encrypted, waiting for the user to choose where the file goes.
    private(set) var made: Made?

    struct Made {
        let database: UnlockedDatabase
        let file: KdbxFile
        let name: String
    }

    var passwordsDiffer: Bool { !confirmation.isEmpty && password != confirmation }

    var canCreate: Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty
            && !password.isEmpty && password == confirmation && !isCreating && made == nil
    }

    /// Makes the database and its file, off the main thread. Slow: the key derivation is timed,
    /// then run again to encrypt the file.
    func create() async {
        guard canCreate else { return }
        let name = name.trimmingCharacters(in: .whitespaces)
        let typed = password
        password = ""
        confirmation = ""
        isCreating = true
        error = nil
        let result = await Task.detached(priority: .userInitiated) { () -> Result<(UnlockedDatabase, Data), Error> in
            var passwordBytes = Secrets.utf8(typed)
            defer { passwordBytes.wipe() }
            return Result {
                let database = try UnlockedDatabase.create(name: name, password: passwordBytes, keyFile: nil)
                do {
                    return (database, try database.save())
                } catch {
                    database.lock()
                    throw error
                }
            }
        }.value
        isCreating = false
        switch result {
        case .success(let (database, bytes)):
            made = Made(database: database, file: KdbxFile(data: bytes), name: name)
        case .failure(let failure):
            error = failure.userMessage + " " + String(localized: "Type the master password again.")
        }
    }

    /// The file was saved at `url`: check it, remember it, and open the database.
    func saved(to url: URL, session: Session, quickUnlock: QuickUnlock) async -> Bool {
        guard let made else { return false }
        let expected = made.file.data
        let readBack = await Task.detached(priority: .userInitiated) { try? FileAccess.read(url) }.value
        guard readBack == expected else {
            error = String(localized: "The new database couldn’t be saved there. Try another location.")
            return false
        }
        var recent: RecentDatabase
        do {
            recent = RecentDatabase(bookmark: try FileAccess.bookmark(url), name: url.lastPathComponent)
        } catch {
            self.error = String(localized: "The new database was saved, but PocketKeePass can’t open it from there. Choose it with Open database.")
            return false
        }
        recent.location = Backups.location(url)
        recent.save()
        // Quick unlock belonged to the previous database. Making this one counts as typing its
        // master password.
        quickUnlock.disable()
        quickUnlock.passwordTyped(recent)
        self.made = nil
        session.start(made.database, recent: recent, url: url, fingerprint: FileAccess.fingerprint(expected), needsUpgrade: false)
        if quickUnlock.shouldOffer(recent) { quickUnlock.offerPending = true }
        return true
    }

    /// Cancelled before the file was saved: wipe the database.
    func discard() {
        password = ""
        confirmation = ""
        guard let made else { return }
        self.made = nil
        let database = made.database
        Task.detached { database.lock() }
    }
}
