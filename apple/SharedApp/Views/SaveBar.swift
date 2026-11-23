import PocketKeePassCore
import SwiftUI
import UniformTypeIdentifiers

/// The strip at the bottom of the browser: "Saving…", then "Saved" for 2 seconds; a save held
/// back while offline; a save that failed, with what to do about it; changes that came in from
/// another device. (Why the file couldn't be checked is on the `SyncLine`.)
struct SaveBar: View {
    @Environment(Session.self) private var session
    @State private var justSaved = false
    @State private var incoming: String?
    @State private var showingProblem = false
    @State private var copy: KdbxFile?
    @State private var exporting = false
    @State private var copyResult: String?

    var body: some View {
        Group {
            if let row {
                row
                    .font(.footnote)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(.bar)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.default, value: session.saveState)
        .animation(.default, value: justSaved)
        .onChange(of: session.saveState) { old, new in
            justSaved = false
            guard old == .saving, new == .saved else { return }
            justSaved = true
            AccessibilityNotification.Announcement(String(localized: "Saved")).post()
            Task {
                try? await Task.sleep(for: .seconds(2))
                if session.saveState == .saved { justSaved = false }
            }
        }
        .onChange(of: session.lastIncoming) { _, new in
            guard let new else { incoming = nil; return }
            let text = Self.describe(new.report, storage: session.sync.storage)
            incoming = text
            AccessibilityNotification.Announcement(text).post()
            Task {
                try? await Task.sleep(for: .seconds(4))
                if incoming == text { incoming = nil }
            }
        }
        .alert(problemTitle, isPresented: $showingProblem) {
            problemActions
        } message: {
            Text(problemMessage)
        }
        .fileExporter(isPresented: $exporting, document: copy, contentType: .kdbx, defaultFilename: session.fileName) { result in
            copy = nil
            switch result {
            case .success: copyResult = String(localized: "Saved a copy. The database still opens from its old file.")
            case .failure(let error as CocoaError) where error.code == .userCancelled: break
            case .failure: copyResult = String(localized: "The copy couldn’t be saved.")
            }
        }
        .alert(copyResult ?? "", isPresented: Binding(get: { copyResult != nil }, set: { if !$0 { copyResult = nil } })) {}
    }

    private var row: AnyView? {
        switch session.saveState {
        case .saving:
            return AnyView(HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text("Saving…")
            })
        case .waiting:
            return AnyView(Label("You’re offline. Your changes are kept on this iPhone and go into the file once you’re back online.", systemImage: "wifi.slash"))
        case .unsaved:
            return AnyView(HStack {
                Label("Your changes aren’t saved to the file.", systemImage: "exclamationmark.triangle.fill")
                    .foregroundStyle(.red)
                Spacer()
                Button("What now?") { showingProblem = true }
            })
        case .saved:
            if justSaved {
                return AnyView(Label("Saved", systemImage: "checkmark"))
            }
            if let incoming {
                return AnyView(Label(incoming, systemImage: "arrow.down.circle"))
            }
            return nil
        }
    }

    // MARK: A save that failed

    private var problem: SaveProblem? {
        if case .unsaved(let problem) = session.saveState { return problem }
        return nil
    }

    private var problemTitle: String {
        problem == .conflict ? String(localized: "The file changed elsewhere") : String(localized: "Changes not saved")
    }

    private var problemMessage: String {
        switch problem {
        case .conflict:
            return String(localized: "Another app or device changed the database file, and PocketKeePass can’t combine those changes with yours: the file may have a new master password, hold another database, or be damaged. Saving now would replace it with yours.\n\nYour changes stay here until the database locks.")
        case .cantWrite:
            return String(localized: "PocketKeePass can’t write to the database file. It may have been moved or deleted, or the storage app may only allow reading it.\n\nYour changes stay here until the database locks. Save a copy to keep them.")
        case .writeCheckFailed:
            return String(localized: "The file didn’t read back correctly after saving, so the storage app may not have stored it properly. Try again, or save a copy to be safe.\n\nYour changes stay here until the database locks.")
        case .engine(let message):
            return String(localized: "\(message)\n\nYour changes stay here until the database locks.")
        case nil:
            return ""
        }
    }

    @ViewBuilder
    private var problemActions: some View {
        if problem == .conflict {
            Button("Replace it with mine", role: .destructive) { session.save(overwrite: true) }
            Button("Discard mine and reload", role: .destructive) { Task { _ = await session.reload() } }
        } else {
            Button("Try again") { session.save() }
        }
        Button("Save a copy…") { saveCopy() }
        Button("Not now", role: .cancel) {}
    }

    private func saveCopy() {
        Task {
            do {
                copy = KdbxFile(data: try await session.copyBytes())
                exporting = true
            } catch {
                copyResult = error.userMessage
            }
        }
    }

    // MARK: Wording

    static func describe(_ problem: SyncProblem) -> String {
        switch problem {
        case .cantRead: String(localized: "Can’t read the file right now. Changes from other devices will come in once it can.")
        case .keyChanged: String(localized: "The master password was changed on another device. Lock, then unlock with the new one.")
        case .otherDatabase: String(localized: "The file now holds a different database. Lock, then unlock it again.")
        case .settingsChanged: String(localized: "The database’s security settings were changed on another device. Lock, then unlock it again to take in the changes.")
        }
    }

    /// "Updated from iCloud Drive: 1 new entry, 2 entries changed".
    static func describe(_ report: SyncReport, storage: String?) -> String {
        let changes = changes(report)
        if let storage { return String(localized: "Updated from \(storage): \(changes)") }
        return String(localized: "Updated from another device: \(changes)")
    }

    /// "1 new entry, 2 entries changed": only the kinds of change there were.
    static func changes(_ report: SyncReport) -> String {
        var parts: [String] = []
        if report.entriesAdded > 0 { parts.append(String(localized: "\(Int(report.entriesAdded)) new entries")) }
        if report.entriesChanged > 0 { parts.append(String(localized: "\(Int(report.entriesChanged)) entries changed")) }
        if report.entriesRemoved > 0 { parts.append(String(localized: "\(Int(report.entriesRemoved)) entries removed")) }
        if report.groupsChanged > 0 { parts.append(String(localized: "\(Int(report.groupsChanged)) groups changed")) }
        return ListFormatter.localizedString(byJoining: parts)
    }
}
