import Observation
import PocketKeePassCore
import SwiftUI

/// Presents the browser's edits: the entry editor, the group form, the move picker, and the
/// questions. Put once on the browser's root.
struct EditingPresenter: ViewModifier {
    @Bindable var editing: Editing
    /// Shows an entry: the iPhone pushes its screen, the Mac selects it.
    var openEntry: (String) -> Void
    @Environment(Session.self) private var session

    func body(content: Content) -> some View {
        content
            .sheet(item: $editing.entryForm) { form in
                // A new entry opens once it's saved, as on Android.
                EntryEditorView(form: form, created: openEntry)
                    .formStyle(.grouped).macSheetFrame(width: 560, height: 640)
            }
            .sheet(item: $editing.groupForm) { form in
                GroupEditorView(form: form)
                    .formStyle(.grouped).macSheetFrame(width: 460, height: 320)
            }
            .sheet(item: $editing.move) { choice in
                MovePickerView(choice: choice, databaseName: session.fileName) { editing.moveTo(choice.item, groupId: $0) }
                    .formStyle(.grouped).macSheetFrame(width: 420, height: 480)
            }
            .upgradeAlert(isPresented: $editing.askingUpgrade) {
                let edit = editing.pendingUpgrade
                editing.pendingUpgrade = nil
                Task {
                    do {
                        try await session.upgrade()
                        edit?()
                    } catch {
                        if session.isUnlocked { editing.message = error.userMessage }
                    }
                }
            }
            .alert(deleteTitle, isPresented: $editing.askingDelete, presenting: editing.confirmDelete) { item in
                Button(deletesForGood(item) ? String(localized: "Delete") : String(localized: "Move to recycle bin"), role: .destructive) {
                    editing.confirmDeletion(item)
                }
                Button("Cancel", role: .cancel) {}
            } message: { item in
                Text(deleteMessage(item))
            }
            .overlay(alignment: .bottom) {
                CopiedToast(message: editing.message).padding(.bottom, 48)
            }
            .onAppear { editing.currentSession = session }
            .onChange(of: session.isUnlocked) { _, unlocked in
                if !unlocked { editing.reset() }
            }
    }

    private func deletesForGood(_ item: EditItem) -> Bool {
        !editing.recycleBinEnabled || item.inRecycleBin || item.isRecycleBin
    }

    private var deleteTitle: String {
        guard let item = editing.confirmDelete else { return "" }
        return deletesForGood(item) ? String(localized: "Delete for good?") : String(localized: "Move to the recycle bin?")
    }

    private func deleteMessage(_ item: EditItem) -> String {
        let name = item.displayName
        if item.isRecycleBin {
            return String(localized: "Everything in the recycle bin is deleted for good. This can’t be undone. A new recycle bin appears the next time you delete something.")
        }
        switch (deletesForGood(item), item.isGroup) {
        case (true, true): return String(localized: "“\(name)” and everything in it are deleted for good. This can’t be undone.")
        case (true, false): return String(localized: "“\(name)” is deleted for good. This can’t be undone.")
        case (false, true): return String(localized: "“\(name)” and everything in it go to the recycle bin. You can move them back out from there.")
        case (false, false): return String(localized: "“\(name)” goes to the recycle bin. You can move it back out from there.")
        }
    }
}

extension View {
    /// Asks whether to upgrade a KDBX 3 database, which PocketKeePass can't save as it is.
    func upgradeAlert(isPresented: Binding<Bool>, upgrade: @escaping () -> Void) -> some View {
        alert("Upgrade to KDBX 4?", isPresented: isPresented) {
            Button("Upgrade", action: upgrade)
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This database uses the older KDBX 3 format, and PocketKeePass only saves KDBX 4. Upgrading keeps everything in it. KeePass 2.35 or later, KeePassXC 2.3 or later, and current KeePass apps open KDBX 4, but apps that only know KDBX 3 won’t open the file after you save.")
        }
    }
}
