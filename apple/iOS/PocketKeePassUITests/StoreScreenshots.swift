import XCTest

/// Takes the App Store screenshots (`art/app-store/screenshots`), with `art/demo/Demo.kdbx` copied
/// into the simulator's "On My iPhone" (or iPad) folder as `Personal.kdbx`, alone. Runs only with
/// `TEST_RUNNER_STORE_SCREENSHOTS=1`; see `art/app-store/screenshots/README.md` for the rest.
///
/// Each screenshot is attached to the test result, named by its order, and exported from there.
final class StoreScreenshots: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    func testStoreScreenshots() throws {
        guard ProcessInfo.processInfo.environment["STORE_SCREENSHOTS"] == "1" else {
            throw XCTSkip("only for taking the App Store screenshots (art/app-store/screenshots/README.md)")
        }
        let app = XCUIApplication()
        app.launch()
        pickPersonalDatabase(app)

        // 1. The unlock screen.
        let password = app.secureTextFields["Master password"]
        XCTAssertTrue(password.waitForExistence(timeout: 10))
        sleep(1)
        shot("1-unlock")

        // Right after the picker closes, a tap can land while it's still animating away.
        for _ in 0..<5 where password.value(forKey: "hasKeyboardFocus") as? Bool != true {
            password.tap()
            sleep(1)
        }
        password.typeText("demo\n")
        XCTAssertTrue(app.staticTexts["Finance"].waitForExistence(timeout: 60), "didn't unlock")
        // A first unlock asks about Face ID on a simulator with it set up.
        if app.alerts.buttons["Not now"].waitForExistence(timeout: 3) { app.alerts.buttons["Not now"].tap() }
        sleep(1)
        // 2. The database's groups and entries.
        shot("2-browse")

        // 3. An entry, with its password shown.
        app.staticTexts["Finance"].tap()
        XCTAssertTrue(app.staticTexts["Credit union"].waitForExistence(timeout: 5))
        app.staticTexts["Credit union"].tap()
        let show = app.buttons["Show Password"]
        XCTAssertTrue(show.waitForExistence(timeout: 5))
        show.tap()
        sleep(1)
        shot("3-entry")

        // 4. The password generator, from the entry's editor.
        app.buttons["Edit"].tap()
        let upgrade = app.alerts.buttons["Upgrade"]
        if upgrade.waitForExistence(timeout: 3) { upgrade.tap() }
        let generate = app.buttons["Generate password"].firstMatch
        XCTAssertTrue(generate.waitForExistence(timeout: 10), "no Generate password\n" + app.debugDescription)
        generate.tap()
        XCTAssertTrue(app.buttons["Use"].waitForExistence(timeout: 5))
        sleep(1)
        shot("4-generator")
        app.buttons.matching(NSPredicate(format: "label ==[c] 'Cancel'")).firstMatch.tap()
        sleep(1)
        app.buttons.matching(NSPredicate(format: "label ==[c] 'Cancel'")).firstMatch.tap()

        // 5. Search across every group.
        for _ in 0..<3 where !app.searchFields["Search entries"].exists {
            app.navigationBars.buttons.element(boundBy: 0).tap()
            sleep(1)
        }
        let search = app.searchFields["Search entries"]
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap()
        search.typeText("sam\n") // submitted, so the keyboard closes
        XCTAssertTrue(app.staticTexts["Streaming"].waitForExistence(timeout: 5))
        sleep(1)
        shot("5-search")
        // iPhone has a button to close search; on iPad, settings is still in reach.
        let close = app.buttons.matching(NSPredicate(format: "label ==[c] 'cancel' OR label ==[c] 'close'")).firstMatch
        if close.exists { close.tap() }

        // 6. Settings: auto-lock, Face ID, AutoFill, clipboard.
        app.buttons["Settings"].tap()
        XCTAssertTrue(app.staticTexts["AutoFill"].waitForExistence(timeout: 5))
        sleep(4) // the AutoFill row appears once iOS answers, or after 3 seconds
        shot("6-settings")
        app.buttons["Done"].tap()
        app.buttons["Lock"].tap()
    }

    /// Picks Personal.kdbx in the system file picker.
    @MainActor
    private func pickPersonalDatabase(_ app: XCUIApplication) {
        let open = app.buttons["Open database"]
        if open.waitForExistence(timeout: 5) { open.tap() } else { app.buttons["Change"].tap() }
        let browse = app.tabBars.buttons["Browse"]
        if browse.waitForExistence(timeout: 10) { browse.tap() }
        for place in ["On My iPhone", "On My iPad"] {
            let cell = app.cells.staticTexts[place]
            if cell.waitForExistence(timeout: 2) { cell.tap(); break }
            let other = app.staticTexts[place]
            if other.exists { other.tap(); break }
        }
        // The file's cell in the picker's grid (the unlock screen behind the sheet may show the
        // same name, from an earlier run).
        let file = app.cells.matching(NSPredicate(format: "label BEGINSWITH 'Personal'")).firstMatch
        XCTAssertTrue(file.waitForExistence(timeout: 10), "Personal.kdbx isn't there\n" + app.debugDescription)
        // The picker runs in another process, and taps on its elements don't always arrive. Tap
        // the middle of the file's cell by position instead.
        let picker = app.navigationBars["FullDocumentManagerViewControllerNavigationBar"]
        let frame = file.frame
        for _ in 0..<3 where picker.exists {
            app.coordinate(withNormalizedOffset: .zero)
                .withOffset(CGVector(dx: frame.midX, dy: frame.midY)).tap()
            _ = picker.waitForNonExistence(timeout: 3)
        }
        XCTAssertFalse(picker.exists, "the picker didn't close")
    }

    @MainActor
    private func shot(_ name: String) {
        let screenshot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        screenshot.name = name
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }
}
