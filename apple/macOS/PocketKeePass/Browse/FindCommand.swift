import SwiftUI

/// Edit › Find (⌘F): puts the cursor in the toolbar's search field. (The iPhone app has its
/// own, on `BrowseView`: iOS has no menu bar command for an iPhone app.)
struct FindAction {
    let focusSearch: @MainActor () -> Void
}

extension FocusedValues {
    /// Set by the browser while a database is open; nil (and Find disabled) otherwise.
    @Entry var findAction: FindAction?
}

struct FindCommands: Commands {
    @FocusedValue(\.findAction) private var find

    var body: some Commands {
        CommandGroup(after: .textEditing) {
            Button("Find…") { find?.focusSearch() }
                .keyboardShortcut("f", modifiers: .command)
                .disabled(find == nil)
        }
    }
}
