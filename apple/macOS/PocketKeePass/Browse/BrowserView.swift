import AppKit
import PocketKeePassCore
import SwiftUI

/// The open database, in three columns: the group tree, the selected group's entries (or the
/// search results, across the whole database), and the selected entry.
struct BrowserView: View {
    @Environment(Session.self) private var session
    @State private var tree: [GroupNode] = []
    @State private var selectedGroup: String?
    @State private var entries: [EntryRow] = []
    @State private var selectedEntry: String?
    @State private var query = ""
    @State private var editing = Editing()
    @State private var showingSync = false
    /// The sidebar group an entry or group is being dragged over.
    @State private var dropTarget: String?
    @FocusState private var searchFocused: Bool
    /// Whether the entry list has the keyboard focus, so ⌘C copies the password.
    @FocusState private var entriesFocused: Bool
    /// Where to go once the open editor's changes are saved or discarded.
    @State private var pendingLeave: (@MainActor () -> Void)?
    @State private var askingLeave = false
    /// "Copied …" after copying from an entry's menu.
    @State private var copiedMessage: String?
    /// Something an entry's menu couldn't do, explained.
    @State private var problem: Problem?
    @State private var askingTypingPermission = false
    #if DEBUG
    @Environment(\.openSettings) private var openSettings
    #endif

    var body: some View {
        NavigationSplitView {
            List(selection: groupSelection) {
                // The top group as a row of its own, with its subgroups shown below it (not
                // folded inside it, which would hide everything at first).
                ForEach(tree) { root in
                    sidebarRow(root, isRoot: true)
                    OutlineGroup(root.children ?? [], children: \.children) { node in
                        sidebarRow(node, isRoot: false)
                    }
                }
            }
            .navigationSplitViewColumnWidth(min: 180, ideal: 220)
        } content: {
            List(selection: entrySelection) {
                Section {
                    ForEach(entries) { row in
                        EntryRowView(row: row, showsPath: !query.isEmpty)
                            .tag(row.id)
                            // Dragged onto a group in the sidebar, it moves there.
                            .draggable(DraggedItem(.entry(id: row.id, name: row.entry.title, inRecycleBin: inRecycleBin)))
                    }
                } header: {
                    SyncLine { showingSync = true }
                }
            }
            .overlay(alignment: .bottom) { CopiedToast(message: copiedMessage) }
            .overlay {
                if entries.isEmpty {
                    Text(query.isEmpty ? String(localized: "This group is empty.") : String(localized: "No matching entries."))
                        .foregroundStyle(.secondary)
                }
            }
            // Double-clicking an entry opens it for editing; right-clicking gives its menu.
            .contextMenu(forSelectionType: String.self) { ids in
                if let id = ids.first, ids.count == 1, let row = entries.first(where: { $0.id == id }) {
                    entryMenu(row)
                }
            } primaryAction: { ids in
                if let id = ids.first, ids.count == 1 { edit(id) }
            }
            .focused($entriesFocused)
            .searchable(text: $query, placement: .toolbar, prompt: Text("Search entries"))
            .searchFocused($searchFocused)
            .navigationSplitViewColumnWidth(min: 240, ideal: 300)
        } detail: {
            if let form = editing.entryForm {
                // Edited in place. Choosing another entry or group leaves it, asking first
                // if anything changed (`leavingEditor`).
                EntryEditorView(form: form, created: { id in
                    query = ""
                    selectedEntry = id
                }, close: { editing.entryForm = nil })
                .formStyle(.grouped)
                .id(form.id)
            } else if let selectedEntry {
                EntryDetailView(id: selectedEntry)
                    .id(selectedEntry)
            } else {
                Text("Choose an entry.").foregroundStyle(.secondary)
            }
        }
        .navigationTitle(session.fileName)
        .focusedSceneValue(\.findAction, FindAction { searchFocused = true })
        .focusedSceneValue(\.entryActions, selectedEntry.map { id in
            EntryActions(copyUsername: { copy(id, field: "UserName") },
                         copyPassword: { copy(id, field: "Password") },
                         copyURL: { copy(id, field: "URL") },
                         openURL: { openURL(id) },
                         autoType: { autoType(id) },
                         listFocused: entriesFocused)
        })
        .alert(leaveTitle, isPresented: $askingLeave) {
            Button("Save") { Task { await saveAndLeave() } }
            Button("Discard Changes", role: .destructive) {
                editing.entryForm = nil
                runPendingLeave()
            }
            Button("Keep Editing", role: .cancel) { pendingLeave = nil }
        } message: {
            Text("Your changes to this entry aren’t saved yet.")
        }
        .alert(item: $problem) { problem in
            Alert(title: Text(problem.message))
        }
        .alert("Allow PocketKeePass to type into other apps", isPresented: $askingTypingPermission) {
            Button("Open System Settings") { AutoType.openSettings() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Auto-type works by pressing keys for you, which macOS allows only with your permission. In System Settings › Privacy & Security › Accessibility, turn on PocketKeePass, then quit PocketKeePass (⌘Q) and open it again: macOS applies the permission when the app starts. Only allow it if you want to use auto-type: an app with this permission can press keys in any app.")
        }
        .safeAreaInset(edge: .bottom, spacing: 0) { SaveBar() }
        .sheet(isPresented: $showingSync) { SyncDetailsView().macSheetFrame(width: 460, height: 420) }
        .modifier(EditingPresenter(editing: editing) { id in
            query = ""
            selectedEntry = id
        })
        .environment(editing)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button("New Entry") {
                        leavingEditor { if let group = targetGroup { editing.newEntry(session, groupId: group) } }
                    }
                        .keyboardShortcut("n", modifiers: .command)
                    Button("New Group") { if let group = targetGroup { editing.newGroup(session, parentId: group) } }
                        .keyboardShortcut("n", modifiers: [.command, .shift])
                } label: {
                    Label("Add", systemImage: "plus")
                }
                .help(Text("New entry (⌘N) or group (⇧⌘N)"))
            }
            ToolbarItem(placement: .primaryAction) {
                Button {
                    session.lock()
                } label: {
                    Label("Lock", systemImage: "lock")
                }
                .help(Text("Lock (⌘L)"))
            }
        }
        .task(id: session.revision) { await loadTree() }
        #if DEBUG
        .task {
            // Layout checks and store screenshots without driving the Mac: select a group, an
            // entry, or a search, or open a sheet or Settings at launch. Debug builds only.
            for _ in 0..<20 where tree.isEmpty { try? await Task.sleep(for: .milliseconds(250)) }
            let environment = ProcessInfo.processInfo.environment
            if let name = environment["PKP_DEBUG_GROUP"] {
                func find(_ nodes: [GroupNode]) -> GroupNode? {
                    for node in nodes {
                        if node.name == name { return node }
                        if let found = find(node.children ?? []) { return found }
                    }
                    return nil
                }
                if let node = find(tree) { selectedGroup = node.id }
            }
            if let search = environment["PKP_DEBUG_QUERY"] { query = search }
            if let title = environment["PKP_DEBUG_ENTRY"] {
                for _ in 0..<20 {
                    if let row = entries.first(where: { $0.entry.title == title }) {
                        selectedEntry = row.id
                        break
                    }
                    try? await Task.sleep(for: .milliseconds(250))
                }
            }
            switch environment["PKP_DEBUG_SHOW"] {
            case "new-entry": if let group = targetGroup { editing.newEntry(session, groupId: group) }
            case "new-entry-field":
                if let group = targetGroup { editing.newEntry(session, groupId: group) }
                if let field = editing.entryForm?.addField() {
                    field.name = "Recovery codes"
                    field.setValue("1111-2222\n3333-4444\n5555-6666")
                }
            case "new-group": if let group = targetGroup { editing.newGroup(session, parentId: group) }
            case "settings": openSettings()
            default: break
            }
        }
        #endif
        .task(id: TaskKey(group: selectedGroup, query: query, revision: session.revision)) {
            if !query.isEmpty {
                // Wait for a pause in typing.
                try? await Task.sleep(for: .milliseconds(150))
                guard !Task.isCancelled else { return }
            }
            await loadEntries()
        }
    }

    /// The entry list's selection. Choosing another entry while one is being edited leaves the
    /// editor first.
    private var entrySelection: Binding<String?> {
        Binding(get: { selectedEntry }, set: { id in
            guard id != selectedEntry else { return }
            leavingEditor { selectedEntry = id }
        })
    }

    /// The group tree's selection, which leaves the editor the same way.
    private var groupSelection: Binding<String?> {
        Binding(get: { selectedGroup }, set: { id in
            guard id != selectedGroup else { return }
            leavingEditor { selectedGroup = id }
        })
    }

    /// Runs `go` once the entry editor is out of the way: at once if it's closed or has no
    /// changes (it just closes), otherwise after asking whether to save or discard them.
    private func leavingEditor(_ go: @escaping @MainActor () -> Void) {
        guard let form = editing.entryForm else { return go() }
        if form.isModified {
            pendingLeave = go
            askingLeave = true
        } else {
            editing.entryForm = nil
            go()
        }
    }

    private var leaveTitle: String {
        let title = editing.entryForm?.mainFields.first { $0.key == "Title" }?.value ?? ""
        return title.isEmpty ? String(localized: "Save changes to this entry?") : String(localized: "Save changes to “\(title)”?")
    }

    private func runPendingLeave() {
        let go = pendingLeave
        pendingLeave = nil
        go?()
    }

    /// "Save" in the question: saves, then goes where the user was going. If saving fails, the
    /// editor stays open with the reason.
    private func saveAndLeave() async {
        guard let form = editing.entryForm else { return runPendingLeave() }
        do {
            _ = try await form.save(in: session)
            if editing.entryForm === form { editing.entryForm = nil }
            runPendingLeave()
        } catch {
            pendingLeave = nil
            if session.isUnlocked { editing.message = error.userMessage }
        }
    }

    /// Where new entries and groups go: the selected group, or the top group.
    private var targetGroup: String? {
        guard let selected = selectedGroup, let node = node(selected), !node.isRecycleBin, !node.inRecycleBin else {
            return tree.first?.id
        }
        return node.id
    }

    private func node(_ id: String) -> GroupNode? {
        func find(_ nodes: [GroupNode]) -> GroupNode? {
            for node in nodes {
                if node.id == id { return node }
                if let found = find(node.children ?? []) { return found }
            }
            return nil
        }
        return find(tree)
    }

    @ViewBuilder
    private func groupMenu(_ node: GroupNode, isRoot: Bool) -> some View {
        let item = EditItem.group(id: node.id, name: node.name, inRecycleBin: node.inRecycleBin, isRecycleBin: node.isRecycleBin)
        if node.isRecycleBin {
            Button("Empty Recycle Bin…", role: .destructive) { editing.delete(session, item) }
        } else {
            if !node.inRecycleBin {
                Button("New Entry") { editing.newEntry(session, groupId: node.id) }
                Button("New Group Inside") { editing.newGroup(session, parentId: node.id) }
                Divider()
            }
            Button("Edit Group…") { editing.editGroup(session, id: node.id) }
            if !isRoot {
                Button("Move…") { editing.move(session, item) }
                Button("Delete…", role: .destructive) { editing.delete(session, item) }
            }
        }
    }

    @ViewBuilder
    private func entryMenu(_ row: EntryRow) -> some View {
        let item = EditItem.entry(id: row.id, name: row.entry.title, inRecycleBin: inRecycleBin)
        // The shortcuts are the Entry menu's (`EntryCommands`), shown here as KeePass does.
        Button("Copy Username") { copy(row.id, field: "UserName") }
            .keyboardShortcut("b", modifiers: .command)
        Button("Copy Password") { copy(row.id, field: "Password") }
            .keyboardShortcut("c", modifiers: .command)
        Button("Copy URL") { copy(row.id, field: "URL") }
            .keyboardShortcut("u", modifiers: [.command, .shift])
        Button("Open URL") { openURL(row.id) }
            .keyboardShortcut("u", modifiers: .command)
        Divider()
        Button("Perform Auto-Type") { autoType(row.id) }
            .keyboardShortcut("v", modifiers: [.command, .shift])
        Divider()
        Button("Edit…") { edit(row.id) }
        Button("Duplicate") { editing.duplicate(session, id: row.id, open: false) }
        Button("Move…") { editing.move(session, item) }
        Button("Delete…", role: .destructive) { editing.delete(session, item) }
    }

    /// Copies one field of an entry from its menu, fetched as bytes and wiped after.
    private func copy(_ id: String, field key: String) {
        let label = switch key {
        case "UserName": String(localized: "Username")
        case "Password": String(localized: "Password")
        default: String(localized: "URL")
        }
        Task {
            do {
                var bytes = try await engine(session) { try $0.revealField(entryId: id, key: key) }
                defer { bytes.wipe() }
                if bytes.isEmpty {
                    show(String(localized: "Nothing to copy: the \(label.lowercased()) is empty."))
                    return
                }
                Clipboard.shared.copy(bytes)
                show(String(localized: "Copied \(label). It clears in \(durationText(seconds: Preferences.clipboardSeconds))."))
            } catch {
                if session.isUnlocked { problem = Problem(message: error.userMessage) }
            }
        }
    }

    /// Opens the entry's URL in the default browser. Only web addresses: KeePass also runs
    /// `cmd://` URLs as commands, and other schemes open whatever app claims them.
    private func openURL(_ id: String) {
        Task {
            do {
                var bytes = try await engine(session) { try $0.revealField(entryId: id, key: "URL") }
                let text = String(decoding: bytes, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
                bytes.wipe()
                guard !text.isEmpty else {
                    show(String(localized: "This entry has no URL."))
                    return
                }
                // "example.com" opens as https://example.com, as KeePass does.
                let withScheme = text.contains("://") ? text : "https://" + text
                guard let url = URL(string: withScheme), let scheme = url.scheme?.lowercased(),
                      scheme == "https" || scheme == "http", url.host() != nil else {
                    problem = Problem(message: String(localized: "PocketKeePass opens only web addresses (http and https). Copy the URL to use it elsewhere."))
                    return
                }
                NSWorkspace.shared.open(url)
            } catch {
                if session.isUnlocked { problem = Problem(message: error.userMessage) }
            }
        }
    }

    private func autoType(_ id: String) {
        Task {
            do {
                try await AutoType.perform(entryId: id, session: session)
            } catch AutoType.Failure.needsPermission {
                askingTypingPermission = true
            } catch AutoType.Failure.noTarget {
                NSApp.unhide(nil)
                problem = Problem(message: String(localized: "There’s no other app to type into. Open the window you want to sign in to first, then come back and choose Perform Auto-Type."))
            } catch AutoType.Failure.interrupted {
                NSApp.unhide(nil)
                NSApp.activate()
                problem = Problem(message: String(localized: "Auto-type stopped because another window came to the front. Some of the entry may already have been typed."))
            } catch is CancellationError {
            } catch {
                NSApp.unhide(nil)
                if session.isUnlocked { problem = Problem(message: error.userMessage) }
            }
        }
    }

    private func show(_ message: String) {
        withAnimation { copiedMessage = message }
        AccessibilityNotification.Announcement(message).post()
        Task {
            try? await Task.sleep(for: .seconds(3))
            if copiedMessage == message { withAnimation { copiedMessage = nil } }
        }
    }

    /// Opens an entry for editing in the right-hand column.
    private func edit(_ id: String) {
        if editing.entryForm?.entryId == id { return } // already editing it
        leavingEditor {
            selectedEntry = id
            Task {
                guard let entry = try? await engine(session, { try $0.entry(id: id) }), editing.entryForm == nil else { return }
                editing.editEntry(session, entry)
            }
        }
    }

    /// Whether the entries shown are in the recycle bin (search never finds those).
    private var inRecycleBin: Bool {
        guard query.isEmpty, let selected = selectedGroup, let node = node(selected) else { return false }
        return node.isRecycleBin || node.inRecycleBin
    }

    /// A group in the sidebar: its menu, dragging it (not the top group or the recycle bin), and
    /// dropping entries and groups on it to move them there, or on the recycle bin to delete them
    /// (which asks first).
    @ViewBuilder
    private func sidebarRow(_ node: GroupNode, isRoot: Bool) -> some View {
        let row = groupLabel(node)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .background {
                if dropTarget == node.id {
                    RoundedRectangle(cornerRadius: 5).strokeBorder(Color.accentColor, lineWidth: 2)
                }
            }
            .tag(node.id)
            .contextMenu { groupMenu(node, isRoot: isRoot) }
            .dropDestination(for: DraggedItem.self) { items, _ in
                drop(items, on: node)
            } isTargeted: { targeted in
                if targeted {
                    dropTarget = node.id
                } else if dropTarget == node.id {
                    dropTarget = nil
                }
            }
        if isRoot || node.isRecycleBin {
            row
        } else {
            row.draggable(DraggedItem(.group(id: node.id, name: node.name, inRecycleBin: node.inRecycleBin, isRecycleBin: false)))
        }
    }

    /// Whether `item` can be dropped on `target`: a move into any group outside the recycle bin
    /// (for a group, not into itself or its own subgroups), or a delete on the recycle bin.
    private func accepts(_ item: DraggedItem, on target: GroupNode) -> Bool {
        if target.isRecycleBin { return !item.inRecycleBin }
        if target.inRecycleBin { return false }
        if item.kind == .group {
            guard item.id != target.id, let moving = node(item.id) else { return false }
            func contains(_ nodes: [GroupNode]) -> Bool {
                nodes.contains { $0.id == target.id || contains($0.children ?? []) }
            }
            return !contains(moving.children ?? [])
        }
        return true
    }

    private func drop(_ items: [DraggedItem], on target: GroupNode) -> Bool {
        dropTarget = nil
        // One at a time: the lists select one item, so a drag carries one.
        guard let dragged = items.first, items.count == 1, accepts(dragged, on: target) else { return false }
        if target.isRecycleBin {
            editing.delete(session, dragged.item)
        } else {
            let item = dragged.item
            editing.begin(session) { editing.moveTo(item, groupId: target.id) }
        }
        return true
    }

    private func groupLabel(_ node: GroupNode) -> some View {
        Label {
            Text(node.name.isEmpty ? String(localized: "(no name)") : node.name)
        } icon: {
            ItemIconView(icon: node.icon, kind: node.isRecycleBin ? .recycleBin : .group)
        }
    }

    private struct TaskKey: Equatable {
        let group: String?
        let query: String
        let revision: Int
    }

    private func loadTree() async {
        guard let found = try? await engine(session, { try GroupNode.tree($0) }) else { return }
        tree = found
        if selectedGroup == nil { selectedGroup = found.first?.id }
    }

    private func loadEntries() async {
        let query = query.trimmingCharacters(in: .whitespaces)
        let group = selectedGroup
        let found: [EntryRow]? = if query.isEmpty {
            try? await engine(session) { database in
                try database.listGroup(id: group).entries.map { EntryRow(entry: $0, path: []) }
            }
        } else {
            try? await engine(session) { database in
                try database.search(query: query).map { EntryRow(entry: $0.entry, path: $0.groupPath) }
            }
        }
        guard let found else { return }
        entries = found
        if let selectedEntry, !found.contains(where: { $0.id == selectedEntry }) { self.selectedEntry = nil }
    }
}

/// A message for an alert.
private struct Problem: Identifiable {
    let id = UUID()
    let message: String
}

/// A group and its subgroups, for the sidebar's outline.
struct GroupNode: Identifiable, Hashable, Sendable {
    let id: String
    let name: String
    let icon: ItemIcon
    let isRecycleBin: Bool
    /// Inside the recycle bin (deleting from there is for good).
    let inRecycleBin: Bool
    /// Nil for a group without subgroups, so the outline shows no disclosure triangle.
    let children: [GroupNode]?

    /// The whole tree, from the top group down: one listing per group.
    nonisolated static func tree(_ database: UnlockedDatabase) throws -> [GroupNode] {
        func node(_ listing: GroupListing, inBin: Bool) throws -> GroupNode {
            let inside = inBin || listing.group.isRecycleBin
            let children = try listing.groups.map { try node(database.listGroup(id: $0.id), inBin: inside) }
            return GroupNode(id: listing.group.id, name: listing.group.name, icon: listing.group.icon,
                             isRecycleBin: listing.group.isRecycleBin, inRecycleBin: inBin,
                             children: children.isEmpty ? nil : children)
        }
        return [try node(database.listGroup(id: nil), inBin: false)]
    }
}

/// An entry in the middle column, with where it lives when it's a search result.
struct EntryRow: Identifiable, Hashable, Sendable {
    let entry: EntryItem
    let path: [String]
    var id: String { entry.id }
}

private struct EntryRowView: View {
    let row: EntryRow
    let showsPath: Bool

    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: 2) {
                Text(row.entry.title.isEmpty ? String(localized: "(no title)") : row.entry.title)
                if !row.entry.username.isEmpty {
                    Text(row.entry.username).font(.caption).foregroundStyle(.secondary)
                }
                if showsPath, !row.path.isEmpty {
                    Text(row.path.joined(separator: " › ")).font(.caption2).foregroundStyle(.tertiary)
                }
            }
        } icon: {
            ItemIconView(icon: row.entry.icon, kind: .entry)
        }
        .padding(.vertical, 2)
    }
}
