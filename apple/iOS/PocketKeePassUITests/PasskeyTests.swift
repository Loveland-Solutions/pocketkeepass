import XCTest

/// Passkeys through the AutoFill extension, in Safari on webauthn.io (a public WebAuthn test
/// site, so this needs the internet): registered with PocketKeePass, then used to sign in.
/// Runs only with `TEST_RUNNER_PASSKEY_SITE=1`, on a simulator with the extension turned on and
/// browse.kdbx remembered (see `apple/README.md`).
final class PasskeyTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    func testPasskeyOnWebAuthnIO() throws {
        guard ProcessInfo.processInfo.environment["PASSKEY_SITE"] == "1" else {
            throw XCTSkip("needs the internet and the extension turned on (apple/README.md)")
        }
        let safari = XCUIApplication(bundleIdentifier: "com.apple.mobilesafari")
        XCUIDevice.shared.system.open(URL(string: "https://webauthn.io")!)
        let username = safari.webViews.textFields.firstMatch
        XCTAssertTrue(username.waitForExistence(timeout: 30), "webauthn.io didn't load")
        let name = "pkp\(Int.random(in: 10000...99999))"
        username.tap()
        username.typeText(name)
        let register = safari.webViews.buttons["Register"].firstMatch
        register.tap() // may only close the keyboard
        sleep(2)
        if register.isHittable { register.tap() }
        // iOS's own sheet: save in PocketKeePass.
        let sheet = XCUIApplication(bundleIdentifier: "com.apple.AuthenticationServicesUI")
        let ours = sheet.buttons.matching(NSPredicate(format: "label CONTAINS 'PocketKeePass'")).firstMatch
        XCTAssertTrue(ours.waitForExistence(timeout: 20), "no passkey sheet\n" + sheet.debugDescription)
        ours.tap()
        sheet.buttons.matching(NSPredicate(format: "label IN {'Add Passkey', 'Continue', 'Save'}")).firstMatch.tap()
        sleep(3)
        attach(sheet, "extension (sheet)")
        attach(safari, "extension (safari)")

        // The extension: unlock, then a new entry.
        let extensionUI = [sheet, safari]
        guard let master = waitForAny(extensionUI.map { $0.secureTextFields["Master password"] }, timeout: 20) else {
            return XCTFail("no unlock screen\n" + sheet.debugDescription)
        }
        master.tap()
        master.typeText("test-password\n")
        guard let newEntry = waitForAny(extensionUI.map { $0.buttons["Save in a new entry"] }, timeout: 90) else {
            return XCTFail("didn't unlock\n" + sheet.debugDescription)
        }
        attach(sheet, "save a passkey")
        // Into Work rather than the top group (or wherever the last run put it).
        let ui = extensionUI.first { $0.buttons["Save in a new entry"].exists } ?? sheet
        let group = ui.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Group'")).firstMatch
        XCTAssertTrue(group.exists, "no group choice\n" + ui.debugDescription)
        group.tap()
        let work = ui.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Work'")).firstMatch
        XCTAssertTrue(work.waitForExistence(timeout: 10), "no group list\n" + ui.debugDescription)
        attach(ui, "choose a group")
        work.tap()
        XCTAssertTrue(ui.buttons.matching(NSPredicate(format: "label CONTAINS 'Work'")).firstMatch.waitForExistence(timeout: 10))
        newEntry.tap()

        // webauthn.io checked the new passkey.
        let success = safari.webViews.staticTexts.matching(NSPredicate(format: "label CONTAINS[c] 'success'")).firstMatch
        XCTAssertTrue(success.waitForExistence(timeout: 90), "webauthn.io didn't accept it\n" + safari.debugDescription)
        attach(safari, "registered")

        // Sign in with it.
        safari.webViews.buttons["Authenticate"].firstMatch.tap()
        // iOS has no list of PocketKeePass's passkeys (none is given to it), so it offers
        // "More from PocketKeePass…".
        let more = sheet.buttons.matching(NSPredicate(format: "label BEGINSWITH 'More from PocketKeePass'")).firstMatch
        XCTAssertTrue(more.waitForExistence(timeout: 20), "no sign-in sheet\n" + sheet.debugDescription)
        attach(sheet, "sign in (sheet)")
        more.tap()
        let proceed = sheet.buttons["Continue"].firstMatch
        if proceed.waitForExistence(timeout: 3) { proceed.tap() }

        // Unlock; the one passkey for the site signs straight away.
        guard let master2 = waitForAny(extensionUI.map { $0.secureTextFields["Master password"] }, timeout: 20) else {
            return XCTFail("no unlock screen for signing in\n" + sheet.debugDescription)
        }
        master2.tap()
        master2.typeText("test-password\n")
        let signedIn = safari.webViews.staticTexts.matching(NSPredicate(format: "label CONTAINS[c] 'logged in'")).firstMatch
        XCTAssertTrue(signedIn.waitForExistence(timeout: 90), "webauthn.io didn't sign in\n" + safari.debugDescription)
        attach(safari, "signed in")
    }

    @MainActor
    private func waitForAny(_ elements: [XCUIElement], timeout: TimeInterval) -> XCUIElement? {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if let found = elements.first(where: \.exists) { return found }
            sleep(1)
        }
        return nil
    }

    @MainActor
    private func attach(_ app: XCUIApplication, _ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }
}
