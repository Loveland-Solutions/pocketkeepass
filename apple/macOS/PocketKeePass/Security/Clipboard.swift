import AppKit
import Foundation

/// Copying field values on the Mac, with an expiry of our own.
///
/// Unlike iOS, `NSPasteboard` has no expiry, so a timer clears the copy after
/// `Preferences.clipboardSeconds`, and locking clears it at once. Either way only if it's still
/// ours (`changeCount`): something the user copied since is left alone. A copy left when the app
/// quits is cleared on the way out.
///
/// Copies are marked with the nspasteboard.org types that clipboard managers honour: concealed
/// (don't show or keep it) and transient (don't record it at all). Handoff's Universal Clipboard
/// only carries what's marked for the current host, so `.currentHostOnly` keeps it on this Mac.
final class Clipboard {
    static let shared = Clipboard()

    private static let concealed = NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType")
    private static let transient = NSPasteboard.PasteboardType("org.nspasteboard.TransientType")

    /// Our most recent copy, so clearing only removes it if it's still there.
    private var lastChangeCount: Int?
    private var clearTask: Task<Void, Never>?

    /// Puts `value` on the clipboard for `Preferences.clipboardSeconds`.
    ///
    /// The bytes become a `String` here because that's what the pasteboard takes; the pasteboard
    /// server keeps its own copy, which we can't wipe. The caller wipes `value`.
    func copy(_ value: Data) {
        guard let text = String(data: value, encoding: .utf8) else { return }
        let pasteboard = NSPasteboard.general
        pasteboard.prepareForNewContents(with: .currentHostOnly)
        pasteboard.setString(text, forType: .string)
        pasteboard.setData(Data(), forType: Self.concealed)
        pasteboard.setData(Data(), forType: Self.transient)
        lastChangeCount = pasteboard.changeCount
        clearTask?.cancel()
        let seconds = Preferences.clipboardSeconds
        clearTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(seconds))
            guard !Task.isCancelled else { return }
            self?.clearIfOurs()
        }
    }

    /// Locking clears a copy that's still on the clipboard.
    func databaseLocked() {
        clearIfOurs()
    }

    /// Clears our copy now, if the user hasn't copied something else since.
    func clearIfOurs() {
        clearTask?.cancel()
        clearTask = nil
        let pasteboard = NSPasteboard.general
        if let lastChangeCount, pasteboard.changeCount == lastChangeCount {
            pasteboard.clearContents()
        }
        lastChangeCount = nil
    }
}
