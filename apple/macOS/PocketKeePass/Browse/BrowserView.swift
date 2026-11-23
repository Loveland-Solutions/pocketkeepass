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

    var body: some View {
        NavigationSplitView {
            List(selection: $selectedGroup) {
                // The top group as a row of its own, with its subgroups shown below it (not
                // folded inside it, which would hide everything at first).
                ForEach(tree) { root in
                    groupLabel(root).tag(root.id)
                    OutlineGroup(root.children ?? [], children: \.children) { node in
                        groupLabel(node).tag(node.id)
                    }
                }
            }
            .navigationSplitViewColumnWidth(min: 180, ideal: 220)
        } content: {
            List(entries, selection: $selectedEntry) { row in
                EntryRowView(row: row, showsPath: !query.isEmpty)
                    .tag(row.id)
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
        .toolbar {
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
        .task(id: TaskKey(group: selectedGroup, query: query, revision: session.revision)) {
            if !query.isEmpty {
                // Wait for a pause in typing.
                try? await Task.sleep(for: .milliseconds(150))
                guard !Task.isCancelled else { return }
            }
            await loadEntries()
        }
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
    /// Nil for a group without subgroups, so the outline shows no disclosure triangle.
    let children: [GroupNode]?

    /// The whole tree, from the top group down: one listing per group.
    nonisolated static func tree(_ database: UnlockedDatabase) throws -> [GroupNode] {
        func node(_ listing: GroupListing) throws -> GroupNode {
            let children = try listing.groups.map { try node(database.listGroup(id: $0.id)) }
            return GroupNode(id: listing.group.id, name: listing.group.name, icon: listing.group.icon,
                             isRecycleBin: listing.group.isRecycleBin, children: children.isEmpty ? nil : children)
        }
        return [try node(database.listGroup(id: nil))]
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
