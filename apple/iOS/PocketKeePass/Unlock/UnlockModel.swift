import Foundation
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

    /// A database was picked in the system's file picker.
    func pickedDatabase(_ url: URL) {
        do {
            // Another database: its key file (if any) is the user's to add again.
            recent = RecentDatabase(bookmark: try FileAccess.bookmark(url), name: url.lastPathComponent)
            recent?.save()
            error = nil
        } catch {
            self.error = error.userMessage
        }
    }

    func pickedKeyFile(_ url: URL) {
        guard var recent else { return }
        do {
            recent.keyFileBookmark = try FileAccess.bookmark(url)
            recent.keyFileName = url.lastPathComponent
            recent.save()
            self.recent = recent
            error = nil
        } catch {
            self.error = String(localized: "PocketKeePass can’t open the key file anymore. It may have been moved, renamed, or deleted. Remove it and add it again.")
        }
    }

    func removeKeyFile() {
        recent?.keyFileBookmark = nil
        recent?.keyFileName = nil
        recent?.save()
    }

    /// Reads the files and runs the key derivation off the main thread. It can't be abandoned
    /// halfway: if the screen goes away meanwhile, the session still gets the result (and
    /// auto-lock still applies to it).
    func unlock(into session: Session) {
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
                session.start(database, fileName: recent.name)
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
