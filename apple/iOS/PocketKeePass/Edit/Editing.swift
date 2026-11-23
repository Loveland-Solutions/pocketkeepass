import Observation
import PocketKeePassCore
import SwiftUI

/// An entry or group to delete or move.
enum EditItem: Equatable {
    case entry(id: String, name: String, inRecycleBin: Bool)
    case group(id: String, name: String, inRecycleBin: Bool, isRecycleBin: Bool)

    var id: String {
        switch self {
        case .entry(let id, _, _), .group(let id, _, _, _): id
        }
    }

    /// The name to show, with a stand-in for an empty one.
    var displayName: String {
        switch self {
        case .entry(_, let name, _): name.isEmpty ? String(localized: "(no title)") : name
        case .group(_, let name, _, _): name.isEmpty ? String(localized: "(no name)") : name
        }
    }

    var inRecycleBin: Bool {
        switch self {
        case .entry(_, _, let inBin), .group(_, _, let inBin, _): inBin
        }
    }

    var isRecycleBin: Bool {
        if case .group(_, _, _, true) = self { true } else { false }
    }

    var isGroup: Bool {
        if case .group = self { true } else { false }
    }
}

/// Creating or editing a group: its name and notes.
@Observable
final class GroupForm: Identifiable {
    /// The group being edited, or nil for a new one.
    let groupId: String?
    /// Where a new group goes.
    let parentId: String?
    var name: String
    var notes: String
    private let initial: (String, String)

    init(groupId: String?, parentId: String?, name: String = "", notes: String = "") {
        self.groupId = groupId
        self.parentId = parentId
        self.name = name
        self.notes = notes
        initial = (name, notes)
    }

    var isNew: Bool { groupId == nil }
    var isModified: Bool { name != initial.0 || notes != initial.1 }
}

/// Choosing where to move `item`: `groups` are the places it can go, in tree order.
struct MoveChoice: Identifiable {
    let item: EditItem
    let groups: [GroupChoice]
    var id: String { item.id }
}

/// The browser's navigation and its edits: the entry editor, the group form, deleting, moving,
/// and duplicating, with the question to upgrade a KDBX 3 file first. Presented once, at the
/// root of the navigation stack (`EditingPresenter`), so every screen of the browser shares them.
///
/// Every change runs in the engine off the main thread through `Session`, which saves it at once.
@Observable
final class Editing {
    var path: [Route] = []
    var entryForm: EntryForm?
    var groupForm: GroupForm?
    var confirmDelete: EditItem?
    var askingDelete = false
    /// Whether deleting moves to the recycle bin, for the question's wording.
    var recycleBinEnabled = true
    var move: MoveChoice?
    /// An edit waiting for the user to agree to upgrade the file.
    var pendingUpgrade: (@MainActor () -> Void)?
    var askingUpgrade = false
    /// What the last change did, or why it failed, shown briefly.
    var message: String?
    /// A change is running.
    private(set) var busy = false

    /// Runs `edit` now, or after asking to upgrade a KDBX 3 file, which can't be saved as it is.
    func begin(_ session: Session, _ edit: @escaping @MainActor () -> Void) {
        if session.needsUpgrade {
            pendingUpgrade = edit
            askingUpgrade = true
        } else {
            edit()
        }
    }

    func newEntry(_ session: Session, groupId: String) {
        begin(session) { self.entryForm = .new(groupId: groupId) }
    }

    func editEntry(_ session: Session, _ entry: EntryDetails) {
        begin(session) { self.entryForm = .edit(entry) }
    }

    func newGroup(_ session: Session, parentId: String) {
        begin(session) { self.groupForm = GroupForm(groupId: nil, parentId: parentId) }
    }

    func editGroup(_ session: Session, id: String) {
        begin(session) {
            Task {
                guard let group = try? await engine(session, { try $0.groupDetails(id: id) }) else { return }
                self.groupForm = GroupForm(groupId: group.id, parentId: nil, name: group.name, notes: group.notes)
            }
        }
    }

    func delete(_ session: Session, _ item: EditItem) {
        begin(session) {
            Task {
                self.recycleBinEnabled = (try? await engine(session, { try $0.summary().recycleBinEnabled })) ?? true
                self.confirmDelete = item
                self.askingDelete = true
            }
        }
    }

    func move(_ session: Session, _ item: EditItem) {
        begin(session) {
            Task {
                guard let groups = try? await engine(session, { try $0.allGroups() }) else { return }
                self.move = MoveChoice(item: item, groups: moveTargets(for: item, in: groups))
            }
        }
    }

    /// Copies an entry, just after the original. From the entry's own screen, the copy opens.
    func duplicate(_ session: Session, id: String, open: Bool) {
        begin(session) {
            self.run(session) {
                let copy = try await session.change { try $0.duplicateEntry(id: id) }
                if open, self.path.last == .entry(id) { self.path[self.path.count - 1] = .entry(copy) }
                self.show(String(localized: "Made a copy."))
            }
        }
    }

    func confirmDeletion(_ item: EditItem) {
        confirmDelete = nil
        guard let session = currentSession else { return }
        run(session) {
            let deletion = try await session.change { database in
                switch item {
                case .entry(let id, _, _): try database.deleteEntry(id: id)
                case .group(let id, _, _, _): try database.deleteGroup(id: id)
                }
            }
            // Leave the deleted entry's screen.
            if self.path.last == .entry(item.id) { self.path.removeLast() }
            self.show(deletion == .movedToRecycleBin ? String(localized: "Moved to the recycle bin.") : String(localized: "Deleted."))
        }
    }

    /// Moves `item` (passed in: dismissing the picker clears `move` first).
    func moveTo(_ item: EditItem, groupId: String) {
        move = nil
        guard let session = currentSession else { return }
        run(session) {
            let moved = try await session.changeIfNeeded { database in
                switch item {
                case .entry(let id, _, _): try database.moveEntry(id: id, groupId: groupId)
                case .group(let id, _, _, _): try database.moveGroup(id: id, parentId: groupId)
                }
            }
            if moved { self.show(String(localized: "Moved.")) }
        }
    }

    /// Saves the group form. Returns the engine's refusal, for the form to show, or nil once saved.
    func saveGroup(_ session: Session, _ form: GroupForm) async -> String? {
        let name = form.name.trimmingCharacters(in: .whitespacesAndNewlines)
        let notes = form.notes
        let groupId = form.groupId, parentId = form.parentId
        do {
            if let groupId {
                try await session.changeIfNeeded { try $0.updateGroup(id: groupId, name: name, notes: notes) }
            } else if let parentId {
                _ = try await session.change { try $0.createGroup(parentId: parentId, name: name, notes: notes) }
            }
            return nil
        } catch {
            return session.isUnlocked ? error.userMessage : nil
        }
    }

    /// The session the browser works on, set by `EditingPresenter`. Used by the confirmations,
    /// which are answered after the screen that asked may have gone.
    @ObservationIgnored weak var currentSession: Session?

    /// Closes everything, when the database locks.
    func reset() {
        entryForm?.clear()
        entryForm = nil
        groupForm = nil
        confirmDelete = nil
        askingDelete = false
        move = nil
        pendingUpgrade = nil
        askingUpgrade = false
        message = nil
        path = []
    }

    private func run(_ session: Session, _ body: @escaping @MainActor () async throws -> Void) {
        guard !busy else { return }
        busy = true
        Task {
            defer { busy = false }
            do {
                try await body()
            } catch {
                if session.isUnlocked { show(error.userMessage) }
            }
        }
    }

    private func show(_ text: String) {
        message = text
        AccessibilityNotification.Announcement(text).post()
        Task {
            try? await Task.sleep(for: .seconds(3))
            if message == text { message = nil }
        }
    }
}

/// Where `item` can be moved: every group except the recycle bin and what's in it (deleting puts
/// things there), and for a group, except itself and its own subgroups. `groups` must be in tree
/// order, as `all_groups` lists them.
func moveTargets(for item: EditItem, in groups: [GroupChoice]) -> [GroupChoice] {
    var out: [GroupChoice] = []
    // While inside the group being moved: its depth. Its subgroups are the groups right after it
    // that are deeper.
    var movingDepth: UInt32?
    for group in groups {
        let inside = movingDepth.map { group.depth > $0 } ?? false
        if !inside { movingDepth = nil }
        if inside { continue }
        if item.isGroup, group.id == item.id {
            movingDepth = group.depth
        } else if !group.isRecycleBin, !group.isInRecycleBin {
            out.append(group)
        }
    }
    return out
}

/// Presents the browser's edits: the entry editor, the group form, the move picker, and the
/// questions. Put once on the browser's root.
struct EditingPresenter: ViewModifier {
    @Bindable var editing: Editing
    @Environment(Session.self) private var session

    func body(content: Content) -> some View {
        content
            .sheet(item: $editing.entryForm) { form in
                // A new entry opens once it's saved, as on Android.
                EntryEditorView(form: form) { id in editing.path.append(.entry(id)) }
            }
            .sheet(item: $editing.groupForm) { form in
                GroupEditorView(form: form)
            }
            .sheet(item: $editing.move) { choice in
                MovePickerView(choice: choice, databaseName: session.fileName) { editing.moveTo(choice.item, groupId: $0) }
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
