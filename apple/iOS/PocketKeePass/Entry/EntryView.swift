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

private struct FieldRow: View {
    let field: EntryField
    let label: String
    let shownValue: String?
    let toggleShown: () -> Void
    let copy: () -> Void

    var body: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 4) {
                Text(label).font(.caption).foregroundStyle(.secondary)
                value
            }
            Spacer(minLength: 8)
            if field.isProtected && !field.isEmpty {
                Button(action: toggleShown) {
                    Image(systemName: shownValue == nil ? "eye" : "eye.slash")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(shownValue == nil ? Text("Show \(label)") : Text("Hide \(label)"))
            }
            if !field.isEmpty {
                Button(action: copy) {
                    Image(systemName: "doc.on.doc")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(Text("Copy \(label)"))
            }
        }
    }

    @ViewBuilder
    private var value: some View {
        if field.isProtected && !field.isEmpty {
            if let shownValue {
                // Shown passwords in a monospaced font, so 0/O and l/1 can be told apart.
                Text(verbatim: shownValue).fontDesign(.monospaced).textSelection(.enabled)
            } else {
                // A fixed row of dots: it gives nothing away, not even the length.
                Text(verbatim: "••••••••").accessibilityLabel(Text("Hidden"))
            }
        } else if field.key == "URL", let url = URL(string: field.value), ["http", "https"].contains(url.scheme?.lowercased()) {
            Link(field.value, destination: url).lineLimit(2)
        } else {
            Text(verbatim: field.value).textSelection(.enabled)
        }
    }
}

/// The entry's current one-time code, hidden until "show" like a password (it's the second
/// factor), with a countdown that's always visible so the user can tell whether there's time to
/// type it. The code is fetched again as each period starts.
private struct TotpRow: View {
    let entryId: String
    let period: UInt32
    @Binding var shown: Bool
    let copied: () -> Void
    @Environment(Session.self) private var session
    @State private var code: String?
    @State private var codePeriod: UInt64?

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let now = UInt64(context.date.timeIntervalSince1970)
            let start = now - now % UInt64(period)
            let left = Int(UInt64(period) - (now - start))
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("One-time code").font(.caption).foregroundStyle(.secondary)
                    HStack(spacing: 10) {
                        if shown, let code, codePeriod == start {
                            Text(verbatim: code).fontDesign(.monospaced).font(.title3)
                        } else {
                            Text(verbatim: "••••••").accessibilityLabel(Text("Hidden"))
                        }
                        Text("Changes in \(left) seconds")
                            .font(.caption)
                            .foregroundStyle(left <= 5 ? .red : .secondary)
                            .monospacedDigit()
                    }
                }
                Spacer(minLength: 8)
                Button {
                    shown.toggle()
                } label: {
                    Image(systemName: shown ? "eye.slash" : "eye")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(shown ? Text("Hide \(String(localized: "One-time code"))") : Text("Show \(String(localized: "One-time code"))"))
                Button {
                    copy(start)
                } label: {
                    Image(systemName: "doc.on.doc")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(Text("Copy \(String(localized: "One-time code"))"))
            }
            .task(id: shown ? start : nil) {
                guard shown else {
                    code = nil
                    return
                }
                await fetch(start)
            }
        }
    }

    private func fetch(_ start: UInt64) async {
        let entryId = entryId
        guard var bytes = try? await engine(session, { try $0.totpCode(entryId: entryId, unixSeconds: start) }) else { return }
        code = String(decoding: bytes, as: UTF8.self)
        codePeriod = start
        bytes.wipe()
    }

    private func copy(_ start: UInt64) {
        let entryId = entryId
        Task {
            guard var bytes = try? await engine(session, { try $0.totpCode(entryId: entryId, unixSeconds: start) }) else { return }
            Clipboard.shared.copy(bytes)
            bytes.wipe()
            copied()
        }
    }
}
