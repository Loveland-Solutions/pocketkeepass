import Foundation
import PocketKeePassCore
import Testing
@testable import PocketKeePass

/// The new-database form: what it lets through, and that what it makes opens with the
/// password typed.
@MainActor
struct CreateDatabaseTests {
    @Test func bothPasswordsAndANameAreNeeded() {
        let model = CreateDatabaseModel()
        #expect(!model.canCreate) // no password yet
        model.password = "correct horse"
        #expect(!model.canCreate)
        model.confirmation = "correct hors"
        #expect(model.passwordsDiffer)
        #expect(!model.canCreate)
        model.confirmation = "correct horse"
        #expect(!model.passwordsDiffer)
        #expect(model.canCreate)
        model.name = "   "
        #expect(!model.canCreate)
    }

    @Test func theNewDatabaseOpensWithThePasswordTyped() async throws {
        let model = CreateDatabaseModel()
        model.name = "Test"
        model.password = "correct horse"
        model.confirmation = "correct horse"
        await model.create()
        let made = try #require(model.made)
        #expect(model.password.isEmpty && model.confirmation.isEmpty) // cleared once used
        let opened = try UnlockedDatabase.open(file: made.file.data, password: Data("correct horse".utf8), keyFile: nil)
        #expect(try opened.summary().formatVersion == "4.0")
        #expect(try opened.listGroup(id: nil).group.name == "Test")
        opened.lock()
        model.discard()
        #expect(model.made == nil)
    }
}
