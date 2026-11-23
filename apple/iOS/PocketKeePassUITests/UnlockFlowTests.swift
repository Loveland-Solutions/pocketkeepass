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
        XCTAssertTrue(app.staticTexts[title].exists, "the new entry isn't listed")
        attach(app, "saved")

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
        XCTAssertTrue(app.staticTexts[title].waitForExistence(timeout: 60), "the entry wasn't in the file")
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
        let row = app.staticTexts[title]
        XCTAssertTrue(row.waitForExistence(timeout: 60))
        row.tap()

        // Edit: the password stays as dots with "Change", and isn't touched.
        let edit = app.buttons["Edit"]
        XCTAssertTrue(edit.waitForExistence(timeout: 5))
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
