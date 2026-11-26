import Foundation
import LocalAuthentication

/// Turning quick unlock on from the app, for the database open in its `Session`. (The AutoFill
/// extension only unlocks with it; it has no `Session`.)
extension QuickUnlock {
    /// Turns quick unlock on for the open database, after a Face ID check of its own. Throws the
    /// check's error if it failed or was cancelled; the caller decides what's worth showing.
    func enable(_ database: RecentDatabase, session: Session, biometry: Biometry) async throws {
        guard let open = session.database else { return }
        do {
            try await store(database, open: open, biometry: biometry)
        } catch {
            disable()
            throw error
        }
        if session.database !== open {
            // Locked meanwhile: stays off.
            disable()
        }
    }

    /// [enable] for the remembered database, from settings or the offer. Returns what to tell
    /// the user if it didn't work, or nil (it worked, or the user cancelled the check).
    func turnOn(session: Session) async -> String? {
        guard let database = RecentDatabase.load() else { return nil }
        let biometry = Self.biometry()
        do {
            try await enable(database, session: session, biometry: biometry)
            return nil
        } catch let failure as LAError where [.userCancel, .systemCancel, .appCancel].contains(failure.code) {
            return nil
        } catch let refused as KeychainRefused {
            return String(localized: "The Keychain wouldn’t keep what \(biometry.kind.name) needs (error \(Int(refused.status))), so it stays off. Your master password still works.")
        } catch {
            return error.localizedDescription
        }
    }
}
