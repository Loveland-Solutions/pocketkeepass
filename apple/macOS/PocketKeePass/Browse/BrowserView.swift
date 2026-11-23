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

    var body: some View {
        NavigationSplitView {
            List(selection: $selectedGroup) {
                // The top group as a row of its own, with its subgroups shown below it (not
                // folded inside it, which would hide everything at first).
                ForEach(tree) { root in
                    groupLabel(root).tag(root.id)
                        .contextMenu { groupMenu(root, isRoot: true) }
                    OutlineGroup(root.children ?? [], children: \.children) { node in
                        groupLabel(node).tag(node.id)
                            .contextMenu { groupMenu(node, isRoot: false) }
                    }
                }
            }
            .navigationSplitViewColumnWidth(min: 180, ideal: 220)
        } content: {
            List(selection: $selectedEntry) {
                Section {
                    ForEach(entries) { row in
                        EntryRowView(row: row, showsPath: !query.isEmpty)
                            .tag(row.id)
                            .contextMenu { entryMenu(row) }
                    }
                } header: {
                    SyncLine { showingSync = true }
                }
            }
            .overlay {
                if entries.isEmpty {
                    Text(query.isEmpty ? String(localized: "This group is empty.") : String(localized: "No matching entries."))
                        .foregroundStyle(.secondary)
                }
            }
            .searchable(text: $query, placement: .toolbar, prompt: Text("Search entries"))
            .navigationSplitViewColumnWidth(min: 240, ideal: 300)
        } detail: {
            if let selectedEntry {
                EntryDetailView(id: selectedEntry)
                    .id(selectedEntry)
            } else {
                Text("Choose an entry.").foregroundStyle(.secondary)
            }
        }
        .navigationTitle(session.fileName)
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
                    Button("New Entry") { if let group = targetGroup { editing.newEntry(session, groupId: group) } }
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
            // Layout checks without driving the Mac: open a sheet at launch. Debug builds only.
            for _ in 0..<20 where tree.isEmpty { try? await Task.sleep(for: .milliseconds(250)) }
            switch ProcessInfo.processInfo.environment["PKP_DEBUG_SHOW"] {
            case "new-entry": if let group = targetGroup { editing.newEntry(session, groupId: group) }
            case "new-group": if let group = targetGroup { editing.newGroup(session, parentId: group) }
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
        Button("Duplicate") { editing.duplicate(session, id: row.id, open: false) }
        Button("Move…") { editing.move(session, item) }
        Button("Delete…", role: .destructive) { editing.delete(session, item) }
    }

    /// Whether the entries shown are in the recycle bin (search never finds those).
    private var inRecycleBin: Bool {
        guard query.isEmpty, let selected = selectedGroup, let node = node(selected) else { return false }
        return node.isRecycleBin || node.inRecycleBin
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
