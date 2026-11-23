import Foundation
import Testing
@testable import PocketKeePass

/// What `QuickUnlock.check` decides before any Face ID prompt. The Keychain's own refusal after
/// the faces change can't be tried in the simulator (it doesn't enforce `.biometryCurrentSet`),
/// so this is the part that says why, and turns quick unlock off, without a prompt.
@MainActor
struct QuickUnlockCheckTests {
    private let defaults: UserDefaults
    private let quickUnlock: QuickUnlock
    private let database = RecentDatabase(bookmark: Data(), name: "test.kdbx")
    private let faces = Data([1, 2, 3])

    init() {
        let suite = "QuickUnlockCheckTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        // Its own Keychain service too: `disable` deletes the items, and the app's own must stay.
        quickUnlock = QuickUnlock(defaults: defaults, service: suite)
        // Turned on for `database` with `faces` enrolled, and the password typed just now.
        defaults.set(database.id, forKey: QuickUnlock.Keys.database)
        defaults.set(faces, forKey: QuickUnlock.Keys.biometryState)
        quickUnlock.passwordTyped(database)
    }

    private func biometry(_ state: Biometry.State) -> Biometry {
        Biometry(kind: .faceID, state: state)
    }

    @Test func usableWithTheSameFaces() {
        #expect(quickUnlock.check(database, biometry: biometry(.ready(faces))) == nil)
        #expect(quickUnlock.isEnabled(for: database))
    }

    @Test func otherFacesTurnItOff() {
        #expect(quickUnlock.check(database, biometry: biometry(.ready(Data([9])))) == .invalidated)
        #expect(!quickUnlock.isEnabled(for: database))
    }

    @Test func removingEveryFaceTurnsItOff() {
        #expect(quickUnlock.check(database, biometry: biometry(.notEnrolled)) == .invalidated)
        #expect(!quickUnlock.isEnabled(for: database))
    }

    @Test func lockedOutOrRefusedLeavesItOn() {
        #expect(quickUnlock.check(database, biometry: biometry(.lockedOut)) == .unavailable)
        #expect(quickUnlock.check(database, biometry: biometry(.unavailable)) == .unavailable)
        #expect(quickUnlock.isEnabled(for: database))
    }

    @Test func thePasswordComesFirstWhenDue() {
        quickUnlock.intervalDays = 1
        // Recorded for another database: this one's password was never typed.
        let other = RecentDatabase(bookmark: Data(), name: "other.kdbx")
        quickUnlock.passwordTyped(other)
        #expect(quickUnlock.check(database, biometry: biometry(.ready(faces))) == .passwordDue)
        #expect(quickUnlock.isEnabled(for: database)) // stays on
    }

    @Test func anotherDatabaseIsOff() {
        let other = RecentDatabase(bookmark: Data(), name: "other.kdbx")
        #expect(quickUnlock.check(other, biometry: biometry(.ready(faces))) == .off)
    }

    @Test func onlyTheOfferedChoicesAreKept() {
        quickUnlock.intervalDays = 5
        #expect(quickUnlock.intervalDays == QuickUnlock.defaultInterval)
        quickUnlock.intervalDays = 30
        #expect(quickUnlock.intervalDays == 30)
    }

    @Test func olderRecordsGetAnID() throws {
        let old = #"{"bookmark":"","name":"old.kdbx"}"#.data(using: .utf8)!
        let decoded = try JSONDecoder().decode(RecentDatabase.self, from: old)
        #expect(!decoded.id.isEmpty)
        let again = try JSONDecoder().decode(RecentDatabase.self, from: JSONEncoder().encode(decoded))
        #expect(again.id == decoded.id)
    }
}
