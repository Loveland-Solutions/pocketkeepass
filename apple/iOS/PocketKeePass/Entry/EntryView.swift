import PocketKeePassCore
import SwiftUI

/// One entry's fields. Protected values aren't in `EntryDetails`; each one is fetched from the
/// engine when the user taps show or copy. A shown value has to become a `String` for SwiftUI to
/// draw it, so it's kept only while this screen is open.
struct EntryView: View {
    let id: String
    @Environment(Session.self) private var session
    @Environment(Editing.self) private var editing
    @State private var entry: EntryDetails?
    @State private var error: String?
    /// Protected values the user chose to see, by field key.
    @State private var shown: [String: String] = [:]
    @State private var totpShown = false
    @State private var copiedMessage: String?

    var body: some View {
        Group {
            if let entry {
                form(entry)
            } else if let error {
                ContentUnavailableView(error, systemImage: "exclamationmark.triangle")
            } else {
                ProgressView()
            }
        }
        .navigationTitle(entry.map { $0.title.isEmpty ? String(localized: "(no title)") : $0.title } ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) { LockButton() }
            if let entry {
                ToolbarItem(placement: .primaryAction) {
                    Button("Edit") { editing.editEntry(session, entry) }
                }
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        let item = EditItem.entry(id: entry.id, name: entry.title, inRecycleBin: entry.isInRecycleBin)
                        Button("Duplicate", systemImage: "plus.square.on.square") {
                            editing.duplicate(session, id: entry.id, open: true)
                        }
                        Button("Move", systemImage: "folder") { editing.move(session, item) }
                        Button("Delete", systemImage: "trash", role: .destructive) { editing.delete(session, item) }
                    } label: {
                        Label("More options", systemImage: "ellipsis.circle")
                    }
                }
            }
        }
        .overlay(alignment: .bottom) { CopiedToast(message: copiedMessage) }
        .task(id: session.revision) {
            // A value shown before an edit or a sync may no longer be the entry's.
            shown = [:]
            await load()
        }
        .onDisappear {
            shown = [:]
            totpShown = false
        }
    }

    private func form(_ entry: EntryDetails) -> some View {
        let hidden = Set(hiddenFields(entry))
        let fields = entry.fields.filter { !hidden.contains($0.key) && $0.key != "Notes" }
        let notes = entry.fields.first { $0.key == "Notes" }

        return Form {
            if entry.isInRecycleBin {
                Section {
                    Label("This entry is in the recycle bin.", systemImage: "trash")
                }
            }
            Section {
                ForEach(fields, id: \.key) { field in
                    if !field.isEmpty || field.key == "UserName" || field.key == "Password" || field.key == "URL" {
                        FieldRow(
                            field: field,
                            label: label(for: field.key),
                            shownValue: shown[field.key],
                            toggleShown: { toggle(field) },
                            copy: { copy(field) }
                        )
                    }
                    if field.key == "Password" {
                        totpRow(entry)
                    }
                }
            } footer: {
                if !entry.groupPath.isEmpty {
                    Text(entry.groupPath.joined(separator: " › "))
                }
            }

            if let passkey = entry.passkey {
                Section {
                    Label {
                        VStack(alignment: .leading) {
                            Text("For \(passkey.rpId)")
                            if let reason = passkey.unusableReason {
                                Text(reason).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    } icon: {
                        Image(systemName: "person.badge.key.fill")
                    }
                } header: {
                    Text("Passkey")
                }
            }

            if let notes, !notes.isEmpty {
                Section("Notes") {
                    if notes.isProtected {
                        FieldRow(field: notes, label: String(localized: "Notes"), shownValue: shown["Notes"],
                                 toggleShown: { toggle(notes) }, copy: { copy(notes) })
                    } else {
                        Text(notes.value).textSelection(.enabled)
                    }
                }
            }

            if !entry.tags.isEmpty {
                Section("Tags") {
                    Text(entry.tags.joined(separator: ", "))
                }
            }
        }
    }

    @ViewBuilder
    private func totpRow(_ entry: EntryDetails) -> some View {
        switch entry.totp {
        case .none:
            EmptyView()
        case .invalid(let reason):
            VStack(alignment: .leading, spacing: 4) {
                Text("One-time code").font(.caption).foregroundStyle(.secondary)
                Text(reason).font(.callout)
            }
        case .ready(let period, _, _):
            TotpRow(entryId: entry.id, period: period, shown: $totpShown, copied: { showCopied(String(localized: "One-time code")) })
        }
    }

    /// Fields the screen shows another way: the TOTP settings (as the code) and the passkey (as
    /// one row). They're still in `fields`, and an editor keeps them.
    private func hiddenFields(_ entry: EntryDetails) -> [String] {
        var hidden: [String] = []
        if case .ready(_, _, let fields) = entry.totp { hidden += fields }
        if let passkey = entry.passkey { hidden += passkey.fields }
        return hidden
    }

    private func label(for key: String) -> String {
        switch key {
        case "UserName": String(localized: "Username")
        case "Password": String(localized: "Password")
        case "URL": String(localized: "URL")
        case "Notes": String(localized: "Notes")
        default: key
        }
    }

    private func load() async {
        let id = id
        do {
            entry = try await engine(session) { try $0.entry(id: id) }
            error = nil
        } catch {
            if session.isUnlocked { self.error = error.userMessage }
        }
    }

    private func toggle(_ field: EntryField) {
        if shown[field.key] != nil {
            shown[field.key] = nil
            return
        }
        let id = id, key = field.key
        Task {
            guard var bytes = try? await engine(session, { try $0.revealField(entryId: id, key: key) }) else { return }
            shown[key] = String(decoding: bytes, as: UTF8.self)
            bytes.wipe()
        }
    }

    private func copy(_ field: EntryField) {
        let label = label(for: field.key)
        if !field.isProtected {
            var bytes = Data(field.value.utf8)
            Clipboard.shared.copy(bytes)
            bytes.wipe()
            showCopied(label)
            return
        }
        let id = id, key = field.key
        Task {
            guard var bytes = try? await engine(session, { try $0.revealField(entryId: id, key: key) }) else { return }
            Clipboard.shared.copy(bytes)
            bytes.wipe()
            showCopied(label)
        }
    }

    private func showCopied(_ label: String) {
        let message = String(localized: "Copied \(label). It clears in \(durationText(seconds: Preferences.clipboardSeconds)).")
        withAnimation { copiedMessage = message }
        AccessibilityNotification.Announcement(message).post()
        Task {
            try? await Task.sleep(for: .seconds(3))
            if copiedMessage == message { withAnimation { copiedMessage = nil } }
        }
    }
}
