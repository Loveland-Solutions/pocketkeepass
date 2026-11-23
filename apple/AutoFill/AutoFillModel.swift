import AuthenticationServices
import Foundation
import LocalAuthentication
import Observation
import PocketKeePassCore
#if os(macOS)
import notify
#endif

/// One AutoFill request: unlock the remembered database, then
/// - for a password: offer the entries saved for the site asking (or any entry, by search), and
///   hand iOS the chosen one's username and password;
/// - for a passkey sign-in: offer the passkeys for the site, and sign with the chosen one;
/// - for a new passkey: make it, in a new entry or one for the same site, save the database,
///   and only then hand iOS the new credential.
///
/// The extension is its own process, apart from the app, so it can't use a database the app
/// has open: it unlocks the file itself each time, with Face ID (quick unlock, shared with the
/// app) or the master password. That unlock is also the user verification every passkey use
/// needs. The database is wiped as soon as the request ends, however it ends, and after the
/// auto-lock time without use. Only a new passkey writes the file (see `savePasskey`).
@Observable
final class AutoFillModel {
    enum Request {
        case password([ASCredentialServiceIdentifier])
        case passkeySignIn(SiteSignIn)
        case newPasskey(SitePasskeyRequest)
    }

    enum Outcome {
        case fill(user: String, password: String)
        case passkey(ASPasskeyAssertionCredential)
        case newPasskey(ASPasskeyRegistrationCredential)
        case cancel
    }

    /// Where a new passkey goes, once the user chose.
    enum PasskeyPlace: Hashable {
        case newEntry
        case entry(id: String, title: String)
    }

    let recent = RecentDatabase.load()
    let request: Request
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
    /// The passkeys for the site asking to sign in.
    private(set) var passkeys: [PasskeyChoice] = []
    /// What a new passkey is for, and the entries it could join.
    private(set) var creation: PasskeyCreation?
    /// A new passkey is being made and saved.
    private(set) var isSaving = false
    /// The KDBX 3 database is upgraded to KDBX 4 when the new passkey is saved.
    private(set) var needsUpgrade = false

    @ObservationIgnored let quickUnlock = QuickUnlock()
    /// The unlocked database's custom icons, for the list.
    @ObservationIgnored let customIcons = CustomIcons()
    @ObservationIgnored private var database: UnlockedDatabase?
    /// The file the database came from, as it was read, and the engine's change count then:
    /// what saving a new passkey compares against. `direct` is false on the Mac when it was
    /// the app's copy (`Backups.latest`), not the file itself.
    @ObservationIgnored private var file: (url: URL, fingerprint: Data, cleanCount: UInt64, direct: Bool)?
    @ObservationIgnored private let finish: (Outcome) -> Void
    @ObservationIgnored private var finished = false
    @ObservationIgnored private var autoPrompted = false
    @ObservationIgnored private var lastInteraction = ContinuousClock.now
    @ObservationIgnored private var timer: Task<Void, Never>?

    init(request: Request, finish: @escaping (Outcome) -> Void) {
        self.request = request
        switch request {
        case .password(let identifiers):
            targets = FillTargets.from(identifiers)
            hosts = FillTargets.hosts(targets)
        case .passkeySignIn(let signIn):
            targets = []
            hosts = [signIn.rpId]
        case .newPasskey(let creation):
            targets = []
            hosts = [creation.rpId]
        }
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
            error = Self.biometryChangedMessage(biometry.kind.name)
            return
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
        let biometry = self.biometry
        Task {
            let hash: Data
            do {
                hash = try await quickUnlock.keyHash(recent, biometry: biometry)
            } catch is QuickUnlock.Invalidated {
                isUnlocking = false
                error = Self.biometryChangedMessage(biometry.kind.name)
                return
            } catch let failure as LAError where [.userCancel, .userFallback, .systemCancel, .appCancel].contains(failure.code) {
                isUnlocking = false
                return
            } catch {
                isUnlocking = false
                self.error = error.localizedDescription
                return
            }
            let result = await Task.detached(priority: .userInitiated) { () -> Result<Opened, Error> in
                var keyHash = hash
                defer { keyHash.wipe() }
                return Result { try Self.open(recent) { try UnlockedDatabase.openWithKeyHash(file: $0, keyHash: keyHash) } }
            }.value
            isUnlocking = false
            switch result {
            case .success(let unlocked):
                await opened(unlocked)
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
            let result = await Task.detached(priority: .userInitiated) { () -> Result<Opened, Error> in
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
            case .success(let unlocked):
                quickUnlock.passwordTyped(recent)
                await opened(unlocked)
            case .failure(is KeyFileUnreadable):
                #if os(macOS)
                // macOS ties the key file's bookmark to the app, so the extension never reads it.
                error = String(localized: "AutoFill on the Mac can’t read the key file. Turn on Touch ID in the PocketKeePass app’s settings to unlock here.")
                #else
                error = String(localized: "PocketKeePass can’t open the key file anymore. Open the PocketKeePass app, remove the key file, and add it again.")
                #endif
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
    ) throws -> Opened {
        let (url, file, direct) = try read(recent)
        let database = try unlock(file)
        // What matches the file, before our own copy is merged in below.
        let cleanCount = database.changeCount()
        let location = Backups.location(url)
        if let ours = Backups.standard.ours(location), FileAccess.fingerprint(ours) != FileAccess.fingerprint(file) {
            // Another master key since, or a copy that won't open: the file as it is.
            _ = try? database.mergeSavedCopy(file: ours)
        }
        return Opened(database: database, url: url, fingerprint: FileAccess.fingerprint(file), cleanCount: cleanCount, direct: direct)
    }

    /// The database's file. On the Mac, the extension can't open a file through the app's
    /// bookmark (macOS ties it to the app that made it), so it reads the copy the app keeps of
    /// the file as it last read or wrote it (`Backups.latest`). Blocking.
    nonisolated private static func read(_ recent: RecentDatabase) throws -> (URL, Data, Bool) {
        #if os(macOS)
        if let url = try? FileAccess.resolve(recent.bookmark).url, let file = try? FileAccess.read(url) {
            return (url, file, true)
        }
        guard let location = recent.location, let copy = Backups.standard.latest(location) else {
            throw NoCopyForAutoFill()
        }
        return (URL(filePath: location), copy, false)
        #else
        let url = try FileAccess.resolve(recent.bookmark).url
        return (url, try FileAccess.read(url), true)
        #endif
    }

    /// A database just unlocked, and its file as it was read.
    nonisolated struct Opened: @unchecked Sendable {
        let database: UnlockedDatabase
        let url: URL
        let fingerprint: Data
        let cleanCount: UInt64
        let direct: Bool
    }

    private func opened(_ opened: Opened) async {
        let database = opened.database
        guard !finished else {
            Task.detached { database.lock() }
            return
        }
        self.database = database
        file = (opened.url, opened.fingerprint, opened.cleanCount, opened.direct)
        customIcons.use(database)
        switch request {
        case .password: await findMatches(database)
        case .passkeySignIn(let request): await findPasskeys(database, request)
        case .newPasskey(let creation): await checkCreation(database, creation)
        }
    }

    private func findMatches(_ database: UnlockedDatabase) async {
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

    // MARK: Passkeys

    private func findPasskeys(_ database: UnlockedDatabase, _ request: SiteSignIn) async {
        let found = await Task.detached(priority: .userInitiated) {
            Result { try database.passkeysForSite(request: request) }
        }.value
        guard !finished else { return }
        isUnlocked = true
        startTimer()
        switch found {
        case .success(let choices):
            passkeys = choices
            // One passkey that works: that's the one. The unlock just now verified the user.
            let usable = choices.filter { $0.unusableReason == nil }
            if usable.count == 1, choices.count == 1 { signIn(with: usable[0]) }
        case .failure(let failure):
            error = failure.userMessage
        }
    }

    /// Signs the sign-in request with the chosen passkey, and hands iOS the result.
    func signIn(with choice: PasskeyChoice) {
        guard case .passkeySignIn(let signIn) = request, let database, choice.unusableReason == nil, !finished else { return }
        interacted()
        let id = choice.result.entry.id
        Task {
            let signed = await Task.detached(priority: .userInitiated) {
                Result { try database.signInAtSite(entryId: id, request: signIn) }
            }.value
            switch signed {
            case .success(let assertion):
                end(.passkey(ASPasskeyAssertionCredential(
                    userHandle: assertion.userHandle,
                    relyingParty: signIn.rpId,
                    signature: assertion.signature,
                    clientDataHash: signIn.clientDataHash,
                    authenticatorData: assertion.authenticatorData,
                    credentialID: assertion.credentialId
                )))
            case .failure(let failure):
                error = failure.userMessage
            }
        }
    }

    private func checkCreation(_ database: UnlockedDatabase, _ request: SitePasskeyRequest) async {
        let checked = await Task.detached(priority: .userInitiated) { () -> Result<(PasskeyCreation, Bool), Error> in
            Result { (try database.passkeyCreationAtSite(request: request), try database.summary().needsUpgrade) }
        }.value
        guard !finished else { return }
        isUnlocked = true
        startTimer()
        switch checked {
        case .success(let (creation, needsUpgrade)):
            self.creation = creation
            self.needsUpgrade = needsUpgrade
        case .failure(let failure):
            error = failure.userMessage
        }
    }

    /// Makes the new passkey where the user chose, saves the database to its file, and only
    /// then hands iOS the new credential: a site must never hold a passkey the file doesn't. If
    /// saving fails, the passkey is taken back out, and the user can try again or cancel.
    func createPasskey(_ place: PasskeyPlace) {
        guard case .newPasskey(let request) = request, let database, let file, !isSaving, !finished else { return }
        interacted()
        isSaving = true
        error = nil
        let upgrade = needsUpgrade
        Task {
            let result = await Task.detached(priority: .userInitiated) { () -> Result<PasskeyRegistration, Error> in
                Result {
                    if upgrade { try database.upgradeToKdbx4() }
                    let target: PasskeyTarget = switch place {
                    case .newEntry: .newEntry(groupId: try database.listGroup(id: nil).group.id)
                    case .entry(let id, _): .entry(entryId: id)
                    }
                    let made = try database.createPasskeyAtSite(target: target, request: request)
                    do {
                        try Self.save(database, to: file)
                    } catch {
                        _ = try? database.undoPasskey(entryId: made.entryId)
                        throw error
                    }
                    return made
                }
            }.value
            isSaving = false
            switch result {
            case .success(let made):
                needsUpgrade = false
                end(.newPasskey(ASPasskeyRegistrationCredential(
                    relyingParty: request.rpId,
                    clientDataHash: request.clientDataHash,
                    credentialID: made.credentialId,
                    attestationObject: made.attestationObject
                )))
            case .failure(let failure as KeePassError):
                error = failure.userMessage
            case .failure:
                error = String(localized: "The passkey couldn’t be saved to the database file, so it wasn’t made. Check that the file is still there, then try again.")
            }
        }
    }

    /// Writes the database to its file, as the app's `Session` does, in brief: if another app or
    /// device changed the file since it was read here, its changes are merged in first; the file
    /// as it was and the new bytes are kept in `Backups`; the write is read back and compared.
    /// The app merges this save in at its next unlock like any other (`Backups.ours`). Blocking.
    nonisolated private static func save(
        _ database: UnlockedDatabase, to file: (url: URL, fingerprint: Data, cleanCount: UInt64, direct: Bool)
    ) throws {
        let location = Backups.location(file.url)
        guard file.direct else {
            // The Mac's copy, not the file: the new bytes wait as an unfinished save, which the
            // app merges into the file and writes: at once if it's open (told by the notice),
            // else at its next unlock, like a save held offline.
            try Backups.standard.keepUnfinished(location, try database.save())
            #if os(macOS)
            notify_post(Backups.extensionSavedNotice)
            #endif
            return
        }
        let current = try FileAccess.read(file.url)
        if FileAccess.fingerprint(current) != file.fingerprint {
            _ = try database.syncWithFile(file: current, unchangedSince: file.cleanCount)
        }
        let bytes = try database.save()
        try? Backups.standard.keep(location, current)
        try? Backups.standard.keepUnfinished(location, bytes)
        try FileAccess.write(file.url, bytes)
        Backups.standard.saved(location)
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
        passkeys = []
        creation = nil
        query = ""
        password = ""
        customIcons.use(nil)
        guard let database else { return }
        self.database = nil
        Task.detached(priority: .userInitiated) { database.lock() }
    }

    private static func biometryChangedMessage(_ name: String) -> String {
        #if os(macOS)
        String(localized: "Unlocking with \(name) was turned off because the fingerprints or password on this Mac changed. Unlock with your master password, then turn it back on in the PocketKeePass app’s settings.")
        #else
        String(localized: "Unlocking with \(name) was turned off because the faces, fingerprints, or passcode on this iPhone changed. Unlock with your master password, then turn it back on in the PocketKeePass app’s settings.")
        #endif
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

/// The Mac's extension found no copy of the database from the app.
private struct NoCopyForAutoFill: LocalizedError {
    var errorDescription: String? {
        String(localized: "Open your database in the PocketKeePass app once, then try again here.")
    }
}
