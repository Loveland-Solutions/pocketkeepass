import AppKit
import CoreGraphics
import PocketKeePassCore

/// Auto-type, as KeePass does it: PocketKeePass steps aside, and the entry's sequence is typed
/// into the app that comes to the front (the one used before PocketKeePass).
///
/// The engine works out the steps (`auto_type`: which sequence applies, whether auto-type is
/// allowed, what each placeholder means). Each field's value is fetched as bytes only when it's
/// typed, and wiped after. Characters are typed as Unicode (`keyboardSetUnicodeString`), so the
/// keyboard layout doesn't matter.
///
/// Typing into another app needs the user's permission (System Settings › Privacy & Security ›
/// Accessibility), the one sandboxed apps can ask for with `CGRequestPostEventAccess`.
///
/// Typing goes only to the app that came to the front when PocketKeePass hid itself: if another
/// app comes forward meanwhile (the user clicked elsewhere), typing stops at once, so nothing
/// is typed into a window the user didn't mean.
@MainActor
enum AutoType {
    enum Failure: Error {
        /// The user hasn't allowed PocketKeePass to type into other apps.
        case needsPermission
        /// No other app came to the front.
        case noTarget
        /// Another app came to the front while typing.
        case interrupted
    }

    /// Pause between keys. Some apps drop keys typed faster.
    private static let keyInterval = Duration.milliseconds(8)

    static func perform(entryId: String, session: Session) async throws {
        // Refusals (auto-type off, a sequence we can't type) come before anything happens.
        let steps = try await engine(session) { try $0.autoType(entryId: entryId) }
        guard CGPreflightPostEventAccess() else {
            // Adds PocketKeePass to the Accessibility list, and the first time, asks.
            _ = CGRequestPostEventAccess()
            throw Failure.needsPermission
        }
        let target = try await stepAside()
        for step in steps {
            switch step {
            case .field(let key):
                var bytes = try await engine(session) { try $0.revealField(entryId: entryId, key: key) }
                defer { bytes.wipe() }
                try await type(bytes, into: target)
            case .totp:
                let now = UInt64(Date().timeIntervalSince1970)
                var bytes = try await engine(session) { try $0.totpCode(entryId: entryId, unixSeconds: now) }
                defer { bytes.wipe() }
                try await type(bytes, into: target)
            case .text(let text):
                try await type(Data(text.utf8), into: target)
            case .key(let key, let count):
                for _ in 0..<count { try await press(keyCode(key), into: target) }
            case .delay(let milliseconds):
                try await Task.sleep(for: .milliseconds(Int(milliseconds)))
            case .clearField:
                try await press(0x00, flags: .maskCommand, into: target) // ⌘A
                try await press(0x33, into: target) // delete
            }
        }
    }

    /// Hides PocketKeePass and waits for another app to come to the front. Returns its process.
    private static func stepAside() async throws -> pid_t {
        let me = ProcessInfo.processInfo.processIdentifier
        NSApp.hide(nil)
        for _ in 0..<40 {
            try await Task.sleep(for: .milliseconds(50))
            if let front = NSWorkspace.shared.frontmostApplication, front.processIdentifier != me {
                // Give its window a moment to take the keyboard focus.
                try await Task.sleep(for: .milliseconds(250))
                guard NSWorkspace.shared.frontmostApplication?.processIdentifier == front.processIdentifier else {
                    throw Failure.interrupted
                }
                return front.processIdentifier
            }
        }
        throw Failure.noTarget
    }

    /// Types UTF-8 text one character at a time. A line break presses Return, a tab Tab.
    private static func type(_ bytes: Data, into target: pid_t) async throws {
        var iterator = bytes.makeIterator()
        var decoder = UTF8()
        while true {
            switch decoder.decode(&iterator) {
            case .scalarValue(let scalar):
                switch scalar {
                case "\n": try await press(0x24, into: target)
                case "\r": continue
                case "\t": try await press(0x30, into: target)
                default:
                    var units: [UInt16] = []
                    UTF16.encode(scalar) { units.append($0) }
                    defer { for i in units.indices { units[i] = 0 } }
                    try await post(unicode: units, into: target)
                }
            case .emptyInput:
                return
            case .error:
                continue
            }
        }
    }

    private static func post(unicode units: [UInt16], into target: pid_t) async throws {
        try check(target)
        let source = CGEventSource(stateID: .privateState)
        for down in [true, false] {
            guard let event = CGEvent(keyboardEventSource: source, virtualKey: 0, keyDown: down) else { continue }
            event.flags = []
            units.withUnsafeBufferPointer { event.keyboardSetUnicodeString(stringLength: $0.count, unicodeString: $0.baseAddress) }
            event.post(tap: .cghidEventTap)
        }
        try await Task.sleep(for: keyInterval)
    }

    private static func press(_ keyCode: CGKeyCode, flags: CGEventFlags = [], into target: pid_t) async throws {
        try check(target)
        let source = CGEventSource(stateID: .privateState)
        for down in [true, false] {
            guard let event = CGEvent(keyboardEventSource: source, virtualKey: keyCode, keyDown: down) else { continue }
            event.flags = flags
            event.post(tap: .cghidEventTap)
        }
        try await Task.sleep(for: keyInterval)
    }

    /// Stops if another app came to the front.
    private static func check(_ target: pid_t) throws {
        guard NSWorkspace.shared.frontmostApplication?.processIdentifier == target else { throw Failure.interrupted }
    }

    /// The Mac's virtual key codes (`kVK_*` in Carbon's `Events.h`).
    private static func keyCode(_ key: AutoTypeKey) -> CGKeyCode {
        switch key {
        case .tab: 0x30
        case .enter: 0x24
        case .space: 0x31
        case .backspace: 0x33
        case .delete: 0x75
        case .escape: 0x35
        case .up: 0x7E
        case .down: 0x7D
        case .left: 0x7B
        case .right: 0x7C
        case .home: 0x73
        case .end: 0x77
        case .pageUp: 0x74
        case .pageDown: 0x79
        }
    }

    /// Opens the Accessibility list in System Settings, where PocketKeePass is turned on.
    static func openSettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility") {
            NSWorkspace.shared.open(url)
        }
    }
}
