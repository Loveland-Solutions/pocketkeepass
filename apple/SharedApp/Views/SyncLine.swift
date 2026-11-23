import PocketKeePassCore
import SwiftUI

/// One line at the top of each group's list saying whether the database is in step with its
/// file, which other devices may share: where the file is, and when it was last checked. Tapping
/// it opens `SyncDetailsView`. The same as Android's `SyncLine`.
///
/// iOS names the storage app only for iCloud Drive. For any other file it can't tell a storage
/// app's folder from "On My iPhone", so the line just speaks of "the file".
struct SyncLine: View {
    @Environment(Session.self) private var session
    let onDetails: () -> Void

    var body: some View {
        TimelineView(.periodic(from: .now, by: 10)) { context in
            let sync = session.sync
            Button(action: onDetails) {
                Label {
                    Text(Self.text(sync, now: context.date))
                        .lineLimit(2)
                } icon: {
                    Image(systemName: Self.icon(sync))
                }
                .font(.footnote)
                .foregroundStyle(sync.problem != nil ? AnyShapeStyle(.red) : AnyShapeStyle(.secondary))
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint(Text("Shows how syncing works and when it last happened"))
        }
    }

    private static func icon(_ sync: SyncStatus) -> String {
        if sync.checking { return "arrow.triangle.2.circlepath" }
        if sync.problem != nil { return "exclamationmark.triangle" }
        return sync.storage != nil ? "checkmark.icloud" : "checkmark.circle"
    }

    private static func text(_ sync: SyncStatus, now: Date) -> String {
        if sync.checking {
            if let storage = sync.storage { return String(localized: "Checking \(storage) for changes…") }
            return String(localized: "Checking the file for changes…")
        }
        if let problem = sync.problem { return SaveBar.describe(problem) }
        let checked = relativeTime(sync.checkedAt, now: now)
        if let storage = sync.storage { return String(localized: "Up to date with \(storage) · checked \(checked)") }
        return String(localized: "Up to date with the file · checked \(checked)")
    }
}

/// What syncing does and when it last happened, with "Check now".
struct SyncDetailsView: View {
    @Environment(Session.self) private var session
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let sync = session.sync
        NavigationStack {
            TimelineView(.periodic(from: .now, by: 10)) { context in
                List {
                    Section {
                        ForEach(Self.lines(sync, now: context.date), id: \.self) { Text($0) }
                    }
                    Section {
                        Text(Self.explanation(sync.storage))
                        Text("While PocketKeePass is open, it checks the file every 30 seconds (or pull down on the list to check now) and takes in changes made on other devices. If two devices changed the database at the same time, both sets of changes are kept. When an entry was changed in both places, the newer version is kept and the other goes into its history.")
                    }
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                }
            }
            .navigationTitle(sync.storage.map { String(localized: "Syncing with \($0)") } ?? String(localized: "Syncing"))
            .inlineNavigationTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
                ToolbarItem(placement: .primaryAction) {
                    if sync.checking {
                        ProgressView()
                    } else {
                        Button("Check now") { Task { await session.checkNow() } }
                    }
                }
            }
        }
        .sheetSize()
    }

    private static func lines(_ sync: SyncStatus, now: Date) -> [String] {
        var lines: [String] = []
        if let problem = sync.problem { lines.append(SaveBar.describe(problem)) }
        if let savedAt = sync.savedAt {
            lines.append(String(localized: "Your last change was saved to the file at \(clock(savedAt))."))
        } else {
            lines.append(String(localized: "Nothing has been changed on this iPhone since unlocking."))
        }
        lines.append(String(localized: "Last checked for changes from other devices \(relativeTime(sync.checkedAt, now: now))."))
        if let incoming = sync.incoming {
            let changes = SaveBar.changes(incoming.report)
            lines.append(String(localized: "Last changes from another device came in at \(clock(incoming.at)): \(changes)."))
        }
        return lines
    }

    private static func explanation(_ storage: String?) -> String {
        if let storage {
            return String(localized: "Every change you make is saved into the file straight away, and \(storage) uploads it from there. PocketKeePass can’t see when the upload has finished. While this iPhone is offline, your changes wait on it and go into the file once it’s back online.")
        }
        return String(localized: "Every change you make is saved into the file straight away. If the file is in a storage app (such as Dropbox or OneDrive), that app uploads it from there: iOS doesn’t tell PocketKeePass which app holds the file, or when the upload has finished. While this iPhone is offline, your changes wait on it and go into the file once it’s back online.")
    }

    private static func clock(_ date: Date) -> String {
        date.formatted(date: .omitted, time: .shortened)
    }
}

/// "just now" within the last minute, then "5 minutes ago", and so on.
private func relativeTime(_ at: Date?, now: Date) -> String {
    guard let at, now.timeIntervalSince(at) >= 60 else { return String(localized: "just now") }
    let formatter = RelativeDateTimeFormatter()
    formatter.unitsStyle = .full
    return formatter.localizedString(for: at, relativeTo: now)
}
