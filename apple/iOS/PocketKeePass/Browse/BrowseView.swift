import PocketKeePassCore
import SwiftUI

enum Route: Hashable {
    case group(String)
    case entry(String)
}

/// Runs an engine read off the main thread, as every read is: the engine takes a lock, and a
/// save or sync may hold it.
func engine<T: Sendable>(_ session: Session, _ read: @escaping @Sendable (UnlockedDatabase) throws -> T) async throws -> T {
    guard let database = session.database else { throw KeePassError.Locked(message: "") }
    return try await Task.detached(priority: .userInitiated) { try read(database) }.value
}

struct BrowseView: View {
    @Environment(Session.self) private var session
    @State private var path: [Route] = []
    @State private var showingSettings = false

    var body: some View {
        NavigationStack(path: $path) {
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
        .sheet(isPresented: $showingSettings) { SettingsView() }
    }
}

/// The lock button, on every screen of the browser.
struct LockButton: View {
    @Environment(Session.self) private var session

    var body: some View {
        Button {
            session.lock()
        } label: {
            Label("Lock", systemImage: "lock")
        }
    }
}

struct GroupView: View {
    /// nil for the root group.
    let id: String?
    @Environment(Session.self) private var session
    @State private var listing: GroupListing?
    @State private var error: String?
    @State private var query = ""
    @State private var results: [SearchResult]?

    var body: some View {
        List {
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
        }
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
                }
            }
        }
        if !listing.entries.isEmpty {
            Section {
                ForEach(listing.entries, id: \.id) { entry in
                    EntryRow(entry: entry, groupPath: nil)
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
            }
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
