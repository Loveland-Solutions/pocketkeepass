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

        // Pick browse.kdbx (from the welcome screen, or "Change" if one was picked before).
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
