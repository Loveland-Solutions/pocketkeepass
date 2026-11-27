import SwiftUI

/// The Entry menu: copying the selected entry's fields, opening its URL, and auto-typing it,
/// with KeePassXC's Mac shortcuts. The entry list's right-click menu shows the same shortcuts.
///
/// ⌘C belongs to Edit › Copy, so Copy Password takes it only while the entry list has the
/// keyboard focus (the list itself has nothing to copy, so Edit › Copy is off then). Anywhere
/// else, in the search field, the editor, or text selected in the entry, ⌘C copies text as usual.
struct EntryActions {
    let copyUsername: @MainActor () -> Void
    let copyPassword: @MainActor () -> Void
    let copyURL: @MainActor () -> Void
    let openURL: @MainActor () -> Void
    let autoType: @MainActor () -> Void
    /// Whether the entry list has the keyboard focus, so ⌘C copies the password.
    let listFocused: Bool
}

extension FocusedValues {
    /// Set by the browser while an entry is selected; nil (and the menu's items off) otherwise.
    @Entry var entryActions: EntryActions?
}

struct EntryCommands: Commands {
    @FocusedValue(\.entryActions) private var actions

    var body: some Commands {
        CommandMenu("Entry") {
            Button("Copy Username") { actions?.copyUsername() }
                .keyboardShortcut("b", modifiers: .command)
                .disabled(actions == nil)
            Button("Copy Password") { actions?.copyPassword() }
                .keyboardShortcut("c", modifiers: .command)
                .disabled(actions?.listFocused != true)
            Button("Copy URL") { actions?.copyURL() }
                .keyboardShortcut("u", modifiers: [.command, .shift])
                .disabled(actions == nil)
            Button("Open URL") { actions?.openURL() }
                .keyboardShortcut("u", modifiers: .command)
                .disabled(actions == nil)
            Divider()
            Button("Perform Auto-Type") { actions?.autoType() }
                .keyboardShortcut("v", modifiers: [.command, .shift])
                .disabled(actions == nil)
        }
    }
}
