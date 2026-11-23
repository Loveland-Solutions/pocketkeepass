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
            recent = RecentDatabase(bookmark: try FileAccess.bookmark(url), name: url.lastPathComponent)
            recent?.save()
            quickUnlock.disable()
            error = nil
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
        } catch {
            self.error = String(localized: "PocketKeePass can’t open the key file anymore. It may have been moved, renamed, or deleted. Remove it and add it again.")
        }
    }

    func removeKeyFile(quickUnlock: QuickUnlock) {
        recent?.keyFileBookmark = nil
        recent?.keyFileName = nil
        recent?.save()
        quickUnlock.disable()
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
                error = String(localized: "\(biometry.kind.name) is locked after too many tries. Use your master password, or unlock the iPhone with its passcode to turn \(biometry.kind.name) back on.")
            }
            return
        }
        isUnlocking = true
        error = nil
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

            let result = await Task.detached(priority: .userInitiated) { () -> Result<UnlockedDatabase, Error> in
                var keyHash = hash
                defer { keyHash.wipe() }
                do {
                    let file = try FileAccess.read(FileAccess.resolve(recent.bookmark).url)
                    return .success(try UnlockedDatabase.openWithKeyHash(file: file, keyHash: keyHash))
                } catch {
                    return .failure(error)
                }
            }.value

            isUnlocking = false
            switch result {
            case .success(let database):
                session.start(database, fileName: recent.name)
            case .failure(KeePassError.WrongKey(_)):
                quickUnlock.outdated()
                error = String(localized: "Unlocking with \(biometry.kind.name) was turned off because this database’s master password or key file changed. Unlock with the new one, then turn it back on in settings.")
            case .failure(let failure):
                error = failure.userMessage
            }
        }
    }

    private func invalidated() {
        error = String(localized: "Unlocking with \(biometry.kind.name) was turned off because the faces, fingerprints, or passcode on this iPhone changed. Unlock with your master password, then turn it back on in settings.")
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

        Task {
            let result = await Task.detached(priority: .userInitiated) { () -> Result<UnlockedDatabase, Error> in
                var passwordBytes: Data? = usePassword ? Secrets.utf8(typed) : nil
                var keyFile: Data?
                defer {
                    passwordBytes?.wipe()
                    keyFile?.wipe()
                }
                do {
                    let file = try FileAccess.read(FileAccess.resolve(recent.bookmark).url)
                    if let keyBookmark = recent.keyFileBookmark {
                        do {
                            keyFile = try FileAccess.read(FileAccess.resolve(keyBookmark).url)
                        } catch {
                            throw KeyFileUnreadable()
                        }
                    }
                    return .success(try UnlockedDatabase.open(file: file, password: passwordBytes, keyFile: keyFile))
                } catch {
                    return .failure(error)
                }
            }.value

            isUnlocking = false
            switch result {
            case .success(let database):
                quickUnlock.passwordTyped(recent)
                session.start(database, fileName: recent.name)
                if quickUnlock.shouldOffer(recent) { quickUnlock.offerPending = true }
            case .failure(let failure as KeyFileUnreadable):
                _ = failure
                error = String(localized: "PocketKeePass can’t open the key file anymore. It may have been moved, renamed, or deleted. Remove it and add it again.")
            case .failure(let failure):
                error = failure.userMessage
            }
        }
    }
}

private struct KeyFileUnreadable: Error {}
