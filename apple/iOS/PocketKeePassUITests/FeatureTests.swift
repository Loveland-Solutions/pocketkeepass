import XCTest

/// What `UnlockFlowTests` doesn't cover: key files, the one-time code and passkey rows,
/// groups (new, edit, move, delete, emptying the recycle bin), long-press menus, duplicating,
/// "Change" in the editor, and auto-lock.
///
/// No setup: each test opens a fresh copy of a fixture through the debug-only
/// `PKP_OPEN_DATABASE` hook (`UnlockView.openTestDatabase`), so none depends on the simulator's
/// files, and none changes them.
final class FeatureTests: XCTestCase {
    private static let fixtures = URL(filePath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .appending(path: "core/tests/fixtures")

    override func setUp() {
        continueAfterFailure = false
    }

    /// Password and key file, then key file alone (an empty password with a key file).
    @MainActor
    func testKeyFiles() throws {
        let app = launch("pw-and-keyfile.kdbx", keyFile: "pw-and-keyfile.keyx")
        XCTAssertTrue(app.staticTexts["Key file: pw-and-keyfile.keyx"].waitForExistence(timeout: 10))
        unlock(app, "test-password")
        XCTAssertTrue(app.staticTexts["Both Entry"].waitForExistence(timeout: 60), "password and key file didn't unlock")
        attach(app, "password and key file")

        app.terminate()
        let keyOnly = launch("keyfile-only.kdbx", keyFile: "keyfile.keyx")
        XCTAssertTrue(keyOnly.staticTexts["Key file: keyfile.keyx"].waitForExistence(timeout: 10))
        keyOnly.buttons["Unlock"].tap()
        XCTAssertTrue(keyOnly.staticTexts["Keyfile Entry"].waitForExistence(timeout: 60), "the key file alone didn't unlock")
        attach(keyOnly, "key file only")
        keyOnly.buttons["Lock"].tap()
    }

    /// The one-time code: hidden until shown, a countdown always visible, shown as digits,
    /// copied; and settings that can't be used explain themselves.
    @MainActor
    func testOneTimeCode() throws {
        let app = launch("totp.kdbx")
        unlock(app, "test-password")
        let defaults = app.staticTexts["Defaults"]
        XCTAssertTrue(defaults.waitForExistence(timeout: 60))
        defaults.tap()

        XCTAssertTrue(app.staticTexts["One-time code"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Changes in '")).firstMatch.exists,
                      "no countdown")
        let sixDigits = NSPredicate(format: "label MATCHES '[0-9]{6}'")
        XCTAssertEqual(app.staticTexts.matching(sixDigits).count, 0, "the code shows before \"show\"")
        // The fields the settings came from aren't listed.
        XCTAssertFalse(app.staticTexts["otp"].exists)
        attach(app, "code hidden")

        app.buttons["Show One-time code"].tap()
        XCTAssertTrue(app.staticTexts.matching(sixDigits).firstMatch.waitForExistence(timeout: 5), "no code shown")
        attach(app, "code shown")
        app.buttons["Copy One-time code"].tap()
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Copied One-time code'"))
            .firstMatch.waitForExistence(timeout: 5))

        // HOTP isn't supported: the reason, and the field stays listed.
        app.navigationBars.buttons.element(boundBy: 0).tap()
        // Below the fold: search for it.
        let search = app.searchFields["Search entries"]
        search.tap()
        search.typeText("Counter")
        let counter = app.staticTexts["Counter"]
        XCTAssertTrue(counter.waitForExistence(timeout: 5))
        counter.tap()
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'HOTP'")).firstMatch.waitForExistence(timeout: 5),
                      "HOTP didn't explain itself")
        XCTAssertTrue(app.staticTexts["otp"].exists)
        attach(app, "HOTP")
        app.buttons["Lock"].tap()
    }

    /// A passkey shows as one row, its fields hidden; one that can't be used says why.
    @MainActor
    func testPasskeyRow() throws {
        let app = launch("passkey.kdbx")
        unlock(app, "test-password")
        let example = app.staticTexts["Example (Passkey)"]
        XCTAssertTrue(example.waitForExistence(timeout: 60))
        example.tap()
        XCTAssertTrue(app.staticTexts["For example.com"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'KPEX_'")).count, 0,
                       "the passkey's fields are listed")
        attach(app, "passkey")

        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.staticTexts["ML-DSA"].tap()
        XCTAssertTrue(app.staticTexts["This passkey uses ML-DSA keys, which PocketKeePass can't use yet."].waitForExistence(timeout: 5),
                      "an unusable passkey didn't say why")
        attach(app, "unusable passkey")
        app.buttons["Lock"].tap()
    }

    /// Groups: new (upgrading KDBX 3 after asking), renamed and moved from the long-press
    /// menu, deleted into the recycle bin, which is then emptied. And duplicating an entry
    /// from its long-press menu.
    @MainActor
    func testGroupsAndLongPressMenus() throws {
        let app = launch("browse.kdbx")
        unlock(app, "test-password")
        XCTAssertTrue(app.staticTexts["Work"].waitForExistence(timeout: 60))

        // New group.
        app.buttons["Add"].tap()
        app.buttons["New group"].tap()
        let upgrade = app.alerts.buttons["Upgrade"]
        XCTAssertTrue(upgrade.waitForExistence(timeout: 5), "KDBX 3 didn't ask to upgrade")
        upgrade.tap()
        let name = app.textFields["Name"]
        XCTAssertTrue(name.waitForExistence(timeout: 5))
        focus(name)
        name.typeText("UI Group")
        app.buttons["Save"].tap()
        XCTAssertTrue(app.staticTexts["UI Group"].waitForExistence(timeout: 60), "the new group isn't listed")
        attach(app, "new group")

        // Rename it from the long-press menu.
        app.staticTexts["UI Group"].press(forDuration: 1.2)
        app.buttons["Edit"].tap()
        XCTAssertTrue(name.waitForExistence(timeout: 5))
        focus(name)
        name.typeText(" 2")
        app.buttons["Save"].tap()
        XCTAssertTrue(app.staticTexts["UI Group 2"].waitForExistence(timeout: 60), "the rename isn't shown")

        // Move it into Work.
        app.staticTexts["UI Group 2"].press(forDuration: 1.2)
        app.buttons["Move"].tap()
        let work = app.buttons["Work"]
        XCTAssertTrue(work.waitForExistence(timeout: 5))
        // A group can't go into itself.
        XCTAssertFalse(app.buttons["UI Group 2"].exists, "the group was offered itself")
        work.tap()
        XCTAssertTrue(app.staticTexts["UI Group 2"].waitForNonExistence(timeout: 30), "still in the top group")
        app.staticTexts["Work"].tap()
        XCTAssertTrue(app.staticTexts["UI Group 2"].waitForExistence(timeout: 5), "not in Work")

        // Delete it: into the recycle bin.
        app.staticTexts["UI Group 2"].press(forDuration: 1.2)
        app.buttons["Delete"].tap()
        let toBin = app.alerts.buttons["Move to recycle bin"]
        XCTAssertTrue(toBin.waitForExistence(timeout: 5))
        toBin.tap()
        XCTAssertTrue(app.staticTexts["UI Group 2"].waitForNonExistence(timeout: 30))
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.staticTexts["Recycle Bin"].tap()
        XCTAssertTrue(app.staticTexts["UI Group 2"].waitForExistence(timeout: 5), "not in the recycle bin")
        attach(app, "in the recycle bin")
        app.navigationBars.buttons.element(boundBy: 0).tap()

        // Empty the recycle bin.
        app.staticTexts["Recycle Bin"].press(forDuration: 1.2)
        app.buttons["Empty recycle bin"].tap()
        let forGood = app.alerts.buttons["Delete"]
        XCTAssertTrue(forGood.waitForExistence(timeout: 5))
        attach(app, "empty question")
        forGood.tap()
        XCTAssertTrue(app.staticTexts["Saved"].waitForExistence(timeout: 60))
        if app.staticTexts["Recycle Bin"].exists {
            app.staticTexts["Recycle Bin"].tap()
            XCTAssertFalse(app.staticTexts["UI Group 2"].waitForExistence(timeout: 3), "the recycle bin wasn't emptied")
            app.navigationBars.buttons.element(boundBy: 0).tap()
        }

        // Duplicate an entry from its long-press menu: the copy is listed, and we stay here.
        app.staticTexts["Banking"].tap()
        let original = app.staticTexts["Crédit Union"]
        XCTAssertTrue(original.waitForExistence(timeout: 5))
        original.press(forDuration: 1.2)
        app.buttons["Duplicate"].tap()
        XCTAssertTrue(app.staticTexts["Crédit Union - Copy"].waitForExistence(timeout: 60), "no copy listed")
        XCTAssertTrue(app.staticTexts["Crédit Union"].exists)
        attach(app, "duplicated")
        app.buttons["Lock"].tap()
    }

    /// "Change" fetches a protected value into the editor; the new one is saved and shown.
    @MainActor
    func testChangeProtectedValue() throws {
        let app = launch("browse.kdbx")
        unlock(app, "test-password")
        XCTAssertTrue(app.staticTexts["Banking"].waitForExistence(timeout: 60))
        app.staticTexts["Banking"].tap()
        app.staticTexts["Crédit Union"].tap()
        XCTAssertTrue(app.buttons["Edit"].waitForExistence(timeout: 5))
        app.buttons["Edit"].tap()
        let upgrade = app.alerts.buttons["Upgrade"]
        if upgrade.waitForExistence(timeout: 3) { upgrade.tap() }

        let change = app.buttons["Change Password"]
        XCTAssertTrue(change.waitForExistence(timeout: 5))
        change.tap()
        let field = app.secureTextFields["Password"]
        XCTAssertTrue(field.waitForExistence(timeout: 5), "\"Change\" didn't open the field")
        // It holds the current value: "Show" reveals it. (The entry screen behind the editor has
        // a "Show Password" of its own.)
        app.cells.containing(.secureTextField, identifier: "Password").buttons["Show Password"].tap()
        let shown = app.textFields["Password"]
        XCTAssertTrue(waitFor(shown, value: "p4ss"), "the current password wasn't fetched")
        focus(shown)
        shown.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 8) + "n3w-pass")
        attach(app, "changed")
        app.buttons["Save"].tap()

        let reveal = app.buttons["Show Password"]
        XCTAssertTrue(reveal.waitForExistence(timeout: 60))
        reveal.tap()
        XCTAssertTrue(app.staticTexts["n3w-pass"].waitForExistence(timeout: 5), "the new password isn't the one saved")
        attach(app, "saved")
        app.buttons["Lock"].tap()
    }

    /// Auto-lock: after the chosen time without touches on screen, and when coming back after
    /// that time in the background (iOS suspends the app, so the check on return does it).
    @MainActor
    func testAutoLock() throws {
        let app = launch("pw-only.kdbx")
        unlock(app, "test-password")
        XCTAssertTrue(app.staticTexts["Email"].waitForExistence(timeout: 60))
        setAutoLock(app, "30 seconds")

        // On screen, untouched.
        XCTAssertTrue(app.secureTextFields["Master password"].waitForExistence(timeout: 45), "didn't lock on screen")
        XCTAssertFalse(app.staticTexts["Email"].exists)
        attach(app, "locked on screen")

        // In the background.
        unlock(app, "test-password")
        XCTAssertTrue(app.staticTexts["Email"].waitForExistence(timeout: 60))
        XCUIDevice.shared.press(.home)
        sleep(40)
        app.activate()
        XCTAssertTrue(app.secureTextFields["Master password"].waitForExistence(timeout: 10), "didn't lock in the background")
        XCTAssertFalse(app.staticTexts["Email"].exists)
        attach(app, "locked after the background")

        // Back to the default, for the other tests.
        unlock(app, "test-password")
        XCTAssertTrue(app.staticTexts["Email"].waitForExistence(timeout: 60))
        setAutoLock(app, "5 minutes")
        app.buttons["Lock"].tap()
    }

    // MARK: Helpers

    /// Launches the app with a fresh copy of `fixture` (and `keyFile`) picked.
    @MainActor
    private func launch(_ fixture: String, keyFile: String? = nil) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["PKP_OPEN_DATABASE"] = Self.fixtures.appending(path: fixture).path(percentEncoded: false)
        if let keyFile {
            app.launchEnvironment["PKP_OPEN_KEY_FILE"] = Self.fixtures.appending(path: keyFile).path(percentEncoded: false)
        }
        app.launch()
        // A run that stopped halfway can leave the app unlocked.
        if app.buttons["Lock"].waitForExistence(timeout: 2) { app.buttons["Lock"].tap() }
        XCTAssertTrue(app.staticTexts[fixture].waitForExistence(timeout: 10) || app.secureTextFields["Master password"].exists,
                      "the fixture wasn't opened")
        return app
    }

    @MainActor
    private func unlock(_ app: XCUIApplication, _ password: String) {
        let field = app.secureTextFields["Master password"]
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        focus(field)
        field.typeText(password + "\n")
    }

    @MainActor
    private func setAutoLock(_ app: XCUIApplication, _ choice: String) {
        app.buttons["Settings"].tap()
        let picker = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Lock after'")).firstMatch
        XCTAssertTrue(picker.waitForExistence(timeout: 5))
        picker.tap()
        let option = app.buttons[choice]
        XCTAssertTrue(option.waitForExistence(timeout: 5), "no \(choice) choice")
        option.tap()
        app.buttons["Done"].tap()
    }

    @MainActor
    private func waitFor(_ element: XCUIElement, value: String, timeout: TimeInterval = 10) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if element.value as? String == value { return true }
            sleep(1)
        }
        return false
    }

    /// Taps until the field has the keyboard.
    @MainActor
    private func focus(_ field: XCUIElement) {
        for _ in 0..<5 {
            field.tap()
            if field.value(forKey: "hasKeyboardFocus") as? Bool == true { return }
            sleep(1)
        }
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
