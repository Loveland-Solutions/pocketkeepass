import XCTest

/// Drives the app in the simulator through the system file picker, as a person would.
///
/// Setup (once per simulator): open the Files app once, then copy `browse.kdbx` from
/// `core/tests/fixtures` into its "On My iPhone" folder, alone (see `apple/README.md`).
final class UnlockFlowTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    func testOpenUnlockBrowseSearchViewAndLock() throws {
        let app = XCUIApplication()
        app.launch()

        pickBrowseDatabase(app)

        // A wrong password: the engine's message, and the field is cleared.
        let password = app.secureTextFields["Master password"]
        XCTAssertTrue(password.waitForExistence(timeout: 5))
        focus(password)
        password.typeText("wrong")
        app.buttons["Unlock"].tap()
        XCTAssertTrue(app.staticTexts["The password or key file is incorrect."].waitForExistence(timeout: 60))
        XCTAssertEqual(password.value as? String ?? "", "Master password") // the placeholder: empty
        XCTAssertFalse(app.buttons["Restore backup"].exists, "a wrong password offered the backup")
        attach(app, "wrong password")

        focus(password)
        password.typeText("test-password\n")
        XCTAssertTrue(app.staticTexts["Work"].waitForExistence(timeout: 60))
        XCTAssertTrue(app.staticTexts["Recycle Bin"].exists)
        attach(app, "root group")

        // Into a group and back.
        app.staticTexts["Work"].tap()
        XCTAssertTrue(app.staticTexts["Servers"].waitForExistence(timeout: 5))
        app.navigationBars.buttons.element(boundBy: 0).tap()

        // Search covers the whole database, never passwords.
        let search = app.searchFields["Search entries"]
        search.tap()
        search.typeText("hunter2")
        XCTAssertTrue(app.staticTexts["No Results for “hunter2”"].waitForExistence(timeout: 5))
        search.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 7))
        search.typeText("rack 4")
        let server = app.staticTexts["Database Server"]
        XCTAssertTrue(server.waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Work › Servers"].exists)
        attach(app, "search")
        app.buttons.matching(NSPredicate(format: "label ==[c] 'cancel' OR label ==[c] 'close'")).firstMatch.tap()

        // An entry with a protected username: dots until shown.
        app.staticTexts["Banking"].tap()
        app.staticTexts["Crédit Union"].tap()
        XCTAssertTrue(app.buttons["Show Password"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["p4ss"].exists)
        app.buttons["Show Password"].tap()
        XCTAssertTrue(app.staticTexts["p4ss"].waitForExistence(timeout: 5))
        attach(app, "entry, password shown")
        app.buttons["Copy Username"].tap()
        XCTAssertTrue(app.staticTexts["Copied Username. It clears in 30 seconds."].waitForExistence(timeout: 5))

        // Lock from the entry screen: back to the unlock form, nothing left on screen.
        app.buttons["Lock"].tap()
        XCTAssertTrue(app.secureTextFields["Master password"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["p4ss"].exists)
        attach(app, "locked")
    }

    /// A new entry is saved into the file straight away, and is there after locking and
    /// unlocking again.
    ///
    /// This changes the simulator's copy of browse.kdbx: the first run upgrades it to KDBX 4
    /// (after asking), and every run adds an entry. Copy the fixture in again to start over.
    @MainActor
    func testNewEntryIsSaved() throws {
        let app = XCUIApplication()
        app.launch()
        pickBrowseDatabase(app)
        let password = app.secureTextFields["Master password"]
        XCTAssertTrue(password.waitForExistence(timeout: 5))
        focus(password)
        password.typeText("test-password\n")
        XCTAssertTrue(app.staticTexts["Work"].waitForExistence(timeout: 60))

        let title = "UI test \(UUID().uuidString.prefix(8))"
        addEntry(app, title: title, password: "s3cret")
        // "Saved" shows for 2 seconds only, so look for it first.
        XCTAssertTrue(app.staticTexts["Saved"].waitForExistence(timeout: 60), "never showed Saved")
        // The new entry opens, and back leads to its group, where it's listed.
        XCTAssertTrue(app.buttons["Edit"].waitForExistence(timeout: 5), "the new entry didn't open")
        XCTAssertTrue(app.staticTexts[title].exists, "another entry opened")
        attach(app, "saved")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.staticTexts["Work"].waitForExistence(timeout: 5))
        XCTAssertTrue(found(app, title), "the new entry isn't in the database")

        // The sync line, and what it opens: the save is there, and "Check now" works.
        let syncLine = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Up to date with the file · checked'")).firstMatch
        XCTAssertTrue(syncLine.waitForExistence(timeout: 5), "no sync line")
        syncLine.tap()
        XCTAssertTrue(app.navigationBars["Syncing"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Your last change was saved to the file at'")).firstMatch.exists)
        app.buttons["Check now"].tap()
        XCTAssertTrue(app.buttons["Check now"].waitForExistence(timeout: 30), "the check never finished")
        XCTAssertTrue(app.staticTexts["Last checked for changes from other devices just now."].exists)
        attach(app, "sync details")
        app.buttons["Done"].tap()

        // From the file again.
        app.buttons["Lock"].tap()
        XCTAssertTrue(password.waitForExistence(timeout: 5))
        focus(password)
        password.typeText("test-password\n")
        XCTAssertTrue(app.staticTexts["Work"].waitForExistence(timeout: 60))
        XCTAssertTrue(found(app, title), "the entry wasn't in the file")
        attach(app, "after unlocking again")
        app.buttons["Lock"].tap()
    }

    /// Editing an entry without touching its password, moving it, and deleting it into the
    /// recycle bin. Works on an entry it adds itself, so the fixture's own entries stay as the
    /// other tests expect them. Every run leaves one more entry in the recycle bin.
    @MainActor
    func testEditMoveAndDeleteEntry() throws {
        let app = XCUIApplication()
        app.launch()
        pickBrowseDatabase(app)
        let password = app.secureTextFields["Master password"]
        XCTAssertTrue(password.waitForExistence(timeout: 5))
        focus(password)
        password.typeText("test-password\n")
        XCTAssertTrue(app.staticTexts["Work"].waitForExistence(timeout: 60))

        let title = "UI edit \(UUID().uuidString.prefix(8))"
        addEntry(app, title: title, password: "keep-me")
        // Saving opens the new entry.

        // Edit: the password stays as dots with "Change", and isn't touched.
        let edit = app.buttons["Edit"]
        XCTAssertTrue(edit.waitForExistence(timeout: 60))
        edit.tap()
        XCTAssertTrue(app.buttons["Change Password"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.secureTextFields["Password"].exists)
        let username = app.textFields["Username"]
        focus(username)
        username.typeText("someone@example.com")
        app.buttons["Add field"].tap()
        let fieldName = app.textFields["Field name"]
        XCTAssertTrue(fieldName.waitForExistence(timeout: 5))
        focus(fieldName)
        fieldName.typeText("Account")
        let value = app.textFields["Value"]
        focus(value)
        value.typeText("42")
        attach(app, "editing")
        app.buttons["Save"].tap()

        XCTAssertTrue(app.staticTexts["someone@example.com"].waitForExistence(timeout: 60), "the edit isn't shown")
        XCTAssertTrue(app.staticTexts["42"].exists)
        app.buttons["Show Password"].tap()
        XCTAssertTrue(app.staticTexts["keep-me"].waitForExistence(timeout: 5), "the password changed")
        attach(app, "edited")

        // Move it into Work.
        app.buttons["More options"].tap()
        app.buttons["Move"].tap()
        let work = app.buttons["Work"]
        XCTAssertTrue(work.waitForExistence(timeout: 5))
        attach(app, "move picker")
        work.tap()
        XCTAssertTrue(app.staticTexts["Work"].waitForExistence(timeout: 10), "the group path didn't change")

        // Delete it: into the recycle bin, and back to the list.
        app.buttons["More options"].tap()
        app.buttons["Delete"].tap()
        let confirm = app.alerts.buttons["Move to recycle bin"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 5))
        attach(app, "delete question")
        confirm.tap()
        XCTAssertTrue(app.staticTexts["Saved"].waitForExistence(timeout: 60), "never showed Saved")
        XCTAssertTrue(app.staticTexts["Recycle Bin"].waitForExistence(timeout: 10), "didn't go back to the list")
        XCTAssertFalse(app.staticTexts[title].exists)

        app.staticTexts["Recycle Bin"].tap()
        XCTAssertTrue(app.staticTexts[title].waitForExistence(timeout: 5), "not in the recycle bin")
        attach(app, "recycle bin")
        app.buttons["Lock"].tap()
    }

    /// The generator: a password for a new entry, with symbols turned off and back on, used,
    /// saved, and the same password shown on the entry's screen.
    @MainActor
    func testGeneratePassword() throws {
        let app = XCUIApplication()
        app.launch()
        pickBrowseDatabase(app)
        let password = app.secureTextFields["Master password"]
        XCTAssertTrue(password.waitForExistence(timeout: 5))
        focus(password)
        password.typeText("test-password\n")
        XCTAssertTrue(app.staticTexts["Work"].waitForExistence(timeout: 60))

        app.buttons["Add"].tap()
        app.buttons["New entry"].tap()
        let upgrade = app.alerts.buttons["Upgrade"]
        if upgrade.waitForExistence(timeout: 3) { upgrade.tap() }
        let titleField = app.textFields["Title"]
        XCTAssertTrue(titleField.waitForExistence(timeout: 5))
        focus(titleField)
        let title = "UI generated \(UUID().uuidString.prefix(8))"
        titleField.typeText(title)

        app.buttons["Generate password"].tap()
        let generated = app.staticTexts["Generated password"]
        XCTAssertTrue(generated.waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label ENDSWITH 'bits)'")).firstMatch.exists, "no strength")

        // No symbols: only letters and digits.
        app.buttons["None"].tap()
        let plain = try XCTUnwrap(generated.value as? String)
        XCTAssertEqual(plain.count, 20)
        XCTAssertTrue(plain.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber) }, "symbols in \(plain)")
        app.buttons["Common"].tap()
        attach(app, "generator")

        let chosen = try XCTUnwrap(generated.value as? String)
        XCTAssertNotEqual(chosen, plain)
        app.buttons["Use"].tap()
        app.buttons["Save"].tap()
        XCTAssertTrue(app.buttons["Show Password"].waitForExistence(timeout: 60), "the new entry didn't open")
        app.buttons["Show Password"].tap()
        XCTAssertTrue(app.staticTexts[chosen].waitForExistence(timeout: 5), "the saved password isn't the generated one")
        attach(app, "saved with the generated password")
        app.buttons["Lock"].tap()
    }

    /// A damaged database file: the unlock screen offers the copy kept before the last save,
    /// restores it after asking, and the file unlocks again.
    ///
    /// The test can't damage the simulator's copy of browse.kdbx itself, so it runs only with
    /// `TEST_RUNNER_DAMAGED_FILE=1`, after the file was cut short from the Mac and with a save
    /// made before that (any run of `testNewEntryIsSaved`). See `apple/README.md`.
    @MainActor
    func testRestoreBackupOfDamagedFile() throws {
        guard ProcessInfo.processInfo.environment["DAMAGED_FILE"] == "1" else {
            throw XCTSkip("needs browse.kdbx cut short from the Mac first (apple/README.md)")
        }
        let app = XCUIApplication()
        app.launch()
        pickBrowseDatabase(app)
        let password = app.secureTextFields["Master password"]
        XCTAssertTrue(password.waitForExistence(timeout: 5))
        focus(password)
        password.typeText("test-password\n")

        let offer = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'PocketKeePass kept a copy of this database'")).firstMatch
        XCTAssertTrue(offer.waitForExistence(timeout: 60), "no backup offered")
        attach(app, "backup offered")
        app.buttons["Restore backup"].tap()
        let replace = app.alerts.buttons["Replace"]
        XCTAssertTrue(replace.waitForExistence(timeout: 5))
        replace.tap()
        XCTAssertTrue(app.staticTexts["The backup was restored. Unlock it as usual."].waitForExistence(timeout: 30))
        XCTAssertFalse(app.buttons["Restore backup"].exists)
        attach(app, "restored")

        focus(password)
        password.typeText("test-password\n")
        XCTAssertTrue(app.staticTexts["Work"].waitForExistence(timeout: 60), "the restored file didn't unlock")
        app.buttons["Lock"].tap()
    }

    /// A damaged database file's backup saved as a new file, "browse (restored).kdbx", which
    /// becomes the database and unlocks. The damaged file is left alone.
    ///
    /// Opt-in like `testRestoreBackupOfDamagedFile`, and it leaves the new file next to
    /// browse.kdbx: delete it afterwards, since the other tests need browse.kdbx alone there.
    @MainActor
    func testSaveBackupAsNewFile() throws {
        guard ProcessInfo.processInfo.environment["DAMAGED_FILE"] == "1" else {
            throw XCTSkip("needs browse.kdbx cut short from the Mac first (apple/README.md)")
        }
        let app = XCUIApplication()
        app.launch()
        pickBrowseDatabase(app)
        let password = app.secureTextFields["Master password"]
        XCTAssertTrue(password.waitForExistence(timeout: 5))
        focus(password)
        password.typeText("test-password\n")
        XCTAssertTrue(app.buttons["Save as new file…"].waitForExistence(timeout: 60))
        app.buttons["Save as new file…"].tap()
        sleep(3)
        attach(app, "save dialog")
        let save = app.buttons.matching(NSPredicate(format: "label ==[c] 'save' OR label ==[c] 'move'")).firstMatch
        XCTAssertTrue(save.waitForExistence(timeout: 10), "the save dialog didn't open")
        save.tap()
        XCTAssertTrue(app.staticTexts["The backup was restored. Unlock it as usual."].waitForExistence(timeout: 10), "not taken as the database")
        XCTAssertTrue(app.staticTexts["browse (restored).kdbx"].exists)
        focus(password)
        password.typeText("test-password\n")
        XCTAssertTrue(app.staticTexts["Work"].waitForExistence(timeout: 60), "the new file didn't unlock")
        attach(app, "unlocked new file")
        app.buttons["Lock"].tap()
    }

    /// Whether searching for `title` finds it. Every run adds entries to the fixture, so a new
    /// one may be below the fold, and lists only make the rows on screen. Leaves search closed.
    private func found(_ app: XCUIApplication, _ title: String) -> Bool {
        let search = app.searchFields["Search entries"]
        search.tap()
        search.typeText(title)
        let isThere = app.staticTexts[title].waitForExistence(timeout: 5)
        app.buttons.matching(NSPredicate(format: "label ==[c] 'cancel' OR label ==[c] 'close'")).firstMatch.tap()
        return isThere
    }

    /// Adds an entry to the group on screen, through "+" › "New entry", answering the upgrade
    /// question if the file is still KDBX 3 (the first run only).
    @MainActor
    private func addEntry(_ app: XCUIApplication, title: String, password: String) {
        app.buttons["Add"].tap()
        app.buttons["New entry"].tap()
        let upgrade = app.alerts.buttons["Upgrade"]
        if upgrade.waitForExistence(timeout: 3) {
            attach(app, "upgrade")
            upgrade.tap()
        }
        let titleField = app.textFields["Title"]
        XCTAssertTrue(titleField.waitForExistence(timeout: 5))
        focus(titleField)
        titleField.typeText(title)
        let entryPassword = app.secureTextFields["Password"]
        focus(entryPassword)
        entryPassword.typeText(password)
        attach(app, "new entry")
        app.buttons["Save"].tap()
    }

    /// Face ID quick unlock: offered after the master password, turned on, used to unlock after
    /// a lock, then turned off again.
    ///
    /// Needs Face ID enrolled in the simulator, and `face-id-matcher.sh` running on the Mac to
    /// answer a prompt with a matching face when this asks (only the Mac can do that). So it runs
    /// only with `TEST_RUNNER_FACE_ID_MATCHER=1` (see `apple/README.md`).
    @MainActor
    func testFaceIDQuickUnlock() throws {
        guard ProcessInfo.processInfo.environment["FACE_ID_MATCHER"] == "1" else {
            throw XCTSkip("needs Face ID enrolled and face-id-matcher.sh running (apple/README.md)")
        }
        // The matcher looks for this file in the test runner's temporary folder.
        // It answers about a second later; then the prompt closes.
        let matchFace = {
            FileManager.default.createFile(atPath: NSTemporaryDirectory() + "face-id-match", contents: Data())
            sleep(3)
        }
        let app = XCUIApplication()
        app.launch()
        // A run that stopped halfway can leave Face ID on: then the app opens at the prompt.
        // (A match with no prompt up does nothing.)
        matchFace()
        pickBrowseDatabase(app)
        let faceID = app.buttons["Unlock with Face ID"]
        XCTAssertFalse(faceID.exists) // picking a database turns it off

        let password = app.secureTextFields["Master password"]
        XCTAssertTrue(password.waitForExistence(timeout: 5))
        focus(password)
        password.typeText("test-password\n")

        // Asked once, right after the master password.
        let turnOn = app.alerts.buttons["Turn on"]
        XCTAssertTrue(turnOn.waitForExistence(timeout: 60), "no offer to turn on Face ID")
        attach(app, "offer")
        turnOn.tap()
        allowFaceIDIfAsked()
        matchFace()

        tapWhenReady(app.buttons["Settings"])
        let toggle = app.switches["Unlock this database with Face ID"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 10))
        XCTAssertTrue(waitFor(toggle, value: "1"), "Face ID didn't turn on")
        attach(app, "settings, on")
        app.buttons["Done"].tap()

        // Lock: the prompt opens by itself, the matcher answers, and the database opens again
        // with nothing typed. (While the prompt is up the app is inactive, so its privacy
        // cover hides the unlock screen.)
        let work = app.staticTexts["Work"]
        tapWhenReady(app.buttons["Lock"])
        XCTAssertTrue(work.waitForNonExistence(timeout: 5), "didn't lock")
        sleep(2)
        XCTAssertFalse(work.exists, "unlocked without Face ID")
        matchFace()
        XCTAssertTrue(work.waitForExistence(timeout: 60), "Face ID didn't unlock")
        attach(app, "unlocked with Face ID")
        XCTAssertFalse(app.alerts.buttons["Turn on"].exists) // not asked again

        // Off again from settings: the next lock has no Face ID.
        tapWhenReady(app.buttons["Settings"])
        XCTAssertTrue(toggle.waitForExistence(timeout: 10))
        toggle.switches.firstMatch.tap()
        XCTAssertTrue(waitFor(toggle, value: "0"))
        app.buttons["Done"].tap()
        tapWhenReady(app.buttons["Lock"])
        XCTAssertTrue(password.waitForExistence(timeout: 10))
        XCTAssertFalse(faceID.exists)
        attach(app, "locked, Face ID off")
    }

    /// The AutoFill extension in Safari: turned on from settings, then picked for a login page's
    /// password field, unlocked with the master password, and an entry found by search filled in.
    ///
    /// Needs the autofill probe's login page served on the Mac, so it runs only with
    /// `TEST_RUNNER_AUTOFILL_PAGE=1` (see `apple/README.md`):
    /// `python3 -m http.server 8765 -d tools/autofill-probe`.
    @MainActor
    func testAutoFillInSafari() throws {
        guard ProcessInfo.processInfo.environment["AUTOFILL_PAGE"] == "1" else {
            throw XCTSkip("needs the probe's login page on localhost:8765 (apple/README.md)")
        }
        let app = XCUIApplication()
        app.launch()
        pickBrowseDatabase(app)
        let password = app.secureTextFields["Master password"]
        XCTAssertTrue(password.waitForExistence(timeout: 5))
        focus(password)
        password.typeText("test-password\n")
        XCTAssertTrue(app.staticTexts["Work"].waitForExistence(timeout: 60))

        // The settings section. iOS's own question doesn't come up in the simulator (its helper
        // doesn't start), so the extension is turned on from the Mac beforehand with pluginkit.
        tapWhenReady(app.buttons["Settings"])
        let turnOn = app.buttons["Turn on AutoFill"]
        let on = app.staticTexts["PocketKeePass fills in passwords"]
        XCTAssertTrue(turnOn.waitForExistence(timeout: 10) || on.exists, "no AutoFill section")
        attach(app, "settings, AutoFill")
        app.buttons["Done"].tap()
        app.buttons["Lock"].tap()

        let safari = openPocketKeePassInSafari()

        // The extension: unlock, nothing saved for localhost, so search.
        let master = safari.secureTextFields["Master password"]
        XCTAssertTrue(master.waitForExistence(timeout: 15), safari.debugDescription)
        attach(safari, "extension, locked")
        master.typeText("test-password\n")
        searchAndFillVPN(safari)
    }

    /// The AutoFill extension unlocked with Face ID: quick unlock turned on in the app is used
    /// there too (the Keychain item is shared through the App Group), and nothing is typed.
    ///
    /// Needs both the login page and the Face ID matcher (`TEST_RUNNER_AUTOFILL_PAGE=1` and
    /// `TEST_RUNNER_FACE_ID_MATCHER=1`, see `apple/README.md`).
    @MainActor
    func testAutoFillWithFaceID() throws {
        let environment = ProcessInfo.processInfo.environment
        guard environment["AUTOFILL_PAGE"] == "1", environment["FACE_ID_MATCHER"] == "1" else {
            throw XCTSkip("needs the login page and face-id-matcher.sh (apple/README.md)")
        }
        let matchFace = {
            FileManager.default.createFile(atPath: NSTemporaryDirectory() + "face-id-match", contents: Data())
            sleep(3)
        }
        let app = XCUIApplication()
        app.launch()
        matchFace() // a run that stopped halfway may have left the prompt up
        pickBrowseDatabase(app)
        let password = app.secureTextFields["Master password"]
        XCTAssertTrue(password.waitForExistence(timeout: 5))
        focus(password)
        password.typeText("test-password\n")
        let turnOn = app.alerts.buttons["Turn on"]
        XCTAssertTrue(turnOn.waitForExistence(timeout: 60), "no offer to turn on Face ID")
        turnOn.tap()
        allowFaceIDIfAsked()
        matchFace()
        tapWhenReady(app.buttons["Lock"])

        // In Safari, the prompt opens by itself; the matcher answers it.
        let safari = openPocketKeePassInSafari()
        sleep(2)
        attach(safari, "extension, Face ID")
        allowFaceIDIfAsked()
        matchFace()
        searchAndFillVPN(safari)

        // The app's own quick unlock is still on (the extension didn't turn it off), then off
        // again for the other tests.
        // (Its prompt opened by itself right after Lock, and Safari took it away.)
        app.activate()
        let faceID = app.buttons["Unlock with Face ID"]
        XCTAssertTrue(faceID.waitForExistence(timeout: 10), "Face ID is off in the app\n" + app.debugDescription)
        tapWhenReady(faceID)
        matchFace()
        XCTAssertTrue(app.staticTexts["Work"].waitForExistence(timeout: 60), "Face ID no longer unlocks the app")
        tapWhenReady(app.buttons["Settings"])
        let toggle = app.switches["Unlock this database with Face ID"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 10))
        if toggle.value as? String == "1" { toggle.switches.firstMatch.tap() }
        app.buttons["Done"].tap()
        tapWhenReady(app.buttons["Lock"])
    }

    /// Opens the autofill probe's login page in Safari and picks PocketKeePass for its password
    /// field. Returns Safari, which shows the extension.
    @MainActor
    private func openPocketKeePassInSafari() -> XCUIApplication {
        let safari = XCUIApplication(bundleIdentifier: "com.apple.mobilesafari")
        XCUIDevice.shared.system.open(URL(string: "http://localhost:8765/login.html")!)
        let field = safari.webViews.secureTextFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 30), "the login page didn't load")
        field.tap()
        sleep(2)
        attach(safari, "password field")
        // With the Mac's keyboard standing in, iOS shows no on-screen keyboard and so no key
        // button above it: the field's edit menu has AutoFill › Passwords instead.
        let keyButton = safari.buttons.matching(NSPredicate(format: "label ==[c] 'Passwords' OR label CONTAINS[c] 'password options'")).firstMatch
        if !keyButton.waitForExistence(timeout: 3) {
            field.tap()
            let autoFill = safari.menuItems["AutoFill"].firstMatch
            XCTAssertTrue(autoFill.waitForExistence(timeout: 5), "no AutoFill in the edit menu\n" + safari.debugDescription)
            autoFill.tap()
            sleep(2)
            attach(safari, "AutoFill menu")
        }
        let passwords = safari.descendants(matching: .any).matching(NSPredicate(format: "label ==[c] 'Passwords'")).firstMatch
        XCTAssertTrue(passwords.waitForExistence(timeout: 10), "no Passwords\n" + safari.debugDescription)
        passwords.tap()
        sleep(2)
        attach(safari, "providers")
        let provider = safari.buttons["PocketKeePass"].firstMatch
        if provider.waitForExistence(timeout: 5) { provider.tap() }
        return safari
    }

    /// In the unlocked extension, with nothing saved for localhost: searches for "VPN", picks
    /// it, and checks the page got its username and password.
    @MainActor
    private func searchAndFillVPN(_ safari: XCUIApplication) {
        let search = safari.searchFields["Search all entries"]
        XCTAssertTrue(search.waitForExistence(timeout: 60), "didn't unlock")
        XCTAssertTrue(safari.staticTexts["No entries are saved for this site. Search for the entry to fill in."].exists)
        attach(safari, "extension, unlocked")
        search.tap()
        search.typeText("VPN")
        let vpn = safari.buttons.matching(NSPredicate(format: "label BEGINSWITH 'VPN'")).firstMatch
        XCTAssertTrue(vpn.waitForExistence(timeout: 10), safari.debugDescription)
        attach(safari, "search")
        vpn.tap()

        // Filled in: the page shows what its fields hold.
        let show = safari.webViews.buttons["Show"]
        XCTAssertTrue(show.waitForExistence(timeout: 15), "the extension didn't close")
        show.tap()
        XCTAssertTrue(safari.webViews.staticTexts["filled: jo.work | correct horse"].waitForExistence(timeout: 5), safari.debugDescription)
        attach(safari, "filled")
    }

    /// A new database: named, a master password typed twice, saved with the system's save
    /// dialog into "On My iPhone", opened straight away, an entry added, then locked and
    /// unlocked with that password.
    ///
    /// It leaves the new file in "On My iPhone", which the file picker tests want to themselves,
    /// so it runs only with `TEST_RUNNER_CREATE_DATABASE=1`, on a simulator of its own (see
    /// `apple/README.md`).
    @MainActor
    func testCreateDatabase() throws {
        guard ProcessInfo.processInfo.environment["CREATE_DATABASE"] == "1" else {
            throw XCTSkip("leaves a new file in On My iPhone (apple/README.md)")
        }
        let app = XCUIApplication()
        app.launch()
        if app.buttons["Lock"].waitForExistence(timeout: 3) { tapWhenReady(app.buttons["Lock"]) }
        let create = app.buttons["Create new database"]
        XCTAssertTrue(create.waitForExistence(timeout: 10))
        create.tap()

        let name = "New \(UUID().uuidString.prefix(6))"
        let nameField = app.textFields["new-database-name"]
        XCTAssertTrue(nameField.waitForExistence(timeout: 5))
        focus(nameField)
        let current = nameField.value as? String ?? ""
        nameField.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count) + name)
        let password = app.secureTextFields["new-master-password"]
        focus(password)
        password.typeText("new-db-password")
        let again = app.secureTextFields["new-master-password-again"]
        focus(again)
        // (Different passwords are refused: `CreateDatabaseTests`.)
        again.typeText("new-db-password")
        XCTAssertFalse(app.staticTexts["The two passwords are different."].exists)
        attach(app, "new database form")
        app.buttons["Create"].tap()

        // The system's save dialog, in "On My iPhone".
        let save = app.buttons.matching(NSPredicate(format: "label IN {'Save', 'Move'}")).firstMatch
        XCTAssertTrue(save.waitForExistence(timeout: 60), "no save dialog\n" + app.debugDescription)
        let onMyPhone = app.buttons["On My iPhone"].firstMatch
        if onMyPhone.exists { onMyPhone.tap() }
        attach(app, "save dialog")
        save.tap()

        // Open straight away, empty.
        let title = app.staticTexts["\(name).kdbx"]
        XCTAssertTrue(title.waitForExistence(timeout: 30), "the new database didn't open\n" + app.debugDescription)
        if app.alerts.buttons["Not now"].waitForExistence(timeout: 3) { app.alerts.buttons["Not now"].tap() }
        attach(app, "new database open")
        addEntry(app, title: "First entry", password: "s3cret")
        XCTAssertTrue(app.staticTexts["Saved"].waitForExistence(timeout: 60), "never showed Saved")
        app.navigationBars.buttons.element(boundBy: 0).tap()

        // Locked, then unlocked from the file with the new password.
        tapWhenReady(app.buttons["Lock"])
        let master = app.secureTextFields["Master password"]
        XCTAssertTrue(master.waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["\(name).kdbx"].exists, "not the remembered database")
        focus(master)
        master.typeText("new-db-password\n")
        XCTAssertTrue(app.staticTexts["First entry"].waitForExistence(timeout: 60), "the entry wasn't in the file")
        attach(app, "unlocked again")
        app.buttons["Lock"].tap()
    }

    /// Taps once the element can take it: right after a Face ID prompt or a sheet closes, it's
    /// still covered for a moment.
    @MainActor
    private func tapWhenReady(_ element: XCUIElement) {
        for _ in 0..<10 where !element.isHittable { sleep(1) }
        element.tap()
    }

    /// iOS asks once per app whether it may use Face ID.
    @MainActor
    private func allowFaceIDIfAsked() {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let allow = springboard.buttons.matching(NSPredicate(format: "label IN {'OK', 'Allow'}")).firstMatch
        if allow.waitForExistence(timeout: 5) { allow.tap() }
    }

    @MainActor
    private func waitFor(_ element: XCUIElement, value: String, timeout: TimeInterval = 15) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if element.value as? String == value { return true }
            sleep(1)
        }
        return false
    }

    /// Picks browse.kdbx in the system file picker (from the welcome screen, or "Change" if one
    /// was picked before). Picking also turns quick unlock off, so each test starts from there.
    @MainActor
    private func pickBrowseDatabase(_ app: XCUIApplication) {
        // A run that stopped halfway can leave Face ID on, and then the app opens unlocked:
        // turn it off and lock first.
        if app.buttons["Lock"].waitForExistence(timeout: 3) {
            // It may still be behind the Face ID prompt that just unlocked it.
            tapWhenReady(app.buttons["Settings"])
            let toggle = app.switches["Unlock this database with Face ID"]
            if toggle.waitForExistence(timeout: 5), toggle.value as? String == "1" {
                toggle.switches.firstMatch.tap()
            }
            app.buttons["Done"].tap()
            tapWhenReady(app.buttons["Lock"])
        }
        let open = app.buttons["Open database"]
        if open.waitForExistence(timeout: 3) { open.tap() } else { app.buttons["Change"].tap() }
        let browseTab = app.tabBars.buttons["Browse"]
        XCTAssertTrue(browseTab.waitForExistence(timeout: 10), "the file picker didn't open")
        browseTab.tap()
        let onMyPhone = app.cells.staticTexts["On My iPhone"]
        if onMyPhone.waitForExistence(timeout: 3) { onMyPhone.tap() }
        // Keep browse.kdbx alone in that folder: taps in the picker's icon grid can land on a
        // neighbouring file.
        let file = app.staticTexts["browse.kdbx"].firstMatch
        XCTAssertTrue(file.waitForExistence(timeout: 10), "browse.kdbx isn't in On My iPhone")
        // The picker runs in another process, and taps on its elements don't always arrive.
        // browse.kdbx is alone in the folder, so tap the grid's first icon by its position.
        let picker = app.navigationBars["FullDocumentManagerViewControllerNavigationBar"]
        for _ in 0..<3 where picker.exists {
            app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: 70, dy: 240)).tap()
            _ = picker.waitForNonExistence(timeout: 3)
        }
        XCTAssertFalse(picker.exists, "the file picker didn't close")
    }

    /// Taps until the field has the keyboard: right after the picker closes, a tap can land
    /// while the sheet is still animating away.
    @MainActor
    private func focus(_ field: XCUIElement) {
        for _ in 0..<5 {
            field.tap()
            if field.value(forKey: "hasKeyboardFocus") as? Bool == true { return }
            sleep(1)
        }
        attach(XCUIApplication(), "no focus")
        XCTFail("the field never got the keyboard")
    }

    @MainActor
    private func attach(_ app: XCUIApplication, _ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }
}
