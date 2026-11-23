import Foundation
import UIKit
import UniformTypeIdentifiers

/// Copying field values, with an expiry.
///
/// iOS clears the item itself when it expires (`expirationDate`), even if PocketKeePass isn't
/// running anymore, so no timer of ours is needed. `localOnly` keeps it off Universal Clipboard,
/// which would otherwise send it to the user's other Apple devices.
///
/// Other apps can only read the clipboard after the user agrees to iOS's paste prompt, or when
/// the user pastes. Keyboards with "full access" can see it too; we can't prevent that.
final class Clipboard {
    static let shared = Clipboard()

    /// Our most recent copy, so locking can clear it only if it's still ours.
    private var lastChangeCount: Int?

    /// Puts `value` on the clipboard for `Preferences.clipboardSeconds`.
    ///
    /// The bytes become a `String` here because that's all `UIPasteboard` takes; the pasteboard
    /// server keeps its own copy, which we can't wipe either. The caller wipes `value`.
    func copy(_ value: Data) {
        guard let text = String(data: value, encoding: .utf8) else { return }
        let pasteboard = UIPasteboard.general
        pasteboard.setItems(
            [[UTType.utf8PlainText.identifier: text]],
            options: [
                .localOnly: true,
                .expirationDate: Date().addingTimeInterval(TimeInterval(Preferences.clipboardSeconds)),
            ]
        )
        lastChangeCount = pasteboard.changeCount
    }

    /// Locking clears a copy that's still on the clipboard, unless the user copied something else
    /// since.
    func databaseLocked() {
        let pasteboard = UIPasteboard.general
        if let lastChangeCount, pasteboard.changeCount == lastChangeCount {
            pasteboard.items = []
        }
        lastChangeCount = nil
    }
}
