import Foundation
import Observation
import PocketKeePassCore

/// The one open database, app-wide. Screens read it from here and never keep their own
/// reference, so locking really drops it everywhere.
@Observable
final class Session {
    /// The unlocked database, or nil while locked.
    private(set) var database: UnlockedDatabase?
    /// The file's name, for the title bar.
    private(set) var fileName = ""
    /// Bumped on every change to the open database, so screens know to read it again.
    private(set) var revision = 0

    var isUnlocked: Bool { database != nil }

    func start(_ database: UnlockedDatabase, fileName: String) {
        lock()
        self.database = database
        self.fileName = fileName
        revision += 1
    }

    /// Wipes the decrypted database at once (not when it's garbage collected) and forgets it.
    /// The wipe runs off the main thread, so a large database doesn't stall the screen; nothing
    /// can reach it in the meantime, since this was the only reference screens use.
    func lock() {
        guard let database else { return }
        self.database = nil
        fileName = ""
        revision += 1
        Clipboard.shared.databaseLocked()
        Task.detached(priority: .userInitiated) { database.lock() }
    }
}
