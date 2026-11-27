import Foundation
import Testing
@testable import PocketKeePassCore

/// Calls the Rust engine from Swift through the generated bindings, on this Mac.
///
/// The engine's own behavior is tested in Rust (`core/`). These tests only prove that the bridge
/// carries every kind of value across correctly: bytes, optional values, records, errors, and
/// object lifetimes. They use the sample databases in `core/tests/fixtures`, like the Android
/// app's `BridgeTest.kt`.
struct BridgeTests {
    var password: Data { Data("test-password".utf8) }

    func fixture(_ name: String) throws -> Data {
        let fixtures = URL(filePath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appending(path: "../../core/tests/fixtures").standardized
        return try Data(contentsOf: fixtures.appending(path: name))
    }

    func open(_ name: String, keyFile: String? = nil) throws -> UnlockedDatabase {
        try UnlockedDatabase.open(file: fixture(name), password: password, keyFile: keyFile.map(fixture))
    }

    /// The engine's error for `body`, failing the test if there is none.
    func error(_ body: () throws -> Void) -> KeePassError? {
        do {
            try body()
        } catch let error as KeePassError {
            return error
        } catch {
            Issue.record("not a KeePassError: \(error)")
            return nil
        }
        Issue.record("no error")
        return nil
    }

    @Test func theEngineLoads() {
        #expect(engineVersion() == "0.1.0")
    }

    @Test func theGeneratorMakesPasswordsAsBytesAndRefusesImpossibleOptions() throws {
        var options = PasswordOptions(
            length: 24, lowercase: true, uppercase: true, digits: true, symbols: .common,
            avoidLookalikes: true
        )
        let letters = "abcdefghijklmnopqrstuvwxyz"
        let allowed = Set(letters + letters.uppercased() + "0123456789" + passwordSymbols(symbols: .common))
            .subtracting(passwordLookalikes())
        let generated = try generatePassword(options: options)
        #expect(generated.count == 24)
        #expect(generated.allSatisfy { allowed.contains(Character(UnicodeScalar($0))) })
        #expect(try passwordStrengthBits(options: options) > 100)
        #expect(passwordSymbols(symbols: .none) == "")

        options.lowercase = false
        options.uppercase = false
        options.digits = false
        options.symbols = .none
        let refused = error { _ = try generatePassword(options: options) }
        #expect(refused == .Invalid(message: "Choose at least one kind of character."))
    }

    @Test func aDatabaseOpensAndDescribesItself() throws {
        let db = try open("kdbx41-keepassxc.kdbx")
        let summary = try db.summary()
        #expect(summary.name == "Passwords")
        #expect(summary.formatVersion == "4.1")
        #expect(!summary.needsUpgrade)
        #expect(summary.groupCount == 1)
        #expect(summary.entryCount == 3)
    }

    @Test func keyFilesWorkAloneAndWithAPassword() throws {
        let alone = try UnlockedDatabase.open(
            file: fixture("keyfile-only.kdbx"), password: nil, keyFile: fixture("keyfile.keyx"))
        #expect(try alone.summary().entryCount == 1)
        let both = try open("kdbx4-argon2d-aes256-keyfile.kdbx", keyFile: "kdbx4-hex.key")
        #expect(try both.summary().entryCount == 2)
    }

    @Test func eachFailureArrivesAsItsOwnErrorWithAReadableMessage() throws {
        let wrong = error {
            _ = try UnlockedDatabase.open(file: fixture("pw-only.kdbx"), password: Data("nope".utf8), keyFile: nil)
        }
        #expect(wrong == .WrongKey(message: "The password or key file is incorrect."))
        #expect(wrong?.localizedDescription == "The password or key file is incorrect.")

        guard case .NotKeePass = error({
            _ = try UnlockedDatabase.open(file: Data("not a database".utf8), password: password, keyFile: nil)
        }) else { Issue.record("expected NotKeePass"); return }
        guard case .Corrupt = error({
            _ = try UnlockedDatabase.open(
                file: fixture("kdbx4-argon2id-chacha20.kdbx").prefix(500), password: password, keyFile: nil)
        }) else { Issue.record("expected Corrupt"); return }
        guard case .InvalidKeyFile = error({
            _ = try UnlockedDatabase.open(file: fixture("pw-only.kdbx"), password: nil, keyFile: nil)
        }) else { Issue.record("expected InvalidKeyFile"); return }
    }

    @Test func aSavedDatabaseOpensAgainWithTheSameContent() throws {
        let db = try open("kdbx4-argon2id-chacha20.kdbx")
        let saved = try db.save()
        #expect(saved != (try fixture("kdbx4-argon2id-chacha20.kdbx")))
        let reopened = try UnlockedDatabase.open(file: saved, password: password, keyFile: nil)
        #expect(try reopened.summary() == db.summary())
    }

    @Test func aKdbx3DatabaseIsOnlySavedAfterAnUpgrade() throws {
        let db = try open("kdbx3-attachment.kdbx")
        #expect(try db.summary().needsUpgrade)
        guard case .NeedsUpgrade = error({ _ = try db.save() }) else { Issue.record("expected NeedsUpgrade"); return }
        try db.upgradeToKdbx4()
        let reopened = try UnlockedDatabase.open(file: db.save(), password: password, keyFile: nil)
        #expect(try reopened.summary().formatVersion == "4.0")
        #expect(try reopened.summary().entryCount == 2)
    }

    @Test func groupsAndSearchResultsArriveAsNestedRecords() throws {
        let db = try open("browse.kdbx")
        let root = try db.listGroup(id: nil)
        #expect(root.isRoot)
        #expect(root.groups.map(\.name) == ["Work", "Banking", "Archive", "Recycle Bin"])
        #expect(root.groups.last?.isRecycleBin == true)
        #expect(root.entries == [EntryItem(
            id: String(repeating: "02", count: 16), title: "Email", username: "me@example.com",
            url: "https://mail.example.com", icon: ItemIcon(standard: 19, customId: nil))])

        let work = try db.listGroup(id: root.groups[0].id)
        #expect(work.parents.map(\.name) == ["Root"])
        #expect(work.groups.map(\.name) == ["Servers"])

        let hits = try db.search(query: "rack 4")
        #expect(hits.map(\.entry.title) == ["Database Server"])
        #expect(hits.first?.groupPath == ["Work", "Servers"])
        #expect(try db.search(query: "hunter2").isEmpty)
    }

    @Test func protectedValuesComeOneAtATimeAsBytes() throws {
        let db = try open("browse.kdbx")
        let bank = try db.entry(id: String(repeating: "08", count: 16))
        #expect(bank.title == "Crédit Union")
        #expect(bank.fields.map(\.key) == ["UserName", "Password", "URL", "Notes"])
        #expect(bank.fields[0] == EntryField(key: "UserName", value: "", isProtected: true, isEmpty: false))
        #expect(try db.revealField(entryId: bank.id, key: "UserName") == Data("jo-secret-user".utf8))
        #expect(try db.revealField(entryId: bank.id, key: "Password") == Data("p4ss".utf8))
        guard case .NotFound = error({ _ = try db.revealField(entryId: bank.id, key: "Nope") })
        else { Issue.record("expected NotFound"); return }
    }

    @Test func autoTypeStepsNameFieldsWithoutTheirValues() throws {
        let db = try open("browse.kdbx")
        let bank = String(repeating: "08", count: 16)
        #expect(try db.autoType(entryId: bank) == [
            .field(key: "UserName"), .key(key: .tab, count: 1), .field(key: "Password"), .key(key: .enter, count: 1),
        ])
        guard case .NotFound = error({ _ = try db.autoType(entryId: String(repeating: "ee", count: 16)) })
        else { Issue.record("expected NotFound"); return }
    }

    @Test func theKeyHashOpensTheDatabaseAgain() throws {
        let file = try fixture("kdbx41-keepassxc.kdbx")
        let hash = try open("kdbx41-keepassxc.kdbx").keyHash()
        #expect(hash.count == 32)
        #expect(try UnlockedDatabase.openWithKeyHash(file: file, keyHash: hash).summary().entryCount == 3)
        guard case .WrongKey = error({ _ = try UnlockedDatabase.openWithKeyHash(file: file, keyHash: Data(count: 32)) })
        else { Issue.record("expected WrongKey"); return }
    }

    @Test func editsCrossTheBridgeAndANilValueKeepsAProtectedField() throws {
        let db = try open("kdbx41-keepassxc.kdbx")
        let root = try db.listGroup(id: nil).group.id
        let id = try db.createEntry(groupId: root, fields: [
            FieldEdit(key: "Title", value: Data("Router".utf8), previousKey: nil, isProtected: false),
            FieldEdit(key: "Password", value: Data("pw".utf8), previousKey: nil, isProtected: false),
            FieldEdit(key: "PIN", value: Data("0000".utf8), previousKey: nil, isProtected: true),
        ], tags: ["home"])
        #expect(try db.updateEntry(id: id, fields: [
            FieldEdit(key: "Title", value: Data("Modem".utf8), previousKey: nil, isProtected: false),
            FieldEdit(key: "Password", value: nil, previousKey: nil, isProtected: false),
            FieldEdit(key: "Code", value: nil, previousKey: "PIN", isProtected: true),
        ], tags: []))
        #expect(try db.entry(id: id).title == "Modem")
        #expect(try db.revealField(entryId: id, key: "Password") == Data("pw".utf8))
        #expect(try db.revealField(entryId: id, key: "Code") == Data("0000".utf8))

        let group = try db.createGroup(parentId: root, name: "Made", notes: "")
        #expect(try db.moveEntry(id: id, groupId: group))
        #expect(try db.deleteGroup(id: group) == .movedToRecycleBin)
        let reopened = try UnlockedDatabase.open(file: db.save(), password: password, keyFile: nil)
        #expect(try reopened.entry(id: id).title == "Modem")
    }

    @Test func totpSettingsCrossAsAnEnumAndCodesAsBytes() throws {
        let db = try open("totp.kdbx")
        let id = String(repeating: "21", count: 16)
        #expect(try db.entry(id: id).totp == .ready(periodSeconds: 30, length: 6, fields: ["otp"]))
        // The RFC 4226 code for counter 1, which a 30-second period reaches at 30 to 59.
        #expect(try db.totpCode(entryId: id, unixSeconds: 59) == Data("287082".utf8))
        guard case .invalid(let reason) = try db.entry(id: String(repeating: "2c", count: 16)).totp
        else { Issue.record("expected invalid"); return }
        #expect(reason.contains("Base32"))
    }

    @Test func aLockedDatabaseRefusesEverything() throws {
        let db = try open("pw-only.kdbx")
        #expect(!db.isLocked())
        db.lock()
        #expect(db.isLocked())
        guard case .Locked = error({ _ = try db.summary() }) else { Issue.record("expected Locked"); return }
        guard case .Locked = error({ _ = try db.save() }) else { Issue.record("expected Locked"); return }
        guard case .Locked = error({ _ = try db.revealField(entryId: String(repeating: "02", count: 16), key: "Password") })
        else { Issue.record("expected Locked"); return }
    }
}
