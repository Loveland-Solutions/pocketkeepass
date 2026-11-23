import PocketKeePassCore
import SwiftUI

struct BrowseView: View {
    @Environment(Session.self) private var session
    @State private var editing = Editing()
    @State private var showingSettings = false

    var body: some View {
        NavigationStack(path: $editing.path) {
            GroupView(id: nil)
                .navigationDestination(for: Route.self) { route in
                    switch route {
                    case .group(let id): GroupView(id: id)
                    case .entry(let id): EntryView(id: id)
                    }
                }
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        Button {
                            showingSettings = true
                        } label: {
                            Label("Settings", systemImage: "gearshape")
                        }
                    }
                }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) { SaveBar() }
        .sheet(isPresented: $showingSettings) { SettingsView() }
        .modifier(EditingPresenter(editing: editing) { id in editing.path.append(.entry(id)) })
        .environment(editing)
    }
}

/// The lock button, on every screen of the browser. Asks first if there are changes that
/// couldn't be saved. (Auto-lock doesn't ask: security wins.)
struct LockButton: View {
    @Environment(Session.self) private var session
    @State private var askingUnsaved = false

    var body: some View {
        Button {
            if case .unsaved = session.saveState { askingUnsaved = true } else { session.lock() }
        } label: {
            Label("Lock", systemImage: "lock")
        }
        .confirmationDialog("Lock without saving?", isPresented: $askingUnsaved, titleVisibility: .visible) {
            Button("Lock anyway", role: .destructive) { session.lock() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Your changes aren’t saved to the file yet. Locking discards them.")
        }
    }
}

struct GroupView: View {
    /// nil for the root group.
    let id: String?
    @Environment(Session.self) private var session
    @Environment(Editing.self) private var editing
    @State private var listing: GroupListing?
    @State private var error: String?
    @State private var query = ""
    @State private var results: [SearchResult]?
    @State private var showingSync = false

    var body: some View {
        List {
            if results == nil, listing != nil {
                Section {
                    SyncLine { showingSync = true }
                        .listRowBackground(Color.clear)
                        .listRowInsets(EdgeInsets(top: 0, leading: 20, bottom: 0, trailing: 20))
                }
                .listSectionSpacing(.compact)
            }
            if let results {
                searchResults(results)
            } else if let listing {
                contents(listing)
            }
        }
        .overlay {
            if let error {
                ContentUnavailableView(error, systemImage: "exclamationmark.triangle")
            } else if results == nil, let listing, listing.groups.isEmpty, listing.entries.isEmpty {
                ContentUnavailableView("This group is empty.", systemImage: "folder")
            }
        }
        .navigationTitle(title)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) { LockButton() }
            if let listing, !isInRecycleBin(listing), results == nil {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button("New entry", systemImage: "key") {
                            editing.newEntry(session, groupId: listing.group.id)
                        }
                        Button("New group", systemImage: "folder.badge.plus") {
                            editing.newGroup(session, parentId: listing.group.id)
                        }
                    } label: {
                        Label("Add", systemImage: "plus")
                    }
                }
            }
        }
        .refreshable { await session.checkNow() }
        .sheet(isPresented: $showingSync) { SyncDetailsView() }
        .modifier(SearchModifier(enabled: id == nil, query: $query))
        .task(id: session.revision) { await load() }
        .task(id: query) { await search() }
    }

    private var title: Text {
        guard let listing, !listing.isRoot else {
            return Text(verbatim: session.fileName.isEmpty ? "PocketKeePass" : session.fileName)
        }
        return listing.group.name.isEmpty ? Text("(no name)") : Text(verbatim: listing.group.name)
    }

    @ViewBuilder
    private func contents(_ listing: GroupListing) -> some View {
        if !listing.groups.isEmpty {
            Section {
                ForEach(listing.groups, id: \.id) { group in
                    let item = EditItem.group(
                        id: group.id, name: group.name, inRecycleBin: isInRecycleBin(listing), isRecycleBin: group.isRecycleBin
                    )
                    NavigationLink(value: Route.group(group.id)) {
                        Label {
                            VStack(alignment: .leading) {
                                Text(group.name.isEmpty ? String(localized: "(no name)") : group.name)
                                Text(countText(groups: group.groupCount, entries: group.entryCount))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        } icon: {
                            ItemIconView(icon: group.icon, kind: group.isRecycleBin ? .recycleBin : .group)
                        }
                    }
                    .contextMenu { groupActions(item) }
                }
            }
        }
        if !listing.entries.isEmpty {
            Section {
                ForEach(listing.entries, id: \.id) { entry in
                    EntryRow(entry: entry, groupPath: nil)
                        .contextMenu {
                            entryActions(.entry(id: entry.id, name: entry.title, inRecycleBin: isInRecycleBin(listing)))
                        }
                }
            }
        }
    }

    @ViewBuilder
    private func searchResults(_ results: [SearchResult]) -> some View {
        if results.isEmpty {
            ContentUnavailableView.search(text: query)
        } else {
            ForEach(results, id: \.entry.id) { result in
                EntryRow(entry: result.entry, groupPath: result.groupPath)
                    .contextMenu {
                        // Search leaves out the recycle bin.
                        entryActions(.entry(id: result.entry.id, name: result.entry.title, inRecycleBin: false))
                    }
            }
        }
    }

    /// Whether this group is the recycle bin or inside it, where deleting is for good and nothing
    /// new is added.
    private func isInRecycleBin(_ listing: GroupListing) -> Bool {
        listing.group.isRecycleBin || listing.parents.contains(where: \.isRecycleBin)
    }

    @ViewBuilder
    private func entryActions(_ item: EditItem) -> some View {
        Button("Duplicate", systemImage: "plus.square.on.square") {
            editing.duplicate(session, id: item.id, open: false)
        }
        Button("Move", systemImage: "folder") { editing.move(session, item) }
        Button("Delete", systemImage: "trash", role: .destructive) { editing.delete(session, item) }
    }

    @ViewBuilder
    private func groupActions(_ item: EditItem) -> some View {
        if item.isRecycleBin {
            Button("Empty recycle bin", systemImage: "trash", role: .destructive) { editing.delete(session, item) }
        } else {
            Button("Edit", systemImage: "pencil") { editing.editGroup(session, id: item.id) }
            Button("Move", systemImage: "folder") { editing.move(session, item) }
            Button("Delete", systemImage: "trash", role: .destructive) { editing.delete(session, item) }
        }
    }

    private func countText(groups: UInt32, entries: UInt32) -> String {
        let groupText = String(localized: "\(Int(groups)) groups")
        let entryText = String(localized: "\(Int(entries)) entries")
        switch (groups, entries) {
        case (0, _): return entryText
        case (_, 0): return groupText
        default: return String(localized: "\(groupText), \(entryText)")
        }
    }

    private func load() async {
        let id = id
        do {
            listing = try await engine(session) { try $0.listGroup(id: id) }
            error = nil
        } catch {
            if session.isUnlocked { self.error = error.userMessage }
        }
    }

    private func search() async {
        let words = query.trimmingCharacters(in: .whitespaces)
        guard !words.isEmpty else {
            results = nil
            return
        }
        // Typing fast shouldn't run a search per keystroke.
        try? await Task.sleep(for: .milliseconds(150))
        guard !Task.isCancelled else { return }
        let found = try? await engine(session) { try $0.search(query: words) }
        guard !Task.isCancelled else { return }
        results = found ?? []
    }
}

/// Search only on the root screen: it covers the whole database anyway.
private struct SearchModifier: ViewModifier {
    var enabled: Bool
    @Binding var query: String

    func body(content: Content) -> some View {
        if enabled {
            content.searchable(text: $query, prompt: Text("Search entries"))
        } else {
            content
        }
    }
}

struct EntryRow: View {
    let entry: EntryItem
    /// Shown for search results, so the user can tell entries with the same title apart.
    let groupPath: [String]?

    var body: some View {
        NavigationLink(value: Route.entry(entry.id)) {
            Label {
                VStack(alignment: .leading, spacing: 2) {
                    Text(entry.title.isEmpty ? String(localized: "(no title)") : entry.title)
                    if !entry.username.isEmpty {
                        Text(entry.username).font(.caption).foregroundStyle(.secondary)
                    }
                    if let groupPath, !groupPath.isEmpty {
                        Text(groupPath.joined(separator: " › ")).font(.caption2).foregroundStyle(.tertiary)
                    }
                }
            } icon: {
                ItemIconView(icon: entry.icon, kind: .entry)
            }
        }
    }
}
