import AuthenticationServices
import Foundation
import LocalAuthentication
import Observation
import PocketKeePassCore

/// One AutoFill request: unlock the remembered database, offer the entries saved for the site
/// asking (or any entry, by search), and hand iOS the chosen one's username and password.
///
/// The extension is its own process, apart from the app, so it can't use a database the app
/// has open: it unlocks the file itself each time, with Face ID (quick unlock, shared with the
/// app) or the master password. The database is wiped as soon as the request ends, filled or
/// cancelled, and after the auto-lock time without use. Nothing is ever written: the file and
/// the app's backups are only read.
@Observable
final class AutoFillModel {
    enum Outcome {
        case fill(user: String, password: String)
        case cancel
    }

    let recent = RecentDatabase.load()
    /// Where the fill goes, as iOS reported it (already verified by iOS).
    let targets: [FillTarget]
    /// The hosts in `targets`, for saying where the fill goes.
    let hosts: [String]

    var password = ""
    var showPassword = false
    var query = ""
    private(set) var isUnlocking = false
    private(set) var error: String?
    private(set) var biometry = QuickUnlock.biometry()
    private(set) var matches: [SearchResult] = []
    private(set) var results: [SearchResult] = []
    private(set) var isUnlocked = false

    @ObservationIgnored let quickUnlock = QuickUnlock()
    @ObservationIgnored private var database: UnlockedDatabase?
    @ObservationIgnored private let finish: (Outcome) -> Void
    @ObservationIgnored private var finished = false
    @ObservationIgnored private var autoPrompted = false
    @ObservationIgnored private var lastInteraction = ContinuousClock.now
    @ObservationIgnored private var timer: Task<Void, Never>?

    init(identifiers: [ASCredentialServiceIdentifier], finish: @escaping (Outcome) -> Void) {
        targets = FillTargets.from(identifiers)
        hosts = FillTargets.hosts(targets)
        self.finish = finish
    }

    // MARK: Unlocking

    /// Whether to show the "Unlock with Face ID" button.
    var canQuickUnlock: Bool {
        guard let recent, quickUnlock.isEnabled(for: recent), !quickUnlock.isPasswordDue(recent) else { return false }
        if case .ready = biometry.state { return true }
        return biometry.state == .lockedOut // tapping explains
    }

    /// Whether the master password is needed this time although quick unlock is on.
    var isPasswordDue: Bool {
        guard let recent else { return false }
        return quickUnlock.isEnabled(for: recent) && quickUnlock.isPasswordDue(recent)
    }

    /// Opens the Face ID prompt once, as the screen appears.
    func appeared() {
        guard !autoPrompted else { return }
        autoPrompted = true
        biometry = QuickUnlock.biometry()
        if canQuickUnlock { unlockWithBiometry() }
    }

    func unlockWithBiometry() {
        guard let recent, !isUnlocking, !isUnlocked else { return }
        interacted()
        biometry = QuickUnlock.biometry()
        switch quickUnlock.check(recent, biometry: biometry, comparingFaces: false) {
        case nil: break
        case .invalidated:
            error = String(localized: "Unlocking with \(biometry.kind.name) was turned off because the faces, fingerprints, or passcode on this iPhone changed. Unlock with your master password, then turn it back on in the PocketKeePass app’s settings.")
            return
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
                error = String(localized: "Unlocking with \(biometry.kind.name) was turned off because the faces, fingerprints, or passcode on this iPhone changed. Unlock with your master password, then turn it back on in the PocketKeePass app’s settings.")
                return
            } catch let failure as LAError where [.userCancel, .userFallback, .systemCancel, .appCancel].contains(failure.code) {
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
                return Result { try Self.open(recent) { try UnlockedDatabase.openWithKeyHash(file: $0, keyHash: keyHash) } }
            }.value
            isUnlocking = false
            switch result {
            case .success(let database):
                await opened(database)
            case .failure(KeePassError.WrongKey):
                quickUnlock.outdated()
                error = String(localized: "Unlocking with \(biometry.kind.name) was turned off because this database’s master password or key file changed. Unlock with the new one, then turn it back on in the PocketKeePass app’s settings.")
            case .failure(let failure):
                error = failure.userMessage
            }
        }
    }

    /// Opens the database with the typed master password (and the remembered key file).
    func unlock() {
        guard let recent, !isUnlocking, !isUnlocked else { return }
        interacted()
        let typed = password
        // As in the app: an empty password with a key file means "key file only".
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
                return Result {
                    try Self.open(recent) { file in
                        if let keyBookmark = recent.keyFileBookmark {
                            do {
                                keyFile = try FileAccess.read(FileAccess.resolve(keyBookmark).url)
                            } catch {
                                throw KeyFileUnreadable()
                            }
                        }
                        return try UnlockedDatabase.open(file: file, password: passwordBytes, keyFile: keyFile)
                    }
                }
            }.value
            isUnlocking = false
            switch result {
            case .success(let database):
                quickUnlock.passwordTyped(recent)
                await opened(database)
            case .failure(is KeyFileUnreadable):
                error = String(localized: "PocketKeePass can’t open the key file anymore. Open the PocketKeePass app, remove the key file, and add it again.")
            case .failure(let failure):
                error = failure.userMessage
            }
        }
    }

    /// Reads the database's file and opens it with `unlock`, then merges in what the app last
    /// saved here, if the file doesn't have it yet (a save waiting to go online, or one another
    /// device overwrote): a password changed in the app is then the one filled in. In memory
    /// only; the app writes it to the file. Blocking.
    nonisolated private static func open(
        _ recent: RecentDatabase, unlock: (Data) throws -> UnlockedDatabase
    ) throws -> UnlockedDatabase {
        let url = try FileAccess.resolve(recent.bookmark).url
        let file = try FileAccess.read(url)
        let database = try unlock(file)
        let location = Backups.location(url)
        if let ours = Backups.standard.ours(location), FileAccess.fingerprint(ours) != FileAccess.fingerprint(file) {
            // Another master key since, or a copy that won't open: the file as it is.
            _ = try? database.mergeSavedCopy(file: ours)
        }
        return database
    }

    private func opened(_ database: UnlockedDatabase) async {
        guard !finished else {
            Task.detached { database.lock() }
            return
        }
        self.database = database
        let targets = targets
        let found = await Task.detached(priority: .userInitiated) { () -> [SearchResult] in
            var seen = Set<String>()
            var matches: [SearchResult] = []
            for target in targets {
                for match in (try? database.autofillMatches(target: target)) ?? [] where seen.insert(match.entry.id).inserted {
                    matches.append(match)
                }
            }
            return matches
        }.value
        guard !finished else { return }
        matches = found
        isUnlocked = true
        startTimer()
        // One entry saved for this site: that's the one. iOS verified the site, and the user
        // chose PocketKeePass for it.
        if found.count == 1 { fill(found[0].entry) }
    }

    // MARK: Choosing

    /// Searches every entry, for filling one that isn't saved for this site.
    func search() async {
        interacted()
        let query = query
        guard let database, !query.trimmingCharacters(in: .whitespaces).isEmpty else {
            results = []
            return
        }
        let found = await Task.detached(priority: .userInitiated) { (try? database.search(query: query)) ?? [] }.value
        if self.query == query { results = found }
    }

    /// Hands iOS the entry's username and password, and ends the request.
    func fill(_ entry: EntryItem) {
        guard let database, !finished else { return }
        interacted()
        let id = entry.id
        Task {
            let credential = await Task.detached(priority: .userInitiated) { () -> (String, String)? in
                guard var user = try? database.revealField(entryId: id, key: "UserName"),
                      var password = try? database.revealField(entryId: id, key: "Password")
                else { return nil }
                defer {
                    user.wipe()
                    password.wipe()
                }
                // iOS takes them only as `String`s, which can't be wiped: the same limit as
                // Android's autofill framework.
                return (String(decoding: user, as: UTF8.self), String(decoding: password, as: UTF8.self))
            }.value
            guard let credential else {
                error = String(localized: "That entry isn’t in the database anymore.")
                return
            }
            guard !credential.0.isEmpty || !credential.1.isEmpty else {
                error = String(localized: "“\(entry.title)” has no username or password to fill in.")
                return
            }
            end(.fill(user: credential.0, password: credential.1))
        }
    }

    func cancel() {
        end(.cancel)
    }

    /// Wipes the database and tells iOS how the request ended. Only the first call counts.
    func end(_ outcome: Outcome) {
        guard !finished else { return }
        finished = true
        lock()
        finish(outcome)
    }

    /// Wipes the decrypted database now, not when it's freed.
    func lock() {
        timer?.cancel()
        isUnlocked = false
        matches = []
        results = []
        query = ""
        password = ""
        guard let database else { return }
        self.database = nil
        Task.detached(priority: .userInitiated) { database.lock() }
    }

    // MARK: Auto-lock

    /// A touch or key press: the auto-lock clock starts again.
    func interacted() {
        lastInteraction = .now
    }

    /// The app's auto-lock time applies here too: left open that long without use, the request
    /// is cancelled and the database wiped.
    private func startTimer() {
        timer?.cancel()
        lastInteraction = .now
        timer = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                let deadline = self.lastInteraction + .seconds(Preferences.autoLockSeconds)
                if ContinuousClock.now >= deadline {
                    self.end(.cancel)
                    return
                }
                try? await Task.sleep(until: deadline, clock: .continuous)
            }
        }
    }
}

private struct KeyFileUnreadable: Error {}
