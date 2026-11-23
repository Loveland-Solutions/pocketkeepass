import Foundation
import PocketKeePassCore
import Testing
@testable import PocketKeePass

/// The entry editor's form and the move picker's choices, against `browse.kdbx` in memory
/// (nothing is saved here; `SessionSaveTests` covers saving).
@MainActor
struct EditingTests {
    private let database: UnlockedDatabase

    init() throws {
        let fixture = URL(filePath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appending(path: "core/tests/fixtures/browse.kdbx")
        database = try UnlockedDatabase.open(file: Data(contentsOf: fixture), password: Data("test-password".utf8), keyFile: nil)
    }

    private static func id(_ byte: String) -> String { String(repeating: byte, count: 16) }
    private static let creditUnion = id("08")
    private static let vpn = id("04")

    private func save(_ form: EntryForm) throws -> Bool {
        try FieldDraft.withEdits(form.drafts()) { try database.updateEntry(id: form.entryId!, fields: $0, tags: form.tagList()) }
    }

    @Test func protectedValuesStartUnfetchedAndAreKept() throws {
        let before = try database.revealField(entryId: Self.creditUnion, key: "UserName")
        #expect(!before.isEmpty)
        let original = try database.entry(id: Self.creditUnion)
        #expect(!original.tags.isEmpty)
        let form = EntryForm.edit(original)
        let username = try #require(form.fields.first { $0.key == "UserName" })
        #expect(!username.isFetched)
        #expect(!form.isModified)

        form.fields.first { $0.key == "Title" }!.setValue("Credit Union")
        #expect(form.isModified)
        #expect(form.drafts().first { $0.key == "UserName" }?.value == nil)
        #expect(try save(form))

        let entry = try database.entry(id: Self.creditUnion)
        #expect(entry.title == "Credit Union")
        #expect(try database.revealField(entryId: Self.creditUnion, key: "UserName") == before)
        #expect(entry.tags == original.tags, "\(entry.tags) != \(original.tags)")
    }

    @Test func anUnchangedFormChangesNothing() throws {
        let form = EntryForm.edit(try database.entry(id: Self.creditUnion))
        #expect(!form.isModified)
        #expect(try save(form) == false)
    }

    @Test func customFieldsCanBeRenamedProtectedAddedAndRemoved() throws {
        let form = EntryForm.edit(try database.entry(id: Self.vpn))
        let account = try #require(form.customFields.first { $0.key == "Account ID" })
        account.name = "Account number"
        account.isProtected = true
        let added = form.addField()
        added.name = "PIN"
        added.setValue("1234")
        // Custom fields go before the notes.
        #expect(form.drafts().last?.key == "Notes")
        #expect(try save(form))

        var entry = try database.entry(id: Self.vpn)
        let renamed = try #require(entry.fields.first { $0.key == "Account number" })
        #expect(renamed.isProtected)
        #expect(!entry.fields.contains { $0.key == "Account ID" })
        #expect(try database.revealField(entryId: Self.vpn, key: "PIN") == Data("1234".utf8))

        let again = EntryForm.edit(entry)
        again.removeField(try #require(again.customFields.first { $0.key == "PIN" }))
        #expect(again.isModified)
        #expect(try save(again))
        entry = try database.entry(id: Self.vpn)
        #expect(!entry.fields.contains { $0.key == "PIN" })
    }

    @Test func aRepeatedFieldNameIsRefused() throws {
        let form = EntryForm.edit(try database.entry(id: Self.vpn))
        form.addField().name = "Account ID"
        #expect(throws: KeePassError.self) { try save(form) }
    }

    @Test func groupsAreNeverMovedIntoThemselvesOrTheRecycleBin() throws {
        let groups = try database.allGroups()
        let work = Self.id("03"), servers = Self.id("05"), bin = Self.id("0b")
        let forGroup = moveTargets(for: .group(id: work, name: "Work", inRecycleBin: false, isRecycleBin: false), in: groups).map(\.id)
        #expect(!forGroup.contains(work))
        #expect(!forGroup.contains(servers))
        #expect(!forGroup.contains(bin))
        #expect(forGroup.contains(Self.id("01")))
        #expect(forGroup.contains(Self.id("07")))

        let forEntry = moveTargets(for: .entry(id: Self.vpn, name: "VPN", inRecycleBin: false), in: groups).map(\.id)
        #expect(forEntry.contains(work))
        #expect(forEntry.contains(servers))
        #expect(!forEntry.contains(bin))
    }
}
