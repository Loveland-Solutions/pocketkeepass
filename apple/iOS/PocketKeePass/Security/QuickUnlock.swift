import Foundation
import LocalAuthentication
import Observation
import PocketKeePassCore
import Security

/// Face ID / Touch ID quick unlock: the database's composite key hash, kept in a Keychain item
/// that iOS hands over only right after a successful Face ID or Touch ID check.
///
/// The hash is what the password and key file turn into before key derivation. Unlike the
/// derived key, it keeps working after the database is saved, by us or any other app, because
/// saving changes the key derivation salt but not the master key. It stops working only when the
/// master key changes, and then the engine just says "wrong key".
///
/// The item is guarded by the Secure Enclave with `.biometryCurrentSet`: adding or removing a
/// face or fingerprint makes it unreadable for good, so someone who adds their own face can't
/// use it. `WhenPasscodeSetThisDeviceOnly` keeps it out of iCloud Keychain and backups, and iOS
/// deletes it if the passcode is turned off. Nothing else is stored: in UserDefaults, only which
/// database it belongs to (`RecentDatabase.id`) and the face/fingerprint set's fingerprint
/// (`LADomainState`), so a change is explained before the prompt instead of failing after it.
///
/// There's one slot, as on Android: the app remembers one database at a time.
///
/// It doesn't last forever ([isPasswordDue]): after the iPhone restarts, and every
/// [intervalDays] days, the master password has to be typed once, so the user doesn't forget it
/// and a lost iPhone needs more than a face. That's the app's rule, not the Keychain's: the item
/// stays, so quick unlock is back as soon as the password has been typed.
@Observable
final class QuickUnlock {
    /// The choices for [intervalDays]. No "never": the point is that the password gets typed.
    static let intervals = [1, 3, 7, 14, 30]
    static let defaultInterval = 14

    /// Set after a master password unlock when it's worth asking whether to turn this on.
    var offerPending = false
    /// Bumped whenever the stored state changes, so views showing it read it again.
    private(set) var revision = 0

    private let defaults: UserDefaults
    /// The Keychain service the item is stored under. Tests use their own.
    private let service: String

    init(defaults: UserDefaults = .standard, service: String = "mn.lls.pocketkeepass.quick-unlock") {
        self.defaults = defaults
        self.service = service
    }

    // MARK: What the device has

    /// What the device can do right now. Asked fresh each time: the user can set up or remove
    /// Face ID, or refuse the app's use of it, while the app is in the background.
    nonisolated static func biometry() -> Biometry {
        let context = LAContext()
        var error: NSError?
        let usable = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error)
        let kind = Biometry.Kind(context.biometryType)
        if usable { return Biometry(kind: kind, state: .ready(context.domainState.biometry.stateHash)) }
        switch LAError.Code(rawValue: error?.code ?? 0) {
        case .biometryLockout: return Biometry(kind: kind, state: .lockedOut)
        case .biometryNotEnrolled: return Biometry(kind: kind, state: .notEnrolled)
        default: return Biometry(kind: kind, state: .unavailable)
        }
    }

    // MARK: Settings

    var intervalDays: Int {
        get {
            _ = revision
            let days = defaults.integer(forKey: Keys.interval)
            return Self.intervals.contains(days) ? days : Self.defaultInterval
        }
        set {
            guard Self.intervals.contains(newValue) else { return }
            defaults.set(newValue, forKey: Keys.interval)
            revision += 1
        }
    }

    /// Whether quick unlock is turned on for this database. It may still turn out to be
    /// invalidated (see [check]).
    func isEnabled(for database: RecentDatabase) -> Bool {
        _ = revision
        return defaults.string(forKey: Keys.database) == database.id
    }

    // MARK: The expiry rule

    /// The user just unlocked this database by typing the master password (or with the key
    /// file alone).
    func passwordTyped(_ database: RecentDatabase) {
        let record = PasswordTyped(database: database.id, moment: .now())
        defaults.set(try? JSONEncoder().encode(record), forKey: Keys.typed)
        revision += 1
    }

    /// Whether quick unlock has to wait for the master password: the iPhone restarted since it
    /// was last typed for this database, or [intervalDays] days have passed.
    func isPasswordDue(_ database: RecentDatabase) -> Bool {
        _ = revision
        let typed = defaults.data(forKey: Keys.typed)
            .flatMap { try? JSONDecoder().decode(PasswordTyped.self, from: $0) }
            .flatMap { $0.database == database.id ? $0.moment : nil }
        return QuickUnlock.isPasswordDue(typed: typed, now: .now(), intervalDays: intervalDays)
    }

    /// The rule behind [isPasswordDue], apart from the device so it can be tested.
    ///
    /// iOS has no boot count, so a restart is spotted two ways: the boot time moved (the kernel
    /// keeps it as a date, and moves it when the clock is set, so setting the clock also counts
    /// as a restart, which only ever asks for the password sooner), or the uptime clock is
    /// behind where it was. Time is measured on the uptime clock, which keeps counting while the
    /// phone sleeps and can't be set back like the date.
    nonisolated static func isPasswordDue(typed: Moment?, now: Moment, intervalDays: Int) -> Bool {
        guard let typed else { return true }
        if abs(now.bootTime - typed.bootTime) > Moment.bootTimeTolerance { return true }
        if now.uptime < typed.uptime { return true }
        return now.uptime - typed.uptime >= Double(intervalDays) * 86_400
    }

    // MARK: Offering it

    /// Whether to ask, right after a master password unlock, if the user would like to use Face
    /// ID next time: the device can, it's off, and they haven't been asked about this database.
    func shouldOffer(_ database: RecentDatabase) -> Bool {
        guard case .ready = Self.biometry().state else { return false }
        return !isEnabled(for: database) && defaults.string(forKey: Keys.offered) != database.id
    }

    /// Remembers that the user answered the offer for this database, whatever they chose.
    /// Kept apart from the enabled state, so turning quick unlock off isn't a reason to ask again.
    func offered(_ database: RecentDatabase) {
        defaults.set(database.id, forKey: Keys.offered)
        offerPending = false
    }

    // MARK: Turning it on and off

    /// Turns quick unlock on for the open database, after a Face ID check of its own. Throws the
    /// check's error if it failed or was cancelled; the caller decides what's worth showing.
    func enable(_ database: RecentDatabase, session: Session, biometry: Biometry) async throws {
        guard let open = session.database else { return }
        let context = LAContext()
        context.localizedCancelTitle = String(localized: "Cancel")
        _ = try await context.evaluatePolicy(
            .deviceOwnerAuthenticationWithBiometrics,
            localizedReason: String(localized: "Turn on unlocking with \(biometry.kind.name)")
        )
        // The face/fingerprint set as it is now, from the check that just passed.
        let state = context.domainState.biometry.stateHash
        let service = self.service
        let stored = try await Task.detached(priority: .userInitiated) { () throws -> Bool in
            var hash = try open.keyHash()
            defer { hash.wipe() }
            return Self.store(hash, service: service, account: database.id)
        }.value
        guard stored, session.database === open else {
            // Locked meanwhile, or the Keychain refused: stays off.
            disable()
            return
        }
        defaults.set(database.id, forKey: Keys.database)
        defaults.set(state, forKey: Keys.biometryState)
        revision += 1
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
        } catch {
            return error.localizedDescription
        }
    }

    /// Turns quick unlock off: forgets which database it was for and deletes the Keychain item.
    func disable() {
        defaults.removeObject(forKey: Keys.database)
        defaults.removeObject(forKey: Keys.biometryState)
        Self.deleteItems(service: service)
        revision += 1
    }

    // MARK: Unlocking

    /// Whether the stored hash can still be used: nil if so, otherwise why not. `.invalidated`
    /// turns quick unlock off.
    func check(_ database: RecentDatabase, biometry: Biometry) -> Unusable? {
        guard isEnabled(for: database) else { return .off }
        if isPasswordDue(database) { return .passwordDue }
        switch biometry.state {
        case .ready(let state):
            // `.biometryCurrentSet` already makes the item unreadable after a change; this says
            // so before the prompt instead of after it.
            if state != defaults.data(forKey: Keys.biometryState) {
                disable()
                return .invalidated
            }
            return nil
        case .notEnrolled:
            // Every face or fingerprint was removed: the item can never be read again.
            disable()
            return .invalidated
        case .lockedOut, .unavailable:
            return .unavailable
        }
    }

    /// Asks for Face ID, then reads the stored hash with that check. The caller wipes the
    /// result. Throws `LAError` from the prompt, or [Invalidated] if the hash is gone (iOS
    /// deleted it, or the faces changed after [check]), which also turns quick unlock off.
    func keyHash(_ database: RecentDatabase, biometry: Biometry) async throws -> Data {
        let context = LAContext()
        context.localizedFallbackTitle = String(localized: "Use master password")
        _ = try await context.evaluatePolicy(
            .deviceOwnerAuthenticationWithBiometrics,
            localizedReason: String(localized: "Unlock \(database.name)")
        )
        let checked = Unchecked(context)
        let service = self.service
        let hash = await Task.detached(priority: .userInitiated) {
            defer { checked.value.invalidate() }
            return Self.read(service: service, account: database.id, context: checked.value)
        }.value
        guard let hash else {
            disable()
            throw Invalidated()
        }
        return hash
    }

    /// The hash no longer opens the database: the master key changed elsewhere.
    func outdated() {
        disable()
    }

    /// No database is remembered (a fresh install, where iOS may have kept the Keychain item
    /// from before the app was deleted): nothing to keep.
    func forgetAll() {
        if defaults.string(forKey: Keys.database) != nil || Self.hasItems(service: service) { disable() }
    }

    // MARK: The Keychain

    nonisolated private static func store(_ hash: Data, service: String, account: String) -> Bool {
        deleteItems(service: service)
        var error: Unmanaged<CFError>?
        guard let access = SecAccessControlCreateWithFlags(
            nil, kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly, .biometryCurrentSet, &error
        ) else { return false }
        let item: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account,
            kSecAttrAccessControl: access,
            kSecValueData: hash,
        ]
        return SecItemAdd(item as CFDictionary, nil) == errSecSuccess
    }

    /// Reads the hash with an LAContext that just passed Face ID, so the Keychain doesn't ask
    /// again. Nil if the item is gone or can't be read anymore.
    nonisolated private static func read(service: String, account: String, context: LAContext) -> Data? {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account,
            kSecReturnData: true,
            kSecMatchLimit: kSecMatchLimitOne,
            kSecUseAuthenticationContext: context,
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }

    nonisolated private static func deleteItems(service: String) {
        let query: [CFString: Any] = [kSecClass: kSecClassGenericPassword, kSecAttrService: service]
        SecItemDelete(query as CFDictionary)
    }

    /// Whether any item exists, without reading it (attributes need no Face ID).
    nonisolated private static func hasItems(service: String) -> Bool {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecReturnAttributes: true,
            kSecMatchLimit: kSecMatchLimitOne,
        ]
        return SecItemCopyMatching(query as CFDictionary, nil) == errSecSuccess
    }

    enum Keys {
        static let database = "quick_unlock_database"
        static let biometryState = "quick_unlock_biometry_state"
        static let offered = "quick_unlock_offered"
        static let interval = "quick_unlock_interval_days"
        static let typed = "quick_unlock_password_typed"
    }

    enum Unusable: Equatable {
        /// Not turned on for this database.
        case off
        /// The master password has to be typed first.
        case passwordDue
        /// Faces, fingerprints, or the passcode changed: turned off.
        case invalidated
        /// Face ID is turned off for the app, or locked after too many tries.
        case unavailable
    }

    struct Invalidated: Error {}
}

/// What the device offers for quick unlock.
nonisolated struct Biometry: Equatable, Sendable {
    enum Kind: Equatable, Sendable {
        case faceID, touchID, opticID

        init(_ type: LABiometryType) {
            switch type {
            case .touchID: self = .touchID
            case .opticID: self = .opticID
            default: self = .faceID
            }
        }

        /// Product names, the same in every language.
        var name: String {
            switch self {
            case .faceID: "Face ID"
            case .touchID: "Touch ID"
            case .opticID: "Optic ID"
            }
        }

        var symbol: String {
            switch self {
            case .faceID: "faceid"
            case .touchID: "touchid"
            case .opticID: "opticid"
            }
        }
    }

    enum State: Equatable, Sendable {
        /// Set up and allowed, with the fingerprint of the current set of faces or fingers.
        case ready(Data?)
        case lockedOut
        case notEnrolled
        /// No hardware, or the user refused the app's use of Face ID.
        case unavailable
    }

    var kind: Kind
    var state: State
}

/// A point in time the expiry rule can compare: when the device last started, and how long it
/// has been running (sleep included). Seconds.
nonisolated struct Moment: Codable, Equatable, Sendable {
    var bootTime: Double
    var uptime: Double

    /// The kernel's boot time drifts by fractions of a second as the clock is kept in sync.
    static let bootTimeTolerance: Double = 60

    static func now() -> Moment {
        var boot = timeval()
        var size = MemoryLayout<timeval>.size
        let bootTime = sysctlbyname("kern.boottime", &boot, &size, nil, 0) == 0
            ? Double(boot.tv_sec) + Double(boot.tv_usec) / 1_000_000
            : -1
        // The same clock as Swift's `ContinuousClock`: counts while asleep, can't be set.
        let uptime = Double(clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW)) / 1_000_000_000
        return Moment(bootTime: bootTime, uptime: uptime)
    }
}

/// When the master password was last typed, and for which database.
nonisolated private struct PasswordTyped: Codable {
    var database: String
    var moment: Moment
}

/// Hands a value that isn't `Sendable` to one other task, which is then its only user.
nonisolated private struct Unchecked<Value>: @unchecked Sendable {
    let value: Value
    init(_ value: Value) { self.value = value }
}
